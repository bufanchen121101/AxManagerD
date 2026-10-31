package frb.axeron.manager.ui.screen

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.BuildConfig
import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.ChatSessionStore
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.util.LocalSnackbarHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断与维护（1.4.0 新增，设置页子页）。
 *
 * 只做「本机可读 / 可清理」的事，不涉及云端与备份：
 * - 复制诊断信息：版本、系统、DO 状态、AI 路由与对话参数拼成一段文本（**不含任何密钥**）
 * - 重置对话偏好：温度 / 历史条数 / 流式 / 超时 / 重试 / 自定义提示词 回到默认值
 * - 清空对话记录：删除本地保存的全部会话
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun DiagnosticScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHost.current
    var showClear by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun buildDiagnostics(): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val route = when {
            AIConfigStore.useCustomKey && AIConfigStore.hasCustomCloudConfig ->
                "自定义 API（全局，${AIConfigStore.cloudModelName.orEmpty()}）"
            AIConfigStore.useOfficialAi && AIConfigStore.officialAxkeyAvailable ->
                "官方默认 AI（其他界面 ${AIConfigStore.modelDisplayName(AIConfigStore.generalModel)}）"
            AIConfigStore.builtinAiAvailable ->
                "内置 NVIDIA (${AIConfigStore.modelDisplayName(AIConfigStore.generalModel)})"
            else -> "不可用"
        }
        return buildString {
            appendLine("==== AxManagerD 诊断信息 ====")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("包名：${context.packageName}")
            appendLine("系统：Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("设备所有者：${if (isDeviceOwner) "是" else "否"}")
            appendLine(
                "制作模块中转站：" +
                    if (AIConfigStore.officialAxkeyAvailable) "可尝试连接" else "未注入访问密码"
            )
            appendLine("通用 AI 路由：$route")
            appendLine(
                "温度：${AIConfigStore.cloudTemperature}  历史条数：${AIConfigStore.chatHistoryLimit}"
            )
            appendLine(
                "流式：${AIConfigStore.chatStreamEnabled}  超时：${AIConfigStore.chatTimeoutSec}s" +
                    "  重试：${AIConfigStore.chatRetryCount}"
            )
            appendLine("AI 总开关：${AIConfigStore.aiEngineEnabled}")
            append("（本信息不含任何密钥）")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "诊断与维护",
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
            SectionTitleLocal("诊断")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "把版本、系统、设备所有者状态与 AI 路由拼成一段文本，方便排障时贴给别人。" +
                        "内容不含密钥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    val text = buildDiagnostics()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("AxManagerD 诊断信息", text))
                    scope.launch { snackbar.showSnackbar("诊断信息已复制") }
                }) {
                    Text("复制诊断信息")
                }
            }

            SectionTitleLocal("维护")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "重置会恢复：温度 0.7、历史自动、流式开、超时自动、不重试、清空自定义提示词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    AIConfigStore.setCloudTemperature(0.7f)
                    AIConfigStore.setChatHistoryLimit(0)
                    AIConfigStore.setChatStreamEnabled(true)
                    AIConfigStore.setChatTimeoutSec(0)
                    AIConfigStore.setChatRetryCount(0)
                    AIConfigStore.setChatSystemPrompt("")
                    scope.launch { snackbar.showSnackbar("对话偏好已重置") }
                }) {
                    Text("重置对话偏好")
                }
                Spacer(Modifier.width(2.dp))
                Button(onClick = { showClear = true }) {
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
private fun SectionTitleLocal(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}