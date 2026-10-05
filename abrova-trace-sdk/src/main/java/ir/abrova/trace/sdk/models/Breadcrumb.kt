package ir.abrova.trace.sdk.models

import com.google.gson.annotations.SerializedName
import java.util.Date

/**
 * Type of breadcrumb
 */
enum class BreadcrumbType(val value: String) {
    @SerializedName("navigation") NAVIGATION("navigation"),
    @SerializedName("user") USER("user"),
    @SerializedName("http") HTTP("http"),
    @SerializedName("state") STATE("state"),
    @SerializedName("info") INFO("info"),
    @SerializedName("error") ERROR("error"),
    @SerializedName("system") SYSTEM("system");

    companion object {
        fun fromString(value: String): BreadcrumbType {
            return values().find { it.value.equals(value, ignoreCase = true) } ?: INFO
        }
    }
}

/**
 * Breadcrumb - represents a single event in the user's journey
 */
data class Breadcrumb(
    val message: String,
    val type: BreadcrumbType = BreadcrumbType.INFO,
    val timestamp: Date = Date(),
    val data: Map<String, Any?> = emptyMap()
) {
    /**
     * Convert to map for JSON serialization
     */
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "message" to message,
            "type" to type.value,
            "timestamp" to timestamp.time,
            "data" to data
        )
    }

    companion object {
        /**
         * Create a navigation breadcrumb
         */
        fun navigation(from: String?, to: String): Breadcrumb {
            val message = if (from != null) "Navigation: $from -> $to" else "Navigation: -> $to"
            return Breadcrumb(
                message = message,
                type = BreadcrumbType.NAVIGATION,
                data = mapOf("from" to from, "to" to to)
            )
        }

        /**
         * Create a user action breadcrumb
         */
        fun user(action: String, data: Map<String, Any?> = emptyMap()): Breadcrumb {
            return Breadcrumb(
                message = action,
                type = BreadcrumbType.USER,
                data = data
            )
        }

        /**
         * Create an HTTP request breadcrumb
         */
        fun http(method: String, url: String, statusCode: Int? = null, duration: Long? = null): Breadcrumb {
            val message = if (statusCode != null) {
                "HTTP $method $url ($statusCode)"
            } else {
                "HTTP $method $url"
            }
            return Breadcrumb(
                message = message,
                type = BreadcrumbType.HTTP,
                data = mapOf(
                    "method" to method,
                    "url" to url,
                    "status_code" to statusCode,
                    "duration_ms" to duration
                ).filterValues { it != null }
            )
        }

        /**
         * Create a state change breadcrumb
         */
        fun state(message: String, data: Map<String, Any?> = emptyMap()): Breadcrumb {
            return Breadcrumb(
                message = message,
                type = BreadcrumbType.STATE,
                data = data
            )
        }

        /**
         * Create a system breadcrumb
         */
        fun system(message: String, data: Map<String, Any?> = emptyMap()): Breadcrumb {
            return Breadcrumb(
                message = message,
                type = BreadcrumbType.SYSTEM,
                data = data
            )
        }

        /**
         * Create an error breadcrumb
         */
        fun error(message: String, data: Map<String, Any?> = emptyMap()): Breadcrumb {
            return Breadcrumb(
                message = message,
                type = BreadcrumbType.ERROR,
                data = data
            )
        }
    }
}
