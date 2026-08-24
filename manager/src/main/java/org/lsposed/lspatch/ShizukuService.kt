package org.lsposed.lspatch

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class ShizukuService : IShizukuService.Stub() {

    init {
        Log.i(TAG, "Shell service starting: pid ${android.os.Process.myPid()}, uid ${android.os.Process.myUid()}")
        reapPreviousInstances()
    }

    /**
     * Kills any earlier instance of this same service still running.
     *
     * A client that dies without unbinding -- a force-stop, a crash, an upgrade -- leaves its shell process behind:
     * several were seen alive at once, surviving force-stops of the manager. Only a shell-uid process may kill another,
     * and this is one, so the newest instance clears its own predecessors. The match is this process's exact name read
     * from `/proc`, not a hardcoded package, so a renamed (cloaked) manager reaps its own strays and nothing else's.
     */
    private fun reapPreviousInstances() {
        runCatching {
            val self = android.os.Process.myPid()
            val name = File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            if (name.isEmpty()) return
            val ps =
                Runtime.getRuntime()
                    .exec(arrayOf("sh", "-c", "ps -A -o PID,NAME"))
                    .inputStream
                    .bufferedReader()
                    .readText()
            ps.lineSequence().forEach { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 2 || columns[1] != name) return@forEach
                val pid = columns[0].toIntOrNull() ?: return@forEach
                if (pid != self) {
                    Log.i(TAG, "Reaping a previous instance of this service: pid $pid")
                    android.os.Process.killProcess(pid)
                }
            }
        }
    }

    /**
     * The running `logcat` collector, or null. It streams to this service's own stdout, which a [readerThread] drains
     * and fans into two rotating on-disk streams; a pipe nobody drained would eventually block logcat, so the reader
     * must keep running for the collector's whole life.
     */
    @Volatile private var collector: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false

    // Set by startNewLogPart(), consumed by the reader thread on its next line: it rotates both
    // writers to fresh parts. A flag rather than a direct call because RotatingWriter is not
    // thread-safe (one writer per reader thread), so only the reader may touch it.
    @Volatile private var rotateRequested = false

    override fun runShellCommand(cmd: String): String {
        Log.v(TAG, "runShellCommand: $cmd")
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val combined = output + error
            // The result crosses Binder as a UTF-16 String — two bytes per char — so ~512K chars
            // already approaches the 1 MB transaction limit and a large `logcat` dump would throw
            // TransactionTooLargeException on the way back, reaching the caller as null. Keep the
            // tail: for a log dump the most recent lines are the ones worth reading.
            if (combined.length > MAX_OUTPUT_CHARS) combined.takeLast(MAX_OUTPUT_CHARS) else combined
        } catch (e: Exception) {
            Log.w(TAG, "runShellCommand failed: $cmd", e)
            e.stackTraceToString()
        }
    }

    /** Runs a shell script via `sh -c`; combined stdout+stderr, tail-capped to MAX_OUTPUT_CHARS for Binder. */
    override fun runShellScript(script: String): String {
        Log.v(TAG, "runShellScript: ${script.take(200)}")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", script))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val combined = output + error
            if (combined.length > MAX_OUTPUT_CHARS) combined.takeLast(MAX_OUTPUT_CHARS) else combined
        } catch (e: Exception) {
            Log.w(TAG, "runShellScript failed", e)
            e.stackTraceToString()
        }
    }

    /**
     * Starts continuous log collection, fanning one live `logcat` into two rotating streams named
     * `verbose_<timestamp>.log` (every line) and `framework_<timestamp>.log`. A line joins the framework stream when it
     * comes from a relevant uid (a patched app or a module — the manager itself only for its own tags or a warning,
     * since its UI process emits the platform's whole rendering chatter — [relevantUids], resolved by the caller) or is
     * an AndroidRuntime warning/error or any fatal line. Timestamped names sort chronologically by name, so no
     * meaningless numeric suffixes.
     */
    override fun startLogCollector(logDir: String, relevantUids: IntArray): Boolean {
        Log.i(TAG, "Starting the log collector in $logDir for ${relevantUids.size} uid(s)")
        return try {
            stopLogCollector()
            // Kill any stray collector a previous service instance left behind. When Shizuku respawns
            // the user service this process's fields are fresh and do not know the old child, which
            // keeps writing to the same dir; matching the exact invocation kills only our collectors.
            runCatching {
                Runtime.getRuntime().exec(arrayOf("pkill", "-f", LOGCAT_MATCH)).waitFor()
            }
            // The shell UID owns this directory; the app never opens the files itself (a cross-UID
            // read of /data/local/tmp is not permitted) — it asks for them back through readLogPart.
            Runtime.getRuntime().exec(arrayOf("mkdir", "-p", logDir)).waitFor()
            Runtime.getRuntime().exec(arrayOf("chmod", "777", logDir)).waitFor()

            // Collection is per-boot: /data/local/tmp survives a reboot, so any part older than this
            // boot is from a previous session and is cleared, rather than growing the log across boots.
            val bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
            File(logDir).listFiles()?.forEach { f ->
                if (f.name.endsWith(".log") && f.lastModified() < bootTime) runCatching { f.delete() }
            }

            val builder =
                ProcessBuilder(
                    "logcat",
                    "-b",
                    "main",
                    "-b",
                    "crash",
                    "-b",
                    "system",
                    "-v",
                    "threadtime",
                )
            builder.redirectErrorStream(true)
            val process = builder.start()
            collector = process
            running = true

            val uids = relevantUids.toHashSet()
            // The manager's own uid is the first element the caller writes; it is filtered more
            // tightly than the rest, so it is named rather than merely present in the set.
            val myUid = relevantUids.firstOrNull() ?: -1
            val thread = Thread { runReader(process, logDir, uids, myUid) }
            thread.isDaemon = true
            thread.start()
            readerThread = thread
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Drains the collector's output and fans each line into the verbose stream (always) and the framework stream (when
     * relevant). A wrapped multi-line message has no threadtime header on its continuation lines, so those inherit the
     * routing of the entry they belong to.
     */
    private fun runReader(process: Process, logDir: String, relevantUids: Set<Int>, myUid: Int) {
        val verbose = RotatingWriter(logDir, "verbose")
        val framework = RotatingWriter(logDir, "framework")
        val pidUid = HashMap<Int, Int>()
        var lastWentToFramework = false
        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (!running) return@forEachLine
                // A "start a new log" request rolls both streams to fresh parts before this line, so
                // the new part begins here and the closed one stays on disk as a chevron.
                if (rotateRequested) {
                    rotateRequested = false
                    verbose.rotate()
                    framework.rotate()
                }
                verbose.write(line)
                val header = HEADER.find(line)
                val toFramework =
                    if (header != null) {
                        val pid = header.groupValues[1].toIntOrNull() ?: -1
                        val level = header.groupValues[2].firstOrNull() ?: ' '
                        val tag = header.groupValues[3].trim()
                        val uid = pidUid.getOrPut(pid) { readUid(pid) }
                        when {
                            // The manager is in the relevant set so the stream is never empty, but
                            // taking *every* line it emits pulls in the platform's rendering
                            // chatter -- Choreographer, HWUI, Surface, Resources -- from its own UI
                            // process. That is not LSPatch's framework log, and being the bulk of
                            // it, it is what made the live tail scroll continuously with nothing
                            // worth reading. Only its own tags, and anything it considers a
                            // problem, belong here.
                            uid == myUid -> tag in OWN_TAGS || level == 'W' || level == 'E' || level == 'F'
                            // A patched app or a module: everything it says is the point.
                            uid in relevantUids -> true
                            else -> (tag == "AndroidRuntime" && (level == 'W' || level == 'E')) || level == 'F'
                        }
                    } else {
                        lastWentToFramework
                    }
                if (toFramework) framework.write(line)
                lastWentToFramework = toFramework
            }
        } catch (e: Exception) {
            // The stream closes when the collector is killed; nothing to do but let the reader end.
        } finally {
            verbose.close()
            framework.close()
        }
    }

    /**
     * LSPatch's own log tags.
     *
     * Listed rather than matched by prefix because they do not share one -- they are class names -- and a prefix rule
     * would either miss most of them or catch the platform's. Anything the manager logs at WARN or above is kept
     * regardless, so a tag missing from here still cannot hide a problem; it only affects which of its *informational*
     * lines reach the framework stream.
     */
    private val OWN_TAGS =
        setOf(
            "LSPatch",
            "LSPatch-XposedService",
            "AppBroadcastReceiver",
            "ConfigManager",
            "GithubReleaseDownloader",
            "LSPPackageManager",
            "LocalAppsUpdater",
            "ManageViewModel",
            "ManagerCloak",
            "ManagerCloakFlow",
            "ManagerMigrate",
            "ManagerService",
            "ModuleDetection",
            "ModuleManageViewModel",
            "ModuleService",
            "PatchInputs",
            "PatchJobHost",
            "PatchLogStore",
            "PatchOutputStore",
            "PatchRequestStore",
            "RepoRepository",
        )

    /** The uid a pid runs as, read from /proc; -1 when it cannot be resolved (already gone). */
    private fun readUid(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val status = File("/proc/$pid/status")
            if (!status.exists()) return -1
            status.bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("Uid:")) {
                        return l.substringAfter("Uid:").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
                    }
                }
            }
            -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun stopLogCollector() {
        Log.i(TAG, "Stopping the log collector")
        running = false
        runCatching { collector?.destroy() }
        collector = null
        runCatching {
            readerThread?.interrupt()
            readerThread?.join(500)
        }
        readerThread = null
    }

    override fun startNewLogPart(): Boolean {
        // No destructive clear: just ask the reader to open fresh parts. Nothing stops, nothing is
        // deleted here (the rotation cap prunes old parts on its own), so framework collection has no
        // gap -- the rootless equivalent of Vector's daemon opening a new log part.
        if (!running || collector?.isAlive != true) return false
        rotateRequested = true
        return true
    }

    override fun isLogCollectorRunning(): Boolean = running && collector?.isAlive == true

    override fun listLogParts(logDir: String, prefix: String): Array<String> {
        return try {
            val dir = File(logDir)
            val pattern = Regex("""^${Regex.escape(prefix)}_.*\.log$""")
            val files = dir.listFiles { f -> pattern.matches(f.name) } ?: return emptyArray()
            // The names carry a timestamp, so lexicographic order is chronological: oldest first,
            // the live part last — the order the reader pages through.
            files.sortedBy { it.name }.map { "${it.absolutePath}\t${it.length()}" }.toTypedArray()
        } catch (e: Exception) {
            emptyArray()
        }
    }

    override fun readLogPart(path: String, maxChars: Int): String {
        return try {
            val file = File(path)
            val length = file.length()
            val cap = maxChars.coerceAtLeast(0).toLong()
            if (length <= cap) return file.readText()
            // Tail read rather than reading the whole part into memory to then throw most of it away.
            // The first line handed back may be a fragment; the caller's parser drops any line that
            // is not a whole log entry, so a partial head costs nothing.
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - cap)
                val buffer = ByteArray(cap.toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun readFileChunk(path: String, offset: Long, maxBytes: Int): ByteArray {
        return try {
            RandomAccessFile(File(path), "r").use { file ->
                val remaining = file.length() - offset
                if (remaining <= 0L) return ByteArray(0)
                file.seek(offset)
                val buffer = ByteArray(minOf(remaining, maxBytes.toLong()).toInt())
                file.readFully(buffer)
                buffer
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFileChunk failed: $path", e)
            ByteArray(0)
        }
    }

    override fun destroy() {
        Log.i(TAG, "Shell service destroyed")
        stopLogCollector()
        exitProcess(0)
    }

    /**
     * A rotating writer for one stream. Lines append to `<prefix>_<timestamp>.log` until it exceeds [MAX_PART_BYTES];
     * then it opens a fresh timestamped part and prunes the oldest so at most [MAX_PARTS] survive per prefix. Not
     * thread-safe — one writer per reader thread.
     */
    private class RotatingWriter(private val dir: String, private val prefix: String) {
        private var writer: BufferedWriter? = null
        private var written = 0L
        private var lastStamp = ""
        private var stampCounter = 0

        private fun stamp(): String {
            val now = STAMP_FORMAT.format(Date())
            // Two parts opened in the same millisecond would collide and one would clobber the other;
            // disambiguate with a counter so both are kept and still sort next to each other.
            return if (now == lastStamp) {
                "${now}_${++stampCounter}"
            } else {
                lastStamp = now
                stampCounter = 0
                now
            }
        }

        private fun openNew() {
            runCatching {
                writer?.flush()
                writer?.close()
            }
            prune()
            val file = File(dir, "${prefix}_${stamp()}.log")
            writer = BufferedWriter(FileWriter(file, false))
            written = 0L
            runCatching { file.setReadable(true, false) }
        }

        private fun prune() {
            val pattern = Regex("""^${Regex.escape(prefix)}_.*\.log$""")
            val files = File(dir).listFiles { f -> pattern.matches(f.name) } ?: return
            // Keep room for the part about to be opened: drop oldest until at most MAX_PARTS-1 remain.
            files
                .sortedBy { it.name }
                .dropLast((MAX_PARTS - 1).coerceAtLeast(0))
                .forEach { runCatching { it.delete() } }
        }

        fun write(line: String) {
            try {
                if (writer == null || written + line.length + 1 > MAX_PART_BYTES) openNew()
                writer?.write(line)
                writer?.write("\n")
                written += line.length + 1
                // Flush every line rather than at the buffer's convenience. The framework stream is
                // sparse — its 8 KB buffer would take an age to fill, so the live part read back from
                // disk stayed empty while older, rotation-flushed parts showed fine (the "empty
                // framework, only older parts work" bug). A crash collector also wants its last lines
                // already on disk when the process it was watching dies mid-buffer.
                writer?.flush()
            } catch (e: Exception) {
                // A single failed line must not tear the reader down; the next write retries.
            }
        }

        /** Closes the current part and opens a fresh one now, keeping the closed part on disk. */
        fun rotate() = openNew()

        fun close() {
            runCatching {
                writer?.flush()
                writer?.close()
            }
            writer = null
        }
    }

    private companion object {
        /** Its own tag, so `adb logcat -s LSPatchShell:V` follows the shell half alone. */
        const val TAG = "LSPatchShell"

        const val MAX_OUTPUT_CHARS = 400_000

        /** ~4 MB per part, eight parts per stream — ~32 MB of history apiece at most. */
        const val MAX_PART_BYTES = 4L * 1024 * 1024
        const val MAX_PARTS = 8

        /** Matches exactly the collector we spawn, so pkill kills stray collectors and nothing else. */
        const val LOGCAT_MATCH = "logcat -b main -b crash -b system"

        val STAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        // "MM-DD HH:MM:SS.mmm  PID  TID L TAG:" — enough of the threadtime header to route by.
        val HEADER = Regex("""^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?):""")
    }
}
