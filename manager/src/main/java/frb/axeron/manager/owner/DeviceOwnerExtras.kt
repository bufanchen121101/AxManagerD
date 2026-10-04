package frb.axeron.manager.owner

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import frb.axeron.server.util.Logger

/**
 * 设备所有者（Device Owner）扩展能力：一键激活 DO + 权限转移。
 *
 * 本文件为独立新增模块，不改动 [DeviceOwnerState] / [DeviceOwnerAdbActivator] 等既有类，
 * 避免污染公共路径。参考实现：
 *  - 一键激活：`dpm set-device-owner`（shell 身份可执行，需 账户=0 且 仅 User 0）
 *  - 权限转移：OwnDroid `TransferOwnershipViewModel`
 *    （`dpm.transferOwnership(admin, component, null)`，@RequiresApi(28)）
 *
 * ## 为什么不用 `cmd role add-role-holder`（重要）
 *
 * 早期实现曾用
 * `cmd role add-role-holder android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>`
 * 来给本应用授予「设备策略管理」角色。真机（vivo / Android 13）实测结论：
 *  - 该角色的合格持有者是 **`com.google.android.gms`**（GMS 独占的
 *    Qualification 角色），第三方应用没有资格持有；
 *  - 执行后必然抛 `RuntimeException: Failed`（Dhizuku 同样被拒）。
 *
 * 因此本模块改用 **`dpm set-device-owner`** —— 这是 Android 官方、通用、
 * 且实测可用的路径，授予的是**完整的 Device Owner 权限**。
 */
object DeviceOwnerExtras {

    private val LOGGER = Logger("DeviceOwnerExtras")

    /** 本应用 DO 管理组件（与 manifest 注册的 receiver 对应）。 */
    private fun selfComponent(context: Context): ComponentName =
        ComponentName(context.packageName, DeviceOwnerReceiver::class.java.name)

    /**
     * 生成「一键激活设备所有者」的 shell 指令。
     *
     * 该指令通过 Shizuku（shell 身份）或 ADB 执行均可，授予**完整 DO 权限**。
     * 前置条件（系统强制）：
     *  - 设备上账户数为 0（`dumpsys account` 里 Accounts: 0）；
     *  - 仅存在 User 0（`pm list users` 只有一项）；
     *  - 当前没有 Profile Owner（两者互斥）。
     * 不满足时 `dpm` 会返回明确错误，由调用方透传给用户。
     */
    fun buildTempDoCommand(context: Context): String {
        val comp = selfComponent(context)
        return "dpm set-device-owner --user 0 ${comp.flattenToShortString()}"
    }

    /**
     * 生成「一键激活资料所有者（Profile Owner）」的 shell 指令。
     *
     * 与设备所有者的区别：
     *  - Profile Owner 只作用于当前用户（等价于「工作资料」所有者），
     *    权限范围小于整机 DO，但同样可调用大部分 DevicePolicyManager 能力；
     *  - **两者互斥**：已成为 Device Owner 时无法再设 Profile Owner，反之亦然。
     *
     * 前置条件：仅存在 User 0，且当前没有 Device Owner。
     */
    fun buildTempProfileOwnerCommand(context: Context): String {
        val comp = selfComponent(context)
        return "dpm set-profile-owner --user 0 ${comp.flattenToShortString()}"
    }

    /**
     * 生成「移除设备所有者的设备管理」的 shell 指令。
     *
     * 注意：已成 DO 的组件需要先 `clearDeviceOwnerApp` 才能移除。
     */
    fun buildRemoveTempDoCommand(context: Context): String {
        val comp = selfComponent(context)
        return "dpm remove-active-admin --user 0 ${comp.flattenToShortString()}"
    }

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
     *  - 必须能被 `DeviceAdminInfo` 正确解析；
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
                val pkg = adminInfo.packageName ?: return@mapNotNull null
                // 排除自身
                if (pkg == selfPkg) return@mapNotNull null
                val ai = adminInfo.activityInfo ?: return@mapNotNull null
                val appInfo = ai.applicationInfo ?: return@mapNotNull null
                // 排除系统应用（与 OwnDroid 一致）
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                    return@mapNotNull null
                }
                // 【v1.9.0 修复 · 发送方向】只保留**声明支持接收 DO 转移**的目标。
                //
                // AOSP `DevicePolicyManagerService#transferOwnership`（A13 L16088）有一道硬断言：
                //   Preconditions.checkArgument(incomingDeviceInfo.supportsTransferOwnership(),
                //           "Provided target does not support ownership transfer.");
                // 目标未在自己的 device_admin.xml 声明 `<support-transfer-ownership />` 时，
                // 调用必然抛 IllegalArgumentException，用户却只能看到一个英文报错。
                // 因此在候选阶段就过滤掉这类目标 —— 宁可列表短，也不给必然失败的按钮。
                if (!supportsTransferOwnership(adminInfo)) {
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

        // 前置校验①：必须自己是 DO 才能转移
        val selfIsOwner = runCatching { dpm.isDeviceOwnerApp(DeviceOwnerState.admin.packageName) }
            .getOrDefault(false)
        if (!selfIsOwner) {
            return TransferResult(false, "当前应用不是设备所有者，无法转移")
        }

        // 【v1.9.0 修复 · 发送方向】前置校验②：目标必须**已被系统激活为设备管理员**。
        //
        // 依据 AOSP `DevicePolicyManagerService#transferOwnership`（A13 L16085-16087）：
        //   final DeviceAdminInfo incomingDeviceInfo = findAdmin(target, callingUserId, true);
        //   checkActiveAdminPrecondition(target, incomingDeviceInfo, policy);
        // 目标未激活时会直接抛出 "No active admin ..."，用户只看到一个英文异常。
        // 这里提前拦截，给出可执行的中文原因（含目标组件名，便于直接用命令激活）。
        val targetActive = runCatching { dpm.isAdminActive(target) }.getOrDefault(false)
        if (!targetActive) {
            return TransferResult(
                false,
                "目标应用尚未启用「设备管理员」，无法接收转移。请先在目标应用内启用设备管理员，" +
                        "或在电脑上执行：adb shell dpm set-active-admin " +
                        target.flattenToShortString() + " ，然后重试。"
            )
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
    // DO 状态查询 / 能力
    // ---------------------------------------------------------------------

    /**
     * 当前应用是否已是设备所有者（即「一键激活 DO」是否已生效）。
     *
     * 直接询问系统 DevicePolicyManager，实时准确，不受进程存活影响。
     */
    fun isTempDoActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    /**
     * 当前应用是否已是资料所有者（Profile Owner）。
     *
     * 注意：Profile Owner 与 Device Owner 互斥，但 DO 应用通常在自身用户上
     * 同时满足 `isProfileOwnerApp`，因此 UI 侧建议先判 DO 再判 PO。
     */
    fun isTempProfileOwnerActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isProfileOwnerApp(context.packageName) }.getOrDefault(false)
    }

    /**
     * 一键激活 DO 是否受当前系统版本支持。
     *
     * `dpm set-device-owner` 自 Android 5.0（API 21）起可用。
     */
    fun isTempDoSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /**
     * 【v1.9.0 新增】查询某个设备管理接收器是否声明了「支持接收所有者身份转移」。
     *
     * 对应 AOSP `DeviceAdminInfo#supportsTransferOwnership()`（API 28 起存在，
     * 解析自 device_admin.xml 的 `<support-transfer-ownership />` 空标签）。
     *
     * 这里用反射而非直接调用：该方法虽为 public，但在不同 SDK 的 `android.jar`
     * 中可见性并不一致，反射可 100% 规避编译期差异（本项目已全局豁免 hiddenapi）。
     * 任何异常一律按「不支持」处理 —— 宁可少列一个目标，也不给用户一个必然失败的按钮。
     */
    private fun supportsTransferOwnership(info: android.app.admin.DeviceAdminInfo): Boolean = try {
        android.app.admin.DeviceAdminInfo::class.java
            .getMethod("supportsTransferOwnership")
            .invoke(info) as? Boolean ?: false
    } catch (t: Throwable) {
        false
    }
}