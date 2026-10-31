package frb.axeron.manager.ai.axkey

import android.os.Build
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 系统序列号一致性探测。
 *
 * 两条取值通道：
 * 1. 普通 App 直读：ro.serialno / Build.getSerial / ro.boot.serialno、/proc/cmdline、/proc/bootconfig；
 * 2. 直读缺失时，走应用内置的 Axeron/ADB shell 再读一遍 getprop 与启动参数。
 *
 * 判定口径：
 * - 系统侧取 ro.serialno（ADB 通道优先，直读兜底）；
 * - 参考值取启动链参数 androidboot.serialno：/proc/cmdline 优先，其次 /proc/bootconfig，
 *   最后 ro.boot.serialno；不取 /sys/devices/soc0/serial_number——多数高通机该节点是十六进制
 *   芯片 ID，与设备序列号不同域，拿来比对会把正常机器误判成篡改；
 * - 两边都非空且相等才是 PASS；不相等是 TAMPERED；任一缺失是 UNKNOWN；
 * - UNKNOWN 与 TAMPERED 都不会进入设备密钥注册流程。
 */
object DeviceIntegrity {
    private const val TAG = "DeviceIntegrity"
    private const val ADB_TIMEOUT_MS = 8_000L

    /** 同时兼容 cmdline 的 `k=v` 与 bootconfig 的 `k = v`（值可能带引号）。 */
    private val BOOT_SERIAL_RE =
        Regex("(?:^|\\s)androidboot\\.serialno\\s*=\\s*\"?([A-Za-z0-9_.:\\-]+)\"?")

    enum class Verdict(val wire: String) {
        PASS("pass"),
        TAMPERED("tampered"),
        UNKNOWN("unknown"),
    }

    data class Report(
        val verdict: Verdict,
        val serial: String,
        val reference: String,
        val source: String,
        val detail: String,
    )

    private data class ShellProbe(
        val system: String,
        val bootProp: String,
        val cmdline: String,
        val bootconfig: String,
    )

    suspend fun check(): Report {
        val directSystem = firstValid(
            readProp("ro.serialno"),
            readBuildSerial(),
            readProp("ro.boot.serialno"),
        )
        val directBootProp = validOrEmpty(readProp("ro.boot.serialno"))
        val directCmdline = validOrEmpty(parseBootSerial(readText("/proc/cmdline")))
        val directBootconfig = validOrEmpty(parseBootSerial(readText("/proc/bootconfig")))

        val needShell = directSystem.isBlank() ||
            (directCmdline.isBlank() && directBootconfig.isBlank() && directBootProp.isBlank())
        val shell = if (needShell) probeViaAdb() else null

        val system = firstValid(shell?.system.orEmpty(), directSystem)
        val bootProp = firstValid(shell?.bootProp.orEmpty(), directBootProp)
        val cmdline = firstValid(shell?.cmdline.orEmpty(), directCmdline)
        val bootconfig = firstValid(shell?.bootconfig.orEmpty(), directBootconfig)
        val reference = firstValid(cmdline, bootconfig, bootProp)
        val source = when {
            cmdline.isNotBlank() -> "cmdline"
            bootconfig.isNotBlank() -> "bootconfig"
            bootProp.isNotBlank() -> "bootprop"
            else -> "none"
        }
        return when {
            system.isBlank() -> Report(
                Verdict.UNKNOWN, "", reference, source, "直读与ADB通道均未取到系统序列号",
            )
            reference.isBlank() -> Report(
                Verdict.UNKNOWN, system, "", source, "直读与ADB通道均未取到出厂参考序列号",
            )
            system != reference -> Report(
                Verdict.TAMPERED, system, reference, source, "序列号与出厂参考值不一致",
            )
            else -> Report(Verdict.PASS, system, reference, source, "序列号一致")
        }
    }

    /**
     * 通过应用内置的 Axeron/ADB shell 读取一次。
     *
     * 脚本固定、无外部输入；只输出四个带前缀的字段，避免解析整段 shell 文本时误判。
     */
    private suspend fun probeViaAdb(): ShellProbe? {
        if (!runCatching { Axeron.pingBinder() }.getOrDefault(false)) {
            Log.i(TAG, "ADB 通道不可用：Axeron binder 未连接")
            return null
        }
        val script = listOf(
            "echo \"SYS=\$(/system/bin/getprop ro.serialno)\"",
            "echo \"BOOT=\$(/system/bin/getprop ro.boot.serialno)\"",
            "echo \"CMDLINE=\$(/system/bin/cat /proc/cmdline 2>/dev/null)\"",
            "echo \"BOOTCFG=\$(/system/bin/cat /proc/bootconfig 2>/dev/null)\"",
        ).joinToString("; ")
        val result = try {
            AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", script),
                env = runCatching { Axeron.getEnvironment() }.getOrNull(),
                timeoutMs = ADB_TIMEOUT_MS,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ADB 通道读取序列号失败", t)
            return null
        }
        if (result.exitCode != 0 || result.stdout.isBlank()) {
            Log.i(TAG, "ADB 通道未返回有效序列号：exit=${result.exitCode} err=${result.stderr.take(120)}")
            return null
        }
        var system = ""
        var bootProp = ""
        var cmdline = ""
        var bootconfig = ""
        for (line in result.stdout.lineSequence()) {
            when {
                line.startsWith("SYS=") -> system = line.substringAfter("SYS=")
                line.startsWith("BOOT=") -> bootProp = line.substringAfter("BOOT=")
                line.startsWith("CMDLINE=") -> cmdline = line.substringAfter("CMDLINE=")
                line.startsWith("BOOTCFG=") -> bootconfig = line.substringAfter("BOOTCFG=")
            }
        }
        val probe = ShellProbe(
            system = validOrEmpty(system),
            bootProp = validOrEmpty(bootProp),
            cmdline = validOrEmpty(parseBootSerial(cmdline)),
            bootconfig = validOrEmpty(parseBootSerial(bootconfig)),
        )
        Log.i(
            TAG,
            "ADB 通道读取完成：system=${probe.system.isNotBlank()} boot=${probe.bootProp.isNotBlank()} " +
                "cmdline=${probe.cmdline.isNotBlank()} bootconfig=${probe.bootconfig.isNotBlank()}",
        )
        return probe.takeIf {
            it.system.isNotBlank() || it.bootProp.isNotBlank() ||
                it.cmdline.isNotBlank() || it.bootconfig.isNotBlank()
        }
    }

    private fun parseBootSerial(text: String): String {
        if (text.isBlank()) return ""
        return BOOT_SERIAL_RE.find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun firstValid(vararg values: String): String {
        for (value in values) {
            val valid = validOrEmpty(value)
            if (valid.isNotBlank()) return valid
        }
        return ""
    }

    private fun validOrEmpty(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.length < 4 || v.length > 64) return ""
        if (v.equals("unknown", ignoreCase = true) || v.equals("null", ignoreCase = true)) return ""
        return v
    }

    private fun readText(path: String): String = try {
        File(path).readText().trim()
    } catch (t: Throwable) {
        ""
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun readBuildSerial(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial() else Build.SERIAL
    } catch (t: Throwable) {
        ""
    }

    private fun readProp(name: String): String {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val value = (method.invoke(null, name) as? String).orEmpty()
            if (value.isNotBlank()) return value
        } catch (t: Throwable) {
            // 隐藏 API 在部分 ROM 上不可用，继续走 getprop。
        }
        return try {
            val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ""
            } else {
                output
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取属性失败: $name", t)
            ""
        }
    }
}
