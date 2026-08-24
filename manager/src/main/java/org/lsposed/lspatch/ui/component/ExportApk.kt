package org.lsposed.lspatch.ui.component

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import frb.axeron.manager.R
import frb.axeron.manager.ui.util.LocalSnackbarHost
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Handed back by [rememberExportApk]; call [export] with the apks to save. */
class ExportApkLauncher internal constructor(
    private val onExport: (String, List<File>) -> Unit,
) {
    fun export(label: String, files: List<File>) = onExport(label, files)
}

/**
 * Saves patched apks wherever the user asks, once, on demand.
 *
 * Exporting is the only thing the old storage-directory grant was really for, and it was demanded
 * up front of everyone whether or not they ever wanted a copy -- which is what made patching depend
 * on a persisted permission and fail outright when one entry point never asked for it. A one-shot
 * grant at the moment of asking needs no permission to persist and nothing to go stale.
 *
 * One apk is written as an apk. Several are written as a zip, because a single grant yields a single
 * file, and silently exporting only the base of a split app would produce something that cannot be
 * installed.
 */
@Composable
fun rememberExportApk(): ExportApkLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val running = stringResource(R.string.patch_export_running)
    val done = stringResource(R.string.patch_export_done)
    val failed = stringResource(R.string.patch_export_failed)

    var pending by remember { mutableStateOf<List<File>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val files = pending
        pending = emptyList()
        if (uri == null || files.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            snackbarHost.showSnackbar(running)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    files.forEach { f ->
                        Log.i("ExportApk", "source file: ${f.name} exists=${f.exists()} length=${f.length()}")
                    }
                    // openOutputStream on some OEM DocumentsProviders (vivo) returns a stream that
                    // does not persist large writes on close, leaving a 0-byte document. Open the raw
                    // descriptor and fsync it so the bytes are on disk before the grant ends.
                    val pfd = context.contentResolver.openFileDescriptor(uri, "rwt")
                    Log.i("ExportApk", "openFileDescriptor(uri)=$pfd")
                    if (pfd == null) throw java.io.IOException("Cannot write to the chosen location")
                    pfd.use { fd ->
                        java.io.FileOutputStream(fd.fileDescriptor).use { output ->
                            if (files.size == 1) {
                                val written = files.first().inputStream().use { input ->
                                    input.copyTo(output).also { n -> Log.i("ExportApk", "copied $n bytes") }
                                }
                                output.flush()
                                fd.fileDescriptor.sync()
                                Log.i("ExportApk", "export done, written=$written")
                            } else {
                                ZipOutputStream(output).use { zip ->
                                    files.forEach { file ->
                                        zip.putNextEntry(ZipEntry(file.name))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                    }
                                }
                                fd.fileDescriptor.sync()
                            }
                        }
                    }
                }.onFailure { t ->
                    Log.e("ExportApk", "EXPORT FAILED: " + t, t)
                }.isSuccess
            }
            snackbarHost.showSnackbar(if (ok) done else failed)
        }
    }

    return remember {
        ExportApkLauncher { label, files ->
            if (files.isEmpty()) return@ExportApkLauncher
            pending = files
            val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "patched" }
            launcher.launch(if (files.size == 1) "$safe.apk" else "$safe-apks.zip")
        }
    }
}
