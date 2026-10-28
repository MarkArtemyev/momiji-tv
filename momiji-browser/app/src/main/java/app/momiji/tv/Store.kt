package app.momiji.tv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Tile(val title: String, val url: String)

class Store(context: Context) {

    private val prefs = context.getSharedPreferences("momiji", Context.MODE_PRIVATE)

    var tiles: List<Tile>
        get() = read("tiles") ?: DEFAULT_TILES
        set(value) = write("tiles", value)

    var recent: List<Tile>
        get() = read("recent") ?: emptyList()
        set(value) = write("recent", value)

    var engine: String
        get() = prefs.getString("engine", "google") ?: "google"
        set(value) = prefs.edit().putString("engine", value).apply()

    var adBlock: Boolean
        get() = prefs.getBoolean("adblock", true)
        set(value) = prefs.edit().putBoolean("adblock", value).apply()

    var desktop: Boolean
        get() = prefs.getBoolean("desktop", false)
        set(value) = prefs.edit().putBoolean("desktop", value).apply()

    var focusMode: Boolean
        get() = prefs.getBoolean("focus_mode", false)
        set(value) = prefs.edit().putBoolean("focus_mode", value).apply()

    var liteMode: Boolean
        get() = prefs.getBoolean("lite_mode", false)
        set(value) = prefs.edit().putBoolean("lite_mode", value).apply()

    var effects: Boolean
        get() = prefs.getBoolean("effects", true)
        set(value) = prefs.edit().putBoolean("effects", value).apply()

    var launches: Int
        get() = prefs.getInt("launches", 0)
        set(value) = prefs.edit().putInt("launches", value).apply()

    fun hasTile(url: String) = tiles.any { it.url == url }

    fun addTile(tile: Tile) {
        tiles = tiles.filterNot { it.url == tile.url } + tile
    }

    fun removeTile(url: String) {
        tiles = tiles.filterNot { it.url == url }
    }

    fun pushRecent(tile: Tile) {
        val host = hostOf(tile.url)
        recent = (listOf(tile) + recent.filterNot { hostOf(it.url) == host }).take(12)
    }

    private fun read(key: String): List<Tile>? {
        val raw = prefs.getString(key, null) ?: return null
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Tile(o.optString("title"), o.getString("url"))
        }
    }

    private fun write(key: String, list: List<Tile>) {
        prefs.edit().putString(key, json(list).toString()).apply()
    }

    companion object {
        val DEFAULT_TILES = listOf(
            Tile("YouTube", "https://www.youtube.com/"),
            Tile("VK Видео", "https://vkvideo.ru/"),
            Tile("Rutube", "https://rutube.ru/"),
            Tile("Twitch", "https://www.twitch.tv/"),
            Tile("Shikimori", "https://shikimori.io/"),
            Tile("Яндекс", "https://ya.ru/"),
            Tile("Google", "https://www.google.com/"),
            Tile("Википедия", "https://ru.wikipedia.org/"),
            Tile("Reddit", "https://www.reddit.com/"),
            Tile("Pinterest", "https://www.pinterest.com/"),
            Tile("Пикабу", "https://pikabu.ru/"),
            Tile("Дзен", "https://dzen.ru/"),
        )

        fun hostOf(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: url

        fun json(list: List<Tile>): JSONArray {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
            return arr
        }
    }
}
