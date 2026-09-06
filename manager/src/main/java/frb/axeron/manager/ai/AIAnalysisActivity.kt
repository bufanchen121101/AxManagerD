package frb.axeron.manager.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import frb.axeron.api.ai.CommandAnalyzer
import frb.axeron.manager.ui.theme.AxManagerTheme
import frb.axeron.manager.ui.theme.GREEN
import frb.axeron.manager.ui.theme.ORANGE
import frb.axeron.manager.ui.theme.RED
import kotlinx.coroutines.launch

class AIAnalysisActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = AIEngineManager.lastAnalysis
        setContent {
            AxManagerTheme {
                AnalysisScreen(
                    payload = payload,
                    onDecision = { allow ->
                        AIEngineManager.onUserDecision(allow)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun AnalysisScreen(
    payload: AIEngineManager.AnalysisPayload?,
    onDecision: (Boolean) -> Unit,
) {
    val result = payload?.result
    val matched = result?.matchedRules ?: emptyList()
    val risk = result?.risk ?: CommandAnalyzer.AnalyzeResult.Risk.SAFE

    val riskColor = when (risk) {
        CommandAnalyzer.AnalyzeResult.Risk.SAFE -> GREEN
        CommandAnalyzer.AnalyzeResult.Risk.LOW -> GREEN
        CommandAnalyzer.AnalyzeResult.Risk.MEDIUM -> ORANGE
        CommandAnalyzer.AnalyzeResult.Risk.HIGH -> RED
    }
    val riskLabel = when (risk) {
        CommandAnalyzer.AnalyzeResult.Risk.SAFE -> "安全"
        CommandAnalyzer.AnalyzeResult.Risk.LOW -> "低风险"
        CommandAnalyzer.AnalyzeResult.Risk.MEDIUM -> "中风险"
        CommandAnalyzer.AnalyzeResult.Risk.HIGH -> "高风险"
    }

    // 问答区（优先云端 AI，未配置则回退到本地规则）
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun askAi(q: String) {
        if (q.isBlank() || asking) return
        asking = true
        answer = ""
        scope.launch {
            // 优先云端对话
            if (AIChatService.isCloudConfigured() && AIConfigStore.aiMasterEnabled) {
                // 注入「环境文档 + 最高权限声明」，并把完整拦截 payload 交给 AI
                val system = AIEnvironment.systemDeclaration(
                    currentPluginName = payload?.ctx?.pluginName,
                    currentCmd = payload?.cmd,
                )
                val reply = AIChatService.chatOnce(
                    system = system,
                    prompt = AIEnvironment.payloadContext(payload) + "\n\n" +
                        "用户问题：$q",
                )
                answer = reply ?: RestrictedQA.answer(q, result)
            } else {
                answer = RestrictedQA.answer(q, result)
            }
            asking = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = "AI 安全分析",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "模块：${payload?.ctx?.pluginName ?: "未知"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 风险等级卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = riskColor.copy(alpha = 0.12f)
                )
            ) {
                Text(
                    text = "风险等级：$riskLabel",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = riskColor
                )
            }
            Spacer(Modifier.height(12.dp))

            // 命中规则列表
            Text(
                text = "检测到的风险操作（${matched.size} 项）：",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (matched.isEmpty()) {
                Text(
                    text = "未发现危险操作",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    matched.forEach { rule ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = rule.name,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = rule.matchedText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 模块解析结果（安装/运行时真实探测得到的执行指令流）
            val inspection = payload?.inspection
            if (inspection != null) {
                Text(
                    text = "模块解析结果",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "类型：${inspection.language}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (inspection.encryptedHint.isNotBlank()) {
                            Text(
                                text = "提示：${inspection.encryptedHint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ORANGE
                            )
                        }
                        if (inspection.commands.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "关键指令（${inspection.commands.size} 条）：",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            inspection.commands.take(20).forEach { c ->
                                BasicText(
                                    text = "  [${c.type}] ${c.target} = ${c.value}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            if (inspection.commands.size > 20) {
                                Text(
                                    text = "  …（其余 ${inspection.commands.size - 20} 条略）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 拦截到的真实执行指令（运行时 strace/sh -x 抓取，加密模块也能穿透显示）
            val runtimeTrace = payload?.runtimeTrace ?: AIEngineManager.lastRuntimeTrace
            if (!runtimeTrace.isNullOrBlank() &&
                !runtimeTrace.contains("未捕获到真实执行指令")) {
                var traceExpanded by remember { mutableStateOf(false) }
                Text(
                    text = "拦截到的真实指令：",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { traceExpanded = !traceExpanded },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (traceExpanded) "▼" else "▶",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (traceExpanded) "点击收起真实指令" else (runtimeTrace.lineSequence().firstOrNull()?.take(60)
                                    ?.plus(if (runtimeTrace.length > 60) "…" else "") ?: "（空）"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 1
                            )
                        }
                        if (traceExpanded) {
                            Spacer(Modifier.height(8.dp))
                            BasicText(
                                text = runtimeTrace,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 提示：解析结果可能不完整
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = ORANGE.copy(alpha = 0.10f)
                )
            ) {
                Text(
                    text = "⚠️ 自动解析的代码可能不完整或有误，建议交给上方 AI 提问分析后再决定是否放行。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ORANGE
                )
            }
            Spacer(Modifier.height(12.dp))

            // 脚本原文（折叠展示，作为解析结果的参考）
            val scriptText = payload?.scriptText ?: AIEngineManager.lastScriptText
            if (!scriptText.isNullOrBlank()) {
                var scriptExpanded by remember { mutableStateOf(false) }
                Text(
                    text = "脚本原文（参考）：",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { scriptExpanded = !scriptExpanded },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (scriptExpanded) "▼" else "▶",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (scriptExpanded) "点击收起脚本原文" else (scriptText.lineSequence().firstOrNull()?.take(60)
                                    ?.plus(if (scriptText.length > 60) "…" else "") ?: "（空）"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                maxLines = 1
                            )
                        }
                        if (scriptExpanded) {
                            Spacer(Modifier.height(8.dp))
                            BasicText(
                                text = scriptText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 命令预览（折叠展示，点击箭头展开/收起，收起时只显示第一行）
            val cmdText = payload?.cmd ?: ""
            var cmdExpanded by remember { mutableStateOf(false) }
            Text(
                text = "即将执行的命令：",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    // 可点击的标题行（带箭头）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { cmdExpanded = !cmdExpanded },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (cmdExpanded) "▾" else "▸",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        // 收起时只显示第一行摘要
                        Text(
                            text = if (cmdExpanded) "点击收起" else (cmdText.lineSequence().firstOrNull()?.take(60)
                                ?.plus(if (cmdText.length > 60) "…" else "") ?: "（空）"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1
                        )
                    }
                    // 展开时显示全文
                    if (cmdExpanded) {
                        Spacer(Modifier.height(8.dp))
                        BasicText(
                            text = cmdText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 问答区
            Text(
                text = "向 AI 提问",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("这个模块会改什么？") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { askAi(question) }, enabled = !asking) {
                    Text(if (asking) "回答中…" else "提问")
                }
            }
            if (answer.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = answer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // 加入白名单（把当前模块加入白名单并放行，之后不再拦截）
            val ctxDirId = payload?.ctx?.pluginDirId
            val ctxName = payload?.ctx?.pluginName
            if (ctxDirId != null || ctxName != null) {
                OutlinedButton(
                    onClick = {
                        AIConfigStore.addWhitelist(ctxDirId, ctxName)
                        onDecision(true)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("加入白名单并放行")
                }
                Spacer(Modifier.height(8.dp))
            }

            // 决策按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { onDecision(false) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("中止")
                }
                Button(
                    onClick = { onDecision(true) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("继续执行")
                }
            }
        }
    }
}
