package com.example.appletvremote.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Short, physical confirmations for the on-screen remote.
 *
 * Compose's [androidx.compose.ui.hapticfeedback.HapticFeedback] is enough for a
 * tick, but a deliberate long press has to feel different from a tap. This
 * wrapper talks to the vibrator directly so a hold gets a double pulse, the
 * same way the system does for a successful long press.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val available: Boolean = vibrator?.hasVibrator() == true

    /** A button press or a touchpad tap. */
    fun tap() = oneShot(durationMs = 9L, amplitude = 110)

    /** The very first contact: lighter than a click, so a swipe does not feel heavy. */
    fun touchDown() = oneShot(durationMs = 6L, amplitude = 70)

    /** A long press was recognised - a distinct double pulse. */
    fun longPress() {
        val v = vibrator ?: return
        if (!available) return
        runCatching {
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 26L, 64L, 42L),
                    intArrayOf(0, 180, 0, 235),
                    -1,
                ),
            )
        }
    }

    private fun oneShot(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!available) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current.applicationContext
    return remember(context) { Haptics(context) }
}
