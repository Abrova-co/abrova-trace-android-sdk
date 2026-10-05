package ir.abrova.trace.sdk.models

import org.junit.Assert.*
import org.junit.Test

class AbrovaTraceErrorTest {

    // --- Construction ---

    @Test
    fun `error created with defaults`() {
        val error = AbrovaTraceError(message = "test error")
        assertEquals("test error", error.message)
        assertNull(error.stackTrace)
        assertEquals("android", error.platform)
        assertEquals("production", error.environment)
        assertNull(error.releaseVersion)
        assertTrue(error.timestamp > 0)
        assertNull(error.userAgent)
        assertTrue(error.breadcrumbs.isEmpty())
        assertTrue(error.extra.isEmpty())
        assertEquals("error", error.level)
        assertTrue(error.tags.isEmpty())
        assertNull(error.url)
    }

    // --- toMap ---

    @Test
    fun `toMap contains all non-null fields`() {
        val error = AbrovaTraceError(
            message = "test",
            stackTrace = "at com.example.Test",
            environment = "staging",
            releaseVersion = "1.0.0",
            userAgent = "TestAgent/1.0",
            extra = mapOf("key" to "value"),
            tags = mapOf("tag1" to "val1"),
            url = "MainActivity"
        )
        val map = error.toMap()

        assertEquals("test", map["message"])
        assertEquals("at com.example.Test", map["stack_trace"])
        assertEquals("android", map["platform"])
        assertEquals("staging", map["environment"])
        assertEquals("1.0.0", map["release_version"])
        assertEquals("TestAgent/1.0", map["user_agent"])
        assertEquals(mapOf("key" to "value"), map["extra"])
        assertEquals(mapOf("tag1" to "val1"), map["tags"])
        assertEquals("MainActivity", map["url"])
    }

    @Test
    fun `toMap filters null values`() {
        val error = AbrovaTraceError(message = "test")
        val map = error.toMap()

        assertFalse(map.containsKey("stack_trace"))
        assertFalse(map.containsKey("release_version"))
        assertFalse(map.containsKey("user_agent"))
        assertFalse(map.containsKey("url"))
        // Non-null fields are always present
        assertTrue(map.containsKey("message"))
        assertTrue(map.containsKey("platform"))
        assertTrue(map.containsKey("environment"))
        assertTrue(map.containsKey("timestamp"))
    }

    // --- fromThrowable ---

    @Test
    fun `fromThrowable uses throwable message when no message provided`() {
        val exception = RuntimeException("original message")
        val error = AbrovaTraceError.fromThrowable(exception)

        assertEquals("original message", error.message)
        assertNotNull(error.stackTrace)
        assertTrue(error.stackTrace!!.contains("RuntimeException"))
    }

    @Test
    fun `fromThrowable uses custom message when provided`() {
        val exception = RuntimeException("original")
        val error = AbrovaTraceError.fromThrowable(exception, message = "custom message")

        assertEquals("custom message", error.message)
    }

    @Test
    fun `fromThrowable uses class name when throwable has no message`() {
        val exception = RuntimeException()
        val error = AbrovaTraceError.fromThrowable(exception)

        assertEquals("RuntimeException", error.message)
    }

    @Test
    fun `fromThrowable captures stack trace`() {
        val exception = IllegalStateException("bad state")
        val error = AbrovaTraceError.fromThrowable(exception)

        assertNotNull(error.stackTrace)
        assertTrue(error.stackTrace!!.contains("IllegalStateException"))
        assertTrue(error.stackTrace!!.contains("bad state"))
    }

    @Test
    fun `fromThrowable includes exception info in extra`() {
        val exception = NullPointerException("null ref")
        val error = AbrovaTraceError.fromThrowable(exception)

        assertEquals("java.lang.NullPointerException", error.extra["exception_type"])
        assertEquals("null ref", error.extra["exception_message"])
    }

    @Test
    fun `fromThrowable passes through all parameters`() {
        val exception = RuntimeException("err")
        val breadcrumbs = listOf(Breadcrumb(message = "bc1"))
        val extra = mapOf("custom_key" to "custom_value")
        val tags = mapOf("env" to "test")

        val error = AbrovaTraceError.fromThrowable(
            throwable = exception,
            environment = "staging",
            releaseVersion = "2.0.0",
            userAgent = "TestAgent",
            breadcrumbs = breadcrumbs,
            extra = extra,
            tags = tags,
            url = "TestActivity"
        )

        assertEquals("staging", error.environment)
        assertEquals("2.0.0", error.releaseVersion)
        assertEquals("TestAgent", error.userAgent)
        assertEquals(1, error.breadcrumbs.size)
        assertTrue(error.extra.containsKey("custom_key"))
        assertTrue(error.extra.containsKey("exception_type"))
        assertEquals(tags, error.tags)
        assertEquals("TestActivity", error.url)
    }

    @Test
    fun `fromThrowable breadcrumbs are converted to maps`() {
        val breadcrumbs = listOf(
            Breadcrumb.navigation("Home", "Settings"),
            Breadcrumb.user("clicked button")
        )
        val error = AbrovaTraceError.fromThrowable(
            RuntimeException("err"),
            breadcrumbs = breadcrumbs
        )

        assertEquals(2, error.breadcrumbs.size)
        assertEquals("navigation", error.breadcrumbs[0]["type"])
        assertEquals("user", error.breadcrumbs[1]["type"])
    }

    // --- message factory ---

    @Test
    fun `message factory creates correct error`() {
        val error = AbrovaTraceError.message(
            message = "User logged in",
            level = MessageLevel.INFO
        )

        assertEquals("User logged in", error.message)
        assertNull(error.stackTrace)
        assertEquals("info", error.level)
    }

    @Test
    fun `message factory with all parameters`() {
        val error = AbrovaTraceError.message(
            message = "Warning message",
            level = MessageLevel.WARNING,
            environment = "staging",
            releaseVersion = "1.5.0",
            userAgent = "TestAgent",
            extra = mapOf("detail" to "more info"),
            tags = mapOf("team" to "backend"),
            url = "SettingsActivity"
        )

        assertEquals("Warning message", error.message)
        assertNull(error.stackTrace)
        assertEquals("warning", error.level)
        assertEquals("staging", error.environment)
        assertEquals("1.5.0", error.releaseVersion)
        assertEquals("TestAgent", error.userAgent)
        assertEquals(mapOf("detail" to "more info"), error.extra)
        assertEquals(mapOf("team" to "backend"), error.tags)
        assertEquals("SettingsActivity", error.url)
    }

    @Test
    fun `message factory defaults to INFO level`() {
        val error = AbrovaTraceError.message(message = "info message")
        assertEquals("info", error.level)
    }

    // --- nativeCrash factory ---

    @Test
    fun `nativeCrash creates correct error`() {
        val error = AbrovaTraceError.nativeCrash(
            crashInfo = "Signal 11 (SIGSEGV)",
            environment = "production",
            releaseVersion = "3.0.0",
            timeSinceCrashSeconds = 120L
        )

        assertEquals("Native crash detected from previous session", error.message)
        assertEquals("fatal", error.level)
        assertNotNull(error.stackTrace)
        assertTrue(error.stackTrace!!.contains("Signal 11 (SIGSEGV)"))
        assertEquals("native_crash", error.extra["error_type"])
        assertEquals(120L, error.extra["time_since_crash_seconds"])
    }

    @Test
    fun `nativeCrash filters null time since crash`() {
        val error = AbrovaTraceError.nativeCrash(
            crashInfo = "crash info",
            timeSinceCrashSeconds = null
        )
        assertFalse(error.extra.containsKey("time_since_crash_seconds"))
        assertTrue(error.extra.containsKey("error_type"))
    }

    // --- anr factory ---

    @Test
    fun `anr creates correct error`() {
        val stackTrace = "at android.os.Handler.dispatchMessage"
        val error = AbrovaTraceError.anr(
            stackTrace = stackTrace,
            environment = "production",
            releaseVersion = "1.0.0",
            anrDurationMs = 6000L
        )

        assertEquals("Application Not Responding (ANR)", error.message)
        assertEquals(stackTrace, error.stackTrace)
        assertEquals("error", error.level)
        assertEquals("anr", error.extra["error_type"])
        assertEquals(6000L, error.extra["anr_duration_ms"])
    }

    @Test
    fun `anr filters null duration`() {
        val error = AbrovaTraceError.anr(
            stackTrace = "stack",
            anrDurationMs = null
        )
        assertFalse(error.extra.containsKey("anr_duration_ms"))
        assertTrue(error.extra.containsKey("error_type"))
    }

    @Test
    fun `every error has its own event id and sends it`() {
        val first = AbrovaTraceError(message = "boom")
        val second = AbrovaTraceError(message = "boom")

        assertNotEquals(first.eventId, second.eventId)
        assertEquals(first.eventId, first.toMap()["event_id"])
        assertEquals(36, first.eventId.length)
    }
}
