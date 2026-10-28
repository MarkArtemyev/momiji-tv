package app.momiji.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional LAN input, limited to the foreground Momiji activity. No Accessibility or ADB. */
object RemoteControl {
    const val PORT = 8791
    interface Target { fun remoteInput(events: JSONArray) }
    data class Pairing(val urls: List<String>, val code: String)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val lock = Any()
    @Volatile private var target: Target? = null
    @Volatile private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var code = ""
    private var codeUntil = 0L
    private var token = ""
    private var pairedIp = ""
    private var lastInput = 0L
    private var lastDispatch = 0L
    private var tries = 0
    private var triesAt = 0L
    private var allowedHosts = emptySet<String>()
    private var pages = emptyMap<String, Pair<String, ByteArray>>()
    private val idleStop = Runnable { if (target == null) stop() }

    fun attach(receiver: Target) { main.removeCallbacks(idleStop); target = receiver }
    fun detach(receiver: Target) {
        if (target === receiver) target = null
        main.postDelayed(idleStop, 30_000)
    }

    fun start(context: Context): Pairing = synchronized(lock) {
        val ips = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
        require(ips.isNotEmpty()) { "Подключи телевизор и ПК к одной сети Wi-Fi или Ethernet." }
        if (server == null) {
            val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) }
            allowedHosts = (ips.map { "$it:$PORT" } + "127.0.0.1:$PORT" + "localhost:$PORT").toSet()
            pages = mapOf("/" to ("text/html" to context.assets.open("remote/index.html").use { it.readBytes() }),
                "/app.js" to ("text/javascript" to context.assets.open("remote/app.js").use { it.readBytes() }),
                "/style.css" to ("text/css" to context.assets.open("remote/style.css").use { it.readBytes() }))
            val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
                { r -> Thread(r, "remote-client").apply { isDaemon = true } })
            pool = workers; server = listener
            Thread({
                try {
                    while (!listener.isClosed) {
                        val client = listener.accept(); clients.add(client)
                        try { workers.execute { handle(client) } } catch (_: Exception) { clients.remove(client); client.close() }
                    }
                } catch (_: Exception) { /* Closing the listener stops accept. */ }
            }, "remote-listener").apply { isDaemon = true }.start()
        }
        code = (100_000 + random.nextInt(900_000)).toString()
        codeUntil = SystemClock.elapsedRealtime() + 5 * 60_000
        token = ""; pairedIp = ""; tries = 0
        Pairing(ips.map { "http://$it:$PORT" }, code)
    }

    fun stop() = synchronized(lock) {
        try { server?.close() } catch (_: Exception) {}
        server = null
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        pool?.shutdownNow(); pool = null
        token = ""; code = ""; pairedIp = ""
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 5000
                val input = BufferedInputStream(socket.getInputStream())
                repeat(200) {
                    val head = readHead(input) ?: return
                    val lines = head.split("\r\n")
                    val request = lines[0].split(' ')
                    if (request.size != 3) return
                    val method = request[0]; val path = request[1]
                    val headers = lines.drop(1).filter { ':' in it }.associate {
                        it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                    }
                    val host = headers["host"]
                    if (host !in allowedHosts || headers.containsKey("transfer-encoding")) { reply(socket, 403, "Forbidden"); return }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length !in 0..32768) { reply(socket, 413, "Too large"); return }
                    val body = ByteArray(length); var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); if (n < 0) return; offset += n }
                    val page = pages[path]
                    if (method == "GET" && page != null) reply(socket, 200, page.second, page.first)
                    else if (method == "POST" && headers["origin"] == "http://$host") {
                        val data = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { reply(socket, 400, "Invalid request"); return }
                        val ip = socket.inetAddress.hostAddress.orEmpty()
                        when (path) {
                            "/pair" -> pair(socket, ip, data.optString("code"))
                            "/input" -> input(socket, ip, data)
                            "/disconnect" -> synchronized(lock) {
                                if (ip == pairedIp && token.isNotEmpty() && equal(token, data.optString("token"))) {
                                    token = ""; pairedIp = ""
                                    reply(socket, 204, "")
                                } else reply(socket, 401, "Unauthorized")
                            }
                            else -> reply(socket, 404, "Not found")
                        }
                    } else reply(socket, 403, "Forbidden")
                    if (headers["connection"].equals("close", true)) return
                }
            }
        } catch (_: Exception) { /* Disconnected or idle peer. */ }
        finally { clients.remove(client) }
    }

    private fun pair(socket: Socket, ip: String, supplied: String) = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (now - triesAt > 30_000) { tries = 0; triesAt = now }
        if (++tries > 5) { reply(socket, 429, "Подожди 30 секунд"); return@synchronized }
        if (target == null || now > codeUntil || code.isEmpty() || !equal(code, supplied)) {
            reply(socket, 403, "Проверь код на телевизоре"); return@synchronized
        }
        token = ByteArray(32).also(random::nextBytes).let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }
        pairedIp = ip; lastInput = now; code = ""
        reply(socket, 200, JSONObject().put("token", token).toString(), "application/json")
    }

    private fun input(socket: Socket, ip: String, data: JSONObject) {
        val receiver: Target
        val events = data.optJSONArray("events") ?: JSONArray()
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (token.isEmpty() || ip != pairedIp || now - lastInput > 30 * 60_000 || !equal(token, data.optString("token"))) {
                reply(socket, 401, "Подключись заново"); return
            }
            receiver = target ?: run { reply(socket, 409, "Открой Momiji на телевизоре"); return }
            if (events.length() > 20 || now - lastDispatch < 15) { reply(socket, 429, "Too fast"); return }
            lastDispatch = now; lastInput = now
        }
        main.post { if (target === receiver) receiver.remoteInput(events) }
        reply(socket, 204, "")
    }

    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun readHead(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (bytes.size() < 16_384) {
            val c = input.read(); if (c < 0) return null
            bytes.write(c)
            matched = if (c.toChar() == end[matched]) matched + 1 else if (c == 13) 1 else 0
            if (matched == 4) return bytes.toString("UTF-8")
        }
        return null
    }

    private fun reply(socket: Socket, code: Int, body: String, type: String = "text/plain") = reply(socket, code, body.toByteArray(Charsets.UTF_8), type)
    private fun reply(socket: Socket, code: Int, body: ByteArray, type: String) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; else -> "Too Many Requests" }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\nConnection: keep-alive\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.UTF_8)); write(body); flush() }
    }
}
