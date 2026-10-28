import app.momiji.net.*
import java.net.*
import java.io.*
import java.security.KeyStore
import javax.net.ssl.*
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import com.sun.net.httpserver.HttpServer

fun main(args: Array<String>) {
    val results=ArrayList<String>()
    fun test(name: String,action: ()->Unit) { action(); results.add(name); println("PASS $name") }
    val suffix=PublicSuffixes("com\nco.uk\ngithub.io\n*.kawasaki.jp\n!city.kawasaki.jp")
    test("public_suffix_private_wildcard_and_exception") {
        check(suffix.site("a.foo.co.uk")=="foo.co.uk"); check(suffix.site("a.github.io")!="b.github.io")
        check(suffix.site("a.city.kawasaki.jp")=="city.kawasaki.jp")
    }
    val rules=RequestRules(listOf("||ads.example^","@@||ads.example/content/","||ads.example/forced*\$important",
        "@@||ads.example/forced-safe\$important","||cdn.example/promo/*\$script,third-party,domain=site.co.uk|~safe.site.co.uk",
        "||cdn.example/exact.js\$script,match-case","||disabled.example^\$image,third-party",
        "||disabled.example^\$third-party,image,badfilter","/ad-banners/*\$image", "||unsupported.example^\$redirect=noopjs",
        "@@||unfiltered.example^\$document","||disabled.example^\$font"),suffix)
    fun block(url: String,document: String="https://www.site.co.uk/",type: ResourceType=ResourceType.SCRIPT)=rules.blocks(FilterRequest(url,document,type))
    test("host_boundary_and_url_exceptions") { check(block("https://sub.ads.example/ad.js")); check(!block("https://ads.example.evil.test/ad.js")); check(!block("https://ads.example/content/play.js")) }
    test("important_priority") { check(block("https://ads.example/forced-ad.js")); check(!block("https://ads.example/forced-safe.js")) }
    test("typed_party_positive_negative_domains") {
        check(block("https://cdn.example/promo/ad.js")); check(!block("https://cdn.example/promo/ad.png",type=ResourceType.IMAGE))
        check(!block("https://cdn.example/promo/ad.js","https://safe.site.co.uk/")); check(!block("https://cdn.example/promo/ad.js","https://cdn.example/"))
    }
    test("case_badfilter_and_unsupported_modifiers") {
        check(block("https://cdn.example/exact.js")); check(!block("https://cdn.example/EXACT.js"))
        check(!block("https://disabled.example/a.png",type=ResourceType.IMAGE)); check(block("https://disabled.example/a.woff",type=ResourceType.FONT))
        check(!block("https://unsupported.example/a.js"))
    }
    test("generic_path_and_document_exception") {
        check(block("https://safe.example/ad-banners/foo.png",type=ResourceType.IMAGE))
        check(!block("https://ads.example/ad.js","https://unfiltered.example/"))
    }
    test("separator_and_non_exponential_wildcards") {
        check(RequestRules.glob("|https://x|","https://x")); check(RequestRules.glob("ad^","ad/",true)); check(!RequestRules.glob("ad^","adverts",true))
        val time=System.nanoTime(); repeat(100) { check(!RequestRules.glob("*a*a*a*a*a*a*b|","a".repeat(8192))) }; check(System.nanoTime()-time<2_000_000_000)
    }
    test("compiled_rules_preserve_priority_and_context") {
        val bytes=ByteArrayOutputStream(); rules.write(bytes)
        val restored=RequestRules(bytes.toByteArray().inputStream(),suffix)
        val samples=listOf(FilterRequest("https://ads.example/content/normal.js","https://site.co.uk",ResourceType.SCRIPT),
            FilterRequest("https://ads.example/forced-safe.js","https://site.co.uk",ResourceType.SCRIPT),
            FilterRequest("https://cdn.example/promo/ad.js","https://site.co.uk",ResourceType.SCRIPT),
            FilterRequest("https://disabled.example/font.woff","https://site.co.uk",ResourceType.FONT))
        for(sample in samples) check(restored.decision(sample)==rules.decision(sample))
    }
    test("strongest_decision_priority_and_exception_ties") {
        val block=RuleDecision(true,1); val allow=RuleDecision(false,2); val forced=RuleDecision(true,3); val forcedAllow=RuleDecision(false,4)
        check(RuleDecision.stronger(null,null)==null); check(RuleDecision.stronger(block,null)==block); check(RuleDecision.stronger(null,allow)==allow)
        check(RuleDecision.stronger(block,allow)==allow); check(RuleDecision.stronger(allow,block)==allow)
        check(RuleDecision.stronger(allow,forced)==forced); check(RuleDecision.stronger(forced,forcedAllow)==forcedAllow)
        // An exception wins a tie from either side; equal blocks stay blocked.
        val tiedAllow=RuleDecision(false,1)
        check(RuleDecision.stronger(block,tiedAllow)==tiedAllow); check(RuleDecision.stronger(tiedAllow,block)==tiedAllow)
        check(RuleDecision.stronger(block,RuleDecision(true,1))?.blocked==true)
    }
    test("base_custom_and_domain_layers_merge_like_the_browser") {
        // Mirrors AdBlock.blocks: base and custom page rules first, then the DNS domain table.
        val base=RequestRules(listOf("||tracker.example^","||cdn.example/ads/*"),suffix)
        val custom=RequestRules(listOf("@@||tracker.example/consent.js","||cdn.example/extra.js\$important"),suffix)
        val table=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { data ->
            val blocked=listOf("dns-only.example","cdn.example").map { DomainTable.hash(it) }.sorted(); val allowed=listOf("ok.dns-only.example").map { DomainTable.hash(it) }.sorted()
            data.writeInt(0x4d444e31); data.writeInt(blocked.size); data.writeInt(allowed.size); blocked.forEach(data::writeLong); allowed.forEach(data::writeLong)
        } }.toByteArray().let { DomainTable(it.inputStream()) }
        fun merged(url: String): Boolean {
            val request=FilterRequest(url,"https://www.site.co.uk/",ResourceType.SCRIPT)
            val contextual=RuleDecision.stronger(base.decision(request),custom.decision(request))
            return RuleDecision.stronger(contextual,table.decision(request.host))?.blocked==true
        }
        check(merged("https://tracker.example/pixel.js")); check(!merged("https://tracker.example/consent.js"))
        check(merged("https://dns-only.example/a.js")); check(!merged("https://ok.dns-only.example/a.js"))
        check(merged("https://cdn.example/extra.js")); check(!merged("https://unrelated.example/app.js"))
        // A page exception (priority 2) beats a plain domain-table block (priority 1).
        val unblock=RequestRules(listOf("@@||cdn.example/lib/"),suffix)
        val lib=FilterRequest("https://cdn.example/lib/app.js","https://www.site.co.uk/",ResourceType.SCRIPT)
        check(RuleDecision.stronger(unblock.decision(lib),table.decision(lib.host))?.blocked==false)
    }
    test("multi_record_hello_fragmentation_preserves_every_handshake_byte") {
        fun u16(n: Int)=byteArrayOf((n ushr 8).toByte(),n.toByte())
        val host="fixture.momiji.test".toByteArray()
        val sni=u16(host.size+3)+byteArrayOf(0)+u16(host.size)+host
        val ext=u16(0)+u16(sni.size)+sni
        val body=byteArrayOf(3,3)+ByteArray(32)+byteArrayOf(0)+u16(2)+byteArrayOf(0x13,1)+byteArrayOf(1,0)+u16(ext.size)+ext
        val hello=byteArrayOf(1,0,(body.size ushr 8).toByte(),body.size.toByte())+body
        fun record(bytes: ByteArray)=byteArrayOf(22,3,3)+u16(bytes.size)+bytes
        val wire=record(hello.copyOfRange(0,hello.size-4))+record(hello.copyOfRange(hello.size-4,hello.size))
        val result=TlsHello.read(wire.inputStream()); check(result.original.contentEquals(wire))
        val input=DataInputStream(result.fragmented.inputStream()); val payload=ByteArrayOutputStream(); var records=0
        while(input.available()>0) { check(input.readUnsignedByte()==22); input.readUnsignedShort(); val n=input.readUnsignedShort(); val data=ByteArray(n); input.readFully(data); payload.write(data); records++ }
        check(records>=2 && payload.toByteArray().contentEquals(hello))
    }
    test("malformed_tls_lengths_fail_with_bounded_input") {
        check(runCatching { TlsHello.read(byteArrayOf(22,3,3,127,127).inputStream()) }.isFailure)
        val input=byteArrayOf(71,69,84).inputStream(); val first=TlsHello.read(input)
        check(first.original.contentEquals(byteArrayOf(71)) && input.read()==69)
    }
    val store=KeyStore.getInstance("PKCS12"); File(args[0]).inputStream().use { store.load(it,"momiji-test".toCharArray()) }
    val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"momiji-test".toCharArray()) }
    val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    val ssl=SSLContext.getInstance("TLS").apply { init(keys.keyManagers,trust.trustManagers,null) }
    val server=ssl.serverSocketFactory.createServerSocket(0,20,InetAddress.getByName("127.0.0.1")) as SSLServerSocket
    val payload=ByteArray(2*1024*1024) { (it*31).toByte() }
    val handlers=Executors.newFixedThreadPool(4) { r -> Thread(r,"Fixture-SSL").apply { isDaemon=true } }
    fun line(input: InputStream): String { val out=ByteArrayOutputStream(); while(true) { val b=input.read(); if(b<0 || b==10) break; if(b!=13) out.write(b) }; return out.toString() }
    Thread({ while(!server.isClosed) { try { val client=server.accept(); handlers.execute { runCatching { client.use { socket ->
        socket.soTimeout=10000; val input=socket.getInputStream(); line(input); while(line(input).isNotEmpty()) {}
        val out=socket.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(payload); out.flush()
    } } } } catch(_: Exception) {} } },"Fixture-Accept").apply { isDaemon=true; start() }
    fun https(proxy: LoopbackProxy) {
        val conn=URL("https://fixture.momiji.test:${server.localPort}/video.mp4").openConnection(proxy.proxy) as HttpsURLConnection
        conn.sslSocketFactory=ssl.socketFactory; conn.connectTimeout=10000; conn.readTimeout=10000
        try { check(conn.responseCode==200); check(conn.inputStream.use { it.readBytes() }.contentEquals(payload)) } finally { conn.disconnect() }
    }
    try {
        for(mode in listOf("OFF","TLS","TCP","TLS_TCP")) test("real_validated_tls_${mode}_large_duplex_relay") {
            LoopbackProxy(NetworkConfig(mode),{ listOf(InetAddress.getByName("127.0.0.1")) }).use { https(it) }
        }
        test("tls_certificate_validation_is_not_bypassed") {
            LoopbackProxy(NetworkConfig("TLS"),{ listOf(InetAddress.getByName("127.0.0.1")) }).use { proxy ->
                val conn=URL("https://wrong-host.invalid:${server.localPort}/").openConnection(proxy.proxy) as HttpsURLConnection
                conn.sslSocketFactory=ssl.socketFactory; conn.readTimeout=5000
                check(runCatching { conn.inputStream.use { it.read() } }.isFailure); conn.disconnect()
            }
        }
        test("http_proxy_preserves_post_body_and_range") {
            val http=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
            http.createContext("/") { exchange -> val body=exchange.requestBody.readBytes()
                val result="${exchange.requestMethod}|${exchange.requestURI}|${exchange.requestHeaders.getFirst("Range")}|${body.toString(Charsets.UTF_8)}".toByteArray()
                exchange.responseHeaders.add("Content-Range","bytes 10-19/100"); exchange.sendResponseHeaders(206,result.size.toLong()); exchange.responseBody.use { it.write(result) } }
            http.start()
            try { LoopbackProxy(NetworkConfig("TLS")).use { proxy ->
                val conn=URL("http://127.0.0.1:${http.address.port}/file?a=1").openConnection(proxy.proxy) as HttpURLConnection
                conn.requestMethod="POST"; conn.setRequestProperty("Range","bytes=10-19"); conn.doOutput=true
                conn.outputStream.use { it.write("payload".toByteArray()) }
                check(conn.responseCode==206); check(conn.inputStream.bufferedReader().readText()=="POST|/file?a=1|bytes=10-19|payload"); conn.disconnect()
            } } finally { http.stop(0) }
        }
        test("socks5_forwarding_preserves_tls") {
            val socks=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
            Thread({ val client=socks.accept(); client.use { c -> val input=DataInputStream(c.getInputStream()); val output=c.getOutputStream()
                check(input.readUnsignedByte()==5); val n=input.readUnsignedByte(); repeat(n) { input.readByte() }; output.write(byteArrayOf(5,0))
                check(input.readUnsignedByte()==5 && input.readUnsignedByte()==1 && input.readUnsignedByte()==0 && input.readUnsignedByte()==3)
                val host=ByteArray(input.readUnsignedByte()); input.readFully(host); check(host.toString(Charsets.US_ASCII)=="fixture.momiji.test"); val port=input.readUnsignedShort(); check(port==server.localPort)
                Socket("127.0.0.1",port).use { destination -> output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0))
                    val upload=Thread({ runCatching { input.copyTo(destination.getOutputStream()); destination.shutdownOutput() } },"Fixture-SocksUp").apply { isDaemon=true; start() }
                    destination.getInputStream().copyTo(output); upload.join(1000)
                }
            } },"Fixture-Socks").apply { isDaemon=true; start() }
            try { LoopbackProxy(NetworkConfig("SOCKS5",socksPort=socks.localPort)).use { https(it) } } finally { socks.close() }
        }
        test("bounded_connections_and_shutdown") {
            val proxy=LoopbackProxy(NetworkConfig("TLS")); val clients=(1..30).map { Socket("127.0.0.1",proxy.port) }
            Thread.sleep(150); check(proxy.connections<=24); proxy.close(); clients.forEach { it.close() }
        }
    } finally { server.close(); handlers.shutdownNow() }
    println("NETWORK_CORE_PASSED=${results.size}")
}
