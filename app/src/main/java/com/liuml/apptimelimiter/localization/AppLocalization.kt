package com.liuml.apptimelimiter.localization

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.liuml.apptimelimiter.data.AppLanguageMode
import java.util.Locale

object AppLocaleController {
    fun wrap(base: Context, mode: AppLanguageMode): Context {
        val locale = mode.explicitLocale() ?: return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(configuration)
    }

    /** Returns true when the caller must recreate its Activity manually. */
    fun apply(context: Context, mode: AppLanguageMode): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return true
        val locales = mode.explicitLocale()?.let { LocaleList(it) }
            ?: LocaleList.getEmptyLocaleList()
        if (localeManager.applicationLocales != locales) {
            localeManager.applicationLocales = locales
        }
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolvedLanguage(context: Context, mode: AppLanguageMode): SupportedLanguage = when (mode) {
        AppLanguageMode.SIMPLIFIED_CHINESE -> SupportedLanguage.CHINESE
        AppLanguageMode.ENGLISH -> SupportedLanguage.ENGLISH
        AppLanguageMode.SYSTEM -> if (
            Resources.getSystem().configuration.locales.get(0)?.language == Locale.CHINESE.language
        ) {
            SupportedLanguage.CHINESE
        } else {
            SupportedLanguage.ENGLISH
        }
    }

    private fun AppLanguageMode.explicitLocale(): Locale? = when (this) {
        AppLanguageMode.SYSTEM -> null
        AppLanguageMode.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLanguageMode.ENGLISH -> Locale.ENGLISH
    }
}

enum class SupportedLanguage {
    CHINESE,
    ENGLISH,
}

/**
 * Transitional localization layer for the existing Compose UI. Static text is translated by an
 * exact table, while a small set of structured dynamic labels is handled explicitly. Unknown
 * content (application names, package names, release notes and diagnostics) is left untouched.
 */
object UiText {
    fun translate(source: String, language: SupportedLanguage): String {
        if (language == SupportedLanguage.CHINESE || source.isBlank()) return source
        EXACT[source]?.let { return it }
        dynamicTranslation(source)?.let { return it }
        return source
    }

    fun translate(context: Context, mode: AppLanguageMode, source: String): String =
        translate(source, AppLocaleController.resolvedLanguage(context, mode))

    private fun dynamicTranslation(source: String): String? {
        if (source.startsWith("✓ ")) {
            return "✓ ${translate(source.removePrefix("✓ "), SupportedLanguage.ENGLISH)}"
        }
        if (source.startsWith("• ")) {
            return "• ${translate(source.removePrefix("• "), SupportedLanguage.ENGLISH)}"
        }
        if (" · " in source) {
            val translated = source.split(" · ")
                .joinToString(" · ") { translate(it, SupportedLanguage.ENGLISH) }
            if (translated != source) return translated
        }
        if ('\n' in source) {
            val translated = source.lines()
                .joinToString("\n") { translate(it, SupportedLanguage.ENGLISH) }
            if (translated != source) return translated
        }
        Regex("^已启用 (\\d+) 个应用$").matchEntire(source)?.let {
            return "${it.groupValues[1]} apps enabled"
        }
        Regex("^(\\d+) 个分组共享每日额度$").matchEntire(source)?.let {
            return "${it.groupValues[1]} shared-allowance groups"
        }
        Regex("^(\\d+) 个应用分组$").matchEntire(source)?.let {
            return "${it.groupValues[1]} app groups"
        }
        Regex("^已验证 (\\d+) / (\\d+) 个应用$").matchEntire(source)?.let {
            return "${it.groupValues[1]} / ${it.groupValues[2]} apps verified"
        }
        Regex("^(\\d+) 个应用未加入作用域$").matchEntire(source)?.let {
            return "${it.groupValues[1]} apps are outside the scope"
        }
        Regex("^已就绪 (\\d+) / (\\d+) 个应用$").matchEntire(source)?.let {
            return "${it.groupValues[1]} / ${it.groupValues[2]} apps ready"
        }
        Regex("^仍有 (\\d+) 个应用启动后才能完成 Hook 验证$").matchEntire(source)?.let {
            return "${it.groupValues[1]} apps need to be launched to finish Hook verification"
        }
        Regex("^(\\d+) 个目标应用进程已回传当前 Hook 状态$").matchEntire(source)?.let {
            return "${it.groupValues[1]} target processes reported the current Hook state"
        }
        Regex("^(\\d+) 个管控应用均已加入作用域$").matchEntire(source)?.let {
            return "All ${it.groupValues[1]} controlled apps are in scope"
        }
        Regex("^仍有 (\\d+) 个应用待验证；请确认作用域后强停并重开它们$").matchEntire(source)?.let {
            return "${it.groupValues[1]} apps still need verification; check the scope, then force-stop and reopen them"
        }
        Regex("^(\\d+) 个管控应用均已回传当前版本 Hook 记录$").matchEntire(source)?.let {
            return "All ${it.groupValues[1]} controlled apps reported the current Hook version"
        }
        Regex("^(\\d+)分$").matchEntire(source)?.let { return "${it.groupValues[1]}m" }
        Regex("^(\\d+)时(\\d+)分$").matchEntire(source)?.let {
            return "${it.groupValues[1]}h ${it.groupValues[2]}m"
        }
        Regex("^(\\d+) 个$").matchEntire(source)?.let { return it.groupValues[1] }
        Regex("^启动 (\\d+) 次 · 限制触发 (\\d+) 次$").matchEntire(source)?.let {
            return "${it.groupValues[1]} launches · ${it.groupValues[2]} limit hits"
        }
        Regex("^共享每日：已用 (.+) / (.+) · 剩余 (.+)$").matchEntire(source)?.let {
            return "Shared daily: used ${translate(it.groupValues[1], SupportedLanguage.ENGLISH)} / " +
                "${translate(it.groupValues[2], SupportedLanguage.ENGLISH)} · " +
                "${translate(it.groupValues[3], SupportedLanguage.ENGLISH)} remaining"
        }
        Regex("^诊断日志（(\\d+)）$").matchEntire(source)?.let {
            return "Diagnostic logs (${it.groupValues[1]})"
        }
        Regex("^选择应用（(\\d+)）$").matchEntire(source)?.let {
            return "Select apps (${it.groupValues[1]})"
        }
        Regex("^发现新版本 (.+)$").matchEntire(source)?.let {
            return "New version ${it.groupValues[1]} available"
        }
        Regex("^当前版本：(.+)$").matchEntire(source)?.let {
            return "Current version: ${it.groupValues[1]}"
        }
        Regex("^当前版本 (.+)，GitHub 最新版本 (.+)。$").matchEntire(source)?.let {
            return "Current version ${it.groupValues[1]}; latest GitHub version ${it.groupValues[2]}."
        }
        Regex("^版本 (.+)$").matchEntire(source)?.let { return "Version ${it.groupValues[1]}" }
        Regex("^反馈邮箱：(.+)$").matchEntire(source)?.let { return "Feedback: ${it.groupValues[1]}" }
        Regex("^最多添加 (\\d+) 个时段。$").matchEntire(source)?.let {
            return "You can add up to ${it.groupValues[1]} time windows."
        }
        Regex("^开始 (.+)$").matchEntire(source)?.let { return "Start ${it.groupValues[1]}" }
        Regex("^结束 (.+)$").matchEntire(source)?.let { return "End ${it.groupValues[1]}" }
        Regex("^(.+)时长（分钟）$").matchEntire(source)?.let {
            return "${translate(it.groupValues[1], SupportedLanguage.ENGLISH)} duration (minutes)"
        }
        Regex("^(\\d+) 秒$").matchEntire(source)?.let { return "${it.groupValues[1]} sec" }
        Regex("^(\\d+) 分钟$").matchEntire(source)?.let { return "${it.groupValues[1]} min" }
        Regex("^(\\d+)/(\\d+) 句，每句最多(\\d+)字$").matchEntire(source)?.let {
            return "${it.groupValues[1]}/${it.groupValues[2]} lines, " +
                "up to ${it.groupValues[3]} characters each"
        }
        Regex("^每日 (.+)$").matchEntire(source)?.let { return "Daily ${it.groupValues[1]}" }
        Regex("^单次 (.+)$").matchEntire(source)?.let { return "Per launch ${it.groupValues[1]}" }
        Regex("^冷却 (.+)$").matchEntire(source)?.let { return "Cooldown ${it.groupValues[1]}" }
        Regex("^已用 (.+) / (.+) · 剩余 (.+)$").matchEntire(source)?.let {
            return "Used ${translate(it.groupValues[1], SupportedLanguage.ENGLISH)} / " +
                "${translate(it.groupValues[2], SupportedLanguage.ENGLISH)} · " +
                "${translate(it.groupValues[3], SupportedLanguage.ENGLISH)} remaining"
        }
        Regex("^(\\d+) 个应用：(.*)$").matchEntire(source)?.let {
            val hasMore = it.groupValues[2].endsWith(" 等")
            val labels = it.groupValues[2]
                .removeSuffix(" 等")
                .replace("、", ", ")
            return "${it.groupValues[1]} apps: $labels${if (hasMore) ", etc." else ""}"
        }
        Regex("^删除 (.+)？$").matchEntire(source)?.let { return "Delete ${it.groupValues[1]}?" }
        Regex("^(.+)（已在 (.+)）$").matchEntire(source)?.let {
            return "${it.groupValues[1]} (in ${it.groupValues[2]})"
        }
        Regex("^(.+)（已有个人设置）$").matchEntire(source)?.let {
            return "${it.groupValues[1]} (has personal settings)"
        }
        Regex("^(.+)（个人设置已暂停）$").matchEntire(source)?.let {
            return "${it.groupValues[1]} (personal settings paused)"
        }
        Regex("^(.+)（已停用）$").matchEntire(source)?.let {
            return "${it.groupValues[1]} (disabled)"
        }
        Regex("^加入 QQ 群：(.+)$").matchEntire(source)?.let {
            return "Join QQ group: ${it.groupValues[1]}"
        }
        Regex("^该时段将在次日 (.+) 结束。$").matchEntire(source)?.let {
            return "This window ends at ${it.groupValues[1]} the next day."
        }
        Regex("^支付宝捐赠：(.+)$").matchEntire(source)?.let {
            return "Alipay donation: ${it.groupValues[1]}"
        }
        if (source == "支付宝或微信扫码支持开发") {
            return "Support development with Alipay or WeChat Pay"
        }
        if (source == "查看收款码 ›") return "View payment QR codes ›"
        Regex("^(.+) · 共享额度$").matchEntire(source)?.let {
            return "${it.groupValues[1]} · shared allowance"
        }
        Regex("^(.+)图标$").matchEntire(source)?.let { return "${it.groupValues[1]} icon" }
        WEEKDAY_SELECTED.matchEntire(source)?.let {
            return "✓${EXACT[it.groupValues[1]] ?: it.groupValues[1]}"
        }
        return null
    }

    private val EXACT = mapOf(
        "时停" to "Time Stop",
        "首页" to "Home",
        "应用" to "Apps",
        "分组" to "Groups",
        "统计" to "Stats",
        "应用管理" to "App management",
        "应用分组" to "App groups",
        "使用统计" to "Usage statistics",
        "应用使用时长管控" to "App usage time control",
        "展示今日使用过的全部应用" to "All apps used today",
        "设置" to "Settings",
        "保护方式" to "Protection method",
        "保护状态" to "Protection status",
        "刷新状态" to "Refresh status",
        "查看作用域提示" to "View scope guide",
        "恢复权限提醒" to "Restore permission reminders",
        "权限提醒已恢复" to "Permission reminders restored",
        "不再显示此类问题" to "Do not show this type of issue again",
        "独立限制页兼容性" to "Standalone restriction-page compatibility",
        "打开兼容设置" to "Open compatibility settings",
        "返回" to "Back",
        "一键修复" to "Quick repairs",
        "开启无障碍" to "Enable accessibility",
        "Shizuku 指南" to "Shizuku guide",
        "请求加入作用域" to "Request scope",
        "检查作用域" to "Check scope",
        "仅看需修复" to "Needs attention only",
        "正常" to "Healthy",
        "待修复" to "Needs attention",
        "规则" to "Rule",
        "已保存" to "Saved",
        "LSPosed 作用域" to "LSPosed scope",
        "Hook 心跳" to "Hook heartbeat",
        "普通保护权限" to "Basic protection permissions",
        "完整" to "Complete",
        "缺失" to "Missing",
        "当前模式不适用" to "Not applicable in this mode",
        "最终接管" to "Effective controller",
        "请求作用域" to "Request scope",
        "作用域提示" to "Scope guide",
        "强停并重开" to "Force-stop and reopen",
        "重新打开" to "Reopen",
        "确认停止" to "Confirm stop",
        "LSPosed" to "LSPosed",
        "普通保护" to "Basic protection",
        "普通保护 + Shizuku" to "Basic protection + Shizuku",
        "由目标应用内 Hook 精确计时并执行限制" to
            "Uses in-app hooks for precise timing and enforcement",
        "无障碍识别前台，UsageStats 校准，到限显示独立限制页" to
            "Accessibility detects foreground use, UsageStats calibrates it, and a restriction page enforces limits",
        "普通保护计时，到限优先通过 Shizuku 强停" to
            "Basic protection handles timing and Shizuku force-stops at the limit",
        "所有管控应用统一使用所选链路，模式之间不会自动混合接管。" to
            "All managed apps use the selected controller. Modes never take over from one another automatically.",
        "非 Root 普通保护" to "Non-root basic protection",
        "使用无障碍前台事件和系统使用统计，无需 Root" to
            "Uses accessibility foreground events and system usage data; no Root required",
        "使用无障碍前台事件和系统使用统计；LSPosed 生效时自动由 Hook 接管" to
            "Uses accessibility foreground events and system usage data; Hook takes over automatically when LSPosed is active",
        "无障碍服务已启用" to "Accessibility service enabled",
        "无障碍服务尚未启用" to "Accessibility service is not enabled",
        "使用情况访问已授予" to "Usage access granted",
        "使用情况访问尚未授予" to "Usage access is not granted",
        "只读取当前前台应用包名，不读取页面节点、文字或输入内容；达到限制时执行返回桌面并显示限制页。" to
            "Only the foreground app package is read. Page nodes, text, and input are never read. When a limit is reached, Time Stop returns Home and shows a restriction page.",
        "只读取当前前台应用包名，不读取页面节点、文字或输入内容；达到限制时显示独立限制页，启动失败才返回桌面。" to
            "Only the foreground app package is read. Page nodes, text, and input are never read. At the limit, Time Stop opens the standalone restriction page and returns Home only if it cannot open.",
        "启用无障碍" to "Enable accessibility",
        "检查无障碍连接" to "Check accessibility connection",
        "授予使用情况访问" to "Grant usage access",
        "自启动与电池设置" to "Autostart and battery settings",
        "Shizuku 强停增强" to "Shizuku force-stop enhancement",
        "可选；只在到限时强停已配置的第三方应用，失败自动回退限制页" to
            "Optional; force-stops configured third-party apps only at the limit and falls back to the restriction page on failure",
        "普通保护到限方式" to "Basic protection action at the limit",
        "选择达到限制后的执行方式" to "Choose what happens when a limit is reached",
        "只影响未被 LSPosed Hook 接管的应用" to
            "Only affects apps that are not handled by the LSPosed Hook",
        "独立限制页" to "Standalone restriction page",
        "Shizuku 强制退出" to "Shizuku force-stop",
        "到限后优先强停第三方应用并丢失当前页面；不可用或执行失败时自动回退独立限制页。" to
            "Force-stops third-party apps first and discards the current page. If unavailable or unsuccessful, it falls back to the standalone restriction page.",
        "到限后返回桌面并显示独立限制页，目标进程保持运行，后台音乐可能继续。" to
            "Returns Home and shows a standalone restriction page. The target process stays alive and background audio may continue.",
        "到限后直接显示独立限制页，解除后可恢复原应用；目标进程保持运行，后台音乐可能继续。" to
            "Opens the standalone restriction page so the target app can resume after the restriction clears. The target process stays alive and background audio may continue.",
        "本次使用计划会在结束前 5 秒提供重新计划入口。" to
            "A session plan offers a replan action five seconds before it ends.",
        "非 Root 的本次使用计划会在结束前 5 秒提供重新计划入口，不使用下方 LSPosed 提醒设置。" to
            "Non-root session plans offer a replan action five seconds before ending and do not use the LSPosed reminder settings below.",
        "Shizuku 已连接并授权" to "Shizuku connected and authorized",
        "正在连接 Shizuku" to "Connecting to Shizuku",
        "Shizuku 等待授权" to "Shizuku authorization required",
        "未检测到运行中的 Shizuku" to "No running Shizuku service detected",
        "Shizuku 连接失败" to "Shizuku connection failed",
        "保存设置后启用 Shizuku 增强" to "Save settings to enable Shizuku enhancement",
        "保存设置后启用 Shizuku 强制退出" to
            "Save settings to enable Shizuku force-stop",
        "强停会丢失目标应用当前页面；Shizuku 非 Root 模式通常需要在重启后重新启动。" to
            "Force-stop discards the target app's current page. Non-root Shizuku usually needs to be started again after a reboot.",
        "请求 Shizuku 授权" to "Request Shizuku authorization",
        "安装与启动指南" to "Installation and startup guide",
        "LSPosed 提醒与退出" to "LSPosed reminders and enforcement",
        "LSPosed 到限方式" to "LSPosed action at the limit",
        "只影响已被 Hook 接管的目标应用" to
            "Only affects target apps handled by the Hook",
        "达到限制后打开独立休息页，使目标界面自然暂停，并尽力暂停常见媒体。休息页不提供延时；单次额度配合冷却时，结束后可继续原页面。切换方式后请强停并重开目标应用。" to
            "Opens a standalone break page at the limit so the target screen pauses naturally, while common media is paused on a best-effort basis. The page has no extension action; a per-session allowance with cooldown can resume the original page afterward. Force-stop and reopen targets after switching modes.",
        "Hook 目标到期前 5 秒显示倒计时" to
            "Shows a countdown five seconds before a Hook target reaches its limit",
        "开启后倒计时覆盖 Hook 目标；关闭时显示顶部圆角提醒" to
            "When enabled, the countdown covers the Hook target; otherwise a rounded top reminder is shown",
        "Hook 退出倒计时出现时震动一次" to
            "Vibrates once when the Hook exit countdown appears",
        "仅对 Hook 提醒生效；可设置 1–60 分钟" to
            "Only affects Hook reminders; range: 1–60 minutes",
        "恢复桌面图标" to "Restore launcher icon",
        "当前无法确认 LSPosed 设置入口，请恢复图标避免无法打开时停" to
            "The LSPosed settings entry cannot be confirmed. Restore the icon to avoid losing access to Time Stop.",
        "仅在 LSPosed 设置入口可用时提供；隐藏后从模块页打开" to
            "Available only when the LSPosed settings entry works; open Time Stop from the module page after hiding it",
        "当前未检测到可用的 LSPosed 设置入口，请关闭上方开关恢复桌面图标；也可连接电脑执行：" to
            "No working LSPosed settings entry was detected. Turn off the switch above to restore the launcher icon, or run this command from a computer:",
        "隐藏后桌面缓存图标可能短暂残留。可从 LSPosed 模块页进入；也可连接电脑执行：" to
            "A cached launcher icon may remain briefly after hiding. Open Time Stop from the LSPosed module page, or run this command from a computer:",
        "记录管控引擎、包名、时间戳和限制事件；仅在你主动反馈时导出" to
            "Records control-engine events, package names, timestamps, and limits; exported only when you choose to send feedback",
        "请选择反馈方式。邮件反馈会附带设备型号、包名、时间戳和诊断日志，仅在你主动发送时离开设备；QQ群适合交流和参与内测。" to
            "Choose a feedback method. Email includes device model, package names, timestamps, and diagnostic logs and leaves the device only when you send it; the QQ group is for discussion and beta testing.",
        "无障碍服务用途说明" to "Accessibility service disclosure",
        "时停使用无障碍服务识别当前前台应用，并在用户保存的时间规则触发时返回桌面、显示计划选择或限制浮层。" to
            "Time Stop uses accessibility to identify the foreground app and, when a saved time rule triggers, return Home and show a plan picker or restriction overlay.",
        "时停使用无障碍服务识别当前前台应用，并在用户保存的时间规则触发时返回桌面、显示计划选择浮层或独立限制页。" to
            "Time Stop uses accessibility to identify the foreground app and, when a saved time rule triggers, return Home and show a plan-picker overlay or the standalone restriction page.",
        "时停使用无障碍服务识别当前前台应用，并在用户保存的时间规则触发时显示计划选择浮层或独立限制页；限制页启动失败时返回桌面。" to
            "Time Stop uses accessibility to identify the foreground app and show a plan-picker overlay or standalone restriction page when a saved rule triggers. It returns Home only if the restriction page cannot open.",
        "时停不会读取页面节点、文字、账号、输入内容或通知，也不会上传无障碍事件。" to
            "Time Stop does not read page nodes, text, accounts, input, or notifications, and does not upload accessibility events.",
        "普通保护属于自我时间管理。你可以随时在系统设置中关闭服务；关闭后非 Root 管控将停止。" to
            "Basic protection is for self-managed screen time. You can disable the service in system settings at any time; non-root control then stops.",
        "同意并前往启用" to "Agree and enable",
        "暂不开启" to "Not now",
        "LSPosed 保护运行中" to "LSPosed protection is active",
        "混合保护运行中" to "Mixed protection is active",
        "Shizuku 增强保护运行中" to "Shizuku enhanced protection is active",
        "普通保护运行中" to "Basic protection is active",
        "普通保护未就绪" to "Basic protection is not ready",
        "部分应用管控异常" to "Some app controls need attention",
        "管控运行中" to "Control is active",
        "管控已配置" to "Control is configured",
        "无障碍服务未启用" to "Accessibility service is disabled",
        "使用情况访问未授权" to "Usage access is not granted",
        "目标应用由 LSPosed 独占管控，普通保护会自动停止重复计时" to
            "LSPosed exclusively controls target apps; basic protection stops duplicate timing automatically",
        "已 Hook 的目标由 LSPosed 管控，其余目标由普通保护接管" to
            "Hooked targets use LSPosed; basic protection handles the remaining targets",
        "无障碍负责计时，达到限制后优先通过 Shizuku 强停第三方应用" to
            "Accessibility tracks time; Shizuku force-stops third-party apps first when a limit is reached",
        "无障碍与系统使用统计协同管控，达到限制后返回桌面并显示限制页" to
            "Accessibility and system usage data work together; Time Stop returns Home and shows a restriction page at the limit",
        "无障碍与系统使用统计协同管控，达到限制后显示独立限制页" to
            "Accessibility and system usage data work together; Time Stop opens the standalone restriction page at the limit",
        "普通保护需要启用时停无障碍服务" to
            "Basic protection requires the Time Stop accessibility service",
        "普通保护需要使用情况访问来校准每日累计时长" to
            "Basic protection requires usage access to calibrate daily totals",
        "请检查普通保护所需权限" to "Check the permissions required by basic protection",
        "请强制停止异常应用并重新打开" to
            "Force-stop and reopen the affected apps",
        "目标应用正在执行已保存的时间规则" to
            "Target apps are enforcing the saved time rules",
        "打开目标应用后自动生效" to "Control starts automatically when a target app opens",
        "若应用未被限制，请打开诊断日志检查运行状态" to
            "If an app is not limited, open diagnostics to check its runtime state",
        "选择适合你的保护方式" to "Choose the protection method that fits",
        "有 LSPosed 时可加入模块作用域获得更稳定的进程内管控；未 Root 设备也可启用普通保护。" to
            "With LSPosed, add apps to the module scope for more reliable in-process control. Non-root devices can use basic protection.",
        "普通保护需要无障碍服务与使用情况访问" to
            "Basic protection requires accessibility and usage access",
        "Shizuku 可选增强只负责到限强停" to
            "The optional Shizuku enhancement only force-stops apps at the limit",
        "普通保护需要修复" to "Basic protection needs attention",
        "保护权限需要处理" to "Protection permissions need attention",
        "时停无障碍服务尚未启用" to "The Time Stop accessibility service is not enabled",
        "使用情况访问权限尚未授予" to "Usage access has not been granted",
        "Shizuku 尚未授权，强制退出不可用" to
            "Shizuku is not authorized, so force-stop is unavailable",
        "Shizuku 未安装或服务尚未运行" to
            "Shizuku is not installed or its service is not running",
        "Shizuku 连接失败" to "Shizuku connection failed",
        "增强兼容检测" to "Enhanced compatibility detection",
        "普通保护仍会使用独立限制页，但在问题解决前不能通过 Shizuku 强制退出。" to
            "Basic protection will continue with the standalone restriction page, but Shizuku force-stop is unavailable until this is fixed.",
        "必要权限缺失时，普通保护不会生效。请完成授权后重新打开目标应用。" to
            "Basic protection is inactive while required permissions are missing. Grant them, then reopen the target app.",
        "必要权限缺失时，非 Root 管控不会生效。LSPosed 已正常 Hook 的应用不受影响。" to
            "Non-root control is inactive while required permissions are missing. Apps already hooked by LSPosed are unaffected.",
        "查看 Shizuku 设置指南" to "View Shizuku setup guide",
        "普通保护运行中 · Shizuku 需处理" to
            "Basic protection is active · Shizuku needs attention",
        "基础管控仍有效；Shizuku 强制退出不可用，到限后将回退独立限制页" to
            "Basic control remains active. Shizuku force-stop is unavailable, so limits fall back to the standalone restriction page.",
        "权限缺失时，非 Root 管控无法可靠计时或拦截应用。LSPosed 已正常 Hook 的应用不受影响。" to
            "Without these permissions, non-root control cannot reliably time or block apps. Apps already hooked by LSPosed are unaffected.",
        "稍后处理" to "Later",
        "请选择一种保护方式；LSPosed 有效时会自动优先使用。" to
            "Choose a protection method. LSPosed is used automatically when available.",
        "LSPosed：启用时停模块，将目标应用加入作用域，保存规则后强停并重开目标应用。" to
            "LSPosed: enable Time Stop, add target apps to its scope, then force-stop and reopen them after saving rules.",
        "普通保护：在设置中开启普通保护，并授予无障碍服务和使用情况访问。无需 Root。" to
            "Basic protection: enable it in Settings and grant accessibility and usage access. Root is not required.",
        "Shizuku：可选增强，仅负责到限后强停第三方应用；不可用时自动回退普通限制页。" to
            "Shizuku: an optional enhancement that only force-stops third-party apps at the limit; it falls back to the basic restriction page when unavailable.",
        "普通保护不会读取页面文字或输入内容，也不会持续轮询；关闭权限、停止或卸载时停后无法继续保护。" to
            "Basic protection does not read page text or input and does not poll continuously. Protection stops if permissions are revoked or Time Stop is stopped or uninstalled.",
        "普通保护需要启用时停无障碍服务，并授予使用情况访问权限。" to
            "Basic protection requires the Time Stop accessibility service and usage access.",
        "Shizuku 是可选增强，仅负责到限后强停第三方应用；不可用时自动回退独立限制页。" to
            "Shizuku is optional and only force-stops third-party apps at the limit. If unavailable, Time Stop falls back to the standalone restriction page.",
        "启用时停模块，将目标应用加入作用域，保存规则后强停并重开目标应用。" to
            "Enable the Time Stop module, add target apps to its scope, then force-stop and reopen them after saving rules.",
        "只有检测到应用未加入作用域或运行异常时，时停才会显示修复提示。" to
            "Time Stop shows a repair prompt only when an app is out of scope or its runtime state is abnormal.",
        "规则保存后由目标应用执行；若实际未生效，请在诊断日志中检查运行状态。" to
            "Target apps enforce saved rules. If control does not take effect, check the runtime state in diagnostics.",
        "搜索应用或包名" to "Search app or package",
        "仅看已启用" to "Enabled only",
        "显示系统应用" to "Show system apps",
        "请确认 LSPosed 作用域" to "Confirm the LSPosed scope",
        "规则已保存，但这些应用尚未回传当前版本的 Hook 验证。请确认已加入时停的 LSPosed 作用域，再强制停止并重新打开目标应用。" to "The rule was saved, but these apps have not reported the current Hook version. Add them to the Time Stop LSPosed scope, then force-stop and reopen them.",
        "检测到这些应用尚未加入时停的 LSPosed 作用域。可以向框架申请加入，确认后请强制停止并重新打开目标应用。" to "These apps are not in the Time Stop LSPosed scope. Request scope access, then force-stop and reopen the target apps after approval.",
        "当前框架连接已变化，暂时无法申请作用域。请稍后重试或在 LSPosed 中手动检查。" to
            "The framework connection changed, so scope access cannot be requested right now. Try again later or check it manually in LSPosed.",
        "当前框架无法直接确认这些应用的作用域，或 Hook 版本仍待验证。请在 LSPosed 中检查作用域，再强制停止并重新打开目标应用。" to "This framework cannot expose the scope directly, or the Hook version is still pending. Check the scope in LSPosed, then force-stop and reopen the target apps.",
        "请求加入作用域" to "Request scope access",
        "正在申请" to "Requesting",
        "我已了解" to "Understood",
        "查看配置要求" to "View setup requirements",
        "未启用管控" to "Control is disabled",
        "Hook 已验证" to "Hook verified",
        "Hook 正在运行" to "Hook is running",
        "Hook 状态异常" to "Hook state is abnormal",
        "作用域已配置" to "Scope configured",
        "等待目标应用启动" to "Waiting for target apps to start",
        "兼容模式，等待 Hook 验证" to "Compatibility mode; waiting for Hook verification",
        "等待 Hook 验证" to "Waiting for Hook verification",
        "请将缺失的应用加入时停作用域" to "Add the missing apps to the Time Stop scope",
        "请强制停止目标应用并重新打开，以加载当前模块版本" to "Force-stop and reopen the target apps to load the current module version",
        "当前框架不支持直接读取作用域，将在目标应用启动后验证" to "This framework cannot expose scope directly; verification will occur after the target app starts",
        "请先选择需要管控的应用" to "Select apps to control first",
        "今日总使用" to "Total today",
        "管控应用数" to "Controlled apps",
        "快捷操作" to "Quick actions",
        "管理应用" to "Manage apps",
        "选择需要管控的应用并设置时间限制" to "Choose apps and configure time limits",
        "查看各应用今天的使用时长记录" to "View today's usage by app",
        "配置要求" to "Setup requirements",
        "诊断日志" to "Diagnostic logs",
        "需要使用情况访问权限" to "Usage access required",
        "授权后由 Android 系统提供今日使用时长，仅在打开时停时读取，不需要后台服务。" to "Android provides today's usage after authorization. Time Stop reads it on demand without a background service.",
        "授权后由 Android 系统提供今日使用时长；统计页按需读取，普通保护仅在前台切换或额度校验时读取，不需要前台常驻服务。" to
            "Android provides today's usage after authorization. Statistics read it on demand, while basic protection reads it only on foreground changes or allowance checks, without a persistent foreground service.",
        "去授权" to "Authorize",
        "使用统计展示已关闭；应用分组仍会保留共享额度所需的内部时长。" to "Usage statistics are hidden; app groups still retain timing required for shared allowances.",
        "Android 系统按需读取 · 无后台服务" to "Android data on demand · no background service",
        "Android 系统前台区间去重 · 无后台服务" to
            "Deduplicated Android foreground intervals · no background service",
        "授权后显示系统使用时长" to "Authorize to show system usage",
        "清空模块记录" to "Clear module records",
        "今天暂无应用使用记录。" to "No app usage recorded today.",
        "Android 系统使用统计" to "Android system usage",
        "需要 LSPosed" to "LSPosed required",
        "保存规则后，请在 LSPosed 中启用本模块并勾选目标应用。首次启用或修改作用域后，需要强制停止目标应用再打开。" to "After saving a rule, enable this module in LSPosed and select the target app. Force-stop and reopen it after first setup or a scope change.",
        "共享每日额度" to "Shared daily allowance",
        "分组额度与规则" to "Group allowances and rules",
        "启用分组管控" to "Enable group control",
        "关闭后保留成员和规则配置，但暂不执行" to "Keep members and rules configured while pausing enforcement",
        "分组规则" to "Group rules",
        "组内成员共同消耗一个每日额度" to "Group members share one daily allowance",
        "统一限制每个成员应用的单次前台使用时长" to "Apply the same per-launch foreground limit to every member",
        "任一成员达到分组额度后，整个分组共同进入冷却" to "When one member reaches a group quota, the whole group enters cooldown",
        "未启用规则" to "No rules enabled",
        "组内应用共同消耗一个每日额度；加入后个人规则暂停，移出分组后恢复。" to "Apps in a group share one daily allowance. Personal rules are suspended while grouped and resume after removal.",
        "可统一设置共享每日额度、单次打开、可用时段和共享冷却；加入后个人规则暂停，移出后恢复。" to "Configure shared daily, per-launch, schedule, and cooldown rules. Personal rules are suspended while grouped and resume after removal.",
        "任一成员触发后全组共用同一冷却" to "One member triggers the same cooldown for the whole group",
        "组内应用只执行本分组规则；个人规则与本次计划在分组期间暂停。" to "Group members run only this group policy. Personal rules and session planning are suspended while grouped.",
        "已选应用会自动置顶。已有个人设置或已属于其他组的应用不能新加入。" to "Selected apps are pinned to the top. Apps with personal settings or another group cannot be newly added.",
        "保存失败：应用可能已有个人设置或属于其他分组" to "Could not save: an app may have personal settings or belong to another group",
        "新建分组" to "New group",
        "新建应用分组" to "New app group",
        "新分组" to "New group",
        "暂无分组。可以把短视频、游戏等应用放入同一组共享额度。" to "No groups yet. Put short-video, game and similar apps into one shared allowance.",
        "共享中" to "Shared",
        "已停用" to "Disabled",
        "编辑应用分组" to "Edit app group",
        "分组名称" to "Group name",
        "每日共享额度（分钟）" to "Daily shared allowance (minutes)",
        "范围 1–1440 分钟" to "Range: 1–1440 minutes",
        "启用共享额度" to "Enable shared allowance",
        "耗尽后组内应用当天均不可继续使用" to "All group apps remain blocked for the day after it is exhausted",
        "每个应用只能属于一个组；所有成员仍需在 LSPosed 作用域中勾选。" to "Each app can belong to only one group. Every member must still be selected in the LSPosed scope.",
        "搜索应用名或包名" to "Search app name or package",
        "没有匹配的应用" to "No matching apps",
        "保存" to "Save",
        "保存设置" to "Save settings",
        "删除分组" to "Delete group",
        "取消" to "Cancel",
        "只会解除分组和共享额度，不会删除应用原有的独立规则。" to "This removes only the group and shared allowance; existing per-app rules remain.",
        "只会解除分组规则，不会删除应用原有的独立规则。" to "This removes only the group rules; existing per-app rules remain.",
        "确认删除" to "Delete",
        "共享额度" to "Shared allowance",
        "待 Hook 验证" to "Hook pending",
        "未加入作用域" to "Not in scope",
        "已在作用域" to "In scope",
        "Hook 运行中" to "Hook running",
        "Hook 待重载" to "Hook reload needed",
        "Hook 异常" to "Hook error",
        "运行中" to "Running",
        "需要重启" to "Restart required",
        "运行异常" to "Runtime error",
        "等待验证" to "Pending verification",
        "管控中" to "Controlled",
        "精细管控" to "Precise control",
        "把使用边界设清楚" to "Set clear usage boundaries",
        "每日累计、单次打开、可用时段和退出后冷却可以独立开启，也可以组合生效。" to "Daily, per-launch, schedule and post-exit cooldown rules can work independently or together.",
        "任一规则先到即执行退出" to "The first rule reached enforces the limit",
        "管理应用被清理后规则仍可继续执行" to
            "Rules can keep working after the manager app is cleared from Recents",
        "本次计划" to "Session plan",
        "打开应用前，先决定用多久" to "Decide how long to use the app",
        "为应用开启“打开时制定计划”，每次新进程首次进入时选择本次前台使用时长。" to "Enable plan-on-launch to choose a foreground-use duration when each new app process first opens.",
        "后台和息屏期间暂停计时" to "Timing pauses in the background and while the screen is off",
        "计划不能超过现有应用或分组剩余额度" to "A plan cannot exceed the remaining app or group allowance",
        "一组应用，共享一套规则" to "One app group, one shared policy",
        "将短视频、游戏等应用归入同一组，统一配置共享每日额度、单次打开、时段和冷却。" to "Group short-video, game and similar apps under shared daily, per-launch, schedule and cooldown rules.",
        "已选应用自动置顶，便于维护" to "Selected apps are pinned for easier management",
        "开始之前" to "Before you begin",
        "启用时停模块后，需要把每个受管控应用加入模块作用域，并强制停止后重新打开。" to "After enabling Time Stop, add every controlled app to the module scope, then force-stop and reopen it.",
        "统计页按需读取系统使用时长" to "System usage is read only when needed",
        "诊断日志可检查 HOOK_READY 与限制事件" to "Diagnostics show HOOK_READY and limit events",
        "欢迎使用时停" to "Welcome to Time Stop",
        "不再显示功能介绍" to "Do not show this introduction again",
        "开始使用" to "Get started",
        "下一页" to "Next",
        "上一页" to "Previous",
        "稍后再看" to "Maybe later",
        "运行前需要完成" to "Before you start",
        "本版本不需要相机、存储、通知等 Android 运行时权限。" to "This version does not need camera, storage, notification or other Android runtime permissions.",
        "1. 手机已 Root，并安装可用的 LSPosed。" to "1. Root the device and install a working LSPosed.",
        "2. 在 LSPosed 中启用“时停”模块。" to "2. Enable the Time Stop module in LSPosed.",
        "3. 在模块作用域中勾选需要限制的目标应用。" to "3. Select target apps in the module scope.",
        "4. 保存规则后，强制停止目标应用并重新打开。" to "4. After saving, force-stop and reopen the target app.",
        "若诊断日志没有 HOOK_READY，说明 Hook 没有进入目标进程，请先检查第 2、3 项。" to "If diagnostics do not contain HOOK_READY, the Hook did not enter the target process. Check steps 2 and 3.",
        "确认 LSPosed 作用域" to "Confirm the LSPosed scope",
        "我知道了" to "Got it",
        "达到限制后" to "When a limit is reached",
        "强制退出用于硬限制；独立休息页可暂停目标界面，冷静后继续" to "Force exit provides a hard limit. The standalone break page pauses the target screen and continues after cooldown",
        "强制退出（默认）" to "Force exit (default)",
        "独立休息页" to "Standalone break page",
        "达到限制后会打开时停的独立休息页，使目标界面自然暂停，并尝试暂停常见的 MediaPlayer、ExoPlayer/Media3 和网页音视频。部分系统可能询问是否允许打开时停；自研播放器、后台服务和游戏引擎可能继续运行。休息页不提供延时，主页与最近任务仍可使用；单次额度需配合冷却，结束后自动继续。切换执行方式后，请强停并重开管控应用。" to "When a limit is reached, Time Stop opens its standalone break page so the target screen pauses naturally. It also attempts to pause common MediaPlayer, ExoPlayer/Media3, and web audio/video playback. Some systems may ask before opening Time Stop; custom players, background services, and game engines may keep running. The page has no extension action; Home and Recents remain available. Pair per-launch limits with cooldown to continue automatically. Force-stop and reopen managed apps after switching enforcement mode.",
        "需先开启每日累计或单次打开，才能启用退出后冷却。" to "Enable a daily or per-launch quota before enabling post-exit cooldown.",
        "提醒与延时" to "Warnings and extensions",
        "退出前提醒" to "Pre-exit warning",
        "到期前 5 秒在屏幕顶部显示倒计时" to "Show a countdown five seconds before exit",
        "全屏退出提醒" to "Full-screen exit warning",
        "开启后倒计时覆盖当前应用；关闭时显示顶部圆角提醒" to "Cover the current app during the countdown; otherwise use the rounded top banner",
        "长震动提醒" to "Long vibration",
        "退出倒计时出现时震动一次" to "Vibrate once when the exit countdown appears",
        "语言" to "Language",
        "默认跟随系统语言" to "Follows the system language by default",
        "跟随系统" to "System default",
        "简体中文" to "Simplified Chinese",
        "外观" to "Appearance",
        "颜色主题" to "Color theme",
        "所有管理页、管控弹窗和限制页同步使用" to
            "Applied across manager screens, control prompts, and restriction pages",
        "健康绿" to "Health green",
        "宁静蓝" to "Calm blue",
        "专注紫" to "Focus purple",
        "明暗模式" to "Light and dark mode",
        "主题模式" to "Theme mode",
        "选择舒适的浅色或深色界面" to "Choose a comfortable light or dark appearance",
        "浅色" to "Light",
        "深色" to "Dark",
        "时间短句" to "Time reflections",
        "在计划、全屏提醒和独立限制页显示" to
            "Show in plan prompts, full-screen warnings, and restriction pages",
        "使用内置短句" to "Use built-in lines",
        "自定义短句（每行一句）" to "Custom lines (one per line)",
        "当前没有可显示的短句" to "There are no lines to display",
        "统计与诊断" to "Statistics and diagnostics",
        "使用时长统计" to "Usage statistics",
        "统计页按需读取系统数据；共享额度会保留必要的内部计时" to "The stats page reads system data on demand; shared allowances retain required internal timing",
        "使用情况访问权限已授予" to "Usage access granted",
        "尚未授予使用情况访问权限" to "Usage access not granted",
        "仅在查看统计或校验每日限额时按需读取，不会常驻后台" to "Read only when viewing stats or checking daily limits; no persistent background process",
        "记录 Hook、计时、延时和退出事件" to "Record Hook, timing, extension and exit events",
        "每次点击延时（分钟）" to "Extension per tap (minutes)",
        "可设置 1–60 分钟；每次点击都会追加" to "Set 1–60 minutes; each tap adds another extension",
        "应用设置" to "App settings",
        "隐藏桌面图标" to "Hide launcher icon",
        "隐藏后仍可从 LSPosed 模块页打开设置" to "Settings remain available from the LSPosed module page",
        "隐藏后，桌面缓存图标可能短暂残留且无法点击，刷新后会消失。可从 LSPosed 模块页，或“系统设置 → 应用 → 时停 → 应用内设置”进入；也可连接电脑执行：" to "A cached launcher icon may remain briefly and stop working until the launcher refreshes. Reopen from LSPosed, System settings → Apps → Time Stop → In-app settings, or connect a computer and run:",
        "关闭后可从 LSPosed 模块页打开应用" to "Open the app from the LSPosed module page after hiding it",
        "隐藏后请先尝试从 LSPosed 模块页打开设置。若没有入口，可连接电脑执行：" to "After hiding, first try opening settings from LSPosed. If unavailable, connect a computer and run:",
        "复制恢复命令" to "Copy recovery command",
        "维护与支持" to "Maintenance and support",
        "检查更新" to "Check for updates",
        "自动检查更新" to "Automatic update checks",
        "打开时停时按需检查，新版发布后主动提醒" to "Check when Time Stop opens and notify you when a new release is available",
        "正在连接 GitHub…" to "Connecting to GitHub…",
        "从 GitHub Releases 检查并下载新版 APK" to "Check GitHub Releases and download a newer APK",
        "检查 ›" to "Check ›",
        "反馈问题" to "Report a problem",
        "通过邮件发送设备信息和诊断日志" to "Send device information and diagnostics by email",
        "反馈 ›" to "Feedback ›",
        "请选择反馈方式。邮件反馈会附带设备信息和诊断日志，QQ群适合交流和参与内测。" to "Choose a feedback channel. Email includes device information and diagnostics; the QQ group is better for discussion and beta testing.",
        "邮件反馈" to "Email feedback",
        "QQ群反馈" to "QQ group feedback",
        "关于" to "About",
        "版本、项目主页和联系方式" to "Version, project page and contact information",
        "查看 ›" to "View ›",
        "支持开发" to "Support development",
        "加入内测" to "Join beta testing",
        "加入 QQ 群获取测试版本并反馈问题" to "Join the QQ group for test builds and feedback",
        "加群 ›" to "Join ›",
        "支持时停开发" to "Support Time Stop development",
        "支付宝" to "Alipay",
        "微信支付" to "WeChat Pay",
        "支付宝收款码" to "Alipay payment QR code",
        "微信收款码" to "WeChat Pay QR code",
        "打开支付宝付款" to "Open Alipay",
        "尝试打开微信付款" to "Try WeChat Pay",
        "去转账 ›" to "Transfer ›",
        "已是最新版本" to "You're up to date",
        "检查更新失败" to "Update check failed",
        "该版本没有填写更新说明。" to "No release notes were provided.",
        "在 GitHub 查看发布页" to "View release on GitHub",
        "请检查网络连接，或稍后重试。" to "Check your connection or try again later.",
        "下载 APK" to "Download APK",
        "确定" to "OK",
        "稍后" to "Later",
        "关于时停" to "About Time Stop",
        "Android / LSPosed 应用前台使用时长限制模块。" to "An Android / LSPosed module for limiting foreground app usage.",
        "单次打开、每日累计、每周可用时段和退出后冷却可以组合使用。" to "Per-launch, daily, weekly schedule and post-exit cooldown rules can be combined.",
        "打开 GitHub 项目主页" to "Open GitHub project",
        "发送问题反馈" to "Send feedback",
        "查看软件声明" to "View software notice",
        "软件声明" to "Software notice",
        "时停项目原创内容保留全部权利。公开可见不代表授予复制、修改、再发布或商业销售许可。" to "All rights to Time Stop's original content are reserved. Public visibility does not grant permission to copy, modify, republish or sell it.",
        "未经项目作者书面授权，不得抄袭、改名冒充、打包倒卖、收费分发或将本软件用于其他商业产品。" to "Without written authorization, do not plagiarize, impersonate, repackage for resale, distribute for a fee or embed this software in another commercial product.",
        "第三方开源组件仍分别遵循其原有许可证；法律规定的合理使用及其他法定权利不受本声明限制。" to "Third-party open-source components remain under their respective licenses. Statutory fair use and other legal rights are unaffected.",
        "完整条款见项目仓库根目录 LICENSE 文件。" to "See the LICENSE file in the repository root for the complete terms.",
        "关闭" to "Close",
        "无法打开链接" to "Unable to open link",
        "暂无日志。请打开一次已配置的目标应用；若仍为空，请检查当前保护方式的权限与配置。" to
            "No logs. Open a configured target app; if this remains empty, check the permissions and configuration for the current protection method.",
        "反馈" to "Feedback",
        "刷新" to "Refresh",
        "清空" to "Clear",
        "这是系统应用。达到限制时只关闭应用界面，不结束系统进程，以避免影响桌面或系统稳定性。" to "This is a system app. At the limit, only its UI is closed; its process is not terminated.",
        "固定管控规则" to "Fixed control rules",
        "计划" to "Plan",
        "打开时制定计划" to "Plan on launch",
        "可选功能：每次目标应用进程启动后，先选择本次计划使用时长" to "Optional: choose a session-plan duration when each target app process starts",
        "每次目标应用进程启动后，先选择本次计划使用时长" to "Choose a foreground-use plan when the target app process starts",
        "计划只计算前台时间；可跳过，也可在退出前重新制定。" to "Only foreground time counts; you may skip or replan before exit.",
        "计划到期时只能关闭应用界面，不结束系统进程。" to "When a plan expires, only the system app UI is closed; its process is not terminated.",
        "每日累计" to "Daily cumulative",
        "当天多次打开累计，第二天自动重置" to "Accumulates across launches and resets the next day",
        "单次打开" to "Per launch",
        "每次目标应用进程启动后重新计时" to "Restarts when the target app process starts",
        "两个限制可同时开启，任何一个先到期都会退出应用。" to "Both limits can be enabled; the first one reached exits the app.",
        "退出后冷却" to "Post-exit cooldown",
        "达到每日或单次额度后，在设定时间内限制再次使用" to "Restrict further use for the configured duration after a daily or per-launch limit",
        "冷却期间反复打开不会重新计算冷却时间，也不会重复增加限制触发次数。" to "Repeated attempts do not restart cooldown or add duplicate limit hits.",
        "保存并启用" to "Save and enable",
        "10 秒测试" to "10-second test",
        "可用时段" to "Usage schedule",
        "按星期和时间限制应用是否允许打开" to "Allow or block the app by weekday and time",
        "仅指定时段允许" to "Allow only during selected times",
        "指定时段禁止" to "Block during selected times",
        "只有下列时段可以使用，其他时间打开会立即退出。" to "The app can only be used during these windows; it exits immediately at other times.",
        "下列时段不能使用，其他时间正常开放。" to "The app is blocked during these windows and available at other times.",
        "添加时段" to "Add time window",
        "请至少添加一个时段。" to "Add at least one time window.",
        "重复日期" to "Repeat days",
        "删除" to "Delete",
        "开始和结束时间不能相同。" to "Start and end times must differ.",
        "请至少选择一天。" to "Select at least one day.",
        "限定允许时段" to "Allow-only schedule",
        "设有禁止时段" to "Blocked schedule",
        "一" to "Mon",
        "二" to "Tue",
        "三" to "Wed",
        "四" to "Thu",
        "五" to "Fri",
        "六" to "Sat",
        "日" to "Sun",
    )

    private val WEEKDAY_SELECTED = Regex("^✓([一二三四五六日])$")
}
