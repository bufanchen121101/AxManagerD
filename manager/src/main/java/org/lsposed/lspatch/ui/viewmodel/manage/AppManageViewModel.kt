package org.lsposed.lspatch.ui.viewmodel.manage

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.share.PatchConfig
import org.lsposed.lspatch.ui.viewstate.ProcessingState
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import org.lsposed.lspatch.util.ShizukuApi

class AppManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ManageViewModel"
    }

    sealed class ViewAction {
        data class PerformOptimize(val appInfo: AppInfo) : ViewAction()
        object ClearOptimizeResult : ViewAction()
    }

    val appList: List<Pair<AppInfo, PatchConfig>> by derivedStateOf {
        LSPPackageManager.appList.mapNotNull { appInfo ->
            appInfo.app.metaData?.getString("lspatch")?.let {
                val json = Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                Log.d(TAG, "Read patched config: $json")
                appInfo to Gson().fromJson(json, PatchConfig::class.java)
            }
        }.also {
            Log.d(TAG, "Loaded ${it.size} patched apps")
        }
    }

    /**
     * The icons of the modules each patched app reaches, keyed by the app's package, so a row can
     * render the module thumbnails (mirroring the module side's app-icon cluster). A Local patch reads
     * its live scope from the database; an Integrated patch bakes its modules into the apk at patch
     * time, so those are read from the `assets/lspatch/modules/` entries. The shared `ModuleRow` gates
     * and caps its own reach band, so this hands over the whole list and keeps no separate count.
     * Refreshed whenever the installed list changes, and whenever a scope edit lands.
     */
    var moduleIcons by mutableStateOf<Map<String, List<ImageBitmap>>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            snapshotFlow { LSPPackageManager.appList }.collect { refreshModuleIcons() }
        }
        viewModelScope.launch {
            ConfigManager.scopeRevision.collect { refreshModuleIcons() }
        }
    }

    private suspend fun refreshModuleIcons() {
        // Read the derived `appList` here, on the collector's context — reading a Compose snapshot
        // state from inside withContext(Dispatchers.IO) throws "Reading a state created after the
        // snapshot was taken" and took the whole Manage screen down. Only the icon extraction (zip
        // reads, archive-info) needs the IO thread, done below with the captured list.
        val apps = appList
        val icons = apps.associate { (appInfo, config) ->
            appInfo.app.packageName to LSPPackageManager.moduleIconsFor(appInfo, config.useManager)
        }
        moduleIcons = icons
    }

    var optimizeState: ProcessingState<Boolean> by mutableStateOf(ProcessingState.Idle)
        private set

    /** Which package was last recompiled, so the outcome can offer to restart that one. */
    var lastOptimized: String? = null
        private set

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.PerformOptimize -> performOptimize(action.appInfo)
                is ViewAction.ClearOptimizeResult -> optimizeState = ProcessingState.Idle
            }
        }
    }

    private suspend fun performOptimize(appInfo: AppInfo) {
        Log.i(TAG, "Perform optimize for ${appInfo.app.packageName}")
        lastOptimized = appInfo.app.packageName
        optimizeState = ProcessingState.Processing
        val result = withContext(Dispatchers.IO) {
            ShizukuApi.performDexOptMode(appInfo.app.packageName)
        }
        optimizeState = ProcessingState.Done(result)
    }
}
