package app.momiji.tv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.momiji.net.*
import java.io.*
import java.net.*
import java.util.zip.GZIPInputStream

/** Used only for an explicitly selected local bypass. Normal playback keeps HttpEngine/HTTP3. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProxyMediaDataSource(private val context: Context,private val defaults: Map<String,String>) : BaseDataSource(true),HttpDataSource {
    class Factory(private val context: Context): HttpDataSource.Factory {
        private var properties=emptyMap<String,String>()
        override fun setDefaultRequestProperties(defaultRequestProperties: Map<String,String>): Factory { properties=defaultRequestProperties.toMap(); return this }
        override fun createDataSource(): HttpDataSource=ProxyMediaDataSource(context,properties)
    }
    private val properties=HashMap<String,String>()
    private var connection: HttpURLConnection?=null
    private var stream: InputStream?=null
    private var resolved: Uri?=null
    private var remaining=C.LENGTH_UNSET.toLong()
    private var started=false
    private var code=-1
    override fun setRequestProperty(name: String,value: String) { properties[name]=value }
    override fun clearRequestProperty(name: String) { properties.remove(name) }
    override fun clearAllRequestProperties() { properties.clear() }
    override fun getUri()=resolved
    override fun getResponseCode()=code
    override fun getResponseHeaders(): Map<String,List<String>> = connection?.headerFields?.filterKeys { it!=null }.orEmpty()
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        var url=URL(dataSpec.uri.toString()); var method=dataSpec.httpMethodString; var body=dataSpec.httpBody
        val headers=HashMap(defaults).apply { putAll(properties); putAll(dataSpec.httpRequestHeaders) }
        try {
            repeat(11) { attempt ->
                if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
                if(Store(context).adBlock && AdBlock.blocks(FilterRequest(url.toString(),headers["Referer"].orEmpty(),ResourceType.MEDIA))) throw IOException("Ресурс заблокирован фильтром Momiji")
                require(url.protocol in setOf("http","https") && url.userInfo==null)
                val conn=NetworkRoute.open(url); connection=conn
                conn.connectTimeout=12000; conn.readTimeout=20000; conn.instanceFollowRedirects=false; conn.requestMethod=method
                headers.forEach { (key,value) -> conn.setRequestProperty(key,value) }
                conn.setRequestProperty("Accept-Encoding","identity")
                if(dataSpec.position!=0L || dataSpec.length!=C.LENGTH_UNSET.toLong()) conn.setRequestProperty("Range","bytes=${dataSpec.position}-"+
                    if(dataSpec.length==C.LENGTH_UNSET.toLong()) "" else (dataSpec.position+dataSpec.length-1).toString())
                if(body!=null) { conn.doOutput=true; conn.setFixedLengthStreamingMode(body!!.size); conn.outputStream.use { it.write(body!!) } }
                code=conn.responseCode
                if(code in setOf(301,302,303,307,308)) {
                    require(attempt<10); val next=URL(url,conn.getHeaderField("Location") ?: error("Missing redirect"))
                    require(!(url.protocol=="https" && next.protocol!="https")) { "Insecure media redirect" }
                    if(url.protocol!=next.protocol || url.host!=next.host || url.port!=next.port) {
                        headers.keys.filter { it.equals("Authorization",true) || it.equals("Cookie",true) }.toList().forEach(headers::remove)
                    }
                    if(code==303 || code in setOf(301,302) && method=="POST") { method="GET"; body=null }
                    conn.disconnect(); connection=null; url=next
                } else {
                    if(code==416 && conn.getHeaderField("Content-Range")?.substringAfter("*/","")?.toLongOrNull()==dataSpec.position) {
                        resolved=Uri.parse(url.toString()); stream=ByteArrayInputStream(ByteArray(0)); remaining=0
                        transferStarted(dataSpec); started=true; return 0
                    }
                    if(code !in 200..299) throw IOException("HTTP $code")
                    if(code==206) {
                        val start=Regex("bytes (\\d+)-\\d+/.*").matchEntire(conn.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                        if(start!=dataSpec.position) throw IOException("Incorrect Content-Range")
                    }
                    resolved=Uri.parse(url.toString())
                    val compressed=conn.contentEncoding?.equals("gzip",true)==true
                    stream=if(compressed) GZIPInputStream(conn.inputStream) else conn.inputStream
                    val skip=if(code==200) dataSpec.position else 0L
                    remaining=if(dataSpec.length!=C.LENGTH_UNSET.toLong()) dataSpec.length else if(compressed || conn.contentLengthLong<0) C.LENGTH_UNSET.toLong() else (conn.contentLengthLong-skip).coerceAtLeast(0)
                    transferStarted(dataSpec); started=true
                    var left=skip; val buffer=ByteArray(16384)
                    while(left>0) { if(Thread.currentThread().isInterrupted) throw InterruptedIOException()
                        val n=stream!!.read(buffer,0,minOf(left,buffer.size.toLong()).toInt()); if(n<0) throw EOFException(); left-=n; bytesTransferred(n) }
                    return remaining
                }
            }
            throw IOException("Too many redirects")
        } catch(error: Exception) { close(); throw if(error is IOException) error else IOException(error) }
    }
    override fun read(buffer: ByteArray,offset: Int,length: Int): Int {
        if(length==0) return 0
        if(remaining==0L) return C.RESULT_END_OF_INPUT
        val n=stream?.read(buffer,offset,if(remaining<0) length else minOf(length.toLong(),remaining).toInt()) ?: C.RESULT_END_OF_INPUT
        if(n<0) { if(remaining>0) throw EOFException(); return C.RESULT_END_OF_INPUT }
        if(remaining>=0) remaining-=n
        bytesTransferred(n); return n
    }
    override fun close() {
        try { stream?.close() } finally {
            stream=null; connection?.disconnect(); connection=null; resolved=null
            if(started) { started=false; transferEnded() }
        }
    }
}
