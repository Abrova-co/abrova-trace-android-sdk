package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.AbrovaTraceConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OfflineErrorStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(): File = temp.root
    private fun dataFile(): File = File(dir(), OfflineErrorStore.FILE_NAME)
    private fun store(max: Int = OfflineErrorStore.MAX_STORED_ERRORS) = OfflineErrorStore({ dir() }, max)
    private fun bodies(store: OfflineErrorStore) = store.readAll().map { it.body }

    // --- Storing ---

    @Test
    fun `stores the exact body`() {
        val body = """{"message":"boom","extra":{"n":1.0,"s":"a\"b\\c <tag> é"},"timestamp":1700000000000}"""
        val store = store()

        assertTrue(store.store(body))

        assertEquals(listOf(body), bodies(store))
        // Survives a new instance (next app launch)
        assertEquals(listOf(body), bodies(store()))
    }

    @Test
    fun `keeps insertion order`() {
        val store = store()
        (1..5).forEach { store.store("""{"n":$it}""") }
        assertEquals((1..5).map { """{"n":$it}""" }, bodies(store))
    }

    @Test
    fun `cap of 100 drops the oldest`() {
        val store = store()
        (1..105).forEach { store.store("""{"n":$it}""") }

        val stored = bodies(store)
        assertEquals(100, stored.size)
        assertEquals("""{"n":6}""", stored.first())
        assertEquals("""{"n":105}""", stored.last())
    }

    @Test
    fun `writes leave no temp file behind`() {
        val store = store()
        store.store("""{"n":1}""")
        store.store("""{"n":2}""")

        assertTrue(dataFile().exists())
        assertFalse(File(dir(), OfflineErrorStore.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `concurrent writers do not corrupt the file`() {
        val store = store()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                start.await()
                repeat(10) { i -> store.store("""{"t":$t,"i":$i}""") }
            }
        }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val stored = bodies(store)
        assertEquals(80, stored.size)
        assertEquals(80, stored.toSet().size)
    }

    @Test
    fun `oversized body is not stored`() {
        val store = store()
        assertFalse(store.store("x".repeat(600 * 1024)))
        assertFalse(dataFile().exists())
    }

    @Test
    fun `unavailable directory never throws`() {
        val none = OfflineErrorStore({ null })
        assertFalse(none.store("{}"))
        assertTrue(none.readAll().isEmpty())
        assertEquals(0, none.flush { SendOutcome.Http(200) })

        val throwing = OfflineErrorStore({ throw SecurityException("no") })
        assertFalse(throwing.store("{}"))
        assertEquals(0, throwing.flush { SendOutcome.Http(200) })
    }

    // --- Corrupt file ---

    @Test
    fun `corrupt file is treated as empty and overwritten`() {
        dataFile().writeText("""[{"id":"a","body":"{\"n\":1}"},{"id":"b","bo""")
        val store = store()

        assertTrue(store.readAll().isEmpty())

        assertTrue(store.store("""{"n":2}"""))
        assertEquals(listOf("""{"n":2}"""), bodies(store))
    }

    @Test
    fun `non-array and garbage content is treated as empty`() {
        for (content in listOf("not json at all", """{"id":"a"}""", "\u0000\u0001\u0002", "")) {
            dataFile().writeText(content)
            val store = store()
            assertTrue("content=$content", store.readAll().isEmpty())
            assertEquals(0, store.flush { fail("nothing to send"); SendOutcome.Http(200) })
        }
    }

    @Test
    fun `malformed entries are skipped`() {
        dataFile().writeText("""[{"id":"a","body":"{\"n\":1}"},42,{"id":"b"},{"body":"x"},{"id":"c","body":"{\"n\":3}"}]""")
        assertEquals(listOf("""{"n":1}""", """{"n":3}"""), bodies(store()))
    }

    // --- Resending ---

    @Test
    fun `resend removes on 2xx`() {
        val store = store()
        (1..3).forEach { store.store("""{"n":$it}""") }
        val sent = mutableListOf<String>()

        val removed = store.flush { sent += it; SendOutcome.Http(201) }

        assertEquals(3, removed)
        assertEquals((1..3).map { """{"n":$it}""" }, sent)
        assertTrue(store.readAll().isEmpty())
        assertFalse(dataFile().exists())
        assertFalse(store.mayHavePending)
    }

    @Test
    fun `resend removes on a 4xx rejection`() {
        for (code in listOf(400, 401, 403, 404, 409, 413, 422)) {
            val store = store()
            store.store("""{"n":1}""")
            assertEquals("code=$code", 1, store.flush { SendOutcome.Http(code) })
            assertTrue("code=$code", store.readAll().isEmpty())
        }
    }

    @Test
    fun `resend keeps on 408 and 429`() {
        val store = store()
        store.store("""{"n":1}""")
        for (code in listOf(408, 429)) {
            assertEquals("code=$code", 0, store.flush { SendOutcome.Http(code) })
            assertEquals("code=$code", 1, store.readAll().size)
        }
    }

    @Test
    fun `resend keeps on 5xx and carries on with the rest`() {
        val store = store()
        (1..3).forEach { store.store("""{"n":$it}""") }
        val sent = mutableListOf<String>()

        val removed = store.flush {
            sent += it
            if (it == """{"n":2}""") SendOutcome.Http(503) else SendOutcome.Http(200)
        }

        assertEquals(2, removed)
        assertEquals(3, sent.size)
        assertEquals(listOf("""{"n":2}"""), bodies(store))
        assertTrue(store.mayHavePending)
    }

    @Test
    fun `resend keeps on network failure and stops the pass`() {
        val store = store()
        (1..4).forEach { store.store("""{"n":$it}""") }
        val sent = mutableListOf<String>()

        val removed = store.flush {
            sent += it
            if (it == """{"n":2}""") SendOutcome.NetworkFailure else SendOutcome.Http(200)
        }

        assertEquals(1, removed)
        assertEquals(listOf("""{"n":1}""", """{"n":2}"""), sent)
        assertEquals((2..4).map { """{"n":$it}""" }, bodies(store))
    }

    @Test
    fun `resend survives a throwing sender`() {
        val store = store()
        store.store("""{"n":1}""")

        assertEquals(0, store.flush { throw IllegalStateException("boom") })
        assertEquals(1, store.readAll().size)
    }

    @Test
    fun `errors stored during a pass are kept`() {
        val store = store()
        store.store("""{"n":1}""")

        store.flush {
            store.store("""{"n":2}""")
            SendOutcome.Http(200)
        }

        assertEquals(listOf("""{"n":2}"""), bodies(store))
        assertTrue(store.mayHavePending)
    }

    @Test
    fun `only one pass runs at a time`() {
        val a = store()
        val b = store() // second instance over the same file
        (1..3).forEach { a.store("""{"n":$it}""") }

        val inFirstSend = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sentByA = mutableListOf<String>()
        val sentByB = mutableListOf<String>()

        val first = thread {
            a.flush {
                sentByA += it
                inFirstSend.countDown()
                release.await(10, TimeUnit.SECONDS)
                SendOutcome.Http(200)
            }
        }
        assertTrue(inFirstSend.await(10, TimeUnit.SECONDS))

        // Same instance and another instance: both must back off.
        assertEquals(0, a.flush { sentByB += it; SendOutcome.Http(200) })
        assertEquals(0, b.flush { sentByB += it; SendOutcome.Http(200) })

        release.countDown()
        first.join(TimeUnit.SECONDS.toMillis(10))

        assertTrue(sentByB.isEmpty())
        assertEquals(3, sentByA.size)
        assertTrue(a.readAll().isEmpty())
    }

    // --- Disabled option ---

    @Test
    fun `disabled option creates no store and never asks for a directory`() {
        var asked = false
        val config = AbrovaTraceConfig(apiKey = "ab_live_test", enableOfflineStorage = false)

        assertNull(OfflineErrorStore.createIfEnabled(config) { asked = true; dir() })
        assertFalse(asked)
        assertTrue(dir().listFiles()!!.isEmpty())
    }

    @Test
    fun `enabled option creates a store without touching the disk`() {
        var asked = false
        val config = AbrovaTraceConfig(apiKey = "ab_live_test")

        assertNotNull(OfflineErrorStore.createIfEnabled(config) { asked = true; dir() })
        assertFalse(asked)
        assertTrue(dir().listFiles()!!.isEmpty())
    }

    @Test
    fun `disabled sdk creates no store`() {
        val config = AbrovaTraceConfig(apiKey = "ab_live_test", enabled = false)
        assertNull(OfflineErrorStore.createIfEnabled(config) { dir() })
    }
}
