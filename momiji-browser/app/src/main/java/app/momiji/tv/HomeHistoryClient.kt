package app.momiji.tv

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Optional companion IPC. The profile is captured once when playback begins. */
class HomeHistoryClient(context: Context) {
    data class Session(val profile: String, val page: String, val title: String, val position: Long)
    data class Resolution(val home: PlaybackScopePolicy.HomeState, val session: Session? = null)
    private val app=context.applicationContext
    private val resolver=context.applicationContext.contentResolver
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"Momiji-HomeHistory").apply { isDaemon=true } }
    private val main=Handler(Looper.getMainLooper())
    private var closed=false
    fun open(page: String?, title: String, callback: (Session?) -> Unit) {
        resolve(page,title) { callback(it.session) }
    }
    /** capturedProfile is an explicit binding, never a hint to fall back to the active profile. */
    fun resolve(page: String?, title: String, capturedProfile: String? = null, captureActive: Boolean = true,
        callback: (Resolution) -> Unit) {
        if (closed) return
        var delivered=false
        val timeout=Runnable { if (!closed && !delivered) { delivered=true; callback(Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)) } }
        worker.execute {
            // Queued writes must finish before the read deadline starts.
            main.postDelayed(timeout,5000)
            val result=runCatching {
                try { app.packageManager.getApplicationInfo(HOME_PACKAGE,0) }
                catch (_: PackageManager.NameNotFoundException) { return@runCatching Resolution(PlaybackScopePolicy.HomeState.ABSENT) }
                if (capturedProfile == null && !captureActive) return@runCatching Resolution(PlaybackScopePolicy.HomeState.AVAILABLE)
                val uri=page?.let(Uri::parse) ?: return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                if (uri.scheme !in setOf("http","https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
                    page.length > 8192 || page.any(Char::isISOControl)) return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                val id=if (capturedProfile != null) capturedProfile else {
                    val profile=resolver.call(HOME,"profile",null,null) ?: return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                    if (profile.getInt("protocol") != 1) return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                    profile.getString("id") ?: return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                }
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                // lookup validates that this exact profile still exists. A missing/deleted profile
                // is not replaced with whichever other profile is currently active.
                val progress=resolver.call(HOME,"lookup",null,Bundle().apply { putString("profile",id); putString("page",page) })
                    ?: return@runCatching Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE)
                Resolution(PlaybackScopePolicy.HomeState.AVAILABLE,Session(id,page,title.take(200),progress.getLong("position",0).coerceAtLeast(0)))
            }.getOrElse { Resolution(PlaybackScopePolicy.HomeState.UNAVAILABLE) }
            main.post {
                main.removeCallbacks(timeout)
                if (!closed && !delivered) { delivered=true; callback(result) }
            }
        }
    }
    fun save(session: Session?, position: Long, duration: Long, ended: Boolean=false) {
        if (closed || session == null || duration <= 0) return
        val data=Bundle().apply {
            putString("profile",session.profile); putString("page",session.page); putString("title",session.title)
            putLong("position",position.coerceIn(0,duration)); putLong("duration",duration); putBoolean("ended",ended)
        }
        worker.execute { runCatching { resolver.call(HOME,"progress",null,data) } }
    }
    fun forget(session: Session?) {
        if (closed || session == null) return
        worker.execute { runCatching { resolver.call(HOME,"forget",null,Bundle().apply { putString("profile",session.profile); putString("page",session.page) }) } }
    }
    fun close() { closed=true; main.removeCallbacksAndMessages(null); worker.shutdown() }
    companion object {
        private const val HOME_PACKAGE="app.momiji.home"
        private val HOME=Uri.parse("content://app.momiji.home.history")
    }
}
