package frb.axeron.manager.ui.viewmodel

import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import frb.axeron.adb.AdbPairingService
import frb.axeron.adb.util.AdbEnvironment
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.AxeronInfo
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.manager.AxeronApplication
import frb.axeron.manager.adb.AdbStarter
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.manager.adb.AdbStarter.stopTcp
import rikka.shizuku.Shizuku
import frb.axeron.manager.adb.AdbStateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ActivateViewModel : ViewModel() {

    companion object {
        const val TAG = "AdbViewModel"
        const val ACTIVATE_FAILED = -1
        const val ACTIVATE_PROCESS = 0
        const val ACTIVATE_SUCCESS = 1

    }

    var activateStatus by mutableStateOf<ActivateStatus>(run {
        if (Axeron.pingBinder() && Axeron.getAxeronInfo().isNeedUpdate()) {
            ActivateStatus.Updating(Axeron.getAxeronInfo())
        }
        ActivateStatus.Disable
    })
        private set

    var axeronInfo by mutableStateOf(AxeronInfo())
        private set

    var isShizukuActive by mutableStateOf(checkShizukuRealPermission())
        private set

    /** 判断是否已获得真实 Shizuku 授权。 */
    private fun checkShizukuRealPermission(): Boolean =
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** 刷新真实 Shizuku 授权状态（不触碰 axCompanion 伪服务机制）。 */
    fun refreshShizukuState() {
        viewModelScope.launch(Dispatchers.Main) {
            isShizukuActive = checkShizukuRealPermission()
        }
    }

    /** 向官方 Shizuku 发起真实授权请求，结果通过 listener 回调。 */
    fun requestShizukuPermission(requestCode: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            if (!Shizuku.pingBinder()) {
                isShizukuActive = false
                return@launch
            }
            if (shizukuPermissionListener == null) {
                shizukuPermissionListener =
                    Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                        viewModelScope.launch(Dispatchers.Main) {
                            isShizukuActive = grantResult == PackageManager.PERMISSION_GRANTED
                        }
                    }
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener!!)
            }
            Shizuku.requestPermission(requestCode)
        }
    }

    private var shizukuPermissionListener: Shizuku.OnRequestPermissionResultListener? = null

    fun setShizukuIntercept(enable: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            isShizukuActive = enable
            Axeron.enableShizukuService(enable)
        }
    }

    fun checkShizukuIntercept() {
        viewModelScope.launch(Dispatchers.Main) {
            isShizukuActive = checkShizukuRealPermission()
        }
    }

    /** 当前是否为 Device Owner（全设备所有者）。 */
    var isDeviceOwner by mutableStateOf(DeviceOwnerState.isDeviceOwner)
        private set

    /** 当前是否为 Profile Owner（工作资料所有者）。 */
    var isProfileOwner by mutableStateOf(DeviceOwnerState.isProfileOwner)
        private set
    /** 当前是否已获得 Dhizuku 授权（可被 Dhizuku 调用，是 Device Owner 激活的前置步骤）。 */
    var isDhizukuGranted by mutableStateOf(false)
        private set

    /**
     * 当前是否已获得 root 权限（通过 superuser Shell）。
     *
     * 注意：初值必须为 false，不能在构造时同步调用 [Shell.getShell]——
     * libsu 在未初始化时会抛 IllegalStateException，而该 ViewModel 在 Activity 创建阶段
     * 即被实例化，若此处抛异常会导致应用一启动就崩溃（白屏）。真实检测放到 [refreshRootState]
     * 中异步进行。
     */
    var isRootActive by mutableStateOf(false)
        private set

    /**
     * 是否已「完全激活」——即至少具备 shizuku / dhizuku(Device Owner) / root 中的任一种高级权限。
     * 若三者皆无，则为 false，用于在首页权限状态卡中展示。
     */
    val isFullyActivated: Boolean
        get() = isShizukuActive || isDhizukuGranted || isDeviceOwner || isProfileOwner || isRootActive

    /** 刷新 root 权限状态。安全获取 shell，libsu 未初始化或无 root 时返回 false，不抛异常。 */
    fun refreshRootState() {
        viewModelScope.launch(Dispatchers.IO) {
            val rooted = runCatching {
                val shell = Shell.getShell()
                shell.isRoot
            }.getOrDefault(false)
            viewModelScope.launch(Dispatchers.Main) {
                isRootActive = rooted
            }
        }
    }

    /** 刷新 Device Owner / Profile Owner 状态。 */
    fun refreshOwnerState() {
        isDeviceOwner = DeviceOwnerState.isDeviceOwner
        isProfileOwner = DeviceOwnerState.isProfileOwner
        isDhizukuGranted = runCatching {
            // 必须先 init 建立到 Dhizuku server 的 binder，否则 isPermissionGranted() 会因
            // requireServer() 无 binder 抛 IllegalStateException，被兜底为 false，导致
            // 「已授权却显示未获得」的 bug。
            com.rosan.dhizuku.api.Dhizuku.init(AxeronApplication.axeronApp) &&
                com.rosan.dhizuku.api.Dhizuku.isPermissionGranted()
        }.getOrDefault(false)
    }

    /**
     * 统一刷新所有权限/激活状态（Shizuku / Dhizuku / Device Owner / Profile Owner / root）。
     * 供首页等在界面恢复（onResume / LaunchedEffect）时调用，确保在用户于外部 Dhizuku/
     * Shizuku 应用里授予或撤销权限后，返回 AxManager 时状态能立即同步，避免出现
     * 「撤销授权后仍显示已授权」的残留乌龙。
     */
    fun refreshAllStates() {
        refreshOwnerState()
        isShizukuActive = checkShizukuRealPermission()
        viewModelScope.launch(Dispatchers.IO) {
            val rooted = runCatching {
                val shell = Shell.getShell()
                shell.isRoot
            }.getOrDefault(false)
            viewModelScope.launch(Dispatchers.Main) {
                isRootActive = rooted
            }
        }
    }

    /** 设备所有者激活指令。 */
    val deviceOwnerCommand: String
        get() = "adb shell dpm set-device-owner " +
                "${DeviceOwnerState.admin.packageName}/.owner.DeviceOwnerReceiver"

    var isNotificationEnabled by mutableStateOf(false)
        private set

    var devSettings by mutableStateOf(false)
        private set

    fun setLaunchDevSettings(launch: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            devSettings = launch
        }
    }

    var tryActivate by mutableStateOf(false)
        private set

    fun setTryToActivate(activate: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            tryActivate = activate
        }
    }

    fun resetStatus() {
        activateStatus = ActivateStatus.Disable
    }

    suspend fun awaitRunning(timeout: Long = 10000) {
        if (activateStatus is ActivateStatus.Running) return
        withTimeoutOrNull(timeout) {
            snapshotFlow { activateStatus }.first { it is ActivateStatus.Running }
        }
    }


    sealed class ActivateStatus {
        object Disable : ActivateStatus()
        object NeedExtraStep : ActivateStatus()
        class Updating(val axeronInfo: AxeronInfo) : ActivateStatus()
        class Running(val axeronInfo: AxeronInfo) : ActivateStatus()
    }

    fun axeronObserve(): Flow<ActivateStatus> = callbackFlow {
        if (Axeron.pingBinder()) {
            Log.i("AxManagerBinder", "binderHasReceived")
            val axeronInfo = Axeron.getAxeronInfo()
            when {
                axeronInfo.isNeedUpdate() -> {
                    trySend(ActivateStatus.Updating(axeronInfo))
                    setTryToActivate(true)
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

                axeronInfo.isRunning() -> {
                    trySend(ActivateStatus.Running(axeronInfo))
                }

                axeronInfo.isNeedExtraStep() -> {
                    trySend(ActivateStatus.NeedExtraStep)
                }
            }
        }
        val receivedListener = Axeron.OnBinderReceivedListener {
            Log.i("AxManagerBinder", "onBinderReceived")
            val axeronInfo = Axeron.getAxeronInfo()
            when {
                axeronInfo.isRunning() -> {
                    trySend(ActivateStatus.Running(axeronInfo))
                }

                axeronInfo.isNeedExtraStep() -> {
                    trySend(ActivateStatus.NeedExtraStep)
                }
            }
        }
        val deadListener = Axeron.OnBinderDeadListener {
            Log.i("AxManagerBinder", "onBinderDead")
            // 稳定性修复：binder 断开时不立即判定未激活，而是先尝试重启 server 并等待
            // 短暂重连窗口，避免 vivo 等系统因后台进程被回收/冻结导致的 binder 短暂抖动
            // 让 UI 手一抖就跳回激活界面。只有在确认无法重连后才真正置为 Disable。
            launch {
                var recovered = false
                for (attempt in 0 until 3) {
                    delay(1200L * (attempt + 1))
                    if (Axeron.pingBinder()) {
                        val info = Axeron.getAxeronInfo()
                        if (info.isRunning()) {
                            trySend(ActivateStatus.Running(info))
                            recovered = true
                            break
                        }
                    }
                    runCatching { Axeron.newProcess(Starter.internalCommand) }
                }
                if (!recovered) {
                    trySend(ActivateStatus.Disable)
                }
            }
        }
        Axeron.addBinderReceivedListener(receivedListener)
        Axeron.addBinderDeadListener(deadListener)
        awaitClose {
            Axeron.removeBinderReceivedListener(receivedListener)
            Axeron.removeBinderDeadListener(deadListener)
        }
    }

    init {
        // 异步检测 root / Owner 状态，避免在构造阶段同步调用 libsu / DeviceOwnerState 导致崩溃。
        refreshRootState()
        viewModelScope.launch {
            axeronObserve().collect { status ->
                val isStillUpdating =
                    status is ActivateStatus.Disable && activateStatus is ActivateStatus.Updating
                axeronInfo = when (status) {
                    is ActivateStatus.Running -> {
                        checkShizukuIntercept()
                        status.axeronInfo
                    }

                    is ActivateStatus.Updating -> {
                        status.axeronInfo
                    }

                    else -> {
                        if (isStillUpdating) {
                            (activateStatus as ActivateStatus.Updating).axeronInfo
                        } else {
                            AxeronInfo()
                        }
                    }
                }
                if (isStillUpdating) return@collect
                Log.i("AxManagerBinder", "status: $status")
                activateStatus = status
                setTryToActivate(false)
            }
        }
    }

    suspend fun startRoot(): Int = withContext(Dispatchers.IO) {
        runCatching {
            if (tryActivate) return@withContext ACTIVATE_PROCESS
            setTryToActivate(true)

            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                return@withContext ACTIVATE_FAILED
            }

            val result = Shell.cmd(Starter.internalCommand).exec()
            if (result.isSuccess) {
                AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.ROOT)
                ACTIVATE_SUCCESS
            } else {
                ACTIVATE_FAILED
            }
        }.getOrElse {
            it.printStackTrace()
            ACTIVATE_FAILED
        }.also {
            Shell.getCachedShell()?.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun updateNotificationState(context: Context) {
        viewModelScope.launch {
            isNotificationEnabled = checkNotificationEnabled(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun startAdbWireless(
        context: Context
    ): AdbStateInfo = withContext(Dispatchers.IO) {
        if (AdbEnvironment.isWifiRequired() && !isWifiEnabled(context)) {
            requestEnableWifi(context)
            return@withContext AdbStateInfo.Failed("WiFi is required")
        }
        if (tryActivate) return@withContext AdbStateInfo.Process("Trying to activate")
        setTryToActivate(true)
        resetStatus()

        val resultChannel = kotlinx.coroutines.channels.Channel<AdbStateInfo>(1)
        val job = launch {
            AdbStarter.startAdbWireless(context) {
                resultChannel.trySend(it)
            }
        }

        val result = withTimeoutOrNull(15000) {
            resultChannel.receive()
        } ?: AdbStateInfo.Failed("Timeout waiting for connection")

        job.cancel()
        result
    }

    suspend fun startAdbTcp(
        context: Context
    ): AdbStateInfo = withContext(Dispatchers.IO) {
        if (tryActivate) return@withContext AdbStateInfo.Process("Trying to activate")
        setTryToActivate(true)
        resetStatus()

        val tcpPort = AdbEnvironment.getAdbTcpPort()

        val resultChannel = kotlinx.coroutines.channels.Channel<AdbStateInfo>(1)
        AdbStarter.startAdbClient(context, tcpPort) {
            resultChannel.trySend(it)
        }
        resultChannel.receive()
    }

    suspend fun stopAdbTcp(
        context: Context, result: (AdbStateInfo) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (tryActivate) return@withContext result(AdbStateInfo.Process("Trying to activate"))
        setTryToActivate(true)

        val tcpPort = AdbEnvironment.getAdbTcpPort()
        if (tcpPort > 0 && !AxeronSettings.getTcpMode()) {
            stopTcp(context, tcpPort)
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    fun requestEnableWifi(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        context.startActivity(intent)
    }


    @RequiresApi(Build.VERSION_CODES.R)
    fun startPairingService(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!isNotificationEnabled) return@launch
            setLaunchDevSettings(true)

            val intent = AdbPairingService.startIntent(context)
            try {
                context.startForegroundService(intent)
            } catch (e: Throwable) {
                Log.e("AxManager", "startForegroundService", e)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && e is ForegroundServiceStartNotAllowedException
                ) {
                    val mode = context.getSystemService(AppOpsManager::class.java)
                        .noteOpNoThrow(
                            "android:start_foreground",
                            android.os.Process.myUid(),
                            context.packageName,
                            null,
                            null
                        )
                    if (mode == AppOpsManager.MODE_ERRORED) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "OP_START_FOREGROUND is denied. What are you doing?",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    context.startService(intent)
                }
            }
        }
    }


    /**
     * Cek notifikasi aktif atau tidak
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkNotificationEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() &&
                (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }
}