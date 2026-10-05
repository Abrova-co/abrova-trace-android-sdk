package ir.abrova.trace.sdk.services

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ir.abrova.trace.sdk.models.LogEntry
import ir.abrova.trace.sdk.models.LogLevel
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow

/**
 * Service for batching and sending logs to AbrovaTrace
 *
 * Features (matching Better Stack/Logtail):
 * - Lazy timer: only runs when buffer has logs
 * - Exponential backoff: retries with increasing delays (1s, 2s, 4s, 8s...)
 * - Max buffer size: drops oldest logs when buffer exceeds limit
 * - Lifecycle hooks: flushes on app background
 */
class LogService(
    private val apiKey: String,
    private val sourceId: String? = null,
    private val sourceName: String? = null,
    private val platform: String = "android",
    private val environment: String = "production",
    private val release: String? = null,
    private val batchSize: Int = 50,
    private val flushIntervalMs: Long = 30000,
    private val maxBufferSize: Int = 1000,
    apiUrl: String = "https://trace.abrova.ir"
) : DefaultLifecycleObserver {
    private val buffer = CopyOnWriteArrayList<LogEntry>()
    private val isFlushing = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var flushTimer: Timer? = null
    private val timerLock = Any()
    private val retryAttempt = AtomicInteger(0)
    @Volatile private var isAppActive = true

    private val apiEndpoint = apiUrl.trimEnd('/')

    // Exponential backoff constants
    private val baseRetryDelayMs: Long = 1000
    private val maxRetryDelayMs: Long = 60000
    private val maxRetryAttempts = 10

    init {
        // Register for app lifecycle events
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // MARK: - Lifecycle Hooks

    override fun onStop(owner: LifecycleOwner) {
        // App going to background
        isAppActive = false
        flush(null)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App returning to foreground
        isAppActive = true
        retryAttempt.set(0)
        if (buffer.isNotEmpty()) {
            scheduleFlush()
        }
    }

    /**
     * Calculate retry delay with exponential backoff
     */
    private fun getRetryDelay(): Long {
        val delay = baseRetryDelayMs * 2.0.pow(retryAttempt.get().toDouble()).toLong()
        return min(delay, maxRetryDelayMs)
    }

    /**
     * Enforce max buffer size by dropping oldest logs
     */
    private fun enforceMaxBufferSize() {
        while (buffer.size > maxBufferSize) {
            buffer.removeAt(0)
            AbrovaTraceLogger.warn("Buffer overflow: dropped oldest log")
        }
    }

    /**
     * Schedule a one-shot flush timer (only if buffer has logs)
     */
    private fun scheduleFlush() {
        synchronized(timerLock) {
            flushTimer?.cancel()

            // Don't schedule if app is inactive
            if (!isAppActive) return

            // Only schedule if there are logs to send
            if (buffer.isEmpty()) return

            // Use exponential backoff delay if retrying, otherwise normal interval
            val delay = if (retryAttempt.get() > 0) getRetryDelay() else flushIntervalMs

            flushTimer = Timer("AbrovaTraceLogFlush", true).apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        flush(null)
                    }
                }, delay)
            }
        }
    }

    /**
     * Cancel the flush timer
     */
    private fun cancelFlushTimer() {
        synchronized(timerLock) {
            flushTimer?.cancel()
            flushTimer = null
        }
    }

    /**
     * Add a log entry to the buffer
     */
    fun add(entry: LogEntry) {
        buffer.add(entry)

        // Enforce max buffer size (drop oldest if exceeds limit)
        enforceMaxBufferSize()

        if (buffer.size >= batchSize) {
            flush(null)
        } else if (flushTimer == null) {
            // Schedule flush only if timer isn't already running
            scheduleFlush()
        }
    }

    /**
     * Add a log with convenience parameters
     */
    fun log(
        message: String,
        level: LogLevel = LogLevel.INFO,
        metadata: Map<String, Any?>? = null,
        userId: String? = null
    ) {
        add(LogEntry(
            message = message,
            level = level,
            metadata = metadata,
            userId = userId
        ))
    }

    /**
     * Send a single log immediately (bypasses batching)
     */
    fun sendImmediate(entry: LogEntry, callback: ((Boolean) -> Unit)? = null) {
        executor.execute {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val payload = JSONObject().apply {
                    put("message", entry.message)
                    put("level", entry.level.value)
                    put("timestamp", dateFormat.format(entry.timestamp))
                    put("platform", platform)
                    put("environment", environment)
                    put("sourceType", "sdk")
                    release?.let { put("release", it) }
                    sourceId?.let { put("sourceId", it) }
                    sourceName?.let { put("sourceName", it) }
                    entry.userId?.let { put("userId", it) }
                    entry.metadata?.let { put("metadata", JSONObject(it)) }
                }

                val success = sendRequest("/api/logs/ingest", payload)
                callback?.invoke(success)
            } catch (e: Exception) {
                AbrovaTraceLogger.error("Failed to send log: ${e.message}")
                callback?.invoke(false)
            }
        }
    }

    /**
     * Flush all buffered logs to the server
     */
    fun flush(callback: ((Boolean) -> Unit)?) {
        // Cancel timer since we're flushing now
        cancelFlushTimer()

        if (buffer.isEmpty() || !isFlushing.compareAndSet(false, true)) {
            callback?.invoke(true)
            return
        }

        val logsToSend = ArrayList(buffer)
        buffer.clear()

        executor.execute {
            try {
                // If no sourceId, send individual logs
                if (sourceId == null) {
                    var allSucceeded = true
                    for (entry in logsToSend) {
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }

                        val payload = JSONObject().apply {
                            put("message", entry.message)
                            put("level", entry.level.value)
                            put("timestamp", dateFormat.format(entry.timestamp))
                            put("platform", platform)
                            put("environment", environment)
                            put("sourceType", "sdk")
                            release?.let { put("release", it) }
                            entry.userId?.let { put("userId", it) }
                            entry.metadata?.let { put("metadata", JSONObject(it)) }
                        }

                        if (!sendRequest("/api/logs/ingest", payload)) {
                            allSucceeded = false
                        }
                    }
                    isFlushing.set(false)
                    callback?.invoke(allSucceeded)
                    return@execute
                }

                // Batch send
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val logsArray = JSONArray()
                for (entry in logsToSend) {
                    val logObj = JSONObject().apply {
                        put("message", entry.message)
                        put("level", entry.level.value)
                        put("timestamp", dateFormat.format(entry.timestamp))
                        put("platform", platform)
                        put("environment", environment)
                        release?.let { put("release", it) }
                        entry.userId?.let { put("userId", it) }
                        entry.metadata?.let { put("metadata", JSONObject(it)) }
                    }
                    logsArray.put(logObj)
                }

                val payload = JSONObject().apply {
                    put("sourceId", sourceId)
                    put("sourceType", "sdk")
                    put("logs", logsArray)
                    sourceName?.let { put("sourceName", it) }
                }

                val success = sendRequest("/api/logs/ingest/batch", payload)

                if (success) {
                    retryAttempt.set(0) // Reset on success
                } else {
                    // Put logs back in buffer for retry
                    buffer.addAll(0, logsToSend)
                    enforceMaxBufferSize() // Don't exceed max when re-adding
                    // Increment retry attempt and schedule with backoff
                    if (retryAttempt.get() < maxRetryAttempts) {
                        retryAttempt.incrementAndGet()
                        scheduleFlush()
                    } else {
                        AbrovaTraceLogger.error("Max retry attempts reached, logs will be dropped")
                        retryAttempt.set(0)
                    }
                }

                isFlushing.set(false)
                callback?.invoke(success)
            } catch (e: Exception) {
                AbrovaTraceLogger.error("Failed to flush logs: ${e.message}")
                // Put logs back in buffer for retry
                buffer.addAll(0, logsToSend)
                enforceMaxBufferSize() // Don't exceed max when re-adding
                // Increment retry attempt and schedule with backoff
                if (retryAttempt.get() < maxRetryAttempts) {
                    retryAttempt.incrementAndGet()
                    scheduleFlush()
                } else {
                    AbrovaTraceLogger.error("Max retry attempts reached, logs will be dropped")
                    retryAttempt.set(0)
                }
                isFlushing.set(false)
                callback?.invoke(false)
            }
        }
    }

    /**
     * Get the number of buffered logs
     */
    val bufferSize: Int
        get() = buffer.size

    /**
     * Shutdown the service
     */
    fun shutdown() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        cancelFlushTimer()
        flush(null)
        executor.shutdown()
    }

    private fun sendRequest(endpoint: String, payload: JSONObject): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(apiEndpoint + endpoint)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                connectTimeout = 30000
                readTimeout = 30000
                doOutput = true
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val success = responseCode in 200..299

            if (success) {
                AbrovaTraceLogger.debug("Logs sent successfully")
            } else {
                AbrovaTraceLogger.warn("Failed to send logs: HTTP $responseCode")
            }

            success
        } catch (e: Exception) {
            AbrovaTraceLogger.error("Error sending logs: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
