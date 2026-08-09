# Time Stop (Android)

Precision app-time control for Android power users who want policy, telemetry, and enforcement in the same loop.

Current version: `0.10.14`

## Why Not Just Use Stock Screen Time?

Different Android vendors ship different screen-time features, but the usual model is still broad and UI-driven. Time Stop prefers LSPosed when a current Hook is active and otherwise provides non-root control through accessibility foreground events plus Android usage access.

| Dimension | Stock screen time | Time Stop |
| --- | --- | --- |
| Core model | Usage reports, daily limits, focus modes | Composable enforcement rules for selected apps |
| Time granularity | Mostly daily totals | Daily quota + per-launch timer + post-exit cooldown |
| Schedule logic | Commonly fixed focus windows | Allow-only or block-during weekly windows, multi-day and overnight aware |
| Shared budgets | Rare or vendor-specific | App groups with one shared daily allowance |
| Enforcement path | System blocker or reminder | Countdown plus force exit by default, or an optional standalone Time Stop break page |
| Observability | Usually hidden | Hook heartbeat, rule source, limit hits, and diagnostic logs |
| Runtime | System feature | LSPosed when available, or non-root accessibility + usage access |

Time Stop is not a soft "please stop scrolling" timer. It is a small policy engine for app usage: rules, foreground accounting, warning UI, exit execution, statistics, update checks, and field diagnostics all live in one workflow.

## Feature Highlights

| Capability | What it does |
| --- | --- |
| Independent app rules | Each app keeps its own enabled state, daily quota, per-launch quota, schedule windows, warning style, and cooldown behavior. |
| App groups and shared rules | Enable shared daily, per-launch, weekly schedule, and cooldown rules for a group. Members run only the group policy; saved personal rules are suspended and resume after removal. |
| Non-root basic protection | Uses content-blind accessibility foreground events and Android usage access. An opt-in enhanced compatibility mode adds package-only content-change events for ROMs that miss normal window events; it still retrieves no nodes, text, or input and adds no foreground service or continuous polling. |
| Global protection mode | Select exactly one controller for all targets: LSPosed, Basic protection, or Basic protection + Shizuku. Controllers never take over automatically. |
| Optional Shizuku enhancement | Only executes a validated force-stop for configured third-party targets; unavailable or failed states fall back to the normal restriction page without switching to LSPosed. |
| Protection status in Settings | Settings shows the selected mode, actual execution path, confirmed issues, and repair actions. Unknown LSPosed evidence is shown as waiting for verification rather than inactive; unavailable Shizuku is shown as a basic-protection fallback. |
| Daily cumulative mode | Uses the stronger source for each app: Android system usage when available, or Hook-local foreground accounting, then resets at local midnight. |
| Per-launch mode | Starts a fresh timer when the target app's main process begins a foreground session. |
| Session planning | Optionally asks for a 5, 10, 15, 30, or custom 1–1440 minute plan when the target process first opens. Quick choices and a direct numeric minute field share one compact page, while the fixed footer keeps exit and skip actions visible. Values beyond the earliest remaining timed quota are rejected. |
| Weekly schedules | Supports allow-only and block-during windows across multiple weekdays, including overnight ranges. Schedule blocks cannot be bypassed with the delay action. |
| Foreground-only accounting | Counts only the `onResume` to `onPause` phase. Background residency does not burn the quota. |
| Warning UI | LSPosed Hook targets can show a five-second top or full-screen warning matching the selected global color, optionally vibrate once, and offer exit or a 1-60 minute extension. Pure non-root mode hides these Hook-only settings; its session plan offers exit or replan five seconds before expiry. |
| Enforcement mode | Settings expose only actions supported by each engine. LSPosed offers force-exit or a themed standalone break page with an exit-to-Home action. Non-root basic protection offers the same styled restriction page or Shizuku force-stop, with automatic page fallback if Shizuku is unavailable or fails. |
| Language | Supports system-default, Simplified Chinese, and English UI; Hook warnings use the same preference. |
| Appearance | Offers health green, calm blue, and focus purple across all in-app and target-side surfaces, each with follow-system, light, and dark modes. Plan prompts, full-screen warnings, and restriction pages can also show built-in or custom time-reflection lines. |
| Delay action | Lets the user add 1-60 minutes for normal time limits while keeping schedule blocks strict. |
| Post-exit cooldown | Blocks reopening for 1-1,440 minutes after a daily or per-launch quota event. A group uses one fixed shared cooldown window for all members; repeated openings do not refresh it or inflate limit-hit counts. Schedule denials do not start cooldown. |
| Group sync loop | Grouped foreground apps synchronize usage every 15 seconds without keeping the manager app alive. |
| Non-blocking system usage | Daily Android `UsageEvents` are refreshed in the module process and reused as a short-lived snapshot, avoiding a full-day scan on the target app's main thread. |
| Hook and scope status | Reads framework and scope state through the optional libxposed service when supported, can request missing scope with framework confirmation, and keeps the current-version Hook heartbeat as the compatibility fallback. |
| Diagnostics | Logs Hook setup, rule reads, timer starts, sync events, stats writes, and limit exits so configuration problems are traceable. |
| Usage totals | Calculates per-app time from foreground events, clears stale foreground state at screen-off, and deduplicates overlapping intervals for the daily total so it cannot exceed the elapsed part of the day. |
| System-app guardrails | Third-party apps can have their target process terminated; system apps only have their UI closed. |
| Updates and feedback | Checks GitHub Releases automatically when the manager opens (rate-limited and configurable), actively prompts for a new stable release, uses Android's download manager for APK updates, and offers email diagnostics or QQ group `1009712674` for feedback and beta participation. Beta and optional-donation notices each appear once after every version update. |

Changing a rule resets the Hook-local accumulator for that app, but Android's system usage for the current day remains part of the daily baseline when usage access is granted. That makes rule tweaking visible, not a loophole.

## Architecture

```mermaid
flowchart LR
    UI["Compose manager"] --> Repo["Rule repository"]
    Repo --> Provider["Rule ContentProvider"]
    Repo --> Primary["Private authoritative rule store"]
    Primary --> Prefs["LSPosed compatibility mirror"]
    Provider -->|"primary channel"| Hook["Target app process Hook"]
    Provider --> Groups["Group usage aggregation"]
    Groups --> Hook
    Prefs -->|"XSharedPreferences fallback"| Hook
    Hook --> Lifecycle["Activity resume/pause events"]
    Lifecycle --> Timer["Foreground timer"]
    Hook --> SessionPlan["Process-local session plan"]
    SessionPlan -->|"expires"| Exit
    Timer -->|"limit reached"| Exit["Close task stack + exit target process"]
    Timer --> State["Target-local usage state"]
    UI -->|"on-demand query"| UsageStats["Android UsageStatsManager"]
    UsageStats -->|"daily baseline"| Provider
    UI -->|"optional scope query"| XposedService["libxposed service"]
    Accessibility["Accessibility foreground events"] --> Coordinator["Non-root coordinator"]
    UsageStats --> Coordinator
    Coordinator -.->|"disabled when LSPosed mode is selected"| Hook
    Coordinator -->|"basic fallback"| BreakPage["Time Stop restriction page"]
    Coordinator -->|"optional executor"| Shizuku["Shizuku UserService"]
```

Key source files:

- `app/src/main/java/com/liuml/apptimelimiter/MainActivity.kt`: Compose UI, app management, group management, statistics, and settings.
- `app/src/main/java/com/liuml/apptimelimiter/data/RuleRepository.kt`: rule persistence, global settings, groups, and compatibility exports.
- `app/src/main/java/com/liuml/apptimelimiter/ipc/RuleProvider.kt`: controlled IPC for rule reads, diagnostics, statistics, and Hook verification.
- `app/src/main/java/com/liuml/apptimelimiter/statistics/`: Android usage-event calculation and module statistics.
- `app/src/main/java/com/liuml/apptimelimiter/xposed/AppTimeLimitHook.kt`: lifecycle hooks, timers, group sync, cooldowns, warnings, and exit execution.
- `app/src/main/java/com/liuml/apptimelimiter/nonroot/`: non-root sessions under the selected global mode, accessibility overlays, restriction fallback, and restricted Shizuku execution.
- `app/src/main/java/com/liuml/apptimelimiter/core/`: pure policy helpers covered by unit tests.
- `xposed-stubs/`: compile-time Xposed API signatures; they are not packaged into the APK.

## Build

Requirements: JDK 17 and Android SDK 37 (`targetSdk` remains 35).

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

If Windows path encoding causes Kotlin or JUnit classpath errors, build from a temporary ASCII drive:

```powershell
subst T: "<repo absolute path>"
T:
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
subst T: /d
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Installation

1. Install the APK, open **Time Stop**, select target apps, and save rules or groups.
2. For non-root protection, enable it in Settings, accept the accessibility disclosure, and grant accessibility plus usage access.
3. Select one global protection mode. Basic protection requires Accessibility and Usage Access; the Shizuku mode additionally requires Shizuku authorization. Protection status and repair actions are shown at the top of Settings. After switching modes, the next real target-app window is owned by the new mode; only confirmed outdated or failed Hooks require a targeted reopen.
4. On rooted devices, enable the LSPosed module and scope controlled apps. Force-stop and reopen targets after changing scope; a current Hook heartbeat automatically takes priority.

Time Stop checks required non-root permissions only when non-root mode has managed targets. Choosing Later suppresses the same issue combination for 72 hours, while “Do not show this type again” suppresses that signature until reminders are restored in Settings. Missing Shizuku capability never disables basic timing; enforcement falls back to the standalone restriction page.

When upgrading from a build whose only rule copy lives in LSPosed's redirected preferences, open Time Stop once while the module is still enabled. This performs a one-time migration to the stable private manager store. After migration, disabling LSPosed no longer switches the manager UI to a different rule database.

On frameworks that expose the modern service, Time Stop can read scope before the target app is opened and request missing packages through the framework confirmation UI. Older frameworks fall back to the persisted `HOOK_READY` heartbeat, so "Hook verified" means the target app has successfully loaded this module version before.

## Distribution

### GitHub Releases

The official APK is published from the LSPosed mirror repository:

<https://github.com/Xposed-Modules-Repo/com.liuml.apptimelimiter/releases/latest>

### Obtainium

Time Stop works with Obtainium by tracking GitHub Releases:

- App source URL: `https://github.com/Xposed-Modules-Repo/com.liuml.apptimelimiter`
- Source type: GitHub Releases
- APK asset pattern: `app-time-limiter-v*.apk`

Obtainium is a third-party updater. It does not change Time Stop's protection engine; it only checks the release page and installs the APK selected by the user.

### F-Droid

Time Stop is licensed as GPL-3.0-only so it can be submitted to F-Droid-compatible repositories. Packaging metadata is kept in `packaging/fdroid/`. Until the official F-Droid review is accepted, use GitHub Releases, LSPosed, Obtainium, or a self-hosted F-Droid repository.

## Diagnostics

Open **Diagnostic Logs** from the home screen and check:

- `RULE_SAVED`: the manager saved the rule.
- `HOOK_READY`: the lifecycle Hook is running in the target process.
- `RULE_READ ... source=provider`: the primary rule channel is working.
- `RULE_READ ... source=xsharedpreferences`: the compatibility fallback is being used.
- `TIMER_START`: foreground timing has started.
- `SESSION_PLAN_PROMPT/STARTED/REPLANNED/SKIPPED/EXPIRED` and `SESSION_PLAN_PROMPT_INTERRUPTED`: lifecycle of the process-local session plan.
- `EXTERNAL_BREAK_PAGE_SHOWN/UPDATED/REMOVED/FAILED`: standalone break-page display, rule changes, recovery, and safe-exit fallback.
- `MEDIA_PAUSE_ATTEMPT/MEDIA_PAUSE_FAILED`: best-effort pause result for common platform, ExoPlayer/Media3, and web media.
- `REST_CYCLE_RESUMED`: a per-launch break ended and a fresh foreground cycle started.
- `GROUP_COOLDOWN_STARTED/REUSED/EXPIRED` and `QUOTA_INCIDENT_DUPLICATE`: shared group cooldown ownership, reuse, expiry, and duplicate suppression.
- `SESSION_PLAN_WAITING_USAGE`, `SESSION_PLAN_REJECTED_OVER_QUOTA`, and `SESSION_PLAN_UNAVAILABLE`: authoritative usage wait, over-quota rejection, or plan unavailability.
- `SESSION_PLAN_SUPPRESSED_BLOCKED`: a schedule, cooldown, or exhausted quota correctly prevented the plan dialog.
- `COOLDOWN_STARTED/COOLDOWN_PERSIST_FAILED`: cooldown persistence after a quota limit.
- `LIMIT_REACHED`: a configured boundary was reached and exit execution started.
- `PROTECTION_MODE_CHANGED`, `HOOK_MODE_ACKNOWLEDGED`, and `PROTECTION_STATUS_DEGRADED`: selected-mode changes, Hook mode-generation acknowledgement, and protection-health degradation.
- `NON_ROOT_RESTRICTION_REENTRY` and `NON_ROOT_RESTRICTION_STATE_PERSIST_FAILED`: fast restriction revalidation after reopening a target and failures to persist its re-entry marker.
- `NON_ROOT_BREAK_PAGE_START_REQUESTED`, `NON_ROOT_BREAK_PAGE_START_ACCEPTED`, `NON_ROOT_BREAK_PAGE_CONFIRMED`, and `NON_ROOT_BREAK_PAGE_CONFIRM_TIMEOUT`: distinguish a launch request, Android accepting it, the page actually gaining foreground focus, and silent vendor-ROM blocking.
- `NON_ROOT_OVERLAY_ADD_REQUESTED`, `NON_ROOT_OVERLAY_ADD_ACCEPTED`, `NON_ROOT_OVERLAY_ATTACH_STATE`, and `NON_ROOT_OVERLAY_ADD_FAILED`: capture session-plan overlay window parameters, attachment state, and the full exception type.
- `NON_ROOT_FOREGROUND_SIGNAL`, `NON_ROOT_FOREGROUND_RECONCILED`, and `NON_ROOT_FOREGROUND_MISMATCH`: record the accepted accessibility source and bounded UsageStats reconciliation before protection UI is shown.
- `NON_ROOT_OVERLAY_DETACHED`, `NON_ROOT_OVERLAY_REATTACHED`, and `NON_ROOT_UI_STATE_CHANGED`: record bounded session-plan overlay recovery and the single-owner non-root UI state.
- `SHIZUKU_FORCE_STOP` and `NON_ROOT_HOME_FALLBACK_RESULT`: report enhanced force-stop execution and the final foreground state after the Home fallback.

If `HOOK_READY` never appears in the in-app log, search LSPosed logs for `AppTimeLimiter: HOOK_INSTALLED` or `HOOK_FAILED`.

`HOOK_INSTALLED` and `HOOK_READY` include the target process bitness and ABI. If neither appears for only a few apps, first check whether those apps are excluded by the Magisk denylist/Zygisk configuration. The Hook uses both the normal Instrumentation lifecycle path and an Activity lifecycle fallback for protected or legacy apps.

The lifecycle Hook remains on the [legacy Xposed Framework API](https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html). The manager optionally uses libxposed service API 102 for framework, scope, and running-target status without adding a second modern Hook entry; unsupported frameworks automatically retain heartbeat verification.

## Known Limitations

- Non-root protection is self-management, not tamper-resistant parental control. Revoking permissions, force-stopping Time Stop, or uninstalling it stops this path.
- A non-root per-launch session survives up to 30 seconds away from the target. Returning within that grace resumes its timer and plan; returning later starts a new session.
- Basic enforcement opens the standalone restriction page directly so the target Activity pauses and may resume when the restriction clears. It returns Home only if the page cannot open. The target process stays alive, so background media may continue. Shizuku force-stop discards the current page and commonly must be restarted after a reboot.
- While a persistent non-root restriction remains active, reopening the target from Home immediately revalidates the current rule and restores the restriction page without waiting for the Hook handshake. This marker only accelerates revalidation and cannot override a changed or cleared rule.
- Non-root mode hides scope, Hook verification, reopen actions, and historical counter prompts. Accessibility is reported as disabled, enabled-but-disconnected, or connected, and global permission failures are not repeated for every app. LSPosed treats an in-scope idle app as ready without waiting for a heartbeat.
- Launcher and launcher-folder windows are hard foreground boundaries: they pause timing with the existing 30-second grace period, cancel stale enforcement callbacks, and never issue navigation, overlays, Toasts, or another Home action.
- Some vendor ROMs omit Activity pause events. The statistics calculator clears stale foreground state at screen-off and waits for a new foreground event after wake, preventing multiple apps from replaying the same screen-on intervals; small errors remain possible when vendor events are missing.
- The non-root restriction page uses a separate task, so it cannot cover the Time Stop manager when the user opens Time Stop from the launcher.
- The standalone restriction Activity is the enforcement surface because it naturally pauses the target Activity. Accessibility overlays are reserved for the session-plan picker and short warnings: an overlay can block touches but cannot reliably pause media, games, or the target lifecycle.
- Enhanced compatibility detection is off by default. It only uses the package name attached to content-change events, keeps `canRetrieveWindowContent=false`, and confirms ambiguous signals with a one-shot recent UsageStats query.
- The accessibility service retrieves no window content, nodes, text, accounts, input, or notifications. Foreground package changes and local timing or diagnostics are not uploaded.

- Session planning is process-local: it is offered once per target process, survives Activity changes in that process, and is cleared when the process ends.
- A session plan counts only resumed foreground Activity time. Background and screen-off time is paused by design; this feature does not provide background media playback or a wall-clock sleep timer.
- Session-plan expiry does not add a limit hit or start cooldown. System apps only have their UI closed.
- A session plan must fit within the earliest remaining app or group time quota. Longer choices are rejected with the available balance, and exhausted quotas, cooldowns, or blocked schedules can never be bypassed.
- The standalone break page pauses the target Activity and attempts to pause common MediaPlayer, ExoPlayer/Media3, and web media. Vendor ROMs may ask before opening Time Stop. Custom players, background services, rendering, or game logic may continue; use force-exit mode when execution must stop completely.
- Rules, statistics, diagnostics, and runtime state are excluded from Android backup and device transfer.
- Per-launch and group per-launch limits start a fresh cycle after configured cooldown. Daily and group-daily quotas remain hard limits until the daily reset; blocked schedules remain active until the allowed period.
- While the standalone page is visible, the target Activity is paused, so Hook-local foreground accumulation also pauses. Playback is never force-resumed by the module.

- Lifecycle tracking uses `Instrumentation.callActivityOnResume/Pause` with a deduplicated `Activity.onResume/onPause` fallback across target processes; diagnostics show the process and bitness that host the UI.
- Already running target apps keep the old Hook after install or upgrade. Force-stop and reopen them to load the new module code. The manager only prompts when the current runtime state is definitively abnormal.
- Activities that remain resumed in picture-in-picture or split-screen mode continue to count toward the limit.
- Multiple resumed apps in the same group synchronize increments every 15 seconds, so concurrent multi-window use can exceed the shared allowance by up to one sync interval.
- Every group member must still be in LSPosed scope. Compatible frameworks can approve missing scope from Time Stop; older frameworks require manual selection. An unhooked member can count toward shared usage through Android usage stats, but cannot execute its own forced exit.

- The Hook-local daily fallback is stored in the target app's data area. If that data is cleared while usage access remains granted, Android's system usage can still restore the daily baseline.
- A short segment before an unexpected crash may not be persisted if `onPause` is never delivered.
- Only launchable apps are listed. Packages without launcher entries need future manual package-name configuration.
- The app list only shows apps in the Android user that hosts the manager. Work-profile or cloned-user instances are not separately controlled yet because rules and statistics are currently keyed by package name.
- Hook behavior cannot be fully verified across ROMs without a real Root/LSPosed device.

This tool should be used only by the device owner or on explicitly authorized managed devices. Do not install it covertly or use it for unauthorized monitoring.

## License and copyright

Time Stop is free software licensed under GPL-3.0-only. You may use, study, modify, and
redistribute it under the terms of the GNU General Public License version 3 only. See
[LICENSE](LICENSE) for the complete terms. Third-party components remain under their respective
licenses.
