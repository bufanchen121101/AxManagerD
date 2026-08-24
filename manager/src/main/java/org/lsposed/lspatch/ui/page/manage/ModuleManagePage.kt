package org.lsposed.lspatch.ui.page.manage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextOverflow
import frb.axeron.manager.R
import org.matrix.vector.ui.ActionDrawerHeader
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.LocalDialogLocalizer
import org.matrix.vector.ui.ApiBadge
import org.matrix.vector.ui.ModuleRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.REACH_ICON_SIZE
import org.lsposed.lspatch.ui.viewmodel.manage.ModuleManageViewModel
import org.lsposed.lspatch.util.LSPPackageManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleManageBody(query: String) {
    val context = LocalContext.current
    val viewModel = viewModel<ModuleManageViewModel>()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    if (viewModel.appList.isEmpty()) {
        val loading = LSPPackageManager.appList.isEmpty()
        PanelEmptyState(
            icon = if (loading) Icons.Rounded.HourglassEmpty else Icons.Rounded.Extension,
            text = stringResource(if (loading) R.string.manage_loading else R.string.manage_no_modules)
        )
    } else {
        val sheetState = rememberModalBottomSheetState()
        var sheetFor by remember { mutableStateOf<Pair<LSPPackageManager.AppInfo, ModuleManageViewModel.XposedInfo>?>(null) }
        val shown = viewModel.appList.filter {
            query.isBlank() || it.first.label.contains(query, true) || it.first.app.packageName.contains(query, true)
        }
        if (shown.isEmpty()) {
            PanelEmptyState(
                icon = Icons.Rounded.SearchOff,
                text = stringResource(R.string.manage_no_match)
            )
        } else PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    LSPPackageManager.fetchAppList()
                    refreshing = false
                }
            }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(
                    items = shown,
                    key = { it.first.app.packageName }
                ) {
                val item = it
                val reach = viewModel.appliesTo[item.first.app.packageName].orEmpty()
                val reachIcons = viewModel.appliesToIcons[item.first.app.packageName].orEmpty()
                ModuleRow(
                    name = item.first.label,
                    versionName = item.second.versionName,
                    description = item.second.description,
                    icon = {
                        Icon(
                            bitmap = LSPPackageManager.getIcon(item.first),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    apiBadge = { ApiBadge(label = "API", value = item.second.api.toString()) },
                    onClick = { sheetFor = item },
                    // The reach, the way Vector shows a module's scope: the patched apps that have this
                    // module enabled, as thumbnails then "+N". Handed to the row as data — the row
                    // positions it bottom-right itself, the same corner an app's reach lands in.
                    reachIcons =
                        reachIcons.map { bitmap ->
                            {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(REACH_ICON_SIZE),
                                )
                            }
                        },
                    reachCount = reach.size,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
                }
            }
        }

        sheetFor?.let { (appInfo, xposed) ->
            val packageName = appInfo.app.packageName
            val reach = viewModel.appliesTo[packageName].orEmpty()
            val settingsIntent = remember(packageName) { LSPPackageManager.getSettingsIntent(packageName) }
            ModalBottomSheet(
                onDismissRequest = { sheetFor = null },
                sheetState = sheetState
            ) {
                // The sheet is its own window, whose fresh Android composition locals drop the
                // in-app language override; re-applied here so it speaks the reader's language.
                LocalDialogLocalizer.current {
                    ActionDrawerHeader(
                        label = appInfo.label,
                        packageName = packageName,
                        icon = {
                            Icon(
                                bitmap = LSPPackageManager.getIcon(appInfo),
                                contentDescription = null,
                                tint = Color.Unspecified,
                            )
                        },
                        extraContent = {
                            if (xposed.description.isNotBlank()) {
                                Text(
                                    text = xposed.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = buildAnnotatedString {
                                    append(AnnotatedString("API", SpanStyle(color = MaterialTheme.colorScheme.secondary)))
                                    append("  ")
                                    append(xposed.api.toString())
                                    xposed.versionName.takeIf { it.isNotBlank() }?.let {
                                        append("   ·   v")
                                        append(it)
                                    }
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Serif,
                                style = MaterialTheme.typography.bodySmall
                            )
                            // What this module actually applies to — the apps that have it enabled — so
                            // the sheet answers "where does this run" instead of leaving it to be guessed.
                            Text(
                                text = buildAnnotatedString {
                                    append(
                                        AnnotatedString(
                                            stringResource(R.string.manage_module_applies_to),
                                            SpanStyle(color = MaterialTheme.colorScheme.secondary),
                                        )
                                    )
                                    append("  ")
                                    append(
                                        if (reach.isEmpty()) stringResource(R.string.manage_module_applies_none)
                                        else reach.joinToString(", ")
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    if (settingsIntent != null) {
                        ActionDrawerItem(
                            icon = Icons.Rounded.Settings,
                            title = stringResource(R.string.manage_module_settings),
                            subtitle = stringResource(R.string.manage_module_settings_desc)
                        ) {
                            sheetFor = null
                            context.startActivity(settingsIntent)
                        }
                }
                ActionDrawerItem(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.manage_app_info),
                    subtitle = stringResource(R.string.manage_app_info_desc)
                ) {
                    sheetFor = null
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    context.startActivity(intent)
                }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
