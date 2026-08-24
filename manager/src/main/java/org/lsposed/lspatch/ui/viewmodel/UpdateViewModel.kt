package org.lsposed.lspatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.LSPConfig
import org.lsposed.lspatch.util.LSPPackageManager
import org.matrix.vector.ui.update.VariantChoice

/**
 * Backs the full-screen self-update page: it checks GitHub for a newer LSPatch release, holds its
 * notes for the shared HTML renderer, and drives the download-and-install the install bar starts.
 *
 * Independent of [HomeViewModel] on purpose. Home keeps its own lightweight check only to mark the
 * version line; this owns the screen's copy so the page can re-check and install without the home
 * dashboard needing to survive in memory behind it.
 */
class UpdateViewModel : ViewModel() {

    /**
     * The latest LSPatch release, whether or not it is newer than the installed build.
     *
     * Always the latest release when the check succeeds, so the page can show its notes even when
     * there is nothing to install -- the way Vector's update page shows the newest build's notes
     * regardless. [newer] says whether it is actually an update to offer; [apks] are the manager apk
     * assets the release published, one per variant (Release, Debug), so the reader can pick which
     * to install -- the shared manager-ui variant picker drives that choice.
     */
    data class Update(
        val version: String,
        val url: String,
        val notes: String,
        val apks: List<ApkAsset>,
        val newer: Boolean,
    )

    /**
     * One manager apk a release published: which variant it is, where to fetch it, and how big.
     *
     * [key] is [VariantChoice.RELEASE] or [VariantChoice.DEBUG] -- the same keys the shared picker
     * labels and the running build is matched against. The two variants are signed with different
     * keys, so installing the one that does not match the running build is refused by the platform
     * as a signature mismatch; the picker still offers both, and the OS asks to uninstall first.
     */
    data class ApkAsset(
        val key: String,
        val name: String,
        val url: String,
        val sizeInBytes: Long,
    )

    /** Progress of a self-update the user has started from the install bar. */
    sealed interface UpdateStage {
        data object Idle : UpdateStage
        /** [progress] is 0f..1f, or -1f while the total size is unknown. */
        data class Downloading(val progress: Float) : UpdateStage
        data object Installing : UpdateStage
        data class Failed(val message: String) : UpdateStage
    }

    var update by mutableStateOf<Update?>(null)
        private set

    var updateStage by mutableStateOf<UpdateStage>(UpdateStage.Idle)
        private set

    /** True while a release check is in flight, so the screen can say it is checking. */
    var checkingUpdate by mutableStateOf(false)
        private set

    /** Whether the running build is the debug variant -- the default the variant picker opens on. */
    private val runningDebuggable =
        (lspApp.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * The variant the reader has chosen, defaulting to the one they are running: a debug build
     * self-installs from the debug apk, since the two variants are signed differently.
     */
    var chosenVariant by mutableStateOf(
        if (runningDebuggable) VariantChoice.DEBUG else VariantChoice.RELEASE
    )
        private set

    /** The apk that will be installed: the chosen variant, or whatever the release did publish. */
    val chosenApk: ApkAsset?
        get() = update?.apks?.let { apks ->
            apks.firstOrNull { it.key == chosenVariant } ?: apks.firstOrNull()
        }

    fun chooseVariant(key: String) {
        chosenVariant = key
    }

    init {
        checkUpdate()
    }

    /**
     * Checks GitHub for a newer release than the installed build. Best-effort and anonymous: it
     * stays null on any failure, so the screen simply reports it is up to date rather than claiming
     * an update that the check never confirmed.
     */
    fun checkUpdate() {
        if (checkingUpdate) return
        viewModelScope.launch {
            checkingUpdate = true
            update = withContext(Dispatchers.IO) { fetchLatest() }
            checkingUpdate = false
        }
    }

    private fun fetchLatest(): Update? = runCatching {
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(json).asJsonObject
            val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val url = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString ?: "$REPO_URL/releases"
            val notes = obj.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty().trim()
            val latest = tag.trimStart('v', 'V').trim()
            val current = LSPConfig.instance.VERSION_NAME.trimStart('v', 'V').trim()
            // The release is *offered* only when it is above the current build and no older than the
            // v0.8 baseline (this UI first shipped in v0.8). But it is always returned, so its notes
            // show whether or not there is anything to install -- which is the whole point of the page.
            val newer = isNewer(latest, current) && !isNewer(MIN_VERSION, latest)

            // Both manager apks the release published, one per variant, so the reader can choose.
            val apks = parseApkAssets(obj.getAsJsonArray("assets"))
            Update(tag, url, notes, apks, newer)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * Downloads the update apk and hands it to the platform installer. Progress is surfaced through
     * [updateStage]; the actual install shows the OS confirmation dialog, so on success this process
     * is replaced and nothing further runs here.
     */
    fun downloadAndInstall() {
        val target = chosenApk?.url ?: return
        if (updateStage is UpdateStage.Downloading || updateStage is UpdateStage.Installing) return
        viewModelScope.launch {
            updateStage = UpdateStage.Downloading(-1f)
            val apk = withContext(Dispatchers.IO) { runCatching { download(target) }.getOrNull() }
            if (apk == null) {
                updateStage = UpdateStage.Failed("Download failed")
                return@launch
            }
            updateStage = UpdateStage.Installing
            val (status, message) = LSPPackageManager.installApk(apk)
            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                updateStage = UpdateStage.Failed(message ?: "Install failed")
            } else {
                updateStage = UpdateStage.Idle
            }
        }
    }

    fun dismissUpdate() {
        if (updateStage !is UpdateStage.Downloading) updateStage = UpdateStage.Idle
    }

    private fun download(fileUrl: String): File {
        val out = File(lspApp.cacheDir, "update.apk")
        val connection = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LSPatch-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
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
                        updateStage = UpdateStage.Downloading(if (total > 0) read.toFloat() / total else -1f)
                    }
                }
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The manager apks a release published, at most one per variant.
     *
     * Every `.apk` asset is read and sorted into Debug (its name carries the `debug` marker) or
     * Release (it does not), so a differently-named future asset such as `manager-v0.9.apk` still
     * parses. When a variant has more than one candidate the exact canonical name wins, so
     * `manager.apk` beats an incidental `foo-release.apk`. Assets without a download URL are dropped.
     */
    private fun parseApkAssets(assets: com.google.gson.JsonArray?): List<ApkAsset> {
        val apks = assets
            ?.map { it.asJsonObject }
            ?.filter { it.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true }
            .orEmpty()
        fun asset(a: com.google.gson.JsonObject): ApkAsset? {
            val name = a.get("name")?.asString ?: return null
            val dl = a.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val size = a.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val key =
                if (name.contains("debug", ignoreCase = true)) VariantChoice.DEBUG
                else VariantChoice.RELEASE
            return ApkAsset(key, name, dl, size)
        }
        // One per variant. When several match, prefer the canonical name over anything incidental.
        return apks.mapNotNull { asset(it) }
            .groupBy { it.key }
            .mapNotNull { (_, group) ->
                group.firstOrNull { it.name.equals("manager.apk", true) || it.name.equals("manager-debug.apk", true) }
                    ?: group.first()
            }
    }

    /** Dotted numeric compare, tolerant of suffixes; a non-numeric part sorts as 0. */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.', '-')
        val c = current.split('.', '-')
        for (i in 0 until maxOf(l.size, c.size)) {
            val li = l.getOrNull(i)?.toIntOrNull() ?: 0
            val ci = c.getOrNull(i)?.toIntOrNull() ?: 0
            if (li != ci) return li > ci
        }
        return false
    }

    companion object {
        const val OWNER_REPO = "JingMatrix/LSPatch"
        const val REPO_URL = "https://github.com/$OWNER_REPO"
        private const val RELEASES_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
        private const val MIN_VERSION = "0.8"
    }
}
