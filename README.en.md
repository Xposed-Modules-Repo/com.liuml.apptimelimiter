# Time Stop (Android / LSPosed)

An Android Xposed module prototype that limits the foreground usage time of selected apps. When an app reaches its configured limit, the module closes its task stack and terminates the process hosting the target UI.

Current version: `0.9.1`

## Features

- Browse and search launchable apps with their real application icons.
- Show third-party apps by default, with an explicit switch for displaying system apps.
- Configure independent daily cumulative and per-launch limits of 1–1,440 minutes for each app; either limit can trigger the exit.
- Configure weekly schedule windows in either **allow-only** or **block-during** mode, with multiple weekdays and overnight windows supported.
- Check the schedule immediately when an app opens, warn five seconds before a blocked boundary, and exit at the boundary. Schedule restrictions cannot be bypassed with the delay action.
- **Daily cumulative mode:** use the greater of Android's system usage and the hook-local timer when usage access is granted, then reset at local midnight.
- **Per-launch mode:** start a fresh timer whenever the target app's main process starts.
- Count time only while an activity is between `onResume` and `onPause`; background time is paused.
- Reset the hook-local accumulator when a rule changes while retaining Android's system usage as the daily baseline.
- Show a warning at the limit, call `finishAffinity()`, and terminate the target app process.
- Show a non-modal rounded countdown banner at the top of the screen for five seconds, adapting to portrait, landscape, display cutouts, and immersive full-screen apps; support adding one to sixty minutes through a delay action.
- Optionally block reopening an app for 1–1,440 minutes after a forced exit; repeated attempts do not restart the cooldown or inflate limit-hit counts.
- Include diagnostic logs for hook initialization, rule reads, timer events, and limit triggers.
- Provide settings for warnings, diagnostic logging, and the delay duration.
- Include an optional Alipay donation entry.
- Check GitHub Releases for updates and download a newer APK through the system download manager.
- Provide an About page and a feedback action that sends diagnostic logs through the user's mail app.
- Show a dashboard with the real hook status, enabled-app count, and today's usage time.
- Show system usage for every launchable app used today, estimate foreground launch sessions from Android events, and report limit triggers from the hook.
- Detect a stale target process that has not loaded the current hook version and prompt the user to force-stop and reopen it.
- Read foreground duration and launch counts from Android's `UsageStatsManager` on demand; managed apps additionally show limit hits reported by the Hook.
- Optionally hide the launcher icon and reopen settings from LSPosed or the `apptimelimiter://settings` deep link.

## Architecture

```mermaid
flowchart LR
    UI["Compose management UI"] --> Repo["Rule repository"]
    Repo --> Provider["Rule ContentProvider"]
    Repo --> Prefs["SharedPreferences compatibility store"]
    Provider -->|"Primary channel"| Hook["Target app process hook"]
    Prefs -->|"XSharedPreferences fallback"| Hook
    Hook --> Lifecycle["Activity lifecycle events"]
    Lifecycle --> Timer["Foreground timer"]
    Timer -->|"Limit reached"| Exit["Close task stack + terminate process"]
    Timer --> State["Target app local usage state"]
```

Key source files:

- `app/src/main/java/com/liuml/apptimelimiter/MainActivity.kt`: app list and rule editor UI.
- `app/src/main/java/com/liuml/apptimelimiter/data/RuleRepository.kt`: rule persistence and cross-process reads.
- `app/src/main/java/com/liuml/apptimelimiter/ipc/RuleProvider.kt`: controlled IPC for reading rules and sending diagnostic logs.
- `app/src/main/java/com/liuml/apptimelimiter/diagnostics/DiagnosticsRepository.kt`: rolling diagnostic log storage.
- `app/src/main/java/com/liuml/apptimelimiter/xposed/AppTimeLimitHook.kt`: lifecycle hooks, timers, daily state, and exit logic.
- `app/src/main/java/com/liuml/apptimelimiter/core/ScheduleEvaluator.kt`: weekly schedule matching, overnight handling, and next-boundary calculation.
- `xposed-stubs/`: compile-time Xposed API signatures; they are not packaged into the APK.

## Requirements

- Android device with Root access.
- A working LSPosed framework.
- JDK 17.
- Android SDK 35.

## Build

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Installation and Usage

1. Install the APK on a rooted Android device.
2. Open **Time Stop**, select target apps, and save their rules.
3. Enable the module in LSPosed and select only the apps that should be limited in the module scope.
4. Force-stop and reopen each target app. Restart the target process after changing its LSPosed scope.
5. Search for `AppTimeLimiter` in the LSPosed log when diagnosing a setup.

The app does not request camera, storage, notification, or other dangerous runtime permissions. System usage statistics require the user to grant Android's special usage-access permission. They are queried on demand for the UI and for daily-limit evaluation; no persistent background service is used. `RECEIVE_BOOT_COMPLETED` is used only to restore URI access after reboot; it does not launch target apps in the background.

## Diagnostics

Open **Diagnostic Logs** from the home screen and check:

- `RULE_SAVED`: the management app saved the rule.
- `HOOK_READY`: the lifecycle hook is running in the target process.
- `RULE_READ ... source=provider`: the primary rule channel is working.
- `RULE_READ ... source=xsharedpreferences`: the compatibility fallback is being used.
- `TIMER_START`: foreground timing has started.
- `LIMIT_REACHED`: the configured limit has been reached and the exit operation was triggered.

If `HOOK_READY` never appears in the in-app log, search the LSPosed log for `AppTimeLimiter: HOOK_INSTALLED` or `HOOK_FAILED`.

The legacy entry point and lifecycle hook use the [Xposed Framework API](https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html). New LSPosed projects may migrate to the [Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API).

## Known Limitations

- The Android 15 compatibility path hooks `Instrumentation.callActivityOnResume/Pause` across all processes in the target package.
- System apps have their activities closed at the limit, but their processes are never terminated. This protects launchers and other system components from destabilization.
- Activities that remain resumed in picture-in-picture or split-screen mode continue to count toward the limit.
- The hook-local daily fallback is stored in the target app's data area. If that data is cleared while usage access remains granted, Android's system usage is still restored as the daily-limit baseline.
- A short interval before an unexpected crash may not be persisted if `onPause` is never delivered.
- Only apps with a launcher entry are listed; packages without a desktop entry require future manual package-name configuration.
- Hook behavior cannot be fully verified across different ROMs without a real Root/LSPosed test device.

This tool should be used only by the device owner or on explicitly authorized managed devices. Do not install it covertly or use it for unauthorized monitoring.
