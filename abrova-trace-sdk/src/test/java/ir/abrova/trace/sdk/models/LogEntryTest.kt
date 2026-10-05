package ir.abrova.trace.sdk.models

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LogEntryTest {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // --- Construction ---

    @Test
    fun `log entry created with defaults`() {
        val entry = LogEntry(message = "test log")
        assertEquals("test log", entry.message)
        assertEquals(LogLevel.INFO, entry.level)
        assertNotNull(entry.timestamp)
        assertNull(entry.metadata)
        assertNull(entry.userId)
    }

    @Test
    fun `log entry created with all fields`() {
        val timestamp = Date()
        val metadata = mapOf("key" to "value")
        val entry = LogEntry(
            message = "detailed log",
            level = LogLevel.ERROR,
            timestamp = timestamp,
            metadata = metadata,
            userId = "user123"
        )
        assertEquals("detailed log", entry.message)
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals(timestamp, entry.timestamp)
        assertEquals(metadata, entry.metadata)
        assertEquals("user123", entry.userId)
    }

    // --- toMap ---

    @Test
    fun `toMap includes required fields`() {
        val timestamp = Date()
        val entry = LogEntry(message = "test", level = LogLevel.WARN, timestamp = timestamp)
        val map = entry.toMap()

        assertEquals("test", map["message"])
        assertEquals("warn", map["level"])
        assertEquals(dateFormat.format(timestamp), map["timestamp"])
    }

    @Test
    fun `toMap excludes null metadata`() {
        val entry = LogEntry(message = "test")
        val map = entry.toMap()
        assertFalse(map.containsKey("metadata"))
    }

    @Test
    fun `toMap includes metadata when present`() {
        val metadata = mapOf("source" to "unit-test")
        val entry = LogEntry(message = "test", metadata = metadata)
        val map = entry.toMap()
        assertEquals(metadata, map["metadata"])
    }

    @Test
    fun `toMap excludes null userId`() {
        val entry = LogEntry(message = "test")
        val map = entry.toMap()
        assertFalse(map.containsKey("userId"))
    }

    @Test
    fun `toMap includes userId when present`() {
        val entry = LogEntry(message = "test", userId = "user_42")
        val map = entry.toMap()
        assertEquals("user_42", map["userId"])
    }

    @Test
    fun `toMap timestamp is ISO 8601 UTC format`() {
        val entry = LogEntry(message = "test")
        val map = entry.toMap()
        val ts = map["timestamp"] as String
        // Verify format: yyyy-MM-ddTHH:mm:ss.SSSZ
        assertTrue(ts.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }

    // --- LogLevel ---

    @Test
    fun `all log levels have correct values`() {
        assertEquals("trace", LogLevel.TRACE.value)
        assertEquals("debug", LogLevel.DEBUG.value)
        assertEquals("info", LogLevel.INFO.value)
        assertEquals("warn", LogLevel.WARN.value)
        assertEquals("error", LogLevel.ERROR.value)
        assertEquals("fatal", LogLevel.FATAL.value)
    }

    @Test
    fun `log level enum has 6 values`() {
        assertEquals(6, LogLevel.values().size)
    }

    // --- Data Class ---

    @Test
    fun `log entries with same values are equal`() {
        val ts = Date(1000000)
        val e1 = LogEntry("msg", LogLevel.INFO, ts, null, null)
        val e2 = LogEntry("msg", LogLevel.INFO, ts, null, null)
        assertEquals(e1, e2)
    }

    @Test
    fun `log entries with different messages are not equal`() {
        val ts = Date(1000000)
        val e1 = LogEntry("msg1", LogLevel.INFO, ts)
        val e2 = LogEntry("msg2", LogLevel.INFO, ts)
        assertNotEquals(e1, e2)
    }
}
