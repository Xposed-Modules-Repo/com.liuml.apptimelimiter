# 时停（Android / LSPosed）

[English documentation](README.en.md)

这是一个可运行的 Android Xposed 模块原型：为指定应用设置前台可使用时长和可用时段，达到限制后先关闭任务栈，再结束目标应用界面所在进程。当前版本为 `0.8.0`。

## 已实现

- 展示应用真实图标，并可搜索设备上的可启动应用。
- 为每个应用分别设置 1–1440 分钟的每日累计和单次打开限制；两个阈值可同时启用，任意一个先到期都会退出应用。
- 可为每个应用选择“仅指定时段允许”或“指定时段禁止”，支持多个星期组合和跨午夜时段。
- 打开应用时立即检查时段；应用保持前台时会在不可用边界前 5 秒提醒，到达边界后退出。时段限制不能通过延时按钮绕过。
- **每日累计**：当天多次打开累计前台时长，第二天自动重置；超限后当天再次打开会立即退出。
- **单次打开**：目标应用每次主进程启动后重新计时。
- 仅在 `Activity.onResume` 到 `Activity.onPause` 之间计时，切到后台时暂停。
- 修改规则会重置该应用原规则下的累计用量。
- 达到阈值后显示提示，调用 `finishAffinity()`，随后结束目标应用进程。
- 首次启动主动说明 Root、LSPosed、模块作用域等运行要求。
- 内置诊断日志，可查看 Hook、规则读取、计时开始/暂停和退出触发事件。
- 顶部“设置”入口可开关退出提醒、开关诊断日志，并配置每次点击延时 1–60 分钟。
- 到期前 5 秒在目标应用内显示倒计时；点击延时后追加本次额度，并在新截止点前再次提醒。
- 设置页提供支付宝捐赠入口，尝试跳转到 `liuml.yx@139.com` 的账户转账页；不支持直接跳转时复制账号并打开支付宝。
- 设置页可通过 GitHub Releases 检查新版本，并调用系统下载管理器下载新版 APK。
- “关于”页面展示版本、项目主页和联系方式；“反馈问题”会调用用户选择的邮件应用，并附加诊断日志发送至 `liuml.yx@139.com`。
- 设置页可隐藏桌面启动图标。隐藏后可尝试从 LSPosed 模块页打开，或通过 `adb shell am start -a android.intent.action.VIEW -d apptimelimiter://settings` 进入设置并恢复图标。
- 首页仪表盘显示真实 Hook 验证状态、已启用管控应用数和今日总使用时长；底部导航分为首页、应用和统计。
- 每日使用时长在打开“时停”时通过 Android `UsageStatsManager` 按需读取，不轮询、不启动常驻服务；Hook 仅记录启动次数和限制触发次数。

## 架构

```mermaid
flowchart LR
    UI["Compose 管理端"] --> Repo["规则仓库"]
    Repo --> Provider["规则 ContentProvider"]
    Repo --> Prefs["SharedPreferences 兼容数据"]
    Provider -->|"主通道"| Hook["目标应用主进程 Hook"]
    Prefs -->|"XSharedPreferences 兜底"| Hook
    Hook --> Lifecycle["Activity 前后台事件"]
    Lifecycle --> Timer["前台计时器"]
    Timer -->|"达到限制"| Exit["关闭任务栈 + 结束进程"]
    Timer --> State["目标应用本地累计状态"]
    UI -->|"打开页面时按需查询"| UsageStats["Android UsageStatsManager"]
```

主要代码：

- `app/src/main/java/com/liuml/apptimelimiter/MainActivity.kt`：应用列表和规则编辑界面。
- `app/src/main/java/com/liuml/apptimelimiter/data/RuleRepository.kt`：规则存储与跨进程可读处理。
- `app/src/main/java/com/liuml/apptimelimiter/ipc/RuleProvider.kt`：目标进程读取规则和回传日志的受控 IPC 通道。
- `app/src/main/java/com/liuml/apptimelimiter/diagnostics/DiagnosticsRepository.kt`：滚动诊断日志。
- `app/src/main/java/com/liuml/apptimelimiter/xposed/AppTimeLimitHook.kt`：生命周期 Hook、计时、每日状态和退出逻辑。
- `app/src/main/java/com/liuml/apptimelimiter/core/ScheduleEvaluator.kt`：每周时段匹配、跨午夜处理和下一访问边界计算。
- `xposed-stubs/`：只用于编译的传统 Xposed API 签名，不会打包进 APK。

## 构建

环境要求：JDK 17、Android SDK 35。

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

当前工作区路径含中文；如果 Windows 上单元测试报 `ClassNotFoundException`，可临时映射英文盘符：

```powershell
subst T: "<你的项目目录>"
T:
.\gradlew.bat testDebugUnitTest assembleDebug
subst T: /d
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装和使用

1. 设备需已 Root，并安装可用的 LSPosed 框架。
2. 安装 APK，打开“时停”，选择目标应用并保存规则。
3. 进入 LSPosed，启用本模块，在作用域中只勾选需要限制的应用。
4. 强制停止目标应用后重新打开。修改 LSPosed 作用域后同样需要重启目标应用进程。
5. 调试时可在 LSPosed 日志中搜索 `AppTimeLimiter`。

应用不需要相机、存储、通知等危险运行时权限。使用统计需要用户在系统特殊权限页面授予“使用情况访问权限”，仅在打开首页或统计页时读取。联网权限只用于访问 GitHub Releases 检查和下载更新；`RECEIVE_BOOT_COMPLETED` 是普通权限，仅用于手机重启后恢复目标应用访问规则 Provider 的 URI 授权，不会在后台启动目标应用。

## 诊断日志判断方法

在首页点击“诊断日志”，重点查看以下事件：

- 只有 `RULE_SAVED`：管理端保存成功，但 Hook 没有进入目标应用；检查 LSPosed 模块开关、目标应用作用域，并强制停止目标应用后重开。
- 出现 `HOOK_READY`：生命周期 Hook 已经运行。
- `RULE_READ ... source=provider`：新版规则通道工作正常。
- `RULE_READ ... source=xsharedpreferences`：Provider 不可用，正在走旧兼容通道；这通常是系统包可见性或 ROM 限制。
- `TIMER_START`：前台计时已开始，日志会显示剩余秒数。
- `LIMIT_REACHED`：限制已达到，代码已发出关闭任务栈和结束进程操作。

如果应用内日志完全没有 `HOOK_READY`，还可以在 LSPosed 日志中搜索 `AppTimeLimiter: HOOK_INSTALLED` 或 `HOOK_FAILED`。

传统入口与生命周期 Hook 使用 [Xposed Framework API](https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html)。LSPosed 的新项目可进一步迁移到 [Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)，以 Remote Preferences 替代当前兼容层。

## 已知限制

- Android 15 兼容路径使用 `Instrumentation.callActivityOnResume/Pause`，并覆盖目标包的所有进程；日志会显示实际承载界面的进程名。
- 画中画、分屏状态下，只要 Activity 保持 Resume 就会继续计时。
- 每日累计状态保存在目标应用数据区；清除目标应用数据会重置累计时间。
- 目标应用或系统崩溃时，最后一个尚未触发 `onPause` 的短时间片可能没有持久化。
- 应用列表只查询带 Launcher 入口的软件；没有桌面入口的包暂不显示，需要在后续版本增加手动包名配置。
- 未连接真实 Root/LSPosed 设备时，只能完成编译、单元测试和 APK 结构校验，不能验证不同 ROM 的 Hook 行为。

本工具应仅用于设备所有者本人或已明确授权的受管设备，不应隐蔽安装或用于未经同意的监控。
