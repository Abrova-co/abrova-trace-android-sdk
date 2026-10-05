# Abrova Trace for Android

Error tracking, crash reporting, logging and performance monitoring for Android apps, reported to your Abrova Trace project.

## Requirements

- Android 4.4 (API 19) or later; native crash reports need Android 11 (API 30) or later
- Kotlin or Java 8+

## Install

Add the Abrova package repository, then the dependency:

```groovy
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url "https://center.abrova.ir/api/packages/abrova/maven" }
    }
}
```

Kotlin DSL: `maven { url = uri("https://center.abrova.ir/api/packages/abrova/maven") }`

```groovy
// app/build.gradle
dependencies {
    implementation "ir.abrova.trace:abrova-trace-android-sdk:0.2.3"
}
```

## Quick start

Initialise the SDK once in your `Application` class. Get the API key from the Abrova console.

```kotlin
import android.app.Application
import ir.abrova.trace.sdk.AbrovaTrace

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AbrovaTrace.init(this, "ab_live_your_api_key")
    }
}
```

Register the class in `AndroidManifest.xml` with `<application android:name=".MyApp" ...>`. Crashes, ANRs and uncaught exceptions are now reported.

## What is captured automatically

- Uncaught Java and Kotlin exceptions
- ANRs (main thread blocked for 5 seconds by default)
- Native crashes, reported on the next app launch (Android 11+)
- Breadcrumbs for activity navigation and app foreground/background
- Device model, OS version and app version with every report

## Usage

The models used below are in `ir.abrova.trace.sdk.models`, the interceptors in `ir.abrova.trace.sdk.network`.

Capture a handled exception or a message:

```kotlin
try {
    submitOrder()
} catch (e: Exception) {
    AbrovaTrace.captureException(e, extra = mapOf("order_id" to "1234"), tags = mapOf("feature" to "checkout"))
}

AbrovaTrace.captureMessage("Payment retried", MessageLevel.WARNING)
```

Set the user, tags and extra data. They are attached to every later report:

```kotlin
AbrovaTrace.setUserId("user-123")   // pass null on logout
AbrovaTrace.setTag("plan", "pro")
AbrovaTrace.setExtra("cart_items", 3)
```

Add a breadcrumb. The most recent breadcrumbs are sent with each error:

```kotlin
AbrovaTrace.addBreadcrumb("Opened checkout", BreadcrumbType.USER, mapOf("cart_items" to 3))
```

Send logs. Logs are batched and sent in the background:

```kotlin
AbrovaTrace.enableLogging(sourceId = "android-app", sourceName = "Android App") // optional
AbrovaTrace.info("Checkout started", mapOf("cart_items" to 3))
AbrovaTrace.warn("Slow response")
AbrovaTrace.logError("Payment declined")
```

Trace HTTP calls made with OkHttp, and time your own operations:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(AbrovaTraceInterceptor())             // HTTP breadcrumbs
    .addInterceptor(AbrovaTraceErrorInterceptor())        // reports 5xx responses and network failures
    .addInterceptor(AbrovaTrace.performanceInterceptor()) // request timing
    .build()

val feed = AbrovaTrace.trackOperation("load_feed") { repository.loadFeed() }
```

## Configuration

Pass an `AbrovaTraceConfig` instead of the plain key to change the defaults:

```kotlin
val config = AbrovaTraceConfig.Builder("ab_live_your_api_key")
    .environment("staging")
    .release(BuildConfig.VERSION_NAME)
    .debug(BuildConfig.DEBUG)
    .build()

AbrovaTrace.init(this, config)
```

| Option | Default | Description |
|--------|---------|-------------|
| `environment` | `"production"` | Environment name shown with each report |
| `release` | app version name | Release version of your app |
| `debug` | `false` | Print SDK debug output to Logcat |
| `enabled` | `true` | Set to `false` to turn the SDK off |
| `captureUncaughtExceptions` | `true` | Report uncaught exceptions |
| `captureSignalCrashes` | `true` | Report native crashes (Android 11+) |
| `captureAnr` | `true` | Report ANRs |
| `anrTimeoutMs` | `5000` | Main-thread block time, in milliseconds, that counts as an ANR |
| `maxBreadcrumbs` | `20` | Number of breadcrumbs kept |
| `sampleRate` | `1.0` | Fraction of `captureException` calls that are sent (0.0 to 1.0) |

## License

MIT. See [LICENSE](LICENSE).
