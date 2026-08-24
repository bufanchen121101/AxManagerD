package org.lsposed.lspatch.data.repository

import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.matrix.vector.ui.store.InstallStep
import org.matrix.vector.ui.store.ReleaseAsset
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreInstallHost

/**
 * LSPatch's store install capability, injected into the shared Details screen so a module can be
 * installed straight from its page — the same install bar Vector shows.
 *
 * A store module is an ordinary APK, so this downloads the chosen asset and hands it to the platform
 * installer through [LSPPackageManager.installApk] — the same session driver the manager uses for
 * patched apps — which shows the OS confirmation dialog. Created per opened module (it needs the
 * package name). LSPatch keeps no device-side module scope (that is the daemon's job in Vector), so
 * [installedScope]/[installedIsLegacy] stay empty and the Information tab falls back to the
 * catalogue's declared scope.
 */
class LSPStoreInstallHost(private val packageName: String) : StoreInstallHost {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _installState = MutableStateFlow<InstallStep>(InstallStep.Idle)
    override val installState: StateFlow<InstallStep> = _installState.asStateFlow()

    // With Shizuku the install is silent (shell installer); without it the platform installer shows
    // its own confirmation. The hint on the confirm dialog follows suit.
    override val silentInstall: Boolean
        get() = ShizukuApi.isPermissionGranted

    override val installedScope: StateFlow<List<String>> = MutableStateFlow(emptyList())
    override val installedIsLegacy: StateFlow<Boolean> = MutableStateFlow(false)

    override fun install(asset: ReleaseAsset, releaseVersion: RepoVersion?) {
        val url = asset.downloadUrl
        if (url.isNullOrBlank()) {
            _installState.value = InstallStep.Failed(packageName, "No download URL")
            return
        }
        val step = _installState.value
        if (step is InstallStep.Downloading || step is InstallStep.Installing) return
        scope.launch {
            try {
                _installState.value = InstallStep.Downloading(packageName, 0, asset.size)
                val apk = download(url) { read, total ->
                    _installState.value = InstallStep.Downloading(packageName, read, total)
                }
                _installState.value = InstallStep.Installing(packageName)
                val (status, message) = LSPPackageManager.installApk(apk)
                _installState.value =
                    if (status == PackageInstaller.STATUS_SUCCESS) InstallStep.Done(packageName)
                    else InstallStep.Failed(packageName, message)
            } catch (e: Exception) {
                _installState.value = InstallStep.Failed(packageName, e.message)
            }
        }
    }

    override fun acknowledge() {
        _installState.value = InstallStep.Idle
    }

    private fun download(fileUrl: String, onProgress: (Long, Long) -> Unit): File {
        val out = File(lspApp.cacheDir, "store-${packageName}.apk")
        val connection =
            (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LSPatch-Manager")
            }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var read = 0L
            connection.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, if (total > 0) total else 0)
                    }
                }
            }
            return out
        } finally {
            connection.disconnect()
        }
    }
}
