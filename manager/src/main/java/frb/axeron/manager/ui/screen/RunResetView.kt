package frb.axeron.manager.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import frb.axeron.manager.ai.PrivilegedShell
import frb.axeron.manager.ai.RestorePlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 重置视图（运行历史 → 重置）。
 *
 * ## 两条路
 * 1. **生成 AI 提示词**：把设备信息 + 所有模块执行过的指令 + 最高可用权限 UID 汇总成一段提示词，
 *    复制给任意 agent，由它产出逐条可核对的恢复命令。适用于「没法自动判定」的目标。
 *
 * 2. **按系统默认值自动恢复**：只对 `settings put` 生成 `settings delete`（删除 = 回到系统默认），
 *    其它写入（文件 / 属性 / 包状态…）只列出、不自动执行 —— 不知道原值就不动。
 *    执行身份：已激活优先软件内 ADB（Axeron），未激活且已授权则走 Shizuku，两者都没有则提示授权/激活。
 *
 * ## 交互约束（用户要求）
 * 执行过程中**不能因为点到别处就退出**：
 * - 本视图内 `BackHandler` 在运行中直接吞掉返回键；
 * - 父级顶栏返回键在运行中被禁用（通过 [onBusyChange] 上报给调用方）。
 * 执行结束后右下角出现「返回」悬浮按钮。
 */
@Composable
fun RunResetView(
    onBusyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var plan by remember { mutableStateOf(RestorePlanner.Plan(emptyList())) }

    var prompt by remember { mutableStateOf<String?>(null) }
    var promptLoading by remember { mutableStateOf(false) }

    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var okCount by remember { mutableStateOf(0) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    var askConfirm by remember { mutableStateOf(false) }

    // 通道状态（进入页面时读一次；授权回调后再刷新）
    var channel by remember { mutableStateOf(PrivilegedShell.channel()) }

    val copiedText = stringResource(R.string.run_recorder_copied)
    // 授权请求码：本页固定值即可（监听器只在本页驻留期间注册）
    val requestCode = 9101

    fun reloadPlan() {
        scope.launch {
            loading = true
            val built = withContext(Dispatchers.IO) {
                runCatching { RestorePlanner.build(context) }.getOrNull()
            }
            plan = built ?: RestorePlanner.Plan(emptyList())
            // 默认全选可自动恢复的项（用户要求「支持全选等便捷操作」）
            selected = plan.autoActions.map { it.key }.toSet()
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        channel = PrivilegedShell.channel()
        reloadPlan()
    }

    LaunchedEffect(running) { onBusyChange(running) }

    // -------- 授权回调（Shizuku） --------
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            channel = PrivilegedShell.channel()
            Toast.makeText(
                context,
                if (granted) {
                    context.getString(R.string.run_reset_grant_ok)
                } else {
                    context.getString(R.string.run_reset_grant_denied, grantResult)
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // 执行过程中吞掉返回键
    BackHandler(enabled = running) { /* 故意留空：不允许中途退出 */ }

    fun executeSelected() {
        if (running) return
        val targets = plan.autoActions.filter { it.key in selected && !it.revert.isNullOrBlank() }
        if (targets.isEmpty()) return
        running = true
        finished = false
        failures = emptyList()
        okCount = 0
        scope.launch {
            var ok = 0
            val failed = ArrayList<String>()
            for (action in targets) {
                val cmd = action.revert.orEmpty()
                if (cmd.isBlank()) continue
                val result = withContext(Dispatchers.IO) {
                    runCatching { PrivilegedShell.exec(cmd) }
                        .getOrElse { t ->
                            PrivilegedShell.ExecResult(false, -1, "", t.message ?: t.toString())
                        }
                }
                if (result.ok) {
                    ok++
                } else {
                    val reason = result.err.ifBlank { result.out }
                        .ifBlank { "exit=${result.code}" }
                        .take(160)
                    failed.add("$cmd → $reason")
                }
            }
            okCount = ok
            failures = failed
            running = false
            finished = true
            channel = PrivilegedShell.channel()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 执行通道 ----------
            item(key = "__channel") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(
                                R.string.run_reset_channel,
                                PrivilegedShell.channelName(channel),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (channel == PrivilegedShell.Channel.NONE) {
                            Text(
                                modifier = Modifier.padding(top = 4.dp),
                                text = stringResource(R.string.run_reset_no_privilege),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    enabled = PrivilegedShell.isShizukuAlive(),
                                    onClick = {
                                        PrivilegedShell.requestShizukuPermission(requestCode)
                                            .onFailure { t ->
                                                Toast.makeText(
                                                    context,
                                                    t.message ?: t.toString(),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                    },
                                ) {
                                    Text(stringResource(R.string.run_reset_grant_shizuku))
                                }
                            }
                            Text(
                                modifier = Modifier.padding(top = 6.dp),
                                text = stringResource(R.string.run_reset_activate_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ---------- 方案一：AI 提示词 ----------
            item(key = "__prompt") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.run_reset_prompt_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = stringResource(R.string.run_reset_prompt_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                enabled = !promptLoading,
                                onClick = {
                                    promptLoading = true
                                    scope.launch {
                                        prompt = withContext(Dispatchers.IO) {
                                            runCatching { RestorePlanner.buildPrompt(context) }
                                                .getOrNull()
                                        }
                                        promptLoading = false
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (promptLoading) {
                                            R.string.run_reset_prompt_building
                                        } else {
                                            R.string.run_reset_prompt_build
                                        }
                                    )
                                )
                            }
                            val text = prompt
                            if (!text.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        copyPromptToClipboard(context, text)
                                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        modifier = Modifier.padding(start = 6.dp),
                                        text = stringResource(R.string.run_reset_prompt_copy),
                                    )
                                }
                            }
                        }
                        val text = prompt
                        if (text != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            ) {
                                SelectionContainer {
                                    Text(
                                        modifier = Modifier.padding(10.dp),
                                        text = text,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 方案二：自动恢复 ----------
            item(key = "__auto_header") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.run_reset_auto_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            modifier = Modifier.padding(top = 4.dp),
                            text = stringResource(R.string.run_reset_auto_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                enabled = !running && !loading,
                                onClick = {
                                    selected = plan.autoActions.map { it.key }.toSet()
                                },
                            ) {
                                Text(stringResource(R.string.run_reset_select_all))
                            }
                            TextButton(
                                enabled = !running && !loading,
                                onClick = { selected = emptySet() },
                            ) {
                                Text(stringResource(R.string.run_reset_select_none))
                            }
                            Button(
                                enabled = !running && !loading && selected.isNotEmpty(),
                                onClick = { askConfirm = true },
                            ) {
                                Text(
                                    stringResource(R.string.run_reset_execute, selected.size)
                                )
                            }
                        }
                        if (running) {
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text(
                                    modifier = Modifier.padding(start = 8.dp),
                                    text = stringResource(R.string.run_reset_busy_hint),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (loading) {
                item(key = "__auto_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
            }

            val autoActions = plan.autoActions
            if (!loading && autoActions.isEmpty()) {
                item(key = "__auto_empty") {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        text = stringResource(R.string.run_reset_auto_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(autoActions, key = { "auto_${it.kind.name}_${it.key}" }) { action ->
                AutoActionCard(
                    action = action,
                    checked = action.key in selected,
                    enabled = !running,
                    onToggle = {
                        selected = if (action.key in selected) {
                            selected - action.key
                        } else {
                            selected + action.key
                        }
                    },
                )
            }

            // ---------- 需人工处理 ----------
            val manualActions = plan.manualActions
            if (!loading && manualActions.isNotEmpty()) {
                item(key = "__manual_header") {
                    Text(
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                        text = stringResource(R.string.run_reset_manual_title, manualActions.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item(key = "__manual_desc") {
                    Text(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        text = stringResource(R.string.run_reset_manual_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(manualActions, key = { "manual_${it.kind.name}_${it.key}" }) { action ->
                    ManualActionCard(action)
                }
            }

            // ---------- 执行结果 ----------
            if (finished) {
                item(key = "__result") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    modifier = Modifier.padding(start = 6.dp),
                                    text = stringResource(
                                        R.string.run_reset_done,
                                        okCount,
                                        failures.size,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            failures.forEach { line ->
                                SelectionContainer {
                                    Text(
                                        modifier = Modifier.padding(top = 6.dp),
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 结束后右下角「返回」悬浮按钮（执行中不出现）
        if (finished && !running) {
            ExtendedFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 22.dp),
                onClick = onBack,
                icon = {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                },
                text = { Text(stringResource(R.string.run_reset_back)) },
            )
        }
    }

    if (askConfirm) {
        AlertDialog(
            onDismissRequest = { askConfirm = false },
            title = { Text(stringResource(R.string.run_reset_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.run_reset_confirm_desc, selected.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askConfirm = false
                        executeSelected()
                    },
                ) {
                    Text(stringResource(R.string.run_reset_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { askConfirm = false }) {
                    Text(stringResource(R.string.run_reset_confirm_cancel))
                }
            },
        )
    }
}

/** 可自动恢复的一条（设置项）：复选框 + 键名 + 恢复指令。 */
@Composable
private fun AutoActionCard(
    action: RestorePlanner.Action,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onToggle() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = action.display,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(R.string.run_reset_module_join, action.modules.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!action.revert.isNullOrBlank()) {
                    Text(
                        modifier = Modifier.padding(top = 2.dp),
                        text = action.revert,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 需人工处理的一条：只展示模块与原始指令。 */
@Composable
private fun ManualActionCard(action: RestorePlanner.Action) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = action.display,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    modifier = Modifier.padding(top = 2.dp),
                    text = stringResource(R.string.run_reset_module_join, action.modules.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 复制到剪贴板（本文件自带实现，避免依赖其它文件的 private 工具函数）。 */
private fun copyPromptToClipboard(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("AxManagerRunReset", text))
    }
}
