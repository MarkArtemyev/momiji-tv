package app.momiji.tv.agent

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AgentService : AccessibilityService() {

    private lateinit var prefs: AgentPrefs
    private lateinit var overlay: Overlay
    private lateinit var audio: AudioManager
    private lateinit var agent: Agent
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "agent").apply { isDaemon = true } }
    private val shots = Executors.newSingleThreadExecutor()
    private var apps: Map<String, String> = emptyMap()
    private var appsAt = 0L

    override fun onServiceConnected() {
        instance = this
        prefs = AgentPrefs(this)
        overlay = Overlay(this)
        audio = getSystemService(AudioManager::class.java)
        agent = Agent(this, prefs)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
    }

    override fun onDestroy() {
        instance = null
        // The system may destroy the service before onServiceConnected ever ran.
        if (::agent.isInitialized) agent.cancel()
        tts?.shutdown()
        if (::overlay.isInitialized) overlay.remove()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (prefs.learning) {
            if (code in RESERVED) return false
            if (event.action == KeyEvent.ACTION_UP) {
                prefs.trigger = code
                prefs.learning = false
                main.post { overlay.show("Кнопка вызова: ${keyName(code)}", 4000) }
            }
            return true
        }
        if (code != prefs.trigger || code == 0) return false
        if (event.action == KeyEvent.ACTION_UP) main.post { listen() }
        return true
    }

    fun listen() {
        agent.cancel()
        tts?.stop()
        if (prefs.key.isBlank()) {
            overlay.show("Сначала задай ключ Gemini: Momiji › Пульт ИИ", 6000)
            return
        }
        overlay.show("Слушаю…")
        val intent = Intent(this, VoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
    }

    fun onSpeech(text: String?) {
        if (text.isNullOrBlank()) {
            overlay.remove()
            return
        }
        command(text)
    }

    fun command(text: String) {
        if (prefs.key.isBlank()) {
            overlay.show("Сначала задай ключ Gemini: Momiji › Пульт ИИ", 6000)
            return
        }
        val ticket = agent.next()
        overlay.show("» $text")
        worker.execute { agent.run(text, ticket) }
    }

    fun status(text: String) {
        main.post { overlay.show(text) }
    }

    fun finish(say: String) {
        main.post {
            if (say.isBlank()) {
                overlay.remove()
            } else {
                overlay.show("✓ $say", 6000)
                tts?.speak(say, TextToSpeech.QUEUE_FLUSH, null, "agent")
            }
        }
    }

    fun foreground(): String = try {
        rootInActiveWindow?.packageName?.toString().orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun launchableApps(): Map<String, String> {
        if (SystemClock.uptimeMillis() - appsAt < 60_000 && apps.isNotEmpty()) return apps
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        apps = pm.queryIntentActivities(query, 0)
            .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .toSortedMap()
        appsAt = SystemClock.uptimeMillis()
        return apps
    }

    fun screenshot(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        runOnMain { overlay.setHidden(true) }
        Thread.sleep(120)
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, shots, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                buffer.close()
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        runOnMain { overlay.setHidden(false) }
        val full = bitmap ?: return null
        // Keep the screen's proportions and free each frame at once: a run takes up to 12
        // screenshots, and on low-RAM TVs they must not wait for the GC.
        val scale = minOf(1f, SHOT_SIZE / maxOf(full.width, full.height))
        val small = Bitmap.createScaledBitmap(full, (full.width * scale).roundToInt().coerceAtLeast(1),
            (full.height * scale).roundToInt().coerceAtLeast(1), true)
        if (small !== full) full.recycle()
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        small.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun perform(a: JSONObject): String = try {
        when (a.optString("type")) {
            "keys" -> keys(a)
            "click_text" -> clickText(a.optString("text"))
            "open_app" -> openApp(a.optString("package"))
            "open_url" -> openUrl(a.optString("url"))
            "search" -> search(a.optString("package"), a.optString("query"))
            "type" -> type(a.optString("text"), a.optBoolean("submit"))
            "wait" -> {
                Thread.sleep((a.optDouble("seconds", 1.0).coerceIn(0.0, 10.0) * 1000).toLong())
                "ок"
            }
            else -> "неизвестное действие"
        }
    } catch (e: ActivityNotFoundException) {
        "не открылось"
    } catch (e: SecurityException) {
        "нет доступа"
    }

    private fun keys(a: JSONObject): String {
        val list = a.optJSONArray("keys") ?: return "не указаны кнопки"
        val names = (0 until list.length()).map { list.getString(it).lowercase() }
        val unknown = names.filter { it !in KEYS }
        if (unknown.isNotEmpty()) return "неизвестные кнопки: ${unknown.joinToString()}"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && names.any { it in DPAD }) {
            return "стрелки и ok доступны только с Android 13, используй click_text"
        }
        repeat(a.optInt("count", 1).coerceIn(1, 25)) {
            for (name in names) {
                press(name)
                Thread.sleep(140)
            }
        }
        return "ок"
    }

    private fun press(name: String) {
        when (name) {
            "up" -> performGlobalAction(GLOBAL_ACTION_DPAD_UP)
            "down" -> performGlobalAction(GLOBAL_ACTION_DPAD_DOWN)
            "left" -> performGlobalAction(GLOBAL_ACTION_DPAD_LEFT)
            "right" -> performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT)
            "ok" -> performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "sleep" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> MEDIA[name]?.let { media(it) }
        }
    }

    private fun media(code: Int) {
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, code, 0))
    }

    private fun clickText(text: String): String {
        if (text.isBlank()) return "пустая подпись"
        val root = rootInActiveWindow ?: return "экран недоступен"
        val nodes = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }
        val node = nodes.firstOrNull() ?: return "не нашёл «$text» на экране"
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable && !target.isFocusable) target = target.parent
        val chosen = target ?: node
        if (chosen.isClickable && chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ок"
        if (chosen.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Thread.sleep(150)
            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            return "ок"
        }
        return "не получилось нажать"
    }

    private fun openApp(pkg: String): String {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
            ?: return "нет такого приложения"
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "ок"
    }

    private fun openUrl(raw: String): String {
        val url = if (Regex("^https?://").containsMatchIn(raw)) raw else "https://$raw"
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setClassName(packageName, "app.momiji.tv.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return "ок"
    }

    private fun search(pkg: String, query: String): String {
        val intent = if (pkg == YOUTUBE) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))).setPackage(pkg)
        } else {
            Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        }
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ок"
        } catch (e: ActivityNotFoundException) {
            "приложение не принимает поиск, ищи через интерфейс"
        }
    }

    private fun type(text: String, submit: Boolean): String {
        val root = rootInActiveWindow ?: return "экран недоступен"
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: return "нет активного поля ввода"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "поле не принимает текст"
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        return "ок"
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            block()
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    companion object {
        @Volatile
        var instance: AgentService? = null

        const val YOUTUBE = "com.google.android.youtube.tv"
        private const val SHOT_SIZE = 960f

        val MEDIA = mapOf(
            "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            "play" to KeyEvent.KEYCODE_MEDIA_PLAY,
            "pause" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "stop" to KeyEvent.KEYCODE_MEDIA_STOP,
            "next" to KeyEvent.KEYCODE_MEDIA_NEXT,
            "previous" to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            "forward" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            "rewind" to KeyEvent.KEYCODE_MEDIA_REWIND,
        )

        val KEYS = listOf("up", "down", "left", "right", "ok", "back", "home", "volume_up", "volume_down", "mute", "sleep") + MEDIA.keys

        private val DPAD = setOf("up", "down", "left", "right", "ok")

        val RESERVED = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
}
