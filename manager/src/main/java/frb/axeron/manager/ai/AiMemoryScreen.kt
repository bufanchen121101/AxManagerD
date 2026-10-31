package frb.axeron.manager.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ai.dev.DevChatStore
import frb.axeron.manager.ai.dev.DevMemoryStore
import frb.axeron.manager.ai.dev.ModuleDevStore
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.util.LocalSnackbarHost
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AI 记忆（1.4.4）。
 *
 * 页面分两层：
 *  1. 记忆文件列表：一个模块一份（与 [DevMemoryStore] 的落盘口径一致），
 *     显示条数、对话数、最近一条内容；
 *  2. 记忆网：点进去就是某一模块的记忆关系图 —— 节点=一条记忆，
 *     连线=两条记忆在关键词上相关（重合越多线越粗），点节点可看/改/删。
 *
 * 为什么不做「全部模块汇总成一张网」：记忆是**按模块隔离**的，
 * A 模块「必须用 POSIX sh」这类约定放到 B 模块就是错的，
 * 混在一起既会让 AI 乱套，也会让用户看不清哪条属于哪个模块。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AiMemoryScreen(navigator: DestinationsNavigator) {
    val snackbar = LocalSnackbarHost.current
    val bottomInset = LocalBottomBarInset.current
    var snapshots by remember { mutableStateOf(emptyList<ModuleSnapshot>()) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var openedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reloadKey) {
        snapshots = withContext(Dispatchers.IO) {
            ModuleDevStore.listProjects().map { p ->
                ModuleSnapshot(
                    project = p,
                    memories = DevMemoryStore.load(p.id).sortedByDescending { it.updatedAt },
                    chats = DevChatStore.loadSessions(p.id).size,
                )
            }
        }
    }
    val current = snapshots.firstOrNull { it.project.id == openedId }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (current == null) "AI 记忆" else "记忆网",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (current == null) {
                                "每个模块一份，互不相通 · 共 " +
                                    snapshots.sumOf { it.memories.size } + " 条"
                            } else {
                                "${current.project.name} · ${current.memories.size} 条记忆"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (current == null) navigator.navigateUp() else openedId = null
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = bottomInset)
        ) {
            if (current == null) {
                SnapshotList(
                    snapshots = snapshots,
                    onOpen = { openedId = it },
                )
            } else {
                MemoryNetPage(
                    project = current.project,
                    memories = current.memories,
                    onChanged = { reloadKey++ },
                    onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                )
            }
        }
    }
}

/** 一个模块的记忆快照（列表页用）。 */
private data class ModuleSnapshot(
    val project: ModuleDevStore.Project,
    val memories: List<DevMemoryStore.Memory>,
    val chats: Int,
)

/** 第一层：记忆文件列表（每个模块一行）。 */
@Composable
private fun SnapshotList(
    snapshots: List<ModuleSnapshot>,
    onOpen: (String) -> Unit,
) {
    if (snapshots.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有模块。\n先在「模块开发」里建一个模块，AI 辅助里聊出来的记忆会按模块存在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(snapshots, key = { it.project.id }) { snap ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(snap.project.id) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (snap.memories.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = snap.project.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${snap.project.id} · v${snap.project.version} · " +
                                "${snap.memories.size} 条记忆 · ${snap.chats} 个对话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val preview = snap.memories.firstOrNull()?.text
                        Text(
                            text = preview ?: "还没有记忆，进去点「让 AI 梳理最近对话」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 第二层：某个模块的记忆网 + 明细。 */
@Composable
private fun MemoryNetPage(
    project: ModuleDevStore.Project,
    memories: List<DevMemoryStore.Memory>,
    onChanged: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val graph = remember(memories) { buildGraph(memories) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DevMemoryStore.Memory?>(null) }
    val selected = memories.firstOrNull { it.id == selectedId }

    fun refineWithAi() {
        if (busy) return
        busy = true
        scope.launch {
            val recent = withContext(Dispatchers.IO) {
                DevChatStore.loadSessions(project.id).firstOrNull()?.messages.orEmpty()
            }
            if (recent.isEmpty()) {
                busy = false
                onMessage("这个模块还没有对话记录，先去 AI 辅助里聊几句")
                return@launch
            }
            val dialogText = recent
                .filter { it.content.isNotBlank() }
                .takeLast(20)
                .joinToString("\n") {
                    (if (it.role == "USER") "用户：" else "AI：") + it.content.take(1200)
                }
            val prompt = buildString {
                appendLine("下面是模块开发对话节选。请整理成「长期有用的记忆条目」。")
                appendLine("要求：每条一行，以「- 」开头；只保留以后做这个模块会用到的事实")
                appendLine("（约定、路径、命令、坑、用户偏好），不要复述寒暄；最多 12 条。")
                appendLine()
                if (memories.isNotEmpty()) {
                    appendLine("【已有记忆】")
                    memories.forEach { appendLine("- " + it.text) }
                    appendLine()
                }
                appendLine("【对话节选】")
                append(dialogText)
            }
            val reply = AIChatService.chatStream(
                system = "你是模块开发的记忆整理助手，只输出条目，不要解释。",
                prompt = prompt,
                history = emptyList(),
                onDelta = {},
                purpose = AIConfigStore.AiPurpose.MODULE_DEV,
            )
            busy = false
            if (reply.isNullOrBlank()) {
                onMessage("梳理失败：${AIChatService.lastError ?: "没有回复"}")
            } else {
                val parsed = reply.lineSequence()
                    .map { it.trim() }
                    .map { it.removePrefix("-").removePrefix("*").trim() }
                    .filter { it.length in 2..300 }
                    .distinct()
                    .take(12)
                    .toList()
                if (parsed.isEmpty()) {
                    onMessage("没整理出可用条目")
                } else {
                    withContext(Dispatchers.IO) { DevMemoryStore.replaceAll(project.id, parsed) }
                    onChanged()
                    onMessage("已整理为 ${parsed.size} 条记忆")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(4.dp),
            ) {
                val areaW = maxWidth
                val areaH = maxHeight
                val linkColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    graph.edges.forEach { e ->
                        val a = graph.nodes[e.from].pos
                        val b = graph.nodes[e.to].pos
                        drawLine(
                            color = linkColor,
                            start = Offset(a.x * size.width, a.y * size.height),
                            end = Offset(b.x * size.width, b.y * size.height),
                            strokeWidth = 1f + e.weight * 5f,
                        )
                    }
                }
                if (graph.nodes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "还没有记忆。\n点下面的「让 AI 梳理最近对话」，或手写一条。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                graph.nodes.forEach { node ->
                    val isSel = node.id == selectedId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSel) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = if (isSel) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            )
                        },
                        modifier = Modifier
                            .offset(
                                x = (areaW - NODE_W) * node.pos.x,
                                y = (areaH - NODE_H) * node.pos.y,
                            )
                            .width(NODE_W)
                            .height(NODE_H)
                            .clickable {
                                selectedId = node.id
                                editing = false
                                editText = memories.firstOrNull { it.id == node.id }?.text.orEmpty()
                            },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = node.text.take(16),
                                fontSize = 9.sp,
                                lineHeight = 11.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = "连线＝两条记忆的关键词重合，越粗越相关；点节点看内容、可改可删。" +
                    "共 ${memories.size} 条 · 连线 ${graph.edges.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy,
                    onClick = { refineWithAi() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "正在梳理…" else "让 AI 梳理最近对话")
                }
            }
        }
        item {
            Column {
                OutlinedTextField(
                    value = newText,
                    onValueChange = { newText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("手写一条，例如：本模块脚本必须是 POSIX sh") },
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        enabled = newText.isNotBlank(),
                        onClick = {
                            val t = newText
                            newText = ""
                            scope.launch(Dispatchers.IO) { DevMemoryStore.add(project.id, t) }
                            onChanged()
                            onMessage("已添加")
                        },
                    ) { Text("添加记忆") }
                }
            }
        }
        item {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
        items(memories, key = { it.id }) { m ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedId = m.id
                    editing = false
                    editText = m.text
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = m.text, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "关联 ${graph.degrees[m.id] ?: 0} 条 · " + timeText(m.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingDelete = m }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (selected != null) {
        AlertDialog(
            onDismissRequest = { if (!editing) selectedId = null },
            title = { Text(if (editing) "修改这条记忆" else "这条记忆") },
            text = {
                Column {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6,
                        )
                    } else {
                        Text(text = selected.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "关联 ${graph.degrees[selected.id] ?: 0} 条 · " +
                                timeText(selected.updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (editing) {
                    TextButton(onClick = {
                        val t = editText
                        scope.launch(Dispatchers.IO) { DevMemoryStore.update(project.id, selected.id, t) }
                        editing = false
                        onChanged()
                        onMessage("已保存")
                    }) { Text("保存") }
                } else {
                    TextButton(onClick = {
                        editText = selected.text
                        editing = true
                    }) { Text("改") }
                }
            },
            dismissButton = {
                if (editing) {
                    TextButton(onClick = { editing = false }) { Text("取消") }
                } else {
                    TextButton(onClick = { pendingDelete = selected }) {
                        Text("删", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆？") },
            text = { Text(target.text) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selectedId = null
                    scope.launch(Dispatchers.IO) { DevMemoryStore.delete(project.id, target.id) }
                    onChanged()
                    onMessage("已删除")
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 节点尺寸：图里的每个记忆小卡片。 */
private val NODE_W = 82.dp
private val NODE_H = 34.dp

/** 图节点：id + 文本 + 归一化坐标（0..1）。 */
private data class MemoryNode(
    val id: String,
    val text: String,
    val pos: Offset,
)

/** 图的连线（from/to 是 nodes 下标）。 */
private data class MemoryEdge(val from: Int, val to: Int, val weight: Float)

/** 一张记忆网。 */
private data class MemoryGraph(
    val nodes: List<MemoryNode>,
    val edges: List<MemoryEdge>,
    val degrees: Map<String, Int>,
)

/**
 * 把一组记忆排成「网」：
 *  - 关键词切分：中文按 2-gram、英文/数字/路径按词；
 *  - 相关性用 Jaccard 相似度，超过阈值就连一条线；
 *  - 位置用几次「排斥 + 连线弹簧」迭代算出来，确定性布局（不随机、不抖动）。
 */
private fun buildGraph(list: List<DevMemoryStore.Memory>): MemoryGraph {
    val n = list.size
    if (n == 0) return MemoryGraph(emptyList(), emptyList(), emptyMap())
    val tokens = list.map { keywordSet(it.text) }
    val raw = ArrayList<Triple<Int, Int, Float>>()
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val w = similarity(tokens[i], tokens[j])
            if (w >= 0.10f) raw.add(Triple(i, j, w))
        }
    }
    val picked = raw.sortedByDescending { it.third }.take(90)
    val px = FloatArray(n)
    val py = FloatArray(n)
    for (i in 0 until n) {
        val ang = (2.0 * Math.PI * i / n).toFloat()
        px[i] = 0.5f + 0.36f * cos(ang)
        py[i] = 0.5f + 0.36f * sin(ang)
    }
    repeat(120) {
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = px[j] - px[i]
                var dy = py[j] - py[i]
                var d2 = dx * dx + dy * dy
                if (d2 < 1e-5f) {
                    dx = 0.001f
                    dy = 0.001f
                    d2 = 1e-6f
                }
                val d = sqrt(d2)
                val f = 0.0022f / d2
                px[i] -= dx / d * f
                py[i] -= dy / d * f
                px[j] += dx / d * f
                py[j] += dy / d * f
            }
        }
        picked.forEach { e ->
            val dx = px[e.second] - px[e.first]
            val dy = py[e.second] - py[e.first]
            val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
            val f = (d - 0.30f) * 0.05f
            px[e.first] += dx / d * f
            py[e.first] += dy / d * f
            px[e.second] -= dx / d * f
            py[e.second] -= dy / d * f
        }
        for (i in 0 until n) {
            px[i] = px[i].coerceIn(0.04f, 0.96f)
            py[i] = py[i].coerceIn(0.06f, 0.94f)
        }
    }
    val nodes = list.mapIndexed { i, m ->
        MemoryNode(id = m.id, text = m.text, pos = Offset(px[i], py[i]))
    }
    val degrees = HashMap<String, Int>()
    picked.forEach { e ->
        val a = list[e.first].id
        val b = list[e.second].id
        degrees[a] = (degrees[a] ?: 0) + 1
        degrees[b] = (degrees[b] ?: 0) + 1
    }
    return MemoryGraph(
        nodes = nodes,
        edges = picked.map { MemoryEdge(it.first, it.second, it.third) },
        degrees = degrees,
    )
}

/** 关键词集合：中文 2-gram + 英文/数字/路径词（够用且不需要分词库）。 */
private fun keywordSet(text: String): Set<String> {
    val out = HashSet<String>()
    val lower = text.lowercase()
    Regex("[a-z0-9_./-]{2,}").findAll(lower).forEach { out.add(it.value) }
    val cjk = StringBuilder()
    lower.forEach { c ->
        if (c in '\u4e00'..'\u9fff') {
            cjk.append(c)
        } else if (cjk.isNotEmpty()) {
            addBigrams(cjk.toString(), out)
            cjk.setLength(0)
        }
    }
    if (cjk.isNotEmpty()) addBigrams(cjk.toString(), out)
    return out
}

private fun addBigrams(s: String, out: MutableSet<String>) {
    if (s.length == 1) {
        out.add(s)
        return
    }
    for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
}

/** Jaccard 相似度。 */
private fun similarity(a: Set<String>, b: Set<String>): Float {
    if (a.isEmpty() || b.isEmpty()) return 0f
    var inter = 0
    a.forEach { if (it in b) inter++ }
    val union = a.size + b.size - inter
    return if (union <= 0) 0f else inter.toFloat() / union
}

private fun timeText(at: Long): String {
    if (at <= 0L) return "未知时间"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }.getOrDefault("")
}
