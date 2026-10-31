package frb.axeron.manager.ai.dev

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ai.AIChatService
import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.AiTrace
import frb.axeron.manager.ai.axkey.AxKeyProvider
import frb.axeron.manager.ui.component.ElevatedCard
import frb.axeron.manager.ui.component.LocalBottomBarInset
import frb.axeron.manager.ui.util.LocalSnackbarHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewAssetLoader
import coil.compose.AsyncImage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.roundToInt

/**
 * 模块开发界面（v1.4.0 新增）。
 *
 * 入口：模块页顶部「模块 + 扳手」图标（Plugin.kt 的 action 区）。
 *
 * 两个部分：
 *  1. 模块开发：工程列表 / 文件树 / 全屏编辑器 / 新建文件与目录 / 导出可安装 zip；
 *  2. AI 辅助：把工程上下文交给云端 AI，AI 用 `<<<FILE:path>>>…<<<END>>>` 协议
 *     直接产出文件，App 解析后写入工程（写入数量会显示在气泡下方）。
 *
 * 存储与导出细节见 [ModuleDevStore]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ModuleDevScreen(navigator: DestinationsNavigator) {
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf(ModuleDevStore.listProjects()) }
    // 【1.5.0】进入页面固定落在「模块开发」主页（不再自动打开上次的工程）。
    // 上次的工程仍记在 ModuleDevStore.activeProject() 里给对话页取聊天记录，只是不再自动进入。
    var opened by remember { mutableStateOf<ModuleDevStore.Project?>(null) }
    var editingPath by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    // 【1.5.0】导入模块：选一个 zip → 解压成工程目录；按 module.prop 自动判定
    // 「运行时模块 / shell 模块」，导入后停在主页，不自动进入工程。
    var importingModule by remember { mutableStateOf(false) }
    val importContext = LocalContext.current
    val moduleZipImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || importingModule) return@rememberLauncherForActivityResult
        importingModule = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = readImportBytesLimited(importContext, uri)
                    if (bytes == null) {
                        Result.failure<ModuleDevStore.ImportResult>(
                            IllegalArgumentException("读取失败，或文件超过 16 MiB")
                        )
                    } else {
                        ModuleDevStore.importModuleZip(devDisplayName(importContext, uri), bytes)
                    }
                }
                if (result.isSuccess) {
                    val r = result.getOrThrow()
                    projects = ModuleDevStore.listProjects()
                    val note = if (r.note.isBlank()) "" else "；${r.note}"
                    snackbar.showSnackbar(
                        "已导入「${r.project.name}」（${r.kind}，${r.fileCount} 个文件）$note"
                    )
                } else {
                    snackbar.showSnackbar(
                        "导入失败：${result.exceptionOrNull()?.message ?: "未知原因"}"
                    )
                }
            } finally {
                importingModule = false
            }
        }
    }
    fun showImportChooser() {
        if (importingModule) return
        runCatching {
            moduleZipImporter.launch(
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "*/*",
                )
            )
        }.onFailure { e ->
            scope.launch { snackbar.showSnackbar("无法打开文件选择器：${e.message ?: "未知原因"}") }
        }
    }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 底栏是浮在内容之上的覆盖层：FAB 落在 Scaffold 里不会自动让位，
    // 不补这段 padding 就会被底栏压住（表现为「点了没反应/被挡住」）。
    val bottomInset = LocalBottomBarInset.current

    fun refresh() {
        projects = ModuleDevStore.listProjects()
        opened = opened?.let { ModuleDevStore.loadProject(it.dir) }
    }
    // 【1.4.4】导出：先建 /storage/emulated/0/AxManagerD，再把这个模块整个打成 zip 放进去。
    // 没有「所有文件访问权限」时系统不允许写手机存储根目录，会退到「下载/AxManagerD」，
    // 并弹一次对话框引导授权（授权后再点导出就落到用户要的目录）。
    var exporting by remember { mutableStateOf(false) }
    var exportHint by remember { mutableStateOf<ModuleDevStore.ExportResult?>(null) }
    // 【1.4.5】导出结果用对话框明确告知：Snackbar 一闪而过、又容易被底栏挡住，
    // 用户实测「点了导出没有任何提示」。成功 / 失败都改成必须确认的弹窗。
    var exportDone by remember { mutableStateOf<ModuleDevStore.ExportResult?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    fun showExportResult(result: Result<ModuleDevStore.ExportResult>) {
        result.onSuccess { r ->
            if (r.needsAllFiles) {
                exportHint = r
            } else {
                exportDone = r
            }
        }.onFailure {
            exportError = it.message ?: "未知原因"
        }
    }
    fun doExport(p: ModuleDevStore.Project) {
        if (exporting) return
        exporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { ModuleDevStore.exportZip(p) }
            exporting = false
            showExportResult(result)
        }
    }

    // ---------- 编辑器全屏态 ----------
    val editRel = editingPath
    val project = opened
    if (project != null && editRel != null) {
        ModuleFileEditor(
            project = project,
            relPath = editRel,
            onBack = {
                editingPath = null
                refresh()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project?.name ?: "模块开发",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (project != null) {
                                "${project.id} · v${project.version} · ${project.fileCount} 个文件"
                            } else {
                                "把想法做成可安装的模块"
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
                            if (project != null) {
                                opened = null
                                tab = 0
                            } else {
                                navigator.navigateUp()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (project == null) {
                        // 【1.5.0】主页右上角「导入模块」：选一个 zip 直接解压成工程
                        IconButton(
                            onClick = { showImportChooser() },
                            enabled = !importingModule,
                        ) {
                            Icon(Icons.Filled.Unarchive, contentDescription = "导入模块")
                        }
                    } else {
                        // 导出：整个模块打成 zip → /storage/emulated/0/AxManagerD/
                        IconButton(onClick = { doExport(project) }) {
                            Icon(Icons.Filled.UploadFile, contentDescription = "导出模块")
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("刷新文件树") },
                                    onClick = {
                                        menu = false
                                        refresh()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除该工程", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menu = false
                                        pendingDelete = ""
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0 && project == null) {
                FloatingActionButton(
                    onClick = { showCreate = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = bottomInset + 12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建工程")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---- 顶部渐变装饰 + Tab ----
            val gradient = Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.0f),
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(gradient)
            ) {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Color.Transparent,
                    divider = {},
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Extension,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("模块开发", fontWeight = FontWeight.SemiBold)
                            }
                        },
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("AI 辅助", fontWeight = FontWeight.SemiBold)
                            }
                        },
                    )
                }
            }

            // 【v1.4.1 修复 · 「AI 辅助里点发送没有任何反应」】
            // 外层是 Column，这两个分支原来直接用 fillMaxSize()。Column 测量「无 weight」
            // 子项时不会扣掉上面 TabRow 已经占用的高度，于是子项拿到的是整屏高度，
            // 整块内容向下溢出约一个 TabRow 的高度 —— 输入栏被挤出父布局底边，
            // 落在全局悬浮底栏的覆盖层区域内，触摸事件先被底栏吃掉，表现就是
            // 「点了发送完全没反应」（云端对话页之所以正常，是因为它在自己的 Scaffold
            // 里、列表用 weight(1f) 只占剩余空间）。
            // 修法：用 weight(1f) 限定为剩余空间，与该页原来的写法保持一致。
            if (tab == 0) {
                Box(modifier = Modifier.weight(1f)) {
                    if (project == null) {
                        ProjectList(
                            projects = projects,
                            onOpen = {
                                opened = it
                                ModuleDevStore.setActiveProject(it.id, it.name)
                            },
                            onCreate = { showCreate = true },
                            onImport = { showImportChooser() },
                            importing = importingModule,
                        )
                    } else {
                        ProjectFiles(
                            project = project,
                            onOpenFile = { editingPath = it },
                            onRefresh = { refresh() },
                            onMessage = { msg ->
                                scope.launch { snackbar.showSnackbar(msg) }
                            },
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    AiAssistTab(
                        project = project,
                        projects = projects,
                        onOpenProject = { p ->
                            opened = p
                            ModuleDevStore.setActiveProject(p.id, p.name)
                        },
                        onProjectChanged = { refresh() },
                    )
                }
            }
        }
    }

    // ---- 导出成功 + 可选的根目录授权引导 ----
    val hint = exportHint
    if (hint != null) {
        val ctx = LocalContext.current
        AlertDialog(
            onDismissRequest = { exportHint = null },
            title = { Text("导出成功") },
            text = {
                Column {
                    Text(hint.fileName, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("保存位置：${hint.location}")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "如需下次直接写进 ${hint.dirPath}，可授予「所有文件访问权限」" +
                            "（Android 11 起普通应用不能写手机存储根目录）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportHint = null
                    openAllFilesAccessSettings(ctx)
                }) { Text("去授权") }
            },
            dismissButton = {
                TextButton(onClick = { exportHint = null }) { Text("先这样") }
            },
        )
    }
    // ---- 导出结果（成功 / 失败都明确提示） ----
    val done = exportDone
    if (done != null) {
        AlertDialog(
            onDismissRequest = { exportDone = null },
            title = { Text("导出成功") },
            text = {
                Column {
                    Text(done.fileName, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "保存位置：${done.location}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "回到「模块」页点 +，选这个 zip 就能安装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { exportDone = null }) { Text("知道了") }
            },
        )
    }
    val err = exportError
    if (err != null) {
        AlertDialog(
            onDismissRequest = { exportError = null },
            title = { Text("导出失败") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { exportError = null }) { Text("知道了") }
            },
        )
    }
    // ---- 新建工程对话框 ----
    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, id, version, author, desc, template ->
                if (name.isBlank() && id.isBlank()) {
                    // 名称和 ID 都空时不创建（否则会得到一个无意义的默认工程）。
                    showCreate = false
                    scope.launch { snackbar.showSnackbar("请先填模块名称或 ID") }
                } else {
                    val r = ModuleDevStore.createProject(name, id, version, author, desc, template)
                    showCreate = false
                    r.onSuccess { p ->
                        refresh()
                        opened = p
                        ModuleDevStore.setActiveProject(p.id, p.name)
                        // 同名/同类型工程会被自动编号（xxx-2），这里显示真实生成的 ID。
                        scope.launch { snackbar.showSnackbar("工程已创建：${p.id}") }
                    }.onFailure {
                        scope.launch {
                            snackbar.showSnackbar("创建失败：${it.message ?: "未知原因"}")
                        }
                    }
                }
            },
        )
    }

    // ---- 删除确认 ----
    if (pendingDelete != null && project != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除工程？") },
            text = { Text("将删除「${project.name}」及其全部文件，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletedId = project.id
                        val ok = ModuleDevStore.deleteProject(project)
                        pendingDelete = null
                        if (ok) {
                            // 删掉的正好是「正在开发的模块」→ 解除绑定，避免对话页挂到空模块
                            if (ModuleDevStore.activeProjectId() == deletedId) {
                                ModuleDevStore.setActiveProject(null)
                            }
                            opened = null
                            refresh()
                            scope.launch { snackbar.showSnackbar("已删除") }
                        } else {
                            scope.launch { snackbar.showSnackbar("删除失败") }
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ----------------------------------------------------------------------
// 工程列表
// ----------------------------------------------------------------------

@Composable
private fun ProjectList(
    projects: List<ModuleDevStore.Project>,
    onOpen: (ModuleDevStore.Project) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    importing: Boolean = false,
) {
    if (projects.isEmpty()) {
        EmptyProjects(onCreate = onCreate, onImport = onImport, importing = importing)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的工程（${projects.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 【1.5.0】创建项目旁边直接给「导入模块」入口（zip → 解压成工程）
                TextButton(onClick = onImport, enabled = !importing) {
                    Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (importing) "导入中…" else "导入模块")
                }
            }
        }
        items(projects, key = { it.absolutePath }) { p ->
            ProjectCard(project = p, onClick = { onOpen(p) })
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun EmptyProjects(onCreate: () -> Unit, onImport: () -> Unit, importing: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 渐变圆形图标
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f),
                        )
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "还没有模块工程",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "点右下角 + 新建，或用「AI 辅助」让它帮你写",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.clickable { onCreate() },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建工程", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        // 【1.5.0】空态也提供导入入口：从别处拿到的模块 zip 直接变成工程
        TextButton(onClick = onImport, enabled = !importing) {
            Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (importing) "导入中…" else "导入模块（zip）")
        }
    }
}

@Composable
private fun ProjectCard(project: ModuleDevStore.Project, onClick: () -> Unit) {
    val accent = accentColorFor(project.id)
    // 【1.5.0】改用工程统一的 ElevatedCard 包装：自定义背景开启时会把阴影压成 0 并加玻璃描边，
    // 修掉「卡片内侧一圈黑边」（半透明容器下 Material 阴影透出来）。
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${project.id} · v${project.version}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${project.fileCount} 个文件 · ${formatTime(project.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 文件树
// ----------------------------------------------------------------------

@Composable
private fun ProjectFiles(
    project: ModuleDevStore.Project,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var nodes by remember(project) { mutableStateOf(ModuleDevStore.listNodes(project)) }
    // 目录内浏览：curDir 为空 = 工程根目录；进入子目录后只显示该目录的直接子项。
    var curDir by remember(project) { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newIsDir by remember { mutableStateOf(false) }
    // 长按文件/目录 → 操作对话框（运行/预览 + 删除）。
    var actionTarget by remember { mutableStateOf<ModuleDevStore.Node?>(null) }
    var runTarget by remember { mutableStateOf<ModuleDevStore.Node?>(null) }
    var htmlPreviewTarget by remember(project) { mutableStateOf<ModuleDevStore.Node?>(null) }
    // 文件页右上角「⋮」菜单：新建文件/新建目录/上传图片/导入文件都收在这里（功能不变，页面更干净）
    var showFileMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageImporting by remember(project) { mutableStateOf(false) }

    fun reload() {
        nodes = ModuleDevStore.listNodes(project)
        onRefresh()
    }

    fun parentOf(rel: String): String = rel.substringBeforeLast('/', "")

    val moduleImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null || imageImporting) return@rememberLauncherForActivityResult
        imageImporting = true
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    readImageBytesLimited(context, uri)?.let { bytes ->
                        ModuleDevStore.importImage(
                            project = project,
                            sourceName = devDisplayName(context, uri),
                            bytes = bytes,
                        )
                    }
                }
                if (imported == null) {
                    onMessage("导入失败：请选择 8 MiB 以内的 PNG/JPG/GIF/BMP/WebP 图片")
                } else {
                    reload()
                    onMessage("已保存 $imported；请在 module.prop 添加：icon=$imported")
                }
            } finally {
                imageImporting = false
            }
        }
    }

    // 导入任意非压缩包文件（.sh/.prop/.json/.html/图片…）到工程根目录。
    var fileImporting by remember(project) { mutableStateOf(false) }
    val moduleFileImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null || fileImporting) return@rememberLauncherForActivityResult
        fileImporting = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = readImportBytesLimited(context, uri)
                    if (bytes == null) {
                        Result.failure<String>(
                            IllegalArgumentException("读取失败，或文件超过 16 MiB")
                        )
                    } else {
                        ModuleDevStore.importFile(project, devDisplayName(context, uri), bytes)
                    }
                }
                result.onSuccess { rel ->
                    reload()
                    onMessage("已导入 $rel")
                }.onFailure { e ->
                    onMessage("导入失败：${e.message ?: "未知原因"}")
                }
            } finally {
                fileImporting = false
            }
        }
    }

    // 只显示当前目录的直接子项（把整棵树摊平的旧行为已去掉）。
    val children = nodes.filter { parentOf(it.relPath) == curDir }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 顶部：目录面包屑 + 「⋮」文件操作菜单（新建/上传/导入全在这里，功能不减） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (curDir.isNotEmpty()) {
                    IconButton(
                        onClick = { curDir = parentOf(curDir) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "上一级",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(horizontal = 9.dp)
                            .size(18.dp),
                        tint = accentColorFor(project.id),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (curDir.isEmpty()) "根目录" else curDir,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${children.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Box {
                    IconButton(onClick = { showFileMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "文件操作")
                    }
                    DropdownMenu(
                        expanded = showFileMenu,
                        onDismissRequest = { showFileMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建文件") },
                            leadingIcon = { Icon(Icons.Filled.Description, null, Modifier.size(18.dp)) },
                            onClick = {
                                showFileMenu = false
                                newIsDir = false
                                showNew = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("新建目录") },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)) },
                            onClick = {
                                showFileMenu = false
                                newIsDir = true
                                showNew = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (imageImporting) "上传图片（导入中…）" else "上传图片") },
                            leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) },
                            onClick = {
                                showFileMenu = false
                                if (!imageImporting) {
                                    moduleImagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (fileImporting) "导入文件（导入中…）" else "导入文件") },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp)) },
                            onClick = {
                                showFileMenu = false
                                if (!fileImporting) {
                                    runCatching { moduleFileImporter.launch(arrayOf("*/*")) }
                                        .onFailure { onMessage("无法打开文件选择器：${it.message ?: "未知原因"}") }
                                }
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            if (children.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (curDir.isEmpty()) "工程是空的" else "这个目录还没有文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "点右下角 + 新建文件（名字要带后缀）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(children, key = { it.relPath }) { node ->
                        FileRow(
                            node = node,
                            expanded = false,
                            accent = accentColorFor(project.id),
                            onClick = {
                                if (node.isDir) {
                                    curDir = node.relPath
                                } else {
                                    onOpenFile(node.relPath)
                                }
                            },
                            onLongClick = { actionTarget = node },
                        )
                    }
                    // 留出底部悬浮栏 + 悬浮加号的空间，最后一项不被遮住。
                    item { Spacer(Modifier.height(120.dp)) }
                }
            }
        }
        // 右下角悬浮「+」：只做新建文件（名字带后缀），位置抬高避免和悬浮底栏重合。
        FloatingActionButton(
            onClick = {
                newIsDir = false
                showNew = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 104.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建文件")
        }
    }
    // 长按后的操作：运行/预览（仅文件）+ 删除
    actionTarget?.let { node ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                Text(
                    text = node.file.name.ifBlank { node.relPath },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = node.relPath,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (node.isDir) {
                            "删除后该目录及内部全部文件一起消失，不可恢复。"
                        } else {
                            "删除后不可恢复。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!node.isDir) {
                        TextButton(onClick = {
                            actionTarget = null
                            if (devIsHtml(node.relPath)) {
                                htmlPreviewTarget = node
                            } else {
                                runTarget = node
                            }
                        }) { Text("运行 / 预览") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actionTarget = null
                    val ok = ModuleDevStore.deletePath(project, node.relPath)
                    if (ok) {
                        if (curDir == node.relPath || curDir.startsWith(node.relPath + "/")) {
                            curDir = parentOf(node.relPath)
                        }
                        reload()
                        onMessage("已删除：${node.relPath}")
                    } else {
                        onMessage("删除失败：${node.relPath}")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) { Text("取消") }
            },
        )
    }
    runTarget?.let { node ->
        DevRunDialog(
            node = node,
            project = project,
            onDismiss = { runTarget = null },
        )
    }
    htmlPreviewTarget?.let { node ->
        DevHtmlPreviewDialog(
            node = node,
            project = project,
            onDismiss = { htmlPreviewTarget = null },
        )
    }
    if (showNew) {
        NewPathDialog(
            isDir = newIsDir,
            initial = if (curDir.isEmpty()) (if (newIsDir) "overlay/" else "") else "$curDir/",
            onDismiss = { showNew = false },
            onConfirm = { input ->
                showNew = false
                val raw = input.trim().trimStart('/').trimEnd('/')
                if (raw.isEmpty()) {
                    onMessage("名字不能为空")
                } else {
                    val full = if (curDir.isEmpty()) raw else "$curDir/$raw"
                    val ok = if (newIsDir) {
                        ModuleDevStore.createDir(project, full) != null
                    } else {
                        ModuleDevStore.createFile(project, full) != null
                    }
                    if (ok) {
                        reload()
                        onMessage(if (newIsDir) "已创建目录：$full" else "已创建文件：$full")
                    } else {
                        onMessage("创建失败：路径非法或已存在")
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: ModuleDevStore.Node,
    expanded: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 14).dp)
            .background(
                if (node.isDir) MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
                else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                node.isDir && expanded -> Icons.Filled.FolderOpen
                node.isDir -> Icons.Filled.Folder
                isScript(node.relPath) -> Icons.Filled.Terminal
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (node.isDir) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = node.file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (!node.isDir && isScript(node.relPath)) FontFamily.Monospace else null,
            fontWeight = if (node.isDir) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!node.isDir) {
            Text(
                text = formatSize(node.file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 全屏编辑器
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleFileEditor(
    project: ModuleDevStore.Project,
    relPath: String,
    onBack: () -> Unit,
) {
    val file = remember(project.absolutePath, relPath) { ModuleDevStore.resolve(project, relPath) }
    val original = remember(project.absolutePath, relPath) {
        file?.let { ModuleDevStore.readText(it) }.orEmpty()
    }
    var text by remember(project.absolutePath, relPath) { mutableStateOf(original) }
    var saved by remember { mutableStateOf(true) }
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    // 返回默认保存（文档要求：点返回默认保存）
    fun saveAndBack() {
        val f = file
        if (f != null && !f.isDirectory) {
            val ok = if (text == original) true else ModuleDevStore.writeText(f, text)
            if (!ok) {
                scope.launch { snackbar.showSnackbar("保存失败") }
                return
            }
        }
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = relPath.substringAfterLast('/'),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = relPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回并保存")
                    }
                },
                actions = {
                    Text(
                        text = if (saved) "" else "未保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(
                        onClick = {
                            val f = file ?: return@IconButton
                            if (ModuleDevStore.writeText(f, text)) {
                                saved = true
                                scope.launch { snackbar.showSnackbar("已保存") }
                            } else {
                                scope.launch { snackbar.showSnackbar("保存失败") }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "保存")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (file == null || file.isDirectory) {
                Text(
                    text = "无法打开该文件",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        saved = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    placeholder = { Text("在此输入文件内容…") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// AI 辅助
// ----------------------------------------------------------------------

private enum class DevRole { USER, AI }

private data class DevMsg(
    val role: DevRole,
    val content: String,
    val applied: Int = 0,
    val failed: Int = 0,
    /**
     * 【1.4.2】模型的思考内容（reasoning_content / reasoning）。
     *
     * 由 AIChatService.chatStream 的 onReasoning 通道实时追加；只用于气泡上方的
     * 可折叠「思考」块，不进 [[content]]、不参与 <<<FILE:>>> 解析。
     */
    val reasoning: String = "",
    /**
     * 【1.4.9】本次回复里执行过的工具。
     *
     * 旧版把「【建目录】参数…」直接拼进 content，气泡里只有一段纯文字、没有图标；
     * 现在改成结构化记录，由气泡渲染成带图标的工具行（对齐 Operit）。
     * 历史仍需带上工具输出，见 AiAssistTab 里的 history 构造。
     */
    val tools: List<DevToolRun> = emptyList(),
)

@Composable
private fun AiAssistTab(
    project: ModuleDevStore.Project?,
    projects: List<ModuleDevStore.Project>,
    onOpenProject: (ModuleDevStore.Project) -> Unit,
    onProjectChanged: () -> Unit,
) {
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // 【1.4.2】按工程落盘：以前只存在内存里，切模块 / 重进页面就全丢。
    val projectId = project?.id
    var messages by remember(projectId) { mutableStateOf(listOf<DevMsg>()) }
    var loadedFor by remember(projectId) { mutableStateOf<String?>(null) }
    // 【1.4.3】一个工程可以有多个会话：原「云端对话」的聊天记录抽屉搬到这里，
    // 做同一个模块分了几天、或想另起一条思路时，不用把老对话清掉。
    var sessions by remember(projectId) { mutableStateOf(listOf<DevChatStore.Session>()) }
    var sessionId by remember(projectId) { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var sessionQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<DevChatStore.Session?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showClearAll by remember { mutableStateOf(false) }
    // 消息操作（长按气泡）：菜单 / 编辑
    var msgMenuIdx by remember { mutableStateOf(-1) }
    var editingIdx by remember { mutableStateOf(-1) }
    var editingText by remember { mutableStateOf("") }

    /** 界面消息 → 存档形态。 */
    fun recsOf(src: List<DevMsg>): List<DevChatStore.Rec> = src.map {
        DevChatStore.Rec(
            role = if (it.role == DevRole.USER) "USER" else "AI",
            // 存档里不留工具占位符（<<<TOOLIDX:n>>>）：重进页面时 tools 不在，占位符只会变成噪声
            content = DevAiTools.stripMarkers(it.content),
            applied = it.applied,
            failed = it.failed,
        )
    }

    /** 同步写盘（调用方保证在 IO 线程）：标题沿用会话现有标题，没有就按第一条用户消息生成。 */
    fun saveSnapshot(pid: String, sid: String, list: List<DevMsg>) {
        if (list.isEmpty()) return
        val title = sessions.firstOrNull { it.id == sid }?.title
            ?.takeIf { it.isNotBlank() && it != "新对话" }
            ?: DevChatStore.autoTitle(
                list.firstOrNull { it.role == DevRole.USER }?.content.orEmpty()
            )
        DevChatStore.save(pid, sid, title, recsOf(list))
    }

    LaunchedEffect(projectId) {
        val pid = projectId
        val all = if (pid == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { DevChatStore.loadSessions(pid) }
        }
        sessions = all
        val first = all.firstOrNull()
        sessionId = if (pid == null) null else (first?.id ?: DevChatStore.newId())
        messages = first?.messages.orEmpty().map {
            DevMsg(
                role = if (it.role == "USER") DevRole.USER else DevRole.AI,
                content = it.content,
                applied = it.applied,
                failed = it.failed,
            )
        }
        loadedFor = pid
    }

    // 【1.4.4 修复 ·「聊完返回再进来记录就没了」】原来只有下面这一个 800ms 防抖写盘：
    // 流式输出时 messages 每帧都在变，防抖被一直重置；用户回复完立刻按返回，
    // 组合销毁 → 写盘协程一起被取消 → 这一整段对话从来没落过盘。
    // 现在三道保险：①每新增一条消息立刻写；②流式内容静默 800ms 再补写；
    // ③离开页面（返回 / 切 Tab / 切模块）时同步补写一次。
    LaunchedEffect(messages.size, projectId, sessionId) {
        val pid = projectId ?: return@LaunchedEffect
        val sid = sessionId ?: return@LaunchedEffect
        if (loadedFor != pid) return@LaunchedEffect
        withContext(Dispatchers.IO) { saveSnapshot(pid, sid, messages) }
        sessions = withContext(Dispatchers.IO) { DevChatStore.loadSessions(pid) }
    }
    LaunchedEffect(messages, projectId, sessionId) {
        val pid = projectId ?: return@LaunchedEffect
        val sid = sessionId ?: return@LaunchedEffect
        if (loadedFor != pid) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        delay(800)
        withContext(Dispatchers.IO) { saveSnapshot(pid, sid, messages) }
        sessions = withContext(Dispatchers.IO) { DevChatStore.loadSessions(pid) }
    }
    // 离开页面时兜底：onDispose 不依赖协程调度，组合销毁一定会执行。
    // 存档只有几十 KB，同步写一次不会卡住界面。
    val curSessionId by rememberUpdatedState(sessionId)
    val curMessages by rememberUpdatedState(messages)
    DisposableEffect(projectId) {
        onDispose {
            val pid = projectId ?: return@onDispose
            val sid = curSessionId ?: return@onDispose
            if (loadedFor != pid) return@onDispose
            val list = curMessages
            if (list.isEmpty()) return@onDispose
            runCatching { saveSnapshot(pid, sid, list) }
        }
    }

    // 切换会话/切模块前先把当前会话写盘，避免「刚说了两句就切走」丢掉最后一段。
    fun persistNow() {
        val pid = projectId ?: return
        val sid = sessionId ?: return
        if (messages.isEmpty()) return
        val snapshot = messages.toList()
        scope.launch(Dispatchers.IO) { saveSnapshot(pid, sid, snapshot) }
    }
    val listState = rememberLazyListState()
    // 【1.4.1】本页自己的模型选择（只影响「制作模块」，其他界面在对话页选）
    var showModelPicker by remember { mutableStateOf(false) }

    // ---------- 【1.4.2】制作模块对话：附件 / 推理档位 / 可中断 ----------
    val context = LocalContext.current
    var attachImages by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var attachName by remember { mutableStateOf<String?>(null) }
    var attachText by remember { mutableStateOf("") }
    var showReason by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    // ---------- 主 AI 自动识图（【1.4.9】手动 OCR 已移除） ----------
    var showVisionSheet by remember { mutableStateOf(false) }
    var showToolSheet by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // 「首轮没有文件块 → 按格式重试」这段网络请求单独标记，
    // 不再占用发送键（否则 AI 已经输出完，按钮还挂在生成中）。
    var retrying by remember { mutableStateOf(false) }

    fun newSession() {
        if (sending) {
            scope.launch { snackbar.showSnackbar("正在回复，先点停止再新建") }
            return
        }
        persistNow()
        sessionId = DevChatStore.newId()
        messages = emptyList()
        input = ""
        attachImages = emptyList()
        attachName = null
        attachText = ""
    }

    fun switchSession(target: DevChatStore.Session) {
        if (target.id == sessionId) return
        if (sending) {
            scope.launch { snackbar.showSnackbar("请先等当前回复结束或点「停止」") }
            return
        }
        persistNow()
        sessionId = target.id
        messages = target.messages.map {
            DevMsg(
                role = if (it.role == "USER") DevRole.USER else DevRole.AI,
                content = it.content,
                applied = it.applied,
                failed = it.failed,
            )
        }
        input = ""
    }

    fun deleteSession(target: DevChatStore.Session) {
        val pid = projectId ?: return
        scope.launch(Dispatchers.IO) {
            DevChatStore.delete(pid, target.id)
            val all = DevChatStore.loadSessions(pid)
            withContext(Dispatchers.Main) {
                sessions = all
                if (sessionId == target.id) {
                    val next = all.firstOrNull()
                    if (next != null) {
                        sessionId = next.id
                        messages = next.messages.map {
                            DevMsg(
                                role = if (it.role == "USER") DevRole.USER else DevRole.AI,
                                content = it.content,
                                applied = it.applied,
                                failed = it.failed,
                            )
                        }
                    } else {
                        sessionId = DevChatStore.newId()
                        messages = emptyList()
                    }
                }
                snackbar.showSnackbar("已删除这个会话")
            }
        }
    }

    // 图片：多选，压到长边 1024 / JPEG 78，避免把请求体撑爆
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.take(6).mapNotNull { u -> readImageDataUrl(context, u) }
            }
            if (added.isEmpty()) {
                snackbar.showSnackbar("图片读取失败")
            } else {
                attachImages = (attachImages + added).take(6)
                snackbar.showSnackbar("已附加 ${added.size} 张图片")
            }
        }
    }
    // 文件：任意格式；文本按 UTF-8 读，二进制给占位说明而不是「读取失败」
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readAttachedText(context, uri) }
            if (read == null) {
                snackbar.showSnackbar("这个文件读不出来（空文件或没有读取权限）")
            } else {
                attachName = read.first
                attachText = read.second
                snackbar.showSnackbar("已附加 ${read.first}（${read.second.length} 字）")
            }
        }
    }

    // 【v1.4.0 修复 · 「聊天框和底栏重合、发送键点了没反应」】
    // 本页的底栏不是 Scaffold 的 bottomBar 槽，而是浮在内容之上的覆盖层（见 AxActivity 的
    // BottomBar 与 LocalBottomBarInset 注释）。页面自己不留底部空间时，输入栏会正好落进
    // 底栏区域——视觉上是「输入框被底栏压住」，交互上更严重：覆盖层先拿到触摸事件，
    // 点击「发送」实际点到底栏上，于是完全没有反应。
    // 修法：给整列补上底栏占用高度；键盘弹出时 imePadding 已经让出空间、
    // 且底栏被键盘挡在下面，此时不再重复补，避免输入栏与键盘之间出现一块空白。
    val barInset = LocalBottomBarInset.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val bottomExtra = if (imeVisible) 0.dp else barInset

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // 【1.4.3 修复】状态条显示「实际生效的路由」。
    // 旧实现写死「官方 · 模型名」，用户关掉官方默认 AI、改用自定义 API 后，
    // 界面仍显示官方模型（与实际请求路由不一致）。
    val modelLabel = AIConfigStore.chatRouteLabel(AIConfigStore.AiPurpose.MODULE_DEV)
    // 连接状态按真实路由判断：自定义 API 只要网址 + Key 齐全就算已配置，
    // 不再因为「中转站没连」而误显示「未连接」。
    val officialConnected =
        AIChatService.isCloudConfigured(AIConfigStore.AiPurpose.MODULE_DEV)

    // 【1.4.3】聊天记录抽屉（原「云端对话」页的左抽屉）：只在抽屉打开时吃手势，
    // 关闭时把左右滑留给页面本身，避免和 TabRow 抢事件。
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(304.dp)) {
                // 【1.4.4】抽屉改成「模块列表 + 本模块的对话」两段：
                // 用户要求先选模块、再自动切到那个模块的聊天记录，
                // 所以模块列表放最上面（没有模块时只提示，不显示会话区）。
                LazyColumn(
                    modifier = Modifier.fillMaxHeight().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "模块与对话",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { scope.launch { drawerState.close() } }) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                        }
                    }
                    item {
                        Text(
                            text = "已创建的模块（点一下切到它的聊天记录）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                    }
                    if (projects.isEmpty()) {
                        item {
                            Text(
                                text = "还没有模块。先回「模块开发」新建一个，聊天记录会按模块分开存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        items(projects, key = { "p:" + it.id }) { p ->
                            DevProjectRow(
                                name = p.name,
                                subtitle = "${p.id} · v${p.version} · ${p.fileCount} 个文件",
                                selected = p.id == projectId,
                                onClick = {
                                    if (p.id != projectId) {
                                        if (sending) {
                                            scope.launch {
                                                snackbar.showSnackbar("正在回复，先点停止再切模块")
                                            }
                                        } else {
                                            persistNow()
                                            onOpenProject(p)
                                        }
                                    }
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    }
                    item {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Text(
                            text = if (project == null) {
                                "本模块的对话"
                            } else {
                                "「${project.name}」的对话"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                        )
                    }
                    if (project == null) {
                        item {
                            Text(
                                text = "先在上面选一个模块，才能看到它的聊天记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        item {
                            Button(
                                onClick = {
                                    newSession()
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("在这个模块里新建对话")
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = sessionQuery,
                                onValueChange = { sessionQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                placeholder = { Text("搜索会话") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                singleLine = true,
                            )
                        }
                        val shownSessions = sessions.filter {
                            sessionQuery.isBlank() ||
                                it.title.contains(sessionQuery.trim(), ignoreCase = true)
                        }
                        if (shownSessions.isEmpty()) {
                            item {
                                Text(
                                    text = if (sessions.isEmpty()) {
                                        "还没有会话，点上面「新建对话」开始。"
                                    } else {
                                        "没有匹配的会话。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                )
                            }
                        } else {
                            items(shownSessions, key = { "s:" + it.id }) { sess ->
                                DevSessionRow(
                                    session = sess,
                                    selected = sess.id == sessionId,
                                    onClick = {
                                        switchSession(sess)
                                        scope.launch { drawerState.close() }
                                    },
                                    onRename = {
                                        renameTarget = sess
                                        renameText = sess.title
                                    },
                                    onDelete = { deleteSession(sess) },
                                )
                            }
                        }
                        item {
                            TextButton(onClick = { showClearAll = true }) {
                                Text("清空本模块全部会话", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(bottom = bottomExtra),
        ) {
        // 状态卡片：第一行工程 + 会话操作，第二行当前模型路由。
        // 旧版把工程名、长模型名、两个图标挤在一行，模型名一长工程名就被挤没。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = project?.name ?: "未选择工程（先回「模块开发」新建/打开）",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 聊天记录入口：本模块的多个会话
                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "本模块的聊天记录",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                messages = emptyList()
                                scope.launch { snackbar.showSnackbar("已清空对话") }
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "清空对话",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 第二行：路由状态点 + 模型名，点整行换「制作模块」用的模型
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showModelPicker = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (officialConnected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (officialConnected) modelLabel else "$modelLabel · 未配置",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (officialConnected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "切换模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (showModelPicker) {
            AlertDialog(
                onDismissRequest = { showModelPicker = false },
                title = { Text("制作模块用的模型") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        AIConfigStore.officialModelGroups.forEach { group ->
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                            group.options.forEach { opt ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            AIConfigStore.setOfficialModelName(opt.value)
                                            showModelPicker = false
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = opt.displayName,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (AIConfigStore.officialModelName == opt.value) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "当前",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "只影响「制作模块」；其他界面在对话页选择模型。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModelPicker = false }) { Text("关闭") }
                },
            )
        }

        if (showReason) {
            DevReasonSheet(onDismiss = { showReason = false })
        }
        // ---------- 识图 / 工具清单 ----------
        if (showVisionSheet) {
            DevVisionSheet(
                onDismiss = { showVisionSheet = false },
            )
        }
        if (showToolSheet) {
            DevToolListSheet(onDismiss = { showToolSheet = false })
        }
        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "描述你想做的模块",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "长按任意一条消息：复制 / 修改后重发 / 从这里重来 / 删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "做一个开机自动清理后台缓存的模块",
                    "把某个 App 的广告域名重定向到 127.0.0.1",
                    "写一个每 10 分钟记录电量的常驻模块",
                ).forEach { sample ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .clickable { input = sample },
                    ) {
                        Text(
                            text = sample,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages.size) { idx ->
                        DevBubble(
                            msg = messages[idx],
                            onLongPress = { msgMenuIdx = idx },
                            onTap = {
                                devCopyToClipboard(context, messages[idx].content)
                                scope.launch { snackbar.showSnackbar("已复制") }
                            },
                        )
                    }
                }
                // 翻到上面看旧消息时，给一个「回到底部」的悬浮按钮
                val canScrollDown by remember {
                    derivedStateOf { listState.canScrollForward }
                }
                if (canScrollDown) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "回到底部",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // 输入栏
        // 【v1.4.1】常驻提示条：把「为什么可能发不出去」直接写在界面上。
        // 旧实现只在点发送后弹一次 snackbar，用户很容易错过，主观感受就是「点了没反应」。
        val blockReason: String? = when {
            project == null -> "未选择工程：请先回到「模块开发」新建或打开一个工程"
            !officialConnected -> "未连接官方 AI：请先到「AI 引擎 → 连接官方 AI（中转站）」点击连接"
            else -> null
        }
        if (blockReason != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = blockReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        // 【1.5.0】原先这里有一条「首轮没有文件块，正在按格式重试」的提示条。
        // 按需求移除该提示 UI；自动重试逻辑本身不变（retrying 仍用于状态复位）。

        // ---------- 【1.4.2】已选附件 chips（可逐个移除） ----------
        if (attachImages.isNotEmpty() || attachName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachImages.forEachIndexed { i, item ->
                    // 图片附件直接显示缩略图（不再是一个「🖼 文件名」的文字 chip）
                    DevAttachChip(label = item.first, imageData = item.second) {
                        attachImages = attachImages.toMutableList().also { it.removeAt(i) }
                    }
                }
                attachName?.let { name ->
                    DevAttachChip("📎 $name") {
                        attachName = null
                        attachText = ""
                    }
                }
            }
        }
        // 【1.4.3】工具栏常驻输入框上方（对齐 Operit）：工具清单 + 识图 + 思考/推理
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val visionOk = AIConfigStore.visionAvailable()
            val toolsCount = if (visionOk) 9 else 8
            DevToolChip(label = "工具", badge = toolsCount.toString()) { showToolSheet = true }
            DevToolChip(
                label = "识图",
                badge = when {
                    !AIConfigStore.visionEnabled -> "已关"
                    !visionOk -> "未配置"
                    AIConfigStore.visionUseOfficialEffective -> "nemotron"
                    AIConfigStore.visionModel.isNotBlank() -> AIConfigStore.visionModel
                    else -> "自定义"
                },
            ) { showVisionSheet = true }
        }
        // 推理档位条常驻输入框上方（对齐 Operit），点一下展开 6 档
        DevReasonStrip(onOpen = { showReason = true })
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("图片（可多选，最多 6 张）") },
                        leadingIcon = { Icon(Icons.Filled.Image, null, Modifier.size(18.dp)) },
                        onClick = {
                            showAttachMenu = false
                            imagePicker.launch("image/*")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("文件（任意格式）") },
                        leadingIcon = { Icon(Icons.Filled.AttachFile, null, Modifier.size(18.dp)) },
                        onClick = {
                            showAttachMenu = false
                            filePicker.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说说你要做的模块…") },
                maxLines = 5,
                shape = RoundedCornerShape(18.dp),
            )
            Surface(
                shape = CircleShape,
                color = if (sending) {
                    MaterialTheme.colorScheme.errorContainer
                } else if (
                    (input.isBlank() && attachText.isBlank() && attachImages.isEmpty()) ||
                    project == null
                ) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (sending) {
                        // 【1.4.2】不再用转圈把按钮卡成「生成中」：给一个明确的停止键，
                        // 由用户决定何时收尾（对齐 Operit 的「停止生成」）。
                        IconButton(
                            onClick = {
                                // 先断 HTTP 连接，再取消协程：只 cancel 协程的话
                                // execute() 还挂在 socket 上，字会继续流出来。
                                AIChatService.cancelActive()
                                job?.cancel()
                                job = null
                                sending = false
                                scope.launch { snackbar.showSnackbar("已中断输出") }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止生成",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                // 【v1.4.0 修复】没选工程时旧实现是静默 return，用户点发送
                                // 完全没有任何反馈（看起来像"点了没反应"）。改为明确提示。
                                val p = project
                                if (p == null) {
                                    // showSnackbar 是 suspend，必须放到协程里（直接调用会编译失败）
                                    scope.launch {
                                        snackbar.showSnackbar("请先回到「模块开发」新建或打开一个工程")
                                    }
                                    return@IconButton
                                }
                                val text = input.trim()
                                val attachedText = attachText
                                val attachedName = attachName
                                val imgs = attachImages.map { it.second }
                                // 【v1.4.1】发送中 / 空内容都给明确反馈，不再静默 return
                                if (sending) return@IconButton
                                if (text.isEmpty() && attachedText.isBlank() && imgs.isEmpty()) {
                                    scope.launch {
                                        snackbar.showSnackbar("请先输入要做的模块描述")
                                    }
                                    return@IconButton
                                }
                                val userText = if (attachedText.isNotBlank()) {
                                    "【附件：${attachedName ?: "文件"}】\n```\n" +
                                        attachedText + "\n```\n\n" + text
                                } else {
                                    text
                                }
                                val bubble = buildString {
                                    append(text)
                                    if (imgs.isNotEmpty()) {
                                        if (isNotEmpty()) append("\n\n")
                                        append("🖼 ${imgs.size} 张图片")
                                    }
                                    if (attachedName != null) {
                                        if (isNotEmpty()) append("\n\n")
                                        append("📎 $attachedName（${attachedText.length} 字）")
                                    }
                                }
                                input = ""
                                attachImages = emptyList()
                                attachName = null
                                attachText = ""
                                sending = true
                                messages = messages + DevMsg(
                                    DevRole.USER,
                                    bubble.ifBlank { userText },
                                )
                                messages = messages + DevMsg(DevRole.AI, "")
                                val aiIdx = messages.lastIndex

                                val history = messages.dropLast(2)
                                    .filter { it.content.isNotBlank() }
                                    .map {
                                        AIChatService.ChatMessage(
                                            if (it.role == DevRole.USER) "user" else "assistant",
                                            it.content + it.tools.joinToString("") { r ->
                                                "\n\n———\n【" + r.name +
                                                    (if (r.ok) "】" else " · 失败】") +
                                                    r.arg + "\n" + r.body
                                            },
                                        )
                                    }
                                job = scope.launch {
                                    try {
                                        // 【1.4.3 修复】只有走官方中转站时才需要先换取日钥。
                                        // 自定义 API 路由不依赖中转站；旧实现无条件 connect，
                                        // 会把纯自定义环境下的消息误拦成「未连接到官方 AI」。
                                        if (AIConfigStore.usesOfficialChatRoute) {
                                            when (val r = AxKeyProvider.connect(forceRefresh = false)) {
                                                is AxKeyProvider.ConnectResult.Fail -> {
                                                    messages = messages.toMutableList().also {
                                                        it[aiIdx] = it[aiIdx].copy(
                                                            content = "未连接到官方 AI：${r.message}\n\n" +
                                                                "请先到「AI 引擎 → 连接官方 AI（中转站）」" +
                                                                "连接成功后再发送；或在 AI 设置里改用自定义 API。",
                                                        )
                                                    }
                                                    return@launch
                                                }
                                                is AxKeyProvider.ConnectResult.Ok -> Unit
                                            }
                                        }
                                        val system = withContext(Dispatchers.IO) {
                                            // 把「本次附带几张图」告诉模型，VISION 用序号指向附图。
                                            ModuleDevStore.moduleDevSystemPrompt(attachedImages = imgs.size)
                                        }
                                        // 上下文放进用户消息：官方模型对 system 只保留 1200 字符，
                                        // 对 prompt 有 6000 字符上限，buildAiContext 已按 3500 字符裁剪。
                                        val promptWithContext = withContext(Dispatchers.IO) {
                                            buildString {
                                                // 只带当前模块的记忆（DevMemoryStore 按工程隔离）
                                                val memos = projectId
                                                    ?.let { DevMemoryStore.load(it) }
                                                    .orEmpty()
                                                if (memos.isNotEmpty()) {
                                                    appendLine("【这个模块已经记住的事】")
                                                    memos.take(20).forEach { appendLine("- " + it.text) }
                                                    appendLine()
                                                }
                                                appendLine("【当前工程上下文】")
                                                appendLine(ModuleDevStore.buildAiContext(p))
                                                appendLine()
                                                appendLine("【我的需求】")
                                                append(userText)
                                            }
                                        }
                                        val full = AIChatService.chatStream(
                                            system = system,
                                            prompt = promptWithContext,
                                            history = history,
                                            onDelta = { delta ->
                                                messages = messages.toMutableList().also {
                                                    val cur = it[aiIdx]
                                                    it[aiIdx] = cur.copy(content = cur.content + delta)
                                                }
                                            },
                                            // 【1.4.2】思考走独立通道：只累加到 reasoning，
                                            // 绝不混进正文（否则 <<<FILE>>> 解析会被思考文字污染）。
                                            onReasoning = { reason ->
                                                messages = messages.toMutableList().also {
                                                    val cur = it[aiIdx]
                                                    it[aiIdx] = cur.copy(reasoning = cur.reasoning + reason)
                                                }
                                            },
                                            images = imgs,
                                            // 「制作模块」是唯一走中转站（动态日钥）的入口；
                                            // 其余 AI 功能默认直连 NVIDIA（见 AIConfigStore.AiPurpose）。
                                            purpose = AIConfigStore.AiPurpose.MODULE_DEV,
                                        )
                                        // 【1.4.2】首轮流式一结束就复位发送键：
                                        // 后续的解析/写盘/按格式重试都不该再占用这个按钮。
                                        sending = false
                                        // 用量写进「这次实际打到哪个地址」对应的桶。
                                        // 本页按 MODULE_DEV 路由走中转站，但仍读 lastOfficial，
                                        // 避免官方取钥失败退回其它路由时算错桶。
                                        if (full != null) {
                                            AIConfigStore.addUsage(
                                                promptWithContext.length,
                                                full.length,
                                                AIChatService.lastOfficial,
                                            )
                                        }
                                        if (full == null) {
                                            if (messages[aiIdx].content.isBlank()) {
                                                val reason = AIChatService.lastError ?: "调用失败"
                                                messages = messages.toMutableList().also {
                                                    it[aiIdx] = it[aiIdx].copy(
                                                        content = "调用失败：$reason\n\n可在「AI 引擎 → 云端模型配置」检查模型与 Key 是否可用。"
                                                    )
                                                }
                                            } else {
                                                snackbar.showSnackbar(
                                                    AIChatService.lastError ?: "回复中断"
                                                )
                                            }
                                        } else {
                                            // 解析并落地 AI 产出的文件（磁盘写放到 IO 线程）
                                            val edits = ModuleDevStore.parseFileEdits(full)
                                            val devCalls = DevAiTools.parse(full)
                                            AiTrace.log(
                                                "dev",
                                                "reply chars=${full.length} edits=${edits.size} head=${full.take(120).replace("\n", " ")}"
                                            )
                                            if (edits.isNotEmpty() || devCalls.isNotEmpty()) {
                                                val result = withContext(Dispatchers.IO) {
                                                    ModuleDevStore.applyEdits(p, edits)
                                                }
                                                val ok = result.first
                                                val failed = result.second
                                                if (devCalls.isNotEmpty()) {
                                                    val toolRun = DevAiTools.runAll(
                                                        p, devCalls,
                                                        allowWrite = true,
                                                        allowRun = true,
                                                        // 把本次消息附带的图片交给 VISION 工具
                                                        visionImages = imgs,
                                                    )
                                                    // 【1.4.9】工具结果不再拼成一段纯文本：存进 DevMsg.tools，
                                                    // 由气泡渲染成带图标的工具行（对齐 Operit）；历史里仍会还原成文本。
                                                    val toolRows = toolRun.map { r ->
                                                        DevToolRun(
                                                            kind = r.call.kind,
                                                            name = devToolName(r.call.kind),
                                                            arg = r.call.arg,
                                                            body = r.text,
                                                            ok = r.ok,
                                                        )
                                                    }
                                                    messages = messages.toMutableList().also {
                                                        it[aiIdx] = it[aiIdx].copy(
                                                            content = DevAiTools.stripMarkers(it[aiIdx].content, keepToolIndex = true),
                                                            tools = it[aiIdx].tools + toolRows,
                                                        )
                                                    }
                                                    snackbar.showSnackbar("已执行 " + toolRun.size + " 个工具调用")
                                                    // 【1.4.3】识图结果回灌给主 AI：
                                                    // 把视觉模型识别出的文字再发一轮给主 AI，
                                                    // 让它据此继续写代码/解释（两个 AI 相互配合），
                                                    // 而不是只把识图文字贴在气泡里。
                                                    val visionRun = toolRun.filter {
                                                        it.call.kind == DevAiTools.Kind.VISION && it.ok
                                                    }
                                                    if (visionRun.isNotEmpty()) {
                                                        val backPrompt = buildString {
                                                            appendLine("【识图工具返回（由视觉模型识别）】")
                                                            visionRun.forEach {
                                                                appendLine(it.text)
                                                                appendLine()
                                                            }
                                                            appendLine("请基于以上识图结果继续完成我的需求；")
                                                            appendLine("需要写文件时用 <<<FILE:相对路径>>>…<<<END>>>；")
                                                            appendLine("除非确实看不清，不要再重复调用 VISION。")
                                                            appendLine()
                                                            appendLine("【我的需求】")
                                                            append(userText)
                                                        }
                                                        val full3 = AIChatService.chatStream(
                                                            system = system,
                                                            prompt = backPrompt,
                                                            history = emptyList(),
                                                            onDelta = { delta ->
                                                                messages = messages.toMutableList().also {
                                                                    val cur = it[aiIdx]
                                                                    it[aiIdx] = cur.copy(
                                                                        content = cur.content + delta
                                                                    )
                                                                }
                                                            },
                                                            purpose = AIConfigStore.AiPurpose.MODULE_DEV,
                                                        )
                                                        if (full3 != null) {
                                                            val edits3 = ModuleDevStore.parseFileEdits(full3)
                                                            val r3 = if (edits3.isEmpty()) {
                                                                Pair(0, emptyList<String>())
                                                            } else {
                                                                withContext(Dispatchers.IO) {
                                                                    ModuleDevStore.applyEdits(p, edits3)
                                                                }
                                                            }
                                                            messages = messages.toMutableList().also {
                                                                val cur = it[aiIdx]
                                                                it[aiIdx] = cur.copy(
                                                                    content = cur.content + "\n\n———\n" +
                                                                        DevAiTools.stripMarkers(full3),
                                                                    applied = cur.applied + r3.first,
                                                                    failed = cur.failed + r3.second.size,
                                                                )
                                                            }
                                                            if (r3.first > 0) onProjectChanged()
                                                        } else {
                                                            AiTrace.log(
                                                                "dev",
                                                                "vision follow-up failed: ${AIChatService.lastError}"
                                                            )
                                                        }
                                                    }
                                                }
                                                messages = messages.toMutableList().also {
                                                    it[aiIdx] = it[aiIdx].copy(
                                                        applied = ok,
                                                        failed = failed.size,
                                                    )
                                                }
                                                if (ok > 0) {
                                                    onProjectChanged()
                                                    snackbar.showSnackbar("已写入 $ok 个文件")
                                                }
                                            } else {
                                                // 【1.4.0 修复】实测部分模型（如 glm-flash）会只聊天、
                                                // 不按 <<<FILE:>>> 格式输出，结果「回了话但工程没变化」，
                                                // 用户看到的就是「发了没用」。这里自动按固定格式再要一次，
                                                // 只有两次都拿不到文件块才提示失败。
                                                retrying = true
                                                AiTrace.log("dev", "no edits in first reply; 自动按格式重试一次")
                                                val retryPrompt = buildString {
                                                    appendLine("请只输出文件块，不要任何解释或寒暄，格式固定：")
                                                    appendLine("<<<FILE:相对路径>>>")
                                                    appendLine("文件完整内容")
                                                    appendLine("<<<END>>>")
                                                    appendLine()
                                                    appendLine("【当前工程上下文】")
                                                    appendLine(ModuleDevStore.buildAiContext(p))
                                                    appendLine()
                                                    appendLine("【我的需求】")
                                                    append(userText)
                                                }
                                                val full2 = AIChatService.chatStream(
                                                    system = system,
                                                    prompt = retryPrompt,
                                                    history = emptyList(),
                                                    onDelta = { },
                                                    purpose = AIConfigStore.AiPurpose.MODULE_DEV,
                                                )
                                                retrying = false
                                                val edits2 = if (full2 != null) {
                                                    ModuleDevStore.parseFileEdits(full2)
                                                } else {
                                                    emptyList()
                                                }
                                                AiTrace.log(
                                                    "dev",
                                                    "retry chars=${full2?.length ?: 0} edits=${edits2.size}"
                                                )
                                                if (edits2.isNotEmpty()) {
                                                    val r2 = withContext(Dispatchers.IO) {
                                                        ModuleDevStore.applyEdits(p, edits2)
                                                    }
                                                    messages = messages.toMutableList().also {
                                                        it[aiIdx] = it[aiIdx].copy(
                                                            content = (full2 ?: full) + "\n\n———\n" +
                                                                "✅ 已按格式重试并写入 ${r2.first} 个文件。",
                                                            applied = r2.first,
                                                            failed = r2.second.size,
                                                        )
                                                    }
                                                    if (r2.first > 0) {
                                                        onProjectChanged()
                                                        snackbar.showSnackbar("已写入 ${r2.first} 个文件")
                                                    }
                                                } else {
                                                    AiTrace.log("dev", "retry still no edits; 未写入任何文件")
                                                    messages = messages.toMutableList().also {
                                                        it[aiIdx] = it[aiIdx].copy(
                                                            content = full + "\n\n———\n" +
                                                                "⚠️ 两次回复都没有可写入的文件块，工程文件没有变化。\n" +
                                                                "可以换个模型（云端模型配置）或直接点名要改哪个文件。"
                                                        )
                                                    }
                                                    snackbar.showSnackbar("两次都没拿到文件块，未写入")
                                                }
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        if (t is kotlinx.coroutines.CancellationException) throw t
                                        // 用户点了「停止」：连接是我们主动掐断的，
                                        // 不要把「已取消」渲染成「调用异常」。
                                        if (!sending) {
                                            if (aiIdx in messages.indices &&
                                                messages[aiIdx].content.isNotBlank()
                                            ) {
                                                messages = messages.toMutableList().also {
                                                    it[aiIdx] = it[aiIdx].copy(
                                                        content = it[aiIdx].content + "\n\n…（已中断）"
                                                    )
                                                }
                                            }
                                            return@launch
                                        }
                                        if (aiIdx in messages.indices) {
                                            messages = messages.toMutableList().also {
                                                it[aiIdx] = it[aiIdx].copy(
                                                    content = "调用异常：${t.message ?: t.javaClass.simpleName}"
                                                )
                                            }
                                        }
                                    } finally {
                                        sending = false
                                        retrying = false
                                        job = null
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = if (sending || input.isBlank() || project == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onPrimary
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    }

    // ---------------- 长按消息：复制 / 修改后重发 / 回撤 / 删除 ----------------
    val menuMsg = messages.getOrNull(msgMenuIdx)
    if (menuMsg != null) {
        AlertDialog(
            onDismissRequest = { msgMenuIdx = -1 },
            title = { Text(if (menuMsg.role == DevRole.USER) "这条提问" else "这条回复") },
            text = {
                Column {
                    Text(
                        text = menuMsg.content.take(160).ifBlank { "（空）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    DevMsgAction("复制内容") {
                        runCatching {
                            val cm = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE
                            ) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("dev-chat", menuMsg.content)
                            )
                        }
                        msgMenuIdx = -1
                        scope.launch { snackbar.showSnackbar("已复制") }
                    }
                    if (menuMsg.role == DevRole.USER) {
                        DevMsgAction("修改后重发") {
                            editingIdx = msgMenuIdx
                            editingText = menuMsg.content
                            msgMenuIdx = -1
                        }
                    }
                    DevMsgAction("从这里重来（删掉后面的对话）") {
                        messages = messages.take(msgMenuIdx + 1)
                        msgMenuIdx = -1
                        scope.launch { snackbar.showSnackbar("已回撤到这条") }
                    }
                    DevMsgAction("删除这一条", danger = true) {
                        val i = msgMenuIdx
                        messages = messages.filterIndexed { index, _ -> index != i }
                        msgMenuIdx = -1
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { msgMenuIdx = -1 }) { Text("关闭") }
            },
        )
    }

    if (editingIdx >= 0) {
        AlertDialog(
            onDismissRequest = { editingIdx = -1 },
            title = { Text("修改这条提问") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "确认后会把这条之后的对话删掉，改好的内容填回输入框（不自动发送）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editingText.isNotBlank(),
                    onClick = {
                        messages = messages.take(editingIdx)
                        input = editingText.trim()
                        editingIdx = -1
                        scope.launch { snackbar.showSnackbar("已回撤到这里，确认后点发送") }
                    },
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { editingIdx = -1 }) { Text("取消") }
            },
        )
    }

    // ---------------- 会话重命名 ----------------
    val rt = renameTarget
    if (rt != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("给这次对话起个名字") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val pid = projectId
                        val title = renameText.trim()
                        renameTarget = null
                        if (pid != null) {
                            DevChatStore.rename(pid, rt.id, title)
                            sessions = DevChatStore.loadSessions(pid)
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // ---------------- 清空本工程全部会话 ----------------
    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部会话？") },
            text = {
                Text("会删掉「${project?.name ?: "当前工程"}」下的所有聊天记录（AI 记忆不受影响）。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    val pid = projectId ?: return@TextButton
                    DevChatStore.clearAll(pid)
                    sessions = emptyList()
                    sessionId = DevChatStore.newId()
                    messages = emptyList()
                    scope.launch { snackbar.showSnackbar("已清空全部会话") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            },
        )
    }

}

/** 抽屉里的模块一行：名字 + id/版本/文件数 +「当前」标记。 */
@Composable
private fun DevProjectRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "当前模块",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 聊天记录抽屉里的一行：标题 + 条数/时间 + 重命名/删除。 */
@Composable
private fun DevSessionRow(
    session: DevChatStore.Session,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${session.messages.size} 条 · " + formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "重命名",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除会话",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 复制到剪贴板：失败也不该崩，静默吞掉即可。 */
private fun devCopyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("module-dev-chat", text))
    }
}

/** 长按菜单里的一行按钮（左对齐、整行可点）。 */
@Composable
private fun DevMsgAction(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DevBubble(
    msg: DevMsg,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
) {
    val isUser = msg.role == DevRole.USER
    val segs = remember(msg.content) { parseDevSegments(msg.content) }
    // 占位符已经渲染过的工具序号：气泡末尾不再重复补一遍（工具行回到原位）
    val placedTools = remember(segs) { segs.mapNotNull { it.toolIdx }.toSet() }
    var detail by remember { mutableStateOf<DevFileHit?>(null) }
    var toolDetail by remember { mutableStateOf<DevToolRun?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.86f else 1f)
                .padding(end = if (isUser) 0.dp else 6.dp)
                // 长按气泡＝打开这一条的操作菜单（复制/修改/回撤/删除）；
                // 轻点＝复制全文（与原「云端对话」页的手感一致）
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
        ) {
            if (isUser) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 6.dp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = msg.content.ifBlank { "" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            } else {
                // 【1.4.2】Operit 风格：思考默认收起，点一下展开；正文为空时视为「正在思考」。
                if (msg.reasoning.isNotBlank() && AIConfigStore.showReasoning) {
                    DevReasonBlock(reasoning = msg.reasoning, thinking = msg.content.isBlank())
                }
                // 【1.4.5】一条 AI 回复只渲染成**一张卡片**：
                // 旧版每个正文段各包一个 Surface，多段回复会变成一串高矮不一的碎气泡。
                // 卡片内正文按段排，写入文件仍是可点开的工具行。
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 6.dp,
                        bottomEnd = 18.dp,
                    ),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        if (segs.isEmpty() && msg.tools.isEmpty()) {
                            Text(
                                text = "…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        } else {
                            segs.forEach { seg ->
                                val path = seg.path
                                val toolIdx = seg.toolIdx
                                if (toolIdx != null) {
                                    // 工具行回到它原本出现的位置（修复「工具图标跑到输出末尾」）
                                    msg.tools.getOrNull(toolIdx)?.let { run ->
                                        DevToolRow(
                                            icon = devToolIcon(run.kind),
                                            name = run.name,
                                            summary = run.arg,
                                            titleColor = DEV_TOOL_BLUE,
                                            summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            done = run.ok,
                                        ) { toolDetail = run }
                                    }
                                } else if (path == null) {
                                    // 正文段：顺手清掉流式阶段可能残留的工具标记，避免闪出尖括号或留下空行
                                    val shown = collapseBlankLines(DevAiTools.stripMarkers(seg.text)).trim()
                                    if (shown.isNotBlank()) {
                                        Text(
                                            text = shown,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(vertical = 5.dp),
                                        )
                                    }
                                } else {
                                    DevToolRow(
                                        icon = Icons.Filled.Description,
                                        name = "写入文件",
                                        summary = path,
                                        titleColor = DEV_TOOL_BLUE,
                                        summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        done = msg.applied > 0,
                                    ) { detail = DevFileHit(path, seg.text) }
                                }
                            }
                        }
                        msg.tools.forEachIndexed { i, run ->
                            // 已经在正文原位置渲染过的工具行不再重复追加
                            if (i in placedTools) return@forEachIndexed
                            DevToolRow(
                                icon = devToolIcon(run.kind),
                                name = run.name,
                                summary = run.arg,
                                titleColor = DEV_TOOL_BLUE,
                                summaryColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                done = run.ok,
                            ) { toolDetail = run }
                        }
                    }
                }
            }
            if (msg.applied > 0 || msg.failed > 0) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (msg.applied > 0) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "已写入 ${msg.applied} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (msg.failed > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${msg.failed} 个路径非法被跳过",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
    detail?.let { hit ->
        DevToolDetailDialog(
            title = "写入文件",
            subtitle = hit.path,
            body = hit.body,
            onDismiss = { detail = null },
        )
    }
    toolDetail?.let { run ->
        DevToolDetailDialog(
            title = run.name,
            subtitle = run.arg,
            body = run.body,
            onDismiss = { toolDetail = null },
        )
    }
}

/**
 * 【1.4.9】一条工具执行记录：气泡里渲染成「图标 + 工具名 + 参数」的可点行（对齐 Operit）。
 */
private data class DevToolRun(
    val kind: DevAiTools.Kind,
    val name: String,
    val arg: String,
    val body: String,
    val ok: Boolean,
)

/**
 * 【1.4.2】AI 回复的可见切片：path 为 null 表示普通正文，非 null 表示一个写入块。
 */
private data class DevSeg(val path: String?, val text: String, val toolIdx: Int? = null)
private data class DevFileHit(val path: String, val body: String)
private const val DEV_FILE_OPEN = "<<<FILE:"
private const val DEV_FILE_CLOSE = "<<<END>>>"
/** 工具占位标记：由 [DevAiTools.stripMarkers] 以 keepToolIndex=true 生成。 */
private const val DEV_TOOL_OPEN = "<<<TOOLIDX:"

/**
 * 把 AI 原文切成「正文 / 写入块」两种切片，供对话流按需渲染。
 * 流式过程中出现的半截标记（如 `<<<FI`）不当作正文显示，避免闪出一堆尖括号。
 */
private fun parseDevSegments(content: String): List<DevSeg> {
    if (content.isEmpty()) return emptyList()
    val out = ArrayList<DevSeg>()
    var idx = 0
    while (idx < content.length) {
        val fileAt = content.indexOf(DEV_FILE_OPEN, idx)
        val toolAt = content.indexOf(DEV_TOOL_OPEN, idx)
        val next = when {
            fileAt < 0 -> toolAt
            toolAt < 0 -> fileAt
            else -> minOf(fileAt, toolAt)
        }
        if (next < 0) {
            val tail = stripDanglingMarker(content.substring(idx))
            if (tail.isNotBlank()) out.add(DevSeg(null, tail))
            break
        }
        if (next > idx) {
            val head = stripDanglingMarker(content.substring(idx, next))
            if (head.isNotBlank()) out.add(DevSeg(null, head))
        }
        // 工具占位（`<<<TOOLIDX:n>>>`）：交给气泡在「原本出现的位置」渲染工具行
        if (toolAt >= 0 && next == toolAt && (fileAt < 0 || toolAt < fileAt)) {
            val end = content.indexOf(">>>", toolAt + DEV_TOOL_OPEN.length)
            if (end < 0) break
            val n = content.substring(toolAt + DEV_TOOL_OPEN.length, end).trim().toIntOrNull()
            if (n != null) out.add(DevSeg(null, "", n))
            idx = end + 3
            continue
        }
        val start = next
        val headerEnd = content.indexOf('\n', start)
        if (headerEnd < 0) break
        val rawPath = content
            .substring(start + DEV_FILE_OPEN.length, headerEnd)
            .trim()
            .removeSuffix(">>>")
            .trim()
        val bodyStart = headerEnd + 1
        val close = content.indexOf(DEV_FILE_CLOSE, bodyStart)
        if (close < 0) {
            out.add(DevSeg(rawPath, content.substring(bodyStart)))
            break
        }
        out.add(DevSeg(rawPath, content.substring(bodyStart, close)))
        idx = close + DEV_FILE_CLOSE.length
    }
    return out
}

/** 【1.5.0】把连续 3 个以上换行压成一个空行：模型常在工具调用前后塞大段空行，气泡里会留一大片空白。 */
private fun collapseBlankLines(text: String): String =
    if (text.contains("\n\n\n")) text.replace(Regex("\\n{3,}"), "\n\n") else text

/** 去掉正文末尾的半截 `<<<FILE:` 标记（流式增量时会出现）。 */
private fun stripDanglingMarker(text: String): String {
    var t = text
    var cut = 0
    for (marker in listOf(DEV_FILE_OPEN, DEV_TOOL_OPEN)) {
        for (len in 1 until marker.length) {
            if (t.endsWith(marker.substring(0, len))) {
                cut = maxOf(cut, len)
                break
            }
        }
    }
    if (cut > 0) t = t.substring(0, t.length - cut)
    return t
}

/** 【1.4.9】工具名（工具清单与气泡工具行共用）。 */
private fun devToolName(kind: DevAiTools.Kind): String = when (kind) {
    DevAiTools.Kind.MKDIR -> "建目录"
    DevAiTools.Kind.LS -> "列目录"
    DevAiTools.Kind.READ -> "读取文件"
    DevAiTools.Kind.DEL -> "删除"
    DevAiTools.Kind.RUN -> "运行脚本"
    DevAiTools.Kind.SEARCH -> "联网搜索"
    DevAiTools.Kind.GH -> "GitHub"
    DevAiTools.Kind.FIND -> "开源检索"
    DevAiTools.Kind.VISION -> "识图"
}

/** 【1.4.9】工具图标（对齐 Operit：按工具类型给图标）。 */
private fun devToolIcon(kind: DevAiTools.Kind): ImageVector = when (kind) {
    DevAiTools.Kind.MKDIR -> Icons.Filled.CreateNewFolder
    DevAiTools.Kind.LS -> Icons.Filled.FolderOpen
    DevAiTools.Kind.READ -> Icons.Filled.Description
    DevAiTools.Kind.DEL -> Icons.Filled.Delete
    DevAiTools.Kind.RUN -> Icons.Filled.Terminal
    DevAiTools.Kind.SEARCH -> Icons.Filled.Search
    DevAiTools.Kind.GH -> Icons.Filled.Code
    DevAiTools.Kind.FIND -> Icons.Filled.Search
    DevAiTools.Kind.VISION -> Icons.Filled.Image
}

/**
 * Operit 同款工具调用行：16dp 图标 + 工具名（80~120dp）+ 单行省略摘要，整行可点。
 */
@Composable
private fun DevToolRow(
    icon: ImageVector,
    name: String,
    summary: String,
    titleColor: Color,
    summaryColor: Color,
    done: Boolean = false,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = DEV_TOOL_BLUE,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = summaryColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
    // 完成后在工具块下方给一个对号（对齐 Operit 的工具执行结果）
    if (done) {
        Row(
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已完成",
                modifier = Modifier.size(12.dp),
                tint = DEV_TOOL_BLUE,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelSmall,
                color = DEV_TOOL_BLUE,
            )
        }
    }
    }
}

@Composable
private fun DevToolDetailDialog(
    title: String,
    subtitle: String,
    body: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = body.ifBlank { "（空内容）" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("axm-dev-file", body)
                    )
                }
            ) { Text("复制") }
        },
    )
}

// ----------------------------------------------------------------------
// 对话框与小组件
// ----------------------------------------------------------------------

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String, ModuleDevStore.Template) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0") }
    var author by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(ModuleDevStore.Template.SCRIPT) }
    var idTouched by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建模块工程", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (!idTouched) id = ModuleDevStore.sanitizeId(it)
                    },
                    label = { Text("模块名称") },
                    placeholder = { Text("例如：省电模式") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = {
                        id = ModuleDevStore.sanitizeId(it)
                        idTouched = true
                    },
                    label = { Text("模块 ID（唯一）") },
                    placeholder = { Text("例如：battery_saver") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it },
                        label = { Text("版本") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("作者") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("描述（可选）") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    text = "模板",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ModuleDevStore.Template.entries.forEach { t ->
                    val selected = template == t
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { template = t },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (t) {
                                    ModuleDevStore.Template.SCRIPT -> Icons.Filled.Terminal
                                    ModuleDevStore.Template.SERVICE -> Icons.Filled.Build
                                    ModuleDevStore.Template.OVERLAY -> Icons.Filled.Layers
                                    ModuleDevStore.Template.RUNTIME -> Icons.Filled.Extension
                                    ModuleDevStore.Template.BLANK -> Icons.Filled.Description
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = t.desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name, id, version, author, desc, template)
                }
            ) {
                Text("创建", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun NewPathDialog(
    isDir: Boolean,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var path by remember { mutableStateOf(if (initial.isNotBlank()) initial else if (isDir) "overlay/" else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDir) "新建目录" else "新建文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(if (isDir) "目录名或路径" else "文件名（含后缀）") },
                    placeholder = {
                        Text(if (isDir) "例如：system/etc" else "例如：action.sh、module.prop")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Text(
                    text = "创建在当前目录下；也可以直接写 a/b/c 这样的多级路径。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(path.trim()) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun AssistChipLike(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ----------------------------------------------------------------------
// 工具函数
// ----------------------------------------------------------------------

private fun isScript(path: String): Boolean {
    val lower = path.lowercase(Locale.ROOT)
    return lower.endsWith(".sh") || lower.endsWith(".prop") || lower.endsWith(".conf")
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}K"
    else -> String.format(Locale.US, "%.1fM", bytes / 1024.0 / 1024.0)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * 按 id 稳定地分配一个强调色（同一工程每次进入颜色一致，避免花哨跳动）。
 */
private fun accentColorFor(id: String): Color {
    val palette = listOf(
        Color(0xFF4C8DFF),
        Color(0xFF2FB37A),
        Color(0xFF9B6BFF),
        Color(0xFFE0723F),
        Color(0xFF22A5B8),
        Color(0xFFD94F6E),
    )
    val hash = id.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return palette[hash % palette.size]
}

// ==================== 【1.4.2】制作模块对话的附件 / 推理档位组件 ====================

/** 输入框上方的常驻档位条：图标 + 「推理」 + 渐变进度 + 当前档位名。点开 6 档面板。 */
/**
 * 【1.4.2】思考折叠块（Operit 同款交互）：一行「图标 + 状态 + 字数 + 展开」，点开看全文。
 *
 * 思考内容是模型自述的推理过程，默认收起，避免刷屏；只影响显示，不参与工具解析。
 */
@Composable
private fun DevReasonBlock(reasoning: String, thinking: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (thinking) "思考中…（${reasoning.length} 字）" else "已思考（${reasoning.length} 字）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = reasoning.ifBlank { "（暂无内容）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun DevReasonStrip(onOpen: () -> Unit) {
    val enabled = AIConfigStore.reasoningEnabled
    val idx = AIConfigStore.reasoningLevelIndex
    val levelCount = AIConfigStore.REASONING_LEVELS.size
    val lastIndex = levelCount - 1
    // 面板或外部配置改变时同步；拖动期间 idx 不变，因此不会打断手势。
    var sliderPosition by remember(idx, levelCount) { mutableStateOf(idx.toFloat()) }
    val pendingIndex = sliderPosition.roundToInt().coerceIn(0, lastIndex)
    val pendingKey = AIConfigStore.REASONING_LEVELS[pendingIndex]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "推理",
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                // 只在松手时持久化，避免拖动过程反复写设置。
                val target = sliderPosition.roundToInt().coerceIn(0, lastIndex)
                sliderPosition = target.toFloat()
                AIConfigStore.setReasoningLevel(target)
            },
            valueRange = 0f..lastIndex.toFloat(),
            // AndroidX：steps 是端点之间的刻度数；6 个可选位置 = 4 + 2。
            steps = (levelCount - 2).coerceAtLeast(0),
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = AIConfigStore.REASONING_LABELS[pendingKey] ?: "标准",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 2.dp, vertical = 6.dp),
        )
    }
}

/** 【1.4.3】输入条上方的工具 chip：左标题 + 右侧小徽标（数量 / 当前模型）。 */
@Composable
private fun DevToolChip(label: String, badge: String? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            if (badge != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 单个工具的静态描述（工具固定启用，这里只展示、不提供开关）。 */
private data class DevToolInfo(
    val name: String,
    val handle: String,
    val desc: String,
    /** 【1.4.9】工具图标（对齐 Operit：清单里每个工具都有图标）。 */
    val icon: ImageVector,
)

/**
 * 【1.4.3】工具清单：8 个 agent 通用工具。
 *
 * 用户明确要求这些是 agent 的通用能力，不是「小工具」开关，因此这个面板是只读的。
 */
@Composable
private fun DevToolListSheet(onDismiss: () -> Unit) {
    val tools = mutableListOf(
        DevToolInfo("建目录", "<<<MKDIR:目录>>>", "创建目录（自动补父目录）", Icons.Filled.CreateNewFolder),
        DevToolInfo("列目录", "<<<LS:目录>>>", "列出目录内容", Icons.Filled.FolderOpen),
        DevToolInfo("读文件", "<<<READ:文件>>>", "读取文件正文", Icons.Filled.Description),
        DevToolInfo("删除", "<<<DEL:路径>>>", "删除文件或目录", Icons.Filled.Delete),
        DevToolInfo("运行脚本", "<<<RUN:x.sh>>>", "运行工程内的 .sh 脚本", Icons.Filled.Terminal),
        DevToolInfo("联网搜索", "<<<SEARCH:关键词>>>", "Bing 优先的网页搜索", Icons.Filled.Search),
        DevToolInfo("GitHub", "<<<GH:owner/repo[/路径]>>>", "查仓库概览 / 目录 / 文件正文", Icons.Filled.Code),
        DevToolInfo("开源检索", "<<<FIND:关键词>>>", "GitHub 仓库 + Stack Overflow", Icons.Filled.Search),
    )
    // 【1.4.3】识图是「按条件出现」的工具：官方 nemotron 识图关且自定义 API 没配时，
    // 这里不列出来，模型也拿不到标记，从源头避免无效调用。
    if (AIConfigStore.visionAvailable()) {
        tools.add(
            DevToolInfo(
                "识图",
                "<<<VISION:#1|要求>>>",
                "看不清图片时交给视觉模型（nemotron / 自定义）识别，结果回灌给主 AI 继续干活",
                Icons.Filled.Image,
            )
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工具（${tools.size}）") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                tools.forEach { t ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            t.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = DEV_TOOL_BLUE,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(t.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = t.handle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = t.desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "这些是 agent 的通用工具，固定启用、不能关闭。" +
                        "「识图」只在官方 nemotron 识图开启或自定义识图 API 配好后才出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 识图设置面板（【1.4.9】手动单张 OCR 已移除）。
 *
 * 【1.4.9】只保留自动识图：主 AI 看不清附件时通过 <<<VISION:...>>> 工具调用；
 * 手动「选择图片识别」入口已移除。
 */
@Composable
private fun DevVisionSheet(
    onDismiss: () -> Unit,
) {
    val officialAiOn = AIConfigStore.useOfficialAi
    var enabled by remember { mutableStateOf(AIConfigStore.visionEnabled) }
    // 【1.4.9】nemotron 辅助受官方默认 AI 联动：官方关闭时开关置灰、强制关闭。
    // 存档里的 visionUseOfficial 不改写：重新打开官方 AI 后，原来的选择自动恢复。
    var useOfficial by remember {
        mutableStateOf(AIConfigStore.visionUseOfficialEffective)
    }
    var provider by remember { mutableStateOf(AIConfigStore.visionProvider) }
    var baseUrl by remember { mutableStateOf(AIConfigStore.visionBaseUrl) }
    var apiKey by remember { mutableStateOf(AIConfigStore.visionApiKey) }
    var model by remember { mutableStateOf(AIConfigStore.visionModel) }
    val showCustom = !officialAiOn || !useOfficial
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 识图辅助") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI 识图模型辅助", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开启后，主 AI 看不清图片时会调用视觉模型，" +
                                "再把识别结果交给主 AI 继续处理。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        AIConfigStore.setVisionEnabled(it)
                    })
                }
                if (!enabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已关闭：识图不会出现在主 AI 的工具清单里。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "nemotron-3.5-super 辅助",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (officialAiOn) {
                                    "使用官方默认 AI 的视觉模型" +
                                        "（${AIConfigStore.OFFICIAL_VISION_MODEL}），" +
                                        "走中转站，无需再填 Key。"
                                } else {
                                    "官方默认 AI 已关闭：该辅助强制关闭且无法开启；" +
                                        "先打开官方默认 AI，再回来开启它。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = useOfficial && officialAiOn,
                            enabled = officialAiOn,
                            onCheckedChange = {
                                useOfficial = it
                                AIConfigStore.setVisionUseOfficial(it)
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (showCustom) {
                        Text("自定义识图 API", style = MaterialTheme.typography.labelMedium)
                        if (!officialAiOn) {
                            Text(
                                text = "官方默认 AI 未开启：识图使用下面这组自定义 API。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AIConfigStore.cloudProviders.forEach { p ->
                                FilterChip(
                                    selected = provider == p,
                                    onClick = {
                                        provider = p
                                        AIConfigStore.setVisionProvider(p)
                                    },
                                    label = { Text(p, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it; AIConfigStore.setVisionBaseUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Base URL（如 https://host/v1）") },
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; AIConfigStore.setVisionApiKey(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("API Key") },
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; AIConfigStore.setVisionModel(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("识图模型名（必填，如 gpt-4o / qwen-vl-max）") },
                        )
                    } else {
                        Text(
                            text = "当前：官方默认 AI · nemotron-3.5-super-vl（走中转站日钥，无需填 Key）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 就绪状态直接写在面板里：用户填完就能知道工具有没有对主 AI 开放。
                    val ready = AIConfigStore.visionAvailable()
                    Text(
                        text = if (ready) {
                            "已就绪：主 AI 需要时会自动调用识图工具。"
                        } else {
                            "未就绪：补全上面的配置后，识图工具才会对主 AI 开放。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}


/** 6 档推理面板：开关 + 档位列表（上游只认 low/medium/high，高 3 档靠 token 与提示表达）。 */
@Composable
private fun DevReasonSheet(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("思考模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 【1.4.3】思考模型总开关：关闭 = 当普通（无思考）模型用；
                // 开启后才把 reasoning_effort / max_tokens 写进请求体。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用思考（思考模型）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "关闭＝当普通模型用，不传推理参数；开启后按下面所选等级调整 reasoning_effort 与最大输出。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = AIConfigStore.reasoningEnabled,
                        onCheckedChange = { AIConfigStore.setReasoningEnabled(it) },
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 【1.4.3】思考引导：适用于不支持思考、但能按要求先推理再作答的普通模型。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("思考引导", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "适用于「无思考模式」的模型：在 system 开头要求它先在内部逐步推理再只输出最终结论。" +
                                "思考模型本身无需开启。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = AIConfigStore.thinkingGuide,
                        onCheckedChange = { AIConfigStore.setThinkingGuide(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "推理等级（仅思考模型生效；高 3 档靠更大输出与自审表达）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                AIConfigStore.REASONING_LEVELS.forEachIndexed { i, key ->
                    val selected = i == AIConfigStore.reasoningLevelIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { AIConfigStore.setReasoningLevel(i) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = AIConfigStore.REASONING_LABELS[key] ?: key,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = AIConfigStore.REASONING_DESCS[key].orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "当前",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** 附件 chip：图片显示真实缩略图，其余显示一行文字 + 移除按钮，长文件名省略。 */
@Composable
private fun DevAttachChip(label: String, imageData: String? = null, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = if (imageData != null) 3.dp else 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageData != null) {
                // 真正的图片缩略图（data URL 直接交给 Coil，不必先落盘）
                AsyncImage(
                    model = imageData,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
            } else {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除",
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/** 图片附件上限：单张压到长边 1024、JPEG 78，避免请求体过大被上游拒。 */
private const val DEV_ATTACH_MAX_BYTES = 384 * 1024
private const val DEV_ATTACH_MAX_CHARS = 200_000

/** 读图片并转成可直接进 image_url 的 data URL；失败返回 null。 */
private fun readImageDataUrl(context: Context, uri: Uri): Pair<String, String>? {
    return try {
        val name = devDisplayName(context, uri) ?: "图片"
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (raw.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var scale = 1
        while (maxSide / scale > 1024) scale *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = scale }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
        if (!bmp.isRecycled) bmp.recycle()
        name to "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (t: Throwable) {
        null
    }
}

/**
 * 读附件：任意格式都尝试按 UTF-8 文本读。
 * - 超过 384KB 只读前 384KB；
 * - 控制字符占比 > 2% 视为二进制，返回「无法作为文本发送」的占位说明（而不是让用户以为读取失败）；
 * - 文本超过 200k 字符截断，避免把请求撑爆。
 */
private fun readAttachedText(context: Context, uri: Uri): Pair<String, String>? {
    return try {
        val name = devDisplayName(context, uri) ?: "附件"
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val raw = stream.readBytes()
            if (raw.size > DEV_ATTACH_MAX_BYTES) raw.copyOf(DEV_ATTACH_MAX_BYTES) else raw
        } ?: return null
        if (bytes.isEmpty()) return null
        var ctrl = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0 || v < 0x09 || (v in 0x0E..0x1F)) ctrl++
        }
        val ratio = ctrl.toFloat() / bytes.size.toFloat()
        if (ratio > 0.02f) {
            return name to buildString {
                appendLine("【附件：$name】")
                appendLine("这是一个二进制文件（含 $ctrl 个不可见控制字符），内容不能当文本发送。")
                append("如果要处理它，请直接说明需求；需要改的文本文件请另选。")
            }
        }
        val text = String(bytes, Charsets.UTF_8)
        val body = if (text.length > DEV_ATTACH_MAX_CHARS) {
            text.take(DEV_ATTACH_MAX_CHARS) + "\n…（内容过长，已截断）"
        } else {
            text
        }
        name to body
    } catch (t: Throwable) {
        null
    }
}

/** 有界读取图片字节，超过存储层上限即失败，避免把超大原图一次性读进内存。 */
private fun readImageBytesLimited(context: Context, uri: Uri): ByteArray? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > ModuleDevStore.MAX_IMAGE_BYTES) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }
}

/** 读取导入文件：与图片同一套流式读取，只是上限换成 [ModuleDevStore.MAX_IMPORT_BYTES]。 */
private fun readImportBytesLimited(context: Context, uri: Uri): ByteArray? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > ModuleDevStore.MAX_IMPORT_BYTES) return@use null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }
}

/** 取文件显示名（SAF 提供的 DISPLAY_NAME）。 */
private fun devDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (t: Throwable) {
        null
    }
}

// ==================== 【1.4.2】模块开发：文件运行 / 工具块配色 ====================

/** 工具块统一蓝色（对齐 Operit 的工具行视觉）。 */
private val DEV_TOOL_BLUE = Color(0xFF3D7BFF)

/** 当前只把 shell 脚本视为「可运行」。其它后缀（.js/.py 等）需要对应运行时，不在此处执行。 */
private val DEV_RUNNABLE_EXT = setOf("sh")

private fun devIsRunnable(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return DEV_RUNNABLE_EXT.contains(ext)
}

private fun devIsHtml(path: String): Boolean {
    return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> true
        else -> false
    }
}

/**
 * 全屏 HTML 预览。通过 WebViewAssetLoader 暴露且仅暴露当前工程目录，
 * 相对 CSS / JS / 图片会与 HTML 使用同一个 https 源，不需要 file:// 或 FileProvider。
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DevHtmlPreviewDialog(
    node: ModuleDevStore.Node,
    project: ModuleDevStore.Project,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val assetLoader = remember(context, project.absolutePath) {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/",
                WebViewAssetLoader.InternalStoragePathHandler(context, project.dir),
            )
            .build()
    }
    val previewUrl = remember(node.relPath) {
        val builder = Uri.Builder()
            .scheme("https")
            .authority(WebViewAssetLoader.DEFAULT_DOMAIN)
        node.relPath.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            builder.appendPath(segment)
        }
        builder.build().toString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭预览")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HTML 预览", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = node.relPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    return assetLoader.shouldInterceptRequest(request.url)
                                }
                            }
                            loadUrl(previewUrl)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.settings.javaScriptEnabled = false
                        webView.removeAllViews()
                        webView.destroy()
                    },
                )
            }
        }
    }
}

/**
 * 长按文件弹出的对话框：可运行后缀给出「运行」，其余只说明原因。
 *
 * 运行身份是 App 自己（uid 为普通应用），不是 root：
 * 适合验证脚本语法与普通逻辑，需要系统权限的步骤会失败——这点直接写在界面上，
 * 避免用户把「权限不足」误判成「脚本写错了」。
 */
@Composable
private fun DevRunDialog(
    node: ModuleDevStore.Node,
    project: ModuleDevStore.Project,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    val runnable = devIsRunnable(node.relPath)
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(if (runnable) "运行脚本" else "这个文件不能直接运行") },
        text = {
            Column {
                Text(
                    text = node.relPath,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (!runnable) {
                    Text(
                        text = "只有 .sh 脚本可以直接运行。其它类型请先导出，再装到设备上测试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "以 App 身份运行（不是 root）：可验证语法与普通逻辑，" +
                            "需要系统权限的步骤会失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    output?.let { text ->
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp),
                            )
                        }
                    }
                    if (running) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "运行中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (runnable) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        running = true
                        output = null
                        scope.launch {
                            output = withContext(Dispatchers.IO) { runScriptFile(project, node) }
                            running = false
                        }
                    },
                ) { Text(if (output == null) "运行" else "再运行一次") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = if (runnable) {
            { TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") } }
        } else {
            null
        },
    )
}

/**
 * 用系统 sh 执行工程内的脚本，最多等 15 秒。
 *
 * 必须限制时长：脚本里出现死循环或 `read` 等待输入时，App 侧会永久挂住，
 * 界面看起来就是「运行按钮点了一下再没反应」。
 */
private fun runScriptFile(project: ModuleDevStore.Project, node: ModuleDevStore.Node): String {
    return try {
        val pb = ProcessBuilder("/system/bin/sh", node.file.absolutePath)
        pb.directory(project.dir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(out) { out.appendLine(line) }
                    }
                }
            } catch (t: Throwable) {
                // 读取中断（进程被杀）时直接结束，不往界面抛异常
            } finally {
                latch.countDown()
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            val partial = synchronized(out) { out.toString() }
            return (if (partial.isBlank()) "" else partial) + "\n…（超过 15 秒已强制终止）"
        }
        val code = proc.waitFor()
        val body = synchronized(out) { out.toString() }
        (if (body.isBlank()) "（没有任何输出）" else body) + "\n[exit=$code]"
    } catch (t: Throwable) {
        "运行失败：${t.message ?: t.javaClass.simpleName}"
    }
}

/** 打开「所有文件访问权限」设置页（拿不到就退到权限总列表页）。 */
private fun openAllFilesAccessSettings(ctx: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appIntent = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:" + ctx.packageName),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(appIntent) }.onFailure {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
