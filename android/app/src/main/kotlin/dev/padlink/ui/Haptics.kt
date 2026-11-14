// Haptics.kt: short tick vibration for clicks, honoring the touch-vibrations setting.
package dev.padlink.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Plays a light tick when [enabled]. */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val effect: VibrationEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
        VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** Mirrors the `haptic_feedback` setting. */
    @Volatile
    var enabled: Boolean = true

    /** Plays one tick if enabled and the device has a vibrator. */
    fun tick() {
        val v = vibrator ?: return
        if (enabled && v.hasVibrator()) v.vibrate(effect)
    }

    private companion object {
        const val TICK_MS = 12L
    }
}
