package frb.axeron.manager.ai

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行历史 · 重置计划（只读分析 + 生成提示词）。
 *
 * ## 两种重置方式
 * 1. **生成 AI 提示词**（[buildPrompt]）：把「所有模块执行过的指令 + 设备信息 + 最高可用权限 UID」
 *    汇总成一段提示词，用户复制给任意 agent，由 agent 产出恢复方案。
 *    适用：写入目标无法自动判定、需要人判断的场景（文件内容、属性、包状态等）。
 * 2. **按系统默认值自动恢复**（[build]）：挑**能确定还原**的写入 ——
 *    `settings put` → `settings delete`（删除即回到系统默认）；
 *    `pm disable-user` / `pm hide` / `pm suspend` → `pm enable` / `pm unhide` / `pm unsuspend`；
 *    `pm uninstall` → `pm install-existing`；`pm grant` ↔ `pm revoke`；
 *    `cmd appops set` → `... default`；`cmd overlay enable/disable` 互逆；
 *    `axeron-dpm hide/suspend/block-uninstall/grant/deny` → 对应反向。
 *    原值无法确定的（文件内容、属性、clear、install）一律列入「需人工」，宁可少做也不误删。
 *    执行通道见 [PrivilegedShell]。
 *
 * ## 只读
 * 不写、不改、不删任何日志；与 [RunRecorderStore] 同一口径。
 */
object RestorePlanner {

    /** 恢复动作的类别。 */
    enum class Kind {
        /** 系统设置项。 */
        SETTINGS,

        /** 系统属性。 */
        PROP,

        /** 文件写入。 */
        FILE,

        /** 其它（包状态、权限、组件…）。 */
        OTHER,

        /** 应用包状态 / 安装状态 / 权限（`pm` 族，可自动逆操作）。 */
        PACKAGE,
    }

    /**
     * 一条待恢复项（同一目标只保留一条，不重复列出）。
     *
     * @param key 归一化目标键（`ns:key` / 绝对路径 / 属性名 / 原指令）
     * @param display 展示名（键名 / 文件名 / 属性名 / 原指令前 60 字）
     * @param modules 写过这个目标的模块（去重，按出现顺序）
     * @param origin 最后一条写入指令（代表"当前生效"的那条）
     * @param revert 自动恢复指令；`null` = 只能人工处理
     */
    data class Action(
        val kind: Kind,
        val key: String,
        val display: String,
        val modules: List<String>,
        val origin: String,
        val revert: String?,
    ) {
        /** 是否可一键自动恢复。 */
        val auto: Boolean get() = !revert.isNullOrBlank()
    }

    /** 整份恢复计划。 */
    data class Plan(val actions: List<Action>) {
        /** 可自动恢复的项（设置项 / 属性 / 文件 / pm 族）。 */
        val autoActions: List<Action> get() = actions.filter { it.auto }

        /** 需人工处理的项（不知道原值、或逆操作会丢数据的）。 */
        val manualActions: List<Action> get() = actions.filter { !it.auto }
    }

    /**
     * 扫描全部运行记录，产出恢复计划。
     *
     * 只做两件事：
     * - `settings put` → 生成 `settings delete <ns> <key>`（删除 = 回到系统默认）；
     * - 其它写入 → 列出但 `revert = null`（不知道原值，不能瞎改）。
     */
    suspend fun build(context: Context): Plan = withContext(Dispatchers.IO) {
        val auto = LinkedHashMap<String, MutableList<Action>>()
        val manual = LinkedHashMap<String, MutableList<Action>>()

        fun record(action: Action) {
            val bucket = if (action.auto) auto else manual
            bucket.getOrPut("${action.kind.name}|${action.key}") { ArrayList() }.add(action)
        }

        val groups = runCatching { RunRecorderStore.listGroups(context) }.getOrDefault(emptyList())
        for (group in groups) {
            for (rec in group.records.sortedBy { it.updatedAt }) {
                val commands = runCatching { RunRecorderStore.readCommands(rec.logFile) }
                    .getOrDefault(emptyList())
                for (command in commands) {
                    val parsed = runCatching { CommandInspector.parse(command) }.getOrNull() ?: continue
                    when (parsed.kind) {
                        CommandInspector.Kind.SETTINGS -> {
                            // 只处理 put：get 是读取、delete 本身已是默认态，都不需要恢复
                            if (parsed.op != "put") continue
                            val ns = parsed.namespace
                            val target = parsed.target
                            if (ns.isNullOrBlank() || target.isBlank()) continue
                            val key = "$ns:$target"
                            record(
                                Action(
                                    kind = Kind.SETTINGS,
                                    key = key,
                                    display = target,
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = "settings delete $ns $target",
                                )
                            )
                        }

                        CommandInspector.Kind.FILE_WRITE,
                        CommandInspector.Kind.FILE_APPEND -> {
                            val path = parsed.path
                            if (path.isNullOrBlank()) continue
                            record(
                                Action(
                                    kind = Kind.FILE,
                                    key = path,
                                    display = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = null,
                                )
                            )
                        }

                        CommandInspector.Kind.PROP -> {
                            // 【v1.5.5】`getprop <key>` 现在也解析成 PROP（为了详情页能读到值并给方案），
                            // 它只是读取，不能进恢复清单，否则清单里会塞满「属性读取」这类噪声。
                            if (parsed.op == "getprop") continue
                            val target = parsed.target
                            if (target.isBlank()) continue
                            record(
                                Action(
                                    kind = Kind.PROP,
                                    key = target,
                                    display = target,
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = null,
                                )
                            )
                        }

                        CommandInspector.Kind.SCAFFOLD,
                        CommandInspector.Kind.READ_ONLY,
                        CommandInspector.Kind.UNKNOWN -> Unit

                        // ---------- 【v1.5.5 需求①】pm 族：能确定的一律给逆操作 ----------
                        CommandInspector.Kind.PKG_STATE -> {
                            val pkg = parsed.pkg ?: continue
                            // 只对「把应用变成非默认状态」的动作给逆操作；
                            // enable / unhide / unsuspend 本身就是在往默认状态回，无法判断之前是什么。
                            val revert = when (parsed.op) {
                                "disable" -> "pm enable $pkg"
                                "hide" -> "pm unhide $pkg"
                                "suspend" -> "pm unsuspend $pkg"
                                else -> null
                            }
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|${parsed.op}",
                                    display = "$pkg（${parsed.op}）",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = revert,
                                )
                            )
                        }

                        CommandInspector.Kind.PKG_UNINSTALL -> {
                            val pkg = parsed.pkg ?: continue
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|uninstall",
                                    display = "$pkg（已卸载，可恢复安装）",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = "pm install-existing $pkg",
                                )
                            )
                        }

                        CommandInspector.Kind.PKG_INSTALL -> {
                            val pkg = parsed.pkg ?: continue
                            // 卸载会连带删数据，不能当「默认恢复」自动做，列进需人工。
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|install",
                                    display = "$pkg（模块装上去的应用）",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = null,
                                )
                            )
                        }

                        CommandInspector.Kind.PKG_CLEAR -> {
                            val pkg = parsed.pkg ?: continue
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|clear",
                                    display = "$pkg（数据已被清除）",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = null,
                                )
                            )
                        }

                        CommandInspector.Kind.PKG_PERM -> {
                            val pkg = parsed.pkg ?: continue
                            val perm = parsed.target
                            if (perm.isBlank()) continue
                            val revert = when (parsed.op) {
                                "revoke" -> "pm grant $pkg $perm"
                                "grant" -> "pm revoke $pkg $perm"
                                else -> null
                            }
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|perm|$perm",
                                    display = "$pkg · $perm",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = revert,
                                )
                            )
                        }

                        CommandInspector.Kind.APP_OPS -> {
                            val pkg = parsed.pkg ?: continue
                            val op = parsed.target
                            if (op.isBlank()) continue
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "$pkg|appops|$op",
                                    display = "$pkg · appops:$op",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = "cmd appops set $pkg $op default",
                                )
                            )
                        }

                        CommandInspector.Kind.OVERLAY -> {
                            val pkg = parsed.pkg ?: continue
                            val sub = parsed.op
                            val revert = when {
                                sub.startsWith("enable") -> "cmd overlay disable $pkg"
                                sub.startsWith("disable") -> "cmd overlay enable $pkg"
                                else -> null
                            }
                            record(
                                Action(
                                    kind = Kind.PACKAGE,
                                    key = "overlay|$pkg",
                                    display = "RRO $pkg",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = revert,
                                )
                            )
                        }

                        CommandInspector.Kind.DPM -> {
                            val pkg = parsed.pkg ?: continue
                            val revert = when (parsed.op) {
                                "hide" -> "axeron-dpm unhide $pkg"
                                "suspend" -> "axeron-dpm unsuspend $pkg"
                                "block-uninstall" -> "axeron-dpm unblock-uninstall $pkg"
                                "grant" -> "axeron-dpm deny $pkg"
                                "deny" -> "axeron-dpm grant $pkg"
                                else -> null
                            }
                            record(
                                Action(
                                    kind = Kind.OTHER,
                                    key = "dpm|${parsed.op}|$pkg",
                                    display = "设备策略 $pkg（${parsed.op}）",
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = revert,
                                )
                            )
                        }

                        else -> {
                            record(
                                Action(
                                    kind = Kind.OTHER,
                                    key = command,
                                    display = command.take(60),
                                    modules = listOf(group.name),
                                    origin = command,
                                    revert = null,
                                )
                            )
                        }
                    }
                }
            }
        }

        /** 把「同目标多条」合并成一条：模块去重、取最后一条指令。 */
        fun merge(bucket: Map<String, MutableList<Action>>): List<Action> =
            bucket.values.map { list ->
                val last = list.last()
                last.copy(modules = list.map { it.modules.first() }.distinct())
            }

        Plan(
            actions = (merge(auto) + merge(manual)).sortedWith(
                compareBy<Action> { it.kind.ordinal }.thenBy { it.display }
            )
        )
    }

    // ==================================================================================
    // 提示词
    // ==================================================================================

    /**
     * 生成「交给 agent 做恢复」的提示词。
     *
     * 必须包含用户要求的三类信息：**所有模块的指令**、**设备信息**、**最高权限 UID**，
     * 外加一份严格的恢复要求（不许扩大改动、不许删系统文件、必须给校验命令）。
     *
     * 【v1.5.5 需求③】开头必须说清「你是谁、要做的事是什么、这些指令是用什么权限跑的」：
     * 之前生成的提示词只有一份指令清单，AI 拿到后并不知道目标是「把系统恢复默认」，
     * 也不知道这些命令是模块以 shell/root 身份执行的真实系统命令（不是普通 App 权限）。
     */
    suspend fun buildPrompt(context: Context): String = withContext(Dispatchers.IO) {
        val groups = runCatching { RunRecorderStore.listGroups(context) }.getOrDefault(emptyList())
        val channel = PrivilegedShell.channel()
        val time = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        }.getOrDefault("-")

        val sb = StringBuilder()
        sb.appendLine("# 任务：把本机被模块改动过的系统状态恢复成默认")
        sb.appendLine()
        sb.appendLine("你是一名 Android 系统恢复助手。下面这份清单是本机所有模块**真实执行过**的命令记录，")
        sb.appendLine("这些命令已经改动了本机的系统设置、系统属性、文件、应用状态或权限。")
        sb.appendLine("你的任务是：**逐条分析这些改动，产出能把系统恢复到「改动前的默认状态」的完整命令清单**，")
        sb.appendLine("并说明每条命令在回退哪一条模块指令。")
        sb.appendLine()
        sb.appendLine("## 这些指令是用什么权限执行的（很重要）")
        sb.appendLine("- 这些命令不是普通 App 权限执行的，而是模块通过 AxManager 以**特权身份**下发的系统命令：")
        sb.appendLine("  - 未 Root 设备：`shell` 身份，UID **2000**（与 `adb shell` 同一个身份）；")
        sb.appendLine("  - 已 Root 设备：`root` 身份，UID **0**。")
        sb.appendLine("- 因此它们能改动 `settings`、`setprop`、`pm`、`cmd`、`am`、`wm`、`/data`、`/system` 等敏感目标，")
        sb.appendLine("  普通 App 身份是改不动的。")
        sb.appendLine("- 你给出的恢复命令**同样要以 shell(2000) 或 root(0) 身份执行**，否则会 Permission denied；")
        sb.appendLine("  如果你是在一个只能跑普通 App 权限的环境里，请明确提示「这些命令需要 ADB/Shizuku/Root 执行」。")
        sb.appendLine()
        sb.appendLine("## 设备信息")
        sb.appendLine("- 品牌：${Build.BRAND}")
        sb.appendLine("- 厂商：${Build.MANUFACTURER}")
        sb.appendLine("- 型号：${Build.MODEL}")
        sb.appendLine("- Android 版本：${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        sb.appendLine("- 最高可用权限 UID：2000（shell / ADB）；如已 Root 则为 0（root）")
        sb.appendLine("- 当前特权通道：${PrivilegedShell.channelName(channel)}")
        sb.appendLine("- 清单生成时间：$time")
        sb.appendLine()
        sb.appendLine("## 恢复要求（必须逐条遵守）")
        sb.appendLine("1. 只回退下面清单里出现过的写入，不要顺手改任何其它项。")
        sb.appendLine("2. 设置项恢复到系统默认：用 `settings delete <namespace> <key>`，不要写成固定值。")
        sb.appendLine("3. 应用状态类：`pm disable-user`/`pm hide`/`pm suspend` 分别用 `pm enable`/`pm unhide`/`pm unsuspend` 还原；")
        sb.appendLine("   被 `pm uninstall` 卸掉的应用用 `pm install-existing <包名>` 恢复安装；权限用 `pm grant`/`pm revoke` 反向。")
        sb.appendLine("4. 文件类写入：不要直接删除系统目录下的文件；确需回退时先备份原文件，再给出备份/回退两条命令。")
        sb.appendLine("5. 每条命令前用一行 `#` 注释，说明它在回退哪个模块的哪一条指令。")
        sb.appendLine("6. 不要给出 reboot、清数据、卸载系统应用、改分区等高风险动作。")
        sb.appendLine("7. 命令按依赖顺序排列，末尾附一段校验命令（读取当前值/文件内容）用于确认恢复结果。")
        sb.appendLine("8. 无法确定如何恢复的项，单独列在「需人工确认」小节，不要猜。")
        sb.appendLine()
        sb.appendLine("## 模块指令清单（本机模块真实执行过的命令）")
        if (groups.isEmpty()) {
            sb.appendLine("（本机暂无运行记录）")
        } else {
            for (group in groups) {
                val commands = runCatching { RunRecorderStore.readGroupCommands(group) }
                    .getOrDefault(emptyList())
                if (commands.isEmpty()) continue
                sb.appendLine()
                sb.appendLine("### ${group.name}（${commands.size} 条）")
                commands.forEachIndexed { index, cmd ->
                    sb.appendLine("${index + 1}. $cmd")
                }
            }
        }
        sb.toString()
    }
}