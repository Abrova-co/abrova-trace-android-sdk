package ir.abrova.trace.sdk.network

import ir.abrova.trace.sdk.AbrovaTraceConfig
import ir.abrova.trace.sdk.models.AbrovaTraceError
import ir.abrova.trace.sdk.network.AbrovaTraceClient.CrashDelivery
import ir.abrova.trace.sdk.services.HandledCrashMarkers
import ir.abrova.trace.sdk.services.OfflineErrorStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests of the error send path against a local HTTP server
 * (reachable = HTTP answer) and a closed local port (network failure).
 */
class AbrovaTraceClientOfflineTest {

    private data class Received(val path: String, val apiKey: String?, val userAgent: String?, val body: String)

    @get:Rule
    val temp = TemporaryFolder()

    private var server: ServerSocket? = null
    private val received = Collections.synchronizedList(mutableListOf<Received>())
    @Volatile private var status = 200

    /** When true the server reads the request and never answers. */
    @Volatile private var silent = false

    private val apiKey = "ab_live_secret_key_123"

    @After
    fun tearDown() {
        try { server?.close() } catch (_: Exception) {}
    }

    /** Minimal HTTP/1.1 server: records each request and answers with [status]. */
    private fun startServer(): String {
        try { server?.close() } catch (_: Exception) {}
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                Thread { socket.use { handle(it) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${s.localPort}"
    }

    private fun handle(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val head = ByteArrayOutputStream()
            while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.write(b)
            }
            val lines = head.toString("ISO-8859-1").trim().split("\r\n")
            val path = lines.first().split(" ")[1]
            val headers = lines.drop(1).associate {
                it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            received += Received(path, headers["x-api-key"], headers["user-agent"], String(body, Charsets.UTF_8))

            if (silent) {
                Thread.sleep(10_000)
                return
            }

            val answer = "HTTP/1.1 $status Status\r\nContent-Type: application/json\r\n" +
                "Content-Length: 2\r\nConnection: close\r\n\r\n{}"
            socket.getOutputStream().apply {
                write(answer.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        } catch (_: Exception) {
        }
    }

    /** A URL nothing listens on: every request fails with an IOException. */
    private fun deadUrl(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    private fun client(url: String, offline: Boolean = true): AbrovaTraceClient {
        val config = AbrovaTraceConfig(
            apiKey = apiKey,
            apiUrl = url,
            httpTimeout = 5,
            enableOfflineStorage = offline
        )
        return AbrovaTraceClient(config).apply {
            offlineStore = OfflineErrorStore.createIfEnabled(config) { temp.root }
        }
    }

    private fun store() = OfflineErrorStore({ temp.root })
    private fun dataFile() = File(temp.root, OfflineErrorStore.FILE_NAME)

    private fun error(message: String) = AbrovaTraceError(
        message = message,
        stackTrace = "java.lang.IllegalStateException: $message\n\tat a.B.c(B.kt:1)",
        timestamp = 1700000000000L,
        extra = mapOf("user_id" to "u1", "count" to 3),
        tags = mapOf("screen" to "home")
    )

    private fun sendAndWait(client: AbrovaTraceClient, error: AbrovaTraceError): Pair<Boolean, String?> {
        val latch = CountDownLatch(1)
        var result: Pair<Boolean, String?> = false to "no callback"
        client.sendError(error) { ok, detail ->
            result = ok to detail
            latch.countDown()
        }
        assertTrue("callback not invoked", latch.await(20, TimeUnit.SECONDS))
        return result
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("timed out waiting for: $what")
    }

    // --- Storing ---

    @Test
    fun `network failure stores the exact body that would have been sent`() {
        // What the server gets when the send works
        val online = client(startServer(), offline = false)
        val boom = error("boom")
        assertTrue(sendAndWait(online, boom).first)
        val sentBody = received.single().body

        // Same error, unreachable server
        val offline = client(deadUrl())
        val (ok, _) = sendAndWait(offline, boom)

        assertFalse(ok)
        assertEquals(listOf(sentBody), store().readAll().map { it.body })
    }

    @Test
    fun `stored file never contains the api key`() {
        val offline = client(deadUrl())
        sendAndWait(offline, error("boom"))

        assertTrue(dataFile().exists())
        assertFalse(dataFile().readText().contains(apiKey))
        assertFalse(dataFile().readText().contains("ab_live_"))
    }

    @Test
    fun `http error response is not stored`() {
        val client = client(startServer())
        for (code in listOf(400, 401, 429, 500, 503)) {
            status = code
            assertFalse("code=$code", sendAndWait(client, error("boom $code")).first)
        }

        assertEquals(5, received.size)
        assertTrue(store().readAll().isEmpty())
        assertFalse(dataFile().exists())
    }

    @Test
    fun `disabled option stores nothing and does not touch the disk`() {
        val client = client(deadUrl(), offline = false)
        assertNull(client.offlineStore)

        assertFalse(sendAndWait(client, error("boom")).first)
        assertEquals(CrashDelivery.NOT_DELIVERED, client.deliverCrashReport(error("crash")))
        client.flushStoredErrors()

        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test
    fun `messages are not stored`() {
        val client = client(deadUrl())
        val latch = CountDownLatch(1)
        client.sendMessage(error("just a message")) { _, _ -> latch.countDown() }
        assertTrue(latch.await(20, TimeUnit.SECONDS))

        assertFalse(dataFile().exists())
    }

    // --- Crash paths ---

    @Test
    fun `sendErrorSync never stores so previous-session crash reports are not queued twice`() {
        val client = client(deadUrl())

        assertFalse(client.sendErrorSync(error("native crash")))

        assertFalse(dataFile().exists())
    }

    // --- Uncaught exception path ---

    @Test
    fun `crash report accepted by the server is sent once and not stored`() {
        status = 201
        val client = client(startServer())
        val handled = AtomicInteger()

        val delivery = client.deliverCrashReport(error("crash")) { handled.incrementAndGet() }

        assertEquals(CrashDelivery.SENT, delivery)
        assertEquals(1, received.size)
        assertEquals("/api/errors", received.single().path)
        assertEquals(apiKey, received.single().apiKey)
        assertEquals(1, handled.get())
        assertFalse(dataFile().exists())

        // Nothing is left for a later launch to send again
        client(startServer().also { received.clear() }).flushStoredErrors()
        Thread.sleep(300)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `crash report has the same body as an error sent the normal way`() {
        val client = client(startServer(), offline = false)
        val boom = error("boom")
        assertTrue(sendAndWait(client, boom).first)
        assertEquals(CrashDelivery.SENT, client.deliverCrashReport(boom))

        assertEquals(2, received.size)
        assertEquals(received[0].body, received[1].body)
        assertEquals(received[0].userAgent, received[1].userAgent)
    }

    @Test
    fun `crash report is stored when the network is down`() {
        val online = client(startServer(), offline = false)
        val crash = error("crash")
        assertTrue(sendAndWait(online, crash).first)
        val sentBody = received.single().body

        val handled = AtomicInteger()
        val delivery = client(deadUrl()).deliverCrashReport(crash) { handled.incrementAndGet() }

        assertEquals(CrashDelivery.STORED, delivery)
        assertEquals(listOf(sentBody), store().readAll().map { it.body })
        assertEquals(1, handled.get())
    }

    @Test
    fun `a stored crash report keeps its event id so the server can count it once`() {
        val crash = error("crash")
        assertEquals(CrashDelivery.STORED, client(deadUrl()).deliverCrashReport(crash))

        val stored = store().readAll().single().body
        assertTrue(stored.contains("\"event_id\":\"${crash.eventId}\""))
    }

    @Test
    fun `crash report is stored when the server answers 5xx 408 or 429`() {
        val client = client(startServer())
        val codes = listOf(500, 502, 503, 408, 429)
        for (code in codes) {
            status = code
            assertEquals("code=$code", CrashDelivery.STORED, client.deliverCrashReport(error("crash $code")))
        }

        // Every report reached the server (the HTTP client itself repeats a
        // request once after a 408) and every one is stored exactly once.
        for (code in codes) {
            assertTrue("code=$code", received.any { it.body.contains("crash $code") })
            assertEquals("code=$code", 1, store().readAll().count { it.body.contains("crash $code") })
        }
        assertEquals(codes.size, store().readAll().size)
    }

    @Test
    fun `crash report rejected by the server is not stored`() {
        val client = client(startServer())
        val handled = AtomicInteger()
        for (code in listOf(400, 401, 403, 404, 413, 422)) {
            status = code
            val delivery = client.deliverCrashReport(error("crash $code")) { handled.incrementAndGet() }
            assertEquals("code=$code", CrashDelivery.REJECTED, delivery)
        }

        assertEquals(6, received.size)
        assertEquals(0, handled.get())
        assertFalse(dataFile().exists())
    }

    @Test
    fun `crash report is stored when the request fails with an unexpected exception`() {
        // Not a URL: building the request throws IllegalArgumentException,
        // which is not an IOException.
        val client = client("not a url")

        assertEquals(CrashDelivery.STORED, client.deliverCrashReport(error("crash")))

        assertEquals(1, store().readAll().size)
    }

    @Test
    fun `crash report is stored within the time limit when the server does not answer`() {
        silent = true
        val client = client(startServer())
        val handled = AtomicInteger()

        val started = System.nanoTime()
        val delivery = client.deliverCrashReport(
            error("crash"), sendTimeoutMs = 300, storeTimeoutMs = 1000
        ) { handled.incrementAndGet() }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(CrashDelivery.STORED, delivery)
        assertTrue("returned after ${elapsedMs}ms", elapsedMs in 300..1300)
        assertEquals(1, store().readAll().size)
        assertEquals(1, handled.get())

        // The abandoned request does not store or report a second time
        Thread.sleep(500)
        assertEquals(1, store().readAll().size)
        assertEquals(1, handled.get())
    }

    @Test
    fun `default wait for a crash report is two seconds plus one for the disk`() {
        assertEquals(2000L, AbrovaTraceClient.CRASH_SEND_TIMEOUT_MS)
        assertEquals(1000L, AbrovaTraceClient.CRASH_STORE_TIMEOUT_MS)
    }

    @Test
    fun `crash report that cannot be sent or stored is not reported as handled`() {
        val client = client(deadUrl(), offline = false)
        val handled = AtomicInteger()

        val delivery = client.deliverCrashReport(error("crash")) { handled.incrementAndGet() }

        assertEquals(CrashDelivery.NOT_DELIVERED, delivery)
        assertEquals(0, handled.get())
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test
    fun `failing follow-up does not lose the crash report`() {
        val client = client(deadUrl())

        val delivery = client.deliverCrashReport(error("crash")) { throw IllegalStateException("boom") }

        assertEquals(CrashDelivery.STORED, delivery)
        assertEquals(1, store().readAll().size)
    }

    @Test
    fun `crash report is delivered from an interrupted thread`() {
        status = 201
        val client = client(startServer())

        Thread.currentThread().interrupt()
        val delivery = try {
            client.deliverCrashReport(error("crash"))
        } finally {
            // The flag is handed back to the caller; clear it for the test runner.
            assertTrue(Thread.interrupted())
        }

        assertEquals(CrashDelivery.SENT, delivery)
        assertEquals(1, received.size)
    }

    @Test
    fun `crash report is not sent on the calling thread`() {
        status = 201
        val client = client(startServer())
        val caller = Thread.currentThread()
        var handledOn: Thread? = null

        client.deliverCrashReport(error("crash")) { handledOn = Thread.currentThread() }

        assertNotNull(handledOn)
        assertNotSame(caller, handledOn)
    }

    @Test
    fun `stored crash report is sent exactly once on a later launch`() {
        status = 500
        assertEquals(CrashDelivery.STORED, client(startServer()).deliverCrashReport(error("crash")))
        assertEquals(1, received.size)

        // Next launch, server healthy again
        status = 201
        val next = client(startServer().also { received.clear() })
        next.flushStoredErrors()
        waitUntil("stored crash resent") { received.size == 1 && !dataFile().exists() }

        // And the launch after that has nothing to send
        client(startServer()).flushStoredErrors()
        Thread.sleep(300)
        assertEquals(1, received.size)
        assertTrue(received.single().body.contains("crash"))
    }

    @Test
    fun `marker is written when a crash report is sent or stored and not when it is rejected`() {
        val markers = HandledCrashMarkers({ temp.newFolder("markers") })
        val client = client(startServer())

        status = 201
        client.deliverCrashReport(error("sent")) { markers.record(101, 1_000L) }
        status = 503
        client.deliverCrashReport(error("stored")) { markers.record(102, 2_000L) }
        status = 400
        client.deliverCrashReport(error("rejected")) { markers.record(103, 3_000L) }

        assertEquals(
            listOf(HandledCrashMarkers.Marker(101, 1_000L), HandledCrashMarkers.Marker(102, 2_000L)),
            markers.readAll()
        )
    }

    // --- Resending ---

    @Test
    fun `stored errors are resent with the current key and removed on 2xx`() {
        sendAndWait(client(deadUrl()), error("stored while offline"))
        val storedBody = store().readAll().single().body

        // Network is back (next launch)
        val client = client(startServer())
        client.flushStoredErrors()
        waitUntil("stored error resent") { received.size == 1 && !dataFile().exists() }

        val request = received.single()
        assertEquals("/api/errors", request.path)
        assertEquals(apiKey, request.apiKey)
        assertTrue(request.userAgent!!.startsWith("AbrovaTrace-SDK/"))
        assertEquals(storedBody, request.body)

        // A second pass has nothing left to send
        client.flushStoredErrors()
        Thread.sleep(200)
        assertEquals(1, received.size)
    }

    @Test
    fun `a successful send triggers resending of stored errors exactly once each`() {
        val offline = client(deadUrl())
        sendAndWait(offline, error("offline 1"))
        sendAndWait(offline, error("offline 2"))
        assertEquals(2, store().readAll().size)

        val client = client(startServer())
        assertTrue(sendAndWait(client, error("live")).first)
        waitUntil("stored errors resent") { received.size == 3 && !dataFile().exists() }

        // Later sends do not resend anything again
        assertTrue(sendAndWait(client, error("live 2")).first)
        Thread.sleep(300)

        val messages = received.map { it.body }
        assertEquals(4, messages.size)
        assertEquals(1, messages.count { it.contains("offline 1") })
        assertEquals(1, messages.count { it.contains("offline 2") })
    }

    @Test
    fun `stored errors are dropped on 4xx and kept on 5xx`() {
        sendAndWait(client(deadUrl()), error("stored"))

        status = 503
        val client = client(startServer())
        client.flushStoredErrors()
        waitUntil("first attempt") { received.size == 1 }
        Thread.sleep(200)
        assertEquals(1, store().readAll().size)

        status = 422
        val next = client(startServer().also { received.clear() })
        next.flushStoredErrors()
        waitUntil("rejected entry dropped") { received.size == 1 && !dataFile().exists() }
    }

    @Test
    fun `stored errors are kept while the network is still down`() {
        val offline = client(deadUrl())
        sendAndWait(offline, error("stored 1"))
        sendAndWait(offline, error("stored 2"))

        offline.flushStoredErrors()
        Thread.sleep(500)

        assertEquals(2, store().readAll().size)
    }
}
