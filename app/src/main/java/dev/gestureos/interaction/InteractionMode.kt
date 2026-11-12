package dev.gestureos.interaction

enum class InteractionMode { NONE, VOLUME, IN_APP_CURSOR, SYSTEM_CURSOR }

data class ControlRequirements(
    val tracking: Boolean,
    val cameraPermission: Boolean,
    val unlocked: Boolean,
    val accessibility: Boolean = false,
) {
    fun permits(mode: InteractionMode) = mode == InteractionMode.NONE ||
        (tracking && cameraPermission && unlocked && (mode != InteractionMode.SYSTEM_CURSOR || accessibility))
}

/** A single selected mode makes concurrent action controllers impossible. Never persisted. */
class InteractionModePolicy {
    var mode = InteractionMode.NONE
        private set

    fun select(requested: InteractionMode, requirements: ControlRequirements): Boolean {
        if (!requirements.permits(requested)) return false
        mode = requested
        return true
    }

    fun clear() { mode = InteractionMode.NONE }
    fun clearIf(selected: InteractionMode) { if (mode == selected) clear() }
    fun leaveActivity() { if (mode != InteractionMode.SYSTEM_CURSOR) clear() }
}
