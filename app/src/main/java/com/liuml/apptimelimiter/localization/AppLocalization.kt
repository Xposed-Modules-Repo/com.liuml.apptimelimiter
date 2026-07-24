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
        Regex("^该时段将在次日 (.+) 结束。$").matchEntire(source)?.let {
            return "This window ends at ${it.groupValues[1]} the next day."
        }
        Regex("^支付宝捐赠：(.+)$").matchEntire(source)?.let {
            return "Alipay donation: ${it.groupValues[1]}"
        }
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
        "搜索应用或包名" to "Search app or package",
        "仅看已启用" to "Enabled only",
        "显示系统应用" to "Show system apps",
        "请确认 LSPosed 作用域" to "Confirm the LSPosed scope",
        "规则已保存，但这些应用尚未回传当前版本的 Hook 验证。请确认已加入时停的 LSPosed 作用域，再强制停止并重新打开目标应用。" to "The rule was saved, but these apps have not reported the current Hook version. Add them to the Time Stop LSPosed scope, then force-stop and reopen them.",
        "检测到这些应用尚未加入时停的 LSPosed 作用域。可以向框架申请加入，确认后请强制停止并重新打开目标应用。" to "These apps are not in the Time Stop LSPosed scope. Request scope access, then force-stop and reopen the target apps after approval.",
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
        "去授权" to "Authorize",
        "使用统计展示已关闭；应用分组仍会保留共享额度所需的内部时长。" to "Usage statistics are hidden; app groups still retain timing required for shared allowances.",
        "Android 系统按需读取 · 无后台服务" to "Android data on demand · no background service",
        "授权后显示系统使用时长" to "Authorize to show system usage",
        "清空模块记录" to "Clear module records",
        "今天暂无应用使用记录。" to "No app usage recorded today.",
        "Android 系统使用统计" to "Android system usage",
        "Hook 计数未同步，请强停该应用后重新打开" to "Hook counters are not synchronized; force-stop and reopen this app",
        "需要 LSPosed" to "LSPosed required",
        "保存规则后，请在 LSPosed 中启用本模块并勾选目标应用。首次启用或修改作用域后，需要强制停止目标应用再打开。" to "After saving a rule, enable this module in LSPosed and select the target app. Force-stop and reopen it after first setup or a scope change.",
        "共享每日额度" to "Shared daily allowance",
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
        "确认删除" to "Delete",
        "共享额度" to "Shared allowance",
        "待 Hook 验证" to "Hook pending",
        "未加入作用域" to "Not in scope",
        "已在作用域" to "In scope",
        "Hook 运行中" to "Hook running",
        "Hook 待重载" to "Hook reload needed",
        "Hook 异常" to "Hook error",
        "管控中" to "Controlled",
        "运行前需要完成" to "Before you start",
        "本版本不需要相机、存储、通知等 Android 运行时权限。" to "This version does not need camera, storage, notification or other Android runtime permissions.",
        "1. 手机已 Root，并安装可用的 LSPosed。" to "1. Root the device and install a working LSPosed.",
        "2. 在 LSPosed 中启用“时停”模块。" to "2. Enable the Time Stop module in LSPosed.",
        "3. 在模块作用域中勾选需要限制的目标应用。" to "3. Select target apps in the module scope.",
        "4. 保存规则后，强制停止目标应用并重新打开。" to "4. After saving, force-stop and reopen the target app.",
        "若诊断日志没有 HOOK_READY，说明 Hook 没有进入目标进程，请先检查第 2、3 项。" to "If diagnostics do not contain HOOK_READY, the Hook did not enter the target process. Check steps 2 and 3.",
        "我知道了" to "Got it",
        "达到限制后" to "When a limit is reached",
        "强制退出用于硬限制；独立休息页可暂停目标界面，冷静后继续" to "Force exit provides a hard limit. The standalone break page pauses the target screen and continues after cooldown",
        "强制退出（默认）" to "Force exit (default)",
        "独立休息页" to "Standalone break page",
        "达到限制后会打开时停的独立休息页，使目标界面自然暂停，并尝试暂停常见的 MediaPlayer、ExoPlayer/Media3 和网页音视频。部分系统可能询问是否允许打开时停；自研播放器、后台服务和游戏引擎可能继续运行。休息页不提供延时，主页与最近任务仍可使用；单次额度需配合冷却，结束后自动继续。切换执行方式后，请强停并重开管控应用。" to "When a limit is reached, Time Stop opens its standalone break page so the target screen pauses naturally. It also attempts to pause common MediaPlayer, ExoPlayer/Media3, and web audio/video playback. Some systems may ask before opening Time Stop; custom players, background services, and game engines may keep running. The page has no extension action; Home and Recents remain available. Pair per-launch limits with cooldown to continue automatically. Force-stop and reopen managed apps after switching enforcement mode.",
        "需先开启每日累计或单次打开，才能启用退出后冷却。" to "Enable a daily or per-launch quota before enabling post-exit cooldown.",
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
        "使用时长统计" to "Usage statistics",
        "统计页按需读取系统数据；共享额度会保留必要的内部计时" to "The stats page reads system data on demand; shared allowances retain required internal timing",
        "使用情况访问权限已授予" to "Usage access granted",
        "尚未授予使用情况访问权限" to "Usage access not granted",
        "仅在查看统计或校验每日限额时按需读取，不会常驻后台" to "Read only when viewing stats or checking daily limits; no persistent background process",
        "记录 Hook、计时、延时和退出事件" to "Record Hook, timing, extension and exit events",
        "每次点击延时（分钟）" to "Extension per tap (minutes)",
        "可设置 1–60 分钟；每次点击都会追加" to "Set 1–60 minutes; each tap adds another extension",
        "隐藏桌面图标" to "Hide launcher icon",
        "隐藏后仍可从 LSPosed 模块页打开设置" to "Settings remain available from the LSPosed module page",
        "隐藏后，桌面缓存图标可能短暂残留且无法点击，刷新后会消失。可从 LSPosed 模块页，或“系统设置 → 应用 → 时停 → 应用内设置”进入；也可连接电脑执行：" to "A cached launcher icon may remain briefly and stop working until the launcher refreshes. Reopen from LSPosed, System settings → Apps → Time Stop → In-app settings, or connect a computer and run:",
        "关闭后可从 LSPosed 模块页打开应用" to "Open the app from the LSPosed module page after hiding it",
        "隐藏后请先尝试从 LSPosed 模块页打开设置。若没有入口，可连接电脑执行：" to "After hiding, first try opening settings from LSPosed. If unavailable, connect a computer and run:",
        "复制恢复命令" to "Copy recovery command",
        "检查更新" to "Check for updates",
        "正在连接 GitHub…" to "Connecting to GitHub…",
        "从 GitHub Releases 检查并下载新版 APK" to "Check GitHub Releases and download a newer APK",
        "检查 ›" to "Check ›",
        "反馈问题" to "Report a problem",
        "通过邮件发送设备信息和诊断日志" to "Send device information and diagnostics by email",
        "反馈 ›" to "Feedback ›",
        "关于" to "About",
        "版本、项目主页和联系方式" to "Version, project page and contact information",
        "查看 ›" to "View ›",
        "支持开发" to "Support development",
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
        "关闭" to "Close",
        "无法打开链接" to "Unable to open link",
        "暂无日志。打开一次已配置的目标应用；若仍为空，请检查 LSPosed 是否启用模块及目标应用作用域。" to "No logs. Open a configured target app; if this remains empty, check the LSPosed module and scope.",
        "反馈" to "Feedback",
        "刷新" to "Refresh",
        "清空" to "Clear",
        "这是系统应用。达到限制时只关闭应用界面，不结束系统进程，以避免影响桌面或系统稳定性。" to "This is a system app. At the limit, only its UI is closed; its process is not terminated.",
        "计划" to "Plan",
        "打开时制定计划" to "Plan on launch",
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
