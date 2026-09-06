package frb.axeron.manager.ai

import android.content.Context
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.server.PluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 模块脚本 strace 拦截器：复用 ExecutePluginAction 里已验证可用的 strace 抓取逻辑，
 * 抽取成公共函数，供「运行模块（action.sh）」和「启用模块（service.sh / post-fs-data.sh）」共用。
 *
 * 原理：后台 setsid 启动静态 strace 跟踪目标脚本（-f 跟踪进程树，-e trace=execve 只抓
 * execve），随后每 500ms 轮询日志，连续 3 秒无新 execve 即判定抓取完成，最后 kill 后台进程组
 * + pkill 清理。逐行把 execve 解析成真实指令喂给 RuntimeCommandTracer，返回 prompt 文本块。
 */
object PluginScriptTracer {
    private const val TAG = "PluginScriptTracer"

    /** 每次 shell 命令的最大执行时间（毫秒），超时强制返回，避免 waitFor() 无限阻塞卡死分析。 */
    private const val EXEC_TIMEOUT_MS = 8000L

    data class TraceResult(
        val promptBlock: String,
        val count: Int,
    )

    /** 落盘调试日志（vivo ratelimit 会丢弃 Log.i，写文件不受限） */
    private const val DEBUG_LOG = "/data/local/tmp/ax_trace_debug.log"
    private suspend fun debug(msg: String) {
        try {
            val line = "${System.currentTimeMillis()} $msg"
            AxeronPluginService.execProcessSafe(
                cmd = arrayOf("/system/bin/sh", "-c", "echo \"$line\" >> $DEBUG_LOG 2>/dev/null"),
                env = Axeron.getEnvironment()
            )
        } catch (_: Throwable) { }
    }

    /** 跟踪模块的某个脚本（如 action.sh / service.sh / post-fs-data.sh），抓真实执行指令。 */
    suspend fun tracePluginScript(
        context: Context,
        plugin: PluginInfo,
        pluginPath: String,
        scriptName: String,
        onStage: (String) -> Unit,
    ): TraceResult = withContext(Dispatchers.IO) {
        val pluginDirId = plugin.dirId
        // 带超时的 exec 包装：任何 shell 命令最多 EXEC_TIMEOUT_MS 就强制返回，
        // 绝不因 waitFor() 无限阻塞卡死整个分析流程。
        suspend fun execSafe(vararg cmd: String): String {
            return withTimeoutOrNull(EXEC_TIMEOUT_MS) {
                AxeronPluginService.execProcessSafe(
                    cmd = arrayOf("/system/bin/sh", "-c", *cmd),
                    env = Axeron.getEnvironment()
                ).stdout
            } ?: ""
        }
        // strace 专用：命令内部用 `timeout -k 1 $timeoutSec` 限时，协程层超时必须更长
        // （timeoutSec + 缓冲），否则配置的抓取时长会被 8 秒默认超时提前打断。
        suspend fun execSafeLong(timeoutMs: Long, cmd: String): String {
            return withTimeoutOrNull(timeoutMs) {
                AxeronPluginService.execProcessSafe(
                    cmd = arrayOf("/system/bin/sh", "-c", cmd),
                    env = Axeron.getEnvironment()
                ).stdout
            } ?: ""
        }

        RuntimeCommandTracer.begin()
        try {
            debug("== tracePluginScript 开始 script=$scriptName pluginDir=$pluginDirId ==")
            onStage("正在释放分析引擎…")
            debug("stage=释放分析引擎")
            val apkPath = context.packageCodePath
            val stracePath = StraceHelper.destPath()
            val extractCmd = StraceHelper.buildExtractCmd(apkPath)
            Log.i(TAG, "释放 strace: $extractCmd")
            debug("释放 strace: $extractCmd")
            execSafe(extractCmd)
            debug("释放 strace 完成")
            val straceReady = execSafe("test -x \"$stracePath\" && echo READY").contains("READY")
            debug("strace READY=$straceReady")
            if (!straceReady) {
                Log.w(TAG, "strace 二进制释放失败")
                debug("strace 二进制释放失败, return")
                return@withContext TraceResult("", 0)
            }

            // 【同步 timeout 方案】前台跑 strace，timeout 到点强制退出，绝不卡死。
            // 常驻 service.sh（while true）抓"启动后前几秒核心指令"即可。
            // 抓取时长改为「AI 设置界面可配置」（AIConfigStore.traceTimeoutSeconds），默认 8 秒。
            onStage("正在跟踪模块真实执行…")
            debug("stage=跟踪模块真实执行")
            val traceLog = "/data/local/tmp/ax_trace_${pluginDirId}.log"
            val timeoutSec = AIConfigStore.traceTimeoutSeconds
            val straceCmd = StraceHelper.buildTraceCmdSync(
                stracePath, pluginPath, traceLog, scriptName, timeoutSeconds = timeoutSec
            )
            Log.i(TAG, "strace 同步启动(timeout ${timeoutSec}s): $straceCmd")
            debug("strace 同步启动(timeout ${timeoutSec}s): $straceCmd")
            // exec 前先清空旧 debug 头（仅第一次）
            val t0 = System.currentTimeMillis()
            runCatching {
                // strace 命令内部用 `timeout -k 1 $timeoutSec` 限时，协程层超时必须
                // 大于它（+5 秒缓冲），否则配置的抓取时长会被 8 秒的默认 execSafe 超时提前打断。
                execSafeLong((timeoutSec + 5) * 1000L, straceCmd)
            }.onFailure {
                Log.e(TAG, "strace 执行异常", it)
            }
            debug("strace 同步执行返回，耗时=${System.currentTimeMillis() - t0}ms")

            onStage("正在解析真实指令…")
            debug("stage=解析真实指令")
            val traceText = runCatching {
                execSafe("cat \"$traceLog\" 2>/dev/null")
            }.getOrDefault("")
            debug("cat 日志完成，长度=${traceText.length}")
            traceText.split('\n').forEach { line ->
                RuntimeCommandTracer.addStraceLine(line)
            }
            runCatching {
                execSafe("rm -f \"$traceLog\" 2>/dev/null; pkill -9 -f strace-arm 2>/dev/null; pkill -9 -f strace-arm64 2>/dev/null")
            }
            debug("清理完成，strace 通道 count=${RuntimeCommandTracer.snapshot().size}")

            // ============ 补充：sh -x（xtrace）运行时拦截 ============
            // strace 只抓 execve「进程启动链」。对加密/动态解密脚本（base64/openssl/eval/shc
            // 解密后才真正执行命令、且在同一 sh 进程内部用变量拼命令再 eval 的场景），strace
            // 可能遗漏脚本内部「解密后真正执行的每条命令」。这里用 `sh -x`（开启 shell xtrace）
            // 真实重新执行脚本，把「解密/展开后逐条真实执行的命令」从前置的 stderr 里逐行抓下来，
            // 与 strace 互补，构成完整运行时拦截。
            //
            // 关键：常驻 service.sh 往往是 `while true` 死循环，sh -x 直接跑会无限执行。
            // 因此用 `timeout -k 1` 限时（抓"启动后前几秒核心指令"即可），到点 SIGKILL 强杀，
            // 绝不卡死。这与 v33 修复 strace 卡死是同一机制。
            debug("补充 sh -x（xtrace）运行时拦截 script=$scriptName")
            onStage("正在运行时拦截（sh -x）…")
            runCatching {
                if (RuntimeCommandTracer.enabled) {
                    // 只丢弃 stdout，保留 stderr：sh -x 的 xtrace 行（`+ cmd`）正是发往 stderr 的，
                    // execWithIO 的 onStderr 才能逐行捕获；若 `2>&1` 会把它们一并重定向吞掉导致抓不到。
                    // 与 strace 段一致：先 sed 加速 sleep（生成临时副本），再 sh -x 追踪，避免被大量 sleep 拖延。
                    val xtraceRun = "${traceLog}.xtrace.sh"
                    val xtraceCmd = "cd \"${pluginPath}\"; " +
                        "sed 's/\\([[:space:]]\\)sleep[[:space:]][0-9.]*/\\1sleep 0.01/g' \"./$scriptName\" > \"$xtraceRun\" 2>/dev/null; " +
                        "timeout -k 1 $timeoutSec sh -x \"$xtraceRun\" >/dev/null; " +
                        "rm -f \"$xtraceRun\"; exit 0"
                    AxeronPluginService.execWithIO(
                        cmd = xtraceCmd,
                        onStdout = { _ -> },
                        onStderr = { chunk ->
                            if (RuntimeCommandTracer.enabled) {
                                chunk.split('\n').forEach { line ->
                                    if (RuntimeCommandTracer.isTraceLine(line)) {
                                        RuntimeCommandTracer.addTrace(line)
                                    }
                                }
                            }
                        },
                        hideStderr = false,
                    )
                }
            }.onFailure {
                Log.e(TAG, "sh -x 拦截失败", it)
            }
            debug("sh -x 拦截完成，累计 count=${RuntimeCommandTracer.snapshot().size}")
            val block = RuntimeCommandTracer.toPromptBlock()
            val count = RuntimeCommandTracer.snapshot().size
            Log.i(TAG, "strace 抓取到 $count 条真实指令")
            debug("strace 抓取到 $count 条真实指令")

            // ============ v1.1.0 卸载回滚：拦截收尾落盘 ============
            // 把本次捕获的真实执行指令持久化到 App 私有目录（filesDir/rollback/{dirId}/），
            // 供模块卸载时「AI 生成恢复脚本」使用。文件名取自模块 name（特殊字符换 _）。
            // 落盘失败不影响主流程（回滚是增强功能，兜底用）。
            if (count > 0) {
                try {
                    UninstallRollback.persistLog(context, plugin, RuntimeCommandTracer.snapshot())
                } catch (t: Throwable) {
                    Log.e(TAG, "卸载回滚日志落盘失败", t)
                    debug("persistLog 失败: ${t.message}")
                }
            }
            return@withContext TraceResult(block, count)
        } catch (t: Throwable) {
            Log.e(TAG, "strace 抓取失败", t)
            debug("tracePluginScript 异常: ${t.message}")
            return@withContext TraceResult("", 0)
        } finally {
            RuntimeCommandTracer.end()
        }
    }

    /** 读明文脚本兜底（strace 没抓到、或明文脚本时）。 */
    suspend fun readScript(pluginPath: String, scriptName: String): String =
        runCatching {
            withTimeoutOrNull(EXEC_TIMEOUT_MS) {
                AxeronPluginService.execProcessSafe(
                    cmd = arrayOf("/system/bin/sh", "-c", "cat \"${pluginPath}/$scriptName\" 2>/dev/null"),
                    env = Axeron.getEnvironment()
                ).stdout
            } ?: ""
        }.getOrDefault("")

    /** 构造喂给 AI 分析的命令上下文（真实指令优先，无则退回 cat 明文脚本）。 */
    suspend fun buildAnalyzeCmd(
        pluginPath: String,
        scriptName: String,
        baseCmd: String,
        trace: TraceResult,
    ): String {
        val catScript = if (trace.count > 0) "" else readScript(pluginPath, scriptName)
        return when {
            trace.count > 0 -> "$baseCmd\n\n# === 运行时真实执行指令流 ===\n${trace.promptBlock}"
            catScript.isNotBlank() -> "$baseCmd\n\n# === $scriptName 脚本内容 ===\n$catScript"
            else -> baseCmd
        }
    }

    /** 把抓到的真实指令流写入 AI 引擎缓存，供弹窗问答读取。 */
    fun cacheTrace(trace: TraceResult, catScript: String = "") {
        if (trace.count > 0) {
            AIEngineManager.updateRuntimeTrace(trace.promptBlock)
        } else if (catScript.isNotBlank()) {
            AIEngineManager.updateRuntimeTrace(catScript)
        }
    }
}