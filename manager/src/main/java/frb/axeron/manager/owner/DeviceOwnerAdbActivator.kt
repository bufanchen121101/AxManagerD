package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemProperties
import android.provider.Settings
import frb.axeron.api.core.AxeronSettings
import frb.axeron.server.util.Logger
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 以「设备所有者（Device Owner）」身份开启 ADB 并让 adbd 监听 TCP 端口。
 *
 * 参考 Shizuku 分支 Shevery 的提权链（manager/dhizuku/DhizukuService.kt）：
 *   ① 应用先成为 Device Owner；
 *   ② 以 DO 身份调用 DevicePolicyManager.setGlobalSetting(admin, "adb_enabled", "1")
 *      （Android 11+ 再开 "adb_wifi_enabled"）；
 *   ③ setprop service.adb.tcp.port <port>，再重启 adbd 让它监听 TCP；
 *   ④ 回连 127.0.0.1:<port>，由 [frb.axeron.manager.adb.AdbStarter] 完成真正的
 *      ADB 握手并拿到 shell 身份（即「用设备所有者换 ADB」）。
 *
 * 与 Shevery 的差异（AxManagerD 更简单，无需 Dhizuku 用户服务）：
 *   Shevery 需要 Dhizuku 的 binderWrapper 提升 DPM 权限（用户服务跑在 system uid），
 *   而 AxManagerD 本身就能作为 Device Owner，直接拿系统 DPM 即可：
 *   - 自我 DO 场景：`context.getSystemService(DPM)` 直接可用；
 *   - 仅经 Dhizuku 授权的场景：回落到 [DeviceOwnerPrivilege.getDeviceOwnerDpm]。
 *
 * 线程模型：所有方法都会做 socket 连接 / adbd 重启等待，**禁止在主线程调用**。
 * UI 侧统一走 `Dispatchers.IO`。
 */
object DeviceOwnerAdbActivator {

    private val LOGGER = Logger("DeviceOwnerAdbActivator")

    /** 默认 ADB TCP 端口（与 Shevery 一致）。 */
    const val DEFAULT_TCP_PORT = 5555

    /** 重启 adbd 后等待端口就绪的探测参数。 */
    private const val PROBE_ATTEMPTS = 10
    private const val PROBE_INTERVAL_MS = 500L

    /** 一次激活尝试的结果。 */
    data class Result(
        val success: Boolean,
        val port: Int,
        val message: String
    )

    /**
     * 本应用是否为 Device Owner / Profile Owner（实时查询，不用静态缓存）。
     */
    fun isOwner(context: Context): Boolean {
        return runCatching { DeviceOwnerState.queryIsOwner(context) }.getOrDefault(false)
    }

    /**
     * 以 DO 身份写入 Global 设置。
     *
     * 注意：Device Owner 只能改写系统允许的那部分 Global 项（`adb_enabled` /
     * `adb_wifi_enabled` 等在白名单内），越权项会被 system_server 拒绝。
     *
     * @return Pair(是否写入成功, 失败原因)
     */
    fun setGlobal(context: Context, key: String, value: String): Pair<Boolean, String?> {
        val dpm = resolveDpm(context)
            ?: return false to "Device Owner not active"
        val admin = resolveAdmin(context)
        return try {
            dpm.setGlobalSetting(admin, key, value)
            LOGGER.i("setGlobal OK: $key=$value (admin=$admin)")
            true to null
        } catch (e: Exception) {
            LOGGER.w("setGlobal failed: $key=$value", e)
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 选择执行特权命令时使用的 DeviceAdminReceiver 组件：
     * - 自我 DO / PO：用本应用自己的 [DeviceOwnerState.admin]；
     * - 仅经 Dhizuku 授权（本应用不是 Owner）：必须用 Dhizuku 自己的 owner 组件，
     *   否则 system_server 会抛 SecurityException（见 [DeviceOwnerPrivilege] 注释）。
     */
    private fun resolveAdmin(context: Context): ComponentName {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val self = DeviceOwnerState.admin
        val selfIsOwner = dpm != null &&
                (runCatching { dpm.isDeviceOwnerApp(self.packageName) }.getOrDefault(false) ||
                        runCatching { dpm.isProfileOwnerApp(self.packageName) }.getOrDefault(false))
        return if (selfIsOwner) self else (DeviceOwnerPrivilege.ownerComponent() ?: self)
    }

    /**
     * 取得具备 DO 特权的 DevicePolicyManager：优先系统真实 DPM（自我 DO），
     * 否则回落到 [DeviceOwnerPrivilege.getDeviceOwnerDpm]（经 Dhizuku 转发）。
     */
    private fun resolveDpm(context: Context): DevicePolicyManager? {
        val sysDpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (sysDpm != null) {
            val admin = DeviceOwnerState.admin
            val selfIsOwner =
                runCatching { sysDpm.isDeviceOwnerApp(admin.packageName) }.getOrDefault(false) ||
                        runCatching { sysDpm.isProfileOwnerApp(admin.packageName) }.getOrDefault(false)
            if (selfIsOwner) return sysDpm
        }
        return DeviceOwnerPrivilege.getDeviceOwnerDpm(context)
    }

    /**
     * 以 DO 身份开启 ADB（含 Android 11+ 的无线调试）。
     */
    fun enableAdb(context: Context): Pair<Boolean, String?> {
        val errors = mutableListOf<String>()

        val (okEnabled, errEnabled) =
            setGlobal(context, Settings.Global.ADB_ENABLED, "1")
        if (!okEnabled) errors += "adb_enabled: $errEnabled"

        // Android 11+：无线调试与 ADB 端口由 adb_wifi_enabled 控制。
        // 部分设备/OEM 未开放该项，失败不视为致命（有线 TCP 仍可走）。
        val (okWifi, errWifi) = setGlobal(context, "adb_wifi_enabled", "1")
        if (!okWifi) LOGGER.i("adb_wifi_enabled not writable: $errWifi")

        // 关闭「ADB 授权超时自动关闭」，避免激活完端口就掉了。
        // 【v1.6.3 修复】注意值：`0` 表示**沿用系统默认的自动断开超时**
        // （Android 13 起默认约 1 小时无操作即关闭 adb），会被 system_server 重置；
        // 要真正关闭该机制必须写 **Integer.MAX_VALUE**（与 aosp 的
        // `ADB_ALLOWED_CONNECTION_TIME` 判定 `== MAX_VALUE → 不再自动关闭` 一致）。
        // 旧实现写 0，等于没关 —— 这是「无线调试熬不过一段时间就掉」的直接原因。
        val (okTimeout, errTimeout) =
            setGlobal(context, "adb_allowed_connection_time", Int.MAX_VALUE.toString())
        if (!okTimeout) LOGGER.i("adb_allowed_connection_time not writable: $errTimeout")

        // 关键项 adb_enabled 写成功即认为开启成功；无线调试项作尽力而为。
        return if (okEnabled || okWifi) {
            true to null
        } else {
            false to (errors.joinToString("; ").ifBlank { "setGlobalSetting rejected" })
        }
    }

    /**
     * 读取当前生效的 ADB TCP 端口：service.adb.tcp.port → persist.adb.tcp.port。
     * 均未设置时返回 -1（与 Shevery 的 getAdbPort 逻辑一致）。
     */
    fun getAdbTcpPort(): Int {
        var port = runCatching { SystemProperties.getInt("service.adb.tcp.port", -1) }.getOrDefault(-1)
        if (port <= 0) {
            port = runCatching { SystemProperties.getInt("persist.adb.tcp.port", -1) }.getOrDefault(-1)
        }
        return port
    }

    /**
     * 让 adbd 监听 TCP 端口。
     *
     * 采用 setprop 方案（与 Shevery 一致，不依赖 ADB 协议握手）：
     *   setprop service.adb.tcp.port <port>
     *   setprop ctl.restart adbd   （失败则 stop adbd; start adbd）
     * 之后再探测端口是否真的可连接。
     *
     * 注意：`ctl.restart` 需要足够的 SELinux / uid 权限。作为 Device Owner
     * （或经 Dhizuku 提升）时直接执行受阻的话，会返回 false —— 此时
     * [frb.axeron.manager.adb.AdbStarter] 里的 `adbd tcpip:<port>` 是备用路径
     * （需已建立一次 ADB 连接），UI 会在失败提示里引导用户。
     *
     * @return Pair(是否成功, 说明)
     */
    fun bindAdbTcp(port: Int = DEFAULT_TCP_PORT): Pair<Boolean, String?> {
        if (port <= 0) return false to "invalid port"

        // 已经是目标端口且端口可达 → 直接成功，避免无谓重启 adbd。
        if (getAdbTcpPort() == port && probePort(port, attempts = 1, intervalMs = 0)) {
            LOGGER.i("bindAdbTcp: port $port already live")
            return true to null
        }

        runCatching {
            SystemProperties.set("service.adb.tcp.port", port.toString())
            LOGGER.i("service.adb.tcp.port=$port")
        }.onFailure {
            LOGGER.w("set service.adb.tcp.port failed", it as? Exception ?: Exception(it))
            return false to "setprop service.adb.tcp.port failed: ${it.message}"
        }

        // 重启 adbd（两种写法都试，不同 ROM 支持的写法不一样）
        val restartOk = execShell("setprop ctl.restart adbd")
        if (!restartOk) {
            LOGGER.i("ctl.restart adbd failed, fallback to stop/start")
            execShell("stop adbd")
            execShell("start adbd")
        }

        val live = probePort(port)
        LOGGER.i("bindAdbTcp: port=$port live=$live")
        return if (live) {
            true to null
        } else {
            false to "adbd did not listen on $port"
        }
    }

    /** 在本地执行一条 sh 命令，返回是否 exitCode==0。 */
    private fun execShell(cmd: String): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            // 必须消费 stdout/stderr，否则缓冲区满了会阻塞子进程。
            drainAsync(process)
            process.waitFor()
            process.exitValue() == 0
        }.onFailure {
            LOGGER.w("execShell failed: $cmd", it as? Exception ?: Exception(it))
        }.getOrDefault(false)
    }

    private fun drainAsync(process: Process) {
        runCatching {
            Thread {
                runCatching { process.inputStream.bufferedReader().use { it.readText() } }
            }.apply { isDaemon = true }.start()
            Thread {
                runCatching { process.errorStream.bufferedReader().use { it.readText() } }
            }.apply { isDaemon = true }.start()
        }
    }

    /** 轮询探测 127.0.0.1:port 是否可连接（即 adbd 是否在监听）。 */
    private fun probePort(
        port: Int,
        attempts: Int = PROBE_ATTEMPTS,
        intervalMs: Long = PROBE_INTERVAL_MS
    ): Boolean {
        repeat(attempts) { i ->
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                true
            }.getOrDefault(false)
            if (ok) return true
            if (intervalMs > 0 && i < attempts - 1) {
                runCatching { Thread.sleep(intervalMs) }
            }
        }
        return false
    }

    /**
     * 完整流程（必须在 IO 线程调用）：
     *   ① 校验 DO / PO 身份
     *   ② 以 DO 身份开启 ADB（+ 无线调试）
     *   ③ setprop + 重启 adbd，让 adbd 监听 TCP
     *
     * 不包含「回连拿 shell」这一步——那一步由 [frb.axeron.manager.adb.AdbStarter.startAdbClient]
     * 完成（它会用本机保存的 ADB 私钥与设备握手）。
     *
     * @param port 目标 TCP 端口，<=0 时自动采用当前已生效端口或 [DEFAULT_TCP_PORT]
     */
    fun enableAdbAndBindTcp(context: Context, port: Int = 0): Result {
        if (!isOwner(context)) {
            return Result(false, -1, "Device Owner not active")
        }
        val (enabled, enableErr) = enableAdb(context)
        if (!enabled) {
            return Result(false, -1, enableErr ?: "Failed to enable ADB")
        }

        // "Enable ADB and Activate" must always use the classic Device-Owner path.
        // The fixed boot-start port is only used by the separate
        // ActivateViewModel.startAdbByFixedPort() (the "Port Auto Start" button),
        // so keep this flow independent of AxeronSettings.getBootStartPort().
        val target = when {
            port > 0 -> port
            getAdbTcpPort() > 0 -> getAdbTcpPort()
            else -> DEFAULT_TCP_PORT
        }
        val (bound, bindErr) = bindAdbTcp(target)
        if (!bound) {
            return Result(false, target, bindErr ?: "Failed to bind ADB TCP")
        }
        return Result(true, target, "ADB listening on 127.0.0.1:$target")
    }
}
