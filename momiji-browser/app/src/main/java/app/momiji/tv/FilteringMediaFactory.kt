package app.momiji.tv

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSpec
import app.momiji.net.*
import java.io.IOException

/** Keeps the fast transport when bypass is OFF; every playlist/segment open still sees filters. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FilteringMediaFactory(private val context: Context,private val source: HttpDataSource.Factory,private val referer: String?): HttpDataSource.Factory {
    override fun setDefaultRequestProperties(properties: Map<String,String>): FilteringMediaFactory { source.setDefaultRequestProperties(properties); return this }
    override fun createDataSource(): HttpDataSource {
        val delegate=source.createDataSource()
        return object: HttpDataSource by delegate {
            override fun open(spec: DataSpec): Long {
                if(Store(context).adBlock) {
                    if(!FilterRepository.awaitReady()) throw java.io.InterruptedIOException("Filter initialization interrupted or timed out")
                    val page=spec.httpRequestHeaders.entries.firstOrNull { it.key.equals("Referer",true) }?.value ?: referer.orEmpty()
                    if(AdBlock.blocks(FilterRequest(spec.uri.toString(),page,ResourceType.MEDIA))) throw IOException("Media resource blocked by Momiji filter")
                }
                return delegate.open(spec)
            }
        }
    }
}
