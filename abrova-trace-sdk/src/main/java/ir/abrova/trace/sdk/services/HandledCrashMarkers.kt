package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Remembers which process instances ended in a crash that the uncaught
 * exception handler already reported (sent, or stored for later sending).
 *
 * On Android 11+ the system also keeps an exit record for such a crash.
 * [NativeCrashReporter] uses these markers to leave that record alone, so the
 * crash is not reported a second time without its stack trace.
 *
 * One small app-private text file, one `pid timestamp` line per crash. A
 * marker is appended while the process is crashing, so writing is a single
 * short append: no network, no main looper, nothing is read first. No method
 * throws.
 */
internal class HandledCrashMarkers(
    private val directoryProvider: () -> File?,
    private val maxMarkers: Int = MAX_MARKERS
) {

    data class Marker(val pid: Int, val timestamp: Long)

    // Resolved lazily: looking up the app's storage directory can touch the
    // disk, so it must not happen on the thread that calls init().
    private val directory: File? by lazy {
        try {
            directoryProvider()
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Crash marker storage unavailable: ${t.message}")
            null
        }
    }

    /**
     * Note that the crash of process [pid] at [timestamp] (wall clock, ms)
     * has been reported. Returns true when the marker was written.
     */
    fun record(pid: Int, timestamp: Long): Boolean {
        return try {
            val dir = directory ?: return false
            synchronized(MUTEX) {
                if (!dir.exists() && !dir.mkdirs() && !dir.exists()) return false
                val file = File(dir, FILE_NAME)
                // Only reached when nothing has trimmed the file for a very
                // long time; start again rather than grow without limit.
                if (file.length() > MAX_FILE_BYTES) file.delete()
                FileOutputStream(file, true).use { out ->
                    out.write("$pid $timestamp\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
            }
            true
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Failed to write crash marker: ${t.message}")
            false
        }
    }

    /**
     * The most recent markers, oldest first. Older ones are dropped from the
     * file. Blocking disk I/O: call off the main thread.
     */
    fun readAll(): List<Marker> {
        return try {
            val dir = directory ?: return emptyList()
            synchronized(MUTEX) {
                val file = File(dir, FILE_NAME)
                if (!file.exists()) return emptyList()

                val lines = file.readLines(Charsets.UTF_8)
                val markers = lines.mapNotNull { parse(it) }
                val kept = markers.takeLast(maxMarkers)
                if (kept.size != lines.size) rewrite(file, kept)
                kept
            }
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Failed to read crash markers: ${t.message}")
            emptyList()
        }
    }

    private fun parse(line: String): Marker? {
        val parts = line.trim().split(' ')
        if (parts.size != 2) return null
        val pid = parts[0].toIntOrNull() ?: return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        return Marker(pid, timestamp)
    }

    private fun rewrite(file: File, markers: List<Marker>) {
        if (markers.isEmpty()) {
            file.delete()
            return
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(markers.joinToString("") { "${it.pid} ${it.timestamp}\n" }, Charsets.UTF_8)
            temp.renameTo(file)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "abrova_trace_handled_crashes"

        // More than the number of exit records read per launch, so every
        // record that can still be read has its marker.
        const val MAX_MARKERS = 32

        private const val MAX_FILE_BYTES = 16 * 1024L

        // The exit record is written when the process is gone, which is after
        // the marker: straight away, or once the user has dismissed the
        // system's crash dialog (it closes by itself after a few minutes).
        // The slack before the marker allows for a clock correction.
        internal const val EXIT_BEFORE_MARKER_MS = 60_000L
        internal const val EXIT_AFTER_MARKER_MS = 15 * 60_000L

        private val MUTEX = Any()

        /**
         * True when an exit record with this [pid] and [exitTimestamp]
         * belongs to a process instance that one of [markers] describes.
         */
        fun covers(markers: List<Marker>, pid: Int, exitTimestamp: Long): Boolean {
            return markers.any { marker ->
                marker.pid == pid &&
                    exitTimestamp >= marker.timestamp - EXIT_BEFORE_MARKER_MS &&
                    exitTimestamp <= marker.timestamp + EXIT_AFTER_MARKER_MS
            }
        }
    }
}
