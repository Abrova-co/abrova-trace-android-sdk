# Changelog

## 0.2.3 - 2026-10-06

- One hang is one ANR report. Before, a report was sent every `anrTimeoutMs` while the main thread stayed blocked.
- No false ANR reports after a crash while the system's crash dialog is open.
- A report that is sent again is counted once: every error carries an `event_id` that stays the same on each attempt.

## 0.2.2 - 2026-10-05

- Crashes on the main thread are now reported.
- A crash report that cannot be delivered is kept on the device and sent on the next launch.
- A Java or Kotlin crash that was already reported is no longer reported again as a native crash (Android 11+).
- `enableOfflineStorage` works: errors that cannot be sent while offline are kept (up to 100) and sent later.

## 0.2.0 - 2026-10-05

First public release.

- Automatic reporting of uncaught Java and Kotlin exceptions and ANRs.
- Native crash reports on Android 11 and later, sent on the next app launch.
- `captureException` and `captureMessage` for handled errors and messages, with user ID, tags and extra data.
- Breadcrumbs: automatic for activity navigation and app foreground/background, plus your own.
- Batched logging with six levels, from trace to fatal.
- OkHttp interceptors for HTTP breadcrumbs, HTTP error reports and request timing.
- Performance spans for your own operations with `trackOperation`.
