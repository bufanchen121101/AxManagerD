package frb.axeron.manager.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AIMainScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AiAdvancedScreenDestination
import com.ramcosta.composedestinations.generated.destinations.AiMemoryScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ChatSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.CloudModelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DiagnosticScreenDestination
import com.ramcosta.composedestinations.generated.destinations.UsageStatsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.component.LocalBottomBarInset

/**
 * AI 设置中心（1.4.0）。
 *
 * 设计口径：
 *  - 这里只做「入口聚合」，不复制任何开关控件 —— 每个开关仍然只有一份实现，
 *    避免同一个设置在两处各存一份状态、互相打架。
 *  - 入口顺序按使用频率排：模型 → 对话参数 → 语音 → 数据排障。
 *  - 首屏卡片直接回答「现在到底在用哪个模型」，省掉进二级页确认这一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AiHubScreen(navigator: DestinationsNavigator) {
    val bottomInset = LocalBottomBarInset.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 设置") },
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = bottomInset + 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { AiOverviewCard() }

            // 【1.4.1】唯一总开关：是否使用用户自己填的 API 密钥（全局生效）
            item { KeySourceCard(onOpenCustom = { navigator.navigate(CloudModelScreenDestination) }) }

            item {
                HubGroup(title = "模型与推理") {
                    HubRow(
                        icon = Icons.Filled.AutoAwesome,
                        title = "AI 引擎",
                        desc = "安全引擎总开关、微分析、插件问答",
                        onClick = { navigator.navigate(AIMainScreenDestination) },
                    )
                    HubDivider()
                    HubRow(
                        icon = Icons.Filled.Api,
                        title = "模型与参数配置",
                        desc = "自定义 API 网址、Key、提供商与模型识别",
                        onClick = { navigator.navigate(CloudModelScreenDestination) },
                    )
                    HubDivider()
                    HubRow(
                        icon = Icons.Filled.Forum,
                        title = "对话与推理参数",
                        desc = "温度、历史条数、超时重试、流式输出、思考模式与推理等级",
                        onClick = { navigator.navigate(ChatSettingsScreenDestination) },
                    )
                }
            }

            item {
                // 【1.4.2】原来「语音服务配置」「Waifu 模式」是两个入口指向同一页，合并成一个
                HubGroup(title = "语音与呈现") {
                    HubRow(
                        icon = Icons.Filled.RecordVoiceOver,
                        title = "语音与呈现",
                        desc = "系统 TTS 自动朗读、语速调节、按句分段",
                        onClick = { navigator.navigate(AiAdvancedScreenDestination) },
                    )
                }
            }

            item {
                HubGroup(title = "数据与排障") {
                    HubRow(
                        icon = Icons.Filled.Psychology,
                        title = "AI 记忆",
                        desc = "每个模块一份记忆文件；点进去是互相关联的记忆网",
                        onClick = { navigator.navigate(AiMemoryScreenDestination) },
                    )
                    HubDivider()
                    HubRow(
                        icon = Icons.Filled.BarChart,
                        title = "用量与性能",
                        desc = "调用次数、Token 估算、7 日趋势、内存与存储",
                        onClick = { navigator.navigate(UsageStatsScreenDestination) },
                    )
                    HubDivider()
                    HubRow(
                        icon = Icons.Filled.BugReport,
                        title = "诊断与维护",
                        desc = "复制诊断信息（不含密钥）、重置对话偏好、清空对话记录",
                        onClick = { navigator.navigate(DiagnosticScreenDestination) },
                    )
                }
            }

        }
    }
}

/** 首屏总览：一句话说清「对话走哪条链路、制作模块用哪个模型」。 */
@Composable
private fun AiOverviewCard() {
    val scheme = MaterialTheme.colorScheme
    // 三级口径：自定义密钥（全局） > 官方默认 AI（分界面） > 内置直连
    val customGlobal = AIConfigStore.useCustomKey && AIConfigStore.hasCustomCloudConfig
    val official = !customGlobal && AIConfigStore.useOfficialAi && AIConfigStore.officialAxkeyAvailable
    val llm = if (customGlobal) {
        AIConfigStore.cloudModelName?.takeIf { it.isNotBlank() } ?: AIConfigStore.BUILTIN_AI_MODEL
    } else {
        AIConfigStore.modelDisplayName(AIConfigStore.generalModel)
    }
    val devModel = if (customGlobal) {
        "全局同自定义模型"
    } else if (official) {
        AIConfigStore.officialModelDisplayName()
    } else {
        "官方未启用，走内置 ${AIConfigStore.modelDisplayName(AIConfigStore.generalModel)}"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(scheme.primaryContainer, scheme.tertiaryContainer)
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "AI 能力总览",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.9f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    customGlobal -> "全局 · 自定义 API"
                    official -> "其他界面 · 官方默认 AI"
                    else -> "对话 · 内置 ${AIConfigStore.BUILTIN_AI_PROVIDER}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = llm,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "制作模块 · 官方链路",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = devModel,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            // 路由、思考与 Max 状态不在总览卡片底部重复展示。
        }
    }
}

/**
 * 「使用自定义密钥」总开关（1.4.1）。
 *
 * 这是唯一的总开关：打开且网址+Key 齐全 → 全局所有 AI 功能都用自定义模型；
 * 关闭 → 官方默认 AI（制作模块用本页选的模型，其他界面用对话页选的模型）；
 * 官方不可用 → 内置直连兜底。
 */
@Composable
private fun KeySourceCard(onOpenCustom: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val on = AIConfigStore.useCustomKey
    val ready = AIConfigStore.hasCustomCloudConfig
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(scheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "使用自定义 API 密钥",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "开启后全局生效；关闭则用官方默认 AI",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { AIConfigStore.setUseCustomKey(it) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    on && ready -> "当前：全局使用自定义模型（" +
                        (AIConfigStore.cloudModelName?.takeIf { it.isNotBlank() } ?: "未选模型") + "）"
                    on && !ready -> "已开启，但还没填网址或 Key —— 会回退官方/内置链路"
                    else -> "当前：使用官方默认 AI（制作模块与其他界面的模型可分别选择）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (on && !ready) scheme.error else scheme.onSurfaceVariant,
            )
            if (on && !ready) {
                TextButton(onClick = onOpenCustom) { Text("去填写网址与 Key") }
            }
        }
    }
}

@Composable
private fun HubGroup(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 13.dp)
                    .background(scheme.primary, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(scheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onPrimaryContainer,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HubDivider() {
    Box(
        modifier = Modifier
            .padding(start = 62.dp, end = 14.dp)
            .fillMaxWidth()
            .height(0.7.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    )
}
