package app.momiji.tv

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.ByteArrayInputStream

/**
 * AdGuard's stand-in resources (assets/protection/vendor/redirects.json): no-op ad SDKs, empty VAST,
 * a one-second clip, transparent pixels. A blocked request gets one of these instead of a network
 * error, so players and pages carry on as if the ad simply had nothing to show.
 */
object RedirectResources {
    private class Resource(val mime: String, val charset: String?, val body: ByteArray)

    @Volatile private var resources: Map<String, Resource> = emptyMap()

    fun init(context: Context) {
        if (resources.isNotEmpty()) return
        synchronized(this) {
            if (resources.isNotEmpty()) return
            resources = runCatching { load(context.applicationContext) }
                .onFailure { android.util.Log.e("MomijiFilters", "Redirect resources unavailable", it) }
                .getOrDefault(emptyMap())
        }
    }

    private fun load(context: Context): Map<String, Resource> {
        val text = context.assets.open("protection/vendor/redirects.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val map = HashMap<String, Resource>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("contentType")
            val base64 = type.endsWith(";base64")
            val mime = type.removeSuffix(";base64")
            val content = item.getString("content")
            val body = if (base64) Base64.decode(content, Base64.DEFAULT) else content.toByteArray(Charsets.UTF_8)
            val textual = mime.startsWith("text/") || "javascript" in mime || "json" in mime || "xml" in mime
            val resource = Resource(mime, if (textual) "utf-8" else null, body)
            map[item.getString("title")] = resource
            val aliases = item.optJSONArray("aliases") ?: JSONArray()
            for (j in 0 until aliases.length()) map[aliases.getString(j)] = resource
        }
        return map
    }

    fun has(name: String) = name in resources

    fun response(name: String, request: WebResourceRequest): WebResourceResponse? {
        val resource = resources[name] ?: return null
        val headers = HashMap<String, String>()
        // fetch/XHR callers may only read a cross-origin stand-in when CORS allows it.
        val origin = request.requestHeaders.entries.firstOrNull { it.key.equals("Origin", true) }?.value
        if (origin != null && origin != "null") {
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Credentials"] = "true"
        } else headers["Access-Control-Allow-Origin"] = "*"
        headers["Cache-Control"] = "no-store"
        return WebResourceResponse(resource.mime, resource.charset, 200, "OK", headers, ByteArrayInputStream(resource.body))
    }
}
