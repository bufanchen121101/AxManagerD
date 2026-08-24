package org.lsposed.lspatch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import frb.axeron.manager.R
import org.lsposed.lspatch.data.model.LogLine
import org.lsposed.lspatch.data.model.toSharedLogLevel
import org.matrix.vector.ui.logs.levelColor
import org.matrix.vector.ui.theme.LogLine as LogLineStyle

/**
 * The patcher's output, live -- the diagnostic, not the headline.
 *
 * This used to be the whole of what a patch looked like: a monospace console that scrolled by for
 * half a minute and then stopped, with nothing in it saying whether that was progress or a hang.
 * It is now something the reader opens when they want it, and what it says is worth reading
 * precisely because it is no longer the only thing on offer.
 *
 * Shaped on Vector's own installer log so a long-running job reads the same in both apps: the tail
 * is followed only while the reader is already at the tail. Lines do not wrap by default -- a
 * patcher line is mostly an absolute path, and wrapping turns one into four -- but [wrap] offers it
 * for the case a long message is easier read wrapped than scrolled.
 */
@Composable
fun PatchLog(
    lines: List<LogLine>,
    terminal: Boolean,
    wrap: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()

    // Whether the reader is sitting at the tail. Derived from the layout, with a two-item tolerance
    // so a line appended while at the bottom still counts as "at the bottom" during the frame before
    // the follow catches up -- without that slack, every append would read as a scroll-away and
    // following would stop after the first line.
    val atBottom by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }

    // Follows only while at the tail. Keyed on atBottom as well as the line count, so the moment the
    // reader scrolls up (atBottom -> false) the effect re-runs and does nothing, and every later line
    // is left where it is -- following resumes only when they scroll back down. scrollToItem, not
    // animateScrollToItem, so a fast stream never has an in-flight animation to fight the reader for
    // the scroll position.
    LaunchedEffect(lines.size, atBottom) {
        if (atBottom && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        // An empty box reads as "still working" at exactly the moment the reader needs to know it
        // stopped, so a finished job with nothing to show says so.
        if (lines.isEmpty() && terminal) {
            Text(
                text = stringResource(R.string.patch_log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            return@Box
        }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(lines) { line ->
                // Not wrapped by default: a patcher line is mostly an absolute path, and wrapping
                // turns one line into four. Wrapping is offered because the opposite case is real
                // too -- a long message read on a phone, where scrolling each line is worse.
                Text(
                    text = line.text,
                    style = LogLineStyle,
                    color = levelColor(line.level.toSharedLogLevel()),
                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                    modifier = if (wrap) Modifier
                    else Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
