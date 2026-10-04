package frb.axeron.api

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AxManager 运行时日志（轻量、无依赖、跨模块可用）。
 *
 * ## 目标
 * 把「模块安装 / 设备管理特权执行 / 权限路由」等关键链路的 **完整命令、环境变量、
 * 退出码、stdout/stderr** 落盘并保留在内存环形缓冲里，使问题可以在**软件内**直接查看、
 * 复制或导出，而不必依赖 logcat / adb。
 *
 * ## 落盘策略（多候选，任一可写即用）
 *   1. `/sdcard/AxManagerLog/runtime.log`（外部存储，文件管理器里可直接取用）；
 *   2. `<extFiles>/AxManagerLog/runtime.log`（无存储权限时的私有兜底）。
 *
 * ## 隔离声明
 * 本对象为**独立新增模块**：不修改任何既有公共方法，未调用 [init] 时也只在内存里记录、
 * 不会抛异常，对既有行为零影响。
 */
object AxeronRuntimeLog {

    /** 外部存储上的日志目录名。 */
    const val LOG_DIR_NAME = "AxManagerLog"
    private const val FILE_NAME = "runtime.log"
    private const val MAX_LINES = 4000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var sink: File? = null

    @Volatile
    private var dir: File? = null

    /** 是否已完成落盘初始化。 */
    val isReady: Boolean get() = sink != null

    /** 初始化落盘（幂等，可重复调用）。 */
    fun init(context: Context) {
        if (sink != null) return
        val candidates = ArrayList<File>(2)
        runCatching {
            candidates.add(File(Environment.getExternalStorageDirectory(), LOG_DIR_NAME))
        }
        runCatching {
            context.getExternalFilesDir(null)?.let { candidates.add(File(it, LOG_DIR_NAME)) }
        }
        for (c in candidates) {
            val ok = runCatching {
                if (!c.exists()) c.mkdirs()
                val probe = File(c, ".probe")
                probe.writeText("ok")
                probe.delete()
                c.canWrite()
            }.getOrDefault(false)
            if (ok) {
                dir = c
                sink = File(c, FILE_NAME)
                break
            }
        }
        if (sink == null) {
            runCatching {
                val f = context.getExternalFilesDir(null)
                if (f != null) {
                    dir = f
                    sink = File(f, FILE_NAME)
                }
            }
        }
        section("session start")
        i("Log", "file=" + (sink?.absolutePath ?: "unavailable"))
    }

    /** 当前日志目录（供 UI 显示）。 */
    fun dirPath(): String? = dir?.absolutePath

    /** 当前日志文件（供 UI 显示）。 */
    fun filePath(): String? = sink?.absolutePath

    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String) = write("E", tag, message)

    /** 写一条分隔标题，便于在长日志里定位一次操作。 */
    fun section(title: String) = write("-", "AXM", "==================== " + title + " ====================")

    /** 读取内存中的全部日志。 */
    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }

    /** 清空内存与落盘日志。 */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            runCatching { sink?.writeText("") }
        }
    }

    /**
     * 直接落盘一段原始文本（不经过格式化，供「导出/追加外部日志」使用）。
     */
    fun appendRaw(block: String) {
        synchronized(lock) {
            block.split('\n').forEach { buffer.addLast(it) }
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            runCatching {
                val f = sink ?: return
                if (f.length() > MAX_FILE_BYTES) f.writeText("")
                f.appendText(block + "\n")
            }
        }
    }

    private fun write(level: String, tag: String, message: String) {
        val line = fmt.format(Date()) + " " + level + "/" + tag + ": " + message
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            runCatching {
                val f = sink ?: return@runCatching
                if (f.length() > MAX_FILE_BYTES) f.writeText("")
                f.appendText(line + "\n")
            }
        }
        runCatching {
            when (level) {
                "W" -> Log.w("AxM." + tag, message)
                "E" -> Log.e("AxM." + tag, message)
                else -> Log.i("AxM." + tag, message)
            }
        }
    }
}
