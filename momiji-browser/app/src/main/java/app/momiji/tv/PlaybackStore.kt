package app.momiji.tv

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Bounded local progress history keyed by hashes. Home's source-page history is stored separately. */
class PlaybackStore(context: Context, url: String) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun entries(): JSONObject = try {
        JSONObject(prefs.getString("positions", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    fun position(): Long {
        val entry = entries().optJSONObject(key) ?: return 0L
        val age = System.currentTimeMillis() - entry.optLong("at")
        return if (age in 0..MAX_AGE) entry.optLong("position").coerceAtLeast(0L) else 0L
    }

    fun save(position: Long, duration: Long) {
        val all = entries()
        val now = System.currentTimeMillis()
        all.keys().asSequence().toList().forEach { id ->
            val age = now - (all.optJSONObject(id)?.optLong("at") ?: 0L)
            if (age !in 0..MAX_AGE) all.remove(id)
        }
        if (position >= 10_000 && duration > 0 && position < duration - 30_000) {
            all.put(key, JSONObject().put("position", position).put("at", now))
        } else {
            all.remove(key)
        }
        val ordered = all.keys().asSequence().toList().sortedByDescending { all.optJSONObject(it)?.optLong("at") ?: 0L }
        ordered.drop(50).forEach { all.remove(it) }
        prefs.edit().putString("positions", all.toString()).apply()
    }

    fun clear() {
        val all = entries()
        all.remove(key)
        prefs.edit().putString("positions", all.toString()).apply()
    }

    private companion object {
        const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
    }
}
