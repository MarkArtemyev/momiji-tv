package app.momiji.tv

import android.net.Uri

/** Keeps only streams seen in this page, never video segments or signed URLs on disk. */
class MediaCatalog {
    data class Stream(val url: String, val referer: String?, val label: String)
    private val streams = LinkedHashMap<String, Stream>()

    @Synchronized fun clear() = streams.clear()

    @Synchronized fun capture(url: String, headers: Map<String, String> = emptyMap(), direct: Boolean = false) {
        val uri = Uri.parse(url)
        if (url.length > 8192 || uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return
        val path = uri.path.orEmpty().lowercase()
        val kind = when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".mpd") -> "DASH"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "MP4"
            path.endsWith(".webm") -> "WebM"
            direct -> "Видео"
            else -> return
        }
        if (streams.size >= 12 && url !in streams) return
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        val old = streams[url]
        streams[url] = Stream(url, referer ?: old?.referer, "$kind · ${uri.host} · ${uri.lastPathSegment.orEmpty().take(48)}")
    }

    @Synchronized fun snapshot(): List<Stream> = streams.values.sortedBy {
        if (Uri.parse(it.url).path.orEmpty().lowercase().endsWith(".m3u8")) 0 else 1
    }
}
