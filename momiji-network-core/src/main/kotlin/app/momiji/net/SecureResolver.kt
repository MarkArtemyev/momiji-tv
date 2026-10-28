package app.momiji.net

import org.json.JSONObject
import java.net.*

/** Explicit, opt-in encrypted DNS. An unavailable selected resolver does not fall back to system DNS. */
class SecureResolver(private val provider: String) {
    private data class Entry(val addresses: List<InetAddress>,val expires: Long)
    private val cache=object: LinkedHashMap<String,Entry>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Entry>?)=size>128
    }
    fun resolve(host: String): List<InetAddress> {
        val key=host.removePrefix("[").removeSuffix("]").lowercase()
        if(provider=="SYSTEM" || key.contains(':') || key.matches(Regex("[0-9.]+"))) return InetAddress.getAllByName(key).toList()
        synchronized(cache) { cache[key]?.takeIf { it.expires>System.currentTimeMillis() }?.let { return it.addresses } }
        val endpoint=when(provider) { "CLOUDFLARE"->"https://1.1.1.1/dns-query"; "GOOGLE"->"https://dns.google/resolve"; else->error("Unknown DNS provider") }
        val answers=ArrayList<InetAddress>(); var ttl=300
        for(type in listOf(1,28)) {
            val url=URL("$endpoint?name=${URLEncoder.encode(IDN.toASCII(key),"UTF-8")}&type=$type")
            val conn=url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout=5000; conn.readTimeout=5000; conn.instanceFollowRedirects=false
            conn.setRequestProperty("Accept","application/dns-json")
            try {
                require(conn.responseCode==200) { "Secure DNS unavailable" }
                val data=conn.inputStream.use { input -> val bytes=input.readBytesBounded(65536); JSONObject(bytes.toString(Charsets.UTF_8)) }
                require(data.optInt("Status",-1)==0) { "Secure DNS returned no answer" }
                val records=data.optJSONArray("Answer")
                for(i in 0 until (records?.length() ?: 0)) {
                    val record=records!!.getJSONObject(i); val value=record.optString("data")
                    if(record.optInt("type")==type && ((type==1 && value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) ||
                        (type==28 && value.contains(':') && value.matches(Regex("[a-fA-F0-9:]+"))))) {
                        answers.add(InetAddress.getByName(value)); ttl=minOf(ttl,record.optInt("TTL",60))
                    }
                }
            } finally { conn.disconnect() }
            if(answers.isNotEmpty()) break // Ask for AAAA only if no IPv4 answer exists.
        }
        require(answers.isNotEmpty()) { "Secure DNS returned no addresses" }
        val result=answers.distinct().take(6)
        synchronized(cache) { cache[key]=Entry(result,System.currentTimeMillis()+ttl.coerceIn(30,300)*1000L) }
        return result
    }
}

fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output=java.io.ByteArrayOutputStream(); val chunk=ByteArray(8192)
    while(true) { val n=read(chunk); if(n<0) break; require(output.size()+n<=limit) { "Response exceeds limit" }; output.write(chunk,0,n) }
    return output.toByteArray()
}
