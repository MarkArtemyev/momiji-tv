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
}

enum class ResourceType { DOCUMENT,SUBDOCUMENT,SCRIPT,STYLESHEET,IMAGE,FONT,MEDIA,XMLHTTPREQUEST,WEBSOCKET,PING,OTHER }
data class RuleDecision(val blocked: Boolean,val priority: Int) {
    companion object {
        /** Higher priority wins; on a tie an exception beats a block. */
        fun stronger(a: RuleDecision?,b: RuleDecision?): RuleDecision? = when {
            a==null->b; b==null->a
            a.priority!=b.priority->if(a.priority>b.priority) a else b
            else->if(!a.blocked) a else b
        }
    }
}
data class FilterRequest(val url: String,val documentUrl: String="",val type: ResourceType=ResourceType.OTHER) {
    val host=runCatching { DomainTable.normalizeHost(URI(url).host.orEmpty()) }.getOrDefault("")
    val documentHost=runCatching { DomainTable.normalizeHost(URI(documentUrl).host.orEmpty()) }.getOrDefault("")
}

/** Supported ABP network subset. Unsupported modifiers/regex are skipped, never broadened. */
class RequestRules private constructor(private val suffixes: PublicSuffixes) {
    private data class Rule(val pattern: String,val host: String?,val allow: Boolean,val important: Boolean,val caseSensitive: Boolean,
        val positive: Int,val negative: Int,val thirdParty: Boolean?,val domains: List<String>,val excluded: List<String>,val documentException: Boolean) {
        val priority get()=if(important) { if(allow) 4 else 3 } else if(allow) 2 else 1
    }
    private val hosts=HashMap<String,MutableList<Rule>>()
    private val keywords=HashMap<Int,MutableList<Rule>>()
    private val fallback=ArrayList<Rule>()
    private val documents=HashMap<String,MutableList<Rule>>()
    private var domainBlocks=LongArray(0)
    private var domainAllows=LongArray(0)
    var count=0; private set
    var skipped=0; private set
    constructor(lines: List<String>,suffixes: PublicSuffixes): this(suffixes) {
        val disabled=lines.filter { it.contains("badfilter") }.map { canonical(it,true) }.toHashSet()
        val block=ArrayList<Long>(); val allow=ArrayList<Long>()
        for(raw in lines) {
            val line=raw.trim()
            if(line.isEmpty() || line.startsWith('!') || line.startsWith('[') || line.contains("##") || line.contains("#@#")) continue
            if(count>=120000 && !line.startsWith("@@")) { skipped++; continue }
            if(line.contains("badfilter") || (disabled.isNotEmpty() && canonical(line,false) in disabled)) continue
            val rule=parse(line)
            if(rule==null) { skipped++; continue }
            count++
            if(rule.host!=null && rule.pattern=="^" && rule.positive==0 && rule.negative==0 && rule.thirdParty==null && rule.domains.isEmpty() && rule.excluded.isEmpty() && !rule.important) {
                (if(rule.allow) allow else block).add(DomainTable.hash(rule.host)); continue
            }
            if(rule.documentException && rule.host!=null) documents.getOrPut(rule.host) { ArrayList() }.add(rule)
            if(rule.host!=null) hosts.getOrPut(rule.host) { ArrayList() }.add(rule)
            else {
                val literal=TOKEN.findAll(rule.pattern).map { it.value }.maxByOrNull { it.length }
                if(literal!=null) keywords.getOrPut(trigram(literal.lowercase(),0)) { ArrayList() }.add(rule)
                else if(fallback.size<128 || rule.allow) fallback.add(rule) else { count--; skipped++ }
            }
        }
        domainBlocks=block.distinct().sorted().toLongArray(); domainAllows=allow.distinct().sorted().toLongArray()
    }
    constructor(input: java.io.InputStream,suffixes: PublicSuffixes): this(suffixes) {
        java.io.DataInputStream(input.buffered(65536)).use { data ->
            require(data.readInt()==0x4d4e5231)
            fun longs(): LongArray { val n=data.readInt(); require(n in 0..250000); return LongArray(n) { data.readLong() } }
            domainBlocks=longs(); domainAllows=longs(); count=data.readInt(); skipped=data.readInt()
            require(count in 0..150000 && skipped>=0)
            val n=data.readInt(); require(n in 0..150000)
            repeat(n) {
                val bucket=data.readInt()
                val host=data.readUTF().ifEmpty { null }; val rule=Rule(data.readUTF(),host,data.readBoolean(),data.readBoolean(),data.readBoolean(),
                    data.readInt(),data.readInt(),when(data.readByte().toInt()) { 0->false; 1->true; else->null },
                    data.readUTF().split('|').filter(String::isNotEmpty),data.readUTF().split('|').filter(String::isNotEmpty),data.readBoolean())
                if(rule.documentException && rule.host!=null) documents.getOrPut(rule.host) { ArrayList() }.add(rule)
                if(rule.host!=null) hosts.getOrPut(rule.host) { ArrayList() }.add(rule)
                else if(bucket<0) fallback.add(rule) else keywords.getOrPut(bucket) { ArrayList() }.add(rule)
            }
            require(data.read()==-1)
        }
    }
    fun write(output: java.io.OutputStream) {
        java.io.DataOutputStream(output.buffered(65536)).use { data ->
            data.writeInt(0x4d4e5231)
            for(array in listOf(domainBlocks,domainAllows)) { data.writeInt(array.size); array.forEach(data::writeLong) }
            data.writeInt(count); data.writeInt(skipped)
            val all=hosts.values.flatten().map { it to -1 }+keywords.entries.flatMap { (key,list)->list.map { it to key } }+fallback.map { it to -1 }
            data.writeInt(all.size)
            for((rule,bucket) in all) {
                data.writeInt(bucket)
                data.writeUTF(rule.host.orEmpty()); data.writeUTF(rule.pattern); data.writeBoolean(rule.allow); data.writeBoolean(rule.important); data.writeBoolean(rule.caseSensitive)
                data.writeInt(rule.positive); data.writeInt(rule.negative); data.writeByte(when(rule.thirdParty) { false->0; true->1; else->2 })
                data.writeUTF(rule.domains.joinToString("|")); data.writeUTF(rule.excluded.joinToString("|")); data.writeBoolean(rule.documentException)
            }
        }
    }
    private fun canonical(line: String,removeBad: Boolean): String {
        val at=line.lastIndexOf('$'); if(at<0) return line
        val opts=line.substring(at+1).split(',').filterNot { removeBad && it=="badfilter" }.sorted()
        return line.substring(0,at)+(if(opts.isEmpty()) "" else "$"+opts.joinToString(","))
    }
    private fun parse(raw: String): Rule? {
        val allow=raw.startsWith("@@"); val text=raw.removePrefix("@@")
        val at=text.lastIndexOf('$'); val pattern=if(at>=0) text.substring(0,at) else text
        if(pattern.startsWith('/') && pattern.endsWith('/') && pattern.length>1) return null
        if(pattern.length !in 2..2048) return null
        var positive=0; var negative=0; var important=false; var matchCase=false; var third: Boolean?=null
        var domains=emptyList<String>(); var excluded=emptyList<String>(); var documentException=false
        for(option in if(at>=0) text.substring(at+1).split(',') else emptyList()) {
            val inverse=option.startsWith('~'); val value=option.removePrefix("~")
            val resource=when(value) {
                "document"->ResourceType.DOCUMENT; "subdocument"->ResourceType.SUBDOCUMENT; "script"->ResourceType.SCRIPT
                "stylesheet"->ResourceType.STYLESHEET; "image"->ResourceType.IMAGE; "font"->ResourceType.FONT; "media"->ResourceType.MEDIA
                "xmlhttprequest","xhr"->ResourceType.XMLHTTPREQUEST; "websocket"->ResourceType.WEBSOCKET; "ping","beacon"->ResourceType.PING; "other"->ResourceType.OTHER; else->null
            }
            if(resource!=null) { if(inverse) negative=negative or (1 shl resource.ordinal) else positive=positive or (1 shl resource.ordinal)
                if(resource==ResourceType.DOCUMENT && !inverse && allow) documentException=true
            } else when {
                option=="important"->important=true
                option=="match-case"->matchCase=true
                option in setOf("third-party","3p")->third=true
                option in setOf("~third-party","~3p","first-party","1p")->third=false
                option.startsWith("domain=")-> {
                    val entries=option.substringAfter('=').split('|'); if(entries.any { !DOMAIN.matches(it.removePrefix("~")) }) return null
                    domains=entries.filterNot { it.startsWith('~') }.map { DomainTable.normalizeHost(it) }
                    excluded=entries.filter { it.startsWith('~') }.map { DomainTable.normalizeHost(it.substring(1)) }
                }
                else->return null
            }
        }
        var host: String?=null; var target=pattern
        if(pattern.startsWith("||")) {
            val remaining=pattern.substring(2); val end=remaining.indexOfFirst { it in "^/|" }.let { if(it<0) remaining.length else it }
            val candidate=remaining.substring(0,end)
            if(!DOMAIN.matches(candidate)) return null
            host=DomainTable.normalizeHost(candidate); target=remaining.substring(end).ifEmpty { "*" }
        }
        return Rule(if(matchCase) target else target.lowercase(),host,allow,important,matchCase,positive,negative,third,domains,excluded,documentException)
    }
    fun blocks(request: FilterRequest): Boolean = decision(request)?.blocked==true
    fun decision(request: FilterRequest): RuleDecision? {
        if(request.host.isEmpty() || request.url.length>16384) return null
        if(request.documentHost.isNotEmpty() && documentExempt(request.documentUrl)) return RuleDecision(false,4)
        var winner=0; var blocked=false
        val lower=request.url.lowercase(); val party=if(request.documentHost.isEmpty()) null else suffixes.site(request.host)!=suffixes.site(request.documentHost)
        fun consider(rule: Rule) {
            if(rule.priority<winner || !applies(rule,request,party)) return
            val value=if(rule.host!=null) {
                val uri=runCatching { URI(request.url) }.getOrNull() ?: return
                (uri.rawPath?.ifEmpty { "/" } ?: "/")+(uri.rawQuery?.let { "?$it" } ?: "")
            } else if(rule.caseSensitive) request.url else lower
            if(glob(rule.pattern,if(rule.caseSensitive) value else value.lowercase(),rule.host!=null)) { winner=rule.priority; blocked=!rule.allow }
        }
        var host=request.host
        while(host.isNotEmpty()) {
            val hash=DomainTable.hash(host)
            if(domainBlocks.binarySearch(hash)>=0 && winner<1) { winner=1; blocked=true }
            if(domainAllows.binarySearch(hash)>=0 && winner<2) { winner=2; blocked=false }
            hosts[host]?.forEach(::consider)
            val at=host.indexOf('.'); if(at<0) break; host=host.substring(at+1)
        }
        val seen=HashSet<Int>()
        for(i in 0..lower.length-3) { val key=trigram(lower,i); if(seen.add(key)) keywords[key]?.forEach(::consider) }
        fallback.forEach(::consider)
        return if(winner==0) null else RuleDecision(blocked,winner)
    }
    private fun documentExempt(url: String): Boolean {
        val request=FilterRequest(url,url,ResourceType.DOCUMENT); var host=request.host
        while(host.isNotEmpty()) {
            val uri=runCatching { URI(url) }.getOrNull() ?: return false
            val tail=(uri.rawPath?.ifEmpty { "/" } ?: "/")+(uri.rawQuery?.let { "?$it" } ?: "")
            if(documents[host]?.any { applies(it,request,false) && glob(it.pattern,if(it.caseSensitive) tail else tail.lowercase(),true) }==true) return true
            val at=host.indexOf('.'); if(at<0) break; host=host.substring(at+1)
        }; return false
    }
    private fun applies(rule: Rule,request: FilterRequest,party: Boolean?): Boolean {
        val bit=1 shl request.type.ordinal
        if(rule.positive!=0 && rule.positive and bit==0 || rule.negative and bit!=0) return false
        if(rule.thirdParty!=null && rule.thirdParty!=party) return false
        fun matches(domain: String)=request.documentHost==domain || request.documentHost.endsWith(".$domain")
        return (rule.domains.isEmpty() || rule.domains.any(::matches)) && rule.excluded.none(::matches)
    }
    companion object {
        private val DOMAIN=Regex("[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*")
        private val TOKEN=Regex("[a-zA-Z0-9_%.-]{3,}")
        private fun trigram(s: String,p: Int)=(s[p].code shl 16) xor (s[p+1].code shl 8) xor s[p+2].code
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
