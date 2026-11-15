package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageInfo
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.manager.dhizuku.DhizukuAuthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备所有者（Dhizuku 兼容）授权管理 ViewModel。
 *
 * 列出所有请求过授权的第三方 app，支持允许/阻止/移除授权。
 */
class DhizukuPermissionViewModel(application: Application) : AndroidViewModel(application) {

    data class AuthApp(
        val uid: Int,
        val packageName: String,
        val label: String,
        val allowApi: Boolean,
        val blocked: Boolean,
        val signature: String,
    )

    var isRefreshing by mutableStateOf(false)
        private set

    var search by mutableStateOf("")

    var authList by mutableStateOf<List<AuthApp>>(emptyList())
        private set

    val filteredList by derivedStateOf {
        val q = search
        if (q.isEmpty()) authList
        else authList.filter { it.label.contains(q, true) || it.packageName.contains(q, true) }
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing = true
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                DhizukuAuthStore.all(app).mapNotNull { entry ->
                    val label = try {
                        pm.getApplicationInfo(entry.packageName, 0).loadLabel(pm).toString()
                    } catch (e: Exception) {
                        entry.packageName
                    }
                    AuthApp(
                        uid = entry.uid,
                        packageName = entry.packageName,
                        label = label,
                        allowApi = entry.allowApi,
                        blocked = entry.blocked,
                        signature = entry.signature,
                    )
                }
            }
            authList = result
            isRefreshing = false
        }
    }

    fun setAllow(uid: Int, allow: Boolean) {
        val app = getApplication<Application>()
        val entry = DhizukuAuthStore.get(app, uid) ?: return
        DhizukuAuthStore.put(
            app,
            entry.copy(allowApi = allow)
        )
        refresh()
    }

    /**
     * 【v1.4.1 修复 · 授权页开关是灰的】
     *
     * 直接为目标应用授予/撤销「所有者权限」，**不要求目标应用先自己申请**。
     *
     * 旧行为：授权记录只在目标应用调用 `Dhizuku.requestPermission()` 时由
     * `RequestDhizukuPermissionActivity` 创建，因此目标应用没申请过时，
     * 授权页里的开关是灰的（`enabled = authEntry != null`），用户必须先打开
     * 目标应用触发一次申请才能回来授权。
     *
     * 现在：没有记录就现场创建一条（签名现算，官方 Dhizuku 也不做签名强校验），
     * 打开开关立即对该应用生效。
     *
     * @return true = 已写入
     */
    fun setAllowOrCreate(uid: Int, packageName: String, allow: Boolean): Boolean {
        val app = getApplication<Application>()
        val existing = DhizukuAuthStore.get(app, uid)
        if (existing != null) {
            DhizukuAuthStore.put(app, existing.copy(allowApi = allow, blocked = false))
            refresh()
            return true
        }
        if (packageName.isBlank()) return false
        viewModelScope.launch {
            val signature = withContext(Dispatchers.IO) {
                DhizukuAuthStore.signatureForUid(app, uid).orEmpty()
            }
            DhizukuAuthStore.put(
                app,
                DhizukuAuthStore.AuthEntry(
                    uid = uid,
                    packageName = packageName,
                    signature = signature,
                    allowApi = allow,
                    blocked = false,
                )
            )
            refresh()
        }
        return true
    }

    fun setBlocked(uid: Int, blocked: Boolean) {
        val app = getApplication<Application>()
        val entry = DhizukuAuthStore.get(app, uid) ?: return
        DhizukuAuthStore.put(
            app,
            entry.copy(blocked = blocked)
        )
        refresh()
    }

    fun remove(uid: Int) {
        val app = getApplication<Application>()
        DhizukuAuthStore.remove(app, uid)
        refresh()
    }
}