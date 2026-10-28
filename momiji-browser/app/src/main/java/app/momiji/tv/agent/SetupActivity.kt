package app.momiji.tv.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.momiji.tv.R
import java.net.Inet4Address
import java.net.NetworkInterface

class SetupActivity : Activity() {

    private lateinit var prefs: AgentPrefs
    private lateinit var server: KeyServer
    private lateinit var service: TextView
    private lateinit var key: TextView
    private lateinit var button: TextView
    private lateinit var hint: TextView
    private lateinit var model: TextView
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        prefs = AgentPrefs(this)
        server = KeyServer(prefs) {}
        service = findViewById(R.id.status_service)
        key = findViewById(R.id.status_key)
        button = findViewById(R.id.status_button)
        hint = findViewById(R.id.key_hint)
        model = findViewById(R.id.model_info)

        findViewById<View>(R.id.btn_service).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        findViewById<View>(R.id.btn_button).setOnClickListener {
            prefs.learning = true
            refresh()
        }
        findViewById<View>(R.id.btn_voice).setOnClickListener { AgentService.instance?.listen() ?: refresh() }
        findViewById<View>(R.id.btn_test).setOnClickListener {
            AgentService.instance?.command("Что сейчас на экране? Ответь одной фразой.") ?: refresh()
        }
        pop(findViewById(android.R.id.content))
        findViewById<View>(R.id.btn_service).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        prefs.watch(listener)
        server.start()
        refresh()
    }

    override fun onPause() {
        prefs.unwatch(listener)
        server.stop()
        if (prefs.learning && AgentService.instance == null) prefs.learning = false
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (prefs.learning && event.keyCode !in AgentService.RESERVED) {
            if (event.action == KeyEvent.ACTION_UP) {
                prefs.trigger = event.keyCode
                prefs.learning = false
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refresh() {
        val running = AgentService.instance != null
        service.text = getString(if (running) R.string.agent_service_on else R.string.agent_service_off)
        key.text = if (prefs.key.isBlank()) getString(R.string.agent_key_missing) else getString(R.string.agent_key_set, prefs.key.takeLast(4))
        button.text = when {
            prefs.learning -> getString(R.string.agent_button_learning)
            prefs.trigger != 0 -> getString(R.string.agent_button_set, AgentService.keyName(prefs.trigger))
            else -> getString(R.string.agent_button_missing)
        }
        val address = localAddress()
        hint.text = if (address != null) getString(R.string.agent_key_hint, "http://$address:${KeyServer.PORT}") else getString(R.string.agent_key_hint_offline)
        val spares = prefs.chain.drop(1).joinToString(", ").ifEmpty { "нет" }
        model.text = getString(R.string.agent_model_info, prefs.model, spares, prefs.thinking)
    }

    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun pop(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) pop(v.getChildAt(i))
        } else if (v.isFocusable) {
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.08f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
            }
        }
    }
}
