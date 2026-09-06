package frb.axeron.manager.ui.screen.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.generated.destinations.EnablePluginScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.R
import frb.axeron.manager.ai.RestoreScriptPreviewActivity
import frb.axeron.manager.ai.UninstallRollback
import frb.axeron.manager.ui.component.ConfirmResult
import frb.axeron.manager.ui.component.rememberConfirmDialog
import frb.axeron.manager.ui.component.rememberLoadingDialog
import frb.axeron.manager.ui.util.DownloadListener
import frb.axeron.manager.ui.util.download
import frb.axeron.manager.ui.viewmodel.PluginViewModel
import frb.axeron.manager.ui.viewmodel.SettingsViewModel
import frb.axeron.server.PluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginList(
    navigator: DestinationsNavigator,
    settings: SettingsViewModel,
    viewModel: PluginViewModel,
    modifier: Modifier,
    onInstallModule: (Uri) -> Unit,
    onClickModule: (plugin: PluginInfo) -> Unit,
    context: Context,
    snackBarHost: SnackbarHostState,
    listState: LazyListState
) {
    val failedEnable = stringResource(R.string.failed_enable_plugin)
    val failedDisable = stringResource(R.string.failed_disable_plugin)

    val loadingDialog = rememberLoadingDialog()
    val confirmDialog = rememberConfirmDialog()

    var expandedPluginId by rememberSaveable { mutableStateOf<String?>(null) }

    val updateText = stringResource(R.string.update)
    val changelogText = stringResource(R.string.changelog)
    val downloadingText = stringResource(R.string.downloading_plugin)
    val startDownloadingText = stringResource(R.string.start_downloading_plugin)
    val fetchChangeLogFailed = stringResource(R.string.fetch_changelog_failed)

    suspend fun onModuleUpdate(
        plugin: PluginInfo,
        changelogUrl: String,
        downloadUrl: String,
        fileName: String,
    ) {
        val changelogResult = loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                runCatching {
                    axeronApp.okhttpClient.newCall(
                        Request.Builder().url(changelogUrl).build()
                    ).execute().body!!.string()
                }
            }
        }

        val showToast: suspend (String) -> Unit = { msg ->
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val changelog = changelogResult.getOrElse {
            showToast(fetchChangeLogFailed.format(it.message))
            return
        }.ifBlank {
            showToast(fetchChangeLogFailed.format(plugin.prop.name))
            return
        }

        // changelog is not empty, show it and wait for confirm
        val confirmResult = confirmDialog.awaitConfirm(
            title = changelogText,
            content = changelog,
            markdown = true,
            confirm = updateText,
            dragHandle = true,
        )

        if (confirmResult != ConfirmResult.Confirmed) {
            return
        }

        showToast(startDownloadingText.format(plugin.prop.name))

        val downloading = downloadingText.format(plugin.prop.name)
        withContext(Dispatchers.IO) {
            download(
                context,
                downloadUrl,
                fileName,
                downloading,
                onDownloaded = onInstallModule,
                onDownloading = {
                    launch(Dispatchers.Main) {
                        Toast.makeText(context, downloading, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    val askUninstallPlugin = stringResource(R.string.ask_uninstall_plugin)
    val uninstall = stringResource(R.string.uninstall)
    val rollbackUninstall = stringResource(R.string.rollback_uninstall)
    val cancel = stringResource(R.string.cancel)
    val moduleUninstallConfirm = stringResource(R.string.uninstall_plugin_confirmation)
    val successUninstall = stringResource(R.string.plugin_uninstalled)
    val failedUninstall = stringResource(R.string.failed_to_uninstall_plugin)
    val restartService = stringResource(R.string.restart_service)
    val rollbackNoLog = stringResource(R.string.uninstall_rollback_no_log)
    val rollbackNoAi = stringResource(R.string.uninstall_rollback_no_ai)

    suspend fun doUninstall(plugin: PluginInfo): Boolean {
        val success = loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                AxeronPluginService.uninstallPlugin(plugin.dirId, plugin.backup)
            }
        }
        if (success) viewModel.fetchModuleList()
        return success
    }

    /**
     * v1.1.0 卸载回滚：uninstallPlugin 仅写 remove 标记（先卸载），
     * 随后引导用户执行 AI 生成的恢复脚本（撤销模块历史系统操作）。
     */
    suspend fun doUninstallWithRollback(plugin: PluginInfo) {
        // 1. 读取拦截器落盘的指令日志；无日志则提示并退回到普通卸载
        val logContent = UninstallRollback.readLog(context, plugin.prop, plugin.dirId)
        if (logContent.isNullOrBlank()) {
            Toast.makeText(context, rollbackNoLog, Toast.LENGTH_SHORT).show()
            doUninstall(plugin)
            return
        }
        // 2. 未配置云端 AI 时降级提示（无法生成恢复脚本）
        if (!frb.axeron.manager.ai.AIChatService.isCloudConfigured()) {
            val degrade = confirmDialog.awaitConfirm(
                askUninstallPlugin,
                content = rollbackNoAi,
                confirm = uninstall,
                dismiss = cancel
            )
            if (degrade == ConfirmResult.Confirmed) {
                doUninstall(plugin)
            }
            return
        }
        // 3. 始终基于最新拦截日志重新生成恢复脚本（去除缓存，选 A）
        loadingDialog.showLoading()
        val script = withContext(Dispatchers.IO) {
            UninstallRollback.generateRestoreScript(context, plugin.prop, logContent)
        }
        loadingDialog.hide()
        if (script.isNullOrBlank()) {
            Toast.makeText(context, rollbackNoAi, Toast.LENGTH_SHORT).show()
            doUninstall(plugin)
            return
        }
        // 4. 高危指令过滤（HIGH 风险行剔除，绝不执行）
        val validated = UninstallRollback.filterDangerous(script)
        // 5. 先卸载（写 remove 标记），再跳转预览执行界面让用户执行恢复脚本
        val uninstalled = doUninstall(plugin)
        if (!uninstalled) {
            Toast.makeText(context, failedUninstall.format(plugin.prop.name), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, RestoreScriptPreviewActivity::class.java)
            .putExtra(RestoreScriptPreviewActivity.EXTRA_SCRIPT, validated.script)
            .putExtra(RestoreScriptPreviewActivity.EXTRA_DIR_ID, plugin.dirId)
            .putExtra(RestoreScriptPreviewActivity.EXTRA_NAME, plugin.prop.name)
            .putStringArrayListExtra(
                RestoreScriptPreviewActivity.EXTRA_BLOCKED,
                ArrayList(validated.blockedLines)
            )
            // 原始完整脚本 + 危险行：供「不拦截」入口按需放回执行
            .putExtra(RestoreScriptPreviewActivity.EXTRA_FULL_SCRIPT, script)
            .putStringArrayListExtra(
                RestoreScriptPreviewActivity.EXTRA_DANGEROUS,
                ArrayList(validated.dangerousLines)
            )
            // 拦截到的原始指令清单（供恢复界面展示，验证拦截是否正确）
            .putExtra(
                RestoreScriptPreviewActivity.EXTRA_CAPTURED,
                UninstallRollback.buildCapturedCommandList(logContent)
            )
        context.startActivity(intent)
    }

    suspend fun onModuleUninstall(plugin: PluginInfo) {
        val confirmResult = confirmDialog.awaitConfirm(
            askUninstallPlugin,
            content = moduleUninstallConfirm.format(plugin.prop.name),
            confirm = uninstall,
            dismiss = cancel,
            neutral = rollbackUninstall
        )
        when (confirmResult) {
            ConfirmResult.Confirmed -> {
                doUninstall(plugin)
            }
            ConfirmResult.Neutral -> {
                doUninstallWithRollback(plugin)
            }
            else -> { /* Dismissed：取消 */ }
        }
    }

    val askRestorePlugin = stringResource(R.string.ask_restore_plugin)
    val restore = stringResource(R.string.restore)
    val moduleRestoreConfirm = stringResource(R.string.restore_plugin_confirmation)
    val pluginRestored = stringResource(R.string.plugin_restored)
    val failedRestore = stringResource(R.string.failed_to_restore_plugin)

    suspend fun onModuleRestore(plugin: PluginInfo) {

        val confirmResult = confirmDialog.awaitConfirm(
            askRestorePlugin,
            content = moduleRestoreConfirm.format(plugin.prop.name),
            confirm = restore,
            dismiss = cancel
        )
        if (confirmResult != ConfirmResult.Confirmed) {
            return
        }

        val success = loadingDialog.withLoading {
            withContext(Dispatchers.IO) {
                AxeronPluginService.restorePlugin(plugin.dirId, plugin.backup)
            }
        }
        if (success) {
            viewModel.fetchModuleList()
            Toast.makeText(context, pluginRestored, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, failedRestore, Toast.LENGTH_SHORT).show()
        }
    }
    PullToRefreshBox(
        modifier = modifier,
        isRefreshing = viewModel.isRefreshing,
        onRefresh = {
            viewModel.fetchModuleList()
        }
    ) {

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()).nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = remember {
                PaddingValues(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 120.dp
                )
            },
        ) {
            when {
                viewModel.pluginList.isEmpty() -> {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_plugin_installed),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    items(viewModel.pluginList) { plugin ->
                        val scope = rememberCoroutineScope()
                        val updatedModule by produceState(
                            key1 = plugin.prop.id,
                            initialValue = Triple("", "", "")
                        ) {
                            value = withContext(Dispatchers.IO) {
                                viewModel.checkUpdate(plugin)
                            }
                        }

                        PluginItem(
                            navigator = navigator,
                            settings = settings,
                            viewModel = viewModel,
                            plugin = plugin,
                            updateUrl = updatedModule.first,
                            onUninstall = {
                                scope.launch { onModuleUninstall(plugin) }
                            },
                            onRestore = {
                                scope.launch { onModuleRestore(plugin) }
                            },
                            onCheckChanged = { targetEnabled ->
                                scope.launch {
                                    // 启用方向：进入「启用分析」界面（strace 拦截 service.sh/post-fs-data.sh
                                    // 真实指令 → AI 弹窗决策 → 允许才真正 togglePlugin + ignite）
                                    // 禁用方向：保持原逻辑，直接 togglePlugin
                                    if (targetEnabled) {
                                        navigator.navigate(
                                            EnablePluginScreenDestination(plugin)
                                        )
                                        return@launch
                                    }

                                    val success = loadingDialog.withLoading {
                                        withContext(Dispatchers.IO) {
                                            AxeronPluginService.togglePlugin(
                                                plugin.dirId,
                                                false,
                                                plugin.backup
                                            )
                                        }
                                    }

                                    if (success) {
                                        viewModel.fetchModuleList()
                                    } else {
                                        snackBarHost.showSnackbar(
                                            failedDisable.format(plugin.prop.name)
                                        )
                                    }
                                }
                            },
                            onUpdate = {
                                scope.launch {
                                    onModuleUpdate(
                                        plugin,
                                        updatedModule.third,
                                        updatedModule.first,
                                        "${plugin.prop.name}-${updatedModule.second}.zip"
                                    )
                                    viewModel.fetchModuleList()
                                }
                            },
                            onClick = {
                                onClickModule(plugin)
                            },
                            expanded = expandedPluginId == plugin.prop.id,
                            onExpandToggle = {
                                expandedPluginId =
                                    if (expandedPluginId == plugin.prop.id) null else plugin.prop.id
                            }
                        )

                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }

        DownloadListener(context, onInstallModule)
    }
}