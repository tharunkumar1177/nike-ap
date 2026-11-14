// DisplayTuning.kt: Galaxy S Ultra display tuning (highest refresh rate, immersive pen canvas).
package dev.padlink.ui

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Window-level display settings shared by the activities. */
object DisplayTuning {

    /** Asks for the display mode with the highest refresh rate at the current resolution (120 Hz panels). */
    fun requestHighRefresh(activity: Activity) {
        val display = currentDisplay(activity) ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        val window = activity.window
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != best.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
    }

    /** Hides system bars; they reappear transiently on an edge swipe. */
    fun enterImmersive(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Display the activity is shown on. */
    private fun currentDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    /** True on Samsung devices, where Air command, Air view, and Edge panels can intercept input. */
    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
