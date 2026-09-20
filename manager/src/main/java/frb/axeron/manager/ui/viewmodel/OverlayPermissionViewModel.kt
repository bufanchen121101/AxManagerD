package frb.axeron.manager.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.manager.features.overlay.OverlayManager
import frb.axeron.manager.features.overlay.OverlayPermissionStore
import frb.axeron.manager.util.OverlayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块核心文件修改权限 —— 授权页 ViewModel。
 *
 * 数据来源分两处：
 *  - 模块列表：shell 域 `runtime_plugins/` 扫描（经 [OverlayManager]）
 *  - 授权状态：shell 域 `perm/granted/*.json`（经 [OverlayPermissionStore]）
 *  - 全局开关 / 免责：App SP（[OverlayPermissionStore] 内部双写 global.json）
 *
 * 列表刻意包含「所有已安装模块」，无论其是否申请过授权 ——
 * 满足「无论哪个模块（无论有没有申请）在设置里都可以控制」的需求。
 */
class OverlayPermissionViewModel(application: Application) : AndroidViewModel(application) {

    /** 授权页单行数据。 */
    data class ModuleRow(
        val moduleId: String,
        /** 模块显示名（暂无 label 来源时等于 moduleId）。 */
        val label: String,
        /** 是否申请过（pending 文件存在）。 */
        val requested: Boolean,
        /** 申请理由（可能为空）。 */
        val reason: String,
        /** 当前授权模式；null = 无授权记录。 */
        val mode: OverlayPermissionStore.GrantMode?,
        /** 覆盖层文件数。 */
        val overlayFiles: Int,
    ) {
        val granted: Boolean
            get() = mode == OverlayPermissionStore.GrantMode.ALWAYS ||
                    mode == OverlayPermissionStore.GrantMode.ONCE
    }

    var isRefreshing by mutableStateOf(false)
        private set

    /** 全局总开关（UI 立即响应）。 */
    var enabled by mutableStateOf(false)
        private set

    /** 免责声明是否已同意。 */
    var disclaimerAccepted by mutableStateOf(false)
        private set

    var rows by mutableStateOf<List<ModuleRow>>(emptyList())
        private set

    var search by mutableStateOf("")

    val filteredRows by derivedStateOf {
        val q = search
        if (q.isEmpty()) rows
        else rows.filter {
            it.moduleId.contains(q, true) || it.label.contains(q, true)
        }
    }

    private val app: Application get() = getApplication()

    // -----------------------------------------------------------------------
    // 加载
    // -----------------------------------------------------------------------

    /** 全量刷新：全局开关 + 免责 + 模块列表 + 授权状态。 */
    fun refresh() {
        viewModelScope.launch {
            isRefreshing = true
            enabled = OverlayPermissionStore.isEnabled(app)
            disclaimerAccepted = OverlayPermissionStore.isDisclaimerAccepted(app)

            // App 启动后 SP 与 shell 域 global.json 可能不一致（例如文件被手工改动），
            // 这里做一次反向校准：以 shell 域为准补齐 SP（只在磁盘上明确为 true 时才覆盖）。
            runCatching {
                OverlayPermissionStore.readGlobalJson(app)?.let { (e, d) ->
                    if (e && !enabled) {
                        enabled = true
                    }
                    if (d && !disclaimerAccepted) {
                        disclaimerAccepted = true
                    }
                    OverlayLog.d("readGlobalJson enabled=$e disclaimer=$d")
                }
            }

            val list = withContext(Dispatchers.IO) { loadRows() }
            rows = list
            isRefreshing = false
        }
    }

    private suspend fun loadRows(): List<ModuleRow> {
        val ids = runCatching { OverlayManager.installedModuleIds() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return emptyList()

        val grants = runCatching { OverlayPermissionStore.allGrants(app) }
            .getOrDefault(emptyList())
            .associateBy { it.moduleId }

        return ids.map { id ->
            val grant = grants[id]
            val pending = runCatching { OverlayPermissionStore.getPending(app, id) }.getOrNull()
            val files = runCatching { OverlayManager.list(id).size }.getOrDefault(0)
            ModuleRow(
                moduleId = id,
                label = id,
                requested = pending != null,
                reason = pending?.first ?: grant?.reason.orEmpty(),
                mode = grant?.mode,
                overlayFiles = files,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 操作
    // -----------------------------------------------------------------------

    /** 同意免责声明（写 SP + global.json）。 */
    fun acceptDisclaimer() {
        viewModelScope.launch {
            OverlayPermissionStore.acceptDisclaimer(app)
            disclaimerAccepted = true
        }
    }

    /**
     * 切换全局总开关。
     *
     * 关闭时会保留各模块的授权记录（只是暂时失效），重新打开即恢复，
     * 避免用户误关后需要逐个重新授权。
     */
    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            enabled = value
            OverlayPermissionStore.setEnabled(app, value)
        }
    }

    /**
     * 手动开启/关闭某模块授权。
     *
     * 开关只做两态：开 = ALWAYS，关 = DENIED（删除授权文件）。
     * 「仅本次」只通过模块申请弹窗授予，在此页视为已开。
     */
    fun setModuleGranted(moduleId: String, granted: Boolean) {
        viewModelScope.launch {
            val current = rows.firstOrNull { it.moduleId == moduleId }
            if (granted) {
                val err = OverlayPermissionStore.putGrant(
                    app, moduleId, OverlayPermissionStore.GrantMode.ALWAYS,
                    reason = current?.reason.orEmpty()
                )
                if (err != null) OverlayLog.w("setModuleGranted($moduleId) 失败: $err")
            } else {
                OverlayPermissionStore.revoke(app, moduleId)
            }
            // 局部刷新该行，避免整页闪烁
            rows = loadRows()
        }
    }

    /** 撤销某模块授权（等同于关闭）。 */
    fun revoke(moduleId: String) = setModuleGranted(moduleId, false)

    /** 清空某模块的覆盖层文件（不影响授权）。 */
    fun clearOverlay(moduleId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                OverlayManager.clear(moduleId)
            }
            rows = loadRows()
        }
    }
}