package com.liuml.apptimelimiter.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(
            "3/20 lines, up to 80 characters each",
            UiText.translate("3/20 句，每句最多80字", SupportedLanguage.ENGLISH),
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

    @Test
    fun `recent feature settings and notice text have english coverage`() {
        val recentUiText = listOf(
            "分组额度与规则",
            "固定管控规则",
            "提醒与延时",
            "统计与诊断",
            "应用设置",
            "维护与支持",
            "外观",
            "主题模式",
            "颜色主题",
            "健康绿",
            "宁静蓝",
            "专注紫",
            "明暗模式",
            "时间短句",
            "使用内置短句",
            "自定义短句（每行一句）",
            "选择舒适的浅色或深色界面",
            "浅色",
            "深色",
            "自动检查更新",
            "打开时停时按需检查，新版发布后主动提醒",
            "精细管控",
            "把使用边界设清楚",
            "• 任一规则先到即执行退出",
            "本次计划",
            "欢迎使用时停",
            "加入内测",
            "支持时停开发",
            "支付宝",
            "微信支付",
            "查看软件声明",
            "软件声明",
            "保护方式",
            "非 Root 普通保护",
            "无障碍服务用途说明",
            "时停使用无障碍服务识别当前前台应用，并在用户保存的时间规则触发时显示计划选择浮层或独立限制页；限制页启动失败时返回桌面。",
            "同意并前往启用",
            "Shizuku 强停增强",
            "请求 Shizuku 授权",
            "安装与启动指南",
            "自启动与电池设置",
            "检查无障碍连接",
            "普通保护运行中",
            "普通保护未就绪",
            "部分应用管控异常",
            "管控运行中",
            "管控已配置",
            "运行中",
            "需要重启",
            "运行异常",
            "等待验证",
            "普通保护需要修复",
            "保护权限需要处理",
            "• 时停无障碍服务尚未启用",
            "• 使用情况访问权限尚未授予",
            "• Shizuku 尚未授权，强制退出不可用",
            "• Shizuku 未安装或服务尚未运行",
            "• Shizuku 连接失败",
            "查看 Shizuku 设置指南",
            "普通保护运行中 · Shizuku 需处理",
            "选择适合你的保护方式",
            "普通保护需要无障碍服务与使用情况访问",
            "普通保护到限方式",
            "使用无障碍前台事件和系统使用统计，无需 Root",
            "选择达到限制后的执行方式",
            "本次使用计划会在结束前 5 秒提供重新计划入口。",
            "必要权限缺失时，普通保护不会生效。请完成授权后重新打开目标应用。",
            "普通保护需要启用时停无障碍服务，并授予使用情况访问权限。",
            "只有检测到应用未加入作用域或运行异常时，时停才会显示修复提示。",
            "规则保存后由目标应用执行；若实际未生效，请在诊断日志中检查运行状态。",
            "暂无日志。请打开一次已配置的目标应用；若仍为空，请检查当前保护方式的权限与配置。",
            "独立限制页",
            "Shizuku 强制退出",
            "无障碍与系统使用统计协同管控，达到限制后显示独立限制页",
            "只读取当前前台应用包名，不读取页面节点、文字或输入内容；达到限制时显示独立限制页，启动失败才返回桌面。",
            "到限后直接显示独立限制页，解除后可恢复原应用；目标进程保持运行，后台音乐可能继续。",
            "Android 系统前台区间去重 · 无后台服务",
            "LSPosed 提醒与退出",
            "LSPosed 到限方式",
            "恢复桌面图标",
            "增强兼容检测",
        )

        recentUiText.forEach { source ->
            val translated = UiText.translate(source, SupportedLanguage.ENGLISH)
            assertFalse("Untranslated English UI text: $source", CJK.containsMatchIn(translated))
        }
    }

    @Test
    fun `recent dynamic labels have english coverage`() {
        val dynamicUiText = listOf(
            "2 个应用分组",
            "共享每日：已用 5分 / 10分 · 剩余 5分",
            "Example（已有个人设置）",
            "Example（个人设置已暂停）",
            "Example（已停用）",
            "加入 QQ 群：1009712674",
        )

        dynamicUiText.forEach { source ->
            val translated = UiText.translate(source, SupportedLanguage.ENGLISH)
            assertFalse("Untranslated English UI text: $source", CJK.containsMatchIn(translated))
        }
    }

    private companion object {
        val CJK = Regex("[\\u4e00-\\u9fff]")
    }
}
