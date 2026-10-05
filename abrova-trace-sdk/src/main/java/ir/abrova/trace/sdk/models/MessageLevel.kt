package ir.abrova.trace.sdk.models

/**
 * Severity level for messages and errors
 */
enum class MessageLevel(val value: String) {
    DEBUG("debug"),
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    FATAL("fatal");

    companion object {
        fun fromString(value: String): MessageLevel {
            return values().find { it.value.equals(value, ignoreCase = true) } ?: INFO
        }
    }
}
