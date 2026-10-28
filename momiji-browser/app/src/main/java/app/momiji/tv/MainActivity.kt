package app.momiji.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.momiji.tv.agent.SetupActivity
import app.momiji.tv.agent.VoiceActivity
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : Activity(), RemoteControl.Target {
    private lateinit var protection: ProtectionPreferences
    private lateinit var webNetwork: WebNetworkBinding
    private var navigationVersion=0
    private var protectionState=""
    private var externalNavigationPending=false
    @Volatile private var documentUrl=""
    private val requestHints=RequestHints()

    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var progress: ProgressBar
    private lateinit var fullscreen: FrameLayout
    private lateinit var toolbar: View
    private lateinit var address: EditText
    private lateinit var hint: TextView
    private lateinit var chipStar: TextView
    private lateinit var chipAds: TextView
    private lateinit var chipDesktop: TextView
    private lateinit var chipEngine: TextView
    private lateinit var chipFocus: TextView
    private lateinit var chipLite: TextView
    private lateinit var chipPlayer: TextView
    private val media = MediaCatalog()
    private val filters = Executors.newSingleThreadExecutor { r -> Thread(r, "page-filters").apply { isDaemon = true } }
    private val assetsLoader by lazy { WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build() }
    private val pageScript by lazy { assets.open("browser/page.js").bufferedReader().use { it.readText() } }
    private var startScript: ScriptHandler? = null
    private var pageEpoch = 0L
    private var cleanedEpoch = -1L
    private var lastFrameAt = 0L
    private var resumed = false
    private var remoteClickAt = 0L
    private var pointerMode = false
    private var mediaPollDelay = 2000L

    @Volatile private var adBlock = true
    @Volatile private var onHome = false
    private val blocked = AtomicInteger()
    private var webAlive = true
    private var lastUrl: String? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var toolbarOpen = false
    private var pageFailed = false
    private var defaultUa = ""

    private val held = BooleanArray(4)
    private val heldSince = LongArray(4)
    private val lastArrow = LongArray(4)
    private var ticking = false
    private var okLong = false
    private var topPushSince = 0L
    private var lastBack = 0L

    private val density by lazy { resources.displayMetrics.density }
    private val vFactor by lazy { ViewConfiguration.get(this).scaledVerticalScrollFactor }
    private val hFactor by lazy { ViewConfiguration.get(this).scaledHorizontalScrollFactor }
    private val imm by lazy { getSystemService(InputMethodManager::class.java) }

    private val ticker = object : Runnable {
        override fun run() {
            if (held.none { it }) {
                ticking = false
                lastFrameAt = 0L
                return
            }
            step()
            cursor.postOnAnimation(this)
        }
    }

    private val mediaPoll = object : Runnable {
        override fun run() {
            if (!resumed || !webAlive) return
            if (!onHome) {
                val epoch = pageEpoch
                web.evaluateJavascript("window.__momijiPage ? window.__momijiPage.state() : null") { raw ->
                    if (!webAlive || !resumed || epoch != pageEpoch) return@evaluateJavascript
                    val state = try { JSONObject(raw) } catch (_: Exception) { null }
                    mediaPollDelay = if (state?.optBoolean("hasMedia", true) == false) 6000L else 2000L
                    web.keepScreenOn = state?.optBoolean("playing") == true || customView != null
                    state?.optJSONArray("sources")?.let { sources ->
                        for (i in 0 until min(sources.length(), 12)) media.capture(sources.optString(i), direct = true)
                    }
                    if (toolbarOpen) refreshChips()
                }
            }
            web.postDelayed(this, mediaPollDelay)
        }
    }

    private val hideHint = Runnable {
        hint.animate().alpha(0f).setDuration(300).withEndAction { hint.visibility = View.GONE }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        protection=ProtectionPreferences(this)
        NetworkRoute.configure(protection.config())
        adBlock = store.adBlock
        AdBlock.init(this)
        Cosmetic.init(this)

        web = findViewById(R.id.web)
        webNetwork=WebNetworkBinding(web)
        cursor = findViewById(R.id.cursor)
        progress = findViewById(R.id.progress)
        fullscreen = findViewById(R.id.fullscreen)
        toolbar = findViewById(R.id.toolbar)
        address = findViewById(R.id.address)
        hint = findViewById(R.id.hint)
        chipStar = findViewById(R.id.chip_star)
        chipAds = findViewById(R.id.chip_ads)
        chipDesktop = findViewById(R.id.chip_desktop)
        chipEngine = findViewById(R.id.chip_engine)
        chipFocus = findViewById(R.id.chip_focus)
        chipLite = findViewById(R.id.chip_lite)
        chipPlayer = findViewById(R.id.chip_player)

        setupWeb()
        setupToolbar()

        val external = if (savedInstanceState == null) externalUrl(intent) else null
        val restored = if (external == null) savedInstanceState?.getBundle("web_state")?.let { web.restoreState(it) } else null
        if (restored == null) load(external ?: savedInstanceState?.getString("url") ?: HOME)
        else web.url?.let { track(it) }

        val launches = store.launches
        store.launches = launches + 1
        if (launches < 3) say(getString(R.string.hint_controls), 8000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalUrl(intent)?.let { externalNavigationPending=true; load(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (webAlive) {
            web.onResume()
            val next="${store.adBlock}|${protection.allowedHosts}|${protection.blockPopups}|${protection.blockThirdPartyCookies}|${protection.config()}|${protection.customRules.hashCode()}|${FilterRepository.revision}"
            if(protectionState.isNotEmpty() && protectionState!=next) {
                adBlock=store.adBlock; installStartScript(); refreshChips()
                if(!externalNavigationPending) webNetwork.apply { okay -> if(okay && webAlive) load(web.url ?: HOME) else say("Прокси недоступен: проверь настройки сети Momiji") }
            }
            protectionState=next
            externalNavigationPending=false
            if (onHome) renderHome()
            web.evaluateJavascript("window.momiji&&window.momiji.setActive(true)", null)
            setPointerMode(pointerMode, force = true)
            web.removeCallbacks(mediaPoll)
            web.post(mediaPoll)
        }
        RemoteControl.attach(this)
    }

    override fun onPause() {
        resumed = false
        RemoteControl.detach(this)
        resetHeld()
        if (webAlive) {
            web.removeCallbacks(mediaPoll)
            web.evaluateJavascript("window.__momijiPage&&window.__momijiPage.pauseAll();window.momiji&&window.momiji.setActive(false)", null)
            web.keepScreenOn = false
            web.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        resetHeld()
        hint.removeCallbacks(hideHint)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) startScript?.remove()
        filters.shutdownNow()
        ServiceWorkerController.getInstance().setServiceWorkerClient(null)
        if (webAlive) {
            webAlive = false
            requestHints.clear(); web.removeJavascriptInterface("MomijiShield")
            web.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastUrl?.let { outState.putString("url", it) }
        if (webAlive) Bundle().also { web.saveState(it); outState.putBundle("web_state", it) }
    }

    private fun resetHeld() {
        held.fill(false)
        ticking = false
        lastFrameAt = 0L
        topPushSince = 0L
        if (::cursor.isInitialized) {
            cursor.removeCallbacks(ticker)
            cursor.setPressedLook(false)
        }
    }

    private fun inputConfig() = "{focus:${store.focusMode},pointer:$pointerMode}"

    /** A pointer and keyboard selection must never own the page at the same time. */
    private fun setPointerMode(pointer: Boolean, force: Boolean = false) {
        if (!force && pointerMode == pointer) {
            if (!pointer) cursor.hideNow()
            return
        }
        pointerMode = pointer
        resetHeld()
        if (!pointer) {
            cursor.hideNow()
            this.pointer(MotionEvent.ACTION_HOVER_EXIT, cursor.cx, cursor.cy)
        } else if (!toolbarOpen) {
            web.requestFocusFromTouch()
        }
        if (webAlive) web.evaluateJavascript(
            "window.__momijiPage&&window.__momijiPage.configure(${inputConfig()});" +
                "window.momiji&&window.momiji.setInputMode('${if (pointer) "pointer" else "keys"}')", null)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::cursor.isInitialized && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.actionMasked in intArrayOf(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL)) {
            setPointerMode(true)
            // A real mouse already has the Android system pointer.
            cursor.hideNow()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::cursor.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN) {
            setPointerMode(true)
            cursor.hideNow()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) resetHeld()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        go(text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        web.setBackgroundColor(getColor(R.color.dusk))
        val s = web.settings
        defaultUa = s.userAgentString.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = !store.liteMode
        s.blockNetworkImage = store.liteMode
        s.mediaPlaybackRequiresUserGesture = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // вне touch mode каждый web.requestFocus() переводит фокус на первый элемент страницы и прокручивает её наверх
        @Suppress("DEPRECATION")
        s.setNeedInitialFocus(false)
        applyUa()

        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(requestHints,"MomijiShield")
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, !(adBlock && protection.blockThirdPartyCookies))

        installStartScript()
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = blockedResponse(request)
        })

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { _, _, _, _, _ -> say(getString(R.string.no_downloads)) }
        web.requestFocus()
    }

    private fun installStartScript() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) startScript?.remove()
        startScript = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val cosmetic = if (adBlock) ProtectionScripts.start(protection) else ""
            startScript = WebViewCompat.addDocumentStartJavaScript(web,
                "(function(){if(!/^https?:$/.test(location.protocol)||location.hostname==='appassets.androidplatform.net')return;" +
                    pageScript + ";window.__momijiPage&&window.__momijiPage.configure(${inputConfig()});" + cosmetic + "})();",
                setOf("*"))
        }
    }

    private fun blockedResponse(request: WebResourceRequest): WebResourceResponse? {
        if (!adBlock || request.isForMainFrame || !AdBlock.blocks(filterRequest(request))) return null
        blocked.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
    private fun filterRequest(request: WebResourceRequest): app.momiji.net.FilterRequest {
        val headers=request.requestHeaders.entries.associate { it.key.lowercase() to it.value.lowercase() }
        val path=request.url.path.orEmpty().lowercase(); val dest=headers["sec-fetch-dest"].orEmpty()
        val hint=requestHints.consume(request.url.toString(),request.method)
        val type=hint?.type ?: when {
            request.isForMainFrame->app.momiji.net.ResourceType.DOCUMENT
            dest in setOf("iframe","frame") -> app.momiji.net.ResourceType.SUBDOCUMENT
            dest=="script" || path.endsWith(".js") || path.endsWith(".mjs")->app.momiji.net.ResourceType.SCRIPT
            dest=="style" || path.endsWith(".css")->app.momiji.net.ResourceType.STYLESHEET
            dest=="image" || headers["accept"].orEmpty().startsWith("image/") || Regex("\\.(png|jpe?g|gif|webp|svg|ico|avif)$").containsMatchIn(path)->app.momiji.net.ResourceType.IMAGE
            dest=="font" || Regex("\\.(woff2?|ttf|otf)$").containsMatchIn(path)->app.momiji.net.ResourceType.FONT
            dest in setOf("video","audio") || Regex("\\.(mp4|m3u8|mpd|webm|mp3|m4a|ts)$").containsMatchIn(path)->app.momiji.net.ResourceType.MEDIA
            headers["x-requested-with"]=="xmlhttprequest" || headers["sec-fetch-mode"] in setOf("cors","same-origin")->app.momiji.net.ResourceType.XMLHTTPREQUEST
            else->app.momiji.net.ResourceType.OTHER
        }
        // Pages can call MomijiShield themselves, so the initiator never comes from a hint.
        val initiator=request.requestHeaders.entries.firstOrNull { it.key.equals("Referer",true) }?.value ?: documentUrl
        return app.momiji.net.FilterRequest(request.url.toString(),initiator,type)
    }

    private fun applyUa() {
        web.settings.userAgentString = if (store.desktop) desktopUa() else defaultUa
    }

    private fun desktopUa(): String {
        val chrome = Regex("Chrome/[\\d.]+").find(defaultUa)?.value ?: "Chrome/130.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    private fun setupToolbar() {
        address.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (enter || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                go(address.text.toString())
                true
            } else {
                false
            }
        }
        onClick(R.id.btn_voice) { voice() }
        onClick(R.id.chip_back) { if (web.canGoBack()) web.goBack(); hideToolbar() }
        onClick(R.id.chip_forward) { if (web.canGoForward()) web.goForward(); hideToolbar() }
        onClick(R.id.chip_reload) { web.reload(); hideToolbar() }
        onClick(R.id.chip_home) { load(HOME); hideToolbar() }
        onClick(R.id.chip_zoom_out) { web.zoomOut() }
        onClick(R.id.chip_zoom_in) { web.zoomIn() }
        onClick(R.id.chip_star) { toggleStar() }
        onClick(R.id.chip_ads) {
            store.adBlock = !store.adBlock
            adBlock = store.adBlock
            installStartScript()
            refreshChips()
            web.reload()
        }
        onClick(R.id.chip_protection) { startActivity(Intent(this,NetworkSettingsActivity::class.java).putExtra("page",documentUrl)) }
        onClick(R.id.chip_desktop) {
            store.desktop = !store.desktop
            applyUa()
            refreshChips()
            web.reload()
        }
        onClick(R.id.chip_engine) {
            store.engine = if (store.engine == "yandex") "google" else "yandex"
            refreshChips()
        }
        onClick(R.id.chip_focus) {
            store.focusMode = !store.focusMode
            setPointerMode(!onHome && !store.focusMode, force = true)
            installStartScript()
            refreshChips()
            hideToolbar()
            say(getString(if (store.focusMode) R.string.focus_hint else R.string.cursor_hint), 4500)
        }
        onClick(R.id.chip_lite) {
            store.liteMode = !store.liteMode
            web.settings.loadsImagesAutomatically = !store.liteMode
            web.settings.blockNetworkImage = store.liteMode
            refreshChips()
            web.reload()
            hideToolbar()
        }
        onClick(R.id.chip_player) { openPlayer() }
        onClick(R.id.chip_remote) { connectRemote() }
        onClick(R.id.chip_effects) {
            store.effects = !store.effects
            if (onHome) renderHome()
            refreshChips()
        }
        pop(toolbar)
    }

    private fun onClick(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    private fun pop(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) pop(v.getChildAt(i))
        } else if (v.isFocusable && v !is EditText) {
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.08f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
    }

    private fun refreshChips() {
        val url = web.url
        val page = url != null && url.startsWith("http")
        chipStar.isEnabled = page
        chipStar.setText(if (page && store.hasTile(url!!)) R.string.unstar else R.string.star)
        val cut = blocked.get()
        chipAds.text = when {
            !store.adBlock -> getString(R.string.ads_off)
            cut > 0 -> getString(R.string.ads_on_count, cut)
            else -> getString(R.string.ads_on)
        }
        chipDesktop.setText(if (store.desktop) R.string.desktop_on else R.string.desktop_off)
        chipEngine.text = getString(R.string.engine, getString(if (store.engine == "yandex") R.string.engine_yandex else R.string.engine_google))
        chipFocus.setText(if (store.focusMode) R.string.focus_on else R.string.focus_off)
        chipLite.setText(if (store.liteMode) R.string.lite_on else R.string.lite_off)
        chipPlayer.isEnabled = media.snapshot().isNotEmpty()
        chipPlayer.alpha = if (chipPlayer.isEnabled) 1f else .45f
        findViewById<TextView>(R.id.chip_effects).setText(if (store.effects) R.string.effects_on else R.string.effects_off)
    }

    private fun openPlayer() {
        val streams = media.snapshot()
        if (streams.isEmpty()) { say(getString(R.string.no_stream)); return }
        fun play(stream: MediaCatalog.Stream) {
            hideToolbar()
            startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra("url", stream.url).putExtra("referer", stream.referer ?: web.url)
                .putExtra("page",web.url).putExtra("title",titleOf(web.url ?: stream.url))
                .putExtra("ua", web.settings.userAgentString))
        }
        if (streams.size == 1) play(streams[0])
        else AlertDialog.Builder(this).setTitle(R.string.choose_stream)
            .setItems(streams.map { it.label }.toTypedArray()) { _, which -> play(streams[which]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun connectRemote() {
        try {
            val pairing = RemoteControl.start(applicationContext)
            AlertDialog.Builder(this).setTitle(R.string.remote_title)
                .setMessage(getString(R.string.remote_pair, pairing.urls.joinToString("\n"), pairing.code))
                .setPositiveButton(R.string.remote_ready, null)
                .setNegativeButton(R.string.remote_stop) { _, _ -> RemoteControl.stop() }.show()
        } catch (e: Exception) {
            say(e.message ?: getString(R.string.remote_unavailable), 6000)
        }
    }

    override fun remoteInput(events: org.json.JSONArray) {
        if (!resumed || !webAlive) return
        for (i in 0 until events.length().coerceAtMost(20)) {
            val e = events.optJSONObject(i) ?: continue
            when (e.optString("type")) {
                "move" -> {
                    val x = e.optDouble("x"); val y = e.optDouble("y")
                    if (!x.isFinite() || !y.isFinite()) continue
                    setPointerMode(true)
                    cursor.moveTo(x.toFloat().coerceIn(0f, 1f) * (cursor.width - 1).coerceAtLeast(0),
                        y.toFloat().coerceIn(0f, 1f) * (cursor.height - 1).coerceAtLeast(0))
                    cursor.show()
                    pointer(MotionEvent.ACTION_HOVER_MOVE, cursor.cx, cursor.cy)
                }
                "click" -> {
                    setPointerMode(true)
                    if (!toolbarOpen) web.requestFocusFromTouch()
                    val down = SystemClock.uptimeMillis()
                    remoteClickAt = down
                    val x = cursor.cx; val y = cursor.cy
                    val receiver = if (toolbarOpen) window.decorView else target()
                    touch(receiver, MotionEvent.ACTION_DOWN, down, x, y)
                    cursor.postDelayed({
                        if (resumed && webAlive) touch(receiver, MotionEvent.ACTION_UP, down, x, y)
                    }, 40)
                }
                "scroll" -> {
                    setPointerMode(true)
                    val x = e.optDouble("x", 0.0); val y = e.optDouble("y", 0.0)
                    if (x.isFinite() && y.isFinite()) pointer(MotionEvent.ACTION_SCROLL, cursor.cx, cursor.cy,
                        -y.toFloat().coerceIn(-6f, 6f), x.toFloat().coerceIn(-6f, 6f))
                }
                "text" -> {
                    val text = e.optString("text").take(4096)
                    if (toolbarOpen && address.hasFocus()) {
                        val start = address.selectionStart.coerceAtLeast(0)
                        val end = address.selectionEnd.coerceAtLeast(start)
                        address.text.replace(start, end, text)
                    } else {
                        val epoch = pageEpoch
                        web.postDelayed({
                            if (!resumed || !webAlive || epoch != pageEpoch) return@postDelayed
                            web.evaluateJavascript("window.__momijiPage?window.__momijiPage.type(${JSONObject.quote(text)}):false") { handled ->
                                if (handled != "true" && resumed && webAlive && epoch == pageEpoch) {
                                    web.onCreateInputConnection(EditorInfo())?.commitText(text, 1)
                                }
                            }
                        }, (remoteClickAt + 400 - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                    }
                }
                "open" -> {
                    val url = resolve(e.optString("text").take(4096))
                    if (Uri.parse(url).scheme in setOf("http", "https")) go(url)
                }
                "key" -> {
                    val key = REMOTE_KEYS[e.optString("key")] ?: continue
                    if (key == KeyEvent.KEYCODE_ESCAPE) { if (toolbarOpen) hideToolbar() else back(); continue }
                    setPointerMode(false)
                    val meta = if (e.optBoolean("shift")) KeyEvent.META_SHIFT_ON else 0
                    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        val event = KeyEvent(0, SystemClock.uptimeMillis(), action, key, 0, meta)
                        if (toolbarOpen) dispatchKeyEvent(event) else web.dispatchKeyEvent(event)
                    }
                }
                "command" -> when (val command = e.optString("command")) {
                    "back" -> if (toolbarOpen) hideToolbar() else back()
                    "home" -> { hideToolbar(); load(HOME) }
                    "menu" -> showToolbar(false)
                    "play_pause", "seek_back", "seek_forward" ->
                        web.evaluateJavascript("window.__momijiPage&&window.__momijiPage.media('$command')", null)
                }
            }
        }
    }

    private fun toggleStar() {
        val url = web.url ?: return
        if (!url.startsWith("http")) return
        if (store.hasTile(url)) {
            store.removeTile(url)
            say(getString(R.string.unstarred))
        } else {
            store.addTile(Tile(titleOf(url), url))
            say(getString(R.string.starred))
        }
        refreshChips()
    }

    private fun titleOf(url: String): String {
        val t = web.title
        return if (t.isNullOrBlank() || t.startsWith("http")) Store.hostOf(url) else t
    }

    private fun showToolbar(typing: Boolean) {
        resetHeld()
        // The native toolbar owns keyboard selection until it closes.
        if (webAlive) web.evaluateJavascript(
            "window.__momijiPage&&window.__momijiPage.configure({focus:${store.focusMode},pointer:true});" +
                "window.momiji&&window.momiji.setInputMode('pointer')", null)
        refreshChips()
        val url = web.url
        address.setText(if (url == null || url.startsWith(HOME)) "" else url)
        if (!toolbarOpen) {
            toolbarOpen = true
            toolbar.animate().cancel()
            toolbar.visibility = View.VISIBLE
            toolbar.alpha = 0f
            toolbar.translationY = -20 * density
            toolbar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        cursor.hideNow()
        address.requestFocus()
        address.setSelection(address.text.length)
        if (typing) address.postDelayed({ imm.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT) }, 120)
    }

    private fun hideToolbar() {
        if (!toolbarOpen) return
        toolbarOpen = false
        imm.hideSoftInputFromWindow(address.windowToken, 0)
        toolbar.animate().cancel()
        toolbar.animate().alpha(0f).translationY(-20 * density).setDuration(140)
            .withEndAction { toolbar.visibility = View.GONE }.start()
        if (pointerMode) web.requestFocusFromTouch() else web.requestFocus()
        setPointerMode(pointerMode, force = true)
        if (pointerMode) cursor.show()
    }

    private fun say(text: String, duration: Long = 2500) {
        hint.text = text
        hint.removeCallbacks(hideHint)
        hint.animate().cancel()
        hint.visibility = View.VISIBLE
        hint.animate().alpha(1f).setDuration(200).start()
        hint.postDelayed(hideHint, duration)
    }

    private fun voice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(intent).setPackage(VoiceActivity.KATNISS), REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_VOICE)
            } catch (e2: ActivityNotFoundException) {
                say(getString(R.string.no_voice))
            }
        }
    }

    private fun go(text: String) {
        hideToolbar()
        load(resolve(text))
    }

    private fun load(url: String) {
        if(!webAlive) return
        val version=++navigationVersion
        webNetwork.apply { okay ->
            if(!webAlive || version!=navigationVersion) return@apply
            if(!okay) { say("Этот WebView не поддерживает прокси или он недоступен. Отключи обход в настройках сети Momiji.",7000); return@apply }
            if(url.startsWith(HOME) || !adBlock) web.loadUrl(url)
            else filters.execute {
                var ready=false
                while(!ready && !Thread.currentThread().isInterrupted) ready=FilterRepository.awaitReady() && FilterRepository.awaitIdle()
                if(ready && !Thread.currentThread().isInterrupted) web.post { if(webAlive && version==navigationVersion) web.loadUrl(url) }
            }
        }
    }

    private fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return HOME
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) return s
        if (!s.contains(' ') && ((s.contains('.') && !s.endsWith('.')) || s.startsWith("localhost"))) return "https://$s"
        val q = URLEncoder.encode(s, "UTF-8")
        return if (store.engine == "yandex") "https://yandex.ru/search/?text=$q" else "https://www.google.com/search?q=$q"
    }

    private fun externalUrl(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (data.scheme == "http" || data.scheme == "https") data.toString() else null
    }

    private fun track(url: String) {
        val home = url.startsWith(HOME)
        val changed = home != onHome
        onHome = home
        lastUrl = url
        if (changed) {
            if (!home) cursor.center()
            setPointerMode(!home && !store.focusMode, force = true)
            if (pointerMode && !toolbarOpen) cursor.show()
        }
    }

    private fun renderHome() {
        val data = JSONObject()
            .put("tiles", Store.json(store.tiles))
            .put("recent", Store.json(store.recent.take(8)))
            .put("engine", store.engine)
            .put("reducedMotion", !store.effects || getSystemService(ActivityManager::class.java).isLowRamDevice)
        web.evaluateJavascript("window.momiji&&momiji.render($data)", null)
    }

    private fun command(uri: Uri) {
        when (uri.host) {
            "search" -> showToolbar(true)
            "voice" -> voice()
            "agent" -> startActivity(Intent(this, SetupActivity::class.java))
            "remote" -> connectRemote()
            "menu" -> showToolbar(false)
            "engine" -> {
                store.engine = if (store.engine == "yandex") "google" else "yandex"
                renderHome()
            }
            "remove" -> uri.getQueryParameter("url")?.let {
                store.removeTile(it)
                renderHome()
            }
        }
    }

    private fun openIntent(raw: String) {
        val intent = try {
            Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (!openExternal(intent)) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && fallback.startsWith("http")) load(fallback) else say(getString(R.string.no_app))
        }
    }

    private fun openExternal(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val up = e.action == KeyEvent.ACTION_UP
        if (okLong && isOk(code)) {
            if (up) okLong = false
            return true
        }
        if (toolbarOpen) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_MENU) {
                if (up) hideToolbar()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (up && !e.isCanceled) back()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (up) showToolbar(false)
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (up) showToolbar(true)
                return true
            }
        }
        val navigation = isOk(code) || code in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB)
        val keyboard = e.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
            e.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        if (!up && navigation) setPointerMode(!keyboard && (customView != null || (!onHome && !store.focusMode)))
        if (onHome && customView == null) return super.dispatchKeyEvent(e)
        val mediaAction = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "play_pause"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> "pause"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "seek_back"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek_forward"
            else -> null
        }
        if (mediaAction != null) {
            if (up) web.evaluateJavascript("window.__momijiPage&&window.__momijiPage.media('$mediaAction')", null)
            return true
        }
        // An alphabetic Bluetooth/USB keyboard needs normal caret, Tab and Enter behavior.
        if (keyboard) return super.dispatchKeyEvent(e)
        if (store.focusMode && customView == null) {
            if (isOk(code) && !up && e.repeatCount > 0 && e.eventTime - e.downTime >= LONG_PRESS) {
                okLong = true
                showToolbar(false)
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> arrow(e)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ok(e)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (!up) page(-1)
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (!up) page(1)
                true
            }
            else -> super.dispatchKeyEvent(e)
        }
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun arrow(e: KeyEvent): Boolean {
        val i = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            else -> DOWN
        }
        val now = SystemClock.uptimeMillis()
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (!held[i]) {
                if (now - lastArrow[i] > REPRESS) {
                    heldSince[i] = now
                    nudge(i)
                }
                held[i] = true
                if (!ticking) {
                    ticking = true
                    cursor.postOnAnimation(ticker)
                }
            }
            lastArrow[i] = now
            cursor.show()
        } else if (e.action == KeyEvent.ACTION_UP) {
            held[i] = false
            lastArrow[i] = now
            cursor.scheduleHide()
        }
        return true
    }

    private fun nudge(i: Int) {
        val n = NUDGE * density
        when (i) {
            LEFT -> move(-n, 0f)
            UP -> move(0f, -n)
            RIGHT -> move(n, 0f)
            else -> move(0f, n)
        }
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val frame = if (lastFrameAt == 0L) 1f else (now - lastFrameAt).coerceIn(0L, 40L) / (1000f / 60f)
        lastFrameAt = now
        var dx = 0f
        var dy = 0f
        for (i in 0..3) {
            if (!held[i]) continue
            val t = now - heldSince[i] - HOLD_DELAY
            if (t <= 0) continue
            val v = min(MAX_SPEED, BASE_SPEED + t * ACCEL) * density * frame
            when (i) {
                LEFT -> dx -= v
                UP -> dy -= v
                RIGHT -> dx += v
                else -> dy += v
            }
        }
        if (dx != 0f || dy != 0f) move(dx, dy)
    }

    private fun move(dx: Float, dy: Float) {
        val x = cursor.cx + dx
        val y = cursor.cy + dy
        val nx = x.coerceIn(0f, cursor.width - 1f)
        val ny = y.coerceIn(0f, cursor.height - 1f)
        cursor.moveTo(nx, ny)
        pointer(MotionEvent.ACTION_HOVER_MOVE, nx, ny)
        val sx = x - nx
        val sy = y - ny
        if (sx != 0f || sy != 0f) pointer(MotionEvent.ACTION_SCROLL, nx, ny, -sy / vFactor, sx / hFactor)
        if (sy < 0f && web.scrollY == 0 && customView == null) {
            val now = SystemClock.uptimeMillis()
            if (topPushSince == 0L) topPushSince = now
            else if (now - topPushSince > TOP_PUSH) showToolbar(false)
        } else {
            topPushSince = 0L
        }
    }

    private fun page(dir: Int) {
        cursor.show()
        pointer(MotionEvent.ACTION_SCROLL, cursor.cx, cursor.cy, -dir * cursor.height * 0.8f / vFactor)
    }

    private fun ok(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                cursor.show()
                cursor.setPressedLook(true)
            } else if (e.isLongPress || e.eventTime - e.downTime >= LONG_PRESS) {
                okLong = true
                cursor.setPressedLook(false)
                showToolbar(false)
            }
        } else if (e.action == KeyEvent.ACTION_UP) {
            cursor.setPressedLook(false)
            if (!e.isCanceled) tap()
        }
        return true
    }

    private fun target(): View = customView ?: web

    private fun tap() {
        val v = target()
        if (v === web) web.requestFocusFromTouch()
        val x = cursor.cx
        val y = cursor.cy
        val down = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, down, x, y)
        cursor.postDelayed({ touch(v, MotionEvent.ACTION_UP, down, x, y) }, 40)
    }

    private fun touch(v: View, action: Int, down: Long, x: Float, y: Float) {
        val ev = motion(action, down, x, y, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
        v.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun pointer(action: Int, x: Float, y: Float, v: Float = 0f, h: Float = 0f) {
        val ev = motion(action, SystemClock.uptimeMillis(), x, y, InputDevice.SOURCE_MOUSE, MotionEvent.TOOL_TYPE_MOUSE, v, h)
        target().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun motion(action: Int, down: Long, x: Float, y: Float, source: Int, tool: Int, v: Float = 0f, h: Float = 0f): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = tool
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            if (v != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            if (h != 0f) setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun back() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (onHome) {
            web.evaluateJavascript("window.momiji?momiji.back():false") { if (it != "true") leave() }
            return
        }
        leave()
    }

    private fun leave() {
        val now = SystemClock.uptimeMillis()
        when {
            web.canGoBack() -> web.goBack()
            !onHome -> load(HOME)
            now - lastBack < 2000 -> finish()
            else -> {
                lastBack = now
                say(getString(R.string.press_back_again))
            }
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        web.keepScreenOn = false
        customCallback?.onCustomViewHidden()
        customCallback = null
        if (pointerMode) web.requestFocusFromTouch() else web.requestFocus()
        setPointerMode(!onHome && !store.focusMode, force = true)
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val adRedirect = adBlock && request.isForMainFrame && AdBlock.blocks(filterRequest(request))
                    if (adRedirect) blocked.incrementAndGet()
                    adRedirect
                }
                "about", "data", "blob", "javascript", "file" -> false
                "momiji" -> {
                    if (onHome) command(uri)
                    true
                }
                // Like Chrome, a page may hand off to another app only from a user gesture,
                // so ads and scripted redirects cannot launch the Play Store or other apps.
                "intent" -> {
                    if (request.hasGesture()) openIntent(uri.toString()) else say(getString(R.string.app_blocked))
                    true
                }
                else -> {
                    if (!request.hasGesture()) say(getString(R.string.app_blocked))
                    else if (!openExternal(Intent(Intent.ACTION_VIEW, uri))) say(getString(R.string.no_app))
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            documentUrl=url
            CookieManager.getInstance().setAcceptThirdPartyCookies(web,!(adBlock && protection.blockThirdPartyCookies && !protection.siteAllowed(Uri.parse(url).host.orEmpty())))
            pageEpoch++
            mediaPollDelay = 2000L
            media.clear()
            resetHeld()
            web.keepScreenOn = false
            pageFailed = false
            blocked.set(0)
            track(url)
            setPointerMode(!onHome && !store.focusMode, force = true)
            progress.visibility = View.VISIBLE
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) pageFailed = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) pageFailed = true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            track(url)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            cleanUp(view, url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url.startsWith(HOME)) {
                renderHome()
            } else if (url.startsWith("http") && !pageFailed) {
                store.pushRecent(Tile(titleOf(url), url))
            }
            cleanUp(view, url)
        }

        private fun cleanUp(view: WebView, url: String) {
            if (!webAlive || !url.startsWith("http") || cleanedEpoch == pageEpoch) return
            cleanedEpoch = pageEpoch
            view.evaluateJavascript(pageScript + ";window.__momijiPage&&window.__momijiPage.configure(${inputConfig()});" +
                "window.momiji&&window.momiji.setInputMode('${if (pointerMode) "pointer" else "keys"}')", null)
            if (!adBlock || protection.siteAllowed(Uri.parse(url).host.orEmpty())) return
            view.evaluateJavascript(AdBlock.COSMETIC_JS, null)
            val host = Uri.parse(url).host ?: return
            val epoch = pageEpoch
            filters.execute {
                val script = Cosmetic.script(host) ?: return@execute
                view.post { if (webAlive && epoch == pageEpoch && adBlock) view.evaluateJavascript(script, null) }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            assetsLoader.shouldInterceptRequest(request.url)?.let { return it }
            blockedResponse(request)?.let { return it }
            media.capture(request.url.toString(), request.requestHeaders)
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (webAlive) {
                webAlive = false
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            cursor.scheduleHide()
            web.keepScreenOn = true
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    private companion object {
        const val HOME = "https://appassets.androidplatform.net/assets/home/index.html"
        const val REQ_VOICE = 7
        const val LEFT = 0
        const val UP = 1
        const val RIGHT = 2
        const val DOWN = 3
        const val HOLD_DELAY = 200L
        const val REPRESS = 150L
        const val BASE_SPEED = 3f
        const val ACCEL = 0.012f
        const val MAX_SPEED = 15f
        const val NUDGE = 7f
        const val LONG_PRESS = 550L
        const val TOP_PUSH = 1000L
        val REMOTE_KEYS = mapOf("ArrowLeft" to KeyEvent.KEYCODE_DPAD_LEFT, "ArrowRight" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "ArrowUp" to KeyEvent.KEYCODE_DPAD_UP, "ArrowDown" to KeyEvent.KEYCODE_DPAD_DOWN,
            "Enter" to KeyEvent.KEYCODE_ENTER, "Escape" to KeyEvent.KEYCODE_ESCAPE, "Backspace" to KeyEvent.KEYCODE_DEL,
            "Delete" to KeyEvent.KEYCODE_FORWARD_DEL, "Tab" to KeyEvent.KEYCODE_TAB,
            "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END)
    }
}
