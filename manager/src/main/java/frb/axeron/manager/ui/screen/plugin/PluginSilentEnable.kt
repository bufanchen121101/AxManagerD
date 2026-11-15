package frb.axeron.manager.ui.screen.plugin

import android.content.Context
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.AIEngineManager
import frb.axeron.manager.ai.PluginScriptTracer
import frb.axeron.manager.ai.RuntimeCommandTracer
import frb.axeron.manager.ai.StraceHelper
import frb.axeron.manager.ai.UninstallRollback
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 【1.4.1】「启用模块」= 直接生效 + 后台静默抓取。
 *
 * 与「点击运行模块」的真实运行段完全同源（见 ExecutePluginAction.kt 的
 * 「开启运行时指令采集」那一段）：模块脚本带 `sh -x` 在**真实环境**里跑一次，
 * stderr 里的 `+ 命令` 行就是它真实执行的每条指令，采集器逐行收下，
 * 结束后落盘到内部目录 `filesDir/rollback/{dirId}/`，同时写进 AI 运行时缓存。
 *
 * 三条硬约束（照抄运行段的语义，别自己发挥）：
 *  1. **不挂预演沙箱**：不加 DryRunSandbox，不用 strace 另跑一遍 —— 脚本就是真跑；
 *  2. **不拦截、不决策**：不弹任何分析 / 决策 / 进度提示框，抓完只落盘，绝不影响启用结果；
 *  3. **不受总开关与白名单影响**：运行段的真实运行采集是在 AI 总开关关闭时**照样落盘**的
 *     （见 ExecutePluginAction 里「v1.9.1 总开关关闭时也要落盘」），这里保持一致。
 *
 * 抓取在独立后台协程里做，启用动作本身立即返回，界面只看到开关状态变化。
 */
object PluginSilentEnable {

    private const val TAG = "PluginSilentEnable"

    /** 启用时会真实执行的脚本（Igniter 用 busybox setsid 拉起），按优先级取第一个存在的。 */
    private val ENABLE_SCRIPTS = listOf("service.sh", "post-fs-data.sh")

    /** 后台抓取用的独立作用域：不占用界面协程，界面退出也不会中断。 */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一个后台抓取，避免多个模块同时启用时互相踩采集器。 */
    private val captureMutex = Mutex()

    /** 统一的 shell 执行入口（与项目既有做法一致）。 */
    private suspend fun execSh(cmd: String): String {
        return try {
            AxeronPluginService.execProcessSafe(
                cmd = arrayOf("/system/bin/sh", "-c", cmd),
                env = Axeron.getEnvironment()
            ).stdout
        } catch (t: Throwable) {
            Log.e(TAG, "execSh 失败: $cmd", t)
            ""
        }
    }

    /**
     * 启用一个模块。
     *
     * 只做「写标记 + ignite」这两步真正生效的动作，毫秒级返回；
     * 抓取另起后台协程，绝不拖慢开关。
     */
    suspend fun enable(context: Context, plugin: PluginInfo): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val ok = AxeronPluginService.togglePlugin(plugin.dirId, true, plugin.backup)
                Log.i(TAG, "togglePlugin(${plugin.dirId}, true) = $ok")
                if (!ok) {
                    return@withContext Result.failure(
                        IllegalStateException("togglePlugin 返回 false（${plugin.prop.name}）")
                    )
                }
                try {
                    AxeronPluginService.igniteSuspendService()
                } catch (t: Throwable) {
                    // ignite 失败不影响「已启用」这个事实，与原有逻辑一致
                    Log.e(TAG, "igniteSuspendService 失败（启用已生效）", t)
                }
                scheduleSilentCapture(context.applicationContext, plugin)
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "启用失败 dirId=${plugin.dirId}", t)
                Result.failure(t)
            }
        }

    /** 启用成功后，把「真实运行段采集」丢到后台，不阻塞、不提示。 */
    private fun scheduleSilentCapture(context: Context, plugin: PluginInfo) {
        bgScope.launch {
            // 给 Igniter 一点时间拉起服务，尽量不让两次执行撞在一起
            delay(1500L)
            try {
                captureMutex.withLock { captureRealRun(context, plugin) }
            } catch (t: Throwable) {
                Log.e(TAG, "后台静默抓取异常 dirId=${plugin.dirId}", t)
            }
        }
    }

    /**
     * 真实执行一次启用脚本并采下它执行的每条指令 —— 与「运行模块」的**分析阶段**同源。
     *
     * 【v1.5.4 重做 · 用户要求「按运行按钮的拦截方式修启用」】
     * 旧实现只跑 `sh -x`：xtrace 只能看到**同一个 shell 进程内**的命令，而启用脚本
     * （尤其带壳的 service.sh）真正的动作都在子 shell / 解密后的 payload 里，
     * 于是出现「开关点开了、却几乎采不到真指令」。
     *
     * 现在改为与「运行」按钮完全同款的抓取链路（实现见 [PluginScriptTracer]）：
     *   ① strace `-f -e trace=execve` 抓整个进程树的真实外部命令（能穿加密壳）；
     *   ② 预演沙箱（[DryRunSandbox]）让写调用只被记录、不落到系统上；
     *   ③ `sh -x` 补抓 shell 内建命令（与 strace 互补）。
     *
     * 时序说明：启用动作（togglePlugin + ignite）在调用本方法**之前**已经真跑完，
     * 这里的复跑只用于采集，不会改变「已启用」这个事实，也不会重复生效。
     */
    private suspend fun captureRealRun(context: Context, plugin: PluginInfo) {
        val pluginPath = try {
            File(
                PathHelper.getWorkingPath(
                    Axeron.getAxeronInfo().isRoot(),
                    AxeronApiConstant.folder.PARENT_PLUGIN
                ),
                plugin.dirId
            )
        } catch (t: Throwable) {
            Log.e(TAG, "取模块路径失败 dirId=${plugin.dirId}", t)
            return
        }

        // 插件目录是 com.android.shell 的私有目录，App 进程 File().exists() 恒 false，
        // 必须用 shell 权限 test -f 探测。
        val existing = ArrayList<String>(ENABLE_SCRIPTS.size)
        for (script in ENABLE_SCRIPTS) {
            val out = execSh("test -f \"${pluginPath.absolutePath}/$script\" && echo YES")
            if (out.contains("YES")) existing.add(script)
        }
        if (existing.isEmpty()) {
            Log.i(TAG, "该模块无 service.sh / post-fs-data.sh，跳过静默抓取")
            return
        }
        // 只抓一个脚本：与「运行」只抓 action.sh 一个保持同等耗时与同等形态。
        val script = existing.first()

        // 采集器是全局单例：用户此刻正在「运行模块」并采集时，绝不插进去重置它的状态。
        if (RuntimeCommandTracer.enabled) {
            Log.i(TAG, "运行链路正在采集，跳过本次启用抓取 dirId=${plugin.dirId}")
            return
        }

        Log.i(TAG, "启用抓取开始（strace+沙箱+sh -x）dirId=${plugin.dirId} script=$script")

        // 与「运行」按钮同一条链路：strace 抓 execve（穿壳）、沙箱拦写、sh -x 补内建。
        // tracePluginScript 内部已负责：begin()/end()、strace 释放与执行、沙箱日志解析、
        // sh -x 采集，以及「抓到就落盘到卸载恢复名单」。
        val trace = PluginScriptTracer.tracePluginScript(
            context = context,
            plugin = plugin,
            pluginPath = pluginPath.absolutePath,
            scriptName = script,
            onStage = { /* 后台静默：不提示 */ },
        )

        Log.i(TAG, "启用抓取完成 dirId=${plugin.dirId} count=${trace.count}")

        // 写进 AI 运行时缓存（与运行段同一字段、同一区块口径）
        if (trace.count > 0) {
            try {
                val block = trace.displayBlock.ifBlank { trace.promptBlock }
                if (block.isNotBlank()) AIEngineManager.updateRuntimeTrace(block)
            } catch (t: Throwable) {
                Log.e(TAG, "写入运行时指令缓存失败", t)
            }
        }

        // 脚本原文一并缓存（运行段同样会存 updateScriptText），AI 问答时能对照原文。
        try {
            val scriptText = execSh("cat \"${pluginPath.absolutePath}/$script\" 2>/dev/null")
            if (scriptText.isNotBlank()) {
                AIEngineManager.updateScriptText(scriptText)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "写入脚本原文明细失败", t)
        }
    }
}
