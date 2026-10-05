package ir.abrova.trace.sdk.models

import java.text.SimpleDateFormat
import java.util.*

/**
 * Represents a performance span for APM tracking
 *
 * A span represents a unit of work or operation (HTTP request, DB query, etc.)
 * with timing information.
 */
data class PerformanceSpan(
    val operation: String,
    val operationType: String = "http",
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,

    // HTTP-specific fields
    val httpMethod: String? = null,
    val httpUrl: String? = null,
    val httpStatusCode: Int? = null,
    val httpHost: String? = null,

    // Timing
    val durationMs: Long,
    val startTime: Long,
    val endTime: Long = startTime + durationMs,

    // Context
    val platform: String = "android",
    val environment: String? = null,
    val releaseVersion: String? = null,

    // Status
    val status: String = "ok", // "ok", "error", "timeout"
    val errorMessage: String? = null,

    // Additional data
    val tags: Map<String, String> = emptyMap(),
    val metadata: Map<String, Any?> = emptyMap()
) {
    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Generate a random trace ID
         */
        fun generateTraceId(): String = UUID.randomUUID().toString().replace("-", "").take(32)

        /**
         * Generate a random span ID
         */
        fun generateSpanId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /**
         * Create a span from an HTTP request/response
         */
        fun fromHttpRequest(
            method: String,
            url: String,
            statusCode: Int?,
            durationMs: Long,
            startTime: Long,
            environment: String? = null,
            releaseVersion: String? = null,
            traceId: String? = null,
            tags: Map<String, String> = emptyMap()
        ): PerformanceSpan {
            // Extract host from URL
            val host = try {
                java.net.URL(url).host
            } catch (e: Exception) {
                null
            }

            // Determine status
            val status = when {
                statusCode == null -> "error"
                statusCode >= 500 -> "error"
                statusCode >= 400 -> "error"
                else -> "ok"
            }

            return PerformanceSpan(
                operation = "$method ${extractPath(url)}",
                operationType = "http",
                traceId = traceId ?: generateTraceId(),
                spanId = generateSpanId(),
                httpMethod = method,
                httpUrl = url,
                httpStatusCode = statusCode,
                httpHost = host,
                durationMs = durationMs,
                startTime = startTime,
                status = status,
                environment = environment,
                releaseVersion = releaseVersion,
                tags = tags
            )
        }

        /**
         * Create a span for a database query
         */
        fun forDbQuery(
            queryType: String,
            tableName: String,
            durationMs: Long,
            startTime: Long,
            rowsAffected: Int? = null,
            environment: String? = null,
            releaseVersion: String? = null,
            tags: Map<String, String> = emptyMap(),
            metadata: Map<String, Any?> = emptyMap()
        ): PerformanceSpan {
            val mergedMetadata = mutableMapOf<String, Any?>()
            mergedMetadata.putAll(metadata)
            if (rowsAffected != null) mergedMetadata["rows_affected"] = rowsAffected

            return PerformanceSpan(
                operation = "$queryType $tableName",
                operationType = "db",
                traceId = generateTraceId(),
                spanId = generateSpanId(),
                durationMs = durationMs,
                startTime = startTime,
                status = "ok",
                environment = environment,
                releaseVersion = releaseVersion,
                tags = tags + mapOf("db_table" to tableName, "query_type" to queryType),
                metadata = mergedMetadata
            )
        }

        /**
         * Create a custom span for any operation
         */
        fun custom(
            operation: String,
            durationMs: Long,
            startTime: Long,
            operationType: String = "custom",
            status: String = "ok",
            errorMessage: String? = null,
            environment: String? = null,
            releaseVersion: String? = null,
            tags: Map<String, String> = emptyMap(),
            metadata: Map<String, Any?> = emptyMap()
        ): PerformanceSpan {
            return PerformanceSpan(
                operation = operation,
                operationType = operationType,
                traceId = generateTraceId(),
                spanId = generateSpanId(),
                durationMs = durationMs,
                startTime = startTime,
                status = status,
                errorMessage = errorMessage,
                environment = environment,
                releaseVersion = releaseVersion,
                tags = tags,
                metadata = metadata
            )
        }

        private fun extractPath(url: String): String {
            return try {
                val parsed = java.net.URL(url)
                val path = parsed.path ?: "/"
                if (path.length > 50) path.take(50) + "..." else path
            } catch (e: Exception) {
                url.take(50)
            }
        }
    }

    /**
     * Convert to a map for JSON serialization
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "operation" to operation,
            "operation_type" to operationType,
            "trace_id" to traceId,
            "span_id" to spanId,
            "parent_span_id" to parentSpanId,
            "http_method" to httpMethod,
            "http_url" to httpUrl,
            "http_status_code" to httpStatusCode,
            "http_host" to httpHost,
            "duration_ms" to durationMs,
            "start_time" to dateFormat.format(Date(startTime)),
            "end_time" to dateFormat.format(Date(endTime)),
            "platform" to platform,
            "environment" to environment,
            "release_version" to releaseVersion,
            "status" to status,
            "error_message" to errorMessage,
            "tags" to tags.ifEmpty { null },
            "metadata" to metadata.ifEmpty { null }
        ).filterValues { it != null }
    }
}
