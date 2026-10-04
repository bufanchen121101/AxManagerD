package frb.axeron.manager.appmgr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 管理动作的**生效校验**（v1.4.0 新增）。
 *
 * 用户要求「逐档尝试、**有效果就不要继续往下面试**」，前提是能判断
 * 「这一档到底有没有真的生效」。命令 exitCode=0 / DPM 返回成功**都不等于生效**
 * （典型：以为有 DO、其实没有 → 报 `Error: Device Owner not active`；
 *  或 shell 下 `pm hide` 因缺 MANAGE_USERS 而失败）。
 *
 * 校验一律用**本进程 PackageManager 的实时状态**读取，不依赖任何执行档位，
 * 因此对 DO / PO / DP / shell 四种执行方式都同样有效。
 *
 * 返回值语义（三态，很关键）：
 *  - `true` ：确认已生效 → 停止继续降档；
 *  - `false`：确认**未**生效 → 继续尝试下一档；
 *  - `null` ：该动作无法静态校验（如清除数据）→ 由调用方按命令结果判定。
 */
object AppManageVerifier {

    /**
     * 校验 [action] 对 [pkg] 是否已生效。
     */
    fun verify(context: Context, action: AppManageAction, pkg: String): Boolean? {
        val pm = context.packageManager
        return try {
            when (action) {
                AppManageAction.SUSPEND -> isSuspended(pm, pkg)
                AppManageAction.FREEZE -> !isEnabled(pm, pkg)
                // hide 后该包对普通调用方不可见（getPackageInfo 抛 NameNotFound）
                AppManageAction.HIDE -> !isVisible(pm, pkg)
                AppManageAction.FORCE_UNINSTALL -> !isVisible(pm, pkg)
                AppManageAction.RESTORE -> isSuspended(pm, pkg) != true && isEnabled(pm, pkg)
                // 清除数据 / 禁止联网没有稳定的进程内查询接口，交由命令结果判定
                AppManageAction.CLEAR_DATA,
                AppManageAction.BLOCK_NETWORK -> null
            }
        } catch (t: Throwable) {
            // 校验本身出错时不下结论（返回 null = 交给命令结果）
            null
        }
    }

    /** 包是否仍对当前调用方可见（已卸载 / 已隐藏时为 false）。 */
    private fun isVisible(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 包是否处于「挂起」状态（`ApplicationInfo.FLAG_SUSPENDED`）。 */
    private fun isSuspended(pm: PackageManager, pkg: String): Boolean? = try {
        val info = pm.getPackageInfo(pkg, 0)
        val ai = info.applicationInfo
        if (ai == null) null else (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 包是否处于「启用」状态（与列表页同一套判定，避免口径不一致）。 */
    private fun isEnabled(pm: PackageManager, pkg: String): Boolean = try {
        val state = pm.getApplicationEnabledSetting(pkg)
        state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    } catch (e: Throwable) {
        true
    }
}
