package frb.axeron.manager.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 国产 ROM「自启动 / 后台运行」白名单引导工具。
 *
 * 背景：MIUI / HyperOS、EMUI / HarmonyOS、ColorOS、OriginOS、Flyme 等定制 ROM
 * 默认会拦截应用被系统广播拉起（含 `BOOT_COMPLETED`）。此时即便
 * `RECEIVE_BOOT_COMPLETED` 已声明、`directBootAware` 已开启，
 * `BootCompleteReceiver` 也不会在开机时收到广播。
 *
 * 唯一可行的办法是把本应用加入系统的「自启动白名单」——这属于用户授权的
 * 系统设置项，应用无法自行写入，只能引导用户手动打开。本类负责按 ROM
 * 跳到对应的管理页面，并在跳转失败时回落到「应用详情页 → 系统设置页」。
 */
object AutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /** 已知的厂商 ROM 自启动管理页组件（按优先级排列，逐个尝试）。 */
    private fun candidateComponents(): List<ComponentName> {
        val list = mutableListOf<ComponentName>()
        when (RomHelper.romType()) {
            RomHelper.Rom.MIUI -> {
                // 小米 / 红米（MIUI / HyperOS）
                list += ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            RomHelper.Rom.EMUI -> {
                // 华为 / 荣耀（EMUI / HarmonyOS）
                list += ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                list += ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                )
            }
            RomHelper.Rom.COLOR_OS -> {
                // OPPO / 一加 / realme（ColorOS）
                list += ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
                list += ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                )
                list += ComponentName(
                    "com.color.safecenter",
                    "com.color.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            RomHelper.Rom.VIVO -> {
                // vivo / iQOO（OriginOS / FuntouchOS）
                list += ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                )
                list += ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.SoftwareManagerActivity"
                )
            }
            RomHelper.Rom.FLYME -> {
                // 魅族（Flyme）：只剩内置手机管家的权限页
                list += ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.permission.PermissionMainActivity"
                )
            }
            RomHelper.Rom.OTHER -> Unit
        }
        return list
    }

    /**
     * 打开本 ROM 的自启动管理页面。
     *
     * @return true 表示成功跳转到（疑似）自启动管理页；false 表示已回落到
     *         应用详情页/系统设置页，需要向用户说明手动路径。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for (component in candidateComponents()) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, intent)) {
                Log.i(TAG, "opened autostart page: $component")
                return true
            }
        }
        openAppDetailSettings(context)
        return false
    }

    /** 打开本应用的「应用详情」页（几乎所有 ROM 都有，必定存在）。 */
    fun openAppDetailSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!tryStart(context, intent)) {
            tryStart(
                context,
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.d(TAG, "start failed: ${intent.component ?: intent.action} -> ${it.message}")
        false
    }

    /** 当前 ROM 在「自启动」界面里的关键路径提示（用于文案兜底）。 */
    fun manualPathHint(): String = when (RomHelper.romType()) {
        RomHelper.Rom.MIUI -> "设置 → 应用设置 → 应用管理 → 本应用 → 权限管理 → 自启动"
        RomHelper.Rom.EMUI -> "设置 → 应用和服务 → 应用启动管理 → 本应用 → 关闭自动管理"
        RomHelper.Rom.COLOR_OS -> "设置 → 电池 → 应用电量管理 → 本应用 → 允许自启动"
        RomHelper.Rom.VIVO -> "i 管家 → 应用管理 → 自启动管理 → 本应用"
        RomHelper.Rom.FLYME -> "手机管家 → 权限管理 → 自启动 → 本应用"
        RomHelper.Rom.OTHER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "设置 → 应用 → 本应用 → 允许后台活动 / 自启动"
        } else {
            "设置 → 应用 → 本应用 → 允许后台活动"
        }
    }
}
