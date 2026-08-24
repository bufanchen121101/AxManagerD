package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import org.lsposed.lspatch.data.model.LogLine
import org.lsposed.lspatch.data.model.PatchStage
import org.lsposed.lspatch.data.model.PatchStep
import org.matrix.vector.ui.theme.LogLine as LogLineStyle

/** The row height every step keeps whatever state it is in, so a state flip cannot resize the list. */
private val ROW_HEIGHT = 48.dp

/** Where a step has got to. Kept local: nothing outside this list needs to name these. */
private enum class StepState { Pending, Active, Done, Failed }

/**
 * What the patcher is doing, as a list of steps rather than a wall of log lines.
 *
 * The steps come from a signal the patcher emits itself, not from matching its log text: the log is
 * English prose that will be reworded, and the split-apk path short-circuits before most of it is
 * ever printed.
 *
 * "Writing & signing" is the one that matters. Deflation, realignment and signing all happen while
 * the output zip is being closed, and nothing is printed for tens of seconds -- which is exactly the
 * stretch that made the old console look frozen. It is shown last, marked as the longest step, and
 * left explicitly indeterminate rather than being given a fake percentage.
 */
@Composable
fun PatchStepList(
    step: PatchStep,
    modules: List<String>,
    lines: List<LogLine> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Which step the reader has opened. One at a time: these are read to answer "what happened
    // there", and several open at once turns the list back into the wall of text it replaced.
    var opened by rememberSaveable { mutableStateOf<PatchStage?>(null) }
    val running = step as? PatchStep.Running
    val failed = step is PatchStep.Failed
    val finished = step is PatchStep.Patched || step is PatchStep.Installing ||
        step is PatchStep.Done || step is PatchStep.NeedsUninstall ||
        step is PatchStep.Confirming || step is PatchStep.Uninstalling

    // Only the stages this patch will actually pass through: a split apk is copied through, never
    // rewritten, and showing steps that will never run reads as a stall once they stay pending.
    val packingSplit = running?.stage == PatchStage.PackingSplit
    val stages = buildList {
        add(PatchStage.ReadingApk)
        add(PatchStage.SigningSetup)
        if (packingSplit) add(PatchStage.PackingSplit)
        add(PatchStage.RewritingManifest)
        add(PatchStage.InjectingLoader)
        if (modules.isNotEmpty()) add(PatchStage.EmbeddingModules)
        add(PatchStage.WritingAndSigning)
    }

    Column(modifier.fillMaxWidth()) {
        stages.forEach { stage ->
            val state = when {
                finished -> StepState.Done
                running == null -> StepState.Pending
                failed && running.stage == stage -> StepState.Failed
                running.stage == stage -> StepState.Active
                // A stage never signalled stays pending rather than blocking the ones after it:
                // the outcome arrives from the runner, not from the last stage seen.
                stages.indexOf(stage) < stages.indexOf(running.stage) -> StepState.Done
                else -> StepState.Pending
            }
            val stageLines = lines.filter { it.stage == stage }
            StepRow(
                label = stringResource(stage.labelRes()),
                detail = when {
                    stage == PatchStage.WritingAndSigning && state == StepState.Active ->
                        stringResource(R.string.patch_stage_writing_note)
                    state == StepState.Active && running != null && running.apkCount > 1 ->
                        stringResource(R.string.patch_stage_apk_progress, running.apkIndex, running.apkCount)
                    else -> null
                },
                state = state,
                // Only a step that actually said something is worth opening; the rest stay inert
                // rather than rewarding a tap with an empty box.
                lineCount = stageLines.size,
                expanded = opened == stage,
                onClick = if (stageLines.isEmpty()) null else {
                    { opened = if (opened == stage) null else stage }
                },
            )
            if (opened == stage && stageLines.isNotEmpty()) {
                StageLines(stageLines)
            }
            if (stage == PatchStage.EmbeddingModules && state != StepState.Pending) {
                modules.forEach { module ->
                    StepRow(
                        label = module,
                        detail = null,
                        state = if (state == StepState.Active) StepState.Pending else StepState.Done,
                        indent = true,
                    )
                }
            }
        }
    }
}

private fun PatchStage.labelRes(): Int = when (this) {
    PatchStage.ReadingApk -> R.string.patch_stage_reading
    PatchStage.SigningSetup -> R.string.patch_stage_signing
    PatchStage.RewritingManifest -> R.string.patch_stage_rewriting
    PatchStage.InjectingLoader -> R.string.patch_stage_injecting
    PatchStage.EmbeddingModules -> R.string.patch_stage_embedding
    PatchStage.PackingSplit -> R.string.patch_stage_packing_split
    PatchStage.WritingAndSigning -> R.string.patch_stage_writing
    PatchStage.Finished -> R.string.patch_patched
}

/**
 * The lines one step produced -- a readable peek, not the raw firehose.
 *
 * Deliberately quieter than the full log. No per-line timestamp (that is for the report, where the
 * question is where the time went); debug lines recede so the info and error lines that say what
 * actually happened carry the eye; and everything wraps, since there is no horizontal room left
 * after the indent to scroll a line within.
 */
@Composable
private fun StageLines(lines: List<LogLine>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        lines.forEach { line ->
            val debug = line.level == android.util.Log.DEBUG || line.level == android.util.Log.VERBOSE
            Text(
                text = line.text,
                style = LogLineStyle,
                color = when {
                    line.level == android.util.Log.ERROR -> MaterialTheme.colorScheme.error
                    debug -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun StepRow(
    label: String,
    detail: String?,
    state: StepState,
    indent: Boolean = false,
    lineCount: Int = 0,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (indent) 32.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Pending ->
                    Box(
                        Modifier
                            .size(if (indent) 14.dp else 18.dp)
                            .border(1.5.dp, colors.outlineVariant, CircleShape)
                    )
                StepState.Active ->
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                StepState.Done ->
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(if (indent) 16.dp else 20.dp),
                    )
                StepState.Failed ->
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(20.dp),
                    )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = if (indent) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (state == StepState.Active) FontWeight.SemiBold else FontWeight.Normal,
                color = when (state) {
                    StepState.Pending -> colors.onSurfaceVariant
                    StepState.Failed -> colors.error
                    else -> colors.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            // The count is the invitation: it says both that there is something behind the row and
            // roughly how much, so opening it is a decision rather than a probe.
            Text(
                text = lineCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f),
            )
        }
    }
}
