package app.momiji.home.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import app.momiji.home.model.AppEntry
import app.momiji.home.model.InputEntry
import java.text.Collator
import java.util.Locale

/** Installed launch targets only; never executes package-management or settings commands. */
class AppCatalog(context: Context) {
    private val context = context.applicationContext
    private val pm = this.context.packageManager

    fun loadApps(): List<AppEntry> {
        val apps = LinkedHashMap<String, AppEntry>()
        for ((category, isTv) in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER to true, Intent.CATEGORY_LAUNCHER to false)) {
            val matches = try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            } catch (_: RuntimeException) { emptyList() }
            for (result in matches.sortedWith(compareBy({ it.activityInfo?.packageName.orEmpty() }, { it.activityInfo?.name.orEmpty() }))) {
                val activity = result.activityInfo ?: continue
                val info = activity.applicationInfo ?: continue
                if (!activity.enabled || !activity.exported || !info.enabled || excluded(activity.packageName, activity.name, info)) continue
                if (activity.packageName in apps) continue // Prefer one TV target over a duplicate phone target.
                val label = try { activity.loadLabel(pm).toString().trim().take(160) }
                    catch (_: RuntimeException) { activity.packageName }
                @Suppress("DEPRECATION")
                val game = info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0
                apps[activity.packageName] = AppEntry(activity.packageName, activity.name,
                    label.ifBlank { activity.packageName }, game, isTv)
            }
        }
        val collator = Collator.getInstance(Locale.getDefault())
        return apps.values.sortedWith { a, b ->
            val labels = collator.compare(a.label, b.label)
            if (labels != 0) labels else a.key.compareTo(b.key)
        }
    }

    fun inputs(): List<InputEntry> {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return try {
            manager.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI }.map { info ->
                val label = try { (info.loadCustomLabel(context) ?: info.loadLabel(context)).toString() }
                    catch (_: RuntimeException) { "HDMI" }
                val connected = try { manager.getInputState(info.id) == TvInputManager.INPUT_STATE_CONNECTED }
                    catch (_: RuntimeException) { false }
                InputEntry(info.id, label.ifBlank { "HDMI" }.take(120), connected)
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
        } catch (_: RuntimeException) { emptyList() }
    }

    fun launch(app: AppEntry): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(intent)
    }

    fun launchInput(input: InputEntry): Boolean {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return false
        val knownHdmi = try { manager.getTvInputInfo(input.id)?.type == TvInputInfo.TYPE_HDMI }
            catch (_: RuntimeException) { false }
        if (!knownHdmi) return false
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER)
        } catch (_: RuntimeException) { return false }
        // A generic content/file handler must never receive HDMI navigation. Require both a
        // system receiver and a filter explicitly claiming the Android TV content authority.
        val receiver = candidates.filter(::isSystemTvReceiver).maxByOrNull { it.match } ?: return false
        intent.component = ComponentName(receiver.activityInfo.packageName, receiver.activityInfo.name)
        return start(intent)
    }

    private fun isSystemTvReceiver(result: ResolveInfo): Boolean {
        val activity = result.activityInfo ?: return false
        val info = activity.applicationInfo ?: return false
        if (!activity.enabled || !activity.exported || !info.enabled) return false
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return false
        val filter = result.filter ?: return false
        return (0 until filter.countDataAuthorities()).any { filter.getDataAuthority(it).host.equals(TvContract.AUTHORITY, true) }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
      catch (_: RuntimeException) { false }

    private fun excluded(packageName: String, activityName: String, info: ApplicationInfo): Boolean {
        if (packageName == context.packageName) return true
        val component = "$packageName/$activityName".lowercase(Locale.ROOT)
        if (listOf("setupwizard", "setupwraith", "provisioning").any { it in component }) return true
        if (packageName == "com.android.provision") return true
        val system = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return system && listOf("factory", "engineering", "engmode", "servicetest").any { it in component }
    }
}
