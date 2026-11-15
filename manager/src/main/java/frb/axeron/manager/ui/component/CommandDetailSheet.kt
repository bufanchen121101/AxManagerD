package frb.axeron.manager.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import frb.axeron.manager.ai.CommandInspector
import kotlinx.coroutines.launch

/**
 * 指令详情悬浮层（从顶部滑入）。
 *
 * ## 它解决什么
 * 运行记录仪里的每条指令都是模块**真实跑过**的写操作，但日志里只有一行命令，
 * 看不出它改了什么、现在是什么值、怎么改回去。这个悬浮层把一条指令摊成四段：
 *
 * 1. **指令本身**：原文（等宽、可选中）+ 归一化后的分类标签与一句话摘要；
 * 2. **目标现场**：这条指令改的那个「键 / 属性 / 文件 / 包 / 屏幕参数」现在的真实值；
 *    文件类还会列出文件内容，并把「这条指令写入的那部分」所在行高亮出来；
 * 3. **修改方案**：由 [CommandInspector.buildSchemes] 按指令族生成的全部可选方案
 *    （恢复原值、回落默认、反向切换、删除、恢复安装…），另外允许自己填值/改内容；
 * 4. **执行**：选方案 →点「运行」真正下发（危险方案二次确认）；想先自己验证就「复制命令」。
 *
 * ## 边界
 * 本组件只负责展示与收集用户意图：解析、读现场、拼命令、执行全在 [CommandInspector]，
 * 且所有特权调用都 `skipAnalyzer = true`（内部工具命令不能再进 AI 拦截挂钩）。
 *
 * @param raw 抓取日志里的指令原文
 * @param onDismiss 请求关闭（下滑、点遮罩、点关闭、系统返回键都会走到这里）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandDetailSheet(
    raw: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 归一化结果只依赖原文：切方案、执行、刷新现场都不需要重新解析
    val parsed = remember(raw) { CommandInspector.parse(raw) }

    var loading by remember(raw) { mutableStateOf(true) }
    var inspection by remember(raw) { mutableStateOf<CommandInspector.Inspection?>(null) }
    var schemes by remember(raw) { mutableStateOf<List<CommandInspector.Scheme>>(emptyList()) }
    var selectedId by remember(raw) { mutableStateOf<String?>(null) }
    var custom by remember(raw) { mutableStateOf("") }
    var editorTouched by remember(raw) { mutableStateOf(false) }
    var running by remember(raw) { mutableStateOf(false) }
    var result by remember(raw) { mutableStateOf<CommandInspector.ApplyResult?>(null) }
    var pendingDanger by remember(raw) { mutableStateOf<CommandInspector.Scheme?>(null) }

    /** 重新读取目标现场（打开时、以及执行成功后都会走这里）。 */
    suspend fun reload() {
        loading = true
        val ins = CommandInspector.inspect(context, parsed)
        inspection = ins
        schemes = CommandInspector.buildSchemes(parsed, ins)
        // 默认选中「恢复类」方案：打开这个界面的第一诉求通常是「它改了什么、怎么回去」
        selectedId = schemes.firstOrNull { it.tone == CommandInspector.Scheme.Tone.RECOVER }?.id
            ?: schemes.firstOrNull()?.id
        if (!editorTouched) custom = initialEditorText(parsed, ins)
        loading = false
    }

    LaunchedEffect(raw) { reload() }

    // ---- 派生值（必须在 @Composable 作用域里算好，LazyColumn 的 DSL 里不能调 remember） ----
    val customCommand = if (custom.isBlank()) {
        null
    } else {
        CommandInspector.customCommand(parsed, custom)
    }
    val supportsCustom = remember(parsed) { CommandInspector.customCommand(parsed, "x") != null }
    val selected = schemes.firstOrNull { it.id == selectedId }
    val effectiveCommand: String? = if (selectedId == CUSTOM_ID) customCommand else selected?.command
    val effectiveLabel = if (selectedId == CUSTOM_ID) {
        stringResource(R.string.cmd_sheet_custom)
    } else {
        selected?.label.orEmpty()
    }
    val copiedText = stringResource(R.string.cmd_sheet_copied)
    val noOutputText = stringResource(R.string.cmd_sheet_no_output)

    // 【v1.5.8 需求①】把「读到的当前值」和「这条指令要写入的值」比一次，先给结论：
    // 已生效 / 未生效。用户不用自己对着两个值猜，也不用手动再去跑一遍验证。
    val appliedState = remember(inspection, parsed) {
        val ins = inspection
        if (ins == null) {
            CommandInspector.AppliedState.UNKNOWN
        } else {
            CommandInspector.judgeApplied(parsed, ins)
        }
    }

    val fileContent = inspection?.fileContent
    val fileLines = remember(fileContent) { fileContent?.split("\n").orEmpty() }
    val visibleLines = remember(fileLines) {
        if (fileLines.size > MAX_FILE_LINES) fileLines.subList(0, MAX_FILE_LINES) else fileLines
    }
    val matchedLineNos = remember(inspection?.matched) {
        inspection?.matched?.map { it.lineNo }?.toSet().orEmpty()
    }

    fun runNow(command: String) {
        if (running || command.isBlank()) return
        scope.launch {
            running = true
            result = null
            val r = CommandInspector.apply(command)
            running = false
            result = r
            if (r.ok) {
                // 写完立刻重新读现场：让用户看到「现在的值」已经变成新的
                editorTouched = false
                reload()
            }
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding(),
        ) {
            // ---------------- 顶部：分类 + 摘要 + 动作 ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = parsed.kind.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = parsed.summary.ifBlank { raw },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { scope.launch { reload() } }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cmd_sheet_reload),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = null)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ---------------- 中部：可滚动的各段内容 ----------------
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // ① 指令原文
                item(key = "raw-title") {
                    CmdSectionTitle(stringResource(R.string.cmd_sheet_raw))
                }
                item(key = "raw") {
                    CmdValueBlock(raw)
                }

                // ② 目标现场
                item(key = "now-title") {
                    CmdSectionTitle(stringResource(R.string.cmd_sheet_now))
                }
                item(key = "now") {
                    if (loading) {
                        CmdHintText(stringResource(R.string.cmd_sheet_loading))
                    } else {
                        val ins = inspection
                        val currentValue = ins?.currentValue
                        val note = ins?.note
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // 【v1.5.8 需求①】结论放最上面：这条指令到底有没有生效
                            when (appliedState) {
                                CommandInspector.AppliedState.APPLIED -> CmdVerdict(
                                    ok = true,
                                    text = stringResource(R.string.cmd_sheet_verdict_ok),
                                    hint = stringResource(R.string.cmd_sheet_verdict_hint),
                                )

                                CommandInspector.AppliedState.NOT_APPLIED -> CmdVerdict(
                                    ok = false,
                                    text = stringResource(R.string.cmd_sheet_verdict_not),
                                )

                                CommandInspector.AppliedState.UNKNOWN -> Unit
                            }
                            when {
                                currentValue != null -> CmdValueBlock(currentValue)
                                !note.isNullOrBlank() -> CmdHintText(note)
                                else -> CmdHintText(stringResource(R.string.cmd_sheet_no_value))
                            }
                            ins?.extraLines?.forEach { line ->
                                CmdInfoRow(line.first, line.second)
                            }
                            val written = parsed.value
                            if (!written.isNullOrBlank()) {
                                CmdInfoRow(
                                    stringResource(R.string.cmd_sheet_written),
                                    written,
                                )
                            }
                        }
                    }
                }

                // ③ 文件内容（只有文件类指令才有现场内容）
                if (fileContent != null) {
                    item(key = "file-title") {
                        CmdSectionTitle(
                            title = stringResource(R.string.cmd_sheet_file),
                            trailing = if (matchedLineNos.isEmpty()) {
                                null
                            } else {
                                stringResource(
                                    R.string.cmd_sheet_file_match,
                                    matchedLineNos.size,
                                )
                            },
                        )
                    }
                    itemsIndexed(
                        items = visibleLines,
                        key = { index, _ -> "line-$index" },
                    ) { index, line ->
                        CmdFileLine(
                            lineNo = index + 1,
                            text = line,
                            matched = matchedLineNos.contains(index + 1),
                        )
                    }
                    if (fileLines.size > MAX_FILE_LINES) {
                        item(key = "file-truncated") {
                            CmdHintText(
                                stringResource(
                                    R.string.cmd_sheet_file_truncated,
                                    MAX_FILE_LINES,
                                ),
                            )
                        }
                    }
                    if (matchedLineNos.isEmpty() && !parsed.value.isNullOrBlank()) {
                        item(key = "file-no-match") {
                            CmdHintText(stringResource(R.string.cmd_sheet_file_no_match))
                        }
                    }
                }

                // ④ 修改方案
                item(key = "scheme-title") {
                    CmdSectionTitle(
                        title = stringResource(R.string.cmd_sheet_schemes),
                        trailing = stringResource(R.string.cmd_sheet_scheme_count, schemes.size),
                    )
                }
                if (!loading && schemes.isEmpty()) {
                    item(key = "scheme-empty") {
                        CmdHintText(stringResource(R.string.cmd_sheet_no_scheme))
                    }
                }
                itemsIndexed(
                    items = schemes,
                    key = { _, scheme -> "scheme-${scheme.id}" },
                ) { _, scheme ->
                    CmdSchemeRow(
                        scheme = scheme,
                        selected = selectedId == scheme.id,
                        onSelect = { selectedId = scheme.id },
                    )
                }

                // ⑤ 自定义：改值 / 改内容（文件类改的就是整份内容）
                if (supportsCustom) {
                    item(key = "custom-title") {
                        CmdSectionTitle(CommandInspector.customLabel(parsed))
                    }
                    item(key = "custom") {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = custom,
                            onValueChange = { text ->
                                custom = text
                                editorTouched = true
                                // 用户一旦动手编辑，就默认他要执行「自己写的这份」
                                val cmd = CommandInspector.customCommand(parsed, text)
                                if (cmd != null && text.isNotBlank()) selectedId = CUSTOM_ID
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            minLines = if (isContentEditor(parsed.kind)) 6 else 1,
                            maxLines = if (isContentEditor(parsed.kind)) 14 else 4,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.cmd_sheet_custom_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                        )
                    }
                    val customGenerated = customCommand
                    if (customGenerated != null && selectedId == CUSTOM_ID) {
                        item(key = "custom-cmd") {
                            CmdInfoRow(
                                stringResource(R.string.cmd_sheet_generated),
                                customGenerated,
                            )
                        }
                    }
                }

                // ⑥ 执行结果
                val applied = result
                if (applied != null) {
                    item(key = "result-title") {
                        CmdSectionTitle(stringResource(R.string.cmd_sheet_result))
                    }
                    item(key = "result") {
                        CmdResultBlock(applied, noOutputText)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ---------------- 底部：提示 + 运行 / 复制 ----------------
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = when {
                            running -> stringResource(R.string.cmd_sheet_running)
                            effectiveCommand.isNullOrBlank() ->
                                stringResource(R.string.cmd_sheet_hint_pick)

                            else -> stringResource(
                                R.string.cmd_sheet_hint_ready,
                                effectiveLabel,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            enabled = !effectiveCommand.isNullOrBlank(),
                            onClick = {
                                val cmd = effectiveCommand
                                if (!cmd.isNullOrBlank()) {
                                    copyToClipboard(context, cmd)
                                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cmd_sheet_copy))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            enabled = !effectiveCommand.isNullOrBlank() && !running,
                            onClick = {
                                val cmd = effectiveCommand
                                if (!cmd.isNullOrBlank()) {
                                    val danger = selected?.tone ==
                                        CommandInspector.Scheme.Tone.DANGER
                                    if (danger) pendingDanger = selected else runNow(cmd)
                                }
                            },
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.cmd_sheet_run))
                        }
                    }
                }
            }
        }
    }

    // 危险方案二次确认（破坏性操作必须显式点头）
    val danger = pendingDanger
    if (danger != null) {
        AlertDialog(
            onDismissRequest = { pendingDanger = null },
            title = { Text(stringResource(R.string.cmd_sheet_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.cmd_sheet_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CmdValueBlock(danger.command)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cmd = danger.command
                        pendingDanger = null
                        runNow(cmd)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.cmd_sheet_confirm_ok),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDanger = null }) {
                    Text(stringResource(R.string.cmd_sheet_cancel))
                }
            },
        )
    }
}

/** 「自定义」这一项的伪方案 id（真实方案 id 由 [CommandInspector] 产生）。 */
private const val CUSTOM_ID = "__custom__"

/** 文件内容最多显示这么多行（Compose 逐行渲染，超长文件不能全塞进去）。 */
private const val MAX_FILE_LINES = 400

/** 这些指令族的「自定义」编辑的是整份文件内容，需要多行输入框。 */
private fun isContentEditor(kind: CommandInspector.Kind): Boolean = when (kind) {
    CommandInspector.Kind.FILE_WRITE,
    CommandInspector.Kind.FILE_APPEND,
    CommandInspector.Kind.FILE_COPY,
    CommandInspector.Kind.FILE_MOVE,
    -> true

    else -> false
}

/** 自定义编辑区的初值：尽量给「当前值 / 当前内容」，让用户在这个基础上改。 */
private fun initialEditorText(
    parsed: CommandInspector.Parsed,
    ins: CommandInspector.Inspection,
): String = when (parsed.kind) {
    CommandInspector.Kind.FILE_WRITE,
    CommandInspector.Kind.FILE_APPEND,
    -> ins.fileContent.orEmpty()

    CommandInspector.Kind.FILE_PERM ->
        ins.extraLines.firstOrNull { it.first == "权限" }?.second.orEmpty()

    CommandInspector.Kind.FILE_OWNER ->
        ins.extraLines.firstOrNull { it.first == "属主" }?.second.orEmpty()

    CommandInspector.Kind.SETTINGS,
    CommandInspector.Kind.PROP,
    CommandInspector.Kind.WINDOW,
    CommandInspector.Kind.APP_OPS,
    -> ins.currentValue.orEmpty()

    // 【v1.5.5】cmd 内部服务：初值只给「子命令 + 参数」，前面会自动补 `cmd <服务>`
    CommandInspector.Kind.CMD_RAW ->
        parsed.op.split(' ').drop(1).joinToString(" ").ifBlank { "" }

    // 【v1.5.5】其余指令（含只读 / 未归类）：初值就是原命令，改完即可执行
    else -> parsed.raw
}

/** 小节标题：左侧文字 + 可选右侧计数。 */
@Composable
private fun CmdSectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 等宽值块：用于指令原文、当前值、生成的命令（可选中复制）。 */
@Composable
private fun CmdValueBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        SelectionContainer {
            Text(
                modifier = Modifier.padding(10.dp),
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 键值行：键固定宽度，值等宽并允许换行。 */
@Composable
private fun CmdInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            modifier = Modifier.width(84.dp),
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            modifier = Modifier.weight(1f),
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 【v1.5.8 需求①】生效判定条：绿色 = 已生效，红色 = 未生效。
 *
 * 放在「目标现场」最上面，让用户先看到结论（这条指令到底改成功没有），
 * 再往下看当前值、文件内容与恢复方案。
 */
@Composable
private fun CmdVerdict(ok: Boolean, text: String, hint: String? = null) {
    val container = if (ok) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (ok) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = container,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
            }
            if (!hint.isNullOrBlank()) {
                Text(
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                )
            }
        }
    }
}

/** 文件的一行：行号 + 标记条 + 内容；命中行整行高亮。 */
@Composable
private fun CmdFileLine(lineNo: Int, text: String, matched: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (matched) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            modifier = Modifier.width(40.dp),
            text = lineNo.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            color = if (matched) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Spacer(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(
                    if (matched) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (matched) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 方案行：单选样式；恢复类用 secondary 容器，危险类用 error 容器，选中项加描边。 */
@Composable
private fun CmdSchemeRow(
    scheme: CommandInspector.Scheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val tone = scheme.tone
    val container = when (tone) {
        CommandInspector.Scheme.Tone.RECOVER ->
            MaterialTheme.colorScheme.secondaryContainer

        CommandInspector.Scheme.Tone.DANGER ->
            MaterialTheme.colorScheme.errorContainer

        CommandInspector.Scheme.Tone.NEUTRAL ->
            MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val titleColor = if (tone == CommandInspector.Scheme.Tone.DANGER) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        BorderStroke(
            width = 2.dp,
            color = if (tone == CommandInspector.Scheme.Tone.DANGER) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    } else {
        null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .heightIn(min = 48.dp)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = scheme.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
                if (!scheme.hint.isNullOrBlank()) {
                    Text(
                        text = scheme.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = scheme.command,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 执行结果块：成功 / 失败用不同容器色，输出等宽展示。 */
@Composable
private fun CmdResultBlock(result: CommandInspector.ApplyResult, noOutputText: String) {
    val ok = result.ok
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (ok) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (ok) {
                    stringResource(R.string.cmd_sheet_result_ok, result.code)
                } else {
                    stringResource(R.string.cmd_sheet_result_fail, result.code)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (ok) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
            )
            val body = buildString {
                if (result.out.isNotBlank()) append(result.out.trimEnd())
                if (result.err.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(result.err.trimEnd())
                }
            }
            SelectionContainer {
                Text(
                    text = body.ifBlank { noOutputText },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (ok) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
        }
    }
}

/** 说明性小字。 */
@Composable
private fun CmdHintText(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 复制到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AxManagerCommandInspector", text))
    }
}