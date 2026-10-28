package app.momiji.tv

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.momiji.net.FilterRequest
import app.momiji.net.ReplaceRules
import app.momiji.net.readBytesBounded
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.charset.Charset

/**
 * AdGuard `$replace` for subresources (scripts, VAST/XML, JSON, frames): the request is fetched here
 * with the page's headers and cookies, rewritten and handed to WebView. Only GET requests that a rule
 * names are touched; anything unexpected returns null and WebView loads the original itself.
 */
object ContentRewriter {
    private const val LIMIT = 8 * 1024 * 1024
    private val SKIPPED_REQUEST = setOf("accept-encoding", "range", "host", "connection", "cookie")
    private val SKIPPED_RESPONSE = setOf("content-length", "content-encoding", "transfer-encoding", "set-cookie", "connection", "keep-alive")

    fun rewrite(request: WebResourceRequest, filter: FilterRequest): WebResourceResponse? {
        if (!request.method.equals("GET", true)) return null
        val rules = AdBlock.replaceRules(filter)
        if (rules.isEmpty()) return null
        return runCatching { fetch(request, rules) }.getOrNull()
    }

    private fun fetch(request: WebResourceRequest, rules: List<Triple<String, String, String>>): WebResourceResponse? {
        val url = URL(request.url.toString())
        if (url.protocol !in setOf("http", "https")) return null
        val connection = NetworkRoute.open(url)
        connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.instanceFollowRedirects = true
        request.requestHeaders.forEach { (name, value) -> if (name.lowercase() !in SKIPPED_REQUEST) connection.setRequestProperty(name, value) }
        val cookies = CookieManager.getInstance()
        cookies.getCookie(url.toString())?.let { connection.setRequestProperty("Cookie", it) }
        val code = connection.responseCode
        connection.headerFields["Set-Cookie"]?.forEach { cookies.setCookie(url.toString(), it) }
        // WebView never follows a redirect inside an intercepted response; let it load anything but a 2xx itself.
        if (code !in 200..299) { connection.disconnect(); return null }
        val type = connection.contentType.orEmpty()
        val mime = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim('"', ' ')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val headers = connection.headerFields.filterKeys { it != null && it.lowercase() !in SKIPPED_RESPONSE }
            .mapKeys { it.key!! }.mapValues { it.value.joinToString(", ") }
        val reason = connection.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
        val bytes = try { connection.inputStream.use { it.readBytesBounded(LIMIT) } } finally { connection.disconnect() }
        val textual = mime.startsWith("text/") || "javascript" in mime || "json" in mime || "xml" in mime || "mpegurl" in mime
        val body = if (textual) ReplaceRules.apply(String(bytes, charset), rules).toByteArray(charset) else bytes
        return WebResourceResponse(mime, if (textual) charset.name() else null, code, reason, headers, ByteArrayInputStream(body))
    }
}
