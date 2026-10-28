package app.momiji.home.model

import android.content.ComponentName

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isGame: Boolean = false,
    val isTv: Boolean = true
) {
    val key: String get() = "$packageName/$activityName"
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

data class InputEntry(val id: String, val label: String, val connected: Boolean)
data class HomeProfile(val id: String, val name: String, val accent: Int)
data class HomeCategory(val id: String, val name: String, val appKeys: List<String>)
data class HomeLayout(
    val columns: Int = 0,
    val cardSize: Int = 1,
    val largeText: Boolean = false,
    val showRecent: Boolean = true,
    val showServices: Boolean = true,
    val showInputs: Boolean = true,
    val roundCards: Boolean = true,
    val fontStyle: Int = 0,
    val ambientEffect: String = "OFF",
    val ambientIntensity: Int = 1,
    val ambientSpeed: Int = 1,
    val ambientColor: Int = 0,
    val showAppLabels: Boolean = false
)
data class RecentLaunch(val appKey: String, val label: String, val launchedAt: Long)

data class MediaCard(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val posterUri: String? = null,
    val intentUri: String? = null,
    val packageName: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lastEngagementTime: Long = 0
)

data class FeedSection(val key: String, val title: String, val cards: List<MediaCard>)
enum class PerformanceMode { LITE, BALANCED, CINEMA }
