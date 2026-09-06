package frb.axeron.manager.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ChatScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.SettingsItem
import kotlinx.coroutines.launch

/**
 * 云端模型配置界面（第二阶段）。
 *
 * 自上而下：
 * 1. 云端对话入口
 * 2. 官方默认 AI（开关，默认开启）
 * 3. 自定义 API 配置：提供商（下拉）、网址、API Key、模型名（支持自动识别）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun CloudModelScreen(navigator: DestinationsNavigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "云端模型",
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
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ============ 1. 云端对话入口 ============
            SettingsItem(
                iconVector = Icons.Filled.Chat,
                label = "云端对话",
                description = "与云端 AI 模型对话",
                onClick = {
                    navigator.navigate(ChatScreenDestination(modelType = "cloud"))
                },
            )

            // ============ 2. 官方默认 AI ============
            SettingsItem(
                iconVector = Icons.Filled.Verified,
                label = "官方默认 AI",
                description = "使用 AxManagerD 官方提供的免费 AI 服务（可能不稳定）",
                checked = AIConfigStore.useOfficialAi,
                onSwitchChange = { AIConfigStore.setUseOfficialAi(it) },
            )

            // ============ 3. 拦截抓取时长 ============
            TraceTimeoutSection()

            // ============ 4. 自定义 API 配置 ============
            CustomApiConfigSection()
        }
    }
}

/**
 * 拦截抓取时长配置：运行时拦截（strace + sh -x）抓取模块真实执行指令的时长（秒）。
 * 值越大抓取越完整但等待越久，值越小等待越短但可能漏抓核心指令。
 */
@Composable
private fun TraceTimeoutSection() {
    // 关键修复：用 AIConfigStore.traceTimeoutSeconds（mutableStateOf 驱动）作为真实值源，
    // LaunchedEffect 在其变化时同步到本地拖动状态，避免「remember 缓存初值导致外部修改后
    // Slider 不刷新」的问题（用户此前反馈「设置里拦截时间不可改变」的根因之一）。
    val stored = AIConfigStore.traceTimeoutSeconds
    var value by remember { mutableStateOf(stored.toFloat()) }
    androidx.compose.runtime.LaunchedEffect(stored) {
        value = stored.toFloat()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "拦截抓取时长",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "运行时拦截抓取模块真实指令的时长（秒）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                AIConfigStore.setTraceTimeoutSeconds(value.toInt())
            },
            valueRange = 3f..120f,
            // steps = 区间内离散点数量；(120-3) = 117 个整数间隔，若要每秒一档则是 116 个点
            steps = 116,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "当前：${value.toInt()} 秒（默认 15 秒）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomApiConfigSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "自定义 API 配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )

        // 提供商下拉
        ProviderDropdown()

        // 网址
        var endpoint by remember { mutableStateOf(AIConfigStore.cloudEndpoint ?: "") }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { v ->
                endpoint = v
                AIConfigStore.setCloudEndpoint(v)
            },
            label = { Text("API 网址 (Endpoint)") },
            placeholder = { Text("https://api.openai.com/v1") },
            leadingIcon = { Icon(Icons.Filled.Link, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // API Key
        var apiKey by remember { mutableStateOf(AIConfigStore.cloudApiKey ?: "") }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { v ->
                apiKey = v
                AIConfigStore.setCloudApiKey(v)
            },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // 模型名（自动识别 + 下拉选择）
        ModelSelector()

        // 获取模型列表按钮
        var loading by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    loading = true
                    scope.launch {
                        val result = ModelListFetcher.fetchModels(
                            endpoint = AIConfigStore.cloudEndpoint ?: "",
                            apiKey = AIConfigStore.cloudApiKey,
                            provider = AIConfigStore.cloudProvider ?: "OpenAI",
                        )
                        loading = false
                        result.fold(
                            onSuccess = { models ->
                                AIConfigStore.setAvailableModels(models)
                                Toast.makeText(
                                    context,
                                    "识别到 ${models.size} 个模型",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onFailure = { e ->
                                Toast.makeText(
                                    context,
                                    "识别失败：${e.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                },
                enabled = !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "识别中…" else "识别可用模型")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector() {
    var expanded by remember { mutableStateOf(false) }
    val models = AIConfigStore.availableModels

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = AIConfigStore.cloudModelName ?: "",
            onValueChange = { AIConfigStore.setCloudModelName(it) },
            label = { Text("模型名") },
            placeholder = { Text("手动输入或点击「识别可用模型」") },
            leadingIcon = { Icon(Icons.Filled.Cloud, null) },
            trailingIcon = {
                if (models.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true,
        )
        if (models.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            AIConfigStore.setCloudModelName(model)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown() {
    var expanded by remember { mutableStateOf(false) }
    val providers = remember { AIConfigStore.cloudProviders }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = AIConfigStore.cloudProvider ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("提供商") },
            leadingIcon = { Icon(Icons.Filled.Cloud, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider) },
                    onClick = {
                        AIConfigStore.setCloudProvider(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}