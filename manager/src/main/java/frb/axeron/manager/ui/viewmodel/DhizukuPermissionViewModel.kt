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