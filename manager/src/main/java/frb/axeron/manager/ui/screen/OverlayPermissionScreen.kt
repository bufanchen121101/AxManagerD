package frb.axeron.manager.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.manager.R
import frb.axeron.manager.features.overlay.OverlayPermissionStore
import frb.axeron.manager.ui.component.OverlayDisclaimerDialog
import frb.axeron.manager.ui.component.OverlayDisclaimerReadOnlyDialog
import frb.axeron.manager.ui.viewmodel.OverlayPermissionViewModel

/**
 * 模块核心文件修改权限 —— 授权页。
 *
 * 结构参考 [DhizukuPermissionScreen]，但增加了：
 *  - 顶部常驻警示卡（危险提示）
 *  - 全局总开关（与设置页开关联动）
 *  - 首次进入的免责声明拦截
 *  - 列表包含所有已安装模块（含未申请者）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun OverlayPermissionScreen(
    navigator: DestinationsNavigator,
) {
    val vm: OverlayPermissionViewModel = viewModel()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 首次进入需同意免责；未同意则不展示列表内容
    var showDisclaimer by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refresh()
    }

    LaunchedEffect(vm.disclaimerAccepted) {
        // 刷新回来后若未同意，弹出免责声明
        if (!vm.disclaimerAccepted) showDisclaimer = true
    }

    if (showDisclaimer) {
        OverlayDisclaimerDialog(
            onAccept = {
                vm.acceptDisclaimer()
                showDisclaimer = false
            },
            onDismiss = {
                showDisclaimer = false
                navigator.popBackStack()
            }
        )
    }

    if (showReview) {
        OverlayDisclaimerReadOnlyDialog(onDismiss = { showReview = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.overlay_perm_manage),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.overlay_perm_manage_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showReview = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Article,
                            contentDescription = stringResource(R.string.overlay_disclaimer_review),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val list = vm.filteredRows

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = remember { PaddingValues(bottom = 120.dp) },
        ) {
            // ---- 顶部警示卡（常驻） ----
            item(key = "warning") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.overlay_warning_card),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.overlay_warning_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // ---- 全局总开关 ----
            item(key = "global") {
                ListItem(
                    modifier = Modifier.padding(end = 6.dp, top = 6.dp),
                    leadingContent = {
                        Icon(
                            imageVector = frb.axeron.manager.ui.icon.AxeronIcons.AxeronMark,
                            contentDescription = null,
                            tint = frb.axeron.manager.ui.icon.AxeronIcons.DEFAULT_TINT,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.overlay_global_switch),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.overlay_global_switch_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = vm.enabled,
                            enabled = vm.disclaimerAccepted,
                            onCheckedChange = { vm.toggleOverlay(it) },
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text(
                    text = stringResource(R.string.overlay_section_modules),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
            }

            // ---- 模块列表 ----
            if (list.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.overlay_module_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(list, key = { it.moduleId }) { row ->
                    ListItem(
                        modifier = Modifier.padding(end = 6.dp, top = 6.dp),
                        headlineContent = {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    text = row.moduleId,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                val status = buildString {
                                    append(
                                        stringResource(
                                            if (row.requested) R.string.overlay_module_requested
                                            else R.string.overlay_module_not_requested
                                        )
                                    )
                                    row.mode?.let { mode ->
                                        append(" · ")
                                        append(
                                            stringResource(
                                                when (mode) {
                                                    OverlayPermissionStore.GrantMode.ALWAYS ->
                                                        R.string.overlay_grant_mode_always
                                                    OverlayPermissionStore.GrantMode.ONCE ->
                                                        R.string.overlay_grant_mode_once
                                                    OverlayPermissionStore.GrantMode.DENIED ->
                                                        R.string.overlay_grant_mode_denied
                                                }
                                            )
                                        )
                                    }
                                }
                                Text(
                                    text = status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.granted) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (row.reason.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.overlay_reason_prefix, row.reason),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = row.granted,
                                    onCheckedChange = { vm.setModuleGranted(row.moduleId, it) },
                                )
                                if (row.overlayFiles > 0) {
                                    IconButton(onClick = { vm.clearOverlay(row.moduleId) }) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}