package frb.axeron.manager.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.util.LocalSnackbarHost
import kotlinx.coroutines.launch

/**
 * AI 高级设置（1.4.0 新增）。
 *
 * 参照 Operit 设置页的分组，但**只保存到本机**、全部项都有真实生效点：
 *  - 语音服务配置：系统 TTS 自动朗读 + 语速 → 对话完成后朗读回复
 *  - Waifu 模式：回复按句分段呈现
 *  - 上下文与总结：历史条数上限 → 直接进请求的 history
 *  - 诊断日志：只给出 adb 抓取命令（内容不在界面展示）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AiAdvancedScreen(navigator: DestinationsNavigator) {
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val bottomInset = LocalBottomBarInset.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音与呈现") },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pv ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pv),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { GroupTitle("语音服务配置") }
            item {
                SwitchRow(
                    title = "自动朗读 AI 回复",
                    desc = "使用系统 TTS，离线可用；不读取、不上传任何内容",
                    checked = AIConfigStore.ttsAutoRead,
                    onChange = { AIConfigStore.setTtsAutoRead(it) },
                )
            }
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "朗读语速：${"%.1f".format(AIConfigStore.ttsRate)}x",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = AIConfigStore.ttsRate,
                        onValueChange = { AIConfigStore.setTtsRate(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                    )
                    TextButton(onClick = {
                        AiSpeaker.speak("这是一段试听文本，用于确认语音服务是否可用。")
                    }) { Text("试听") }
                }
            }

            item { GroupTitle("Waifu 模式") }
            item {
                SwitchRow(
                    title = "按句分段呈现",
                    desc = "流式输出时在每个句末自动换行，呈现「一句一段」的分句效果",
                    checked = AIConfigStore.waifuSplit,
                    onChange = { AIConfigStore.setWaifuSplit(it) },
                )
            }

            item { GroupTitle("诊断日志（adb 专用）") }
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "AI 请求过程会写成本机日志，界面上不展示内容（含密钥掩码，不含明文密钥）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "adb pull /sdcard/Android/data/frb.axeron.manager/files/ai_trace.log",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "或：adb logcat -s AI_TRACE",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            AiTrace.clear()
                            scope.launch { snackbar.showSnackbar("已清空本机 AI 日志") }
                        }) { Text("清空本机日志") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun InfoRow(title: String, desc: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditRow(title: String, desc: String, initial: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    onSave(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("留空表示跟随默认") },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
