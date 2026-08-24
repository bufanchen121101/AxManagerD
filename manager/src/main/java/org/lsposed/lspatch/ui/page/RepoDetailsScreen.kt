package org.lsposed.lspatch.ui.page

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.lsposed.lspatch.data.repository.LSPStoreInstallHost
import org.lsposed.lspatch.data.repository.LSPStoreSettings
import org.lsposed.lspatch.data.repository.RepoRepository

/**
 * The module details page. A thin host over the shared `org.matrix.vector.ui.store.RepoDetailsScreen`:
 * it supplies an [LSPStoreInstallHost] so a module can be installed straight from its page — the same
 * install bar Vector shows — since a store module is an ordinary APK the manager can install.
 */
@Destination<RootGraph>
@Composable
fun RepoDetailsScreen(navigator: DestinationsNavigator, packageName: String) {
    val context = LocalContext.current
    val installHost = remember(packageName) { LSPStoreInstallHost(packageName) }
    org.matrix.vector.ui.store.RepoDetailsScreen(
        packageName = packageName,
        onNavigateBack = { navigator.navigateUp() },
        onOpenUrl = { url ->
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        dataSource = RepoRepository.getInstance(context),
        settings = LSPStoreSettings,
        host = installHost,
    )
}
