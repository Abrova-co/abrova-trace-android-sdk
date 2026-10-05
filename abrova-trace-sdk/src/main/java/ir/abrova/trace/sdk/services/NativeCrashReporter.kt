package ir.abrova.trace.sdk.services

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import ir.abrova.trace.sdk.models.MessageLevel
import ir.abrova.trace.sdk.models.AbrovaTraceError
import ir.abrova.trace.sdk.utils.DeviceInfo
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.io.ByteArrayOutputStream

/**
 * Native crash reporting backed by Android's [ApplicationExitInfo] (API 30+).
 *
 * On each launch this class queries [ActivityManager.getHistoricalProcessExitReasons]
 * and converts any crash/ANR/native-crash exit records since the previous
 * launch into [AbrovaTraceError] payloads. The timestamp of the most recent
 * processed record is persisted in [SharedPreferences] so the same crash is
 * not reported twice.
 *
 * Why this approach:
 * - On API 30+ the OS records exit reasons (REASON_CRASH, REASON_CRASH_NATIVE,
 *   REASON_ANR) with stack traces collected by the platform itself. This is
 *   strictly more reliable than any in-process signal handler an app could
 *   install, because it survives the very signals that terminate the process.
 * - JVM/Kotlin crashes are also captured by [AbrovaTrace]'s in-process
 *   `Thread.setDefaultUncaughtExceptionHandler` for immediate (same-session)
 *   delivery. This class is the cold-start safety net: a REASON_CRASH record
 *   is reported from here only when that handler did not report the crash
 *   itself (see [HandledCrashMarkers]).
 *
 * On API 29 and below, the historical API is unavailable and this class
 * silently does nothing. JVM crashes are still captured by the in-process
 * uncaught handler.
 */
internal class NativeCrashReporter(
    private val context: Context,
    private val handledCrashes: HandledCrashMarkers? = null
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class PendingCrash(val timestamp: Long, val error: AbrovaTraceError)

    fun drainPendingCrashReports(
        environment: String,
        releaseVersion: String?,
        userAgent: String?
    ): List<PendingCrash> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AbrovaTraceLogger.debug("ApplicationExitInfo unavailable on API ${Build.VERSION.SDK_INT}")
            return emptyList()
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()

        val sinceTimestamp = prefs.getLong(KEY_LAST_PROCESSED_TIMESTAMP, 0L)
        val infos: List<ApplicationExitInfo> = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS_TO_FETCH)
        } catch (e: Exception) {
            AbrovaTraceLogger.error("getHistoricalProcessExitReasons failed: ${e.message}")
            return emptyList()
        }

        val pending = mutableListOf<PendingCrash>()
        val handled = handledCrashes?.readAll() ?: emptyList()

        for (info in infos) {
            if (info.timestamp <= sinceTimestamp) continue
            val reasonKey = exitReasonKey(info.reason) ?: continue
            if (isAlreadyReported(info.reason, info.pid, info.timestamp, handled)) {
                AbrovaTraceLogger.debug(
                    "Exit record of pid ${info.pid} skipped: the crash was reported when it happened"
                )
                continue
            }
            pending += PendingCrash(
                timestamp = info.timestamp,
                error = buildError(info, reasonKey, environment, releaseVersion, userAgent)
            )
        }

        // Send oldest first so failure of one doesn't drop older records.
        return pending.sortedBy { it.timestamp }
    }

    /**
     * Persist that a crash with the given timestamp has been sent successfully.
     * Callers MUST invoke this only after the network send succeeded, so that a
     * failed send is retried on the next launch instead of being silently lost.
     */
    fun markSent(timestamp: Long) {
        val current = prefs.getLong(KEY_LAST_PROCESSED_TIMESTAMP, 0L)
        if (timestamp > current) {
            prefs.edit { putLong(KEY_LAST_PROCESSED_TIMESTAMP, timestamp) }
        }
    }

    private fun exitReasonKey(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash_jvm"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        else -> null
    }

    private fun buildError(
        info: ApplicationExitInfo,
        reasonKey: String,
        environment: String,
        releaseVersion: String?,
        userAgent: String?
    ): AbrovaTraceError {
        // ApplicationExitInfo#getTraceInputStream returns:
        //   - REASON_CRASH_NATIVE  -> binary Tombstone protobuf (API 30+)
        //   - REASON_ANR           -> plain-text thread dump
        //   - REASON_CRASH (JVM)   -> null (JVM crashes handled in-process)
        val rawBytes = readTraceBytes(info)

        val description = stripNulls(info.description ?: "")
        val processName = stripNulls(info.processName ?: "")
        val reasonName = reasonHumanName(info.reason)

        // Try to parse as a tombstone protobuf when the exit reason says so.
        // If parsing succeeds we get both a Sentry-shape JSON for structured
        // rendering AND a debuggerd-style text fallback for consumers that
        // don't understand the JSON.
        // Only attempt protobuf parse if we have the full stream. A truncated
        // tombstone is unparseable ("input ended unexpectedly in the middle
        // of a field"), and a partial parse would silently drop threads.
        val parsed: TombstoneParser.ParsedTombstone? =
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE &&
                rawBytes.bytes.isNotEmpty() && !rawBytes.truncated) {
                TombstoneParser.parse(rawBytes.bytes)
            } else null

        if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && rawBytes.truncated) {
            AbrovaTraceLogger.warn(
                "Tombstone truncated at ${MAX_TRACE_BYTES} bytes; falling back to text summary. " +
                    "Raise MAX_TRACE_BYTES if you need full structured parsing."
            )
        }

        val stackText: String
        val resolved: String?
        val messageDetail: String
        if (parsed != null) {
            stackText = parsed.textFallback
            resolved = parsed.structuredJson
            messageDetail = parsed.summary
        } else {
            // Fallback: treat bytes as UTF-8 text (ANR path or non-parseable).
            stackText = stripNulls(String(rawBytes.bytes, Charsets.UTF_8))
                .ifBlank { "No tombstone trace available from ApplicationExitInfo." }
            resolved = null
            messageDetail = if (description.isNotBlank()) " ($description)" else ""
        }

        val extra = mutableMapOf<String, Any?>(
            "error_type" to "native_crash",
            "exit_reason" to reasonKey,
            "exit_reason_code" to info.reason,
            "exit_reason_name" to reasonName,
            "exit_description" to description,
            "process_name" to processName,
            "pid" to info.pid,
            "importance" to info.importance,
            "rss_kb" to info.rss,
            "pss_kb" to info.pss,
            "crash_reporter" to "application_exit_info",
            "tombstone_parsed" to (parsed != null),
            "device_info" to DeviceInfo.getDeviceInfo()
        )

        val message = if (parsed != null) {
            "Native crash: $messageDetail"
        } else {
            "Native crash: $reasonName$messageDetail"
        }

        return AbrovaTraceError(
            message = message,
            stackTrace = stackText,
            resolvedStackTrace = resolved,
            environment = environment,
            releaseVersion = releaseVersion,
            timestamp = info.timestamp,
            userAgent = userAgent,
            extra = extra,
            level = MessageLevel.FATAL.value
        )
    }

    private data class TraceBytes(val bytes: ByteArray, val truncated: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is TraceBytes && bytes.contentEquals(other.bytes) && truncated == other.truncated
        override fun hashCode(): Int = 31 * bytes.contentHashCode() + truncated.hashCode()
    }

    private fun readTraceBytes(info: ApplicationExitInfo): TraceBytes {
        val stream = try {
            info.traceInputStream
        } catch (e: Exception) {
            AbrovaTraceLogger.debug("traceInputStream threw: ${e.message}")
            null
        } ?: return TraceBytes(ByteArray(0), false)

        return try {
            stream.use {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var truncated = false
                while (true) {
                    val n = it.read(buf)
                    if (n <= 0) break
                    if (out.size() + n > MAX_TRACE_BYTES) {
                        out.write(buf, 0, (MAX_TRACE_BYTES - out.size()).coerceAtLeast(0))
                        truncated = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                TraceBytes(out.toByteArray(), truncated)
            }
        } catch (e: Exception) {
            AbrovaTraceLogger.debug("Reading trace stream failed: ${e.message}")
            TraceBytes(ByteArray(0), false)
        }
    }

    // Tombstone / ANR streams and some ApplicationExitInfo fields contain NUL
    // bytes copied from ELF module names and other binary structures. Postgres
    // text columns reject NULs with "invalid byte sequence for encoding UTF8:
    // 0x00" (SQLSTATE 22021), which surfaces to the SDK as an HTTP 500 and
    // drops the crash report. Strip them at the source.
    private fun stripNulls(s: String): String =
        if (s.indexOf('\u0000') < 0) s else s.replace("\u0000", "")

    private fun reasonHumanName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JVM crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
        ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
        ApplicationExitInfo.REASON_UNKNOWN -> "Unknown"
        else -> "Reason($reason)"
    }

    companion object {
        /**
         * True for the exit record of a JVM crash that the uncaught exception
         * handler already sent or stored. Native crash and ANR records are
         * never matched: nothing else reports those.
         */
        internal fun isAlreadyReported(
            reason: Int,
            pid: Int,
            timestamp: Long,
            handled: List<HandledCrashMarkers.Marker>
        ): Boolean {
            return reason == ApplicationExitInfo.REASON_CRASH &&
                HandledCrashMarkers.covers(handled, pid, timestamp)
        }

        private const val PREFS_NAME = "abrova_trace_native_crash"
        private const val KEY_LAST_PROCESSED_TIMESTAMP = "last_processed_timestamp"
        private const val MAX_RECORDS_TO_FETCH = 20
        // 512KB — enough headroom to hold a full Android 13+ tombstone
        // protobuf on high-thread-count apps (~200-400KB observed on a
        // Samsung A71 with ~60 threads). Tombstones are one-shot, per-crash,
        // and drained in a background thread — memory footprint is trivial.
        private const val MAX_TRACE_BYTES = 512 * 1024
    }
}
