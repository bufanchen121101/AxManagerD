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

    /** 当前状态快照，供 server 侧判断运行身份使用。 */
    @Volatile
    var isDeviceOwner: Boolean = false
        private set

    @Volatile
    var isProfileOwner: Boolean = false
        private set

    val isOwner: Boolean
        get() = isDeviceOwner || isProfileOwner

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
