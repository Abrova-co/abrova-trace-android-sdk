package ir.abrova.trace.sdk.services

import ir.abrova.trace.sdk.tombstone.TombstoneProtos
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import java.io.InputStream

/**
 * Parses the binary Android tombstone protobuf produced by
 * `ApplicationExitInfo.getTraceInputStream()` for `REASON_CRASH_NATIVE`
 * exits (API 30+) and converts it into a Sentry-compatible JSON payload
 * plus a human-readable text version.
 *
 * The JSON matches Sentry's public event schema (subset) so the dashboard
 * can render Android native crashes with the same frame-by-frame UI it
 * uses for iOS dSYM-symbolicated crashes.
 *
 * https://develop.sentry.dev/sdk/data-model/event-payloads/exception/
 * https://develop.sentry.dev/sdk/data-model/event-payloads/threads/
 * https://develop.sentry.dev/sdk/data-model/event-payloads/stacktrace/
 * https://develop.sentry.dev/sdk/data-model/event-payloads/debugmeta/
 */
internal object TombstoneParser {

    data class ParsedTombstone(
        /** Sentry-shape structured JSON, ready to send in resolved_stack_trace. */
        val structuredJson: String,
        /** Human-readable text version, ready to send in stack_trace. */
        val textFallback: String,
        /** Best-effort short summary suitable for the error `message` field. */
        val summary: String
    )

    /**
     * Parse a binary tombstone stream. Returns null if the bytes aren't a
     * valid tombstone protobuf (caller should fall back to raw text).
     */
    fun parse(stream: InputStream): ParsedTombstone? {
        val tombstone = try {
            TombstoneProtos.Tombstone.parseFrom(stream)
        } catch (e: Exception) {
            AbrovaTraceLogger.debug("Not a tombstone protobuf: ${e.message}")
            return null
        }
        return build(tombstone)
    }

    /** Same as [parse] but from an in-memory byte array. Used in tests. */
    fun parse(bytes: ByteArray): ParsedTombstone? {
        val tombstone = try {
            TombstoneProtos.Tombstone.parseFrom(bytes)
        } catch (e: Exception) {
            AbrovaTraceLogger.debug("Not a tombstone protobuf: ${e.message}")
            return null
        }
        return build(tombstone)
    }

    private fun build(t: TombstoneProtos.Tombstone): ParsedTombstone {
        val crashingTid = t.tid
        val signal = t.signalInfo
        val abortMessage = t.abortMessage.orEmpty()

        val signalName = signal.name.ifEmpty { signalNameForNumber(signal.number) }
        val signalCode = signal.codeName.ifEmpty { "code_${signal.code}" }
        val faultDesc = when {
            abortMessage.isNotBlank() -> abortMessage
            signal.hasFaultAddress -> "fault addr 0x${signal.faultAddress.toHex()}"
            else -> signalName
        }

        val threadsSorted = t.threadsMap.entries
            .sortedBy { it.key }
            .map { it.value }

        val debugImages = buildDebugImages(t.memoryMappingsList)

        val structuredJson = buildJson {
            key("format").str("structured")
            key("platform").str("native")
            key("level").str("fatal")

            key("exception").obj {
                key("type").str(signalName)
                key("value").str(faultDesc)
                key("thread_id").num(crashingTid.toLong())
                key("mechanism").obj {
                    key("type").str("application_exit_info")
                    key("handled").bool(false)
                    key("meta").obj {
                        key("signal").obj {
                            key("number").num(signal.number.toLong())
                            key("code").num(signal.code.toLong())
                            key("name").str(signalName)
                            key("code_name").str(signalCode)
                        }
                    }
                    if (signal.hasFaultAddress) {
                        key("data").obj {
                            key("fault_address").str("0x${signal.faultAddress.toHex()}")
                        }
                    }
                }
            }

            key("threads").arr {
                for (thread in threadsSorted) writeThread(thread, crashingTid)
            }

            key("debug_meta").obj {
                key("images").arr {
                    for (img in debugImages) {
                        obj {
                            key("type").str(img.type)
                            if (img.codeId.isNotEmpty()) key("code_id").str(img.codeId)
                            if (img.debugId.isNotEmpty()) key("debug_id").str(img.debugId)
                            key("code_file").str(img.codeFile)
                            key("image_addr").str(img.imageAddr)
                            key("image_size").num(img.imageSize)
                            key("arch").str(img.arch)
                        }
                    }
                }
            }

            key("tombstone_meta").obj {
                key("build_fingerprint").str(t.buildFingerprint)
                key("arch").str(archName(t.arch))
                if (t.processUptime > 0) key("process_uptime_s").num(t.processUptime.toLong())
                key("pid").num(t.pid.toLong())
                key("uid").num(t.uid.toLong())
                if (t.selinuxLabel.isNotBlank()) key("selinux_label").str(t.selinuxLabel)
                if (t.commandLineCount > 0) {
                    key("command_line").arr {
                        for (arg in t.commandLineList) str(arg)
                    }
                }
                if (abortMessage.isNotBlank()) key("abort_message").str(abortMessage)
            }
        }

        val textFallback = buildText(t, crashingTid, signalName, signalCode, faultDesc)
        val summary = "$signalName ($signalCode) $faultDesc".trim()

        return ParsedTombstone(
            structuredJson = structuredJson,
            textFallback = textFallback,
            summary = summary
        )
    }

    // ── JSON body builders ──

    private fun JsonBuilder.writeThread(
        thread: TombstoneProtos.Thread,
        crashingTid: Int
    ) {
        obj {
            key("id").num(thread.id.toLong())
            key("name").str(thread.name.ifEmpty { "tid_${thread.id}" })
            val crashed = thread.id == crashingTid
            key("crashed").bool(crashed)
            key("current").bool(crashed)
            key("main").bool(thread.name == "main")

            key("stacktrace").obj {
                key("frames").arr {
                    // Sentry wants frames oldest -> newest. Android tombstone
                    // lists them newest -> oldest (frame 0 is the crash), so
                    // we reverse.
                    val frames = thread.currentBacktraceList.reversed()
                    for (frame in frames) writeFrame(frame)
                }
                if (thread.registersCount > 0) {
                    key("registers").obj {
                        for (reg in thread.registersList) {
                            key(reg.name).str("0x${reg.u64.toHex()}")
                        }
                    }
                }
            }
        }
    }

    private fun JsonBuilder.writeFrame(frame: TombstoneProtos.BacktraceFrame) {
        obj {
            val fn = frame.functionName.ifEmpty { "<unknown>" }
            val fnWithOffset = if (frame.functionOffset > 0) {
                "$fn+${frame.functionOffset}"
            } else fn
            key("function").str(fnWithOffset)
            key("package").str(frame.fileName)
            key("instruction_addr").str("0x${frame.pc.toHex()}")
            if (frame.relPc != 0L) key("rel_pc").str("0x${frame.relPc.toHex()}")
            if (frame.sp != 0L) key("sp").str("0x${frame.sp.toHex()}")
            // in_app heuristic: app-owned libraries live under /data/app/.
            // System, apex, and vendor code is out-of-app.
            key("in_app").bool(frame.fileName.startsWith("/data/app/"))
            key("platform").str("native")
            if (frame.buildId.isNotEmpty()) key("build_id").str(frame.buildId)
        }
    }

    // ── Debug images ──

    private data class DebugImage(
        val type: String,           // "elf"
        val codeId: String,         // GNU build-id lowercase hex
        val debugId: String,        // Sentry-format debug id (see below)
        val codeFile: String,       // ELF path
        val imageAddr: String,      // hex, 0x-prefixed
        val imageSize: Long,        // bytes
        val arch: String            // "arm64", "x86_64", ...
    )

    private fun buildDebugImages(
        mappings: List<TombstoneProtos.MemoryMapping>
    ): List<DebugImage> {
        // The tombstone lists every VMA. For debug_meta we only want executable
        // ELF mappings that have a build_id — those are the ones symbolication
        // servers can look up.
        val out = mutableListOf<DebugImage>()
        val seen = mutableSetOf<String>()
        for (m in mappings) {
            if (!m.execute) continue
            if (m.buildId.isEmpty()) continue
            if (m.mappingName.isBlank()) continue
            val key = "${m.mappingName}@${m.beginAddress}"
            if (!seen.add(key)) continue

            val size = (m.endAddress - m.beginAddress).coerceAtLeast(0)
            out += DebugImage(
                type = "elf",
                codeId = m.buildId.lowercase(),
                debugId = sentryDebugIdFromBuildId(m.buildId),
                codeFile = m.mappingName,
                imageAddr = "0x${m.beginAddress.toHex()}",
                imageSize = size,
                arch = "" // filled from Tombstone.arch by caller if needed
            )
        }
        return out
    }

    /**
     * Sentry expects an ELF debug_id derived from the GNU build-id via a
     * little-endian byte shuffle on the first 16 bytes, formatted as a
     * standard UUID (8-4-4-4-12). See
     * https://getsentry.github.io/symbolic/api/symbolic-debuginfo/elf/index.html
     */
    private fun sentryDebugIdFromBuildId(buildIdHex: String): String {
        if (buildIdHex.length < 32) return buildIdHex.lowercase()
        val hex = buildIdHex.lowercase().take(32)
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        // Little-endian shuffle on first three groups (4, 2, 2 bytes).
        fun swap(a: Int, b: Int) {
            val t = bytes[a]; bytes[a] = bytes[b]; bytes[b] = t
        }
        swap(0, 3); swap(1, 2)
        swap(4, 5)
        swap(6, 7)
        val sb = StringBuilder(36)
        for ((i, b) in bytes.withIndex()) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    // ── Text fallback (debuggerd-style) ──

    private fun buildText(
        t: TombstoneProtos.Tombstone,
        crashingTid: Int,
        signalName: String,
        signalCode: String,
        faultDesc: String
    ): String = buildString {
        appendLine("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***")
        appendLine("Build fingerprint: ${t.buildFingerprint}")
        appendLine("ABI: ${archName(t.arch)}")
        if (t.processUptime > 0) appendLine("Process uptime: ${t.processUptime}s")
        val cmd = if (t.commandLineCount > 0) t.commandLineList.joinToString(" ") else "<unknown>"
        appendLine("pid: ${t.pid}, tid: ${t.tid}, name: $cmd")
        appendLine("uid: ${t.uid}")
        appendLine("signal $signalName ($signalCode), fault: $faultDesc")
        if (t.abortMessage.isNotBlank()) appendLine("Abort message: '${t.abortMessage}'")
        appendLine()

        val threads = t.threadsMap.entries.sortedBy { it.key }
        // Print crashing thread first so it's readable at the top.
        val (crashing, others) = threads.partition { it.key == crashingTid }
        for (entry in crashing + others) {
            val thread = entry.value
            val marker = if (entry.key == crashingTid) " (CRASHED)" else ""
            appendLine("--- tid=${thread.id} name='${thread.name}'$marker ---")
            if (thread.registersCount > 0 && entry.key == crashingTid) {
                appendLine("Registers:")
                val cols = 4
                val chunks = thread.registersList.chunked(cols)
                for (row in chunks) {
                    appendLine(
                        "  " + row.joinToString("  ") { r ->
                            "${r.name.padEnd(4)}=0x${r.u64.toHex()}"
                        }
                    )
                }
            }
            appendLine("Backtrace:")
            for ((i, f) in thread.currentBacktraceList.withIndex()) {
                val fn = f.functionName.ifEmpty { "<unknown>" }
                val off = if (f.functionOffset > 0) "+${f.functionOffset}" else ""
                appendLine(
                    "  #${i.toString().padStart(2, '0')} pc 0x${f.pc.toHex()}  ${f.fileName} ($fn$off)"
                )
            }
            appendLine()
        }
    }

    // ── Enum + primitive helpers ──

    private fun archName(a: TombstoneProtos.Architecture): String = when (a) {
        TombstoneProtos.Architecture.ARM32 -> "arm"
        TombstoneProtos.Architecture.ARM64 -> "arm64"
        TombstoneProtos.Architecture.X86 -> "x86"
        TombstoneProtos.Architecture.X86_64 -> "x86_64"
        TombstoneProtos.Architecture.RISCV64 -> "riscv64"
        else -> "unknown"
    }

    private fun signalNameForNumber(n: Int): String = when (n) {
        4 -> "SIGILL"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        13 -> "SIGPIPE"
        14 -> "SIGALRM"
        15 -> "SIGTERM"
        else -> "signal_$n"
    }

    private fun Long.toHex(): String = java.lang.Long.toHexString(this).padStart(16, '0')
}

// ── Tiny hand-rolled JSON builder ──
//
// We already ship Gson (see build.gradle), but pulling reflection or
// intermediate maps for this deep, well-known shape would waste allocations.
// The builder is 60 lines and produces spec-compliant JSON, so we use it.

private class JsonBuilder {
    private val sb = StringBuilder(4 * 1024)
    private var needComma = false

    fun toJson(): String = sb.toString()

    fun obj(body: JsonBuilder.() -> Unit): JsonBuilder {
        preValue(); sb.append('{'); resetComma()
        body()
        sb.append('}'); needComma = true
        return this
    }

    fun arr(body: JsonBuilder.() -> Unit): JsonBuilder {
        preValue(); sb.append('['); resetComma()
        body()
        sb.append(']'); needComma = true
        return this
    }

    fun key(name: String): JsonBuilder {
        preComma(); sb.append('"'); escape(name); sb.append('"').append(':')
        needComma = false
        return this
    }

    fun str(s: String): JsonBuilder {
        preValue(); sb.append('"'); escape(s); sb.append('"'); needComma = true
        return this
    }

    fun num(n: Long): JsonBuilder { preValue(); sb.append(n); needComma = true; return this }
    fun bool(b: Boolean): JsonBuilder { preValue(); sb.append(b); needComma = true; return this }

    private fun preComma() { if (needComma) sb.append(','); needComma = false }
    private fun preValue() { preComma() }
    private fun resetComma() { needComma = false }

    private fun escape(s: String) {
        for (i in s.indices) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c.code == 0x0C -> sb.append("\\f")
                c.code < 0x20 -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
    }
}

private fun buildJson(body: JsonBuilder.() -> Unit): String {
    val jb = JsonBuilder()
    jb.obj(body)
    return jb.toJson()
}
