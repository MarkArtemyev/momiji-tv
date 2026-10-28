package app.momiji.home.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.momiji.home.model.FeedSection
import app.momiji.home.model.MediaCard
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class NowPlayingInfo(
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
    val controller: MediaController
)

/** Reads only provider-published TV cards. It never assigns a service's cards to a local profile. */
class MediaRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val packages = app.packageManager
    private val appLabels = ConcurrentHashMap<String, String>()

    fun hasTvPermission(): Boolean =
        app.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    /** Call from an IO worker. At most 200 rows are scanned and six sections returned. */
    fun load(limitPerRow: Int = 10): List<FeedSection> {
        if (!hasTvPermission()) return emptyList()
        val limit = limitPerRow.coerceIn(1, 12)
        val budget = ReadBudget(200)
        val sections = ArrayList<FeedSection>(6)
        val watch = readCards(TvContract.WatchNextPrograms.CONTENT_URI, "watch_next", null, limit,
            budget, 60, "last_engagement_time_utc_millis DESC", WATCH_COLUMNS)
            .sortedByDescending { it.lastEngagementTime }
        if (watch.isNotEmpty()) sections.add(FeedSection("watch_next", "Продолжить из сервисов", watch))

        val channels = ArrayList<Channel>(12)
        val channelUri = TvContract.Channels.CONTENT_URI.buildUpon().appendQueryParameter("preview", "true").build()
        try { query(channelUri, CHANNEL_COLUMNS, null)?.use { cursor ->
            var visited = 0
            while (budget.remaining > 0 && visited < 40 && channels.size < 12 && cursor.moveToNext()) {
                budget.remaining--; visited++
                try {
                    if (cursor.number("browsable", 1) == 0L) continue
                    val type = cursor.text("type")
                    if (type != null && type != TvContract.Channels.TYPE_PREVIEW) continue
                    val id = cursor.number("_id", -1)
                    if (id < 0) continue
                    val source = validPackage(cursor.text("package_name"))
                    val name = cursor.text("display_name")?.take(160).orEmpty()
                    channels.add(Channel(id, name, source))
                } catch (_: RuntimeException) { /* Ignore a malformed provider record. */ }
            }
        } } catch (_: RuntimeException) { /* An unavailable OEM provider leaves other sections usable. */ }
        for (channel in channels) {
            if (sections.size >= 6 || budget.remaining <= 0) break
            val cards = readCards(TvContract.buildPreviewProgramsUriForChannel(channel.id), "channel_${channel.id}",
                channel.packageName, limit, budget, 40, "weight DESC", PREVIEW_COLUMNS)
            if (cards.isEmpty()) continue
            val source = channel.packageName?.let(::appLabel).orEmpty()
            val name = channel.name.ifBlank { source.ifBlank { "Рекомендации сервиса" } }
            val title = if (source.isNotBlank() && !name.equals(source, true)) "$source · $name" else name
            sections.add(FeedSection("channels_${channel.id}", title, cards))
        }
        return sections
    }

    private data class Channel(val id: Long, val name: String, val packageName: String?)
    private class ReadBudget(var remaining: Int)

    private fun readCards(uri: Uri, prefix: String, sourcePackage: String?, limit: Int, budget: ReadBudget,
                          visitLimit: Int, sort: String?, projection: Array<String>): List<MediaCard> {
        val cards = ArrayList<MediaCard>(limit)
        try { query(uri, projection, sort)?.use { cursor ->
            var visited = 0
            while (cards.size < limit && budget.remaining > 0 && visited < visitLimit && cursor.moveToNext()) {
                budget.remaining--; visited++
                try {
                    if (cursor.number("browsable", 1) == 0L) continue
                    val rowId = cursor.number("_id", -1)
                    val title = cursor.text("title")?.take(240) ?: continue
                    if (rowId < 0) continue
                    val source = validPackage(cursor.text("package_name")) ?: sourcePackage
                    val duration = cursor.number("duration_millis").coerceAtLeast(0)
                    val position = cursor.number("last_playback_position_millis").coerceAtLeast(0)
                        .let { if (duration > 0) it.coerceAtMost(duration) else 0L }
                    val subtitle = source?.let(::appLabel).orEmpty()
                    val art = imageUri(cursor.text("poster_art_uri")) ?: imageUri(cursor.text("thumbnail_uri"))
                    cards.add(MediaCard(id = "$prefix:${source.orEmpty()}:$rowId", title = title, subtitle = subtitle,
                        posterUri = art, intentUri = cursor.text("intent_uri")?.takeIf { it.length <= 16_384 },
                        packageName = source, positionMs = position, durationMs = duration,
                        lastEngagementTime = cursor.number("last_engagement_time_utc_millis").coerceAtLeast(0)))
                } catch (_: RuntimeException) { /* The next card can still be usable. */ }
            }
        } } catch (_: RuntimeException) { /* Keep cards read before a provider/cursor failure. */ }
        return cards
    }

    /** OEMs can omit optional columns or reject an otherwise valid sort expression. */
    private fun query(uri: Uri, projection: Array<String>, sort: String?): Cursor? {
        try { return resolver.query(uri, projection, null, null, sort) }
        catch (_: SecurityException) { return null }
        catch (_: RuntimeException) { /* Retry without provider-specific projection/sort. */ }
        return try { resolver.query(uri, null, null, null, null) }
        catch (_: RuntimeException) { null }
    }

    private fun Cursor.text(column: String): String? {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) null else getString(at)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Cursor.number(column: String, fallback: Long = 0): Long {
        val at = getColumnIndex(column)
        return if (at < 0 || isNull(at)) fallback else try { getLong(at) } catch (_: RuntimeException) { fallback }
    }

    private fun appLabel(packageName: String): String {
        appLabels[packageName]?.let { return it }
        val label = try { packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString().take(160) }
        catch (_: RuntimeException) { packageName }
        catch (_: PackageManager.NameNotFoundException) { packageName }
        if (appLabels.size < 64) appLabels[packageName] = label
        return label
    }

    private fun validPackage(value: String?): String? = value?.takeIf {
        it.length <= 256 && PACKAGE_NAME.matches(it)
    }

    private fun imageUri(value: String?): String? {
        if (value == null || value.length > 8192) return null
        val uri = try { Uri.parse(value) } catch (_: RuntimeException) { return null }
        return value.takeIf { uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http", "content", "android.resource") }
    }

    /** Opens a real provider deep link, then falls back to launching its source app. */
    fun open(card: MediaCard): Boolean {
        val owner = validPackage(card.packageName)
        val deepLink = safeIntent(card.intentUri, owner)
        if (deepLink != null && launch(deepLink, owner)) return true
        if (owner == null) return false
        val fallback = try { packages.getLeanbackLaunchIntentForPackage(owner) ?: packages.getLaunchIntentForPackage(owner) }
        catch (_: RuntimeException) { null }
        return fallback != null && launch(fallback.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }, owner)
    }

    private fun safeIntent(raw: String?, owner: String?): Intent? {
        val value = raw?.trim()?.takeIf { it.length in 1..16_384 } ?: return null
        val encoded = value.startsWith("intent:", true)
        val parsed = try {
            if (encoded) Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(value))
        } catch (_: Exception) { return null }
        val data = parsed.data ?: return null
        val scheme = data.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme in UNSAFE_SCHEMES) return null
        if (scheme == "http" || scheme == "https") {
            if (data.host.isNullOrBlank()) return null
        } else if (owner == null) return null
        // Raw and intent:-encoded app schemes are package-bound; launch() verifies the resolved owner.
        if (parsed.action != null && parsed.action != Intent.ACTION_VIEW) return null

        // Build from scratch: no selector, ClipData, URI grants, arbitrary action or foreign component.
        return Intent(Intent.ACTION_VIEW, data).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (owner != null) {
                setPackage(owner)
                parsed.component?.takeIf { it.packageName == owner }?.let { component = it }
            }
            if (encoded && owner != null) {
                val extras = parsed.extras
                extras?.keySet()?.take(24)?.forEach { key ->
                    if (key == "browser_fallback_url" || key.length > 128) return@forEach
                    @Suppress("DEPRECATION") val extra = extras.get(key)
                    when (extra) {
                        is String -> if (extra.length <= 8192) putExtra(key, extra)
                        is Boolean -> putExtra(key, extra)
                        is Int -> putExtra(key, extra)
                        is Long -> putExtra(key, extra)
                        is Float -> putExtra(key, extra)
                        is Double -> putExtra(key, extra)
                        is Byte -> putExtra(key, extra)
                        is Short -> putExtra(key, extra)
                        is Char -> putExtra(key, extra)
                    }
                }
            }
        }
    }

    private fun launch(intent: Intent, owner: String?): Boolean {
        return try {
            val target = packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
            if (owner != null && target.activityInfo.packageName != owner) return false
            app.startActivity(intent)
            true
        } catch (_: RuntimeException) { false }
    }

    fun nowPlaying(): NowPlayingInfo? {
        if (!notificationsAllowed(app)) return null
        return try {
            val manager = app.getSystemService(MediaSessionManager::class.java) ?: return null
            val listener = ComponentName(app, HomeNotificationListener::class.java)
            val sessions = manager.getActiveSessions(listener).take(12)
                .sortedByDescending { playing(it.playbackState?.state) }
            sessions.firstNotNullOfOrNull { controller ->
                if (controller.playbackState?.state !in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
                        PlaybackState.STATE_REWINDING)) return@firstNotNullOfOrNull null
                val metadata = controller.metadata ?: return@firstNotNullOfOrNull null
                val title = (metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_TITLE))?.trim()?.take(240)
                    ?.takeIf { it.isNotEmpty() } ?: return@firstNotNullOfOrNull null
                val subtitle = (metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))?.trim()?.take(240)
                    ?.takeIf { it.isNotEmpty() } ?: appLabel(controller.packageName)
                NowPlayingInfo(title, subtitle, playing(controller.playbackState?.state), controller)
            }
        } catch (_: RuntimeException) { null }
    }

    private fun playing(state: Int?): Boolean = state in setOf(PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING)

    companion object {
        private const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val UNSAFE_SCHEMES = setOf("javascript", "file", "content", "data", "tel", "sms", "smsto",
            "mms", "mmsto", "mailto", "geo", "market", "settings", "package", "android-app", "about", "intent")
        private val WATCH_COLUMNS = arrayOf("_id", "title", "package_name", "poster_art_uri", "thumbnail_uri",
            "intent_uri", "browsable", "duration_millis", "last_playback_position_millis", "last_engagement_time_utc_millis")
        private val PREVIEW_COLUMNS = arrayOf("_id", "title", "package_name", "poster_art_uri", "thumbnail_uri",
            "intent_uri", "browsable", "duration_millis", "last_playback_position_millis", "weight")
        private val CHANNEL_COLUMNS = arrayOf("_id", "display_name", "package_name", "type", "browsable")

        fun notificationsAllowed(context: Context): Boolean {
            val component = ComponentName(context, HomeNotificationListener::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= 27) {
                    context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true
                } else {
                    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
                }
            } catch (_: RuntimeException) { false }
        }
    }
}
