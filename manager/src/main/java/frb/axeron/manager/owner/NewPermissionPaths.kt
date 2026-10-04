package frb.axeron.manager.owner

import android.content.Context

/**
 * 新权限提权路径：`DP` + `WS`
 *
 * ## 背景
 *
 * 国产 ROM（vivo / OPPO / 小米 / 华为等）大量砍掉或阉割 `DeviceOwner`(DO) / `ProfileOwner`(PO)，
 * 导致依赖 DO 的提权体系失效。经 AOSP 源码解析 + vivo 真机实测，确定一条**未被砍**的替代路径：
 *
 * | 代号 | 全称 | 定位 |
 * |---|---|---|
 * | `DP` | `android.app.role.DEVICE_POLICY_MANAGEMENT` | 能力容器，解锁 73 个权限 |
 * | `WS` | `android.permission.WRITE_SECURE_SETTINGS` | 能力钥匙，改任意系统设置 |
 *
 * **关键**：`DP` 的 73 个权限里**已包含** `WRITE_SECURE_SETTINGS`（以及
 * `MANAGE_PROFILE_AND_DEVICE_OWNERS`），因此拿到 `DP` 即自动获得 `WS`。
 *
 * ## 与 [DeviceOwnerExtras] 的关系
 *
 * 旧实现（[DeviceOwnerExtras] 注释里）判断「Role 必然被拒」——那是因为**没开 bypass**。
 * 真机实测修正结论：
 *  - 直接 `add-role-holder` → `RuntimeException: Failed`（资格校验未过）
 *  - 先 `set-bypassing-role-qualification true` → **成功**（`holders=<pkg>`）
 *
 * 因此本模块采用「两步走」：先开 bypass，再授予 Role。
 *
 * ## 副作用声明
 *
 * `set-bypassing-role-qualification true` 是**全局开关**，会让系统跳过**所有** Role
 * 的资格校验且持续生效。调用方应当：
 *  - 授权成功后**尽量恢复**为 `false`（[buildRestoreBypassCommand]）；
 *  - 或在 UI 上明确告知用户该副作用。
 *
 * 本文件为独立新增模块，**不改动** [DeviceOwnerExtras] / [DeviceOwnerState] 等既有类，
 * 避免污染公共路径。
 */
object NewPermissionPaths {

    /** `DEVICE_POLICY_MANAGEMENT` Role 全名（`@SystemApi`，公开 SDK 无常量，必须用字面量）。 */
    const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    /** `WRITE_SECURE_SETTINGS` 权限名（`DP` 已包含，此处保留常量供 manifest 声明与检查）。 */
    const val PERM_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** `MANAGE_DEVICE_ADMINS` 权限名（`DP` 已包含，静默增删设备管理员）。 */
    const val PERM_MANAGE_DEVICE_ADMINS = "android.permission.MANAGE_DEVICE_ADMINS"

    /** 本应用包名（由调用方传入 context 动态取，避免 flavor 差异出错）。 */
    private fun pkg(context: Context): String = context.packageName

    // ---------------------------------------------------------------------
    // 1. Role 授予（DP）
    // ---------------------------------------------------------------------

    /**
     * 生成「开启 Role 资格校验绕过」的 shell 指令。
     *
     * ⚠️ 全局副作用：会让系统跳过**所有** Role 的资格校验，持续生效。
     */
    fun buildEnableBypassCommand(): String =
        "cmd role set-bypassing-role-qualification true"

    /** 生成「关闭 Role 资格校验绕过」的 shell 指令（用于还原系统状态）。 */
    fun buildRestoreBypassCommand(): String =
        "cmd role set-bypassing-role-qualification false"

    /**
     * 生成「授予 `DEVICE_POLICY_MANAGEMENT` Role」的 shell 指令。
     *
     * 前置：必须先执行 [buildEnableBypassCommand]，否则会抛 `RuntimeException: Failed`。
     */
    fun buildAddRoleHolderCommand(context: Context): String =
        "cmd role add-role-holder --user 0 $ROLE_DEVICE_POLICY_MANAGEMENT ${pkg(context)}"

    /** 生成「撤销 `DEVICE_POLICY_MANAGEMENT` Role」的 shell 指令。 */
    fun buildRemoveRoleHolderCommand(context: Context): String =
        "cmd role remove-role-holder --user 0 $ROLE_DEVICE_POLICY_MANAGEMENT ${pkg(context)}"

    /**
     * 生成「DP 授权完整流程」的 shell 脚本（**推荐使用**）。
     *
     * 【v2.0.0 起为「默认方案 → 备选方案」的降级脚本】
     * ```sh
     * # 默认方案：开 bypass → 授予 DP Role（两种 --user 语法都试）
     * cmd role set-bypassing-role-qualification true
     * cmd role add-role-holder --user 0 android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>
     * # 默认方案失败才执行 → 备选方案：WS 兜底（不依赖 Role / 账户）
     * pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
     * ```
     *
     * 结尾永远输出 `DP_MODE=ROLE|WS|NONE`，并仍以 `DP_GRANT_OK` / `DP_GRANT_FAIL`
     * 结束（保持调用方判定兼容）：`DP_MODE=WS` 表示默认方案失败、已降级到备选方案。
     * 最后再跑一次 `dumpsys role` 让调用方可以直接从输出里确认结果。
     */
    fun buildGrantDpScript(context: Context, keepBypass: Boolean = true): String = buildString {
        // 【v1.4.1 自适应 + 诊断版】
        //
        // 背景：Android 15（如小米 HyperOS）上激活 DP 只回一句 `DP_GRANT_FAIL`，
        // 旧脚本把 stderr 丢掉、也不检查 bypass 命令是否存在，导致无法判断失败原因：
        //   - `set-bypassing-role-qualification` 在 14/15 上可能已不存在（role 实现
        //     迁出 framework），bypass 静默失效 → add-role-holder 因资格校验失败；
        //   - 或 `cmd role add-role-holder` 的 `--user` 参数位置在部分 ROM 上不同；
        //   - 或该 Role 在 14+ 已成为 system-exclusive，shell 根本无权授予。
        //
        // 现在改为：①探测 bypass 是否可用；②两种 `--user` 语法都试；
        // ③把**系统原文**（stderr 一并捕获）带回 UI，便于精确定位。
        // 返回值仍以 DP_GRANT_OK / DP_GRANT_FAIL 结尾，保持调用方判定兼容。
        // v1.4.4：shell 变量前缀用字符码生成（36 = 0x24），
        // 源码里不出现反斜杠与转义符，避免编辑链把转义吃掉导致 Kotlin 模板解析错误。
        val D = Char(36).toString()
        append("ROLE=").append(ROLE_DEVICE_POLICY_MANAGEMENT).append("; ")
        append("PKG=").append(pkg(context)).append("; ")
        append("BYPASS=0; ")
        append("if cmd role set-bypassing-role-qualification true >/dev/null 2>&1; then BYPASS=1; fi; ")
        // v1.4.5：14+ 前置探测。DP role 是 static role，非默认持有者必须靠 bypass，
        // 而 14+ 的 bypass 由 DPM 二次把关（要求“全设备无不兼容账户”）。
        // 这里把 SDK / 账户数 / 用户数一并带回，便于精确定位。
        append("SDK=" + D + "(getprop ro.build.version.sdk); ")
        append("ACCTS=" + D + "(dumpsys account 2>/dev/null | grep -c 'Account {'); ")
        append("USERS=" + D + "(dumpsys user 2>/dev/null | grep -c 'UserInfo{'); ")
        append("echo DP_SDK=" + D + "SDK DP_ACCTS=" + D + "ACCTS DP_USERS=" + D + "USERS; ")
        append("OUT=" + D + "(cmd role add-role-holder --user 0 " + D + "ROLE " + D + "PKG 2>&1); RC=" + D + "?; ")
        append("if [ " + D + "RC -ne 0 ]; then ")
        append("OUT2=" + D + "(cmd role add-role-holder " + D + "ROLE " + D + "PKG --user 0 2>&1); RC2=" + D + "?; ")
        append("if [ " + D + "RC2 -eq 0 ]; then RC=0; OUT=" + D + "OUT2; else OUT=" + D + "OUT; fi; ")
        append("fi; ")
        // ==================== 【v2.0.0 降级：默认方案 → 备选方案】 ====================
        //
        // 真机实测（vivo / Android 13）结论，决定本段结构：
        //  1. 默认方案 = bypass + `cmd role add-role-holder <DP role> <pkg>`。
        //     这是唯一能真正拿到 DP Role（从而自动获得其 73 个 role 位权限，
        //     含 MANAGE_PROFILE_AND_DEVICE_OWNERS / MANAGE_DEVICE_ADMINS）的通道。
        //  2. 备选方案 = `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`。
        //     该权限 protectionLevel 含 development 位，shell 身份可直授，
        //     **不经过 Role 资格校验、不读账户列表**（实测无报错、granted=true）。
        //     用于「Role 通道被 ROM 封死」时仍保证 App 具备改系统设置的能力。
        //  3. 判据不只看命令返回码，还复核 `dumpsys role` 的 holders
        //     （部分 ROM 上 add-role-holder 返回 0 但角色并未真正落地）。
        //
        // bypass 处理：默认**不再无条件关回 false**（见参数 keepBypass）。
        // 实测表明 bypass=false 时 add-role-holder 直接失败；该开关是纯内存态、
        // 重启即复位、不写盘，保留它不会持久污染系统，却能消除「下一次授予失败」。
        append("WS=").append(PERM_WRITE_SECURE_SETTINGS).append("; ")
        if (!keepBypass) {
            append("if [ " + D + "BYPASS -eq 1 ]; then cmd role set-bypassing-role-qualification false >/dev/null 2>&1; fi; ")
        }
        append("echo DP_BYPASS=" + D + "BYPASS DP_KEEP_BYPASS=")
        append(if (keepBypass) "1" else "0")
        append(" DP_RC=" + D + "RC; ")
        append("echo DP_OUT=" + D + "OUT; ")
        // holders 复核：与 buildQueryDpScript 同一判据，避免 ROM 缩进差异。
        append("H=" + D + "(dumpsys role 2>/dev/null | grep -A20 '" + ROLE_DEVICE_POLICY_MANAGEMENT +
                "' | grep -A10 'holders' | grep -c '" + pkg(context) + "'); ")
        append("echo DPD_ROLE_HOLDER=" + D + "H; ")
        // ---------- 默认方案成功 → 结束 ----------
        append("if [ " + D + "RC -eq 0 ] || [ " + D + "H -gt 0 ]; then ")
        append("echo DP_MODE=ROLE; echo DP_GRANT_OK; exit 0; fi; ")
        // ---------- 备选方案：WS 兜底（不依赖 Role / 账户） ----------
        append("FB=" + D + "(pm grant " + D + "PKG " + D + "WS 2>&1); FBRC=" + D + "?; ")
        append("WSQ=" + D + "(dumpsys package " + D + "PKG 2>/dev/null | grep -c 'WRITE_SECURE_SETTINGS: granted=true'); ")
        append("echo DP_FB_RC=" + D + "FBRC DP_FB_WS=" + D + "WSQ; ")
        append("echo DP_FB_OUT=" + D + "FB; ")
        append("if [ " + D + "WSQ -gt 0 ]; then ")
        append("echo DP_MODE=WS; echo DP_GRANT_OK; exit 0; fi; ")
        // ---------- 两条通道均失败：原文写进 stderr，UI 才能显示真实原因 ----------
        append("if [ " + D + "SDK -ge 34 ]; then echo DP_EXPLAIN=DP_ROLE_STATIC_NEEDS_NO_ACCOUNTS; fi; ")
        append("echo DP_SDK=" + D + "SDK DP_ACCTS=" + D + "ACCTS DP_USERS=" + D + "USERS >&2; ")
        append("echo DP_OUT=" + D + "OUT >&2; ")
        append("echo DP_FB_OUT=" + D + "FB >&2; ")
        append("echo DP_MODE=NONE; echo DP_GRANT_FAIL; echo DP_GRANT_FAIL >&2; exit 1")
    }

    /**
     * 生成「撤销 DP」的完整脚本（含还原 bypass）。
     */
    fun buildRevokeDpScript(context: Context): String = buildString {
        // 【v1.4.1 自适应 + 诊断版】与 buildGrantDpScript 对称：
        // 探测 bypass 是否可用、捕获 stderr、回显系统原文。
        // v1.4.4：同 grant，前缀用 Char(36) 生成，源码零反斜杠。
        val D = Char(36).toString()
        append("ROLE=").append(ROLE_DEVICE_POLICY_MANAGEMENT).append("; ")
        append("PKG=").append(pkg(context)).append("; ")
        append("BYPASS=0; ")
        append("if cmd role set-bypassing-role-qualification true >/dev/null 2>&1; then BYPASS=1; fi; ")
        append("OUT=" + D + "(cmd role remove-role-holder --user 0 " + D + "ROLE " + D + "PKG 2>&1); RC=" + D + "?; ")
        append("if [ " + D + "BYPASS -eq 1 ]; then cmd role set-bypassing-role-qualification false >/dev/null 2>&1; fi; ")
        append("echo DP_BYPASS=" + D + "BYPASS DP_RC=" + D + "RC; ")
        append("echo DP_OUT=" + D + "OUT; ")
        append("if [ " + D + "RC -eq 0 ]; then echo DP_REVOKE_OK; exit 0; else echo DP_REVOKE_FAIL; exit 1; fi")
    }

    // ---------------------------------------------------------------------
    // 2. 状态查询（**必须走 Shizuku**）
    //
    // ⚠️ 重要（实测结论）：`dumpsys role` / `dumpsys package` 需要
    // `android.permission.DUMP`（shell 专属）。若用应用自身 UID 执行
    // （`Runtime.exec`），实测返回：
    //   Permission Denial: can't dump role ... missing android.permission.DUMP
    // 或**完全空输出**，会导致「授权成功却显示未激活」。
    //
    // 因此本模块**只生成「可交给 Shizuku 执行的查询脚本」**，
    // 由调用方（`ActivateViewModel.execViaShizuku`）以 shell 身份执行后，
    // 把输出交给 [parseDp] / [parseGranted] 解析。
    // ---------------------------------------------------------------------

    /**
     * 生成「查询 DP 是否由本应用持有」的脚本（走 Shizuku 执行后把输出交给 [parseDp])。
     *
     * 【v1.4.6 修复】旧实现用 `grep -A3 '<ROLE>'`，但 `dumpsys role` 的真实输出形如：
     * ```
     *   Role name: android.app.role.DEVICE_POLICY_MANAGEMENT
     *     holders:
     *       frb.axeron.manager
     * ```
     * `name=` 与 `holders=` 并不相邻、且行首缩进固定，`-A3` 会随 ROM 差异漏抓 `holders` 行，
     * 导致 [parseDp] 恒为 false —— 即「DP Role 明明已授予，卡片却显示未授予」的根因。
     *
     * 现在改为输出一个**自描述标记行** `DPD_ROLE_HOLDER=<0/1>`：
     * 用 `-A20` 覆盖整个 Role 段落，并在 shell 内直接判断 holders 段中是否含本应用包名，
     * 结果不依赖 UI 侧二次解析，避免缩进 / 换行差异导致的误判。
     */
    fun buildQueryDpScript(context: Context): String {
        val D = Char(36).toString()
        return "H=" + D + "(dumpsys role 2>/dev/null | grep -A20 '" + ROLE_DEVICE_POLICY_MANAGEMENT +
                "' | grep -A10 'holders' | grep -c '" + pkg(context) + "'); " +
                "echo DPD_ROLE_HOLDER=" + D + "H"
    }

    /** 生成「查询 WS 是否已授予」的脚本（走 Shizuku 执行）。 */
    fun buildQueryWsScript(context: Context): String =
        "dumpsys package ${pkg(context)} 2>/dev/null | grep '$PERM_WRITE_SECURE_SETTINGS'"

    /** 生成「查询 MANAGE_DEVICE_ADMINS 是否已授予」的脚本（走 Shizuku 执行）。 */
    fun buildQueryMdaScript(context: Context): String =
        "dumpsys package ${pkg(context)} 2>/dev/null | grep '$PERM_MANAGE_DEVICE_ADMINS'"

    // ---------------------------------------------------------------------
    // 【v1.6.4 新增】MANAGE_PROFILE_AND_DEVICE_OWNERS 查询
    //
    // 背景：该权限的 protectionLevel = signature|role。role 位授予后
    // `checkSelfPermission` 只在「应用已在 manifest 声明该权限」时才可能返回
    // GRANTED（PermissionManagerServiceImpl#checkPermissionInternal →
    // UidPermissionState.isPermissionGranted 要求 mPermissions 表内存在该权限）。
    // 若 manifest 漏声明，则恒 DENIED。
    //
    // 为在 manifest 已声明的前提下再提供一条**绕过应用侧权限表**的独立判据
    // （用于诊断「角色已授 vs 权限已落地」），此处用 shell 身份读 dumpsys package。
    //   dumpsys package <pkg> 输出中 shape 为：
    //     android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS: granted=true
    // ---------------------------------------------------------------------

    /** `MANAGE_PROFILE_AND_DEVICE_OWNERS` 权限名（直连激活的核心放行判据）。 */
    const val PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS =
        "android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS"

    /**
     * 生成「查询 MAPDO 是否已授予」的脚本（走 Shizuku 执行）。
     *
     * 输出形如：
     * ```
     * android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS: granted=true
     * ```
     * 与 [parseGranted] 配合使用。
     */
    fun buildQueryMapdoScript(context: Context): String =
        "dumpsys package ${pkg(context)} 2>/dev/null | grep '$PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS'"

    /**
     * 解析「DP 查询脚本」的输出。
     *
     * 输入形如：
     * ```
     *         name=android.app.role.DEVICE_POLICY_MANAGEMENT
     *         holders=frb.axeron.manager
     * ```
     */
    /**
     * 【v1.4.5】把 Android 14+ 的 DP 授予失败翻译成可执行的中文提示。
     *
     * 依据（AOSP 15 源码，已逐条核对）：
     *  1. `android.app.role.DEVICE_POLICY_MANAGEMENT` 在 roles.xml 里 `static="true"`，
     *     所以 `Role#isPackageQualifiedAsUser` 只对 `config_devicePolicyManagement`
     *     的默认持有者放行（`if (mStatic && !defaultHolders.contains(pkg)) return false;`）。
     *  2. 非默认持有者唯一出路是 bypass：`shouldAllowBypassingQualification()
     *     && RoleManagerCompat.isBypassingRoleQualification()`。
     *  3. 14+ 的 bypass 由 DPM 二次把关：
     *     `DevicePolicyManagerService#shouldAllowBypassingDevicePolicyManagementRoleQualification()`
     *     = `mBypassDevicePolicyManagementRoleQualifications`（只能由系统在角色变更时置位）
     *       或（非测试用户 ≤ 1 且 **全设备没有任何“不兼容账户”**）；
     *       而“不兼容账户”= 未声明 `ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED`
     *       的账户（见 DevicePolicyManagerService$HasIncompatibleAccountsTask）。
     *  4. framework 没有 shell 命令能把这个状态置真
     *     （DevicePolicyManagerServiceShellCommand 里无 bypass/qualification 命令）。
     *
     * 结论：14+ 上 DP 只可能在“设备尚未配置（无任何账户）”时授予成功。
     */
    fun explainDpFailure(context: Context, output: String?): String? {
        if (android.os.Build.VERSION.SDK_INT < 34) return null
        val text = output ?: return null
        if (!text.contains("DP_GRANT_FAIL")) return null
        val accts = Regex("DP_ACCTS=([0-9]+)").find(text)?.groupValues?.get(1)
        return buildString {
            append("DP Role 未授予（Android 14+ 的系统限制）：")
            append("该 Role 是 static role，非预置持有者必须靠 bypass，")
            append("而 14+ 的 bypass 只在“设备无任何账户”时生效")
            if (accts != null) append("（当前检测到账户数 ").append(accts).append("）")
            append("。可选方案：")
            append("① 临时在系统设置里删除全部账户（Google/小米等）后重试，")
            append("授予成功后角色会保留，可再把账户加回来；")
            append("② 或改用 Dhizuku / Shizuku shell 通道（本应用其它激活入口）。")
        }
    }

    fun parseDp(context: Context, output: String?): Boolean {
        val out = output ?: return false
        // 【v1.4.6】优先读脚本内自判定的标记行（最稳，不受 ROM 缩进差异影响）。
        val marked = Regex("DPD_ROLE_HOLDER=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (marked != null) return marked > 0
        // 回退：兼容旧脚本输出（holders=<pkg>）。
        if (!out.contains("holders")) return false
        val target = pkg(context)
        return out.contains(target)
    }

    /** 解析 WS / MDA 查询输出（判断是否 `granted=true`）。 */
    fun parseGranted(output: String?): Boolean = output?.contains("granted=true") == true

    /**
     * 【v2.0.0】解析「DP 授权脚本」实际生效的通道。
     *
     * [buildGrantDpScript] 结尾会输出 `DP_MODE=ROLE|WS|NONE`：
     *  - `ROLE` —— 默认方案成功（本应用持有 DP Role）；
     *  - `WS`   —— 默认方案失败，已降级到备选方案
     *              （仅拿到 `WRITE_SECURE_SETTINGS`，未持有 DP Role）；
     *  - `NONE` —— 两条通道均失败（脚本以 `exit 1` 结束，调用方拿到 stderr）。
     *
     * @return `"ROLE"` / `"WS"` / `"NONE"`；输出中无标记时返回 `null`。
     */
    fun parseGrantMode(output: String?): String? {
        val out = output ?: return null
        return Regex("DP_MODE=([A-Z]+)").find(out)?.groupValues?.get(1)
    }
}
