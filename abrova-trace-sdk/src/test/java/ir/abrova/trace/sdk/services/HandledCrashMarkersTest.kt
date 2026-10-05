package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.services.HandledCrashMarkers.Marker
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HandledCrashMarkersTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun markers(max: Int = HandledCrashMarkers.MAX_MARKERS) = HandledCrashMarkers({ temp.root }, max)
    private fun file() = File(temp.root, HandledCrashMarkers.FILE_NAME)

    // Values of ApplicationExitInfo.REASON_*
    private val reasonCrash = 4
    private val reasonCrashNative = 5
    private val reasonAnr = 6

    private val crashTime = 1_700_000_000_000L

    // --- Writing and reading ---

    @Test
    fun `marker survives a new instance`() {
        assertTrue(markers().record(4321, crashTime))

        assertEquals(listOf(Marker(4321, crashTime)), markers().readAll())
    }

    @Test
    fun `markers are kept in order`() {
        val markers = markers()
        (1..5).forEach { markers.record(it, crashTime + it) }

        assertEquals((1..5).map { Marker(it, crashTime + it) }, markers.readAll())
    }

    @Test
    fun `nothing written means nothing read and no file`() {
        assertTrue(markers().readAll().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun `only the newest markers are kept`() {
        val markers = markers(max = 3)
        (1..10).forEach { markers.record(it, crashTime + it) }

        assertEquals(listOf(8, 9, 10), markers.readAll().map { it.pid })
        // The file itself was trimmed, and no temp file is left
        assertEquals(3, file().readLines().size)
        assertEquals(listOf(HandledCrashMarkers.FILE_NAME), temp.root.list()!!.toList())
    }

    @Test
    fun `unreadable lines are ignored and removed`() {
        file().writeText("garbage\n12 abc\n\n77 $crashTime\n1 2 3\n")

        assertEquals(listOf(Marker(77, crashTime)), markers().readAll())
        assertEquals("77 $crashTime\n", file().readText())
    }

    @Test
    fun `unavailable storage never throws`() {
        val broken = HandledCrashMarkers({ throw IllegalStateException("no storage") })
        assertFalse(broken.record(1, crashTime))
        assertTrue(broken.readAll().isEmpty())

        val none = HandledCrashMarkers({ null })
        assertFalse(none.record(1, crashTime))
        assertTrue(none.readAll().isEmpty())
    }

    // --- Matching exit records ---

    @Test
    fun `exit record of the marked process is covered`() {
        val handled = listOf(Marker(4321, crashTime))

        assertTrue(HandledCrashMarkers.covers(handled, 4321, crashTime))
        assertTrue(HandledCrashMarkers.covers(handled, 4321, crashTime + 40))
        // Crash dialog left open for a few minutes
        assertTrue(HandledCrashMarkers.covers(handled, 4321, crashTime + 5 * 60_000))
    }

    @Test
    fun `other process instances are not covered`() {
        val handled = listOf(Marker(4321, crashTime))

        assertFalse(HandledCrashMarkers.covers(handled, 4322, crashTime + 40))
        // Same pid, used again by a much later or much earlier process
        assertFalse(HandledCrashMarkers.covers(handled, 4321, crashTime + 60 * 60_000))
        assertFalse(HandledCrashMarkers.covers(handled, 4321, crashTime - 60 * 60_000))
        assertFalse(HandledCrashMarkers.covers(emptyList(), 4321, crashTime))
    }

    @Test
    fun `drain skips the JVM crash record of a handled crash`() {
        val handled = listOf(Marker(4321, crashTime))

        assertTrue(NativeCrashReporter.isAlreadyReported(reasonCrash, 4321, crashTime + 40, handled))
    }

    @Test
    fun `drain keeps JVM crash records that were not handled`() {
        val handled = listOf(Marker(4321, crashTime))

        assertFalse(NativeCrashReporter.isAlreadyReported(reasonCrash, 5000, crashTime + 40, handled))
        assertFalse(NativeCrashReporter.isAlreadyReported(reasonCrash, 4321, crashTime + 40, emptyList()))
    }

    @Test
    fun `drain never skips native crash or ANR records`() {
        val handled = listOf(Marker(4321, crashTime))

        assertFalse(NativeCrashReporter.isAlreadyReported(reasonCrashNative, 4321, crashTime + 40, handled))
        assertFalse(NativeCrashReporter.isAlreadyReported(reasonAnr, 4321, crashTime + 40, handled))
    }

    @Test
    fun `reason codes match the platform`() {
        assertEquals(android.app.ApplicationExitInfo.REASON_CRASH, reasonCrash)
        assertEquals(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, reasonCrashNative)
        assertEquals(android.app.ApplicationExitInfo.REASON_ANR, reasonAnr)
    }
}
