package org.lsposed.lspatch.data.repository

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.data.model.ModuleBinding
import org.lsposed.lspatch.data.model.ModuleOrigin
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.share.PatchConfig
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.LSPPackageManager.AppInfo
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile

/**
 * What a patched app carries inside itself, recovered so it can be patched again.
 *
 * A patched apk keeps the original it was built from at [Constants.ORIGINAL_APK_ASSET_PATH] and, in
 * Integrated mode, every module it embedded under [Constants.EMBEDDED_MODULES_ASSET_PATH]. That is
 * enough to rebuild the app with different settings or a different module set, and enough to put the
 * original back -- without the source apk, and without the modules being installed on the device.
 */
object PatchInputs {

    private const val TAG = "PatchInputs"

    /** The legacy name for the embedded original, still found in apks patched by older builds. */
    private const val LEGACY_ORIGIN_ASSET = "assets/lspatch/origin_apk.bin"

    data class RecoveredPatch(
        /** The original apks, base first -- the input to a re-patch, or the whole of a restore. */
        val originApks: List<File>,
        val embeddedModules: List<ModuleBinding>,
        val config: PatchConfig?,
    )

    /**
     * Unpacks [app]'s originals and embedded modules into app-private storage.
     *
     * Recovery is per-apk: a split app hides one original inside each of its splits, so every one
     * has to be opened, not just the base.
     */
    suspend fun fromInstalledPatchedApp(app: AppInfo): RecoveredPatch = withContext(Dispatchers.IO) {
        val pkg = app.app.packageName
        val workDir = lspApp.noBackupFilesDir.resolve("recovered").resolve(pkg).apply {
            deleteRecursively()
            mkdirs()
        }
        val apkPaths = listOf(app.app.sourceDir) + (app.app.splitSourceDirs ?: emptyArray())

        val originApks = apkPaths.map { apk ->
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(Constants.ORIGINAL_APK_ASSET_PATH)
                    ?: zip.getEntry(LEGACY_ORIGIN_ASSET)
                    ?: throw FileNotFoundException("No original apk inside ${File(apk).name}")
                val dst = workDir.resolve(File(apk).name)
                zip.getInputStream(entry).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst
            }
        }

        val moduleDir = workDir.resolve("modules").also { it.mkdirs() }
        // Keyed by package so a module contributing more than one entry cannot appear twice: the
        // patcher writes every embedded module to `modules/<package>.apk`, so two entries for one
        // package would collide there anyway, and only the first is ever recoverable.
        val modules = linkedMapOf<String, ModuleBinding>()
        runCatching {
            ZipFile(app.app.sourceDir).use { zip ->
                zip.entries().iterator().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    if (!entry.name.startsWith(Constants.EMBEDDED_MODULES_ASSET_PATH)) return@forEach
                    // Guards against the directory entry itself, whose basename is empty: resolving
                    // "" yields the directory, and writing to it fails the entire recovery.
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isEmpty()) return@forEach
                    val dst = moduleDir.resolve(fileName)
                    zip.getInputStream(entry).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                    val pkgName = fileName.removeSuffix(".apk")
                    if (modules.containsKey(pkgName)) return@forEach
                    modules[pkgName] =
                        LSPPackageManager.moduleBindingFromFile(dst)?.copy(
                            packageName = pkgName,
                            origin = ModuleOrigin.Embedded,
                        ) ?: ModuleBinding(
                            packageName = pkgName,
                            label = pkgName,
                            versionName = null,
                            versionCode = 0L,
                            manifest = null,
                            icon = null,
                            apkPath = dst.absolutePath,
                            origin = ModuleOrigin.Embedded,
                        )
                }
            }
        }.onFailure { Log.w(TAG, "Could not read embedded modules of $pkg", it) }

        RecoveredPatch(originApks, modules.values.toList(), configOf(app))
    }

    /** The patch config an installed app was built with, read from its manifest metadata. */
    fun configOf(app: AppInfo): PatchConfig? =
        app.app.metaData?.getString("lspatch")?.let {
            runCatching {
                Gson().fromJson(
                    Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8),
                    PatchConfig::class.java,
                )
            }.getOrNull()
        }

    /** Drops the working copies for [packageName] once they have been consumed. */
    suspend fun discard(packageName: String) = withContext(Dispatchers.IO) {
        lspApp.noBackupFilesDir.resolve("recovered").resolve(packageName).deleteRecursively()
        Unit
    }
}
