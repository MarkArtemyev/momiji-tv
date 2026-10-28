package app.momiji.net

/**
 * Shared AdGuard/uBO list syntax: `!#if` blocks, `!+ PLATFORM` hints, escaped option values and
 * domain lists with `example.*` wildcards. Lists are evaluated as the AdGuard Chromium extension
 * sees them: Safari, iOS and content-blocker workarounds stay out of a Chromium-based WebView.
 */
object FilterSyntax {
    const val PLATFORM = "ext_chromium"
    private val CONSTANTS = setOf("adguard", "adguard_ext_chromium")
    private val DOMAIN = Regex("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:\\.\\*)?")
    private val COSMETIC = Regex("#@?(?:\\$\\??|\\?|%)?#|\\$@?\\$")

    /** Rule lines that apply to this platform; directives, comments and hinted-away rules are dropped. */
    fun applicable(lines: Sequence<String>): Sequence<String> = sequence {
        val branches = ArrayList<Boolean>()
        var hinted: Boolean? = null
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("!#")) {
                when {
                    line.startsWith("!#if") -> branches.add(evaluate(line.removePrefix("!#if")))
                    line.startsWith("!#else") -> if (branches.isNotEmpty()) branches[branches.lastIndex] = !branches.last()
                    line.startsWith("!#endif") -> if (branches.isNotEmpty()) branches.removeAt(branches.lastIndex)
                }
                continue
            }
            if (line.startsWith("!+")) {
                hinted = (hinted ?: true) && hintAllows(line.removePrefix("!+"))
                continue
            }
            val allowed = hinted ?: true
            hinted = null
            if (line.isEmpty() || line[0] == '!' || line[0] == '[' || !allowed || branches.any { !it }) continue
            yield(line)
        }
    }

    /** True for element hiding, CSS, scriptlet, JS and HTML-filtering rules. */
    fun isCosmetic(line: String) = COSMETIC.containsMatchIn(line)

    private fun hintAllows(hint: String): Boolean {
        for (match in Regex("(NOT_PLATFORM|PLATFORM)\\(([^)]*)\\)").findAll(hint)) {
            val platforms = match.groupValues[2].split(',').map { it.trim() }
            val listed = PLATFORM in platforms
            if (match.groupValues[1] == "PLATFORM" && !listed) return false
            if (match.groupValues[1] == "NOT_PLATFORM" && listed) return false
        }
        return true
    }

    /** Evaluates `!#if` conditions; unknown constants are false, malformed conditions disable the block. */
    fun evaluate(expression: String): Boolean {
        val tokens = Regex("\\|\\||&&|[!()]|[A-Za-z0-9_]+").findAll(expression).map { it.value }.toList()
        var at = 0
        fun or(): Boolean {
            fun not(): Boolean {
                val token = tokens.getOrNull(at++) ?: error("Unexpected end")
                return when (token) {
                    "!" -> !not()
                    "(" -> or().also { require(tokens.getOrNull(at++) == ")") }
                    "&&", "||", ")" -> error("Unexpected $token")
                    else -> token in CONSTANTS
                }
            }
            fun and(): Boolean {
                var value = not()
                while (tokens.getOrNull(at) == "&&") { at++; value = not() && value }
                return value
            }
            var value = and()
            while (tokens.getOrNull(at) == "||") { at++; value = and() || value }
            return value
        }
        return runCatching { or().also { require(at == tokens.size) } }.getOrDefault(false)
    }

    /** Index of the `$` that starts network rule options, ignoring `\$` inside option values. */
    fun optionsStart(text: String): Int {
        var at = text.length - 1
        while (at >= 0) {
            if (text[at] == '$' && (at == 0 || text[at - 1] != '\\')) return at
            at--
        }
        return -1
    }

    /** Splits on [separator] unless it is escaped with a backslash; escapes stay in place. */
    fun splitUnescaped(text: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var escaped = false
        for (i in text.indices) {
            when {
                escaped -> escaped = false
                text[i] == '\\' -> escaped = true
                text[i] == separator -> { parts.add(text.substring(start, i)); start = i + 1 }
            }
        }
        parts.add(text.substring(start))
        return parts
    }

    /** Removes the backslash before each of [chars]; other escapes are kept for the consumer. */
    fun unescape(text: String, chars: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in chars) { out.append(text[i + 1]); i += 2 }
            else out.append(text[i++])
        }
        return out.toString()
    }

    /** Normalized include/exclude lists, or null when any entry is a regex or otherwise unsupported. */
    fun domains(entries: List<String>): Pair<List<String>, List<String>>? {
        val include = ArrayList<String>()
        val exclude = ArrayList<String>()
        for (raw in entries) {
            val entry = raw.trim().lowercase()
            if (entry.isEmpty()) continue
            val negated = entry.startsWith('~')
            val name = entry.removePrefix("~").trimEnd('.')
            // Dropping an entry we cannot read could broaden the remaining rule.
            if (!DOMAIN.matches(name)) return null
            val normalized = if (name.endsWith(".*")) name else DomainTable.normalizeHost(name).ifEmpty { return null }
            (if (negated) exclude else include).add(normalized)
        }
        return include to exclude
    }

    /** Host keys under which rules for [host] are stored: every parent domain and its `name.*` form. */
    fun hostKeys(host: String, suffixes: PublicSuffixes): List<String> {
        val keys = ArrayList<String>()
        var current = host
        while (current.isNotEmpty()) {
            keys.add(current)
            val dot = current.indexOf('.')
            if (dot < 0) break
            current = current.substring(dot + 1)
        }
        var label = withoutSuffix(host, suffixes)
        while (label.isNotEmpty() && label != host) {
            keys.add("$label.*")
            val dot = label.indexOf('.')
            if (dot < 0) break
            label = label.substring(dot + 1)
        }
        return keys
    }

    /** `example.*` matches example.com, example.co.uk and their subdomains; plain names match subdomains. */
    fun domainMatches(host: String, domain: String, suffixes: PublicSuffixes): Boolean {
        if (!domain.endsWith(".*")) return host == domain || host.endsWith(".$domain")
        val base = domain.dropLast(2)
        val label = withoutSuffix(host, suffixes)
        return label == base || label.endsWith(".$base")
    }

    private fun withoutSuffix(host: String, suffixes: PublicSuffixes): String {
        if (!host.contains('.') || host.contains(':') || host.all { it.isDigit() || it == '.' }) return host
        val suffix = suffixes.site(host).substringAfter('.', "")
        return if (suffix.isEmpty()) host else host.removeSuffix(".$suffix")
    }
}
