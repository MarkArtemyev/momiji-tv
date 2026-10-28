package app.momiji.home.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small decorative effects drawn by Android, without a WebView, textures, or a render thread. */
enum class AmbientEffect { OFF, MAPLE, FIREFLIES, STARS }

/**
 * Optional foreground ambience. Call its public methods on the main thread.
 *
 * The host owns the user's animation, battery-saver and performance preferences. It must call
 * [setRunning] with false when its screen stops, and [close] when the view is discarded. Visibility
 * and attachment also stop the clock, so a hidden screen never keeps an animation callback alive.
 * Pausing retains the last composition; selecting OFF clears it. There are at most 32 particles,
 * with 20 callbacks/second at low intensity and fewer than 24 otherwise. No per-frame allocations
 * or unbounded animation duration: particle coordinates and phase are wrapped continuously.
 */
class AmbientView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leaf = Path().apply {
        // One reusable silhouette, including a small stem. Coordinates are scaled on the canvas.
        moveTo(0f, -1f)
        lineTo(.19f, -.55f)
        lineTo(.42f, -.67f)
        lineTo(.40f, -.29f)
        lineTo(.84f, -.42f)
        lineTo(.70f, -.08f)
        lineTo(1f, .06f)
        lineTo(.46f, .29f)
        lineTo(.49f, .51f)
        lineTo(.09f, .39f)
        lineTo(.06f, .82f)
        lineTo(-.04f, .84f)
        lineTo(-.06f, .39f)
        lineTo(-.46f, .52f)
        lineTo(-.43f, .29f)
        lineTo(-.96f, .05f)
        lineTo(-.68f, -.09f)
        lineTo(-.82f, -.43f)
        lineTo(-.39f, -.29f)
        lineTo(-.42f, -.66f)
        lineTo(-.18f, -.54f)
        close()
    }
    private val x = FloatArray(MAX_PARTICLES)
    private val y = FloatArray(MAX_PARTICLES)
    private val velocityX = FloatArray(MAX_PARTICLES)
    private val velocityY = FloatArray(MAX_PARTICLES)
    private val size = FloatArray(MAX_PARTICLES)
    private val phase = FloatArray(MAX_PARTICLES)
    private val phaseRate = FloatArray(MAX_PARTICLES)
    private val angle = FloatArray(MAX_PARTICLES)
    private val angularVelocity = FloatArray(MAX_PARTICLES)
    private val opacity = FloatArray(MAX_PARTICLES)
    private var effect = AmbientEffect.OFF
    private var intensity = 1
    private var speed = 1f
    private var tintRgb = DEFAULT_TINT and 0x00ffffff
    private var count = 0
    private var intervalMs = 42L
    private var running = false
    private var scheduled = false
    private var attached = false
    private var closed = false
    private var lastFrameMs = 0L
    private var remainderSeconds = 0f
    private var seed = 0x4d6f6d69
    private val density = resources.displayMetrics.density

    private val frame: Runnable = object : Runnable {
        override fun run() {
            scheduled = false
            if (!canAnimate()) return
            val now = SystemClock.uptimeMillis()
            // Limit catch-up after a busy frame; a resumed effect never jumps across the screen.
            val elapsed = (now - lastFrameMs).coerceIn(0L, 100L)
            lastFrameMs = now
            remainderSeconds += elapsed.toFloat() / 1000f
            var steps = 0
            while (remainderSeconds >= FIXED_STEP && steps < MAX_STEPS) {
                advance(FIXED_STEP * speed)
                remainderSeconds -= FIXED_STEP
                steps++
            }
            if (steps == MAX_STEPS) remainderSeconds = min(remainderSeconds, FIXED_STEP)
            invalidate()
            schedule(intervalMs)
        }
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        resetParticles()
    }

    fun configure(
        effect: AmbientEffect,
        intensity: Int = 1,
        speed: Float = 1f,
        tint: Int = DEFAULT_TINT,
    ) {
        requireMainThread()
        if (closed) return
        val nextIntensity = intensity.coerceIn(0, 2)
        val nextSpeed = if (speed.isFinite()) speed.coerceIn(.25f, 2f) else 1f
        val changedPattern = this.effect != effect || this.intensity != nextIntensity
        this.effect = effect
        this.intensity = nextIntensity
        this.speed = nextSpeed
        tintRgb = tint and 0x00ffffff
        intervalMs = if (nextIntensity == 0) 50L else 42L
        count = when (effect) {
            AmbientEffect.OFF -> 0
            AmbientEffect.MAPLE -> when (nextIntensity) { 0 -> 8; 1 -> 14; else -> 20 }
            AmbientEffect.FIREFLIES -> when (nextIntensity) { 0 -> 10; 1 -> 20; else -> 32 }
            AmbientEffect.STARS -> when (nextIntensity) { 0 -> 12; 1 -> 22; else -> 32 }
        }
        if (changedPattern) resetParticles()
        reconcileClock()
        // OFF needs a single clear while visible, never a self-sustaining invalidation loop.
        if (attached && isShown && windowVisibility == VISIBLE) invalidate()
    }

    /** Unknown names fail closed. "QUIET" is an alias for the sparse star effect. */
    fun configure(
        effect: String,
        intensity: Int = 1,
        speed: Float = 1f,
        tint: Int = DEFAULT_TINT,
    ) {
        val resolved = when {
            effect.equals("MAPLE", ignoreCase = true) -> AmbientEffect.MAPLE
            effect.equals("FIREFLIES", ignoreCase = true) -> AmbientEffect.FIREFLIES
            effect.equals("STARS", ignoreCase = true) || effect.equals("QUIET", ignoreCase = true) -> AmbientEffect.STARS
            else -> AmbientEffect.OFF
        }
        configure(resolved, intensity, speed, tint)
    }

    fun setRunning(running: Boolean) {
        requireMainThread()
        if (closed) return
        this.running = running
        reconcileClock()
    }

    fun close() {
        requireMainThread()
        if (closed) return
        closed = true
        running = false
        count = 0
        stopClock()
        if (attached && isShown && windowVisibility == VISIBLE) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        reconcileClock()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopClock()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        reconcileClock()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        reconcileClock()
    }

    private fun canAnimate(): Boolean =
        !closed && running && count > 0 && attached && isShown && windowVisibility == VISIBLE

    private fun reconcileClock() {
        if (!canAnimate()) {
            stopClock()
        } else if (!scheduled) {
            lastFrameMs = SystemClock.uptimeMillis()
            remainderSeconds = 0f
            schedule(0L)
        }
    }

    private fun schedule(delay: Long) {
        if (scheduled || !canAnimate()) return
        scheduled = postDelayed(frame, delay)
    }

    private fun stopClock() {
        removeCallbacks(frame)
        scheduled = false
        lastFrameMs = 0L
        remainderSeconds = 0f
    }

    private fun randomUnit(): Float {
        // Deterministic LCG used only when changing presets, never while drawing or stepping.
        seed = seed * 1664525 + 1013904223
        return ((seed ushr 8) and 0x00ffffff).toFloat() / 16777216f
    }

    private fun resetParticles() {
        seed = 0x4d6f6d69
        var i = 0
        while (i < MAX_PARTICLES) {
            x[i] = randomUnit()
            y[i] = randomUnit()
            velocityX[i] = .003f + randomUnit() * .008f
            velocityY[i] = .012f + randomUnit() * .020f
            size[i] = .6f + randomUnit() * .8f
            phase[i] = randomUnit() * TWO_PI
            phaseRate[i] = .25f + randomUnit() * .55f
            angle[i] = randomUnit() * 360f
            angularVelocity[i] = (randomUnit() - .5f) * 22f
            opacity[i] = .45f + randomUnit() * .55f
            i++
        }
    }

    private fun advance(dt: Float) {
        var i = 0
        while (i < count) {
            phase[i] += phaseRate[i] * dt
            if (phase[i] >= TWO_PI) phase[i] -= TWO_PI
            when (effect) {
                AmbientEffect.MAPLE -> {
                    x[i] += (velocityX[i] + sin(phase[i]) * .008f) * dt
                    y[i] += velocityY[i] * dt
                    angle[i] += angularVelocity[i] * dt
                    if (angle[i] > 360f) angle[i] -= 360f
                    if (angle[i] < 0f) angle[i] += 360f
                    if (y[i] > 1.06f) y[i] = -.06f
                }
                AmbientEffect.FIREFLIES -> {
                    x[i] += cos(phase[i]) * velocityX[i] * dt
                    y[i] += sin(phase[i]) * velocityY[i] * .38f * dt
                }
                AmbientEffect.STARS -> x[i] += velocityX[i] * .08f * dt
                AmbientEffect.OFF -> Unit
            }
            if (x[i] > 1.06f) x[i] = -.06f
            if (x[i] < -.06f) x[i] = 1.06f
            if (y[i] > 1.06f) y[i] = -.06f
            if (y[i] < -.06f) y[i] = 1.06f
            i++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed || effect == AmbientEffect.OFF || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        var i = 0
        while (i < count) {
            val px = x[i] * w
            val py = y[i] * h
            val pulse = .60f + .40f * sin(phase[i])
            when (effect) {
                AmbientEffect.MAPLE -> {
                    val radius = (7f + size[i] * 6f) * density
                    paint.color = tintRgb or ((52f + 57f * opacity[i]).toInt() shl 24)
                    val saved = canvas.save()
                    canvas.translate(px, py)
                    canvas.rotate(angle[i])
                    // A changing horizontal width suggests gentle tumbling without a bitmap.
                    canvas.scale(radius * (.65f + .30f * sin(phase[i])), radius)
                    canvas.drawPath(leaf, paint)
                    canvas.restoreToCount(saved)
                }
                AmbientEffect.FIREFLIES -> {
                    val radius = (1.1f + size[i] * .8f) * density
                    val alpha = (100f * pulse * opacity[i]).toInt()
                    paint.color = tintRgb or ((alpha / 14) shl 24)
                    canvas.drawCircle(px, py, radius * 6f, paint)
                    paint.color = tintRgb or ((alpha / 4) shl 24)
                    canvas.drawCircle(px, py, radius * 2.7f, paint)
                    paint.color = tintRgb or ((alpha + 45).coerceAtMost(180) shl 24)
                    canvas.drawCircle(px, py, radius, paint)
                }
                AmbientEffect.STARS -> {
                    val radius = (.55f + size[i] * .55f) * density
                    paint.color = tintRgb or ((40f + 90f * pulse * opacity[i]).toInt() shl 24)
                    canvas.drawCircle(px, py, radius, paint)
                    if (i % 4 == 0) {
                        paint.alpha = (paint.alpha * .40f).toInt()
                        canvas.drawRect(px - radius * 3f, py - .35f * density,
                            px + radius * 3f, py + .35f * density, paint)
                        canvas.drawRect(px - .35f * density, py - radius * 3f,
                            px + .35f * density, py + radius * 3f, paint)
                    }
                }
                AmbientEffect.OFF -> Unit
            }
            i++
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "AmbientView must be configured on the main thread" }
    }

    companion object {
        private const val MAX_PARTICLES = 32
        private const val MAX_STEPS = 6
        private const val FIXED_STEP = 1f / 60f
        private const val TWO_PI = 6.2831855f
        private val DEFAULT_TINT = Color.rgb(235, 190, 119)
    }
}
