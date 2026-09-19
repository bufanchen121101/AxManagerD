package frb.axeron.manager.features.runtime.quick

import android.util.Log
import frb.axeron.api.AxeronPluginService
import org.json.JSONObject

/**
 * 快捷指令执行器（对应设计文档 §4.5.2）。
 *
 * 统一返回 JSON：
 * {
 *   "id": "cpu.load",
 *   "ok": true,
 *   "ts": 1757587200000,
 *   "raw": "0.43 0.51 0.62 2/1245 33120",
 *   "data": { "load1": 0.43, ... }
 * }
 *
 * 约束：
 *  - 只读，绝不写系统节点；
 *  - 单条超时 3s；
 *  - 走 execProcessSafeWithTimeout，不用 withTimeoutOrNull 包 execProcessSafe。
 */
object QuickCommandRunner {

    private const val TAG = "RuntimeQuickCmd"

    /** 单条指令超时。 */
    private const val TIMEOUT_MS = 3_000L

    /**
     * 执行一条指令（按 id）。
     *
     * @param id         指令 id，如 cpu.load
     * @param timeoutMs  可选超时，默认 3s
     */
    suspend fun run(id: String, timeoutMs: Long = TIMEOUT_MS): JSONObject {
        val spec = QuickCommandRegistry.byId(id)
        val result = JSONObject()
        result.put("id", id)
        result.put("ts", System.currentTimeMillis())

        if (spec == null) {
            result.put("ok", false)
            result.put("raw", "")
            result.put("err", "未知指令：$id")
            return result
        }

        val exec = runCatching {
            AxeronPluginService.execWithIO(
                cmd = spec.cmd,
                useBusybox = spec.useBusybox,
                hideStderr = true,
            )
        }.getOrElse { e ->
            Log.e(TAG, "指令执行失败：$id", e)
            AxeronPluginService.ResultExec(-1, "", e.toString())
        }

        val raw = exec.out.trim()
        result.put("ok", exec.isSuccess())
        result.put("raw", raw)
        if (!exec.isSuccess()) {
            result.put("err", exec.err)
        }
        result.put("data", parse(id, raw))
        return result
    }

    /**
     * 轻量解析：把常见指令的 raw 文本转成结构化字段。
     *
     * 解析失败不影响主流程，data 返回空对象即可（UI 仍可显示 raw）。
     */
    private fun parse(id: String, raw: String): JSONObject {
        val data = JSONObject()
        runCatching {
            when (id) {
                "cpu.load" -> {
                    val p = raw.split(" ")
                    if (p.size >= 4) {
                        data.put("load1", p[0].toDoubleOrNull() ?: 0.0)
                        data.put("load5", p[1].toDoubleOrNull() ?: 0.0)
                        data.put("load15", p[2].toDoubleOrNull() ?: 0.0)
                        data.put("processes", p[3])
                    }
                }
                "cpu.online" -> data.put("online", raw)
                "cpu.uptime" -> {
                    val p = raw.split(" ")
                    data.put("uptimeSec", p.getOrNull(0)?.toDoubleOrNull() ?: 0.0)
                    data.put("idleSec", p.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
                }
                "mem.total", "mem.avail", "mem.free" -> {
                    val v = raw.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                    data.put("kb", v)
                }
                "batt.level", "batt.temp", "temp.cpu" -> {
                    data.put("value", raw.toLongOrNull() ?: -1L)
                }
                "batt.volt", "batt.cur" -> {
                    data.put("value", raw.toLongOrNull() ?: -1L)
                }
                "scr.size", "scr.density" -> {
                    data.put("raw", raw)
                }
                "net.proxy" -> data.put("proxy", raw)
                "batt.status", "batt.health" -> data.put("state", raw)
            }
        }.onFailure { e ->
            Log.w(TAG, "解析 $id 失败：${e.message}")
        }
        return data
    }
}