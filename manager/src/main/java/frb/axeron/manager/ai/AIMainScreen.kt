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
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AiReferenceScreenDestination
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
            // ============ 0. AI 引擎总开关（最上方，唯一开关） ============
            // 【合并】原本「AI 引擎」+「微型分析模型」两个开关合并为这一个：
            // 两个开关在拦截链路里互相钳制（任一为 false 就放行），分开显示只会造成
            // “开关点了没反应”的错觉，因此统一为一个开关，一次切换同时生效。
            SettingsItem(
                iconVector = Icons.Filled.PowerSettingsNew,
                label = "AI 引擎",
                description = "总开关。开启＝执行模块前做 AI / 规则拦截分析；" +
                    "关闭＝不拦截、不分析，直接放行。",
                checked = AIConfigStore.aiEngineEnabled,
                onSwitchChange = { AIConfigStore.setAiEngineEnabled(it) },
            )

            // ============ 1. 拦截抓取时长（自「云端模型」页迁移至此） ============
            TraceTimeoutSection()

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

            // ============ 5. AI 参考文档（底层提示词 / 参考代码库 / 危险代码库） ============
            SettingsItem(
                iconVector = Icons.Filled.MenuBook,
                label = "AI 参考文档",
                description = "集中查阅：AI 底层提示词、内置参考代码库（规则）与危险代码库",
                onClick = { navigator.navigate(AiReferenceScreenDestination) },
            )
        }
    }
}

/**
 * 云端 AI 板块：官方默认 AI + 自定义 API 配置。
 *
 * 注意：云端对话入口已【合并到「云端模型配置」界面内】（唯一入口），
 * 此处不再重复提供，避免两个入口造成困惑。
 */
@Composable
private fun CloudAiSection(navigator: DestinationsNavigator) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("云端 AI")

        // 配置入口（官方默认 AI + 自定义 API + 云端对话）
        SettingsItem(
            iconVector = Icons.Filled.Cloud,
            label = "云端模型配置",
            description = "官方默认 AI / 自定义 API 地址、Key、模型（含「云端对话」入口）",
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

/**
 * 拦截抓取时长配置：运行时拦截（strace + sh -x）抓取模块真实执行指令的时长（秒）。
 * 值越大抓取越完整但等待越久，值越小等待越短但可能漏抓核心指令。
 *
 * 【迁移】原先位于「云端模型」页（CloudModelScreen），按需求移到 AI 设置主页面。
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
