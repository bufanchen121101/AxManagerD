package frb.axeron.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.R
import frb.axeron.manager.ai.CommandInspector
import frb.axeron.manager.ai.RunConflictAnalyzer
import frb.axeron.manager.ai.RunRecorderStore
import frb.axeron.manager.ui.component.CommandDetailSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行历史。
 *
 * ## 作用
 * 把「卸载回滚」链路落盘的抓取日志（`filesDir/rollback/{dirId}/{模块名}.axd_log`）
 * 以 **模块名** 为标题列出来，点进去看这个模块被拦截抓取到的指令原文。
 *
 * ## 分组口径
 * 标题取日志首行 meta 的 `name`（模块原名），**不是** `dirId` ——
 * 文件名里的特殊字符已被替换成下划线，无法反推原名。
 *
 * ## 为什么用单页双视图而不是两个 Destination
 * 详情页需要携带「哪一条记录」的信息。本项目所有 `@Destination` 页面都只用
 * `DestinationsNavigator` / `ViewModelGlobal` 参数，没有 String 路由参数先例；
 * 把模块目录名/文件名塞进 nav route 属于未经验证的路径（中文名、路径分隔符都可能出问题）。
 * 因此详情做成同页内的第二视图：列表 ↔ 详情由本地状态切换，系统返回键先回列表。
 *
 * ## 实现约束
 * 纯只读：不写、不改、不删任何日志文件，落盘与清理仍然只由 [frb.axeron.manager.ai.UninstallRollback] 负责。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun RunRecorderScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    // ---------- 列表视图状态 ----------
    var loading by remember { mutableStateOf(true) }
    var records by remember { mutableStateOf<List<RunRecorderStore.Group>>(emptyList()) }
    var reloadToken by remember { mutableStateOf(0) }

    // ---------- 详情视图状态（opened == null 表示当前显示列表） ----------
    var opened by remember { mutableStateOf<RunRecorderStore.Group?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailCommands by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailReloadToken by remember { mutableStateOf(0) }

    // ---------- 指令详情悬浮层状态（null 表示未打开） ----------
    var inspecting by remember { mutableStateOf<String?>(null) }

    // ---------- 冲突分析状态 ----------
    var conflictLoading by remember { mutableStateOf(true) }
    var conflicts by remember { mutableStateOf<List<RunConflictAnalyzer.Conflict>>(emptyList()) }
    var showConflicts by remember { mutableStateOf(false) }
    var openedConflict by remember { mutableStateOf<RunConflictAnalyzer.Conflict?>(null) }

    // ---------- 「只显示有意义的指令」开关（默认开启；跨旋转保留） ----------
    var meaningfulOnly by rememberSaveable { mutableStateOf(true) }

    // ---------- 重置视图状态 ----------
    var showReset by remember { mutableStateOf(false) }
    // 重置执行中：禁止用任何方式退出（父级返回键 / 系统返回键）
    var resetBusy by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        loading = true
        conflictLoading = true
        val loaded = withContext(Dispatchers.IO) {
            val groups = RunRecorderStore.listGroups(context)
            // 同一遍 IO 里把冲突也算出来：入口卡片要显示冲突数量
            groups to RunConflictAnalyzer.analyzeGroups(groups)
        }
        records = loaded.first
        conflicts = loaded.second
        loading = false
        conflictLoading = false
    }

    LaunchedEffect(opened, detailReloadToken) {
        val group = opened ?: return@LaunchedEffect
        detailLoading = true
        detailCommands = withContext(Dispatchers.IO) {
            RunRecorderStore.readGroupCommands(group)
        }
        detailLoading = false
    }

    // 详情视图下拦截系统返回键：先回到列表，而不是直接退出本页。
    // 悬浮层打开时交还返回键（由 ModalBottomSheet 自己消费），否则会连详情一起关掉。
    // 冲突分析也是本页内的第二/第三层视图，返回键同样逐层后退。
    BackHandler(
        enabled = (opened != null || showConflicts || openedConflict != null || showReset) &&
            inspecting == null,
    ) {
        when {
            // 重置执行中：返回键被吞掉，不退出（用户要求「过程中不能因点击别处返回」）
            showReset -> if (!resetBusy) showReset = false
            openedConflict != null -> openedConflict = null
            showConflicts -> showConflicts = false
            else -> opened = null
        }
    }

    val current = opened
    val copiedText = stringResource(R.string.run_recorder_copied)

    Scaffold(
        topBar = {
            val conflict = openedConflict
            when {
                showReset -> TopBar(
                    title = stringResource(R.string.run_reset),
                    subtitle = stringResource(R.string.run_reset_desc),
                    scrollBehavior = scrollBehavior,
                    backEnabled = !resetBusy,
                    onBack = { if (!resetBusy) showReset = false },
                )

                conflict != null -> TopBar(
                    title = conflict.display,
                    subtitle = stringResource(
                        R.string.run_conflict_detail_subtitle,
                        conflict.modules.size,
                    ),
                    scrollBehavior = scrollBehavior,
                    onBack = { openedConflict = null },
                )

                showConflicts -> TopBar(
                    title = stringResource(R.string.run_conflict_title),
                    subtitle = stringResource(R.string.run_conflict_count, conflicts.size),
                    scrollBehavior = scrollBehavior,
                    onBack = { showConflicts = false },
                    onRefresh = { reloadToken++ },
                )

                current != null -> TopBar(
                    title = current.name,
                    subtitle = stringResource(
                        R.string.run_recorder_command_count,
                        detailCommands.size,
                    ),
                    scrollBehavior = scrollBehavior,
                    onBack = { opened = null },
                    onRefresh = { detailReloadToken++ },
                    extraActions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MeaningfulOnlyMenu(
                                meaningfulOnly = meaningfulOnly,
                                onToggle = { meaningfulOnly = it },
                            )
                            IconButton(
                                enabled = detailCommands.isNotEmpty(),
                                onClick = {
                                    copyToClipboard(context, detailCommands.joinToString("\n"))
                                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.run_recorder_copy_all),
                                )
                            }
                        }
                    },
                )

                else -> TopBar(
                    title = stringResource(R.string.run_recorder),
                    subtitle = stringResource(R.string.run_recorder_record_count, records.size),
                    scrollBehavior = scrollBehavior,
                    onBack = { navigator.popBackStack() },
                    onRefresh = { reloadToken++ },
                    extraActions = {
                        MeaningfulOnlyMenu(
                            meaningfulOnly = meaningfulOnly,
                            onToggle = { meaningfulOnly = it },
                        )
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            val conflict = openedConflict
            when {
                showReset -> RunResetView(
                    onBusyChange = { resetBusy = it },
                    onBack = {
                        if (!resetBusy) {
                            showReset = false
                            reloadToken++
                        }
                    },
                )

                conflict != null -> ConflictDetailView(conflict)

                showConflicts -> ConflictList(
                    loading = conflictLoading,
                    conflicts = conflicts,
                    onOpen = { openedConflict = it },
                )

                current != null -> CommandList(
                    loading = detailLoading,
                    commands = detailCommands,
                    meaningfulOnly = meaningfulOnly,
                    onOpen = { inspecting = it },
                )

                else -> RecordList(
                    loading = loading,
                    records = records,
                    conflictCount = conflicts.size,
                    onOpenConflicts = { showConflicts = true },
                    onOpenReset = { showReset = true },
                    onOpen = { group ->
                        // 先清空上一条的指令并立刻进入加载态，避免详情首帧闪出「未捕获到指令」
                        detailCommands = emptyList()
                        detailLoading = true
                        opened = group
                    },
                )
            }
        }
    }

    // 指令详情悬浮层：点某条指令后从顶部滑入
    val sheetCommand = inspecting
    if (sheetCommand != null) {
        CommandDetailSheet(
            raw = sheetCommand,
            onDismiss = { inspecting = null },
        )
    }
}

/** 列表视图：顶部「冲突分析」入口卡片 + 每个模块名一张卡片（圆角卡片 + 图标块 + 条数/时间 + 箭头）。 */
@Composable
private fun RecordList(
    loading: Boolean,
    records: List<RunRecorderStore.Group>,
    conflictCount: Int,
    onOpenConflicts: () -> Unit,
    onOpenReset: () -> Unit,
    onOpen: (RunRecorderStore.Group) -> Unit,
) {
    if (loading) {
        CenterProgress()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部留白：悬浮底栏是内容层的兄弟节点，滚到底时最后一项不被遮住
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 冲突分析入口：始终固定在列表最上面
        item(key = "__conflict") {
            ConflictEntryCard(count = conflictCount, onClick = onOpenConflicts)
        }
        // 重置入口：紧挨冲突分析，同样固定在最上面
        item(key = "__reset") {
            ResetEntryCard(onClick = onOpenReset)
        }
        if (records.isEmpty()) {
            item(key = "__empty") {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    text = stringResource(R.string.run_recorder_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            items(records, key = { it.name }) { group ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = { onOpen(group) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.run_recorder_item_desc,
                                    group.commandCount,
                                    formatTime(group.updatedAt),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 详情视图：某个模块被拦截抓取到的全部指令原文（顺序 = 落盘顺序 = 真实执行顺序）。
 *
 * - 「只显示有意义的指令」开启时，过滤掉脚本脚手架（sleep / true / cd / export…）与纯输出，
 *   只留真正会改系统的东西；读取类指令（cat / settings get…）保留，供后续查看文件内容。
 * - 不再显示 `sh×12 · cmd×3` 这类聚合统计行（用户要求去掉）。
 *
 * 每条指令一张卡片：序号徽标 + 等宽正文（最多两行）+ 分类标签 + 箭头。
 * 单击卡片打开 [CommandDetailSheet]；正文仍可长按选词复制（SelectionContainer 只吃长按）。
 */
@Composable
private fun CommandList(
    loading: Boolean,
    commands: List<String>,
    meaningfulOnly: Boolean,
    onOpen: (String) -> Unit,
) {
    val shown = remember(commands, meaningfulOnly) {
        if (meaningfulOnly) commands.filter { isMeaningful(it) } else commands
    }
    when {
        loading -> CenterProgress()

        shown.isEmpty() -> CenterHint(
            stringResource(
                if (commands.isEmpty()) {
                    R.string.run_recorder_detail_empty
                } else {
                    R.string.run_recorder_detail_filtered_empty
                }
            )
        )

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 10.dp,
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(shown, key = { index, _ -> "cmd_$index" }) { index, cmd ->
                    CommandCard(index = index, cmd = cmd, onOpen = onOpen)
                }
            }
        }
    }
}

/** 单条指令卡片。 */
@Composable
private fun CommandCard(index: Int, cmd: String, onOpen: (String) -> Unit) {
    // 分类标签：走 CommandInspector 的解析结果（列表只 compose 可见项，成本可控）
    val tag = remember(cmd) {
        runCatching { CommandInspector.parse(cmd).kind.label }.getOrDefault("")
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .clickable { onOpen(cmd) }
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = cmd,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                    )
                }
                if (tag.isNotBlank()) {
                    Text(
                        modifier = Modifier.padding(top = 3.dp),
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp),
            )
        }
    }
}

/** 「只显示有意义的指令」开关：右上角三点 → 下拉菜单里一个复选项。 */
@Composable
private fun MeaningfulOnlyMenu(
    meaningfulOnly: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.run_recorder_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.run_meaningful_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.run_meaningful_only_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    Checkbox(checked = meaningfulOnly, onCheckedChange = null)
                },
                onClick = {
                    onToggle(!meaningfulOnly)
                    expanded = false
                },
            )
        }
    }
}

/** 冲突分析入口卡片（固定在运行历史顶部）。 */
@Composable
private fun ConflictEntryCard(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.run_conflict_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (count > 0) {
                        stringResource(R.string.run_conflict_found, count)
                    } else {
                        stringResource(R.string.run_conflict_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 重置入口卡片（固定在运行历史顶部，紧挨冲突分析）。 */
@Composable
private fun ResetEntryCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.run_reset),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.run_reset_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 冲突列表：每处冲突一张卡片，只显示键名 / 文件名（**不显示值**）。 */
@Composable
private fun ConflictList(
    loading: Boolean,
    conflicts: List<RunConflictAnalyzer.Conflict>,
    onOpen: (RunConflictAnalyzer.Conflict) -> Unit,
) {
    if (loading) {
        CenterProgress()
        return
    }
    if (conflicts.isEmpty()) {
        CenterHint(stringResource(R.string.run_conflict_none))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(conflicts, key = { "${it.type.name}|${it.key}" }) { conflict ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                onClick = { onOpen(conflict) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = conflict.display,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                if (conflict.type == RunConflictAnalyzer.Type.SETTINGS) {
                                    R.string.run_conflict_type_settings
                                } else {
                                    R.string.run_conflict_type_file
                                },
                                conflict.modules.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 「有意义」判定：只过滤**确定没有系统改动**的东西。
 *
 * - [CommandInspector.Kind.SCAFFOLD]：cd / export / true / : / if… 纯脚本结构；
 * - [CommandInspector.Kind.READ_ONLY] 里的纯等待与纯输出：sleep / usleep / echo / printf / sync / date…
 *
 * 读取类（cat / settings get / pm list…）**保留**：用户要能点进去看它读到的文件内容。
 * 解析不了的指令也保留，宁可多显示也不误删。
 */
private val MEANINGLESS_OPS = setOf(
    "sleep", "usleep", "sync", "true", "false", ":", "echo", "printf", "wait", "date",
)

private fun isMeaningful(cmd: String): Boolean {
    val text = cmd.trim()
    if (text.isEmpty()) return false
    val parsed = runCatching { CommandInspector.parse(text) }.getOrNull() ?: return true
    if (parsed.kind == CommandInspector.Kind.SCAFFOLD) return false
    if (parsed.kind == CommandInspector.Kind.READ_ONLY && parsed.op.lowercase() in MEANINGLESS_OPS) {
        return false
    }
    return true
}

/** 居中加载指示。 */
@Composable
private fun CenterProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 居中提示文案。 */
@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 页面顶栏：标题 + 条数副标题 + 返回 / 刷新（详情视图额外带复制）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    backEnabled: Boolean = true,
    onBack: () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    extraActions: @Composable (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            if (onRefresh != null) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.run_recorder_refresh),
                    )
                }
            }
            extraActions?.invoke()
        },
        scrollBehavior = scrollBehavior,
    )
}

/** 落盘时间格式化：月-日 时:分（与日志同机时区一致）。 */
private fun formatTime(ts: Long): String {
    if (ts <= 0L) return "-"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }.getOrDefault("-")
}

/** 复制到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AxManagerRunRecorder", text))
    }
}