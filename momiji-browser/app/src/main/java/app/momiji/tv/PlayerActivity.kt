package app.momiji.tv

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Optional native playback for discoverable HTTP media; the web player remains the fallback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity(), RemoteControl.Target {
    private lateinit var view: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var menu: TextView
    private lateinit var frameRate: FrameRateMatcher
    private var progressStore: PlaybackStore? = null
    private lateinit var homeHistory: HomeHistoryClient
    private var historySession: HomeHistoryClient.Session? = null
    private var historyResolved = false
    private var playbackScope = PlaybackScopePolicy.PENDING
    private var capturedProfile: String? = null
    private var capturedPage: String? = null
    private var started=false
    private var player: ExoPlayer? = null
    private var position = 0L
    private var resumePlayback = true
    private var errorShown = false
    private var foreground = false
    private var seekOnReady = true
    private var preparedAt = 0L
    private var firstFrameMs = -1L
    private var bandwidthEstimate = 0L
    private var stats = PlaybackStatsListener(false, null)
    private var optionsDialog: AlertDialog? = null
    private var pointerActive = false
    private var pointerX = Float.NaN
    private var pointerY = Float.NaN

    private val checkpoint = object : Runnable {
        override fun run() {
            if (!foreground) return
            saveProgress()
            root.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri = Uri.parse(intent.getStringExtra("url").orEmpty())
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) { finish(); return }
        val page = intent.getStringExtra("page")
        val saved = state?.let { PlaybackScopePolicy.Snapshot(it.getString("playback_scope"),
            it.getString("captured_profile"),it.getString("captured_page"),it.getLong("position").coerceAtLeast(0)) }
        val request = PlaybackScopePolicy.request(saved,page)
        homeHistory = HomeHistoryClient(this)
        homeHistory.resolve(page,intent.getStringExtra("title").orEmpty(),request.profile,
            captureActive=request.kind == PlaybackScopePolicy.RequestKind.FRESH) { result ->
            if (isDestroyed || isFinishing) return@resolve
            val decision=PlaybackScopePolicy.decide(request,saved,uri.toString(),page,result.home,
                result.session?.profile,result.session?.page)
            historySession=result.session.takeIf { decision.useSession }
            capturedProfile=historySession?.profile
            capturedPage=historySession?.page
            playbackScope=decision.scope
            progressStore=decision.localKey?.let { PlaybackStore(this,it) }
            if (decision.standalone) migrateStandaloneProgress(uri.toString())
            position=when {
                decision.restorePosition -> saved?.position ?: 0L
                historySession != null -> historySession!!.position
                else -> progressStore?.position() ?: 0L
            }
            historyResolved=true
            if (started) startPlayer()
        }
        resumePlayback = state?.getBoolean("playing", true) ?: true
        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 4000
        }
        frameRate = FrameRateMatcher(this, view)
        root = FrameLayout(this)
        cursor = CursorView(this, null)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        menu = TextView(this).apply {
            setText(R.string.video_menu)
            setTextColor(0xFFFFD39A.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                setColor(0xDF251C2D.toInt())
                cornerRadius = 12 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), 0x70FFB347)
            }
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { _, focused ->
                setTextColor(if (focused) Color.WHITE else 0xFFFFD39A.toInt())
                scaleX = if (focused) 1.05f else 1f
                scaleY = scaleX
            }
            setOnClickListener { showOptions() }
        }
        root.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (18 * d).toInt(); rightMargin = (18 * d).toInt()
        })
        cursor.bringToFront()
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            menu.visibility = visibility
            if (visibility != View.VISIBLE && menu.hasFocus()) view.requestFocus()
        })
        setContentView(root)
        view.requestFocus()
    }

    /** 0.2.1 used the raw stream hash. Migration is allowed only with Home truly absent. */
    private fun migrateStandaloneProgress(source: String) {
        val target=progressStore ?: return
        val old=PlaybackStore(this,source)
        val previous=old.position()
        if (target.position() == 0L && previous > 0) target.save(previous,Long.MAX_VALUE)
        // Clear the old key even if the scoped key already exists: completion must never
        // resurrect a stale legacy position on a future open.
        old.clear()
    }

    override fun onStart() {
        super.onStart()
        started=true
        startPlayer()
    }
    private fun startPlayer() {
        if (!::view.isInitialized || !historyResolved || player != null) return
        errorShown = false
        seekOnReady = true
        firstFrameMs = -1L
        bandwidthEstimate = 0L
        stats = PlaybackStatsListener(false, null)
        val http = MediaNetwork.httpFactory(this, intent.getStringExtra("ua"), intent.getStringExtra("referer"))
        // Cookies and authorization are deliberately not copied to an entire adaptive stream:
        // its playlists and segments can live on different hosts. Authenticated/DRM video uses WebView.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setBackBuffer(5_000, true).build()
        player = frameRate.configure(ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(load).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build().also { p ->
                view.player = p
                frameRate.attach(p)
                p.setAudioAttributes(AudioAttributes.DEFAULT, true)
                p.setHandleAudioBecomingNoisy(true)
                p.addAnalyticsListener(stats)
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                        if (firstFrameMs < 0) firstFrameMs = (SystemClock.elapsedRealtime() - preparedAt).coerceAtLeast(0L)
                    }
                    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                        bandwidthEstimate = bitrateEstimate.coerceAtLeast(0L)
                    }
                })
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { view.keepScreenOn = isPlaying }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && seekOnReady) {
                            seekOnReady = false
                            if (!p.isCurrentMediaItemLive && p.isCurrentMediaItemSeekable &&
                                position > 0 && p.duration > position + 30_000) p.seekTo(position)
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            progressStore?.clear(); homeHistory.save(historySession,p.currentPosition,p.duration,ended=true)
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        showPlayerError()
                    }
                })
                p.setMediaItem(MediaItem.fromUri(intent.getStringExtra("url")!!))
                preparedAt = SystemClock.elapsedRealtime()
                p.prepare()
                p.playWhenReady = resumePlayback
            }
    }

    override fun onPause() {
        foreground = false
        RemoteControl.detach(this)
        if (::root.isInitialized) root.removeCallbacks(checkpoint)
        saveProgress()
        if (BuildConfig.DEBUG) player?.let { p ->
            java.io.File(filesDir,"native-playback-check.json").writeText(org.json.JSONObject()
                .put("position",p.currentPosition).put("duration",p.duration).put("resumeTarget",position)
                .put("firstFrameMs",firstFrameMs).put("profileCaptured",historySession != null)
                .put("profile",capturedProfile).put("scope",playbackScope).toString())
        }
        // A pause held for a display mode change is not the viewer's pause.
        player?.let { val held = frameRate.cancelHold(); resumePlayback = it.playWhenReady || held; if (!seekOnReady) position = it.currentPosition; it.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        RemoteControl.attach(this)
        player?.playWhenReady = resumePlayback
        if (player?.playerError != null) showPlayerError()
        if (::root.isInitialized) { root.removeCallbacks(checkpoint); root.postDelayed(checkpoint, 30_000) }
    }

    override fun onStop() {
        started=false
        optionsDialog?.dismiss()
        optionsDialog = null
        if (::view.isInitialized) {
            view.keepScreenOn = false
            view.player = null
        }
        if (::frameRate.isInitialized) frameRate.detach()
        player?.release()
        player = null
        super.onStop()
    }
    override fun onDestroy() {
        if (::homeHistory.isInitialized) homeHistory.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", if (seekOnReady) position else player?.currentPosition ?: position)
        outState.putBoolean("playing", if (foreground) player?.playWhenReady ?: resumePlayback else resumePlayback)
        outState.putString("playback_scope",playbackScope)
        outState.putString("captured_profile",capturedProfile)
        outState.putString("captured_page",capturedPage)
        super.onSaveInstanceState(outState)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        optionsDialog?.takeIf { it.isShowing }?.let { return it.dispatchKeyEvent(event) }
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_INFO) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (event.keyCode == KeyEvent.KEYCODE_INFO) showStats() else showOptions()
            }
            return true
        }
        if (navigationKey(event.keyCode)) { pointerActive = false; cursor.hideNow() }
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) frameRate.cancelHold()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::cursor.isInitialized && event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            pointerActive = false
            cursor.hideNow()
            root.requestFocusFromTouch()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::cursor.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerActive = false
            cursor.hideNow()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun saveProgress() {
        val p = player ?: return
        if (!historyResolved || seekOnReady || p.isCurrentMediaItemLive || !p.isCurrentMediaItemSeekable || p.duration <= 0) return
        progressStore?.save(p.currentPosition, p.duration)
        homeHistory.save(historySession,p.currentPosition,p.duration)
    }

    private fun showPlayerError() {
        if (!foreground || isFinishing || errorShown) return
        errorShown = true
        showDialog(AlertDialog.Builder(this).setTitle(R.string.player_error_title).setMessage(R.string.player_error)
            .setPositiveButton(R.string.player_return) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create())
    }

    private fun showDialog(dialog: AlertDialog) {
        if (!foreground || isFinishing) return
        optionsDialog?.dismiss()
        optionsDialog = dialog
        dialog.setOnDismissListener {
            if (optionsDialog === dialog) { optionsDialog = null; attachCursor(root) }
        }
        dialog.setOnKeyListener { _, key, _ ->
            if (navigationKey(key)) { pointerActive = false; cursor.hideNow() }
            false
        }
        dialog.show()
        attachCursor(pointerTarget())
    }

    private fun navigationKey(key: Int) = key in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

    private fun pointerTarget(): ViewGroup = optionsDialog?.takeIf { it.isShowing }?.window?.decorView as? ViewGroup ?: root

    private fun attachCursor(surface: ViewGroup) {
        if (cursor.parent !== surface) {
            (cursor.parent as? ViewGroup)?.removeView(cursor)
            surface.addView(cursor, ViewGroup.LayoutParams(-1, -1))
        }
        cursor.bringToFront()
        surface.post { placeCursor(); if (pointerActive) cursor.show() else cursor.hideNow() }
    }

    /** Keep remote coordinates in the activity's coordinate space, including while a dialog owns the cursor. */
    private fun placeCursor() {
        if (!pointerX.isFinite()) { pointerX = root.width / 2f; pointerY = root.height / 2f }
        val origin = IntArray(2); val overlay = IntArray(2)
        root.getLocationOnScreen(origin); cursor.getLocationOnScreen(overlay)
        cursor.moveTo(pointerX + origin[0] - overlay[0], pointerY + origin[1] - overlay[1])
    }

    private fun pointerCoordinates(surface: View): Pair<Float, Float> {
        val origin = IntArray(2); val target = IntArray(2)
        root.getLocationOnScreen(origin); surface.getLocationOnScreen(target)
        return Pair(pointerX + origin[0] - target[0], pointerY + origin[1] - target[1])
    }

    private fun activatePointer(surface: ViewGroup) {
        pointerActive = true
        surface.requestFocusFromTouch()
        placeCursor()
        cursor.show()
    }

    private fun hover(surface: ViewGroup, scroll: Float = 0f) {
        val (x, y) = pointerCoordinates(surface)
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            if (scroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, scroll)
        })
        val event = MotionEvent.obtain(now, now, if (scroll == 0f) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_SCROLL,
            1, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        surface.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private fun showOptions() {
        if (player == null) return
        showDialog(AlertDialog.Builder(this).setTitle(R.string.video_options)
            .setItems(arrayOf(getString(R.string.video_quality), getString(R.string.video_restart), getString(R.string.video_stats))) { _, choice ->
                when (choice) {
                    0 -> showQuality()
                    1 -> { progressStore?.clear(); homeHistory.forget(historySession); position = 0L; player?.seekToDefaultPosition() }
                    2 -> showStats()
                }
            }.setNegativeButton(android.R.string.cancel, null).create())
    }

    private data class VideoChoice(val group: Tracks.Group, val track: Int, val format: Format)

    private fun formatLabel(format: Format): String {
        val size = if (format.height > 0) "${format.height}p" else format.sampleMimeType?.substringAfter('/') ?: getString(R.string.video_unknown)
        val fps = if (format.frameRate > 0) " · ${FrameRatePlan.number(format.frameRate)} fps" else ""
        val rate = if (format.bitrate > 0) " · ${getString(R.string.video_mbps, format.bitrate / 1_000_000.0)}" else ""
        return size + fps + rate
    }

    private fun showQuality() {
        val p = player ?: return
        val choices = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { VideoChoice(group, it, group.getTrackFormat(it)) }
        }.sortedWith(compareByDescending<VideoChoice> { it.format.height }.thenByDescending { it.format.bitrate })
        if (choices.isEmpty()) {
            showDialog(AlertDialog.Builder(this).setTitle(R.string.video_quality).setMessage(R.string.video_quality_loading)
                .setPositiveButton(android.R.string.ok, null).create())
            return
        }
        val labels = arrayOf(getString(R.string.video_quality_auto)) + choices.mapIndexed { index, choice ->
            val label = formatLabel(choice.format)
            if (choices.count { formatLabel(it.format) == label } > 1) "$label · ${getString(R.string.video_track, index + 1)}" else label
        }
        val override = p.trackSelectionParameters.overrides.values.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        val current = choices.indexOfFirst { override != null && override.mediaTrackGroup == it.group.mediaTrackGroup && override.trackIndices.contains(it.track) } + 1
        showDialog(AlertDialog.Builder(this).setTitle(R.string.video_quality)
            .setSingleChoiceItems(labels, if (override == null) 0 else current) { dialog, which ->
                val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (which > 0) choices[which - 1].let { builder.setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.track)) }
                p.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).create())
    }

    private fun showStats() {
        val p = player ?: return
        val snapshot = stats.combinedPlaybackStats
        fun seconds(ms: Long) = if (ms >= 0) getString(R.string.video_seconds, ms / 1000.0) else getString(R.string.video_unknown)
        @Suppress("DEPRECATION")
        val refresh = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay)?.refreshRate
        val screen = when {
            refresh == null -> getString(R.string.video_unknown)
            !frameRate.active -> getString(R.string.video_screen, FrameRatePlan.number(refresh))
            else -> frameRate.requested?.let { getString(R.string.video_screen_matched, FrameRatePlan.number(refresh), FrameRatePlan.number(it)) }
                ?: getString(R.string.video_screen_measuring, FrameRatePlan.number(refresh))
        }
        val detail = getString(R.string.video_stats_values,
            seconds(firstFrameMs), p.videoFormat?.let(::formatLabel) ?: getString(R.string.video_unknown),
            seconds((p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)), snapshot.totalRebufferCount,
            seconds(snapshot.totalRebufferTimeMs), snapshot.totalDroppedFrames,
            if (bandwidthEstimate > 0) getString(R.string.video_mbps, bandwidthEstimate / 1_000_000.0) else getString(R.string.video_unknown), screen)
        showDialog(AlertDialog.Builder(this).setTitle(R.string.video_stats).setMessage(detail)
            .setPositiveButton(android.R.string.ok, null).create())
    }

    override fun remoteInput(events: org.json.JSONArray) {
        val p = player ?: return
        for (i in 0 until events.length().coerceAtMost(20)) {
            val e = events.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "move" -> {
                    val x = e.optDouble("x"); val y = e.optDouble("y")
                    if (!x.isFinite() || !y.isFinite()) continue
                    pointerX = x.toFloat().coerceIn(0f, 1f) * (root.width - 1).coerceAtLeast(0)
                    pointerY = y.toFloat().coerceIn(0f, 1f) * (root.height - 1).coerceAtLeast(0)
                    val surface = pointerTarget()
                    activatePointer(surface)
                    hover(surface)
                    if (surface === root) view.showController()
                }
                "click" -> {
                    val receiver = pointerTarget()
                    activatePointer(receiver)
                    val now = SystemClock.uptimeMillis()
                    val (x, y) = pointerCoordinates(receiver)
                    if (receiver !== root && (x < 0 || y < 0 || x >= receiver.width || y >= receiver.height)) {
                        optionsDialog?.cancel(); continue
                    }
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    receiver.dispatchTouchEvent(down); down.recycle()
                    receiver.postDelayed({
                        if (foreground && !isFinishing && receiver.isAttachedToWindow) {
                            val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                            receiver.dispatchTouchEvent(up); up.recycle()
                        }
                    }, 40)
                }
                "scroll" -> {
                    val y = e.optDouble("y", 0.0)
                    if (y.isFinite()) {
                        val receiver = pointerTarget()
                        if (receiver !== root) { activatePointer(receiver); hover(receiver, -y.toFloat().coerceIn(-6f, 6f)) }
                        else if (y > 0) p.seekForward() else if (y < 0) p.seekBack()
                    }
                }
                "command" -> when (e.optString("command")) {
                    "back" -> optionsDialog?.takeIf { it.isShowing }?.dismiss() ?: finish()
                    "home" -> finish()
                    "menu" -> showOptions()
                    "play_pause" -> if (p.isPlaying) p.pause() else p.play()
                    "seek_back" -> p.seekBack()
                    "seek_forward" -> p.seekForward()
                }
                "text" -> if (e.optString("text") == " ") { if (p.isPlaying) p.pause() else p.play() }
                "key" -> {
                    val key = when (e.optString("key")) {
                        "ArrowLeft" -> KeyEvent.KEYCODE_DPAD_LEFT
                        "ArrowRight" -> KeyEvent.KEYCODE_DPAD_RIGHT
                        "ArrowUp" -> KeyEvent.KEYCODE_DPAD_UP
                        "ArrowDown" -> KeyEvent.KEYCODE_DPAD_DOWN
                        "Enter" -> KeyEvent.KEYCODE_DPAD_CENTER
                        "Escape" -> { optionsDialog?.takeIf { it.isShowing }?.dismiss() ?: finish(); continue }
                        else -> continue
                    }
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
        }
    }
}
