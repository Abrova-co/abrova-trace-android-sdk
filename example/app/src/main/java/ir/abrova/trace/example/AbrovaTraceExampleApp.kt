package ir.abrova.trace.example

import android.app.Application
import ir.abrova.trace.sdk.AbrovaTrace
import ir.abrova.trace.sdk.AbrovaTraceConfig

class AbrovaTraceExampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize AbrovaTrace SDK
        val config = AbrovaTraceConfig.Builder("ab_live_your_api_key")
            .environment(if (BuildConfig.DEBUG) "development" else "production")
            .release(BuildConfig.VERSION_NAME)
            .debug(BuildConfig.DEBUG)
            .captureUncaughtExceptions(true)
            .captureAnr(true)
            .anrTimeoutMs(5000)
            .maxBreadcrumbs(30)
            // Sample rate: 1.0 = capture 100% of errors
            // Set to 0.5 for 50%, 0.25 for 25%, etc.
            .sampleRate(1.0f)
            // Offline storage: store errors when offline, retry on reconnect
            .enableOfflineStorage(true)
            .build()

        AbrovaTrace.init(this, config)

        // Optionally set user ID if known at startup
        // AbrovaTrace.setUserId("user-123")

        // Enable logging
        AbrovaTrace.enableLogging(
            sourceId = "android-demo-app",
            sourceName = "Android Demo App"
        )
    }

    override fun onTerminate() {
        // Close SDK gracefully
        AbrovaTrace.close()
        super.onTerminate()
    }
}
