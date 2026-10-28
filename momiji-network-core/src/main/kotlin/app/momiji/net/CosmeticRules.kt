package app.momiji.net

/**
 * Page-side rules from AdGuard and uBO lists: element hiding (`##`), extended CSS (`#?#`, ExtendedCss),
 * CSS injection (`#$#`), scriptlets (`#%#//scriptlet()`, `##+js()`) and raw JS (`#%#`), with their `#@`
 * exceptions. Lists are compiled to [HEADER] lines once; [page] answers what one frame needs.
 *
 * Generic `##.class` / `##[id]` selectors (most of the 15k generic rules) are not sent to every page:
 * the page reports the classes and ids it actually uses and [generic] returns only matching selectors.
 */
class CosmeticRules(compiled: String, custom: List<String>, private val suffixes: PublicSuffixes) {
    enum class Kind(val code: String) { HIDE("H"), EXTENDED("X"), STYLE("C"), SCRIPTLET("S"), SCRIPT("J") }

    class Script(val id: Int, val include: List<String>, val exclude: List<String>, val code: String)

    /** Everything a frame on one host needs except the lowly generic selectors. */
    class Page(val css: String, val extended: List<String>, val scriptlets: List<List<String>>, val scripts: List<Int>, val generic: Boolean)

    // `include` is only needed while indexing; entries keep just what lookups read.
    private class Entry(val kind: Kind, val allow: Boolean, val include: List<String>, exclude: List<String>, val body: String) {
        val exclude: List<String> = if (exclude.isEmpty()) emptyList() else exclude.toList()
        var id = -1
    }
    private class Stored(val kind: Kind, val allow: Boolean, val exclude: List<String>, val body: String, val id: Int)

    private val site = HashMap<String, MutableList<Stored>>()
    private val generic = HashMap<Kind, MutableList<Stored>>()
    private val lowly = HashMap<String, MutableList<Stored>>()
    private val genericAllow = ArrayList<Stored>()
    private val scriptList = ArrayList<Script>()
    val counts = HashMap<Kind, Int>()
    val scripts: List<Script> get() = scriptList

    init {
        val lines = compiled.lineSequence()
        require(lines.firstOrNull() == HEADER) { "Unknown cosmetic cache" }
        lines.drop(1).filter { it.isNotEmpty() }.forEach { add(decode(it)) }
        FilterSyntax.applicable(custom.asSequence()).mapNotNull(::parse).forEach(::add)
    }

    private fun add(entry: Entry) {
        if (entry.kind == Kind.SCRIPT && !entry.allow) {
            entry.id = scriptList.size
            scriptList.add(Script(entry.id, entry.include, entry.exclude, entry.body))
        }
        val stored = Stored(entry.kind, entry.allow, entry.exclude, entry.body, entry.id)
        if (entry.include.isNotEmpty()) entry.include.forEach { site.getOrPut(it) { ArrayList(2) }.add(stored) }
        else if (entry.allow) genericAllow.add(stored)
        else {
            val token = if (entry.kind == Kind.HIDE) lowlyToken(entry.body) else null
            if (token != null) lowly.getOrPut(token) { ArrayList(2) }.add(stored)
            else generic.getOrPut(entry.kind) { ArrayList() }.add(stored)
        }
        if (!entry.allow) counts[entry.kind] = (counts[entry.kind] ?: 0) + 1
    }

    /** Hiding, styles, extended CSS, scriptlets and script ids for a frame on [host] with network [flags]. */
    fun page(host: String, flags: Int = 0): Page {
        val keys = FilterSyntax.hostKeys(host, suffixes)
        val hidden = flags and (PageFlags.DOCUMENT or PageFlags.ELEMHIDE) != 0
        val noScripts = flags and (PageFlags.DOCUMENT or PageFlags.JSINJECT) != 0
        val noGeneric = hidden || flags and PageFlags.GENERICHIDE != 0
        val noSpecific = hidden || flags and PageFlags.SPECIFICHIDE != 0
        val specific = LinkedHashSet<Stored>()
        keys.forEach { key -> site[key]?.forEach { if (applies(it, host)) specific.add(it) } }
        val allowed = allowedBodies(host, specific)
        fun pick(kind: Kind): List<String> {
            if (kind == Kind.SCRIPTLET || kind == Kind.SCRIPT) { if (noScripts) return emptyList() }
            else if (hidden) return emptyList()
            val chosen = LinkedHashSet<String>()
            if (!(noSpecific && kind != Kind.SCRIPTLET && kind != Kind.SCRIPT))
                specific.forEach { if (it.kind == kind && !it.allow) chosen.add(it.body) }
            if (!(noGeneric && kind != Kind.SCRIPTLET))
                generic[kind]?.forEach { if (applies(it, host)) chosen.add(it.body) }
            return chosen.filter { it !in (allowed[kind] ?: emptySet<String>()) }
        }
        val css = StringBuilder()
        pick(Kind.HIDE).take(4000).forEach { css.append(it).append("{display:none!important}\n") }
        pick(Kind.STYLE).take(1000).forEach { css.append(it).append('\n') }
        val extended = pick(Kind.EXTENDED).take(1000).map { if ('{' in it) it else "$it { display: none !important; }" }
        val disabledScriptlets = allowed[Kind.SCRIPTLET] ?: emptySet()
        val scriptlets = if (ALL_SCRIPTLETS in disabledScriptlets) emptyList() else pick(Kind.SCRIPTLET).asSequence()
            .map(::jsonArray).filter { call -> scriptletKey(call) !in disabledScriptlets && nameKey(call) !in disabledScriptlets }.take(300).toList()
        val scriptIds = if (noScripts) emptyList() else specific.filter { it.kind == Kind.SCRIPT && !it.allow && it.body !in (allowed[Kind.SCRIPT] ?: emptySet()) }.map { it.id }
        return Page(css.toString(), extended, scriptlets, scriptIds, !noGeneric)
    }

    /** CSS for generic selectors keyed by the `.class` / `#id` [tokens] a frame on [host] has seen. */
    fun generic(host: String, tokens: Collection<String>, flags: Int = 0): String {
        if (flags and (PageFlags.DOCUMENT or PageFlags.ELEMHIDE or PageFlags.GENERICHIDE) != 0) return ""
        val allowed by lazy {
            val specific = LinkedHashSet<Stored>()
            FilterSyntax.hostKeys(host, suffixes).forEach { key -> site[key]?.forEach { if (it.allow && applies(it, host)) specific.add(it) } }
            allowedBodies(host, specific)[Kind.HIDE] ?: emptySet()
        }
        val css = StringBuilder()
        for (token in tokens.take(2000)) {
            lowly[token]?.forEach { if (applies(it, host) && it.body !in allowed) css.append(it.body).append("{display:none!important}\n") }
        }
        return css.toString()
    }

    private fun allowedBodies(host: String, specific: Collection<Stored>): Map<Kind, Set<String>> {
        val allowed = HashMap<Kind, MutableSet<String>>()
        for (entry in specific.asSequence() + genericAllow.asSequence().filter { applies(it, host) }) {
            if (!entry.allow) continue
            val bodies = allowed.getOrPut(entry.kind) { HashSet() }
            if (entry.kind == Kind.SCRIPTLET) bodies.add(if (entry.body.isEmpty()) ALL_SCRIPTLETS else jsonArray(entry.body).let { if (it.size == 1) nameKey(it) else scriptletKey(it) })
            else bodies.add(entry.body)
        }
        return allowed
    }

    private fun applies(entry: Stored, host: String) = entry.exclude.none { FilterSyntax.domainMatches(host, it, suffixes) }

    companion object {
        const val HEADER = "momiji-cosmetic-v3"
        private const val ALL_SCRIPTLETS = "\u0000all"
        private val SEPARATOR = Regex("#@?(?:\\$\\??|\\?|%)?#")
        private val EXTENDED_PSEUDO = listOf(":has-text(", ":contains(", ":-abp-contains(", ":-abp-has(", ":matches-css", ":matches-attr(",
            ":matches-property(", ":xpath(", ":nth-ancestor(", ":upward(", ":remove(", ":if-not(", ":if(", "[-ext-")
        private val UNSUPPORTED = listOf(":min-text-length(", ":watch-attr(", ":others(", ":matches-path(", ":matches-media(", ":style(")
        private val STYLE = Regex("^(.+?)\\s*\\{([^{}]*)\\}\\s*$", RegexOption.DOT_MATCHES_ALL)
        private val TOKEN = Regex("^([#.])([A-Za-z_][A-Za-z0-9_-]*)")
        private val UBO_NAMES = mapOf("set" to "set-constant", "acis" to "abort-current-inline-script", "acs" to "abort-current-inline-script",
            "abort-current-script" to "abort-current-inline-script", "aopr" to "abort-on-property-read", "aopw" to "abort-on-property-write",
            "aost" to "abort-on-stack-trace", "nostif" to "prevent-settimeout", "no-settimeout-if" to "prevent-settimeout",
            "nosiif" to "prevent-setinterval", "no-setinterval-if" to "prevent-setinterval", "aeld" to "prevent-addeventlistener",
            "addeventlistener-defuser" to "prevent-addeventlistener", "nowoif" to "prevent-window-open", "no-window-open-if" to "prevent-window-open",
            "rmnt" to "remove-node-text", "ra" to "remove-attr", "rc" to "remove-class", "no-fetch-if" to "prevent-fetch",
            "no-xhr-if" to "prevent-xhr", "set-constant" to "set-constant")

        /** Compiles list lines (directives already allowed for) into the cache format read by the constructor. */
        fun compile(lines: Sequence<String>): String {
            val out = StringBuilder(HEADER).append('\n')
            val seen = HashSet<String>()
            FilterSyntax.applicable(lines).mapNotNull(::parse).forEach { entry ->
                val line = encode(entry)
                if (seen.add(line)) out.append(line).append('\n')
            }
            return out.toString()
        }

        private fun parse(line: String): Entry? {
            val hash = line.indexOf('#')
            if (hash < 0 || line.startsWith("[$")) return null
            val separator = SEPARATOR.find(line, hash)?.takeIf { it.range.first == hash } ?: return null
            val body = line.substring(separator.range.last + 1).trim()
            if (body.isEmpty() && separator.value != "#@%#" && separator.value != "#@#") return null
            if (body.length > 65536 || body.any { it == '\u0000' || it == '\n' || it == '\r' }) return null
            val (include, exclude) = FilterSyntax.domains(line.substring(0, hash).split(',')) ?: return null
            val allow = separator.value.startsWith("#@")
            val kind = when (separator.value.replace("@", "")) {
                "##" -> when {
                    body.startsWith("+js(") -> return uboScriptlet(body, allow, include, exclude)
                    body.startsWith("^") -> return null
                    else -> return selector(body, allow, include, exclude, false)
                }
                "#?#" -> return selector(body, allow, include, exclude, true)
                "#$#", "#$?#" -> return style(body, allow, include, exclude, separator.value.contains('?'))
                "#%#" -> if (body.startsWith("//scriptlet(")) Kind.SCRIPTLET else Kind.SCRIPT
                else -> return null
            }
            if (kind == Kind.SCRIPTLET) {
                val call = adguardScriptlet(body) ?: return null
                if (call.isEmpty() && !allow) return null
                return Entry(kind, allow, include, exclude, if (call.isEmpty()) "" else json(call))
            }
            // Raw JS is code: only ever site-specific, and a generic exception would be meaningless.
            if (include.isEmpty() || body.isEmpty()) return null
            return Entry(kind, allow, include, exclude, body)
        }

        private fun selector(body: String, allow: Boolean, include: List<String>, exclude: List<String>, extended: Boolean): Entry? {
            if (body.isEmpty()) return null
            if (body.any { it == '{' || it == '}' || it == '\t' } || body.length > 8192 || UNSUPPORTED.any { body.contains(it) }) return null
            val isExtended = extended || EXTENDED_PSEUDO.any { body.contains(it) }
            val text = if (body.endsWith(":remove()")) "${body.removeSuffix(":remove()")} { remove: true; }" else body
            return Entry(if (isExtended || text != body) Kind.EXTENDED else Kind.HIDE, allow, include, exclude, text)
        }

        private fun style(body: String, allow: Boolean, include: List<String>, exclude: List<String>, extended: Boolean): Entry? {
            val match = STYLE.matchEntire(body) ?: return null
            val selector = match.groupValues[1].trim(); val declarations = match.groupValues[2].trim()
            // Same limits as AdGuard: injected CSS may restyle the page but never load anything.
            val lower = declarations.lowercase()
            if (selector.isEmpty() || "url(" in lower || "@import" in lower || "expression(" in lower || '\\' in declarations || '\\' in selector && "url" in selector.lowercase()) return null
            if (UNSUPPORTED.any { selector.contains(it) }) return null
            val isExtended = extended || EXTENDED_PSEUDO.any { selector.contains(it) } || Regex("(^|;)\\s*remove\\s*:\\s*true").containsMatchIn(lower)
            return Entry(if (isExtended) Kind.EXTENDED else Kind.STYLE, allow, include, exclude, "$selector { $declarations }")
        }

        /** `//scriptlet('name', 'a', "b")` → [name, a, b]; empty list for `//scriptlet()`; null when malformed. */
        private fun adguardScriptlet(body: String): List<String>? {
            if (!body.startsWith("//scriptlet(") || !body.trimEnd().endsWith(")")) return null
            val inner = body.substring("//scriptlet(".length, body.trimEnd().length - 1).trim()
            val args = ArrayList<String>()
            var i = 0
            while (i < inner.length) {
                val quote = inner[i]
                if (quote != '\'' && quote != '"') return null
                val value = StringBuilder(); i++
                var closed = false
                while (i < inner.length) {
                    val ch = inner[i]
                    if (ch == '\\' && i + 1 < inner.length) { val next = inner[i + 1]; if (next != quote) value.append('\\'); value.append(next); i += 2; continue }
                    if (ch == quote) { closed = true; i++; break }
                    value.append(ch); i++
                }
                if (!closed) return null
                args.add(value.toString())
                while (i < inner.length && inner[i] == ' ') i++
                if (i < inner.length) { if (inner[i] != ',') return null; i++; while (i < inner.length && inner[i] == ' ') i++ }
            }
            if (args.isNotEmpty() && args[0].isBlank()) return null
            return args
        }

        /** uBO `+js(name, a, b)` → AdGuard's `ubo-name.js` alias with the same arguments. */
        private fun uboScriptlet(body: String, allow: Boolean, include: List<String>, exclude: List<String>): Entry? {
            if (!body.endsWith(")")) return null
            val inner = body.substring(4, body.length - 1)
            if (inner.isBlank()) return if (allow) Entry(Kind.SCRIPTLET, true, include, exclude, "") else null
            val args = FilterSyntax.splitUnescaped(inner, ',').map { FilterSyntax.unescape(it.trim(), ",") }
            val name = args[0].let { if (it.endsWith(".js")) it else "$it.js" }
            if (!Regex("[A-Za-z0-9._-]+").matches(name)) return null
            return Entry(Kind.SCRIPTLET, allow, include, exclude, json(listOf("ubo-$name") + args.drop(1)))
        }

        private fun lowlyToken(selector: String): String? {
            if (',' in selector || '\\' in selector) return null
            val match = TOKEN.find(selector) ?: return null
            return match.groupValues[1] + match.groupValues[2]
        }

        /** Canonical name for exception matching across `ubo-x.js`, `x.js` and short uBO aliases. */
        private fun canonicalName(name: String): String {
            val bare = name.lowercase().removePrefix("ubo-").removeSuffix(".js")
            return UBO_NAMES[bare] ?: bare
        }
        private fun nameKey(call: List<String>) = canonicalName(call.firstOrNull().orEmpty())
        private fun scriptletKey(call: List<String>) = (listOf(nameKey(call)) + call.drop(1)).joinToString("\u0001")

        private fun encode(entry: Entry): String {
            val domains = (entry.include + entry.exclude.map { "~$it" }).joinToString(",").ifEmpty { "*" }
            return "${entry.kind.code}${if (entry.allow) "A" else ""}\t$domains\t${escape(entry.body)}"
        }

        private fun decode(line: String): Entry {
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3)
            val allow = fields[0].endsWith("A") && fields[0].length == 2
            val kind = Kind.values().first { it.code == fields[0].substring(0, 1) }
            val (include, exclude) = if (fields[1] == "*") emptyList<String>() to emptyList() else FilterSyntax.domains(fields[1].split(',')) ?: error("Bad domains")
            return Entry(kind, allow, include, exclude, unescape(fields[2]))
        }

        private fun escape(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t")
        private fun unescape(text: String): String {
            if ('\\' !in text) return text
            val out = StringBuilder(text.length); var i = 0
            while (i < text.length) {
                if (text[i] == '\\' && i + 1 < text.length) { out.append(if (text[i + 1] == 't') '\t' else text[i + 1]); i += 2 } else out.append(text[i++])
            }
            return out.toString()
        }

        fun json(values: List<String>) = values.joinToString(",", "[", "]") { Json.quote(it) }
        private fun jsonArray(text: String): List<String> = Json.stringArray(text)
    }
}

/** Minimal JSON helpers for strings and string arrays; the core has no JSON dependency. */
object Json {
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\""); '\\' -> out.append("\\\\"); '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
                ' ' -> out.append("\\u2028"); ' ' -> out.append("\\u2029")
                else -> if (ch < ' ') out.append(String.format("\\u%04x", ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    /** Parses a JSON array of strings as written by [quote]. */
    fun stringArray(text: String): List<String> {
        val values = ArrayList<String>(); var i = text.indexOf('[') + 1
        require(i > 0)
        while (i < text.length) {
            when (text[i]) {
                ' ', ',' -> i++
                ']' -> return values
                '"' -> {
                    val value = StringBuilder(); i++
                    while (text[i] != '"') {
                        if (text[i] == '\\') {
                            val next = text[i + 1]
                            when (next) {
                                'n' -> value.append('\n'); 'r' -> value.append('\r'); 't' -> value.append('\t')
                                'u' -> { value.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                                else -> value.append(next)
                            }
                            i += 2
                        } else value.append(text[i++])
                    }
                    values.add(value.toString()); i++
                }
                else -> error("Bad JSON array")
            }
        }
        error("Unterminated JSON array")
    }
}
