package com.liuml.apptimelimiter.core

import org.junit.Assert.*
import org.junit.Test

class WindowRestrictionPolicyTest {
    @Test fun windowedRestrictionDoesNotCreateAnotherActivity() {
        assertTrue(WindowRestrictionPolicy.useEmbeddedPanel(TargetWindowMode.WINDOWED))
        assertFalse(WindowRestrictionPolicy.useEmbeddedPanel(TargetWindowMode.FULLSCREEN))
        assertFalse(WindowRestrictionPolicy.useEmbeddedPanel(TargetWindowMode.UNKNOWN))
        assertTrue(WindowRestrictionPolicy.configurationIsWindowed("winConfig={mWindowingMode=freeform mActivityType=standard}"))
        assertTrue(WindowRestrictionPolicy.configurationIsWindowed("mWindowingMode=zoom}"))
        assertFalse(WindowRestrictionPolicy.configurationIsWindowed("mWindowingMode=fullscreen}"))
        assertFalse(WindowRestrictionPolicy.configurationIsWindowed("mWindowingMode=freeformExtra}"))
    }
    @Test fun delayNeverOverridesScheduleCooldownOrQuota() {
        assertTrue(WindowRestrictionPolicy.canExtend(true, false, 0, 1))
        assertFalse(WindowRestrictionPolicy.canExtend(true, true, 0, 1))
        assertFalse(WindowRestrictionPolicy.canExtend(true, false, 1, 1))
        assertFalse(WindowRestrictionPolicy.canExtend(true, false, 0, 0))
        assertFalse(WindowRestrictionPolicy.canExtend(false, false, 0, 1))
    }
    @Test fun ordinaryOverlayRejectsUnknownOrOffscreenWindowBounds() {
        assertNull(TargetWindowBounds.parse(listOf("0", "0", "0", "100")))
        assertNull(TargetWindowBounds.parse(listOf("-1", "0", "100", "100")))
        assertNull(TargetWindowBounds.parse(listOf("0", "0", "bad", "100")))
        val bounds = checkNotNull(TargetWindowBounds.parse(listOf("20", "30", "320", "500")))
        assertTrue(bounds.fits(1080, 2376))
        assertFalse(bounds.fits(300, 2376))
        val legacy = com.liuml.apptimelimiter.nonroot.TargetTaskSnapshot.parse("123|5")
        assertNull(legacy?.bounds)
        assertEquals(bounds, com.liuml.apptimelimiter.nonroot.TargetTaskSnapshot.parse("123|5|20|30|320|500")?.bounds)
    }
}
