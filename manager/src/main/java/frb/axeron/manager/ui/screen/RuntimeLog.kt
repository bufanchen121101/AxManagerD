package frb.axeron.manager.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.AxeronRuntimeLog
import frb.axeron.manager.AxeronApplication
import frb.axeron.manager.R
import frb.axeron.manager.owner.DeviceOwnerPrivilege
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.manager.ui.util.LocalSnackbarHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志页面（软件内抓取日志入口）。
 *
 * ## 为什么需要它
 * 模块安装（exit code）、特权命令（DO/DP/shell 路由）、环境变量注入等故障，
 * 在设备上很难靠现象定位。本页把 [AxeronRuntimeLog] 记录的关键链路原样展示出来，
 * 并支持 **复制 / 导出到 Download / 一键环境快照 / 系统 logcat 快照**，
 * 使排查不再依赖 adb。
 *
 * ## 实现约束
 * - 只读 [AxeronRuntimeLog] 与系统公开 API，不改动任何既有功能；
 * - 显示上限 [MAX_DISPLAY_CHARS]，避免超长文本拖垮 Compose。
 */
private const val MAX_DISPLAY_CHARS = 120_000

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun RuntimeLogScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackBarHost = LocalSnackbarHost.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        content = AxeronRuntimeLog.snapshot().takeLast(MAX_DISPLAY_CHARS)
    }

    val copiedText = stringResource(R.string.runtime_log_copied)
    val clearedText = stringResource(R.string.runtime_log_cleared)
    val failedText = stringResource(R.string.runtime_log_failed)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AxeronRuntimeLog.init(context) }
        refresh()
    }

    Scaffold(
        topBar = {
            TopBar(
                logPath = AxeronRuntimeLog.filePath(),
                onBack = { navigator.popBackStack() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackBarHost) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { refresh() },
                    ) { Text(stringResource(R.string.runtime_log_refresh)) }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            copyToClipboard(context, AxeronRuntimeLog.snapshot())
                            scope.launch { snackBarHost.showSnackbar(copiedText) }
                        },
                    ) { Text(stringResource(R.string.runtime_log_copy)) }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            AxeronRuntimeLog.clear()
                            refresh()
                            scope.launch { snackBarHost.showSnackbar(clearedText) }
                        },
                    ) { Text(stringResource(R.string.runtime_log_clear)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val block = withContext(Dispatchers.IO) { buildEnvSnapshot(context) }
                                AxeronRuntimeLog.section("env snapshot")
                                AxeronRuntimeLog.appendRaw(block)
                                refresh()
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.runtime_log_snapshot)) }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val block = withContext(Dispatchers.IO) { dumpLogcat() }
                                AxeronRuntimeLog.section("logcat -d")
                                AxeronRuntimeLog.appendRaw(block)
                                refresh()
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.runtime_log_logcat)) }

                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { exportToDownload() }
                                refresh()
                                busy = false
                                snackBarHost.showSnackbar(
                                    r ?: failedText
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.runtime_log_export)) }
                }
            }

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                text = stringResource(
                    R.string.runtime_log_path,
                    AxeronRuntimeLog.filePath() ?: "unavailable"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 悬浮底栏：滚动内容底部留白（滚到底时最后一项不被玻璃栏遮住）
                    .padding(bottom = 120.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    text = content.ifBlank { stringResource(R.string.runtime_log_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 复制到剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AxManagerLog", text))
    }
}

/**
 * 环境快照：把定位问题所需的关键状态一次性抓下来。
 *
 * 只使用公开 API + [AxeronPluginService] 的公开 getter，全部包在 runCatching 里，
 * 任何一项失败都不影响其余项的采集。
 */
private fun buildEnvSnapshot(context: Context): String {
    val sb = StringBuilder()
    fun line(k: String, v: Any?) = sb.append(k).append(" = ").append(v).append('\n')

    line("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    line("device", Build.MANUFACTURER + " " + Build.MODEL)
    line("android", Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")")
    line("abi", Build.SUPPORTED_ABIS.joinToString(","))
    runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        line("app", context.packageName + " " + pi.versionName + " (" + pi.longVersionCode + ")")
    }
    runCatching { line("root_mode", AxeronPluginService.ROOT_MODE) }
    runCatching { line("AXERONDIR", AxeronPluginService.AXERONDIR) }
    runCatching { line("AXERONBIN", AxeronPluginService.AXERONBIN) }
    runCatching { line("BUSYBOX", AxeronPluginService.BUSYBOX) }
    runCatching { line("BUSYBOX exists", File(AxeronPluginService.BUSYBOX).exists()) }
    runCatching { line("BASEAPK", AxeronPluginService.BASEAPK) }
    runCatching {
        val f = File(AxeronPluginService.AXERONBIN, "functions.sh")
        line("functions.sh", "exists=" + f.exists() + " size=" + f.length())
    }
    runCatching {
        val f = File(AxeronPluginService.AXERONBIN, "functions.sh")
        val hasFn = f.exists() && f.readText().contains("install_plugin")
        line("functions.sh install_plugin", hasFn)
    }
    runCatching {
        val flags = DeviceOwnerState.queryOwnerFlags(context)
        line("isDeviceOwner", flags.first)
        line("isProfileOwner", flags.second)
        line("DeviceOwnerState.admin", DeviceOwnerState.admin.flattenToString())
    }
    runCatching { line("localDpmUsable", DeviceOwnerPrivilege.isLocalDpmUsable(context)) }
    runCatching {
        line("shizuku pingBinder", rikka.shizuku.Shizuku.pingBinder())
        line(
            "shizuku permission",
            rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    runCatching {
        line(
            "external storage writable",
            Environment.getExternalStorageDirectory()?.canWrite()
        )
    }
    return sb.toString()
}

/** 抓一次系统侧 logcat 快照（应用自身 uid 可见范围）。 */
private fun dumpLogcat(): String = runCatching {
    val process = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-t", "800", "-v", "time")
    )
    val out = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    process.destroy()
    out.ifBlank { "(empty logcat)" }
}.getOrElse { "logcat failed: ${it.message}" }

/** 导出日志到 Download 目录，返回结果文案；失败返回 null。 */
private fun exportToDownload(): String? = runCatching {
    val dir = File(Environment.getExternalStorageDirectory(), "Download")
    if (!dir.exists()) dir.mkdirs()
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val target = File(dir, "AxManagerLog_" + stamp + ".txt")
    target.writeText(
        buildString {
            append("=== AxManager runtime log ===\n")
            append("file: ").append(AxeronRuntimeLog.filePath()).append('\n')
            append("=== log ===\n")
            append(AxeronRuntimeLog.snapshot())
            append("\n=== env ===\n")
            append(buildEnvSnapshot(AxeronApplication.axeronApp))
        }
    )
    target.absolutePath
}.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    logPath: String?,
    onBack: () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.runtime_log),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (logPath != null) {
                    Text(
                        text = logPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}