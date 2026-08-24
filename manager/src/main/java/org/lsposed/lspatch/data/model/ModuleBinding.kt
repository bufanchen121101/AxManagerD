package org.lsposed.lspatch.data.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.material3.MaterialTheme
import frb.axeron.manager.R
import org.matrix.vector.ui.module.ModuleManifest

/** Where a module in a patch came from, which decides what can still be done with it. */
enum class ModuleOrigin {
    /** Installed on this device. Its apk can be re-read at any time. */
    Installed,

    /** Baked into a patched apk and not installed here. Recoverable only from that apk. */
    Embedded,

    /** Picked from storage for this patch. Lives in the temp dir until the patch consumes it. */
    Picked,
}

@Composable
fun ModuleOrigin.color(): Color = when (this) {
    ModuleOrigin.Installed -> MaterialTheme.colorScheme.secondary
    ModuleOrigin.Embedded -> MaterialTheme.colorScheme.tertiary
    ModuleOrigin.Picked -> MaterialTheme.colorScheme.primary
}

fun ModuleOrigin.labelRes(): Int = when (this) {
    ModuleOrigin.Installed -> R.string.patch_module_origin_installed
    ModuleOrigin.Embedded -> R.string.patch_module_origin_embedded
    ModuleOrigin.Picked -> R.string.patch_module_origin_file
}

/**
 * One module as a patch sees it, whatever mode the patch is in.
 *
 * The manager's existing currency for "a package" is `LSPPackageManager.AppInfo`, which wraps a live
 * `ApplicationInfo` and looks its icon up in a map keyed by installed packages. Neither holds for a
 * module baked into someone else's apk: it has no `ApplicationInfo` in the system, and asking for
 * its icon throws. Yet that is exactly the module an Integrated app's detail page has to list, with
 * the same name, version, API badge and description an installed one gets -- otherwise a patched
 * app's own modules read as second-class next to the installed ones.
 *
 * So this carries the answers rather than a handle to look them up with: the fields are already
 * resolved, [apkPath] points at something readable now, and [icon] is null when there genuinely is
 * no icon rather than a promise that throws.
 */
data class ModuleBinding(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val manifest: ModuleManifest?,
    val icon: ImageBitmap?,
    /** The apk this module can be embedded from, or null when it could not be recovered. */
    val apkPath: String?,
    val origin: ModuleOrigin,
) {
    /** A module whose apk could not be read cannot be carried into a re-patch. */
    val usable: Boolean
        get() = apkPath != null

    val apiVersion: Int
        get() = manifest?.targetApiVersion?.takeIf { it > 0 }
            ?: manifest?.minApiVersion?.takeIf { it > 0 }
            ?: 0
}
