package dev.gestureos.cursor

import android.content.Context

/** Shared persisted calibration for the in-app and system cursor adapters. */
object CursorSettings {
    fun load(context: Context): CursorConfig = runCatching {
        val preferences = context.getSharedPreferences("cursor", Context.MODE_PRIVATE)
        CursorConfig(sensitivity = preferences.getFloat("sensitivity", 1f), smoothingMs = preferences.getFloat("smoothing", 85f),
            deadZone = preferences.getFloat("deadZone", 0.004f),
            region = CursorRect(preferences.getFloat("left", 0.15f), preferences.getFloat("top", 0.15f),
                preferences.getFloat("right", 0.85f), preferences.getFloat("bottom", 0.85f)),
            pinchEntry = preferences.getFloat("entry", CursorConfig().pinchEntry),
            pinchRelease = preferences.getFloat("release", CursorConfig().pinchRelease),
            pointArmMs = preferences.getLong("arm", 200), pinchDebounceMs = preferences.getLong("debounce", 80),
            pinchConfirmMs = preferences.getLong("confirm", 200), releaseMs = preferences.getLong("releaseMs", 150),
            clickCooldownMs = preferences.getLong("cooldown", 450))
    }.getOrDefault(CursorConfig())
}
