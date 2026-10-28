package app.momiji.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.EOFException

/** Changes TLS record boundaries only. Certificate validation and TLS transcript stay end to end. */
object TlsHello {
    data class FirstFlight(val original: ByteArray,val fragmented: ByteArray,val splitAt: Int)
    private fun u16(a: ByteArray,p: Int)=((a[p].toInt() and 255) shl 8) or (a[p+1].toInt() and 255)
    private fun exact(input: InputStream,n: Int): ByteArray {
        val out=ByteArray(n); var at=0
        while(at<n) { val read=input.read(out,at,n-at); if(read<0) throw EOFException(); at+=read }
        return out
    }
    fun read(input: InputStream): FirstFlight {
        val first=input.read(); if(first<0) throw EOFException()
        if(first!=22) return FirstFlight(byteArrayOf(first.toByte()),byteArrayOf(first.toByte()),1)
        val header=byteArrayOf(first.toByte())+exact(input,4)
        val length=u16(header,3)
        require(header[1].toInt()==3 && length in 4..18432) { "Invalid TLS record" }
        val wire=ByteArrayOutputStream(); val payload=ByteArrayOutputStream()
        wire.write(header); val part=exact(input,length); wire.write(part); payload.write(part)
        if(part[0].toInt()!=1) return FirstFlight(wire.toByteArray(),wire.toByteArray(),1)
        val helloSize=4+((part[1].toInt() and 255) shl 16)+((part[2].toInt() and 255) shl 8)+(part[3].toInt() and 255)
        require(helloSize in 4..65536)
        while(payload.size()<helloSize) {
            val next=exact(input,5); val n=u16(next,3)
            require(next[0].toInt()==22 && next[1].toInt()==3 && n in 1..18432 && payload.size()+n<=81920)
            val data=exact(input,n); wire.write(next); wire.write(data); payload.write(data)
        }
        val data=payload.toByteArray()
        val found=sniBoundary(data,helloSize)
        if(found==null) {
            // IP-only/ECH-without-SNI connections have no hostname to split. Preserve their
            // record layout instead of manufacturing a one-byte handshake record.
            val original=wire.toByteArray()
            return FirstFlight(original,original,minOf(6,original.size-1).coerceAtLeast(1))
        }
        val boundary=found.coerceIn(1,data.size-1)
        val rewritten=ByteArrayOutputStream(data.size+20)
        fun record(start: Int,end: Int) {
            var p=start
            while(p<end) { val n=minOf(16384,end-p)
                rewritten.write(byteArrayOf(22,header[1],header[2],(n ushr 8).toByte(),n.toByte()))
                rewritten.write(data,p,n); p+=n
            }
        }
        record(0,boundary); record(boundary,data.size)
        // Locate the same split in the original record stream, even for multi-record hellos.
        val raw=wire.toByteArray(); var p=0; var accumulated=0; var wireSplit=1
        while(p+5<=raw.size) { val n=u16(raw,p+3)
            if(boundary<accumulated+n) { wireSplit=p+5+boundary-accumulated; break }
            accumulated+=n; p+=5+n
        }
        return FirstFlight(raw,rewritten.toByteArray(),wireSplit.coerceIn(1,raw.size-1))
    }
    private fun sniBoundary(a: ByteArray,end: Int): Int? = runCatching {
        var p=4+2+32
        require(p<end); p+=1+(a[p].toInt() and 255)
        require(p+2<=end); p+=2+u16(a,p)
        require(p<end); p+=1+(a[p].toInt() and 255)
        require(p+2<=end); val stop=minOf(end,p+2+u16(a,p)); p+=2
        while(p+4<=stop) {
            val type=u16(a,p); val size=u16(a,p+2); p+=4; require(p+size<=stop)
            if(type==0 && size>=5) {
                val listEnd=p+2+u16(a,p); require(listEnd<=p+size); var q=p+2
                while(q+3<=listEnd) {
                    val nameType=a[q].toInt(); val n=u16(a,q+1); q+=3; require(q+n<=listEnd)
                    if(nameType==0 && n>=2) return@runCatching q+n/2
                    q+=n
                }
            }
            p+=size
        }
        null
    }.getOrNull()
}
