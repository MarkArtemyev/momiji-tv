package app.momiji.tv.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

class KeyServer(private val prefs: AgentPrefs, private val onSaved: () -> Unit) {

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(isDaemon = true, name = "keyserver") {
            try {
                val socket = ServerSocket(PORT)
                server = socket
                while (!socket.isClosed) {
                    val client = socket.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (e: IOException) {
            } finally {
                server = null
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (e: IOException) {
        }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (length > 0) String(readBytes(input, minOf(length, 64 * 1024)), Charsets.UTF_8) else ""
            val page = when {
                method == "GET" && (path == "/" || path.startsWith("/?")) -> form()
                method == "POST" && path == "/save" -> {
                    save(parse(body))
                    onSaved()
                    done()
                }
                else -> null
            }
            val bytes = (page ?: "Not found").toByteArray(Charsets.UTF_8)
            val status = if (page != null) "200 OK" else "404 Not Found"
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toChar() == end[matched]) matched + 1 else if (b.toChar() == '\r') 1 else 0
            if (matched == 4) return buf.toString("UTF-8")
        }
        return null
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return data.copyOf(read)
    }

    private fun parse(body: String): Map<String, String> = body.split('&')
        .filter { '=' in it }
        .associate { pair ->
            val (k, v) = pair.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun save(fields: Map<String, String>) {
        fields["key"]?.trim()?.takeIf { it.isNotEmpty() }?.let { prefs.key = it }
        fields["model"]?.let { prefs.model = it }
        fields["fallback"]?.let { prefs.fallback = it }
        fields["thinking"]?.takeIf { it in LEVELS }?.let { prefs.thinking = it }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun form(): String {
        val hint = if (prefs.key.isBlank()) "вставь ключ" else "ключ задан, оставь пустым, чтобы не менять"
        val options = LEVELS.joinToString("") { level ->
            val selected = if (level == prefs.thinking) " selected" else ""
            "<option$selected>$level</option>"
        }
        return """<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Momiji · пульт ИИ</title><style>$STYLE</style>
<form method="post" action="/save"><h1>🍁 Пульт ИИ</h1><p>Ключ хранится только на телевизоре.</p>
<label>Ключ Gemini API</label><input name="key" type="password" autocomplete="off" placeholder="${escape(hint)}">
<label>Модель</label><input name="model" value="${escape(prefs.model)}">
<label>Запасные модели через запятую, по очереди (пусто, чтобы отключить)</label><input name="fallback" value="${escape(prefs.fallback)}">
<label>Насколько думать</label><select name="thinking">$options</select>
<button>Сохранить</button></form></html>"""
    }

    private fun done() = """<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Momiji · пульт ИИ</title><style>$STYLE</style><form><h1>🍁 Сохранил</h1><p>Можно возвращаться к телевизору.</p></form></html>"""

    companion object {
        const val PORT = 8790
        private val LEVELS = listOf("minimal", "low", "medium", "high")
        private const val STYLE = "body{margin:0;min-height:100vh;display:grid;place-items:center;background:#1b1020;color:#fff3e0;" +
            "font:16px system-ui,sans-serif}form{width:min(440px,92vw);padding:28px;border-radius:20px;background:#2a1828;" +
            "border:1px solid #ffb34766}h1{margin:0 0 6px;font-size:24px}p{margin:0 0 18px;opacity:.75}" +
            "label{display:block;margin:14px 0 6px;font-size:14px;opacity:.85}input,select{width:100%;box-sizing:border-box;" +
            "padding:12px 14px;border-radius:12px;border:1px solid #ffb34755;background:#1b1020;color:inherit;font:inherit}" +
            "button{margin-top:20px;width:100%;padding:13px;border:0;border-radius:12px;" +
            "background:linear-gradient(90deg,#ffb347,#ff7a3d);color:#2a140c;font:700 16px system-ui}"
    }
}
