package frb.axeron.manager.ai

import android.util.Log
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 请求追踪日志（1.4.0 新增）。
 *
 * 用途：AI 对话「发了没反应 / 没回复」时定位到底卡在哪一步。
 * 设计约束（按需求）：**不在界面上展示**，只供开发侧用 adb 抓取，
 * 也不上传到任何服务器。
 *
 * 落盘：/sdcard/Android/data/<包名>/files/ai_trace.log
 *   adb pull /sdcard/Android/data/frb.axeron.manager/files/ai_trace.log
 * 同时打 logcat（tag = AI_TRACE）：
 *   adb logcat -s AI_TRACE
 *
 * 安全：密钥只记录「长度 + 前 4 位掩码」，绝不写完整密钥。
 */
object AiTrace {

    private const val TAG = "AI_TRACE"
    private const val FILE_NAME = "ai_trace.log"
    private const val MAX_BYTES = 512 * 1024L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 日志文件（外部私有目录，adb 可直接 pull；无需任何运行时权限）。 */
    fun file(): File? = try {
        val app = axeronApp
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        File(dir, FILE_NAME)
    } catch (t: Throwable) {
        null
    }

    /** 密钥指纹：只给长度和前 4 位，用于确认「用的是哪把钥匙」，不泄露内容。 */
    fun keyFingerprint(key: String): String {
        if (key.isBlank()) return "<empty>"
        val s = key.trim()
        val head = if (s.length <= 4) "****" else s.take(4) + "****"
        return "$head len=${s.length}"
    }

    fun log(stage: String, msg: String) {
        val line = fmt.format(Date()) + " [" + stage + "] " + msg
        try {
            Log.i(TAG, line)
        } catch (t: Throwable) {
            // logcat 不可用（如被系统限制）也要保证文件写入
        }
        append(line)
    }

    @Synchronized
    private fun append(line: String) {
        try {
            val f = file() ?: return
            if (f.exists() && f.length() > MAX_BYTES) {
                val bak = File(f.parentFile, FILE_NAME + ".1")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
            f.appendText(line + "\n")
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "写追踪日志失败: " + (t.message ?: t.javaClass.simpleName))
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 清空日志（供诊断页调用；界面只显示「已清空」，不展示内容）。 */
    fun clear() {
        try {
            file()?.delete()
            File(file()?.parentFile, FILE_NAME + ".1").delete()
        } catch (t: Throwable) {
        }
    }
}
