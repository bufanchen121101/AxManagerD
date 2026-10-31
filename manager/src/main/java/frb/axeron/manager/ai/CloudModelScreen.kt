package frb.axeron.manager.ai

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.LocalBottomBarInset
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
                .padding(vertical = 12.dp)
                // 悬浮底栏占位：本页最后一项就是「识别可用模型」按钮，
                // 不留出底栏高度就会和悬浮的玻璃底栏重合（值由 AxActivity 实测下发）。
                .padding(bottom = LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ============ 1. 内置 AI（通用用途默认路由） ============
            // 【1.4.0 分流】「通用 AI」类功能（终端助手 / 恢复脚本等）
            // 默认直连内置 NVIDIA 模型；只有「制作模块」对话走中转站。
            // 此处只做说明、不再提供开关：开关会让界面状态与真实路由不一致。
            SettingsItem(
                iconVector = Icons.Filled.Verified,
                label = "内置 AI",
                description = "通用功能默认使用内置模型 ${AIConfigStore.modelDisplayName(AIConfigStore.generalModel)}，" +
                    "无需填写 Key；下方自定义配置优先于内置模型。",
            )

            // ============ 1.1 官方模型（辅助选择：快速 / 慢速 / 小模型） ============
            var showOfficialModelPicker by remember { mutableStateOf(false) }
            SettingsItem(
                iconVector = Icons.Filled.Psychology,
                label = "官方模型",
                description = "当前：${AIConfigStore.officialModelDisplayName()}。" +
                    "仅「制作模块」对话使用；点击打开弹窗，按「快速 / 慢速 / 小模型」三档选择。",
                onClick = { showOfficialModelPicker = true },
            )
            if (showOfficialModelPicker) {
                OfficialModelPickerDialog(
                    current = AIConfigStore.officialModelName,
                    onPick = { value ->
                        AIConfigStore.setOfficialModelName(value)
                        showOfficialModelPicker = false
                    },
                    onDismiss = { showOfficialModelPicker = false },
                )
            }

            // ============ 3. 自定义 API 配置 ============
            CustomApiConfigSection()
        }
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
            placeholder = { Text("Your link") },
            leadingIcon = { Icon(Icons.Filled.Link, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // API Key
        // 【1.4.0 安全要求】不回填已保存的 Key，界面上不出现任何明文片段：
        // 输入框恒为空 + 密码掩码，仅当用户主动输入时才写入存储。
        // 留空不写入 → 不破坏已有配置；用户清空后保存即回落到内置密钥。
        var apiKey by remember { mutableStateOf("") }
        val keyConfigured = !AIConfigStore.cloudApiKey.isNullOrBlank()
        OutlinedTextField(
            value = apiKey,
            onValueChange = { v ->
                apiKey = v
                AIConfigStore.setCloudApiKey(v.trim())
            },
            label = { Text("API Key") },
            placeholder = {
                Text(if (keyConfigured) "已配置（内容不显示）" else "留空则使用内置密钥")
            },
            leadingIcon = { Icon(Icons.Filled.Key, null) },
            visualTransformation = PasswordVisualTransformation(),
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

/**
 * 官方模型选择弹窗：按「快速 / 慢速 / 小模型」三档展示，点选即保存并关闭。
 *
 * 数据源：AIConfigStore.officialModelGroups（见该处注释：kimi-k3 保持短名）。
 */
@Composable
private fun OfficialModelPickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择官方模型") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AIConfigStore.officialModelGroups.forEach { group ->
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    group.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(option.value) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = current == option.value,
                                onClick = { onPick(option.value) },
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = option.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}