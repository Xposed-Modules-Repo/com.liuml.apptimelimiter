# 时停

> 把时间留给真正重要的事。

![Android 8.1+](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white)
![LSPosed API 93+](https://img.shields.io/badge/LSPosed-API%2093%2B-5C6BC0)
![Version 0.8.0](https://img.shields.io/badge/version-0.8.0-8E44AD)

系统自带的屏幕时间管理通常更侧重**查看使用统计、设置每日限额或开启专注模式**。**时停**则专注于更精细、可组合的单应用规则：不仅能限制一天总共使用多久，还能限制每次连续打开多久，并规定每周哪些时段允许或禁止使用。条件到达后，时停会通过 LSPosed 在目标应用进程中执行提醒与退出，让“少用一会儿”变成明确、可执行的边界。

[下载最新版本](https://github.com/Xposed-Modules-Repo/com.liuml.apptimelimiter/releases/latest) · [LSPosed 模块页面](https://modules.lsposed.org/module/com.liuml.apptimelimiter/) · [English documentation](README.en.md)

当前版本：`0.8.0`

## 它和系统屏幕时间有什么不同？

> 不同厂商和 ROM 的系统功能会有所差异，下表以常见的 Android 屏幕时间管理能力作为对比。

| 对比维度 | 系统自带屏幕时间 | 时停 |
| --- | --- | --- |
| 核心定位 | 查看使用情况、每日限额、专注模式 | 为指定应用建立可组合的强制使用边界 |
| 时间粒度 | 通常以每天的总时长为主 | 每日累计 + 单次打开时长可独立或同时启用 |
| 时段规则 | 常见为专注模式或固定停用时段 | 支持“仅指定时段允许”与“指定时段禁止”、多星期组合和跨午夜规则 |
| 到达限制后 | 通常显示系统拦截页或提醒 | 先倒计时提醒，再关闭任务栈并结束目标应用界面所在进程 |
| 生效验证 | 通常不展示具体执行链路 | 提供真实 Hook 心跳、规则来源和限制触发诊断日志 |
| 运行方式 | 系统原生，无需 Root | 依赖 Root 与 LSPosed，在目标应用进程内执行规则 |

系统自带功能更适合快速了解整体使用习惯；时停更适合希望对某些应用设置**更细粒度、更明确、更难随手忽略**的使用规则。

时停也不只是一个“到点弹窗”的计时器。它覆盖从**规则配置、前台计时、到期提醒和限制执行**，到**使用统计、Hook 验证、故障诊断、在线更新和问题反馈**的完整使用流程。

## 功能丰富，覆盖完整使用流程

| 能力 | 说明 |
| --- | --- |
| 独立应用规则 | 每个应用分别保存启用状态、时间额度和时段计划，互不影响。 |
| 每日累计限制 | 为每个应用设置 1–1440 分钟的每日额度，多次打开会累计，次日自动重置。 |
| 单次打开限制 | 每次目标应用主进程启动后重新计时，适合控制一次连续使用的时长。 |
| 每周时段规则 | 支持“仅指定时段允许”和“指定时段禁止”，可组合多个星期并覆盖跨午夜时段。 |
| 精确前台计时 | 仅统计 Activity 处于前台的时间，应用切到后台后暂停计时。 |
| 到期提醒与延时 | 到期前 5 秒显示倒计时，可按设置临时延长 1–60 分钟；时段禁用规则不能被延时绕过。 |
| 使用统计 | 首页和统计页按需读取 Android `UsageStatsManager`，展示今日时长、启动次数和限制触发次数。 |
| Hook 状态验证 | 仪表盘根据真实 Hook 心跳判断模块是否已经在目标应用中生效。 |
| 多级规则回退 | 优先通过受控 Provider 读取规则，并提供 `XSharedPreferences` 与本地缓存回退，增强不同 ROM 下的可用性。 |
| 诊断日志 | 记录 Hook 安装、规则来源、计时开始与暂停、统计写入和限制触发，方便快速定位配置问题。 |
| 个性化设置 | 可控制退出提醒、诊断记录和默认延时时长，并在修改规则后自动重置旧累计状态。 |
| 隐藏桌面入口 | 可隐藏启动图标，并通过 LSPosed 模块页或 `apptimelimiter://settings` 恢复进入设置。 |
| 更新与反馈 | 可检查 GitHub Releases、调用系统下载管理器更新，并通过邮件附带诊断日志反馈问题。 |

计时仅覆盖 `Activity.onResume` 到 `Activity.onPause` 的前台阶段，切到后台会暂停。修改规则时会重置该应用在旧规则下的累计用量。

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
