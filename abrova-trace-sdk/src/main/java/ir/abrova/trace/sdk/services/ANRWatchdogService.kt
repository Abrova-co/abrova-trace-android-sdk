package ir.abrova.trace.sdk.services

import android.os.Handler
import android.os.Looper
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR (Application Not Responding) Watchdog
 * Detects when the main thread is blocked for too long
 */
class ANRWatchdog(
    private val timeoutMs: Long = 5000L,
    private val onAnrDetected: (String) -> Unit
) : Thread("AbrovaTrace-ANR-Watchdog") {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tick = AtomicLong(0)
    private val reported = AtomicBoolean(false)

    @Volatile
    private var running = true

    init {
        isDaemon = true
    }

    override fun run() {
        AbrovaTraceLogger.debug("ANR Watchdog started with timeout: ${timeoutMs}ms")

        while (running && !isInterrupted) {
            try {
                // Reset the tick counter
                reported.set(false)
                tick.set(System.currentTimeMillis())

                // Post a runnable to main thread
                mainHandler.post {
                    tick.set(0)
                }

                // Wait for timeout period
                sleep(timeoutMs)

                // Check if main thread responded
                val tickValue = tick.get()
                if (tickValue != 0L && running) {
                    val anrDuration = System.currentTimeMillis() - tickValue
                    if (anrDuration >= timeoutMs && !reported.getAndSet(true)) {
                        AbrovaTraceLogger.warn("ANR detected! Main thread blocked for ${anrDuration}ms")

                        // Capture main thread stack trace
                        val mainThread = Looper.getMainLooper().thread
                        val stackTrace = mainThread.stackTrace
                        val stackTraceString = formatStackTrace(mainThread, stackTrace)

                        onAnrDetected(stackTraceString)
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
    fun start(timeoutMs: Long = ANRWatchdog.DEFAULT_TIMEOUT_MS, onAnrDetected: (String) -> Unit) {
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
