package app.momiji.net

import java.io.DataInputStream
import java.io.InputStream
import java.net.IDN
import java.util.zip.GZIPInputStream

/** Compact offline DNS filter shared by Home and Browser. No network or background threads. */
class DomainTable(input: InputStream) {
    private val blocked: LongArray
    private val allowed: LongArray
    init {
        val peek=java.io.PushbackInputStream(input,2); val signature=ByteArray(2); val read=peek.read(signature); if(read>0) peek.unread(signature,0,read)
        DataInputStream((if(read==2 && signature[0]==0x1f.toByte() && signature[1]==0x8b.toByte()) GZIPInputStream(peek) else peek).buffered(65536)).use { data ->
            require(data.readInt()==0x4d444e31)
            val a=data.readInt(); val b=data.readInt(); require(a in 0..600000 && b in 0..600000)
            blocked=LongArray(a) { data.readLong() }; allowed=LongArray(b) { data.readLong() }
            require(data.read()==-1)
        }
    }
    val size get()=blocked.size
    fun blocks(host: String): Boolean = decision(host)?.blocked==true
    fun decision(host: String): RuleDecision? {
        var suffix=normalizeHost(host); var blockedMatch=false
        while(suffix.isNotEmpty()) {
            val hash=hash(suffix)
            if(allowed.binarySearch(hash)>=0) return RuleDecision(false,2)
            if(blocked.binarySearch(hash)>=0) blockedMatch=true
            val at=suffix.indexOf('.'); if(at<0) break; suffix=suffix.substring(at+1)
        }
        return if(blockedMatch) RuleDecision(true,1) else null
    }
    companion object {
        fun hash(s: String): Long { var h=-0x340d631b7bdddcdbL; for(c in s) { h=(h xor c.code.toLong())*0x100000001b3L }; return h }
        fun normalizeHost(raw: String): String = runCatching { IDN.toASCII(raw.trimEnd('.').lowercase()) }.getOrDefault("")
    }
}
