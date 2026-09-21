package frb.axeron.manager.ui.screen




import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    // 【v1.1.6 修复】激活后再次进入本页时，服务已在运行，会立即命中 popBackStack()。
    // 但 LaunchedEffect 在首帧组合期即执行，此时本页的导航事务尚未提交，
    // 直接 pop 会操作到"正在入栈"的 back stack，触发导航状态崩溃（表现为进入即闪退）。
    // 用 remember 标记保证只回退一次，并延迟到本页入栈稳定后再执行。
    val popped = remember { mutableStateOf(false) }
    LaunchedEffect(axeronInfo) {
        if (axeronInfo.isRunning() && !axeronInfo.isNeedUpdate() && !popped.value) {
            popped.value = true
            delay(600)
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

            if (AdbEnvironment.getAdbTcpPort() > 0) {
                TcpDebuggingCard(navigator, activateViewModel)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WirelessDebuggingCard(navigator, activateViewModel)
            }
            RootCard(navigator, activateViewModel)
            DeviceOwnerActivateCard(activateViewModel)
            TempDeviceOwnerCard(activateViewModel)
            OwnerTransferCard(activateViewModel)
            PermissionSections(activateViewModel)
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
    LaunchedEffect(Unit) {
        activateViewModel.refreshTempDoState()
    }

    val cmd = activateViewModel.tempDoCommand
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
 * 【权限转移】卡片（参照 OwnDroid）。
 *
 * 仅在当前应用为 Device Owner 时可用：把 DO 身份转移给系统里另一个具备
 * 设备管理接收器的应用（`dpm.transferOwnership`，Android 9+）。
 */
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
                    text = stringResource(R.string.owner_transfer_no_target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                        Text(t.label)
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

@Composable
fun DeviceOwnerActivateCard(activateViewModel: ActivateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingDialog = rememberLoadingDialog()
    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { }

    // 进入界面时刷新一次真实 Owner 状态，避免激活后回到本页仍显示未激活。
    LaunchedEffect(Unit) {
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

            val ownerActive =
                activateViewModel.isDeviceOwner || activateViewModel.isProfileOwner

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
            // -------- Port Auto Start: reuse the persisted fixed port --------
            Text(
                text = stringResource(R.string.port_boot_start),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.port_boot_start_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val noPortTemplate = stringResource(R.string.port_boot_start_no_port)
            Button(
                enabled = activateViewModel.isDeviceOwner &&
                    !activateViewModel.tryActivate &&
                    AxeronSettings.getBootStartPort() in 1..65535,
                onClick = {
                    scope.launch {
                        loadingDialog.withLoading {
                            val ai = activateViewModel.startAdbByFixedPort(context)
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
                Text(stringResource(R.string.port_boot_start))
            }
            if (AxeronSettings.getBootStartPort() !in 1..65535) {
                Text(
                    text = noPortTemplate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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

@Composable
fun ShizukuSection(activateViewModel: ActivateViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 进入界面时刷新状态
    LaunchedEffect(Unit) {
        activateViewModel.refreshOwnerState()
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
            Button(
                onClick = {
                    scope.launch {
                        if (!Shizuku.pingBinder()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.shizuku_not_running),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.shizuku_granted),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            activateViewModel.requestShizukuPermission(REQUEST_CODE_SHIZUKU)
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    contentDescription = null
                )
                Text(
                    if (isActive) {
                        stringResource(R.string.shizuku_revoke)
                    } else {
                        stringResource(R.string.shizuku_grant)
                    }
                )
            }
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

                // Shizuku 已授权：提供「选择激活方式」入口。
                // 用户可在此选择用 Shizuku 激活「设备所有者」（完整权限）。
                Spacer(Modifier.height(12.dp))
                ShizukuActivateChooser(activateViewModel)
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
                        Text(stringResource(R.string.shizuku_activate_owner))
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
                        Text(stringResource(R.string.shizuku_activate_profile_owner))
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
