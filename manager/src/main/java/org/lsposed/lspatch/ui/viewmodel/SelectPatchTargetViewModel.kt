package org.lsposed.lspatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo

/** The installed apps a patch can be started from. */
class SelectPatchTargetViewModel : ViewModel() {

    var loading by mutableStateOf(true)
        private set

    var apps by mutableStateOf(emptyList<AppInfo>())
        private set

    init {
        load(refresh = false)
    }

    fun load(refresh: Boolean) {
        viewModelScope.launch {
            loading = true
            if (LSPPackageManager.appList.isEmpty() || refresh) {
                LSPPackageManager.fetchAppList()
            }
            // System apps are excluded: they are installed on the read-only system partition, and a
            // patched copy could not replace one even if it were built.
            apps = LSPPackageManager.appList.filter { it.app.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            loading = false
        }
    }

    fun filtered(query: String): List<AppInfo> =
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.app.packageName.contains(query, ignoreCase = true)
        }
}
