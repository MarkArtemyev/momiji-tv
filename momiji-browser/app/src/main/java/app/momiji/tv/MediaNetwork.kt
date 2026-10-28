package app.momiji.tv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresExtension
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One reusable media connection pool. WebView continues to own its browser network stack. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MediaNetwork {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000

    @Volatile var activeStack: String = "HTTP"
        private set

    @SuppressLint("NewApi") // HttpEngine also ships on Android 12+ through SDK extension 7.
    fun httpFactory(context: Context, userAgent: String?, referer: String?): HttpDataSource.Factory {
        AdBlock.init(context)
        val headers = referer?.takeIf {
            val uri = Uri.parse(it)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }?.let { mapOf("Referer" to it) } ?: emptyMap()

        NetworkRoute.configure(ProtectionPreferences(context).config())
        if(NetworkRoute.config().active) {
            activeStack="Локальный прокси · ${if(NetworkRoute.nativeConfig().mode=="OFF") "HTTPS DNS" else NetworkRoute.nativeConfig().mode}"
            return FilteringMediaFactory(context,ProxyMediaDataSource.Factory(context.applicationContext).setDefaultRequestProperties(headers+mapOf("User-Agent" to (userAgent ?: "Momiji"))),referer)
        }

        val supportsEngine = Build.VERSION.SDK_INT >= 34 ||
            (Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7)
        if (supportsEngine) {
            try {
                EngineApi.factory(context.applicationContext, userAgent, headers)?.let {
                    activeStack = "HttpEngine · HTTP/2 / HTTP/3"
                    return FilteringMediaFactory(context,it,referer)
                }
            } catch (_: LinkageError) {
                // Some vendor images advertise an extension without a working provider.
            } catch (_: RuntimeException) {
                // A missing/disabled provider must not prevent native playback.
            }
        }
        activeStack = "HTTP"
        return FilteringMediaFactory(context,DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setDefaultRequestProperties(headers),referer)
    }

    // Keep references to framework HTTP classes out of paths executed on Android 8–11.
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
    private object EngineApi {
        private var attempted = false
        private var engine: android.net.http.HttpEngine? = null
        private val callbacks by lazy {
            ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(128),
                { task -> Thread(task, "media-http").apply { isDaemon = true } },
                ThreadPoolExecutor.CallerRunsPolicy()).apply { allowCoreThreadTimeOut(true) }
        }

        @DoNotInline
        @Synchronized
        fun factory(context: Context, userAgent: String?, headers: Map<String, String>): HttpDataSource.Factory? {
            if (!attempted) {
                attempted = true
                engine = try {
                    android.net.http.HttpEngine.Builder(context)
                        .setEnableHttp2(true)
                        .setEnableQuic(true)
                        .build()
                } catch (_: LinkageError) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
                Log.i("Momiji", if (engine == null) "Media network: default HTTP" else "Media network: HttpEngine")
            }
            val shared = engine ?: return null
            return androidx.media3.datasource.HttpEngineDataSource.Factory(shared, callbacks)
                .setUserAgent(userAgent)
                .setConnectionTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setDefaultRequestProperties(headers)
        }
    }
}
