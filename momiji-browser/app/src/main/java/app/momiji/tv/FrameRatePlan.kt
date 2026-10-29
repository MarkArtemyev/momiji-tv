package app.momiji.tv

import kotlin.math.abs

/**
 * Which frame rate the native player asks the display for when the owner set "Match content frame rate"
 * to Always (Momiji Home's cinema preset turns it on). Pure logic, checked on the JVM (tests/display).
 */
object FrameRatePlan {
    /** Film and broadcast rates. 23.976 and 24 are different modes, and so are 59.94 and 60. */
    val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)

    /** The nearest standard rate within 1 %; variable or unusual rates stay with the system. */
    fun snap(rate: Float): Float? {
        if (!rate.isFinite() || rate <= 0f) return null
        val nearest = STANDARD.minBy { abs(it - rate) }
        return nearest.takeIf { abs(it - rate) <= it * 0.01f }
    }

    /**
     * Keeps the current request when it already shows the new content without judder (50 for a 25 fps
     * variant), so an adaptive stream does not blank the screen on a quality change.
     */
    fun next(requested: Float?, content: Float): Float {
        if (requested == null) return content
        val ratio = requested / content
        val whole = Math.round(ratio)
        return if (whole >= 1 && abs(ratio - whole) < 1e-4f * whole) requested else content
    }

    /** "23,976" and "24", so statistics tell the two apart. */
    fun number(rate: Float): String {
        val thousandths = Math.round(rate * 1000)
        return if (thousandths % 1000 == 0) (thousandths / 1000).toString()
            else (thousandths / 1000.0).toString().replace('.', ',')
    }
}

/**
 * Measures the frame rate from presentation times when the stream does not declare it (MPEG-TS, HLS
 * without FRAME-RATE). Frames arrive in display order; seeks and gaps restart the count, and dropped
 * frames, which show up as double steps, are left out of the average.
 */
class FrameRateEstimator(private val window: Int = 60) {
    private val steps = LongArray(window)
    private var count = 0
    private var last = Long.MIN_VALUE

    /** Feeds one rendered frame; returns a standard rate once [window] steps agree, otherwise null. */
    fun add(presentationUs: Long): Float? {
        val previous = last
        last = presentationUs
        if (previous == Long.MIN_VALUE) return null
        val step = presentationUs - previous
        if (step <= 0 || step > 200_000) { count = 0; return null }
        steps[count % window] = step
        count++
        if (count < window) return null
        val median = steps.sorted()[window / 2]
        val steady = steps.filter { abs(it - median) * 20 <= median }
        if (steady.size < window * 3 / 4) return null
        return FrameRatePlan.snap(1_000_000f * steady.size / steady.sum())
    }
}
