package frb.axeron.manager.ui.screen.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import frb.axeron.api.ai.RuleEngine
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.R
import frb.axeron.manager.ai.AIEngineManager
import frb.axeron.manager.ai.AIChatService
import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.AIEnvironment
import frb.axeron.manager.ai.PluginScriptTracer
import frb.axeron.manager.ai.RestrictedQA
import frb.axeron.manager.ui.component.AxSnackBarHost
import frb.axeron.manager.ui.component.KeyEventBlocker
import frb.axeron.manager.ui.util.LocalSnackbarHost
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.manager.ui.theme.GREEN
import frb.axeron.manager.ui.theme.ORANGE
import frb.axeron.manager.ui.theme.RED
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启用模块分析界面：和「运行 action.sh」界面完全一致，
 * 但拦截的是模块在「启用」时真实执行的脚本（service.sh / post-fs-data.sh）。
 *
 * 流程：strace 动态拦截真实指令 → AI 分析弹窗（Source.ACTION 强制）→
 * 用户允许才真正 togglePlugin 启用，拒绝则回滚（不启用）。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnablePluginScreen(
    navigator: DestinationsNavigator,
    plugin: PluginInfo,
    viewModelGlobal: ViewModelGlobal,
) {
    val developerOptionsEnabled = AxeronSettings.getEnableDeveloperOptions()
    val pluginViewModel = viewModelGlobal.pluginViewModel

    var isRunning by remember { mutableStateOf(true) }

    var stage by remember { mutableStateOf("正在准备分析…") }

    // 界面内决策弹窗状态（绕开跨 Activity 启动，避免后台启动限制导致卡死）
    var decisionResult by remember { mutableStateOf<AnalyzeResult?>(null) }
    var decisionDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var decisionCmd by remember { mutableStateOf("") }
    var decisionPluginName by remember { mutableStateOf("") }
    var decisionScriptText by remember { mutableStateOf("") }
    // 启用时拦截到的「真实执行指令」（strace 抓到的 combinedBlock），
    // 单独展示，与「脚本明文」区分开（v43）。
    var decisionTraceText by remember { mutableStateOf("") }

    // 左上角「AI 分析」按钮状态：把当前输出的指令代码喂给云端 AI，分析是否有危险
    var aiAnalyzing by remember { mutableStateOf(false) }
    var aiAnalysisReply by remember { mutableStateOf<String?>(null) }

    val view = LocalView.current
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose {
            view.keepScreenOn = false
        }
    }

    BackHandler(enabled = isRunning) {
        // 分析中禁止返回
    }

    val scope = rememberCoroutineScope()

    // 注意：必须用 remember（非 rememberSaveable）。若用 rememberSaveable，用户在
    // 卡住/返回后再次进入时，text 会被恢复成非"正在初始化…"的旧值，导致下面 guard
    // 判定成立直接 return，整个分析流程永不启动 → 永久卡在"正在准备分析…"。
    // 用 remember：每次导航真实进入都是全新组合，text 恒为初始值，协程必然启动。
    var text by remember { mutableStateOf("正在初始化…") }
    val logContent = remember { StringBuilder() }

    suspend fun dbg(msg: String) {
        try {
            frb.axeron.api.AxeronPluginService.execProcessSafe(
                cmd = arrayOf("/system/bin/sh", "-c", "echo \"${System.currentTimeMillis()} EnableScreen: $msg\" >> /data/local/tmp/ax_trace_debug.log 2>/dev/null"),
                env = frb.axeron.api.Axeron.getEnvironment()
            )
        } catch (_: Throwable) { }
    }

    LaunchedEffect(Unit) {
        dbg("== EnablePluginScreen LaunchedEffect 进入 ==")
        // LaunchedEffect(Unit) 只在组合首次进入时执行一次，无需 text guard。
        // 原 guard（if text!="正在初始化…" return）配合 rememberSaveable 是致命 bug 来源，
        // 已删除。
        launch(Dispatchers.IO) {
            val pluginPath = File(
                PathHelper.getWorkingPath(
                    Axeron.getAxeronInfo().isRoot(),
                    AxeronApiConstant.folder.PARENT_PLUGIN
                ), plugin.dirId
            )

            suspend fun execSh(cmd: String): String =
                runCatching {
                    frb.axeron.api.AxeronPluginService.execProcessSafe(
                        cmd = arrayOf("/system/bin/sh", "-c", cmd),
                        env = frb.axeron.api.Axeron.getEnvironment()
                    ).stdout
                }.getOrDefault("")

            // 启用时会真实执行的脚本（Igniter 依次跑 post-fs-data.sh、system.prop、service.sh）
            // 注意：必须用【shell 权限】探测脚本是否存在（test -f）。插件目录位于
            // /data/user_de/0/com.android.shell/axeron/plugins 下，是 com.android.shell 私有目录，
            // App 进程的 File(...).exists() 无法访问 → 恒 false，导致即使模块有 service.sh 也被判成"无启用脚本"。
            val enableScripts = listOf("service.sh", "post-fs-data.sh")
            val existingScripts = enableScripts.filter { script ->
                execSh("test -f \"${pluginPath.absolutePath}/$script\" && echo YES").contains("YES")
            }
            dbg("existingScripts=${existingScripts}（shell 权限 test -f 探测）")

            // 真正启用：写标记 + 触发 ignite 让 Igniter 执行 service.sh 注册
            suspend fun doEnable() {
                stage = "正在启用模块…"
                dbg("doEnable 开始: togglePlugin")
                val ok = AxeronPluginService.togglePlugin(plugin.dirId, true, plugin.backup)
                dbg("doEnable togglePlugin ok=$ok")
                if (ok) {
                    runCatching { AxeronPluginService.igniteSuspendService() }
                    dbg("doEnable ignite 完成")
                }
                pluginViewModel.markNeedRefresh()
                launch(Dispatchers.Main) {
                    text = if (ok) "✅ 模块已启用\n\n${plugin.prop.name} 已成功启用。" else "❌ 启用失败"
                }
                isRunning = false
                dbg("doEnable 完成 isRunning=false")
            }

            // 没有任何启用脚本：直接启用（无需分析）
            if (existingScripts.isEmpty()) {
                stage = "该模块无启用脚本（service.sh/post-fs-data.sh），直接启用…"
                dbg("无启用脚本，直接 doEnable")
                doEnable()
                return@launch
            }
            // 白名单：命中则跳过拦截分析，直接启用（避免耗时，用户已明确信任该模块）
            if (AIConfigStore.isWhitelisted(plugin.dirId, plugin.prop.name)) {
                stage = "该模块已在白名单，跳过拦截直接启用…"
                dbg("命中白名单 dirId=${plugin.dirId} name=${plugin.prop.name}，直接 doEnable")
                doEnable()
                return@launch
            }
            dbg("有启用脚本，进入 strace 拦截流程")

            val analyzer = AxeronPluginService.commandAnalyzer
            dbg("commandAnalyzer=${analyzer != null}")

            // strace 拦截各启用脚本的真实指令
            var combinedBlock = ""
            var totalCount = 0
            for (script in existingScripts) {
                dbg(">> 开始 strace 拦截 script=$script")
                val trace = PluginScriptTracer.tracePluginScript(
                    context = view.context,
                    plugin = plugin,
                    pluginPath = pluginPath.absolutePath,
                    scriptName = script,
                    onStage = { stage = it },
                )
                dbg(">> strace 拦截完成 script=$script count=${trace.count}")
                if (trace.count > 0) {
                    if (combinedBlock.isNotEmpty()) combinedBlock += "\n"
                    combinedBlock += trace.promptBlock
                    totalCount += trace.count
                }
            }
            dbg("全部脚本拦截完成 totalCount=$totalCount combinedBlock.length=${combinedBlock.length}")

            val baseCmd = existingScripts.joinToString(prefix = "sh ", separator = " sh ") { "./$it" }

            val analyzeCmd = if (totalCount > 0) {
                "$baseCmd\n\n# === 启用时真实执行指令流 ===\n$combinedBlock"
            } else {
                val catParts = mutableListOf<String>()
                for (script in existingScripts) {
                    val cat = PluginScriptTracer.readScript(pluginPath.absolutePath, script)
                    if (cat.isNotBlank()) catParts.add("# === $script ===\n$cat")
                }
                if (catParts.isNotEmpty()) {
                    "$baseCmd\n\n${catParts.joinToString("\n\n")}"
                } else {
                    baseCmd
                }
            }

            // 始终读取各启用脚本明文，作为 AI 参考 + 拦截界面原文展示（解析结果可能有误，原文供参考）
            val catPartsAll = mutableListOf<String>()
            for (script in existingScripts) {
                val cat = PluginScriptTracer.readScript(pluginPath.absolutePath, script)
                if (cat.isNotBlank()) catPartsAll.add("# === $script ===\n$cat")
            }
            val scriptPlainText = catPartsAll.joinToString("\n\n")
            if (scriptPlainText.isNotBlank()) {
                AIEngineManager.updateScriptText(scriptPlainText)
            }

            // 写入缓存供 AI 弹窗问答读取
            if (totalCount > 0) {
                AIEngineManager.updateRuntimeTrace(combinedBlock)
            }

            // AI 分析（Source.ACTION 强制弹窗）；在界面内直接弹决策框，绕开跨 Activity 启动
            if (analyzer != null) {
                stage = "正在生成分析报告…"
                dbg("stage=生成分析报告，analyzeCmd.length=${analyzeCmd.length}，totalCount=$totalCount")

                // 把 strace 真实抓到的执行指令流展示到界面主体（让用户看清"启用此模块会执行哪些指令"）
                // 优先展示真实执行的指令清单；若没抓到则展示脚本明文。
                val cmdDisplay = if (totalCount > 0) {
                    "【检测到该模块启用时真实执行的指令（共 $totalCount 条）】\n\n$combinedBlock"
                } else {
                    val catTxt = buildString {
                        for (script in existingScripts) {
                            val c = PluginScriptTracer.readScript(pluginPath.absolutePath, script)
                            if (c.isNotBlank()) append("# === $script ===\n$c\n\n")
                        }
                    }
                    if (catTxt.isNotBlank()) "【未能用 strace 检测到真实指令，下列为该模块启用脚本明文】\n\n$catTxt"
                    else "【未检测到可分析的指令】"
                }
                launch(Dispatchers.Main) {
                    text = cmdDisplay
                }

                // 直接本地规则分析拿结果（不涉及网络/Activity，稳定可靠）
                dbg("RuleEngine.analyze 开始")
                val ruleResult = RuleEngine.analyze(analyzeCmd)
                dbg("RuleEngine.analyze 完成 risk=${ruleResult.risk}")

                // 界面内决策：弹决策 Dialog，挂起等用户点「继续执行 / 中止」
                dbg("进入 decision 等待（挂起等用户点击…）")
                val allowed = withContext(Dispatchers.Main) {
                    val deferred = CompletableDeferred<Boolean>()
                    decisionResult = ruleResult
                    decisionCmd = analyzeCmd
                    decisionPluginName = plugin.prop.name
                    decisionScriptText = scriptPlainText
                    decisionTraceText = combinedBlock
                    decisionDeferred = deferred
                    val result = deferred.await()
                    decisionResult = null
                    decisionDeferred = null
                    result
                }
                dbg("decision 用户选择 allowed=$allowed")

                if (!allowed) {
                    dbg("用户拒绝，回滚不启用")
                    launch(Dispatchers.Main) {
                        text = "❌ 已被用户中止\n\n${plugin.prop.name} 的启用操作已被取消，模块未启用。"
                    }
                    isRunning = false
                    return@launch
                }
            }
            dbg("用户允许（或 analyzer==null），调用 doEnable")

            // 用户允许：真正启用
            doEnable()
        }
    }

    val snackBarHost = LocalSnackbarHost.current
    val scrollState = rememberScrollState()

    val logSaved = stringResource(R.string.log_saved_to)
    val logFailed = stringResource(R.string.failed_to_save_log)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "启用 ${plugin.prop.name}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = dropUnlessResumed { navigator.popBackStack() },
                            enabled = !isRunning
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                        IconButton(
                            onClick = {
                                if (aiAnalyzing) return@IconButton
                                // 前置校验：云端未配置密钥且本地模型未导入 → 提示不能用
                                if (!AIChatService.isAiAvailable()) {
                                    scope.launch { snackBarHost.showSnackbar("未配置云端密钥且未导入本地模型，无法使用 AI 分析") }
                                    return@IconButton
                                }
                                val cmdText = text
                                if (cmdText.isBlank() || cmdText == "正在初始化…") {
                                    scope.launch { snackBarHost.showSnackbar("暂无可分析的指令") }
                                    return@IconButton
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
                        onClick = {
                            if (!isRunning) {
                                scope.launch {
                                    val format = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
                                    val date = format.format(Date())
                                    val baseDir = PathHelper.getPath(AxeronApiConstant.folder.PARENT_LOG)
                                    if (!baseDir.exists()) baseDir.mkdirs()
                                    val file = File(baseDir, "AxManager_enable_log_${date}.log")
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
                        },
                        enabled = !isRunning
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!isRunning) {
                ExtendedFloatingActionButton(
                    text = { Text(text = stringResource(R.string.close)) },
                    icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    onClick = { navigator.popBackStack() }
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
                    lineHeight = MaterialTheme.typography.bodyMedium.fontSize,
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

    if (isRunning && decisionResult == null) {
        AlertDialog(
            onDismissRequest = { /* 分析中不允许关闭 */ },
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

    // 界面内决策弹窗：分析完成后，让用户决定「继续执行 / 中止」
    val currentDecision = decisionResult
    if (currentDecision != null && decisionDeferred != null) {
        val riskLabel = when (currentDecision.risk) {
            AnalyzeResult.Risk.SAFE -> "安全"
            AnalyzeResult.Risk.LOW -> "低风险"
            AnalyzeResult.Risk.MEDIUM -> "中风险"
            AnalyzeResult.Risk.HIGH -> "高风险"
        }
        val riskColor = when (currentDecision.risk) {
            AnalyzeResult.Risk.SAFE -> GREEN
            AnalyzeResult.Risk.LOW -> GREEN
            AnalyzeResult.Risk.MEDIUM -> ORANGE
            AnalyzeResult.Risk.HIGH -> RED
        }
        val matched = currentDecision.matchedRules

        AlertDialog(
            onDismissRequest = { /* 必须显式选择，不允许点外部关闭 */ },
            title = {
                Text(
                    text = "AI 安全分析 — ${decisionPluginName}",
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
                    // 提示：自动解析的代码可能不完整或有误，建议交 AI 分析后决定
                    Text(
                        text = "⚠️ 自动解析的代码可能不完整或有误，建议交给下方 AI 提问分析后再决定是否放行。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ORANGE
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "风险等级：$riskLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = riskColor
                    )
                    Spacer(Modifier.height(8.dp))
                    if (matched.isEmpty()) {
                        Text(
                            text = "未发现危险操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "检测到的风险操作（${matched.size} 项）：",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        matched.take(10).forEach { rule ->
                            Text(
                                text = "• ${rule.name}：${rule.matchedText}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        if (matched.size > 10) {
                            Text(
                                text = "…（其余 ${matched.size - 10} 项略）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    // 命令预览（可折叠，收起时显示第一行摘要）
                    var cmdExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (cmdExpanded) "▾" else "▸",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (cmdExpanded) "点击收起" else (decisionCmd.lineSequence().firstOrNull()?.take(50)
                                ?.plus(if (decisionCmd.length > 50) "…" else "") ?: "（空）"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.clickable { cmdExpanded = !cmdExpanded }
                        )
                    }
                    if (cmdExpanded) {
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            text = decisionCmd,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    // ============ 拦截到的真实指令（v43）：单独清晰展示 strace 抓取的真实执行指令 ============
                    // 与「命令预览」（analyzeCmd 拼接体）和「脚本原文」（明文脚本）区分开。
                    // 这是 AI 分析与用户判断的核心依据：模块启用时真实执行了哪些命令行。
                    if (decisionTraceText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        var traceExpanded by remember { mutableStateOf(true) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (traceExpanded) "▼" else "▶",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (traceExpanded) "拦截到的真实指令（点击收起）" else "拦截到的真实指令（点击展开）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = GREEN,
                                modifier = Modifier.clickable { traceExpanded = !traceExpanded }
                            )
                        }
                        if (traceExpanded) {
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = decisionTraceText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    // 脚本原文（参考）折叠区：解析结果可能有误，原文供用户/AI 参考
                    if (decisionScriptText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        var scriptExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (scriptExpanded) "▼" else "▶",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (scriptExpanded) "脚本原文（参考，点击收起）" else "脚本原文（参考，点击展开）",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = ORANGE,
                                modifier = Modifier.clickable { scriptExpanded = !scriptExpanded }
                            )
                        }
                        if (scriptExpanded) {
                            Spacer(Modifier.height(4.dp))
                            BasicText(
                                text = decisionScriptText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    // ============ AI 对话区（仅本地 AI + 云端 AI） ============
                    // 让用户在拦截决策时直接向 AI 提问"这个模块有什么用/会改什么"，
                    // 基于真实执行指令流 + 规则结果回答，帮助判断是否放行。
                    var aiQuestion by remember { mutableStateOf("") }
                    var aiAnswer by remember { mutableStateOf("") }
                    var aiAsking by remember { mutableStateOf(false) }

                    fun askAi(q: String) {
                        if (q.isBlank() || aiAsking) return
                        aiAsking = true
                        aiAnswer = ""
                        scope.launch {
                            // 1) 云端 AI 可用且总开关开启 → 问云端 AI（真实对话）
                            if (AIChatService.isCloudConfigured() && AIConfigStore.aiMasterEnabled) {
                                val system = AIEnvironment.systemDeclaration(
                                    currentPluginName = decisionPluginName,
                                    currentCmd = decisionCmd,
                                )
                                val contextBlock = buildString {
                                    appendLine("【模块名】$decisionPluginName")
                                    appendLine("【待验证命令】")
                                    appendLine(decisionCmd.take(2000))
                                    val trace = AIEngineManager.lastRuntimeTrace
                                    if (!trace.isNullOrBlank()) {
                                        appendLine()
                                        appendLine("【真实执行指令流】")
                                        appendLine(trace.take(3000))
                                    }
                                    val scriptText = AIEngineManager.lastScriptText
                                    if (!scriptText.isNullOrBlank() && !AIEnvironment.isEncryptedScript(scriptText)) {
                                        appendLine()
                                        appendLine("【脚本原文（解析结果可能有误，此原文供参考）】")
                                        appendLine(scriptText.take(3000))
                                    } else if (!scriptText.isNullOrBlank() && AIEnvironment.isEncryptedScript(scriptText)) {
                                        appendLine()
                                        appendLine("【脚本已加密/混淆，无法读取明文，请仅依据上方「真实执行指令流」分析其真实行为】")
                                    }
                                }
                                val reply = AIChatService.chatOnce(
                                    system = system,
                                    prompt = contextBlock + "\n\n用户问题：$q",
                                )
                                aiAnswer = reply
                                    ?: RestrictedQA.answer(q, currentDecision)
                            } else {
                                // 2) 云端未配置/总开关关 → 用本地规则降级回答
                                aiAnswer = RestrictedQA.answer(q, currentDecision)
                            }
                            aiAsking = false
                        }
                    }

                    Text(
                        text = "向 AI 提问（了解模块功能/风险）",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = aiQuestion,
                            onValueChange = { aiQuestion = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("这个模块会改什么？有什么风险？") },
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = { askAi(aiQuestion) }, enabled = !aiAsking) {
                            Text(if (aiAsking) "回答中…" else "提问")
                        }
                    }
                    if (aiAnswer.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(
                            text = aiAnswer,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    // 加入白名单：把当前模块加入白名单并放行，之后启用/运行/安装都不再拦截
                    androidx.compose.material3.TextButton(
                        onClick = {
                            AIConfigStore.addWhitelist(plugin.dirId, decisionPluginName)
                            decisionDeferred?.complete(true)
                        }
                    ) {
                        Text("加入白名单并放行", color = GREEN)
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { decisionDeferred?.complete(true) }
                    ) {
                        Text("继续执行")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { decisionDeferred?.complete(false) }
                ) {
                    Text("中止", color = MaterialTheme.colorScheme.error)
                }
            },
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
                androidx.compose.material3.TextButton(
                    onClick = { aiAnalysisReply = null }
                ) {
                    Text("关闭")
                }
            },
        )
    }
}
