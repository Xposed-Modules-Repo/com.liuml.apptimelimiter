# F-Droid packaging notes

This directory contains a draft `fdroiddata` metadata file for Time Stop.

Official F-Droid does not accept uploaded APKs. The normal process is:

1. Ensure the upstream repository has a FOSS license. Time Stop now uses `GPL-3.0-only`.
2. Copy `packaging/fdroid/com.liuml.apptimelimiter.yml` to `fdroiddata/metadata/com.liuml.apptimelimiter.yml`.
3. Test the metadata with the F-Droid server tools if available.
4. Open a merge request against `fdroid/fdroiddata`.

Important review notes:

- The app includes an optional Accessibility protection mode. It declares that the service does not retrieve window content and is used only for package-level foreground changes.
- The app includes optional Shizuku support as an execution enhancement. It is not a timing engine and falls back to the normal restriction page when unavailable.
- The app checks GitHub Releases for updates. F-Droid maintainers may request a F-Droid-specific build flavor or patch that disables the in-app updater for F-Droid builds.
- Current tag format is `<versionCode>-<versionName>`, for example `37-0.10.14`.

