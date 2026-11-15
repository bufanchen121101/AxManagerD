package frb.axeron.manager.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import frb.axeron.manager.ai.CommandInspector
import frb.axeron.manager.ai.RunConflictAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冲突详情（页面内第三层视图）。
 *
 * ## 显示什么
 * - 顶部：冲突类型 + 目标全名（设置项显示 `namespace:key`，文件显示完整路径）
 * - 「当前系统值」：设置项走 `settings get` 读现值；文件走应用自身或特权 shell 读文件内容
 * - 每个模块一条：模块名 + 它执行过的指令 + 写入值；文件类可展开看**写入内容**（默认收起）
 * - 底部：选中「保留哪一条」后点「保存并执行」，执行的就是那一条原始指令
 *
 * ## 为什么执行用原始指令而不是"重新生成"
 * 这些指令是模块**真实跑过**的，带着模块自己的路径/参数；重写一遍反而容易走样。
 * 所以这里直接复用 [CommandInspector.apply]，与「指令详情」里执行方案走同一条特权通道。
 */
@Composable
fun ConflictDetailView(conflict: RunConflictAnalyzer.Conflict) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isFile = conflict.type == RunConflictAnalyzer.Type.FILE

    // 当前系统状态（设置现值 / 文件内容）
    var stateLoading by remember(conflict.key) { mutableStateOf(true) }
    var inspection by remember(conflict.key) { mutableStateOf<CommandInspector.Inspection?>(null) }

    // 选择保留哪一条：默认最后一条（= 最后写入者，也就是当前实际生效的那条）
    var selected by remember(conflict.key) {
        mutableStateOf((conflict.entries.size - 1).coerceAtLeast(0))
    }
    var expanded by remember(conflict.key) { mutableStateOf(-1) }
    var applying by remember(conflict.key) { mutableStateOf(false) }
    var askConfirm by remember(conflict.key) { mutableStateOf(false) }

    val selectedCommand = conflict.entries.getOrNull(selected)?.command.orEmpty()

    LaunchedEffect(conflict.key) {
        stateLoading = true
        inspection = withContext(Dispatchers.IO) {
            runCatching {
                val parsed = CommandInspector.parse(conflict.entries.firstOrNull()?.command.orEmpty())
                CommandInspector.inspect(context, parsed)
            }.getOrNull()
        }
        stateLoading = false
    }

    fun doApply() {
        if (selectedCommand.isBlank() || applying) return
        applying = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CommandInspector.apply(selectedCommand) }.getOrNull()
            }
            applying = false
            val ok = result?.ok == true
            val msg = if (ok) {
                context.getString(R.string.run_conflict_apply_success)
            } else {
                context.getString(
                    R.string.run_conflict_apply_failed,
                    result?.err?.take(120).orEmpty().ifBlank { result?.code?.toString().orEmpty() },
                )
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "__target") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(
                            if (isFile) {
                                R.string.run_conflict_type_file
                            } else {
                                R.string.run_conflict_type_settings
                            },
                            conflict.modules.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = conflict.key,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        itemsIndexed(conflict.entries, key = { index, _ -> "entry_$index" }) { index, entry ->
            val isExpanded = expanded == index
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = if (selected == index) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selected == index,
                            onClick = { selected = index },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.module,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            SelectionContainer {
                                Text(
                                    modifier = Modifier.padding(top = 2.dp),
                                    text = entry.command,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (!entry.value.isNullOrBlank()) {
                        Text(
                            modifier = Modifier.padding(start = 44.dp, top = 2.dp),
                            text = stringResource(R.string.run_conflict_written_value, entry.value.orEmpty()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 文件类：可收起框看这条指令到底往文件里写了什么
                    if (isFile) {
                        TextButton(
                            modifier = Modifier.padding(start = 34.dp),
                            onClick = { expanded = if (isExpanded) -1 else index },
                        ) {
                            Text(
                                text = stringResource(
                                    if (isExpanded) {
                                        R.string.run_conflict_collapse
                                    } else {
                                        R.string.run_conflict_expand
                                    }
                                ),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (isExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 44.dp, end = 4.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                            ) {
                                SelectionContainer {
                                    Text(
                                        modifier = Modifier.padding(8.dp),
                                        text = if (entry.value.isNullOrBlank()) {
                                            stringResource(R.string.run_conflict_no_inline)
                                        } else {
                                            entry.value
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 当前系统状态放在各模块条目**下方**：文件类显示当前文件内容，设置类显示当前值
        item(key = "__state") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = stringResource(R.string.run_conflict_now_value),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        stateLoading -> Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = stringResource(R.string.run_conflict_reading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        inspection == null -> Text(
                            modifier = Modifier.padding(top = 6.dp),
                            text = stringResource(R.string.run_conflict_state_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> {
                            val insp = inspection!!
                            if (isFile) {
                                val body = insp.fileContent
                                if (body.isNullOrEmpty()) {
                                    Text(
                                        modifier = Modifier.padding(top = 6.dp),
                                        text = stringResource(R.string.run_conflict_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                modifier = Modifier.padding(8.dp),
                                                text = body,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }
                                    }
                                    if (insp.fileTruncated) {
                                        Text(
                                            modifier = Modifier.padding(top = 4.dp),
                                            text = stringResource(R.string.run_conflict_truncated),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                val value = insp.currentValue
                                Text(
                                    modifier = Modifier.padding(top = 6.dp),
                                    text = when {
                                        value == null -> stringResource(R.string.run_conflict_empty)
                                        value.isBlank() -> stringResource(R.string.run_conflict_empty)
                                        else -> value
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                insp.extraLines.forEach { (k, v) ->
                                    Text(
                                        modifier = Modifier.padding(top = 2.dp),
                                        text = "$k = $v",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!insp.readable) {
                                Text(
                                    modifier = Modifier.padding(top = 4.dp),
                                    text = stringResource(R.string.run_conflict_unreadable),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "__apply") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedCommand.isNotBlank() && !applying,
                onClick = { askConfirm = true },
            ) {
                Text(
                    text = stringResource(R.string.run_conflict_apply),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }

    if (askConfirm) {
        AlertDialog(
            onDismissRequest = { askConfirm = false },
            title = { Text(stringResource(R.string.run_conflict_apply_title)) },
            text = {
                Text(
                    text = stringResource(R.string.run_conflict_apply_desc) + "\n\n" + selectedCommand,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askConfirm = false
                        doApply()
                    },
                ) {
                    Text(stringResource(R.string.run_conflict_apply_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { askConfirm = false }) {
                    Text(stringResource(R.string.run_conflict_apply_cancel))
                }
            },
        )
    }
}