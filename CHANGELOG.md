# Changelog

## 0.2.0 - 2026-10-05

First public release.

- Automatic reporting of uncaught Java and Kotlin exceptions and ANRs.
- Native crash reports on Android 11 and later, sent on the next app launch.
- `captureException` and `captureMessage` for handled errors and messages, with user ID, tags and extra data.
- Breadcrumbs: automatic for activity navigation and app foreground/background, plus your own.
- Batched logging with six levels, from trace to fatal.
- OkHttp interceptors for HTTP breadcrumbs, HTTP error reports and request timing.
- Performance spans for your own operations with `trackOperation`.
