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

    var isActionRunning by rememberSaveable { mutableStateOf(true) }

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

    LaunchedEffect(Unit) {
        if (text.isNotEmpty()) {
            return@LaunchedEffect
        }
        launch(Dispatchers.IO) {
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
            if (analyzer != null && !isWhitelisted) {
                // 【方案 A（strace 版）】执行前用静态 strace 跟踪 `sh action.sh` 整个进程树，
                // 只抓 execve 系统调用，从而 100% 捕获解密/展开后「最终真实启动的命令」
                // （YTAS 三层壳、base64、openssl、eval、shc 编译 ELF 全部覆盖）。
                frb.axeron.manager.ai.RuntimeCommandTracer.begin()
                var straceBlock = ""
                var straceCount = 0
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
                        // 2. 【同步 timeout 版】前台跑 strace，timeout 到点强制退出，绝不卡死。
                        // 抛弃旧的 setsid 后台轮询方案——那在 Shizuku binder 下 execProcessSafe 的
                        // waitFor() 会卡死，导致抓不到或阻塞。同步 timeout 对任意模块（含 while true
                        // 常驻）都安全：抓"启动后前 N 秒核心指令"即可，最多 60 秒。
                        stage = "正在跟踪模块真实执行…"
                        val traceLog = "/data/local/tmp/ax_trace_${plugin.dirId}.log"
                        val straceCmd = frb.axeron.manager.ai.StraceHelper.buildTraceCmdSync(
                            stracePath, pluginPath.absolutePath, traceLog, "action.sh", timeoutSeconds = 60
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
                            frb.axeron.manager.ai.RuntimeCommandTracer.addStraceLine(line)
                        }
                        runCatching {
                            AxeronPluginService.execProcessSafe(
                                cmd = arrayOf("/system/bin/sh", "-c", "rm -f \"$traceLog\" 2>/dev/null"),
                                env = Axeron.getEnvironment()
                            )
                        }
                    } else {
                        android.util.Log.w("AIEngine", "strace 二进制释放失败，回退 sh -x")
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AIEngine", "strace 预执行抓取失败，回退 sh -x", t)
                } finally {
                    // 若 strace 没抓到，回退到 sh -x 抓 xtrace
                    if (frb.axeron.manager.ai.RuntimeCommandTracer.snapshot().isEmpty()) {
                        stage = "正在回退解析（sh -x）…"
                        // 加 timeout 限时：while true 等常驻脚本会卡死 sh -x，限时抓前 60 秒核心指令
                        val traceCmd = "export PATH=${pluginBin}:\$PATH; cd \"${pluginPath.absolutePath}\"; timeout -k 1 60 sh -x ./action.sh; exit 0"
                        runCatching {
                            AxeronPluginService.execWithIO(
                                cmd = traceCmd,
                                onStdout = { _ -> },
                                onStderr = { chunk ->
                                    if (frb.axeron.manager.ai.RuntimeCommandTracer.enabled) {
                                        chunk.split('\n').forEach { line ->
                                            if (frb.axeron.manager.ai.RuntimeCommandTracer.isTraceLine(line)) {
                                                frb.axeron.manager.ai.RuntimeCommandTracer.addTrace(line)
                                            }
                                        }
                                    }
                                },
                                hideStderr = false,
                            )
                        }
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
                if (straceCount > 0) {
                    frb.axeron.manager.ai.AIEngineManager.updateRuntimeTrace(straceBlock)
                } else if (catScript.isNotBlank()) {
                    frb.axeron.manager.ai.RuntimeCommandTracer.begin()
                    try { catScript.split('\n').forEach { frb.axeron.manager.ai.RuntimeCommandTracer.addTraceLine(it) } }
                    finally { frb.axeron.manager.ai.RuntimeCommandTracer.end() }
                    frb.axeron.manager.ai.AIEngineManager.updateRuntimeTrace(
                        frb.axeron.manager.ai.RuntimeCommandTracer.toPromptBlock()
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
                    launch(Dispatchers.Main) {
                        text = "已被用户中止"
                    }
                    isActionRunning = false
                    return@launch
                }
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
                            if (frb.axeron.manager.ai.RuntimeCommandTracer.isTraceLine(line)) {
                                frb.axeron.manager.ai.RuntimeCommandTracer.addTrace(line)
                            }
                        }
                    }
                    logContent.append(it).append("\n")
                }
            )
            frb.axeron.manager.ai.RuntimeCommandTracer.end()
            // 把抓取到的真实指令流喂给云端 AI，生成模块行为总结（并缓存供提问）
            frb.axeron.manager.ai.AIEngineManager.analyzeRuntimeTrace(
                pluginName = plugin.prop.name,
                traceText = frb.axeron.manager.ai.RuntimeCommandTracer.toPromptBlock()
            )
            isActionRunning = false
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

    // 解析/拦截阶段进度弹窗
    if (isActionRunning) {
        AlertDialog(
            onDismissRequest = { /* 运行中不允许关闭 */ },
            confirmButton = {},
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