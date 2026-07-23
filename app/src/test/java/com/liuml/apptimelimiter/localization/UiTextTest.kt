package com.liuml.apptimelimiter.localization

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun `chinese mode preserves source text`() {
        assertEquals(
            "退出前提醒",
            UiText.translate("退出前提醒", SupportedLanguage.CHINESE),
        )
    }

    @Test
    fun `english mode translates static setting labels`() {
        assertEquals(
            "Long vibration",
            UiText.translate("长震动提醒", SupportedLanguage.ENGLISH),
        )
        assertEquals(
            "System default",
            UiText.translate("跟随系统", SupportedLanguage.ENGLISH),
        )
    }

    @Test
    fun `english mode translates structured dynamic labels`() {
        assertEquals(
            "3 apps enabled",
            UiText.translate("已启用 3 个应用", SupportedLanguage.ENGLISH),
        )
        assertEquals(
            "2 launches · 1 limit hits",
            UiText.translate("启动 2 次 · 限制触发 1 次", SupportedLanguage.ENGLISH),
        )
    }

    @Test
    fun `unknown app and diagnostic content remains unchanged`() {
        assertEquals(
            "com.example.custom",
            UiText.translate("com.example.custom", SupportedLanguage.ENGLISH),
        )
    }

    @Test
    fun `group member summary translates separators and overflow suffix`() {
        assertEquals(
            "6 apps: App A, App B, etc.",
            UiText.translate("6 个应用：App A、App B 等", SupportedLanguage.ENGLISH),
        )
    }
}
