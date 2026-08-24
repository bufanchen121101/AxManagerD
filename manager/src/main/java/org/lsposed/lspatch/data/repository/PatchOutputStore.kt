package org.lsposed.lspatch.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.share.Constants
import org.lsposed.lspatch.util.LSPPackageManager
import java.io.File

/**
 * Where a patch writes its result, and who cleans it up.
 *
 * Patched apks are app-private ([LSPApplication.patchedDir]) rather than written into a folder the
 * user picked. Nothing about patching needs the user to choose a location -- the result's only
 * purpose is to be handed to the package installer moments later -- and asking for one made the
 * whole flow depend on a persisted SAF grant that could be missing, revoked, or (as it was) never
 * requested at all on one of the two entry points.
 *
 * One directory per package. The patcher names its outputs after the *input file's* basename only
 * (`base-<verCode>-lspatched.apk`), so every app patched into a shared directory would collide on
 * `base-…`; the package level is what keeps two apps' results apart.
 */
object PatchOutputStore {

    private const val TAG = "PatchOutputStore"

    private fun dirFor(packageName: String): File = lspApp.patchedDir.resolve(packageName)

    /**
     * An empty directory for [packageName]'s next patch.
     *
     * Cleared rather than reused: a previous run's splits left behind would be picked up by
     * [outputs] and installed alongside the new base, and a leftover file with the same name would
     * make the patcher refuse to start at all.
     */
    suspend fun prepare(packageName: String): File = withContext(Dispatchers.IO) {
        dirFor(packageName).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /**
     * The apks produced for [packageName], base first.
     *
     * Order matters to the installer only in that the base must be present; sorting it first keeps
     * session writes deterministic and the "base + N splits" summary honest.
     */
    suspend fun outputs(packageName: String): List<File> = withContext(Dispatchers.IO) {
        val files = dirFor(packageName).listFiles()
            ?.filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
            .orEmpty()
        files.sortedBy { it.name.startsWith("split_") }
    }

    /** Drops [packageName]'s output -- called once its apks have been installed successfully. */
    suspend fun discard(packageName: String) = withContext(Dispatchers.IO) {
        dirFor(packageName).deleteRecursively()
        Unit
    }

    /**
     * Drops output left behind by a patch that was never installed.
     *
     * A directory is kept only while its package is still installed *and* still patched -- that is
     * the one case where the user may yet want to install or export it. Anything else is a crash
     * or a cancellation away from being permanent, and these files are large.
     */
    suspend fun sweep() = withContext(Dispatchers.IO) {
        runCatching {
            val known = LSPPackageManager.appList.mapTo(mutableSetOf()) { it.app.packageName }
            lspApp.patchedDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name !in known) {
                    Log.i(TAG, "Sweeping stale patch output for ${dir.name}")
                    dir.deleteRecursively()
                }
            }
        }.onFailure { Log.w(TAG, "Sweep failed", it) }
        Unit
    }
}
