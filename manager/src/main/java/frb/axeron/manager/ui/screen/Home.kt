package frb.axeron.manager.ui.screen

import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ActivateScreenDestination
import com.ramcosta.composedestinations.generated.destinations.QuickShellScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.core.Starter
import frb.axeron.manager.BuildConfig
import frb.axeron.manager.R
import frb.axeron.manager.ui.component.PowerDialog
import frb.axeron.manager.ui.component.rememberConfirmDialog
import frb.axeron.manager.ui.component.rememberLoadingDialog
import frb.axeron.manager.ui.util.checkNewVersion
import frb.axeron.manager.ui.util.module.LatestVersionInfo
import frb.axeron.manager.ui.viewmodel.ActivateViewModel
import frb.axeron.manager.ui.viewmodel.ViewModelGlobal
import frb.axeron.shared.AxeronApiConstant.server.VERSION_CODE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>(start = true)
@Composable
fun HomeScreen(navigator: DestinationsNavigator, viewModelGlobal: ViewModelGlobal) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val pluginViewModel = viewModelGlobal.pluginViewModel
    val privilegeViewModel = viewModelGlobal.privilegeViewModel
    val activateViewModel = viewModelGlobal.activateViewModel

    val isRunning = activateViewModel.activateStatus is ActivateViewModel.ActivateStatus.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                modifier = Modifier.padding(start = 10.dp),
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                actions = {
                    val loadingDialog = rememberLoadingDialog()
                    val scope = rememberCoroutineScope()

                    var showDialog by remember { mutableStateOf(false) }

                    if (showDialog) {
                        PowerDialog(
                            onDismiss = { showDialog = false },
                            onReignite = {
                                scope.launch {
                                    val success = loadingDialog.withLoading {
                                        AxeronPluginService.igniteSuspendService()
                                    }

                                    if (success) {
                                        pluginViewModel.fetchModuleList()
                                    }
                                }
                            },
                            onShutdown = { Axeron.destroy() },
                            onRestart = {
                                Axeron.newProcess(
                                    AxeronCommandSession.getQuickCmd(
                                        Starter.internalCommand,
                                        true,
                                        false
                                    ),
                                    null,
                                    null
                                )
                            }
                        )
                    }

                    AnimatedVisibility(visible = isRunning) {
                        IconButton(
                            modifier = Modifier.padding(end = 2.dp),
                            onClick = { showDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Shutdown"
                            )
                        }

                    }
                    Spacer(modifier = Modifier.padding(end = 12.dp))

                    // 【v1.3.0】主页右上角「软件管理」入口已迁移至
                    // 底部导航栏「特权」页（PrivilegeScreen）内，此处不再提供入口。
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = isRunning) {
                FloatingActionButton(
                    onClick = {
                        navigator.navigate(QuickShellScreenDestination)
                    }
                ) {
                    Icon(Icons.Filled.Terminal, null)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp)
                .padding(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(
                activateViewModel = activateViewModel,
                moduleCount = pluginViewModel.plugins.size,
                privilegeCount = privilegeViewModel.privilegedCount
            ) {
                if (!it) {
                    navigator.navigate(ActivateScreenDestination)
                }
            }

            UpdateCard()
            InfoCard(activateViewModel)

            SupportCard()
            LearnCard()
            IssueReportCard()
        }
    }
}

@Composable
fun SupportCard() {
    val uriHandler = LocalUriHandler.current
    val githubUrl = "https://github.com/bufanchen121101/AxManagerD"

    ElevatedCard(
        onClick = {
            uriHandler.openUri(githubUrl)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.support_me),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.support_me_msg),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Icon(
                modifier = Modifier.padding(end = 10.dp, start = 24.dp),
                painter = painterResource(R.drawable.ic_github),
                contentDescription = "Support to github",
            )
        }
    }
}

@Composable
fun LearnCard() {
    val uriHandler = LocalUriHandler.current
    val learnAxManager = "https://1852775966.share.123pan.cn/123pan/J03gvd-2dC8h"

    ElevatedCard(
        onClick = {
            uriHandler.openUri(learnAxManager)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.learn_more),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.learn_more_msg),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Icon(
                modifier = Modifier.padding(end = 10.dp, start = 24.dp),
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Support to github",
            )
        }
    }
}

@Composable
fun StatusCard(
    activateViewModel: ActivateViewModel,
    moduleCount: Int = 0,
    privilegeCount: Int = 0,
    onClick: (Boolean) -> Unit = {}
) {
    val axeronInfo = activateViewModel.axeronInfo
    val context = LocalContext.current
    val isRunning = activateViewModel.activateStatus is ActivateViewModel.ActivateStatus.Running
    val isUpdating = activateViewModel.activateStatus is ActivateViewModel.ActivateStatus.Updating
    val isNeedExtraStep = activateViewModel.activateStatus is ActivateViewModel.ActivateStatus.NeedExtraStep

    val uriHandler = LocalUriHandler.current
    val extraStepUrl =
        "https://fahrez182.github.io/AxManager/guide/faq.html#start-via-wireless-debugging-start-by-connecting-to-a-computer-the-permission-of-adb-is-limited"

    // 【v1.3.1】主页状态卡进入/恢复时刷新权限状态，
    // 外部 Dhizuku / Shizuku 应用中的授予或撤销能及时反映。
    LaunchedEffect(Unit) {
        activateViewModel.refreshAllStates()
        activateViewModel.refreshTempDoState()
        activateViewModel.refreshProfileOwnerState()
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = run {
                when {
                    isUpdating -> MaterialTheme.colorScheme.primaryContainer
                    isNeedExtraStep -> MaterialTheme.colorScheme.errorContainer
                    isRunning -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        val scope = rememberCoroutineScope()
        val updating = stringResource(R.string.updating)
        var debugClickCount by remember { mutableIntStateOf(0) }
        var debugJob: Job? by remember { mutableStateOf(null) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (isUpdating) {
                        Toast.makeText(context, updating, Toast.LENGTH_SHORT).show()
                        return@clickable
                    }
                    if (isNeedExtraStep) {
                        uriHandler.openUri(extraStepUrl)
                        return@clickable
                    }
                    if (isRunning) {
                        debugClickCount++
                        debugJob?.cancel()
                        debugJob = scope.launch {
                            delay(1000)
                            debugClickCount = 0
                        }
                        if (debugClickCount >= 8) {
                            throw RuntimeException("AxManager Manual Crash Test")
                        }
                    }
                    onClick(isRunning)
                }
        ) {
            val isDark = isSystemInDarkTheme()
            val colorScheme = MaterialTheme.colorScheme
            val fadeColor = when {
                isDark -> colorScheme.surfaceVariant
                else -> colorScheme.surfaceVariant
            }

            when {
                isUpdating -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(20.dp, 30.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            modifier = Modifier.size(145.dp),
                            imageVector = Icons.Outlined.Update,
                            contentDescription = null
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        fadeColor.copy(alpha = 0.0f),
                                        fadeColor.copy(alpha = 0.55f)
                                    ),
                                    startY = 0f,
                                    endY = Float.POSITIVE_INFINITY
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val serverUpdatingVersion = stringResource(R.string.server_updating_version)
                        Text(
                            text = updating,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = serverUpdatingVersion.format(axeronInfo.getVersionCode(), VERSION_CODE),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                isNeedExtraStep -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(40.dp, 40.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            modifier = Modifier.size(145.dp),
                            imageVector = Icons.Outlined.Build,
                            contentDescription = null
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        fadeColor.copy(alpha = 0.0f),
                                        fadeColor.copy(alpha = 0.55f)
                                    ),
                                    startY = 0f,
                                    endY = Float.POSITIVE_INFINITY
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.home_need_fix),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.home_need_fix_msg),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                isRunning -> {
                    val ownerChip = when {
                        activateViewModel.isDeviceOwner -> {
                            stringResource(R.string.home_device_owner_chip)
                        }

                        activateViewModel.isTempDoActive && !activateViewModel.isProfileOwner -> {
                            stringResource(R.string.temp_do_active_label)
                        }

                        activateViewModel.isProfileOwnerActive && !activateViewModel.isTempDoActive -> {
                            stringResource(R.string.temp_profile_owner_active_label)
                        }

                        else -> null
                    }

                    var time by remember { mutableLongStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            time = SystemClock.elapsedRealtime() - axeronInfo.serverInfo.starting
                            delay(1000)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatusRing(
                                icon = Icons.Filled.Check,
                                tint = LocalContentColor.current
                            )
                            Text(
                                text = stringResource(id = R.string.home_running),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            ownerChip?.let {
                                StatusChip(text = it)
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.home_stats,
                                privilegeCount,
                                moduleCount
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatUptime(
                                time,
                                stringResource(R.string.day_singular),
                                stringResource(R.string.day_plural)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }


                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatusRing(
                            icon = Icons.Filled.Close,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_not_running),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.home_not_running_msg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            modifier = Modifier.size(22.dp),
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateCard() {
    val latestVersionInfo = LatestVersionInfo()
    val newVersion by produceState(initialValue = latestVersionInfo) {
        value = withContext(Dispatchers.IO) {
            checkNewVersion()
        }
    }

    val currentVersionCode = BuildConfig.VERSION_CODE
    val newVersionCode = newVersion.versionCode
    val newVersionUrl = newVersion.downloadUrl
    val changelog = newVersion.changelog

    val uriHandler = LocalUriHandler.current
    val title = stringResource(R.string.changelog)
    val updateText = stringResource(R.string.update)
    val newVersionAvailable = stringResource(R.string.new_version_available)

    AnimatedVisibility(
        visible = newVersionCode > currentVersionCode,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut()
    ) {
        val updateDialog = rememberConfirmDialog(onConfirm = { uriHandler.openUri(newVersionUrl) })
        WarningCard(
            message = newVersionAvailable.format(newVersionCode),
            MaterialTheme.colorScheme.outlineVariant
        ) {
            if (changelog.isEmpty()) {
                uriHandler.openUri(newVersionUrl)
            } else {
                updateDialog.showConfirm(
                    title = title,
                    content = changelog,
                    markdown = true,
                    confirm = updateText
                )
            }
        }
    }
}

@Composable
fun WarningCard(
    message: String, color: Color = MaterialTheme.colorScheme.error, onClick: (() -> Unit)? = null
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = color
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(onClick?.let { Modifier.clickable { it() } } ?: Modifier)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(18.dp),
                imageVector = Icons.Outlined.Info,
                contentDescription = null
            )
            Text(
                text = message, style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun InfoCard(activateViewModel: ActivateViewModel) {
    val axeronInfo = activateViewModel.axeronInfo
    val versionPid = stringResource(R.string.version_pid)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = stringResource(R.string.home_device_info),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ElevatedCard(
            elevation = CardDefaults.cardElevation(
                defaultElevation = 1.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                InfoCardItem(
                    label = stringResource(R.string.home_device),
                    content = "${Build.MANUFACTURER} ${Build.MODEL}"
                )

                InfoCardDivider()

                InfoCardItem(
                    label = stringResource(R.string.android_version),
                    content = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
                )

                InfoCardDivider()

                InfoCardItem(
                    label = stringResource(R.string.abi_supported),
                    content = Build.SUPPORTED_ABIS.joinToString(", ")
                )

                InfoCardDivider()

                InfoCardItem(
                    label = stringResource(R.string.selinux_context),
                    content = axeronInfo.serverInfo.selinuxContext
                )

                InfoCardDivider()

                InfoCardItem(
                    label = stringResource(R.string.home_service_version),
                    content = versionPid.format(axeronInfo.getVersionCode(), axeronInfo.serverInfo.pid)
                )

                InfoCardDivider()

                InfoCardItem(
                    label = stringResource(R.string.home_manager_version),
                    content = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
            }
        }
    }
}

@Composable
private fun InfoCardItem(label: String, content: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = content,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun InfoCardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun StatusRing(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .border(width = 2.dp, color = tint, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = icon,
            contentDescription = null,
            tint = tint
        )
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onPrimaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primaryContainer
        )
    }
}

private fun formatUptime(millis: Long, daySingular: String, dayPlural: String): String {
    val totalSeconds = millis / 1000
    val days = totalSeconds / 86400
    val hours = (totalSeconds % 86400) / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val dayPart = when {
        days == 1L -> "1 $daySingular "
        days > 1 -> "$days $dayPlural "
        else -> ""
    }
    return "T+$dayPart%02d:%02d:%02d".format(hours, minutes, seconds)
}


@Composable
fun IssueReportCard() {
    val feedbackEmail = "xx1112z@qq.com"
    val uriHandler = LocalUriHandler.current
    val telegramUrl = "https://t.me/axManagerD"
    val qqGroupUrl = "https://qun.qq.com/universal-share/share?ac=1&authKey=d7sQ4bPq9T2YrL4C0ruZJBoX7DjJJ95yLYHpw%2BOFCldG%2BWsZZFap%2FrC%2BZHNC2Nct&busi_data=eyJncm91cENvZGUiOiI2NzQyNjM3NzgiLCJ0b2tlbiI6Imo2b1dWTXdVK2tYQXk1Z0hPNUpqOU9PSXc4cWtyVWpRNVVKVjRQK0hscTdJMFZSVkN6R2lJK2N5NmVaMjM3T2kiLCJ1aW4iOiIzNjM3MjY5MDM0In0%3D&data=1dbLzn9aN3Dk2XP5imCU2oQXSvyu_JjceIr7kJHyVbCqeaqTGVd4azMIoELzvNU-uSzT2T-TlDACdKf9AGxIHA&svctype=4&tempid=h5_group_info"

    ElevatedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.report_issue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.report_issue_msg),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.report_issue_msg2),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "发送邮件至 $feedbackEmail",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { uriHandler.openUri(telegramUrl) }) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        painter = painterResource(R.drawable.ic_telegram),
                        contentDescription = "Telegram",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { uriHandler.openUri(qqGroupUrl) }) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        painter = painterResource(R.drawable.ic_qq),
                        contentDescription = "QQ Group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

