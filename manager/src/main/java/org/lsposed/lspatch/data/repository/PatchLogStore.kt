package org.lsposed.lspatch.data.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.lspApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last few patch reports on disk.
 *
 * A patch log used to live only in memory, for as long as the screen that produced it. That is
 * exactly backwards for the case it exists to serve: a patch that failed is usually diagnosed some
 * time later, by someone who was not watching, from a report the user sends on. Once the outcome
 * had been acknowledged the evidence was gone, and the only remaining trace was whatever of the
 * app's own `Log` calls had not yet rotated out of logcat.
 *
 * A handful of reports is enough -- they are small, and nobody debugs the tenth-most-recent patch --
 * so the directory is capped rather than left to grow for the life of the install.
 */
object PatchLogStore {

    private const val TAG = "PatchLogStore"

    /** How many reports to keep. Small: these are read newest-first, if at all. */
    private const val KEEP = 10

    private val dir: File
        get() = lspApp.noBackupFilesDir.resolve("patch-logs").also { it.mkdirs() }

    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)

    /**
     * Writes [report] as the record of a patch of [packageName].
     *
     * Named so the directory sorts chronologically and a reader can tell which app a report is
     * about without opening it.
     */
    suspend fun write(packageName: String, report: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val file = dir.resolve("${fileStamp.format(Date())}-$packageName.log")
            file.writeText(report)
            prune()
            file
        }.onFailure { Log.w(TAG, "Could not write the patch report", it) }.getOrNull()
    }

    /** Every kept report, newest first. */
    suspend fun recent(): List<File> = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name }.orEmpty()
    }

    suspend fun read(file: File): String = withContext(Dispatchers.IO) {
        runCatching { file.readText() }.getOrDefault("")
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun prune() {
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
