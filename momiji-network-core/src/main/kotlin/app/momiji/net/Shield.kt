package app.momiji.net

/** JSON handed to the page shield for one frame; see `assets/protection/shield.js`. */
object ShieldPayload {
    fun build(page: CosmeticRules.Page, flags: Int, jsonPrune: Boolean): String {
        val out = StringBuilder(page.css.length + 256).append("{\"v\":1")
        out.append(",\"f\":").append(flags)
        out.append(",\"css\":").append(Json.quote(page.css))
        out.append(",\"ext\":").append(page.extended.joinToString(",", "[", "]") { Json.quote(it) })
        out.append(",\"s\":").append(page.scriptlets.joinToString(",", "[", "]") { CosmeticRules.json(it) })
        out.append(",\"js\":").append(page.scripts.joinToString(",", "[", "]"))
        out.append(",\"g\":").append(if (page.generic) 1 else 0)
        out.append(",\"jp\":").append(if (jsonPrune) 1 else 0)
        return out.append('}').toString()
    }
}

/** AdGuard `$replace` with JavaScript semantics: `g` replaces every match, otherwise the first; `$&`, `$n`, `$$`. */
object ReplaceRules {
    fun apply(text: String, rules: List<Triple<String, String, String>>): String {
        var result = text
        for ((source, replacement, flags) in rules) {
            val options = HashSet<RegexOption>()
            if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
            if ('m' in flags) options.add(RegexOption.MULTILINE)
            if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
            // A JS pattern Java cannot read (e.g. a bare `{`) is skipped rather than guessed at.
            val regex = runCatching { Regex(source, options) }.getOrNull() ?: continue
            result = if ('g' in flags) regex.replace(result) { expand(replacement, it) }
            else regex.find(result)?.let { result.substring(0, it.range.first) + expand(replacement, it) + result.substring(it.range.last + 1) } ?: result
        }
        return result
    }

    private fun expand(replacement: String, match: MatchResult): String {
        val out = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch == '$' && i + 1 < replacement.length) {
                val next = replacement[i + 1]
                if (next == '$') { out.append('$'); i += 2; continue }
                if (next == '&') { out.append(match.value); i += 2; continue }
                if (next.isDigit()) {
                    var end = i + 2
                    if (end < replacement.length && replacement[end].isDigit() && replacement.substring(i + 1, end + 1).toInt() < match.groups.size) end++
                    val group = replacement.substring(i + 1, end).toInt()
                    if (group in 1 until match.groups.size) { out.append(match.groups[group]?.value.orEmpty()); i = end; continue }
                }
            }
            out.append(ch); i++
        }
        return out.toString()
    }
}

/**
 * Stand-ins for requests the filters block without naming a `$redirect`. A player that asked for an
 * ad gets something it can parse (an empty VAST, a one-second clip, a no-op ad SDK) and moves on to
 * the content instead of stalling on a network error or detecting the blocker.
 */
object Surrogates {
    private val VMAP = Regex("(^|[/_.?&=-])vmap([/_.?&=-]|\\d|$)")
    private val VAST = Regex("(^|[/_.?&=-])(vast|vpaid|adtag|ad_tag|gampad/ads)([/_.?&=-]|\\d|$)")
    private val AUDIO = Regex("\\.(mp3|aac|m4a|ogg|opus|wav)$")

    fun fallback(url: String, type: ResourceType): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return when (type) {
            ResourceType.SCRIPT -> when {
                "imasdk.googleapis.com" in path && path.endsWith("/ima3.js") -> "google-ima3"
                "imasdk.googleapis.com" in path && path.endsWith("/ima3_dai.js") -> "google-ima3-dai"
                path.endsWith("/gpt.js") && ("googletagservices.com" in path || "doubleclick.net" in path) -> "googletagservices-gpt"
                path.endsWith("/adsbygoogle.js") -> "googlesyndication-adsbygoogle"
                "google-analytics.com" in path && path.endsWith("/analytics.js") -> "google-analytics"
                "google-analytics.com" in path && path.endsWith("/ga.js") -> "google-analytics-ga"
                "amazon-adsystem.com" in path && path.endsWith("/apstag.js") -> "amazon-apstag"
                "mc.yandex." in path && path.endsWith("/tag.js") -> "metrika-yandex-tag"
                "mc.yandex." in path && path.endsWith("/watch.js") -> "metrika-yandex-watch"
                "scorecardresearch.com" in path && "beacon" in path -> "scorecardresearch-beacon"
                else -> null
            }
            ResourceType.XMLHTTPREQUEST, ResourceType.OTHER -> when {
                VMAP.containsMatchIn(lower) -> "noopvmap-1.0"
                VAST.containsMatchIn(lower) -> "noopvast-4.0"
                else -> null
            }
            ResourceType.MEDIA -> if (AUDIO.containsMatchIn(path)) "noopmp3-0.1s" else "noopmp4-1s"
            ResourceType.IMAGE -> "1x1-transparent.gif"
            ResourceType.SUBDOCUMENT -> "noopframe"
            ResourceType.STYLESHEET -> "noopcss"
            else -> null
        }
    }
}
