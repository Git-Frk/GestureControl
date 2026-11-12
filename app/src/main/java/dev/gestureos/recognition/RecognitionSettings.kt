package dev.gestureos.recognition

import android.content.Context

/** Shared by the visible UI and the camera service. Existing installations keep their tuning. */
object RecognitionSettings {
    fun load(context: Context): RecognitionConfig = runCatching {
        val preferences = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)
        RecognitionConfig(
            holdMs = preferences.getLong("holdMs", 400),
            cooldownMs = preferences.getLong("cooldownMs", 600),
            smoothingAlpha = preferences.getFloat("alpha", 0.65f),
            maxFps = preferences.getInt("fps", 12),
            extendedAngle = preferences.getFloat("angle", 155f),
        )
    }.getOrDefault(RecognitionConfig())
}
