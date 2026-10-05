package ir.abrova.trace.sdk.services

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR (Application Not Responding) Watchdog
 * Detects when the main thread is blocked for too long
 */
class ANRWatchdog internal constructor(
    private val timeoutMs: Long,
    private val recoveryPollMs: Long,
    private val postToMain: (Runnable) -> Unit,
    private val now: () -> Long,
    private val mainThread: () -> Thread,
    private val onAnrDetected: (stackTrace: String, blockedMs: Long) -> Unit
) : Thread("AbrovaTrace-ANR-Watchdog") {

    constructor(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onAnrDetected: (stackTrace: String, blockedMs: Long) -> Unit
    ) : this(
        timeoutMs = timeoutMs,
        recoveryPollMs = RECOVERY_POLL_MS,
        postToMain = Handler(Looper.getMainLooper()).let { handler -> { ping -> handler.post(ping) } },
        now = { SystemClock.uptimeMillis() },
        mainThread = { Looper.getMainLooper().thread },
        onAnrDetected = onAnrDetected
    )

    // Time the current ping was posted to the main thread; 0 once it ran.
    private val tick = AtomicLong(0)

    @Volatile
    private var running = true

    init {
        isDaemon = true
    }

    override fun run() {
        AbrovaTraceLogger.debug("ANR Watchdog started with timeout: ${timeoutMs}ms")

        while (running && !isInterrupted) {
            try {
                val postedAt = now()
                tick.set(postedAt)
                postToMain(Runnable { tick.set(0) })

                sleep(timeoutMs)

                if (tick.get() != 0L && running) {
                    val blockedMs = now() - postedAt
                    AbrovaTraceLogger.warn("ANR detected! Main thread blocked for ${blockedMs}ms")

                    val main = mainThread()
                    onAnrDetected(formatStackTrace(main, main.stackTrace), blockedMs)

                    // One hang is one report: wait for the main thread to
                    // come back before watching for the next hang.
                    while (running && tick.get() != 0L) {
                        sleep(recoveryPollMs)
                    }
                }
            } catch (e: InterruptedException) {
                AbrovaTraceLogger.debug("ANR Watchdog interrupted")
                running = false
            } catch (e: Exception) {
                AbrovaTraceLogger.error("ANR Watchdog error: ${e.message}")
            }
        }

        AbrovaTraceLogger.debug("ANR Watchdog stopped")
    }

    /**
     * Stop the watchdog
     */
    fun shutdown() {
        running = false
        interrupt()
    }

    /**
     * Format stack trace for reporting
     */
    private fun formatStackTrace(thread: Thread, stackTrace: Array<StackTraceElement>): String {
        val sb = StringBuilder()
        sb.append("ANR in ${thread.name} (${thread.state})\n")
        sb.append("Main thread stack trace:\n")
        for (element in stackTrace) {
            sb.append("\tat ")
            sb.append(element.toString())
            sb.append("\n")
        }

        // Also capture other threads for context
        sb.append("\n--- Other threads ---\n")
        try {
            val allStackTraces = getAllStackTraces()
            for ((t, trace) in allStackTraces) {
                if (t != thread && t.name != name) {
                    sb.append("\nThread: ${t.name} (${t.state})\n")
                    for (element in trace.take(5)) {
                        sb.append("\tat ")
                        sb.append(element.toString())
                        sb.append("\n")
                    }
                    if (trace.size > 5) {
                        sb.append("\t... ${trace.size - 5} more\n")
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("(Could not capture other threads)\n")
        }

        return sb.toString()
    }

    companion object {
        /**
         * Default timeout for ANR detection (5 seconds, same as Android's threshold)
         */
        const val DEFAULT_TIMEOUT_MS = 5000L

        private const val RECOVERY_POLL_MS = 250L
    }
}

/**
 * Service class for managing ANR detection
 */
class ANRWatchdogService {

    private var watchdog: ANRWatchdog? = null

    /**
     * Start ANR detection
     */
    fun start(timeoutMs: Long = ANRWatchdog.DEFAULT_TIMEOUT_MS, onAnrDetected: (stackTrace: String, blockedMs: Long) -> Unit) {
        if (watchdog?.isAlive == true) {
            AbrovaTraceLogger.debug("ANR Watchdog already running")
            return
        }

        watchdog = ANRWatchdog(timeoutMs, onAnrDetected).also {
            it.start()
        }
    }

    /**
     * Stop ANR detection
     */
    fun stop() {
        watchdog?.shutdown()
        watchdog = null
    }

    /**
     * Check if watchdog is running
     */
    fun isRunning(): Boolean = watchdog?.isAlive == true
}
