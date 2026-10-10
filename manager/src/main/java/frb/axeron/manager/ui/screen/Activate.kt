package frb.axeron.manager.ui.screen




import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import frb.axeron.manager.owner.DpDoEscalation
import rikka.shizuku.Shizuku
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import frb.axeron.manager.ui.component.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import frb.axeron.adb.AdbPairingService
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.adb.util.AdbEnvironment
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.manager.R
import frb.axeron.manager.adb.AdbStateInfo
import frb.axeron.manager.ui.component.ConfirmResult
import frb.axeron.manager.ui.component.rememberConfirmDialog
import frb.axeron.manager.ui.component.rememberLoadingDialog
import frb.axeron.manager.ui.util.ClipboardUtil
import frb.axeron.manager.ui.viewmodel.ActivateViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
// 【v1.4.7】提权界面路由（compose-destinations 生成，位于 generated.destinations 包）。
import com.ramcosta.composedestinations.generated.destinations.ElevateScreenDestination

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.compatibility.DeviceCompatibility
private const val REQUEST_CODE_SHIZUKU = 7

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ActivateScreen(navigator: DestinationsNavigator, viewModelGlobal: ViewModelGlobal) {
    val activateViewModel = viewModelGlobal.activateViewModel
    val axeronInfo = activateViewModel.axeronInfo
    val activateStatus = activateViewModel.activateStatus

    // 【跳转修复】同时监听 axeronInfo 与 activateStatus：
    // activation 成功的权威信号是 activateStatus == Running（awaitRunning() 用的也是它），
    // 而 axeronInfo 只是它的一份快照，个别情况下（如 data class 相等、先 Running 后补齐）
    // 单独监听 axeronInfo 可能不触发重组，导致「激活成功却不跳回主页」。
    // 这里任一信号满足条件即返回上一页；不加 delay / popped 标记（那会造成崩溃）。
    LaunchedEffect(axeronInfo, activateStatus) {
        val running = activateStatus is ActivateViewModel.ActivateStatus.Running ||
                (axeronInfo.isRunning() && !axeronInfo.isNeedUpdate())
        if (running) {
            navigator.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.activate),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (DeviceCompatibility.isMiui()) {
                val notifStyle = Settings.System.getInt(
                    LocalContext.current.contentResolver,
                    "status_bar_notification_style",
                    1
                )
                if (notifStyle != 1) {
                    ElevatedCard(
                        colors = CardDefaults.cardColors().copy(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.notification_warn_miui),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.padding(4.dp))
                            Text(
                                text = stringResource(R.string.notification_warn_miui_2),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────
            // 【v1.2.0 UI 分区】本页分为两大区：
            //   ① 授权区（本区）：Shizuku / Dhizuku / DO / PO / 新权限（DP+WS）等一切"权限获取"
            //   ② 激活区（下方）：默认激活方式与各种激活入口
            // 注意：本次仅调整顺序 + 新增分区标题卡片，原有卡片一个不删、元件不新建页面。
            // ─────────────────────────────────────────────────────────────

            SectionHeader(
                titleRes = R.string.section_title_authorization,
                descRes = R.string.section_desc_authorization,
            )
            PermissionSections(activateViewModel)
            NewPermissionPathCard(navigator, activateViewModel)
            // 【v1.6.1】卡片 2：非 ADB 直连激活（无需 Shizuku 宿主身份、无需清账户）。
            // 按需求放在「授权区」，紧跟卡片 1 之后。
            DirectActivationCard(navigator, activateViewModel)
            // 【v1.4.6 UI】「临时设备所有者」「临时资料所有者」两张卡从激活区上移到授权区：
            // 它们本质是「获取权限身份」的手段，而不是「启动服务」的激活入口，放在授权区更顺。
            // 本次仅移动调用位置，卡片内部实现一行未改。
            TempDeviceOwnerCard(activateViewModel)
            TempProfileOwnerCard(activateViewModel)
            // 【本次需求】「设备所有者权限转移」卡从激活区上移到授权区末尾。
            // 仅调整调用位置，卡片内部实现一行未改。
            OwnerTransferCard(activateViewModel)

            SectionHeader(
                titleRes = R.string.section_title_activation,
                descRes = R.string.section_desc_activation,
            )
            // —— 激活区：DO/PO 激活入口（v1.3.0：由「授权区」下移至此） ——
            DeviceOwnerActivateCard(activateViewModel)
            RootCard(navigator, activateViewModel)
            if (AdbEnvironment.getAdbTcpPort() > 0) {
                TcpDebuggingCard(navigator, activateViewModel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WirelessDebuggingCard(navigator, activateViewModel)
            }
            // 【v1.3.1 仿 Scene】新增「通过 Shizuku 激活」卡片：
            // 用 Shizuku 的 shell(2000) 身份直接拉起 Axeron 服务（libaxeron.so），
            // 等效于在电脑上执行卡片里那条命令，省掉电脑与数据线。
            ShizukuLaunchCard(activateViewModel)
            ComputerCard()
        }
    }
}

@Composable
fun TcpDebuggingCard(
    navigator: DestinationsNavigator,
    activateViewModel: ActivateViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    ElevatedCard(
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Adb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.activate_by_tcp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.size(20.dp))

            Text(
                text = stringResource(R.string.activate_by_tcp_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.tcp_port_value, AdbEnvironment.getAdbTcpPort()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val ai = activateViewModel.startAdbTcp(context)
                            Toast.makeText(context, ai.message, Toast.LENGTH_SHORT).show()

                            if (ai is AdbStateInfo.Success) {
                                activateViewModel.awaitRunning()
                                // 【v2.0.1】连接动作成功 ≠ 激活完成：若 Axeron 服务仍未就绪，
                                // 如实再提示一次，避免「只弹成功却原地不动、也不跳转」误导用户。
                                if (activateViewModel.activateStatus !is ActivateViewModel.ActivateStatus.Running) {
                                    Toast.makeText(
                                        context,
                                        "已连上 ADB，但 Axeron 服务未就绪（未跳转）。请重试，或查看日志标签 AxManagerBinder。",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            activateViewModel.setTryToActivate(false)
                        }
                    }
                }
            ) {
                if (AdbEnvironment.getAdbTcpPort() != AxeronSettings.getTcpPort()) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                        contentDescription = "Restart"
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                        contentDescription = "Start"
                    )
                }
                Text(stringResource(R.string.connect_tcp_debugging))
            }

            Spacer(Modifier.size(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            activateViewModel.stopAdbTcp(context) { ai ->
                                scope.launch(Dispatchers.Main) {
                                    Toast.makeText(context, ai.message, Toast.LENGTH_SHORT).show()
                                }

                                Log.e("AxManagerStartAdb", ai.message, ai.cause)
                                activateViewModel.setTryToActivate(false)
                            }
                        }
                    }

                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Stop"
                )
                Text(stringResource(R.string.stop_tcp_debugging))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun WirelessDebuggingCard(
    navigator: DestinationsNavigator,
    activateViewModel: ActivateViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    // Panggil sekali untuk update state dari ViewModel
    LaunchedEffect(Unit) {
        activateViewModel.updateNotificationState(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        activateViewModel.updateNotificationState(context) // auto re-check izin
    }

    val launcherDeveloper = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        scope.launch {
            delay(500)
            if (activateViewModel.axeronInfo.isRunning()) {
                activateViewModel.setTryToActivate(false)
                return@launch
            }
            loadingDialog.withLoading {
                val ai = activateViewModel.startAdbWireless(context)
                if (ai is AdbStateInfo.Success) {
                    val intent = AdbPairingService.stopIntent(context)
                    context.startService(intent)
                    activateViewModel.awaitRunning()
                }
                activateViewModel.setTryToActivate(false)
            }
        }
    }

    val dialogDeveloper = rememberConfirmDialog()


    val uriHandler = LocalUriHandler.current
    val stepByStepUrl =
        "https://fahrez182.github.io/AxManager/guide/user-manual.html#start-with-wireless-debugging"

    LaunchedEffect(activateViewModel.devSettings) {
        if (activateViewModel.devSettings) {
            val packageName = "com.android.settings"
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

            try {
                val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(
                            packageName,
                            "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
                        )
                    )
                    addFlags(flags)
                }
                launcherDeveloper.launch(intent)
            } catch (e1: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                        addFlags(flags)
                    }
                    launcherDeveloper.launch(intent)
                } catch (e2: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        launcherDeveloper.launch(intent)
                    } catch (e3: Exception) {
                        Toast.makeText(context, "Tidak dapat membuka pengaturan", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            activateViewModel.setLaunchDevSettings(false)
        }
    }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.activate_by_wireless),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.size(20.dp))

            Text(
                text = stringResource(R.string.activate_by_wireless_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(20.dp))

            val title = stringResource(R.string.enable_wireless_debugging)
            val content = stringResource(R.string.enable_wireless_debugging_msg)
            val confirm = stringResource(R.string.open_developer_opt)
            val cancel = stringResource(R.string.cancel)
            val neutral = stringResource(R.string.step_by_step)
            Button(
                onClick = {
                    scope.launch {
                        val confirmResult = dialogDeveloper.awaitConfirm(
                            title = title,
                            content = content,
                            confirm = confirm,
                            dismiss = cancel,
                            neutral = neutral
                        )
                        if (confirmResult == ConfirmResult.Confirmed) {
                            activateViewModel.setLaunchDevSettings(true)
                        }
                        if (confirmResult == ConfirmResult.Neutral) {
                            uriHandler.openUri(stepByStepUrl)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Instruction"
                )
                Text(stringResource(R.string.instruction))
            }
            Spacer(modifier = Modifier.size(8.dp))

            Button(
                onClick = {
                    if (!activateViewModel.isNotificationEnabled) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        launcher.launch(intent)
                        return@Button
                    } else {
                        scope.launch {
                            loadingDialog.withLoading {
                                val ai = activateViewModel.startAdbWireless(context)
                                Toast.makeText(context, ai.message, Toast.LENGTH_SHORT).show()

                                if (ai is AdbStateInfo.Failed) {
                                    activateViewModel.startPairingService(context)
                                } else if (ai is AdbStateInfo.Success) {
                                    val intent = AdbPairingService.stopIntent(context)
                                    context.startService(intent)
                                    activateViewModel.awaitRunning()
                                }
                                activateViewModel.setTryToActivate(false)
                            }
                        }
                    }
                }
            ) {
                when {
                    !activateViewModel.isNotificationEnabled -> {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(16.dp),
                            contentDescription = null
                        )
                        Text(stringResource(R.string.enable_notification))
                    }

                    else -> {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(16.dp),
                            contentDescription = "Start"
                        )
                        Text(stringResource(R.string.start_pairing))
                    }
                }

            }
        }
    }
}

@SuppressLint("ShowToast")
@Composable
fun RootCard(
    navigator: DestinationsNavigator,
    activateViewModel: ActivateViewModel
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    ElevatedCard(
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.activate_by_root),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.size(20.dp))

            @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
            AndroidView(
                factory = { context ->
                    TextView(context).apply {
                        text = Html.fromHtml(
                            context.getString(
                                R.string.activate_by_root_msg,
                                "<b><a href=\"https://dontkillmyapp.com/\">Don\'t kill my app!</a></b>"
                            ),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                        movementMethod = LinkMovementMethod.getInstance()
                    }
                }
            )
            Spacer(modifier = Modifier.size(20.dp))
            val failed = stringResource(R.string.failed_to_start)
            val success = stringResource(R.string.activate_success)
            stringResource(R.string.please_wait)
            Button(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val state = activateViewModel.startRoot()
                            when (state) {
                                ActivateViewModel.ACTIVATE_FAILED -> {
                                    Toast.makeText(ctx, failed, Toast.LENGTH_SHORT).show()
                                }

                                ActivateViewModel.ACTIVATE_SUCCESS -> {
                                    Toast.makeText(ctx, success, Toast.LENGTH_SHORT).show()
                                    activateViewModel.awaitRunning()
                                }
                            }
                            activateViewModel.setTryToActivate(false)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Start"
                )
                Text(stringResource(R.string.start))
            }
        }
    }
}

/**
 * 【一键激活 DO】卡片。
 *
 * 通过 shell 指令激活**完整权限**的 Device Owner：
 *   `dpm set-device-owner --user 0 frb.axeron.manager/.owner.DeviceOwnerReceiver`
 *
 * 为什么不用 `cmd role add-role-holder android.app.role.DEVICE_POLICY_MANAGEMENT`：
 * 该角色是 GMS（com.google.android.gms）独占的 Qualification 角色，第三方应用
 * 没有资格持有，真机实测必然失败。`dpm set-device-owner` 才是官方通用路径。
 *
 * 前置条件：设备账户数为 0 且仅存在 User 0（不满足时 dpm 会返回明确错误）。
 *
 * 提供两个操作：
 *  ① 复制指令（供用户在 adb / 终端自行执行）
 *  ② 用 Shizuku 激活（页面已有 Shizuku 授权入口，无需软件本身已激活）
 */
@Composable
fun TempDeviceOwnerCard(activateViewModel: ActivateViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val confirmDialog = rememberConfirmDialog()

    // 进入页面时刷新一次临时 DO 状态
    // 【修复④】改为 ON_RESUME 触发：撤销 DO/PO 后从外部页面返回时，
    // 卡片能重新查询真实状态，避免仍显示「已生效」。
    OnResumeEffect {
        activateViewModel.refreshTempDoState()
    }

    // 【v1.9.0】展示 / 复制统一为「电脑端指令」（带 adb shell 前缀）；
    // 实际执行仍由 ViewModel 内部用裸命令交 Shizuku 执行，两条路径隔离。
    val cmd = activateViewModel.tempDoPcCommand
    val title = stringResource(R.string.temp_do_title)
    val copied = stringResource(R.string.copied)
    val copy = stringResource(R.string.copy)
    val cancel = stringResource(R.string.cancel)

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.size(20.dp))

            Text(
                text = stringResource(R.string.temp_do_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(12.dp))

            // 状态指示：临时 DO 是否生效
            Surface(
                color = if (activateViewModel.isTempDoActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = stringResource(
                        if (activateViewModel.isTempDoActive) {
                            R.string.temp_do_active
                        } else {
                            R.string.temp_do_inactive
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activateViewModel.isTempDoActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.size(16.dp))

            // 指令展示
            Text(
                text = cmd,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(20.dp))

            // ① 复制指令
            Button(
                onClick = {
                    scope.launch {
                        val result = confirmDialog.awaitConfirm(
                            title = title,
                            content = cmd,
                            markdown = false,
                            confirm = copy,
                            dismiss = cancel
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(ctx, cmd)) {
                                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Copy"
                )
                Text(stringResource(R.string.temp_do_copy))
            }

            Spacer(modifier = Modifier.size(8.dp))

            // ② 用 Shizuku 激活
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val r = activateViewModel.activateDeviceOwnerViaShizuku()
                            val msg = r.getOrElse { it.message ?: it.toString() }
                            Toast.makeText(ctx, msg.ifBlank { "OK" }, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Start"
                )
                Text(stringResource(R.string.temp_do_activate_by_shizuku))
            }
        }
    }
}

/**
 * 【v1.3.1 仿星野 Scene】「通过 Shizuku 激活」卡片。
 *
 * 与 [TempDeviceOwnerCard] 的本质区别：本卡片**不涉及 DO/PO 身份**，
 * 只是借用 Shizuku 提供的 shell(2000) 身份把 Axeron 服务（`libaxeron.so`）
 * 拉起来 —— 等效于在电脑上执行卡片里展示的那条命令，省掉电脑与数据线。
 *
 * 布局与同页其它卡片完全一致：ElevatedCard + （图标 + 标题）+ 描述 +
 * 状态块 + 「复制指令」按钮 + 「用 Shizuku 激活」按钮；
 * 复制走与同页一致的确认弹窗（[rememberConfirmDialog]）。
 */
@Composable
fun ShizukuLaunchCard(activateViewModel: ActivateViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val confirmDialog = rememberConfirmDialog()
    // 进入页面时刷新一次 Shizuku 真实授权状态。
    // 【v1.3.1】该刷新内部含原版 Shizuku 的主动拉取通道修复（ShizukuBinderPuller）：
    // 推送通道漏发 binder 时，这里会主动「要」一次，避免状态误判为未授权。
    OnResumeEffect {
        activateViewModel.refreshShizukuState()
    }
    // 展示 / 复制统一为裸命令（不带 `adb shell` 前缀）：
    // 前缀属于电脑端，交给设备内 shell 执行会失败；与 [TempDeviceOwnerCard] 约定相同。
    val cmd = activateViewModel.shizukuLaunchCommand
    val title = stringResource(R.string.shizuku_launch_title)
    val copied = stringResource(R.string.copied)
    val copy = stringResource(R.string.copy)
    val cancel = stringResource(R.string.cancel)
    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.shizuku_launch_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(12.dp))
            // 状态指示：Shizuku 是否已授权
            // 注意：未授权时下方按钮**不禁用** —— 点击会先走主动拉取 + 授权请求，
            // 避免「没有 binder 就静默失败」这一老问题。
            Surface(
                color = if (activateViewModel.isShizukuActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = stringResource(
                        if (activateViewModel.isShizukuActive) {
                            R.string.shizuku_launch_ready
                        } else {
                            R.string.shizuku_launch_not_ready
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activateViewModel.isShizukuActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.size(16.dp))
            // 指令展示
            Text(
                text = cmd,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(20.dp))
            // ① 复制指令
            Button(
                onClick = {
                    scope.launch {
                        val result = confirmDialog.awaitConfirm(
                            title = title,
                            content = cmd,
                            markdown = false,
                            confirm = copy,
                            dismiss = cancel
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(ctx, cmd)) {
                                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Copy"
                )
                Text(stringResource(R.string.shizuku_launch_copy))
            }
            Spacer(modifier = Modifier.size(8.dp))
            // ② 用 Shizuku 激活
            OutlinedButton(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val r = activateViewModel.activateViaShizuku()
                            val msg = r.getOrElse { it.message ?: it.toString() }
                            Toast.makeText(ctx, msg.ifBlank { "OK" }, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = "Start"
                )
                Text(stringResource(R.string.shizuku_launch_action))
            }
        }
    }
}

/**
 * 【权限转移】卡片（参照 OwnDroid）。
 *
 * 仅在当前应用为 Device Owner 时可用：把 DO 身份转移给系统里另一个具备
 * 设备管理接收器的应用（`dpm.transferOwnership`，Android 9+）。
 */
@Composable
fun TempProfileOwnerCard(activateViewModel: ActivateViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    // 【修复④】改为 ON_RESUME 触发：撤销 DO/PO 后从外部页面返回时，
    // 卡片能重新查询真实状态，避免仍显示「已生效」。
    OnResumeEffect {
        activateViewModel.refreshProfileOwnerState()
    }
    // 【v1.9.0】展示 / 复制统一为电脑端指令（带 adb shell 前缀），执行仍走裸命令
    val cmd = activateViewModel.tempProfileOwnerPcCommand
    val title = stringResource(R.string.temp_profile_owner_title)
    val copied = stringResource(R.string.copied)
    // 【v1.9.0】统一交互：复制前先弹窗展示指令
    // （此前本卡片是「点击直接复制」且弹窗标题缺失，与同页其它卡片不一致）
    val confirmDialog = rememberConfirmDialog()
    val copyLabel = stringResource(R.string.copy)
    val cancelLabel = stringResource(R.string.cancel)
    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.size(20.dp))
            Text(
                text = stringResource(R.string.temp_profile_owner_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(12.dp))

            Surface(
                color = if (activateViewModel.isProfileOwnerActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = stringResource(
                        if (activateViewModel.isProfileOwnerActive) {
                            R.string.temp_profile_owner_active
                        } else {
                            R.string.temp_profile_owner_inactive
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activateViewModel.isProfileOwnerActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                text = cmd,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.size(20.dp))

            Button(
                onClick = {
                    // 【v1.9.0】统一为「点击 → 弹窗展示指令 → 确认后复制」
                    scope.launch {
                        val result = confirmDialog.awaitConfirm(
                            title = title,
                            content = cmd,
                            markdown = false,
                            confirm = copyLabel,
                            dismiss = cancelLabel
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(ctx, cmd)) {
                                Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.temp_profile_owner_copy))
            }
            Spacer(modifier = Modifier.size(8.dp))

            OutlinedButton(
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val r = activateViewModel.activateProfileOwnerViaShizuku()
                            val msg = r.getOrElse { it.message ?: it.toString() }
                            Toast.makeText(ctx, msg.ifBlank { "OK" }, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.temp_profile_owner_activate_by_shizuku))
            }
        }
    }
}

@Composable
fun OwnerTransferCard(activateViewModel: ActivateViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 仅 DO / Profile Owner 才显示该卡片
    if (!activateViewModel.canRemoveOwner) return

    LaunchedEffect(Unit) {
        activateViewModel.refreshTransferTargets()
    }

    val confirmDialog = rememberConfirmDialog()
    val targets = activateViewModel.transferTargets

    // 在 @Composable 作用域内预先解析字符串（不能在 onClick 回调里调用 stringResource）
    val titleStr = stringResource(R.string.owner_transfer_title)
    val confirmStr = stringResource(R.string.confirm)
    val dismissStr = stringResource(R.string.cancel)
    val confirmTemplate = stringResource(R.string.owner_transfer_confirm)
    val successStr = stringResource(R.string.owner_transfer_success)
    val failedTemplate = stringResource(R.string.owner_transfer_failed)

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.owner_transfer_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.size(20.dp))

            Text(
                text = stringResource(R.string.owner_transfer_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(16.dp))

            if (targets.isEmpty()) {
                Text(
                    text = stringResource(R.string.owner_transfer_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(12.dp))
                OutlinedButton(
                    onClick = { activateViewModel.refreshTransferTargets() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                        contentDescription = null
                    )
                    Text(stringResource(R.string.owner_transfer_refresh))
                }
            } else {
                targets.forEach { t ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = confirmDialog.awaitConfirm(
                                    title = titleStr,
                                    content = confirmTemplate.format(t.label),
                                    markdown = false,
                                    confirm = confirmStr,
                                    dismiss = dismissStr
                                )
                                if (result == ConfirmResult.Confirmed) {
                                    activateViewModel.transferOwnership(t.receiver) { ok, err ->
                                        val msg = if (ok) {
                                            successStr
                                        } else {
                                            failedTemplate.format(err.orEmpty())
                                        }
                                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !activateViewModel.isTransferring,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(t.label)
                            Text(
                                text = t.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComputerCard() {
    val context = LocalContext.current

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // Result kalau butuh, biasanya kirim aja kosong kalau cuma share
    }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.activate_by_computer),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = stringResource(R.string.activate_by_computer_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val dialogDeveloper = rememberConfirmDialog()
            val scope = rememberCoroutineScope()

            val title = stringResource(R.string.view_command)
            val content = stringResource(
                R.string.view_command_message,
                Starter.adbCommand
            )
            val confirm = stringResource(R.string.copy)
            val dismiss = stringResource(R.string.cancel)
            val neutral = stringResource(R.string.send)
            val share = stringResource(R.string.share_command)
            val copied = stringResource(R.string.copied)

            Button(
                onClick = {

                    scope.launch {
                        val confirmResult = dialogDeveloper.awaitConfirm(
                            title = title,
                            content = content,
                            markdown = true,
                            confirm = confirm,
                            dismiss = dismiss,
                            neutral = neutral
                        )
                        if (confirmResult == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(context, Starter.adbCommand)) {
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (confirmResult == ConfirmResult.Neutral) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                            }

                            shareLauncher.launch(
                                Intent.createChooser(
                                    intent,
                                    share
                                )
                            )
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = title
                )
                Text(title)
            }
        }
    }
}

/**
 * 【修复④】在每次界面回到前台（ON_RESUME）时执行 [onResume]。
 *
 * 用于替代 `LaunchedEffect(Unit)`：后者只在 Composable 首次进入组合时执行一次，
 * 导致「撤销 DO/PO 或外部撤销 Shizuku 授权后返回本页」时状态不刷新、
 * 仍显示旧的「已生效 / 已授权」。
 *
 * 仅使用 androidx.lifecycle 核心库（LocalLifecycleOwner + LifecycleEventObserver），
 * 不额外引入 lifecycle-runtime-compose 依赖，也不改动任何公共组件。
 */
@Composable
private fun OnResumeEffect(onResume: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun DeviceOwnerActivateCard(activateViewModel: ActivateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    // 进入界面时刷新一次真实 Owner 状态，避免激活后回到本页仍显示未激活。
// 【修复④】改为 ON_RESUME 触发：撤销 DO/PO 后从外部页面返回时，
    // 卡片能重新查询真实状态，避免仍显示「已生效」。
    OnResumeEffect {
        activateViewModel.refreshOwnerState()
    }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.activate_by_owner),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = stringResource(R.string.activate_by_owner_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 【Bug 修复】前置条件不再只认「本应用自身是 DO/PO」：
            // 经第三方授权（Dhizuku / Android 14+ 的 Device Policy Role）取得设备所有者
            // 特权的用户，同样满足本卡片条件 —— 底层 DeviceOwnerAdbActivator 的
            // resolveDpm / resolveAdmin 已支持转发通道，旧版本也正是这样可用的。
            val ownerActive = activateViewModel.isOwnerPrivilegeAvailable

            // 状态指示：是否已具备设备所有者（本激活方式的前置条件）
            Surface(
                color = if (ownerActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        modifier = Modifier.size(14.dp),
                        imageVector = if (ownerActive) Icons.Filled.CheckCircle else Icons.Filled.Security,
                        contentDescription = null,
                        tint = if (ownerActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = stringResource(
                            if (ownerActive) R.string.owner_activate_ready
                            else R.string.owner_activate_need_owner
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // —— Step 1：把 AxManager 设为设备所有者（一次性，需一条 ADB 指令） ——
            Text(
                text = stringResource(R.string.owner_activate_prereq_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.owner_activate_prereq_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val ownerCommand = activateViewModel.deviceOwnerCommand
            val cmdDialog = rememberConfirmDialog()
            val cmdTitle = stringResource(R.string.device_owner_command)
            val cmdContent = stringResource(R.string.device_owner_command_message, ownerCommand)
            val copyLabel = stringResource(R.string.copy)
            val cancelLabel = stringResource(R.string.cancel)
            val sendLabel = stringResource(R.string.send)
            val copiedLabel = stringResource(R.string.copied)
            val shareLabel = stringResource(R.string.share_command)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = cmdDialog.awaitConfirm(
                            title = cmdTitle,
                            content = cmdContent,
                            markdown = true,
                            confirm = copyLabel,
                            dismiss = cancelLabel,
                            neutral = sendLabel
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(context, ownerCommand)) {
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (result == ConfirmResult.Neutral) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ownerCommand)
                            }
                            shareLauncher.launch(Intent.createChooser(intent, shareLabel))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(cmdTitle)
            }

            // —— Step 2：用设备所有者权限开 ADB 并激活 ——
            Text(
                text = stringResource(R.string.owner_activate_adb_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.owner_activate_adb_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val failedTemplate = stringResource(R.string.owner_activate_failed, "%s")
            Button(
                enabled = ownerActive && !activateViewModel.tryActivate,
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val ai = activateViewModel.startAdbByDeviceOwner(context)
                            when (ai) {
                                is AdbStateInfo.Success -> {
                                    activateViewModel.awaitRunning()
                                }

                                is AdbStateInfo.Failed -> {
                                    Toast.makeText(
                                        context,
                                        failedTemplate.format(ai.message),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                else -> {
                                    Toast.makeText(context, ai.message, Toast.LENGTH_SHORT).show()
                                }
                            }
                            activateViewModel.setTryToActivate(false)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.owner_activate_start))
            }
            // 【v1.9.0 UI 精简】原「端口自启动」区块已按需求整体删除
            // （字符串 port_boot_start / port_boot_start_desc / port_boot_start_no_port
            //  仅被此处引用，删除后不再出现在激活页；Settings 里的开机自启开关不受影响）。
        }
    }
}

@Composable
fun PermissionSections(activateViewModel: ActivateViewModel) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.activate_permission_group),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.activate_permission_group_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ShizukuSection(activateViewModel)
        DhizukuSection(activateViewModel)
        BatteryOptimizationSection()
    }
}

// =====================================================================
// 三段式分区：大标题（授权 / 激活）
//
// 【设计约束】仅新增组件，不改动任何既有 Composable 的内部实现。
// 通过调整 ActivateScreen 中 Column 的调用顺序 + 在两个位置插入本标题，
// 把原本混杂的「授权」与「激活」两件事在视觉上分开。
// =====================================================================

/**
 * 分区大标题：一个大字 + 一行说明。
 *
 * @param titleRes 标题文案资源
 * @param descRes  说明文案资源（可选，传 null 则不显示）
 */
@Composable
fun SectionHeader(
    titleRes: Int,
    descRes: Int? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (descRes != null) {
            Text(
                text = stringResource(descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 卡片：`DP` 差异化激活设备所有者（**非 ADB 直连路径**）。
 *
 * ## 方案说明（v1.6.0 重写）
 *
 * 旧实现走「DP Role + Shizuku(ADB)」：Shizuku 以 shell(uid 2000) 身份执行
 * `dpm set-device-owner`，命中 `setDeviceOwner()` 的 **ADB 分支**，
 * 必须过三道闸 —— `hasUserSetupCompleted` + `nonTestNonPrecreatedUsersExist`
 * + `hasIncompatibleAccountsOrNonAdb` → **必须清空全部账户**，否则失败。
 *
 * 新实现走 **非 ADB 分支**：拿到 DP Role 后，用**应用自身身份**（`isAdb()=false`）
 * 反射直连 `DevicePolicyManager`：
 * ```
 * forceUpdateUserSetupComplete(0)   ← 反向覆盖内存 mUserSetupComplete，绕开 setup 闸
 * setActiveAdmin(self, true)        ← MANAGE_DEVICE_ADMINS（DP Role 已含）
 * setDeviceOwner(self, null, 0)     ← 非 ADB 分支**完全不读账户**
 * ```
 * → **不清账户、不删隐藏账号也能激活**。这是 DP 路相对 Dhizuku 的核心差异。
 *
 * ## AOSP 依据（A13→A17 五版本逐行取证，语义完全一致）
 *
 * - `DevicePolicyManagerService#setDeviceOwner` 的 `if (isAdb) { ... } else { ... }`：
 *   `hasIncompatibleAccountsOrNonAdb` **只在 `if (isAdb)` 块内被读取**；
 * - `else` 分支只查 `hasUserSetupCompleted`，返回 `STATUS_USER_SETUP_COMPLETED` 或 `STATUS_OK`；
 * - `forceUpdateUserSetupComplete` 在 A13/A15/A16/A17 均存在（A17 仅内联重构），
 *   实现体对 `policy.mUserSetupComplete` **双向赋值**；
 * - `MANAGE_PROFILE_AND_DEVICE_OWNERS` 自 A13 起 protectionLevel = `signature|role`，
 *   DP Role 的 `<permissions>` 已显式列出 → 持 Role 即持权限。
 *
 * 详见项目文档 `AxManagerD_AOSP_13to17_DP差异化取证.md`。
 *
 * ## 版本限制
 *
 * - **Android 13+（API 33+）**：`DEVICE_POLICY_MANAGEMENT` Role 自 A13 引入 → 可用；
 * - **Android 12 及以下**：无该 Role → 卡片置灰，请改用下方纯 Shizuku/Dhizuku 通道。
 *
 * 【设计约束】独立卡片，不改动其它既有实现。
 *
 * 【v1.4.7 变更】主按钮不再原地执行激活，而是**跳转到独立提权界面**（ElevateScreen）：
 * 提权界面用 ASCII 大字展示品牌、实时输出全部命令结果，并在结束时给出
 * 成功（旋转箭头）/ 失败（叉号）的醒目反馈。激活逻辑封装在
 * `ActivateViewModel.runElevateDirectFlow()`。
 */
@Composable
fun NewPermissionPathCard(
    navigator: DestinationsNavigator,
    activateViewModel: ActivateViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    // 【v1.6.3 修复】改为 ON_RESUME 触发。
    //
    // 旧写法 `LaunchedEffect(Unit)` 只在**首次进入组合**时执行一次。
    // 而本卡的激活按钮会 `navigate(ElevateScreenDestination)` 跳到提权页，
    // 提权完成后 `popBackStack()` 返回本页 —— 此时 ActivateScreen 在返回栈中
    // **并未被销毁**，`LaunchedEffect(Unit)` 不会重跑，于是 isDpGranted /
    // isDirectActivationAvailable / isDeviceOwner 全部保持旧值，
    // 出现「提权明明成功、返回卡片却仍显示未授予」的现象。
    // 改用本文件既有的 [OnResumeEffect]（ON_RESUME 每次回前台都触发）。
    //
    // 注意：`refreshDpDoDiagnostics` / `refreshNewPermissionState` 是 suspend，
    // 而 OnResumeEffect 的回调是普通 lambda（非挂起），必须用 scope.launch 包裹。
    OnResumeEffect {
        activateViewModel.refreshOwnerState()
        activateViewModel.refreshDirectActivationAvailability()
        scope.launch {
            activateViewModel.refreshDpDoDiagnostics()
            activateViewModel.refreshNewPermissionState()
        }
    }

    val versionSupported = activateViewModel.isDpDoVersionSupported
    val shizukuReady = activateViewModel.isShizukuActive
    val isDeviceOwner = activateViewModel.isDeviceOwner
    val directAvailable = activateViewModel.isDirectActivationAvailable
    val diag = activateViewModel.dpDoDiagnostics

    val activateFailTemplate = stringResource(R.string.dpdo_activate_failed, "%s")
    val deactivateOkLabel = stringResource(R.string.dpdo_deactivate_ok)
    val shizukuNeededLabel = stringResource(R.string.new_perm_shizuku_required)
    val copiedLabel = stringResource(R.string.copied)

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— 标题 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dpdo_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.dpdo_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // —— 版本不满足提示（Android 12 及以下）——
            if (!versionSupported) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.dpdo_version_unsupported,
                            Build.VERSION_CODES.TIRAMISU
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // —— 状态行 ——
            StatusRow(
                label = stringResource(R.string.dpdo_status_dp_role),
                granted = activateViewModel.isDpGranted
            )
            StatusRow(
                label = stringResource(R.string.dpdo_status_shizuku),
                granted = shizukuReady
            )
            // 【v1.6.0】新增：非 ADB 直连通道是否就绪（DP Role 带来的 MAPDO 权限）。
            StatusRow(
                label = stringResource(R.string.dpdo_status_direct_channel),
                granted = directAvailable
            )
            StatusRow(
                label = stringResource(R.string.dpdo_status_device_owner),
                granted = isDeviceOwner
            )

            // —— 【v1.6.1】本卡为「DP + Shizuku」路径说明 ——
            //
            // 走 shell(ADB) 身份执行 dpm，命中 ADB 分支（需过账户/用户三道闸）。
            // 因此本卡会在首次失败后**自动自救**：删除隐藏用户 999、
            // 并临时冻结「非系统」的账户所属应用，重试后自动解冻。
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.dpdo_rescue_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (diag != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.dpdo_direct_accounts_info, diag.accountCount, diag.userCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // —— 主按钮：激活设备所有者（跳转到提权界面）——
            Button(
                enabled = versionSupported && !isDeviceOwner && !activateViewModel.tryActivate,
                onClick = {
                    if (!shizukuReady) {
                        Toast.makeText(context, shizukuNeededLabel, Toast.LENGTH_LONG).show()
                    } else {
                        // 【v1.6.1】卡片 1 走「DP + Shizuku」模式：
                        // 先尝试激活，失败则删 999 隐藏用户 + 冻结非系统账户应用后重试。
                        activateViewModel.setElevateMode(
                            ActivateViewModel.ELEVATE_MODE_DP_SHIZUKU
                        )
                        navigator.navigate(ElevateScreenDestination)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.dpdo_activate))
            }

            // —— 【v1.9.0】次按钮：撤销 DP 权限 ——
            // 原「移除设备所有者」按钮已按需求从本卡片移除（该能力仅保留在授权区的
            // 「设备所有者 / Dhizuku」卡片中），本卡片只保留「撤销 DP 权限」。
            val revokeDpDialog = rememberConfirmDialog()
            val revokeDpTitle = stringResource(R.string.new_perm_revoke_dp)
            val revokeDpDesc = stringResource(R.string.new_perm_revoke_dp_desc)
            val revokeDpScriptText = activateViewModel.revokeDpScript
            val revokeDpMsg = revokeDpDesc + "\n\n```sh\n" + revokeDpScriptText + "\n```"
            val revokeDpCancel = stringResource(R.string.cancel)
            val revokeDpOk = stringResource(R.string.new_perm_revoke_dp_ok)
            val revokeDpFail = stringResource(R.string.new_perm_revoke_dp_failed)
            OutlinedButton(
                // 撤销经 Shizuku 执行，未运行/未授权时置灰，避免点了没反应
                enabled = activateViewModel.isShizukuActive,
                onClick = {
                    scope.launch {
                        // 【v1.9.0】统一交互：先弹窗展示指令，确认后再执行（复制 + 撤销）
                        val result = revokeDpDialog.awaitConfirm(
                            title = revokeDpTitle,
                            content = revokeDpMsg,
                            markdown = true,
                            confirm = revokeDpTitle,
                            dismiss = revokeDpCancel,
                        )
                        if (result == ConfirmResult.Confirmed) {
                            ClipboardUtil.put(context, revokeDpScriptText)
                            loadingDialog.withLoading {
                                val r = activateViewModel.revokeDpViaShizuku()
                                val msg = r.getOrElse { it.message ?: it.toString() }
                                Toast.makeText(
                                    context,
                                    if (r.isSuccess) revokeDpOk else revokeDpFail.format(msg),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.new_perm_revoke_dp))
            }

            // —— 辅助按钮：查看 / 复制 shell 指令 ——
            val scriptDialog = rememberConfirmDialog()
            val scriptTitle = stringResource(R.string.new_perm_script_title)
            val scriptDesc = stringResource(R.string.new_perm_script_desc)
            val copyLabel = stringResource(R.string.copy)
            val cancelLabel = stringResource(R.string.cancel)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val body = scriptDesc + "\n\n```sh\n" +
                                activateViewModel.grantDpScript + "\n```"
                        val result = scriptDialog.awaitConfirm(
                            title = scriptTitle,
                            content = body,
                            markdown = true,
                            confirm = copyLabel,
                            dismiss = cancelLabel,
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(context, activateViewModel.grantDpScript)) {
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.new_perm_view_script))
            }
        }
    }
}

/**
 * 【v1.6.1】卡片 2：`非 ADB 直连`激活设备所有者（**无需删除账号**）。
 *
 * ## 与卡片 1「DP + Shizuku」的区别
 *
 * | 项 | 卡片 1（DP + Shizuku） | 卡片 2（本卡，非 ADB 直连） |
 * |---|---|---|
 * | 执行身份 | shell uid 2000（`isAdb=true`） | **应用自身身份**（`isAdb=false`） |
 * | 账户要求 | 命中 ADB 分支，需清账户（本卡自动自救） | **完全不读账户，无需清理** |
 * | 需要 Shizuku | 需要（提供 shell 身份） | 激活本身不需要；但授 DP Role 与置 0/恢复 1 需要 |
 * | 电脑指令 | 无 | **无**（v1.6.7 起改为 Shizuku 可执行的 shell 指令） |
 *
 * ## 界面构成
 *  - 标题：「激活设备所有者（无需删账号）」
 *  - **红色危险提示**（v1.6.7 新增）：仅测试 Android 13 + 导航键失灵
 *  - 状态行：DP 角色 / 直连通道（MAPDO） / 设备所有者
 *  - **一键激活按钮**：点击即跳转提权界面执行直连激活
 *  - **复制 shell 指令**按钮（v1.6.7：由「电脑端 adb 指令」改为 Shizuku 指令）
 *  - 辅助：解除设备所有者（已持有时显示）
 *
 * ## AOSP 依据（v1.6.7 更正）
 *
 * 非 ADB 分支只查 `hasUserSetupCompleted(USER_SYSTEM)`，**不读 `hasIncompatibleAccountsOrNonAdb`**
 * —— 所以「清账户」对本卡毫无意义。该闸**可以绕开**（v1.6.6 真机实测）：
 *   ① shell 身份 `settings put secure user_setup_complete 0`
 *   ② 应用自身 `forceUpdateUserSetupComplete(0)` 把该值同步进 DPM 内存态
 *   ③ `setActiveAdmin` → `setDeviceOwner` 放行
 *   ④ shell 身份 `settings put secure user_setup_complete 1` 恢复
 *
 * **⚠️ v1.6.7 关键修复**：以上 4 步必须**先置 0 再激活**。旧版（v1.6.6）是
 * 「先激活 → 失败 → 再置 0 重试」，而 `activate()` 的向导预检位于
 * `forceUpdateUserSetupComplete` 之前，首次调用必定因预检发现向导已完成而
 * 直接返回，第 3 步从未执行 —— 这就是「激活前没有运行 settings put ... 0」
 * 导致的失败根因。现在改为「激活前先置 0」（见
 * `ActivateViewModel#runElevateDirectFlow` 的 ④ 段）。
 *
 * A13→A16 逐版本取证见 `AxManagerD_AOSP_13to17_DP差异化取证.md`。
 */
@Composable
fun DirectActivationCard(
    navigator: DestinationsNavigator,
    activateViewModel: ActivateViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    // 【v1.6.3 修复】与卡片 1 同理：`LaunchedEffect(Unit)` 在从提权页返回时
    // 不会重跑（ActivateScreen 未被销毁），导致状态行停留在旧值。
    // 改用 ON_RESUME 触发，回到本页即重新查询真实权限状态。
    // 注意 suspend 的两个刷新必须用 scope.launch 包裹（回调非挂起）。
    OnResumeEffect {
        activateViewModel.refreshOwnerState()
        activateViewModel.refreshDirectActivationAvailability()
        scope.launch {
            activateViewModel.refreshDpDoDiagnostics()
            activateViewModel.refreshNewPermissionState()
            // 【v1.7.0】回到本页时刷新「开机向导闸」状态（走 Shizuku 回读）。
            // 应用自身读不到该 @hide 键，必须经 shell 身份读，故放在 suspend 作用域里。
            activateViewModel.refreshSetupGateState()
        }
    }

    val versionSupported = activateViewModel.isDpDoVersionSupported
    val directAvailable = activateViewModel.isDirectActivationAvailable
    val isDeviceOwner = activateViewModel.isDeviceOwner
    // 【v1.6.5】向导已完成 → 非 ADB 分支硬闸关闭，本卡直接不可用（如实在卡片上说明）。
    val setupCompleted = activateViewModel.isSetupCompleted
    // 【v1.7.0】「开机向导闸」是否已打开（经 Shizuku 回读，见 refreshSetupGateState）。
    // 这是「激活」按钮灰化的唯一依据 —— 不能用 setupCompleted（应用侧读不到该键）。
    val setupGateOpen = activateViewModel.isSetupGateOpen
    val setupGateBusy = activateViewModel.isSetupGateBusy
    val setupGateMsg = activateViewModel.setupGateMessage

    val copiedLabel = stringResource(R.string.copied)
    val shizukuNeededLabel = stringResource(R.string.new_perm_shizuku_required)
    val activateFailTemplate = stringResource(R.string.dpdo_activate_failed, "%s")
    val deactivateOkLabel = stringResource(R.string.dpdo_deactivate_ok)
    val pcScript = activateViewModel.pcAdbCommands

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // —— 标题 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dpdo_direct_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.dpdo_direct_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // —— 版本不满足提示 ——
            if (!versionSupported) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.dpdo_version_unsupported,
                            Build.VERSION_CODES.TIRAMISU
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // —— 【v1.6.5 新增 / v1.6.6 语义更新】向导已完成时的说明 ——
            //
            // 旧版这里是红色「本卡不可用」提示，并同时置灰按钮。
            // 【v1.6.6】起本卡改为**自动处理**该硬闸（临时置 0 → 激活 → 恢复 1），
            // 因此改为中性色的「注意事项」，按钮也不再置灰 —— 否则会出现
            // 「红字说不可用、按钮却能点」的自相矛盾。
            if (setupCompleted) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.dpdo_direct_setup_done_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            // —— 【v1.7.0】原「危险提示（红色）」改为中性操作说明 ——
            //
            // 旧文案讲的是「本流程会自动临时置 0，期间导航键失灵」，v1.7.0 起置 0
            // 已移到用户手动点击的「第一步：准备」按钮，激活过程本身不再让导航键失灵，
            // 因此红色危险提示改为中性的**操作顺序说明**（仍是本卡最需要注意的一句话）。
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.dpdo_direct_danger_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // —— 状态行 ——
            //
            // 【v1.6.3 修复】旧版此卡只显示「DP 角色 / 直连通道 / 设备所有者」三项，
            // 而「直连通道」用的是 MAPDO 权限判据、「DP 角色」用的是 role holder 判据，
            // 两者可能一个 ✓ 一个 ✗，用户看到并排的矛盾状态却没有任何解释
            // （这正是「前面说已获得 DP、后面说未持有 MAPDO」的视觉来源）。
            // 现在把「DP 角色（已授予）」与「直连权限（MAPDO 已落地）」拆开命名，
            // 并在存在差异时追加一行说明，让用户能自己判断卡在哪一步。
            StatusRow(
                label = stringResource(R.string.dpdo_status_dp_role),
                granted = activateViewModel.isDpGranted
            )
            StatusRow(
                label = stringResource(R.string.dpdo_status_direct_channel),
                granted = directAvailable
            )
            // DP 角色已授予、但 MAPDO 未落地 → 明确提示这是「角色已授、权限未生效」。
            if (activateViewModel.isDpGranted && !directAvailable) {
                Text(
                    text = stringResource(R.string.dpdo_direct_role_granted_perm_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            StatusRow(
                label = stringResource(R.string.dpdo_status_device_owner),
                granted = isDeviceOwner
            )

            // —— 无需清账户说明 ——
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.dpdo_direct_no_account_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // —— 【v1.7.0 新增】第一步：准备（打开「开机向导」闸）——
            //
            // 背景（用户要求）：置 0 从激活流程里删掉，改由用户手动点本按钮完成；
            // 「激活」按钮默认置灰，只有确认 user_setup_complete == 0 之后才可点。
            //
            // 为什么必须走 Shizuku：`settings put secure` 受 WRITE_SECURE_SETTINGS 保护，
            // 应用自身（untrusted_app）持不到；同理，回读也必须借 shell(uid 2000) 身份。
            Button(
                enabled = versionSupported && !isDeviceOwner &&
                        !setupGateBusy && !activateViewModel.tryActivate,
                onClick = {
                    if (!activateViewModel.isShizukuActive) {
                        Toast.makeText(context, shizukuNeededLabel, Toast.LENGTH_LONG).show()
                    } else {
                        activateViewModel.openSetupGate()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.dpdo_direct_gate_prepare))
            }
            Text(
                text = stringResource(R.string.dpdo_direct_gate_prepare_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // —— 闸状态提示 ——
            // 绿底 = 已就绪（可点激活）；灰底 = 未打开（激活按钮置灰）。
            Surface(
                color = if (setupGateOpen) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = setupGateMsg?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            if (setupGateOpen) R.string.dpdo_direct_gate_open
                            else R.string.dpdo_direct_gate_closed
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (setupGateOpen) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // —— 一键激活按钮（【v1.6.2】由 Switch 改为与卡片 1 同款的
            //    「激活 + 竖三角」按钮；用 Filled.PlayArrow 与同页其它按钮图标一致）——
            Button(
                // 【v1.7.0】新增「闸已打开」作为前置条件：默认置灰，
                // 用户先点上面的「第一步：准备」把 user_setup_complete 置 0，
                // 经 Shizuku 回读确认为 0（isSetupGateOpen）后才允许点击。
                enabled = versionSupported && !isDeviceOwner &&
                        setupGateOpen &&
                        !activateViewModel.tryActivate,
                onClick = {
                    // 一键激活：跳转提权界面执行「非 ADB 直连」流程。
                    // 【v1.7.0】置 0 已由上面的「第一步：准备」完成，本流程只做激活，
                    //          并在 finally 中把 user_setup_complete 恢复为 1。
                    // 直连的激活本身不需要 Shizuku，但辅助链路（DP Role 授予、闸的开关）
                    // 需要，因此这里统一要求 Shizuku 可用，否则给明确提示（不静默失败）。
                    if (!activateViewModel.isShizukuActive) {
                        Toast.makeText(context, shizukuNeededLabel, Toast.LENGTH_LONG).show()
                    } else {
                        activateViewModel.setElevateMode(
                            ActivateViewModel.ELEVATE_MODE_DIRECT
                        )
                        navigator.navigate(ElevateScreenDestination)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.dpdo_direct_activate))
            }
            Text(
                text = stringResource(R.string.dpdo_direct_switch_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // —— 查看激活指令（弹窗形式，与「通过电脑命令行激活」卡片保持一致）——
            //
            // 【v1.6.7 重做】旧版是「点一下直接复制 + 卡片内常驻等宽预览」，
            // 与同页「通过电脑命令行激活」的交互不一致；而且预览里塞了大量
            // 说明性中文（“本路径以应用自身身份执行…”），用户无法直接粘贴使用。
            // 现在统一为：点击 → 弹窗展示完整指令 → 弹窗内「复制 / 发送 / 取消」。
            val cmdDialog = rememberConfirmDialog()
            val shareCmdLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                // 仅用于「发送」指令，无需处理返回值
            }
            val cmdTitle = stringResource(R.string.dpdo_direct_copy_pc_cmd)
            val cmdContent = stringResource(R.string.dpdo_direct_cmd_message, pcScript)
            val cmdConfirm = stringResource(R.string.copy)
            val cmdDismiss = stringResource(R.string.cancel)
            val cmdNeutral = stringResource(R.string.send)
            val cmdShare = stringResource(R.string.share_command)

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val result = cmdDialog.awaitConfirm(
                            title = cmdTitle,
                            content = cmdContent,
                            markdown = true,
                            confirm = cmdConfirm,
                            dismiss = cmdDismiss,
                            neutral = cmdNeutral
                        )
                        if (result == ConfirmResult.Confirmed) {
                            if (ClipboardUtil.put(context, pcScript)) {
                                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (result == ConfirmResult.Neutral) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, pcScript)
                            }
                            shareCmdLauncher.launch(Intent.createChooser(intent, cmdShare))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = cmdTitle
                )
                Text(cmdTitle)
            }

            // —— 【v1.9.0】撤销 DP 权限 ——
            // 原「移除设备所有者」按钮已按需求从本卡片移除（该能力仅保留在授权区的
            // 「设备所有者 / Dhizuku」卡片中），本卡片只保留「撤销 DP 权限」。
            val revokeDpDialog = rememberConfirmDialog()
            val revokeDpTitle = stringResource(R.string.new_perm_revoke_dp)
            val revokeDpDesc = stringResource(R.string.new_perm_revoke_dp_desc)
            val revokeDpScriptText = activateViewModel.revokeDpScript
            val revokeDpMsg = revokeDpDesc + "\n\n```sh\n" + revokeDpScriptText + "\n```"
            val revokeDpCancel = stringResource(R.string.cancel)
            val revokeDpOk = stringResource(R.string.new_perm_revoke_dp_ok)
            val revokeDpFail = stringResource(R.string.new_perm_revoke_dp_failed)
            OutlinedButton(
                // 撤销经 Shizuku 执行，未运行/未授权时置灰
                enabled = activateViewModel.isShizukuActive,
                onClick = {
                    scope.launch {
                        // 【v1.9.0】统一交互：先弹窗展示指令，确认后再执行（复制 + 撤销）
                        val result = revokeDpDialog.awaitConfirm(
                            title = revokeDpTitle,
                            content = revokeDpMsg,
                            markdown = true,
                            confirm = revokeDpTitle,
                            dismiss = revokeDpCancel,
                        )
                        if (result == ConfirmResult.Confirmed) {
                            ClipboardUtil.put(context, revokeDpScriptText)
                            loadingDialog.withLoading {
                                val r = activateViewModel.revokeDpViaShizuku()
                                val msg = r.getOrElse { it.message ?: it.toString() }
                                Toast.makeText(
                                    context,
                                    if (r.isSuccess) revokeDpOk else revokeDpFail.format(msg),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.new_perm_revoke_dp))
            }
        }
    }
}

/** 一行状态指示（已授予 / 未授予）。 */
@Composable
private fun StatusRow(label: String, granted: Boolean) {
    Surface(
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                modifier = Modifier.size(14.dp),
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Security,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    if (granted) R.string.new_perm_granted else R.string.new_perm_not_granted
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ShizukuSection(activateViewModel: ActivateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 【修复④】ON_RESUME 刷新，保证外部撤销 Shizuku 授权后返回时状态同步。
    OnResumeEffect {
        activateViewModel.refreshOwnerState()
        activateViewModel.refreshShizukuState()
    }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.shizuku_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.shizuku_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            val isActive = activateViewModel.isShizukuActive
            val requestError = activateViewModel.shizukuRequestError
            // 【修复②】Shizuku 官方 API 未提供「撤销授权」能力（updateFlagsForUid 被
            // @RestrictTo(LIBRARY_GROUP_PREFIX) 限定，第三方应用无法调用），撤销只能在
            // Shizuku 应用内由用户操作。因此这里不再渲染无效的「撤销」按钮：
            // 已授权时仅展示状态，未授权时才提供明确的「申请」按钮。
            if (!isActive) {
                Button(
                    onClick = {
                        scope.launch {
                            // 不做任何前置过滤/分支判断：直接发起授权请求，
                            // 由 Shizuku 的结果回调决定成败；授权不了就如实提示。
                            activateViewModel.requestShizukuPermission(REQUEST_CODE_SHIZUKU)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                        contentDescription = null
                    )
                    Text(stringResource(R.string.shizuku_grant))
                }
            }
            // 授权失败：如实提示失败原因（不做过滤策略的配套反馈）。
            if (!isActive && requestError != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = requestError,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            // 【修复】已授权时只保留唯一一处「已授权」提示（此前存在重复渲染，
            // 会出现两个 CheckCircle 图标）。
            if (isActive) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.shizuku_granted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 【用 Shizuku 激活】选择入口。
 *
 * 在 Shizuku 已授权后显示，点击弹出选择对话框，让用户明确选择要用
 * Shizuku 激活哪一种能力，而不是在多个卡片里各点一次。
 *
 * 当前提供：
 *  - 激活设备所有者（完整权限）：`dpm set-device-owner`
 */
@Composable
fun ShizukuActivateChooser(activateViewModel: ActivateViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()

    var showChooser by remember { mutableStateOf(false) }

    val chooserTitle = stringResource(R.string.shizuku_activate_chooser)
    val cancelLabel = stringResource(R.string.cancel)

    Button(
        onClick = { showChooser = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(16.dp),
            contentDescription = null
        )
        Text(chooserTitle)
    }

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text(chooserTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.shizuku_activate_chooser_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            showChooser = false
                            scope.launch {
                                loadingDialog.withLoading {
                                    val r = activateViewModel.activateDeviceOwnerViaShizuku()
                                    val msg = r.getOrElse { it.message ?: it.toString() }
                                    Toast.makeText(ctx, msg.ifBlank { "OK" }, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(16.dp),
                            contentDescription = null
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.shizuku_activate_owner))
                            Text(
                                text = stringResource(R.string.shizuku_activate_owner_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showChooser = false
                            scope.launch {
                                loadingDialog.withLoading {
                                    val r = activateViewModel.activateProfileOwnerViaShizuku()
                                    val msg = r.getOrElse { it.message ?: it.toString() }
                                    Toast.makeText(ctx, msg.ifBlank { "OK" }, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(16.dp),
                            contentDescription = null
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(R.string.shizuku_activate_profile_owner))
                            Text(
                                text = stringResource(R.string.shizuku_activate_profile_owner_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChooser = false }) {
                    Text(cancelLabel)
                }
            }
        )
    }
}

@Composable
fun DhizukuSection(activateViewModel: ActivateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.dhizuku_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.dhizuku_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 状态指示：Dhizuku 授权状态（Device Owner 激活的前置步骤）
            // 修复：当本应用已成为 Device Owner 时，Dhizuku 授权只是「前置步骤/附属能力」，
            // 不应再作为主状态显示（否则会出现「已是设备所有者却显示 Dhizuku 授权」的误导）。
            val dhizukuGranted = activateViewModel.isDhizukuGranted
            val ownerActive = activateViewModel.isDeviceOwner || activateViewModel.isProfileOwner
            if (ownerActive) {
                // 已是设备所有者：显示所有者具备的高级能力标识（包含 Dhizuku 转发能力）
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            modifier = Modifier.size(14.dp),
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.owner_privilege_active),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            } else {
                Surface(
                    color = if (dhizukuGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            modifier = Modifier.size(14.dp),
                            imageVector = if (dhizukuGranted) Icons.Filled.CheckCircle else Icons.Filled.Security,
                            contentDescription = null,
                            tint = if (dhizukuGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (dhizukuGranted) stringResource(R.string.dhizuku_granted) else stringResource(R.string.dhizuku_not_granted),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            // 状态指示：Device Owner / Profile Owner 激活状态
            val ownerLabel = when {
                activateViewModel.isDeviceOwner -> stringResource(R.string.device_owner_active)
                activateViewModel.isProfileOwner -> stringResource(R.string.profile_owner_active)
                else -> stringResource(R.string.device_owner_inactive)
            }
            Surface(
                color = if (activateViewModel.isDeviceOwner || activateViewModel.isProfileOwner) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = ownerLabel,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // —— 已激活 Device Owner / Profile Owner 时的「移除设备所有者」入口 ——
            // 参照 Dhizuku 官方实现：应用内直接调用 clearProfileOwner + clearDeviceOwnerApp，
            // 无需 adb / root。仅在系统拒绝时才需要下面的兜底指令。
            if (activateViewModel.canRemoveOwner) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.device_owner_remove_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.device_owner_remove_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val removeDialog = rememberConfirmDialog()
                    val removeTitle = stringResource(R.string.device_owner_remove_title)
                    val removeMessage = stringResource(R.string.device_owner_remove_confirm_message)
                    val confirmLabel = stringResource(R.string.confirm)
                    val cancelLabel = stringResource(R.string.cancel)
                    val removingLabel = stringResource(R.string.device_owner_removing)
                    val removedLabel = stringResource(R.string.device_owner_removed)
                    val removeBtnLabel = stringResource(R.string.device_owner_remove_button)
                    val failedLabel = stringResource(R.string.device_owner_remove_failed)
                    val isDeactivating = activateViewModel.isDeactivating

                    Button(
                        enabled = !isDeactivating,
                        onClick = {
                            scope.launch {
                                val result = removeDialog.awaitConfirm(
                                    title = removeTitle,
                                    content = removeMessage,
                                    confirm = confirmLabel,
                                    dismiss = cancelLabel
                                )
                                if (result == ConfirmResult.Confirmed) {
                                    activateViewModel.deactivateOwner { success, error ->
                                        val msg = if (success) {
                                            removedLabel
                                        } else {
                                            failedLabel.format(error ?: "")
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        activateViewModel.clearDeactivateResult()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .size(16.dp),
                            contentDescription = null
                        )
                        Text(if (isDeactivating) removingLabel else removeBtnLabel)
                    }

                    // —— 兜底：系统拒绝应用内解除时，用 adb/root 执行此指令 ——
                    val showFallback = remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showFallback.value = !showFallback.value },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(
                                if (showFallback.value) R.string.device_owner_fallback_hide
                                else R.string.device_owner_fallback_show
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (showFallback.value) {
                        Text(
                            text = stringResource(R.string.device_owner_remove_fallback_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val removeCmd = activateViewModel.removeOwnerCommand
                        val cmdDialog = rememberConfirmDialog()
                        val removeCmdMessage = removeCmd
                        val copyLabel = stringResource(R.string.copy)
                        val cancelLabel2 = stringResource(R.string.cancel)
                        val sendLabel = stringResource(R.string.send)
                        val copiedLabel = stringResource(R.string.copied)
                        val shareLabel = stringResource(R.string.share_command)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = cmdDialog.awaitConfirm(
                                        title = removeTitle,
                                        content = removeCmdMessage,
                                        markdown = true,
                                        confirm = copyLabel,
                                        dismiss = cancelLabel2,
                                        neutral = sendLabel
                                    )
                                    if (result == ConfirmResult.Confirmed) {
                                        if (ClipboardUtil.put(context, removeCmd)) {
                                            Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    if (result == ConfirmResult.Neutral) {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, removeCmd)
                                        }
                                        shareLauncher.launch(Intent.createChooser(intent, shareLabel))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .size(16.dp),
                                contentDescription = null
                            )
                            Text(removeCmd)
                        }
                    }
                }
            }


            // —— 通过指令激活设备所有者 ——
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.device_owner_via_command),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = stringResource(R.string.fewer_restrictions_badge),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.device_owner_via_command_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val command = activateViewModel.deviceOwnerCommand
                val dialogConfirm = rememberConfirmDialog()
                val title = stringResource(R.string.device_owner_command)
                val content = stringResource(
                    R.string.device_owner_command_message,
                    command
                )
                val confirm = stringResource(R.string.copy)
                val dismiss = stringResource(R.string.cancel)
                val neutral = stringResource(R.string.send)
                val copied = stringResource(R.string.copied)
                val share = stringResource(R.string.share_command)

                Button(
                    onClick = {
                        scope.launch {
                            val result = dialogConfirm.awaitConfirm(
                                title = title,
                                content = content,
                                markdown = true,
                                confirm = confirm,
                                dismiss = dismiss,
                                neutral = neutral
                            )
                            if (result == ConfirmResult.Confirmed) {
                                if (ClipboardUtil.put(context, command)) {
                                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (result == ConfirmResult.Neutral) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, command)
                                }
                                shareLauncher.launch(
                                    Intent.createChooser(intent, share)
                                )
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(16.dp),
                        contentDescription = null
                    )
                    Text(stringResource(R.string.device_owner_command))
                }
            }

            // —— Dhizuku 授权申请 ——
            OutlinedButton(
                onClick = {
                    val granted = context.getString(R.string.dhizuku_granted)
                    if (Dhizuku.init(context)) {
                        if (!Dhizuku.isPermissionGranted()) {
                            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                                override fun onRequestPermission(grantResult: Int) {
                                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                        DeviceOwnerState.sync(context)
                                        activateViewModel.refreshOwnerState()
                                        Toast.makeText(context, granted, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.device_owner_inactive),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            })
                        } else {
                            DeviceOwnerState.sync(context)
                            activateViewModel.refreshOwnerState()
                            Toast.makeText(context, granted, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.device_owner_inactive),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(stringResource(R.string.dhizuku_grant))
            }
        }
    }
}


@Composable
fun BatteryOptimizationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager

    fun isIgnoring(pkg: String): Boolean = pm.isIgnoringBatteryOptimizations(pkg)

    fun requestIgnore(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开电池优化设置: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    ElevatedCard(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "忽略电池优化（防后台冻结）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "vivo 的 com.vivo.pem 会把后台应用冻结（D/S 状态），导致 Dhizuku 服务和 Service 无法稳定拉起。请为 AxManager 和 Dhizuku 都开启“忽略电池优化”，避免被系统冻结。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // AxManager 自己
            val selfPkg = context.packageName
            val selfIgnoring = isIgnoring(selfPkg)
            OutlinedButton(
                onClick = { requestIgnore(selfPkg) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (selfIgnoring) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                    modifier = Modifier.padding(end = 10.dp).size(16.dp),
                    contentDescription = null
                )
                Text(if (selfIgnoring) "AxManager 已忽略电池优化" else "请求忽略 AxManager 电池优化")
            }

            // Dhizuku
            val dhizukuPkg = "com.rosan.dhizuku"
            val dhizukuIgnoring = runCatching { isIgnoring(dhizukuPkg) }.getOrDefault(false)
            OutlinedButton(
                onClick = { requestIgnore(dhizukuPkg) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (dhizukuIgnoring) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                    modifier = Modifier.padding(end = 10.dp).size(16.dp),
                    contentDescription = null
                )
                Text(if (dhizukuIgnoring) "Dhizuku 已忽略电池优化" else "请求忽略 Dhizuku 电池优化")
            }
        }
    }
}
