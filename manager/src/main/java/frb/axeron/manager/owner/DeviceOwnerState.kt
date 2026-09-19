package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import frb.axeron.server.ServerConstants
import frb.axeron.server.util.Logger

/**
 * Device Owner / Profile Owner 状态判断工具类。
 *
 * 这是 AxManager 引入 Dhizuku 授权机制的核心状态模型：
 * - [isDeviceOwner]：本应用是否为全设备所有者（Device Owner）
 * - [isProfileOwner]：本应用是否为工作资料（Work Profile）所有者
 * - [isOwner]：是否为两者之一（即具备设备管理高级权限）
 *
 * 与 [DeviceOwnerReceiver] 配合使用：receiver 收到激活/取消广播后调用 [sync]，
 * 本类负责检测身份，并在激活成功后自动授予本应用申请的所有 dangerous 权限
 * （这是 Device Owner 非 root 场景下获取高级权限的关键手段）。
 */
object DeviceOwnerState {

    private val LOGGER = Logger("DeviceOwnerState")

    /** 设备管理组件名，与 manifest 中注册的 receiver 对应。 */
    val admin: ComponentName =
        ComponentName(ServerConstants.MANAGER_APPLICATION_ID, DeviceOwnerReceiver::class.java.name)

    /**
     * 当前状态快照，供 server 侧判断运行身份使用。
     *
     * 注意：该字段仅为「最近一次 [sync] 的结果缓存」，**不可作为 UI 显示依据**——
     * 若 App 进程在 `dpm set-device-owner` 时未存活（或激活后未重启 App），
     * receiver 的 onEnabled 广播收不到，该字段会长期停留在 false，
     * 从而出现「设备早已是 Device Owner，界面却仍显示未激活/仅 Dhizuku 授权」的
     * 假阴性 bug。界面判断请改用 [queryIsDeviceOwner] / [queryIsProfileOwner]
     * （它们直接询问系统 DevicePolicyManager，永远实时准确）。
     */
    @Volatile
    var isDeviceOwner: Boolean = false
        private set

    @Volatile
    var isProfileOwner: Boolean = false
        private set

    val isOwner: Boolean
        get() = isDeviceOwner || isProfileOwner

    /**
     * 实时查询：本应用是否为全设备所有者（Device Owner）。
     *
     * 直接询问系统 `DevicePolicyManager.isDeviceOwnerApp()`，不依赖任何缓存，
     * 因此无论 receiver 是否收到过广播、App 是否重启过，结果都准确。
     * 修复「已激活设备所有者，界面仍显示未激活 / 仍显示 Dhizuku 授权」的问题。
     */
    fun queryIsDeviceOwner(context: Context): Boolean = queryOwnerFlags(context).first

    /** 实时查询：本应用是否为工作资料所有者（Profile Owner）。 */
    fun queryIsProfileOwner(context: Context): Boolean = queryOwnerFlags(context).second

    /** 实时查询：(isDeviceOwner, isProfileOwner)，一次拿到两个结果并顺手刷新缓存。 */
    fun queryOwnerFlags(context: Context): Pair<Boolean, Boolean> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false to false
        val pkg = context.packageName
        return try {
            val doFlag = dpm.isDeviceOwnerApp(pkg)
            val poFlag = dpm.isProfileOwnerApp(pkg)
            // 顺手同步静态缓存，让 server 侧读到最新值
            isDeviceOwner = doFlag
            isProfileOwner = poFlag
            LOGGER.i("queryOwnerFlags: isDeviceOwner=$doFlag, isProfileOwner=$poFlag")
            doFlag to poFlag
        } catch (e: Exception) {
            LOGGER.w("queryOwnerFlags failed", e)
            false to false
        }
    }

    /**
     * 实时查询：本应用当前是否「已激活设备管理机构」（Device Owner 或 Profile Owner）。
     */
    fun queryIsOwner(context: Context): Boolean {
        val (d, p) = queryOwnerFlags(context)
        return d || p
    }

    /**
     * 生成「移除设备所有者」所需的解除指令（作为应用内解除失败时的兜底方案）。
     *
     * 优先使用 [deactivateOwner] 在应用内直接解除；仅当系统拒绝时才需要此 adb/root 指令。
     */
    fun buildRemoveOwnerCommand(): String =
        "adb shell dpm remove-active-admin ${admin.packageName}/${admin.className}"

    /**
     * 应用内主动解除设备所有者 / 工作资料所有者身份。
     *
     * 参考 Dhizuku 官方做法（`HomePage.DeactivateWidget`）：
     * ```
     * manager.clearProfileOwner(admin)              // 清 Profile Owner
     * manager.clearDeviceOwnerApp(context.packageName)  // 清 Device Owner
     * ```
     * 注意：
     *  - `clearDeviceOwnerApp` 虽标注 @Deprecated，但 Device Owner 自己调用它是**合法且生效**的
     *    （这与 `dpm remove-active-admin` 需要 adb/root 的情形不同）。
     *  - 若设备同时是 Profile Owner（如工作资料），需要先清 Profile Owner 再清 Device Owner。
     *  - 两个 API 均以 try/catch 包裹并分别尝试：即使其中一个抛异常，另一个仍会执行，
     *    以最大化解除成功率（与 Dhizuku 的实现保持一致）。
     *
     * @return 解除结果。[Result.success] 表示解除调用已成功发出且复查确认不再是 Owner。
     */
    fun deactivateOwner(context: Context): DeactivateResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return DeactivateResult(false, "DevicePolicyManager unavailable")
        val pkg = context.packageName
        val errors = mutableListOf<String>()

        // 1) 先尝试清除 Profile Owner（@Deprecated API，但仍在 Device Owner 场景下有效）
        try {
            @Suppress("DEPRECATION")
            dpm.clearProfileOwner(admin)
            LOGGER.i("clearProfileOwner OK")
        } catch (t: Throwable) {
            LOGGER.w("clearProfileOwner failed: ${t.message}")
            errors += "clearProfileOwner: ${t.message}"
        }

        // 2) 再清除 Device Owner（关键步骤）。Dhizuku 同样使用该 Deprecated API。
        try {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(pkg)
            LOGGER.i("clearDeviceOwnerApp OK")
        } catch (t: Throwable) {
            LOGGER.w("clearDeviceOwnerApp failed: ${t.message}")
            errors += "clearDeviceOwnerApp: ${t.message}"
        }

        // 3) 复查实际状态：系统解除可能是异步生效，这里立即查询一次
        val (stillDo, stillPo) = queryOwnerFlags(context)
        val ok = !stillDo && !stillPo
        LOGGER.i("deactivateOwner result: ok=$ok, stillDeviceOwner=$stillDo, stillProfileOwner=$stillPo")

        return if (ok) {
            onDisabled(context, dpm)
            DeactivateResult(true, null)
        } else {
            DeactivateResult(false, errors.joinToString("; ").ifBlank { "system rejected" })
        }
    }

    /** 解除结果。 */
    data class DeactivateResult(
        val success: Boolean,
        val error: String?
    )

    /**
     * 重新检测并同步 Device Owner / Profile Owner 状态。
     * 检测成功后，若为 Owner，则自动授予 dangerous 权限并回调 [onEnabled]。
     */
    fun sync(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: run {
                LOGGER.w("DevicePolicyManager unavailable")
                return
            }
        isDeviceOwner = dpm.isDeviceOwnerApp(admin.packageName)
        isProfileOwner = dpm.isProfileOwnerApp(admin.packageName)
        LOGGER.i("sync: isDeviceOwner=$isDeviceOwner, isProfileOwner=$isProfileOwner")

        if (isOwner) {
            onEnabled(context, dpm)
        } else {
            onDisabled(context, dpm)
        }
    }

    private fun onEnabled(context: Context, dpm: DevicePolicyManager) {
        grantPermissions(context, dpm)
    }

    private fun onDisabled(context: Context, dpm: DevicePolicyManager) {
        // 失去 Owner 身份时，无额外清理动作（dangerous 权限状态由系统回收粒度决定）。
        LOGGER.i("device owner disabled")
    }

    /**
     * 遍历本应用在 manifest 中申请的所有 dangerous 权限，并以 Device Owner 身份
     * 将其授权状态置为 GRANTED。这是非 root 情况下获取高级（dangerous）权限的核心能力。
     *
     * 注意：`DevicePolicyManager.setPermissionGrantState` 为隐藏 API（@UnsupportedAppUsage），
     * 在 Rikka hidden stub 中的暴露形式（实例方法 vs `DevicePolicyManagerHidden` 静态包装）
     * 未确认，故采用反射调用以 100% 规避编译期不确定性；运行时依赖 AxeronApplication 中
     * 已启动的 `HiddenApiBypass.setHiddenApiExemptions("")` 豁免灰名单拦截。
     * 该方法必须在 Device Owner / Profile Owner 身份下调用才生效。
     */
    private fun grantPermissions(context: Context, dpm: DevicePolicyManager) {
        val permissions = getAllRequestedPermissions(context).filterNotNull().filter { permission ->
            val info = getPermissionInfo(context, permission)
            info?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    it.protectionFlags and PermissionInfo.PROTECTION_DANGEROUS != 0
                } else {
                    @Suppress("DEPRECATION")
                    (it.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                        PermissionInfo.PROTECTION_DANGEROUS
                }
            } ?: false
        }
        if (permissions.isEmpty()) {
            LOGGER.i("no dangerous permissions to grant")
            return
        }
        permissions.forEach { permission ->
            val granted = setPermissionGrantState(dpm, permission)
            LOGGER.i("grant dangerous permission '$permission': $granted")
        }
    }

    /**
     * 反射调用 DevicePolicyManager.setPermissionGrantState(ComponentName, String, String, int)。
     * 返回是否调用成功（未抛异常即视为成功）。
     */
    private fun setPermissionGrantState(dpm: DevicePolicyManager, permission: String): Boolean {
        return try {
            val method = DevicePolicyManager::class.java.getMethod(
                "setPermissionGrantState",
                ComponentName::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE
            )
            method.invoke(
                dpm,
                admin,
                admin.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            true
        } catch (e: Exception) {
            LOGGER.w("setPermissionGrantState failed for $permission", e)
            false
        }
    }

    private fun getAllRequestedPermissions(context: Context): Array<String?> {
        return try {
            val packageInfo: PackageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        admin.packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        admin.packageName,
                        PackageManager.GET_PERMISSIONS
                    )
                }
            packageInfo.requestedPermissions ?: emptyArray()
        } catch (e: Exception) {
            LOGGER.w("getAllRequestedPermissions failed", e)
            emptyArray()
        }
    }

    private fun getPermissionInfo(context: Context, permission: String): PermissionInfo? {
        return try {
            context.packageManager.getPermissionInfo(permission, 0)
        } catch (e: Exception) {
            null
        }
    }
}
