package ir.abrova.trace.sdk.network

import ir.abrova.trace.sdk.AbrovaTrace
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp Interceptor for automatic HTTP breadcrumb tracking
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(AbrovaTraceInterceptor())
 *     .build()
 * ```
 */
class AbrovaTraceInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        return try {
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime

            // Add successful HTTP breadcrumb
            AbrovaTrace.addHttpBreadcrumb(
                method = request.method(),
                url = sanitizeUrl(request.url().toString()),
                statusCode = response.code(),
                duration = duration
            )

            response
        } catch (e: IOException) {
            val duration = System.currentTimeMillis() - startTime

            // Add failed HTTP breadcrumb
            AbrovaTrace.addHttpBreadcrumb(
                method = request.method(),
                url = sanitizeUrl(request.url().toString()),
                statusCode = null,
                duration = duration
            )

            throw e
        }
    }

    /**
     * Sanitize URL to remove sensitive query parameters
     */
    private fun sanitizeUrl(url: String): String {
        // Remove common sensitive query params
        val sensitiveParams = listOf(
            "token", "api_key", "apikey", "key", "secret",
            "password", "pwd", "auth", "authorization",
            "access_token", "refresh_token", "session"
        )

        var sanitized = url
        for (param in sensitiveParams) {
            // Match param=value in query string
            sanitized = sanitized.replace(
                Regex("([?&])$param=[^&]*", RegexOption.IGNORE_CASE),
                "$1$param=[REDACTED]"
            )
        }
        return sanitized
    }
}

/**
 * OkHttp Interceptor that captures errors and sends them to AbrovaTrace
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(AbrovaTraceErrorInterceptor())
 *     .build()
 * ```
 */
class AbrovaTraceErrorInterceptor(
    private val captureClientErrors: Boolean = false, // 4xx errors
    private val captureServerErrors: Boolean = true   // 5xx errors
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        return try {
            val response = chain.proceed(request)

            // Capture server errors (5xx)
            if (captureServerErrors && response.code() >= 500) {
                AbrovaTrace.captureMessage(
                    message = "HTTP ${response.code()} Error: ${request.method()} ${request.url()}",
                    level = ir.abrova.trace.sdk.models.MessageLevel.ERROR,
                    extra = mapOf(
                        "http_method" to request.method(),
                        "http_url" to request.url().toString(),
                        "http_status" to response.code(),
                        "http_message" to response.message()
                    )
                )
            }

            // Capture client errors (4xx)
            if (captureClientErrors && response.code() >= 400 && response.code() < 500) {
                AbrovaTrace.captureMessage(
                    message = "HTTP ${response.code()} Error: ${request.method()} ${request.url()}",
                    level = ir.abrova.trace.sdk.models.MessageLevel.WARNING,
                    extra = mapOf(
                        "http_method" to request.method(),
                        "http_url" to request.url().toString(),
                        "http_status" to response.code(),
                        "http_message" to response.message()
                    )
                )
            }

            response
        } catch (e: IOException) {
            // Capture network errors
            AbrovaTrace.captureException(
                throwable = e,
                message = "Network Error: ${request.method()} ${request.url()}",
                extra = mapOf(
                    "http_method" to request.method(),
                    "http_url" to request.url().toString()
                )
            )
            throw e
        }
    }
}

/**
 * OkHttp Interceptor for APM performance tracking
 *
 * This interceptor captures HTTP request timing and sends it to AbrovaTrace APM.
 * It tracks latency, status codes, and errors for all HTTP requests.
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(AbrovaTracePerformanceInterceptor())
 *     .build()
 * ```
 *
 * Note: APM requires a paid plan (Starter or higher).
 * Free plan users can still use this interceptor, but spans won't be stored.
 */
class AbrovaTracePerformanceInterceptor(
    private val minDurationMs: Long = 0 // Only report spans longer than this duration
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        return try {
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime

            // Only report if duration exceeds minimum threshold
            if (duration >= minDurationMs) {
                AbrovaTrace.reportPerformanceSpan(
                    method = request.method(),
                    url = sanitizeUrl(request.url().toString()),
                    statusCode = response.code(),
                    durationMs = duration,
                    startTime = startTime
                )
            }

            response
        } catch (e: IOException) {
            val duration = System.currentTimeMillis() - startTime

            // Report failed request
            AbrovaTrace.reportPerformanceSpan(
                method = request.method(),
                url = sanitizeUrl(request.url().toString()),
                statusCode = null,
                durationMs = duration,
                startTime = startTime,
                errorMessage = e.message
            )

            throw e
        }
    }

    /**
     * Sanitize URL to remove sensitive query parameters
     */
    private fun sanitizeUrl(url: String): String {
        val sensitiveParams = listOf(
            "token", "api_key", "apikey", "key", "secret",
            "password", "pwd", "auth", "authorization",
            "access_token", "refresh_token", "session"
        )

        var sanitized = url
        for (param in sensitiveParams) {
            sanitized = sanitized.replace(
                Regex("([?&])$param=[^&]*", RegexOption.IGNORE_CASE),
                "$1$param=[REDACTED]"
            )
        }
        return sanitized
    }
}
