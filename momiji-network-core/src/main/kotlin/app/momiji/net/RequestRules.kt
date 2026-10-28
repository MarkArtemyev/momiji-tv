package app.momiji.net

import java.net.URI
import java.net.IDN

class PublicSuffixes(text: String) {
    private val exact=HashSet<String>(); private val wild=HashSet<String>(); private val exceptions=HashSet<String>()
    init { text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }.forEach {
        val target=when { it.startsWith("!")->exceptions; it.startsWith("*.")->wild; else->exact }
        runCatching { IDN.toASCII(it.removePrefix("!").removePrefix("*.")) }.getOrNull()?.let(target::add)
    } }
    fun site(host: String): String {
        val normalized=DomainTable.normalizeHost(host); val labels=normalized.split('.')
        if(labels.size<2 || normalized.contains(':') || labels.all { it.toIntOrNull() in 0..255 }) return normalized
        var suffix=1
        for(i in labels.indices) {
            val candidate=labels.drop(i).joinToString(".")
            if(candidate in exceptions) { suffix=labels.size-i-1; break }
            if(candidate in exact) suffix=maxOf(suffix,labels.size-i)
            if(i>0 && candidate in wild) suffix=maxOf(suffix,labels.size-i+1)
        }
        return labels.takeLast(minOf(labels.size,suffix+1)).joinToString(".")
    }
    fun isSuffix(name: String)=name in exact || name.substringAfter('.',"") in wild
}

/** POPUP is a new window or tab; only rules that name `$popup` (or `$all`) apply to it. */
enum class ResourceType { DOCUMENT,SUBDOCUMENT,SCRIPT,STYLESHEET,IMAGE,FONT,MEDIA,XMLHTTPREQUEST,WEBSOCKET,PING,OTHER,POPUP }

/** [redirect] names a harmless stand-in resource (noopjs, google-ima3, noopvast-4.0...) served instead of an error. */
data class RuleDecision(val blocked: Boolean,val priority: Int,val redirect: String?=null) {
    companion object {
        /** Higher priority wins; on a tie an exception beats a block, and a redirecting block beats a plain one. */
        fun stronger(a: RuleDecision?,b: RuleDecision?): RuleDecision? = when {
            a==null->b; b==null->a
            a.priority!=b.priority->if(a.priority>b.priority) a else b
            !a.blocked->a; !b.blocked->b
            else->if(a.redirect!=null) a else b
        }
    }
}
data class FilterRequest(val url: String,val documentUrl: String="",val type: ResourceType=ResourceType.OTHER) {
    val host=runCatching { DomainTable.normalizeHost(URI(url).host.orEmpty()) }.getOrDefault("")
    val documentHost=runCatching { DomainTable.normalizeHost(URI(documentUrl).host.orEmpty()) }.getOrDefault("")
}

/** Page-level exceptions from `@@...$document`, `$elemhide`, `$generichide`... rules. */
object PageFlags {
    const val DOCUMENT=1; const val ELEMHIDE=2; const val GENERICHIDE=4; const val SPECIFICHIDE=8
    const val JSINJECT=16; const val CONTENT=32; const val URLBLOCK=64; const val GENERICBLOCK=128
    const val ALL=255
}

/** Response rewriting kinds: AdGuard `$replace`, `$hls` and `$jsonprune`. */
enum class ContentKind { REPLACE,HLS,JSONPRUNE }

/**
 * Supported ABP/AdGuard network syntax. Unsupported modifiers and regex patterns are skipped, never broadened.
 * Besides blocking it answers which stand-in to serve ($redirect, $redirect-rule), which page-wide exceptions
 * apply to a document, and which response rewrites ($replace, $hls, $jsonprune) a request needs.
 */
class RequestRules private constructor(private val suffixes: PublicSuffixes) {
    private class Rule(val pattern: String,val host: String?,val allow: Boolean,val important: Boolean,val caseSensitive: Boolean,
        val positive: Int,val negative: Int,val thirdParty: Boolean?,val domains: List<String>,val excluded: List<String>,
        val pageFlags: Int,val redirect: String?,val redirectRule: Boolean,val denyallow: List<String>,
        val content: ContentKind?,val contentValue: String) {
        val priority get()=if(important) { if(allow) 4 else 3 } else if(allow) 2 else 1
        val generic get()=domains.isEmpty()
    }

    /** Host, trigram and fallback buckets so a request only meets rules that can match it. */
    private class RuleIndex {
        val hosts=HashMap<String,MutableList<Rule>>(); val keywords=HashMap<Int,MutableList<Rule>>(); val fallback=ArrayList<Rule>()
        fun bucket(rule: Rule): Int {
            if(rule.host!=null) return -1
            val literal=TOKEN.findAll(rule.pattern).map { it.value }.maxByOrNull { it.length } ?: return -2
            return trigram(literal.lowercase(),0)
        }
        fun add(rule: Rule,bucket: Int=bucket(rule)) {
            when { rule.host!=null->hosts.getOrPut(rule.host) { ArrayList() }.add(rule)
                bucket>=0 || bucket<-2->keywords.getOrPut(bucket) { ArrayList() }.add(rule)
                else->fallback.add(rule) }
        }
        inline fun visit(request: FilterRequest,lower: String,action: (Rule)->Unit) {
            var host=request.host
            while(host.isNotEmpty()) { hosts[host]?.forEach(action); val at=host.indexOf('.'); if(at<0) break; host=host.substring(at+1) }
            if(keywords.isNotEmpty()) { val seen=HashSet<Int>(); for(i in 0..lower.length-3) { val key=trigram(lower,i); if(seen.add(key)) keywords[key]?.forEach(action) } }
            fallback.forEach(action)
        }
        fun all(): List<Pair<Rule,Int>> = hosts.values.flatten().map { it to -1 }+keywords.entries.flatMap { (key,list)->list.map { it to key } }+fallback.map { it to -2 }
        val size get()=hosts.values.sumOf { it.size }+keywords.values.sumOf { it.size }+fallback.size
    }

    /** Host hash → resource-type mask for the ~260k `host$image`-style rules that need no object each. */
    private class TypedHosts {
        private val pending=HashMap<Long,Int>()
        var keys=LongArray(0); private set
        var masks=IntArray(0); private set
        fun add(hash: Long,mask: Int) { pending.merge(hash,mask,Int::or) }
        fun seal() { val sorted=pending.entries.sortedBy { it.key }; keys=LongArray(sorted.size) { sorted[it].key }; masks=IntArray(sorted.size) { sorted[it].value }; pending.clear() }
        fun load(k: LongArray,m: IntArray) { keys=k; masks=m }
        fun mask(hash: Long): Int { val at=keys.binarySearch(hash); return if(at<0) 0 else masks[at] }
    }
    private val typedAny=TypedHosts()
    private val typedThirdParty=TypedHosts()
    private val main=RuleIndex()
    private val redirectRules=RuleIndex()
    private val pages=RuleIndex()
    private val contents=RuleIndex()
    private var domainBlocks=LongArray(0)
    private var domainAllows=LongArray(0)
    var count=0; private set
    var skipped=0; private set
    val contentCount get()=contents.size
    val redirectCount by lazy { main.all().count { it.first.redirect!=null }+redirectRules.size }
    val popupCount by lazy { main.all().count { it.first.positive and POPUP_BIT!=0 } }

    constructor(lines: List<String>,suffixes: PublicSuffixes): this(suffixes) {
        val usable=FilterSyntax.applicable(lines.asSequence()).filterNot(FilterSyntax::isCosmetic).toList()
        val disabled=usable.filter { it.contains("badfilter") }.map { canonical(it,true) }.toHashSet()
        val block=ArrayList<Long>(); val allow=ArrayList<Long>(); var objects=0
        for(line in usable) {
            if(line.contains("badfilter") || (disabled.isNotEmpty() && canonical(line,false) in disabled)) continue
            val rule=parse(line)
            if(rule==null) { skipped++; continue }
            val plainHost=rule.host!=null && rule.pattern=="^" && rule.negative==0 && rule.domains.isEmpty() && rule.excluded.isEmpty() &&
                !rule.important && rule.redirect==null && rule.denyallow.isEmpty() && rule.content==null && rule.pageFlags==0
            when {
                plainHost && rule.positive==0 && rule.thirdParty==null->(if(rule.allow) allow else block).add(DomainTable.hash(rule.host!!))
                plainHost && !rule.allow && rule.positive!=0 && rule.positive and (POPUP_BIT or DOCUMENT_BIT)==0 && rule.thirdParty!=false->
                    (if(rule.thirdParty==true) typedThirdParty else typedAny).add(DomainTable.hash(rule.host!!),rule.positive)
                // Objects cost memory on a TV; exceptions are always kept so a cap never turns into over-blocking.
                objects>=MAX_OBJECTS && !rule.allow->{ skipped++; continue }
                rule.content!=null->{ contents.add(rule); objects++ }
                rule.pageFlags!=0->{ pages.add(rule); objects++ }
                rule.redirectRule->{ redirectRules.add(rule); objects++ }
                else->{ val bucket=main.bucket(rule)
                    if(bucket==-2 && !rule.allow && main.fallback.size>=256) { skipped++; continue } else { main.add(rule,bucket); objects++ } }
            }
            count++
        }
        typedAny.seal(); typedThirdParty.seal()
        domainBlocks=block.distinct().sorted().toLongArray(); domainAllows=allow.distinct().sorted().toLongArray()
    }

    constructor(input: java.io.InputStream,suffixes: PublicSuffixes): this(suffixes) {
        java.io.DataInputStream(input.buffered(65536)).use { data ->
            require(data.readInt()==MAGIC)
            fun longs(): LongArray { val n=data.readInt(); require(n in 0..600000); return LongArray(n) { data.readLong() } }
            domainBlocks=longs(); domainAllows=longs()
            for(table in listOf(typedAny,typedThirdParty)) { val keys=longs(); table.load(keys,IntArray(keys.size) { data.readInt() }) }
            count=data.readInt(); skipped=data.readInt()
            require(count in 0..1000000 && skipped>=0)
            fun list()=data.readUTF().split('|').filter(String::isNotEmpty)
            for(index in listOf(main,redirectRules,pages,contents)) {
                val n=data.readInt(); require(n in 0..250000)
                repeat(n) {
                    val bucket=data.readInt()
                    val host=data.readUTF().ifEmpty { null }
                    val rule=Rule(data.readUTF(),host,data.readBoolean(),data.readBoolean(),data.readBoolean(),data.readInt(),data.readInt(),
                        when(data.readByte().toInt()) { 0->false; 1->true; else->null },list(),list(),data.readInt(),
                        data.readUTF().ifEmpty { null },data.readBoolean(),list(),data.readByte().toInt().let { if(it<0) null else ContentKind.values()[it] },
                        String(ByteArray(data.readInt().also { size -> require(size in 0..65536) }).also(data::readFully),Charsets.UTF_8))
                    index.add(rule,bucket)
                }
            }
            require(data.read()==-1)
        }
    }

    fun write(output: java.io.OutputStream) {
        java.io.DataOutputStream(output.buffered(65536)).use { data ->
            data.writeInt(MAGIC)
            for(array in listOf(domainBlocks,domainAllows)) { data.writeInt(array.size); array.forEach(data::writeLong) }
            for(table in listOf(typedAny,typedThirdParty)) { data.writeInt(table.keys.size); table.keys.forEach(data::writeLong); table.masks.forEach(data::writeInt) }
            data.writeInt(count); data.writeInt(skipped)
            for(index in listOf(main,redirectRules,pages,contents)) {
                val all=index.all(); data.writeInt(all.size)
                for((rule,bucket) in all) {
                    data.writeInt(bucket)
                    data.writeUTF(rule.host.orEmpty()); data.writeUTF(rule.pattern); data.writeBoolean(rule.allow); data.writeBoolean(rule.important); data.writeBoolean(rule.caseSensitive)
                    data.writeInt(rule.positive); data.writeInt(rule.negative); data.writeByte(when(rule.thirdParty) { false->0; true->1; else->2 })
                    data.writeUTF(rule.domains.joinToString("|")); data.writeUTF(rule.excluded.joinToString("|")); data.writeInt(rule.pageFlags)
                    data.writeUTF(rule.redirect.orEmpty()); data.writeBoolean(rule.redirectRule); data.writeUTF(rule.denyallow.joinToString("|"))
                    data.writeByte(rule.content?.ordinal ?: -1)
                    val value=rule.contentValue.toByteArray(Charsets.UTF_8); data.writeInt(value.size); data.write(value)
                }
            }
        }
    }

    private fun canonical(line: String,removeBad: Boolean): String {
        val at=FilterSyntax.optionsStart(line); if(at<0) return line
        val opts=FilterSyntax.splitUnescaped(line.substring(at+1),',').filterNot { removeBad && it=="badfilter" }.sorted()
        return line.substring(0,at)+(if(opts.isEmpty()) "" else "$"+opts.joinToString(","))
    }

    private fun parse(raw: String): Rule? {
        val allow=raw.startsWith("@@"); val text=raw.removePrefix("@@")
        val at=FilterSyntax.optionsStart(text); val pattern=if(at>=0) text.substring(0,at) else text
        if(pattern.startsWith('/') && pattern.endsWith('/') && pattern.length>1) return null
        if(pattern.length>2048 || pattern.length<2 && !(pattern.isEmpty() || pattern=="*") ) return null
        var positive=0; var negative=0; var important=false; var matchCase=false; var third: Boolean?=null
        var domains=emptyList<String>(); var excluded=emptyList<String>(); var pageFlags=0
        var redirect: String?=null; var redirectRule=false; var denyallow=emptyList<String>()
        var content: ContentKind?=null; var contentValue=""
        for(option in if(at>=0) FilterSyntax.splitUnescaped(text.substring(at+1),',') else emptyList()) {
            val inverse=option.startsWith('~'); val name=option.removePrefix("~").substringBefore('=').lowercase()
            val value=option.substringAfter('=',"")
            val resource=when(name) {
                "document","doc"->ResourceType.DOCUMENT; "subdocument","frame"->ResourceType.SUBDOCUMENT; "script"->ResourceType.SCRIPT
                "stylesheet","css"->ResourceType.STYLESHEET; "image"->ResourceType.IMAGE; "font"->ResourceType.FONT; "media"->ResourceType.MEDIA
                "xmlhttprequest","xhr"->ResourceType.XMLHTTPREQUEST; "websocket"->ResourceType.WEBSOCKET; "ping","beacon"->ResourceType.PING
                "other","object"->ResourceType.OTHER; "popup"->ResourceType.POPUP; else->null
            }
            if(resource!=null && !option.contains('=')) {
                if(inverse) negative=negative or (1 shl resource.ordinal) else positive=positive or (1 shl resource.ordinal)
                if(resource==ResourceType.DOCUMENT && !inverse && allow) pageFlags=pageFlags or PageFlags.ALL
                continue
            }
            if(inverse && name !in setOf("third-party","3p","first-party","1p")) return null
            when(name) {
                "all"->positive=positive or ALL_TYPES
                "important"->important=true
                "match-case"->matchCase=true
                "third-party","3p"->third=!inverse
                "first-party","1p"->third=inverse
                "domain","from"-> { val parsed=FilterSyntax.domains(value.split('|')) ?: return null; domains=parsed.first; excluded=parsed.second }
                "denyallow"-> { val parsed=FilterSyntax.domains(value.split('|')) ?: return null; if(parsed.second.isNotEmpty()) return null; denyallow=parsed.first }
                // `@@...$redirect` only switches stand-ins off; read as a plain exception it would unblock.
                "redirect"-> { if(value.isEmpty() || allow) return null; redirect=value }
                "redirect-rule"-> { if(value.isEmpty() || allow) return null; redirect=value; redirectRule=true }
                "empty"->redirect="nooptext"
                "mp4"->{ redirect="noopmp4-1s"; positive=positive or (1 shl ResourceType.MEDIA.ordinal) }
                "elemhide","ehide"->pageFlags=pageFlags or PageFlags.ELEMHIDE
                "generichide","ghide"->pageFlags=pageFlags or PageFlags.GENERICHIDE
                "specifichide","shide"->pageFlags=pageFlags or PageFlags.SPECIFICHIDE
                "jsinject"->pageFlags=pageFlags or PageFlags.JSINJECT
                "content"->pageFlags=pageFlags or PageFlags.CONTENT
                "urlblock"->pageFlags=pageFlags or PageFlags.URLBLOCK
                "genericblock"->pageFlags=pageFlags or PageFlags.GENERICBLOCK
                "replace"-> { content=ContentKind.REPLACE; contentValue=FilterSyntax.unescape(value,",$") }
                "hls"-> { content=ContentKind.HLS; contentValue=FilterSyntax.unescape(value,",$") }
                "jsonprune"-> { content=ContentKind.JSONPRUNE; contentValue=FilterSyntax.unescape(value,",$") }
                else->return null
            }
        }
        // Page-wide switches, stand-ins and rewrites each mean something only in one direction.
        if(pageFlags!=0 && !allow) return null
        if(pageFlags!=0 && pageFlags!=PageFlags.ALL && (positive!=0 || redirect!=null || content!=null)) {
            positive=positive and (1 shl ResourceType.DOCUMENT.ordinal).inv()
            if(positive!=0 || redirect!=null || content!=null) return null
        }
        if(content!=null) {
            if(redirect!=null || pageFlags!=0) return null
            if(!allow && contentValue.isEmpty()) return null
            if(content==ContentKind.REPLACE && contentValue.isNotEmpty() && replaceParts(contentValue)==null) return null
        }
        // A pattern that matches every URL is only acceptable when domains narrow it down.
        if((pattern.isEmpty() || pattern=="*") && domains.isEmpty()) return null
        var host: String?=null; var target=pattern.ifEmpty { "*" }
        val bareHost=pattern.removeSuffix("^")
        if(!pattern.startsWith("|") && positive!=0 && negative==0 && domains.isEmpty() && redirect==null && content==null && pageFlags==0 &&
            DOMAIN.matches(bareHost) && '.' in bareHost && suffixes.isSuffix(bareHost.substringAfterLast('.'))) {
            host=DomainTable.normalizeHost(bareHost); target="^"
        } else if(pattern.startsWith("||")) {
            val remaining=pattern.substring(2); val end=remaining.indexOfFirst { it in "^/|:?" }.let { if(it<0) remaining.length else it }
            val candidate=remaining.substring(0,end)
            if(!DOMAIN.matches(candidate) || candidate.endsWith(".*")) return null
            host=DomainTable.normalizeHost(candidate); target=remaining.substring(end).ifEmpty { "*" }
            if(target.startsWith(':')) return null
        }
        return Rule(if(matchCase) target else target.lowercase(),host,allow,important,matchCase,positive,negative,third,domains,excluded,
            pageFlags,redirect,redirectRule,denyallow,content,contentValue)
    }

    fun blocks(request: FilterRequest): Boolean = decision(request)?.blocked==true

    fun decision(request: FilterRequest): RuleDecision? {
        if(request.host.isEmpty() || request.url.length>16384) return null
        // A document is judged by its own exceptions, everything else by the page that loads it.
        val page=if(request.type==ResourceType.DOCUMENT) request.url else request.documentUrl
        val flags=if(page.isNotEmpty()) pageFlags(page) else 0
        if(flags and (PageFlags.DOCUMENT or PageFlags.URLBLOCK)!=0) return RuleDecision(false,4)
        val genericOff=flags and PageFlags.GENERICBLOCK!=0
        var winner=0; var blocked=false; var redirect: String?=null
        val context=Context(request); val bit=1 shl request.type.ordinal
        if(request.type!=ResourceType.POPUP && !genericOff) {
            var host=request.host
            while(host.isNotEmpty()) {
                val hash=DomainTable.hash(host)
                if(domainBlocks.binarySearch(hash)>=0 && winner<1) { winner=1; blocked=true }
                if(winner<1 && (typedAny.mask(hash) and bit!=0 || context.party==true && typedThirdParty.mask(hash) and bit!=0)) { winner=1; blocked=true }
                if(domainAllows.binarySearch(hash)>=0 && winner<2) { winner=2; blocked=false; redirect=null }
                val at=host.indexOf('.'); if(at<0) break; host=host.substring(at+1)
            }
        }
        main.visit(request,context.lower) { rule ->
            if(rule.priority<winner || genericOff && rule.generic && !rule.allow) return@visit
            // Same priority: only a redirecting block may replace a plain block.
            if(rule.priority==winner && (rule.allow || !blocked || redirect!=null || rule.redirect==null)) return@visit
            if(matches(rule,context)) { winner=rule.priority; blocked=!rule.allow; redirect=if(rule.allow) null else rule.redirect }
        }
        return if(winner==0) null else RuleDecision(blocked,winner,redirect)
    }

    /** The `$redirect-rule` stand-in for a request that some rule, in any layer, already blocks. */
    fun redirectRule(request: FilterRequest): String? {
        if(request.host.isEmpty() || redirectRules.size==0) return null
        val context=Context(request); var found: String?=null
        redirectRules.visit(request,context.lower) { rule -> if(found==null && matches(rule,context)) found=rule.redirect }
        return found
    }

    /** OR of [PageFlags] that exception rules grant to the document at [url]. */
    fun pageFlags(url: String): Int {
        if(pages.size==0) return 0
        val request=FilterRequest(url,url,ResourceType.DOCUMENT)
        if(request.host.isEmpty()) return 0
        val context=Context(request,party=false); var flags=0
        pages.visit(request,context.lower) { rule -> if(rule.pageFlags and flags.inv()!=0 && matches(rule,context,ignoreType=true)) flags=flags or rule.pageFlags }
        return flags
    }

    /** Values of the rewrite rules of [kind] for this request, after `@@` exceptions. Empty when none apply. */
    fun content(request: FilterRequest,kind: ContentKind): List<String> {
        if(request.host.isEmpty() || contents.size==0) return emptyList()
        if(request.documentHost.isNotEmpty() && pageFlags(request.documentUrl) and (PageFlags.DOCUMENT or PageFlags.CONTENT)!=0) return emptyList()
        val context=Context(request); val values=LinkedHashSet<String>(); val disabled=HashSet<String>(); var disableAll=false
        contents.visit(request,context.lower) { rule ->
            if(rule.content!=kind || !matches(rule,context)) return@visit
            if(rule.allow) { if(rule.contentValue.isEmpty()) disableAll=true else disabled.add(rule.contentValue) } else values.add(rule.contentValue)
        }
        return if(disableAll) emptyList() else values.filter { it !in disabled }
    }

    /** True when the page's domain has any `$jsonprune` rule, so the page should report JSON responses. */
    fun hasContentFor(documentUrl: String,kind: ContentKind): Boolean {
        val host=FilterRequest(documentUrl).host; if(host.isEmpty()) return false
        return contents.all().any { (rule,_)-> rule.content==kind && !rule.allow &&
            (rule.domains.any { FilterSyntax.domainMatches(host,it,suffixes) } || rule.domains.isEmpty() && rule.host?.let { host==it || host.endsWith(".$it") }==true) }
    }

    private inner class Context(val request: FilterRequest,party: Boolean?=null) {
        val lower=request.url.lowercase()
        val party: Boolean?=party ?: if(request.documentHost.isEmpty()) null else suffixes.site(request.host)!=suffixes.site(request.documentHost)
        val tail: String? by lazy { runCatching { URI(request.url) }.getOrNull()?.let { (it.rawPath?.ifEmpty { "/" } ?: "/")+(it.rawQuery?.let { q-> "?$q" } ?: "") } }
    }

    private fun matches(rule: Rule,context: Context,ignoreType: Boolean=false): Boolean {
        val request=context.request
        if(!ignoreType) {
            val bit=1 shl request.type.ordinal
            if(request.type==ResourceType.POPUP && rule.positive and POPUP_BIT==0) return false
            if(rule.positive!=0 && rule.positive and bit==0 || rule.negative and bit!=0) return false
        }
        if(rule.thirdParty!=null && rule.thirdParty!=context.party) return false
        if(rule.domains.isNotEmpty() || rule.excluded.isNotEmpty()) {
            // `$domain` names the page for subresources and popups, but the target itself for a document.
            val host=if(request.type==ResourceType.DOCUMENT) request.host else request.documentHost
            if(rule.domains.isNotEmpty() && rule.domains.none { FilterSyntax.domainMatches(host,it,suffixes) }) return false
            if(rule.excluded.any { FilterSyntax.domainMatches(host,it,suffixes) }) return false
        }
        if(rule.denyallow.any { FilterSyntax.domainMatches(request.host,it,suffixes) }) return false
        val value=if(rule.host!=null) context.tail ?: return false else if(rule.caseSensitive) request.url else context.lower
        return glob(rule.pattern,if(rule.caseSensitive) value else value.lowercase(),rule.host!=null)
    }

    companion object {
        private const val MAGIC=0x4d4e5232
        private val DOMAIN=Regex("[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*(?:\\.\\*)?")
        private val TOKEN=Regex("[a-zA-Z0-9_%.-]{3,}")
        private val POPUP_BIT=1 shl ResourceType.POPUP.ordinal
        private val DOCUMENT_BIT=1 shl ResourceType.DOCUMENT.ordinal
        private const val MAX_OBJECTS=160000
        private val ALL_TYPES=(1 shl ResourceType.values().size)-1
        private fun trigram(s: String,p: Int)=(s[p].code shl 16) xor (s[p+1].code shl 8) xor s[p+2].code

        /** Splits an AdGuard `$replace` value `/regex/replacement/flags`; null when malformed. */
        fun replaceParts(value: String): Triple<String,String,String>? {
            if(!value.startsWith('/')) return null
            val parts=ArrayList<String>(); val current=StringBuilder(); var escaped=false
            for(ch in value.substring(1)) {
                when { escaped->{ current.append('\\').append(ch); escaped=false }
                    ch=='\\'->escaped=true
                    ch=='/' && parts.size<2->{ parts.add(current.toString()); current.setLength(0) }
                    else->current.append(ch) }
            }
            if(escaped) current.append('\\')
            if(parts.size!=2 || parts[0].isEmpty() || current.any { it !in "gimsu" }) return null
            return Triple(parts[0],parts[1].replace("\\/","/"),current.toString())
        }

        /** Greedy bounded wildcard matcher; no downloaded regular expressions are evaluated. */
        fun glob(raw: String,value: String,hostAnchored: Boolean=false): Boolean {
            val left=hostAnchored || raw.startsWith('|'); val right=raw.endsWith('|')
            val pattern=(if(left) "" else "*")+raw.removePrefix("|").removeSuffix("|")+(if(right) "" else "*")
            var p=0; var s=0; var star=-1; var retry=0
            while(s<value.length) {
                when {
                    p<pattern.length && pattern[p]=='*'->{ star=p++; retry=s }
                    p<pattern.length && (pattern[p]==value[s] || pattern[p]=='^' && !value[s].isLetterOrDigit() && value[s] !in "_%.-")->{ p++; s++ }
                    star>=0->{ p=star+1; s=++retry }
                    else->return false
                }
            }
            while(p<pattern.length && pattern[p] in "*^") p++
            return p==pattern.length
        }
    }
}
