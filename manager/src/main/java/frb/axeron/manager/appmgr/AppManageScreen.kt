package frb.axeron.manager.appmgr

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal

/**
 * 软件管理主界面。
 *
 * 入口：主页右上角「三个竖点」菜单 → 软件管理。
 * 点击某个应用卡片 → 弹出管理面板（冻结/挂起/隐藏/强制卸载/清除数据/禁止联网）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AppManageScreen(
    navigator: DestinationsNavigator,
    viewModelGlobal: ViewModelGlobal,
) {
    val vm = viewModel<AppManageViewModel>()
    val activateViewModel = viewModelGlobal.activateViewModel
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ManagedApp?>(null) }

    // 【v1.9.1 新增】搜索态：点击右上角搜索图标后，标题区变为一条输入横线。
    var searching by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(searching) {
        // 进入搜索态自动聚焦（弹出键盘），符合「点一下就能直接打字」的预期。
        if (searching) runCatching { searchFocusRequester.requestFocus() }
    }

    LaunchedEffect(Unit) { vm.loadApps() }

    // 权限快照：DO > DP > shell。
    // 【v1.3.0】每次进入界面都实时刷新（本地 DPM 判定，不依赖 Shizuku），
    // 避免使用 ActivateViewModel 里可能过期的缓存导致「重开显示权限掉了」。
    var privilege by remember {
        mutableStateOf(
            AppManageViewModel.PrivilegeSnapshot(
                hasDo = activateViewModel.isDeviceOwner,
                hasDp = activateViewModel.isDpGranted,
                hasShell = activateViewModel.isShizukuActive,
                hasPo = activateViewModel.isProfileOwner,
            )
        )
    }
    LaunchedEffect(Unit) {
        // 本地 DPM 判定立即生效（同步快），Shizuku 查询在后台完成后再刷新一次
        privilege = vm.queryPrivilegeSnapshot()
    }

    LaunchedEffect(vm.lastResult) {
        vm.lastResult?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        // 【v1.9.1】搜索态：标题区变成一条可写文字的输入横线（用户要求）。
                        TextField(
                            value = vm.searchQuery,
                            onValueChange = { vm.updateSearchQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text("搜索应用名或包名") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    } else {
                        Text("软件管理")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 【v1.9.1】搜索入口：点击后标题区变为输入横线；再点一次清空并退出搜索。
                    IconButton(onClick = {
                        if (searching) {
                            vm.updateSearchQuery("")
                            searching = false
                            focusManager.clearFocus()
                        } else {
                            searching = true
                        }
                    }) {
                        Icon(
                            imageVector = if (searching) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searching) "关闭搜索" else "搜索",
                        )
                    }
                    // 右上角三个竖点
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("显示系统应用") },
                            leadingIcon = {
                                Switch(
                                    checked = vm.showSystemApps,
                                    onCheckedChange = { vm.toggleShowSystemApps(it) },
                                )
                            },
                            onClick = { vm.toggleShowSystemApps(!vm.showSystemApps) },
                        )
                        DropdownMenuItem(
                            text = { Text("刷新列表") },
                            onClick = {
                                menuOpen = false
                                vm.loadApps()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrivilegeBanner(privilege)

            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // 【v1.9.1】列表消费「过滤后」的列表；无匹配时给出明确空态提示。
                val shown = vm.visibleApps
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (vm.searchQuery.isBlank()) "未发现应用"
                                    else "没有找到匹配的应用",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(items = shown, key = { it.key }) { app ->
                            AppCard(app = app, onClick = { selected = app })
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AppManageDetailDialog(
            app = app,
            privilege = privilege,
            busy = vm.busyPackage == app.packageName,
            onDismiss = { selected = null },
            onAction = { action -> vm.perform(action, app.packageName, privilege) },
        )
    }
}

/** 顶部权限分级提示条。 */
@Composable
private fun PrivilegeBanner(privilege: AppManageViewModel.PrivilegeSnapshot) {
    val level = when {
        privilege.hasDo -> AppManagePrivilegeLevel.DO
        privilege.hasPo -> AppManagePrivilegeLevel.PO
        privilege.hasDp -> AppManagePrivilegeLevel.DP
        privilege.hasShell -> AppManagePrivilegeLevel.SHELL
        else -> AppManagePrivilegeLevel.NONE
    }
    val text = when (level) {
        AppManagePrivilegeLevel.DO -> "当前权限：设备所有者（DO）— 全部管理功能可用"
        AppManagePrivilegeLevel.PO -> "当前权限：资料所有者（PO）— 全部管理功能可用"
        AppManagePrivilegeLevel.DP -> "当前权限：DP Role — 挂起/卸载/禁用等可用"
        AppManagePrivilegeLevel.SHELL -> "当前权限：Shizuku(ADB) — 仅部分功能可用"
        AppManagePrivilegeLevel.NONE -> "当前无高级权限，请先激活 DO / PO / DP / Shizuku"
    }
    val color = when (level) {
        AppManagePrivilegeLevel.DO,
        AppManagePrivilegeLevel.PO,
        AppManagePrivilegeLevel.DP -> MaterialTheme.colorScheme.primaryContainer
        AppManagePrivilegeLevel.SHELL -> MaterialTheme.colorScheme.tertiaryContainer
        AppManagePrivilegeLevel.NONE -> MaterialTheme.colorScheme.errorContainer
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = color),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 单个应用卡片。 */
@Composable
private fun AppCard(app: ManagedApp, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.isSystem) {
                Text(
                    text = "系统",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * 软件管理界面（管理动作面板）。
 *
 * 每个动作的可用性由 [AppManageRouter.plan] 依当前权限动态判定；
 * 不可用的动作显示为禁用并说明原因（「授权不了就直接提示授权不了」）。
 */
@Composable
private fun AppManageDetailDialog(
    app: ManagedApp,
    privilege: AppManageViewModel.PrivilegeSnapshot,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (AppManageAction) -> Unit,
) {
    var pending by rememberSaveable { mutableStateOf<AppManageAction?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(app.label) },
        text = {
            Column {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                AppManageAction.entries
                    .filter { it != AppManageAction.RESTORE }
                    .forEach { action ->
                        val plan = AppManageRouter.plan(
                            action = action,
                            pkg = app.packageName,
                            hasDo = privilege.hasDo,
                            hasDp = privilege.hasDp,
                            hasShell = privilege.hasShell,
                            hasPo = privilege.hasPo,
                        )
                        ActionRow(
                            action = action,
                            plan = plan,
                            enabled = plan.available && !busy,
                            onClick = { pending = action },
                        )
                    }
                Spacer(Modifier.height(8.dp))
                ActionRow(
                    action = AppManageAction.RESTORE,
                    plan = AppManageRouter.plan(
                        action = AppManageAction.RESTORE,
                        pkg = app.packageName,
                        hasDo = privilege.hasDo,
                        hasDp = privilege.hasDp,
                        hasShell = privilege.hasShell,
                        hasPo = privilege.hasPo,
                    ),
                    enabled = !busy,
                    onClick = { pending = AppManageAction.RESTORE },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )

    // 危险操作二次确认
    pending?.let { action ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认${AppManageRouter.actionLabel(action)}？") },
            text = {
                Text(
                    when (action) {
                        AppManageAction.FORCE_UNINSTALL -> "将强制卸载「${app.label}」。卸载系统应用可能导致设备异常（变砖），请确认。"
                        AppManageAction.CLEAR_DATA -> "将清除「${app.label}」的全部用户数据，且不可恢复。"
                        else -> "将对「${app.label}」执行「${AppManageRouter.actionLabel(action)}」。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onAction(action)
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            },
        )
    }
}

/** 单行管理动作。 */
@Composable
private fun ActionRow(
    action: AppManageAction,
    plan: AppManagePlan,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val icon = when (action) {
        AppManageAction.FREEZE -> Icons.Outlined.Build
        AppManageAction.SUSPEND -> Icons.Filled.Cancel
        AppManageAction.HIDE -> Icons.Filled.Cancel
        AppManageAction.FORCE_UNINSTALL -> Icons.Filled.Delete
        AppManageAction.CLEAR_DATA -> Icons.Outlined.Update
        AppManageAction.BLOCK_NETWORK -> Icons.Filled.Security
        AppManageAction.RESTORE -> Icons.Filled.CheckCircle
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = AppManageRouter.actionLabel(action),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 显示将使用的权限等级；不可用时说明原因。
            Text(
                text = if (plan.available) "权限：${AppManageRouter.levelLabel(plan.level)}"
                else (plan.reason ?: "不可用"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}