package frb.axeron.manager.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AiReferenceScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ChatSettingsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.CloudModelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.DangerCodeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.WhitelistScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ai.axkey.AxKeyProvider
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.component.SettingsItem
import kotlinx.coroutines.launch

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
                .padding(vertical = 12.dp)
                // 【v1.4.0 修复 · 「AI 设置界面滑不下去」】
                // 底栏是浮在内容之上的覆盖层（见 LocalBottomBarInset 注释）。本页虽然一直有
                // verticalScroll，但滚动内容末尾没有给底栏留空间 —— 滚到底时最后几项
                // （AI 参考文档 / 危险代码库）正好压在底栏下面，看起来就是"滑不下去了"。
                // 这里在滚动内容的最底部补上底栏占用高度，滚到底会把最后一项完整露出来。
                .padding(bottom = LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ============ 0. AI 引擎总开关（最上方，唯一开关） ============
            // 【合并】原本「AI 引擎」+「微型分析模型」两个开关合并为这一个：
            // 两个开关在拦截链路里互相钳制（任一为 false 就放行），分开显示只会造成
            // “开关点了没反应”的错觉，因此统一为一个开关，一次切换同时生效。
            SettingsItem(
                iconVector = Icons.Filled.PowerSettingsNew,
                label = "拦截总开关（默认关闭）",
                description = "开启＝执行模块前先做「沙箱预演 + AI / 规则拦截分析」，" +
                    "可疑写入不会真的落到系统上。\n" +
                    "关闭（默认）＝不做预演、不弹拦截分析，但**仍然抓取**：" +
                    "运行模块时在真实执行过程中记录本次变更，并写入卸载恢复名单，卸载时可据此还原。",
                checked = AIConfigStore.aiEngineEnabled,
                onSwitchChange = { AIConfigStore.setAiEngineEnabled(it) },
            )

            // ============ 1. 连接云端 AI（中转站）—— 第二位置、醒目入口 ============
            // 【v1.9.1】按需求从「云端 AI」板块中独立出来，固定在总开关下方，
            // 用高亮提示条 + 星标标题强调，避免用户找不到连接入口。
            OfficialConnectSection()

            // ============ 2. 拦截抓取时长（自「云端模型」页迁移至此） ============
            TraceTimeoutSection()

            // ============ 3. 云端 AI 板块（模型配置 / 对话设置） ============
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
 * 注意：原「云端对话」页面已下线 —— 对话能力统一放在
 * 「模块 → 制作模块 → AI 辅助」里（按工程隔离，见 ModuleDevScreen.AiAssistTab），
 * 这里只保留连接与配置入口，避免两个对话入口造成困惑。
 */
@Composable
private fun CloudAiSection(navigator: DestinationsNavigator) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("云端 AI 配置")

        // 配置入口（官方默认 AI + 自定义 API）
        SettingsItem(
            iconVector = Icons.Filled.Cloud,
            label = "云端模型配置",
            description = "官方默认 AI / 自定义 API 地址、Key、模型",
            onClick = { navigator.navigate(CloudModelScreenDestination) },
        )


        // 对话设置（v1.4.1 新增，参考 Operit 的模型参数 / 提示词 / 数据三段式）
        SettingsItem(
            iconVector = Icons.Filled.Settings,
            label = "对话设置",
            description = "上下文长度、工具清单、思考展示、温度与历史条数、清空对话记录",
            onClick = { navigator.navigate(ChatSettingsScreenDestination) },
        )
    }
}

/**
 * 「连接官方 AI」入口（v1.4.0 新增）。
 *
 * 为什么需要：官方 AI 走中转站 + 每日动态密钥，客户端必须先用构建期注入的
 * 访问密码换取「日钥」才能发起对话。旧实现只在第一次发送时静默取钥，失败时界面上
 * 没有任何可分辨的反馈 —— 用户看到的就是「点了发送没反应」。
 *
 * 这里给一个显式入口：点击即真实打一次取钥请求（不吃本地缓存），结果（成功 /
 * 失败精确原因）直接显示在卡片里，用户能自行确认密钥到底拿到了没有。
 *
 * 【1.4.0 分流】只有「制作模块」的对话走中转站，其余 AI 功能走内置 NVIDIA 直连，
 * 因此本卡片是「制作模块专用」，不是全应用 AI 的前置条件。
 */
@Composable
private fun OfficialConnectSection() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(AxKeyProvider.keyState()) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    val statusText = when {
        busy -> "正在校验序列号并连接服务器…"
        // 【1.4.0 安全要求】不在界面展示密钥内容与到期时间：
        // 密钥做了构建期混淆 + 传输加密，界面一旦显示前几位/有效期，
        // 等于把「何时换钥、钥匙长什么样」暴露给截图与旁观者。
        state.hasKey -> "已连接 · 设备密钥可用"
        else -> "未连接 · 制作模块对话前请先连接"
    }

    val doConnect: () -> Unit = {
        if (!busy) {
            busy = true
            resultText = null
            scope.launch {
                try {
                    when (val r = AxKeyProvider.connect(forceRefresh = true)) {
                        is AxKeyProvider.ConnectResult.Ok -> {
                            state = AxKeyProvider.keyState()
                            resultOk = true
                            // 【1.4.0 安全要求】不显示密钥片段与到期时间，只报「可用」
                            resultText = "连接成功，序列号校验通过"
                        }
                        is AxKeyProvider.ConnectResult.Fail -> {
                            state = AxKeyProvider.keyState()
                            resultOk = false
                            resultText = "连接失败：${r.message}"
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    resultOk = false
                    resultText = "连接异常：${t.message ?: t.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }
    }

    // 【v1.9.1】醒目入口：独立标题 + 高亮提示条，固定在 AI 设置页第二个位置。
    SectionHeader("⭐ 连接云端 AI（中转站）")
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "推荐优先连接：验证系统序列号后按设备发放密钥，" +
                "供「制作模块」的 AI 对话使用。连接一次即可，失败会显示具体原因。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }

    SettingsItem(
        iconVector = Icons.Filled.Link,
        label = "⭐ 连接云端 AI（中转站）",
        description = "$statusText\n" +
            "仅「制作模块」对话走中转站；其余 AI 功能默认使用内置模型，无需连接。" +
            "连接时会校验系统序列号；通过后按设备发放密钥，每月额度独立计算。",
        content = { _, _ ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = doConnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            busy -> "连接中…"
                            state.hasKey -> "重新连接"
                            else -> "连接服务器"
                        }
                    )
                }
                resultText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
    )
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
