package app.momiji.tv

import android.webkit.JavascriptInterface
import app.momiji.net.CosmeticRules
import app.momiji.net.FilterRequest
import app.momiji.net.PageFlags
import app.momiji.net.ShieldPayload

/**
 * `window.MomijiShield` for shield.js. Any page can call it, so it only ever answers with public
 * filter data for the URL it is given and never acts on a page's say-so: request-type hints are
 * the one input it keeps, and they can only change how that page's own requests are classified.
 */
class ShieldBridge(private val hints: RequestHints) {
    @Volatile var blockPopups = true

    @JavascriptInterface
    fun page(url: String): String {
        val host = hostOf(url) ?: return ""
        val flags = AdBlock.pageFlags(url)
        if (flags and PageFlags.DOCUMENT != 0) return "{\"v\":1,\"f\":$flags}"
        val page = Cosmetic.page(host, flags) ?: return ""
        return ShieldPayload.build(page, flags, AdBlock.hasJsonPrune(url)).dropLast(1) + ",\"p\":${if (blockPopups) 1 else 0}}"
    }

    @JavascriptInterface
    fun cosmetic(tokens: String, url: String): String {
        val host = hostOf(url) ?: return ""
        if (tokens.length > 65536) return ""
        return Cosmetic.generic(host, tokens.split(' ').filter { it.length in 2..256 }, AdBlock.pageFlags(url))
    }

    @JavascriptInterface
    fun response(url: String, page: String, type: String): String {
        if (hostOf(url) == null || page.length > 16384) return ""
        val paths = AdBlock.jsonPrune(url, page)
        return if (paths.isEmpty()) "" else CosmeticRules.json(paths)
    }

    @JavascriptInterface
    fun hls(text: String, url: String, page: String): String {
        if (hostOf(url) == null || page.length > 16384) return ""
        return runCatching { AdBlock.prunePlaylist(text, url, page) }.getOrNull().orEmpty()
    }

    @JavascriptInterface
    fun scripts(url: String): String {
        val host = hostOf(url) ?: return ""
        return Cosmetic.page(host, AdBlock.pageFlags(url))?.scripts?.joinToString(",").orEmpty()
    }

    @JavascriptInterface
    fun note(url: String, method: String, kind: String) = hints.note(url, method, kind)

    private fun hostOf(url: String): String? {
        if (url.length > 16384 || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        return FilterRequest(url).host.ifEmpty { null }
    }
}
