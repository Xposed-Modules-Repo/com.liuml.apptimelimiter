package com.liuml.apptimelimiter.nonroot

import android.view.accessibility.AccessibilityEvent
import com.liuml.apptimelimiter.data.NonRootCompatibilityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityForegroundEventPolicyTest {
    @Test
    fun `window state event identifies foreground package`() {
        assertEquals(
            "com.example.target",
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                " com.example.target ",
                "com.liuml.apptimelimiter",
            ),
        )
    }

    @Test
    fun `windows changed event from accessibility overlay is ignored`() {
        assertNull(
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                "com.liuml.apptimelimiter",
                "com.liuml.apptimelimiter",
            ),
        )
    }

    @Test
    fun `window state event from visible accessibility overlay is ignored`() {
        assertNull(
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.liuml.apptimelimiter",
                "com.liuml.apptimelimiter",
                ignoreOwnPackageWindowEvent = true,
            ),
        )
    }

    @Test
    fun `own activity still identifies foreground when no overlay is visible`() {
        assertEquals(
            "com.liuml.apptimelimiter",
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                "com.liuml.apptimelimiter",
                "com.liuml.apptimelimiter",
                ignoreOwnPackageWindowEvent = false,
            ),
        )
    }

    @Test
    fun `windows changed event identifies resumed target task`() {
        assertEquals(
            "com.example.target",
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                "com.example.target",
                "com.liuml.apptimelimiter",
            ),
        )
    }

    @Test
    fun `blank package is ignored`() {
        assertNull(
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                " ",
                "com.liuml.apptimelimiter",
            ),
        )
    }

    @Test
    fun `content change is ignored in standard mode`() {
        assertNull(
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.example.target",
                "com.liuml.apptimelimiter",
                compatibilityMode = NonRootCompatibilityMode.STANDARD,
            ),
        )
    }

    @Test
    fun `content change identifies package only in enhanced mode`() {
        assertEquals(
            "com.example.target",
            AccessibilityForegroundEventPolicy.packageForForegroundEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                "com.example.target",
                "com.liuml.apptimelimiter",
                compatibilityMode = NonRootCompatibilityMode.ENHANCED_EVENTS,
            ),
        )
    }
}
