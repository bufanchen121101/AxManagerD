package org.lsposed.lspatch.ui.viewmodel.manage

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager
import org.matrix.vector.ui.module.ModuleDetection

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    class XposedInfo(
        val api: Int,
        val description: String,
        val versionName: String,
    )

    val appList: List<Pair<LSPPackageManager.AppInfo, XposedInfo>> by derivedStateOf {
        val pm = lspApp.packageManager
        LSPPackageManager.appList.filter { it.isXposedModule }.map { appInfo ->
            // The shared ModuleDetection reads both module generations in one APK pass: the
            // description (literal or string-resource, modern or legacy), the API level, and the
            // scope — the same code Vector uses, rather than a reimplementation here.
            val manifest = ModuleDetection.inspect(appInfo.app, pm)
            val api =
                manifest.targetApiVersion.takeIf { it > 0 }
                    ?: manifest.minApiVersion.takeIf { it > 0 }
                    ?: 102
            appInfo to XposedInfo(
                api = api,
                description = manifest.description,
                versionName = versionName(appInfo.app.packageName),
            )
        }.also {
            Log.d(TAG, "Loaded ${it.size} Xposed modules")
        }
    }

    /**
     * Which patched apps have each module enabled, keyed by the module's package, valued by the app
     * labels. Loaded off the scope database (Local-mode patches) and refreshed whenever the installed
     * list changes, so a row can say what it actually applies to instead of standing there blank.
     */
    var appliesTo by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    /**
     * The launcher icons of the apps each module reaches, keyed by the module's package — the visual
     * counterpart of [appliesTo], so a module row can preview its scope as app thumbnails exactly the
     * way an app row previews the modules reaching it (the shared `IconCluster`) and the way Vector's
     * own module scope does, rather than a bare "N apps" count. Populated alongside [appliesTo].
     */
    var appliesToIcons by mutableStateOf<Map<String, List<ImageBitmap>>>(emptyMap())
        private set

    init {
        viewModelScope.launch {
            snapshotFlow { LSPPackageManager.appList }.collect { refreshScopes() }
        }
        // A module's reach is the same relationship an app's module list is, seen from the other
        // end -- so editing an app's scope changes what belongs on this tab too. Without this the
        // two tabs of one screen disagreed until the manager was restarted.
        viewModelScope.launch {
            ConfigManager.scopeRevision.collect { refreshScopes() }
        }
    }

    private suspend fun refreshScopes() {
        val labels = LSPPackageManager.appList.associate { it.app.packageName to it.label }
        val icons = LSPPackageManager.appList.associate { it.app.packageName to LSPPackageManager.getIcon(it) }
        val modules = LSPPackageManager.appList.filter { it.isXposedModule }
        val labelMap = HashMap<String, List<String>>()
        val iconMap = HashMap<String, List<ImageBitmap>>()
        modules.forEach { module ->
            val apps = ConfigManager.getAppsForModule(module.app.packageName)
            labelMap[module.app.packageName] = apps.map { labels[it] ?: it }.sorted()
            // Keep the icons in the same sorted order as the labels, so the preview icons are the
            // first apps the reach lists rather than an arbitrary set.
            iconMap[module.app.packageName] =
                apps.sortedBy { labels[it] ?: it }.mapNotNull { icons[it] }
        }
        appliesTo = labelMap
        appliesToIcons = iconMap
    }

    private fun versionName(pkgName: String): String =
        runCatching { lspApp.packageManager.getPackageInfo(pkgName, 0).versionName }
            .getOrNull()
            .orEmpty()
}
