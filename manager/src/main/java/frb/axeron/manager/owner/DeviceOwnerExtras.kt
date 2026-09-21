package frb.axeron.manager.owner

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import frb.axeron.server.util.Logger

/**
 * 设备所有者（Device Owner）扩展能力：权限转移 + 临时 DO。
 *
 * 本文件为独立新增模块，不改动 [DeviceOwnerState] / [DeviceOwnerAdbActivator] 等既有类，
 * 避免污染公共路径。参考实现：
 *  - 权限转移：OwnDroid `TransferOwnershipViewModel`
 *    （`dpm.transferOwnership(dar, component, null)`，@RequiresApi(28)）
 *  - 临时 DO：Android 角色机制
 *    （`cmd role add-role-holder android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>`，重启失效）
 */
object DeviceOwnerExtras {

    private val LOGGER = Logger("DeviceOwnerExtras")

    /** 设备策略管理角色名。shell 侧授予使用的是该完整角色名。 */
    const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    /**
     * 生成「临时 DO」的 shell 指令。
     *
     * 该角色可通过 adb / shell 授予，但**重启后失效**，需要重新执行。
     * 由于是 role holder 而非真正的 Device Owner，能力受限（不具备全部 DO 权限）。
     */
    fun buildTempDoCommand(packageName: String): String =
        "cmd role add-role-holder $ROLE_DEVICE_POLICY_MANAGEMENT $packageName"

    /** 移除「临时 DO」角色持有者的指令。 */
    fun buildRemoveTempDoCommand(packageName: String): String =
        "cmd role remove-role-holder $ROLE_DEVICE_POLICY_MANAGEMENT $packageName"

    // ---------------------------------------------------------------------
    // 权限转移（参照 OwnDroid）
    // ---------------------------------------------------------------------

    /** 可接收 DO 身份转移的目标应用。 */
    data class TransferTarget(
        val label: String,
        val packageName: String,
        val receiver: ComponentName
    )

    /**
     * 枚举可作为 DO 转移目标的设备管理接收器。
     *
     * 参照 OwnDroid `TransferOwnershipModel` 的过滤逻辑：
     *  - 必须是「可见的」设备管理接收器（`isVisible`）；
     *  - 排除自身包名；
     *  - 排除系统应用（`FLAG_SYSTEM`）。
     */
    fun queryTransferTargets(context: Context): List<TransferTarget> {
        val pm = context.packageManager
        val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
        val flags = PackageManager.GET_META_DATA
        val receivers: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, flags)
            }
        } catch (t: Throwable) {
            LOGGER.w("queryTransferTargets failed: ${t.message}")
            return emptyList()
        }

        val selfPkg = context.packageName
        return receivers.asSequence()
            .mapNotNull { ri ->
                // 与 OwnDroid 对齐：先尝试用 DeviceAdminInfo 解析，
                // 只有真正合法的设备管理接收器才会被纳入候选。
                val adminInfo = try {
                    android.app.admin.DeviceAdminInfo(context, ri)
                } catch (t: Throwable) {
                    return@mapNotNull null
                }
                if (!adminInfo.isVisible) return@mapNotNull null
                val pkg = adminInfo.packageName ?: return@mapNotNull null
                // 排除自身
                if (pkg == selfPkg) return@mapNotNull null
                val ai = adminInfo.activityInfo ?: return@mapNotNull null
                val appInfo = ai.applicationInfo ?: return@mapNotNull null
                // 排除系统应用（与 OwnDroid 一致）
                if (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) {
                    return@mapNotNull null
                }
                try {
                    TransferTarget(
                        label = appInfo.loadLabel(pm).toString(),
                        packageName = pkg,
                        receiver = adminInfo.component
                    )
                } catch (t: Throwable) {
                    null
                }
            }
            .distinctBy { it.receiver }
            .sortedBy { it.label }
            .toList()
    }

    /** DO 身份转移结果。 */
    data class TransferResult(val success: Boolean, val error: String?)

    /**
     * 将本应用的 Device Owner 身份转移给指定目标组件。
     *
     * 参照 OwnDroid `TransferOwnershipViewModel.transferOwnership`：
     * `dpm.transferOwnership(admin, target, null)`
     *  - `@RequiresApi(28)`；低版本直接返回失败。
     *  - 转移成功后本应用不再是 DO，目标应用成为新的 DO。
     */
    fun transferOwnership(context: Context, target: ComponentName): TransferResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return TransferResult(false, "需要 Android 9 (API 28) 及以上")
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return TransferResult(false, "DevicePolicyManager 不可用")

        // 前置校验：必须自己是 DO 才能转移
        val selfIsOwner = runCatching { dpm.isDeviceOwnerApp(DeviceOwnerState.admin.packageName) }
            .getOrDefault(false)
        if (!selfIsOwner) {
            return TransferResult(false, "当前应用不是设备所有者，无法转移")
        }

        return try {
            @Suppress("NewApi")
            dpm.transferOwnership(DeviceOwnerState.admin, target, null)
            LOGGER.i("transferOwnership OK -> $target")
            // 复查：确认自己已不再是 DO
            val stillOwner = runCatching {
                dpm.isDeviceOwnerApp(DeviceOwnerState.admin.packageName)
            }.getOrDefault(false)
            if (stillOwner) {
                TransferResult(false, "系统未执行转移（可能目标不支持）")
            } else {
                TransferResult(true, null)
            }
        } catch (t: Throwable) {
            LOGGER.w("transferOwnership failed: ${t.message}")
            TransferResult(false, t.message ?: t.toString())
        }
    }

    // ---------------------------------------------------------------------
    // 临时 DO（role holder）
    // ---------------------------------------------------------------------

    /**
     * 当前应用是否持有「设备策略管理」角色（即临时 DO 是否生效）。
     */
    fun isTempDoActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val rm = context.getSystemService(android.app.role.RoleManager::class.java)
            rm?.isRoleHeld(ROLE_DEVICE_POLICY_MANAGEMENT) ?: false
        }.getOrDefault(false)
    }

    /** 临时 DO 是否受当前系统版本支持（Android 10+）。 */
    fun isTempDoSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
