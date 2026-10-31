package frb.axeron.manager.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.util.LocalSnackbarHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对话设置（v1.4.1 新增，参数组织参考 Operit 的「模型参数 / 提示词 / 数据」三段式）。
 *
 * 这里放的都是**真正会生效**的参数（不是摆设）：
 *  - 温度 → 请求体的 temperature（AIChatService 直接读 AIConfigStore.cloudTemperature）
 *  - 历史条数 → chatStream 里裁剪 history 的上限
 *  - 流式输出 → 关闭后请求 stream=false，整包返回（弱网/中转站不稳时更可靠）
 *  - 请求超时 → 覆盖 AIChatService 的 read/call timeout（0 = 自动）
 *  - 失败重试 → 非流式调用（chatOnce）失败后自动重试 0~3 次
 *  - 清空对话数据 → 删除本地保存的所有会话
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ChatSettingsScreen(navigator: DestinationsNavigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current

    var temperature by remember { mutableFloatStateOf(AIConfigStore.cloudTemperature) }
    var historyLimit by remember { mutableIntStateOf(AIConfigStore.chatHistoryLimit) }
    var streamEnabled by remember { mutableStateOf(AIConfigStore.chatStreamEnabled) }
    var retryCount by remember { mutableIntStateOf(AIConfigStore.chatRetryCount) }
    var timeoutSec by remember { mutableIntStateOf(AIConfigStore.chatTimeoutSec) }
    var showClear by remember { mutableStateOf(false) }
    // 【1.4.2】上下文长度 + 小工具开关（都是真正会写进请求 body / system 提示词的参数）
    var historyItemChars by remember { mutableIntStateOf(AIConfigStore.chatHistoryItemChars) }
    var promptChars by remember { mutableIntStateOf(AIConfigStore.chatPromptChars) }
    var showReasoning by remember { mutableStateOf(AIConfigStore.showReasoning) }
    var ghToken by remember { mutableStateOf(AIConfigStore.githubToken) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "对话设置",
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
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
                .padding(bottom = LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ============ 模型参数 ============
            SectionTitle("模型参数")

            // 温度
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("温度（随机性）", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "越小越稳定、越大越发散。代码类建议 0.2~0.7。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    onValueChangeFinished = {
                        AIConfigStore.setCloudTemperature(temperature)
                    },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "当前：${"%.1f".format(temperature)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 历史条数
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("携带的历史消息条数", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "0 = 自动（官方 6 条 / 自定义 20 条）。官方免费模型建议保持自动或调小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = historyLimit.toFloat(),
                    onValueChange = { historyLimit = it.toInt() },
                    onValueChangeFinished = {
                        AIConfigStore.setChatHistoryLimit(historyLimit)
                    },
                    valueRange = 0f..20f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (historyLimit == 0) "当前：自动" else "当前：$historyLimit 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 流式输出
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("流式输出", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启＝逐字显示；关闭＝等全部生成完再一次性显示（更稳）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = streamEnabled,
                    onCheckedChange = {
                        streamEnabled = it
                        AIConfigStore.setChatStreamEnabled(it)
                    },
                )
            }

            // 请求超时（1.4.0 新增）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("单次请求超时", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "0 = 自动（制作模块 300 秒 / 其余 180 秒）。弱网可调大，避免「发送后一直没反应」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = timeoutSec.toFloat(),
                    onValueChange = { timeoutSec = it.toInt() },
                    onValueChangeFinished = { AIConfigStore.setChatTimeoutSec(timeoutSec) },
                    valueRange = 0f..600f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (timeoutSec == 0) "当前：自动" else "当前：$timeoutSec 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 失败重试（1.4.0 新增）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Text("失败自动重试", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "只作用于一次性返回的调用（脚本生成 / 审查等）；流式对话不自动重试，避免重复文本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = retryCount.toFloat(),
                    onValueChange = { retryCount = it.toInt() },
                    onValueChangeFinished = { AIConfigStore.setChatRetryCount(retryCount) },
                    valueRange = 0f..3f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (retryCount == 0) "当前：不重试" else "当前：最多重试 $retryCount 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ============ 上下文与世界（1.4.2 新增） ============
            SectionTitle("上下文与世界")
            BudgetRow(
                title = "单条历史上限",
                desc = "每条历史消息最多携带多少字符。0 = 自动（官方 2000 / 自定义不裁）。",
                value = historyItemChars,
                options = listOf(0, 500, 1000, 2000, 4000, 8000),
                onChange = {
                    historyItemChars = it
                    AIConfigStore.setChatHistoryItemChars(it)
                },
            )
            BudgetRow(
                title = "单次输入上限",
                desc = "一条消息最多发送多少字符。0 = 自动（官方 6000 / 自定义不裁）。",
                value = promptChars,
                options = listOf(0, 2000, 6000, 16000, 32000, 64000),
                onChange = {
                    promptChars = it
                    AIConfigStore.setChatPromptChars(it)
                },
            )

            // ============ 工具（1.4.3：固定启用，不再是开关） ============
            SectionTitle("工具")
            Text(
                "MKDIR / LS / READ / DEL / RUN / SEARCH / GH / FIND 共 8 个，是 agent 的通用能力，" +
                    "固定启用、不可关闭。清单在对话界面输入条上方的「工具」里查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // ============ 思考展示（1.4.2 新增） ============
            SectionTitle("思考展示")
            ToolSwitch(
                title = "显示模型思考过程",
                desc = "开启后回复气泡上方出现可展开的「思考」区块；仅影响显示，不改请求。",
                checked = showReasoning,
            ) {
                showReasoning = it
                AIConfigStore.setShowReasoning(it)
            }

            // ============ GitHub（1.4.2 新增） ============
            SectionTitle("GitHub")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "可选。未填时 GitHub 接口按出口 IP 限流 60 次/小时；填了提升到 5000 次/小时，" +
                        "大量使用 GH / FIND 时基本不再限流。只需公共仓库读权限，不必勾任何 scope。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub Token（可留空）") },
                    placeholder = { Text("github_pat_... 或 ghp_...") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        AIConfigStore.githubToken = ghToken.trim()
                        scope.launch { snackbar.showSnackbar("Token 已保存") }
                    }) {
                        Text("保存 Token")
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = {
                        ghToken = ""
                        AIConfigStore.githubToken = ""
                        scope.launch { snackbar.showSnackbar("已清空 Token") }
                    }) {
                        Text("清空")
                    }
                }
            }

            // ============ 数据 ============
            SectionTitle("数据")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "对话记录保存在应用私有目录，卸载应用即清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { showClear = true },
                ) {
                    Text("清空所有对话记录")
                }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空所有对话记录？") },
            text = { Text("将删除本机保存的全部会话，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { ChatSessionStore.clearAll() }
                        snackbar.showSnackbar("已清空 $n 个会话")
                    }
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BudgetRow(
    title: String,
    desc: String,
    value: Int,
    options: List<Int>,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                FilterChip(
                    selected = value == opt,
                    onClick = { onChange(opt) },
                    label = { Text(if (opt == 0) "自动" else opt.toString()) },
                )
            }
        }
    }
}

@Composable
private fun ToolSwitch(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}