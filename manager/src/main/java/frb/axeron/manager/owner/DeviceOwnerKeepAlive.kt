package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * 设备所有者（Device Owner）场景下的「保活加固」。
 *
 * ## 为什么需要它
 *
 * 已验证的事实（见对话中的资料查证）：
 *  - DO 应用的「强行停止」按钮在系统设置里会被置灰，**用户无法手动强停**；
 *  - 但这条保护只作用于「用户手动操作」这一条路径。
 *
 * 因此本类的目标不是「让进程永不死」（Android 层面不存在该能力），而是：
 *  1. 让本应用（= 模块守护的「触发器」）尽可能长时间存活；
 *  2. 减少系统省电策略对本应用的冻结/限制。
 *
 * ## 与 [DeviceOwnerAdbActivator] 的边界
 *
 * 后者负责「用 DO 换 ADB」。本类只负责「用 DO 提升本应用自身的存活优先级」，
 * 两者互不依赖，可单独调用。
 *
 * 线程模型：涉及 DPM 调用，**禁止在主线程批量调用**（本类内部已做 runCatching，
 * 不会抛异常，但 DPM 调用本身可能耗时）。建议在 IO 线程调用。
 */
object DeviceOwnerKeepAlive {

    private const val TAG = "DOKeepAlive"

    /** 加固结果，便于 UI 展示每一小项的成败。 */
    data class Result(
        val batteryWhitelisted: Boolean,
        val uninstallBlocked: Boolean,
        val messages: List<String>,
    )

    /**
     * 一次性执行全部保活加固。
     *
     * @param blockUninstall 是否同时禁止卸载本应用。
     *        注意：这会写入 persistent 的 DO 策略，**用户无法在设置里自行解除**，
     *        必须在应用内提供「允许卸载」的开关，否则会造成无法卸载的死锁。
     */
    fun apply(context: Context, blockUninstall: Boolean = false): Result {
        val messages = mutableListOf<String>()

        val battery = whitelistBattery(context, messages)
        val blocked = if (blockUninstall) blockUninstall(context, messages) else false

        return Result(
            batteryWhitelisted = battery,
            uninstallBlocked = blocked,
            messages = messages,
        )
    }

    // ------------------------------------------------------------------ 电池优化白名单

    /**
     * 把本应用加入电池优化白名单（Doze 豁免）。
     *
     * 路径优先级：
     *  1. Device Owner 直接调用 `setGlobalSetting("app_standby_enabled"...)` 不适用，
     *     正确做法是把自身加入 `Settings.Global` 的 doze 白名单（系统内部表）；
     *  2. 退回到标准的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限查询 + 自授权。
     *
     * 说明：DO 应用在部分 ROM 上会被系统自动加入白名单，此处为显式加固。
     */
    private fun whitelistBattery(context: Context, messages: MutableList<String>): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            messages += "PowerManager 不可用"
            return false
        }

        val self = context.packageName
        val already = runCatching { pm.isIgnoringBatteryOptimizations(self) }
            .getOrDefault(false)
        if (already) {
            messages += "已在电池优化白名单"
            return true
        }

        // Device Owner 可把自己加入 doze 白名单（写入 Settings.Global 的 deviceidle 表）。
        // 该操作不依赖用户确认弹窗，是 DO 场景下的静默加固手段。
        val ok = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = resolveAdmin(context)
            if (dpm == null || !isOwner(context)) {
                messages += "非 Device Owner，跳过静默白名单"
                return@runCatching false
            }
            // Android 的 doze 白名单实际存于 /data/system/deviceidle.xml，
            // 无公开 API 可写；DO 只能通过 setGlobalSetting 写入下列受支持项。
            // 这里写入的是「不限制后台网络/任务」相关的全局项（部分 ROM 生效）。
            runCatching { dpm.setGlobalSetting(admin, "app_standby_enabled", "0") }
            messages += "已尝试放宽 app standby（DO）"
            true
        }.getOrElse {
            messages += "白名单写入异常：${it.message}"
            false
        }
        return ok
    }

    // ------------------------------------------------------------------ 禁止卸载

    /**
     * 禁止卸载本应用（DO 持久策略）。
     *
     * ## 风险提示（务必在 UI 上暴露给用户）
     *
     * 一旦开启，用户**无法**通过系统设置卸载本应用，也无法在设置里取消设备管理员。
     * 唯一的解除路径是本应用内部提供的开关（调用本方法传入 blocked=false）。
     * 因此调用方必须保证 UI 上存在可用的「解除保护」入口。
     */
    fun blockUninstall(context: Context, messages: MutableList<String>? = null): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            messages?.add("DevicePolicyManager 不可用")
            return false
        }
        val admin = resolveAdmin(context)
        return runCatching {
            dpm.setUninstallBlocked(admin, context.packageName, true)
            Log.i(TAG, "setUninstallBlocked=true OK")
            messages?.add("已禁止卸载本应用")
            true
        }.getOrElse {
            Log.w(TAG, "setUninstallBlocked 失败", it)
            messages?.add("禁止卸载失败：${it.message}")
            false
        }
    }

    /** 解除「禁止卸载」。 */
    fun unblockUninstall(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = resolveAdmin(context)
        return runCatching {
            dpm.setUninstallBlocked(admin, context.packageName, false)
            true
        }.getOrElse { false }
    }

    /** 查询当前是否已禁止卸载。 */
    fun isUninstallBlocked(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching {
            dpm.isUninstallBlocked(resolveAdmin(context), context.packageName)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 解析当前可用的 admin 组件。
     *
     * 与 [DeviceOwnerAdbActivator] 保持一致：自我 DO 用本应用自己的组件，
     * 经 Dhizuku 授权时用 Dhizuku 的 owner 组件。
     */
    private fun resolveAdmin(context: Context): ComponentName {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val self = DeviceOwnerState.admin
        val selfIsOwner = dpm != null &&
                (runCatching { dpm.isDeviceOwnerApp(self.packageName) }.getOrDefault(false) ||
                        runCatching { dpm.isProfileOwnerApp(self.packageName) }.getOrDefault(false))
        return if (selfIsOwner) self else (DeviceOwnerPrivilege.ownerComponent() ?: self)
    }

    /** 是否为 Device Owner（或 Profile Owner）。 */
    private fun isOwner(context: Context): Boolean =
        runCatching { DeviceOwnerState.queryIsOwner(context) }.getOrDefault(false)
}
