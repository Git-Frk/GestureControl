package dev.gestureos.motion

import android.content.Context
import androidx.core.content.edit

object MotionSettings {
    fun load(context: Context): MotionConfig = runCatching {
        val defaults = MotionConfig()
        val preferences = context.getSharedPreferences("motion", Context.MODE_PRIVATE)
        with(preferences) {
            MotionConfig(
                poseConfirmMs = getLong("poseConfirm", defaults.poseConfirmMs),
                deadZone = getFloat("deadZone", defaults.deadZone),
                smoothingMs = getFloat("smoothing", defaults.smoothingMs),
                sensitivity = getFloat("sensitivity", defaults.sensitivity),
                scrollThreshold = getFloat("threshold", defaults.scrollThreshold),
                scrollDistance = getFloat("distance", defaults.scrollDistance),
                swipeDurationMs = getLong("duration", defaults.swipeDurationMs),
                repeatIntervalMs = getLong("repeat", defaults.repeatIntervalMs),
                navigationDistance = getFloat("navDistance", defaults.navigationDistance),
                navigationMinMs = getLong("navMin", defaults.navigationMinMs),
                navigationMaxMs = getLong("navMax", defaults.navigationMaxMs),
                navigationCooldownMs = getLong("navCooldown", defaults.navigationCooldownMs),
                neutralMs = getLong("neutral", defaults.neutralMs),
            )
        }
    }.getOrDefault(MotionConfig())

    fun save(context: Context, config: MotionConfig) {
        context.getSharedPreferences("motion", Context.MODE_PRIVATE).edit {
            putLong("poseConfirm", config.poseConfirmMs)
            putFloat("deadZone", config.deadZone)
            putFloat("smoothing", config.smoothingMs)
            putFloat("sensitivity", config.sensitivity)
            putFloat("threshold", config.scrollThreshold)
            putFloat("distance", config.scrollDistance)
            putLong("duration", config.swipeDurationMs)
            putLong("repeat", config.repeatIntervalMs)
            putFloat("navDistance", config.navigationDistance)
            putLong("navMin", config.navigationMinMs)
            putLong("navMax", config.navigationMaxMs)
            putLong("navCooldown", config.navigationCooldownMs)
            putLong("neutral", config.neutralMs)
        }
    }
}
