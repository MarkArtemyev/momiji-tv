package app.momiji.tv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import app.momiji.net.*
import java.io.IOException

/**
 * Keeps the fast transport when bypass is OFF and filters what the native player loads without ever
 * cutting playback: HLS media playlists lose their ad segments before the player reads them (VOD; see
 * HlsPruner), the main stream is refused only when it is itself an ad, and segments are never failed
 * mid-stream.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FilteringMediaFactory(private val context: Context,private val source: HttpDataSource.Factory,private val referer: String?): HttpDataSource.Factory {
    @Volatile private var mainChecked=false

    override fun setDefaultRequestProperties(properties: Map<String,String>): FilteringMediaFactory { source.setDefaultRequestProperties(properties); return this }

    override fun createDataSource(): HttpDataSource {
        val delegate=source.createDataSource()
        return object: HttpDataSource by delegate {
            private var buffer: ByteArray?=null
            private var position=0

            override fun open(spec: DataSpec): Long {
                buffer=null; position=0
                if(!Store(context).adBlock) return delegate.open(spec)
                if(!FilterRepository.awaitReady()) throw java.io.InterruptedIOException("Filter initialization interrupted or timed out")
                val page=spec.httpRequestHeaders.entries.firstOrNull { it.key.equals("Referer",true) }?.value ?: referer.orEmpty()
                val url=spec.uri.toString()
                val playlist=looksLikePlaylist(url)
                // Only the first request (the stream the user opened) or a playlist can be refused outright.
                val first=!mainChecked.also { mainChecked=true }
                if((first || playlist) && AdBlock.blocks(FilterRequest(url,page,ResourceType.MEDIA))) throw IOException("Media resource blocked by Momiji filter")
                val length=delegate.open(spec)
                val type=delegate.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type",true) }?.value?.firstOrNull().orEmpty()
                if(!(playlist || "mpegurl" in type.lowercase()) || spec.position!=0L || spec.length!=C.LENGTH_UNSET.toLong()) return length
                val text=readAll(delegate) ?: return reopen(spec)
                val pruned=runCatching { AdBlock.prunePlaylist(text,delegate.uri?.toString() ?: url,page) }.getOrNull()
                buffer=(pruned ?: text).toByteArray(Charsets.UTF_8)
                return buffer!!.size.toLong()
            }

            override fun read(target: ByteArray,offset: Int,length: Int): Int {
                val data=buffer ?: return delegate.read(target,offset,length)
                if(length==0) return 0
                if(position>=data.size) return C.RESULT_END_OF_INPUT
                val count=minOf(length,data.size-position)
                System.arraycopy(data,position,target,offset,count)
                position+=count
                return count
            }

            override fun close() { buffer=null; position=0; delegate.close() }

            /** Playlists are small; anything bigger is not a playlist and goes back to streaming. */
            private fun readAll(source: HttpDataSource): String? {
                val out=java.io.ByteArrayOutputStream(); val chunk=ByteArray(16384)
                while(true) {
                    val n=source.read(chunk,0,chunk.size)
                    if(n==C.RESULT_END_OF_INPUT) break
                    out.write(chunk,0,n)
                    if(out.size()>8*1024*1024) return null
                }
                return out.toString("UTF-8")
            }

            private fun reopen(spec: DataSpec): Long { delegate.close(); buffer=null; return delegate.open(spec) }
        }
    }

    private fun looksLikePlaylist(url: String): Boolean {
        val path=Uri.parse(url).path.orEmpty().lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }
}
