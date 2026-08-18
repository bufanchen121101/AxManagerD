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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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

    LaunchedEffect(axeronInfo) {
        if (axeronInfo.isRunning() && !axeronInfo.isNeedUpdate()) {
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
            }
        }
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
            val dhizukuGranted = activateViewModel.isDhizukuGranted
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
