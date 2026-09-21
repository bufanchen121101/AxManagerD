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
import frb.axeron.manager.owner.DeviceOwnerAdbActivator
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
        val context = AxeronApplication.axeronApp
        // 关键修复：不再读 DeviceOwnerState 的静态缓存（进程未重启时会长期停留在 false），
        // 而是直接向系统 DevicePolicyManager 实时查询，保证「已激活设备所有者」立即正确显示。
        val (owner, profileOwner) = runCatching {
            DeviceOwnerState.queryOwnerFlags(context)
        }.getOrDefault(false to false)
        isDeviceOwner = owner
        isProfileOwner = profileOwner
        isDhizukuGranted = runCatching {
            // 必须先 init 建立到 Dhizuku server 的 binder，否则 isPermissionGranted() 会因
            // requireServer() 无 binder 抛 IllegalStateException，被兜底为 false，导致
            // 「已授权却显示未获得」的 bug。
            com.rosan.dhizuku.api.Dhizuku.init(context) &&
                com.rosan.dhizuku.api.Dhizuku.isPermissionGranted()
        }.getOrDefault(false)
    }

    /** 移除设备所有者（解除 Device Owner）所需的命令，作为应用内解除失败时的兜底。 */
    val removeOwnerCommand: String
        get() = DeviceOwnerState.buildRemoveOwnerCommand()

    /**
     * 是否应显示「移除设备所有者」操作入口。
     * 只要当前是 Device Owner / Profile Owner，就允许执行解除。
     */
    val canRemoveOwner: Boolean
        get() = isDeviceOwner || isProfileOwner

    /** 解除进行中标志，用于 UI 禁用按钮 / 显示进度。 */
    var isDeactivating by mutableStateOf(false)
        private set

    /** 最近一次解除操作的错误信息（成功时为 null）。 */
    var deactivateError by mutableStateOf<String?>(null)
        private set

    /** 最近一次解除操作是否成功（用于 Toast 提示）。 */
    var deactivateSuccess by mutableStateOf(false)
        private set

    /**
     * 应用内主动解除设备所有者 / 工作资料所有者身份。
     *
     * 参照 Dhizuku 官方实现（HomePage.DeactivateWidget），直接调用 DPM 的
     * clearProfileOwner + clearDeviceOwnerApp，无需 adb / root。
     *
     * @param onDone 解除流程结束后的回调：(success, errorMessage)
     */
    fun deactivateOwner(onDone: ((Boolean, String?) -> Unit)? = null) {
        if (isDeactivating) return
        isDeactivating = true
        deactivateError = null
        deactivateSuccess = false
        viewModelScope.launch(Dispatchers.IO) {
            val context = AxeronApplication.axeronApp
            val result = runCatching {
                DeviceOwnerState.deactivateOwner(context)
            }.getOrElse { t ->
                DeviceOwnerState.DeactivateResult(false, t.message ?: t.toString())
            }
            viewModelScope.launch(Dispatchers.Main) {
                // 无论成功与否都刷新一次真实状态，保证界面与系统一致
                refreshOwnerState()
                isDeactivating = false
                deactivateSuccess = result.success
                deactivateError = result.error
                onDone?.invoke(result.success, result.error)
            }
        }
    }

    /** 清除解除操作的提示状态（Toast 消费后调用）。 */
    fun clearDeactivateResult() {
        deactivateSuccess = false
        deactivateError = null
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

    /**
     * 【设备所有者激活】用 Device Owner 权限开启 ADB 并回连激活 AxManager。
     *
     * 完整链路（参考 Shevery 的 Dhizuku 提权链，但不需要 Dhizuku 用户服务）：
     *   ① 校验本应用已是 Device Owner / Profile Owner；
     *   ② 以 DO 身份 setGlobalSetting 打开 adb_enabled（Android 11+ 同时开 adb_wifi_enabled）；
     *   ③ setprop service.adb.tcp.port + 重启 adbd，让 adbd 监听 127.0.0.1；
     *   ④ 用 [AdbStarter.startAdbClient] 回连本机端口，握手后执行 Starter.internalAdbCommand
     *      拿到 shell 身份，完成激活（与「USB/TCP 调试激活」同一条回连路径）。
     *
     * 必须在 IO 线程执行（内部有 socket 探测与 adbd 重启等待）。
     */
    suspend fun startAdbByDeviceOwner(context: Context): AdbStateInfo = withContext(Dispatchers.IO) {
        if (tryActivate) return@withContext AdbStateInfo.Process("Trying to activate")
        setTryToActivate(true)
        resetStatus()

        // ① 身份校验（失败原因直接透传给 UI 做提示）
        if (!DeviceOwnerAdbActivator.isOwner(context)) {
            setTryToActivate(false)
            return@withContext AdbStateInfo.Failed("Device Owner not active")
        }

        // ②③ 开 ADB + 让 adbd 监听 TCP
        val bind = DeviceOwnerAdbActivator.enableAdbAndBindTcp(context)
        if (!bind.success || bind.port <= 0) {
            setTryToActivate(false)
            return@withContext AdbStateInfo.Failed(
                bind.message.ifBlank { "Failed to enable ADB via Device Owner" }
            )
        }

        // ④ 回连 127.0.0.1:<port> 完成激活（复用现成的 ADB 客户端握手）
        val resultChannel = kotlinx.coroutines.channels.Channel<AdbStateInfo>(1)
        val job = launch {
            AdbStarter.startAdbClient(context, bind.port) {
                resultChannel.trySend(it)
            }
        }

        val result = withTimeoutOrNull(20000) {
            resultChannel.receive()
        } ?: AdbStateInfo.Failed("Timeout waiting for connection")

        job.cancel()

        // 记录本次激活方式为「设备所有者」，供「开机自动激活」在重启后走 DO 分支。
        // 注意：AdbStarter.startAdbClient 成功时会写入 LaunchMethod.ADB，故此处必须在其之后覆盖。
        if (result is AdbStateInfo.Success) {
            AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.DEVICE_OWNER)
        }
        result
    }

    /**
     * Connect using the persisted fixed port (Device Owner Auto Start).
     *
     * Used by the Activate screen's "Port Auto Start" button: after a reboot the
     * Device Owner keeps wireless debugging on, so we just reuse the saved port
     * and hand it to the ADB client (which issues `tcpip:<port>` if needed).
     */
    suspend fun startAdbByFixedPort(context: Context): AdbStateInfo = withContext(Dispatchers.IO) {
        if (tryActivate) return@withContext AdbStateInfo.Process("Trying to activate")
        val fixedPort = AxeronSettings.getBootStartPort()
        if (fixedPort !in 1..65535) {
            return@withContext AdbStateInfo.Failed("No fixed port saved")
        }
        setTryToActivate(true)
        resetStatus()

        val resultChannel = kotlinx.coroutines.channels.Channel<AdbStateInfo>(1)
        val job = launch {
            AdbStarter.startAdbClient(context, fixedPort, forceTcpPort = fixedPort) {
                resultChannel.trySend(it)
            }
        }
        val result = withTimeoutOrNull(20000) {
            resultChannel.receive()
        } ?: AdbStateInfo.Failed("Timeout waiting for connection")

        job.cancel()
        if (result is AdbStateInfo.Success) {
            AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.DEVICE_OWNER)
        }
        setTryToActivate(false)
        result
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

    // =====================================================================
    // 以下为「资料所有者完善」新增能力，全部为独立追加，不改动既有方法。
    // =====================================================================

    /** 当前应用是否持有「设备策略管理」角色（临时 DO 是否生效）。 */
    var isTempDoActive by mutableStateOf(false)
        private set

    /** 刷新临时 DO 状态。 */
    fun refreshTempDoState() {
        val context = AxeronApplication.axeronApp
        isTempDoActive = frb.axeron.manager.owner.DeviceOwnerExtras.isTempDoActive(context)
    }

    /** 临时 DO 指令（供复制）。 */
    val tempDoCommand: String
        get() = frb.axeron.manager.owner.DeviceOwnerExtras
            .buildTempDoCommand(AxeronApplication.axeronApp.packageName)

    /**
     * 用 Shizuku 执行任意 shell 命令（不需要本应用已是 DO）。
     *
     * 场景：激活页面在软件尚未激活时理论上没有 ADB 调试权，但页面已有 Shizuku 授权入口；
     * 用户授予 Shizuku 权限后，即可用 Shizuku 身份执行
     * `cmd role add-role-holder android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>`
     * 来授予「临时 DO」。
     *
     * 实现说明：`rikka.shizuku.Shizuku.newProcess` 是 private（编译期不可用），
     * 因此与项目内 `ShizukuApi` 保持一致，走 binder 直连：
     *   - `Shizuku.getBinder()` 拿到 Shizuku server 的 binder；
     *   - 用 `ShizukuBinderWrapper` 包一层，使 `getCallingUid()` 返回 2000(shell)；
     *   - `moe.shizuku.server.IShizukuService.Stub.asInterface(...)` 后调 `newProcess`。
     * 注意：`newProcess` 返回的是 AIDL 对象（非 null），但仍做空值兜底。
     *
     * @return 命令输出（成功）或错误信息（失败）
     */
    suspend fun execViaShizuku(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!Shizuku.pingBinder()) {
                throw IllegalStateException("Shizuku 未运行")
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw IllegalStateException("未获得 Shizuku 授权")
            }
            val binder = Shizuku.getBinder()
                ?: throw IllegalStateException("Shizuku binder 不可用")
            // AIDL 接口：asInterface 在 binder 可用时返回非空实例
            val service = moe.shizuku.server.IShizukuService.Stub.asInterface(
                rikka.shizuku.ShizukuBinderWrapper(binder)
            )

            val process = service.newProcess(
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null
            )

            // IRemoteProcess 是 AIDL 接口：流以 ParcelFileDescriptor 形式返回，
            // 需转成 FileInputStream 后再读取（不能用 Java Process 的 inputStream）。
            val out = process.inputStream.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor)
                    .bufferedReader().use { it.readText() }
            }
            val err = process.errorStream.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor)
                    .bufferedReader().use { it.readText() }
            }
            val code = process.waitFor()
            if (code != 0) {
                throw IllegalStateException(
                    err.trim().ifBlank { "命令执行失败 (exit=$code)" }
                )
            }
            out.trim()
        }
    }

    /** 用 Shizuku 授予「临时 DO」角色。 */
    suspend fun enableTempDoViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val r = execViaShizuku(tempDoCommand)
        if (r.isSuccess) refreshTempDoState()
        r
    }

    // ---------------------------------------------------------------------
    // 权限转移（参照 OwnDroid）
    // ---------------------------------------------------------------------

    /** 可转移的目标列表（当前为空表示系统里没有合适的接收方）。 */
    var transferTargets by mutableStateOf<List<frb.axeron.manager.owner.DeviceOwnerExtras.TransferTarget>>(emptyList())
        private set

    var isTransferring by mutableStateOf(false)
        private set

    /** 刷新可转移目标列表。 */
    fun refreshTransferTargets() {
        val context = AxeronApplication.axeronApp
        transferTargets = runCatching {
            frb.axeron.manager.owner.DeviceOwnerExtras.queryTransferTargets(context)
        }.getOrDefault(emptyList())
    }

    /**
     * 将 DO 身份转移给指定目标。
     *
     * @param onDone 回调：(success, errorMessage)
     */
    fun transferOwnership(
        target: android.content.ComponentName,
        onDone: ((Boolean, String?) -> Unit)? = null
    ) {
        if (isTransferring) return
        isTransferring = true
        viewModelScope.launch(Dispatchers.IO) {
            val context = AxeronApplication.axeronApp
            val result = runCatching {
                frb.axeron.manager.owner.DeviceOwnerExtras.transferOwnership(context, target)
            }.getOrElse { t ->
                frb.axeron.manager.owner.DeviceOwnerExtras
                    .TransferResult(false, t.message ?: t.toString())
            }
            viewModelScope.launch(Dispatchers.Main) {
                refreshOwnerState()
                isTransferring = false
                onDone?.invoke(result.success, result.error)
            }
        }
    }
}