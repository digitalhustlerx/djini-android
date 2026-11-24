package com.wangzan101.djini.buddy

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.wangzan101.djini.core.buddy.HapticPattern

/**
 * Dynamic physical haptic engine backed by Android's [VibratorManager] / [Vibrator].
 * Supports full amplitude-controlled waveforms on modern linear motors (e.g. Samsung S21)
 * with graceful fallback on simpler motors.
 */
class HapticBehaviorEngine(context: Context) {
    private val app = context.applicationContext

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    fun hasHardware(): Boolean = vibrator()?.hasVibrator() == true

    fun hasAmplitudeControl(): Boolean = vibrator()?.hasAmplitudeControl() == true

    fun play(pattern: HapticPattern): Boolean =
        playWaveform(pattern.timingsMs, pattern.amplitudes)

    fun playWaveform(timingsMs: List<Long>, amplitudes: List<Int>): Boolean {
        val v = vibrator() ?: return false
        if (!v.hasVibrator()) return false
        return runCatching {
            val timings = timingsMs.toLongArray()
            val amps = amplitudes.map { it.coerceIn(0, 255) }.toIntArray()
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, amps, -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            v.vibrate(effect)
            true
        }.onFailure {
            Log.w(TAG, "Failed to play haptic waveform", it)
        }.getOrDefault(false)
    }

    fun cancel() {
        runCatching { vibrator()?.cancel() }
    }

    companion object {
        private const val TAG = "DjiniHaptics"
    }
}
