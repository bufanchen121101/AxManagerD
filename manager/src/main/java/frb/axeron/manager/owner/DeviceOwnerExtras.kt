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
        val receiver: ComponentName,
        /**
         * 目标是否在自己的 `device_admin.xml` 里声明了 `<support-transfer-ownership />`。
         *
         * AOSP `DevicePolicyManagerService#transferOwnership` 对此有硬断言，
         * 未声明者必定抛 IllegalArgumentException。这里只做「标注 + 排序」，
         * 不再直接过滤 —— 过滤会让列表变空，用户以为「转移功能坏了」。
         */
        val supportsTransfer: Boolean = true
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
                // 【v1.4.1 修复 · 转移列表为空/总失败】
                // 旧版在这里把「未声明 support-transfer-ownership」的目标直接过滤掉，
                // 结果常见机型上一个候选都不剩，卡片永远显示「没有可转移的应用」。
                // 现在改为只打标记（supportsTransfer），由 UI 置灰并给出原因；
                // 是否真的能收，交给系统 API 判定。
                val supported = supportsTransferOwnership(adminInfo)
                try {
                    TransferTarget(
                        label = appInfo.loadLabel(pm).toString(),
                        packageName = pkg,
                        receiver = adminInfo.component,
                        supportsTransfer = supported
                    )
                } catch (t: Throwable) {
                    null
                }
            }
            .distinctBy { it.receiver }
            // 支持转移的排前面，其余按名称排（UI 不再过滤，保证列表可见）
            .sortedWith(compareByDescending<TransferTarget> { it.supportsTransfer }.thenBy { it.label })
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
        val sysDpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return TransferResult(false, "DevicePolicyManager 不可用")
        val selfPkg = context.packageName
        val selfIsOwner = runCatching {
            sysDpm.isDeviceOwnerApp(selfPkg) || sysDpm.isProfileOwnerApp(selfPkg)
        }.getOrDefault(false)

        // 【v1.4.1 修复 · 双通道】转移必须由「当前的 Owner 身份」发起，
        // 所以这里先判定本应用实际是哪一种身份，再决定用哪个 DPM / 哪个 admin：
        //  ① 自己就是 DO / PO（直接激活 / ADB 激活）→ 系统真实 DPM + 自己的 admin 组件；
        //  ② 自己不是 Owner，但 Dhizuku 已授权（Dhizuku 激活）→ Dhizuku 提升后的 DPM，
        //     admin 换成 Dhizuku 的 owner 组件（AOSP 要求 admin 必须是当前 Owner）。
        // 旧版只走 ①，Dhizuku 用户永远看到「当前应用不是设备所有者，无法转移」。
        val dpm: DevicePolicyManager
        val admin: ComponentName
        if (selfIsOwner) {
            dpm = sysDpm
            admin = DeviceOwnerState.admin
        } else {
            val elevated = DeviceOwnerPrivilege.getDeviceOwnerDpm(context)
                ?: return TransferResult(
                    false,
                    "当前应用不是设备所有者，也没有可用的 Device Owner 通道。" +
                            "请先在本页激活设备所有者（或授权 Dhizuku）后再转移。"
                )
            val owner = DeviceOwnerPrivilege.ownerComponent()
                ?: return TransferResult(false, "无法获取当前 Device Owner 组件（Dhizuku 未就绪）")
            dpm = elevated
            admin = owner
        }

        return try {
            @Suppress("NewApi")
            // 【v1.4.1 修复 · 总失败】这里不再要求目标「已被激活为设备管理员」。
            // AOSP transferOwnership 内部会用 findAdmin(target) 解析接收器，
            // 再由 setDeviceOwner 把目标激活；旧版的 isAdminActive 前置校验
            // 会把所有未激活的候选全部挡掉，表现就是「点哪个都失败」。
            dpm.transferOwnership(admin, target, null)
            LOGGER.i("transferOwnership OK -> $target (admin=$admin)")
            // 复查：目标是否已成为新的 Owner
            val ok = runCatching { dpm.isDeviceOwnerApp(target.packageName) }.getOrDefault(false)
            if (!ok) {
                TransferResult(false, "系统未执行转移（目标可能不支持接收所有者身份）")
            } else {
                TransferResult(true, null)
            }
        } catch (t: Throwable) {
            LOGGER.w("transferOwnership failed: ${t.message}")
            TransferResult(false, friendlyTransferError(t))
        }
    }

    /**
     * 把 `transferOwnership` 抛出的英文异常翻译成可操作的中文原因。
     *
     * 常见真实报错（AOSP `DevicePolicyManagerService`）：
     *  - `java.lang.IllegalArgumentException: Provided target does not support ownership transfer.`
     *  - `java.lang.IllegalArgumentException: Provided target is not a device admin active on the calling user.`
     *  - `java.lang.SecurityException: Only the shell can transfer ownership`（部分 ROM 定制）
     */
    private fun friendlyTransferError(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        val low = raw.lowercase()
        return when {
            low.contains("support ownership transfer") || low.contains("does not support") ->
                "目标应用未声明「支持接收设备所有者身份」（device_admin.xml 缺少 " +
                        "<support-transfer-ownership/>），系统拒绝转移。请换一个目标应用。"

            low.contains("not a device admin") || low.contains("no active admin") ->
                "目标不是有效的设备管理组件，系统无法把所有者身份交给它。"

            low.contains("only the shell") ->
                "该系统 ROM 限定只能由 shell 转移所有者。请改用电脑执行 adb shell dpm 相关命令。"

            low.contains("permission") || low.contains("security") ->
                "系统拒绝了这次转移（$raw）。请确认本应用当前仍是设备所有者。"

            else -> raw
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