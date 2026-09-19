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
 * 提供三级路由的 shell 命令解析（`axeron-dpm` 调用）：
 * - 第一级：标准 DPM 公开 API（hide / unhide / suspend / grant / org-name / camera / keyguard /
 *   statusbar / user-restrict / lock-task / wipe 等）
 * - 第二级：隐藏 API 包装（force-stop / uninstall）
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
            // 自我 DO 快速通道：当 AxManager 自己就是 Device/Profile Owner 时，
            // `axeron-dpm` 命令是「以 DO 身份在自己的 app 进程里执行特权命令」，
            // 本就具备完整 DPM 特权，直接返回系统真实 DPM 即可，无需再走
            // Dhizuku 授权校验与 binderWrapper。
            //
            // 背景：`axeron-dpm` 由 shell(uid=2000) 通过 content call 触发，
            // 在 binder 线程里 Binder.getCallingUid()==2000，导致后续
            // Dhizuku.isPermissionGranted() -> checkCallingPermission(2000) 因
            // 授权记录不存在而失败（shell 从未被授权），返回 "Device Owner not active"。
            // 而「授权方式」是第三方 app 用自己的 uid 主动授权，故能通过——两者原理不同。
            val sysDpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (sysDpm != null) {
                val adminPkg = DeviceOwnerState.admin.packageName
                val isSelfOwner = sysDpm.isDeviceOwnerApp(adminPkg) || sysDpm.isProfileOwnerApp(adminPkg)
                if (isSelfOwner) {
                    LOGGER.i("getDeviceOwnerDpm: self is Device Owner, use native DPM directly")
                    return sysDpm
                }
            }

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
            return 1 to "Usage: axeron-dpm <hide|unhide|suspend|unsuspend|set-anim|set-global|set-secure|cleardata|grant|deny|block-uninstall|unblock-uninstall|reboot|locknow|org-name|lock-task|camera|keyguard|statusbar|user-restrict|clear-user-restrict|install-apps|wipe|force-stop|uninstall> ..."
        }
        val dpm = getDeviceOwnerDpm(context) ?: return 1 to "Error: Device Owner not active"
        // admin 选择：自我 DO 场景下（本进程就是 Owner）直接用 DeviceOwnerState.admin；
        // 否则（第三方经 Dhizuku 授权）用 Dhizuku 的 ownerComponent。
        val admin = ownerComponent() ?: DeviceOwnerState.admin
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
                "org-name" -> level1SetOrganizationName(dpm, admin, args)
                "lock-task" -> level1SetLockTaskPackages(dpm, admin, args)
                "camera" -> level1SetCameraDisabled(dpm, admin, args)
                "keyguard" -> level1SetKeyguardDisabled(dpm, admin, args)
                "statusbar" -> level1SetStatusBarDisabled(dpm, admin, args)
                "user-restrict" -> level1UserRestriction(dpm, admin, args, true)
                "clear-user-restrict" -> level1UserRestriction(dpm, admin, args, false)
                "install-apps" -> level1SetInstallApps(dpm, admin, args)
                "wipe" -> level1WipeData(dpm, admin, args)
                "force-stop" -> level2ForceStop(context, args)
                "uninstall" -> level2Uninstall(context, args)
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

    /**
     * org-name [name]：设置「组织名称」（锁屏 "This device belongs to <name>" 中的 <name>）。
     *
     * 这是**唯一**能自定义锁屏托管提示里组织名的公开 API（API 24/26+）。
     * 不传参数或传空串则清除（锁屏回退为系统默认文案）。
     */
    private fun level1SetOrganizationName(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        val name = if (args.size >= 2) args[1] else ""
        return try {
            dpm.setOrganizationName(admin, name)
            if (name.isEmpty()) {
                0 to "Success: organization name cleared"
            } else {
                0 to "Success: organization name set to '$name'"
            }
        } catch (e: Exception) {
            LOGGER.w("level1SetOrganizationName failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** lock-task <package|clear>：设置 LockTask（Kiosk 固定任务）包名单。 */
    private fun level1SetLockTaskPackages(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm lock-task <package|clear> [package2 ...]"
        val packages = if (args[1] == "clear") {
            emptyArray()
        } else {
            args.subList(1, args.size).toTypedArray()
        }
        return try {
            dpm.setLockTaskPackages(admin, packages)
            if (packages.isEmpty()) 0 to "Success: lock task packages cleared"
            else 0 to "Success: lock task packages = ${packages.joinToString(" ")}"
        } catch (e: Exception) {
            LOGGER.w("level1SetLockTaskPackages failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** camera on|off：启用/禁用摄像头。 */
    private fun level1SetCameraDisabled(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm camera <on|off>"
        val disable = args[1].equals("off", true) || args[1] == "0" || args[1] == "false"
        return try {
            dpm.setCameraDisabled(admin, disable)
            0 to "Success: camera ${if (disable) "disabled" else "enabled"}"
        } catch (e: Exception) {
            LOGGER.w("level1SetCameraDisabled failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** keyguard on|off：启用/禁用锁屏（关闭锁屏时界面更接近 Kiosk）。 */
    private fun level1SetKeyguardDisabled(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm keyguard <on|off>"
        val disable = args[1].equals("off", true) || args[1] == "0" || args[1] == "false"
        return try {
            dpm.setKeyguardDisabled(admin, disable)
            0 to "Success: keyguard ${if (disable) "disabled" else "enabled"}"
        } catch (e: Exception) {
            LOGGER.w("level1SetKeyguardDisabled failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** statusbar on|off：启用/禁用状态栏。 */
    private fun level1SetStatusBarDisabled(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm statusbar <on|off>"
        val disable = args[1].equals("off", true) || args[1] == "0" || args[1] == "false"
        return try {
            dpm.setStatusBarDisabled(admin, disable)
            0 to "Success: status bar ${if (disable) "disabled" else "enabled"}"
        } catch (e: Exception) {
            LOGGER.w("level1SetStatusBarDisabled failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** user-restrict/clear-user-restrict <restriction>：添加/清除用户限制（DISALLOW_*）。 */
    private fun level1UserRestriction(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>, add: Boolean): Pair<Int, String> {
        val cmd = if (add) "user-restrict" else "clear-user-restrict"
        if (args.size < 2) return 1 to "Usage: axeron-dpm $cmd <restriction>"
        val restriction = args[1]
        return try {
            if (add) dpm.addUserRestriction(admin, restriction)
            else dpm.clearUserRestriction(admin, restriction)
            0 to "Success: ${if (add) "added" else "cleared"} restriction $restriction"
        } catch (e: Exception) {
            LOGGER.w("level1UserRestriction failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** install-apps on|off：允许/禁止安装应用（DISALLOW_INSTALL_APPS 用户限制）。 */
    private fun level1SetInstallApps(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm install-apps <on|off>"
        val allow = args[1].equals("on", true) || args[1] == "1" || args[1] == "true"
        return try {
            if (allow) {
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
            } else {
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
            }
            0 to "Success: installing apps ${if (allow) "allowed" else "disallowed"}"
        } catch (e: Exception) {
            LOGGER.w("level1SetInstallApps failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** wipe [flags]：恢复出厂设置（危险）。flags 默认 0。 */
    private fun level1WipeData(dpm: DevicePolicyManager, admin: ComponentName, args: List<String>): Pair<Int, String> {
        val flags = args.getOrNull(1)?.toIntOrNull() ?: 0
        return try {
            dpm.wipeData(flags)
            0 to "Success: wipe requested (flags=$flags)"
        } catch (e: Exception) {
            LOGGER.w("level1WipeData failed", e)
            4 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------- 第二级：隐藏 API（经 Dhizuku 提升的 system_server binder 反射调用） ----------
    private fun level2ForceStop(context: Context, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm force-stop <package>"
        val pkg = args[1]
        return try {
            // 1. 拿 "activity" 服务原始 binder，经 Dhizuku 提升为 Device Owner 身份
            val amBinder = rikka.shizuku.SystemServiceHelper.getSystemService("activity")
                ?: return 5 to "Error: activity service unavailable"
            val wrapped = Dhizuku.binderWrapper(amBinder)
            val am = Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, wrapped) ?: return 5 to "Error: IActivityManager asInterface failed"
            // 2. 反射调用 forceStopPackage(pkg, userId)
            val userId = android.os.Process.myUid() / 100000
            val m = am.javaClass.methods.firstOrNull { it.name == "forceStopPackage" }
                ?: return 5 to "Error: forceStopPackage not found"
            m.isAccessible = true
            when {
                m.parameterTypes.size >= 3 -> m.invoke(am, pkg, userId, false)
                m.parameterTypes.size == 2 -> m.invoke(am, pkg, userId)
                else -> return 5 to "Error: unsupported forceStopPackage signature"
            }
            0 to "Success: force-stopped $pkg"
        } catch (e: Exception) {
            LOGGER.w("level2ForceStop failed", e)
            5 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    private fun level2Uninstall(context: Context, args: List<String>): Pair<Int, String> {
        if (args.size < 2) return 1 to "Usage: axeron-dpm uninstall <package>"
        val pkg = args[1]
        return try {
            // 1. 拿 "package" 服务原始 binder，经 Dhizuku 提升为 Device Owner 身份
            val pmBinder = rikka.shizuku.SystemServiceHelper.getSystemService("package")
                ?: return 5 to "Error: package service unavailable"
            val wrapped = Dhizuku.binderWrapper(pmBinder)
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, wrapped) ?: return 5 to "Error: IPackageManager asInterface failed"
            // 2. 反射调用 deletePackageAsUser(pkg, versionCode, userId)
            val userId = android.os.Process.myUid() / 100000
            val m = pm.javaClass.methods.firstOrNull { it.name == "deletePackageAsUser" }
                ?: return 5 to "Error: deletePackageAsUser not found"
            m.isAccessible = true
            when {
                m.parameterTypes.size >= 4 ->
                    m.invoke(pm, pkg, -1, userId, 0)
                m.parameterTypes.size == 3 ->
                    m.invoke(pm, pkg, -1, userId)
                else -> return 5 to "Error: unsupported deletePackageAsUser signature"
            }
            0 to "Success: uninstalled $pkg"
        } catch (e: Exception) {
            LOGGER.w("level2Uninstall failed", e)
            5 to "Error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    // ---------- 第三级：拒绝名单 ----------
private fun level3Reject(cmd: String): Pair<Int, String> =
        6 to "Error: '$cmd' needs Root permission"
}