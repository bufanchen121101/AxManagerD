package org.lsposed.lspatch.manager

import android.os.Binder
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.LoadedModule
import java.io.File

object ManagerService : IFrameworkService.Stub() {

    private const val TAG = "ManagerService"

    private fun callerModules(legacy: Boolean): List<LoadedModule> {
        val app = lspApp.packageManager.getNameForUid(Binder.getCallingUid()) ?: return emptyList()
        return runBlocking { ConfigManager.getModuleFilesForApp(app, legacy) }
    }

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModules(): List<LoadedModule> {
        val list = callerModules(legacy = true)
        Log.d(TAG, "getLegacyModules: ${list.map { it.packageName }}")
        return list
    }

    override fun getModules(): List<LoadedModule> {
        val list = callerModules(legacy = false)
        Log.d(TAG, "getModules: ${list.map { it.packageName }}")
        // Record which modules this host process runs, so a companion's reload can find it as a
        // target. (Best-effort; kept for future hot-reload support, no companion push in pure-patch.)
        HotReloadRegistry.recordModules(Binder.getCallingUid(), Binder.getCallingPid(), list.map { it.packageName })
        return list
    }

    override fun getPrefsPath(packageName: String): String {
        return File(Environment.getDataDirectory(), "data/$packageName/shared_prefs/").absolutePath
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return runCatching {
            ParcelFileDescriptor.open(
                File(lspApp.applicationInfo.sourceDir), ParcelFileDescriptor.MODE_READ_ONLY
            )
        }.onFailure { Log.e(TAG, "Failed to open manager APK", it) }.getOrNull()
    }

    override fun requestManagerService(): IBinder? {
        return null
    }

    override fun attachProcessChannel(channel: IProcessChannel?) {
        // The host's way back in, kept so the manager can drive a hot reload into it (manager mode
        // plays the daemon). Keyed by the calling (uid, pid) -- the same process getModules() records
        // its modules under -- and dropped when the channel dies.
        if (channel == null) return
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val name = lspApp.packageManager.getNameForUid(uid) ?: "uid$uid"
        HotReloadRegistry.attach(uid, pid, name, channel)
    }
}
