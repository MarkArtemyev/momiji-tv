package app.momiji.home.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.util.LruCache
import app.momiji.home.model.AppEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.math.min

/** Bounded two-worker artwork cache. Bitmaps are immutable from the consumer's perspective. */
class ArtworkLoader(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private class CloseHandle(private val action: () -> Unit) {
        private val done = AtomicBoolean()
        fun close() { if (done.compareAndSet(false, true)) runCatching(action) }
    }
    private class Request(val key: String, val epoch: Int, val read: (Request) -> Bitmap?,
        val callbacks: MutableList<(Bitmap?) -> Unit>) {
        val resources = ArrayList<CloseHandle>() // Guarded by lock, including registration after cancellation.
        var task: FutureTask<Unit>? = null
        var cancelled = false
    }
    private val active = HashMap<String, Request>()
    // The Activity requests artwork for visible cards; work for a previous render is disposable.
    private val workers = ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { r -> Thread(r,"Momiji-Artwork").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var closed = false
    @Volatile private var paused = false
    private var epoch = 0

    fun loadApp(app: AppEntry, callback: (Bitmap?) -> Unit) {
        loadApp(app,true,callback)
    }
    fun loadApp(app: AppEntry,showLabel: Boolean,callback: (Bitmap?)->Unit) {
        request("app:${app.key}:$showLabel", { appArtwork(it,app,showLabel) },callback)
    }
    fun loadUri(uri: String, callback: (Bitmap?) -> Unit) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null || parsed.scheme !in setOf("https", "content", "android.resource", "file")) {
            val current = synchronized(lock) { if (closed || paused) return; epoch }
            deliver(callback,null,current); return
        }
        request("uri:$uri", { uriArtwork(it, parsed) }, callback)
    }

    private fun request(key: String, read: (Request) -> Bitmap?, callback: (Bitmap?) -> Unit) {
        val request: Request
        synchronized(lock) {
            if (closed || paused) return
            cache.get(key)?.let { deliver(callback,it,epoch); return }
            val existing = active[key]
            if (existing != null) {
                if (existing.callbacks.size < 128) existing.callbacks.add(callback)
                else deliver(callback,null,epoch)
                return
            }
            request = Request(key,epoch,read, mutableListOf(callback))
            active[key] = request
        }
        enqueue(request)
    }

    private fun enqueue(request: Request) {
        val task = FutureTask { perform(request); Unit }
        synchronized(lock) {
            if (!isCurrent(request)) return
            request.task = task
        }
        try {
            workers.execute(task)
        } catch (_: RejectedExecutionException) {
            val callbacks = synchronized(lock) {
                val current = isCurrent(request)
                removeCurrent(request)
                if (current) request.callbacks.toList().also { request.callbacks.clear() }
                else { request.callbacks.clear(); emptyList() }
            }
            callbacks.forEach { deliver(it,null,request.epoch) }
        }
    }

    private fun perform(request: Request) {
        var bitmap: Bitmap? = null
        var borrowed = false
        var published = false
        try {
            checkCurrent(request)
            val cached = cache.get(request.key)
            borrowed = cached != null
            bitmap = cached ?: request.read(request)
            val callbacks = synchronized(lock) {
                if (isCurrent(request)) {
                    bitmap?.let { cache.put(request.key, it); published = true }
                    removeCurrent(request)
                    request.callbacks.toList().also { request.callbacks.clear() }
                } else {
                    removeCurrent(request)
                    request.callbacks.clear()
                    emptyList()
                }
            }
            callbacks.forEach { deliver(it, bitmap, request.epoch) }
        } catch (_: Exception) {
            val callbacks = synchronized(lock) {
                val current = isCurrent(request)
                removeCurrent(request)
                if (current) request.callbacks.toList().also { request.callbacks.clear() }
                else { request.callbacks.clear(); emptyList() }
            }
            callbacks.forEach { deliver(it, null, request.epoch) }
        } finally {
            // A cancelled decoder result has never reached a view or the cache.
            if (!borrowed && !published) bitmap?.takeUnless { it.isRecycled }?.recycle()
            val remaining = synchronized(lock) {
                removeCurrent(request)
                request.callbacks.clear()
                request.resources.toList().also { request.resources.clear() }
            }
            remaining.forEach { it.close() } // Already on the worker, never the UI thread.
        }
    }

    /** Both helpers are called under lock, and never remove a newer same-key request. */
    private fun isCurrent(request: Request): Boolean = !closed && !paused && !request.cancelled &&
        request.epoch == epoch && active[request.key] === request
    private fun removeCurrent(request: Request) { if (active[request.key] === request) active.remove(request.key) }
    private fun checkCurrent(request: Request) {
        if (Thread.currentThread().isInterrupted || !synchronized(lock) { isCurrent(request) })
            throw CancellationException("Artwork request cancelled")
    }

    private fun register(request: Request, close: () -> Unit): CloseHandle {
        val handle = CloseHandle(close)
        val accepted = synchronized(lock) {
            if (isCurrent(request)) { request.resources.add(handle); true } else false
        }
        if (!accepted) {
            closeAsync(handle)
            throw CancellationException("Artwork request cancelled before resource registration")
        }
        return handle
    }
    private fun release(request: Request, handle: CloseHandle) {
        synchronized(lock) { request.resources.remove(handle) }
        handle.close() // Only called from artwork workers.
    }
    private inline fun <T> withStream(request: Request, stream: InputStream, block: (InputStream) -> T): T {
        val handle = register(request) { stream.close() }
        try { checkCurrent(request); return block(stream) } finally { release(request, handle) }
    }

    private fun deliver(callback: (Bitmap?) -> Unit, bitmap: Bitmap?, requestEpoch: Int) {
        synchronized(lock) {
            if (closed || paused || requestEpoch != epoch) return
            // Posting under the same lock lets pause() remove every old-generation callback.
            main.post {
                if (synchronized(lock) { closed || paused || requestEpoch != epoch }) return@post
                callback(bitmap)
            }
        }
    }

    /** Cancels one render while preserving cached bitmaps and the current paused/resumed state. */
    fun cancelRequests() { cancelRequestsInternal() }

    /** Stream closes and disconnects may block, so cancellation never runs them on the caller. */
    private fun cancelRequestsInternal(pause: Boolean = false, close: Boolean = false) {
        val resources = synchronized(lock) {
            if (closed) return
            if (pause) paused = true
            if (close) closed = true
            epoch++
            val handles = ArrayList<CloseHandle>()
            active.values.forEach {
                it.cancelled = true
                it.callbacks.clear()
                it.task?.cancel(true)
                handles.addAll(it.resources.asReversed())
                it.resources.clear()
            }
            active.clear()
            workers.queue.clear()
            main.removeCallbacksAndMessages(null)
            handles
        }
        resources.forEach(::closeAsync)
    }
    /** A background transition cancels queued and active I/O, including provider reads. */
    fun pause() { cancelRequestsInternal(pause = true) }
    fun resume() { synchronized(lock) { if (!closed) paused = false } }
    /** Drops cached references without recycling bitmaps held by currently visible cards. */
    fun trim() { cache.evictAll() }
    fun close() {
        cancelRequestsInternal(pause = true, close = true)
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
        cache.evictAll()
    }

    private fun appArtwork(request: Request, app: AppEntry,showLabel: Boolean): Bitmap? {
        checkCurrent(request)
        val pm = context.packageManager
        val info = runCatching { pm.getActivityInfo(app.component,0) }.getOrNull()
        val banner = runCatching { info?.loadBanner(pm) ?: pm.getApplicationBanner(app.packageName) }.getOrNull()
        checkCurrent(request)
        if (banner != null) return drawableBitmap(request, banner,true,showLabel)
        val icon = runCatching { info?.loadIcon(pm) ?: pm.getApplicationIcon(app.packageName) }.getOrNull() ?: return null
        checkCurrent(request)
        return drawableBitmap(request, icon,false,showLabel)
    }

    private fun drawableBitmap(request: Request, drawable: Drawable, banner: Boolean,showLabel: Boolean): Bitmap {
        checkCurrent(request)
        val bitmap = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(HomePalette.SURFACE)
            if (banner) {
                val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 320
                val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 180
                val scale = max(320f / w,180f / h)
                val dw = (w * scale).toInt(); val dh = (h * scale).toInt()
                drawable.setBounds((320-dw)/2,(180-dh)/2,(320+dw)/2,(180+dh)/2)
            } else {
                val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                val size=if(showLabel) 88f else 104f
                val scale = min(size/w,size/h)
                val dw = (w*scale).toInt(); val dh = (h*scale).toInt()
                val center=if(showLabel) 69 else 90
                drawable.setBounds((320-dw)/2,center-dh/2,(320+dw)/2,center+dh/2)
            }
            drawable.draw(canvas)
            checkCurrent(request)
            return bitmap
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    private fun uriArtwork(request: Request, uri: Uri): Bitmap? {
        checkCurrent(request)
        val bytes = when (uri.scheme) {
            "content", "android.resource" -> contentBytes(request, uri)
            "https" -> download(request, uri.toString())
            "file" -> {
                if (!uri.authority.isNullOrEmpty()) return null
                val root=File(context.filesDir,"icons").canonicalFile
                val file=File(uri.path ?: return null).canonicalFile
                if (file.parentFile != root || !file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
                withStream(request, file.inputStream()) { limitedRead(request, it) }
            }
            else -> null
        } ?: return null
        checkCurrent(request)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        checkCurrent(request)
        if (bounds.outWidth !in 1..20000 || bounds.outHeight !in 1..20000) return null
        var sample = 1
        while (bounds.outWidth / (sample*2) >= 320 && bounds.outHeight / (sample*2) >= 180) sample *= 2
        // Very tall/wide images must also fit a small decode allocation.
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000L) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: return null
        var returnDecoded = false
        try {
            checkCurrent(request)
            if (decoded.width == 320 && decoded.height == 180) { returnDecoded = true; return decoded }
            val output = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888)
            try {
                val scale = max(320f/decoded.width,180f/decoded.height)
                val visibleWidth = min(decoded.width,(320/scale).toInt().coerceAtLeast(1))
                val visibleHeight = min(decoded.height,(180/scale).toInt().coerceAtLeast(1))
                val left = (decoded.width-visibleWidth)/2; val top = (decoded.height-visibleHeight)/2
                Canvas(output).drawBitmap(decoded,Rect(left,top,left+visibleWidth,top+visibleHeight),
                    Rect(0,0,320,180),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                checkCurrent(request)
                return output
            } catch (error: Exception) { output.recycle(); throw error }
        } finally {
            if (!returnDecoded) decoded.recycle()
        }
    }

    private fun contentBytes(request: Request, uri: Uri): ByteArray? {
        val signal = CancellationSignal()
        val signalHandle = register(request) { signal.cancel() }
        try {
            checkCurrent(request)
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: return null
            val descriptorHandle = register(request) { descriptor.close() }
            try {
                checkCurrent(request)
                return withStream(request, descriptor.createInputStream()) { limitedRead(request, it) }
            } finally { release(request, descriptorHandle) }
        } finally { release(request, signalHandle) }
    }

    private fun download(request: Request, uri: String): ByteArray? {
        var current = URL(uri)
        repeat(4) {
            checkCurrent(request)
            if (current.protocol != "https") return null
            val connection = app.momiji.home.media.HomeNetwork.open(context,current) as? HttpsURLConnection ?: return null
            val connectionHandle = register(request) { connection.disconnect() }
            try {
                connection.connectTimeout = 4000; connection.readTimeout = 4000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept","image/*")
                connection.setRequestProperty("User-Agent","MomijiHome/1.0")
                val code = connection.responseCode
                checkCurrent(request)
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = URL(current,location)
                } else {
                    if (code !in 200..299 || connection.contentLengthLong > MAX_BYTES) return null
                    return withStream(request, connection.inputStream) { limitedRead(request, it) }
                }
            } finally { release(request, connectionHandle) }
        }
        return null
    }
    private fun limitedRead(request: Request, stream: InputStream): ByteArray? {
        val output = ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            checkCurrent(request)
            val count = stream.read(buffer)
            checkCurrent(request)
            if (count < 0) return output.toByteArray()
            total += count
            if (total > MAX_BYTES) return null
            output.write(buffer,0,count)
        }
    }
    private fun closeAsync(handle: CloseHandle) { resourceCloser.execute { handle.close() } }
    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        // Process-wide and bounded. It outlives individual loaders, including a late provider return.
        val resourceCloser = ThreadPoolExecutor(4, 4, 15, TimeUnit.SECONDS, LinkedBlockingQueue(),
            { r -> Thread(r, "Momiji-Artwork-Cancel").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
