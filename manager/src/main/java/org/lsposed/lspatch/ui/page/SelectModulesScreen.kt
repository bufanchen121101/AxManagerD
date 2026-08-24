package org.lsposed.lspatch.ui.page

import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.result.ResultBackNavigator
import kotlinx.parcelize.Parcelize
import frb.axeron.manager.R
import org.lsposed.lspatch.ui.viewmodel.SelectModulesViewModel
import org.matrix.vector.ui.ApiBadge
import org.matrix.vector.ui.ModuleRow
import org.matrix.vector.ui.PanelEmptyState
import org.matrix.vector.ui.SearchField

/**
 * What the module picker hands back.
 *
 * Package names only. The result travels through the back stack's saved state, which is written
 * into the process's saved instance state -- so returning whole `ApplicationInfo` objects, as the
 * old shared result type did, meant serialising the system's own package records on every save.
 * The caller has the bindings already; a name is enough to say which.
 */
@Parcelize
data class SelectedModules(val packageNames: ArrayList<String>) : Parcelable

/**
 * Picks installed Xposed modules to embed in a patch.
 *
 * A destination of its own with its own result type, rather than a flag on a shared app picker.
 * The two pickers answer different questions and returned through one untyped channel, which is
 * how a screen expecting one app could be handed a list of modules and cast it blindly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun SelectModulesScreen(
    navigator: ResultBackNavigator<SelectedModules>,
    initialSelected: ArrayList<String>?,
) {
    val viewModel = viewModel<SelectModulesViewModel>()
    var query by remember { mutableStateOf("") }

    // Keyed on the loaded list, not on first composition: seeding before the module list has been
    // read leaves nothing selected, and seeding again on every recomposition accumulates.
    LaunchedEffect(viewModel.modules) {
        if (viewModel.modules.isNotEmpty()) viewModel.seed(initialSelected.orEmpty())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_modules_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(20.dp)) {
                    Button(
                        onClick = {
                            navigator.navigateBack(
                                SelectedModules(ArrayList(viewModel.selected))
                            )
                        },
                        enabled = viewModel.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.patch_modules_add_selected,
                                viewModel.selected.size,
                            )
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.manage_search),
            )
            val shown = viewModel.filtered(query)
            if (shown.isEmpty()) {
                PanelEmptyState(
                    icon = if (viewModel.modules.isEmpty()) Icons.Rounded.Extension
                    else Icons.Rounded.SearchOff,
                    text = stringResource(
                        if (viewModel.modules.isEmpty()) R.string.select_modules_none
                        else R.string.manage_no_match
                    ),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(items = shown, key = { it.packageName }) { module ->
                    val chosen = module.packageName in viewModel.selected
                    ModuleRow(
                        name = module.label,
                        versionName = module.versionName.orEmpty(),
                        description = module.manifest?.description.orEmpty(),
                        icon = {
                            val bitmap = module.icon
                            if (bitmap != null) {
                                Icon(
                                    bitmap = bitmap,
                                    contentDescription = null,
                                    tint = Color.Unspecified,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Extension,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                        apiBadge = { ApiBadge(label = "API", value = module.apiVersion.toString()) },
                        selected = chosen,
                        onClick = { viewModel.toggle(module.packageName) },
                        onIconClick = { viewModel.toggle(module.packageName) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
