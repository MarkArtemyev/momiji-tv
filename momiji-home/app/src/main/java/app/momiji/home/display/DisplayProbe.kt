package app.momiji.home.display

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Display

/** Reads what the panel and Android report; anything the system hides stays null instead of guessed. */
object DisplayProbe {
    fun facts(activity: Activity): ScreenFacts {
        @Suppress("DEPRECATION")
        val display: Display = (if (Build.VERSION.SDK_INT >= 30) activity.display else null) ?: activity.windowManager.defaultDisplay
        val current = mode(display.mode)
        val modes = display.supportedModes.map(::mode).sortedWith(compareBy({ -it.width }, { -it.refreshRate }))
        @Suppress("DEPRECATION")
        val capabilities = runCatching { display.hdrCapabilities }.getOrNull()
        val types = if (Build.VERSION.SDK_INT >= 34) display.mode.supportedHdrTypes.takeIf { it.isNotEmpty() } ?: capabilities?.supportedHdrTypes
            else capabilities?.supportedHdrTypes
        val hdr = types?.toList()?.mapNotNull { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DOLBY_VISION
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.HDR10_PLUS
                else -> null
            }
        }?.toSet().orEmpty()
        fun nits(value: Float?) = value?.takeIf { it.isFinite() && it in 50f..20000f }
        return ScreenFacts(
            device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            sdk = Build.VERSION.SDK_INT,
            current = current,
            modes = modes,
            hdr = hdr,
            peakNits = nits(capabilities?.desiredMaxLuminance),
            averageNits = nits(capabilities?.desiredMaxAverageLuminance),
            wideColor = display.isWideColorGamut,
            hdrScreen = activity.resources.configuration.isScreenHdr,
            allmSupported = Build.VERSION.SDK_INT >= 30 && display.isMinimalPostProcessingSupported,
            settings = SystemSettings(
                matchFrameRate = secure(activity, ScreenPlan.KEY_MATCH),
                hdrFormatsAuto = global(activity, ScreenPlan.KEY_HDR_AUTO),
                allm = secure(activity, ScreenPlan.KEY_ALLM),
            ),
            canWriteSettings = canWriteSettings(activity),
        )
    }

    fun canWriteSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun mode(mode: Display.Mode) = Mode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate,
        if (Build.VERSION.SDK_INT >= 31) mode.alternativeRefreshRates.toSet() else emptySet())

    // Since Android 12 hidden keys may be unreadable for apps; that is reported as unknown, not as a value.
    private fun secure(context: Context, key: String): Int? =
        runCatching { Settings.Secure.getInt(context.contentResolver, key) }.getOrNull()

    private fun global(context: Context, key: String): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()
}
