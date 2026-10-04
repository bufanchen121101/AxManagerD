package frb.axeron.manager.appmgr

import android.content.Context
import frb.axeron.manager.owner.DeviceOwnerPrivilege

/**
 * 软件管理的**权限等级路由**（v1.4.0 重写）。
 *
 * 用户要求：**按能力等级从大到小逐档尝试，某一档真正生效就立刻停止**；
 * 等级排名：**DO ＞ PO ＞ DP ＞ ADB(shell)**。
 *
 * 本类只负责「算出候选计划列表」，实际执行与「是否真生效」的判定在
 * [AppManageViewModel.perform] 里逐档进行（校验见 [AppManageVerifier]）。
 *
 * 这样修掉了旧实现的两大缺陷：
 *  1. 旧实现只算「一个」计划 → 一旦高估权限（例如界面缓存的 DO），
 *     就直接报 `Error: Device Owner not active`，**却不继续尝试下一档**；
 *  2. 旧实现失败后另外重算一次计划 → 用户看到的
 *     「先用 DO 挂起一次，再用 Shizuku 又挂起一次」的重复尝试。
 *
 * 现在：候选等级**去重**（相同实现在低档位不再重复出现），
 * 且只有「真的可用的等级」才会进入候选。
 */
object AppManageRouter {

    /** 等级数值越大能力越强（DO＞PO＞DP＞SHELL＞NONE），用于排序/展示。 */
    fun rankOf(level: AppManagePrivilegeLevel): Int = when (level) {
        AppManagePrivilegeLevel.DO -> 4
        AppManagePrivilegeLevel.PO -> 3
        AppManagePrivilegeLevel.DP -> 2
        AppManagePrivilegeLevel.SHELL -> 1
        AppManagePrivilegeLevel.NONE -> 0
    }

    /**
     * 计算某动作的**候选执行计划列表**，按能力**从大到小**排列：
     * DO → PO → DP → ADB(shell)。
     *
     * 调用方应当依次尝试，**第一个「校验到真的生效」的档位即停止**（用户要求）。
     *
     * 去重规则：实现完全相同的计划（同一份 localAction 或同一条 shell 命令）
     * 只保留**等级最高**的那一条，避免在低档位做无意义的重复尝试。
     *
     * @param action      管理动作。
     * @param pkg         目标包名。
     * @param hasDo       本应用当前是否真的是 Device Owner（实时查询）。
     * @param hasPo       本应用当前是否真的是 Profile Owner（实时查询）。
     * @param hasDp       本应用当前是否持有 `DEVICE_POLICY_MANAGEMENT` Role。
     * @param hasShell    当前是否可用 Shizuku shell 身份。
     * @param hasLocalDpm 本进程内的 DPM 是否真的可用（DO/PO 本体，或 Dhizuku 已授权）。
     *                    **仅持有 DP Role 时为 false**，此时本地 DPM 必然报
     *                    「Device Owner not active」，因此不生成该候选。
     * @param userId      目标用户（默认 0）。
     */
    fun candidates(
        action: AppManageAction,
        pkg: String,
        hasDo: Boolean,
        hasPo: Boolean,
        hasDp: Boolean,
        hasShell: Boolean,
        hasLocalDpm: Boolean,
        userId: Int = 0,
    ): List<AppManagePlan> {
        val out = ArrayList<AppManagePlan>(4)
        val seen = HashSet<String>(4)

        fun add(plan: AppManagePlan) {
            val key = plan.localAction?.joinToString(separator = " ")
                ?.let { "L:$it" }
                ?: "S:${plan.shellCommand}"
            if (seen.add(key)) out += plan
        }

        // ① DO：真正的设备所有者 + 本地 DPM 真的可用
        if (hasDo && hasLocalDpm) {
            localActionFor(action)?.let {
                add(AppManagePlan(AppManageMethod.LOCAL_DPM, AppManagePrivilegeLevel.DO, localAction = it))
            }
        }
        // ② PO：用户级所有者，能力仅次于 DO
        if (hasPo && hasLocalDpm) {
            localActionFor(action)?.let {
                add(AppManagePlan(AppManageMethod.LOCAL_DPM, AppManagePrivilegeLevel.PO, localAction = it))
            }
        }
        // ③ DP：持有 `DEVICE_POLICY_MANAGEMENT` Role（Device Policy Management Role）。
        //
        // 【v1.4.0 修订 · 保留入口 + 本地 DPM 优先：14+ 用真能力、13 如实降档】
        //
        // 保留入口的原因：用户在 Android 13 上确实能激活 DP Role，界面必须有这个档位，
        // 否则会被误认为「授了 DP 却看不到、用不上」。
        //
        // AOSP 事实（android-13.0.0_r1 DevicePolicyManagerService:11272）：
        //   Android 13 能 `setPackagesSuspended` 的身份只有 DO / PO / shell，
        //   DP Role 授予的 WRITE_SECURE_SETTINGS / MANAGE_DEVICE_ADMINS 里**不含 SUSPEND_APPS**。
        // AOSP 事实（android-14.0.0_r1 同文件:13214）：
        //   14 起引入 Device Policy Engine，启用它的 ROM 走「基于权限」的新路径，
        //   DP Role 持有者被授予 MANAGE_DEVICE_POLICY_* 权限族 → **可以本地直接挂起**。
        //
        // 下面按「本地 DPM 优先、失败自动降档」生成候选，两个版本都会得到如实结果。
        if (hasDp) {
            // 【v1.4.0 修订 · DP 档 = 「本地 DPM 优先」，让 Android 14+ 用上真能力】
            //
            // 为什么**不再用 hasLocalDpm 卡住**本地候选：
            //   hasLocalDpm 只能判定「本进程已是 DO/PO，或 Dhizuku 已授权」。
            //   但 Android 14 起引入了 Device Policy Engine（AOSP 源码中的 Unicorn），
            //   在启用 DPE 的 ROM 上 `setPackagesSuspended` 走的是
            //     enforcePermissionAndGetEnforcingAdmin(who, MANAGE_DEVICE_POLICY_PACKAGE_STATE, ...)
            //   —— **基于权限**的新路径（AOSP android-14.0.0_r1
            //   DevicePolicyManagerService.java:13214）。DEVICE_POLICY_MANAGEMENT Role
            //   的持有者会被授予 MANAGE_DEVICE_POLICY_* 权限族，因而**可以直接本地挂起**，
            //   完全不需要 shell —— 这正是 14+ 受众应得的真能力。
            //
            //   该能力**无法静态判定**（取决于 ROM 是否启用 DPE、Role 是否真被授予该权限），
            //   只能在运行时**逐档尝试**：失败会被 execLocalDpm 转成可读错误并自动降档。
            //   因此这里**无条件**生成本地 DPM 候选，既让 14+ 用上真能力，
            //   也让 13 如实失败后降档，档位不再凭空消失。
            localActionFor(action)?.let {
                add(AppManagePlan(AppManageMethod.LOCAL_DPM, AppManagePrivilegeLevel.DP, localAction = it))
            }
            // 说明：这里**不**给 DP 档挂 shell 候选。
            //   Android 13 上 DP Role 不含 SUSPEND_APPS，本地 DPM 必然不可用，
            //   唯一能真正执行的身份是 shell(uid=2000)。若把 shell 命令记在 DP 档下，
            //   就会出现「活是 shell 干的、却显示 DP Role 生效」的**假档位**，
            //   既误导用户又掩盖真实权限来源。改为让 SHELL 档如实生效，
            //   用户看到的档位名就是真实执行者。
        }
        // ④ ADB / Shizuku shell：兜底最低档
        if (hasShell) {
            buildCommand(action, pkg, AppManagePrivilegeLevel.SHELL, userId)?.let {
                add(AppManagePlan(AppManageMethod.SHELL_CMD, AppManagePrivilegeLevel.SHELL, shellCommand = it))
            }
        }
        return out
    }

    /**
     * 兼容旧调用：取**最高档**的那一个计划（供界面展示「权限：xxx」用）。
     *
     * 真正的执行请用 [candidates]（逐档尝试、生效即停）。
     */
    fun plan(
        action: AppManageAction,
        pkg: String,
        hasDo: Boolean,
        hasDp: Boolean,
        hasShell: Boolean,
        userId: Int = 0,
        hasLocalDpm: Boolean = hasDo,
        hasPo: Boolean = false,
    ): AppManagePlan {
        candidates(
            action = action,
            pkg = pkg,
            hasDo = hasDo,
            hasPo = hasPo,
            hasDp = hasDp,
            hasShell = hasShell,
            hasLocalDpm = hasLocalDpm,
            userId = userId,
        ).firstOrNull()?.let { return it }

        val best = when {
            hasDo -> AppManagePrivilegeLevel.DO
            hasPo -> AppManagePrivilegeLevel.PO
            hasDp -> AppManagePrivilegeLevel.DP
            hasShell -> AppManagePrivilegeLevel.SHELL
            else -> AppManagePrivilegeLevel.NONE
        }
        val reason = if (best == AppManagePrivilegeLevel.NONE) {
            "无可用权限：需要 Device Owner / Profile Owner / DP Role / Shizuku shell 之一"
        } else {
            "${actionLabel(action)} 在 ${levelLabel(best)} 权限下无可用实现"
        }
        return AppManagePlan(AppManageMethod.UNAVAILABLE, best, reason = reason)
    }

    /**
     * DO / PO / DP 档位的**本地 DPM 动作**映射。
     *
     * 返回 null 表示该动作不能通过本地 DPM API 完成，调用方应改用 shell 命令。
     * 这些动作最终由 [AppManageViewModel.execLocalDpm] 调用
     * [DeviceOwnerPrivilege.execute] 执行（同一套 DPM 实现，命令行格式复用）。
     */
    private fun localActionFor(action: AppManageAction): List<String>? = when (action) {
        AppManageAction.FREEZE -> listOf("force-stop")
        AppManageAction.SUSPEND -> listOf("suspend")
        AppManageAction.HIDE -> listOf("hide")
        AppManageAction.FORCE_UNINSTALL -> listOf("uninstall")
        AppManageAction.CLEAR_DATA -> listOf("cleardata")
        AppManageAction.RESTORE -> listOf("unsuspend_unhide_enable")
        // 禁止联网在 DPM 无对应 API，回落 shell
        AppManageAction.BLOCK_NETWORK -> null
    }

    /**
     * 依据权限等级生成命令。
     *
     * 返回 null 表示该等级下无法实现此动作。
     *
     * **DO 与 DP 的差异**在卸载上最典型：
     *   - DO  ：`pm uninstall --user <u> <pkg>`（owner 身份，可卸系统应用）
     *   - DP  ：同样走静默卸载，但命令实际由 shell(uid=2000) 执行，系统应用会被拒绝
     *   - SHELL：同 DP，仅能卸普通应用
     */
    private fun buildCommand(
        action: AppManageAction,
        pkg: String,
        level: AppManagePrivilegeLevel,
        userId: Int,
    ): String? = when (action) {
        // 冻结：停进程 + 禁用包（shell 即可）
        AppManageAction.FREEZE ->
            "am force-stop $pkg; pm disable-user --user $userId $pkg"
        // 挂起：Android 10+ 的 suspend 机制
        AppManageAction.SUSPEND ->
            "pm suspend --user $userId $pkg"
        // 隐藏：从启动器隐藏（hide 需要高权限）
        AppManageAction.HIDE -> when (level) {
            AppManagePrivilegeLevel.DO,
            AppManagePrivilegeLevel.PO,
            AppManagePrivilegeLevel.DP -> "pm hide $pkg"
            else -> null
        }
        // 强制卸载：DO/DP 静默卸载；shell 仅普通应用
        AppManageAction.FORCE_UNINSTALL -> when (level) {
            AppManagePrivilegeLevel.NONE -> null
            else -> "pm uninstall --user $userId $pkg"
        }
        // 清除用户数据
        AppManageAction.CLEAR_DATA -> "pm clear --user $userId $pkg"
        // 禁止联网：Android 的 restrict-background netpolicy
        AppManageAction.BLOCK_NETWORK -> when (level) {
            AppManagePrivilegeLevel.NONE -> null
            else -> "cmd netpolicy add restrict-background $userId $pkg"
        }
        // 恢复：撤销上述限制
        AppManageAction.RESTORE -> when (level) {
            AppManagePrivilegeLevel.DO,
            AppManagePrivilegeLevel.PO,
            AppManagePrivilegeLevel.DP ->
                "cmd netpolicy remove restrict-background $userId $pkg; " +
                        "pm unsuspend --user $userId $pkg; " +
                        "pm unhide $pkg; " +
                        "pm enable --user $userId $pkg"
            AppManagePrivilegeLevel.SHELL ->
                "cmd netpolicy remove restrict-background $userId $pkg; " +
                        "pm unsuspend --user $userId $pkg; " +
                        "pm enable --user $userId $pkg"
            else -> null
        }
    }

    fun actionLabel(action: AppManageAction): String = when (action) {
        AppManageAction.FREEZE -> "冻结"
        AppManageAction.SUSPEND -> "挂起"
        AppManageAction.HIDE -> "隐藏"
        AppManageAction.FORCE_UNINSTALL -> "强制卸载"
        AppManageAction.CLEAR_DATA -> "清除用户数据"
        AppManageAction.BLOCK_NETWORK -> "禁止联网"
        AppManageAction.RESTORE -> "恢复"
    }

    fun levelLabel(level: AppManagePrivilegeLevel): String = when (level) {
        AppManagePrivilegeLevel.DO -> "设备所有者(DO)"
        AppManagePrivilegeLevel.PO -> "资料所有者(PO)"
        AppManagePrivilegeLevel.DP -> "DP Role"
        AppManagePrivilegeLevel.SHELL -> "Shizuku(ADB)"
        AppManagePrivilegeLevel.NONE -> "无"
    }

    /**
     * 查询当前「显示系统应用」所需的一条快捷命令（供调试/复制用，非必需）。
     */
    fun listPackagesCommand(context: Context, includeSystem: Boolean): String =
        if (includeSystem) "pm list packages" else "pm list packages -3"
}