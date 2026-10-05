package ir.abrova.trace.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Represents an error to be sent to AbrovaTrace
 */
data class AbrovaTraceError(
    val message: String,

    @SerializedName("stack_trace")
    val stackTrace: String? = null,

    /**
     * Optional pre-resolved, structured stack trace. When set, the dashboard
     * renders this as Sentry-style frames instead of the plain-text
     * [stackTrace]. Native crashes populate this with a parsed tombstone JSON;
     * plain Kotlin/Java errors leave it null.
     */
    @SerializedName("resolved_stack_trace")
    val resolvedStackTrace: String? = null,

    val platform: String = "android",

    val environment: String = "production",

    @SerializedName("release_version")
    val releaseVersion: String? = null,

    val timestamp: Long = Date().time,

    @SerializedName("user_agent")
    val userAgent: String? = null,

    val breadcrumbs: List<Map<String, Any?>> = emptyList(),

    val extra: Map<String, Any?> = emptyMap(),

    val level: String = MessageLevel.ERROR.value,

    val tags: Map<String, String> = emptyMap(),

    val url: String? = null
) {
    /**
     * Convert to map for JSON serialization
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "message" to message,
            "stack_trace" to stackTrace,
            "resolved_stack_trace" to resolvedStackTrace,
            "platform" to platform,
            "environment" to environment,
            "release_version" to releaseVersion,
            "timestamp" to timestamp,
            "user_agent" to userAgent,
            "breadcrumbs" to breadcrumbs,
            "extra" to extra,
            "level" to level,
            "tags" to tags,
            "url" to url
        ).filterValues { it != null }
    }

    companion object {
        /**
         * Create from a Throwable
         */
        fun fromThrowable(
            throwable: Throwable,
            message: String? = null,
            environment: String = "production",
            releaseVersion: String? = null,
            userAgent: String? = null,
            breadcrumbs: List<Breadcrumb> = emptyList(),
            extra: Map<String, Any?> = emptyMap(),
            tags: Map<String, String> = emptyMap(),
            url: String? = null
        ): AbrovaTraceError {
            val errorMessage = message ?: throwable.message ?: throwable.javaClass.simpleName
            val stackTrace = throwable.stackTraceToString()

            return AbrovaTraceError(
                message = errorMessage,
                stackTrace = stackTrace,
                environment = environment,
                releaseVersion = releaseVersion,
                userAgent = userAgent,
                breadcrumbs = breadcrumbs.map { it.toMap() },
                extra = extra + mapOf(
                    "exception_type" to throwable.javaClass.name,
                    "exception_message" to throwable.message
                ),
                tags = tags,
                url = url
            )
        }

        /**
         * Create a message (non-exception) error
         */
        fun message(
            message: String,
            level: MessageLevel = MessageLevel.INFO,
            environment: String = "production",
            releaseVersion: String? = null,
            userAgent: String? = null,
            breadcrumbs: List<Breadcrumb> = emptyList(),
            extra: Map<String, Any?> = emptyMap(),
            tags: Map<String, String> = emptyMap(),
            url: String? = null
        ): AbrovaTraceError {
            return AbrovaTraceError(
                message = message,
                stackTrace = null,
                level = level.value,
                environment = environment,
                releaseVersion = releaseVersion,
                userAgent = userAgent,
                breadcrumbs = breadcrumbs.map { it.toMap() },
                extra = extra,
                tags = tags,
                url = url
            )
        }

        /**
         * Create a native crash error
         */
        fun nativeCrash(
            crashInfo: String,
            environment: String = "production",
            releaseVersion: String? = null,
            userAgent: String? = null,
            timeSinceCrashSeconds: Long? = null
        ): AbrovaTraceError {
            return AbrovaTraceError(
                message = "Native crash detected from previous session",
                stackTrace = "Native crash - No Java stack trace available.\n\nCrash detected via crash marker file.\n\n$crashInfo",
                level = MessageLevel.FATAL.value,
                environment = environment,
                releaseVersion = releaseVersion,
                userAgent = userAgent,
                extra = mapOf(
                    "error_type" to "native_crash",
                    "time_since_crash_seconds" to timeSinceCrashSeconds,
                    "crash_info" to crashInfo
                ).filterValues { it != null }
            )
        }

        /**
         * Create an ANR (Application Not Responding) error
         */
        fun anr(
            stackTrace: String,
            environment: String = "production",
            releaseVersion: String? = null,
            userAgent: String? = null,
            anrDurationMs: Long? = null
        ): AbrovaTraceError {
            return AbrovaTraceError(
                message = "Application Not Responding (ANR)",
                stackTrace = stackTrace,
                level = MessageLevel.ERROR.value,
                environment = environment,
                releaseVersion = releaseVersion,
                userAgent = userAgent,
                extra = mapOf(
                    "error_type" to "anr",
                    "anr_duration_ms" to anrDurationMs
                ).filterValues { it != null }
            )
        }
    }
}

/**
 * Extension function to convert Throwable to string
 */
private fun Throwable.stackTraceToString(): String {
    val sb = StringBuilder()
    sb.append(this.toString())
    sb.append("\n")
    for (element in this.stackTrace) {
        sb.append("\tat ")
        sb.append(element.toString())
        sb.append("\n")
    }
    this.cause?.let { cause ->
        sb.append("Caused by: ")
        sb.append(cause.stackTraceToString())
    }
    return sb.toString()
}
