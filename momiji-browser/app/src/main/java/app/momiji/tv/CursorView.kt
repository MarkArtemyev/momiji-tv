package app.momiji.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CursorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var cx = 0f
        private set
    var cy = 0f
        private set

    private val d = resources.displayMetrics.density
    private val r = 11f * d
    private var pressedLook = false

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFB347
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF7A3D }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = 0xFFFFF3E0.toInt()
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0xB32A140C.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF7A3D.toInt() }

    private val fade = Runnable { animate().alpha(0f).setDuration(400).start() }

    init {
        isFocusable = false
        isClickable = false
        alpha = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (cx == 0f && cy == 0f) center()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(cx, cy)
        if (pressedLook) canvas.scale(0.8f, 0.8f)
        canvas.drawCircle(0f, 0f, r * 2.4f, glow)
        canvas.drawCircle(0f, 0f, r, fill)
        canvas.drawCircle(0f, 0f, r + 2f * d, edge)
        canvas.drawCircle(0f, 0f, r, ring)
        canvas.drawCircle(0f, 0f, r - 2f * d, edge)
        canvas.drawCircle(0f, 0f, 2.8f * d, dot)
        canvas.restore()
    }

    fun moveTo(x: Float, y: Float) {
        if (cx == x && cy == y) return
        cx = x
        cy = y
        invalidate()
    }

    fun center() = moveTo(width / 2f, height / 2f)

    fun show() {
        removeCallbacks(fade)
        animate().cancel()
        alpha = 1f
        postDelayed(fade, IDLE)
    }

    fun scheduleHide() {
        removeCallbacks(fade)
        postDelayed(fade, IDLE)
    }

    fun hideNow() {
        removeCallbacks(fade)
        animate().cancel()
        alpha = 0f
    }

    fun setPressedLook(pressed: Boolean) {
        if (pressedLook == pressed) return
        pressedLook = pressed
        invalidate()
    }

    private companion object {
        const val IDLE = 4000L
    }
}
