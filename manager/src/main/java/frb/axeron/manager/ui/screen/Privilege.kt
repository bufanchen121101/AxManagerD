package frb.axeron.manager.ui.screen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.R
import frb.axeron.manager.appmgr.AppManageAction
import frb.axeron.manager.appmgr.AppManageRouter
import frb.axeron.manager.appmgr.AppManageVerifier
import frb.axeron.manager.appmgr.AppManageViewModel
import frb.axeron.manager.ui.component.UseLifecycle
import frb.axeron.manager.ui.viewmodel.AppsViewModel
import frb.axeron.manager.ui.viewmodel.DhizukuPermissionViewModel
import frb.axeron.manager.ui.viewmodel.PrivilegeViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 特权页（本轮 UI 改版）。
 *
 * 结构：
 *   ① [PrivilegeListContent]   —— 特权用户列表：圆角搜索框 + 应用行（图标右下角小圆点表示有无特权）。
 *      点某一行进入 ②。
 *   ② [PrivilegeDetailContent] —— 单个应用的详情：
 *        · 「特权用户」开关   → 复用 [PrivilegeViewModel.grant] / [PrivilegeViewModel.revoke]
 *        · 「所有者权限」开关 → 复用 [DhizukuPermissionViewModel.setAllow]
 *        · 「软件管理」开关组 → 复用 [AppManageViewModel.perform]（动作见 [AppManageAction]）
 *        · 「强制卸载」「清除用户数据」按钮 → 同样复用 [AppManageViewModel.perform]，保留原有二次确认弹窗
 *
 * 只动 UI：颜色一律取 [MaterialTheme] 既有颜色角色，未新增/修改任何颜色。
 * 开关语义：打开 = 执行该管理动作；关闭 = 执行源码既有的 [AppManageAction.RESTORE]（撤销以上限制）——
 * 源码没有单项反向命令，按「不加功能」的要求不改 Router/枚举。
 */
@Destination<RootGraph>
@Composable
fun PrivilegeScreen(
    navigator: DestinationsNavigator,
    viewModelGlobal: ViewModelGlobal,
) {
    val privilegeViewModel = viewModelGlobal.privilegeViewModel

    // 当前正在查看的应用（null = 停在列表页）。只存 uid，避免把 Parcelable 状态塞大。
    var openedUid by rememberSaveable { mutableStateOf<Int?>(null) }

    UseLifecycle(
        {
            privilegeViewModel.loadInstalledApps(false)
        }
    )

    // 读 privileges（Compose 状态）→ 授予/撤销后详情页会自动重组。
    val openedApp = openedUid?.let { privilegeViewModel.privileges[it] }

    if (openedApp == null) {
        PrivilegeListContent(
            viewModel = privilegeViewModel,
            onOpenApp = { uid -> openedUid = uid },
        )
    } else {
        PrivilegeDetailContent(
            viewModel = privilegeViewModel,
            app = openedApp,
            onBack = { openedUid = null },
        )
    }
}

/* ═══════════════════════ ① 特权用户列表 ═══════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivilegeListContent(
    viewModel: PrivilegeViewModel,
    onOpenApp: (Int) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.privilege_user),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
                // 常驻圆角搜索框（原来是顶栏放大镜展开，改为设计稿里的常驻胶囊）
                TextField(
                    value = viewModel.search,
                    onValueChange = { viewModel.search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    placeholder = { Text(stringResource(R.string.search_label_apps)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                        )
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    ) { paddingValues ->
        PullToRefreshBox(
            modifier = Modifier.padding(paddingValues),
            isRefreshing = viewModel.isRefreshing,
            onRefresh = { viewModel.loadInstalledApps() },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = remember { PaddingValues(bottom = 120.dp) },
            ) {
                items(
                    viewModel.privilegeList,
                    key = { it.packageName + it.uid }
                ) { app ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenApp(app.uid) }
                            .padding(end = 6.dp, top = 6.dp),
                        headlineContent = {
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = { AppIconWithStatus(app) },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 应用图标 + 右下角状态小圆点（有特权=主题色，无特权=弱化色）。 */
@Composable
private fun AppIconWithStatus(app: AppsViewModel.AppInfo) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(48.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(app.packageInfo)
                .crossfade(true)
                .build(),
            contentDescription = app.label,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (app.isAdded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
        )
    }
}

/* ═══════════════════════ ② 应用特权详情 ═══════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivilegeDetailContent(
    viewModel: PrivilegeViewModel,
    app: AppsViewModel.AppInfo,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appManageViewModel: AppManageViewModel = viewModel()
    val dhizukuViewModel: DhizukuPermissionViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }

    val uid = app.uid
    val pkg = app.packageName

    BackHandler(enabled = true) { onBack() }

    // 权限快照：与「软件管理」页同一套实时判定（DO / PO / DP / shell）
    var privilege by remember {
        mutableStateOf(
            AppManageViewModel.PrivilegeSnapshot(
                hasDo = false,
                hasDp = false,
                hasShell = false,
            )
        )
    }
    LaunchedEffect(Unit) {
        privilege = appManageViewModel.queryPrivilegeSnapshot()
    }

    // 所有者权限：读 Dhizuku 授权记录（未请求过授权的应用没有记录 → 开关不可用）
    LaunchedEffect(uid) { dhizukuViewModel.refresh() }
    val authEntry = dhizukuViewModel.authList.firstOrNull { it.uid == uid }

    // 软件管理各项当前状态（冻结 / 挂起 / 隐藏可静态校验；禁止联网无查询接口，按操作结果记本地态）
    var frozen by remember(uid) { mutableStateOf(false) }
    var suspended by remember(uid) { mutableStateOf(false) }
    var hidden by remember(uid) { mutableStateOf(false) }
    var netBlocked by remember(uid) { mutableStateOf(false) }
    LaunchedEffect(uid) {
        val states = withContext(Dispatchers.IO) {
            listOf(
                AppManageVerifier.verify(context, AppManageAction.FREEZE, pkg),
                AppManageVerifier.verify(context, AppManageAction.SUSPEND, pkg),
                AppManageVerifier.verify(context, AppManageAction.HIDE, pkg),
            )
        }
        frozen = states.getOrNull(0) == true
        suspended = states.getOrNull(1) == true
        hidden = states.getOrNull(2) == true
    }

    // 结果反馈（复用软件管理 ViewModel 的既有结果文案）
    LaunchedEffect(appManageViewModel.lastResult) {
        appManageViewModel.lastResult?.let {
            snackbarHostState.showSnackbar(it)
            appManageViewModel.consumeResult()
        }
    }

    val busy = appManageViewModel.busyPackage == pkg

    fun runAction(action: AppManageAction) {
        appManageViewModel.perform(action, pkg, privilege)
    }

    // 危险动作的二次确认（沿用源码既有行为，不删弹窗）
    var pendingAction by remember { mutableStateOf<AppManageAction?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = remember { PaddingValues(bottom = 120.dp) },
        ) {
            item(key = "hero") { AppHeaderCard(app) }

            // 开关一：特权用户（= 授予 ADB 权限）
            item(key = "privilege_switch") {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.privilege_user),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.privilege_adb_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = app.isAdded,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.grant(uid) else viewModel.revoke(uid)
                            },
                        )
                    },
                )
            }

            // 开关二：所有者权限（原「管理设备所有者授权」入口 → 开关）
            item(key = "owner_switch") {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.privilege_owner),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = if (authEntry == null) {
                                stringResource(R.string.privilege_owner_unavailable)
                            } else {
                                stringResource(R.string.privilege_owner_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = authEntry != null && authEntry.allowApi && !authEntry.blocked,
                            enabled = authEntry != null,
                            onCheckedChange = { checked ->
                                dhizukuViewModel.setAllow(uid, checked)
                            },
                        )
                    },
                )
            }

            // 小节：软件管理（原「软件管理」入口 → 开关组 + 两个按钮）
            item(key = "app_manage_section") {
                Text(
                    text = stringResource(R.string.app_manage_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
                )
            }

            item(key = "sw_freeze") {
                ActionSwitchRow(
                    title = AppManageRouter.actionLabel(AppManageAction.FREEZE),
                    description = stringResource(R.string.privilege_action_freeze_desc),
                    checked = frozen,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        frozen = checked
                        runAction(if (checked) AppManageAction.FREEZE else AppManageAction.RESTORE)
                    },
                )
            }

            item(key = "sw_suspend") {
                ActionSwitchRow(
                    title = AppManageRouter.actionLabel(AppManageAction.SUSPEND),
                    description = stringResource(R.string.privilege_action_suspend_desc),
                    checked = suspended,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        suspended = checked
                        runAction(if (checked) AppManageAction.SUSPEND else AppManageAction.RESTORE)
                    },
                )
            }

            item(key = "sw_hide") {
                ActionSwitchRow(
                    title = AppManageRouter.actionLabel(AppManageAction.HIDE),
                    description = stringResource(R.string.privilege_action_hide_desc),
                    checked = hidden,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        hidden = checked
                        runAction(if (checked) AppManageAction.HIDE else AppManageAction.RESTORE)
                    },
                )
            }

            item(key = "sw_block_network") {
                ActionSwitchRow(
                    title = AppManageRouter.actionLabel(AppManageAction.BLOCK_NETWORK),
                    description = stringResource(R.string.privilege_action_block_network_desc),
                    checked = netBlocked,
                    enabled = !busy,
                    onCheckedChange = { checked ->
                        netBlocked = checked
                        runAction(
                            if (checked) AppManageAction.BLOCK_NETWORK else AppManageAction.RESTORE
                        )
                    },
                )
            }

            // 危险项：按钮（不是开关），点击后仍走原有确认弹窗
            item(key = "danger_buttons") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { pendingAction = AppManageAction.FORCE_UNINSTALL },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(AppManageRouter.actionLabel(AppManageAction.FORCE_UNINSTALL))
                    }
                    OutlinedButton(
                        onClick = { pendingAction = AppManageAction.CLEAR_DATA },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(AppManageRouter.actionLabel(AppManageAction.CLEAR_DATA))
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        val actionLabel = AppManageRouter.actionLabel(action)
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(R.string.privilege_confirm_title, actionLabel)) },
            text = {
                Text(
                    when (action) {
                        AppManageAction.FORCE_UNINSTALL ->
                            stringResource(R.string.privilege_confirm_uninstall, app.label)

                        AppManageAction.CLEAR_DATA ->
                            stringResource(R.string.privilege_confirm_clear_data, app.label)

                        else ->
                            stringResource(R.string.privilege_confirm_generic, app.label, actionLabel)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        runAction(action)
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** 详情页头卡：图标 + 名称 + 版本 + 包名 + UID 徽标。 */
@Composable
private fun AppHeaderCard(app: AppsViewModel.AppInfo) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(app.packageInfo)
                    .crossfade(true)
                    .build(),
                contentDescription = app.label,
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                app.packageInfo.versionName?.let { version ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.privilege_uid_format, app.uid),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 软件管理的一行开关。 */
@Composable
private fun ActionSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}