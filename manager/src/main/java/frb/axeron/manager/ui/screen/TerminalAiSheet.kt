package frb.axeron.manager.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import frb.axeron.manager.ai.AIChatService
import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.TerminalAiPolicy
import kotlinx.coroutines.launch

/**
 * 终端 AI 助手面板（从 QuickShell 右下角 AI 悬浮按钮唤起）。
 *
 * 与「AI 安全引擎 → 云端对话」的区别：
 * 1. **职责受限**：只回答 Shell/命令/系统排障/本应用功能四类问题，
 *    越界提问在本地就被 [TerminalAiPolicy] 拦截，不消耗免费额度；
 * 2. **带终端上下文**：自动把当前终端最近的输出/命令注入提问，
 *    用户不用手动复制粘贴报错内容；
 * 3. **免费 API 优先**：复用 [AIConfigStore.useOfficialAi] 开关，
 *    未配置自定义 Key 时自动走官方免费服务，且不阻塞用户。
 *
 * @param onInsertCommand 把 AI 给出的命令回填到终端输入框（可空）
 * @param terminalContext 当前终端上下文（最近命令 + 输出摘要），用于自动附带
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAiSheet(
    onDismiss: () -> Unit,
    onInsertCommand: (String) -> Unit,
    terminalContext: () -> String,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<TerminalAiMsg>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 免费 API 状态提示
    val usingOfficial = AIConfigStore.useOfficialAi
    val cloudReady = AIChatService.isCloudConfigured()
    val available = usingOfficial || cloudReady

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || sending) return

        // —— 本地范围检查（越界直接拒绝，不消耗额度）——
        val verdict = TerminalAiPolicy.judge(text)
        if (verdict == TerminalAiPolicy.Verdict.DENY) {
            messages.add(TerminalAiMsg(true, text))
            messages.add(TerminalAiMsg(false, TerminalAiPolicy.denyMessage()))
            input = ""
            return
        }

        if (!available) {
            messages.add(TerminalAiMsg(true, text))
            messages.add(
                TerminalAiMsg(
                    false,
                    "当前没有可用的 AI 服务。请到「设置 → AI 安全引擎」中开启官方免费 AI，" +
                        "或配置自己的云端 API Key。",
                ),
            )
            input = ""
            return
        }

        input = ""
        messages.add(TerminalAiMsg(true, text))
        sending = true

        // 把终端上下文附给 AI（让它能看到报错输出）
        val ctx = terminalContext()
        val prompt = buildString {
            append(text)
            if (ctx.isNotBlank()) {
                append("\n\n【当前终端上下文（仅供参考）】\n")
                append(ctx)
            }
        }

        scope.launch {
            messages.add(TerminalAiMsg(false, ""))
            val idx = messages.lastIndex
            val full = AIChatService.chatStream(
                system = TerminalAiPolicy.systemPrompt(),
                prompt = prompt,
                history = emptyList<AIChatService.ChatMessage>(),
                onDelta = { delta ->
                    val cur = messages[idx].text
                    messages[idx] = TerminalAiMsg(false, cur + delta)
                },
            )
            if (full == null && messages[idx].text.isBlank()) {
                val reason = AIChatService.lastError ?: "未知原因"
                messages[idx] = TerminalAiMsg(
                    false,
                    "调用失败：$reason\n\n若使用官方免费 AI，可稍等片刻再试（免费服务可能限流）。",
                )
            }
            sending = false
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // —— 标题行 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "终端 AI 助手",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (messages.isNotEmpty()) {
                    IconButton(onClick = { messages.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空对话")
                    }
                }
            }

            Text(
                text = when {
                    usingOfficial -> "当前使用：官方免费 AI（可能限流）"
                    cloudReady -> "当前使用：自定义云端 API"
                    else -> "未配置 AI 服务，请到「设置 → AI 安全引擎」开启"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(10.dp))

            // —— 对话区 ——
            if (messages.isEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Text(
                            text = "我只能回答与终端相关的问题。试试下面这些：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(messages) { index, msg ->
                        TerminalMsgBubble(
                            text = msg.text,
                            fromUser = msg.fromUser,
                            onInsertCommand = if (!msg.fromUser) {
                                { onInsertCommand(extractCommand(msg.text)) }
                            } else null,
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
            }

            // —— 快捷提问（引导"只能问哪些问题"）——
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(TerminalAiPolicy.QUICK_QUESTIONS) { pair ->
                    val label = pair.first
                    val template = pair.second
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.clickable { input = template },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))

            // —— 输入栏 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问我终端/命令/系统相关的问题…") },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { send(input) }, enabled = !sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/**
 * 终端 AI 面板内的一条消息。
 * fromUser = true 表示用户提问，false 表示 AI 回复。
 */
private data class TerminalAiMsg(
    val fromUser: Boolean,
    val text: String,
)

/** 终端 AI 气泡：AI 回复若含代码块，提供"填入终端"按钮 */
@Composable
private fun TerminalMsgBubble(
    text: String,
    fromUser: Boolean,
    onInsertCommand: (() -> Unit)?,
) {
    val bubbleColor = if (fromUser) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (fromUser) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    val cmd = remember(text) { extractCommand(text) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = bubbleColor,
            ) {
                Text(
                    text = text.ifEmpty { "…" },
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // 有可执行命令时，提供一键填入
            if (!fromUser && cmd.isNotBlank() && onInsertCommand != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onInsertCommand) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("填入终端", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * 从 AI 回复中抽取第一条可执行命令。
 * 优先取 ``` 代码块内容；没有代码块时取整行以命令开头的内容。
 */
private fun extractCommand(text: String): String {
    // 1) Markdown 代码块
    val block = Regex("```[a-zA-Z]*\\n([\\s\\S]*?)```").find(text)
    if (block != null) {
        val body = block.groupValues[1].trim()
        // 多行只取第一行非空命令
        return body.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    // 2) 行内 code
    val inline = Regex("`([^`\\n]+)`").find(text)
    if (inline != null) return inline.groupValues[1].trim()
    // 3) 以常见命令开头的行
    val line = text.lines().firstOrNull { l ->
        val t = l.trim()
        t.isNotBlank() && Regex(
            "^(su|sh|pm|am|adb|ls|cat|grep|find|ps|top|df|du|cp|mv|rm|chmod|chown|mount|" +
                "dumpsys|getprop|setprop|settings|logcat|cmd|svc|input|wm|ifconfig|ip|netstat|" +
                "curl|wget|echo|sleep|kill|mkdir|touch|tar|unzip|zip)\\b",
        ).containsMatchIn(t)
    }
    return line?.trim()?.removePrefix("$")?.trim().orEmpty()
}
