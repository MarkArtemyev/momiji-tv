package app.momiji.home.data

import android.content.Context
import app.momiji.home.model.AppEntry
import app.momiji.home.model.HomeProfile
import app.momiji.home.model.PerformanceMode
import app.momiji.home.model.RecentLaunch
import app.momiji.home.model.HomeCategory
import app.momiji.home.model.HomeLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** App launch history is local to a profile. It is deliberately separate from media watch history. */
class HomeStore(context: Context, prefsName: String = "momiji_home") {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cachedState: State? = null

    fun profiles(): List<HomeProfile> = synchronized(storageLock) { read().profiles.map { it.profile } }

    fun activeProfile(): HomeProfile = synchronized(storageLock) {
        val state = read()
        state.profiles.firstOrNull { it.profile.id == state.active }?.profile ?: state.profiles.first().profile
    }

    fun createProfile(name: String): HomeProfile = synchronized(storageLock) {
        val state = read()
        require(state.profiles.size < MAX_PROFILES) { "Можно создать не более $MAX_PROFILES профилей" }
        val profile = HomeProfile(UUID.randomUUID().toString(), profileName(name), accents[state.profiles.size % accents.size])
        state.profiles.add(ProfileData(profile))
        write(state)
        profile
    }

    fun selectProfile(id: String) = synchronized(storageLock) {
        val state = read()
        requireProfile(state, id)
        state.active = id
        write(state)
    }

    fun renameProfile(id: String, name: String) = synchronized(storageLock) {
        val state = read()
        val profile = requireProfile(state, id)
        profile.profile = profile.profile.copy(name = profileName(name))
        write(state)
    }

    fun deleteProfile(id: String): Boolean = synchronized(storageLock) {
        val state = read()
        if (state.profiles.size <= 1 || state.profiles.none { it.profile.id == id }) return@synchronized false
        state.profiles.removeAll { it.profile.id == id }
        if (state.active == id) state.active = state.profiles.first().profile.id
        write(state)
        true
    }

    fun favorites(profileId: String): List<String> = synchronized(storageLock) {
        requireProfile(read(), profileId).favorites.toList()
    }

    fun setFavorites(profileId: String, keys: List<String>) = synchronized(storageLock) {
        val state = read()
        val profile = requireProfile(state, profileId)
        profile.favorites = cleanKeys(keys)
        write(state)
    }

    fun hiddenApps(profileId: String): Set<String> = synchronized(storageLock) {
        requireProfile(read(), profileId).hidden.toSet()
    }

    fun setHidden(profileId: String, key: String, hidden: Boolean) = synchronized(storageLock) {
        val state = read()
        val profile = requireProfile(state, profileId)
        require(validKey(key)) { "Некорректный ярлык приложения" }
        if (hidden) {
            if (key !in profile.hidden && profile.hidden.size < MAX_KEYS) profile.hidden.add(key)
        } else profile.hidden.remove(key)
        write(state)
    }

    fun recordLaunch(profileId: String, app: AppEntry) = synchronized(storageLock) {
        val state = read()
        val profile = requireProfile(state, profileId)
        require(validKey(app.key)) { "Некорректный ярлык приложения" }
        profile.recent.removeAll { it.appKey == app.key }
        profile.recent.add(0, RecentLaunch(app.key, app.label.ifBlank { app.packageName }.take(160), System.currentTimeMillis()))
        if (profile.recent.size > MAX_RECENT) profile.recent.subList(MAX_RECENT, profile.recent.size).clear()
        write(state)
    }

    fun recentLaunches(profileId: String): List<RecentLaunch> = synchronized(storageLock) {
        requireProfile(read(), profileId).recent.toList()
    }

    fun clearHistory(profileId: String) = synchronized(storageLock) {
        val state = read()
        requireProfile(state, profileId).recent.clear()
        write(state)
    }

    fun layout(profileId: String): HomeLayout = synchronized(storageLock) { requireProfile(read(), profileId).layout }
    fun setLayout(profileId: String, layout: HomeLayout) = synchronized(storageLock) {
        require(layout.columns in 0..6 && layout.cardSize in 0..2 && layout.fontStyle in 0..3)
        require(layout.ambientEffect in setOf("OFF","MAPLE","FIREFLIES","STARS") && layout.ambientIntensity in 0..2 && layout.ambientSpeed in 0..2 && layout.ambientColor in 0..4)
        val state = read(); requireProfile(state, profileId).layout = layout; write(state)
    }
    fun setAccent(profileId: String, accent: Int) = synchronized(storageLock) {
        val state = read(); val data = requireProfile(state, profileId)
        data.profile = data.profile.copy(accent = accent or 0xFF000000.toInt()); write(state)
    }
    fun aliases(profileId: String): Map<String, String> = synchronized(storageLock) { requireProfile(read(), profileId).aliases.toMap() }
    fun setAlias(profileId: String, key: String, label: String) = synchronized(storageLock) {
        require(validKey(key)); val state = read(); val data = requireProfile(state, profileId)
        val clean = label.trim().filterNot(Char::isISOControl).take(80)
        if (clean.isBlank()) data.aliases.remove(key) else {
            require(key in data.aliases || data.aliases.size < MAX_KEYS)
            data.aliases[key] = clean
        }; write(state)
    }
    fun icons(profileId: String): Map<String,String> = synchronized(storageLock) { requireProfile(read(),profileId).icons.toMap() }
    fun setIcon(profileId: String, key: String, uri: String?) = synchronized(storageLock) {
        require(validKey(key)); val state=read(); val data=requireProfile(state,profileId)
        if (uri == null) data.icons.remove(key) else {
            require(uri.startsWith("file:///") && uri.length <= 16384 && uri.none(Char::isISOControl))
            require(key in data.icons || data.icons.size < MAX_KEYS); data.icons[key]=uri
        }; write(state)
    }
    fun gameApps(profileId: String): Set<String> = synchronized(storageLock) { requireProfile(read(), profileId).games.toSet() }
    fun setGame(profileId: String, key: String, enabled: Boolean) = synchronized(storageLock) {
        require(validKey(key)); val state = read(); val data = requireProfile(state, profileId)
        if (enabled) { require(key in data.games || data.games.size < MAX_KEYS); data.games.add(key) } else data.games.remove(key)
        write(state)
    }
    fun categories(profileId: String): List<HomeCategory> = synchronized(storageLock) {
        requireProfile(read(), profileId).categories.map { it.copy(appKeys = it.appKeys.toList()) }
    }
    fun createCategory(profileId: String, name: String): HomeCategory = synchronized(storageLock) {
        val state = read(); val data = requireProfile(state, profileId)
        require(data.categories.size < 16) { "Не больше 16 категорий в профиле" }
        val category = HomeCategory(UUID.randomUUID().toString(), profileName(name), emptyList())
        data.categories.add(category); write(state); category
    }
    fun updateCategory(profileId: String, category: HomeCategory) = synchronized(storageLock) {
        val state = read(); val data = requireProfile(state, profileId)
        val index = data.categories.indexOfFirst { it.id == category.id }; require(index >= 0)
        data.categories[index] = category.copy(name = profileName(category.name), appKeys = cleanKeys(category.appKeys)); write(state)
    }
    fun moveCategory(profileId: String, id: String, delta: Int) = synchronized(storageLock) {
        val state = read(); val data = requireProfile(state, profileId)
        val index = data.categories.indexOfFirst { it.id == id }; require(index >= 0)
        val target = (index + delta).coerceIn(0, data.categories.lastIndex)
        java.util.Collections.swap(data.categories, index, target); write(state)
    }
    fun deleteCategory(profileId: String, id: String) = synchronized(storageLock) {
        val state = read(); requireProfile(state, profileId).categories.removeAll { it.id == id }; write(state)
    }
    /** Portable layout only: no watch/launch history, private wallpaper paths, accounts or tokens. */
    fun exportProfile(profileId: String): String = synchronized(storageLock) {
        val data = requireProfile(read(), profileId)
        JSONObject().put("format", "momiji-home-profile").put("version", 1)
            .put("profile", encodeProfile(data, includeHistory = false)).toString(2).also {
                require(it.toByteArray(Charsets.UTF_8).size <= 262_144) { "Слишком много настроек для одного файла" }
            }
    }
    fun importProfile(payload: String): HomeProfile = synchronized(storageLock) {
        require(payload.length <= 262_144) { "Файл настроек больше 256 КБ" }
        val root = JSONObject(payload)
        require(root.optString("format") == "momiji-home-profile" && root.optInt("version") == 1) { "Неподдерживаемый файл Momiji" }
        val item = root.getJSONObject("profile").put("id", UUID.randomUUID().toString()).put("recent", JSONArray())
        item.remove("icons")
        val data = decodeProfile(item, 0) ?: throw IllegalArgumentException("Повреждённые настройки профиля")
        val state = read(); require(state.profiles.size < MAX_PROFILES) { "Сначала удали лишний профиль" }
        state.profiles.add(data); state.active = data.profile.id; write(state); data.profile
    }

    var performanceMode: PerformanceMode
        get() = try { PerformanceMode.valueOf(prefs.getString("performance", null).orEmpty()) }
            catch (_: Exception) { PerformanceMode.BALANCED }
        set(value) { prefs.edit().putString("performance", value.name).apply() }

    var wallpaperUri: String?
        get() = try { prefs.getString("wallpaper_uri", null)?.takeIf { it.isNotBlank() && it.length <= 16_384 && it.none(Char::isISOControl) } }
            catch (_: ClassCastException) { null }
        set(value) {
            require(value == null || (value.isNotBlank() && value.length <= 16_384 && value.none(Char::isISOControl))) { "Некорректный адрес фона" }
            prefs.edit().putString("wallpaper_uri", value).apply()
        }

    var animationsEnabled: Boolean
        get() = try { prefs.getBoolean("animations", true) } catch (_: ClassCastException) { true }
        set(value) { prefs.edit().putBoolean("animations", value).apply() }

    private fun requireProfile(state: State, id: String): ProfileData =
        state.profiles.firstOrNull { it.profile.id == id } ?: throw IllegalArgumentException("Профиль не найден")

    private fun read(): State {
        val raw = try { prefs.getString(STATE_KEY, null) } catch (_: ClassCastException) { null }
        if (cachedState != null && raw == cachedRaw) return cachedState!!
        val state = decodeState(raw)
        cachedRaw = raw; cachedState = state
        return state
    }

    private fun decodeState(raw: String?): State = try {
        if (raw == null || raw.length > 2_000_000) defaultState()
        else {
            val root = JSONObject(raw)
            val array = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = ArrayList<ProfileData>()
            val ids = HashSet<String>()
            for (i in 0 until minOf(array.length(), MAX_PROFILES)) {
                val item = array.optJSONObject(i) ?: continue
                val id = (item.opt("id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: continue
                if (!ids.add(id)) continue
                val profile = decodeProfile(item, profiles.size) ?: continue
                profiles.add(profile)
            }
            if (profiles.isEmpty()) defaultState()
            else State(profiles, root.optString("active").takeIf { id -> profiles.any { it.profile.id == id } } ?: profiles.first().profile.id)
        }
    } catch (_: Exception) { defaultState() }

    private fun write(state: State) {
        val profiles = JSONArray()
        for (data in state.profiles) {
            profiles.put(encodeProfile(data, includeHistory = true))
        }
        val payload = JSONObject().put("version", 1).put("active", state.active).put("profiles", profiles).toString()
        if (payload.length > 2_000_000) { cachedState = null; throw IllegalArgumentException("Слишком много данных профилей") }
        prefs.edit().putString(STATE_KEY, payload).apply()
        cachedRaw = payload; cachedState = state
    }

    private fun encodeProfile(data: ProfileData, includeHistory: Boolean): JSONObject {
        val recent = JSONArray()
        if (includeHistory) for (launch in data.recent) recent.put(JSONObject().put("key", launch.appKey).put("label", launch.label).put("at", launch.launchedAt))
        val categories = JSONArray()
        for (category in data.categories) categories.put(JSONObject().put("id", category.id).put("name", category.name).put("apps", JSONArray(category.appKeys)))
        val l = data.layout
        return JSONObject().put("id", data.profile.id).put("name", data.profile.name).put("accent", data.profile.accent)
            .put("favorites", JSONArray(data.favorites)).put("hidden", JSONArray(data.hidden.toList())).put("recent", recent)
            .put("games", JSONArray(data.games.toList())).put("aliases", JSONObject(data.aliases.toMap())).put("categories", categories)
            .also { if (includeHistory) it.put("icons",JSONObject(data.icons.toMap())) }
            .put("layout", JSONObject().put("columns", l.columns).put("cardSize", l.cardSize).put("largeText", l.largeText)
                .put("recent", l.showRecent).put("services", l.showServices).put("inputs", l.showInputs).put("round", l.roundCards).put("font",l.fontStyle)
                .put("ambient",l.ambientEffect).put("ambientIntensity",l.ambientIntensity).put("ambientSpeed",l.ambientSpeed).put("ambientColor",l.ambientColor).put("appLabels",l.showAppLabels))
    }

    private fun decodeProfile(item: JSONObject, index: Int): ProfileData? {
        val id = (item.opt("id") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: return null
        val name = (item.opt("name") as? String).orEmpty().trim().filterNot(Char::isISOControl).take(40).ifBlank { "Профиль" }
        val data = ProfileData(HomeProfile(id, name, item.optInt("accent", accents[index % accents.size]) or 0xFF000000.toInt()))
        data.favorites = cleanKeys(strings(item.optJSONArray("favorites")))
        data.hidden.addAll(cleanKeys(strings(item.optJSONArray("hidden"))))
        data.games.addAll(cleanKeys(strings(item.optJSONArray("games"))))
        val aliases = item.optJSONObject("aliases")
        aliases?.keys()?.asSequence()?.take(MAX_KEYS)?.forEach { key ->
            val label = (aliases.opt(key) as? String)?.trim()?.filterNot(Char::isISOControl)?.take(80)
            if (validKey(key) && !label.isNullOrBlank()) data.aliases[key] = label
        }
        val icons=item.optJSONObject("icons")
        icons?.keys()?.asSequence()?.take(MAX_KEYS)?.forEach { key ->
            val uri=icons.opt(key) as? String
            if (validKey(key) && uri != null && uri.startsWith("file:///") && uri.length <= 16384 && uri.none(Char::isISOControl)) data.icons[key]=uri
        }
        val categories = item.optJSONArray("categories") ?: JSONArray()
        val categoryIds = HashSet<String>()
        for (n in 0 until minOf(categories.length(), 16)) {
            val c = categories.optJSONObject(n) ?: continue
            val cid = c.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) && categoryIds.add(it) } ?: continue
            val title = c.optString("name").trim().filterNot(Char::isISOControl).take(40).ifBlank { "Категория" }
            data.categories.add(HomeCategory(cid, title, cleanKeys(strings(c.optJSONArray("apps")))))
        }
        val l = item.optJSONObject("layout") ?: JSONObject()
        data.layout = HomeLayout(l.optInt("columns", 0).coerceIn(0, 6), l.optInt("cardSize", 1).coerceIn(0, 2),
            l.optBoolean("largeText", false), l.optBoolean("recent", true), l.optBoolean("services", true),
            l.optBoolean("inputs", true), l.optBoolean("round", true),l.optInt("font",0).coerceIn(0,3),
            l.optString("ambient","OFF").takeIf { it in setOf("OFF","MAPLE","FIREFLIES","STARS") } ?: "OFF",
            l.optInt("ambientIntensity",1).coerceIn(0,2),l.optInt("ambientSpeed",1).coerceIn(0,2),l.optInt("ambientColor",0).coerceIn(0,4),l.optBoolean("appLabels",false))
        val recent = item.optJSONArray("recent") ?: JSONArray(); val seen = HashSet<String>()
        for (n in 0 until minOf(recent.length(), MAX_KEYS)) {
            val launch = recent.optJSONObject(n) ?: continue
            val key = launch.opt("key") as? String ?: continue; val time = launch.optLong("at", 0)
            if (!validKey(key) || time <= 0 || !seen.add(key)) continue
            data.recent.add(RecentLaunch(key, launch.optString("label").take(160).ifBlank { key.substringBefore('/') }, time))
            if (data.recent.size == MAX_RECENT) break
        }
        return data
    }

    private class ProfileData(var profile: HomeProfile) {
        var favorites: List<String> = emptyList()
        val hidden = LinkedHashSet<String>()
        val recent = ArrayList<RecentLaunch>()
        var layout = HomeLayout()
        val aliases = LinkedHashMap<String, String>()
        val icons = LinkedHashMap<String,String>()
        val games = LinkedHashSet<String>()
        val categories = ArrayList<HomeCategory>()
    }
    private data class State(val profiles: MutableList<ProfileData>, var active: String)

    companion object {
        private const val STATE_KEY = "state_v1"
        private const val MAX_PROFILES = 32
        private const val MAX_KEYS = 256
        private const val MAX_RECENT = 32
        private val storageLock = Any()
        private val accents = intArrayOf(0xFFFFB347.toInt(), 0xFF8EC8C4.toInt(), 0xFFC4A7E7.toInt(), 0xFFF28B82.toInt(), 0xFFA8C986.toInt(), 0xFF87B7E8.toInt())
        private fun defaultState() = State(arrayListOf(ProfileData(HomeProfile("main", "Основной", accents[0]))), "main")
        private fun validKey(key: String) = key.isNotBlank() && key.length <= 512 && key.none(Char::isISOControl)
        private fun cleanKeys(keys: List<String>) = keys.asSequence().filter(::validKey).distinct().take(MAX_KEYS).toList()
        private fun profileName(name: String): String = name.trim().filterNot(Char::isISOControl).take(40).also { require(it.isNotEmpty()) { "Введи название профиля" } }
        private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else
            (0 until minOf(array.length(), MAX_KEYS)).mapNotNull { array.opt(it) as? String }
    }
}
