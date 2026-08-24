package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.ShizukuOp
import org.lsposed.lspatch.util.ShizukuReason
import org.matrix.vector.ui.SharedAlertDialog

/**
 * The one place a caught Shizuku failure reaches the user.
 *
 * Hosted above the navigation rather than on a screen, because the operations that need Shizuku are spread across all
 * of them and a failure has to be legible wherever it happens. It says three things in order: what could not be done,
 * what would fix it, and — for a call the device itself rejected — the trace, handed to the shared trace screen through
 * [onViewTrace] so a reader can copy it into a report. That trace matters more here than anywhere else: without Shizuku
 * the log screen has nothing to show, so this dialog is the only account of what went wrong.
 *
 * Shown once per distinct problem (see `ShizukuApi.record`), so a background poll that keeps hitting the same wall does
 * not keep interrupting.
 */
@Composable
fun ShizukuFailureDialog(onViewTrace: (String) -> Unit) {
    val failure = ShizukuApi.lastFailure ?: return

    val title =
        when (failure.reason) {
            ShizukuReason.NotRunning -> R.string.shizuku_failure_not_running
            ShizukuReason.ConnectionLost -> R.string.shizuku_failure_lost
            ShizukuReason.NotGranted -> R.string.shizuku_failure_not_granted
            ShizukuReason.ServiceUnavailable -> R.string.shizuku_failure_no_service
            ShizukuReason.CallFailed -> R.string.shizuku_failure_call
        }
    val hint =
        when (failure.reason) {
            ShizukuReason.NotRunning -> R.string.shizuku_failure_hint_not_running
            ShizukuReason.ConnectionLost -> R.string.shizuku_failure_hint_lost
            ShizukuReason.NotGranted -> R.string.shizuku_failure_hint_not_granted
            ShizukuReason.ServiceUnavailable -> R.string.shizuku_failure_hint_no_service
            ShizukuReason.CallFailed -> R.string.shizuku_failure_hint_call
        }
    val subject =
        when (failure.op) {
            ShizukuOp.Grant -> R.string.shizuku_failure_op_grant
            ShizukuOp.Install -> R.string.shizuku_failure_op_install
            ShizukuOp.Uninstall -> R.string.shizuku_failure_op_uninstall
            ShizukuOp.PackageQuery -> R.string.shizuku_failure_op_packages
            ShizukuOp.Logs -> R.string.shizuku_failure_op_logs
            ShizukuOp.Shell -> R.string.shizuku_failure_op_shell
            ShizukuOp.Optimize -> R.string.shizuku_failure_op_optimize
        }

    // Offered only when there is something to ask: with Shizuku not running there is nobody to grant.
    val canGrant = failure.reason == ShizukuReason.NotGranted && ShizukuApi.isBinderAvailable
    val trace = failure.trace

    SharedAlertDialog(
        onDismissRequest = { ShizukuApi.dismissFailure() },
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(subject), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                // The raw reason, kept verbatim: it is the part a report has to carry, and
                // rephrasing it would lose exactly what distinguishes one device's refusal from
                // another's.
                Text(
                    failure.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (canGrant) {
                TextButton(
                    onClick = {
                        ShizukuApi.dismissFailure()
                        ShizukuApi.requestPermission()
                    }
                ) {
                    Text(stringResource(R.string.shizuku_failure_grant))
                }
            } else {
                TextButton(onClick = { ShizukuApi.dismissFailure() }) {
                    Text(stringResource(R.string.shizuku_failure_dismiss))
                }
            }
        },
        dismissButton =
            trace?.let {
                {
                    TextButton(
                        onClick = {
                            ShizukuApi.dismissFailure()
                            onViewTrace(it)
                        }
                    ) {
                        Text(stringResource(R.string.shizuku_failure_view_trace))
                    }
                }
            },
    )
}
