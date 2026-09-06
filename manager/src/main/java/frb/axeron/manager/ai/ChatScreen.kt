package frb.axeron.manager.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch

/**
 * 云端对话界面。
 *
 * 本地模型功能已移除，此界面仅服务于云端 AI 对话（OpenAI 兼容 chat completions，流式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ChatScreen(
    navigator: DestinationsNavigator,
    modelType: String = "cloud",
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 消息列表（本地内存状态，不持久化）
    var messages by remember {
        mutableStateOf(listOf<ChatMessage>())
    }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 空态提示文案
    val emptyHint = if (AIConfigStore.useOfficialAi) {
        "正在使用官方默认 AI（英伟达 nemotron），输入消息开始对话"
    } else if (!AIChatService.isCloudConfigured()) {
        "未配置 API，请先在「云端模型配置」中填写网址、Key 并识别模型"
    } else {
        "云端对话已就绪，输入消息开始对话"
    }

    // 自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "云端对话",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            if (messages.isEmpty()) {
                // 空态（用 weight 占满剩余空间，保证底部输入栏始终可见）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages) { msg ->
                        MessageBubble(msg)
                    }
                }
            }

            // 输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (input.isBlank() || sending) return@IconButton
                        val userText = input.trim()
                        input = ""
                        messages = messages + ChatMessage(ChatRole.USER, userText)
                        sending = true

                        // 云端流式对话
                        // 注意：history 应保留完整对话（含 AI 的历史回复），否则多轮对话
                        // 会丢失上下文（AI 看不到自己上一轮的回答，无法连贯回答）。
                        val history = messages.map {
                            AIChatService.ChatMessage(it.role.name.lowercase(), it.content)
                        }
                        scope.launch {
                            // 先插入一个空的 AI 气泡用于流式填充
                            messages = messages + ChatMessage(ChatRole.AI, "")
                            val aiIndex = messages.lastIndex
                            val full = AIChatService.chatStream(
                                system = AIEnvironment.systemDeclaration(),
                                prompt = userText,
                                history = history,
                                onDelta = { delta ->
                                    val cur = messages[aiIndex].content
                                    messages = messages.toMutableList().also {
                                        it[aiIndex] = ChatMessage(ChatRole.AI, cur + delta)
                                    }
                                },
                            )
                            if (full == null && messages[aiIndex].content.isBlank()) {
                                // 失败且无内容
                                messages = messages.toMutableList().also {
                                    it[aiIndex] = ChatMessage(
                                        ChatRole.AI,
                                        "调用失败，请检查云端配置（网址/Key/模型）",
                                    )
                                }
                            }
                            sending = false
                        }
                    },
                    enabled = !sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

enum class ChatRole { USER, AI }

data class ChatMessage(
    val role: ChatRole,
    val content: String,
)

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = if (isUser)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isUser)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bubbleColor,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}