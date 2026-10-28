package app.momiji.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.momiji.home.data.AppCatalog
import app.momiji.home.data.HomeStore
import app.momiji.home.data.StoreSelfCheck
import app.momiji.home.data.OwnedAssets
import app.momiji.home.media.MediaRepository
import app.momiji.home.media.WatchHistory
import app.momiji.home.media.WatchRecord
import app.momiji.home.model.*
import app.momiji.home.ui.ArtworkLoader
import app.momiji.home.ui.HomeBackdrop
import app.momiji.home.ui.HomeTileView
import app.momiji.home.ui.CustomizeDialogs
import app.momiji.home.ui.VisibleArtwork
import app.momiji.home.ui.AmbientView
import app.momiji.home.ui.AmbientEffect
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

class HomeActivity : Activity() {
    private enum class Page { HOME, APPS, GAMES }
    private lateinit var store: HomeStore
    private lateinit var catalog: AppCatalog
    private lateinit var media: MediaRepository
    private lateinit var artwork: ArtworkLoader
    private lateinit var visibleArtwork: VisibleArtwork
    private lateinit var ownedAssets: OwnedAssets
    private lateinit var ambient: AmbientView
    private lateinit var backdrop: HomeBackdrop
    private lateinit var customize: CustomizeDialogs
    private lateinit var history: WatchHistory
    private var watchRecords = emptyList<WatchRecord>()
    private lateinit var shell: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private var appJob: Future<*>? = null
    private var feedJob: Future<*>? = null
    private var wallpaperJob: Future<*>? = null
    private var wallpaperVersion = 0
    private var gridPage = 0
    private var categoryFilter: String? = null
    private var currentLayout = HomeLayout()
    private var currentAliases: Map<String, String> = emptyMap()
    private var currentIcons: Map<String,String> = emptyMap()
    private var pendingIcon: Pair<String,String>? = null
    private val iconVersions=HashMap<String,Int>()
    private var currentGames: Set<String> = emptySet()
    private var pendingExportToken: String? = null
    private var hoveredTile: HomeTileView? = null
    private var restoreFocusPending = false
    private var observersRegistered = false
    private val motionObserver=object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { syncAmbient() } }
    private val powerReceiver=object : BroadcastReceiver() { override fun onReceive(context: Context?,intent: Intent?) { syncAmbient() } }
    private val tiles = ArrayList<HomeTileView>()
    private var apps = emptyList<AppEntry>()
    private var inputs = emptyList<InputEntry>()
    private var feeds = emptyList<FeedSection>()
    private var page = Page.HOME
    private var search = ""
    private var pointerMode = false
    private var loading = true
    private var feedsLoading = true
    private var active = false
    private var loadVersion = 0
    private var renderVersion = 0
    private var savedScroll = 0
    private var savedFocusTag: String? = null
    private var clock: TextView? = null
    private val white = Color.rgb(248, 245, 239)
    private val muted = Color.rgb(191, 195, 201)
    private val minuteTick = object : Runnable {
        override fun run() {
            clock?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            if (active) main.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = HomeStore(this)
        val firstRun=getSharedPreferences("home_ui",MODE_PRIVATE)
        if (!firstRun.getBoolean("performance_configured",false)) {
            if (getSystemService(android.app.ActivityManager::class.java).isLowRamDevice) store.performanceMode=PerformanceMode.LITE
            firstRun.edit().putBoolean("performance_configured",true).apply()
        }
        catalog = AppCatalog(this)
        media = MediaRepository(this)
        history = WatchHistory(this)
        artwork = ArtworkLoader(this)
        visibleArtwork = VisibleArtwork(artwork) { active && !isDestroyed }
        ownedAssets=OwnedAssets(this,store)
        customize = CustomizeDialogs(this, store, { apps }) { render(true) }
        restoreUiState(savedInstanceState)
        runDebugCheck(intent)
        val root = FrameLayout(this)
        backdrop = HomeBackdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        ambient=AmbientView(this)
        root.addView(ambient,FrameLayout.LayoutParams(-1,-1))
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(40), dp(20), dp(40), 0)
        }
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        shell.addView(header, LinearLayout.LayoutParams(-1, dp(44)))
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setOnScrollChangeListener { _,_,_,_,_ -> visibleArtwork.updateSoon() }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(20), 0, dp(40))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        registerMotionObservers()
        io.submit { ownedAssets.pruneOrphans(); pruneOldExports() }
        // No render here: onResume always follows and refresh() renders straight away.
    }

    override fun onResume() {
        super.onResume()
        app.momiji.home.media.HomeNetwork.invalidate()
        active = true
        artwork.resume()
        backdrop.setWallpaper(store.wallpaperUri)
        backdrop.setMode(store.performanceMode)
        main.removeCallbacks(minuteTick)
        minuteTick.run()
        refresh()
        syncAmbient()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        syncAmbient()
    }
    override fun onPause() {
        active = false
        if (::ambient.isInitialized) ambient.setRunning(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runDebugCheck(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            page = Page.HOME
            search = ""
            savedScroll = 0
            savedFocusTag = null
            restoreFocusPending=false
            categoryFilter=null; gridPage=0
            if (active) render(false)
        }
    }

    private fun runDebugCheck(request: Intent) {
        if (BuildConfig.DEBUG && request.getBooleanExtra("self_check", false)) {
            val result = StoreSelfCheck.run(this)
            File(filesDir, "store-self-check.json").writeText(result)
            android.util.Log.i("MomijiHomeCheck", result)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uiProfile",store.activeProfile().id)
        outState.putString("page",page.name); outState.putString("search",search.take(120))
        outState.putString("category",categoryFilter); outState.putInt("gridPage",gridPage)
        outState.putInt("scroll",if (content.childCount>0) scroll.scrollY else savedScroll)
        outState.putString("focus",currentFocus?.tag as? String ?: savedFocusTag)
        outState.putBoolean("pointer",pointerMode)
        pendingIcon?.let { outState.putString("iconProfile",it.first); outState.putString("iconKey",it.second) }
        outState.putString("exportToken",pendingExportToken)
        super.onSaveInstanceState(outState)
    }

    private fun restoreUiState(state: Bundle?) {
        state ?: return
        val iconProfile=state.getString("iconProfile")
        val iconKey=state.getString("iconKey")
        if (iconProfile != null && store.profiles().any { it.id==iconProfile } && !iconKey.isNullOrBlank() && iconKey.length<=1024)
            pendingIcon=iconProfile to iconKey
        pendingExportToken=state.getString("exportToken")?.takeIf { exportFile(it)?.isFile==true }
        if (state.getString("uiProfile") != store.activeProfile().id) return
        page=runCatching { Page.valueOf(state.getString("page").orEmpty()) }.getOrDefault(Page.HOME)
        search=state.getString("search").orEmpty().take(120)
        categoryFilter=state.getString("category")?.take(80)
        gridPage=state.getInt("gridPage").coerceIn(0,10000)
        savedScroll=state.getInt("scroll").coerceIn(0,100000)
        savedFocusTag=state.getString("focus")?.take(2048)
        pointerMode=state.getBoolean("pointer")
        restoreFocusPending=true
    }

    private fun registerMotionObservers() {
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,motionObserver)
        val filter=IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT>=33) registerReceiver(powerReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(powerReceiver,filter)
        observersRegistered=true
    }

    private fun syncAmbient() {
        if (!::ambient.isInitialized) return
        val profile=store.activeProfile(); val layout=store.layout(profile.id)
        val permitted=store.animationsEnabled && store.performanceMode!=PerformanceMode.LITE &&
            ValueAnimator.areAnimatorsEnabled() && !getSystemService(PowerManager::class.java).isPowerSaveMode
        val effect=if (permitted) runCatching { AmbientEffect.valueOf(layout.ambientEffect) }.getOrDefault(AmbientEffect.OFF) else AmbientEffect.OFF
        val colors=intArrayOf(profile.accent,Color.rgb(255,181,91),Color.rgb(114,225,171),Color.rgb(193,162,255),Color.rgb(153,215,255))
        ambient.configure(effect,layout.ambientIntensity,floatArrayOf(.6f,1f,1.4f)[layout.ambientSpeed],colors[layout.ambientColor])
        ambient.visibility=if (page==Page.HOME && effect!=AmbientEffect.OFF) View.VISIBLE else View.GONE
        ambient.setRunning(active && hasWindowFocus() && page==Page.HOME && effect!=AmbientEffect.OFF)
    }

    override fun onStop() {
        savedScroll = scroll.scrollY
        (currentFocus?.tag as? String)?.let { savedFocusTag = it }
        restoreFocusPending=true
        active = false
        loadVersion++
        renderVersion++
        main.removeCallbacks(minuteTick)
        cancelLoads()
        ambient.setRunning(false)
        visibleArtwork.clear()
        artwork.pause()
        artwork.trim()
        content.removeAllViews()
        header.removeAllViews(); clock=null
        tiles.clear()
        hoveredTile=null
        super.onStop()
    }

    override fun onDestroy() {
        wallpaperVersion++
        wallpaperJob?.cancel(true)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        visibleArtwork.clear()
        artwork.close()
        backdrop.close()
        ambient.close()
        history.close()
        if (observersRegistered) {
            contentResolver.unregisterContentObserver(motionObserver)
            unregisterReceiver(powerReceiver)
        }
        if (isFinishing) pendingExportToken?.let { exportFile(it)?.delete() }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        if (level >= TRIM_MEMORY_RUNNING_LOW) artwork.trim()
        super.onTrimMemory(level)
    }

    private fun refresh() {
        feedsLoading=true
        cancelLoads()
        val generation = ++loadVersion
        val profileId = store.activeProfile().id
        render(true)
        appJob = io.submit {
            val foundApps = runCatching { catalog.loadApps() }.getOrDefault(emptyList())
            val foundInputs = runCatching { catalog.inputs() }.getOrDefault(emptyList())
            main.post {
                if (!isCurrent(generation, profileId)) return@post
                val firstApps = apps.isEmpty()
                // Every render rebuilds the whole screen, so skip it when the reload found
                // nothing new; a pending focus/scroll restore still needs one.
                val changed = loading || foundApps != apps || foundInputs != inputs
                apps = foundApps
                inputs = foundInputs
                loading = false
                val seeded = seedFavorites(profileId)
                if (changed || seeded || restoreFocusPending) render(!firstApps)
            }
        }
        feedJob = io.submit {
            val foundFeeds = runCatching { media.load(if (store.performanceMode == PerformanceMode.LITE) 8 else 12) }
                .getOrDefault(emptyList())
            val watched = runCatching { history.list(profileId, limit = 12) }.getOrDefault(emptyList())
            main.post {
                if (!isCurrent(generation, profileId)) return@post
                val changed = foundFeeds != feeds || watched != watchRecords
                feeds = foundFeeds
                watchRecords = watched
                feedsLoading=false
                if (changed || restoreFocusPending) render(true)
            }
        }
    }

    private fun cancelLoads() {
        appJob?.cancel(true)
        feedJob?.cancel(true)
        (io as? ThreadPoolExecutor)?.purge()
    }

    private fun isCurrent(generation: Int, profileId: String): Boolean =
        active && !isDestroyed && generation == loadVersion && store.activeProfile().id == profileId

    /** Returns true when it filled the profile's favorites. */
    private fun seedFavorites(profileId: String): Boolean {
        val uiPrefs = getSharedPreferences("home_ui", MODE_PRIVATE)
        val seeded = uiPrefs.getStringSet("seeded_profiles", emptySet())!!.toMutableSet()
        if (profileId in seeded || apps.isEmpty()) return false
        val empty = store.favorites(profileId).isEmpty()
        if (empty) {
            // Only the companion browser and the stock YouTube app are singled out; the rest are
            // whatever TV apps this device has.
            val packages = listOf("app.momiji.tv", "com.google.android.youtube.tv")
            val preferred = packages.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } }.map { it.key }
            val tvApps = apps.filter { it.isTv }.ifEmpty { apps }
            store.setFavorites(profileId, (preferred + tvApps.take(6).map { it.key }).distinct().take(6))
        }
        seeded.add(profileId)
        uiPrefs.edit().putStringSet("seeded_profiles", seeded).apply()
        return empty
    }

    private fun render(preserveFocus: Boolean) {
        if (!::content.isInitialized) return
        val focusedTag = if (preserveFocus || restoreFocusPending) (if (restoreFocusPending) savedFocusTag else currentFocus?.tag as? String ?: savedFocusTag) else null
        val y = if (!restoreFocusPending && preserveFocus && content.childCount > 0) scroll.scrollY else savedScroll
        renderVersion++
        visibleArtwork.clear()
        hoveredTile=null
        tiles.clear()
        header.removeAllViews()
        content.removeAllViews()
        val profile = store.activeProfile()
        currentLayout = store.layout(profile.id)
        currentAliases = store.aliases(profile.id)
        currentIcons = store.icons(profile.id)
        currentGames = store.gameApps(profile.id)
        syncAmbient()
        val accent = profile.accent
        val brand = text("MOMIJI  HOME", 13f, white).apply {
            letterSpacing = .14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        headerAction(if (page == Page.HOME) "Домой ·" else "Домой", 72, accent, "nav:home") { changePage(Page.HOME) }
        headerAction("Приложения", 102, accent, "nav:apps") { changePage(Page.APPS) }
        headerAction("Игры", 65, accent, "nav:games") { changePage(Page.GAMES) }
        headerAction(profile.name.take(15), 112, accent, "nav:profile") { showProfiles() }
        headerAction("Настройки", 94, accent, "nav:settings") { showSettings() }
        clock = text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 23f, white).apply {
            setPadding(dp(12), 0, 0, 0)
        }
        header.addView(clock, LinearLayout.LayoutParams(-2, -2))
        addHero(profile)
        when (page) {
            Page.HOME -> renderHome(profile)
            Page.APPS -> renderApps(profile, false)
            Page.GAMES -> renderApps(profile, true)
        }
        val focusGeneration=renderVersion
        main.post {
            if (!active || focusGeneration != renderVersion) return@post
            if (restoreFocusPending && focusedTag!=null && tiles.none { it.tag==focusedTag } && (loading || feedsLoading)) {
                visibleArtwork.updateSoon(); return@post
            }
            val target = tiles.firstOrNull { it.tag == focusedTag } ?: tiles.firstOrNull { (it.tag as? String)?.startsWith("app:") == true }
                ?: tiles.firstOrNull { (it.tag as? String)?.startsWith("media:") == true } ?: tiles.firstOrNull()
            if (!pointerMode) {
                if (target?.requestFocus() == true) restoreFocusPending=false
                scroll.scrollTo(0,if (target?.tag==focusedTag) y.coerceAtLeast(0) else 0)
            } else { scroll.scrollTo(0, y.coerceAtLeast(0)); if (!loading && !feedsLoading) restoreFocusPending=false }
            visibleArtwork.updateSoon()
        }
    }

    private fun addHero(profile: HomeProfile) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when (hour) { in 5..11 -> "Доброе утро"; in 12..16 -> "Добрый день"; in 17..22 -> "Добрый вечер"; else -> "Тихой ночи" }
        val title = when (page) { Page.HOME -> greeting; Page.APPS -> "Твои приложения"; Page.GAMES -> "Игровая комната" }
        content.addView(text(title, 32f, white).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        val mode = modeLabel(store.performanceMode)
        val caption = when (page) {
            Page.HOME -> "${SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(Date())}  ·  $mode  ·  ${networkLabel()}"
            Page.APPS -> if (search.isEmpty()) "${categoryFilter?.let { id -> store.categories(profile.id).firstOrNull { it.id == id }?.name } ?: "Все приложения"}  ·  Удерживай OK для настройки карточки" else "Поиск: $search"
            Page.GAMES -> "Игры и быстрый запуск  ·  Фон Momiji приостанавливается во время игры"
        }
        content.addView(text(caption, 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(22) })
    }

    private fun renderHome(profile: HomeProfile) {
        val hidden = store.hiddenApps(profile.id)
        val available = apps.filter { it.key !in hidden }.associateBy { it.key }
        val favoriteApps = store.favorites(profile.id).mapNotNull { available[it] }
        if (favoriteApps.isNotEmpty()) appRow("Быстрый доступ", favoriteApps, profile)
        else actionRow(if (loading) "Подключаем твой экран" else "Быстрый доступ", listOf(
            Triple("Все приложения", "Выбери и закрепи любимые", { changePage(Page.APPS) })
        ), profile)

        val watching = watchRecords.filter { it.profileId == profile.id }
        if (watching.isNotEmpty()) {
            val row = newRow("Продолжить в Momiji · ${profile.name}")
            watching.forEach { record ->
                val position = timeLabel(record.position)
                val duration = timeLabel(record.duration)
                val tile = makeTile(record.title, "$position / $duration  ·  Momiji", "media", profile.accent,"watch:${record.id}")
                tile.progress = (record.position.toFloat()/record.duration.coerceAtLeast(1)).coerceIn(0f,1f)
                tile.badge = "MOMIJI"
                tile.setOnClickListener { launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(record.page)).setPackage("app.momiji.tv")) }
                tile.setOnLongClickListener {
                    AlertDialog.Builder(this).setTitle(record.title).setItems(arrayOf("Открыть страницу", "Убрать из продолжения")) { _, which ->
                        if (which == 0) launchIntent(Intent(Intent.ACTION_VIEW,Uri.parse(record.page)).setPackage("app.momiji.tv"))
                        else { io.submit { history.remove(record.profileId,record.id); main.post { if (active) refresh() } } }
                    }.setNegativeButton("Закрыть",null).show(); true
                }
                row.addView(tile,rowParams(202,116))
            }
        }

        store.categories(profile.id).forEach { category ->
            val entries = category.appKeys.mapNotNull { available[it] }
            if (entries.isNotEmpty()) {
                val row = newRow(category.name)
                entries.take(12).forEach { row.addView(appTile(it, profile,"category:${category.id}"), appRowParams()) }
                val more = makeTile("Вся категория", "${entries.size} приложений", "action", profile.accent, "category:${category.id}")
                more.setOnClickListener { categoryFilter = category.id; page = Page.APPS; search = ""; gridPage = 0; savedScroll = 0; render(false) }
                row.addView(more, appRowParams())
            }
        }

        val now = runCatching { media.nowPlaying() }.getOrNull()
        if (now != null) {
            sectionTitle("Сейчас проигрывается")
            content.addView(text(now.title.take(100), 17f, white), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            actionRow(null, listOf(
                Triple("Назад", now.subtitle.take(30), { runCatching { now.controller.transportControls.skipToPrevious() }; Unit }),
                Triple(if (now.isPlaying) "Пауза" else "Продолжить", "Управление воспроизведением", {
                    runCatching { if (now.isPlaying) now.controller.transportControls.pause() else now.controller.transportControls.play() }
                    main.postDelayed({ if (active) render(true) }, 300); Unit
                }),
                Triple("Дальше", "Следующая запись", { runCatching { now.controller.transportControls.skipToNext() }; Unit })
            ), profile)
        }
        val watch = feeds.firstOrNull { it.key == "watch_next" }
        if (watch != null && currentLayout.showServices) mediaRow(watch, profile)
        val recent = store.recentLaunches(profile.id).mapNotNull { available[it.appKey] }.distinctBy { it.key }.take(10)
        if (recent.isNotEmpty() && currentLayout.showRecent) appRow("Недавно открывали · ${profile.name}", recent, profile)
        if (currentLayout.showServices) feeds.filter { it.key != "watch_next" }.take(4).forEach { mediaRow(it, profile) }
        if (!media.hasTvPermission() && currentLayout.showServices) actionRow("Твой контент", listOf(
            Triple("Добавить карточки", "Продолжение и ряды сервисов", { requestTvCards() })
        ), profile)
        val games = available.values.filter { isGame(it) }
        if (games.isNotEmpty()) appRow("Можно поиграть", games.take(8), profile)
        if (inputs.isNotEmpty() && currentLayout.showInputs) {
            val row = newRow("Входы телевизора")
            inputs.forEach { input ->
                val tile = makeTile(input.label, if (input.connected) "Подключён" else "Внешний источник", "input", profile.accent, "input:${input.id}")
                tile.badge = "HDMI"
                tile.setOnClickListener { if (!catalog.launchInput(input)) toast("Не удалось открыть этот вход") }
                row.addView(tile, rowParams(142, 86))
            }
        }
        actionRow("Под рукой", listOf(
            Triple("Профили", "Своё избранное и недавние", { showProfiles() }),
            Triple("Оформление", modeLabel(store.performanceMode), { showSettings() }),
            Triple("Поиск", "Приложения и доступный контент", { showSearch() })
        ), profile)
    }

    private fun renderApps(profile: HomeProfile, gamesOnly: Boolean) {
        val hidden = store.hiddenApps(profile.id)
        val category = categoryFilter?.let { id -> store.categories(profile.id).firstOrNull { it.id == id } }
        val visible = apps.filter { it.key !in hidden && (!gamesOnly || isGame(it)) &&
            (category == null || it.key in category.appKeys) &&
            (search.isEmpty() || (currentAliases[it.key] ?: it.label).contains(search, true) || it.label.contains(search, true)) }
        if (!gamesOnly) actionRow(null, listOf(
            Triple("Найти", if (search.isEmpty()) "Приложения и карточки" else "Изменить запрос", { showSearch() }),
            Triple("Голосом", "Через распознавание Android", { voiceSearch() }),
            Triple("Категории", "Свои ряды приложений", { customize.categories() })
        ), profile)
        if (gamesOnly) {
            val gamepad = android.view.InputDevice.getDeviceIds().map { android.view.InputDevice.getDevice(it) }.filterNotNull()
                .firstOrNull { (it.sources and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD }
            actionRow(null, listOf(
                Triple("Геймпад", gamepad?.name?.take(32) ?: "Подключить по Bluetooth", { launchIntent(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }),
                Triple("Добавить игру", "Удерживай OK на приложении", { changePage(Page.APPS) }),
                Triple("Режим экрана", modeLabel(store.performanceMode), { showPerformance() })
            ), profile)
        }
        if (visible.isEmpty()) {
            content.addView(text(if (loading) "Загружаем приложения…" else if (gamesOnly) "Установленные игры появятся здесь" else "Ничего не найдено", 19f, white))
        }
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = if (currentLayout.columns >= 3) currentLayout.columns else if (widthDp >= 900) 5 else 4
        val tileWidth = ((widthDp - 80 - (columns - 1) * 12) / columns).toInt()
        val pageSize = columns * 4
        val pages = ((visible.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        gridPage = gridPage.coerceIn(0, pages - 1)
        val grid = GridLayout(this).apply { columnCount = columns; clipChildren = false; clipToPadding = false }
        visible.drop(gridPage * pageSize).take(pageSize).forEachIndexed { index, app ->
            val tile = appTile(app, profile)
            val params = GridLayout.LayoutParams().apply {
                width = dp(tileWidth); height = dp(intArrayOf(86,104,128)[currentLayout.cardSize])
                columnSpec = GridLayout.spec(index % columns)
                rowSpec = GridLayout.spec(index / columns)
                rightMargin = if (index % columns == columns - 1) 0 else dp(12)
                bottomMargin = dp(14)
            }
            grid.addView(tile, params)
        }
        content.addView(grid, LinearLayout.LayoutParams(-1, -2))
        if (pages > 1) actionRow("Страница ${gridPage+1} из $pages", listOf(
            Triple("Назад", "Предыдущие приложения", { gridPage = (gridPage-1+pages)%pages; savedScroll=0; render(false) }),
            Triple("Вперёд", "Следующие приложения", { gridPage = (gridPage+1)%pages; savedScroll=0; render(false) })
        ), profile)
        if (search.isNotBlank() && !gamesOnly) feeds.map { section ->
            section.copy(cards = section.cards.filter { it.title.contains(search,true) || it.subtitle.contains(search,true) })
        }.filter { it.cards.isNotEmpty() }.take(5).forEach { mediaRow(it, profile) }
    }

    private fun appRow(title: String, entries: List<AppEntry>, profile: HomeProfile) {
        val row = newRow(title)
        entries.take(16).forEach { row.addView(appTile(it, profile,"row:$title"), appRowParams()) }
    }
    private fun appRowParams() = rowParams(intArrayOf(132,156,190)[currentLayout.cardSize], intArrayOf(86,104,128)[currentLayout.cardSize])
    private fun isGame(app: AppEntry) = app.isGame || app.key in currentGames

    private fun appTile(app: AppEntry, profile: HomeProfile,scope: String="grid"): HomeTileView {
        val tile = makeTile(currentAliases[app.key] ?: app.label, if (isGame(app)) "Игра" else "", "app", profile.accent, "app:$scope:${app.key}")
        val custom=currentIcons[app.key]
        val showLabel=currentLayout.showAppLabels
        visibleArtwork.bind(tile) { ready ->
            if (custom == null) artwork.loadApp(app,showLabel,ready)
            else artwork.loadUri(custom) { bitmap ->
                if (bitmap != null) ready(bitmap) else artwork.loadApp(app,showLabel,ready)
            }
        }
        tile.setOnClickListener {
            savedFocusTag = tile.tag as? String
            val owner = store.activeProfile().id
            if (catalog.launch(app)) {
                store.recordLaunch(owner, app)
                if (app.isGame) artwork.trim()
            } else toast("Не удалось открыть ${app.label}")
        }
        tile.setOnLongClickListener { showAppMenu(app, profile.id); true }
        return tile
    }

    private fun mediaRow(section: FeedSection, profile: HomeProfile) {
        if (section.cards.isEmpty()) return
        val row = newRow(section.title)
        section.cards.forEach { card ->
            val tile = makeTile(card.title, card.subtitle, "media", profile.accent, "media:${section.key}:${card.id}")
            if (card.durationMs > 0) tile.progress = (card.positionMs.toFloat() / card.durationMs).coerceIn(0f, 1f)
            card.posterUri?.let { uri -> visibleArtwork.bind(tile) { ready -> artwork.loadUri(uri,ready) } }
            tile.setOnClickListener { if (!media.open(card)) toast("Открой запись в приложении сервиса") }
            row.addView(tile, rowParams(202, 132))
        }
        if (section.key == "watch_next") content.addView(text("Карточки сервисов общие; аккаунт выбирается в самом сервисе", 10f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(-9); bottomMargin = dp(16) })
    }

    private fun actionRow(title: String?, actions: List<Triple<String, String, () -> Unit>>, profile: HomeProfile) {
        val row = newRow(title)
        actions.forEach { (label, subtitle, action) ->
            val tile = makeTile(label, subtitle, "action", profile.accent, "action:$label")
            tile.setOnClickListener { action() }
            row.addView(tile, rowParams(194, 76))
        }
    }

    private fun newRow(title: String?): LinearLayout {
        if (title != null) sectionTitle(title)
        val viewport = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(5), dp(5), dp(5), dp(8))
            setOnScrollChangeListener { _,_,_,_,_ -> visibleArtwork.updateSoon() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        viewport.addView(row, FrameLayout.LayoutParams(-2, -2))
        content.addView(viewport, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(-5); rightMargin = dp(-5); bottomMargin = dp(15) })
        return row
    }

    private fun sectionTitle(title: String) {
        content.addView(text(title, 15f, white).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun makeTile(label: String, sub: String, kind: String, tint: Int, tag: String): HomeTileView = HomeTileView(this).apply {
        title = label
        subtitle = sub
        accent = tint
        this.kind = kind
        this.tag = tag
        roundCards = currentLayout.roundCards
        largeText = currentLayout.largeText
        showAppLabel=currentLayout.showAppLabels
        fontFamily = arrayOf("sans-serif-medium","sans-serif-condensed","serif","monospace")[currentLayout.fontStyle]
        id = View.generateViewId()
        performanceMode = if (store.animationsEnabled) store.performanceMode else PerformanceMode.LITE
        setPointerMode(pointerMode)
        setOnHoverListener { view,event ->
            if (event.actionMasked==MotionEvent.ACTION_HOVER_EXIT) { if (hoveredTile===view) hoveredTile=null }
            else if (pointerMode) hoveredTile=this
            false
        }
        tiles.add(this)
    }

    private fun headerAction(label: String, width: Int, tint: Int, tag: String, action: () -> Unit) {
        val tile = makeTile(label, "", "action", tint, tag)
        tile.setOnClickListener { action() }
        header.addView(tile, LinearLayout.LayoutParams(dp(width), dp(36)).apply { leftMargin = dp(5) })
    }

    private fun changePage(value: Page) {
        restoreFocusPending=false
        page = value
        gridPage = 0
        categoryFilter = null
        if (value != Page.APPS) search = ""
        savedScroll = 0
        savedFocusTag = null
        render(false)
    }

    private fun restartApp(pkg: String) {
        val service = HomeKeyService.instance
        if (service != null) {
            toast("Перезапускаю…")
            service.restart(pkg)
        } else {
            toast("Включи «Кнопка Home: Momiji» в спецвозможностях, а пока нажми «Остановить» сам")
            launchIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
    }

    private fun showAppMenu(app: AppEntry, profileId: String) {
        val favorites = store.favorites(profileId)
        val index = favorites.indexOf(app.key)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) { labels.add(label); actions.add(action) }
        add(if (index >= 0) "Убрать из быстрого доступа" else "Закрепить в быстром доступе") {
            store.setFavorites(profileId, if (index >= 0) favorites.filter { it != app.key } else favorites + app.key)
            render(true)
        }
        if (index > 0) add("Передвинуть влево") {
            val reordered = favorites.toMutableList(); java.util.Collections.swap(reordered, index, index - 1)
            store.setFavorites(profileId, reordered); render(true)
        }
        if (index >= 0 && index < favorites.lastIndex) add("Передвинуть вправо") {
            val reordered = favorites.toMutableList(); java.util.Collections.swap(reordered, index, index + 1)
            store.setFavorites(profileId, reordered); render(true)
        }
        add("Скрыть в этом профиле") { store.setHidden(profileId, app.key, true); render(true) }
        add("Добавить в категорию") { customize.appCategories(profileId, app) }
        add("Название карточки") { customize.renameApp(profileId, app) }
        add("Своя картинка карточки") { chooseAppIcon(profileId,app.key) }
        if (app.key in store.icons(profileId)) add("Вернуть картинку приложения") {
            val requestKey="$profileId|${app.key}"; iconVersions[requestKey]=(iconVersions[requestKey] ?: 0)+1
            val old=store.icons(profileId)[app.key]; store.setIcon(profileId,app.key,null); deletePrivateIcon(old); render(true)
        }
        if (!app.isGame) add(if (app.key in store.gameApps(profileId)) "Убрать из игровой комнаты" else "В игровую комнату") {
            store.setGame(profileId, app.key, app.key !in store.gameApps(profileId)); render(true)
        }
        if (app.packageName != packageName) add("Перезапустить") { restartApp(app.packageName) }
        add("О приложении") { launchIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
        AlertDialog.Builder(this).setTitle(app.label).setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun showProfiles() {
        val profiles = store.profiles()
        val activeId = store.activeProfile().id
        val labels = profiles.map { (if (it.id == activeId) "●  " else "○  ") + it.name } + listOf("＋  Новый профиль", "Настроить текущий профиль")
        AlertDialog.Builder(this).setTitle("Кто смотрит?").setItems(labels.toTypedArray()) { _, which ->
            when {
                which < profiles.size -> { store.selectProfile(profiles[which].id); savedScroll = 0; refresh() }
                which == profiles.size -> editProfileName(null)
                else -> manageProfile()
            }
        }.setNegativeButton("Закрыть", null).show()
    }

    private fun editProfileName(id: String?) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Имя профиля"
            filters = arrayOf(InputFilter.LengthFilter(24))
            if (id != null) setText(store.profiles().firstOrNull { it.id == id }?.name.orEmpty())
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), dp(8)); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dialog = AlertDialog.Builder(this).setTitle(if (id == null) "Новый профиль" else "Имя профиля").setView(box)
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "Введи имя"; return@setOnClickListener }
                try {
                    if (id == null) store.selectProfile(store.createProfile(name).id) else store.renameProfile(id, name)
                } catch (error: IllegalArgumentException) {
                    input.error = error.message ?: "Не удалось сохранить профиль"
                    return@setOnClickListener
                }
                dialog.dismiss(); savedScroll = 0; refresh()
            }
        }
        dialog.show()
        input.requestFocus()
    }

    private fun manageProfile() {
        val profile = store.activeProfile()
        AlertDialog.Builder(this).setTitle(profile.name).setItems(arrayOf("Изменить имя", "Очистить недавние запуски", "Удалить профиль", "Как работают профили", "Очистить просмотр Momiji")) { _, which ->
            when (which) {
                0 -> editProfileName(profile.id)
                1 -> { store.clearHistory(profile.id); render(true) }
                2 -> AlertDialog.Builder(this).setTitle("Удалить ${profile.name}?")
                    .setMessage("Избранное и недавние запуски этого профиля будут удалены.")
                    .setPositiveButton("Удалить") { _, _ ->
                        val oldIcons=store.icons(profile.id).values.toList()
                        if (store.deleteProfile(profile.id)) {
                            io.submit { history.clear(profile.id); oldIcons.forEach { ownedAssets.removeUnusedIcon(it) } }
                            refresh()
                        } else toast("Нужно оставить хотя бы один профиль")
                    }.setNegativeButton("Отмена", null).show()
                3 -> AlertDialog.Builder(this).setTitle("Локальные профили")
                    .setMessage("У каждого профиля свои категории, оформление, избранное, скрытые приложения, запуски и просмотр в нативном плеере Momiji Browser 0.2.2+. Чтобы продолжить видео, открой его страницу и запусти нативный плеер. Аккаунты YouTube, Кинопоиска и других сервисов выбираются внутри сервисов; их карточки могут быть общими.")
                    .setPositiveButton("Понятно", null).show()
                4 -> AlertDialog.Builder(this).setTitle("Очистить просмотр · ${profile.name}?")
                    .setMessage("Сохранённые позиции видео Momiji этого профиля будут удалены.")
                    .setPositiveButton("Очистить") { _, _ -> io.submit { history.clear(profile.id); main.post { if (active) refresh() } } }
                    .setNegativeButton("Отмена",null).show()
            }
        }.setNegativeButton("Закрыть", null).show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "Оформление профиля", "Мои категории", "Профили", "Перенос настроек",
            "Производительность · ${modeLabel(store.performanceMode)}",
            "Анимации · ${if (store.animationsEnabled) "включены" else "выключены"}",
            "Выбрать свои обои", "Вернуть фон Momiji", "Скрытые приложения",
            "Карточки сервисов", "Медиапульт и уведомления", "Настройки Android", "Открыть Google TV", "О Momiji Home", "Состояние устройства", "Атмосферные эффекты", "Сеть и защита Momiji"
        )
        AlertDialog.Builder(this).setTitle("Настроить свой экран").setItems(labels) { _, which ->
            when (which) {
                0 -> customize.appearance()
                1 -> customize.categories()
                2 -> showProfiles()
                3 -> showBackup()
                4 -> showPerformance()
                5 -> { store.animationsEnabled = !store.animationsEnabled; render(true) }
                6 -> chooseWallpaper()
                7 -> resetWallpaper()
                8 -> showHiddenApps()
                9 -> requestTvCards()
                10 -> launchIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                11 -> launchIntent(Intent(Settings.ACTION_SETTINGS))
                12 -> { HomeKeyService.allowGoogleHome(); openPackage(HomeKeyService.GOOGLE_HOME) }
                13 -> AlertDialog.Builder(this).setTitle("Momiji Home ${BuildConfig.VERSION_NAME}")
                    .setMessage("Твой главный экран: категории, оформление карточек, профили, избранное, раздельный просмотр в нативном плеере Momiji Browser 0.2.2+, доступные карточки сервисов и медиапульт.\n\nУдерживай OK на приложении для настройки. Поиск: кнопка Search или Ctrl+F. Меню: кнопка Menu. При запуске игры фоновые загрузки и оформление Momiji останавливаются.\n\nЛокальные профили Momiji не переключают аккаунты других сервисов.")
                    .setPositiveButton("Хорошо", null).show()
                14 -> showDeviceInfo()
                15 -> showAmbientSettings()
                16 -> {
                    try { startActivity(Intent().setClassName("app.momiji.tv","app.momiji.tv.NetworkSettingsActivity")) }
                    catch(_: Exception) { toast("Нужна новая версия Momiji Browser с настройками сети") }
                }
            }
        }.setNegativeButton("Закрыть", null).show()
    }

    private fun showAmbientSettings() {
        val id=store.activeProfile().id
        val layout=store.layout(id)
        val effectNames=arrayOf("Выключены","Кленовые листья","Светлячки","Звёзды")
        val effectValues=arrayOf("OFF","MAPLE","FIREFLIES","STARS")
        val densityNames=arrayOf("Редкие","Средние","Густые")
        val speedNames=arrayOf("Медленно","Обычно","Быстро")
        val colorNames=arrayOf("Цвет профиля","Янтарь","Нефрит","Лаванда","Лёд")
        val effectIndex=effectValues.indexOf(layout.ambientEffect).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Атмосфера · ${store.activeProfile().name}")
            .setItems(arrayOf("Эффект · ${effectNames[effectIndex]}","Плотность · ${densityNames[layout.ambientIntensity]}",
                "Скорость · ${speedNames[layout.ambientSpeed]}","Цвет · ${colorNames[layout.ambientColor]}","Когда работают эффекты")) { _, which ->
                fun choose(title: String,choices: Array<String>,selected: Int,apply: (Int)->HomeLayout) {
                    AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(choices,selected) { dialog,index ->
                        if (store.profiles().any { it.id==id }) { store.setLayout(id,apply(index)); syncAmbient() }
                        dialog.dismiss()
                    }.setNegativeButton("Отмена",null).show()
                }
                when(which) {
                    0 -> choose("Выбрать эффект",effectNames,effectIndex) { layout.copy(ambientEffect=effectValues[it]) }
                    1 -> choose("Плотность",densityNames,layout.ambientIntensity) { layout.copy(ambientIntensity=it) }
                    2 -> choose("Скорость",speedNames,layout.ambientSpeed) { layout.copy(ambientSpeed=it) }
                    3 -> choose("Цвет",colorNames,layout.ambientColor) { layout.copy(ambientColor=it) }
                    4 -> AlertDialog.Builder(this).setTitle("Лёгкая атмосфера")
                        .setMessage("Эффект виден на главном экране. Уход в приложение, игра, экономный режим, системное отключение анимаций и выключатель «Анимации» останавливают его.\n\nВыбор сохраняется отдельно для каждого профиля. На слабом ТВ начни с редкой плотности.")
                        .setPositiveButton("Понятно",null).show()
                }
            }.setNegativeButton("Закрыть",null).show()
    }

    private fun showPerformance() {
        val modes = PerformanceMode.entries.toTypedArray()
        AlertDialog.Builder(this).setTitle("Режим оформления").setSingleChoiceItems(
            arrayOf("Экономный · минимум эффектов", "Сбалансированный · плавный фокус", "Кинотеатр · выразительное оформление"),
            modes.indexOf(store.performanceMode)
        ) { dialog, which ->
            store.performanceMode = modes[which]
            backdrop.setMode(modes[which]); artwork.trim(); dialog.dismiss(); refresh()
        }.setNegativeButton("Закрыть", null).show()
    }
    private fun showDeviceInfo() {
        val manager=getSystemService(android.app.ActivityManager::class.java)
        val memory=android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val mib=1024*1024
        AlertDialog.Builder(this).setTitle("Состояние устройства")
            .setMessage("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}\n\nСеть: ${networkLabel()}\nОЗУ свободно: ${memory.availMem/mib} из ${memory.totalMem/mib} МБ\nЛимит памяти Momiji: ${manager.memoryClass} МБ\nРежим: ${modeLabel(store.performanceMode)}\n\nСистема управляет ресурсами игр. Momiji останавливает свои загрузки и анимации при уходе с главного экрана.")
            .setPositiveButton("Готово",null).show()
    }

    private fun showHiddenApps() {
        val profileId = store.activeProfile().id
        val hidden = store.hiddenApps(profileId)
        val choices = apps.toList()
        AlertDialog.Builder(this).setTitle("Скрыть для ${store.activeProfile().name}")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), choices.map { it.key in hidden }.toBooleanArray()) { _, which, checked ->
                if (store.profiles().any { it.id == profileId }) store.setHidden(profileId, choices[which].key, checked)
            }.setPositiveButton("Готово") { _, _ -> render(true) }.show()
    }

    private fun showSearch() {
        val input = EditText(this).apply { setSingleLine(true); hint = "Приложение или доступный контент"; setText(search); filters = arrayOf(InputFilter.LengthFilter(120)) }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), dp(8)); addView(input) }
        AlertDialog.Builder(this).setTitle("Найти на своём экране").setView(box)
            .setPositiveButton("Найти") { _, _ -> applySearch(input.text.toString()) }
            .setNeutralButton("Все") { _, _ -> search = ""; changePage(Page.APPS) }.setNegativeButton("Отмена", null).show()
    }
    private fun applySearch(query: String) {
        search = query.trim().take(120); categoryFilter = null; gridPage = 0
        page = Page.APPS; savedScroll = 0; render(false)
    }
    private fun voiceSearch() {
        try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PROMPT, "Что найти в Momiji?"), 41)
        } catch (_: Exception) { toast("Голосовой поиск недоступен. Можно ввести запрос с клавиатуры"); showSearch() }
    }

    private fun requestTvCards() {
        if (media.hasTvPermission()) { refresh(); toast("Обновляем доступные карточки сервисов"); return }
        AlertDialog.Builder(this).setTitle("Карточки сервисов")
            .setMessage("Разреши чтение доступных телепрограмм, чтобы показать продолжение просмотра и ряды из установленных приложений.")
            .setPositiveButton("Разрешить") { _, _ -> requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"), 21) }
            .setNegativeButton("Позже", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 21) refresh()
    }

    private fun chooseWallpaper() {
        AlertDialog.Builder(this).setTitle("Свои обои").setItems(arrayOf("Выбрать файл", "Из папки Momiji", "Вернуть фон Momiji")) { _, which ->
            when (which) {
                0 -> chooseWallpaperDocument()
                1 -> chooseMediaFolderWallpaper()
                2 -> resetWallpaper()
            }
        }.setNegativeButton("Закрыть", null).show()
    }

    private fun chooseWallpaperDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, 31) } catch (_: Exception) { chooseMediaFolderWallpaper() }
    }

    private fun chooseMediaFolderWallpaper() {
        val folder = externalMediaDirs.firstOrNull() ?: run { toast("Папка изображений недоступна"); return }
        folder.mkdirs()
        val choices = folder.listFiles()?.filter {
            it.isFile && it.length() in 1..20L * 1024 * 1024 &&
                it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedBy { it.name.lowercase(Locale.getDefault()) }.orEmpty().take(100)
        if (choices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Папка Momiji")
                .setMessage("Добавь JPG, PNG или WebP в папку:\n\n${folder.absolutePath}\n\nПосле этого выбери здесь своё изображение. Размер файла — до 20 МБ.")
                .setPositiveButton("Понятно", null).show()
        } else AlertDialog.Builder(this).setTitle("Изображения в папке Momiji")
            .setItems(choices.map { it.name }.toTypedArray()) { _, which -> importWallpaper { choices[which].inputStream() } }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun importWallpaper(open: () -> InputStream?) {
        wallpaperJob?.cancel(true)
        val generation = ++wallpaperVersion
        toast("Сохраняем обои…")
        wallpaperJob = io.submit {
            val directory = File(filesDir, "wallpapers").apply { mkdirs() }
            val file = File(directory, "wallpaper-${java.util.UUID.randomUUID()}.img")
            try {
                val source = open() ?: throw IllegalArgumentException("Не удалось открыть изображение")
                source.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var count = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= 20L * 1024 * 1024) { "Изображение больше 20 МБ" }
                        output.write(buffer, 0, size)
                    }
                } }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "Не удалось прочитать изображение" }
                main.post {
                    if (isDestroyed || generation != wallpaperVersion) { file.delete(); return@post }
                    val old = store.wallpaperUri
                    store.wallpaperUri = Uri.fromFile(file).toString()
                    backdrop.setWallpaper(store.wallpaperUri)
                    old?.let { previous -> runCatching {
                        val uri = Uri.parse(previous)
                        if (uri.scheme == "file") {
                            val oldFile = File(uri.path.orEmpty()).canonicalFile
                            if (oldFile.parentFile == directory.canonicalFile && oldFile != file) oldFile.delete()
                        }
                    } }
                    toast("Обои применены")
                }
            } catch (error: Exception) {
                file.delete()
                if (error !is InterruptedException) main.post { if (!isDestroyed && generation == wallpaperVersion) toast(error.message ?: "Не удалось сохранить обои") }
            }
        }
    }

    private fun resetWallpaper() {
        wallpaperVersion++; wallpaperJob?.cancel(true)
        val previous = store.wallpaperUri
        store.wallpaperUri = null; backdrop.setWallpaper(null); render(true)
        previous?.let { runCatching {
            val uri = Uri.parse(it)
            if (uri.scheme == "file") {
                val file = File(uri.path.orEmpty()).canonicalFile
                if (file.parentFile == File(filesDir,"wallpapers").canonicalFile) file.delete()
            }
        } }
    }

    private fun chooseAppIcon(profileId: String, key: String) {
        AlertDialog.Builder(this).setTitle("Картинка карточки")
            .setItems(arrayOf("Выбрать файл", "Из папки Momiji")) { _, which ->
                if (which == 1) chooseFolderIcon(profileId,key)
                else {
                    pendingIcon=profileId to key
                    try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),34) }
                    catch (_: Exception) { pendingIcon=null; chooseFolderIcon(profileId,key) }
                }
            }.setNegativeButton("Отмена",null).show()
    }
    private fun chooseFolderIcon(profileId: String,key: String) {
        val folder=externalMediaDirs.firstOrNull() ?: return
        folder.mkdirs()
        val choices=folder.listFiles()?.filter { it.isFile && it.length() in 1..4L*1024*1024 && it.extension.lowercase(Locale.ROOT) in setOf("png","jpg","jpeg","webp") }
            ?.sortedBy { it.name }?.take(100).orEmpty()
        if (choices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Картинки Momiji").setMessage("Добавь JPG, PNG или WebP до 4 МБ в:\n\n${folder.absolutePath}")
                .setPositiveButton("Понятно",null).show(); return
        }
        AlertDialog.Builder(this).setTitle("Картинки карточки").setItems(choices.map { it.name }.toTypedArray()) { _, index ->
            importAppIcon(profileId,key) { choices[index].inputStream() }
        }.setNegativeButton("Отмена",null).show()
    }
    private fun importAppIcon(profileId: String,key: String,open: () -> InputStream?) {
        val requestKey="$profileId|$key"; val generation=(iconVersions[requestKey] ?: 0)+1
        iconVersions[requestKey]=generation
        io.submit {
            val directory=File(filesDir,"icons").apply { mkdirs() }
            val file=File(directory,"icon-${java.util.UUID.randomUUID()}.img")
            try {
                (open() ?: error("Файл недоступен")).use { input -> file.outputStream().use { output ->
                    val buffer=ByteArray(8192); var total=0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val size=input.read(buffer); if (size<0) break
                        total+=size; require(total <= 4L*1024*1024) { "Картинка больше 4 МБ" }; output.write(buffer,0,size)
                    }
                } }
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.absolutePath,bounds)
                require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000) { "Не удалось прочитать картинку" }
                main.post {
                    if (isDestroyed || iconVersions[requestKey] != generation || store.profiles().none { it.id == profileId }) { file.delete(); return@post }
                    val old=store.icons(profileId)[key]
                    store.setIcon(profileId,key,Uri.fromFile(file).toString()); deletePrivateIcon(old)
                    if (active) render(true); toast("Картинка сохранена")
                }
            } catch (error: Exception) {
                file.delete()
                if (error !is InterruptedException) main.post { if (!isDestroyed) toast(error.message ?: "Не удалось сохранить картинку") }
            }
        }
    }
    private fun deletePrivateIcon(uri: String?) {
        if (uri != null) io.submit { ownedAssets.removeUnusedIcon(uri) }
    }

    private fun showBackup() {
        AlertDialog.Builder(this).setTitle("Перенос настроек")
            .setMessage("Файл хранит оформление текущего профиля, категории и ярлыки. История запусков, аккаунты и изображения в него не входят. Импорт создаёт отдельный профиль.")
            .setPositiveButton("Экспорт") { _, _ ->
                prepareExport()
            }.setNeutralButton("Импорт") { _, _ ->
                try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),33)
                } catch (_: Exception) { importFromFolder() }
            }.setNegativeButton("Закрыть", null).show()
    }
    private fun exportFile(token: String): File? = runCatching {
        if (!token.matches(Regex("profile-[a-f0-9-]{36}\\.json"))) return null
        val directory=File(cacheDir,"exports").canonicalFile
        File(directory,token).canonicalFile.takeIf { it.parentFile==directory }
    }.getOrNull()

    private fun pruneOldExports() {
        val directory=File(cacheDir,"exports").canonicalFile
        directory.listFiles()?.forEach { file ->
            if (Thread.currentThread().isInterrupted) return
            val owned=exportFile(file.name)
            if (owned!=null && owned==file.canonicalFile && owned.isFile && System.currentTimeMillis()-owned.lastModified()>86_400_000) owned.delete()
        }
    }

    private fun prepareExport() {
        val profileId=store.activeProfile().id
        io.submit {
            var snapshot: File?=null
            try {
                val bytes=store.exportProfile(profileId).toByteArray(Charsets.UTF_8)
                require(bytes.size<=262_144) { "Настройки больше 256 КБ" }
                val token="profile-${java.util.UUID.randomUUID()}.json"
                snapshot=exportFile(token) ?: error("Папка экспорта недоступна")
                snapshot.parentFile?.mkdirs(); snapshot.writeBytes(bytes)
                val prepared=snapshot
                main.post {
                    if (isDestroyed || !active) { prepared.delete(); return@post }
                    pendingExportToken?.let { exportFile(it)?.delete() }
                    pendingExportToken=token
                    try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE,"Momiji-profile.json"),32)
                    } catch (_: Exception) { exportToFolder() }
                }
            } catch (error: Exception) {
                snapshot?.delete()
                main.post { if (!isDestroyed) toast(error.message ?: "Не удалось подготовить настройки") }
            }
        }
    }

    private fun exportToFolder() {
        val token=pendingExportToken ?: return; pendingExportToken=null
        val snapshot=exportFile(token) ?: return
        val folder = externalMediaDirs.firstOrNull() ?: run { snapshot.delete(); toast("Папка Momiji недоступна"); return }
        io.submit { runCatching {
            require(snapshot.isFile && snapshot.length() in 1..262_144)
            folder.mkdirs(); val file = File(folder,"Momiji-profile-${System.currentTimeMillis()}.json"); snapshot.copyTo(file)
            main.post { if (!isDestroyed) AlertDialog.Builder(this).setTitle("Настройки сохранены").setMessage(file.absolutePath).setPositiveButton("Готово",null).show() }
        }.onFailure { main.post { if (!isDestroyed) toast("Не удалось сохранить файл") } }; snapshot.delete() }
    }
    private fun importFromFolder() {
        val folder = externalMediaDirs.firstOrNull() ?: return
        val choices = folder.listFiles()?.filter { it.isFile && it.extension.equals("json",true) && it.length() in 1..262_144 }
            ?.sortedBy { it.name }?.take(100).orEmpty()
        if (choices.isEmpty()) { toast("Добавь файл настроек JSON в ${folder.absolutePath}"); return }
        AlertDialog.Builder(this).setTitle("Импорт профиля").setItems(choices.map { it.name }.toTypedArray()) { _, which ->
            readProfileFile { choices[which].inputStream() }
        }.setNegativeButton("Отмена",null).show()
    }
    private fun readProfileFile(open: () -> InputStream?) {
        io.submit {
            try {
                val payload = (open() ?: throw IllegalArgumentException("Файл недоступен")).use { stream ->
                    val bytes = java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                    while (true) { val count=stream.read(buffer); if (count<0) break
                        require(bytes.size()+count <= 262_144) { "Файл больше 256 КБ" }; bytes.write(buffer,0,count) }
                    bytes.toString("UTF-8")
                }
                val root = org.json.JSONObject(payload)
                require(root.optString("format") == "momiji-home-profile" && root.optInt("version") == 1) { "Это не файл настроек Momiji" }
                val name = root.getJSONObject("profile").optString("name","Профиль").take(40)
                main.post { if (!isDestroyed) AlertDialog.Builder(this).setTitle("Добавить профиль «$name»?")
                    .setMessage("Текущие профили сохранятся.").setPositiveButton("Импортировать") { _, _ ->
                        try {
                            val imported = store.importProfile(payload)
                            val prefs=getSharedPreferences("home_ui",MODE_PRIVATE)
                            val seeded=prefs.getStringSet("seeded_profiles",emptySet())!!.toMutableSet()
                            seeded.add(imported.id)
                            prefs.edit().putStringSet("seeded_profiles",seeded).apply()
                            savedScroll=0
                            refresh()
                            toast("Профиль добавлен")
                        } catch (error: Exception) { toast(error.message ?: "Не удалось импортировать профиль") }
                    }.setNegativeButton("Отмена",null).show() }
            } catch (error: Exception) { main.post { if (!isDestroyed) toast(error.message ?: "Не удалось прочитать настройки") } }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 31 && resultCode == RESULT_OK) data?.data?.let { uri ->
            if (uri.scheme != "content") { toast("Выбери изображение через файловый менеджер"); return@let }
            importWallpaper { contentResolver.openInputStream(uri) }
        }
        if (requestCode == 32) {
            val snapshot=pendingExportToken?.let { exportFile(it) }; pendingExportToken=null
            val uri=data?.data?.takeIf { it.scheme=="content" }
            if (resultCode==RESULT_OK && uri!=null && snapshot!=null) {
                io.submit { runCatching {
                    require(snapshot.isFile && snapshot.length() in 1..262_144) { "Снимок настроек недоступен; повтори экспорт" }
                    snapshot.inputStream().use { source -> (contentResolver.openOutputStream(uri,"wt") ?: error("Файл недоступен")).use { source.copyTo(it) } }
                }
                    .onSuccess { main.post { if (!isDestroyed) toast("Настройки сохранены") } }
                    .onFailure { main.post { if (!isDestroyed) toast(it.message ?: "Не удалось сохранить настройки") } }; snapshot.delete() }
            } else { snapshot?.delete(); if (resultCode==RESULT_OK) toast("Снимок настроек недоступен; повтори экспорт") }
        }
        if (requestCode == 33 && resultCode == RESULT_OK) data?.data?.takeIf { it.scheme == "content" }?.let { uri -> readProfileFile { contentResolver.openInputStream(uri) } }
        if (requestCode == 34) {
            val target=pendingIcon; pendingIcon=null
            if (resultCode == RESULT_OK && target != null) data?.data?.takeIf { it.scheme == "content" }?.let { uri ->
                importAppIcon(target.first,target.second) { contentResolver.openInputStream(uri) }
            }
        }
        if (requestCode == 41 && resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { applySearch(it) }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE || event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_SCROLL)) {
            setPointerMode(true)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) setPointerMode(true)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE)) {
            val target=if (pointerMode) hoveredTile else null
            setPointerMode(false)
            if (target?.isAttachedToWindow==true && target.isShown) target.requestFocusFromTouch()
        }
        if (event.keyCode==KeyEvent.KEYCODE_BUTTON_A && currentFocus is HomeTileView) {
            if (event.action==KeyEvent.ACTION_UP && !event.isCanceled) currentFocus?.performClick()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) { showSettings(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH && event.action == KeyEvent.ACTION_UP) { showSearch(); return true }
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_F && event.action == KeyEvent.ACTION_UP) { showSearch(); return true }
        return super.dispatchKeyEvent(event)
    }

    private fun setPointerMode(value: Boolean) {
        if (pointerMode == value) return
        pointerMode = value
        tiles.forEach { it.setPointerMode(value) }
        if (!value) hoveredTile=null
        window.decorView.pointerIcon = PointerIcon.getSystemIcon(this, if (value) PointerIcon.TYPE_DEFAULT else PointerIcon.TYPE_NULL)
    }

    override fun onBackPressed() {
        when {
            page != Page.HOME -> changePage(Page.HOME)
            scroll.scrollY > dp(20) -> scroll.smoothScrollTo(0, 0)
            else -> super.onBackPressed()
        }
    }

    private fun launchIntent(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Этот экран недоступен на телевизоре") }
    }
    private fun openPackage(name: String) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(name) ?: packageManager.getLaunchIntentForPackage(name)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(name)
        launchIntent(launch)
    }

    private fun networkLabel(): String = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Без сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Сеть через VPN"
            else -> "В сети"
        }
    }.getOrDefault("В сети")

    private fun modeLabel(mode: PerformanceMode): String = when (mode) {
        PerformanceMode.LITE -> "Экономный"
        PerformanceMode.BALANCED -> "Сбалансированный"
        PerformanceMode.CINEMA -> "Кинотеатр"
    }
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun timeLabel(ms: Long): String {
        val seconds=(ms/1000).coerceAtLeast(0)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60)
        else "%d:%02d".format(seconds/60,seconds%60)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun rowParams(width: Int, height: Int) = LinearLayout.LayoutParams(dp(width), dp(height)).apply { rightMargin = dp(12) }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
