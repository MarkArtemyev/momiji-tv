package app.momiji.home.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Only viewport cards own decoded bitmaps. LRU entries remain reusable when scrolling back. */
class VisibleArtwork(private val loader: ArtworkLoader,private val isActive: () -> Boolean) {
    private class Binding(val tile: HomeTileView,val load: ((Bitmap?) -> Unit) -> Unit) {
        var inFlight=false; var loaded=false; var attempts=0
        var retryAt=0L
    }
    private val main=Handler(Looper.getMainLooper())
    private val rect=Rect()
    private val bindings=ArrayList<Binding>()
    private var visible=emptySet<HomeTileView>()
    private var generation=0
    private val tick=Runnable { update() }
    fun bind(tile: HomeTileView,load: ((Bitmap?) -> Unit) -> Unit) { bindings.add(Binding(tile,load)) }
    fun updateSoon() {
        main.removeCallbacks(tick)
        if (isActive()) main.postDelayed(tick,60)
    }
    fun clear() {
        generation++; main.removeCallbacks(tick); loader.cancelRequests()
        bindings.forEach { it.tile.setArtwork(null) }; bindings.clear(); visible=emptySet()
    }
    private fun onScreen(tile: HomeTileView)=tile.isAttachedToWindow && tile.isShown && tile.getGlobalVisibleRect(rect) && rect.width()>8 && rect.height()>8
    private fun update() {
        if (!isActive()) return
        val current=bindings.filter { onScreen(it.tile) }
        val tiles=current.map { it.tile }.toSet()
        if (tiles != visible) {
            loader.cancelRequests()
            generation++
            bindings.forEach {
                if (it.inFlight) it.attempts=(it.attempts-1).coerceAtLeast(0)
                it.inFlight=false
                if (it.tile !in tiles) {
                    if (it.loaded) it.tile.setArtwork(null)
                    it.loaded=false; it.attempts=0; it.retryAt=0
                }
            }
            visible=tiles
        }
        val version=generation
        var available=8-current.count { it.inFlight }
        var nextRetry=Long.MAX_VALUE
        for (binding in current) {
            if (binding.loaded || binding.inFlight || binding.attempts>=2) continue
            val wait=binding.retryAt-SystemClock.uptimeMillis()
            if (wait>0) { nextRetry=minOf(nextRetry,wait); continue }
            if (available<=0) continue
            available--
            binding.inFlight=true; binding.attempts++
            binding.load ready@ { bitmap ->
                if (version != generation || !isActive()) return@ready
                binding.inFlight=false
                if (bitmap != null && onScreen(binding.tile)) {
                    binding.tile.setArtwork(bitmap); binding.loaded=true
                } else if (bitmap == null && binding.attempts<2) {
                    binding.retryAt=SystemClock.uptimeMillis()+500
                }
                updateSoon()
            }
        }
        if (nextRetry!=Long.MAX_VALUE) { main.removeCallbacks(tick); main.postDelayed(tick,nextRetry.coerceAtLeast(60)) }
    }
}
