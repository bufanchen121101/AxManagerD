package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import org.lsposed.lspatch.data.model.PatchStep

/**
 * A patch that is happening somewhere else.
 *
 * The job outlives the screen it was started from, so leaving the patch screen has to leave
 * something behind that says so -- otherwise walking away looks exactly like cancelling. Absent
 * entirely when there is nothing to report, so it never becomes a permanent strip that is read once
 * and then ignored.
 */
@Composable
fun PatchProgressLine(step: PatchStep, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (text, icon) = when (step) {
        is PatchStep.Preparing -> stringResource(R.string.patch_progress_running, step.request.label) to null
        is PatchStep.Running -> stringResource(R.string.patch_progress_running, step.request.label) to null
        is PatchStep.Patched -> stringResource(R.string.patch_progress_done, step.request.label) to Icons.Rounded.CheckCircle
        is PatchStep.NeedsUninstall -> stringResource(R.string.patch_progress_done, step.request.label) to Icons.Rounded.CheckCircle
        is PatchStep.Installing -> stringResource(R.string.patch_installing) to null
        is PatchStep.Confirming -> stringResource(R.string.patch_confirming) to null
        is PatchStep.Uninstalling -> stringResource(R.string.uninstalling) to null
        is PatchStep.Restoring -> stringResource(R.string.manage_restore_running_line, step.label) to null
        is PatchStep.Done -> stringResource(R.string.patch_progress_done, step.label) to Icons.Rounded.CheckCircle
        is PatchStep.Failed -> stringResource(R.string.patch_progress_failed, step.label) to Icons.Rounded.ErrorOutline
        PatchStep.Idle -> return
    }
    val failed = step is PatchStep.Failed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (failed) colors.errorContainer.copy(alpha = 0.6f)
                else colors.primary.copy(alpha = 0.09f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (failed) colors.error else colors.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) colors.onErrorContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
