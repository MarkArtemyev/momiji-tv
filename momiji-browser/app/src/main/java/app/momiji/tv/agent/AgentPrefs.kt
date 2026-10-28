package app.momiji.tv.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class AgentPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(value) = prefs.edit().putString("key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var fallback: String
        get() = prefs.getString("fallback", DEFAULT_FALLBACK) ?: ""
        set(value) = prefs.edit().putString("fallback", value.trim()).apply()

    val chain: List<String>
        get() = (listOf(model) + fallback.split(',').map { it.trim() }).filter { it.isNotEmpty() }.distinct()

    fun limitedUntil(model: String): Long = exhausted().optLong(model, 0L)

    fun markLimited(model: String, until: Long) {
        prefs.edit().putString("exhausted", exhausted().put(model, until).toString()).apply()
    }

    private fun exhausted(): JSONObject = try {
        JSONObject(prefs.getString("exhausted", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    var thinking: String
        get() = prefs.getString("thinking", "medium") ?: "medium"
        set(value) = prefs.edit().putString("thinking", value).apply()

    var trigger: Int
        get() = prefs.getInt("trigger", 0)
        set(value) = prefs.edit().putInt("trigger", value).apply()

    var learning: Boolean
        get() = prefs.getBoolean("learning", false)
        set(value) = prefs.edit().putBoolean("learning", value).apply()

    fun watch(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unwatch(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_FALLBACK = "gemini-3.6-flash,gemini-3.7-flash,gemini-3.5-flash-lite,gemini-3.1-flash-lite"
    }
}
