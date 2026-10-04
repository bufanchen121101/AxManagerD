package frb.axeron.manager.owner

import android.content.Context
import android.os.Build

/**
 * 新路径：`DP` + `Shizuku(ADB)` 联合激活 Device Owner。
 *
 * ## 背景与依据
 *
 * 该方案完全基于 AOSP 源码取证（android-15.0.0_r1 / android-13.0.0_r1）：
 *
 * 1. `dpm set-device-owner` 的真实入口是
 *    `DevicePolicyManagerServiceShellCommand#runSetDeviceOwner`，其流程为三明治结构：
 *    ```
 *    setActiveAdmin(component, false, userId)        // ① 先成为设备管理员
 *    setDeviceOwner(component, userId, !doOnly)      // ② 再成为设备所有者（失败回滚 ①）
 *    setUserProvisioningState(FINALIZED, userId)     // ③ 收尾
 *    ```
 *    → **成为 active admin 是成为 DO 的必要前置条件**，无法跳过。
 *
 * 2. `DevicePolicyManagerService#isAdb(CallerIdentity)`（L18853）：
 *    ```java
 *    return isShellUid(caller) || isRootUid(caller);
 *    ```
 *    → **Shizuku 运行在 shell uid(2000)，通过 Shizuku 执行 `dpm` 等同 ADB 路径**。
 *
 * 3. `enforceCanSetDeviceOwnerLocked`（L10980）：非 ADB 时需
 *    `MANAGE_PROFILE_AND_DEVICE_OWNERS`（`signature|role`，DP Role 持有者本就合法持有）；
 *    ADB 时直接放行。
 *
 * 4. `checkDeviceOwnerProvisioningPreConditionLocked`（L17111）ADB 分支：
 *    向导未完成 → `STATUS_OK`（零条件）；
 *    向导已完成 → 需 `nonTestNonPrecreatedUsersExist()==false`（普通机仅 1 个用户）
 *    且 `hasIncompatibleAccountsOnAnyUser()==false`（**全设备无账户**）。
 *
 * 5. `CalculateHasIncompatibleAccountsTask#userHasIncompatibleAccounts`（L18814）：
 *    遍历 `am.getAccounts()`，**无任何账户即返回 false**。
 *
 * ## 因此本方案的完整通过条件
 *
 * | 条件 | 说明 |
 * |---|---|
 * | DP Role 已持有 | 提供 `MANAGE_DEVICE_ADMINS`，让 ① 步可由应用自主完成 |
 * | Shizuku 可用 | 提供 shell uid，让 ② 步走 ADB 分支 |
 * | 无账户 | 系统设置里删除全部账户（含"系统账户"） |
 * | 仅 User 0 | 无工作资料 / 克隆 / 隐私空间等第二用户 |
 * | release 包 | 非 `android:testOnly` |
 *
 * ## 版本限制（源码级）
 *
 * - **Android 13（API 33）起才存在 `DEVICE_POLICY_MANAGEMENT` 这个 Role**：
 *   AOSP `android-12.0.0_r1` 全量 grep `DEVICE_POLICY_MANAGEMENT` **零命中**，
 *   而 `android-13.0.0_r1` 的 `DevicePolicyManagerService` 已大量使用
 *   （L17813 / L18773 / L18802 / `OverlayPackagesProvider` L117）。
 * - `isAdb()`（shell/root uid）在 11/12/13/15 均存在，**Shizuku=ADB 无版本限制**。
 * - 因此：
 *   - **Android 13+**：DP Role 可用 → 本方案（DP + Shizuku）可行；
 *   - **Android 12 及以下**：无 DP Role → 本卡片自动置灰，请走纯 Shizuku/Dhizuku 通道；
 *   - **Android 14+**：DP Role 变为 static role，非预置持有者必须靠 bypass，
 *     而 14+ 的 bypass 由 DPM 二次把关（同样要求"全设备无不兼容账户"）→ 与 DO 门槛一致。
 *
 * 本文件为独立新增模块，**不改动** [NewPermissionPaths] / [DeviceOwnerAdbActivator]
 * 等既有类，避免污染公共路径。
 */
object DpDoEscalation {

    /** `DEVICE_POLICY_MANAGEMENT` Role 全名（`@SystemApi`，公开 SDK 无常量，必须用字面量）。 */
    const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    /** 本方案支持的最低 API（Android 13 = API 33，DP Role 自该版本引入）。 */
    const val MIN_SUPPORTED_SDK = Build.VERSION_CODES.TIRAMISU

    /** 运行期版本是否满足（Android 13+）。 */
    fun isVersionSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_SUPPORTED_SDK

    // ---------------------------------------------------------------------
    // 1. 组件名
    // ---------------------------------------------------------------------

    /** `dpm` 命令需要的组件扁平化字符串（`<pkg>/<receiver 全类名>`）。 */
    private fun component(context: Context): String {
        val cn = DeviceOwnerState.admin
        return cn.flattenToShortString()
    }

    /** 本应用包名（动态取，避免 flavor 差异）。 */
    private fun pkg(context: Context): String = context.packageName

    // ---------------------------------------------------------------------
    // 2. 诊断脚本（走 Shizuku 执行，收集当前设备状态）
    // ---------------------------------------------------------------------

    /**
     * 生成「采集 DO 激活前置状态」的脚本。
     *
     * 输出行（供 [parseDiagnostics] 解析）：
     * ```
     * DD_SDK=35
     * DD_ACCTS=0
     * DD_USERS=1
     * DD_SETUP=1
     * DD_HAS_DO=0
     * DD_HAS_PO=0
     * ```
     */
    fun buildDiagnosticScript(context: Context): String = buildString {
        // v1.4.4 约定：shell 变量前缀用字符码生成（36 = 0x24），源码零反斜杠/转义符。
        val D = Char(36).toString()
        append("echo DD_SDK=" + D + "(getprop ro.build.version.sdk); ")
        // 【v1.5.0 修复 · 账户数误报】
        //
        // 旧实现：`dumpsys account | grep -c 'Account {'`
        // 实测（一加 ACE 6T / ColorOS，Android 15）故障现象：
        //   用户在系统设置里把账户全部删除后，本应用仍显示「有账户」→
        //   卡片判定前置条件不满足 → DP 提权 DO 直接失败；
        //   而同一台设备用 Dhizuku 通道激活可成功（Dhizuku 不查账户）。
        //
        // 根因：`dumpsys account` 输出里含 `Account {` 的行**不止一处**：
        //   · `Accounts: N` 段落内的真实账户行        ← 只有这段算数；
        //   · `AccountSyncStatus` / `SyncAdapters` 段内的同步状态行
        //     （应用卸载/登出后，其 `AuthenticatorDescription` 注册记录与
        //      同步状态行仍会被 dump 出来，`grep -c` 一并计数）。
        // 于是「账户已清空」时行数依然 > 0，形成**永久误报**。
        //
        // 修法：改读 `AccountManagerService.dump()` 的**权威计数行** `Accounts: N`。
        // 用 awk 取该行第 2 个字段（N 本身），零转义、不受同步段落干扰；
        // awk 读不到（极少数 ROM 输出格式不同）时**回落到精确匹配**：
        // 要求行内同时出现 `name=` 与 `type=`，避免再被同步段落污染。
        append(
            "echo DD_ACCTS=" + D + "(C=" + D + "(dumpsys account 2>/dev/null " +
                    "| awk '/^[ ]*Accounts:/{print " + D + "2; exit}'); " +
                    // awk 取不到（ROM 输出格式差异）时回落到严格匹配：
                    // 要求该行同时含 name= 与 type=，只数真实账户行，
                    // 同步段落（只有 type= 没有 name=）不会被计入。
                    "[ -z \"" + D + "C\" ] && C=" + D + "(dumpsys account 2>/dev/null " +
                    "| awk '/name=/{if (" + D + "0 ~ /type=/) n++} END{print n+0}'); " +
                    "echo " + D + "C); "
        )
        append(
            "echo DD_USERS=" + D + "(dumpsys user 2>/dev/null " +
                    "| awk '/^[ ]*UserInfo[{]/{n++} END{print n+0}'); "
        )
        append(
            "echo DD_SETUP=" + D + "(settings get secure user_setup_complete 2>/dev/null); "
        )
        append(
            "echo DD_HAS_DO=" + D + "(dumpsys device_policy 2>/dev/null | " +
                    "grep -c 'Device Owner:.*" + pkg(context) + "'); "
        )
        append(
            "echo DD_HAS_PO=" + D + "(dumpsys device_policy 2>/dev/null | " +
                    "grep -c 'Profile Owner:.*" + pkg(context) + "'); "
        )
        append("true")
    }

    /** 解析诊断脚本输出。 */
    data class Diagnostics(
        val sdk: Int,
        val accountCount: Int,
        val userCount: Int,
        val setupComplete: Boolean,
        val hasDeviceOwner: Boolean,
        val hasProfileOwner: Boolean,
    ) {
        /** 「无账户」是否满足。 */
        val noAccounts: Boolean get() = accountCount <= 0

        /** 「仅 1 个用户」是否满足（普通手机）。 */
        val singleUser: Boolean get() = userCount <= 1

        /** 全部前置是否满足（向导已完成的情况下）。 */
        val allReady: Boolean
            get() = noAccounts && singleUser && !hasDeviceOwner && !hasProfileOwner
    }

    fun parseDiagnostics(output: String?): Diagnostics? {
        val out = output ?: return null
        fun intOf(key: String): Int? =
            Regex("$key=([0-9]+)").find(out)?.groupValues?.get(1)?.toIntOrNull()

        val sdk = intOf("DD_SDK") ?: return null
        return Diagnostics(
            sdk = sdk,
            accountCount = intOf("DD_ACCTS") ?: 0,
            userCount = intOf("DD_USERS") ?: 1,
            setupComplete = (intOf("DD_SETUP") ?: 0) != 0,
            hasDeviceOwner = (intOf("DD_HAS_DO") ?: 0) > 0,
            hasProfileOwner = (intOf("DD_HAS_PO") ?: 0) > 0,
        )
    }

    // ---------------------------------------------------------------------
    // 3. 激活脚本
    // ---------------------------------------------------------------------

    /**
     * 生成「DP + Shizuku 联合激活 DO」的完整 shell 脚本。
     *
     * 步骤：
     * ```sh
     * dpm set-active-admin --user 0 <pkg>/<receiver>     # ①（DP 亦可自主完成）
     * dpm set-device-owner --user 0 <pkg>/<receiver>     # ②（Shizuku 提供 ADB 身份）
     * ```
     * 说明：`dpm set-device-owner` 内部会自动执行 ① 与 ③，此处显式再执行 ① 是为了
     * 在「DP Role 已持有」时先行建立管理员身份，避免部分 ROM 上
     * `runSetDeviceOwner` 首步抛 `IllegalArgumentException` 时的回滚语义差异。
     *
     * 失败时把系统原文（stderr 一并捕获）带回 UI，便于精确诊断。
     * 返回值以 `DPDO_OK` / `DPDO_FAIL` 结尾，供调用方判定。
     */
    fun buildActivateScript(context: Context): String = buildString {
        val D = Char(36).toString()
        val comp = component(context)
        append("COMP=").append(comp).append("; ")
        append("OUT1=" + D + "(dpm set-active-admin --user 0 " + D + "COMP 2>&1); RC1=" + D + "?; ")
        append("echo DPDO_ADMIN_RC=" + D + "RC1; echo DPDO_ADMIN_OUT=" + D + "OUT1; ")
        append(
            "OUT2=" + D + "(dpm set-device-owner --user 0 " + D + "COMP 2>&1); RC2=" + D + "?; "
        )
        append("echo DPDO_DO_RC=" + D + "RC2; echo DPDO_DO_OUT=" + D + "OUT2; ")
        append("if [ " + D + "RC2 -eq 0 ]; then echo DPDO_OK; exit 0; else echo DPDO_FAIL; exit 1; fi")
    }

    // ---------------------------------------------------------------------
    // 3b. 【v1.4.7】账户清理：冻结 / 解冻「持有账户的应用」
    //
    // 背景（一加 ACE5 / Android 16 实测）：
    //   `dpm set-device-owner` 在 ADB 分支下仍会执行
    //   `setDeviceOwner()` 内的这一段（AOSP 15/16 L9772-L9780）：
    //     if (!hasIncompatibleAccountsOrNonAdb) {
    //         if (!isAdminTestOnlyLocked(admin, userId) && hasAccountsOnAnyUser()) return false;
    //     }
    //   即：release 包 + 设备上存在任何账户 → 直接返回 false → 报
    //   "Can't set package ... as device owner."
    //
    // 用户实测发现：账户并非全部来自「系统设置 → 账户」，更多是各 App 通过
    //   AccountManager.addAccountExplicitly() 注册的登录态（抖音 / 番茄 / 夸克 /
    //   Telegram / QQ邮箱 等），在设置里退不掉；即使退了，厂商系统组件
    //   (uid=1000) 也会立刻把账户加回来。
    //
    // 对策：把「持有账户的应用」临时**冻结**（`pm disable-user`），
    //   AccountManagerService 会因为组件被禁用而不再返回其账户；
    //   激活完成后**解冻**（`pm enable`）。这是可逆的、不丢数据的操作。
    // ---------------------------------------------------------------------

    /**
     * 生成「列出当前所有账户及其所属包名」的脚本。
     *
     * 输出格式（每行一个）：
     * ```
     * DDACC|com.google|xxx@gmail.com
     * DDACC|com.ss.android.ugc.aweme|抖音昵称
     * ```
     *
     * 实现说明：`dumpsys account` 只给 type（= 认证器包名），此处直接输出该 type，
     * 因为 android 账户的 `type` 字段等于 `AccountAuthenticator` 所在包名，
     * 可作为 `pm disable-user` 的目标。
     */
    fun buildListAccountsScript(context: Context): String = buildString {
        val D = Char(36).toString()
        append("dumpsys account 2>/dev/null | grep 'Account {' | sed 's/.*type=//; s/}.*//' ")
        append("| sort -u | while read T; do if [ -n \"" + D + "T\" ]; then echo DDACC=")
        append(D + "T; fi; done; true")
    }

    /**
     * 生成「冻结指定包」的脚本（用于临时清空账户可见性）。
     *
     * `pm disable-user --user 0 <pkg>` 会禁用该包，其注册的账户随之下线；
     * 对系统组件（如 `com.google.android.gms`）会失败，脚本容忍失败并继续。
     */
    fun buildFreezeAppsScript(packages: List<String>): String = buildString {
        val D = Char(36).toString()
        append("FROZEN=\"\"; ")
        for (p in packages) {
            append("if pm disable-user --user 0 ").append(p)
            append(" >/dev/null 2>&1; then FROZEN=\"" + D + "FROZEN ").append(p).append("\"; echo DPDO_FROZE_OK=").append(p).append("; ")
            append("else echo DPDO_FROZE_SKIP=").append(p).append("; fi; ")
        }
        append("echo DPDO_FROZEN_LIST=" + D + "FROZEN; true")
    }

    /** 生成「解冻指定包」的脚本（激活结束后恢复原状）。 */
    fun buildUnfreezeAppsScript(packages: List<String>): String = buildString {
        for (p in packages) {
            append("pm enable --user 0 ").append(p).append(" >/dev/null 2>&1 ")
            append("&& echo DPDO_THAW_OK=").append(p).append(" ")
            append("|| echo DPDO_THAW_SKIP=").append(p).append("; ")
        }
        append("true")
    }

    /** 解析「列出账户」脚本输出，得到去重后的包名列表。 */
    fun parseAccountPackages(output: String?): List<String> {
        val out = output ?: return emptyList()
        return out.lineSequence()
            .filter { it.startsWith("DDACC|") }
            .map { it.removePrefix("DDACC|").trim() }
            .filter { it.isNotBlank() && it.contains('.') }
            .distinct()
            .toList()
    }

    /**
     * 【v1.4.7】生成「带账户自动清理的完整激活流程」脚本。
     *
     * 流程（全部在一个 shell 里完成，保证上下文连贯）：
     * ```
     * ① 尝试默认方案：dpm set-device-owner
     * ② 若失败：列出账户 → 冻结持有账户的应用 → 等 3 秒 → 重试
     * ③ 无论成败：解冻所有被冻结的应用
     * ```
     *
     * 输出标记（供 UI 解析）：
     * ```
     * DPDO_STAGE=...        阶段进度（UI 逐步显示）
     * DPDO_OK / DPDO_FAIL   最终结果
     * DPDO_FROZEN_LIST=...  被冻结的包（保证解冻名单准确）
     * ```
     *
     * @param forTestOnly 仅用于 UI 预览脚本，不参与运行
     */
    fun buildSmartActivateScript(context: Context): String = buildString {
        val D = Char(36).toString()
        val comp = component(context)
        append("COMP=").append(comp).append("; ")
        // 【v1.5.0 前置预检】在动手前先把两个 ADB 分支的硬前置测出来。
        //
        // 依据 AOSP `DevicePolicyManagerServiceShellCommand#runSetDeviceOwner`
        // 与 `setDeviceOwner()` 内的 `hasAccountsOnAnyUser()`：
        // release 包 + 设备存在任何账户 → 直接 false；
        // 存在第二用户（工作资料 / 克隆 / 隐私空间）→ 同样 false。
        //
        // 这两条无法通过「冻结持有账户的应用」绕过：账户存在 `accounts.db`，
        // 不是应用内存态，`pm disable-user` 只会让同步器下线，账户本身仍在，
        // `hasAccountsOnAnyUser()` 依然为 true。旧脚本因此白跑一整轮
        // 「列账户 → 冻结 → sleep 3 → 重试 → 解冻」，日志被撑长、耗时变久
        // （在旧版本上还会因日志过长触发 TransactionTooLargeException 闪退）。
        //
        // 现在：前置不满足 → 立刻 DPDO_FAIL + 中文原因，跳过整段无用流程。
        // 账户数取值与 [buildDiagnosticScript] 完全一致（读权威行 `Accounts: N`）。
        append("AC=" + D + "(dumpsys account 2>/dev/null " +
                "| awk '/^[ ]*Accounts:/{print " + D + "2; exit}'); ")
        append("UC=" + D + "(dumpsys user 2>/dev/null " +
                "| awk '/^[ ]*UserInfo[{]/{n++} END{print n+0}'); ")
        append("echo DPDO_STAGE=precheck; ")
        append("echo DPDO_ACCTS=" + D + "AC; echo DPDO_USERS=" + D + "UC; ")
        append("if [ -n \"" + D + "AC\" ] && [ \"" + D + "AC\" -gt 0 ]; then ")
        append("  echo \"DPDO_REASON=请先在「设置 → 用户与账号」中删除全部账户" +
                "（当前 " + D + "AC 个），删除后等待约 10 秒再重试。\"; ")
        append("  echo DPDO_FAIL; exit 1; fi; ")
        append("if [ -n \"" + D + "UC\" ] && [ \"" + D + "UC\" -gt 1 ]; then ")
        append("  echo \"DPDO_REASON=设备存在多个用户（当前 " + D + "UC 个），" +
                "请删除工作资料/克隆/隐私空间，仅保留机主后重试。\"; ")
        append("  echo DPDO_FAIL; exit 1; fi; ")
        append("echo DPDO_STAGE=start-default; ")
        append("OUT=" + D + "(dpm set-active-admin --user 0 " + D + "COMP 2>&1); echo \"" + D + "OUT\"; ")
        append("OUT2=" + D + "(dpm set-device-owner --user 0 " + D + "COMP 2>&1); RC=" + D + "?; ")
        append("echo \"" + D + "OUT2\"; ")
        append("if [ " + D + "RC -eq 0 ]; then echo DPDO_OK; exit 0; fi; ")
        append("echo DPDO_STAGE=default-failed-try-freeze; ")
        // 收集账户所属包名
        append(
            "PKGS=" + D + "(dumpsys account 2>/dev/null | grep 'Account {' " +
                    "| sed 's/.*type=//; s/}.*//' | sort -u); "
        )
        append("echo DPDO_ACCT_PKGS=" + D + "PKGS; ")
        append("FROZEN=\"\"; ")
        append("for P in " + D + "PKGS; do ")
        append("  [ -z \"" + D + "P\" ] && continue; ")
        append("  if pm disable-user --user 0 " + D + "P >/dev/null 2>&1; then ")
        append("    FROZEN=\"" + D + "FROZEN " + D + "P\"; echo DPDO_FROZE_OK=" + D + "P; ")
        append("  else echo DPDO_FROZE_SKIP=" + D + "P; fi; ")
        append("done; ")
        append("echo DPDO_FROZEN_LIST=" + D + "FROZEN; ")
        append("sleep 3; ")
        append("OUT3=" + D + "(dpm set-device-owner --user 0 " + D + "COMP 2>&1); RC3=" + D + "?; ")
        append("echo \"" + D + "OUT3\"; ")
        // 无论成败都解冻
        append(
            "for P in " + D + "FROZEN; do pm enable --user 0 " + D +
                    "P >/dev/null 2>&1 && echo DPDO_THAW_OK=" + D + "P; done; "
        )
        append("if [ " + D + "RC3 -eq 0 ]; then echo DPDO_OK; exit 0; else echo DPDO_FAIL; exit 1; fi")
    }
    /** 从「智能激活脚本」输出中解析被冻结的包列表（供异常情况下兜底解冻）。 */
    fun parseFrozenList(output: String?): List<String> {
        val out = output ?: return emptyList()
        return out.lineSequence()
            .filter { it.startsWith("DPDO_FROZE_OK=") }
            .map { it.removePrefix("DPDO_FROZE_OK=").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    // ---------------------------------------------------------------------
    // 3.1 【v1.6.1】新增能力（独立追加，不改动上方任何既有方法）
    //     面向「DP + Shizuku 卡片」的新逻辑：
    //       先试激活 → 失败则按报错「删隐藏 999 用户 + 只冻结非系统账户应用」→ 重试
    // ---------------------------------------------------------------------

    /** 隐藏用户（XSpace / 隐私空间的典型 uid）。 */
    const val HIDDEN_USER_ID = 999

    /**
     * 判定某包是否为「系统应用」——系统包不参与冻结。
     *
     * 规则（保守，宁可不冻也不误冻）：
     *  - `android`（framework 自身）
     *  - `com.android.*`（AOSP 组件，含 `com.android.providers.*` 等）
     *  - `com.google.android.gms` / `com.google.android.gsf` / `com.google.android.backuptransport`
     *    （GMS 三件套：冻结会连带影响系统同步与激活框架）
     *  - 本应用自身（冻结自己会直接终止流程）
     *
     * 其余一律视为用户应用，可冻结。
     */
    fun isSystemPackage(pkg: String, selfPkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == selfPkg) return true
        if (pkg == "android") return true
        if (pkg.startsWith("com.android.")) return true
        if (pkg == "com.google.android.gms") return true
        if (pkg == "com.google.android.gsf") return true
        if (pkg == "com.google.android.backuptransport") return true
        return false
    }

    /**
     * 【v1.6.1】生成「先试激活 → 失败则删 999 隐藏用户 + 冻结非系统账户应用 → 重试」脚本。
     *
     * 与 [buildSmartActivateScript]（v1.5.0）的差异：
     *
     * | 项 | 旧（buildSmartActivateScript） | 新（本方法） |
     * |---|---|---|
     * | 前置预检 | 账户数 > 0 或 用户数 > 1 → **直接失败退出** | **不再直接退出**，改为尝试自救 |
     * | 隐藏账号 | 不处理 | `pm remove-user 999` 删除隐藏用户 |
     * | 冻结范围 | 账户所属全部包（可能含系统包） | **仅非系统包**（[isSystemPackage] 同规则） |
     * | 解冻 | 无论成败都解冻 | 同上（冻结是临时手段，激活后必须恢复） |
     *
     * 流程：
     * ```
     * ① 前置统计（账户数 / 用户数 / 是否存在 999）
     * ② 直接尝试 dpm set-device-owner
     * ③ 失败 → 删除隐藏用户 999（若存在）→ 等待
     * ④ 重新统计账户 → 冻结「非系统」的账户所属应用 → 等待 → 重试
     * ⑤ 无论成败：解冻全部被冻结应用
     * ⑥ 输出 DPDO_OK / DPDO_FAIL
     * ```
     *
     * 输出标记（供 UI 解析）：
     * ```
     * DPDO_STAGE=...          阶段进度
     * DPDO_ACCTS=N            账户数
     * DPDO_USERS=N            用户数
     * DPDO_HAS999=yes/no      是否存在隐藏用户 999
     * DPDO_999_REMOVED=yes/no 是否已删除 999
     * DPDO_FROZE_OK=<pkg>     成功冻结的包
     * DPDO_FROZE_SKIP=<pkg>   跳过的系统包
     * DPDO_FROZEN_LIST=...    被冻结包清单（空格分隔）
     * DPDO_REASON=...         失败时的中文原因
     * DPDO_OK / DPDO_FAIL     最终结果
     * ```
     */
    fun buildRescueActivateScript(context: Context): String = buildString {
        val D = Char(36).toString()
        val comp = component(context)
        val self = pkg(context)

        append("COMP=").append(comp).append("; ")
        append("SELF=").append(self).append("; ")

        // ---------- ① 前置统计 ----------
        append("echo DPDO_STAGE=precheck; ")
        append("AC=" + D + "(dumpsys account 2>/dev/null " +
                "| awk '/^[ ]*Accounts:/{print " + D + "2; exit}'); ")
        append("UC=" + D + "(dumpsys user 2>/dev/null " +
                "| awk '/^[ ]*UserInfo[{]/{n++} END{print n+0}'); ")
        append("H999=" + D + "(pm list users 2>/dev/null | grep -c 'UserInfo{999:'); ")
        append("echo DPDO_ACCTS=" + D + "AC; echo DPDO_USERS=" + D + "UC; ")
        append("echo DPDO_HAS999=" + D + "H999; ")

        // ---------- ② 直接尝试激活 ----------
        append("echo DPDO_STAGE=try-default; ")
        append("dpm set-active-admin --user 0 " + D + "COMP >/dev/null 2>&1; ")
        append("OUT2=" + D + "(dpm set-device-owner --user 0 " + D + "COMP 2>&1); RC=" + D + "?; ")
        append("echo \"" + D + "OUT2\"; ")
        append("if [ " + D + "RC -eq 0 ]; then echo DPDO_OK; exit 0; fi; ")

        // ---------- ③ 自救：删除隐藏用户 999 ----------
        append("echo DPDO_STAGE=rescue-remove-999; ")
        append("if [ \"" + D + "H999\" -gt 0 ]; then ")
        append("  R9=" + D + "(pm remove-user 999 2>&1); RC9=" + D + "?; ")
        append("  echo \"" + D + "R9\" | sed 's/^/  /'; ")
        append("  if [ " + D + "RC9 -eq 0 ]; then echo DPDO_999_REMOVED=yes; ")
        append("  else echo DPDO_999_REMOVED=no; fi; ")
        append("  sleep 2; ")
        append("else echo DPDO_999_REMOVED=skip; fi; ")

        // ---------- ④ 冻结「非系统」的账户所属应用 ----------
        append("echo DPDO_STAGE=rescue-freeze; ")
        append("PKGS=" + D + "(dumpsys account 2>/dev/null | grep 'Account {' " +
                "| sed 's/.*type=//; s/}.*//' | sort -u); ")
        append("echo DPDO_ACCT_PKGS=" + D + "PKGS; ")
        append("FROZEN=\"\"; ")
        append("for P in " + D + "PKGS; do ")
        append("  [ -z \"" + D + "P\" ] && continue; ")
        // —— 系统包判定（与 Kotlin 侧 isSystemPackage 完全同规则）——
        append("  case \"" + D + "P\" in ")
        append("    android|" + D + "SELF|com.android.*|com.google.android.gms" +
                "|com.google.android.gsf|com.google.android.backuptransport) ")
        append("      echo DPDO_FROZE_SKIP=" + D + "P; continue;; ")
        append("  esac; ")
        append("  if pm disable-user --user 0 " + D + "P >/dev/null 2>&1; then ")
        append("    FROZEN=\"" + D + "FROZEN " + D + "P\"; echo DPDO_FROZE_OK=" + D + "P; ")
        append("  else echo DPDO_FROZE_FAIL=" + D + "P; fi; ")
        append("done; ")
        append("echo DPDO_FROZEN_LIST=" + D + "FROZEN; ")
        append("sleep 3; ")

        // ---------- ⑤ 重试 ----------
        append("echo DPDO_STAGE=retry; ")
        append("OUT3=" + D + "(dpm set-device-owner --user 0 " + D + "COMP 2>&1); RC3=" + D + "?; ")
        append("echo \"" + D + "OUT3\"; ")

        // ---------- ⑥ 无论成败都解冻 ----------
        append("for P in " + D + "FROZEN; do pm enable --user 0 " + D +
                "P >/dev/null 2>&1 && echo DPDO_THAW_OK=" + D + "P; done; ")

        append("if [ " + D + "RC3 -eq 0 ]; then echo DPDO_OK; exit 0; ")
        append("else echo DPDO_FAIL; exit 1; fi")
    }

    /** 解析 `DPDO_HAS999` / `DPDO_999_REMOVED` 等布尔标记。 */
    fun parseYesNo(output: String?, key: String): Boolean? {
        val out = output ?: return null
        val v = out.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            .orEmpty()
        return when (v) {
            "yes" -> true
            "no" -> false
            else -> null
        }
    }

    /** 从脚本输出解析账户数 / 用户数。 */
    fun parseIntMark(output: String?, key: String): Int? =
        (output ?: "")
            .lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?.toIntOrNull()

    // ---------------------------------------------------------------------
    // 3.2 【v1.6.1】供「电脑激活」卡片复制的纯 adb 指令
    // ---------------------------------------------------------------------

    /**
     * 【v1.6.7 重做】生成卡片 2「非 ADB 直连激活」的**完整指令序列**（供弹窗查看 / 复制）。
     *
     * ## 序列（与真实链路逐步对应，可整段粘贴执行）
     *
     * ```sh
     * settings put secure user_setup_complete 0
     * sleep 1
     * cmd role set-bypassing-role-qualification true
     * cmd role add-role-holder --user 0 android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>
     * cmd role set-bypassing-role-qualification false
     * # 在应用内点击「激活」
     * settings put secure user_setup_complete 1
     * ```
     *
     * 顺序按用户要求固定为：**先置 0 → 再 DP 授予与其余激活指令 → 最后恢复 1**。
     * （应用内等价操作：先点「第一步：准备」= 前两行，再点「激活」= 中间段，
     *  `finally` 自动恢复 = 最后一行。）
     *
     * - ① 打开「开机向导」硬闸：`settings put secure` 受 `WRITE_SECURE_SETTINGS` 保护
     *   （protectionLevel `signature|privileged|development`），应用自身持不到，
     *   **必须经 Shizuku（shell uid 2000）执行**（真机实测 shell 可写）；
     *   随后 `sleep 1` 等 provider 落库，避免下一步读到旧值；
     * - ② 授予 DP Role：`MANAGE_PROFILE_AND_DEVICE_OWNERS` 的来源，非 ADB 分支的授权前提；
     * - ③ 真正的激活：`forceUpdateUserSetupComplete → setActiveAdmin → setDeviceOwner`
     *   全部是反射调用，**只能由应用自身发起**（非 ADB 身份），shell 无法代替，
     *   所以这一步在 App 内点击「激活」完成；
     * - ④ 恢复：无论成功或失败都必须执行，否则三键导航的「主页 / 最近任务」键会持续失灵。
     *   （卡片 2 的激活流程已在 `finally` 中自动完成这一步，手动执行仅作补救。）
     *
     * ## 与旧版（v1.6.1）的区别
     *
     * 旧版是**电脑端 adb** 指令，且注释写着「请先在设置 → 账户中移除全部账户」——
     * 与本卡「无需清账户」的语义完全相反（清账户是 ADB 分支的要求，本卡走非 ADB 分支、
     * `hasIncompatibleAccountsOrNonAdbNoLock` 首句即 `if (!isAdb) return true;`，根本不读账户）。
     * 新版全部走 Shizuku，且不含任何账户操作。
     */
    fun buildPcAdbCommands(context: Context): String = buildString {
        val key = "user_setup_complete"
        val pkgName = pkg(context)
        // ① 电脑端：准备（打开闸 + 授予 DP Role）
        //
        // 【v1.9.0 重做】全部改为电脑端可直接执行的 `adb shell ...` 指令，
        // 并拆成 ①/②/③ 三段，避免用户「整段粘贴」时把 ③ 的恢复行提前执行
        // （那会在激活之前就 user_setup_complete=1，向导闸被关回，激活必然失败）。
        append("# ① 在电脑上执行（准备：打开向导闸 + 授予 DP Role）\n")
        append("adb shell settings put secure ").append(key).append(" 0\n")
        append("adb shell cmd role set-bypassing-role-qualification true\n")
        append("adb shell cmd role add-role-holder --user 0 ")
            .append(NewPermissionPaths.ROLE_DEVICE_POLICY_MANAGEMENT)
            .append(' ').append(pkgName).append('\n')
        append("adb shell cmd role set-bypassing-role-qualification false\n")
        append('\n')
        // ② 应用自身完成激活（非 ADB 身份，电脑端无法代替）
        append("# ② 回到手机：在本卡片点「激活」\n")
        append("#    （该步必须由应用自身发起：forceUpdateUserSetupComplete → setActiveAdmin → setDeviceOwner 均为反射调用）\n")
        append('\n')
        // ③ 恢复（无论成功或失败都必须执行）
        append("# ③ 激活结束后，回到电脑执行恢复（务必执行，否则三键导航的「主页 / 最近任务」键会持续失灵）\n")
        append("adb shell settings put secure ").append(key).append(" 1\n")
    }


    /** 生成「撤销 DO（回退为设备管理员或彻底移除）」的脚本。 */
    fun buildDeactivateScript(context: Context): String = buildString {
        val D = Char(36).toString()
        val comp = component(context)
        append("COMP=").append(comp).append("; ")
        append(
            "OUT=" + D + "(dpm remove-active-admin --user 0 " + D + "COMP 2>&1); RC=" + D + "?; "
        )
        append("echo DPDO_REVOKE_RC=" + D + "RC; echo DPDO_REVOKE_OUT=" + D + "OUT; ")
        append(
            "if [ " + D + "RC -eq 0 ]; then echo DPDO_REVOKE_OK; exit 0; " +
                    "else echo DPDO_REVOKE_FAIL; exit 1; fi"
        )
    }

    // ---------------------------------------------------------------------
    // 4. 失败原因翻译（AOSP 错误字符串 → 可执行中文提示）
    // ---------------------------------------------------------------------

    /**
     * 把 `dpm set-device-owner` 的失败输出翻译成可执行的中文提示。
     *
     * 依据 AOSP `computeProvisioningErrorStringLocked`（L11002+）的原文案：
     *  - `already set`        → 已是 DO
     *  - `already has a profile owner` → 该用户已有 PO
     *  - `already set-up`     → 设备已完成开机向导（非 ADB 路径限制）
     *  - `several users`      → 存在第二用户
     *  - `some accounts`      → 存在账户
     *  - `already paired`     → 设备已配对
     */
    fun explainFailure(output: String?): String? {
        val text = output ?: return null
        if (!text.contains("DPDO_FAIL") && !text.contains("DPDO_REVOKE_FAIL")) return null

        val raw = text.substringAfter("DPDO_DO_OUT=", "")
            .substringBefore('\n')
            .trim()
            .ifBlank { text.substringAfter("DPDO_REVOKE_OUT=", "").substringBefore('\n').trim() }

        return when {
            raw.contains("already set-up", ignoreCase = true) ->
                "设备已完成开机向导，且当前存在账户或第二用户。请先删除全部账户" +
                        "（设置 → 账户，含系统账户可直接退出），并确保没有工作资料/克隆/隐私空间，" +
                        "然后重试。若刚删除账户，请等待约 10 秒让系统重新统计后再试。"

            raw.contains("some accounts", ignoreCase = true) ->
                "设备上仍有账户存在。请在「设置 → 账户」中删除全部账户后重试" +
                        "（删除后需等待约 10 秒让系统重新统计）。"

            raw.contains("several users", ignoreCase = true) ->
                "设备上存在多个用户或工作资料/克隆/隐私空间。请先将额外用户全部删除，仅保留机主，然后重试。"

            raw.contains("already has a profile owner", ignoreCase = true) ->
                "当前用户已被设为资料所有者（PO）。请先在应用内解除资料所有者身份，再重试。"

            raw.contains("device owner is already set", ignoreCase = true) ->
                "设备已经是设备所有者状态。"

            raw.contains("already paired", ignoreCase = true) ->
                "设备已与手表等设备配对，无法再设设备所有者。请解除配对后重试。"

            raw.contains("not system user", ignoreCase = true) ->
                "目标用户不是 User 0。设备所有者只能在机主（User 0）上设置。"

            raw.contains("not running", ignoreCase = true) ->
                "目标用户未运行。请切换到机主用户后重试。"

            raw.contains("Permission Denial", ignoreCase = true) ||
                    raw.contains("SecurityException", ignoreCase = true) ->
                "权限被拒绝：请确认 Shizuku 正在运行且已授权本应用（shell 身份执行 dpm 命令）。"

            raw.isNotBlank() -> "激活失败：$raw"

            else -> null
        }
    }
}
