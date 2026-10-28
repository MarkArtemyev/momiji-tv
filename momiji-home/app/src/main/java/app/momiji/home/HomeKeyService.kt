package app.momiji.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Google TV keeps its own launcher on HOME regardless of the preferred home, so HOME is taken over here. */
class HomeKeyService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("home_keys", MODE_PRIVATE) }
    private var longPress = false
    private var lastPackage = ""
    private val queue = ArrayDeque<StopJob>()
    private var job: StopJob? = null

    private class StopJob(val pkg: String, val then: (() -> Unit)?) {
        var step = 0
        var started = 0L
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { vpnAvailable(network) } }
    }

    private val resetAfterVpn = Runnable {
        val foreground = rootInActiveWindow?.packageName?.toString() ?: lastPackage
        val touched = prefs.getStringSet(KEY_TOUCHED, emptySet()).orEmpty()
        val stale = touched.filter { it != foreground }
        if (stale.isEmpty()) return@Runnable
        prefs.edit().putStringSet(KEY_TOUCHED, touched - stale.toSet()).apply()
        Toast.makeText(this, "VPN сменился, сбрасываю YouTube", Toast.LENGTH_SHORT).show()
        stale.forEach { stop(it, null) }
    }

    override fun onServiceConnected() {
        instance = this
        if (rootInActiveWindow?.packageName == GOOGLE_HOME) openHome()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, vpnCallback) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(vpnCallback) }
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // The system rebinds this service when the VPN reconnects, so the last network id lives in prefs, not in fields.
    private fun vpnAvailable(network: Network) {
        val id = network.toString()
        val previous = prefs.getString(KEY_VPN, null)
        if (id == previous) return
        prefs.edit().putString(KEY_VPN, id).apply()
        if (previous == null) return
        main.removeCallbacks(resetAfterVpn)
        main.postDelayed(resetAfterVpn, 3000)
    }

    private fun touch(pkg: String) {
        val touched = prefs.getStringSet(KEY_TOUCHED, emptySet()).orEmpty()
        if (pkg !in touched) prefs.edit().putStringSet(KEY_TOUCHED, touched + pkg).apply()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longPress = false
            } else if (!longPress && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                longPress = true
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
            KeyEvent.ACTION_UP -> if (!longPress && !event.isCanceled) openHome()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in TRANSIENT) return
        if (pkg in RESET_ON_VPN) touch(pkg)
        val entering = pkg == GOOGLE_HOME && lastPackage != GOOGLE_HOME
        lastPackage = pkg
        if (entering && job == null && SystemClock.uptimeMillis() > allowGoogleUntil &&
            event.className?.toString().orEmpty().endsWith(".home.HomeActivity")) openHome()
    }

    private fun openHome() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClass(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { startActivity(intent) } catch (_: Exception) { }
    }

    fun restart(pkg: String) {
        stop(pkg) {
            val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Android 14 lets apps kill only their own processes, so the stop goes through the system "Остановить" button. */
    private fun stop(pkg: String, then: (() -> Unit)?) {
        queue.add(StopJob(pkg, then))
        if (job == null) next()
    }

    private fun next() {
        val j = queue.removeFirstOrNull() ?: return
        job = j
        j.started = SystemClock.uptimeMillis()
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${j.pkg}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(details) } catch (_: Exception) { return finish(j) }
        main.postDelayed(::step, 400)
    }

    private fun step() {
        val j = job ?: return
        if (SystemClock.uptimeMillis() - j.started > 8000) return finish(j)
        val root = rootInActiveWindow
        if (root?.packageName == SETTINGS) {
            when (j.step) {
                0 -> find(root, STOP_LABELS)?.let { button ->
                    if (!button.isEnabled) return finish(j)
                    if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) j.step = 1
                }
                1 -> find(root, OK_LABELS)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) j.step = 2 }
                2 -> if (find(root, OK_LABELS) == null) return finish(j)
            }
        }
        main.postDelayed(::step, 250)
    }

    private fun finish(j: StopJob) {
        if (rootInActiveWindow?.packageName == SETTINGS) performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({
            job = null
            j.then?.invoke()
            next()
        }, 600)
    }

    private fun find(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        for (label in labels) for (node in root.findAccessibilityNodeInfosByText(label)) {
            if (node.text?.toString()?.trim() != label) continue
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            if (target != null) return target
        }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"
        private const val SETTINGS = "com.android.tv.settings"
        private const val LONG_PRESS_MS = 500L
        private const val KEY_VPN = "vpn_network"
        private const val KEY_TOUCHED = "reset_on_vpn"
        private val TRANSIENT = setOf("com.android.systemui", "android")
        private val RESET_ON_VPN = setOf("com.google.android.youtube.tv", "com.google.android.youtube.tvmusic")
        private val STOP_LABELS = setOf("Остановить", "Принудительно остановить", "Force stop")
        private val OK_LABELS = setOf("ОК", "OK", "Ок")

        @Volatile var allowGoogleUntil = 0L
        @Volatile var instance: HomeKeyService? = null

        fun allowGoogleHome() { allowGoogleUntil = SystemClock.uptimeMillis() + 5000 }
    }
}
