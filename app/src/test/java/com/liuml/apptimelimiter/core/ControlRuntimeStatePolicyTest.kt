package com.liuml.apptimelimiter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRuntimeStatePolicyTest {
    @Test fun `restriction flow accepts parent unlock and cooldown`() {
        assertTrue(ControlRuntimeStatePolicy.canTransition(ControlRuntimeState.LIMIT_CLAIMED, ControlRuntimeState.WAITING_PARENT_AUTH))
        assertTrue(ControlRuntimeStatePolicy.canTransition(ControlRuntimeState.WAITING_PARENT_AUTH, ControlRuntimeState.OVERRIDE_ACTIVE))
        assertTrue(ControlRuntimeStatePolicy.canTransition(ControlRuntimeState.OVERRIDE_ACTIVE, ControlRuntimeState.COOLDOWN_ACTIVE))
        assertFalse(ControlRuntimeStatePolicy.canTransition(ControlRuntimeState.CANCELLED, ControlRuntimeState.OVERRIDE_ACTIVE))
    }
}
