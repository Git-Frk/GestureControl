package dev.gestureos.volume

import android.content.Context
import android.media.AudioManager
import android.os.UserManager
import android.util.Log

/** Only STREAM_MUSIC is touched. No audio focus, ringer changes, DND access, or mute bypasses. */
class AndroidMediaVolumeDevice(context: Context) : MediaVolumeDevice {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val users = context.applicationContext.getSystemService(UserManager::class.java)

    override fun read(): VolumeReading = guarded {
        val manager = audio ?: return@guarded VolumeReading(restriction = VolumeRestriction.UNAVAILABLE)
        val minimum = manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        VolumeReading(
            VolumeLevel(current, minimum, maximum, manager.isStreamMute(AudioManager.STREAM_MUSIC)),
            when {
                manager.isVolumeFixed -> VolumeRestriction.FIXED
                users?.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME) == true -> VolumeRestriction.POLICY
                else -> null
            },
        )
    }

    override fun adjustOneStep(direction: VolumeDirection): VolumeReading = guarded {
        val before = read()
        if (before.restriction != null || before.level == null || before.level.atLimit(direction)) return@guarded before
        checkNotNull(audio).adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction == VolumeDirection.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        read() // Report the platform's actual level, never a speculative/clamped target.
    }

    private inline fun guarded(operation: () -> VolumeReading): VolumeReading = try {
        operation()
    } catch (error: SecurityException) {
        Log.w(TAG, "Media volume denied by Android audio policy", error)
        VolumeReading(restriction = VolumeRestriction.DENIED)
    } catch (error: RuntimeException) {
        Log.w(TAG, "Media volume unavailable", error)
        VolumeReading(restriction = VolumeRestriction.UNAVAILABLE)
    }

    companion object { private const val TAG = "GestureOSVolume" }
}
