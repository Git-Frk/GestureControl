package dev.gestureos.interaction

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/** All callers and observers run on the main thread. */
object InteractionModes {
    private val policy = InteractionModePolicy()
    private val mutableMode = MutableLiveData(policy.mode)
    val state: LiveData<InteractionMode> = mutableMode
    val current get() = policy.mode

    fun select(mode: InteractionMode, requirements: ControlRequirements): Boolean {
        val accepted = policy.select(mode, requirements)
        publish()
        return accepted
    }

    fun clear() { policy.clear(); publish() }
    fun clearIf(mode: InteractionMode) { policy.clearIf(mode); publish() }
    fun leaveActivity() { policy.leaveActivity(); publish() }

    private fun publish() {
        if (mutableMode.value != policy.mode) mutableMode.value = policy.mode
    }
}
