package ir.abrova.trace.sdk.services

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class ANRWatchdogTest {

    private val reports = CopyOnWriteArrayList<Long>()
    private val pendingPing = AtomicReference<Runnable?>(null)

    @Volatile
    private var mainThreadResponds = true
    private var watchdog: ANRWatchdog? = null

    /** A watchdog whose "main thread" answers pings only while [mainThreadResponds]. */
    private fun start(timeoutMs: Long = 40L): ANRWatchdog {
        val test = Thread.currentThread()
        return ANRWatchdog(
            timeoutMs = timeoutMs,
            recoveryPollMs = 5L,
            postToMain = { ping -> if (mainThreadResponds) ping.run() else pendingPing.set(ping) },
            now = { System.nanoTime() / 1_000_000 },
            mainThread = { test },
            onAnrDetected = { _, blockedMs -> reports.add(blockedMs) }
        ).also {
            watchdog = it
            it.start()
        }
    }

    /** The main thread comes back and runs what was waiting for it. */
    private fun recover() {
        mainThreadResponds = true
        pendingPing.getAndSet(null)?.run()
    }

    @After
    fun tearDown() {
        watchdog?.shutdown()
        watchdog?.join(1000)
    }

    @Test
    fun `a responsive main thread is never reported`() {
        start()
        Thread.sleep(300)
        assertEquals(0, reports.size)
    }

    @Test
    fun `one long hang is one report`() {
        mainThreadResponds = false
        start()
        Thread.sleep(500) // more than ten timeouts

        assertEquals(1, reports.size)
        assertTrue("blocked for ${reports[0]}ms", reports[0] >= 40L)
    }

    @Test
    fun `a second hang after the main thread recovered is reported again`() {
        mainThreadResponds = false
        start()
        Thread.sleep(200)
        assertEquals(1, reports.size)

        recover()
        Thread.sleep(200)
        assertEquals(1, reports.size)

        mainThreadResponds = false
        Thread.sleep(300)
        assertEquals(2, reports.size)
    }
}
