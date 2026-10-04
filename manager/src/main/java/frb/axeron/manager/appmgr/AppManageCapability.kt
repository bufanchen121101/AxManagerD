package frb.axeron.manager.appmgr

/**
 * 软件管理功能的**权限分级**抽象。
 *
 * 设计原则（用户要求）：
 *   **按能力等级从大到小逐档尝试：DO ＞ PO ＞ DP ＞ ADB(shell)，
 *     某一档真正生效就立刻停止**（见 [AppManageRouter.candidates] 与
 *     [AppManageVerifier]）。
 *
 * 本文件只描述「能力等级」与「路由结果」，不掺杂任何 UI / 业务逻辑，
 * 保证公共路径不被污染。
 */
enum class AppManagePrivilegeLevel {
    /** 无任何高权限，任何管理操作都不可用。 */
    NONE,
    /** 仅 Shizuku / ADB shell 身份（`pm` / `cmd` 等）：排名最低。 */
    SHELL,
    /** 持有 `DEVICE_POLICY_MANAGEMENT` Role（DP）：本进程内可用 role 授予的 73 项权限。 */
    DP,
    /** Profile Owner（PO）：用户级所有者，能力仅次于 DO。 */
    PO,
    /** 真正的 Device Owner（DO）：能力最全，排名最高。 */
    DO,
}

/**
 * 单个管理操作在某一权限等级下的**实现方式**。
 *
 * - [DIRECT_API] ：直接调用系统 API（仅 DO 可行，如 `DevicePolicyManager`）。
 * - [LOCAL_DPM]  ：走本进程内的 [frb.axeron.manager.owner.DeviceOwnerPrivilege]
 *                  （DO/DP 通用；**不依赖 Shizuku**）。
 * - [SHELL_CMD]  ：走 shell 命令（DO/DP/shell 均可，命令内容随等级不同）。
 * - [UNAVAILABLE]：该等级下无法实现。
 */
enum class AppManageMethod {
    DIRECT_API,
    LOCAL_DPM,
    SHELL_CMD,
    UNAVAILABLE,
}

/**
 * 一个管理操作的**执行计划**：由 [AppManageRouter] 依据当前实际权限算出。
 *
 * @param method      实现方式。
 * @param level       实际选用的权限等级（优先级 DO > DP > SHELL）。
 * @param shellCommand 当 [method] 为 [SHELL_CMD] 时要执行的命令；否则为 null。
 * @param localAction 当 [method] 为 [LOCAL_DPM] 时，传给
 *                    [frb.axeron.manager.owner.DeviceOwnerPrivilege.execute] 的参数
 *                    （不含目标包名，包名由调用方追加）；否则为 null。
 * @param reason      不可用时的原因文案（给用户看）。
 */
data class AppManagePlan(
    val method: AppManageMethod,
    val level: AppManagePrivilegeLevel,
    val shellCommand: String? = null,
    val localAction: List<String>? = null,
    val reason: String? = null,
) {
    val available: Boolean get() = method != AppManageMethod.UNAVAILABLE
}

/**
 * 管理功能的种类。
 *
 * 用户点名要求的功能：冻结、挂起、隐藏、强制卸载、清除用户数据、禁止联网。
 * 另加若干同类能力，便于卡片管理界面统一编排。
 */
enum class AppManageAction {
    /** 冻结（force-stop / 禁用组件，走 shell 的 `pm` 命令）。 */
    FREEZE,

    /** 挂起（`pm suspend`；DO/DP 亦可）。 */
    SUSPEND,

    /** 隐藏（`pm hide` / 或改组件 enabled 状态）。 */
    HIDE,

    /** 强制卸载（DO/DP 走静默卸载；shell 走 `pm uninstall --user 0`）。 */
    FORCE_UNINSTALL,

    /** 清除用户数据（`pm clear` / DPM `clearApplicationUserData`）。 */
    CLEAR_DATA,

    /** 禁止联网（`cmd netpolicy add restrict-background` 等）。 */
    BLOCK_NETWORK,

    /** 恢复/撤销以上所有限制。 */
    RESTORE,
}
