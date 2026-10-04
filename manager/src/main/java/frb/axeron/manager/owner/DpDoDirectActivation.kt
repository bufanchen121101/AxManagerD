package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import frb.axeron.server.util.Logger

/**
 * `DP` 差异化激活 Device Owner —— **非 ADB 分支**（应用自身身份直连系统服务）。
 *
 * ## 与 [DpDoEscalation] 的本质区别
 *
 * [DpDoEscalation] 走的是**纯 ADB 路径**：用 Shizuku 以 shell uid(2000) 执行
 * `dpm set-active-admin` / `dpm set-device-owner`。而 `isAdb()` = `isShellUid() || isRootUid()`，
 * 因此它命中 `DevicePolicyManagerService#setDeviceOwner` 的 `if (isAdb)` 分支：
 *
 * ```
 * hasUserSetupCompleted()            ← 闸 ①（向导已完成 → 拒绝）
 * nonTestNonPrecreatedUsersExist()   ← 闸 ②（存在第二用户 → 拒绝）
 * hasIncompatibleAccountsOrNonAdb    ← 闸 ③（存在账户 → 拒绝）★必须清账户★
 * ```
 *
 * 本模块走**非 ADB 分支**：用应用自身 uid 直接调用 binder 接口，`isAdb()=false`，命中 else 分支：
 *
 * ```java
 * // DevicePolicyManagerService#setDeviceOwner（A13/A15/A16/A17 逐版本核对，语义完全一致）
 * } else {
 *     if ((!isHeadlessSystemUserMode || isHeadlessModeAffiliated)
 *             && deviceOwnerUserId != UserHandle.USER_SYSTEM) {
 *         return STATUS_NOT_SYSTEM_USER;
 *     }
 *     if (hasUserSetupCompleted(ensureSetUpUser)) {      // ←★唯一硬闸★
 *         return STATUS_USER_SETUP_COMPLETED;
 *     }
 *     return STATUS_OK;                                  // ←★完全不读账户★
 * }
 * ```
 *
 * **`hasIncompatibleAccountsOrNonAdb` 只在 `if (isAdb)` 块内被读取**，非 ADB 分支根本不碰它。
 *
 * ## 完整链路（A13→A17 五版本一致，取证见项目文档）
 *
 * ```
 * 前置：Shizuku 已授权（仅用于第 1/2 步的 shell 身份）
 *
 * 第 1 步  cmd role set-bypassing-role-qualification true          （shell，已由 NewPermissionPaths 覆盖）
 * 第 2 步  cmd role add-role-holder <DP_ROLE> <pkg> --user 0        （shell）
 *          ★ 本应用获得 MANAGE_PROFILE_AND_DEVICE_OWNERS（protectionLevel=signature|role 的 role 位）
 *
 * 第 3 步  反射调 DevicePolicyManager.forceUpdateUserSetupComplete(0)，**应用自身身份**
 *          ★ Preconditions.checkCallAuthorization(hasCallingOrSelfPermission(
 *                MANAGE_PROFILE_AND_DEVICE_OWNERS)) → 第 2 步已满足
 *          ★ 【v1.6.5 更正】该方法的参数是 **userId**，不是「完成状态」。
 *            实现体（A13→A16 逐版本核对，语义一致）为：
 *              boolean isUserCompleted = settingsSecureGetIntForUser(
 *                      Settings.Secure.USER_SETUP_COMPLETE, 0, userId) != 0;
 *              policy.mUserSetupComplete = isUserCompleted;
 *            —— 它把 `Settings.Secure.USER_SETUP_COMPLETE` 的**真实值**搬进内存，
 *            **不是**把内存态压成 false。因此它**不能**用来绕开 setup 闸：
 *            在向导已完成的设备上，它反而把 mUserSetupComplete 确认为 true。
 *            它在本流程中仅用于「同步内存态与 Settings」，不承担绕闸职责。
 *
 * 第 4 步  反射调 DevicePolicyManager.setActiveAdmin(self, true)，**应用自身身份**
 *          ★ 需要 MANAGE_DEVICE_ADMINS（DP Role 也已授予）
 *
 * 第 5 步  反射调 DevicePolicyManager.setDeviceOwner(...)，**应用自身身份**
 *          ★ 【v1.6.5 跨版本修复】该方法的签名在 AOSP 上**发生过变更**：
 *              Android 13      : setDeviceOwner(ComponentName, @Nullable String ownerName, int userId)
 *              Android 14 / 15 / 16 : setDeviceOwner(ComponentName, int userId)
 *            旧实现写死三参数反射，在 A14+ 会直接 NoSuchMethodException。
 *            现改为**按 SDK 选签名 + 双签名回退**，五版本均可命中。
 *          ★ enforceCanSetDeviceOwnerLocked：非 ADB 时凭 MANAGE_PROFILE_AND_DEVICE_OWNERS 放行
 *          ★ 非 ADB 分支只查 hasUserSetupCompleted → **唯一硬闸**
 *            （已完成向导的设备上，需先用 shell 把 user_setup_complete 置 0
 *            再由本模块的 forceUpdateUserSetupComplete 同步进内存，见下方「向导硬闸」）
 *          → 激活成功，**不清账户、不删隐藏账号**
 *
 * ## ⚠️ 向导硬闸：**可以绕开**（v1.6.6 真机实测更正，旧结论作废）
 *
 * 非 ADB 分支的唯一判据是：
 * ```java
 * // DevicePolicyManagerService#checkDeviceOwnerProvisioningPreConditionLocked
 * } else {
 *     if (deviceOwnerUserId != UserHandle.USER_SYSTEM) return STATUS_NOT_SYSTEM_USER;
 *     if (hasUserSetupCompleted(UserHandle.USER_SYSTEM)) return STATUS_USER_SETUP_COMPLETED;
 *     return STATUS_OK;
 * }
 * ```
 * `hasUserSetupCompleted` 读的是内存态 `DevicePolicyData.mUserSetupComplete`。
 *
 * ### 旧结论（v1.6.5，**错误**）
 *
 * 旧注释断言「没有任何 App 可达的 API 能改写它，实测不可行」——
 * 只考虑了「直接改内存」这一条路，忽略了**换一条路把内存态喂成 false**：
 *
 * ```java
 * // DevicePolicyManager#forceUpdateUserSetupComplete(int userId)  ← 双向赋值
 * boolean isUserCompleted = settingsSecureGetIntForUser(USER_SETUP_COMPLETE, 0, userId) != 0;
 * policy.mUserSetupComplete = isUserCompleted;   // ← 直接覆盖内存态（true / false 都能写）
 * ```
 *
 * 也就是说 **`forceUpdateUserSetupComplete` 本身就是那把「重新加载」的钥匙**：
 * 它不做单向提升（对比 `updateUserSetupCompleteAndPaired()` 只把 false 升成 true），
 * 而是**把 `Settings.Secure.USER_SETUP_COMPLETE` 的当前值原样搬进内存**。
 *
 * ### 正确链路（真机 vivo A13 铁证，`setDeviceOwner` 返回 true）
 *
 * ```
 * ① shell（Shizuku，持 WRITE_SECURE_SETTINGS）: settings put secure user_setup_complete 0
 *    —— 应用自身是 untrusted_app，写不了 Settings.Secure，必须借 shell 身份
 * ② 应用自身: forceUpdateUserSetupComplete(0)  → 内存态 mUserSetupComplete = false
 * ③ 应用自身: setActiveAdmin → setDeviceOwner → STATUS_OK（非 ADB 分支完全不读账户）
 * ④ shell: settings put secure user_setup_complete 1   —— 收尾恢复，导航键立刻回正常
 * ```
 *
 * ⚠️ 只做 ① 不做 ② 无效：ADB 分支（`dpm set-device-owner`）读的同样是内存态，
 * 实测「1 账户 + Settings=0」下重跑 dpm 仍报 `already some accounts`。
 *
 * ### 副作用（必须在界面上如实告知）
 *
 * `user_setup_complete=0` 期间系统认为设备未完成向导，会启用「防逃离设置向导」：
 * **三键导航的 Home / 最近任务键失效**（返回键可用；全屏手势导航不受影响）。
 * 恢复为 1 后立即正常 —— 因此本模块的调用方必须在 `finally` 中恢复。
 *
 * **结论：非 ADB 直连在「已完成开机向导」的设备上同样可用**，
 * 代价是流程进行中需要临时把 `user_setup_complete` 置 0 数秒（并承担导航键短暂失灵）。
 * 本模块通过 [isSetupCompleted] 供调用方判断「是否走这条补救路径」，
 * 并直接给出 [Stage.SETUP_COMPLETED] 让调用方决定是否重试。
 *
 * ## 隐藏 API 说明
 *
 * `forceUpdateUserSetupComplete` 标注 `@TestApi`，`setDeviceOwner` 标注 `@SystemApi`，
 * 均属 `hiddenapi` 名单。本项目 `AxeronApplication` 的 companion `init` 已执行
 * `HiddenApiBypass.setHiddenApiExemptions("")`（SDK≥28 全量豁免），故**反射调用可靠**，
 * 不依赖 LSPatch。所有调用点均用 `@SuppressLint` 语义的反射 + try/catch，编译期无隐藏 API 引用。
 *
 * ## 设计约束
 *
 * 本文件为**独立新增模块**，不改动 [DpDoEscalation] / [NewPermissionPaths] / [DeviceOwnerState]
 * 任何既有成员，避免污染公共路径（失败时调用方可原样回退到 [DpDoEscalation] 的 ADB 路径）。
 */
object DpDoDirectActivation {

    private val LOGGER = Logger("DpDoDirectActivation")

    /** `DEVICE_POLICY_MANAGEMENT` Role（与 [NewPermissionPaths] 同源，此处独立声明避免耦合）。 */
    private const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    /** DPM 的 `STATUS_OK`（`setDeviceOwner` 成功）。 */
    private const val STATUS_OK = 0

    /**
     * 兜底错误码：调用被系统拒绝但**未能从异常文案反查出具体 `STATUS_*`**。
     *
     * 取一个 `computeProvisioningErrorString` 未使用的值，避免与真实码混淆；
     * [explainStatusCode] 会给出「系统拒绝但原因未知」的说明。
     */
    private const val STATUS_REJECTED_GENERIC = -2

    /** 激活结果细分，供 UI 给出精确提示。 */
    enum class Stage {
        /** 前置权限（DP Role / MAPDO）未就绪。 */
        PERMISSION_MISSING,

        /**
         * 【v1.6.5 新增 / v1.6.6 语义更新】设备已完成开机向导 —— 非 ADB 分支的硬闸尚未打开。
         *
         * 依据 `DevicePolicyManagerService#checkDeviceOwnerProvisioningPreConditionLocked`
         * 的 `else` 分支：`hasUserSetupCompleted(USER_SYSTEM)` 为真时直接返回
         * `STATUS_USER_SETUP_COMPLETED`。
         *
         * 【v1.6.6 更正】该闸**可以绕开**：先用 shell 身份执行
         * `settings put secure user_setup_complete 0`（见
         * [buildSetSetupCompleteCommand]），再调用 [activate]（其第 3 步
         * `forceUpdateUserSetupComplete(0)` 会把 Settings 的真实值搬进内存态），
         * 即可放行。真机（vivo A13）实测 `setDeviceOwner` 返回 true。
         *
         * 因此本状态的含义是「**需要调用方先执行补救步骤**」，
         * 而不是「此设备永久不可用」。
         */
        SETUP_COMPLETED,

        /** 第 3 步失败：反射调 forceUpdateUserSetupComplete 出错。 */
        FORCE_SETUP_FAILED,

        /** 第 4 步失败：setActiveAdmin 被拒。 */
        SET_ADMIN_FAILED,

        /** 第 5 步失败：setDeviceOwner 返回了非 STATUS_OK 的 provisioning 错误码。 */
        SET_OWNER_REJECTED,

        /** 第 5 步抛异常（SecurityException / 隐藏 API 拦截等）。 */
        SET_OWNER_THREW,

        /** 全部成功。 */
        OK,
    }

    /** 单次尝试的结果。
     *
     * 命名刻意避开 Kotlin 内置的 `Result`，避免在 `Result<...>` 泛型位置产生歧义。
     */
    data class ActivationResult(
        val stage: Stage,
        /** 是否成功成为 Device Owner。 */
        val success: Boolean,
        /** 可展示的中文说明（成功时为简要说明）。 */
        val message: String,
        /** 过程日志（逐行，供提权界面展示）。 */
        val log: List<String>,
    )

    // ---------------------------------------------------------------------
    // 1. 前置：确认本应用真正持有 DP Role 带来的 MANAGE_PROFILE_AND_DEVICE_OWNERS
    // ---------------------------------------------------------------------

    /**
     * 本应用是否已实际持有 `MANAGE_PROFILE_AND_DEVICE_OWNERS`。
     *
     * ## 判据说明（v1.6.4 更正）
     *
     * 旧注释断言「`checkSelfPermission` 对 role 授予的权限**同样返回 GRANTED**」——
     * **该断言不完整**，实测会导致恒 false 的误判。真实链路（AOSP 15 已逐层核对）：
     *
     * ```
     * ContextImpl#checkSelfPermission(p)
     *   → checkPermission(p, myPid, myUid)
     *     → PermissionManagerServiceImpl#checkPermissionInternal
     *       → UidPermissionState#isPermissionGranted(p)
     * ```
     * 而 `UidPermissionState#isPermissionGranted` 的实现是：
     * ```java
     * public boolean isPermissionGranted(@NonNull String name) {
     *     final PermissionState permissionState = getPermissionState(name);
     *     return permissionState != null && permissionState.isGranted();
     * }
     * ```
     * —— **`mPermissions` 表里只有「应用在 manifest 中 `<uses-permission>` 声明过的权限」**。
     * 若 manifest 漏声明 `MANAGE_PROFILE_AND_DEVICE_OWNERS`，则 `PermissionState == null`，
     * 无论 Role 是否授予，`checkSelfPermission` **恒返回 DENIED**。
     *
     * 该权限的 `protectionLevel = signature|role`，role 位授予后系统会**自动**把
     * 已声明的该权限置为 granted（无需 `pm grant`）。所以：
     *  - manifest 必须声明（本文件依赖 `AndroidManifest.xml` 中已补的声明）；
     *  - 声明之后，本判据即可靠。
     *
     * @param shellEvidence 可选：由调用方用 shell 身份读到的 `dumpsys package` 文本
     *   （见 [NewPermissionPaths.buildQueryMapdoScript]）。当应用侧权限表因 ROM 差异
     *   未能及时刷新时，用 `granted=true` 文本作为**并列判据**，避免误报未授权。
     *   传 null（默认）时只走 `checkSelfPermission`，行为与旧版完全一致。
     */
    fun hasManageProfileAndDeviceOwners(
        context: Context,
        shellEvidence: String? = null,
    ): Boolean = runCatching {
        // 判据 ①：应用侧权限表（manifest 已声明时可靠）
        if (context.checkSelfPermission(PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return@runCatching true
        }
        // 判据 ②：shell 侧 dumpsys 证据（可选，用于 ROM 权限表刷新延迟的场景）
        if (shellEvidence != null &&
            shellEvidence.contains(PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS) &&
            shellEvidence.contains("granted=true")
        ) {
            return@runCatching true
        }
        false
    }.getOrDefault(false)

    /** MAPDO 权限名（复用 [NewPermissionPaths] 的常量，避免两处字面量漂移）。 */
    private val PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS =
        NewPermissionPaths.PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS

    /**
     * 【v1.6.5】设备是否已完成开机向导。
     *
     * ## 判据来源
     *
     * `DevicePolicyManagerService#checkDeviceOwnerProvisioningPreConditionLocked`
     * 的非 ADB 分支唯一硬闸是 `hasUserSetupCompleted(UserHandle.USER_SYSTEM)`，
     * 其值取自 `DevicePolicyData.mUserSetupComplete`，初值来自
     * `Settings.Secure.USER_SETUP_COMPLETE`（`1` = 已完成）。
     *
     * 本函数读取的正是同一个 `Settings.Secure` 键，因此在**同一次开机周期**内
     * 与 DPM 内存态一致。用户手动改过该 Settings 的极端情况下可能有偏差，
     * 此时仍以第 5 步的真实错误码为准（[explainStatusCode] 的 `1` 分支）。
     *
     * ## 如何绕开（v1.6.6 实测更正）
     *
     * 该闸读取的是 `DevicePolicyData` 里的内存布尔值。虽然 AOSP 没有暴露
     * 「把内存态直接置 false」的 API，但 `forceUpdateUserSetupComplete(userId)`
     * 是一条**双向**通道 —— 它把 `Settings.Secure.USER_SETUP_COMPLETE` 的当前值
     * 原样搬进内存（见类注释「向导硬闸」）。
     *
     * 所以绕开它只需要两步（顺序不能颠倒）：
     * ```
     * ① shell: settings put secure user_setup_complete 0     ← 借 shell 的 WRITE_SECURE_SETTINGS
     * ② 应用自身: forceUpdateUserSetupComplete(0)             ← 在 [activate] 的第 3 步自动发生
     * ```
     * 完成后内存态即为 false，非 ADB 分支放行。
     *
     * 本函数（读 Settings）与 DPM 内存态在**同一开机周期内**一致，因此调用方
     * 可用它判断「是否还需要执行上面的 ①」—— 返回 true 就说明要补救。
     *
     * ## 为什么用字面量而非 `Settings.Secure.USER_SETUP_COMPLETE`
     *
     * 该常量标注 `@hide`，SDK 的 `Settings.Secure` 里**不存在**该字段
     * （直接引用会 Unresolved reference，实测编译失败）。
     * 其真实值在 AOSP 各版本中恒为 `"user_setup_complete"`，此处用字面量。
     *
     * ## 为什么用 `getString` 而非 `getIntForUser`
     *
     * `Settings.Secure.getIntForUser` 同样标注 `@hide`（不在公开 SDK 内），
     * 实测报 `Unresolved reference: getIntForUser`。
     * 公开 API 里 `Settings.Secure.getString` 会读取**当前用户**的值 ——
     * 而 DO 激活只可能发生在 User 0（机主），故二者等价。
     * 返回的字符串再自行解析为整数。
     */
    private const val SETTING_USER_SETUP_COMPLETE = "user_setup_complete"

    /**
     * [buildSetSetupCompleteScript] 的回读标记，供 [parseSetupCompleteOutput] 解析。
     * 取一个不会被 `settings` 自身输出干扰的独特前缀。
     */
    private const val SETUP_READBACK_TAG = "AXMD_SETUP_NOW="

    fun isSetupCompleted(context: Context): Boolean = runCatching {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            SETTING_USER_SETUP_COMPLETE,
        )
        // 读不到时按「未完成」处理，让后续步骤给出真实的系统错误码，
        // 而不是在此处武断地判定「已完成」把用户挡在门外。
        (raw?.trim()?.toIntOrNull() ?: 0) != 0
    }.getOrDefault(false)

    /**
     * 【v1.6.6 新增】构造「写入 / 恢复」`Settings.Secure.USER_SETUP_COMPLETE` 的 shell 命令。
     *
     * ## 为什么必须借 shell 身份
     *
     * 该 Settings 键的写入受 `WRITE_SECURE_SETTINGS` 保护，其 protectionLevel 为
     * `signature|privileged|development`，**应用自身（untrusted_app）拿不到**；
     * 而本模块的两个调用角色里，只有 shell(uid 2000) 天然持有它。
     * 因此本函数只负责**生成命令文本**，实际执行由调用方经
     * `ActivateViewModel#execViaShizuku`（Shizuku 通道）完成 —— 本文件保持
     * 「不直接依赖 Shizuku」的既有约束，便于单元化阅读与复用。
     *
     * ## 用法（见 `ActivateViewModel#runElevateDirectFlow`）
     *
     * ```
     * 默认方式激活失败且 isSetupCompleted == true 时：
     *   execViaShizuku(buildSetSetupCompleteCommand(0))   // 临时开门
     *   … 重跑 activate(context, mapdoPrecheck = false) …
     * finally:
     *   execViaShizuku(buildSetSetupCompleteCommand(1))   // 恢复，导航键随即正常
     * ```
     *
     * ⚠️ 置 0 期间三键导航的 Home / 最近任务键会失效（返回键可用，手势导航不受影响），
     * 界面必须如实提示用户，并保证在 `finally` 中恢复为 1。
     *
     * @param value 0 = 临时标记为「未完成向导」；1 = 恢复正常（设备已完成向导）。
     */
    fun buildSetSetupCompleteCommand(value: Int): String =
        "settings put secure $SETTING_USER_SETUP_COMPLETE $value"

    /**
     * 【v1.6.7 新增】生成「写入 / 恢复」`Settings.Secure.USER_SETUP_COMPLETE` 的
     * **完整 shell 脚本**：写入 → 等待落库（`sleep 1`）→ 回读并回显结果。
     *
     * ## 为什么需要它（真机排障结论）
     *
     * 旧的 [buildSetSetupCompleteCommand] 只发一条 `settings put` 就立刻继续下一步，
     * 存在两个实测风险：
     *  - **无等待**：命令返回后 provider 尚未把值对外可见，紧随其后的读取 / 激活
     *    可能仍看到旧值；
     *  - **无从确认**：只看退出码并不可靠（部分 ROM 退出码为 0 但值未落库），
     *    而「应用侧再读一次」在同一进程内也可能读到未刷新的一致性问题。
     *
     * 因此改为把三步串成一个脚本，由调用方经 Shizuku（shell 身份）**整段执行**：
     * ```sh
     * settings put secure user_setup_complete <value>; sleep 1; \
     *   echo AXMD_SETUP_NOW=$(settings get secure user_setup_complete 2>/dev/null)
     * ```
     * 调用方用 [parseSetupCompleteOutput] 从**命令自身的输出**里解析
     * `AXMD_SETUP_NOW=<值>`，即可确认设置是否真的落库。
     *
     * 该「回显判定」模式与 `NewPermissionPaths#buildGrantDpScript`
     * （`echo DP_GRANT_OK` / `echo DP_GRANT_FAIL`）保持一致，便于复用既有解析习惯。
     *
     * ## 身份约束（不可省略）
     *
     * 该脚本**必须**经 Shizuku 以 shell(uid 2000) 身份执行：`settings put secure`
     * 受 `WRITE_SECURE_SETTINGS` 保护（protectionLevel
     * `signature|privileged|development`），应用自身（untrusted_app）持不到；
     * 真机实测 shell 身份可写（`settings put` 后回读即为目标值）。
     * 本函数只负责**生成脚本文本**，不直接依赖 Shizuku。
     *
     * @param value 0 = 临时标记为「未完成向导」；1 = 恢复正常（设备已完成向导）。
     */
    fun buildSetSetupCompleteScript(value: Int): String = buildString {
        // shell 变量前缀用字符码生成（36 = 0x24），源码中不出现反斜杠与转义符，
        // 与 NewPermissionPaths 的既有写法保持一致，避免编辑链吃掉转义。
        val D = Char(36).toString()
        append("settings put secure ").append(SETTING_USER_SETUP_COMPLETE)
            .append(' ').append(value).append("; ")
        // 等待 provider 落库后再回读，避免「刚写完就读」拿到旧值
        append("sleep 1; ")
        append("echo ").append(SETUP_READBACK_TAG).append(D)
            .append("(settings get secure ").append(SETTING_USER_SETUP_COMPLETE)
            .append(" 2>/dev/null)")
    }

    /**
     * 【v1.7.0 新增】生成**只读**的 `Settings.Secure.USER_SETUP_COMPLETE` 回读脚本。
     *
     * ## 为什么必须走 shell 身份
     *
     * 该键虽可被公开 API `Settings.Secure.getString` 读取，但**仅限同一用户且
     * 该键对调用方可见**；实测本应用（untrusted_app）在向导已完成的设备上
     * 读不到该键（返回 null）—— [isSetupCompleted] 因此在应用侧恒为 false，
     * v1.6.9 之前的「用应用侧读值当开关」正是「置 0 / 恢复 1 整段被跳过」的根因。
     *
     * 因此 UI 侧判定「闸是否已打开」**必须经 Shizuku 用 shell(uid 2000) 身份读**，
     * 本函数即为此生成纯只读脚本（不写入任何值，可安全反复执行）：
     * ```sh
     * echo AXMD_SETUP_NOW=$(settings get secure user_setup_complete 2>/dev/null)
     * ```
     * 调用方用 [parseSetupCompleteOutput] 解析回读值。
     *
     * 本函数只负责**生成脚本文本**，不直接依赖 Shizuku（与既有约定一致）。
     */
    fun buildReadSetupCompleteScript(): String = buildString {
        val D = Char(36).toString()
        append("echo ").append(SETUP_READBACK_TAG).append(D)
            .append("(settings get secure ").append(SETTING_USER_SETUP_COMPLETE)
            .append(" 2>/dev/null)")
    }

    /**
     * 从 [buildSetSetupCompleteScript] 的执行输出里解析回读值。
     *
     * @return `0` / `1`；找不到 `AXMD_SETUP_NOW=` 标记或解析失败时返回 null
     *   （调用方应按「未确认生效」处理，不得当作成功）。
     */
    fun parseSetupCompleteOutput(output: String?): Int? =
        output?.lineSequence()
            ?.firstOrNull { it.startsWith(SETUP_READBACK_TAG) }
            ?.substringAfter(SETUP_READBACK_TAG)
            ?.trim()
            ?.toIntOrNull()

    /** 本应用管理组件（与 manifest 注册的 receiver 对应）。 */
    private fun selfComponent(context: Context): ComponentName =
        DeviceOwnerState.admin

    // ---------------------------------------------------------------------
    // 2. 主流程
    // ---------------------------------------------------------------------

    /**
     * 以**应用自身身份（非 ADB）**完成 Device Owner 激活。
     *
     * ⚠️ 必须在**非主线程**调用（会阻塞 binder 调用）。调用方通常已在 `Dispatchers.IO`。
     *
     * ⚠️ 本方法**假定** DP Role 已由 [NewPermissionPaths.buildGrantDpScript] 授予完成。
     * 若未授予，会在 [Stage.PERMISSION_MISSING] 直接返回，不会做任何破坏性操作。
     *
     * @param forceSetupComplete true 时，在调 setDeviceOwner 前执行
     *   `forceUpdateUserSetupComplete(0)` 以反向覆盖内存中的 `mUserSetupComplete`。
     *   **这是绕开非 ADB 分支唯一硬闸的关键步骤**，默认 true。
     * @param mapdoPrecheck 是否在内部重做一次 MAPDO 前置检查（默认 true，保持既有行为）。
     *   调用方若已用 [hasManageProfileAndDeviceOwners] 的**双通道判据**
     *   （含 shell 侧 dumpsys 证据）确认过权限已落地，可传 false 跳过此处的
     *   单判据复查 —— 否则在「manifest 已声明但应用侧权限表刷新延迟」的 ROM 上，
     *   这里会以 `checkSelfPermission == DENIED` 误判为 PERMISSION_MISSING。
     *
     * @param shellSetupValue 【v1.8.0 新增】由调用方经 Shizuku（shell 身份）回读到的
     *   `user_setup_complete` 值（见 [parseSetupCompleteOutput]）。
     *
     *   **为什么必须由调用方传入**：本模块的向导预检原先用 [isSetupCompleted]
     *   在应用侧自行读取该键，但该键标注 `@hide`，普通应用（untrusted_app）
     *   读不到 —— 实测恒返回 null、恒判定为 false，导致预检**永远放行**，
     *   「未置 0 就激活」的误用只能等到第 5 步才以异常暴露。
     *   改由调用方传入 shell 身份的回读值后，预检才真正具备拦截能力。
     *
     *   取值为：
     *   - `0`：闸已打开（未完成向导）→ 放行；
     *   - `1`：闸未打开（已完成向导）→ 在动手前直接返回 [Stage.SETUP_COMPLETED]；
     *   - `null`（默认）：调用方未提供，退回旧行为（只打日志、不拦截），
     *     保持既有调用点语义不变。
     */
    fun activate(
        context: Context,
        forceSetupComplete: Boolean = true,
        mapdoPrecheck: Boolean = true,
        shellSetupValue: Int? = null,
    ): ActivationResult {
        val log = mutableListOf<String>()
        // 显式声明为函数类型变量，避免作为参数传递时类型推断失败
        // （局部函数引用传给高阶参数在 Kotlin 里会被推成 Unit）。
        val note: (String) -> Unit = { line ->
            LOGGER.i(line)
            log += line
        }

        val pkg = context.packageName
        val admin = selfComponent(context)
        note("包名：$pkg")
        note("管理组件：${admin.flattenToShortString()}")
        note("调用身份：应用自身 uid=${Process.myUid()}（isAdb=false → 走非 ADB 分支）")

        // ---------- 前置：DP Role 是否已带来 MAPDO ----------
        // 【v1.6.4】mapdoPrecheck=false 时跳过本段单判据复查（调用方已用双通道判据确认）。
        // 注意：此处不能直接信任「跳过」——`forceUpdateUserSetupComplete` /
        // `setDeviceOwner` 最终仍由系统按真实权限放行，若实际未落地会在第 3/5 步
        // 以 SecurityException 明确失败，不会静默成功。
        if (mapdoPrecheck && !hasManageProfileAndDeviceOwners(context)) {
            note("x 未持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS")
            note("  请先完成 DP Role 授予（cmd role add-role-holder ...）")
            return ActivationResult(
                stage = Stage.PERMISSION_MISSING,
                success = false,
                message = "本应用尚未获得设备策略管理角色（DP Role）带来的权限，" +
                        "无法使用非 ADB 直连路径。请先授予 DP Role 后重试。",
                log = log,
            )
        }
        if (mapdoPrecheck) {
            note("v 已持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS（DP Role 生效）")
        } else {
            note("- 已由调用方以双通道判据确认 MAPDO 落地，跳过内部单判据复查")
        }

        // ---------- 【v1.6.5 新增 / v1.6.7 语义更新 / v1.8.0 判据修正】向导状态预检 ----------
        //
        // 非 ADB 分支的唯一硬闸是 hasUserSetupCompleted(USER_SYSTEM)。
        //
        // 【v1.6.7 调用约定】**本函数的调用方负责在调用前把 Settings 置 0**
        // （见 ActivateViewModel#runElevateDirectFlow 的 ④ 段：激活前先经 Shizuku
        // 执行 settings put secure user_setup_complete 0）。因此正常路径下
        // 下面这个预检应当读到 0 直接放行。
        //
        // 预检仍保留的意义（两重）：
        //   ① 兜底防御：万一调用方漏了前置置 0，这里给用户**准确的失败原因**，
        //      而不是等到第 5 步收到一个被反射包成 InvocationTargetException 的
        //      IllegalStateException；
        //   ② 说明性输出：把「需先置 0」这个前置条件写进日志，便于用户自查。
        //
        // ⚠️ 顺序要点：本预检位于第 3 步 forceUpdateUserSetupComplete **之前**，
        //    所以若此处读到 1 就 return，第 3 步不会执行、内存态不会被刷新 ——
        //    这正是「只在失败后补救」的旧实现会失败的原因。调用方必须先置 0。
        //
        // 【v1.8.0 判据修正 — 关键】旧实现此处调用 [isSetupCompleted]（应用自身
        // 读 Settings），但该键标注 `@hide`，普通应用读不到 —— 恒 null → 恒 false，
        // **预检永远放行**，上面的「兜底防御」实际从未生效。
        // 现改为优先采用调用方经 Shizuku 回读到的 [shellSetupValue]：
        //   - 传了值 → 以该值为准（0 = 闸已打开 / 1 = 闸未打开）；
        //   - 未传值 → 退回旧行为（仅打日志、不拦截），不改变既有调用点语义。
        note("")
        val appSideSetup = isSetupCompleted(context)
        val effectiveSetupValue: Int? = shellSetupValue
        val setupCompleted: Boolean = when {
            effectiveSetupValue != null -> effectiveSetupValue != 0
            else -> appSideSetup
        }
        note("向导状态：USER_SETUP_COMPLETE=" +
                (effectiveSetupValue?.toString() ?: "未确认") +
                "（判据来源：${if (effectiveSetupValue != null) "调用方 shell 回读" else "应用侧读取，可能不可靠"}）" +
                "（${if (setupCompleted) "已完成 → 需调用方先临时置 0 再重试" else "未完成 → 可直接尝试直连"}）")
        if (effectiveSetupValue == null) {
            note("  ! 调用方未提供 shell 回读值：预检不具备拦截能力（应用侧读 @hide 键恒为 false）")
            note("    实际是否放行将以第 5 步的系统返回为准")
        }
        if (setupCompleted) {
            note("x 设备已完成开机向导，非 ADB 分支的硬闸尚未打开")
            note("  依据：DevicePolicyManagerService#checkDeviceOwnerProvisioningPreConditionLocked")
            note("        非 ADB 分支在 hasUserSetupCompleted(USER_SYSTEM) 为真时返回 STATUS_USER_SETUP_COMPLETED")
            note("  前置步骤（应由调用方在此之前完成）：")
            note("        ① shell 身份 settings put secure ${SETTING_USER_SETUP_COMPLETE} 0")
            note("        ② 再调用本函数（第 3 步 forceUpdateUserSetupComplete 会把该值同步进内存态）")
            note("        ③ 结束后 settings put secure ${SETTING_USER_SETUP_COMPLETE} 1 恢复")
            note("  副作用：① 期间三键导航的 Home / 最近任务失效（返回键可用，手势导航不受影响）")
            return ActivationResult(
                stage = Stage.SETUP_COMPLETED,
                success = false,
                message = "设备已完成开机向导：非 ADB 直连的唯一硬闸（向导未完成）尚未打开。\n\n" +
                        "补救方式（由提权流程自动执行）：先用 shell 身份把 " +
                        "user_setup_complete 临时置 0，再重跑本流程，结束后自动恢复为 1。\n" +
                        "副作用：期间三键导航的「主页 / 最近任务」键会短暂失灵" +
                        "（返回键可用，全屏手势导航不受影响），恢复后立即正常。\n\n" +
                        "若补救后仍失败，请改用其它激活入口（如「DP + Shizuku」或 Shizuku / Dhizuku 通道）。",
                log = log,
            )
        }

        // 已是 DO：直接成功，避免重复调用（部分 ROM 上重复 setDeviceOwner 会抛异常）
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ActivationResult(
                Stage.SET_OWNER_THREW, false, "无法获取 DevicePolicyManager（系统异常）", log,
            )
        if (runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)) {
            note("v 本应用已是 Device Owner，无需重复激活")
            return ActivationResult(Stage.OK, true, "本应用已是设备所有者。", log)
        }

        // ---------- 第 3 步：forceUpdateUserSetupComplete(userId=0) ----------
        //
        // 【v1.6.5 语义更正】参数是 userId，不是「完成状态」。该调用把
        // Settings.Secure.USER_SETUP_COMPLETE 的真实值同步进 DPM 内存态，
        // **不具备绕开 setup 闸的能力**（详见类注释第 3 步）。
        // 保留它的原因：① 让内存态与 Settings 保持一致，避免设备在
        // 「向导未完成但 Settings 已置位」的边角状态下出现误判；
        // ② 它同时刷新 mStateCache.setDeviceProvisioned()。
        // 若该 API 在某 ROM 上被裁剪，跳过即可，不影响后续判定。
        if (forceSetupComplete) {
            note("")
            note("> 第 3 步：反射调用 forceUpdateUserSetupComplete(0)（同步向导状态到内存）...")
            val setupOk = invokeForceUpdateUserSetupComplete(dpm, 0, note)
            if (!setupOk) {
                // 不中断：该 API 在部分 ROM 上被裁剪，且本流程不依赖它绕闸。
                note("! 该调用失败（不中断：本流程不依赖它绕开 setup 闸）")
            }
        }

        // ---------- 第 4 步：setActiveAdmin(self, true) ----------
        note("")
        note("> 第 4 步：反射调用 setActiveAdmin(self) ...")
        val adminOk = invokeSetActiveAdmin(dpm, admin, true, note)
        if (!adminOk) {
            return ActivationResult(
                stage = Stage.SET_ADMIN_FAILED,
                success = false,
                message = "无法将本应用注册为设备管理员（setActiveAdmin 被系统拒绝）。" +
                        "可能原因：管理组件未在 manifest 正确注册，或厂商 ROM 封锁了该调用。",
                log = log,
            )
        }
        note("v 已成为设备管理员")

        // ---------- 第 5 步：setDeviceOwner(self, null, 0) ----------
        note("")
        note("> 第 5 步：反射调用 setDeviceOwner(self, null, 0) ...")
        val ownerResult = invokeSetDeviceOwner(dpm, admin, null, 0, note)

        // 复查：系统侧身份是否真的落地（反射返回 void，必须查实际状态）
        val nowDo = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
        note("复查 isDeviceOwnerApp($pkg) = $nowDo")

        return when {
            nowDo -> ActivationResult(Stage.OK, true, "已成功激活设备所有者（非 ADB 直连路径）。", log)
            ownerResult != null -> ActivationResult(
                stage = Stage.SET_OWNER_REJECTED,
                success = false,
                message = explainStatusCode(ownerResult),
                log = log,
            )
            else -> ActivationResult(
                stage = Stage.SET_OWNER_THREW,
                success = false,
                message = "setDeviceOwner 调用抛出异常且未能识别原因，激活未完成。\n\n" +
                        "请查看下方日志中「setDeviceOwner 抛异常」一行的异常类型与系统原文。\n" +
                        "常见原因：\n" +
                        "① 设备已完成开机向导（本模块会在动手前预检并直接提示）；\n" +
                        "② DP Role 尚未真正生效（权限异步落地，可稍等数秒重试）；\n" +
                        "③ 本应用尚未成为 active admin（第 4 步实际未生效）；\n" +
                        "④ 厂商 ROM 封锁了该 API。",
                log = log,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 3. 反射封装（全部 try/catch，绝不抛出）
    // ---------------------------------------------------------------------

    /**
     * 反射调用 `DevicePolicyManager#forceUpdateUserSetupComplete(int)`（`@TestApi`）。
     *
     * 系统实现（A13→A17 一致，A17 仅 `getUserData` 内联重构）：
     * ```java
     * public void forceUpdateUserSetupComplete(@UserIdInt int userId) {
     *     Preconditions.checkCallAuthorization(
     *             hasCallingOrSelfPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS));
     *     boolean isUserCompleted = mInjector.settingsSecureGetIntForUser(
     *             USER_SETUP_COMPLETE, 0, userId) != 0;
     *     DevicePolicyData policy = getUserData(userId);
     *     policy.mUserSetupComplete = isUserCompleted;   // ← 双向赋值
     *     mStateCache.setDeviceProvisioned(isUserCompleted);
     *     synchronized (getLockObject()) { saveSettingsLocked(userId); }
     * }
     * ```
     *
     * @return 调用是否成功（未抛异常）。
     */
    private fun invokeForceUpdateUserSetupComplete(
        dpm: DevicePolicyManager,
        userId: Int,
        note: (String) -> Unit,
    ): Boolean = try {
        val m = DevicePolicyManager::class.java.getMethod(
            "forceUpdateUserSetupComplete", Integer.TYPE,
        )
        m.invoke(dpm, userId)
        note("v forceUpdateUserSetupComplete($userId) 调用成功")
        true
    } catch (t: Throwable) {
        // 常见：NoSuchMethodException（ROM 裁剪）/ SecurityException（权限未生效）
        note("  forceUpdateUserSetupComplete 失败：${t.javaClass.simpleName}: ${t.message}")
        LOGGER.w("forceUpdateUserSetupComplete failed", t)
        false
    }

    /**
     * 反射调用 `DevicePolicyManager#setActiveAdmin(ComponentName, boolean, int)`。
     *
     * 需要 `MANAGE_DEVICE_ADMINS`（DP Role 的 `<permissions>` 里已显式列出）。
     */
    private fun invokeSetActiveAdmin(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        refreshing: Boolean,
        note: (String) -> Unit,
    ): Boolean = try {
        val m = DevicePolicyManager::class.java.getMethod(
            "setActiveAdmin", ComponentName::class.java, Boolean::class.javaPrimitiveType, Integer.TYPE,
        )
        m.invoke(dpm, admin, refreshing, 0)
        true
    } catch (t: Throwable) {
        note("  setActiveAdmin 失败：${t.javaClass.simpleName}: ${t.message}")
        LOGGER.w("setActiveAdmin failed", t)
        false
    }

    /**
     * 反射调用 `DevicePolicyManager#setDeviceOwner(...)`（`@TestApi` / `@SystemApi`）。
     *
     * ## 【v1.6.5 跨版本修复 / v1.8.0 补源码级取证】签名在 AOSP 上发生过变更
     *
     * 断裂点是 **Android 14**。以下为从 AOSP 官方镜像逐版本下载原文核对后的结果
     * （完整取证见项目文档 `AxManagerD_AOSP_13to17_反射签名与向导闸_源码取证.md`）：
     *
     * | 版本 | 真实签名 | `DevicePolicyManager.java` 行号 |
     * |---|---|---|
     * | Android 13 | `boolean setDeviceOwner(ComponentName, @Nullable String ownerName, int userId)` | `8672` |
     * | Android 14 | `boolean setDeviceOwner(ComponentName, int userId)` | `9085` |
     * | Android 15 | 同 A14（两参） | `9377` |
     * | Android 16 | 同 A14（两参） | `9699` |
     * | Android 17 | 同 A14（两参） | `9686` |
     *
     * 旧实现写死三参数，故在 A14+ 会直接抛 `NoSuchMethodException`（被本函数
     * 包成「抛异常」），在 A13 上能命中但内部可能抛 `IllegalStateException`。
     * 现按 SDK 优先选对应签名，命中失败时**回退另一签名**，五版本均可工作。
     *
     * ⚠️ 注意：**不只是公开方法变了**。A13 的公开方法把三参原样转发给服务
     * （`return mService.setDeviceOwner(who, ownerName, userId, ...)`，L8676），
     * A14 起改传两参（`return mService.setDeviceOwner(who, userId, ...)`，L9088）——
     * 即 Binder 侧的 `IDevicePolicyManager` 接口签名同样变了。
     * 因此**不存在**「三参在 14+ 也能跑通」的兼容写法，只能按版本选参数个数。
     *
     * `setDeviceOwnerOnly` 的签名变化与本法**完全同步**（A13 三参 / A14+ 两参，
     * 行号 `8694` / `9106` / `9398` / `9720` / `9707`），如后续用到需按同一规则分支。
     *
     * `ownerName` 在 A14+ 已被系统移除，调用方恒传 `null` 即可（本方法仅在
     * 三参签名上使用该值）。
     *
     * ## 返回值语义
     *
     * - 命中并返回 `true` / `false`：`true` = 成功，`false` = 由 `Preconditions`
     *   之外的原因返回 false（极少见，DPM 里失败通常直接抛异常）。
     * - **抛异常时返回 `null`**，调用方据此区分「返回值失败」与「抛异常」。
     *
     * @return 是否成功；抛异常时返回 null。
     */
    private fun invokeSetDeviceOwner(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        ownerName: String?,
        userId: Int,
        note: (String) -> Unit,
    ): Int? = try {
        val cls = DevicePolicyManager::class.java
        // 按 SDK 决定优先尝试的签名顺序（A13 是三参数，A14+ 是两参数）。
        val preferThreeArgs = Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU
        val threeArgs = runCatching {
            cls.getMethod(
                "setDeviceOwner",
                ComponentName::class.java, String::class.java, Integer.TYPE,
            )
        }.getOrNull()
        val twoArgs = runCatching {
            cls.getMethod(
                "setDeviceOwner",
                ComponentName::class.java, Integer.TYPE,
            )
        }.getOrNull()

        // 注意：`threeArgs to true` 里 threeArgs 是 `Method?`，而 `listOfNotNull`
        // 只过滤 Pair 本身（Pair 永不为 null），元素类型会被推成 `Pair<Method?, Boolean>`，
        // 导致下方 `method.invoke(...)` 报「Only safe (?.) or non-null asserted (!!.)
        // calls are allowed on a nullable receiver」。
        // 因此这里显式用 if-else 构造非空列表，避免可空类型进入循环。
        val ordered: List<Pair<java.lang.reflect.Method, Boolean>> = if (preferThreeArgs) {
            listOfNotNull(
                threeArgs?.let { it to true },
                twoArgs?.let { it to false },
            )
        } else {
            listOfNotNull(
                twoArgs?.let { it to false },
                threeArgs?.let { it to true },
            )
        }
        if (ordered.isEmpty()) {
            note("  setDeviceOwner 方法不存在（本 ROM 可能封闭了该 API）")
            return null
        }

        var lastError: Throwable? = null
        for ((method, isThree) in ordered) {
            val rv = try {
                if (isThree) method.invoke(dpm, admin, ownerName, userId)
                else method.invoke(dpm, admin, userId)
            } catch (t: Throwable) {
                // 记录真实原因（InvocationTargetException 的真身在 cause 里），
                // 换下一个签名再试；两个都失败时把最后一次的原因带回 UI。
                lastError = t
                continue
            }
            val ok = (rv as? Boolean) ?: false
            note("  setDeviceOwner 调用成功（${if (isThree) "三参 / Android 13" else "两参 / Android 14+"} 签名），返回：$ok")
            return if (ok) STATUS_OK else STATUS_REJECTED_GENERIC
        }

        // 全部签名均抛异常：把最内层原因暴露出来（而不是裸的 InvocationTargetException）。
        val real = unwrap(lastError)
        note("  setDeviceOwner 抛异常：${real?.javaClass?.name}: ${real?.message}")

        // ---------- 【v1.8.0 新增】Android 14+ 的 ServiceSpecificException 分支 ----------
        //
        // AOSP 取证（DevicePolicyManagerService.java）：
        //   - Android 13 `enforceCanSetDeviceOwnerLocked`：一律抛 IllegalStateException；
        //   - Android 14 起同方法改为：
        //       if (code == STATUS_HEADLESS_SYSTEM_USER_MODE_NOT_SUPPORTED) {
        //           throw new ServiceSpecificException(code, provisioningErrorStringLocked);
        //       } else {
        //           throw new IllegalStateException(provisioningErrorStringLocked);
        //       }
        //   即 A14+ 上 provisioning 失败**可能是 ServiceSpecificException**，
        //   旧实现只判 IllegalStateException，会漏接这个分支。
        //
        // 该类属于 hidden API（不在公开 SDK 内），因此这里**不直接引用类型**，
        // 而是按类名匹配 + 反射读取其 `getErrorCode()`，避免编译期依赖。
        val realClassName = real?.javaClass?.name
        if (real != null && realClassName != null &&
            realClassName.endsWith("ServiceSpecificException")
        ) {
            val code = runCatching {
                real.javaClass.getMethod("getErrorCode").invoke(real) as? Int
            }.getOrNull()
            note("  识别为 ServiceSpecificException（Android 14+ 路径），errorCode=$code")
            note("  系统原文：${real.message}")
            // 优先用异常自带 errorCode；取不到（或为 0）时退回文案匹配。
            val codeFromExc = code?.takeIf { it != 0 }
            return codeFromExc ?: errorCodeFromMessage(real.message) ?: STATUS_REJECTED_GENERIC
        }

        if (real is IllegalStateException) {
            // DPM 的 provisioning 前置失败走这条（computeProvisioningErrorString 的文案）。
            note("  系统原文：${real.message}")
            return errorCodeFromMessage(real.message) ?: STATUS_REJECTED_GENERIC
        }
        if (real is IllegalArgumentException) {
            // 例如 "Not active admin: xxx" / "Invalid component xxx"。
            note("  系统原文：${real.message}")
        }
        LOGGER.w("setDeviceOwner threw", real ?: lastError)
        null
    } catch (t: Throwable) {
        val real = unwrap(t)
        note("  setDeviceOwner 反射失败：${real?.javaClass?.simpleName}: ${real?.message}")
        LOGGER.w("setDeviceOwner reflection failed", real ?: t)
        null
    }

    /**
     * 剥掉反射包装异常，取出真实原因。
     *
     * 反射调用（`Method#invoke`）会把目标方法抛出的异常统一包成
     * `InvocationTargetException`，而其 `message` 恒为 `null` ——
     * 这正是旧版日志只显示 `InvocationTargetException: null`、
     * 完全无法定位问题的原因。真实异常在 `cause` 里，必须逐层剥出。
     */
    private fun unwrap(t: Throwable?): Throwable? {
        var cur = t
        var guard = 0
        while (cur is java.lang.reflect.InvocationTargetException && cur.cause != null && guard < 8) {
            cur = cur.cause
            guard++
        }
        return cur
    }

    /**
     * 把 DPM 抛出的 `IllegalStateException` 文案反查成 `STATUS_*` 数值。
     *
     * 依据 `DevicePolicyManagerService#computeProvisioningErrorString` 的原文映射
     * （A13→A16 文案一致）。用**文案匹配**而非引用隐藏常量，避免编译期依赖。
     */
    private fun errorCodeFromMessage(msg: String?): Int? {
        val m = msg ?: return null
        return when {
            m.contains("device owner is already set", true) -> 5     // STATUS_HAS_DEVICE_OWNER
            m.contains("already has a profile owner", true) -> 6     // STATUS_USER_HAS_PROFILE_OWNER
            m.contains("not running", true) -> 2                     // STATUS_USER_NOT_RUNNING
            m.contains("is not system user", true) -> 4              // STATUS_NOT_SYSTEM_USER
            m.contains("already set-up", true) -> 1                  // STATUS_USER_SETUP_COMPLETED
            m.contains("already several users", true) -> 2           // STATUS_NONSYSTEM_USER_EXISTS
            m.contains("already some accounts", true) -> 3           // STATUS_ACCOUNTS_NOT_EMPTY
            m.contains("already paired", true) -> 7                  // STATUS_HAS_PAIRED
            else -> null
        }
    }

    /**
     * 把 `DevicePolicyManagerService` 的 provisioning 错误码翻译成中文。
     *
     * 依据 AOSP `computeProvisioningErrorStringLocked` 的 `STATUS_*` 常量语义，
     * 此处只用**数值映射**（不引用任何隐藏常量，避免编译期依赖）。
     */
    private fun explainStatusCode(code: Int): String = when (code) {
        1 -> "设备已完成开机向导（STATUS_USER_SETUP_COMPLETED）。" +
                "非 ADB 直连要求「向导未完成」，这是它唯一的前置条件，无法绕开。" +
                "请改用其它激活入口。"

        2 -> "设备上存在第二用户 / 工作资料 / 应用分身（STATUS_NONSYSTEM_USER_EXISTS）。" +
                "请删除后重试。"

        3 -> "设备上存在账户（STATUS_ACCOUNTS_NOT_EMPTY）。" +
                "非 ADB 路径理论上不检查账户，若出现该错误说明 ROM 修改了系统逻辑。"

        4 -> "目标用户不是机主（STATUS_NOT_SYSTEM_USER）。设备所有者只能设在 User 0。"

        5 -> "设备已存在设备所有者（STATUS_DEVICE_OWNER_ALREADY_SET）。"

        6 -> "当前用户已被设为资料所有者（STATUS_USER_HAS_PROFILE_OWNER）。"

        7 -> "设备已与其它设备配对（STATUS_USER_ALREADY_HAS_A_PROFILE_OWNER）。"

        8 -> "设备已经设置过（STATUS_UNKNOWN）。"

        STATUS_REJECTED_GENERIC ->
            "系统拒绝了设备所有者设置（未能从系统返回中解析出具体原因）。" +
                    "请查看上方日志中的「系统原文」一行。"

        else -> "系统拒绝了设备所有者设置（错误码 $code）。"
    }
}
