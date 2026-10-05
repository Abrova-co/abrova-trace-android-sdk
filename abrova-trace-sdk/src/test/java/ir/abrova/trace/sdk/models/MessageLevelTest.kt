package ir.abrova.trace.sdk.models

import org.junit.Assert.*
import org.junit.Test

class MessageLevelTest {

    @Test
    fun `enum values have correct string values`() {
        assertEquals("debug", MessageLevel.DEBUG.value)
        assertEquals("info", MessageLevel.INFO.value)
        assertEquals("warning", MessageLevel.WARNING.value)
        assertEquals("error", MessageLevel.ERROR.value)
        assertEquals("fatal", MessageLevel.FATAL.value)
    }

    @Test
    fun `fromString returns correct level`() {
        assertEquals(MessageLevel.DEBUG, MessageLevel.fromString("debug"))
        assertEquals(MessageLevel.INFO, MessageLevel.fromString("info"))
        assertEquals(MessageLevel.WARNING, MessageLevel.fromString("warning"))
        assertEquals(MessageLevel.ERROR, MessageLevel.fromString("error"))
        assertEquals(MessageLevel.FATAL, MessageLevel.fromString("fatal"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(MessageLevel.DEBUG, MessageLevel.fromString("DEBUG"))
        assertEquals(MessageLevel.WARNING, MessageLevel.fromString("Warning"))
        assertEquals(MessageLevel.FATAL, MessageLevel.fromString("FATAL"))
    }

    @Test
    fun `fromString defaults to INFO for unknown values`() {
        assertEquals(MessageLevel.INFO, MessageLevel.fromString("unknown"))
        assertEquals(MessageLevel.INFO, MessageLevel.fromString(""))
        assertEquals(MessageLevel.INFO, MessageLevel.fromString("warn"))
    }

    @Test
    fun `all enum values are present`() {
        val values = MessageLevel.values()
        assertEquals(5, values.size)
        assertTrue(values.contains(MessageLevel.DEBUG))
        assertTrue(values.contains(MessageLevel.INFO))
        assertTrue(values.contains(MessageLevel.WARNING))
        assertTrue(values.contains(MessageLevel.ERROR))
        assertTrue(values.contains(MessageLevel.FATAL))
    }
}
