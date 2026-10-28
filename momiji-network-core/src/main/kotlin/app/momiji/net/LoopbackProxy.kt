package app.momiji.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class NetworkConfig(val mode: String="OFF",val dns: String="SYSTEM",val socksHost: String="127.0.0.1",val socksPort: Int=1080) {
    val active get()=mode!="OFF" || dns!="SYSTEM"
    init { require(mode in setOf("OFF","TLS","TCP","TLS_TCP","SOCKS5")); require(dns in setOf("SYSTEM","CLOUDFLARE","GOOGLE")); require(socksPort in 1..65535); require(socksHost.length in 1..253 && socksHost.none { it.isWhitespace() || it in "/@?#" }) }
}

/** Loopback-only proxy. At most 24 tunnels, bounded transfer workers and buffers; idle workers expire. */
class LoopbackProxy(val config: NetworkConfig,private val resolve: (String)->List<InetAddress> = { InetAddress.getAllByName(it).toList() }) : AutoCloseable {
    private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
    val port get()=server.localPort
    val proxy: Proxy get()=Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",port))
    private val closed=AtomicBoolean()
    private val count=AtomicInteger()
    val connections get()=count.get()
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    private val workers=ThreadPoolExecutor(2,4,15,TimeUnit.SECONDS,ArrayBlockingQueue<Runnable>(16),
        { r -> Thread(r,"Momiji-Handshake").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private class TransferState {
        val remaining=AtomicInteger(2)
        @Volatile var touched=System.nanoTime()
        val ended=AtomicBoolean()
    }
    private val relayWorkers=ThreadPoolExecutor(0,48,15,TimeUnit.SECONDS,SynchronousQueue<Runnable>(),
        { r -> Thread(r,"Momiji-Transfer").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
    init { Thread({ relay() },"Momiji-Tunnels").apply { isDaemon=true; start() } }
    private fun line(input: InputStream): String {
        val bytes=ByteArrayOutputStream()
        while(bytes.size()<8192) { val b=input.read(); if(b<0) error("Incomplete proxy request")
            if(b==10) return bytes.toString("ISO-8859-1").removeSuffix("\r")
            require(b!=0); bytes.write(b)
        }; error("Proxy line too long")
    }
    private fun establish(client: Socket) {
        var upstream: Socket?=null; var handedOff=false; var replied=false
        try {
            client.soTimeout=8000; client.tcpNoDelay=true
            val input=client.getInputStream(); val request=line(input).split(' ')
            require(request.size==3 && request[2] in setOf("HTTP/1.0","HTTP/1.1"))
            val headers=ArrayList<String>(); var total=0
            while(true) { val h=line(input); if(h.isEmpty()) break; total+=h.length; require(total<=32768 && headers.size<100); require(h.contains(':')); headers.add(h) }
            val connect=request[0]=="CONNECT"
            val uri=if(connect) URI("https://${request[1]}") else URI(request[1])
            require(uri.userInfo==null && uri.fragment==null && (connect || uri.scheme=="http"))
            val host=uri.host ?: error("Missing host"); val targetPort=if(uri.port>=0) uri.port else if(connect) 443 else 80
            require(targetPort in 1..65535 && !(host in setOf("localhost","127.0.0.1","[::1]","::1") && targetPort==port))
            upstream=Socket(); sockets.add(upstream)
            upstream.tcpNoDelay=true; upstream.soTimeout=8000
            if(config.mode=="SOCKS5") {
                upstream.connect(InetSocketAddress(config.socksHost,config.socksPort),6000)
                socks(upstream,host,targetPort)
            } else {
                val addresses=resolve(host); require(addresses.isNotEmpty()) { "DNS returned no addresses" }
                var failure: Exception?=null
                for(address in addresses.take(3)) {
                    try { upstream!!.connect(InetSocketAddress(address,targetPort),6000); failure=null; break }
                    catch(error: Exception) { failure=error; sockets.remove(upstream); upstream?.close(); upstream=Socket(); sockets.add(upstream); upstream.tcpNoDelay=true; upstream.soTimeout=8000 }
                }
                failure?.let { throw it }
            }
            val output=upstream.getOutputStream()
            if(connect) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); replied=true
                if(config.mode in setOf("TLS","TCP","TLS_TCP")) {
                    val hello=TlsHello.read(input)
                    val literal=host.removePrefix("[").removeSuffix("]").let { it.contains(':') || it.matches(Regex("[0-9.]+")) }
                    val recordSplit=config.mode!="TCP" && !literal && !hello.original.contentEquals(hello.fragmented)
                    val bytes=if(recordSplit) hello.fragmented else hello.original
                    if(config.mode=="TLS" && recordSplit) output.write(bytes)
                    else { val at=if(recordSplit) minOf(6,bytes.size) else hello.splitAt
                        output.write(bytes,0,at); output.flush(); Thread.sleep(12); output.write(bytes,at,bytes.size-at) }
                    output.flush()
                }
            } else {
                val path=(uri.rawPath?.ifEmpty { "/" } ?: "/")+(uri.rawQuery?.let { "?$it" } ?: "")
                val safe=headers.filter { it.substringBefore(':').lowercase() !in setOf("proxy-connection","proxy-authorization","connection","host") }
                val upgrade=headers.any { it.substringBefore(':').equals("Upgrade",true) }
                val authority=if(targetPort==80) host else "$host:$targetPort"
                output.write(("${request[0]} $path HTTP/1.1\r\nHost: $authority\r\n"+safe.joinToString("\r\n")+"\r\nConnection: ${if(upgrade) "Upgrade" else "close"}\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            }
            handedOff=true; transferPair(client,upstream)
        } catch(_: Exception) {
            if(!replied) runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        } finally {
            if(!handedOff) { sockets.remove(client); runCatching { client.close() }; upstream?.let { sockets.remove(it); runCatching { it.close() } }; count.decrementAndGet() }
        }
    }
    private fun socks(socket: Socket,host: String,port: Int) {
        val output=socket.getOutputStream(); val input=socket.getInputStream()
        output.write(byteArrayOf(5,1,0)); require(input.read()==5 && input.read()==0) { "SOCKS5 needs authentication or is unavailable" }
        val name=IDN.toASCII(host.removePrefix("[").removeSuffix("]")).toByteArray(Charsets.US_ASCII)
        require(name.size in 1..253)
        output.write(byteArrayOf(5,1,0,3,name.size.toByte())+name+byteArrayOf((port ushr 8).toByte(),port.toByte()))
        require(input.read()==5 && input.read()==0); require(input.read()==0)
        val n=when(input.read()) { 1->4; 4->16; 3->input.read().also { require(it in 1..255) }; else->error("Invalid SOCKS address") }
        repeat(n+2) { require(input.read()>=0) }
    }
    private fun transferPair(a: Socket,b: Socket) {
        val state=TransferState()
        fun finish() {
            if(state.remaining.decrementAndGet()==0 && state.ended.compareAndSet(false,true)) {
                for(channel in listOf(a,b)) { sockets.remove(channel); runCatching { channel.close() } }
                count.decrementAndGet()
            }
        }
        fun pump(source: Socket,destination: Socket) {
            try {
                source.soTimeout=2000
                val input=source.getInputStream(); val output=destination.getOutputStream()
                val buffer=ByteArray(16384)
                while(!closed.get()) {
                    val n=try { input.read(buffer) } catch(_: SocketTimeoutException) {
                        if(System.nanoTime()-state.touched>120_000_000_000L) throw SocketTimeoutException("Idle proxy tunnel") else continue
                    }
                    if(n<0) break
                    if(n>0) { output.write(buffer,0,n); state.touched=System.nanoTime() }
                }
                runCatching { destination.shutdownOutput() }
            } catch(_: Exception) { runCatching { source.close() }; runCatching { destination.close() } }
            finally { finish() }
        }
        try { relayWorkers.execute { pump(a,b) } }
        catch(_: RejectedExecutionException) { finish(); finish(); return }
        try { relayWorkers.execute { pump(b,a) } }
        catch(_: RejectedExecutionException) { runCatching { a.close() }; runCatching { b.close() }; finish() }
    }
    private fun relay() {
        try { while(!closed.get()) {
            val client=server.accept()
            if(count.incrementAndGet()>24) { count.decrementAndGet(); client.close(); continue }
            sockets.add(client)
            try { workers.execute { establish(client) } }
            catch(_: RejectedExecutionException) { sockets.remove(client); client.close(); count.decrementAndGet() }
        } } catch(_: Exception) { /* Closing the accept socket is normal shutdown. */ }
        finally { sockets.forEach { runCatching { it.close() } }; runCatching { server.close() } }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        sockets.forEach { runCatching { it.close() } }; workers.shutdownNow(); relayWorkers.shutdownNow(); runCatching { server.close() }
    }
}
