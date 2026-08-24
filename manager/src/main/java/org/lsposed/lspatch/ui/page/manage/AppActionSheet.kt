package org.lsposed.lspatch.ui.page.manage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import frb.axeron.manager.R
import org.lsposed.lspatch.lspApp
import frb.axeron.manager.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.ActionDrawerHeader
import org.matrix.vector.ui.ActionDrawerItem
import org.matrix.vector.ui.LocalDialogLocalizer

/**
 * The quick actions for a patched app, on long press.
 *
 * Deliberately only the acts that concern the app as the *operating system* sees it -- launch it,
 * stop it, inspect it, recompile it, remove it. Everything about the patch itself (its modules, its
 * mode, re-patching, restoring the original) belongs to the detail page, where there is room to say
 * what each one will do. Keeping both kinds here is what made the old sheet a menu that had to be
 * read rather than a set of things to press.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppActionSheet(app: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val viewModel = viewModel<AppManageViewModel>()

    val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
    val forceStopped = stringResource(R.string.manage_forcestop_done)
    val uninstalled = stringResource(R.string.manage_uninstall_successfully)

    val uninstallLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // The application scope, not the sheet's: the sheet is gone by the time this lands.
                lspApp.globalScope.launch {
                    LSPPackageManager.fetchAppList()
                    snackbarHost.showSnackbar(uninstalled)
                }
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // A sheet is its own window, and a new window gets a fresh set of Android composition
        // locals taken from that window's context -- which drops the in-app language override on
        // the way in. Without this the sheet speaks the phone's language while the screen behind it
        // speaks the reader's. The shared sheets all do the same.
        LocalDialogLocalizer.current {
            ActionDrawerHeader(
                label = app.label,
                packageName = app.app.packageName,
                icon = {
                    Icon(
                        bitmap = LSPPackageManager.getIcon(app),
                        contentDescription = null,
                        tint = Color.Unspecified,
                    )
                },
            )
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

            val launchIntent = remember(app.app.packageName) {
                LSPPackageManager.getLaunchIntentForPackage(app.app.packageName)
            }
            if (launchIntent != null) {
                ActionDrawerItem(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = stringResource(R.string.manage_open),
                    subtitle = stringResource(R.string.manage_open_desc),
                ) {
                    onDismiss()
                    runCatching { context.startActivity(launchIntent) }
                }
            }
            ActionDrawerItem(
                icon = Icons.Rounded.Stop,
                title = stringResource(R.string.manage_forcestop),
                subtitle = stringResource(R.string.manage_forcestop_desc),
            ) {
                onDismiss()
                // Launched on the application scope. Work started from a `rememberCoroutineScope` here
                // is cancelled the instant the sheet leaves the composition, which it does immediately.
                lspApp.globalScope.launch {
                    if (!ShizukuApi.isPermissionGranted) {
                        snackbarHost.showSnackbar(shizukuUnavailable)
                    } else {
                        ShizukuApi.runShellCommand("am force-stop ${app.app.packageName}")
                        snackbarHost.showSnackbar(forceStopped)
                    }
                }
            }
            ActionDrawerItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.manage_app_info),
                subtitle = stringResource(R.string.manage_info_desc),
            ) {
                onDismiss()
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", app.app.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            ActionDrawerItem(
                icon = Icons.Rounded.Bolt,
                title = stringResource(R.string.manage_optimize),
                subtitle = stringResource(R.string.manage_optimize_desc),
                tint = MaterialTheme.colorScheme.primary,
            ) {
                onDismiss()
                scope.launch {
                    if (!ShizukuApi.isPermissionGranted) {
                        snackbarHost.showSnackbar(shizukuUnavailable)
                    } else {
                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(app))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ActionDrawerItem(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.uninstall),
                subtitle = stringResource(R.string.manage_uninstall_desc),
                tint = MaterialTheme.colorScheme.error,
            ) {
                onDismiss()
                uninstallLauncher.launch(
                    Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:${app.app.packageName}")
                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
