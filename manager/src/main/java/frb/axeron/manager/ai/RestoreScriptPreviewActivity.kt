package frb.axeron.manager.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ui.theme.AxManagerTheme
import frb.axeron.manager.ui.theme.ORANGE
import frb.axeron.manager.ui.theme.RED
import frb.axeron.manager.ui.util.LocaleHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * v1.1.1 恢复脚本预览 + 执行 + AI 对话界面。
 *
 * 由 PluginList 卸载回滚流程通过 startActivity 启动，Intent 传递：
 * - extra_restore_script：过滤后的恢复脚本（安全行）
 * - extra_full_script：原始完整脚本（含危险行，供「不拦截」入口放回）
 * - extra_plugin_dir_id：模块 dirId
 * - extra_plugin_name：模块名
 * - extra_blocked_lines：被拦截行（含语法/危险说明，展示用）
 * - extra_dangerous_lines：被拦截行的原文（供「不拦截」开关放回执行）
 *
 * 新能力（v1.1.1）：
 * 1. 双 AI 对话框：生成 AI / 审查 AI 可切换，可自由对话。
 * 2. 停止恢复 / 继续恢复：执行中可中断（超时强杀）。
 * 3. 不拦截入口：开关控制是否把危险行放回脚本执行。
 */
class RestoreScriptPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val script = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
        val fullScript = intent.getStringExtra(EXTRA_FULL_SCRIPT) ?: script
        val dirId = intent.getStringExtra(EXTRA_DIR_ID) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val blocked = intent.getStringArrayListExtra(EXTRA_BLOCKED) ?: arrayListOf()
        val dangerous = intent.getStringArrayListExtra(EXTRA_DANGEROUS) ?: arrayListOf()
        val captured = intent.getStringExtra(EXTRA_CAPTURED) ?: ""
        setContent {
            AxManagerTheme {
                RestoreScriptPreviewScreen(
                    script = script,
                    fullScript = fullScript,
                    dirId = dirId,
                    moduleName = name,
                    blockedLines = blocked,
                    dangerousLines = dangerous,
                    capturedCommands = captured,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_SCRIPT = "extra_restore_script"
        const val EXTRA_FULL_SCRIPT = "extra_full_script"
        const val EXTRA_DIR_ID = "extra_plugin_dir_id"
        const val EXTRA_NAME = "extra_plugin_name"
        const val EXTRA_BLOCKED = "extra_blocked_lines"
        const val EXTRA_DANGEROUS = "extra_dangerous_lines"
        const val EXTRA_CAPTURED = "extra_captured_commands"
    }
}

/** AI 助理类型：生成 / 审查 */
private enum class AiRole(val label: String) {
    GENERATE("生成 AI"),
    REVIEW("审查 AI"),
}

/** 一条对话气泡 */
private data class ChatBubble(
    val role: String,          // "user" / "assistant"
    val content: String,
    val tag: AiRole? = null,   // 该气泡由哪个 AI 产生
)

@Composable
fun RestoreScriptPreviewScreen(
    script: String,
    fullScript: String,
    dirId: String,
    moduleName: String,
    blockedLines: List<String>,
    dangerousLines: List<String>,
    capturedCommands: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 执行状态
    var executing by remember { mutableStateOf(false) }
    var executed by remember { mutableStateOf(false) }
    var resultOutput by remember { mutableStateOf("") }
    var resultError by remember { mutableStateOf("") }
    var execJob by remember { mutableStateOf<Job?>(null) }

    // 不拦截开关（是否把危险行放回执行）
    var allowUnsafe by remember { mutableStateOf(false) }

    // 实际将要执行的脚本：默认用过滤后的安全脚本，开启不拦截则用完整脚本
    val effectiveScript = if (allowUnsafe && fullScript.isNotBlank()) fullScript else script

    // AI 对话状态
    var chatList by remember { mutableStateOf(listOf<ChatBubble>()) }
    var chatInput by remember { mutableStateOf("") }
    var aiRole by remember { mutableStateOf(AiRole.GENERATE) }
    var aiThinking by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "恢复脚本预览",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "模块【$moduleName】的恢复脚本（撤销其历史系统操作）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 拦截到的原始指令展示（验证拦截是否正确）
            if (capturedCommands.isNotBlank()) {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "运行时/启用时拦截到的指令",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = capturedCommands,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 危险行提示 + 不拦截开关
            if (blockedLines.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.12f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "已拦截 ${blockedLines.size} 条高危/语法错误指令",
                            color = RED,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (allowUnsafe) "不拦截（危险行也会执行）" else "拦截中（默认不执行危险行）",
                                style = MaterialTheme.typography.bodySmall,
                                color = RED
                            )
                            Switch(
                                checked = allowUnsafe,
                                onCheckedChange = { allowUnsafe = it }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        blockedLines.take(6).forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = RED
                            )
                        }
                        if (blockedLines.size > 6) {
                            Text(
                                text = "… 等共 ${blockedLines.size} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = RED
                            )
                        }
                    }
                }
            }

            // 脚本预览（显示实际会执行的脚本）
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = if (effectiveScript.isBlank()) "（无脚本内容）" else effectiveScript,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // AI 对话区
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    // 切换按钮：生成 AI / 审查 AI
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AiRole.values().forEach { role ->
                            val selected = aiRole == role
                            Button(
                                onClick = { aiRole = role },
                                modifier = Modifier.weight(1f),
                                colors = if (selected) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.outlinedButtonColors()
                                }
                            ) {
                                Text(role.label)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 对话气泡列表
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (chatList.isEmpty()) {
                            item {
                                Text(
                                    text = "与 AI 对话：生成 AI 可重新生成/修改脚本，审查 AI 可校验脚本语法与安全。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(chatList) { bubble ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (bubble.role == "user") {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        MaterialTheme.shapes.medium
                                    )
                                    .padding(8.dp)
                            ) {
                                val tag = bubble.tag?.label ?: if (bubble.role == "user") "你" else "AI"
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = bubble.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    // 输入行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("向${aiRole.label}提问…") },
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                val msg = chatInput.trim()
                                if (msg.isEmpty() || aiThinking) return@Button
                                chatInput = ""
                                chatList = chatList + ChatBubble("user", msg)
                                aiThinking = true
                                scope.launch {
                                    val langInstr = LocaleHelper.languageInstruction(context)
                                    val reply = runCatching {
                                        when (aiRole) {
                                            AiRole.GENERATE -> {
                                                AIChatService.chatOnce(
                                                    system = "你是 Android 恢复脚本生成专家，帮助用户生成/修改恢复脚本。$langInstr",
                                                    prompt = msg + "\n\n当前脚本：\n" + effectiveScript
                                                )
                                            }
                                            AiRole.REVIEW -> {
                                                AIChatService.chatOnce(
                                                    system = "你是 Android Shell 脚本审查专家，校验脚本语法/符号/安全，只指出问题。$langInstr",
                                                    prompt = "请审查以下脚本：\n" + effectiveScript + "\n\n（用户补充：$msg）",
                                                    modelOverride = AIConfigStore.resolveReviewModel()
                                                )
                                            }
                                        }
                                    }.getOrNull()
                                    aiThinking = false
                                    chatList = chatList + ChatBubble(
                                        "assistant",
                                        reply ?: "（AI 调用失败，请检查云端配置）",
                                        aiRole
                                    )
                                }
                            },
                            enabled = !aiThinking
                        ) {
                            if (aiThinking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("发送")
                            }
                        }
                    }
                }
            }

            // 执行结果展示
            if (executed) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ORANGE.copy(alpha = 0.12f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "执行完成，输出：",
                            color = ORANGE,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = resultOutput.ifBlank { "（无标准输出）" },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (resultError.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "错误：\n$resultError",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = RED
                            )
                        }
                    }
                }
            }

            // 底部按钮：取消 / 执行或停止恢复
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (executed) "关闭" else "取消")
                }
                Button(
                    onClick = {
                        if (executing) {
                            // 停止恢复：取消执行协程
                            execJob?.cancel()
                            executing = false
                            executed = true
                            resultError = "已停止恢复（用户中断）"
                        } else {
                            if (effectiveScript.isBlank()) return@Button
                            executing = true
                            executed = false
                            resultOutput = ""
                            resultError = ""
                            execJob = scope.launch {
                                val result = UninstallRollback.executeRestoreScript(effectiveScript)
                                executing = false
                                executed = true
                                if (result != null) {
                                    resultOutput = result.out
                                    resultError = result.err
                                } else {
                                    resultError = "执行超时（60 秒）或已被中断"
                                }
                                // 执行完成后销毁该模块的日志 + 缓存
                                kotlin.runCatching {
                                    UninstallRollback.destroyByDirId(context, dirId)
                                }
                            }
                        }
                    },
                    enabled = !executing || true,
                    modifier = Modifier.weight(1f),
                    colors = if (executing) {
                        ButtonDefaults.buttonColors(containerColor = RED)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    if (executing) Text("停止恢复") else Text("执行恢复")
                }
            }
        }
    }
}