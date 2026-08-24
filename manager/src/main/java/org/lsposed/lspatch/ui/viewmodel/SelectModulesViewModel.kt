package org.lsposed.lspatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.util.LSPPackageManager

/** The installed Xposed modules, and which of them the user has ticked. */
class SelectModulesViewModel : ViewModel() {

    var modules by mutableStateOf(emptyList<ModuleBinding>())
        private set

    val selected = mutableSetOf<String>().toMutableStateList()

    private var seeded = false

    init {
        viewModelScope.launch {
            if (LSPPackageManager.appList.isEmpty()) LSPPackageManager.fetchAppList()
            modules = LSPPackageManager.installedModuleBindings()
        }
    }

    /**
     * Ticks the modules already carried by the patch, once.
     *
     * Guarded rather than merely additive: the caller seeds from a `LaunchedEffect` keyed on the
     * loaded list, and re-entering the screen would otherwise tick the same set again on top of the
     * user's own changes.
     */
    fun seed(packageNames: List<String>) {
        if (seeded) return
        seeded = true
        selected.clear()
        selected.addAll(packageNames)
    }

    fun toggle(packageName: String) {
        if (!selected.remove(packageName)) selected.add(packageName)
    }

    fun filtered(query: String): List<ModuleBinding> =
        if (query.isBlank()) modules
        else modules.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
}
