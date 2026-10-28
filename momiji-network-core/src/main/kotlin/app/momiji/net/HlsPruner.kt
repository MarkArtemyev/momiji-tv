package app.momiji.net

import java.net.URI

/**
 * Cuts server-side inserted ads out of HLS media playlists before a player sees them, so the player
 * never requests ad segments and nothing it needs is ever blocked mid-stream.
 *
 * A segment is an ad when it sits between SCTE-35 cue markers (`#EXT-X-CUE-OUT` ... `#EXT-X-CUE-IN`,
 * `#EXT-X-SCTE35` CUE-OUT/CUE-IN), matches an AdGuard `$hls` rule, or its URL is blocked by the filters.
 * Only VOD playlists are edited: in a live playlist removing segments would shift the media sequence
 * the player tracks between reloads. Encryption keys, init maps and byte ranges are carried over so
 * the kept segments decode exactly as before, and every cut is marked with `#EXT-X-DISCONTINUITY`.
 */
object HlsPruner {
    class Rule internal constructor(val regex: Regex?, val pattern: String?, val tags: Boolean)

    class Result(val text: String, val removed: Int, val kept: Int)

    /** Parses a `$hls` value: `/regex/flags` (t = test tag lines, i = ignore case, s = dot matches all) or a URL pattern. */
    fun rule(value: String): Rule? {
        if (value.isEmpty()) return null
        if (!value.startsWith('/')) return Rule(null, value.lowercase(), false)
        val end = value.lastIndexOf('/')
        if (end <= 0) return null
        val flags = value.substring(end + 1)
        if (flags.any { it !in "tis" }) return null
        val options = HashSet<RegexOption>()
        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
        if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
        return runCatching { Rule(Regex(value.substring(1, end), options), null, 't' in flags) }.getOrNull()
    }

    private class Segment(val tags: List<String>, val uri: String, val url: String) {
        var duration = 0.0
        var byteRange: String? = null
    }

    /** The pruned playlist, or null for master/live/non-HLS text, when nothing matched, or when a cut looks wrong. */
    fun prune(text: String, baseUrl: String, rules: List<Rule> = emptyList(), isAd: (String) -> Boolean = { false }): Result? {
        if (text.length > 8 * 1024 * 1024) return null
        val lines = text.split('\n').map { it.trimEnd('\r') }
        if (lines.firstOrNull()?.trim() != "#EXTM3U") return null
        if (lines.none { it.startsWith("#EXTINF") }) return null
        val vod = lines.any { it.trim() == "#EXT-X-ENDLIST" || it.startsWith("#EXT-X-PLAYLIST-TYPE:VOD") }
        if (!vod) return null

        val header = ArrayList<String>()
        val segments = ArrayList<Segment>()
        var pending = ArrayList<String>()
        var endList = false
        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) {
                val name = line.substringBefore(':')
                when {
                    name == "#EXT-X-ENDLIST" -> endList = true
                    segments.isEmpty() && name in PLAYLIST_TAGS -> header.add(line)
                    else -> pending.add(line)
                }
                continue
            }
            segments.add(Segment(pending, line.trim(), resolve(baseUrl, line.trim())))
            pending = ArrayList()
        }
        val trailer = pending
        if (endList) trailer.add("#EXT-X-ENDLIST")
        if (segments.isEmpty()) return null

        // Classify in playlist order; cue markers carry state across segments and the last marker on a segment wins.
        val ads = BooleanArray(segments.size)
        var inBreak = false
        val lastEnd = HashMap<String, Long>()
        for ((index, segment) in segments.withIndex()) {
            for (tag in segment.tags) {
                val name = tag.substringBefore(':')
                when {
                    name == "#EXT-X-CUE-OUT" || name == "#EXT-X-CUE-OUT-CONT" -> inBreak = true
                    name == "#EXT-X-CUE-IN" -> inBreak = false
                    name == "#EXT-X-SCTE35" && "CUE-OUT=YES" in tag.uppercase() -> inBreak = true
                    name == "#EXT-X-SCTE35" && "CUE-IN=YES" in tag.uppercase() -> inBreak = false
                    name == "#EXTINF" -> segment.duration = tag.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    name == "#EXT-X-BYTERANGE" -> {
                        val value = tag.substringAfter(':')
                        val length = value.substringBefore('@').trim().toLongOrNull() ?: return null
                        val offset = if ('@' in value) value.substringAfter('@').trim().toLongOrNull() ?: return null else lastEnd[segment.url] ?: 0L
                        lastEnd[segment.url] = offset + length
                        segment.byteRange = "#EXT-X-BYTERANGE:$length@$offset"
                    }
                }
            }
            val byRule = rules.any { rule ->
                when {
                    rule.tags -> segment.tags.any { rule.regex!!.containsMatchIn(it) }
                    rule.regex != null -> rule.regex.containsMatchIn(segment.url)
                    else -> RequestRules.glob(rule.pattern!!, segment.url.lowercase())
                }
            }
            ads[index] = inBreak || byRule || isAd(segment.url)
        }
        val removed = ads.count { it }
        val strippable = segments.any { segment -> segment.tags.any(::isMarker) }
        if (removed == 0 && !strippable) return null
        if (removed == segments.size) return null
        val total = segments.sumOf { it.duration }
        val cut = segments.withIndex().filter { ads[it.index] }.sumOf { it.value.duration }
        // Ads are a minority of any real VOD; a bigger cut means a marker or filter misfired.
        if (total > 0 && cut / total > 0.7) return null

        val out = ArrayList<String>(lines.size)
        out.addAll(header)
        var currentKey: String? = null; var currentMap: String? = null
        var emittedKey: String? = null; var emittedMap: String? = null
        var gap = false; var emittedAny = false
        for ((index, segment) in segments.withIndex()) {
            segment.tags.forEach { tag ->
                if (tag.startsWith("#EXT-X-KEY")) currentKey = tag
                if (tag.startsWith("#EXT-X-MAP")) currentMap = tag
            }
            if (ads[index]) { gap = true; continue }
            val discontinuity = segment.tags.any { it == "#EXT-X-DISCONTINUITY" } || gap && emittedAny
            if (discontinuity) out.add("#EXT-X-DISCONTINUITY")
            if (currentKey != emittedKey) { out.add(currentKey ?: "#EXT-X-KEY:METHOD=NONE"); emittedKey = currentKey }
            if (currentMap != null && currentMap != emittedMap) { out.add(currentMap!!); emittedMap = currentMap }
            for (tag in segment.tags) {
                if (tag == "#EXT-X-DISCONTINUITY" || tag.startsWith("#EXT-X-KEY") || tag.startsWith("#EXT-X-MAP") || isMarker(tag)) continue
                out.add(if (tag.startsWith("#EXT-X-BYTERANGE")) segment.byteRange ?: tag else tag)
            }
            out.add(segment.uri)
            gap = false; emittedAny = true
        }
        out.addAll(trailer.filterNot(::isMarker))
        return Result(out.joinToString("\n", postfix = "\n"), removed, segments.size - removed)
    }

    private fun isMarker(tag: String): Boolean {
        val name = tag.substringBefore(':')
        return name in MARKERS || name == "#EXT-X-DATERANGE" && (INTERSTITIAL.containsMatchIn(tag) || "SCTE35" in tag.uppercase())
    }

    private fun resolve(base: String, uri: String): String =
        runCatching { URI(base).resolve(URI(uri)).toString() }.getOrDefault(uri)

    private val PLAYLIST_TAGS = setOf("#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-DISCONTINUITY-SEQUENCE",
        "#EXT-X-PLAYLIST-TYPE", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-START", "#EXT-X-I-FRAMES-ONLY", "#EXT-X-ALLOW-CACHE", "#EXT-X-DEFINE")
    private val MARKERS = setOf("#EXT-X-CUE-OUT", "#EXT-X-CUE-OUT-CONT", "#EXT-X-CUE-IN", "#EXT-X-SCTE35", "#EXT-OATCLS-SCTE35",
        "#EXT-X-ASSET", "#EXT-X-CUE", "#EXT-X-SPLICEPOINT-SCTE35", "#EXT-X-PLACEMENT-OPPORTUNITY")
    private val INTERSTITIAL = Regex("CLASS=\"(com\\.apple\\.hls\\.interstitial|twitch-stitched-ad|[^\"]*\\bad[s]?\\b[^\"]*)\"", RegexOption.IGNORE_CASE)
}
