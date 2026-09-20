package frb.axeron.manager.util

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Overlay 功能专用日志。
 *
 * 双重输出：
 *  1. logcat（TAG = `AxOverlay`，可 `logcat -s AxOverlay` 过滤）；
 *  2. **落盘文件**（默认 `/sdcard/AxManagerD/logs/overlay.log`）——
 *     便于用户无需 adb 也能直接把日志文件发出来定位问题。
 *
 * 落盘策略：
 *  - 追加写，不覆盖，单文件超过 [MAX_BYTES] 时滚动成 `.1`（只保留一代）；
 *  - 全部 IO 在后台单线程执行，调用方（主线程/协程）**永不阻塞**；
 *  - 任何异常静默吞掉（日志本身绝不能拖垮功能）。
 */
object OverlayLog {

    const val TAG = "AxOverlay"

    /** 内存环形缓冲容量。 */
    private const val RING_CAPACITY = 500

    /** 单文件上限（512KB），超出滚动。 */
    private const val MAX_BYTES = 512L * 1024L

    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 落盘目录（外部存储，免权限可见）。 */
    private val logDir: File
        get() = File(Environment.getExternalStorageDirectory(), "AxManagerD/logs")

    private val logFile: File
        get() = File(logDir, "overlay.log")

    /** 单线程串行落盘，避免多线程写坏文件。 */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "AxOverlayLog").apply { isDaemon = true }
    }

    /** 是否启用落盘（可关闭以减少 IO）。 */
    @Volatile
    var fileEnabled: Boolean = true

    // -----------------------------------------------------------------------
    // 对外 API
    // -----------------------------------------------------------------------

    fun d(msg: String) = write("D", msg)
    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)
    fun e(msg: String, tr: Throwable? = null) =
        write("E", if (tr == null) msg else "$msg\n${Log.getStackTraceString(tr)}")

    /** 记录一次「关键操作」开始，返回 token 供 [end] 配对。 */
    fun begin(tag: String, detail: String = ""): Long {
        val t = System.currentTimeMillis()
        i(">> [$tag] begin ${if (detail.isEmpty()) "" else detail}")
        return t
    }

    /** 与 [begin] 配对，输出耗时与结果。 */
    fun end(tag: String, token: Long, result: String = "ok") {
        val cost = System.currentTimeMillis() - token
        i("<< [$tag] end ${cost}ms -> $result")
    }

    /** 导出当前内存环形缓冲。 */
    @Synchronized
    fun dump(): String = ring.joinToString("\n")

    /** 落盘文件路径（供 UI/提示展示）。 */
    fun logFilePath(): String = logFile.absolutePath

    /** 清空日志。 */
    fun clear() {
        runCatching { writer.execute { runCatching { logFile.writeText("") } } }
        synchronized(ring) { ring.clear() }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun write(level: String, msg: String) {
        val ts = timeFmt.format(Date())
        val line = "$ts $level/$TAG: $msg"

        runCatching { Log.println(levelToPriority(level), TAG, msg) }

        synchronized(ring) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }

        if (!fileEnabled) return
        writer.execute {
            runCatching {
                if (!logDir.exists()) logDir.mkdirs()
                if (logFile.exists() && logFile.length() > MAX_BYTES) {
                    val bak = File(logDir, "overlay.log.1")
                    runCatching { bak.delete() }
                    runCatching { logFile.renameTo(bak) }
                }
                logFile.appendText(line + "\n")
            }
        }
    }

    private fun levelToPriority(level: String): Int = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        "E" -> Log.ERROR
        else -> Log.VERBOSE
    }
}