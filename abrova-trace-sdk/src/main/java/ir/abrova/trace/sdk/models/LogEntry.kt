package ir.abrova.trace.sdk.models

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Log level for AbrovaTrace logging
 */
enum class LogLevel(val value: String) {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    FATAL("fatal")
}

/**
 * A single log entry to be sent to AbrovaTrace
 */
data class LogEntry(
    val message: String,
    val level: LogLevel = LogLevel.INFO,
    val timestamp: Date = Date(),
    val metadata: Map<String, Any?>? = null,
    val userId: String? = null
) {
    fun toMap(): Map<String, Any?> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return buildMap {
            put("message", message)
            put("level", level.value)
            put("timestamp", dateFormat.format(timestamp))
            metadata?.let { put("metadata", it) }
            userId?.let { put("userId", it) }
        }
    }
}
