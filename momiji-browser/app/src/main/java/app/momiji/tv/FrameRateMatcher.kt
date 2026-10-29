package app.momiji.tv

import android.app.Activity
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Puts the display on the film's own frame rate, with a blank if it must, when the owner set
 * "Match content frame rate" to Always (Android 12+). ExoPlayer itself asks only for seamless switches,
 * so it is told to stay out and Momiji asks with CHANGE_FRAME_RATE_ALWAYS instead. Playback waits
 * while the screen changes mode, so nothing plays into the dark.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FrameRateMatcher(private val activity: Activity, private val view: PlayerView) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var frames: VideoFrameMetadataListener? = null
    private var requestedAt = 0L
    private var refresh = 0f
    var active = false; private set
    /** The content rate asked of the display, or null while it is unknown. */
    var requested: Float? = null; private set
    var holding = false; private set

    /** Read for every new player: the owner may change the setting between openings. */
    fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder {
        active = Build.VERSION.SDK_INT >= 31 && activity.getSystemService(DisplayManager::class.java)
            ?.matchContentFrameRateUserPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS
        return builder.setVideoChangeFrameRateStrategy(
            if (active) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
    }

    fun attach(p: ExoPlayer) {
        if (!active || Build.VERSION.SDK_INT < 31) return
        player = p
        requested = null
        refresh = activity.display?.refreshRate ?: 0f
        p.addAnalyticsListener(formats)
        activity.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, main)
        (view.videoSurfaceView as? SurfaceView)?.holder?.addCallback(surfaces)
    }

    fun detach() {
        val p = player ?: return
        p.removeAnalyticsListener(formats)
        stopMeasuring()
        activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        (view.videoSurfaceView as? SurfaceView)?.holder?.removeCallback(surfaces)
        cancelHold()
        if (Build.VERSION.SDK_INT >= 30) surface()?.let { runCatching { it.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } }
        requested = null
        player = null
    }

    /** Stops waiting for the screen; returns whether playback was being held for it. */
    fun cancelHold(): Boolean {
        val was = holding
        holding = false
        main.removeCallbacks(resume)
        return was
    }

    private val formats = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val declared = FrameRatePlan.snap(format.frameRate)
            if (declared != null) { stopMeasuring(); request(declared) }
            else if (requested == null) measure()
        }
    }

    /** Streams that do not declare a rate are measured over their first frames on the playback thread. */
    private fun measure() {
        val p = player ?: return
        if (frames != null) return
        val estimator = FrameRateEstimator()
        val listener = object : VideoFrameMetadataListener {
            private var done = false
            override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
                if (done) return
                val rate = estimator.add(presentationTimeUs) ?: return
                done = true
                main.post { if (frames === this) { stopMeasuring(); request(rate) } }
            }
        }
        frames = listener
        p.setVideoFrameMetadataListener(listener)
    }

    private fun stopMeasuring() {
        frames?.let { player?.clearVideoFrameMetadataListener(it) }
        frames = null
    }

    private fun request(content: Float) {
        val target = FrameRatePlan.next(requested, content)
        if (target == requested) return
        requested = target
        ask()
    }

    private fun ask() {
        val rate = requested ?: return
        if (Build.VERSION.SDK_INT < 31) return
        val surface = surface() ?: return
        if (runCatching { surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }.isSuccess)
            requestedAt = SystemClock.elapsedRealtime()
    }

    private fun surface(): Surface? = (view.videoSurfaceView as? SurfaceView)?.holder?.surface?.takeIf { it.isValid }

    // The vote belongs to the surface, which Android recreates when the player comes back to the screen.
    private val surfaces = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = ask()
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {}
    }

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val display = (if (Build.VERSION.SDK_INT >= 30) activity.display else null) ?: return
            if (displayId != display.displayId) return
            val rate = display.refreshRate
            val changed = abs(rate - refresh) >= 0.01f
            refresh = rate
            if (changed && SystemClock.elapsedRealtime() - requestedAt < SWITCH_WINDOW_MS) hold()
        }
    }

    private fun hold() {
        val p = player ?: return
        if (!holding && !p.playWhenReady) return
        holding = true
        p.playWhenReady = false
        main.removeCallbacks(resume)
        main.postDelayed(resume, SETTLE_MS)
    }

    private val resume = Runnable { if (holding) { holding = false; player?.playWhenReady = true } }

    private companion object {
        /** A mode change this long after Momiji's request is taken as its answer. */
        const val SWITCH_WINDOW_MS = 5_000L
        /** Long enough for the panel, or an HDMI receiver, to show a picture again. */
        const val SETTLE_MS = 1_500L
    }
}
