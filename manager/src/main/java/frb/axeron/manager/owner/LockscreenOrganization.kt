package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import frb.axeron.api.core.AxeronSettings

/**
 * 锁屏「此设备归 XX 所有」的**组织名称**设置（方案 A：纯 API，不 root）。
 *
 * 背景（AOSP 结论）：
 * - SystemUI 锁屏托管提示来自资源字符串：
 *     do_disclosure_with_name = "This device belongs to %s"   (%s = 组织名)
 *     do_disclosure_generic   = "This device belongs to your organization"（无组织名时的兜底）
 * - 「This device belongs to your organization」整句是 SystemUI 硬编码，只要
 *   isDeviceManaged() 为真就**无条件显示**，纯 API 无法删除整句。
 * - 唯一能改这句里「XX」的 API 是 [DevicePolicyManager.setOrganizationName]（API 24/26+）。
 *   因此本功能的语义是「自定义组织名」，而非「隐藏提示」。
 *
 * 设计原则：本文件自包含，读写走 [AxeronSettings.getPreferences]，
 * 不修改 [AxeronSettings] / 其他公共类，避免污染公共路径。
 */
object LockscreenOrganization {

    private const val KEY_ENABLED = "lockscreen_org_enabled"
    private const val KEY_NAME = "lockscreen_org_name"

    /** 是否启用自定义组织名。 */
    fun isEnabled(): Boolean =
        AxeronSettings.getPreferences().getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        AxeronSettings.getPreferences().edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 用户自定义的组织名（未设置时返回空串）。 */
    fun getName(): String =
        AxeronSettings.getPreferences().getString(KEY_NAME, "") ?: ""

    fun setName(name: String) {
        AxeronSettings.getPreferences().edit().putString(KEY_NAME, name).apply()
    }

    /**
     * 把当前配置写入系统：调用 [DevicePolicyManager.setOrganizationName]。
     *
     * @return Pair(是否成功, 说明文案)
     */
    fun apply(context: Context, name: String = getName()): Pair<Boolean, String> {
        val dpm = resolveDpm(context)
            ?: return false to "未检测到设备所有者（需先激活 Device Owner）"
        val admin = resolveAdmin(context)
        return try {
            dpm.setOrganizationName(admin, name)
            if (name.isBlank()) {
                true to "已清除组织名（锁屏将回退为系统默认文案）"
            } else {
                true to "组织名已设为「$name」"
            }
        } catch (e: Exception) {
            false to "设置失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 优先使用本进程内真实 DPM（自身即 Owner），否则回落 Dhizuku 转发。 */
    private fun resolveDpm(context: Context): DevicePolicyManager? {
        val sysDpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (sysDpm != null) {
            val pkg = DeviceOwnerState.admin.packageName
            val selfIsOwner =
                runCatching { sysDpm.isDeviceOwnerApp(pkg) }.getOrDefault(false) ||
                        runCatching { sysDpm.isProfileOwnerApp(pkg) }.getOrDefault(false)
            if (selfIsOwner) return sysDpm
        }
        return DeviceOwnerPrivilege.getDeviceOwnerDpm(context)
    }

    private fun resolveAdmin(context: Context): ComponentName =
        DeviceOwnerPrivilege.ownerComponent() ?: DeviceOwnerState.admin
}
