package frb.axeron.manager.features.runtime.sensor

import android.util.Log
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.features.runtime.model.RuntimeSensorType
import org.json.JSONObject

/**
 * 采集器实现（对应设计文档 §4.2，Q2 定案：8 种全上）。
 *
 * 每个采集器只干一件事：跑一条只读 shell，把结果解析成 JSONObject。
 * 全部只读，禁止写系统节点（写操作归模块自己的脚本）。
 *
 * 注意：所有采集走 execWithIO（内部已带超时处理），单条超时 3s，
 * 避免高频采集打爆 shell（见 §5.1.1 phantom 限制）。
 */
object RuntimeSensors {

    private const val TAG = "RuntimeSensors"
    private const val TIMEOUT_MS = 3_000L

    /**
     * 执行采集。
     *
     * @return 采集结果；失败返回带 error 字段的 JSONObject（不抛异常）
     */
    suspend fun sample(type: String): JSONObject {
        val out = JSONObject()
        return runCatching {
            when (type) {
                RuntimeSensorType.CPU -> sampleCpu()
                RuntimeSensorType.MEMORY -> sampleMemory()
                RuntimeSensorType.THERMAL -> sampleThermal()
                RuntimeSensorType.BATTERY -> sampleBattery()
                RuntimeSensorType.NETWORK -> sampleNetwork()
                RuntimeSensorType.STORAGE -> sampleStorage()
                RuntimeSensorType.SCREEN -> sampleScreen()
                RuntimeSensorType.PROCESS -> sampleProcess()
                else -> JSONObject().put("error", "未知采集器类型：$type")
            }
        }.getOrElse { e ->
            Log.e(TAG, "采集失败：$type", e)
            JSONObject().put("error", e.toString())
        }
    }

    // ---------------------------------------------------------------------
    // CPU
    // ---------------------------------------------------------------------
    private suspend fun sampleCpu(): JSONObject {
        val load = sh("cat /proc/loadavg")
        val o = JSONObject()
        val parts = load.split(" ")
        if (parts.size >= 3) {
            o.put("load1", parts[0].toDoubleOrNull() ?: 0.0)
            o.put("load5", parts[1].toDoubleOrNull() ?: 0.0)
            o.put("load15", parts[2].toDoubleOrNull() ?: 0.0)
        }
        // 在线 CPU 数与当前主频
        val online = sh("cat /sys/devices/system/cpu/online").trim()
        o.put("online", online)
        val freq = sh("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").trim()
        o.put("freq0KHz", freq.toLongOrNull() ?: 0L)
        return o
    }

    // ---------------------------------------------------------------------
    // 内存
    // ---------------------------------------------------------------------
    private suspend fun sampleMemory(): JSONObject {
        val raw = sh("cat /proc/meminfo")
        val o = JSONObject()
        for (line in raw.lineSequence()) {
            val kv = line.split(":", limit = 2)
            if (kv.size < 2) continue
            val key = kv[0].trim()
            val value = kv[1].trim().removeSuffix("kB").trim().toLongOrNull() ?: continue
            when (key) {
                "MemTotal" -> o.put("total", value)
                "MemAvailable" -> o.put("available", value)
                "MemFree" -> o.put("free", value)
                "SwapTotal" -> o.put("swapTotal", value)
                "SwapFree" -> o.put("swapFree", value)
                "Buffers" -> o.put("buffers", value)
                "Cached" -> o.put("cached", value)
            }
        }
        return o
    }

    // ---------------------------------------------------------------------
    // 温度
    // ---------------------------------------------------------------------
    private suspend fun sampleThermal(): JSONObject {
        val o = JSONObject()
        // 遍历 thermal zone，取前若干个读数
        val zones = sh("ls /sys/class/thermal/ 2>/dev/null | grep thermal_zone").trim()
        val arr = org.json.JSONArray()
        var idx = 0
        for (z in zones.lineSequence()) {
            if (z.isBlank()) continue
            if (idx >= 8) break
            val tmp = sh("cat /sys/class/thermal/$z/temp 2>/dev/null").trim()
            val value = tmp.toLongOrNull() ?: continue
            val obj = JSONObject()
            obj.put("zone", z)
            // 多数机型是千分之一摄氏度
            obj.put("tempC", if (value > 1000) value / 1000.0 else value.toDouble())
            arr.put(obj)
            idx++
        }
        o.put("zones", arr)
        return o
    }

    // ---------------------------------------------------------------------
    // 电池
    // ---------------------------------------------------------------------
    private suspend fun sampleBattery(): JSONObject {
        val base = "/sys/class/power_supply/battery"
        val o = JSONObject()
        o.put("level", sh("cat $base/capacity 2>/dev/null").trim().toIntOrNull() ?: -1)
        o.put("temp", sh("cat $base/temp 2>/dev/null").trim().toIntOrNull() ?: -1)
        o.put("voltage", sh("cat $base/voltage_now 2>/dev/null").trim().toLongOrNull() ?: -1L)
        o.put("current", sh("cat $base/current_now 2>/dev/null").trim().toLongOrNull() ?: -1L)
        o.put("status", sh("cat $base/status 2>/dev/null").trim())
        o.put("health", sh("cat $base/health 2>/dev/null").trim())
        return o
    }

    // ---------------------------------------------------------------------
    // 网络
    // ---------------------------------------------------------------------
    private suspend fun sampleNetwork(): JSONObject {
        val o = JSONObject()
        val raw = sh("cat /proc/net/dev")
        var rx = 0L
        var tx = 0L
        for (line in raw.lineSequence()) {
            val idx = line.indexOf(":")
            if (idx < 0) continue
            val iface = line.substring(0, idx).trim()
            if (iface == "lo") continue
            val nums = line.substring(idx + 1).trim().split(Regex("\\s+"))
            if (nums.size < 9) continue
            rx += nums[0].toLongOrNull() ?: 0L
            tx += nums[8].toLongOrNull() ?: 0L
        }
        o.put("rxBytes", rx)
        o.put("txBytes", tx)
        o.put("proxy", sh("settings get global http_proxy").trim())
        return o
    }

    // ---------------------------------------------------------------------
    // 存储
    // ---------------------------------------------------------------------
    private suspend fun sampleStorage(): JSONObject {
        val raw = sh("df -k 2>/dev/null")
        val arr = org.json.JSONArray()
        for (line in raw.lineSequence()) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6) continue
            // Filesystem 1K-blocks Used Available Use% Mounted
            val total = cols[1].toLongOrNull() ?: continue
            val used = cols[2].toLongOrNull() ?: continue
            val avail = cols[3].toLongOrNull() ?: continue
            val obj = JSONObject()
            obj.put("mount", cols[5])
            obj.put("totalKB", total)
            obj.put("usedKB", used)
            obj.put("availKB", avail)
            arr.put(obj)
        }
        val o = JSONObject()
        o.put("partitions", arr)
        return o
    }

    // ---------------------------------------------------------------------
    // 屏幕
    // ---------------------------------------------------------------------
    private suspend fun sampleScreen(): JSONObject {
        val o = JSONObject()
        val size = sh("wm size").trim()
        o.put("sizeRaw", size)
        val density = sh("wm density").trim()
        o.put("densityRaw", density)
        val state = sh("dumpsys power 2>/dev/null | grep -m1 'mWakefulness='").trim()
        o.put("wakefulness", state.substringAfter("=", "").trim())
        return o
    }

    // ---------------------------------------------------------------------
    // 进程
    // ---------------------------------------------------------------------
    private suspend fun sampleProcess(): JSONObject {
        val o = JSONObject()
        val top = sh("top -b -n 1 -o %CPU,%MEM,CMDLINE 2>/dev/null | head -n 12")
        o.put("topRaw", top.trim())
        val axeron = sh("ps -A 2>/dev/null | grep -i axeron | head -n 20")
        o.put("axeronRaw", axeron.trim())
        val zombie = sh("ps -A -o STAT,PID,NAME 2>/dev/null | grep '^Z' | head -n 20")
        o.put("zombieRaw", zombie.trim())
        return o
    }

    // ---------------------------------------------------------------------
    // 底层：执行只读命令
    // ---------------------------------------------------------------------
    private suspend fun sh(cmd: String): String {
        return runCatching {
            AxeronPluginService.execWithIO(
                cmd = cmd,
                useBusybox = true,
                hideStderr = true,
            ).out
        }.getOrElse {
            Log.w(TAG, "命令失败：$cmd -> ${it.message}")
            ""
        }
    }
}