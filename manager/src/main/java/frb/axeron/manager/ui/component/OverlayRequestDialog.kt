package frb.axeron.manager.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import frb.axeron.manager.ui.icon.AxeronIcons

/**
 * 模块核心文件修改权限 —— 申请弹窗。
 *
 * 模块通过 `axoverlay request --reason="..."` 写入 pending 记录后，
 * App 侧监听并弹出本对话框，由用户三选一：
 *
 *  - 拒绝        -> GrantMode.DENIED（写空 = 删除授权文件）
 *  - 仅本次      -> GrantMode.ONCE（带有效期，见 ONCE_SESSION_MS）
 *  - 始终允许    -> GrantMode.ALWAYS
 *
 * 视觉与免责声明弹窗保持一致（无棕色背景图标 + 错误色警示）。
 *
 * @param moduleId 申请模块 id（= runtime_plugins 下的目录名）
 * @param reason 模块给出的申请理由；空串时隐藏该行
 * @param caps 模块声明/申请的能力标签；空时隐藏该行
 * @param onDecide 用户决定；参数为 null 表示「拒绝」
 * @param onDismiss 用户关闭弹窗（等同拒绝，但不写记录）
 */
@Composable
fun OverlayRequestDialog(
    moduleId: String,
    reason: String,
    caps: List<String> = emptyList(),
    onDecide: (allow: Boolean, always: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = AxeronIcons.AxeronMark,
                contentDescription = null,
                tint = AxeronIcons.DEFAULT_TINT,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.overlay_request_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = moduleId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.overlay_request_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (reason.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.overlay_request_reason),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (caps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = caps.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onDecide(false, false) }) {
                    Text(
                        text = stringResource(R.string.overlay_deny),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = { onDecide(true, false) }) {
                    Text(stringResource(R.string.overlay_allow_once))
                }
                TextButton(onClick = { onDecide(true, true) }) {
                    Text(
                        text = stringResource(R.string.overlay_allow_always),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}