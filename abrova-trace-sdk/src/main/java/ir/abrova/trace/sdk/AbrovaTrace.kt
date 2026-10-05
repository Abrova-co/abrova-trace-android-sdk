package ir.abrova.trace.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ir.abrova.trace.sdk.models.*
import ir.abrova.trace.sdk.network.AbrovaTraceClient
import ir.abrova.trace.sdk.services.*
import ir.abrova.trace.sdk.utils.DeviceInfo
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AbrovaTrace Android SDK
 *
 * Error tracking SDK for Android applications.
 * Supports API 19+ (Android 4.4 KitKat and above).
 *
 * Usage:
 * ```kotlin
 * // In Application.onCreate()
 * AbrovaTrace.init(this, AbrovaTraceConfig.Builder("your-api-key")
 *     .environment("production")
 *     .debug(BuildConfig.DEBUG)
 *     .build())
 *
 * // Capture exceptions
 * AbrovaTrace.captureException(exception)
 *
 * // Capture messages
 * AbrovaTrace.captureMessage("User completed onboarding", MessageLevel.INFO)
 *
 * // Add breadcrumbs
 * AbrovaTrace.addBreadcrumb("Button clicked", BreadcrumbType.USER)
 * ```
 */
object AbrovaTrace {

    private var config: AbrovaTraceConfig? = null
    private var client: AbrovaTraceClient? = null
    private var context: Context? = null
    private var nativeCrashReporter: NativeCrashReporter? = null
    private var handledCrashMarkers: HandledCrashMarkers? = null
    private var anrWatchdog: ANRWatchdogService? = null

    private val isInitialized = AtomicBoolean(false)
    private var originalExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var sessionId: String = UUID.randomUUID().toString()

    @Volatile
    private var userId: String? = null

    @Volatile
    private var userAgent: String? = null

    @Volatile
    private var currentActivityName: String? = null

    private val extraContext = mutableMapOf<String, Any?>()
    private val tags = mutableMapOf<String, String>()

    private var logService: LogService? = null

    // ==================== INITIALIZATION ====================

    /**
     * Initialize AbrovaTrace SDK
     *
     * @param context Application context
     * @param config SDK configuration
     */
    @JvmStatic
    fun init(context: Context, config: AbrovaTraceConfig) {
        if (isInitialized.getAndSet(true)) {
            AbrovaTraceLogger.warn("AbrovaTrace already initialized")
            return
        }

        this.context = context.applicationContext
        this.config = config
        this.client = AbrovaTraceClient(config)
        this.userAgent = DeviceInfo.getUserAgent(context)

        // Set debug mode
        AbrovaTraceLogger.isDebugEnabled = config.debug

        AbrovaTraceLogger.info("Initializing AbrovaTrace SDK v${BuildConfig.SDK_VERSION}")
        AbrovaTraceLogger.debug("Config: API Key=${config.apiKey.take(10)}..., env=${config.environment}")

        if (!config.enabled) {
            AbrovaTraceLogger.info("AbrovaTrace SDK is disabled")
            return
        }

        // Configure breadcrumbs
        BreadcrumbService.setMaxBreadcrumbs(config.maxBreadcrumbs)

        // Offline storage: keep errors that cannot be sent while the network
        // is down, and resend what an earlier session left behind.
        setupOfflineStorage()

        // Where the uncaught exception handler notes the crashes it reported,
        // so the exit records read below are not reported a second time.
        if (config.captureSignalCrashes && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appContext = context.applicationContext
            handledCrashMarkers = HandledCrashMarkers({ storageDirectory(appContext) })
        }

        // Setup uncaught exception handler (real JVM/Kotlin crashes)
        if (config.captureUncaughtExceptions) {
            setupUncaughtExceptionHandler()
        }

        // Native crash reporting via ApplicationExitInfo (API 30+).
        // Drains any REASON_CRASH / REASON_CRASH_NATIVE / REASON_ANR exit
        // records that occurred since the previous launch.
        if (config.captureSignalCrashes) {
            setupNativeCrashReporting()
        }

        // Setup ANR detection
        if (config.captureAnr) {
            setupAnrDetection()
        }

        // Setup lifecycle callbacks
        setupLifecycleCallbacks()

        // Add system breadcrumb
        BreadcrumbService.addSystem("AbrovaTrace SDK initialized", mapOf(
            "sdk_version" to BuildConfig.SDK_VERSION,
            "environment" to config.environment
        ))

        AbrovaTraceLogger.info("AbrovaTrace SDK initialized successfully")
    }

    /**
     * Initialize with simple API key string
     */
    @JvmStatic
    fun init(context: Context, apiKey: String) {
        init(context, AbrovaTraceConfig.simple(apiKey))
    }

    /**
     * Check if SDK is initialized
     */
    @JvmStatic
    fun isInitialized(): Boolean = isInitialized.get()

    // ==================== ERROR CAPTURE ====================

    /**
     * Capture an exception
     *
     * @param throwable The exception to capture
     * @param message Optional custom message
     * @param extra Additional context data
     * @param tags Tags for categorization
     * @param callback Callback with success status
     */
    @JvmStatic
    @JvmOverloads
    fun captureException(
        throwable: Throwable,
        message: String? = null,
        extra: Map<String, Any?> = emptyMap(),
        tags: Map<String, String> = emptyMap(),
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!ensureInitialized()) {
            callback?.invoke(false, "SDK not initialized")
            return
        }

        val cfg = config ?: return

        // Apply sample rate
        if (cfg.sampleRate < 1.0f && Math.random() > cfg.sampleRate) {
            AbrovaTraceLogger.debug("Error dropped due to sample rate")
            callback?.invoke(false, "Dropped due to sample rate")
            return
        }

        // Add error breadcrumb
        BreadcrumbService.addError("Exception: ${throwable.javaClass.simpleName}", mapOf(
            "message" to throwable.message
        ))

        val error = AbrovaTraceError.fromThrowable(
            throwable = throwable,
            message = message,
            environment = cfg.environment,
            releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(context!!),
            userAgent = userAgent,
            breadcrumbs = BreadcrumbService.getBreadcrumbs(),
            extra = extraContext + extra + mapOf(
                "user_id" to userId,
                "session_id" to sessionId,
                "device_info" to DeviceInfo.getDeviceInfo()
            ),
            tags = this.tags + tags,
            url = currentActivityName?.let { "android://$it" }
        )

        client?.sendError(error, callback)
    }

    /**
     * Capture a message
     *
     * @param message The message to capture
     * @param level Message severity level
     * @param extra Additional context data
     * @param tags Tags for categorization
     * @param callback Callback with success status
     */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(
        message: String,
        level: MessageLevel = MessageLevel.INFO,
        extra: Map<String, Any?> = emptyMap(),
        tags: Map<String, String> = emptyMap(),
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!ensureInitialized()) {
            callback?.invoke(false, "SDK not initialized")
            return
        }

        val cfg = config ?: return

        val msg = AbrovaTraceError.message(
            message = message,
            level = level,
            environment = cfg.environment,
            releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(context!!),
            userAgent = userAgent,
            breadcrumbs = BreadcrumbService.getBreadcrumbs(),
            extra = extraContext + extra + mapOf(
                "user_id" to userId,
                "session_id" to sessionId,
                "device_info" to DeviceInfo.getDeviceInfo()
            ),
            tags = this.tags + tags,
            url = currentActivityName?.let { "android://$it" }
        )

        client?.sendMessage(msg, callback)
    }

    // ==================== BREADCRUMBS ====================

    /**
     * Add a breadcrumb
     */
    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, type: BreadcrumbType = BreadcrumbType.INFO, data: Map<String, Any?> = emptyMap()) {
        BreadcrumbService.add(message, type, data)
    }

    /**
     * Add a navigation breadcrumb
     */
    @JvmStatic
    fun addNavigationBreadcrumb(from: String?, to: String) {
        BreadcrumbService.addNavigation(from, to)
    }

    /**
     * Add a user action breadcrumb
     */
    @JvmStatic
    @JvmOverloads
    fun addUserBreadcrumb(action: String, data: Map<String, Any?> = emptyMap()) {
        BreadcrumbService.addUser(action, data)
    }

    /**
     * Add an HTTP request breadcrumb
     */
    @JvmStatic
    @JvmOverloads
    fun addHttpBreadcrumb(method: String, url: String, statusCode: Int? = null, duration: Long? = null) {
        BreadcrumbService.addHttp(method, url, statusCode, duration)
    }

    /**
     * Clear all breadcrumbs
     */
    @JvmStatic
    fun clearBreadcrumbs() {
        BreadcrumbService.clear()
    }

    // ==================== CONTEXT & TAGS ====================

    /**
     * Set the user ID
     */
    @JvmStatic
    fun setUserId(id: String?) {
        userId = id
        if (id != null) {
            BreadcrumbService.addSystem("User ID set", mapOf("user_id" to id))
        }
    }

    /**
     * Get current user ID
     */
    @JvmStatic
    fun getUserId(): String? = userId

    /**
     * Set extra context data
     */
    @JvmStatic
    fun setExtra(key: String, value: Any?) {
        extraContext[key] = value
    }

    /**
     * Set multiple extra context values
     */
    @JvmStatic
    fun setExtras(extras: Map<String, Any?>) {
        extraContext.putAll(extras)
    }

    /**
     * Clear extra context
     */
    @JvmStatic
    fun clearExtras() {
        extraContext.clear()
    }

    /**
     * Set a tag
     */
    @JvmStatic
    fun setTag(key: String, value: String) {
        tags[key] = value
    }

    /**
     * Set multiple tags
     */
    @JvmStatic
    fun setTags(newTags: Map<String, String>) {
        tags.putAll(newTags)
    }

    /**
     * Clear all tags
     */
    @JvmStatic
    fun clearTags() {
        tags.clear()
    }

    // ==================== PERFORMANCE / APM ====================

    /**
     * Report a performance span (for APM tracking)
     *
     * This method sends HTTP request timing data to AbrovaTrace APM.
     * Requires a paid plan (Starter or higher).
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param url Request URL
     * @param statusCode HTTP status code (null if request failed)
     * @param durationMs Duration in milliseconds
     * @param startTime Start time in milliseconds since epoch
     * @param errorMessage Error message if request failed
     * @param tags Additional tags for filtering
     */
    @JvmStatic
    @JvmOverloads
    fun reportPerformanceSpan(
        method: String,
        url: String,
        statusCode: Int?,
        durationMs: Long,
        startTime: Long,
        errorMessage: String? = null,
        tags: Map<String, String> = emptyMap()
    ) {
        if (!ensureInitialized()) return

        val cfg = config ?: return

        val span = PerformanceSpan.fromHttpRequest(
            method = method,
            url = url,
            statusCode = statusCode,
            durationMs = durationMs,
            startTime = startTime,
            environment = cfg.environment,
            releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(context!!),
            tags = this.tags + tags
        ).let {
            if (errorMessage != null) {
                it.copy(status = "error", errorMessage = errorMessage)
            } else {
                it
            }
        }

        client?.sendPerformanceSpan(span)
    }

    /**
     * Report a PerformanceSpan object directly (for DB queries, custom operations, etc.)
     *
     * @param span The PerformanceSpan to report
     * @param callback Optional callback with success status
     */
    @JvmStatic
    fun reportPerformanceSpan(
        span: PerformanceSpan,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!ensureInitialized()) return

        val cfg = config ?: return

        val enrichedSpan = span.copy(
            environment = span.environment ?: cfg.environment,
            releaseVersion = span.releaseVersion ?: cfg.release ?: DeviceInfo.getAppVersion(context!!),
            tags = this.tags + span.tags
        )

        client?.sendPerformanceSpan(enrichedSpan, callback)
    }

    /**
     * Report multiple performance spans in a batch
     *
     * @param spans List of PerformanceSpan objects
     * @param callback Optional callback with success status
     */
    @JvmStatic
    fun reportPerformanceSpanBatch(
        spans: List<PerformanceSpan>,
        callback: ((Boolean, String?) -> Unit)? = null
    ) {
        if (!ensureInitialized()) return

        val cfg = config ?: return

        val enrichedSpans = spans.map { span ->
            span.copy(
                environment = span.environment ?: cfg.environment,
                releaseVersion = span.releaseVersion ?: cfg.release ?: DeviceInfo.getAppVersion(context!!),
                tags = this.tags + span.tags
            )
        }

        client?.sendPerformanceSpanBatch(enrichedSpans, callback)
    }

    /**
     * Track a synchronous operation and automatically report it as a performance span
     *
     * @param operation Name of the operation
     * @param operationType Type of operation (default: "custom")
     * @param tags Additional tags
     * @param block The operation to execute and time
     * @return The result of the operation
     */
    @JvmStatic
    @JvmOverloads
    fun <T> trackOperation(
        operation: String,
        operationType: String = "custom",
        tags: Map<String, String> = emptyMap(),
        block: () -> T
    ): T {
        val startTime = System.currentTimeMillis()
        var status = "ok"
        var errorMessage: String? = null

        return try {
            val result = block()
            result
        } catch (e: Exception) {
            status = "error"
            errorMessage = e.message
            throw e
        } finally {
            val durationMs = System.currentTimeMillis() - startTime
            val span = PerformanceSpan.custom(
                operation = operation,
                durationMs = durationMs,
                startTime = startTime,
                operationType = operationType,
                status = status,
                errorMessage = errorMessage,
                tags = tags
            )
            reportPerformanceSpan(span)
        }
    }

    /**
     * Create a performance interceptor for OkHttp
     *
     * Usage:
     * ```kotlin
     * val client = OkHttpClient.Builder()
     *     .addInterceptor(AbrovaTrace.performanceInterceptor())
     *     .build()
     * ```
     */
    @JvmStatic
    @JvmOverloads
    fun performanceInterceptor(minDurationMs: Long = 0): okhttp3.Interceptor {
        return ir.abrova.trace.sdk.network.AbrovaTracePerformanceInterceptor(minDurationMs)
    }

    // ==================== LOGGING ====================

    /**
     * Enable logging with optional configuration
     *
     * @param sourceId Identifier for this log source (e.g., "my-android-app")
     * @param sourceName Human-readable name for this source
     * @param batchSize Number of logs to batch before sending (default: 50)
     * @param flushIntervalMs How often to flush logs in milliseconds (default: 5000)
     */
    @JvmStatic
    @JvmOverloads
    fun enableLogging(
        sourceId: String? = null,
        sourceName: String? = null,
        batchSize: Int = 50,
        flushIntervalMs: Long = 5000
    ) {
        if (!ensureInitialized()) return

        val cfg = config ?: return

        logService = LogService(
            apiKey = cfg.apiKey,
            sourceId = sourceId,
            sourceName = sourceName,
            platform = "android",
            environment = cfg.environment,
            release = cfg.release ?: DeviceInfo.getAppVersion(context!!),
            batchSize = batchSize,
            flushIntervalMs = flushIntervalMs,
            apiUrl = cfg.apiUrl
        )

        AbrovaTraceLogger.debug("Logging enabled with sourceId: $sourceId")
    }

    /**
     * Log a message with the specified level
     *
     * @param message The log message
     * @param level Log level (TRACE, DEBUG, INFO, WARN, ERROR, FATAL)
     * @param metadata Additional metadata to attach to the log
     */
    @JvmStatic
    @JvmOverloads
    fun log(
        message: String,
        level: LogLevel = LogLevel.INFO,
        metadata: Map<String, Any?>? = null
    ) {
        if (!ensureInitialized()) return

        // Auto-enable logging if not already enabled
        if (logService == null) {
            enableLogging()
        }

        logService?.log(message, level, metadata, userId)
    }

    /**
     * Log a trace-level message
     */
    @JvmStatic
    @JvmOverloads
    fun trace(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.TRACE, metadata)
    }

    /**
     * Log a debug-level message
     */
    @JvmStatic
    @JvmOverloads
    fun logDebug(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.DEBUG, metadata)
    }

    /**
     * Log an info-level message
     */
    @JvmStatic
    @JvmOverloads
    fun info(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.INFO, metadata)
    }

    /**
     * Log a warning-level message
     */
    @JvmStatic
    @JvmOverloads
    fun warn(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.WARN, metadata)
    }

    /**
     * Log an error-level message (for non-exception errors)
     */
    @JvmStatic
    @JvmOverloads
    fun logError(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.ERROR, metadata)
    }

    /**
     * Log a fatal-level message
     */
    @JvmStatic
    @JvmOverloads
    fun fatal(message: String, metadata: Map<String, Any?>? = null) {
        log(message, LogLevel.FATAL, metadata)
    }

    /**
     * Flush all pending logs immediately
     */
    @JvmStatic
    fun flushLogs(callback: ((Boolean) -> Unit)? = null) {
        logService?.flush(callback)
    }

    /**
     * Get the number of logs currently buffered
     */
    @JvmStatic
    fun getPendingLogCount(): Int = logService?.bufferSize ?: 0

    // ==================== LIFECYCLE ====================

    /**
     * Close the SDK (call on app termination for graceful shutdown)
     */
    @JvmStatic
    fun close() {
        AbrovaTraceLogger.debug("AbrovaTrace SDK closing...")

        // Flush and shutdown log service
        logService?.shutdown()

        // Stop ANR watchdog
        anrWatchdog?.stop()

        // Shutdown network client
        client?.shutdown()

        BreadcrumbService.addSystem("AbrovaTrace SDK closed")

        AbrovaTraceLogger.info("AbrovaTrace SDK closed")
    }

    // ==================== PRIVATE METHODS ====================

    private fun ensureInitialized(): Boolean {
        if (!isInitialized.get()) {
            AbrovaTraceLogger.error("AbrovaTrace SDK not initialized. Call AbrovaTrace.init() first.")
            return false
        }
        if (config?.enabled == false) {
            AbrovaTraceLogger.debug("AbrovaTrace SDK is disabled")
            return false
        }
        return true
    }

    private fun setupOfflineStorage() {
        try {
            val ctx = context ?: return
            val cfg = config ?: return
            val c = client ?: return

            // Null when enableOfflineStorage is off: nothing is stored and
            // the disk is not touched.
            val store = OfflineErrorStore.createIfEnabled(cfg) {
                // Runs on a background thread, on first use.
                storageDirectory(ctx)
            } ?: return

            c.offlineStore = store

            // Background pass over anything stored by an earlier session.
            c.flushStoredErrors()
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Offline storage setup failed: ${t.message}")
        }
    }

    /** App-private directory for the SDK's own files. Touches the disk. */
    private fun storageDirectory(ctx: Context): java.io.File? {
        val noBackup = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ctx.noBackupFilesDir
        } else null
        return noBackup ?: ctx.filesDir
    }

    private fun setupNativeCrashReporting() {
        val ctx = context ?: return
        val cfg = config ?: return
        val c = client ?: return

        val reporter = NativeCrashReporter(ctx, handledCrashMarkers)
        nativeCrashReporter = reporter

        // Drain and dispatch on a background thread. Synchronous OkHttp on the
        // main thread would trigger NetworkOnMainThreadException, and the
        // ApplicationExitInfo query itself is cheap but the send is not.
        //
        // The whole body is wrapped in try/catch(Throwable) — the crash
        // reporter must never itself crash the host app. Any unexpected
        // failure (parser bug, OkHttp init failure, NoSuchMethodError from
        // a classloader collision, etc.) is swallowed here.
        Thread({
            try {
                val pending = reporter.drainPendingCrashReports(
                    environment = cfg.environment,
                    releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(ctx),
                    userAgent = userAgent
                )
                for (item in pending) {
                    AbrovaTraceLogger.info(
                        "Sending native crash from previous session " +
                            "(reason=${item.error.extra["exit_reason"]}, ts=${item.timestamp})"
                    )
                    val sent = c.sendErrorSync(item.error)
                    if (sent) {
                        // Only advance the watermark on success. A failed send is
                        // retried on the next launch instead of being silently lost.
                        reporter.markSent(item.timestamp)
                    } else {
                        // Stop advancing so older-then-current records still retry
                        // next time; a transient network failure shouldn't purge
                        // the backlog.
                        AbrovaTraceLogger.warn(
                            "Native crash send failed (ts=${item.timestamp}); will retry next launch"
                        )
                        break
                    }
                }
            } catch (t: Throwable) {
                AbrovaTraceLogger.error(
                    "NativeCrashDrain failed (swallowed to protect host app): ${t.javaClass.simpleName}: ${t.message}",
                    t
                )
            }
        }, "AbrovaTrace-NativeCrashDrain").apply {
            isDaemon = true
            start()
        }
    }

    private fun setupUncaughtExceptionHandler() {
        originalExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // The app is going down: the previous handler may keep the main
            // thread busy for as long as the system's crash dialog is open,
            // and that is not a hang to report.
            try {
                anrWatchdog?.stop()
            } catch (_: Throwable) {
            }

            // Whatever happens here, the previous handler runs afterwards:
            // the SDK must never change how the app crashes.
            try {
                reportUncaughtException(thread, throwable)
            } catch (t: Throwable) {
                try {
                    AbrovaTraceLogger.error("Failed to report crash: ${t.message}")
                } catch (_: Throwable) {
                }
            }

            // Call original handler
            originalExceptionHandler?.uncaughtException(thread, throwable)
        }

        AbrovaTraceLogger.debug("Uncaught exception handler installed")
    }

    /**
     * Runs on the crashing thread, which is often the main thread. The
     * report is sent from a helper thread while this one waits a bounded
     * time; a report that was not accepted in that time is stored for the
     * next launch (when offline storage is enabled).
     */
    private fun reportUncaughtException(thread: Thread, throwable: Throwable) {
        AbrovaTraceLogger.error("Uncaught exception in thread ${thread.name}", throwable)

        val cfg = config ?: return
        val c = client ?: return
        val error = AbrovaTraceError.fromThrowable(
            throwable = throwable,
            message = "Uncaught exception in thread: ${thread.name}",
            environment = cfg.environment,
            releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(context!!),
            userAgent = userAgent,
            breadcrumbs = BreadcrumbService.getBreadcrumbs(),
            extra = extraContext + mapOf(
                "user_id" to userId,
                "session_id" to sessionId,
                "thread_name" to thread.name,
                "thread_id" to thread.id,
                "error_type" to "uncaught_exception",
                "device_info" to DeviceInfo.getDeviceInfo()
            ),
            tags = tags,
            url = currentActivityName?.let { "android://$it" }
        )

        val markers = handledCrashMarkers
        c.deliverCrashReport(
            error = error,
            onHandled = if (markers == null) null else {
                { markers.record(android.os.Process.myPid(), System.currentTimeMillis()) }
            }
        )
    }

    private fun setupAnrDetection() {
        val cfg = config ?: return
        anrWatchdog = ANRWatchdogService()

        anrWatchdog?.start(cfg.anrTimeoutMs) { stackTrace, blockedMs ->
            AbrovaTraceLogger.warn("ANR detected, sending report...")

            val error = AbrovaTraceError.anr(
                stackTrace = stackTrace,
                environment = cfg.environment,
                releaseVersion = cfg.release ?: DeviceInfo.getAppVersion(context!!),
                userAgent = userAgent,
                anrDurationMs = blockedMs
            )

            client?.sendError(error)
        }

        AbrovaTraceLogger.debug("ANR watchdog started")
    }

    private fun setupLifecycleCallbacks() {
        val ctx = context ?: return

        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_START -> BreadcrumbService.addSystem("App entered foreground")
                        Lifecycle.Event.ON_STOP -> BreadcrumbService.addSystem("App entered background")
                        else -> {}
                    }
                }
            })
        } catch (e: Exception) {
            AbrovaTraceLogger.error("Failed to setup lifecycle observer: ${e.message}")
        }

        // Track activity lifecycle
        if (ctx is Application) {
            ctx.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var currentActivity: String? = null

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    val activityName = activity.javaClass.simpleName
                    BreadcrumbService.addNavigation(currentActivity, activityName)
                    currentActivity = activityName
                    currentActivityName = activityName
                }

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    val activityName = activity.javaClass.simpleName
                    if (currentActivity != activityName) {
                        BreadcrumbService.addNavigation(currentActivity, activityName)
                        currentActivity = activityName
                        currentActivityName = activityName
                    }
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        AbrovaTraceLogger.debug("Lifecycle callbacks registered")
    }
}
