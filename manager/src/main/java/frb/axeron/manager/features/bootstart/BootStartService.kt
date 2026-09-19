package frb.axeron.manager.features.bootstart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import frb.axeron.adb.AdbMdns
import frb.axeron.adb.util.AdbEnvironment
import frb.axeron.api.Axeron
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.R
import frb.axeron.manager.adb.AdbStateInfo
import frb.axeron.manager.adb.AdbStarter
import frb.axeron.manager.owner.DeviceOwnerAdbActivator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「开机自动启动（无线调试预热）」前台服务。
 *
 * 设计参考 Stellar 的 `AdbStartWorker` + `SelfStarterService`，但用前台服务替代
 * WorkManager（本工程未引入 WorkManager 依赖，且前台服务在国产 ROM 上更容易被
 * 放行 —— 与既有的 [frb.axeron.manager.features.keepalive.KeepAliveService] 同一套路）。
 *
 * 为什么需要它（v1.6.0 实测结论）：
 *   Device Owner 已能在开机时成功把 `adb_enabled` / `adb_wifi_enabled` 打开
 *   （用户实测「开机自动打开了无线调试」），但后续「绑 TCP + 回连」失败。
 *   原因是既有路径 [AdbStarter.startAdbWireless] 被两道门控卡住：
 *     · WifiReadyGate —— 要求 Wi-Fi 链路已连接；
 *     · AdbWifiGate  —— 要求本应用持有 WRITE_SECURE_SETTINGS。
 *   DO 场景下（开机早期无 Wi-Fi、普通应用无 WRITE_SECURE_SETTINGS）两道门都过不去，
 *   于是永远拿不到无线调试的随机端口。
 *
 * Stellar 的做法是**完全绕开这两道门**，直接：
 *   ① 以特权身份写 ADB 全局设置；
 *   ② 端口发现走 SystemProperties（service.adb.tcp.port / persist.adb.tcp.port），
 *      拿不到再用 AdbMdns 发现 `_adb-tls-connect._tcp`（无线调试的随机 TLS 端口）；
 *   ③ 用本机保存的 ADB 私钥回连 127.0.0.1:<port> 完成 ADB 握手并执行内部启动命令。
 *
 * 本服务实现同一套流程，并在成功后把 `adb_wifi_enabled` 置 0（收紧暴露面）。
 */
class BootStartService : Service() {

    companion object {
        private const val TAG = "BootStartService"

        private const val CHANNEL_ID = "axmanager_boot_start"
        private const val NOTIFICATION_ID = 0x7A12

        private const val ACTION_START = "frb.axeron.manager.action.BOOT_START"
        private const val ACTION_CANCEL = "frb.axeron.manager.action.BOOT_START_CANCEL"

        /** mDNS 端口发现的总超时（无线调试端口是随机的，只能靠发现）。 */
        private const val PORT_DISCOVERY_TIMEOUT_MS = 45_000L

        /** 端口已知情况下，回连前的就绪等待。 */
        private const val CONNECT_TIMEOUT_MS = 25_000L

        /** 整体重试轮数（每轮之间退避，应对开机早期 adbd 尚未就绪）。 */
        private const val MAX_ROUNDS = 3

        /** 固定端口绑定后，轮询等待 adbd 真正监听的参数（开机早期 adbd 启动有延迟）。 */
        private const val PROBE_ATTEMPTS = 16
        private const val PROBE_INTERVAL_MS = 500L

        /** 启动服务（幂等：重复调用不会产生多个实例）。 */
        fun start(context: Context) {
            val intent = Intent(context, BootStartService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.e(TAG, "start failed", it)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BootStartService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.stopService(intent) }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var adbMdns: AdbMdns? = null

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification(getString(R.string.boot_start_enabling_adb))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 已经处于运行中（Axeron 已激活）则无需重复启动。
        if (Axeron.pingBinder()) {
            Log.i(TAG, "Axeron already running, skip boot start")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            Log.i(TAG, "boot start already in progress")
            return START_NOT_STICKY
        }
        running = true
        serviceScope.launch { runBootStart() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { adbMdns?.stop() }
        runCatching { serviceScope.cancel() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 主流程

    private suspend fun runBootStart() {
        var lastError = ""
        for (round in 1..MAX_ROUNDS) {
            Log.i(TAG, "boot start round $round/$MAX_ROUNDS")

            // ① 以特权身份打开 ADB（DO 场景）或尽力用 WRITE_SECURE_SETTINGS（Root/ADB 场景）
            if (!ensureAdbEnabled()) {
                lastError = "enable adb failed"
                Log.w(TAG, "round $round: $lastError")
            } else {
                // ② 端口发现：SystemProperties → mDNS（绕开 WifiReadyGate / AdbWifiGate）
                updateNotification(getString(R.string.boot_start_discovering_port))
                val port = discoverPort()
                if (port > 0) {
                    Log.i(TAG, "round $round: discovered port $port")

                    // ③ 回连 127.0.0.1:<port>，由 AdbClient 完成 TLS + 密钥握手并执行内部命令
                    updateNotification(getString(R.string.boot_start_connecting))
                    val result = connectAndStart(port)
                    if (result is AdbStateInfo.Success) {
                        Log.i(TAG, "boot start succeeded on round $round")
                        onSuccess()
                        return
                    }
                    lastError = (result as? AdbStateInfo.Failed)?.message ?: "connect failed"
                } else {
                    lastError = "no adb port discovered"
                }
            }

            Log.w(TAG, "round $round failed: $lastError")
            if (round < MAX_ROUNDS) {
                updateNotification(getString(R.string.boot_start_retrying))
                delay(5_000L * round)
            }
        }

        Log.e(TAG, "boot start gave up: $lastError")
        onFailure()
    }

    /**
     * 打开 ADB 相关全局设置。
     *
     * 优先级：
     *   ① Device Owner —— 通过 DevicePolicyManager.setGlobalSetting（v1.6.0 已验证可用）；
     *   ② WRITE_SECURE_SETTINGS —— Root / ADB 激活场景下的常规写法。
     */
    private fun ensureAdbEnabled(): Boolean {
        // ① Device Owner 路径
        if (DeviceOwnerAdbActivator.isOwner(this)) {
            val r = DeviceOwnerAdbActivator.enableAdb(this)
            Log.i(TAG, "enableAdb via DeviceOwner: success=${r.first} ${r.second ?: ""}")
            if (r.first) return true
        }

        // ② WRITE_SECURE_SETTINGS 路径（尽力而为）
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted and not a device owner")
            // 即便是 DO 失败、权限也没有，仍返回 true 让流程继续尝试端口发现，
            // 因为「无线调试已被系统自动打开」的情况下端口可能是活的。
            return DeviceOwnerAdbActivator.getAdbTcpPort() > 0
        }
        return runCatching {
            val cr = contentResolver
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            // 关闭自动断开（必须是 MAX_VALUE；0 = 沿用系统默认超时，见
            // DeviceOwnerAdbActivator.enableAdb 的注释）。
            Settings.Global.putLong(cr, "adb_allowed_connection_time", Int.MAX_VALUE.toLong())
            true
        }.getOrElse {
            Log.e(TAG, "putGlobal failed", it)
            false
        }
    }

    /**
     * 【v1.6.3 根因修复】把 adbd 主动钉到用户设定的固定端口，并等待其就绪。
     *
     * 为什么必须这么做（实测 + 联网查证结论）：
     *   · 原生「无线调试」的端口由系统随机分配（TLS 端口），**重启后必然变化**；
     *   · [AdbMdns] 走 `_adb-tls-connect._tcp` 发现，且 [AdbMdns.onServiceResolved]
     *     会先用 bind 判断端口是否「在本机可 bind」（即未被占用）才回调 ——
     *     开机早期 adbd 尚未注册服务，45s 超时内找不到端口是常态；
     *   · 结论：开机自启动**不能靠「发现随机端口」**，只能靠「自己把端口钉死」。
     *
     * 做法（与既有 [DeviceOwnerAdbActivator.bindAdbTcp] 同源，且在 DO 场景走
     * `setprop` + 重启 adbd，不依赖任何已有 ADB 连接）：
     *   ① 读用户持久化的固定端口（[AxeronSettings.getBootStartPort]，Settings 里
     *      打开「开机自启动」开关时由 [PortHelper.generateSafeRandomPort] 生成）；
     *   ② 已是该端口且可连 → 直接返回；
     *   ③ 否则 setprop service.adb.tcp.port + 重启 adbd，轮询等待端口可连。
     *
     * @return 就绪的端口号；非 DO / 未配置 / 失败时返回 -1（调用方继续走后面的兜底逻辑）。
     */
    private suspend fun ensureFixedPortBound(): Int {
        if (!DeviceOwnerAdbActivator.isOwner(this)) return -1

        val fixedPort = runCatching { AxeronSettings.getBootStartPort() }.getOrDefault(-1)
        if (fixedPort !in 1..65535) return -1

        // ① 已经是目标端口并且真的可连 → 不必重启 adbd（省一次开机早期的抖动）
        if (DeviceOwnerAdbActivator.getAdbTcpPort() == fixedPort &&
            probeLocalPort(fixedPort)
        ) {
            Log.i(TAG, "fixed port $fixedPort already live")
            return fixedPort
        }

        // ② setprop + 重启 adbd，把 adbd 钉到固定端口
        val (bound, err) = DeviceOwnerAdbActivator.bindAdbTcp(fixedPort)
        Log.i(TAG, "bindAdbTcp($fixedPort) bound=$bound ${err ?: ""}")
        if (bound) return fixedPort

        // ③ 即便 bind 报告失败，也再探一次：部分 ROM 上 adbd 起来比探测慢
        return if (probeLocalPort(fixedPort)) fixedPort else -1
    }

    /** 探测 127.0.0.1:port 是否可连（adbd 是否真的在监听）。 */
    private suspend fun probeLocalPort(port: Int): Boolean {
        repeat(PROBE_ATTEMPTS) { i ->
            val ok = runCatching {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", port), 250)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
            if (i < PROBE_ATTEMPTS - 1) delay(PROBE_INTERVAL_MS)
        }
        return false
    }

    /**
     * 端口发现。
     *
     * 顺序（v1.6.3 起）：
     *   ① **固定端口**（Device Owner 专属）—— 见 [ensureFixedPortBound]。
     *      原生无线调试的端口是**随机 TLS 端口**且重启后变化，开机早期 mDNS 往往
     *      还没注册完成，靠「发现」在开机时刻基本必失败。因此这里改为
     *      「主动把 adbd 钉在固定端口上，再回连这个已知端口」。
     *   ② SystemProperties（有线 TCP 模式一定命中）；
     *   ③ mDNS 兜底。
     *
     * **这里刻意不复用 [AdbStarter.startAdbWireless]**，因为那条路径的
     * WifiReadyGate / AdbWifiGate 在开机早期（无 Wi-Fi / 无 WRITE_SECURE_SETTINGS）
     * 必定失败 —— 这正是 v1.6.0 实测卡住的原因。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun discoverPort(): Int {
        // ① 固定端口：DO 场景主动把 adbd 钉到已知端口（开机自启动的关键）
        val fixed = ensureFixedPortBound()
        if (fixed > 0) {
            Log.i(TAG, "port bound from fixed boot-start port: $fixed")
            return fixed
        }

        // ② SystemProperties（service.adb.tcp.port → persist.adb.tcp.port）
        val sysPort = AdbEnvironment.getAdbTcpPort()
        if (sysPort > 0) {
            Log.i(TAG, "port from SystemProperties: $sysPort")
            return sysPort
        }

        // ③ mDNS 发现无线调试端口（_adb-tls-connect._tcp）
        Log.i(TAG, "SystemProperties has no port, fallback to mDNS discovery")
        return withTimeoutOrNull(PORT_DISCOVERY_TIMEOUT_MS) {
            val channel = Channel<Int>(1)

            // AdbMdns 通过 Observer<Int> 回调发现的端口；这里直接把回调桥接到 Channel。
            val observer = Observer<Int> { p ->
                Log.d(TAG, "mDNS discovered port: $p")
                if (p in 1..65535) {
                    channel.trySend(p)
                }
            }

            val mdns = AdbMdns(this@BootStartService, AdbMdns.TLS_CONNECT, observer)
            adbMdns = mdns
            // 无限刷新：无线调试端口是随机的，且开机早期可能尚未注册完成。
            mdns.indefinite = true
            runCatching { mdns.start() }.onFailure {
                Log.e(TAG, "mDNS start failed", it)
            }

            val result = runCatching { channel.receive() }.getOrNull() ?: -1

            runCatching { mdns.stop() }
            adbMdns = null
            result
        } ?: -1
    }

    /** 回连本机 ADB 端口，复用既有 AdbStarter 的握手 + 内部命令执行。 */
    private suspend fun connectAndStart(port: Int): AdbStateInfo {
        val channel = Channel<AdbStateInfo>(1)
        val job = serviceScope.launch {
            runCatching {
                AdbStarter.startAdbClient(this@BootStartService, port) { state ->
                    Log.d(TAG, "startAdbClient state: ${state.message}")
                    channel.trySend(state)
                }
            }.onFailure {
                Log.e(TAG, "startAdbClient threw", it)
                channel.trySend(AdbStateInfo.Failed(it.message ?: "unknown"))
            }
        }
        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { channel.receive() }
            ?: AdbStateInfo.Failed("timeout waiting for adb handshake")
        job.cancel()
        return result
    }

    private fun onSuccess() {
        // 记录启动方式，保证下次重启仍走「无线调试预热」分支。
        // 注意：必须是 DEVICE_OWNER —— [AdbStarter.startAdbClient] 握手成功后会把它
        // 覆写成 ADB，这里显式改回来，否则下次开机不会进 DEVICE_OWNER 分支。
        runCatching {
            AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.DEVICE_OWNER)
        }.onFailure { Log.w(TAG, "setLastLaunchMode failed", it) }

        // 【v1.6.3 修复】只有在「没有固定端口」时才关无线调试。
        // 旧实现在这里无条件关掉 adb_wifi_enabled —— 而固定端口恰恰依附于无线调试
        // 打开状态，关掉它等同于把下次开机的连接路径一起掐断（这正是「只有开机自启
        // 生效、无线调试无法真正自启」的直接原因之一）。
        val keepFixedPort = runCatching { AxeronSettings.getBootStartPort() }.getOrDefault(-1)
        if (keepFixedPort in 1..65535) {
            Log.i(TAG, "fixed port $keepFixedPort configured, keep adb_wifi_enabled")
        } else {
            // 未配置固定端口（非 DO / 老用户）→ 维持原有的收紧暴露面行为。
            runCatching {
                if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 0)
                } else if (DeviceOwnerAdbActivator.isOwner(this)) {
                    DeviceOwnerAdbActivator.setGlobal(this, "adb_wifi_enabled", "0")
                }
            }.onFailure { Log.w(TAG, "disable adb_wifi_enabled failed", it) }
        }

        updateNotification(getString(R.string.boot_start_success))
        handler.postDelayed({ stopSelf() }, 3_000L)
    }

    private fun onFailure() {
        updateNotification(getString(R.string.boot_start_failed))
        handler.postDelayed({ stopSelf() }, 5_000L)
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.boot_start_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.boot_start_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val cancelIntent = Intent(this, BootStartService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPi = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_axeron)
            .setContentTitle(getString(R.string.boot_start_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.cancel),
                    cancelPi
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    @Volatile
    private var running = false
}