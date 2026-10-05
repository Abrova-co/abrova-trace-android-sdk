package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.AbrovaTraceConfig
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of one attempt to deliver an error payload.
 */
internal sealed class SendOutcome {
    /** The server answered (any status code). */
    data class Http(val code: Int) : SendOutcome()

    /** The request never got an answer: no connectivity, timeout, I/O error. */
    object NetworkFailure : SendOutcome()

    /** The request could not be made at all (unexpected, non-network failure). */
    object Failed : SendOutcome()

    /** The server took the report (2xx). */
    val isAccepted: Boolean
        get() = this is Http && code in 200..299

    /**
     * The server refused the report and would refuse it again: 4xx other
     * than 408 and 429. Everything that is neither accepted nor rejected is
     * worth another attempt later.
     */
    val isRejected: Boolean
        get() = this is Http && code in 400..499 && code != 408 && code != 429
}

/**
 * Disk queue for error payloads that could not be sent because the network
 * was unavailable, and for crash reports the server did not accept before
 * the process ended.
 *
 * - One app-private JSON file holding at most [maxEntries] payloads; when
 *   full, the oldest are dropped.
 * - Each entry is the exact JSON body of the failed `/api/errors` request.
 *   Credentials are never written; they are taken from the live config when
 *   the entry is resent.
 * - Writes go to a temp file that is then renamed over the real one, and all
 *   access is serialised (in-process mutex plus a file lock for apps that run
 *   the SDK in more than one process).
 * - A file that cannot be parsed is treated as empty and replaced.
 *
 * Every method does blocking disk I/O and must be called off the main
 * thread. No method throws.
 */
internal class OfflineErrorStore(
    private val directoryProvider: () -> File?,
    private val maxEntries: Int = MAX_STORED_ERRORS
) {

    data class Entry(val id: String, val body: String)

    /**
     * Cheap in-memory hint so callers can skip a disk read when nothing is
     * queued. Starts as `true` (unknown) and is corrected by the first
     * [flush].
     */
    @Volatile
    var mayHavePending: Boolean = true
        private set

    private val flushing = AtomicBoolean(false)

    // Resolved lazily: looking up the app's storage directory can itself
    // touch the disk, so it must not happen on the caller's (main) thread.
    private val directory: File? by lazy {
        try {
            directoryProvider()
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Offline storage unavailable: ${t.message}")
            null
        }
    }

    /**
     * Queue a payload for later delivery. Returns true when it was written.
     */
    fun store(body: String): Boolean {
        return try {
            if (body.length > MAX_ENTRY_CHARS) {
                AbrovaTraceLogger.warn("Error too large for offline storage (${body.length} chars); dropped")
                return false
            }
            val stored = locked { file ->
                val entries = read(file)
                entries.add(Entry(UUID.randomUUID().toString(), body))
                trim(entries)
                write(file, entries)
                true
            } ?: false
            if (stored) {
                mayHavePending = true
                AbrovaTraceLogger.debug("Error stored offline for later sending")
            }
            stored
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Failed to store offline error: ${t.message}")
            false
        }
    }

    /**
     * Snapshot of the queued payloads, oldest first.
     */
    fun readAll(): List<Entry> {
        return try {
            locked { file -> read(file).toList() } ?: emptyList()
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Failed to read offline errors: ${t.message}")
            emptyList()
        }
    }

    /**
     * Try to deliver every queued payload, oldest first.
     *
     * An entry is removed once the server answered 2xx, or 4xx (the server
     * rejected it and would reject it again). It is kept on 408 and 429 (try
     * again later), on a 5xx answer and on a network failure; the pass stops
     * at the first network failure.
     *
     * Only one pass runs at a time, across threads and processes, so an
     * entry is never handed to [send] by two passes at once.
     *
     * @return number of entries removed
     */
    fun flush(send: (String) -> SendOutcome): Int {
        if (!flushing.compareAndSet(false, true)) return 0
        var removed = 0
        try {
            val dir = directory ?: return 0
            if (!dir.exists()) {
                mayHavePending = false
                return 0
            }
            val passLock = PassLock.acquire(File(dir, FLUSH_LOCK_FILE_NAME))
            if (passLock == null) {
                AbrovaTraceLogger.debug("Offline errors are being sent by another process")
                return 0
            }
            try {
                val pending = locked { file -> read(file).toList() } ?: emptyList()
                if (pending.isEmpty()) {
                    mayHavePending = false
                    return 0
                }

                AbrovaTraceLogger.info("Sending ${pending.size} stored offline errors")

                for (entry in pending) {
                    val outcome = try {
                        send(entry.body)
                    } catch (t: Throwable) {
                        SendOutcome.Failed
                    }

                    if (outcome is SendOutcome.Http) {
                        val accepted = outcome.isAccepted
                        val rejected = outcome.isRejected
                        if (accepted || rejected) {
                            // Remove straight away so a crash later in the
                            // pass cannot cause this entry to be sent again.
                            remove(entry.id)
                            removed++
                            if (rejected) {
                                AbrovaTraceLogger.warn(
                                    "Stored error rejected by server (HTTP ${outcome.code}); discarded"
                                )
                            }
                        } else {
                            AbrovaTraceLogger.debug(
                                "Stored error not accepted (HTTP ${outcome.code}); will retry later"
                            )
                        }
                    } else {
                        AbrovaTraceLogger.debug("Network still unavailable; stored errors will be retried later")
                        break
                    }
                }

                mayHavePending = locked { file -> read(file).isNotEmpty() } ?: true
            } finally {
                passLock.release()
            }
        } catch (t: Throwable) {
            AbrovaTraceLogger.error("Failed to send stored errors: ${t.message}")
        } finally {
            flushing.set(false)
        }
        return removed
    }

    private fun remove(id: String) {
        locked { file ->
            val entries = read(file)
            if (entries.removeAll { it.id == id }) {
                write(file, entries)
            }
        }
    }

    private fun trim(entries: MutableList<Entry>) {
        while (entries.size > maxEntries) {
            entries.removeAt(0)
        }
        var total = entries.sumOf { it.body.length.toLong() }
        while (entries.size > 1 && total > MAX_TOTAL_CHARS) {
            total -= entries.removeAt(0).body.length
        }
    }

    /**
     * Runs [block] with exclusive access to the storage file. Returns null
     * when storage is unavailable.
     */
    private fun <T> locked(block: (File) -> T): T? {
        val dir = directory ?: return null
        synchronized(MUTEX) {
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                AbrovaTraceLogger.error("Offline storage directory cannot be created")
                return null
            }

            var raf: RandomAccessFile? = null
            var lock: FileLock? = null
            try {
                raf = RandomAccessFile(File(dir, LOCK_FILE_NAME), "rw")
                lock = raf.channel.lock()
            } catch (e: Exception) {
                // The in-process mutex still applies.
                AbrovaTraceLogger.debug("Offline storage file lock unavailable: ${e.message}")
            }

            try {
                return block(File(dir, FILE_NAME))
            } finally {
                try { lock?.release() } catch (_: Exception) {}
                try { raf?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun read(file: File): MutableList<Entry> {
        val entries = mutableListOf<Entry>()
        if (!file.exists()) return entries

        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return entries

            val root = JsonParser.parseString(text)
            if (!root.isJsonArray) throw IllegalStateException("not a JSON array")

            for (element in root.asJsonArray) {
                val obj = if (element.isJsonObject) element.asJsonObject else continue
                val id = obj.stringOrNull("id") ?: continue
                val body = obj.stringOrNull("body") ?: continue
                entries.add(Entry(id, body))
            }
        } catch (e: Exception) {
            AbrovaTraceLogger.warn("Offline error file is unreadable and was reset: ${e.message}")
            entries.clear()
            file.delete()
        }
        return entries
    }

    private fun write(file: File, entries: List<Entry>) {
        val temp = File(file.parentFile, file.name + ".tmp")

        if (entries.isEmpty()) {
            temp.delete()
            if (file.exists() && !file.delete()) {
                throw IOException("could not clear ${file.name}")
            }
            return
        }

        val array = JsonArray()
        for (entry in entries) {
            array.add(JsonObject().apply {
                addProperty("id", entry.id)
                addProperty("body", entry.body)
            })
        }

        try {
            FileOutputStream(temp).use { out ->
                out.write(array.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                throw IOException("could not replace ${file.name}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    /**
     * Exclusive, non-blocking lock held for the length of one [flush] pass.
     * It uses its own lock file: on POSIX a process loses all its locks on a
     * file as soon as it closes any descriptor for it, so the pass lock
     * cannot share a file with the short-lived lock taken in [locked].
     */
    private class PassLock(private val raf: RandomAccessFile?, private val lock: FileLock?) {
        fun release() {
            try { lock?.release() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
        }

        companion object {
            /** Returns null when another pass already holds the lock. */
            fun acquire(lockFile: File): PassLock? {
                var raf: RandomAccessFile? = null
                try {
                    raf = RandomAccessFile(lockFile, "rw")
                    val lock = raf.channel.tryLock()
                    if (lock == null) {
                        raf.close()
                        return null
                    }
                    return PassLock(raf, lock)
                } catch (e: OverlappingFileLockException) {
                    try { raf?.close() } catch (_: Exception) {}
                    return null
                } catch (e: Exception) {
                    // File locking not available here: carry on with the
                    // in-process guard only.
                    try { raf?.close() } catch (_: Exception) {}
                    return PassLock(null, null)
                }
            }
        }
    }

    companion object {
        const val FILE_NAME = "abrova_trace_offline_errors.json"
        const val MAX_STORED_ERRORS = 100

        private const val LOCK_FILE_NAME = "abrova_trace_offline_errors.lock"
        private const val FLUSH_LOCK_FILE_NAME = "abrova_trace_offline_errors.flush.lock"

        // Guards against a single huge payload, or a full queue of large
        // ones, making the file expensive to load.
        private const val MAX_ENTRY_CHARS = 512 * 1024
        private const val MAX_TOTAL_CHARS = 4L * 1024 * 1024

        private val MUTEX = Any()

        /**
         * Returns a store when [AbrovaTraceConfig.enableOfflineStorage] is
         * on, otherwise null. Nothing is read from or written to disk here.
         */
        fun createIfEnabled(config: AbrovaTraceConfig, directoryProvider: () -> File?): OfflineErrorStore? {
            if (!config.enabled || !config.enableOfflineStorage) return null
            return OfflineErrorStore(directoryProvider)
        }
    }
}
