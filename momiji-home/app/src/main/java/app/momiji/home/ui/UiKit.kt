package app.momiji.home.ui

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.text.TextUtils
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import app.momiji.home.R
import app.momiji.home.model.PerformanceMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

object HomePalette {
    const val BACKGROUND: Int = 0xff101216.toInt()
    const val SURFACE: Int = 0xff22262d.toInt()
    const val TEXT: Int = 0xfff6f1e7.toInt()
    const val MUTED: Int = 0xffbdc1c7.toInt()
    const val AMBER: Int = 0xffebbe77.toInt()
    const val BORDER: Int = 0xff41464f.toInt()
}

/** One static, sampled backdrop. Only decoding runs off the UI thread. */
class HomeBackdrop(context: Context) : View(context) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Momiji-Wallpaper").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val source = Rect()
    private val destination = RectF()
    private var picture: Bitmap? = null
    private var shade: LinearGradient? = null
    private var mode = PerformanceMode.BALANCED
    private var closed = false
    private var wallpaperRequested = false
    private var requestedWallpaper: String? = null
    private var loadingWallpaper = false
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; setWillNotDraw(false); setWallpaper(null) }

    fun setWallpaper(uri: String?) {
        if (closed) return
        if (wallpaperRequested && requestedWallpaper == uri && (loadingWallpaper || picture != null)) return
        wallpaperRequested = true
        requestedWallpaper = uri
        loadingWallpaper = true
        val request = generation.incrementAndGet()
        worker.execute {
            val bitmap = runCatching { decode(uri) ?: if (uri != null) decode(null) else null }.getOrNull()
            post {
                if (closed || generation.get() != request) { bitmap?.recycle(); return@post }
                loadingWallpaper = false
                // Do not recycle the old bitmap: a hardware display list may still use it.
                picture = bitmap
                crop()
                invalidate()
            }
        }
    }

    private fun decode(uri: String?): Bitmap? {
        fun open(): java.io.InputStream? = if (uri != null) {
            val parsed = Uri.parse(uri)
            when (parsed.scheme) {
                "content", "android.resource" -> context.contentResolver.openInputStream(parsed)
                "file" -> runCatching {
                    // Only copies imported into this app's own wallpaper folder may be decoded.
                    if (!parsed.authority.isNullOrEmpty()) return@runCatching null
                    val path = parsed.path ?: return@runCatching null
                    val root = File(context.filesDir, "wallpapers").canonicalFile
                    val file = File(path).canonicalFile
                    if (!file.path.startsWith(root.path + File.separator) || !file.isFile ||
                        file.length() !in 1L..(20L * 1024 * 1024)) null
                    else file.inputStream()
                }.getOrNull()
                else -> null
            }
        } else {
            resources.openRawResource(R.raw.home_wallpaper)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1440) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun setMode(mode: PerformanceMode) { if (this.mode != mode) { this.mode = mode; updateShade(); invalidate() } }
    private fun updateShade() {
        if (height == 0) return
        val middle = when (mode) {
            PerformanceMode.LITE -> 0xaa101216.toInt()
            PerformanceMode.BALANCED -> 0x76101216
            PerformanceMode.CINEMA -> 0x46101216
        }
        val top = if (mode == PerformanceMode.CINEMA) 0x9b101216.toInt() else 0xc6101216.toInt()
        shade = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(top, middle, 0xe8101216.toInt(), HomePalette.BACKGROUND),
            floatArrayOf(0f, .34f, .76f, 1f), Shader.TileMode.CLAMP)
        shadePaint.shader = shade
    }
    private fun crop() {
        val bitmap = picture ?: return
        if (width == 0 || height == 0) return
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val visibleWidth = min(bitmap.width, (width / scale).toInt().coerceAtLeast(1))
        val visibleHeight = min(bitmap.height, (height / scale).toInt().coerceAtLeast(1))
        val left = (bitmap.width - visibleWidth) / 2
        val top = (bitmap.height - visibleHeight) / 2
        source.set(left, top, left + visibleWidth, top + visibleHeight)
        destination.set(0f, 0f, width.toFloat(), height.toFloat())
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); crop(); updateShade() }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(HomePalette.BACKGROUND)
        picture?.takeUnless { it.isRecycled }?.let { canvas.drawBitmap(it, source, destination, paint) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
    }
    fun close() { if (closed) return; closed = true; loadingWallpaper = false; generation.incrementAndGet(); worker.shutdownNow(); picture = null; invalidate() }
}

/** A single custom-drawn TV card. Pointer hover and remote focus are mutually exclusive. */
class HomeTileView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val cardPath = Path()
    private val borderPath = Path()
    private val bounds = RectF()
    private val imageSource = Rect()
    private val playPath = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HomePalette.SURFACE }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlay = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.4f) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f); strokeCap = Paint.Cap.ROUND; color = HomePalette.MUTED }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = dp(15f); color = HomePalette.TEXT }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = dp(10.5f); color = HomePalette.MUTED }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = dp(8.5f); color = HomePalette.TEXT }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xc9101216.toInt() }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRect = RectF()
    private val iconRect = RectF()
    private var artwork: Bitmap? = null
    private var pointerMode = false
    private var hover = false
    private var highlighted = false
    private var displayTitle = ""
    private var displaySubtitle = ""
    private var badgeWidth = 0f
    var title: String = ""
        set(value) { field = value; updateText(); updateDescription(); invalidate() }
    var subtitle: String = ""
        set(value) { field = value; updateText(); updateDescription(); invalidate() }
    var accent: Int = HomePalette.AMBER
        set(value) { field = value; progressPaint.color = value; invalidate() }
    var kind: String = "app"
        set(value) { field = value; updateText(); invalidate() }
    var performanceMode: PerformanceMode = PerformanceMode.BALANCED
        set(value) { field = value; updateHighlight() }
    var roundCards: Boolean = true
        set(value) { field = value; if (width > 0) updateShape(); invalidate() }
    var largeText: Boolean = false
        set(value) { field = value; updateText(); invalidate() }
    var showAppLabel: Boolean = true
        set(value) { field=value; invalidate() }
    var fontFamily: String = "sans-serif-medium"
        set(value) { field = value; updateText(); invalidate() }
    var progress: Float = 0f
        set(value) { field = value.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f; invalidate() }
    var badge: String = ""
        set(value) { field = value; badgeWidth = badgePaint.measureText(value) + dp(14f); invalidate() }

    init {
        setDefaultFocusHighlightEnabled(false)
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        progressPaint.color = accent
        setWillNotDraw(false)
    }

    fun setArtwork(bitmap: Bitmap?) { artwork = bitmap; updateCrop(); invalidate() }
    fun setPointerMode(pointer: Boolean) {
        if (pointerMode == pointer) return
        pointerMode = pointer
        hover = false
        updateHighlight()
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        updateHighlight()
    }
    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!pointerMode) return super.onHoverEvent(event)
        val nowHover = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        if (hover != nowHover) { hover = nowHover; updateHighlight() }
        return super.onHoverEvent(event)
    }
    private fun updateHighlight() {
        highlighted = if (pointerMode) hover else isFocused
        animate().cancel()
        val target = if (highlighted && !pointerMode) when (performanceMode) {
            PerformanceMode.LITE -> 1f
            PerformanceMode.BALANCED -> 1.045f
            PerformanceMode.CINEMA -> 1.065f
        } else 1f
        if (performanceMode == PerformanceMode.LITE || pointerMode || !isAttachedToWindow) {
            scaleX = target; scaleY = target
        } else animate().scaleX(target).scaleY(target).setDuration(if (performanceMode == PerformanceMode.CINEMA) 160 else 130).start()
        translationZ = if (highlighted) dp(2f) else 0f
        invalidate()
    }
    private fun updateDescription() { contentDescription = if (subtitle.isBlank()) title else "$title, $subtitle" }
    private fun updateText() {
        titlePaint.typeface = Typeface.create(fontFamily,Typeface.NORMAL)
        subPaint.typeface = Typeface.create(if (fontFamily == "sans-serif-medium") "sans-serif" else fontFamily,Typeface.NORMAL)
        titlePaint.textSize = dp((if (kind == "action" && height <= dp(44f)) 12f else 15f) * if (largeText) 1.12f else 1f)
        subPaint.textSize = dp(if (largeText) 11.5f else 10.5f)
        val available = (width - dp(if (kind == "action" && height <= dp(44f)) 16f else 28f)).coerceAtLeast(0f)
        displayTitle = TextUtils.ellipsize(title,titlePaint,available,TextUtils.TruncateAt.END).toString()
        displaySubtitle = TextUtils.ellipsize(subtitle,subPaint,available,TextUtils.TruncateAt.END).toString()
    }
    private fun updateCrop() {
        val bitmap = artwork ?: return
        if (bitmap.isRecycled || width == 0 || height == 0) return
        val factor = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val sw = min(bitmap.width,(width / factor).toInt().coerceAtLeast(1))
        val sh = min(bitmap.height,(height / factor).toInt().coerceAtLeast(1))
        val x = (bitmap.width - sw) / 2
        val y = (bitmap.height - sh) / 2
        imageSource.set(x,y,x+sw,y+sh)
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        bounds.set(0f,0f,w.toFloat(),h.toFloat())
        playPath.reset()
        playPath.moveTo(w*.5f-dp(8f),h*.38f-dp(12f))
        playPath.lineTo(w*.5f+dp(13f),h*.38f)
        playPath.lineTo(w*.5f-dp(8f),h*.38f+dp(12f)); playPath.close()
        updateShape()
        overlay.shader = LinearGradient(0f,h*.22f,0f,h.toFloat(),
            intArrayOf(0x00101216,0x2c101216,0xed101216.toInt()),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
        updateCrop(); updateText()
    }
    private fun updateShape() {
        val radius = if (roundCards) 14f else 4f
        cardPath.reset(); cardPath.addRoundRect(bounds,dp(radius),dp(radius),Path.Direction.CW)
        val inset = dp(1.3f)
        borderPath.reset(); borderPath.addRoundRect(inset,inset,width-inset,height-inset,dp(radius-1f),dp(radius-1f),Path.Direction.CW)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawRect(bounds,fill)
        if (kind != "action" && kind != "input") {
            val bitmap = artwork
            if (bitmap != null && !bitmap.isRecycled) canvas.drawBitmap(bitmap,imageSource,bounds,imagePaint)
            if(kind!="app" || showAppLabel || bitmap==null) canvas.drawRect(bounds,overlay)
            if (kind == "media" && bitmap == null) {
                iconPaint.color = accent; iconPaint.alpha = 125
                canvas.drawPath(playPath,iconPaint); iconPaint.alpha = 255
            }
        }
        val action = kind == "action" || kind == "input"
        if (action) drawAction(canvas)
        else if(kind!="app" || showAppLabel || artwork==null) {
            val baseline = height - dp(if (displaySubtitle.isBlank()) 15f else 30f)
            canvas.drawText(displayTitle,dp(14f),baseline,titlePaint)
            if (displaySubtitle.isNotBlank()) canvas.drawText(displaySubtitle,dp(14f),height-dp(13f),subPaint)
        }
        if (badge.isNotBlank()) {
            badgeRect.set(width-badgeWidth-dp(10f),dp(10f),width-dp(10f),dp(29f))
            canvas.drawRoundRect(badgeRect,dp(6f),dp(6f),badgeFill)
            canvas.drawText(badge,badgeRect.left+dp(7f),dp(23f),badgePaint)
        }
        if (progress > 0f) canvas.drawRect(0f,height-dp(3f),width*progress,height.toFloat(),progressPaint)
        canvas.restoreToCount(save)
        stroke.color = if (highlighted) accent else 0x50494f59
        stroke.strokeWidth = dp(if (highlighted) 2.4f else .8f)
        canvas.drawPath(borderPath,stroke)
    }
    private fun drawAction(canvas: Canvas) {
        val cx = width / 2f
        val hasSubtitle = displaySubtitle.isNotBlank()
        if (kind == "input") {
            val top = height*.22f
            iconPaint.color = if (highlighted) accent else HomePalette.MUTED
            iconRect.set(cx-dp(18f),top,cx+dp(18f),top+dp(22f))
            canvas.drawRoundRect(iconRect,dp(3f),dp(3f),iconPaint)
            canvas.drawLine(cx,top+dp(23f),cx,top+dp(28f),iconPaint)
            canvas.drawLine(cx-dp(8f),top+dp(29f),cx+dp(8f),top+dp(29f),iconPaint)
        }
        val baseline = if (kind == "input") height*.72f else height*.5f + if (hasSubtitle) 0f else dp(5f)
        canvas.drawText(displayTitle,cx-titlePaint.measureText(displayTitle)/2,baseline,titlePaint)
        if (hasSubtitle) canvas.drawText(displaySubtitle,cx-subPaint.measureText(displaySubtitle)/2,baseline+dp(20f),subPaint)
    }
    override fun onDetachedFromWindow() { animate().cancel(); super.onDetachedFromWindow() }
}
