package ir.abrova.trace.sdk.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import ir.abrova.trace.sdk.AbrovaTraceConfig
import ir.abrova.trace.sdk.models.*
import ir.abrova.trace.sdk.services.OfflineErrorStore
import ir.abrova.trace.sdk.services.SendOutcome
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import okhttp3.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP client for AbrovaTrace API
 */
class AbrovaTraceClient(
    private val config: AbrovaTraceConfig
) {
    companion object {
        private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")
        private const val API_KEY_HEADER = "X-API-Key"

        // Longest the crashing thread waits for a crash report to be sent.
        internal const val CRASH_SEND_TIMEOUT_MS = 2000L

        // Longest it then waits for an unsent report to reach the disk.
        internal const val CRASH_STORE_TIMEOUT_MS = 1000L

        private const val CRASH_PENDING = 0
        private const val CRASH_WORKER_FINISHES = 1
        private const val CRASH_GIVEN_UP = 2
    }

    /** What became of a crash report handed to [deliverCrashReport]. */
    internal enum class CrashDelivery {
        /** The server accepted it. */
        SENT,

        /** Not accepted in time; it is on disk and will be sent later. */
        STORED,

        /** The server refused it for good (4xx other than 408 and 429). */
        REJECTED,

        /** Neither sent nor stored. */
        NOT_DELIVERED;

        /** Sent or stored: nothing else has to report this crash. */
        val isHandled: Boolean get() = this == SENT || this == STORED
    }

    /**
     * Disk queue for errors that could not be sent because the network was
     * unavailable. Null when offline storage is disabled.
     */
    @Volatile
    internal var offlineStore: OfflineErrorStore? = null

    private val flushScheduled = AtomicBoolean(false)

    private val baseUrl: String = config.apiUrl.trimEnd('/')

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Send an error to AbrovaTrace
     */
    fun sendError(error: AbrovaTraceError, callback: ((Boolean, String?) -> Unit)? = null) {
        val json = gson.toJson(error.toMap())
        val request = buildErrorRequest(json)

        AbrovaTraceLogger.debug("Sending error to AbrovaTrace: ${error.message}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send error: ${e.message}")
                // No answer from the server: keep the report for later.
                // Runs on OkHttp's dispatcher thread, never the main thread.
                offlineStore?.store(json)
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Error sent successfully")
                } else {
                    AbrovaTraceLogger.error("Error response: $responseBody")
                }

                try {
                    callback?.invoke(success, responseBody)
                } finally {
                    // The network is back: try the stored errors too.
                    if (success) flushStoredErrors()
                }
            }
        })
    }

    /**
     * Send an error synchronously (for use in crash handlers)
     */
    fun sendErrorSync(error: AbrovaTraceError): Boolean {
        // Never stored offline: callers of this method (the previous-session
        // crash drain) keep their own record of what still has to be sent.
        val url = "$baseUrl/api/errors"
        val json = gson.toJson(error.toMap())

        return try {
            val response = client.newCall(buildErrorRequest(json)).execute()
            val success = response.isSuccessful
            if (!success) {
                AbrovaTraceLogger.error("sendErrorSync HTTP ${response.code()} url=$url")
            }
            response.close()
            success
        } catch (e: Exception) {
            AbrovaTraceLogger.error(
                "sendErrorSync ${e.javaClass.simpleName}: ${e.message ?: "no message"} url=$url"
            )
            false
        }
    }

    /**
     * Deliver the report of an uncaught exception while the process is
     * about to die. Called on the crashing thread, which may be the main
     * thread: the request is made on a helper thread and the caller waits
     * for it for at most [sendTimeoutMs].
     *
     * If the server has not accepted the report by then - no network, a
     * timeout, any exception, a 5xx, 408 or 429 answer - it is written to
     * the offline store (when enabled) before this method returns, so a
     * later launch sends it. A report the server refused for good is not
     * stored. The disk write also happens off the calling thread, bounded by
     * [storeTimeoutMs].
     *
     * [onHandled] runs once, off the calling thread and inside the same
     * time limits, when the report was sent or stored.
     *
     * Never throws.
     */
    internal fun deliverCrashReport(
        error: AbrovaTraceError,
        sendTimeoutMs: Long = CRASH_SEND_TIMEOUT_MS,
        storeTimeoutMs: Long = CRASH_STORE_TIMEOUT_MS,
        onHandled: (() -> Unit)? = null
    ): CrashDelivery {
        var json: String? = null
        try {
            val body = gson.toJson(error.toMap())
            json = body

            val state = AtomicInteger(CRASH_PENDING)
            val result = AtomicReference<CrashDelivery?>(null)
            val activeCall = AtomicReference<Call?>(null)
            val workerDone = CountDownLatch(1)

            startCrashThread("AbrovaTrace-CrashSend") {
                try {
                    val outcome = sendCrashReport(body, state, activeCall)
                    // The answer came in time: this thread finishes the job.
                    if (state.compareAndSet(CRASH_PENDING, CRASH_WORKER_FINISHES)) {
                        result.set(settleCrashReport(body, outcome, onHandled))
                    }
                } catch (t: Throwable) {
                    AbrovaTraceLogger.error("Crash report could not be sent: ${t.message}")
                } finally {
                    workerDone.countDown()
                }
            }

            if (!awaitBounded(workerDone, sendTimeoutMs)) {
                if (state.compareAndSet(CRASH_PENDING, CRASH_GIVEN_UP)) {
                    // No answer in time. Stop the request and keep the report.
                    try { activeCall.get()?.cancel() } catch (_: Throwable) {}
                    AbrovaTraceLogger.warn("Crash report not sent within ${sendTimeoutMs}ms")

                    val storeDone = CountDownLatch(1)
                    startCrashThread("AbrovaTrace-CrashStore") {
                        try {
                            result.set(settleCrashReport(body, SendOutcome.NetworkFailure, onHandled))
                        } catch (t: Throwable) {
                            AbrovaTraceLogger.error("Crash report could not be stored: ${t.message}")
                        } finally {
                            storeDone.countDown()
                        }
                    }
                    awaitBounded(storeDone, storeTimeoutMs)
                } else {
                    // The helper thread got its answer at the last moment
                    // and is storing or finishing up.
                    awaitBounded(workerDone, storeTimeoutMs)
                }
            }

            return result.get() ?: CrashDelivery.NOT_DELIVERED
        } catch (t: Throwable) {
            // For example no memory left to start a thread. Last resort: a
            // plain disk write on this thread.
            try {
                AbrovaTraceLogger.error("Crash report delivery failed: ${t.message}")
                val body = json
                if (body != null && offlineStore?.store(body) == true) {
                    try { onHandled?.invoke() } catch (_: Throwable) {}
                    return CrashDelivery.STORED
                }
            } catch (_: Throwable) {
            }
            return CrashDelivery.NOT_DELIVERED
        }
    }

    /** One blocking attempt to post a crash report. Helper thread only. */
    private fun sendCrashReport(
        json: String,
        state: AtomicInteger,
        activeCall: AtomicReference<Call?>
    ): SendOutcome {
        return try {
            val call = client.newCall(buildErrorRequest(json))
            activeCall.set(call)
            // The caller may have given up while the request was being built.
            if (state.get() != CRASH_PENDING) {
                call.cancel()
                return SendOutcome.NetworkFailure
            }
            val response = call.execute()
            val code = response.code()
            response.close()
            SendOutcome.Http(code)
        } catch (e: IOException) {
            AbrovaTraceLogger.error("Crash report not sent: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            SendOutcome.NetworkFailure
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Crash report not sent: ${t.javaClass.simpleName}: ${t.message ?: "no message"}")
            SendOutcome.Failed
        }
    }

    /**
     * Decide what happens to a crash report after the send attempt, and
     * store it when it still has to be sent. Disk I/O only.
     */
    private fun settleCrashReport(
        json: String,
        outcome: SendOutcome,
        onHandled: (() -> Unit)?
    ): CrashDelivery {
        val delivery = when {
            outcome.isAccepted -> CrashDelivery.SENT
            outcome.isRejected -> {
                AbrovaTraceLogger.error("Crash report rejected by server (HTTP ${(outcome as SendOutcome.Http).code})")
                CrashDelivery.REJECTED
            }
            offlineStore?.store(json) == true -> CrashDelivery.STORED
            else -> CrashDelivery.NOT_DELIVERED
        }
        if (delivery.isHandled) {
            try {
                onHandled?.invoke()
            } catch (t: Throwable) {
                AbrovaTraceLogger.error("Crash report follow-up failed: ${t.message}")
            }
        }
        AbrovaTraceLogger.debug("Crash report: $delivery")
        return delivery
    }

    private fun startCrashThread(name: String, body: () -> Unit) {
        Thread(body, name).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Wait for [latch] for at most [timeoutMs], also when the calling thread
     * has been interrupted. Returns true when the latch was released.
     */
    private fun awaitBounded(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var interrupted = false
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return latch.count == 0L
                try {
                    return latch.await(remaining, TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Resend errors that were stored while the network was unavailable.
     * Returns immediately; the work happens on a background thread, and only
     * one pass runs at a time.
     */
    internal fun flushStoredErrors() {
        val store = offlineStore ?: return
        if (!store.mayHavePending) return
        if (!flushScheduled.compareAndSet(false, true)) return

        try {
            Thread({
                try {
                    store.flush { body -> postStoredError(body) }
                } catch (t: Throwable) {
                    AbrovaTraceLogger.error("Sending stored errors failed: ${t.message}")
                } finally {
                    flushScheduled.set(false)
                }
            }, "AbrovaTrace-OfflineFlush").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            flushScheduled.set(false)
            AbrovaTraceLogger.error("Could not start sending stored errors: ${t.message}")
        }
    }

    /**
     * Post a stored error body with the current API key and headers.
     * Blocking; called from the offline flush thread only.
     */
    private fun postStoredError(json: String): SendOutcome {
        return try {
            val response = client.newCall(buildErrorRequest(json)).execute()
            val code = response.code()
            response.close()
            SendOutcome.Http(code)
        } catch (e: IOException) {
            SendOutcome.NetworkFailure
        } catch (e: Exception) {
            AbrovaTraceLogger.error("Stored error could not be sent: ${e.message}")
            SendOutcome.Failed
        }
    }

    private fun buildErrorRequest(json: String): Request {
        return Request.Builder()
            .url("$baseUrl/api/errors")
            .post(RequestBody.create(JSON_MEDIA_TYPE, json))
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()
    }

    /**
     * Send a message to AbrovaTrace
     */
    fun sendMessage(message: AbrovaTraceError, callback: ((Boolean, String?) -> Unit)? = null) {
        val url = "$baseUrl/api/messages"
        val json = gson.toJson(message.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending message to AbrovaTrace: ${message.message}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send message: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Message sent successfully")
                } else {
                    AbrovaTraceLogger.error("Message response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Send a performance span to AbrovaTrace APM
     */
    fun sendPerformanceSpan(span: PerformanceSpan, callback: ((Boolean, String?) -> Unit)? = null) {
        val url = "$baseUrl/api/performance/spans"
        val json = gson.toJson(span.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending performance span: ${span.operation}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send span: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Span sent successfully")
                } else {
                    AbrovaTraceLogger.error("Span response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Send multiple performance spans in a batch
     */
    fun sendPerformanceSpanBatch(spans: List<PerformanceSpan>, callback: ((Boolean, String?) -> Unit)? = null) {
        if (spans.isEmpty()) {
            callback?.invoke(true, null)
            return
        }

        val url = "$baseUrl/api/performance/spans/batch"
        val json = gson.toJson(mapOf("spans" to spans.map { it.toMap() }))
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending ${spans.size} performance spans")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send span batch: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Span batch sent successfully")
                } else {
                    AbrovaTraceLogger.error("Span batch response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Shutdown the client
     */
    fun shutdown() {
        client.dispatcher().executorService().shutdown()
        client.connectionPool().evictAll()
    }
}
