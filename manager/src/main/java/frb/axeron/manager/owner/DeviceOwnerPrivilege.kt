package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import com.rosan.dhizuku.api.Dhizuku
import frb.axeron.server.util.Logger
import java.lang.reflect.Method

/**
 * 设备所有者（Device Owner）特权命令执行器。
 *
 * 通过 Dhizuku 的 binderWrapper 把 [DevicePolicyManager] 的 mService 字段替换为
 * 「经由 Dhizuku 转发的 Binder」，从而以 Device Owner 身份调用 DPM 的高级 API。
 *
 * 关键点（与第三方参考一致）：调用 Device Owner 接口时，第一个 ComponentName 必须传
 * Dhizuku 自己的 DeviceAdminReceiver（[ownerComponent]），而不是 AxManager 的 Receiver，
 * 否则 system_server 会抛 SecurityException。
 *
 * 提供三级路由的 shell 命令解析（供模块的 action.sh 通过 `axeron-dpm` 调用）：
 * - 第一级：标准 DPM 公开 API（hide / unhide）
 * - 第二级：隐藏 API 包装（force-stop / uninstall，占位）
 * - 第三级：拒绝名单（mount / chmod ... 返回需 root 提示）
 */
object DeviceOwnerPrivilege {
    private val LOGGER = Logger("DeviceOwnerPrivilege")

    private const val REAL_STUB = "android.app.admin.IDevicePolicyManager\$Stub"

    /**
     * 获取经 Dhizuku 提升为 Device Owner 身份的 DevicePolicyManager。
     *
     * 仅通过反射 + IBinder 处理 mService 字段（不依赖自定义隐藏接口 stub 的类型），
     * 避免运行时 ClassCastException。
     *
     * @return 提升后的 DPM，失败（未激活/未授权）返回 null。
     */
    fun getDeviceOwnerDpm(context: Context): DevicePolicyManager? {
        return try {
            // 修复「第一条成功、后续 Device Owner not active」问题：
            // Dhizuku 的 remote(会被 linkToDeath 清空) 与 mOwnerComponent(不会清空)
            // 静态缓存生命周期错位，导致第二次 init 命中缓存分支时
            // getOwnerComponent().getPackageName() 抛 IllegalStateException。
            // 这里做「init → 失败/异常则 reset 一次 → 再 init」的保护。
            var inited = try {
                Dhizuku.init(context)
            } catch (t: Throwable) {
                LOGGER.w("getDeviceOwnerDpm: init 抛异常，重置缓存重试: ${t.message}")
                false
            }
            if (!inited) {
                Dhizuku.reset()
                inited = try {
                    Dhizuku.init(context)
                } catch (t: Throwable) {
                    LOGGER.w("getDeviceOwnerDpm: 重试 init 仍失败: ${t.message}")
                    false
                }
                if (!inited) {
                    LOGGER.w("getDeviceOwnerDpm: Dhizuku not available")
                    return null
                }
            }
            if (!Dhizuku.isPermissionGranted()) {
                LOGGER.w("getDeviceOwnerDpm: Dhizuku permission not granted")
                return null
            }

            // 参考 OwnDroid 的 binderWrapperDevicePolicyManager：
            // 必须从 Dhizuku 自己的 package context（CONTEXT_IGNORE_SECURITY=2）取 DPM，
            // 否则拿到的是绑定到 AxManager 自己 uid 的 mService，binderWrapper 后身份仍不对，
            // 表现为「权限 UID 反而不如 shell」，DO 指令（list-owners / suspend 等）被
            // getCallerIdentity / checkCallAuthorization 拒绝。
            val ownerComponent = Dhizuku.getOwnerComponent()
                ?: run {
                    LOGGER.w("getDeviceOwnerDpm: owner component is null")
                    return null
                }
            val ownerContext = context.createPackageContext(
                ownerComponent.packageName,
                2 /* Context.CONTEXT_IGNORE_SECURITY */
            )
            val dpm = ownerContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val field = DevicePolicyManager::class.java.getDeclaredField("mService")
            field.isAccessible = true

            // 旧值：系统真实的 IDevicePolicyManager.Stub（实现隐藏接口），取它的 asBinder()
            val oldService = field.get(dpm)
            val oldBinder: IBinder = if (oldService is IBinder) {
                oldService
            } else {
                // 反射调用旧对象的 asBinder()
                val asBinder = oldService?.javaClass?.getMethod("asBinder")
                asBinder?.invoke(oldService) as? IBinder
                    ?: return null
            }

            // 经 Dhizuku 转发，得到包裹后的 Binder
            val wrappedBinder = Dhizuku.binderWrapper(oldBinder)

            // 用系统真实的 Stub.asInterface 还原为代理对象（它实现了系统隐藏接口，
            // mService 字段的声明类型正是该接口，因此可安全 field.set 回去）
            val newService = realAsInterface(wrappedBinder)
                ?: return null

            field.set(dpm, newService)
            dpm
        } catch (e: Exception) {
            LOGGER.w("getDeviceOwnerDpm failed", e)
            null
        }
    }

    /**
     * 反射调用系统真实的 `android.app.admin.IDevicePolicyManager$Stub.asInterface(IBinder)`。
     */
    private fun realAsInterface(binder: IBinder): Any? {
        val realStub = Class.forName(REAL_STUB)
        val m: Method = realStub.getDeclaredMethod("asInterface", IBinder::class.java)
        m.isAccessible = true
        return m.invoke(null, binder)
    }

    /** Dhizuku 的 owner 组件（调用 DO 接口时作为第一个参数）。 */
    fun ownerComponent(): ComponentName? = try {
        Dhizuku.getOwnerComponent()
    } catch (e: Exception) {
        null
    }

    /**
     * 执行 `axeron-dpm` 命令。
     * @return Pair(exitCode, output)，exitCode = 0 成功，非 0 失败。
     */
    fun execute(context: Context, args: List<String>): Pair<Int, String> {
        if (args.isEmpty()) {
            return 1 to "Usage: axeron-dpm <hide|unhide|suspend|unsuspend|set-anim|set-global|set-secure|cleardata|grant|deny|block-uninstall|unblock-uninstall|reboot|locknow|force-stop|uninstall> ..."
        }
        val dpm = getDeviceOwnerDpm(context) ?: return 1 to "Error: Device Owner not active"
        val admin = ownerComponent() ?: return 1 to "Error: Device Owner not active"
        LOGGER.i("execute: admin=${admin.flattenToString()} package=${admin.packageName} class=${admin.className}")
        val command = args[0]
        return try {
            when (command) {
                "hide" -> level1Hide(dpm, admin, args)
                "unhide" -> level1Unhide(dpm, admin, args)
                "suspend" -> level1Suspend(dpm, admin, args)
                "unsuspend" -> level1Unsuspend(dpm, admin, args)
                "set-anim" -> level1SetAnim(dpm, admin, args)
                "set-global" -> level1SetGlobalSetting(dpm, admin, args)
                "set-secure" -> level1SetSecureSetting(dpm, admin, args)
                "cleardata" -> level1ClearData(dpm, admin, args)
                "grant" -> level1SetPermission(dpm, admin, args, true)
                "deny" -> level1SetPermission(dpm, admin, args, false)
                "block-uninstall" -> level1SetUninstallBlocked(dpm, admin, args, true)
                "unblock-uninstall" -> level1SetUninstallBlocked(dpm, admin, args, false)
                "reboot" -> level1Reboot(dpm, admin)
                "locknow" -> level1LockNow(dpm)
                "force-stop" -> level2ForceStop(args)
                "uninstall" -> level2Uninstall(args)
                "mount", "chmod", "chown", "insmod", "rmmod" -> level3Reject(command)
                else -> 2 to "Error: unknown command '$command'"
            }
        } catch (e: SecurityException) {
            LOGGER.w("SecurityException in execute", e)
            3 to "Error: SecurityException (${e.message ?: "permission denied"})"
        } catch (e: Exception) {
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------- 第一级：标准 DPM 公开 API ----------

    private fun level1Hide(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm hide <package>"
        val pkg = args[1]
        dpm.setApplicationHidden(admin, pkg, true)
        return 0 to "Success: hidden $pkg"
    }

    private fun level1Unhide(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm unhide <package>"
        val pkg = args[1]
        dpm.setApplicationHidden(admin, pkg, false)
        return 0 to "Success: unhidden $pkg"
    }

    private fun level1Suspend(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm suspend <package>"
        // 支持传入多个包：axeron-dpm suspend pkg1 pkg2 pkg3
        val packages = args.subList(1, args.size).toTypedArray()
        // 全走 DO：Dhizuku Device Owner 的 DPM.setPackagesSuspended
        val failed = dpm.setPackagesSuspended(admin, packages, true)
        return if (failed.isNullOrEmpty()) {
            0 to "Success: suspended ${packages.joinToString(" ")}"
        } else {
            1 to "Partial: suspend failed for ${failed.joinToString(" ")}"
        }
    }

    private fun level1Unsuspend(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm unsuspend <package>"
        val packages = args.subList(1, args.size).toTypedArray()
        // 全走 DO：Dhizuku Device Owner 的 DPM.setPackagesSuspended
        val failed = dpm.setPackagesSuspended(admin, packages, false)
        return if (failed.isNullOrEmpty()) {
            0 to "Success: unsuspended ${packages.joinToString(" ")}"
        } else {
            1 to "Partial: unsuspend failed for ${failed.joinToString(" ")}"
        }
    }

    /** set-anim <scale>：设置三个动画缩放（窗口/转场/动画时长）。 */
    private fun level1SetAnim(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm set-anim <scale>"
        val scale = args[1]
        val keys = listOf(
            android.provider.Settings.Global.WINDOW_ANIMATION_SCALE,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        )
        for (k in keys) dpm.setGlobalSetting(admin, k, scale)
        return 0 to "Success: animation scale set to $scale"
    }
    /** set-global <key> <value>：写入 Global 设置（需 DO 允许的项）。 */
    private fun level1SetGlobalSetting(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 3) return 1 to "Usage: axeron-dpm set-global <key> <value>"
        dpm.setGlobalSetting(admin, args[1], args[2])
        return 0 to "Success: Global ${args[1]} = ${args[2]}"
    }
    /** set-secure <key> <value>：写入 Secure 设置（需 DO 允许的项）。 */
    private fun level1SetSecureSetting(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 3) return 1 to "Usage: axeron-dpm set-secure <key> <value>"
        dpm.setSecureSetting(admin, args[1], args[2])
        return 0 to "Success: Secure ${args[1]} = ${args[2]}"
    }
    /** cleardata <package>：清除应用数据。 */
    private fun level1ClearData(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm cleardata <package>"
        val pkg = args[1]
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        var msg = ""
        dpm.clearApplicationUserData(admin, pkg, exec) { _, succeeded ->
            ok = succeeded
            msg = if (succeeded) "Success: cleared $pkg" else "Failed: clear $pkg"
            latch.countDown()
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        exec.shutdown()
        return (if (ok) 0 else 1) to msg
    }
    /** grant/deny <package> <permission>：设置运行时权限授予状态。 */
    private fun level1SetPermission(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>, grant: Boolean): Pair<Int, String> {
        if (args.size < 3) return 1 to "Usage: axeron-dpm ${if (grant) "grant" else "deny"} <package> <permission>"
        val state = if (grant) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED else DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        val res = dpm.setPermissionGrantState(admin, args[1], args[2], state)
        return (if (res) 0 else 1) to (if (res) "Success: ${if (grant) "granted" else "denied"} ${args[2]} for ${args[1]}" else "Failed: set permission state for ${args[1]}")
    }
    /** block-uninstall/unblock-uninstall <package>：设置禁止卸载。 */
    private fun level1SetUninstallBlocked(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>, blocked: Boolean): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm ${if (blocked) "block-uninstall" else "unblock-uninstall"} <package>"
        dpm.setUninstallBlocked(admin, args[1], blocked)
        return 0 to "Success: ${if (blocked) "blocked" else "unblocked"} uninstall for ${args[1]}"
    }
    /** reboot：重启设备。 */
    private fun level1Reboot(dpm: DevicePolicyManager, admin: ComponentName): Pair<Int, String> {
        dpm.reboot(admin)
        return 0 to "Success: rebooting"
    }
    /** locknow：锁定屏幕。 */
    private fun level1LockNow(dpm: DevicePolicyManager): Pair<Int, String> {
        dpm.lockNow()
        return 0 to "Success: locked"
    }
    // ---------- 第二级：隐藏 API 包装（占位，后续按 SDK_INT 适配事务码） ----------

    private fun level2ForceStop(args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm force-stop <package>"
        return 5 to "Error: force-stop needs hidden API (not yet wired)"
    }

    private fun level2Uninstall(args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm uninstall <package>"
        return 5 to "Error: uninstall needs hidden API (not yet wired)"
    }

    // ---------- 第三级：拒绝名单 ----------

    private fun level3Reject(cmd: String): Pair<Int, String> =
        6 to "Error: '$cmd' needs Root permission"
}