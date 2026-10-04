package frb.axeron.manager.appmgr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import frb.axeron.manager.AxeronApplication
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.AxeronRuntimeLog
import frb.axeron.manager.owner.DeviceOwnerPrivilege
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.manager.owner.NewPermissionPaths
import frb.axeron.manager.ui.util.HanziToPinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 软件管理界面的条目模型。
 *
 * 只保留 UI 需要的最小集合，避免把 PackageInfo 全量塞进 Compose 状态。
 */
data class ManagedApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val enabled: Boolean,
    val suspended: Boolean,
) {
    /** 稳定 key，供 LazyColumn 使用。 */
    val key: String get() = packageName
}

/**
 * 软件管理 ViewModel。
 *
 * 职责：
 *   1. 加载应用列表（支持「显示系统应用」开关）；
 *   2. **按能力等级从大到小逐档尝试**（DO → PO → DP → ADB/shell），
 *      **某一档经校验真的生效就立刻停止**（用户要求）；
 *   3. 维护执行结果与进行中状态。
 *
 * 权限分级原则见 [AppManageRouter]（候选列表）与 [AppManageVerifier]（生效校验）。
 */
class AppManageViewModel : ViewModel() {

    /** 是否显示系统应用。 */
    var showSystemApps by mutableStateOf(false)
        private set

    /** 全量应用列表（已按 showSystemApps 过滤）。 */
    var apps by mutableStateOf<List<ManagedApp>>(emptyList())
        private set

    /**
     * 搜索关键字（匹配应用名 / 包名 / 中文拼音）。
     *
     * 【v1.9.1 新增】用户要求：软件管理页支持搜索，省去在几百个应用里翻找。
     * 行为与授权页 PrivilegeViewModel 保持一致：
     * 仅在输入含非 ASCII 字符（中文）时才做拼音匹配，避免无谓开销。
     */
    var searchQuery by mutableStateOf("")
        private set

    /**
     * 更新搜索关键字。
     *
     * 注意：这里不能命名为 `setSearchQuery`——`var searchQuery ... private set`
     * 已生成 JVM 方法 `setSearchQuery(String)`，再定义一个同签名函数会
     * 触发 Kotlin 的 "Platform declaration clash" 编译错误。
     */
    fun updateSearchQuery(q: String) {
        searchQuery = q
    }

    /**
     * 过滤后的展示列表（UI 直接消费本属性）。
     *
     * 改动隔离：不改 [apps] 的语义（始终是全量列表），过滤只发生在读取时；
     * 因此「刷新列表 / 显示系统应用」等既有逻辑完全不受影响。
     */
    val visibleApps: List<ManagedApp>
        get() {
            val q = searchQuery.trim()
            if (q.isEmpty()) return apps
            val needPinyin = q.any { it.code > 128 }
            return apps.filter { app ->
                app.label.contains(q, ignoreCase = true) ||
                        app.packageName.contains(q, ignoreCase = true) ||
                        (needPinyin && runCatching {
                            HanziToPinyin.getInstance().toPinyinString(app.label)
                                .contains(q, ignoreCase = true)
                        }.getOrDefault(false))
            }
        }

    /** 列表加载中。 */
    var loading by mutableStateOf(false)
        private set

    /** 最近一次操作结果文案（成功/失败），供 Snackbar 展示。 */
    var lastResult by mutableStateOf<String?>(null)
        private set

    /** 正在执行操作的应用包名（用于禁用按钮/显示进度）。 */
    var busyPackage by mutableStateOf<String?>(null)
        private set

    fun consumeResult() {
        lastResult = null
    }

    /** 切换「显示系统应用」并重新加载列表。 */
    fun toggleShowSystemApps(show: Boolean) {
        showSystemApps = show
        loadApps()
    }

    /** 加载应用列表。 */
    fun loadApps() {
        viewModelScope.launch {
            loading = true
            val context = AxeronApplication.axeronApp
            val list = withContext(Dispatchers.IO) { queryApps(context, showSystemApps) }
            apps = list
            loading = false
        }
    }

    private fun queryApps(context: Context, includeSystem: Boolean): List<ManagedApp> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val infos = runCatching {
            pm.getInstalledApplications(flags)
        }.getOrDefault(emptyList())
        return infos.asSequence()
            .filter { info ->
                includeSystem || (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .map { info ->
                val enabled = runCatching {
                    pm.getApplicationEnabledSetting(info.packageName) !=
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                            pm.getApplicationEnabledSetting(info.packageName) !=
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }.getOrDefault(info.enabled)
                ManagedApp(
                    packageName = info.packageName,
                    // 【v1.9.1】应用名获取统一为 loadLabel（与授权页 PrivilegeViewModel 一致）；
                    // 仅当系统确实取不到名称时才回落包名，避免「明明有应用名却显示包名」。
                    label = runCatching { info.loadLabel(pm).toString().trim() }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?: info.packageName,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    enabled = enabled,
                    suspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * 执行一个管理动作。
     *
     * 【v1.4.0 核心重写】用户要求：**按能力等级从大到小逐档尝试，
     * 某一档真正生效就立刻停止**（排名：DO ＞ PO ＞ DP ＞ ADB）。
     *
     * 旧实现的问题：先按 DO 试一次、失败再按 shell 试一次，且「失败」只看函数返回，
     * 不看是否真的生效 → 用户看到「先用 DO 挂起一次，再用 Shizuku 又挂起一次」。
     *
     * 现在的流程：
     *   1. 实时探测本进程 DPM 是否真的可用（DO/PO 本体或 Dhizuku 已授权）；
     *   2. 由 [AppManageRouter.candidates] 按 DO→PO→DP→ADB 生成**去重后**的候选；
     *   3. 依次执行，每档执行后用 [AppManageVerifier] 校验**是否真的生效**：
     *      生效 → 立刻停止并把该档位写进运行日志；未生效 → 记录原因并降档重试。
     *
     * 权限来源由调用方传入（避免 ViewModel 反向依赖 ActivateViewModel）：
     * 这里接收一个 [PrivilegeSnapshot]，使路由逻辑可测试且不污染公共路径。
     */
    fun perform(
        action: AppManageAction,
        pkg: String,
        privilege: PrivilegeSnapshot,
    ) {
        if (busyPackage != null) return
        busyPackage = pkg
        viewModelScope.launch {
            val context = AxeronApplication.axeronApp
            // 本地 DPM 可用性探测（DO/PO 本体 或 Dhizuku 已授权）。
            // 仅持有 DP Role 时为 false —— 此时本地 DPM 必然报
            // 「Device Owner not active」，因此不生成该候选（不再浪费一次尝试）。
            val hasLocalDpm = withContext(Dispatchers.IO) {
                runCatching {
                    DeviceOwnerPrivilege.isLocalDpmUsable(context)
                }.getOrDefault(false)
            }
            val plans = AppManageRouter.candidates(
                action = action,
                pkg = pkg,
                hasDo = privilege.hasDo,
                hasPo = privilege.hasPo,
                hasDp = privilege.hasDp,
                hasShell = privilege.hasShell,
                hasLocalDpm = hasLocalDpm,
            )

            AxeronRuntimeLog.section("app manage")
            AxeronRuntimeLog.i(
                "AppManage",
                "action=${AppManageRouter.actionLabel(action)} pkg=$pkg " +
                        "privilege(do=${privilege.hasDo},po=${privilege.hasPo}," +
                        "dp=${privilege.hasDp},shell=${privilege.hasShell}) " +
                        "hasLocalDpm=$hasLocalDpm " +
                        "tiers=${plans.joinToString(",") { AppManageRouter.levelLabel(it.level) }}"
            )

            if (plans.isEmpty()) {
                // 文案如实反映系统事实：Android 13 上能挂起/冻结的身份只有 DO / PO / shell；
                // Android 14+ 启用 Device Policy Engine 后，DP Role 持有者凭
                // MANAGE_DEVICE_POLICY_* 权限族亦可直接执行（见 DeviceOwnerPrivilege）。
                val reason = buildString {
                    append("无可用权限：请先激活「设备所有者(DO) / 资料所有者(PO) / DP Role」之一，")
                    append("或开启「Shizuku(ADB shell)」通道")
                }
                AxeronRuntimeLog.w("AppManage", "不可用：$reason")
                lastResult = "${AppManageRouter.actionLabel(action)}失败：$reason"
                busyPackage = null
                return@launch
            }

            var usedPlan: AppManagePlan? = null
            var lastError: String? = null
            try {
                // 逐档尝试：从能力最强的 DO 开始，一旦「真生效」立即停止。
                for (plan in plans) {
                    val label = AppManageRouter.levelLabel(plan.level)
                    // ★ 用 runCatching 包住整档执行：任何异常都转成失败结果，
                    //   绝不让协程崩溃（否则 busyPackage 会卡住，后续点击毫无反应）。
                    val output: Result<String> = runCatching {
                        withContext(Dispatchers.IO) {
                            when {
                                plan.method == AppManageMethod.LOCAL_DPM && plan.localAction != null ->
                                    execLocalDpm(plan.localAction, pkg)
                                plan.available && plan.shellCommand != null ->
                                    exec(plan.shellCommand)
                                else -> Result.failure(IllegalStateException("该档位无可用实现"))
                            }
                        }
                    }.getOrElse { t ->
                        Result.failure(IllegalStateException(t.message ?: t.toString()))
                    }
                    if (output.isFailure) {
                        lastError = output.exceptionOrNull()?.message ?: "未知错误"
                        AxeronRuntimeLog.w("AppManage", "档位[$label]执行失败：$lastError → 降档重试")
                        continue
                    }
                    // 关键：不只看返回值，还要**校验是否真的生效**（用户要求「有效果才停」）。
                    val verified = runCatching {
                        withContext(Dispatchers.IO) {
                            AppManageVerifier.verify(context, action, pkg)
                        }
                    }.getOrNull()
                    if (verified == false) {
                        lastError = "档位[$label]报告成功但校验未生效"
                        AxeronRuntimeLog.w("AppManage", "$lastError → 降档重试")
                        continue
                    }
                    usedPlan = plan
                    AxeronRuntimeLog.i(
                        "AppManage",
                        "档位[$label]生效（校验=${if (verified == true) "已确认" else "无法校验，按结果采信"}）" +
                                "，停止继续降档。输出=${output.getOrNull()}"
                    )
                    break
                }

                lastResult = if (usedPlan != null) {
                    "已${AppManageRouter.actionLabel(action)}（${AppManageRouter.levelLabel(usedPlan.level)}）"
                } else {
                    "${AppManageRouter.actionLabel(action)}失败：${lastError ?: "所有权限档位均未生效"}"
                }
                AxeronRuntimeLog.i("AppManage", "结果：${lastResult}")
            } finally {
                // ★ 无论成功、失败还是抛异常，都必须复位「忙」状态；
                //   否则按钮会永久禁用，表现为后续点击**没有任何反应**。
                busyPackage = null
            }
            // 卸载/隐藏等会改变列表，重新加载
            if (action == AppManageAction.FORCE_UNINSTALL || action == AppManageAction.HIDE) {
                loadApps()
            }
        }
    }

    /**
     * DO / PO / DP 档位的**本地执行**：不经 Shizuku、不经 shell，直接在本进程内调用
     * [DeviceOwnerPrivilege.execute]（内部走 DevicePolicyManager API / Dhizuku binderWrapper）。
     *
     * 这是「DP 权限在无 Shizuku 时也能挂起/卸载」的关键：
     * 应用本身就是 Device Owner 时，DPM 调用天然以 owner 身份通过鉴权。
     */
    private suspend fun execLocalDpm(
        action: List<String>,
        pkg: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val context = AxeronApplication.axeronApp
            val (code, output) = DeviceOwnerPrivilege.execute(context, action + pkg)
            if (code != 0) {
                throw IllegalStateException(output.trim().ifBlank { "本地 DPM 执行失败 (code=$code)" })
            }
            output.trim()
        }
    }

    /**
     * 以 **Axeron/Shizuku shell 身份**执行命令。
     *
     * 【v1.4.0 关键修复 · 挂起报 `Permission denied for transactRemote`】
     *
     * 旧实现手工 `Shizuku.getBinder()` + `ShizukuBinderWrapper` 裸 binder 直连
     * `IShizukuService.newProcess`，**绕过了 Axeron 自身的权限校验与重连逻辑**：
     *   - Axeron server 按 (uid, pid) 维护「已授权 client」。服务端进程一旦重启
     *     （或被系统回收），client 记录即丢失；此时用**早先缓存的旧 binder** 再调用，
     *     就会被服务端 `enforceCallingPermission("transactRemote")` 拒绝，
     *     抛出 `Permission denied for transactRemote`；
     *   - 真机表现：**同一个「挂起」动作，前一次成功、后一次失败**，且不会自愈。
     *
     * 现统一改为项目既有的 [AxeronPluginService.execProcessSafeWithTimeout]
     * （内部走 `Axeron.pingBinder()` + `Axeron.newProcess()`，并对 binder 失效
     * 返回**可读错误**而不是 SecurityException），并在「通道类失败」时
     * **重新握手 + 重试一次**，以覆盖服务端刚重启的窗口期。
     *
     * 说明：本次只替换本 ViewModel 内部的执行实现，**未改动任何公共方法**。
     */
    private suspend fun exec(command: String): Result<String> {
        // 单次执行：统一走 Axeron 正规 API（内部对 binder 失效返回可读错误，不抛异常）。
        suspend fun once(): Pair<Boolean, String> = runCatching {
            val env = runCatching { Axeron.getEnvironment() }.getOrNull()
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("sh", "-c", command),
                env = env,
                // 管理类命令（pm suspend / pm uninstall）正常在 1s 内返回，
                // 30s 仅用于兜住极端卡死，避免 UI 一直转圈。
                timeoutMs = 30_000L,
            )
            if (r.exitCode == 0) {
                true to r.stdout.trim()
            } else {
                false to (r.stderr.trim().ifBlank { r.stdout.trim() }
                    .ifBlank { "命令执行失败 (exit=${r.exitCode})" })
            }
        }.getOrElse { t ->
            // ★ 兜底铁律：任何异常都必须转成「可读失败」，**绝不允许冒泡**。
            //   Axeron 的 binder 层可能直接抛 SecurityException（例如
            //   `Permission denied for transactRemote`）。若异常冒泡到 perform()，
            //   协程会直接崩溃 → busyPackage 永远不会复位 → 按钮一直处于「忙」，
            //   表现就是用户点「挂起」**毫无反应**（旧实现有 runCatching 兜住，
            //   上一版重构时丢掉了，此处补回）。
            false to (t.message ?: t.toString())
        }

        val (ok1, msg1) = once()
        if (ok1) return Result.success(msg1)

        if (isChannelError(msg1)) {
            // 通道类错误（binder 失效 / 授权被拒 / 服务端刚重启）→ 重新握手后重试一次。
            AxeronRuntimeLog.w("AppManage", "shell 通道异常（$msg1）→ 重新获取 binder 后重试一次")
            runCatching { Axeron.pingBinder() }
            kotlinx.coroutines.delay(300L)
            val (ok2, msg2) = once()
            if (ok2) return Result.success(msg2)
            AxeronRuntimeLog.w("AppManage", "通道重试仍失败（$msg2）→ 回落应用自身执行")
            return execViaSelf(command)
        }
        return Result.failure(IllegalStateException(msg1))
    }

    /**
     * 回落：以 **应用自身身份**执行命令（不经 Axeron / shell）。
     *
     * 为什么保留这条兜底：
     *  - `DP`（`DEVICE_POLICY_MANAGEMENT` Role）会给本应用授予
     *    `WRITE_SECURE_SETTINGS` / `MANAGE_DEVICE_ADMINS` 等权限，
     *    部分命令在应用自身身份下**确实能完成**；
     *  - 即使命令失败，也要**如实反馈错误**，而不是静默无响应。
     *
     * ⚠️ 已知系统限制（真机 + AOSP 源码双证）：
     *  `pm suspend` 在 Android 13 上要求调用者持有 `SUSPEND_APPS`
     *  （仅 shell / DO / PO 具备），而 `DP` Role **不包含**该权限；
     *  且已开机设备无法再激活 DO/PO。因此本回落对「挂起」通常仍会失败——
     *  这是系统鉴权规则，不是代码缺陷，错误信息会原样回显给用户。
     */
    private suspend fun execViaSelf(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (process.exitValue() != 0) {
                val msg = err.trim().ifBlank { out.trim() }
                    .ifBlank { "命令执行失败 (exit=${process.exitValue()})" }
                throw IllegalStateException(msg)
            }
            out.trim()
        }
    }

    /**
     * 判定错误是否属于「通道不可用」类（而非命令本身执行失败）。
     *
     * 这类错误的共同特征是**重试有意义**：重新取得 binder 后通常立即恢复。
     * 典型：`Permission denied for transactRemote`（Axeron 服务端拒绝旧 client）、
     * binder 置空、服务不可用、DeadObjectException。
     */
    private fun isChannelError(msg: String): Boolean =
        msg.contains("transactRemote") ||
                msg.contains("binder", ignoreCase = true) ||
                msg.contains("服务不可用") ||
                msg.contains("Permission Denial") ||
                msg.contains("DeadObjectException")


    /** 供 UI 显示的当前设备权限快照（由界面从 ActivateViewModel 填充）。 */
    data class PrivilegeSnapshot(
        val hasDo: Boolean,
        val hasDp: Boolean,
        val hasShell: Boolean,
        /** 是否为 Profile Owner（PO）：能力仅次于 DO（v1.4.0 新增）。 */
        val hasPo: Boolean = false,
    )

    /**
     * 校验 DP Role 是否仍持有（供界面在打开时刷新）。
     *
     * 【v1.3.0 修复「重开 App 权限就掉」】优先级：
     *   1. 本应用已是 Device/Profile Owner → 直接本地判定为 true（不依赖 Shizuku，
     *      也不依赖任何进程内缓存，永远实时准确）；
     *   2. 否则走 Shizuku 查询 Role holder。
     *
     * 原实现「无 Shizuku 一律 false」导致：已激活 DO 的设备只要没开 Shizuku，
     * 界面就显示 DP 未授权 —— 正是用户反馈的「重开软件权限就掉」。
     */
    suspend fun queryDpGranted(): Boolean = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        // 1. 本地实时判定（无 Shizuku 也可用）
        if (DeviceOwnerState.queryIsOwner(context)) return@withContext true
        // 2. 回落 Axeron/Shizuku 查询 Role
        //
        // 【v1.4.0 同步修复】这里同样是「裸 binder 直连」，会踩和 exec() 一样的坑：
        // Axeron server 重启后旧 binder 被 `enforceCallingPermission("transactRemote")`
        // 拒绝 → 整个查询 runCatching 返回 false → 界面误判为「DP 权限掉了」。
        // 现统一改用项目正规 API（内部做 binder 获取与判空）。
        runCatching {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("sh", "-c", NewPermissionPaths.buildQueryDpScript(context)),
                env = runCatching { Axeron.getEnvironment() }.getOrNull(),
                timeoutMs = 20_000L,
            )
            if (r.exitCode != 0) return@runCatching false
            NewPermissionPaths.parseDp(context, r.stdout)
        }.getOrDefault(false)
    }

    /**
     * 实时判定 DO / PO / DP / shell 四档权限（供界面每次进入时刷新，
     * 避免「重开掉权限」的假象，也避免高估权限导致 `Device Owner not active`）。
     *
     * - DO：本地 DPM API `isDeviceOwnerApp`（永不依赖 Shizuku）；
     * - PO：本地 DPM API `isProfileOwnerApp`；
     * - DP：DO/PO 为真时直接为真，否则查 Role；
     * - shell：Shizuku 实时状态。
     *
     * 【v1.4.0】DO 与 PO **分开**上报（旧实现用 `isDo || isPo` 合并成一个 hasDo，
     * 导致档位排序无法区分 DO / PO，也掩盖了真实身份）。
     */
    suspend fun queryPrivilegeSnapshot(): PrivilegeSnapshot = withContext(Dispatchers.IO) {
        val context = AxeronApplication.axeronApp
        val (isDo, isPo) = DeviceOwnerState.queryOwnerFlags(context)
        val hasDp = (isDo || isPo) || queryDpGranted()
        val hasShell = runCatching {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false) || runCatching {
            // 【v1.4.6 关键修复 ·「挂起无效 / 回退 shell 形同虚设」】
            //
            // SHELL 档位的**实际执行通道**是 Axeron（见 exec() →
            // AxeronPluginService.execProcessSafeWithTimeout），而不是 Shizuku。
            // 旧实现只探测 Shizuku：本机（Shizuku 未授权 / 只有 Stellar 之类的
            // Shizuku 兼容服务）时 hasShell 恒为 false，AppManageRouter 便**根本不生成**
            // SHELL 候选，于是「DP 档失败后回退 shell」永远没有下一档可退，
            // 表现就是「挂起点了没反应 / 只报 DP 不可用」。
            // 这里补上 Axeron 通道探测：谁真正能执行，hasShell 就为真。
            Axeron.pingBinder()
        }.getOrDefault(false)
        PrivilegeSnapshot(hasDo = isDo, hasDp = hasDp, hasShell = hasShell, hasPo = isPo)
    }
}