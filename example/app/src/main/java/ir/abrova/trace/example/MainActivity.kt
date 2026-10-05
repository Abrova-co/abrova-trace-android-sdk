package ir.abrova.trace.example

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ir.abrova.trace.example.databinding.ActivityMainBinding
import ir.abrova.trace.sdk.AbrovaTrace
import ir.abrova.trace.sdk.models.BreadcrumbType
import ir.abrova.trace.sdk.models.MessageLevel
import ir.abrova.trace.sdk.models.PerformanceSpan
import ir.abrova.trace.sdk.network.AbrovaTracePerformanceInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        // ==================== ERROR TRACKING ====================

        // Capture Exception
        binding.btnCaptureException.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Clicked capture exception button")

            try {
                throw RuntimeException("Test exception from AbrovaTrace Example App")
            } catch (e: Exception) {
                AbrovaTrace.captureException(
                    throwable = e,
                    extra = mapOf("button" to "capture_exception", "screen" to "main")
                ) { success, _ ->
                    runOnUiThread {
                        showToast(if (success) "Exception captured!" else "Failed to capture")
                    }
                }
            }
        }

        // Capture Message
        binding.btnCaptureMessage.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Clicked capture message button")

            AbrovaTrace.captureMessage(
                message = "User clicked the test button",
                level = MessageLevel.INFO,
                extra = mapOf("action" to "test_button_click")
            ) { success, _ ->
                runOnUiThread {
                    showToast(if (success) "Message captured!" else "Failed to capture")
                }
            }
        }

        // Trigger Crash (will crash the app!)
        binding.btnTriggerCrash.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("User triggered intentional crash")
            AbrovaTrace.captureMessage("About to crash intentionally", MessageLevel.WARNING)

            // This will crash the app - SDK will capture it
            throw RuntimeException("Intentional crash for testing AbrovaTrace")
        }

        // ==================== BREADCRUMBS ====================

        // Add Breadcrumb
        binding.btnAddBreadcrumb.setOnClickListener {
            AbrovaTrace.addBreadcrumb(
                message = "Custom breadcrumb added",
                type = BreadcrumbType.USER,
                data = mapOf("timestamp" to System.currentTimeMillis())
            )

            // Send message to API with the breadcrumb attached
            AbrovaTrace.captureMessage(
                message = "Breadcrumb test - custom breadcrumb added",
                level = MessageLevel.INFO,
                extra = mapOf("test_type" to "breadcrumb")
            ) { success, _ ->
                runOnUiThread {
                    showToast(if (success) "Breadcrumb added and sent!" else "Breadcrumb added locally but failed to send")
                }
            }
        }

        // Navigate to Second Activity
        binding.btnNavigate.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Navigating to SecondActivity")

            AbrovaTrace.captureMessage(
                message = "User navigating to SecondActivity",
                level = MessageLevel.INFO,
                extra = mapOf("from" to "MainActivity", "to" to "SecondActivity")
            ) { _, _ -> }

            startActivity(Intent(this, SecondActivity::class.java))
        }

        // HTTP Request (with manual breadcrumb)
        binding.btnHttpRequest.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Making HTTP request")

            thread {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://httpbin.org/get")
                        .build()

                    val response = client.newCall(request).execute()
                    val statusCode = response.code()

                    // Add HTTP breadcrumb manually
                    AbrovaTrace.addHttpBreadcrumb(
                        method = "GET",
                        url = "https://httpbin.org/get",
                        statusCode = statusCode,
                        duration = 0
                    )

                    response.close()

                    AbrovaTrace.captureMessage(
                        message = "HTTP request completed",
                        level = MessageLevel.INFO,
                        extra = mapOf(
                            "method" to "GET",
                            "url" to "https://httpbin.org/get",
                            "status_code" to statusCode
                        )
                    ) { success, _ ->
                        runOnUiThread {
                            showToast(if (success) "HTTP Request successful: $statusCode" else "HTTP Request done but failed to send to API")
                        }
                    }
                } catch (e: IOException) {
                    AbrovaTrace.captureException(e, "HTTP Request failed")
                    runOnUiThread {
                        showToast("HTTP Request failed: ${e.message}")
                    }
                }
            }
        }

        // ==================== USER CONTEXT ====================

        // Set User ID
        binding.btnSetUser.setOnClickListener {
            val userId = "user_${System.currentTimeMillis()}"
            AbrovaTrace.setUserId(userId)

            AbrovaTrace.captureMessage(
                message = "User ID updated",
                level = MessageLevel.INFO,
                extra = mapOf("user_id" to userId)
            ) { success, _ ->
                runOnUiThread {
                    showToast(if (success) "User ID set: $userId" else "User ID set locally but failed to send to API")
                }
            }
        }

        // ==================== PERFORMANCE ====================

        // Track Performance (Manual Spans)
        binding.btnTrackPerformance.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Testing performance tracking")
            showToast("Tracking performance...")

            thread {
                // 1. Simulated API call span
                val startTime1 = System.currentTimeMillis()
                Thread.sleep(350)
                AbrovaTrace.reportPerformanceSpan(
                    method = "GET",
                    url = "https://api.example.com/users",
                    statusCode = 200,
                    durationMs = System.currentTimeMillis() - startTime1,
                    startTime = startTime1,
                    tags = mapOf("endpoint" to "users")
                )

                // 2. Simulated slow DB query span
                val startTime2 = System.currentTimeMillis()
                Thread.sleep(150)
                AbrovaTrace.reportPerformanceSpan(
                    method = "SELECT",
                    url = "db://users?query=findAll",
                    statusCode = 200,
                    durationMs = System.currentTimeMillis() - startTime2,
                    startTime = startTime2,
                    tags = mapOf("db_table" to "users", "query_type" to "SELECT")
                )

                // 3. Simulated failed request span
                val startTime3 = System.currentTimeMillis()
                Thread.sleep(100)
                AbrovaTrace.reportPerformanceSpan(
                    method = "POST",
                    url = "https://api.example.com/orders",
                    statusCode = 500,
                    durationMs = System.currentTimeMillis() - startTime3,
                    startTime = startTime3,
                    errorMessage = "Internal Server Error",
                    tags = mapOf("endpoint" to "orders")
                )

                runOnUiThread {
                    showToast("3 performance spans sent to AbrovaTrace")
                }
            }
        }

        // Performance Interceptor (Auto HTTP tracking via OkHttp)
        binding.btnPerformanceInterceptor.setOnClickListener {
            AbrovaTrace.addUserBreadcrumb("Testing performance interceptor")
            showToast("Making auto-tracked HTTP requests...")

            thread {
                // Create OkHttp client with AbrovaTrace performance interceptor
                val client = OkHttpClient.Builder()
                    .addInterceptor(AbrovaTrace.performanceInterceptor(minDurationMs = 10))
                    .build()

                try {
                    // Request 1 - auto-tracked as performance span
                    val request1 = Request.Builder()
                        .url("https://jsonplaceholder.typicode.com/posts/1")
                        .build()
                    val response1 = client.newCall(request1).execute()
                    val code1 = response1.code()
                    response1.close()

                    // Request 2 - also auto-tracked
                    val request2 = Request.Builder()
                        .url("https://jsonplaceholder.typicode.com/users/1")
                        .build()
                    val response2 = client.newCall(request2).execute()
                    val code2 = response2.code()
                    response2.close()

                    runOnUiThread {
                        showToast("2 HTTP requests auto-tracked: $code1, $code2")
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        showToast("HTTP request failed: ${e.message}")
                    }
                }
            }
        }

        // DB Query Span
        binding.btnDbQuerySpan.setOnClickListener {
            showToast("Reporting DB query spans...")

            thread {
                // Simulate a SELECT query
                val startTime1 = System.currentTimeMillis()
                Thread.sleep(15)
                AbrovaTrace.reportPerformanceSpan(
                    PerformanceSpan.forDbQuery(
                        queryType = "SELECT",
                        tableName = "users",
                        durationMs = System.currentTimeMillis() - startTime1,
                        startTime = startTime1,
                        rowsAffected = 42
                    )
                )

                // Simulate an INSERT query
                val startTime2 = System.currentTimeMillis()
                Thread.sleep(25)
                AbrovaTrace.reportPerformanceSpan(
                    PerformanceSpan.forDbQuery(
                        queryType = "INSERT",
                        tableName = "orders",
                        durationMs = System.currentTimeMillis() - startTime2,
                        startTime = startTime2,
                        rowsAffected = 1,
                        tags = mapOf("priority" to "high")
                    )
                )

                runOnUiThread {
                    showToast("2 DB query spans sent to AbrovaTrace")
                }
            }
        }

        // Track Operation (Auto-timed)
        binding.btnTrackOperation.setOnClickListener {
            showToast("Running tracked operation...")

            thread {
                // trackOperation auto-measures the duration and reports a span
                val result = AbrovaTrace.trackOperation(
                    operation = "simulateApiCall",
                    operationType = "custom",
                    tags = mapOf("source" to "example_app")
                ) {
                    Thread.sleep(350)
                    "success"
                }

                // Track an operation that fails
                try {
                    AbrovaTrace.trackOperation(
                        operation = "failingOperation",
                        operationType = "custom"
                    ) {
                        Thread.sleep(100)
                        throw RuntimeException("Simulated operation failure")
                    }
                } catch (_: Exception) {
                    // Expected - the span is reported with status "error"
                }

                runOnUiThread {
                    showToast("2 operations tracked (1 ok, 1 error). Result: $result")
                }
            }
        }

        // Batch Span Reporting
        binding.btnBatchSpans.setOnClickListener {
            showToast("Sending batch of spans...")

            thread {
                val spans = listOf(
                    PerformanceSpan.fromHttpRequest(
                        method = "GET",
                        url = "https://api.example.com/products",
                        statusCode = 200,
                        durationMs = 120,
                        startTime = System.currentTimeMillis() - 120
                    ),
                    PerformanceSpan.forDbQuery(
                        queryType = "SELECT",
                        tableName = "products",
                        durationMs = 8,
                        startTime = System.currentTimeMillis() - 8,
                        rowsAffected = 50
                    ),
                    PerformanceSpan.custom(
                        operation = "renderProductList",
                        durationMs = 45,
                        startTime = System.currentTimeMillis() - 45,
                        operationType = "render"
                    )
                )

                AbrovaTrace.reportPerformanceSpanBatch(spans) { success, _ ->
                    runOnUiThread {
                        showToast(if (success) "Batch of ${spans.size} spans sent!" else "Failed to send batch")
                    }
                }
            }
        }

        // ==================== LOGGING ====================

        // Send Logs
        binding.btnSendLogs.setOnClickListener {
            AbrovaTrace.trace("Entering checkout flow")
            AbrovaTrace.logDebug("Cart items loaded", mapOf("item_count" to 3))
            AbrovaTrace.info("User started checkout")
            AbrovaTrace.warn("Inventory low for item SKU-123", mapOf("stock" to 2))
            AbrovaTrace.logError("Failed to apply discount code")
            AbrovaTrace.fatal("Database connection lost")

            AbrovaTrace.flushLogs()
            showToast("6 logs sent to AbrovaTrace")
        }

        // ==================== ADVANCED FEATURES ====================

        // Crash Detection - check if previous session crashed
        binding.btnCrashDetection.setOnClickListener {
            // Note: Crash detection is automatically handled by the SDK on init.
            // The CrashDetector checks for a previous crash on startup and sends it.
            // This button demonstrates what happens at init time.
            //
            // To test:
            // 1. Press "Trigger Crash" to crash the app
            // 2. Reopen the app
            // 3. The SDK automatically detects and reports the crash
            //
            // The crash report includes:
            // - Session ID of the crashed session
            // - Last activity that was visible
            // - Time since the crash

            AbrovaTrace.captureMessage(
                message = "Crash detection is automatic. Use 'Trigger Crash' then reopen the app to test.",
                level = MessageLevel.INFO,
                extra = mapOf(
                    "feature" to "crash_detection",
                    "how_it_works" to "Session marker file + clean exit marker",
                    "max_crash_age_hours" to 24
                )
            ) { success, _ ->
                runOnUiThread {
                    showToast(
                        "Crash detection is automatic on SDK init.\n" +
                        "1) Press 'Trigger Crash'\n" +
                        "2) Reopen app\n" +
                        "3) SDK auto-reports the crash"
                    )
                }
            }
        }

        // Sample Rate Demo
        binding.btnSampleRate.setOnClickListener {
            showToast("Sending 10 errors with sampleRate: 1.0...")

            // The sample rate is configured at init time in AbrovaTraceExampleApp.
            // With sampleRate: 1.0 (default), all errors are captured.
            // With sampleRate: 0.5, ~50% of errors are randomly dropped.
            //
            // To test: Change sampleRate in AbrovaTraceExampleApp.kt and observe
            // how many of the 10 errors actually get sent.

            val sentCount = AtomicInteger(0)
            val totalErrors = 10

            thread {
                for (i in 0 until totalErrors) {
                    try {
                        throw RuntimeException("Sample rate test error #$i")
                    } catch (e: Exception) {
                        AbrovaTrace.captureException(
                            throwable = e,
                            message = "Sample rate test",
                            extra = mapOf("error_index" to i, "total_errors" to totalErrors)
                        ) { success, _ ->
                            if (success) sentCount.incrementAndGet()
                        }
                    }
                }

                // Wait for callbacks
                Thread.sleep(2000)

                runOnUiThread {
                    showToast("Sample rate: ${sentCount.get()}/$totalErrors errors sent (rate: 1.0)")
                }
            }
        }

        // Native crash test buttons. These trigger real POSIX signals from a
        // JNI shim so ApplicationExitInfo records REASON_CRASH_NATIVE with a
        // tombstone. The app dies immediately; on next launch the SDK's
        // NativeCrashReporter drains the pending report and POSTs it as a
        // Sentry-shape structured event.
        binding.btnNativeSigsegv.setOnClickListener {
            AbrovaTrace.addBreadcrumb("User triggered native SIGSEGV", BreadcrumbType.INFO)
            Handler(Looper.getMainLooper()).postDelayed({
                CrashHelper.nativeSigsegv()
            }, 50)
        }

        binding.btnNativeAbort.setOnClickListener {
            AbrovaTrace.addBreadcrumb("User triggered native abort()", BreadcrumbType.INFO)
            Handler(Looper.getMainLooper()).postDelayed({
                CrashHelper.nativeAbort()
            }, 50)
        }

        binding.btnNativeAnr.setOnClickListener {
            AbrovaTrace.addBreadcrumb("User triggered ANR (main thread block)", BreadcrumbType.INFO)
            Handler(Looper.getMainLooper()).postDelayed({
                Thread.sleep(15_000)
            }, 50)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
