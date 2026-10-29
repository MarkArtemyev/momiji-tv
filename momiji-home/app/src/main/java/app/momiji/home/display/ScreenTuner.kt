package app.momiji.home.display

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.provider.Settings

/**
 * Applies a [Preset]: Home's own window always; system-wide display settings only with WRITE_SECURE_SETTINGS,
 * which the owner grants once over ADB. The first value Momiji replaces is remembered, so
 * [Preset.SYSTEM] puts every setting back exactly as it was.
 */
object ScreenTuner {
    const val ADB_GRANT = "adb shell pm grant app.momiji.home android.permission.WRITE_SECURE_SETTINGS"
    const val ADB_REVOKE = "adb shell pm revoke app.momiji.home android.permission.WRITE_SECURE_SETTINGS"
    private const val ORIGINALS = "momiji_screen_originals"

    /**
     * Wide colour lets P3 photos and wallpapers use the panel's gamut; HDR mode (Android 14+) lets an Ultra HDR
     * wallpaper shine above SDR white, so it is only asked for when the wallpaper actually carries a gain map.
     * The refresh rate only ever goes up to a rate the panel reaches without blanking.
     */
    fun applyWindow(activity: Activity, preset: Preset, facts: ScreenFacts, hdrWallpaper: Boolean) {
        val tuning = ScreenPlan.tuning(preset)
        val window = activity.window
        val colorMode = when {
            Build.VERSION.SDK_INT >= 34 && tuning.hdrWallpaper && hdrWallpaper && facts.hdrScreen -> ActivityInfo.COLOR_MODE_HDR
            tuning.wideColor && facts.wideColor -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            else -> ActivityInfo.COLOR_MODE_DEFAULT
        }
        if (window.colorMode != colorMode) window.colorMode = colorMode
        val target = if (tuning.maxRefresh) ScreenPlan.refreshMode(facts)?.id ?: 0 else 0
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId != target) {
            attributes.preferredDisplayModeId = target
            window.attributes = attributes
        }
    }

    class Outcome(val applied: List<String>, val failed: List<String>)

    fun applySystem(context: Context, preset: Preset, facts: ScreenFacts): Outcome {
        if (!DisplayProbe.canWriteSettings(context)) return Outcome(emptyList(), emptyList())
        val originals = context.getSharedPreferences(ORIGINALS, Context.MODE_PRIVATE)
        val applied = ArrayList<String>(); val failed = ArrayList<String>()
        if (preset == Preset.SYSTEM) {
            for ((name, value) in originals.all) {
                val table = if (name.startsWith("GLOBAL:")) Table.GLOBAL else Table.SECURE
                val key = name.substringAfter(':')
                val original = value as? Int
                if (write(context, table, key, original)) applied.add(key) else failed.add(key)
            }
            originals.edit().clear().apply()
            return Outcome(applied, failed)
        }
        val editor = originals.edit()
        for (change in ScreenPlan.changes(preset, facts)) {
            val name = "${change.table}:${change.key}"
            // -1 stands for "was not set", so restoring deletes our value instead of inventing one.
            if (!originals.contains(name)) editor.putInt(name, ScreenPlan.current(facts.settings, change.key) ?: -1)
            if (write(context, change.table, change.key, change.value)) applied.add(change.label) else failed.add(change.label)
        }
        editor.apply()
        return Outcome(applied, failed)
    }

    private fun write(context: Context, table: Table, key: String, value: Int?): Boolean = runCatching {
        val resolver = context.contentResolver
        if (value == null || value < 0) {
            // Android has no public delete for these tables; restoring "unset" falls back to the platform default.
            val defaults = mapOf(ScreenPlan.KEY_MATCH to ScreenPlan.MATCH_SEAMLESS, ScreenPlan.KEY_HDR_AUTO to 1, ScreenPlan.KEY_ALLM to 1)
            val fallback = defaults[key] ?: return@runCatching false
            if (table == Table.GLOBAL) Settings.Global.putInt(resolver, key, fallback) else Settings.Secure.putInt(resolver, key, fallback)
        } else if (table == Table.GLOBAL) Settings.Global.putInt(resolver, key, value) else Settings.Secure.putInt(resolver, key, value)
    }.getOrDefault(false)
}
