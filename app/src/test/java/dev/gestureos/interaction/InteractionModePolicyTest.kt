package dev.gestureos.interaction

import org.junit.Assert.*
import org.junit.Test

class InteractionModePolicyTest {
    private val ready = ControlRequirements(true, true, true, true)

    @Test fun coldLaunchIsDisabled() {
        assertEquals(InteractionMode.NONE, InteractionModePolicy().mode)
    }

    @Test fun allActionModesAreMutuallyExclusive() {
        val policy = InteractionModePolicy()
        for (mode in InteractionMode.entries) {
            assertTrue(policy.select(mode, ready))
            assertEquals(mode, policy.mode)
        }
    }

    @Test fun systemModeRequiresTrackingPermissionUnlockedScreenAndConnection() {
        for (requirements in listOf(ready.copy(tracking = false), ready.copy(cameraPermission = false),
            ready.copy(unlocked = false), ready.copy(accessibility = false))) {
            val policy = InteractionModePolicy()
            assertFalse(policy.select(InteractionMode.SYSTEM_CURSOR, requirements))
            assertEquals(InteractionMode.NONE, policy.mode)
        }
    }

    @Test fun localModesWorkWithoutAccessibility() {
        val policy = InteractionModePolicy()
        assertTrue(policy.select(InteractionMode.IN_APP_CURSOR, ready.copy(accessibility = false)))
        assertTrue(policy.select(InteractionMode.VOLUME, ready.copy(accessibility = false)))
    }

    @Test fun homePreservesOnlySystemMode() {
        for (mode in InteractionMode.entries) {
            val policy = InteractionModePolicy()
            policy.select(mode, ready)
            policy.leaveActivity()
            assertEquals(if (mode == InteractionMode.SYSTEM_CURSOR) mode else InteractionMode.NONE, policy.mode)
        }
    }

    @Test fun disablingLocalControllerDoesNotDisableSystemController() {
        val policy = InteractionModePolicy()
        policy.select(InteractionMode.SYSTEM_CURSOR, ready)
        policy.clearIf(InteractionMode.VOLUME)
        policy.clearIf(InteractionMode.IN_APP_CURSOR)
        assertEquals(InteractionMode.SYSTEM_CURSOR, policy.mode)
        policy.clear()
        assertEquals(InteractionMode.NONE, policy.mode)
    }

    @Test fun rejectedActivationDoesNotChangeTheCurrentMode() {
        val policy = InteractionModePolicy()
        policy.select(InteractionMode.VOLUME, ready)
        assertFalse(policy.select(InteractionMode.SYSTEM_CURSOR, ready.copy(accessibility = false)))
        assertEquals(InteractionMode.VOLUME, policy.mode)
    }
}
