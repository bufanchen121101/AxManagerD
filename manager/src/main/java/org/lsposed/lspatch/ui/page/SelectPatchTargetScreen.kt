package org.lsposed.lspatch.ui.page

import android.content.pm.PackageInstaller
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import frb.axeron.manager.R
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.PatchOrigin
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.data.repository.PatchRequestStore
import com.ramcosta.composedestinations.generated.destinations.AppDetailScreenDestination
import com.ramcosta.composedestinations.generated.destinations.NewPatchScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SelectPatchTargetScreenDestination
import frb.axeron.manager.ui.util.LocalSnackbarHost
import org.lsposed.lspatch.ui.viewmodel.SelectPatchTargetViewModel
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.matrix.vector.ui.PackageRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.SearchField

// Plain apks plus app bundles: .xapk/.apks/.apkm carry no registered mime, so most file providers
// report them as zip or octet-stream — both are allowed so a bundle can be picked and unzipped.
internal val APK_AND_BUNDLE_TYPES =
    arrayOf(
        "application/vnd.android.package-archive",
        "application/zip",
        "application/octet-stream",
    )

/**
 * What to patch -- the single entry into the patch flow, from Home and from Manage alike.
 *
 * The choice used to be made by a dialog offering "an installed app" or "apk from storage", which one entry point
 * showed and the other did not. There is nothing to choose between: the installed apps *are* the list, and a file from
 * storage is one more way to name a target, so it lives as an action in the bar rather than as a fork the user has to
 * take before they can see anything.
 *
 * Tapping a row is the commit. There is no confirm button, because the next screen is where the patch is configured and
 * where it can still be abandoned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun SelectPatchTargetScreen(navigator: DestinationsNavigator) {
    val viewModel = viewModel<SelectPatchTargetViewModel>()
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val errorUnknown = stringResource(R.string.error_unknown)
    var query by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    // An apk picked from storage that is itself already an LSPatch build: there is nothing to patch,
    // so it is offered for install instead, held here until the user confirms.
    var alreadyPatched by remember { mutableStateOf<AppInfo?>(null) }
    var installing by remember { mutableStateOf(false) }

    /**
     * Persists the request and hands over to the patch screen, replacing this one in the back stack: coming back from a
     * patch should return where the patch was started from, not to the picker that has already served its purpose.
     */
    fun commit(request: PatchRequest) {
        scope.launch {
            val token = PatchRequestStore.put(request)
            navigator.navigate(NewPatchScreenDestination(token = token)) {
                popUpTo(SelectPatchTargetScreenDestination) { inclusive = true }
            }
        }
    }

    /**
     * Installs an already-patched apk picked from storage, then opens its page. The apk and its splits already sit in
     * the temp dir from reading the manifest, so install is a plain session; on success the picker is left behind so
     * Back returns to where patching was started.
     */
    fun installAlreadyPatched(app: AppInfo) {
        scope.launch {
            installing = true
            val apkPaths = listOf(app.app.sourceDir) + (app.app.splitSourceDirs ?: emptyArray())
            val (status, message) = LSPPackageManager.installFiles(apkPaths.map(::File), useShizuku = true)
            installing = false
            alreadyPatched = null
            if (status == PackageInstaller.STATUS_SUCCESS) {
                navigator.navigate(AppDetailScreenDestination(packageName = app.app.packageName)) {
                    popUpTo(SelectPatchTargetScreenDestination) { inclusive = true }
                }
            } else {
                snackbarHost.showSnackbar(message ?: errorUnknown)
            }
        }
    }

    val storageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
            if (apks.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                reading = true
                LSPPackageManager.getAppInfoFromApks(apks)
                    .onSuccess { infos ->
                        reading = false
                        val primary = infos.first()
                        // An apk that is already an LSPatch build cannot be patched again from here;
                        // offer to install it and send the user to its page to manage loaders/modules.
                        if (primary.isLSPatched) {
                            alreadyPatched = primary
                            return@onSuccess
                        }
                        commit(
                            PatchRequest(
                                token = UUID.randomUUID().toString(),
                                target =
                                    PatchTarget.ApkFiles(
                                        packageName = primary.app.packageName,
                                        label = primary.label,
                                        apkPaths =
                                            listOf(primary.app.sourceDir) +
                                                (primary.app.splitSourceDirs ?: emptyArray()),
                                    ),
                                mode = PatchMode.Local,
                                origin = PatchOrigin.New,
                            )
                        )
                    }
                    .onFailure {
                        reading = false
                        snackbarHost.showSnackbar(it.message ?: errorUnknown)
                    }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.patch_select_target)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { storageLauncher.launch(APK_AND_BUNDLE_TYPES) }) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = stringResource(R.string.patch_from_storage),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.manage_search),
            )
            // Held until the load finishes rather than filled in as it goes: the list is sorted by
            // label, so a partial list re-sorts underneath a reader and moves the row they were
            // reaching for.
            if (viewModel.loading || reading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.patch_target_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                return@Column
            }
            val shown = viewModel.filtered(query)
            if (shown.isEmpty()) {
                PanelEmptyState(
                    icon = Icons.Rounded.SearchOff,
                    text = stringResource(R.string.manage_no_match),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(items = shown, key = { it.app.packageName }) { app ->
                    // An app already patched by LSPatch has nothing to patch here, so tapping it
                    // opens its own page to manage modules/loaders instead of starting a new patch.
                    val patched = app.isLSPatched
                    PackageRow(
                        icon = LSPPackageManager.getIcon(app),
                        label = app.label,
                        packageName = app.app.packageName,
                        modifier =
                            Modifier.clickable {
                                if (patched) {
                                    navigator.navigate(AppDetailScreenDestination(packageName = app.app.packageName))
                                } else {
                                    commit(newRequestFor(app))
                                }
                            },
                        additionalContent =
                            if (patched) {
                                {
                                    Text(
                                        text = stringResource(R.string.patch_target_already_patched),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                    )
                }
            }
        }
    }

    alreadyPatched?.let { app ->
        AlertDialog(
            onDismissRequest = { if (!installing) alreadyPatched = null },
            title = { Text(stringResource(R.string.patch_storage_patched_title)) },
            text = {
                if (installing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.patch_storage_installing),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                } else {
                    Text(stringResource(R.string.patch_storage_patched_text, app.label))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { installAlreadyPatched(app) },
                    enabled = !installing,
                ) {
                    Text(stringResource(R.string.patch_action_install))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { alreadyPatched = null },
                    enabled = !installing,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
