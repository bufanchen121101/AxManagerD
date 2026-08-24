package org.lsposed.lspatch.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.data.model.ModuleOrigin
import org.lsposed.lspatch.data.model.PatchMode
import org.lsposed.lspatch.data.model.mode
import org.lsposed.lspatch.data.repository.PatchInputs
import org.lsposed.lspatch.share.PatchConfig
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import java.io.File

/** How many modules a pending edit adds and removes. */
data class PendingChanges(val added: Int, val removed: Int) {
    val any: Boolean get() = added > 0 || removed > 0
    val total: Int get() = added + removed
}

/**
 * One patched app: what it is, which modules it carries, and the edit in progress.
 *
 * The two modes are read from different places and applied in different ways -- Local's scope lives
 * in the manager's database and takes effect on the app's next start, Integrated's is baked into
 * the apk and takes a rebuild -- but they are edited the same way here, as a draft set against a
 * baseline, so the page above can present one list and one apply bar.
 */
class AppDetailViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val packageName: String = savedStateHandle["packageName"] ?: ""

    var loading by mutableStateOf(true)
        private set

    var app by mutableStateOf<AppInfo?>(null)
        private set

    var config by mutableStateOf<PatchConfig?>(null)
        private set

    /** Every module that could be shown for this app -- carried or merely available. */
    var candidates by mutableStateOf(emptyList<ModuleBinding>())
        private set

    /** What the app carries right now, as last read. */
    private var baseline by mutableStateOf(emptySet<String>())

    /**
     * The installed apk this page's contents were read from.
     *
     * A re-patch replaces that apk while this page is in the back stack, so "the set cannot change
     * behind our back" -- which is true of an ordinary visit -- is exactly false in the one case the
     * page itself sends the user into. Comparing a stat of the file on the way back is cheap enough
     * to do on every resume, and re-reads only when the app really is a different build.
     */
    private var loadedStamp: String? = null

    val draft = mutableSetOf<String>().toMutableStateList()

    val mode: PatchMode
        get() = config?.mode ?: PatchMode.Local

    val pending: PendingChanges
        get() = PendingChanges(
            added = draft.count { it !in baseline },
            removed = baseline.count { it !in draft },
        )

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            if (LSPPackageManager.appList.isEmpty()) LSPPackageManager.fetchAppList()
            val found = LSPPackageManager.appList.firstOrNull { it.app.packageName == packageName }
            app = found
            if (found == null) {
                loading = false
                return@launch
            }
            loadedStamp = stampOf(found)
            val patchConfig = PatchInputs.configOf(found)
            config = patchConfig

            if (patchConfig?.mode == PatchMode.Integrated) {
                // Baked in, so the app's own apk is the only source. Unzipping it is the expensive
                // part, which is why this is not repeated on every return -- only when [onResume]
                // finds the apk has actually been replaced.
                val embedded = LSPPackageManager.embeddedModulesOf(found)
                candidates = embedded
                baseline = embedded.mapTo(mutableSetOf()) { it.packageName }
            } else {
                // Live, so every installed module is a candidate and the enabled set is the scope.
                val installed = LSPPackageManager.installedModuleBindings()
                candidates = installed
                baseline = ConfigManager.getModulesForApp(packageName)
                    .mapTo(mutableSetOf()) { it.pkgName }
            }
            draft.clear()
            draft.addAll(baseline)
            loading = false
        }
    }

    /**
     * Brings the page back up to date when it is returned to.
     *
     * Two quite different staleness cases, so two answers. If the *apk itself* has changed -- which
     * is what a re-patch does, and this page is where a re-patch is started from -- everything shown
     * describes a build that no longer exists, so the page is read again from scratch. Otherwise only
     * a Local app can have moved, since its scope is shared state another screen can edit, and that
     * is a cheap database read.
     *
     * An edit in progress is never discarded by either path: the baseline moves so the pending count
     * stays honest, but the draft is left as the user typed it.
     */
    fun onResume() {
        if (loading) return
        viewModelScope.launch {
            val current = LSPPackageManager.appList.firstOrNull { it.app.packageName == packageName }
            if (current != null && stampOf(current) != loadedStamp) {
                // A different build: the module set, the patch config and the loader version are all
                // potentially different, and none of what is on screen can be trusted.
                draft.clear()
                load()
                return@launch
            }
            if (mode != PatchMode.Local) return@launch
            val fresh = ConfigManager.getModulesForApp(packageName).mapTo(mutableSetOf()) { it.pkgName }
            if (!pending.any) {
                baseline = fresh
                draft.clear()
                draft.addAll(fresh)
            } else {
                baseline = fresh
            }
        }
    }

    /** Identifies the exact build on disk: a re-patch changes its size and its timestamp. */
    private fun stampOf(info: AppInfo): String =
        runCatching {
            val file = File(info.app.sourceDir)
            "${info.app.sourceDir}:${file.lastModified()}:${file.length()}"
        }.getOrDefault(info.app.sourceDir.orEmpty())

    fun toggle(modulePackage: String) {
        if (!draft.remove(modulePackage)) draft.add(modulePackage)
    }

    fun discard() {
        draft.clear()
        draft.addAll(baseline)
    }

    /** Adds a module apk picked from storage -- only meaningful for an Integrated re-patch. */
    fun addFromFile(file: File, onRejected: () -> Unit) {
        viewModelScope.launch {
            val binding = LSPPackageManager.moduleBindingFromFile(file)
            if (binding == null) {
                onRejected()
                return@launch
            }
            val existing = candidates.map { it.packageName }.toSet()
            if (binding.packageName !in existing) {
                candidates = candidates + binding.copy(origin = ModuleOrigin.Picked)
            }
            // draft is a list, so guard the add: re-picking a module already drafted would
            // otherwise double-count it in the pending total and leave a copy behind on toggle.
            if (binding.packageName !in draft) draft.add(binding.packageName)
        }
    }

    /**
     * Writes a Local app's scope. Integrated never comes here -- it goes through a re-patch.
     *
     * On success both baseline and draft move to what was written, so the apply bar retires instead
     * of reappearing over an edit that has already landed.
     */
    fun applyLocalScope(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val target = draft.toSet()
            val result = ConfigManager.setScopeForApp(packageName, target)
            if (result.isSuccess) {
                baseline = target
                draft.clear()
                draft.addAll(target)
            }
            onResult(result.isSuccess)
        }
    }

    /** The drafted set, as bindings, for handing to a re-patch. */
    fun draftedBindings(): List<ModuleBinding> =
        candidates.filter { it.packageName in draft }
}
