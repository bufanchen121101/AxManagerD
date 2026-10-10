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
import frb.axeron.manager.owner.NewPermissionPaths
import frb.axeron.manager.owner.DpDoDirectActivation
import frb.axeron.manager.owner.DpDoEscalation
// 【v1.3.1】原版 Shizuku 通道修复：主动拉取 binder（详见该类文档）。
import frb.axeron.manager.shizuku.ShizukuBinderPuller
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

/**
 * 【v1.3.1】原版 Shizuku 主动拉取 binder 后，等待管理器回写的时长。
 *
 * 管理器是用 binder 单向往回调（`transact(1, ...)`）把服务 binder 写回来的，
 * 实测耗时在毫秒级；350ms 足以覆盖跨进程调度，又不会让用户感到卡顿。
 *
 * 仅被 [ActivateViewModel.refreshShizukuState] / [ActivateViewModel.requestShizukuPermission]
 * 使用，放在文件级是为了不新增第二个 companion object（一个类只允许有一个）。
 */
private const val SHIZUKU_PULL_WAIT_MS = 350L

class ActivateViewModel : ViewModel() {

    companion object {
        const val TAG = "AdbViewModel"
        const val ACTIVATE_FAILED = -1

        /** 【v1.6.1】提权模式：卡片 1 —— DP + Shizuku（先试激活，失败则删 999 隐藏用户 + 冻结非系统账户应用后重试）。 */
        const val ELEVATE_MODE_DP_SHIZUKU = 1

        /** 【v1.6.1】提权模式：卡片 2 —— 非 ADB 直连（不清账户、不冻结）。 */
        const val ELEVATE_MODE_DIRECT = 2
        const val ACTIVATE_PROCESS = 0
        const val ACTIVATE_SUCCESS = 1

        /**
         * 【v1.4.9 闪退修复】提权日志的字符上限。
         *
         * 取 24 KB：远小于 Binder 1MB 上限，也远小于崩溃日志里出事的 531KB 单条目；
         * 又足够容纳一次完整提权流程的输出（正常仅几 KB）。
         *
         * 背景：`elevateLog` 是长任务实时输出，一旦被某个 rememberSaveable 持有，
         * 用户按返回/切后台时会被打包进 onSaveInstanceState 的 Binder 事务，
         * 超限即抛 TransactionTooLargeException 崩进程（Android 15 实测）。
         * 这里设硬上限做兜底，界面侧同时禁止用 rememberSaveable 持有它。
         */
        const val ELEVATE_LOG_MAX_CHARS = 24_000
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
            // 【v1.3.1】原版 Shizuku 通道修复：
            // 若当前没有可用 binder（推送通道漏发 / 先到那台管理器已退出），
            // 先主动向原版 Shizuku 管理器「要」一次 binder，再读状态。
            // 拉取是异步的，因此这里等一小段再判定；已有 binder 时此分支不执行，
            // 行为与改动前完全一致。
            if (!checkShizukuRealPermission()) {
                val ctx = AxeronApplication.axeronApp
                withContext(Dispatchers.IO) { ShizukuBinderPuller.pull(ctx) }
                delay(SHIZUKU_PULL_WAIT_MS)
            }
            isShizukuActive = checkShizukuRealPermission()
        }
    }

    /**
     * 最近一次 Shizuku 授权尝试的失败原因（成功或未尝试时为 null）。
     *
     * 用于「不做过滤、失败即如实提示」的策略：不再提前判断 pingBinder/权限状态并
     * 静默 return，而是把真实失败原因交给 UI 提示。
     */
    var shizukuRequestError by mutableStateOf<String?>(null)
        private set

    fun clearShizukuRequestError() {
        shizukuRequestError = null
    }

    /** 向官方 Shizuku 发起真实授权请求，结果通过 listener 回调。失败原因写入 [shizukuRequestError]。 */
    fun requestShizukuPermission(requestCode: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            shizukuRequestError = null
            // 【v1.3.1】原版 Shizuku 通道修复：发起授权请求前，若当前还没有 binder，
            // 先主动拉取一次再请求 —— 否则 `Shizuku.requestPermission()` 会因为
            // 「没有收到 binder」直接抛异常，用户看到的就是「点了没反应 / 没有授权入口」。
            // 已有 binder 时本段完全不执行，行为与改动前完全一致。
            if (!Shizuku.pingBinder()) {
                withContext(Dispatchers.IO) { ShizukuBinderPuller.pull(AxeronApplication.axeronApp) }
                delay(SHIZUKU_PULL_WAIT_MS)
                isShizukuActive = checkShizukuRealPermission()
            }
            // 不做 pingBinder/权限过滤：直接尝试请求，由 Shizuku 回调决定结果。
            if (shizukuPermissionListener == null) {
                shizukuPermissionListener =
                    Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                        viewModelScope.launch(Dispatchers.Main) {
                            isShizukuActive = grantResult == PackageManager.PERMISSION_GRANTED
                            if (!isShizukuActive) {
                                shizukuRequestError = "Shizuku 授权被拒绝（代码 $grantResult）"
                            }
                        }
                    }
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener!!)
            }
            runCatching { Shizuku.requestPermission(requestCode) }
                .onFailure { t ->
                    // binder 不可用 / 服务已死等：如实抛出，不再静默 return
                    isShizukuActive = false
                    shizukuRequestError = t.message ?: t.toString()
                }
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
     * 【Bug 修复】「设备所有者特权」是否可用 —— 本应用自身为 DO/PO，或已通过第三方
     * （Dhizuku 授权 / Android 14+ 的 Device Policy Role）取得经转发的设备所有者特权。
     *
     * 与 [isDeviceOwner] / [isProfileOwner]（判「本应用是不是 DO 本体」）不同，本字段判的是
     * 「能不能发出 DO 特权命令」，口径与 [DeviceOwnerAdbActivator.hasOwnerPrivilege] 完全一致：
     * 激活页「用设备所有者激活」卡片的前置条件与执行入口都看它，
     * 修复「改用别的软件授权后，被误提示未授予设备所有者权限」。
     */
    var isOwnerPrivilegeAvailable by mutableStateOf(false)
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
     * 是否已「完全激活」——即至少具备 shizuku / dhizuku(Device Owner) / root / DP Role / WS 中的任一种高级权限。
     * 若皆无，则为 false，用于在首页权限状态卡中展示。
     *
     * 【v1.2.0】新增 [isDpGranted] / [isWsGranted]：[isDpGranted] 表示本应用持有
     * `DEVICE_POLICY_MANAGEMENT` Role（即「用 DP 激活」），它与 DO/PO 等价地提供高权限，
     * 因此必须计入「已激活」，否则用 DP 激活的用户在主页会看到「未完全激活」。
     */
    val isFullyActivated: Boolean
        get() = isShizukuActive || isDhizukuGranted || isDeviceOwner || isProfileOwner || isRootActive ||
                isDpGranted || isWsGranted

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
        // 【Bug 修复】与上面同时刷新「设备所有者特权可用性」：
        // 除自我 DO/PO 外，经第三方授权（Dhizuku / DP Role）的转发通道也算可用，
        // 供激活页「用设备所有者激活」卡片的前置条件使用。
        isOwnerPrivilegeAvailable = runCatching {
            DeviceOwnerAdbActivator.hasOwnerPrivilege(context)
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
            // 【v1.2.0】一并刷新 DP / WS 新权限状态，保证「用 DP 激活」时主页权限状态正确显示。
            // 该调用走 Shizuku 查询；Shizuku 不可用时内部会保持原值，不会误报。
            runCatching { refreshNewPermissionState() }
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

        // 【v2.0.1 激活状态机修复】「提示成功却没有跳转」的兜底。
        //
        // 背景：activateStatus 只在 axeronObserve() 的 binder 事件（onBinderReceived /
        // onBinderDead）到来时才会被改写；而每个激活入口在开始前都会 resetStatus()
        // 把它清成 Disable。若此时 App 已经持有活着的 binder（典型场景：已经激活过
        // 再点一次「连接 ADB 端口」；或 server 因本次激活重建、binder 事件晚于本函数
        // 超时），就再也不会有一条新的 Running 事件进来 —— awaitRunning 只能等满
        // timeout 后静默返回，表现就是「Toast 说成功、界面原地不动、也不跳回主页」。
        //
        // 处理：超时后直接向 Axeron 复核一次真实状态，确认服务确实在跑就补发 Running，
        // 让 Activate.kt 里的跳转监听（activateStatus is Running）正常生效。
        if (activateStatus !is ActivateStatus.Running) {
            val info = runCatching {
                if (Axeron.pingBinder()) {
                    Axeron.getAxeronInfo().takeIf { it.isRunning() }
                } else {
                    null
                }
            }.getOrNull()

            if (info != null) {
                withContext(Dispatchers.Main) {
                    axeronInfo = info
                    activateStatus = ActivateStatus.Running(info)
                }
            } else {
                Log.w(
                    "AxManagerBinder",
                    "awaitRunning 超时：binder=" + Axeron.pingBinder() + "，Axeron 服务未就绪"
                )
            }
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
            // 【崩溃修复】axeronObserve() 内部已对 binder 异常兜底，但为防止任何遗漏的
            // 异常沿协程冒泡崩掉进程，这里再加一层 catch，保证「重进软件不再先崩溃一次」。
            runCatching {
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
            }.onFailure {
                Log.e("AxManagerBinder", "axeronObserve collect failed", it)
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
        // 【Bug 修复】接受两种通道：本应用自身为 DO/PO，或经第三方（Dhizuku / DP Role）
        // 授权转发到设备所有者特权——后者在旧版本同样能走通本条链路。
        if (!DeviceOwnerAdbActivator.hasOwnerPrivilege(context)) {
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
            .buildTempDoCommand(AxeronApplication.axeronApp)

    /** 资料所有者（Profile Owner）指令（供复制）。 */
    val tempProfileOwnerCommand: String
        get() = frb.axeron.manager.owner.DeviceOwnerExtras
            .buildTempProfileOwnerCommand(AxeronApplication.axeronApp)

    /**
     * 【v1.9.0】临时 DO 指令的**电脑端展示版**（带 `adb shell` 前缀）。
     *
     * 为什么单独开一个字段而不是直接改 [tempDoCommand]：
     * [tempDoCommand] 同时被 [enableTempDoViaShizuku] / [activateDeviceOwnerViaShizuku]
     * 交给 Shizuku 以 shell 身份执行，**加上 `adb shell` 会让执行失败**
     * （那是在电脑上敲的命令前缀，不是设备内命令的一部分）。
     * 因此：执行走 [tempDoCommand]（裸命令），UI 展示/复制走本字段。
     */
    val tempDoPcCommand: String
        get() = "adb shell " + tempDoCommand

    /** 【v1.9.0】资料所有者指令的电脑端展示版（带 `adb shell` 前缀）。执行仍走 [tempProfileOwnerCommand]。 */
    val tempProfileOwnerPcCommand: String
        get() = "adb shell " + tempProfileOwnerCommand
    /** 当前应用是否已是资料所有者。 */
    var isProfileOwnerActive by mutableStateOf(false)
        private set

    // =====================================================================
    // 新权限路径：DP（DEVICE_POLICY_MANAGEMENT Role）+ WS（WRITE_SECURE_SETTINGS）
    //
    // 独立新增，不改动上方任何既有状态/方法，避免污染公共路径。
    // 实现细节见 [frb.axeron.manager.owner.NewPermissionPaths]。
    // =====================================================================

    /** 是否持有 `DEVICE_POLICY_MANAGEMENT` Role。 */
    var isDpGranted by mutableStateOf(false)
        private set

    /** 是否已授予 `WRITE_SECURE_SETTINGS`。 */
    var isWsGranted by mutableStateOf(false)
        private set

    /** 是否已授予 `MANAGE_DEVICE_ADMINS`。 */
    var isManageDeviceAdminsGranted by mutableStateOf(false)
        private set

    /**
     * 刷新新权限路径的状态（DP / WS / MANAGE_DEVICE_ADMINS）。
     *
     * ⚠️ **必须走 Shizuku**：`dumpsys role` / `dumpsys package` 需要
     * `android.permission.DUMP`（shell 专属）。若用应用自身 UID 执行
     * （`Runtime.exec`），只会得到 `Permission Denial` 或**空输出**，
     * 导致 `isDpGranted` 恒为 false —— 即「授权成功却显示未激活」的根因。
     *
     * Shizuku 不可用时退回「保持原值」，避免误报为「未授予」。
     */
    suspend fun refreshNewPermissionState() = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        if (!isShizukuActive && !Shizuku.pingBinder()) {
            // 无 Shizuku：无法可靠查询，保持原值不动（宁可 stale 也不误报）
            Log.w("AxManager", "refreshNewPermissionState: Shizuku 不可用，跳过查询")
            return@withContext
        }
        val dpOut = execViaShizuku(NewPermissionPaths.buildQueryDpScript(context)).getOrNull()
        val wsOut = execViaShizuku(NewPermissionPaths.buildQueryWsScript(context)).getOrNull()
        val mdaOut = execViaShizuku(NewPermissionPaths.buildQueryMdaScript(context)).getOrNull()
        isDpGranted = NewPermissionPaths.parseDp(context, dpOut)
        isWsGranted = NewPermissionPaths.parseGranted(wsOut)
        isManageDeviceAdminsGranted = NewPermissionPaths.parseGranted(mdaOut)
    }

    /** 授予 DP 的完整 shell 脚本（供 UI 展示 / 复制）。 */
    val grantDpScript: String
        get() = NewPermissionPaths.buildGrantDpScript(AxeronApplication.axeronApp)

    /** 撤销 DP 的完整 shell 脚本（供 UI 展示 / 复制）。 */
    val revokeDpScript: String
        get() = NewPermissionPaths.buildRevokeDpScript(AxeronApplication.axeronApp)

    /**
     * 用 Shizuku 授予 `DEVICE_POLICY_MANAGEMENT` Role。
     *
     * 走 [execViaShizuku]，因为 `cmd role` 需要 shell 身份。
     * 脚本内部已处理「开 bypass → 授予 → 还原 bypass」，无需额外调用。
     *
     * 成功后刷新状态；失败时返回原始错误（含 `RuntimeException: Failed` 等）。
     */
    suspend fun grantDpViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        val r = execViaShizuku(NewPermissionPaths.buildGrantDpScript(context))
        if (r.isSuccess) {
            // 【v2.0.0】脚本内部自带降级：DP_MODE=ROLE 为默认方案成功，
            // DP_MODE=WS 为默认方案失败、已降级到备选方案（pm grant WRITE_SECURE_SETTINGS）。
            val mode = NewPermissionPaths.parseGrantMode(r.getOrNull())
            Log.i("AxManager", "grantDpViaShizuku 生效通道=" + (mode ?: "unknown"))
            refreshNewPermissionState()
        }
        r
    }

    /**
     * 用 Shizuku 撤销 `DEVICE_POLICY_MANAGEMENT` Role（含还原 bypass 开关）。
     */
    suspend fun revokeDpViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        val r = execViaShizuku(NewPermissionPaths.buildRevokeDpScript(context))
        if (r.isSuccess) {
            refreshNewPermissionState()
        }
        r
    }


    /** 刷新资料所有者状态。 */
    fun refreshProfileOwnerState() {
        val context = AxeronApplication.axeronApp
        isProfileOwnerActive =
            frb.axeron.manager.owner.DeviceOwnerExtras.isTempProfileOwnerActive(context)
    }

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
    // =====================================================================
    // 新路径：DP + Shizuku(ADB) 联合激活 Device Owner
    //
    // 独立新增，不改动上方任何既有状态/方法，避免污染公共路径。
    // 原理与版本限制见 [frb.axeron.manager.owner.DpDoEscalation]。
    // =====================================================================

    /** 运行期版本是否满足本方案（Android 13+，DP Role 自 A13 引入）。 */
    val isDpDoVersionSupported: Boolean
        get() = DpDoEscalation.isVersionSupported()

    /** 最近一次「DO 激活前置状态」诊断结果（未采集过为 null）。 */
    var dpDoDiagnostics by mutableStateOf<DpDoEscalation.Diagnostics?>(null)
        private set

    /** 采集「DO 激活前置状态」（账户数 / 用户数 / 向导状态 / 是否已是 DO/PO）。 */
    suspend fun refreshDpDoDiagnostics() = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        if (!isShizukuActive && !Shizuku.pingBinder()) {
            // 无 Shizuku：无法可靠查询，保持原值（宁可 stale 也不误报）
            Log.w("AxManager", "refreshDpDoDiagnostics: Shizuku 不可用，跳过查询")
            return@withContext
        }
        val out = execViaShizuku(DpDoEscalation.buildDiagnosticScript(context)).getOrNull()
        dpDoDiagnostics = DpDoEscalation.parseDiagnostics(out)
    }

    /** 激活脚本原文（供 UI 展示 / 复制）。 */
    val dpDoActivateScript: String
        get() = DpDoEscalation.buildActivateScript(AxeronApplication.axeronApp)

    // =====================================================================
    // 【新增】DP 差异化激活：非 ADB 直连分支
    //
    // 独立追加，不改动上方任何既有状态/方法（[runElevateFlow] 等保持原样），
    // 避免污染公共路径。原理与 A13→A17 源码取证见
    // [frb.axeron.manager.owner.DpDoDirectActivation] 与项目文档
    // `AxManagerD_AOSP_13to17_DP差异化取证.md`。
    //
    // 与既有 [runElevateFlow] 的区别：那条走 ADB 分支（Shizuku shell 执行 dpm），
    // 必须清账户；本路径走非 ADB 分支（应用自身身份直连 binder），**不清账户**。
    // =====================================================================

    /** 非 ADB 直连路径是否可用（运行期版本 + 已持有 DP Role）。 */
    var isDirectActivationAvailable by mutableStateOf(false)
        private set

    /**
     * 【v1.6.5】设备是否已完成开机向导。
     *
     * 非 ADB 分支的**唯一硬闸**，向导完成后即关闭且无法绕开（A13→A16 源码核实）。
     * 卡片 2 据此显示 [R.string.dpdo_direct_setup_done_hint]，让用户提前知道
     * 该入口在其设备上不可用，而不是点进去白跑一趟再吃一个异常。
     */
    var isSetupCompleted by mutableStateOf(false)
        private set

    /** 最近一次非 ADB 直连激活的结果（未跑过为 null）。 */
    var directActivationResult by mutableStateOf<DpDoDirectActivation.ActivationResult?>(null)
        private set

    /** 刷新「非 ADB 直连路径」可用性（版本 + MAPDO 权限实际持有情况 + 向导状态）。 */
    fun refreshDirectActivationAvailability() {
        val context = AxeronApplication.axeronApp
        isDirectActivationAvailable = DpDoEscalation.isVersionSupported() &&
                DpDoDirectActivation.hasManageProfileAndDeviceOwners(context)
        // 【v1.6.5】向导状态：读 Settings.Secure.USER_SETUP_COMPLETE（与 DPM 同源）。
        // 独立于 MAPDO 判据，因为「向导已完成」是本路径不可用的根本原因，
        // 即使用户把 DP Role / MAPDO 都搞定了也依然走不通。
        //
        // ⚠️【v1.7.0】注意：`isSetupCompleted` 用的是**应用自身身份**读 Settings，
        //    实测读不到该 `@hide` 键（恒 null → 恒 false），因此它**不能**用来判断
        //    「激活按钮是否该亮」。真正的判定改用下面的 [isSetupGateOpen]（走 Shizuku）。
        isSetupCompleted = DpDoDirectActivation.isSetupCompleted(context)
    }

    // =====================================================================
    // 【v1.7.0】「开机向导」闸的打开 / 关闭 / 状态（卡片 2 专用）
    //
    // 设计背景（用户要求）：
    //   把「激活前自动执行 settings put secure user_setup_complete 0」从激活流程里
    //   删掉，改成卡片上「激活」按钮**之前**多一个「第一步：准备」按钮，由用户手动点；
    //   「激活」按钮默认置灰，只有确认 `user_setup_complete == 0` 之后才可点。
    //
    // 为什么状态必须走 Shizuku：应用自身（untrusted_app）读不到该 `@hide` 键，
    //   只能借 shell(uid 2000) 身份执行脚本回读。
    // =====================================================================

    /**
     * 「开机向导」闸是否已打开（`user_setup_complete == 0`）。
     *
     * 由 [refreshSetupGateState] 经 Shizuku 回读后刷新；应用自身读不到该键，
     * 因此本字段是**唯一可靠**的按钮灰化依据。未知（尚未刷新 / 读取失败）时为 false，
     * 即「激活」按钮默认保持置灰 —— 与用户要求「默认激活按钮是灰色的」一致。
     */
    var isSetupGateOpen by mutableStateOf(false)
        private set

    /** 闸状态刷新是否正在进行（避免连点重复执行脚本）。 */
    var isSetupGateBusy by mutableStateOf(false)
        private set

    /** 闸操作最近一次的人类可读提示（成功 / 失败原因），供卡片展示。 */
    var setupGateMessage by mutableStateOf<String?>(null)
        private set

    /**
     * 经 Shizuku（shell 身份）回读 `user_setup_complete`，刷新 [isSetupGateOpen]。
     *
     * @param showMessage 是否把失败原因写入 [setupGateMessage]（刷新场景通常不写，
     *   避免用户一进页面就看到红色提示；按钮动作场景则写）。
     */
    suspend fun refreshSetupGateState(showMessage: Boolean = false) {
        val res = runCatching {
            execViaShizuku(DpDoDirectActivation.buildReadSetupCompleteScript())
        }.getOrElse { Result.failure(it) }
        val value = DpDoDirectActivation.parseSetupCompleteOutput(res.getOrNull())
        val open = value == 0
        withContext(Dispatchers.Main) {
            isSetupGateOpen = open
            if (showMessage && !open) {
                setupGateMessage = res.exceptionOrNull()?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: ("当前 user_setup_complete=" + (value?.toString() ?: "未确认") +
                            "；若为 1 表示闸未打开。")
            }
        }
    }

    /**
     * 【第一步：准备】把 `user_setup_complete` 临时置 0，打开非 ADB 分支的唯一硬闸。
     *
     * 本操作**不执行激活**，只开门；用户随后手动点「激活」。
     * 成功后 [isSetupGateOpen] 变 true，「激活」按钮才可点。
     */
    fun openSetupGate() {
        if (isSetupGateBusy) return
        isSetupGateBusy = true
        setupGateMessage = null
        viewModelScope.launch {
            val res = runCatching {
                execViaShizuku(DpDoDirectActivation.buildSetSetupCompleteScript(0))
            }.getOrElse { Result.failure(it) }
            val value = DpDoDirectActivation.parseSetupCompleteOutput(res.getOrNull())
            val ok = res.isSuccess && value == 0
            withContext(Dispatchers.Main) {
                isSetupGateOpen = ok
                setupGateMessage = if (ok) {
                    "已就绪：「开机向导」闸已打开（user_setup_complete=0），现在可以点「激活」。"
                } else {
                    "打开失败：" + (res.exceptionOrNull()?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: ("回读值=" + (value?.toString() ?: "未确认"))) +
                            "；请确认 Shizuku 正在运行且已授权本应用。"
                }
                isSetupGateBusy = false
            }
        }
    }

    /**
     * 【还原：关闭闸】把 `user_setup_complete` 恢复为 1（导航键恢复正常）。
     *
     * 正常路径下激活流程的 `finally` 已自动恢复，本方法供用户手动补救
     * （例如激活中途退出、或设备停在导航键失灵状态时）。
     */
    fun closeSetupGate() {
        if (isSetupGateBusy) return
        isSetupGateBusy = true
        setupGateMessage = null
        viewModelScope.launch {
            val res = runCatching {
                execViaShizuku(DpDoDirectActivation.buildSetSetupCompleteScript(1))
            }.getOrElse { Result.failure(it) }
            val value = DpDoDirectActivation.parseSetupCompleteOutput(res.getOrNull())
            val ok = res.isSuccess && value == 1
            withContext(Dispatchers.Main) {
                isSetupGateOpen = !ok
                setupGateMessage = if (ok) {
                    "已恢复：「开机向导」闸已关闭（user_setup_complete=1），导航键正常。"
                } else {
                    "恢复失败：请手动执行 settings put secure user_setup_complete 1"
                }
                isSetupGateBusy = false
            }
        }
    }

    // =====================================================================
    // 【v1.6.4】MAPDO 落地判据（双通道）
    //
    // 背景：`MANAGE_PROFILE_AND_DEVICE_OWNERS` 的 protectionLevel = signature|role。
    // `Context#checkSelfPermission` 最终走
    // `PermissionManagerServiceImpl#checkPermissionInternal` →
    // `UidPermissionState#isPermissionGranted`，而后者要求该权限**已在 manifest 声明**
    // （未声明 → 权限表中无该项 → PermissionState == null → 恒 DENIED）。
    // 旧版 manifest 恰好漏声明该权限，导致「DP_GRANT_OK 却永远等不到 MAPDO」。
    //
    // 现已补 manifest 声明；此处再叠加一条**shell 侧 dumpsys 证据**作为并列判据，
    // 用于覆盖两类残余场景：
    //   ① 部分 ROM 上 Role 位权限的应用侧权限表刷新晚于 role holder 落地；
    //   ② 用户设备上旧版 APK 未重装（旧 manifest 无声明）时的诊断可读性。
    //
    // ⚠️ 依赖 `execViaShizuku`（shell 身份）。Shizuku 不可用时自动退回
    //    `checkSelfPermission` 单判据，行为与旧版一致，不会误报。
    // =====================================================================

    /**
     * MAPDO 是否已落地（应用侧权限表 **或** shell 侧 dumpsys 证据任一成立）。
     *
     * ⚠️ suspend：判据 ② 需经 [execViaShizuku] 走 shell 身份查询。
     * 必须在**非主线程**调用（`execViaShizuku` 内部会阻塞 binder）。
     */
    private suspend fun isMapdoReady(context: Context): Boolean {
        // 判据 ①：应用侧权限表（manifest 已声明时可靠）
        if (DpDoDirectActivation.hasManageProfileAndDeviceOwners(context)) return true
        // 判据 ②：shell 侧证据（需 Shizuku；失败时静默退回判据 ①）
        val evidence = runCatching {
            if (!Shizuku.pingBinder()) return@runCatching null
            execViaShizuku(NewPermissionPaths.buildQueryMapdoScript(context)).getOrNull()
        }.getOrNull() ?: return false
        return DpDoDirectActivation.hasManageProfileAndDeviceOwners(context, evidence)
    }

    /**
     * MAPDO 未落地时的中文原因（区分「权限未声明」与「role 未授予」两类根因）。
     *
     * 旧实现只回一句「请确认系统未限制该 Role」，用户在
     * 「DP 角色 = ✓ 已授予 / 直连通道 = ✗ 未就绪」的界面下无法判断到底卡在哪。
     */
    private fun mapdoMissingReason(context: Context): String {
        // 【v1.6.4】按项目既有风格处理 API 33+ 的 getPackageInfo 废弃：
        // 33+ 用 PackageInfoFlags，以下用 @Suppress("DEPRECATION") 老签名。
        val declared = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_PERMISSIONS.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                )
            }
            info.requestedPermissions?.contains(
                NewPermissionPaths.PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS,
            ) == true
        }.getOrDefault(false)

        return if (!declared) {
            "本应用未在清单中声明 MANAGE_PROFILE_AND_DEVICE_OWNERS —— " +
                    "系统不会把该权限纳入本应用的权限表，即使持有 DP 角色也无法生效。" +
                    "请安装包含该声明的新版本后重试。"
        } else if (!isDpGranted) {
            "本应用尚未持有 DEVICE_POLICY_MANAGEMENT 角色，因此没有 " +
                    "MANAGE_PROFILE_AND_DEVICE_OWNERS 权限。请先完成 DP 角色授予。"
        } else {
            "DP 角色已授予，但 MANAGE_PROFILE_AND_DEVICE_OWNERS 仍未生效。" +
                    "Android 14+ 上该角色仅在「设备无任何账户」时才会真正授予此权限；" +
                    "请确认已删除全部账户，或改用其它激活通道。"
        }
    }

    /**
     * 【v1.6.1】卡片 2 用：供用户复制到**电脑**执行的 adb 激活指令。
     *
     * 惰性生成并缓存（内容只依赖包名/组件名，运行期不变）。
     */
    val pcAdbCommands: String by lazy {
        DpDoEscalation.buildPcAdbCommands(AxeronApplication.axeronApp)
    }

    /**
     * 以 **DP Role + 应用自身身份（非 ADB 分支）** 激活 Device Owner。
     *
     * 流程：
     * ```
     * ① 确认 Shizuku 可用（仅第 ② 步需要 shell 身份）
     * ② 授予 DP Role（复用 [NewPermissionPaths.buildGrantDpScript]，含 bypass 处理）
     * ③ 等待权限落地 + 校验 MANAGE_PROFILE_AND_DEVICE_OWNERS
     * ④ 非 ADB 直连：forceUpdateUserSetupComplete → setActiveAdmin → setDeviceOwner
     * ⑤ 失败时不抛出，把 [DpDoDirectActivation.Result] 交给 UI 如实展示
     * ```
     *
     * 与 [runElevateFlow] 的差异：本路径**不清账户、不冻结应用**，
     * 因为它命中的是非 ADB 分支（只受 `hasUserSetupCompleted` 一道闸，可被反向覆盖）。
     */
    suspend fun activateDeviceOwnerViaDpDirect(): Result<DpDoDirectActivation.ActivationResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = AxeronApplication.axeronApp

                // ---------- ① Shizuku（仅 DP Role 授予需要）----------
                if (!Shizuku.pingBinder()) {
                    throw IllegalStateException("Shizuku 未运行：DP Role 授予需要 shell 身份")
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("未获得 Shizuku 授权")
                }
                // ---------- ② 授予 DP Role（已持有时跳过）----------
                //
                // 【v1.6.2】不再只看退出码：必须核对 MAPDO 是否真正落地，
                // 否则会在第 ④ 步以语义模糊的 PERMISSION_MISSING 失败。
                // 【v1.6.4】判据改用 isMapdoReady（含 shell 侧证据），与下方等待循环一致。
                if (!isDpGranted && !isMapdoReady(context)) {
                    val grant = execViaShizuku(NewPermissionPaths.buildGrantDpScript(context))
                    if (grant.isFailure) {
                        // 【v1.6.3】`execViaShizuku` 失败时原文在 exception.message（stderr），
                        // getOrNull() 为 null。两者都试，保证 `explainDpFailure` 能拿到真实文本
                        // （它靠 DP_ACCTS / DP_GRANT_FAIL 等标记判断 Android 14+ 的账户限制）。
                        val raw = grant.getOrNull()
                            ?: (grant.exceptionOrNull()?.message ?: "")
                        throw IllegalStateException(
                            NewPermissionPaths.explainDpFailure(context, raw)
                                ?: ("DP Role 授予失败：" + raw.ifBlank { "未知原因" })
                        )
                    }
                }
                refreshNewPermissionState()

                // ---------- ③ 权限异步生效：最多等 ~12s ----------
                // 【v1.6.4】判据改用 isMapdoReady（checkSelfPermission 或 shell 侧
                // dumpsys 证据任一成立即算落地），修正 manifest 漏声明导致的恒 false。
                var ready = isMapdoReady(context)
                var waited = 0L
                val waits = longArrayOf(500L, 1000L, 2000L, 2000L, 3000L, 4000L)
                var i = 0
                while (!ready && i < waits.size) {
                    Thread.sleep(waits[i])
                    waited += waits[i]
                    ready = isMapdoReady(context)
                    i++
                }
                if (!ready) {
                    // 【v1.6.2】改为明确失败：继续调用只会得到同样的 PERMISSION_MISSING，
                    // 不如把「DP Role 没换来 MAPDO」这个真实原因直接抛给 UI。
                    throw IllegalStateException(
                        mapdoMissingReason(context) +
                                "（已等待 ${waited}ms）"
                    )
                }

                // ---------- ④ 非 ADB 直连激活 ----------
                // 【v1.6.4】传入 mapdoPrecheck=false：上面已用 isMapdoReady（双通道判据）
                // 确认 MAPDO 落地，避免内部单判据在权限表刷新延迟时误判 PERMISSION_MISSING。
                val result = DpDoDirectActivation.activate(context, mapdoPrecheck = false)

                // ---------- ⑤ 刷新全局状态 ----------
                refreshOwnerState()
                refreshDpDoDiagnostics()
                refreshNewPermissionState()
                refreshDirectActivationAvailability()
                withContext(Dispatchers.Main) { directActivationResult = result }

                result
            }
        }

    /**
     * 用「DP + Shizuku」激活 Device Owner。
     *
     * 走 [execViaShizuku]（shell uid = ADB 路径）执行：
     * `dpm set-active-admin` → `dpm set-device-owner`。
     * 成功后刷新 DO 状态与前置诊断。
     */
    suspend fun activateDeviceOwnerViaDp(): Result<String> = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        // 【v1.4.6 修复】先确保 DP Role 真正授予，再执行 dpm 激活。
        //
        // 旧实现直接跑 `dpm set-active-admin` → `dpm set-device-owner`。
        // 由于 Shizuku 已是 shell(ADB) 身份，`dpm set-device-owner` 在
        // 「无账户 + 仅 User 0」的设备上**即使不持有 DP Role 也能成功**（ADB 分支放行），
        // 于是出现「激活成功、但卡片 DP Role 显示未授予」的现象 ——
        // 用户实际走的是纯 shell 路径，DP 从未参与。
        //
        // 现在：若尚未持有 DP，则先走 [grantDpViaShizuku]（bypass + add-role-holder），
        // 让本卡片名副其实（DP 提供 MANAGE_DEVICE_ADMINS，Shizuku 提供 ADB 身份）。
        // 若 DP 授予失败（如 14+ static role 资格校验不过），不阻断 DO 激活 ——
        // 仅记录到输出中，由诊断行如实展示。
        val dpNote = StringBuilder()
        if (!isDpGranted) {
            val dpResult = grantDpViaShizuku()
            if (dpResult.isFailure) {
                dpNote.append("[DP role] ")
                    .append(dpResult.exceptionOrNull()?.message ?: "grant failed")
                    .append("\n")
            }
        }
        val r = execViaShizuku(DpDoEscalation.buildActivateScript(context))
        if (r.isSuccess) {
            refreshOwnerState()
            refreshDpDoDiagnostics()
            refreshNewPermissionState()
        }
        if (dpNote.isNotEmpty()) {
            r.map { dpNote.toString() + it }
        } else {
            r
        }
    }
    /** 撤销由本方案激活的 Device Owner（`dpm remove-active-admin`）。 */
    suspend fun deactivateDeviceOwnerViaDp(): Result<String> = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        // 【v1.4.6】移除 DO 时，把本卡片曾授予的 DP Role 一并撤销，避免残留。
        //
        // 旧实现只跑 `dpm remove-active-admin`，因此：
        //  - 若 DO 实际是纯 shell 路径设上的，移除本身是有效的；
        //  - 但 DP Role 会残留为已授予状态，卡片仍显示「已授予」，用户以为没生效。
        // 现在两步都做，并如实把结果带回 UI。
        val revokeNote = StringBuilder()
        if (isDpGranted || isManageDeviceAdminsGranted) {
            val revokeResult = revokeDpViaShizuku()
            if (revokeResult.isFailure) {
                revokeNote.append("[DP role] ")
                    .append(revokeResult.exceptionOrNull()?.message ?: "revoke failed")
                    .append("\n")
            }
        }
        val r = execViaShizuku(DpDoEscalation.buildDeactivateScript(context))
        if (r.isSuccess) {
            refreshOwnerState()
            refreshDpDoDiagnostics()
            refreshNewPermissionState()
        }
        if (revokeNote.isNotEmpty()) {
            r.map { revokeNote.toString() + it }
        } else {
            r
        }
    }
    // =====================================================================
    // 【v1.4.7】DP + Shizuku「提权流程」：供 ElevateScreen 使用
    //
    // 独立新增，不改动上方任何既有方法。
    // =====================================================================


    /**
     * 提权流程的实时输出（逐行追加，供提权界面渲染）。
     *
     * 【v1.4.9 闪退修复 · TransactionTooLargeException】
     * 真实崩溃日志（Android 15，PID 28599）：
     * ```
     * RuntimeException: android.os.TransactionTooLargeException: data parcel size 544532 bytes
     *   androidx.lifecycle.BundlableSavedStateRegistry.key [size=543664]
     *     SaveableStateRegistry:-1 [size=542876]
     * ```
     * 根因：提权输出是长任务（20~60s）的实时日志，内容可达数百 KB。只要它被
     * 某个 `rememberSaveable` 持有，用户一按返回/切后台，系统就会在
     * `onSaveInstanceState` 阶段把整段日志打包进 Binder 事务（上限 1MB），
     * 超限即 [`android.os.TransactionTooLargeException`] → **进程当场崩溃**，
     * 命令半途而废（拿不到 DO）。
     *
     * 因此这里做两道保险：
     *  ① 硬上限 [ELEVATE_LOG_MAX_CHARS]：只保留**末尾**（最新）部分，
     *     头部用省略标记替代 —— 提权输出越往后越关键（DPDO_* 标记在末尾）；
     *  ② 界面侧**不得**用 `rememberSaveable` 持有它（见 Elevate.kt）。
     */
    var elevateLog by mutableStateOf("")
        private set

    /** 提权流程当前阶段（0=空闲，1=运行中，2=成功，3=失败）。 */
    var elevatePhase by mutableStateOf(0)
        private set

    /**
     * 【v1.6.1】提权界面当前模式，决定 ElevateScreen 触发哪条流程。
     *
     * 两张卡片共用同一个提权界面（ElevateScreen），故用本字段分流：
     *  - [ELEVATE_MODE_DP_SHIZUKU]：卡片 1「DP + Shizuku 激活」
     *  - [ELEVATE_MODE_DIRECT]    ：卡片 2「非 ADB 直连激活」
     *
     * 注：属性名刻意用 `elevateModeValue` 而非 `elevateMode`，避免与下方
     * [setElevateMode] 生成同签名 `setXxx(I)V` 造成 JVM 平台声明冲突。
     */
    var elevateModeValue by mutableStateOf(ELEVATE_MODE_DP_SHIZUKU)
        private set

    /** 当前提权模式（只读访问，配对 [setElevateMode]）。 */
    val elevateMode: Int
        get() = elevateModeValue

    /** 设置提权模式（由卡片在跳转前调用）。 */
    fun setElevateMode(mode: Int) {
        elevateModeValue = mode
    }

    /** 提权流程失败时的中文原因（成功时为 null）。 */
    var elevateError by mutableStateOf<String?>(null)
        private set

    // 【v1.7.0】原 `elevateWarning`（「三键导航会暂时失灵」红色警告条）已整体删除。
    // 原因：置 0 已移出激活流程、改由卡片上的「第一步：准备」按钮手动完成（见
    // [openSetupGate] / [closeSetupGate]），激活过程本身不再让导航键失灵，
    // 该警告不再有出现场景；随 Elevate.kt 的渲染块一并清理。

    /** 向提权输出追加一行。
     *
     * 【v1.4.8 闪退修复】必须切到主线程写 Compose 状态：
     * `elevateLog` 是 `mutableStateOf`，Compose 的快照写入本身要求
     * 「同一状态对象只在同一线程写入」。此前本方法在 IO 线程直接写，
     * 而 UI 又在主线程读取/触发重绘，高版本 Android 上会命中
     * 「snapshot apply conflict / 状态被并发修改」而导致**进程直接崩溃**
     * （现象正是用户反馈的「命令没跑完就闪退」）。
     *
     * 这里用 [viewModelScope] 无法保证顺序，故改用显式追加缓冲 + 主线程提交：
     * 所有写入统一经 [elevatePost]，由主线程串行消费，天然有序且不跨线程。
     */
    private fun elevateAppend(line: String) {
        elevatePendingPost = elevatePendingPost + line + "\n"
    }

    /** 待提交的日志缓冲（仅在主线程读写）。 */
    private var elevatePendingPost: String = ""

    /** 把缓冲内容提交到 [elevateLog]（只能在主线程调用）。 */
    private fun elevateFlush() {
        if (elevatePendingPost.isEmpty()) return
        val merged = elevateLog + elevatePendingPost
        elevatePendingPost = ""
        // 【v1.4.9】硬截断到 [ELEVATE_LOG_MAX_CHARS]，只保留末尾（最新）部分。
        // 目的：即使某处误用 rememberSaveable 持有本状态，也不会因日志过长
        // 触发 TransactionTooLargeException（实测崩溃时单条目达 531KB）。
        // 保留末尾而非头部：DPDO_* 结果标记永远在输出最后几行，头部是无关的启动噪声。
        elevateLog = if (merged.length <= ELEVATE_LOG_MAX_CHARS) {
            merged
        } else {
            "[... 已省略前 " + (merged.length - ELEVATE_LOG_MAX_CHARS) + " 字符 ...]\n" +
                    merged.substring(merged.length - ELEVATE_LOG_MAX_CHARS)
        }
    }

    /**
     * 【v1.4.8 闪退修复】提权流程的**唯一入口**。
     *
     * 与旧版的区别：流程跑在 [viewModelScope] 上，而**不是** `LaunchedEffect`。
     * `LaunchedEffect` 的协程绑定 Composable 生命周期，用户一按返回/切后台，
     * 协程被取消而底层 `execViaShizuku` 的阻塞读流仍在继续，随后向已销毁的
     * 界面状态回写 → 高版本 Android 直接闪退（且命令半途而废，拿不到 DO）。
     *
     * 现在：流程生命周期与 ViewModel（≈ Activity）一致；
     * 界面销毁只影响渲染，不影响命令执行与结果落库。
     */
    fun startElevateFlow() {
        if (elevatePhase == 1) return // 已在运行，避免重复触发
        elevatePhase = 1
        elevateError = null
        elevateLog = ""
        elevatePendingPost = ""
        viewModelScope.launch {
            runCatching { runElevateFlow() }
                .onFailure { t ->
                    // 兜底：任何未预期异常都不允许冒泡成进程崩溃
                    Log.e("AxManager", "runElevateFlow crashed", t)
                    elevateAppend("x 提权流程异常终止：" + (t.message ?: t.toString()))
                    elevateError = "提权流程异常终止：" + (t.message ?: t.toString())
                    elevatePhase = 3
                }
        }
    }

    /** 重置提权流程状态（每次进入提权界面时调用）。 */
    fun resetElevateState() {
        elevateLog = ""
        elevatePendingPost = ""
        elevatePhase = 0
        elevateError = null
        // 【v1.7.0】原「警告条」状态已随 elevateWarning 字段一并删除。
    }

    /**
     * 【v1.4.8】DP + Shizuku 提权主流程（内部实现，请通过 [startElevateFlow] 调用）。
     *
     * 步骤：
     * ```
     * ① 检查 Shizuku 可用性
     * ② 授予 DP Role（bypass + add-role-holder）
     * ③ 尝试默认激活：dpm set-active-admin → dpm set-device-owner
     * ④ 若失败：列出账户 → 冻结持有账户的应用 → 重试 → 解冻
     * ⑤ 汇总结果，更新 elevatePhase
     * ```
     *
     * ⚠️ 本方法**不在** IO 线程直接写 Compose 状态：所有 [elevateAppend]
     * 只写线程内缓冲，经 [withContext] 切主线程后统一提交，避免跨线程写状态崩进程。
     *
     * ⚠️ 【v1.6.1】本方法为**卡片 1「DP + Shizuku 激活」**的主流程：
     * 先尝试激活，失败则删隐藏用户 999 + 只冻结「非系统」的账户所属应用后重试。
     * 卡片 2「非 ADB 直连」走 [startElevateDirectFlow]（不清账户、不冻结）。
     */
    private suspend fun runElevateFlow() = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp

        suspend fun post(line: String) = withContext(Dispatchers.Main) {
            elevateAppend(line)
            elevateFlush()
        }

        suspend fun postAll(block: String) = withContext(Dispatchers.Main) {
            block.lineSequence().forEach { if (it.isNotBlank()) elevateAppend("  " + it) }
            elevateFlush()
        }

        // ---------- ① Shizuku 可用性 ----------
        post("> 检查执行通道 ...")
        if (!Shizuku.pingBinder()) {
            post("x Shizuku 未运行")
            withContext(Dispatchers.Main) {
                elevateError = "Shizuku 未运行，请先启动 Shizuku 并授权本应用"
                elevatePhase = 3
            }
            return@withContext
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            post("x 未获得 Shizuku 授权")
            withContext(Dispatchers.Main) {
                elevateError = "未获得 Shizuku 授权，请在弹窗中允许本应用使用 Shizuku"
                elevatePhase = 3
            }
            return@withContext
        }
        post("v Shizuku 通道就绪（shell 身份）")

        // ---------- ② 授予 DP Role ----------
        post("")
        post("> 授予 DEVICE_POLICY_MANAGEMENT 角色 ...")
        val dpResult = runCatching {
            execViaShizuku(NewPermissionPaths.buildGrantDpScript(context))
        }.getOrElse { Result.failure(it) }
        postAll(dpResult.getOrNull().orEmpty())
        if (dpResult.isSuccess) {
            post("v DP 角色已授予")
        } else {
            post(
                "! DP 角色授予失败（不阻断后续激活）：" +
                        (dpResult.exceptionOrNull()?.message ?: "")
            )
        }
        runCatching { refreshNewPermissionState() }

        // ---------- ③④ 激活 DO（默认 → 删 999 + 冻结非系统账户应用 → 重试）----------
        post("")
        post("> 开始激活设备所有者 ...")
        //
        // 【v1.6.1】改用 buildRescueActivateScript：
        //   - 不再「账户>0 就直接失败退出」，改为尝试自救；
        //   - 失败自动删隐藏用户 999 + 只冻结「非系统」的账户所属应用后重试；
        //   - 无论成败都解冻，不留下副作用。
        val raw = runCatching {
            execViaShizuku(DpDoEscalation.buildRescueActivateScript(context))
        }.getOrElse { Result.failure(it) }
        val out = raw.getOrNull().orEmpty()
        postAll(out)

        val ok = raw.isSuccess && out.contains("DPDO_OK")

        // 兜底解冻：脚本内已解冻，这里对「记录到但可能未解冻」的包再补一次，
        // 避免异常中断导致 App 被永久禁用。
        val frozen = DpDoEscalation.parseFrozenList(out)
        if (frozen.isNotEmpty()) {
            runCatching { execViaShizuku(DpDoEscalation.buildUnfreezeAppsScript(frozen)) }
        }

        // ---------- ⑤ 汇总 ----------
        post("")
        if (ok) {
            post("v 提权流程全部成功")
            withContext(Dispatchers.Main) { elevatePhase = 2 }
            runCatching {
                refreshOwnerState()
                refreshAllStates()
            }
        } else {
            val err = raw.exceptionOrNull()?.message
            // 【v1.5.0】前置预检给出的中文原因优先级最高：
            // 脚本在 precheck 阶段就已判定账户/用户不满足并直接返回，
            // 此时 explainFailure(out) 拿到的 DPDO_DO_OUT 是空的（根本没跑 dpm），
            // 只有 DPDO_REASON 才是真正可执行的原因，必须优先采用。
            val reason = out.lineSequence()
                .firstOrNull { it.contains("DPDO_REASON=") }
                ?.substringAfter("DPDO_REASON=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val friendly = reason
                ?: DpDoEscalation.explainFailure(out)
                ?: "设备所有者激活被系统拒绝。已尝试自动冻结账户应用并重试，仍失败。"
            post("x 提权失败")
            if (!err.isNullOrBlank()) post("  " + err)
            withContext(Dispatchers.Main) {
                elevateError = friendly
                elevatePhase = 3
            }
        }
    }


    /**
     * 【v1.6.0】DP 差异化提权流程的**唯一入口**（非 ADB 直连路径）。
     *
     * 与 [startElevateFlow] 完全对称（同走 viewModelScope、同样的异常兜底），
     * 区别只在底层流程：[runElevateDirectFlow] 走应用自身身份直连 binder，
     * 不清账户、不冻结应用。
     */
    fun startElevateDirectFlow() {
        if (elevatePhase == 1) return // 已在运行，避免重复触发
        elevatePhase = 1
        elevateError = null
        elevateLog = ""
        elevatePendingPost = ""
        viewModelScope.launch {
            runCatching { runElevateDirectFlow() }
                .onFailure { t ->
                    // 兜底：任何未预期异常都不允许冒泡成进程崩溃
                    Log.e("AxManager", "runElevateDirectFlow crashed", t)
                    elevateAppend("x 提权流程异常终止：" + (t.message ?: t.toString()))
                    elevateError = "提权流程异常终止：" + (t.message ?: t.toString())
                    elevatePhase = 3
                }
        }
    }

    /**
     * 【v1.6.0】DP 差异化提权主流程（**非 ADB 直连**）。
     *
     * 取代 [runElevateFlow] 的 ADB 路径。核心差异：本流程**不清账户、不冻结应用**。
     *
     * 步骤：
     * ```
     * ① 检查 Shizuku（仅 DP Role 授予需要 shell 身份）
     * ② 授予 DP Role（bypass + add-role-holder）
     * ③ 等待并校验 MANAGE_PROFILE_AND_DEVICE_OWNERS 落地
     * ④ 非 ADB 直连：DpDoDirectActivation.activate()
     *    forceUpdateUserSetupComplete(0) → setActiveAdmin → setDeviceOwner
     * ⑤ 汇总结果，更新 elevatePhase
     * ```
     *
     * 与 [runElevateFlow] 同样的线程约束：所有 [elevateAppend] 只写线程内缓冲，
     * 经 `withContext(Dispatchers.Main)` 统一提交。
     */
    private suspend fun runElevateDirectFlow() = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp

        suspend fun post(line: String) = withContext(Dispatchers.Main) {
            elevateAppend(line)
            elevateFlush()
        }

        suspend fun postAll(block: String) = withContext(Dispatchers.Main) {
            block.lineSequence().forEach { if (it.isNotBlank()) elevateAppend("  " + it) }
            elevateFlush()
        }

        // ---------- ① Shizuku（仅 DP Role 授予需要）----------
        post("> 检查执行通道 ...")
        if (!Shizuku.pingBinder()) {
            post("x Shizuku 未运行")
            withContext(Dispatchers.Main) {
                elevateError = "Shizuku 未运行，请先启动 Shizuku 并授权本应用"
                elevatePhase = 3
            }
            return@withContext
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            post("x 未获得 Shizuku 授权")
            withContext(Dispatchers.Main) {
                elevateError = "未获得 Shizuku 授权，请在弹窗中允许本应用使用 Shizuku"
                elevatePhase = 3
            }
            return@withContext
        }
        post("v Shizuku 通道就绪（仅用于授予 DP Role）")
        // ---------- ② 授予 DP Role ----------
        //
        // 【v1.6.2 修复】旧实现只看脚本退出码就打印「v DP 角色已授予」，
        // 而脚本在 14+ static role 资格校验未过时**退出码仍可能为 0**
        // （bypass 命令本身成功、add-role-holder 的失败被吞在 DP_OUT 里）。
        // 于是界面显示"已获得 DP"，紧接着 activate() 判 PERMISSION_MISSING 失败 ——
        // 即用户看到的「第一部分提示获得 DP 权限，直连却说未持有 MAPDO」。
        //
        // 现在：显式解析 DP_GRANT_OK / DP_GRANT_FAIL，并**以 MAPDO 实际落地为准**。
        post("")
        post("> 授予 DEVICE_POLICY_MANAGEMENT 角色 ...")
        // 【v1.6.3 修复】旧写法 `runCatching { execViaShizuku(...).also { dpGrantRaw = it.getOrNull() } }`
        // 有个致命盲点：`execViaShizuku` 在脚本 exit!=0 时**抛异常**（stderr 原文进
        // exception.message），此时 `.also{}` 尚未执行 → `dpGrantRaw` 恒为 null；
        // 而 `postAll(dpResult.getOrNull().orEmpty())` 在失败分支拿到 null →
        // **系统原文（DP_OUT / DP_GRANT_FAIL 所在行）根本没被打印到界面**，
        // 于是下面的 `grantMarkedFail` 分支成了永不触发的死代码。
        //
        // 现在：失败时把异常原文一并取出，统一合并成一个 `raw` 字符串，
        // 既用于界面展示（用户能看到系统真实返回），也用于标记判定。
        val dpExec: Result<String> = execViaShizuku(
            NewPermissionPaths.buildGrantDpScript(context)
        )
        val dpGrantRaw: String = dpExec.getOrNull()
            ?: (dpExec.exceptionOrNull()?.message ?: "")
        postAll(dpGrantRaw)
        val grantMarkedOk = dpGrantRaw.contains("DP_GRANT_OK")
        val grantMarkedFail = dpGrantRaw.contains("DP_GRANT_FAIL")
        when {
            grantMarkedFail -> post("! DP 角色授予被系统拒绝（详见上方系统原文）")
            grantMarkedOk -> post("v DP 角色授予指令已执行（等待权限落地校验）")
            else -> post("x DP 角色授予未返回成功标记，详见上方系统原文")
        }
        runCatching { refreshNewPermissionState() }

        // ---------- ③ 等待权限落地 ----------
        //
        // 【v1.6.2】等待窗口从 3.5s 放宽到 ~12s：Role 位权限的落地依赖
        // RoleManagerService 的异步授权广播，长设备上 3.5s 常不够。
        //
        // 【v1.6.4 修复】判据增补 shell 侧证据。
        // 旧实现只调 `hasManageProfileAndDeviceOwners(context)`（内部走
        // `checkSelfPermission`）。而该判据要求 manifest 已声明该权限；此外
        // 部分 ROM 上 Role 位权限的**应用侧权限表刷新**晚于 role holder 落地，
        // 会出现「role 已授予、checkSelfPermission 仍 DENIED」的窗口期。
        // 现在每轮同时用 shell 身份读一次 `dumpsys package`（granted=true 即算落地），
        // 两条判据取「或」，既修 manifest 漏声明导致的恒 false，也消除刷新延迟误判。
        post("")
        post("> 等待角色权限生效 ...")
        var ready = isMapdoReady(context)
        val waits = longArrayOf(500L, 1000L, 2000L, 2000L, 3000L, 4000L)
        var i = 0
        while (!ready && i < waits.size) {
            Thread.sleep(waits[i])
            ready = isMapdoReady(context)
            i++
        }
        if (ready) {
            post("v 已获得 MANAGE_PROFILE_AND_DEVICE_OWNERS")
        } else {
            // 【v1.6.2】不再"仍继续尝试"——那样必然在第 ④ 步白跑一次并报
            // 语义模糊的「未持有 MAPDO」。这里直接给出可执行的中文原因后终止。
            post("x 未获得 MANAGE_PROFILE_AND_DEVICE_OWNERS（等待 ${waits.sum()}ms）")
            val reason = NewPermissionPaths.explainDpFailure(context, dpGrantRaw)
                ?: mapdoMissingReason(context)
            post("  " + reason)
            withContext(Dispatchers.Main) {
                elevateError = reason
                elevatePhase = 3
            }
            runCatching {
                refreshNewPermissionState()
                refreshDirectActivationAvailability()
            }
            return@withContext
        }


        // ---------- ④ 非 ADB 直连激活（**置 0 已移到卡片按钮，本流程不再自动置 0**）----------
        //
        // 【v1.7.0 改造】用户要求：把「激活前自动运行的 settings put secure user_setup_complete 0」
        // 从激活流程里删掉，改由用户在卡片上先点「第一步：准备」按钮手动打开向导闸；
        // 「激活」按钮默认置灰，只有确认闸已打开（shell 侧回读值 == 0）后才可点。
        //
        // 因此本流程**不再自动置 0**，只做一次防御性检查：闸没开就直接给出可执行的提示，
        // 而不是等到 setDeviceOwner 吃一个被反射包成 InvocationTargetException 的异常。
        //
        // ⚠️ 恢复 1 **仍保留在 finally**：卡片按钮置 0 之后设备处于「系统认为未完成向导」
        //    的状态（三键导航的 Home / 最近任务键失灵），激活结束后必须无条件恢复，绝不能让
        //    用户停在导航键失灵的状态。若闸本来就没开（gateValue != 0），恢复段会被跳过判断
        //    自然「置 1 也没坏处」——但为避免无谓写入，这里用 needRestoreGate 标记。
        //
        // 全程不删账户、不清数据、不退出登录。
        post("")
        post("> 检查「开机向导」闸状态 ...")
        // 【v1.7.0】本流程不再自动置 0。置 0 已移到卡片上的「第一步：准备」按钮，
        // 由用户先手动点开；这里只做一次**防御性检查**（经 Shizuku 的 shell 身份回读），
        // 闸没开就给出可执行的提示，而不是等 setDeviceOwner 吃一个被反射包成
        // InvocationTargetException 的异常（那样用户看不到任何有用信息）。
        var needRestoreGate = false
        // 【v1.8.0】把 shell 回读到闸值提到外层，供第 ⑤ 步传给 activate() 做真正的预检。
        // 旧实现里 activate() 的向导预检用应用自身读 @hide 键（恒 false → 永远放行），
        // 等于没有预检；这里把 shell 身份的权威读值传进去，预检才具备拦截能力。
        var shellGateValue: Int? = null
        run {
            val gateRes = runCatching {
                execViaShizuku(DpDoDirectActivation.buildReadSetupCompleteScript())
            }.getOrElse { Result.failure(it) }
            val gateOut = gateRes.getOrNull()
            gateOut?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.forEach { line -> post("  " + line) }
            val gateValue = DpDoDirectActivation.parseSetupCompleteOutput(gateOut)
            shellGateValue = gateValue
            if (gateValue != 0) {
                post("x 「开机向导」闸未打开（当前 user_setup_complete=" +
                        (gateValue?.toString() ?: "未确认") + "）")
                post("  请先回卡片点击「第一步：准备」，把该值临时置为 0，再点「激活」。")
                withContext(Dispatchers.Main) {
                    elevateError = "「开机向导」闸尚未打开：请先在卡片上点「第一步：准备」按钮" +
                            "（它会经 Shizuku 把 user_setup_complete 临时置为 0），然后再点「激活」。"
                    elevatePhase = 3
                }
                runCatching { refreshDirectActivationAvailability() }
                return@withContext
            }
            post("v 闸已打开（user_setup_complete=0，由卡片上的「第一步：准备」设置），开始激活")
            needRestoreGate = true
        }

        // 真正的激活。恢复 1 放在 finally 中，保证无论成功失败都执行。
        var direct: DpDoDirectActivation.ActivationResult
        try {
            post("")
            post("> 开始非 ADB 直连激活（应用自身身份）...")
            post("  注：置 0 已由卡片上的「第一步：准备」完成，本流程不重复写入。")
            // 【v1.6.4】mapdoPrecheck=false：上游已用 isMapdoReady（双通道判据）确认落地，
            // 避免内部单判据在「权限表刷新延迟」的 ROM 上误判 PERMISSION_MISSING。
            //
            // 【v1.8.0】shellSetupValue=shellGateValue：把上面经 Shizuku 回读到的权威闸值
            // 传进去，让 activate() 内部的向导预检真正生效（否则它用应用自身读 @hide 键，
            // 恒 false → 预检永远放行，形同虚设）。
            direct = runCatching {
                DpDoDirectActivation.activate(
                    context,
                    mapdoPrecheck = false,
                    shellSetupValue = shellGateValue,
                )
            }.getOrElse { t ->
                post("x 激活过程抛出异常：" + (t.message ?: t.toString()))
                DpDoDirectActivation.ActivationResult(
                    stage = DpDoDirectActivation.Stage.SET_OWNER_THREW,
                    success = false,
                    message = "激活过程异常：" + (t.message ?: t.toString()),
                    log = emptyList(),
                )
            }
            postAll(direct.log.joinToString("\n"))
        } finally {
            // 【v1.7.0】恢复 1 保留：用户在「第一步：准备」里已把闸置 0，设备此刻处于
            // 「系统认为未完成开机向导」状态（三键导航的 Home / 最近任务键失灵），
            // 激活结束后必须对称恢复，绝不能让用户停在导航键失灵的状态。
            // 仅当本次流程**确实确认过闸已打开**（needRestoreGate）才恢复，
            // 避免在闸本来就没开的设备上做无谓写入。
            if (needRestoreGate) {
                post("")
                post("> 恢复 user_setup_complete=1（经 Shizuku 的 shell 身份执行脚本）...")
                val backRes = runCatching {
                    execViaShizuku(DpDoDirectActivation.buildSetSetupCompleteScript(1))
                }.getOrElse { Result.failure(it) }
                // 【v1.6.7】与置 0 对称：判据同样取脚本输出的回读值，确认真的恢复为 1，
                // 而不是「命令发出去了就当成功」——否则设备会停在导航键失灵的状态。
                val backVal = DpDoDirectActivation.parseSetupCompleteOutput(backRes.getOrNull())
                if (backRes.isSuccess && backVal == 1) {
                    post("v 已恢复 user_setup_complete=1（脚本回读确认），导航键恢复正常")
                } else {
                    post("x 恢复 user_setup_complete 失败：" +
                            (backRes.exceptionOrNull()?.message
                                ?: "回读值=" + (backVal?.toString() ?: "未确认")))
                    post("  请手动执行：settings put secure user_setup_complete 1")
                }
                // 【v1.7.0】原 `elevateWarning = null` 已删除（字段整体移除）。
                runCatching { refreshDirectActivationAvailability() }
            }
        }

        // ---------- ⑤ 汇总 ----------
        post("")
        if (direct.success) {
            post("v 提权流程全部成功（非 ADB 直连路径）")
            post("  " + direct.message)
            withContext(Dispatchers.Main) { elevatePhase = 2 }
            runCatching {
                refreshOwnerState()
                refreshAllStates()
                refreshDirectActivationAvailability()
            }
        } else {
            post("x 提权失败")
            withContext(Dispatchers.Main) {
                elevateError = direct.message
                elevatePhase = 3
            }
        }
    }

    /** 用 Shizuku 一键激活完整的 Device Owner 权限。 */
    suspend fun enableTempDoViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val r = execViaShizuku(tempDoCommand)
        if (r.isSuccess) {
            refreshTempDoState()
            refreshAllStates()
        }
        r
    }

    /**
     * 用 Shizuku 一键激活 DO，并把原始输出翻译成友好提示。
     *
     * 与 [enableTempDoViaShizuku] 的差异：本方法会识别 `dpm` 的常见失败原因
     * （账户未清空 / 多用户 / 已有 DO），返回可直接展示给用户的中文说明。
     */
    suspend fun activateDeviceOwnerViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val raw: Result<String> = execViaShizuku(tempDoCommand)
        if (raw.isSuccess) {
            refreshTempDoState()
            // 激活成功后同步刷新整页状态（DO 卡片 / 主页指示 / 权限分组）
            refreshAllStates()
            val active = frb.axeron.manager.owner.DeviceOwnerExtras
                .isTempDoActive(AxeronApplication.axeronApp)
            return@withContext if (active) {
                Result.success(raw.getOrNull().orEmpty().ifBlank { "已激活设备所有者" })
            } else {
                Result.failure(IllegalStateException("命令已执行，但设备所有者未生效"))
            }
        }

        val msg = raw.exceptionOrNull()?.message.orEmpty()
        val friendly = when {
            msg.contains("already some accounts") ||
                    msg.contains("already several accounts") ->
                "激活失败：设备上仍有账户，请先在「设置 → 账户」中移除全部账户后重试"

            msg.contains("already some users") ->
                "激活失败：设备上存在多用户/应用分身，请先关闭或删除后再重试"

            msg.contains("device owner is already set") ->
                "激活失败：设备所有者已被其他应用占用，一台设备只能有一个"

            msg.contains("Unknown admin") ->
                "激活失败：设备管理组件未注册，请先卸载重装本应用"

            else -> "激活失败：$msg"
        }
        Result.failure(IllegalStateException(friendly))
    }

    /**
     * 用 Shizuku 一键激活资料所有者（Profile Owner）。
     *
     * 与 [activateDeviceOwnerViaShizuku] 的区别：只作用于当前用户（工作资料），
     * 且与 Device Owner **互斥**（已是 DO 时会明确报错）。
     */
    suspend fun activateProfileOwnerViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        val raw: Result<String> = execViaShizuku(tempProfileOwnerCommand)
        if (raw.isSuccess) {
            refreshProfileOwnerState()
            refreshOwnerState()
            refreshAllStates()
            val active = frb.axeron.manager.owner.DeviceOwnerExtras
                .isTempProfileOwnerActive(AxeronApplication.axeronApp)
            return@withContext if (active) {
                Result.success(raw.getOrNull().orEmpty().ifBlank { "已激活资料所有者" })
            } else {
                Result.failure(IllegalStateException("命令已执行，但资料所有者未生效"))
            }
        }

        val msg = raw.exceptionOrNull()?.message.orEmpty()
        val friendly = when {
            msg.contains("already has a device owner") ||
                    msg.contains("device owner") ->
                "激活失败：设备已是设备所有者，与资料所有者互斥"

            msg.contains("already some users") ->
                "激活失败：设备上存在多用户/应用分身，请先关闭或删除后再重试"

            msg.contains("Unknown admin") ->
                "激活失败：设备管理组件未注册，请先卸载重装本应用"

            else -> "激活失败：$msg"
        }
        Result.failure(IllegalStateException(friendly))
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

    // =====================================================================
    // 【v1.3.1】用 Shizuku 直接启动 Axeron 服务（激活页「通过 Shizuku 激活」卡片）
    //
    // 原理参照 Scene（com.omarea.vtools）的 `up.sh`：激活的本质就是「以 shell
    // 身份把服务进程拉起来」，我们的等价物是 [Starter.internalCommand]：
    //
    //     <nativeLibraryDir>/libaxeron.so --apk=<apk 路径>
    //
    // 与卡片 1（DP + Shizuku）、卡片 2（非 ADB 直连）的区别：
    //   · 不做 Device Owner / DP Role 的任何操作，只负责「启动服务」；
    //   · 因此不需要电脑、不需要重启、不碰账户，只要有 Shizuku 授权即可。
    //
    // 【隔离约定】本段全部为新增成员，不改动上方任何既有状态与方法。
    // =====================================================================

    /**
     * 「通过 Shizuku 激活」卡片展示 / 复制的命令原文。
     *
     * 与 UI 展示保持一致：这里刻意用 `Starter.internalCommand`（裸命令），
     * **不带 `adb shell` 前缀** —— 前缀属于电脑端，交给设备内 shell 执行会失败
     * （与 [tempDoPcCommand] 的处理约定相同）。
     */
    val shizukuLaunchCommand: String
        get() = Starter.internalCommand

    /**
     * 用 Shizuku（shell 身份）启动 Axeron 服务。
     *
     * ## 为什么命令要「脱离会话」再执行
     *
     * `libaxeron.so` 启动后**本身就是常驻的服务进程**，不是执行完就退出的短命令。
     * 若直接把它交给 `sh -c` 前台执行，Shizuku 侧的子进程会一直不结束，
     * `execViaShizuku` 就会一直等（等于卡死）。因此这里用与
     * [AxeronCommandSession.getQuickCmd] 同样的思路，用 `setsid` 把它放进
     * **新的会话**并转入后台，再立即重定向标准流：
     *
     *     setsid <命令> >/dev/null 2>&1 &
     *
     * `setsid` 使服务成为新会话的首进程，脱离我们这条 shell 会话 ——
     * 外层 shell 退出时它不会被连带杀死，Binder 也能正常注册。
     *
     * @return 成功（命令已发出）或失败信息（未授权 / Shizuku 未运行 / 执行报错）
     */
    suspend fun activateViaShizuku(): Result<String> = withContext(Dispatchers.IO) {
        // ① 先确保有 binder：没有就主动拉一次（原版 Shizuku 通道修复，见 ShizukuBinderPuller）
        //    注意：拉取失败**不能**直接返回 —— 交给下面的 execViaShizuku 抛出可读原因
        //    （「Shizuku 未运行」/「未获得 Shizuku 授权」），否则用户只看到拉取异常。
        if (!Shizuku.pingBinder()) {
            runCatching { ShizukuBinderPuller.pull(AxeronApplication.axeronApp) }
            delay(SHIZUKU_PULL_WAIT_MS)
        }
        // ② 授权检查交给 execViaShizuku 内部统一处理（未授权时抛出可读错误）
        //    此处**不能**再套一层 runCatching：execViaShizuku 已返回 Result<String>，
        //    再包会变成 Result<Result<String>>，与函数声明的返回类型不符（CI 编译报错 2120:9）。
        val detached = "setsid ${Starter.internalCommand} >/dev/null 2>&1 &"
        execViaShizuku(detached)
            .onSuccess {
                Log.i("AxManager", "activateViaShizuku: 已通过 Shizuku 拉起 Axeron 服务")
            }
            .onFailure {
                Log.w("AxManager", "activateViaShizuku 失败: ${it.message}", it)
            }
    }
}