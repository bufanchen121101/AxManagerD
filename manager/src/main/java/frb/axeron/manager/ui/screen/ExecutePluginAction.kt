package frb.axeron.manager.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.fox2code.androidansi.ktx.parseAsAnsiAnnotatedString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.utils.AnsiFilter
import frb.axeron.manager.R
import frb.axeron.manager.ai.AIEngineManager
import frb.axeron.manager.ai.AIChatService
import frb.axeron.manager.ui.component.AxSnackBarHost
import frb.axeron.manager.ui.component.KeyEventBlocker
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Destination<RootGraph>
@Composable
fun ExecutePluginActionScreen(
    navigator: DestinationsNavigator,
    plugin: PluginInfo
) {
    val developerOptionsEnabled = AxeronSettings.getEnableDeveloperOptions()

    // 【运行信任弹窗】点「运行」后先询问用户是否信任该模块：
    //   信任   → 直接执行模块（不做抓取 / 分析，运行全程不弹任何解析类弹窗）；
    //   不信任 → 走原有解析链路（strace + sh -x 抓取 → AI / 规则分析 → 再执行）。
    // null = 尚未选择（弹窗显示中）
    var trustDecision by rememberSaveable { mutableStateOf<Boolean?>(null) }

    // 【v1.4.0 修复③】把「运行模块时弹出的弹窗」归入 AI 设置里的拦截总开关。
    //
    // 总开关（AI 设置 → 拦截总开关 / AIConfigStore.aiEngineEnabled）关闭时，表示
    // 用户不想要任何拦截：此时不再弹「是否信任该模块」，直接按「信任」处理 ——
    // 不做 strace/sh -x 抓取、不做规则/AI 分析、运行过程中零弹窗。
    // 总开关打开时行为完全不变（照旧弹信任弹窗，选「不信任」才走拦截分析）。
    val aiEngineOn = frb.axeron.manager.ai.AIConfigStore.aiEngineEnabled
    LaunchedEffect(aiEngineOn) {
        if (!aiEngineOn && trustDecision == null) {
            trustDecision = true
        }
    }

    // 是否正在运行（等用户选择「信任 / 不信任」期间为 false，避免误显示"正在分析"）
    var isActionRunning by rememberSaveable { mutableStateOf(false) }

    // 解析 / 拦截阶段的进度弹窗是否显示（仅「不信任」链路需要）
    var showAnalysisDialog by rememberSaveable { mutableStateOf(false) }

    // 解析/拦截阶段的进度指示（避免用户以为卡死）
    var stage by rememberSaveable { mutableStateOf("正在准备解析…") }

    val view = LocalView.current
    DisposableEffect(isActionRunning) {
        view.keepScreenOn = isActionRunning
        onDispose {
            view.keepScreenOn = false
        }
    }

    BackHandler(enabled = isActionRunning) {
        // Disable back button if action is running
    }

    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val logContent = rememberSaveable { StringBuilder() }

    // 左上角「AI 分析」按钮状态：把当前输出的指令代码喂给云端 AI，分析是否有危险
    var aiAnalyzing by rememberSaveable { mutableStateOf(false) }
    var aiAnalysisReply by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(trustDecision) {
        // 等用户在信任弹窗里做出选择；未选择前不做任何事
        val trusted = trustDecision ?: return@LaunchedEffect
        if (text.isNotEmpty()) {
            return@LaunchedEffect
        }
        isActionRunning = true
        launch(Dispatchers.IO) {
            try {
                val pluginPath =
                    File(
                        PathHelper.getWorkingPath(
                            Axeron.getAxeronInfo().isRoot(),
                            AxeronApiConstant.folder.PARENT_PLUGIN
                        ), plugin.dirId
                    )
                val pluginBin = "${pluginPath.absolutePath}/system/bin"
                val cmd =
                    "export PATH=$pluginBin:\$PATH; cd \"$pluginPath\"; sh -x ./action.sh; RES=\$?; cd /; exit \$RES"
                // ---- AI 强制分析挂钩（方案 A：运行模块前强制弹整页分析）----
                val analyzer = AxeronPluginService.commandAnalyzer
                // 白名单：命中则完全跳过拦截分析（不跑 strace/sh -x、不弹窗），直接运行，
                // 避免白名单模块运行时仍等待数十秒的抓取时长（用户已明确信任该模块）。
                val isWhitelisted = frb.axeron.manager.ai.AIConfigStore.isWhitelisted(
                    plugin.dirId, plugin.prop.name
                )
                // 只有「不信任」且不在白名单时才做拦截分析；
                // 选择「信任」时完全跳过：不做 strace 抓取、不做 AI / 规则分析、不弹任何弹窗。
                if (!trusted && analyzer != null && !isWhitelisted) {
                    showAnalysisDialog = true
                    // 【方案 A（strace 版）】执行前用静态 strace 跟踪 `sh action.sh` 整个进程树，
                    // 只抓 execve 系统调用，从而 100% 捕获解密/展开后「最终真实启动的命令」
                    // （YTAS 三层壳、base64、openssl、eval、shc 编译 ELF 全部覆盖）。
                    frb.axeron.manager.ai.RuntimeCommandTracer.begin()
                    var straceBlock = ""
                    var straceCount = 0
                    // 【v1.4.0 修复②】预演沙箱目录：在 strace 段内准备、在 finally 的 sh -x 段继续复用，
                    // 故声明在 try 之外（两段抓取都必须挂沙箱，否则任一段都会真跑模块）。
                    var dryRunBin: String? = null
                    try {
                        // 1. 通过 shell 权限从 APK 自身 assets 里抽出静态 strace 二进制到 /data/local/tmp 并 chmod 755
                        stage = "正在释放分析引擎…"
                        val apkPath = view.context.packageCodePath
                        val stracePath = frb.axeron.manager.ai.StraceHelper.destPath()
                        val extractCmd = frb.axeron.manager.ai.StraceHelper.buildExtractCmd(apkPath)
                        android.util.Log.i("AIEngine", "释放 strace: $extractCmd")
                        AxeronPluginService.execProcessSafe(
                            cmd = arrayOf("/system/bin/sh", "-c", extractCmd),
                            env = Axeron.getEnvironment()
                        )
                        // 用 shell 权限检测 strace 是否可执行（app 进程对 /data/local/tmp 无读权限，
                        // File.exists() 会误判 false，故改用 shell 的 test -x）
                        val straceReady = AxeronPluginService.execProcessSafe(
                            cmd = arrayOf("/system/bin/sh", "-c", "test -x \"$stracePath\" && echo READY"),
                            env = Axeron.getEnvironment()
                        ).stdout.contains("READY")
                        if (straceReady) {
                            // 【v1.4.0 修复②】抓取阶段走「预演沙箱」：strace 段与 sh -x 段都是"真的把
                            // action.sh 跑一遍"，不挂沙箱的话写调用（pm disable / settings put /
                            // axeron-dpm suspend …）会在用户点「继续执行」之前就落地 —— 即"选了拦截抓取、
                            // 恶意模块其实已经跑完"。挂上沙箱后，这些写调用只被记录进日志，不落到系统上；
                            // 只读调用仍放行真实命令，保证模块分支判断与抓取完整性不变。
                            // 沙箱准备失败（dryRunBin = null）时退回旧行为，绝不因沙箱失败导致抓不到。
                            dryRunBin = if (frb.axeron.manager.ai.DryRunSandbox.prepare())
                                frb.axeron.manager.ai.DryRunSandbox.DIR else null
                            android.util.Log.i("AIEngine", "预演沙箱 dryRunBin=$dryRunBin")
                            // 2. 【同步 timeout 版】前台跑 strace，timeout 到点强制退出，绝不卡死。
                            // 抛弃旧的 setsid 后台轮询方案——那在 Shizuku binder 下 execProcessSafe 的
                            // waitFor() 会卡死，导致抓不到或阻塞。同步 timeout 对任意模块（含 while true
                            // 常驻）都安全：抓"启动后前 N 秒核心指令"即可。
                            //
                            // 【v1.4.0 关键修复 1：时长必须读设置值】
                            // 旧代码这里**写死 60**，完全没读 AIConfigStore.traceTimeoutSeconds，
                            // 导致用户在 AI 设置里调「拦截抓取时长」滑块对 action.sh 这条链路**完全无效**
                            // （这正是用户反馈"调长调短都抓不全"的直接原因之一）。
                            // 现改为读取设置值，与 PluginScriptTracer（启用模块链路）保持一致。
                            val timeoutSec = frb.axeron.manager.ai.AIConfigStore.traceTimeoutSeconds
                            stage = "正在跟踪模块真实执行（${timeoutSec}s）…"
                            val traceLog = "/data/local/tmp/ax_trace_${plugin.dirId}.log"
                            val straceCmd = frb.axeron.manager.ai.StraceHelper.buildTraceCmdSync(
                                stracePath, pluginPath.absolutePath, traceLog, "action.sh",
                                timeoutSeconds = timeoutSec,
                                // 【v1.4.0 修复②】strace 段挂预演沙箱（argv 上下文，$PATH 不转义）
                                dryRunBin = dryRunBin
                            )
                            android.util.Log.i("AIEngine", "strace 同步启动: $straceCmd")
                            runCatching {
                                AxeronPluginService.execProcessSafe(
                                    cmd = arrayOf("/system/bin/sh", "-c", straceCmd),
                                    env = Axeron.getEnvironment()
                                )
                            }
                            // 3. 读日志，逐行解析 execve 喂给采集器
                            stage = "正在解析真实指令…"
                            val traceText = runCatching {
                                AxeronPluginService.execProcessSafe(
                                    cmd = arrayOf("/system/bin/sh", "-c", "cat \"$traceLog\" 2>/dev/null"),
                                    env = Axeron.getEnvironment()
                                ).stdout
                            }.getOrDefault("")
                            traceText.split('\n').forEach { line ->
                                frb.axeron.manager.ai.RuntimeCommandTracer.addStraceEvent(line)
                            }
                            runCatching {
                                AxeronPluginService.execProcessSafe(
                                    cmd = arrayOf("/system/bin/sh", "-c", "rm -f \"$traceLog\" 2>/dev/null"),
                                    env = Axeron.getEnvironment()
                                )
                            }
                            // 【v1.5.0 后果优先】读取预演沙箱拦下的写调用日志。
                            // 这是「与拼写无关」的权威写意图清单：只要模块的写命令走 PATH，
                            // 写成 `pm disable-user …`、`busybox pm …`、`$BIN disable …` 都会
                            // 落到同一份日志里，不再依赖任何工具名名单。
                            if (dryRunBin != null) {
                                val sandboxLog = runCatching {
                                    AxeronPluginService.execProcessSafe(
                                        cmd = arrayOf("/system/bin/sh", "-c", "cat \"${frb.axeron.manager.ai.DryRunSandbox.LOG}\" 2>/dev/null"),
                                        env = Axeron.getEnvironment()
                                    ).stdout
                                }.getOrDefault("")
                                frb.axeron.manager.ai.RuntimeCommandTracer.addSandboxLog(sandboxLog)
                            }
                        } else {
                            android.util.Log.w("AIEngine", "strace 二进制释放失败，回退 sh -x")
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("AIEngine", "strace 预执行抓取失败，回退 sh -x", t)
                    } finally {
                        // ============ v1.4.0 关键修复 2：sh -x 段「总是执行」，不再只做兜底 ============
                        // 旧逻辑：仅当 `snapshot().isEmpty()`（strace 一条都没抓到）才跑 sh -x。
                        // 问题：strace 抓 execve 时，模块里占比最大的 shell 内建核心操作
                        // （`echo 1 > /sys/.../scaling_governor`、`[ "$x" = "1" ]` 判断、变量赋值、
                        //  `for`/`if` 结构、`sleep` 等待后的写入）**根本不产生 execve**，永远抓不到。
                        // 于是只要 strace 抓到哪怕一条 chmod，就跳过 sh -x → 内建核心指令全丢，
                        // 表现为「抓到的指令不准确/不全」，且「时长调多长都没用」（再长也没 execve）。
                        // 现改为：两段**互补、都跑**——strace 抓外部命令（能穿加密壳），
                        // sh -x 抓内建命令（带完整重定向与参数）。
                        stage = "正在补抓 shell 内建指令（sh -x）…"
                        val xtraceSec = runCatching { frb.axeron.manager.ai.AIConfigStore.traceTimeoutSeconds }
                            .getOrDefault(15)
                        val traceCmd = frb.axeron.manager.ai.StraceHelper.buildXtraceCmd(
                            pluginPath = pluginPath.absolutePath,
                            pluginBin = pluginBin,
                            scriptName = "action.sh",
                            timeoutSeconds = xtraceSec,
                            // 【v1.5.0 修复】这里原先漏传 dryRunBin：strace 段挂了预演沙箱，
                            // sh -x 段却是"裸跑"，导致预演阶段写调用照样落地，且不会被沙箱日志记录。
                            dryRunBin = dryRunBin
                        )
                        runCatching {
                            AxeronPluginService.execWithIO(
                                cmd = traceCmd,
                                onStdout = { _ -> },
                                onStderr = { chunk ->
                                    if (frb.axeron.manager.ai.RuntimeCommandTracer.enabled) {
                                        chunk.split('\n').forEach { line ->
                                            // 【v1.5.0 后果优先】原始行先交给采集器：拼回 mksh 拆行的重定向、
                                            // 从语法结构上认出写入目标（`> path` / `| tee path` / `sed -i`）。
                                            frb.axeron.manager.ai.RuntimeCommandTracer.noteXtrace(line)
                                            if (frb.axeron.manager.ai.RuntimeCommandTracer.isTraceLine(line)) {
                                                frb.axeron.manager.ai.RuntimeCommandTracer.addTrace(line)
                                            } else {
                                                // 【v1.4.8】非 trace 行（脚本自己的错误输出等）会打断
                                                // 「重定向 → 命令」的相邻关系，必须清空未匹配的重定向片段
                                                frb.axeron.manager.ai.RuntimeCommandTracer.onInterleavedStderrLine(line)
                                            }
                                        }
                                    }
                                },
                                hideStderr = false,
                            )
                        }
                        frb.axeron.manager.ai.RuntimeCommandTracer.end()
                    }
                    straceBlock = frb.axeron.manager.ai.RuntimeCommandTracer.toPromptBlock()
                    straceCount = frb.axeron.manager.ai.RuntimeCommandTracer.snapshot().size
                    android.util.Log.i("AIEngine", "执行前 strace 抓取到 $straceCount 条真实指令")
                    android.util.Log.i("AIEngine", "traceBlock=$straceBlock")
                    // ============ v1.1.1 卸载回滚落盘 ============
                    // 关键修复：运行模块（action.sh）场景之前【从未落盘】，导致卸载时
                    // readLog 读不到、报 "No captured command log found"。这里在拦截完成后
                    // 把真实指令流持久化到 App 私有目录，供卸载时「AI 生成恢复脚本」使用。
                    if (straceCount > 0) {
                        try {
                            frb.axeron.manager.ai.UninstallRollback.persistLog(
                                view.context,
                                plugin,
                                frb.axeron.manager.ai.RuntimeCommandTracer.snapshot()
                            )
                        } catch (t: Throwable) {
                            android.util.Log.e("AIEngine", "卸载回滚日志落盘失败", t)
                        }
                    }
                    // ============ 落盘结束 ============
                    // 始终读取脚本明文（供 AI 参考 + 拦截界面原文展示），
                    // 即使 strace 抓到真实指令也保留明文作为补充参考。
                    val catScript = runCatching {
                        AxeronPluginService.execProcessSafe(
                            cmd = arrayOf("/system/bin/sh", "-c", "cat \"${pluginPath.absolutePath}/action.sh\" 2>/dev/null"),
                            env = Axeron.getEnvironment()
                        ).stdout
                    }.getOrDefault("")
                    // 缓存脚本明文，供 AI 分析上下文（脚本原文参考）与拦截界面展示
                    if (catScript.isNotBlank()) {
                        frb.axeron.manager.ai.AIEngineManager.updateScriptText(catScript)
                    }
                    val analyzeCmd = when {
                        straceCount > 0 -> "$cmd\n\n# === 运行时真实执行指令流 ===\n$straceBlock"
                        catScript.isNotBlank() -> "$cmd\n\n# === action.sh 脚本内容 ===\n$catScript"
                        else -> cmd
                    }

                    // 写入全局缓存供弹窗问答读取（优先真实指令流，其次 cat 文本）
                    // v1.4.0 修复④：写入缓存的用「仅核心指令」区块（界面与 AI 问答展示用），
                    // 与「恢复卸载」界面同源、同内容，避免展示层混入环境探测聚合块显得杂乱。
                    if (straceCount > 0) {
                        frb.axeron.manager.ai.AIEngineManager.updateRuntimeTrace(
                            frb.axeron.manager.ai.RuntimeCommandTracer.toDisplayBlock()
                        )
                    } else if (catScript.isNotBlank()) {
                        // 【v1.4.0 修复 · 「拦截结果里一堆现实中不存在的指令」】
                        // 旧实现在"没抓到真实指令"时，把 action.sh 的**原文逐行**灌进采集器
                        // （RuntimeCommandTracer.addTraceLine），当成"真实指令"展示。
                        // 对混淆/加密模块而言，原文是 base64 块、shell 变量拼接、if/for 分支里
                        // **从未被执行**的命令行 —— 于是界面出现大量「根本不存在的指令」，
                        // 而真正执行过的动作一条都没有（因为加密壳把真实命令藏在子 shell 里）。
                        // 现在：不再把原文伪装成指令流。原文已经通过 updateScriptText 单独缓存，
                        // AI 仍然能看到它（作为"脚本内容"而不是"执行指令"），显示层只给一句明确说明。
                        frb.axeron.manager.ai.AIEngineManager.updateRuntimeTrace(
                            "（本次未捕获到可解析的真实执行指令。\n" +
                                "该模块可能是混淆/加密壳（命令在子 shell 内动态展开），\n" +
                                "请以「脚本内容」原文参考；把抓取时长调长可提高捕获率。）"
                        )
                    }

                    val ctx = frb.axeron.api.ai.CommandAnalyzer.CommandContext(
                        source = frb.axeron.api.ai.CommandAnalyzer.CommandContext.Source.ACTION,
                        pluginDirId = plugin.dirId,
                        pluginName = plugin.prop.name,
                    )
                    stage = "正在生成分析报告…"
                    val analysis = analyzer.analyze(analyzeCmd, ctx)
                    if (analysis != null && !analysis.allow) {
                            // 用户选择中止：不执行
                            showAnalysisDialog = false
                            launch(Dispatchers.Main) {
                                text = "已被用户中止"
                            }
                            isActionRunning = false
                            return@launch
                        }
                        // 分析阶段结束，进入执行阶段（此后不再显示进度弹窗）
                        showAnalysisDialog = false
                    }
                // ---- 挂钩结束 ----
                // 开启运行时指令采集：cmd 里已用 `sh -x ./action.sh` 开启 xtrace，
                // 把模块脚本内部真实执行（含加密脚本解密后）的每一条命令打到 stderr。
                stage = "正在执行模块…"
                frb.axeron.manager.ai.RuntimeCommandTracer.begin()
                AxeronPluginService.execWithIO(
                    cmd = cmd,
                    onStdout = {
                        if (AnsiFilter.isScreenControl(it)) { // clear command
                            launch(Dispatchers.Main) {
                                text = AnsiFilter.stripAnsi(it) + "\n"
                            }
                        } else {
                            launch(Dispatchers.Main) {
                                text += "$it\n"
                            }
                        }
                        logContent.append(it).append("\n")
                    },
                    onStderr = {
                        // stderr 里的 `+ ` 开头行就是 xtrace 真实执行指令，逐条喂给采集器（最终喂给 AI）
                        if (frb.axeron.manager.ai.RuntimeCommandTracer.enabled) {
                            it.split('\n').forEach { line ->
                                // 【v1.5.0 后果优先】真实运行段同样按「后果」记录：
                                // 这样界面展示的是「这次运行真的改了哪些文件」，而不是一堆脚手架。
                                frb.axeron.manager.ai.RuntimeCommandTracer.noteXtrace(line)
                                if (frb.axeron.manager.ai.RuntimeCommandTracer.isTraceLine(line)) {
                                    frb.axeron.manager.ai.RuntimeCommandTracer.addTrace(line)
                                } else {
                                    // 【v1.4.8】同上：非 trace 行打断重定向与命令的相邻关系
                                    frb.axeron.manager.ai.RuntimeCommandTracer.onInterleavedStderrLine(line)
                                }
                            }
                        }
                        logContent.append(it).append("\n")
                    }
                )
                frb.axeron.manager.ai.RuntimeCommandTracer.end()

                // ============ 【v1.9.1】总开关关闭时也要落盘 ============
                // 真实运行段（cmd 本身带 `sh -x`）刚刚抓完整段执行，无论本次是否
                // 走过「拦截分析」，都把它写进「卸载恢复名单」：
                //   filesDir/rollback/{dirId}/{name}.axd_log
                // 这样即使用户把总开关关掉（默认关闭），卸载时依然有恢复依据。
                // 注意：此处不挂沙箱 —— 开关关闭时抓的就是**真实运行**这一次。
                val runTraceCount =
                    frb.axeron.manager.ai.RuntimeCommandTracer.snapshot().size
                if (runTraceCount > 0) {
                    try {
                        frb.axeron.manager.ai.UninstallRollback.persistLog(
                            view.context,
                            plugin,
                            frb.axeron.manager.ai.RuntimeCommandTracer.snapshot()
                        )
                    } catch (t: Throwable) {
                        android.util.Log.e("AIEngine", "卸载回滚日志落盘失败(真实运行段)", t)
                    }
                }

                // ============ 【修复：卡在「运行中」界面】 ============
                // 对齐原版 AxManager 的行为：模块脚本执行完（execWithIO 返回）就立刻解锁界面。
                //
                // 旧实现在这里 **同步 await** AIEngineManager.analyzeRuntimeTrace(...)，而它内部
                // 走 OkHttp 请求 NVIDIA 云端（connect 15s / read 120s）。网络慢或不通时这一行会
                // 长时间不返回，界面就一直停在「运行中」——返回按钮（enabled = !isActionRunning）
                // 与关闭 FAB（if (!isActionRunning)）都不出现，看起来像卡死。
                //
                // 现在：先把真实指令流写进内存缓存（纯本地、零耗时，供 AI 问答页立即读取），
                // 然后立刻 isActionRunning = false 解锁界面；AI 行为总结改为后台「尽力而为」，
                // 成功则更新缓存，失败/超时只影响总结本身，绝不再阻塞界面收尾。
                // 【v1.4.8 修复 · 界面「大量无效指令」】
                // 这里原先用 toPromptBlock()（核心指令 + 最多 40 类环境探测样本），
                // 而该值会写进 lastRuntimeTrace，被「拦截到的真实指令」区块原样展示，
                // 于是用户看到的是一屏 cat/grep/getprop 探测噪声。
                // 展示与问答上下文统一改用 toDisplayBlock()（仅核心指令），
                // 与「恢复卸载」界面同源，避免展示层混入探测聚合块。
                val runtimeTraceBlock = frb.axeron.manager.ai.RuntimeCommandTracer.toDisplayBlock()
                frb.axeron.manager.ai.AIEngineManager.updateRuntimeTrace(runtimeTraceBlock)
                isActionRunning = false
                scope.launch(Dispatchers.IO) {
                    try {
                        frb.axeron.manager.ai.AIEngineManager.analyzeRuntimeTrace(
                            pluginName = plugin.prop.name,
                            traceText = runtimeTraceBlock
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("AIEngine", "后台 AI 行为总结失败（不影响界面）", t)
                    }
                }
            } finally {
                // 【v1.4.8 修复 · 「卡在运行中」兜底】
                // 任何路径（正常结束 / 用户中止 / 分析页未回调 / 协程内部抛异常）
                // 都必须解锁界面：否则返回键被 BackHandler 吞掉、关闭按钮不渲染，
                // 用户只能停在「运行中」界面。
                isActionRunning = false
                showAnalysisDialog = false
                frb.axeron.manager.ai.RuntimeCommandTracer.end()
            }
        }
    }

    val snackBarHost = LocalSnackbarHost.current
    val scrollState = rememberScrollState()

    val logSaved = stringResource(R.string.log_saved_to)
    val logFailed = stringResource(R.string.failed_to_save_log)

    Scaffold(
        topBar = {
            TopBar(
                isActionRunning = isActionRunning,
                aiAnalyzing = aiAnalyzing,
                onBack = dropUnlessResumed {
                    navigator.popBackStack()
                },
                onAiAnalyze = {
                    if (aiAnalyzing) return@TopBar
                    // 前置校验：云端未配置密钥且本地 AI 未运行（本地推理尚未接入）→ 提示不能用
                    if (!AIChatService.isCloudConfigured()) {
                        scope.launch { snackBarHost.showSnackbar("未配置云端密钥且未运行本地 AI，无法使用 AI 分析") }
                        return@TopBar
                    }
                    val cmdText = if (developerOptionsEnabled) logContent.toString() else text
                    if (cmdText.isBlank()) {
                        scope.launch { snackBarHost.showSnackbar("暂无可分析的指令") }
                        return@TopBar
                    }
                    aiAnalyzing = true
                    aiAnalysisReply = null
                    scope.launch {
                        val result = AIEngineManager.analyzeViaCloud(cmdText)
                        aiAnalysisReply = when {
                            result == null -> "AI 分析失败（网络异常或响应异常）"
                            else -> {
                                val riskLabel = when (result.risk) {
                                    AnalyzeResult.Risk.SAFE -> "安全"
                                    AnalyzeResult.Risk.LOW -> "低风险"
                                    AnalyzeResult.Risk.MEDIUM -> "中风险"
                                    AnalyzeResult.Risk.HIGH -> "高风险"
                                }
                                "【AI 分析】风险等级：$riskLabel\n\n${result.summary}"
                            }
                        }
                        aiAnalyzing = false
                    }
                },
                onSave = {
                    if (!isActionRunning) {
                        scope.launch {
                            val format =
                                SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                            val date = format.format(Date())

                            val baseDir = PathHelper.getPath(AxeronApiConstant.folder.PARENT_LOG)
                            if (!baseDir.exists()) {
                                baseDir.mkdirs()
                            }

                            val file = File(baseDir, "AxManager_action_log_${date}.log")

                            try {
                                val fos = Axeron.newFileService()
                                    .getStreamSession(file.absolutePath, true, false).outputStream
                                fos.write("$logContent\n".toByteArray())
                                fos.flush()

                                snackBarHost.showSnackbar(logSaved.format(file.absolutePath))
                            } catch (e: Exception) {
                                snackBarHost.showSnackbar(logFailed.format(e.message))
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isActionRunning) {
                ExtendedFloatingActionButton(
                    text = { Text(text = stringResource(R.string.close)) },
                    icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    onClick = {
                        navigator.popBackStack()
                    }
                )
            }
        },
        snackbarHost = { AxSnackBarHost(snackBarHost) }
    ) { innerPadding ->
        KeyEventBlocker {
            it.key == Key.VolumeDown || it.key == Key.VolumeUp
        }
        Column(
            modifier = Modifier
                .fillMaxSize(1f)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            LaunchedEffect(text) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }

            text = if (developerOptionsEnabled) logContent.toString() else text

            BasicText(
                modifier = Modifier.padding(8.dp),
                text = text.parseAsAnsiAnnotatedString(),
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize, // samain dengan fontSize
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                ),
                softWrap = true,
            )
        }
    }

    // 【运行信任弹窗】点「运行」后先询问用户是否信任该模块：
    //   信任   → 直接执行模块，运行全程不弹任何弹窗（不做抓取 / 分析）；
    //   不信任 → 走解析链路（strace + sh -x 抓取 → AI / 规则分析 → 再执行）。
    // v1.4.0 修复③：该弹窗已归入 AI 设置里的拦截总开关 —— 总开关关闭时不显示此弹窗
    // （aiEngineOn == false 直接按「信任」处理，见上方 LaunchedEffect）。
    if (trustDecision == null && aiEngineOn) {
        AlertDialog(
            onDismissRequest = { navigator.popBackStack() },
            title = {
                Text(
                    text = "是否信任该模块？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "信任：直接运行「${plugin.prop.name}」，不做指令抓取与安全分析，" +
                            "运行过程中不会出现任何弹窗。\n\n" +
                            "不信任：先抓取并分析该模块真实执行的指令，确认后再运行。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { trustDecision = true }) {
                    Text("信任，直接运行")
                }
            },
            dismissButton = {
                TextButton(onClick = { trustDecision = false }) {
                    Text("不信任，先分析")
                }
            }
        )
    }

    // 解析/拦截阶段进度弹窗（仅「不信任」链路显示；「信任」链路运行时全程零弹窗）
    if (showAnalysisDialog) {
        // 【v1.4.8 修复 · 「卡在运行中」】主动中止：
        // 若分析页没起来 / 被系统回收 / 回调丢失，用户不能只能干等着超时。
        // 走与「返回键」同一条安全侧路径（视为不执行），并立刻解锁界面。
        val cancelAnalysis: () -> Unit = {
            frb.axeron.manager.ai.AIEngineManager.onUserDecision(false)
            showAnalysisDialog = false
            isActionRunning = false
        }
        AlertDialog(
            onDismissRequest = { cancelAnalysis() },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { cancelAnalysis() }) {
                    Text("取消")
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "正在分析模块", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Text(text = stage, style = MaterialTheme.typography.bodyMedium)
            }
        )
    }

    // AI 分析结果弹窗（左上角「AI 分析」按钮触发，把当前输出代码喂给云端 AI）
    val aiReply = aiAnalysisReply
    if (aiReply != null) {
        AlertDialog(
            onDismissRequest = { aiAnalysisReply = null },
            title = {
                Text(
                    text = "AI 分析结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    BasicText(
                        text = aiReply,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { aiAnalysisReply = null }
                ) {
                    Text("关闭")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    isActionRunning: Boolean,
    aiAnalyzing: Boolean = false,
    onBack: () -> Unit = {},
    onAiAnalyze: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.action),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    enabled = !isActionRunning
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                IconButton(
                    onClick = onAiAnalyze,
                    enabled = !aiAnalyzing
                ) {
                    if (aiAnalyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI 分析")
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = onSave,
                enabled = !isActionRunning
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = null,
                )
            }
        },
    )
}