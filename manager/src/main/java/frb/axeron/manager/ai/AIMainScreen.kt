package frb.axeron.manager.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ChatScreenDestination
import com.ramcosta.composedestinations.generated.destinations.CloudModelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DangerCodeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.WhitelistScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.SettingsItem

/**
 * AI 主界面（第二阶段）。
 *
 * 自上而下：
 * 1. 微型分析模型 开关（最上方）
 * 2. 云端 AI 板块：官方默认 AI + 自定义 API 配置 + 对话（整合为一个板块）
 * 3. 危险代码库
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AIMainScreen(navigator: DestinationsNavigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI 引擎",
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
            // ============ 0. AI 引擎总开关（最上方） ============
            SettingsItem(
                iconVector = Icons.Filled.PowerSettingsNew,
                label = "AI 引擎",
                description = "AI 分析/对话功能总开关。关闭后所有分析将被跳过。",
                checked = AIConfigStore.aiMasterEnabled,
                onSwitchChange = { AIConfigStore.setAiMasterEnabled(it) },
            )

            // ============ 1. 微型分析模型开关 ============
            SettingsItem(
                iconVector = Icons.Filled.Psychology,
                label = "微型分析模型",
                description = "命令执行前的本地规则分析。关闭后若未配置云端则不再拦截。",
                checked = AIConfigStore.isMicroAnalysisEnabled,
                onSwitchChange = { AIConfigStore.setMicroAnalysisEnabled(it) },
            )

            // ============ 2. 云端 AI 板块（整合） ============
            CloudAiSection(navigator)

            // ============ 白名单 ============
            SettingsItem(
                iconVector = Icons.Filled.VerifiedUser,
                label = "AI 白名单",
                description = "加入白名单的模块，运行 / 启用 / 安装时均不拦截（${AIConfigStore.whitelist.size} 个）",
                onClick = { navigator.navigate(WhitelistScreenDestination) },
            )

            // ============ 4. 危险代码库 ============
            SettingsItem(
                iconVector = Icons.Filled.Dangerous,
                label = "危险代码库",
                description = "自定义危险规则代码的导入与导出",
                onClick = { navigator.navigate(DangerCodeScreenDestination) },
            )
        }
    }
}

/**
 * 云端 AI 板块：官方默认 AI + 自定义 API 配置 + 对话 整合在一个板块内。
 */
@Composable
private fun CloudAiSection(navigator: DestinationsNavigator) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("云端 AI")

        // 对话入口
        SettingsItem(
            iconVector = Icons.Filled.Chat,
            label = "云端对话",
            description = "与云端 AI 模型对话",
            onClick = {
                navigator.navigate(ChatScreenDestination(modelType = "cloud"))
            },
        )

        // 配置入口（官方默认 AI + 自定义 API）
        SettingsItem(
            iconVector = Icons.Filled.Cloud,
            label = "云端模型配置",
            description = "官方默认 AI / 自定义 API 地址、Key、模型",
            onClick = { navigator.navigate(CloudModelScreenDestination) },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
