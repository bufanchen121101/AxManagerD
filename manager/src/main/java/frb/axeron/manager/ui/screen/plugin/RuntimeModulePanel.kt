package frb.axeron.manager.ui.screen.plugin

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import frb.axeron.manager.features.runtime.RuntimeDiagnostics
import frb.axeron.manager.features.runtime.RuntimeModuleService
import frb.axeron.manager.features.runtime.model.RuntimeModuleState
import frb.axeron.manager.features.runtime.model.RuntimeModuleStatus
import kotlinx.coroutines.launch

/**
 * 运行时模块面板（内嵌于「插件」界面的第二个分区）。
 *
 * 与 shell 模块分区并列，由 PluginScreen 的 SegmentedButton 切换。
 * 内部保留二级切换：模块列表 / 输出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeModulePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // 进入分区时确保服务在跑并重扫目录，装好的模块立即可见。
    LaunchedEffect(Unit) {
        runCatching { RuntimeModuleService.start(context) }
        RuntimeModuleScreenState.refresh(context)
    }

    // 二级切换：0=模块列表，1=输出
    // 「指令」页已按需求移除（快捷指令对运行时模块没有实际用途）。
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("模块", "输出")

    // 「运行」按钮触发：自动切到「输出」Tab。
    val wantOutputTab = RuntimeModuleScreenState.requestOutputTab
    LaunchedEffect(wantOutputTab) {
        if (wantOutputTab) {
            tab = 1
            RuntimeModuleScreenState.requestOutputTab = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                    icon = {},
                ) {
                    Text(label)
                }
            }
        }

        DiagnosticsBar()

        when (tab) {
            0 -> ModuleList()
            else -> OutputPanel()
        }
    }
}

/**
 * 诊断工具条：把运行时扫描全链路导出成 JSON，供外部排查。
 *
 * 与业务无关，纯粹为了「列表不显示」这类问题的定位。导出路径会显示在按钮下方。
 */
@Composable
private fun DiagnosticsBar() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ElevatedAssistChip(
                onClick = {
                    if (busy) return@ElevatedAssistChip
                    busy = true
                    scope.launch {
                        val f = RuntimeDiagnostics.export(context)
                        hint = if (f != null) {
                            "已导出：${f.absolutePath}"
                        } else {
                            "导出失败（看日志）"
                        }
                        busy = false
                    }
                },
                label = { Text(if (busy) "导出中…" else "导出诊断") },
            )
            ElevatedAssistChip(
                onClick = {
                    RuntimeModuleScreenState.refresh(context)
                    hint = "已触发重扫"
                },
                label = { Text("重扫") },
            )
        }
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 输出条目所属的来源。 */
enum class OutputScope(val label: String) {
    /** 某个具体运行时模块产生的输出（如 onactivate.sh / 模块采集）。 */
    MODULE("模块"),

    /** 快捷指令（全局 shell）产生的输出。 */
    COMMAND("指令"),

    /** 服务层面的总体输出 / 汇总。 */
    OVERALL("总体"),
}

/** 一条输出记录。 */
data class OutputItem(
    val scope: OutputScope,
    val title: String,
    val subtitle: String = "",
    val body: String,
    val ok: Boolean? = null,
    val moduleId: String? = null,
    val at: Long = System.currentTimeMillis(),
)

/** 页面内共享状态（避免为了这点 UI 去改 ViewModelGlobal）。 */
object RuntimeModuleScreenState {
    var lastOutput by mutableStateOf("")
    var lastCommand by mutableStateOf("")

    /** 输出记录，按来源分别保存，便于「单模块」与「总体」分开查看。 */
    var outputs by mutableStateOf<List<OutputItem>>(emptyList())

    /**
     * 请求在「输出」页打开指定模块的详情。
     *
     * 由模块卡片上的「运行」按钮设置：执行完 action.sh 后，
     * 面板会自动切到「输出」Tab 并进入该模块的详情，用户无需手动翻找。
     * 消费后由 [OutputPanel] 置回 null，避免重复跳转。
     */
    var openModuleOutput by mutableStateOf<String?>(null)

    /** 请求切到「输出」Tab。由「运行」按钮一并触发。 */
    var requestOutputTab by mutableStateOf(false)

    /** 追加一条输出（保留最近 200 条）。 */
    fun appendOutput(item: OutputItem) {
        outputs = (outputs + item).takeLast(200)
        // 兼容旧字段
        lastCommand = item.subtitle.ifBlank { item.title }
        lastOutput = item.body
    }

    /**
     * 清空输出。
     *
     * @param scope 为 null 表示清空全部；否则只清该来源。
     * @param moduleId 当 scope 为 [OutputScope.MODULE] 时，只清这一个模块的记录；
     *                 为 null 表示清掉所有模块的记录。
     */
    fun clearOutput(scope: OutputScope? = null, moduleId: String? = null) {
        outputs = when {
            scope == null -> emptyList()
            moduleId != null -> outputs.filterNot { it.scope == scope && it.moduleId == moduleId }
            else -> outputs.filter { it.scope != scope }
        }
        if (scope == null || scope == OutputScope.COMMAND) {
            lastCommand = ""
            lastOutput = ""
        }
    }

    /**
     * 触发一次重新扫描（安装完模块 / 手动点刷新时调用）。
     *
     * 服务会把最新扫描结果写回 RuntimeModuleService.statuses，
     * UI 因订阅该 Compose 状态而自动重组。
     */
    fun refresh(context: Context) {
        runCatching { RuntimeModuleService.rescan(context) }
    }
}

@Composable
private fun ModuleList() {
    val statuses = RuntimeModuleService.statuses
    if (statuses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "未发现运行时模块\n\n点右下角「+」安装一个，模块目录为 axeron/runtime_plugins/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(statuses, key = { it.id }) { status ->
            ModuleCard(status)
        }
    }
}

@Composable
private fun ModuleCard(status: RuntimeModuleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.name.ifBlank { status.id }, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "运行模型：${status.runModel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 右上角：运行状态提示 + 启用开关，纵向排列。
                // 开关放在这里而不是下方行内，避免模块尺寸变化时被挤成竖排文字。
                Column(horizontalAlignment = Alignment.End) {
                    StateBadge(status.state)
                    Spacer(Modifier.height(2.dp))
                    Switch(
                        checked = status.enabled,
                        onCheckedChange = { on ->
                            RuntimeModuleService.requestToggle(status.id, on)
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stateText(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // softWrap=true + 不限制行数：模块尺寸变小时也只会换行，
                    // 不会因为窄列宽被逐字挤成竖排。
                    softWrap = true,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.weight(1f),
                )
                if (status.pid > 0) {
                    Text(
                        text = "pid ${status.pid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 【需求4】WebUI 入口：< > 图标，仅在模块声明了 webroot/index.html 时可用。
                // 与 shell 模块（PluginItem）的 Web 入口语义一致。
                if (status.hasWebUi) {
                    val openContext = LocalContext.current
                    IconButton(
                        onClick = {
                            if (!status.enabled) {
                                Toast.makeText(
                                    openContext,
                                    "模块已禁用，请先启用再打开 WebUI",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                runCatching {
                                    openContext.startActivity(
                                        Intent(
                                            openContext,
                                            frb.axeron.manager.ui.webui.WebUIActivity::class.java,
                                        ).apply {
                                            // 运行时模块不在 Axeron 插件表里，
                                            // 必须直接传模块 id + 目录，供 WebUIActivity 定位 webroot。
                                            putExtra("id", status.id)
                                            putExtra("runtime_dir", status.dirId)
                                            putExtra("runtime_mode", true)
                                        }
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        openContext,
                                        "打开 WebUI 失败：${it.message}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Code, contentDescription = "WebUI")
                    }
                }
                // 【需求2】动作入口：仅当模块目录存在 action.sh 时显示。
                // 图标与 shell 模块（PluginItem 的「运行」按钮）保持一致：Icons.Outlined.Terminal。
                //
                // 不复用 ExecutePluginActionScreen：那个界面用 PARENT_PLUGIN/<dirId> 定位目录
                // （ExecutePluginAction.kt:114-123），只适用装在 plugins/ 下的 shell 模块；
                // 运行时模块在 runtime_plugins/ 下，复用会找不到 action.sh。
                // 因此这里直接交给服务侧按模块自身目录执行。
                if (status.hasAction) {
                    val actionContext = LocalContext.current
                    IconButton(
                        onClick = {
                            if (!status.enabled) {
                                Toast.makeText(
                                    actionContext,
                                    "模块已禁用，请先启用再执行动作",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                RuntimeModuleService.requestAction(actionContext, status.id) { ok, body ->
                                    // 结果落到「输出」页的该模块分区，与 shell 模块的运行体验对齐。
                                    RuntimeModuleScreenState.appendOutput(
                                        OutputItem(
                                            scope = OutputScope.MODULE,
                                            title = status.name.ifBlank { status.id },
                                            subtitle = "action.sh",
                                            moduleId = status.id,
                                            ok = ok,
                                            body = body,
                                        )
                                    )
                                    // 需求：运行后直接进入该模块的输出界面，让用户立刻看到结果，
                                    // 不需要自己再去「输出」页里翻。参考 shell 模块「点运行 → 进输出页」。
                                    RuntimeModuleScreenState.requestOutputTab = true
                                    RuntimeModuleScreenState.openModuleOutput = status.id
                                    Toast.makeText(
                                        actionContext,
                                        if (ok) "已执行，正在展示输出" else "执行失败，详见输出",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = "运行")
                    }
                }
                // 【需求2】卸载：二次确认后停止进程并写 remove 标记
                var askUninstall by remember { mutableStateOf(false) }
                val context = LocalContext.current
                IconButton(onClick = { askUninstall = true }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "卸载",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                if (askUninstall) {
                    AlertDialog(
                        onDismissRequest = { askUninstall = false },
                        title = { Text("卸载模块") },
                        text = {
                            Text(
                                "确定卸载「${status.name.ifBlank { status.id }}」吗？\n\n" +
                                        "会先停止运行中的进程，再删除模块。此操作不可撤销。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                askUninstall = false
                                RuntimeModuleService.requestUninstall(context, status.id)
                            }) { Text("卸载") }
                        },
                        dismissButton = {
                            TextButton(onClick = { askUninstall = false }) { Text("取消") }
                        },
                    )
                }
            }

            AnimatedVisibility(visible = status.lastError.isNotBlank()) {
                Text(
                    text = status.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StateBadge(state: RuntimeModuleState) {
    val (text, color) = when (state) {
        RuntimeModuleState.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        RuntimeModuleState.STARTING -> "启动中" to MaterialTheme.colorScheme.tertiary
        RuntimeModuleState.RETRYING -> "重启中" to MaterialTheme.colorScheme.tertiary
        RuntimeModuleState.STOPPING -> "停止中" to MaterialTheme.colorScheme.tertiary
        RuntimeModuleState.FAILED -> "已失败" to MaterialTheme.colorScheme.error
        RuntimeModuleState.DISABLED -> "已禁用" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "空闲" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun stateText(status: RuntimeModuleStatus): String = when (status.state) {
    RuntimeModuleState.RUNNING -> {
        // 探测间隔由模块自行声明，未声明时为默认 8 秒，因此这里动态显示，
        // 避免出现「文案说 8 秒、实际按 3 秒探测」的误导。
        val sec = status.aliveCheckIntervalMs / 1000.0
        val shown = if (sec % 1.0 == 0.0) sec.toInt().toString() else String.format("%.1f", sec)
        "进程存活探测中（每 $shown 秒）"
    }
    RuntimeModuleState.RETRYING -> "第 ${status.restartCount} 次重试"
    RuntimeModuleState.FAILED -> "重试超限，已熔断"
    RuntimeModuleState.IDLE -> "未运行"
    RuntimeModuleState.DISABLED -> "模块未启用"
    RuntimeModuleState.STARTING -> "正在执行 onactivate.sh"
    RuntimeModuleState.STOPPING -> "正在执行 onstop.sh"
    RuntimeModuleState.KILLED -> "进程已终止"
}

// ---------------------------------------------------------------------
// 输出
// ---------------------------------------------------------------------
/**
 * 输出页（两级结构）。
 *
 * 第一级：模块列表 —— 每个运行时模块一行，显示名称与输出条数，点进去看该模块的输出。
 * 第二级：该模块的输出明细。
 *
 * 之前是把所有模块的输出堆在一条时间流里，模块一多就分不清谁是谁；
 * 现在改为「选模块 → 看输出」，与 shell 模块的「一模块一输出」体验对齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputPanel() {
    val outputs = RuntimeModuleScreenState.outputs

    // 当前正在查看的模块；null 表示还在模块选择层。
    var openedModuleId by remember { mutableStateOf<String?>(null) }

    // 「运行」按钮触发的自动进入：消费一次后清空，避免返回时又被弹回去。
    val pendingOpen = RuntimeModuleScreenState.openModuleOutput
    LaunchedEffect(pendingOpen) {
        if (pendingOpen != null) {
            openedModuleId = pendingOpen
            RuntimeModuleScreenState.openModuleOutput = null
        }
    }

    // 按模块聚合输出（含总体的「记录输出」快照）。
    val grouped = remember(outputs) {
        outputs.filter { it.scope == OutputScope.MODULE }
            .groupBy { it.moduleId ?: it.title }
    }

    // 用状态里的模块列表做主视图，保证「没有输出」的模块也能被选中。
    val moduleStatuses = RuntimeModuleService.statuses
    val moduleDisplayNames = remember(moduleStatuses) {
        moduleStatuses.associate { it.id to it.name.ifBlank { it.id } }
    }

    val opened = openedModuleId
    if (opened != null) {
        ModuleOutputDetail(
            moduleId = opened,
            displayName = moduleDisplayNames[opened] ?: opened,
            items = grouped[opened] ?: emptyList(),
            onBack = { openedModuleId = null },
        )
        return
    }

    // ---------------- 第一级：模块选择 ----------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择一个模块，查看它的输出",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            ElevatedAssistChip(
                onClick = { RuntimeModuleScreenState.clearOutput(null) },
                label = { Text("清空全部") },
            )
        }

        if (moduleStatuses.isEmpty()) {
            EmptyHint("还没有运行时模块\n\n先到「模块」页安装一个。")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(moduleStatuses, key = { it.id }) { status ->
                val count = grouped[status.id]?.size ?: 0
                OutputModuleRow(
                    name = status.name.ifBlank { status.id },
                    moduleId = status.id,
                    stateLabel = stateText(status),
                    count = count,
                    onClick = { openedModuleId = status.id },
                )
            }
        }
    }
}

/** 第一级列表里的一行：模块名 + 输出条数 + 进入箭头。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputModuleRow(
    name: String,
    moduleId: String,
    stateLabel: String,
    count: Int,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = moduleId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (count > 0) "$count 条" else "暂无",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (count > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 第二级：单个模块的输出明细。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleOutputDetail(
    moduleId: String,
    displayName: String,
    items: List<OutputItem>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = moduleId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ElevatedAssistChip(
                onClick = { RuntimeModuleScreenState.clearOutput(OutputScope.MODULE, moduleId) },
                label = { Text("清空") },
            )
        }

        if (items.isEmpty()) {
            EmptyHint("该模块还没有输出\n\n点模块卡片上的「运行」入口执行 action.sh，结果会出现在这里。")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(items.asReversed(), key = { "${it.at}_${it.subtitle}_${it.title}" }) { item ->
                OutputCard(item, showScope = false)
            }
        }
    }
}

@Composable
private fun OutputCard(item: OutputItem, showScope: Boolean) {
    val tag = item.scope.label
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (item.ok) {
                true -> MaterialTheme.colorScheme.surfaceContainerLow
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showScope) {
                    Text(
                        text = "[$tag]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}