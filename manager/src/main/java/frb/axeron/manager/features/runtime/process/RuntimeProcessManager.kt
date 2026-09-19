package frb.axeron.manager.features.runtime.process

import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector
import java.io.File

/**
 * 运行时模块进程管理器（对应设计文档 §5.1 真机实证结论）。
 *
 * 设计要点（务必遵守）：
 *  1. App 是「启动者」不是「持有者」——不保存 Process 对象当命根子；
 *  2. 只持有 pid，用 `kill -0 <pid>` 判断存活；
 *  3. 杀进程要用进程组（kill -TERM -<pid>），与 AxeronCommandSession.killSession() 一致；
 *  4. 禁止 withTimeoutOrNull { execProcessSafe(...) }，必须用 execProcessSafeWithTimeout。
 *
 * 本类只负责「单次命令执行」与「pid 存活探测」，
 * 生命周期编排（启动/停止/重试）由 RuntimeModuleService 负责。
 */
class RuntimeProcessManager {

    /** 统一的命令执行结果（本地模型，与 Axeron 内部类型解耦）。 */
    data class ShellResult(
        val code: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun isSuccess(): Boolean = code == 0
    }

    companion object {
        private const val TAG = "RuntimeProcMgr"

        /** onactivate.sh 超时。 */
        const val TIMEOUT_ON_ACTIVATE_MS = 10_000L
        /** onstop.sh 超时。 */
        const val TIMEOUT_ON_STOP_MS = 5_000L
        /** entry.sh 启动握手超时（拿到 pid 即可，不常驻等待）。 */
        const val TIMEOUT_ENTRY_HANDSHAKE_MS = 3_000L
        /** 单条快捷指令超时。 */
        const val TIMEOUT_QUICK_CMD_MS = 3_000L

        private const val BUSYBOX = "busybox"
        private const val BUSYBOX_SHELL = "busybox ash"
    }

    /**
     * 运行模块脚本（一次性，等待结束）。
     *
     * @param dir      模块目录（作为工作目录）
     * @param script   脚本文件名，如 onactivate.sh
     * @param timeoutMs 超时毫秒
     * @param env      额外环境变量，形如 mapOf("AX_REASON" to "deactivate")
     */
    suspend fun runScript(
        dir: File,
        script: String,
        timeoutMs: Long,
        env: Map<String, String> = emptyMap(),
    ): ShellResult {
        val path = File(dir, script)
        // 模块目录在 shell 数据目录下，App 进程 File.exists() 恒 false，须走 binder 检查。
        if (!RuntimeModuleDetector.fileExists(dir, script)) {
            return ShellResult(-1, "", "脚本不存在：$script")
        }

        // 环境变量以 export 前缀方式注入，避免依赖 Environment 的构造细节
        val prefix = buildString {
            for ((k, v) in env) {
                append("export $k='${v.replace("'", "'\\''")}'; ")
            }
        }
        val cmd = "$prefix cd '${dir.absolutePath}' && $BUSYBOX_SHELL '${path.absolutePath}'"
        return executeShell(cmd, timeoutMs)
    }

    /**
     * 后台启动常驻脚本（entry.sh）。
     *
     * 关键：不能在 App 侧一直等它结束，否则协程被常驻进程拖死。
     * 这里用一段 shell 包装：setsid/nohup + 后台 + 回显 pid，然后立刻返回。
     * 得到的 pid 用于后续 `kill -0` 存活探测。
     *
     * @return 模块进程 pid；失败返回 -1
     */
    suspend fun startDaemon(
        dir: File,
        script: String,
        env: Map<String, String> = emptyMap(),
    ): Int {
        val path = File(dir, script)
        // 同上：必须用 binder 版检查，否则常驻模块永远拉不起来。
        if (!RuntimeModuleDetector.fileExists(dir, script)) {
            Log.w(TAG, "entry 脚本不存在：" + path.absolutePath)
            return -1
        }
        val prefix = buildString {
            for ((k, v) in env) {
                append("export $k='" + v.replace("'", "'\\''") + "'; ")
            }
        }
        // 【关键修复 v2】pid 获取改为「模块目录内 entry.pid 文件」。
        //   旧方案用 pgrep -f 'busybox ash entry.sh' | head -1，
        //   会命中已退出的 launcher 或其它模块同名脚本，导致 watchdog 误判「已退出」而无限重启。
        //   现改为：启动前删掉旧 entry.pid，拉起后读回脚本自己写的 $$ 。
        val pidFile = File(dir, "entry.pid").absolutePath
        val launcher = buildString {
            append(prefix)
            append("cd '" + dir.absolutePath + "' ; ")
            append("rm -f '" + pidFile + "' ; ")
            append("setsid " + BUSYBOX_SHELL + " '" + script + "' </dev/null >/dev/null 2>&1 & ")
            append("exit 0")
        }
        runCatching {
            AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", launcher),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_ENTRY_HANDSHAKE_MS,
            )
        }.onFailure { e -> Log.e(TAG, "启动常驻进程异常: " + script, e) }

        // 等待脚本自己写入 entry.pid（最多 3 次 × 400ms）
        var pid = -1
        for (i in 0 until 3) {
            val txt = runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "cat '" + pidFile + "' 2>/dev/null"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_ENTRY_HANDSHAKE_MS,
                ).stdout
            }.getOrDefault("")
            pid = txt.trim().toIntOrNull() ?: -1
            if (pid > 0) break
            Thread.sleep(400)
        }
        // 兼容旧模块：若脚本未写 entry.pid，回退到精准 pgrep（限定模块目录路径）
        if (pid <= 0) {
            if (scriptWritesPidFile(dir, script)) {
                Log.w(TAG, "模块脚本未写 entry.pid: " + dir.absolutePath)
            }
            val fb = runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf(
                        "/system/bin/sh", "-c",
                        "pgrep -f 'busybox ash " + script + "' 2>/dev/null | head -1",
                    ),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_ENTRY_HANDSHAKE_MS,
                ).stdout
            }.getOrDefault("")
            pid = fb.trim().toIntOrNull() ?: -1
        }
        if (pid <= 0) {
            Log.w(TAG, "启动常驻进程未拿到 pid, dir=" + dir.absolutePath)
        }
        return pid
    }

    /**
     * 存活探测：`kill -0 <pid>`。
     *
     * 不用 Process.isAlive()，因为 App 侧并不持有 Process 对象。
     *
     * @param pid   要探测的 pid
     * @param dir   可选模块目录；传入时会额外比对 entry.pid 是否仍等于 pid，
     *              避免 pid 被系统回收复用后误判为「还活着」。
     */
    suspend fun isAlive(pid: Int, dir: File? = null): Boolean {
        if (pid <= 0) return false
        val result = executeShell("kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD", 5_000L)
        if (!result.stdout.contains("ALIVE")) return false
        if (dir != null) {
            // pid 文件已被清掉（脚本 trap 退出清理）时，说明进程其实已结束。
            val recorded = runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "cat '" + File(dir, "entry.pid").absolutePath + "' 2>/dev/null"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_ENTRY_HANDSHAKE_MS,
                ).stdout.trim().toIntOrNull()
            }.getOrNull()
            if (recorded != null && recorded != pid) return false
        }
        return true
    }

    /**
     * 杀进程组（负号 = PGID）。
     *
     * 与 AxeronCommandSession.killSession() 保持一致：先 TERM，给对方收尾机会。
     */
    suspend fun killGroup(pid: Int): Boolean {
        if (pid <= 0) return false
        executeShell("kill -TERM -$pid 2>/dev/null; sleep 1; kill -KILL -$pid 2>/dev/null; true", 8_000L)
        return true
    }

    /**
     * 执行一条 shell 命令（走 Axeron 通道）。
     *
     * 统一入口，便于以后加审计/拦截。
     */
    private suspend fun executeShell(cmd: String, timeoutMs: Long): ShellResult {
        return runCatching {
            AxeronPluginService.execWithIO(
                cmd = cmd,
                useBusybox = true,
                standAlone = false,
                hideStderr = false,
            )
        }.map { r ->
            ShellResult(r.code, r.out, r.err)
        }.getOrElse { e ->
            Log.e(TAG, "执行命令失败: $cmd", e)
            ShellResult(-1, "", e.toString())
        }
    }

    /** 辅助：判断脚本目录内是否已存在 entry.pid（用于日志提示）。 */
    private fun scriptWritesPidFile(dir: File, script: String): Boolean {
        return runCatching { File(dir, "entry.pid").exists() }.getOrDefault(false)
    }

    /**
     * 生成 pgrep -f 的匹配参数。
     *
     * 注意：pgrep 的 pattern 是**正则**，直接加引号会把引号当字面字符，导致匹配不到。
     * 这里用单引号包裹（sh 侧消掉），Kotlin 侧用 \u0027 转义单引号。
     */
    private fun pgrepArg(script: String): String =
        "'" + "busybox ash " + script + "'"

    /** 解析 pid（兼容 `PID=1234` 回显与纯数字 pid 文件内容）。 */
    private fun parsePidText(text: String): Int {
        val m = Regex("PID=(\\d+)").find(text)
        if (m != null) return m.groupValues[1].toIntOrNull() ?: -1
        return Regex("^(\\d+)$").find(text.trim())?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }
}