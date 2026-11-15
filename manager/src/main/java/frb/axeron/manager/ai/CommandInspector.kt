package frb.axeron.manager.ai

import android.content.Context
import android.provider.Settings
import android.util.Log
import frb.axeron.api.AxeronPluginService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.min

/**
 * 指令检查器：把「运行记录仪」里抓到的一条真实指令，变成可读、可改、可执行的对象。
 *
 * ## 为什么单独成层
 * 抓取日志里的 `cmd` 形态是 `StraceHelper.finishExecveArgs()` 的产物：
 * **argv0 的 basename + 空格拼接的其他参数**（引号已丢失）；
 * sh -x 通道还会把 `> /path` / `>> /path` 这类重定向片段拼回命令尾部。
 * 所以这里必须先做一层「归一化 + 分类」，才能知道
 * 「它改的是哪个键 / 哪个文件 / 哪个包」，进而读出该目标的当前现场。
 *
 * ## 三层职责
 * 1. [parse]   —— 归一化（剥掉 sh -c / busybox / timeout 等包装器）并归类到 [Kind]
 * 2. [inspect] —— 读取目标现场（尽量用免特权通道：ContentResolver / File / PackageManager /
 *                 SystemProperties，失败再退回特权 shell）
 * 3. [buildSchemes] —— 针对该目标列出**所有**可选的修改方案（恢复原值、回落默认、反向切换、删除、自定义…）
 *
 * ## 边界
 * 只读现场；真正写系统必须由用户在界面上点某个方案后调用 [apply]。
 * 所有特权调用都显式 `skipAnalyzer = true`（这是内部工具命令，不是模块脚本，
 * 不能再次进入 AI 拦截挂钩导致挂起）。
 */
object CommandInspector {
    private const val TAG = "CommandInspector"

    /** 现场文本（文件内容）展示上限，避免超长文件把 Compose 拖垮。 */
    private const val MAX_TEXT = 12_000

    /** 单条命令执行超时。 */
    private const val EXEC_TIMEOUT_MS = 25_000L

    /**
     * 只读现场的超时。
     *
     * 比执行短得多：悬浮层一打开就要读现场，如果特权服务没就绪，
     * 读操作最多只等这么久，避免界面上一直转圈。
     */
    private const val READ_TIMEOUT_MS = 6_000L

    /** heredoc 结束标记（内容里出现同名行概率极低）。 */
    private const val HEREDOC_END = "__AXM_EOF__"

    /** 指令所属的「指令族」，决定读取什么现场、能给出哪些方案。 */
    enum class Kind {
        SETTINGS,
        PROP,
        FILE_WRITE,
        FILE_APPEND,
        FILE_PERM,
        FILE_OWNER,
        FILE_COPY,
        FILE_MOVE,
        FILE_DELETE,
        DIR_CREATE,
        FILE_LINK,
        PKG_STATE,
        PKG_INSTALL,
        PKG_UNINSTALL,
        PKG_CLEAR,
        PKG_PERM,
        APP_OPS,
        ACTIVITY,
        WINDOW,
        DPM,
        MOUNT,
        CONTENT,
        OVERLAY,
        PROCESS,
        READ_ONLY,
        SCAFFOLD,
        UNKNOWN,

        /**
         * 【v1.5.5】`cmd <service> <sub>` 这类「系统内部服务子命令」。
         *
         * 以前只有 package / appops / overlay / activity / window 五个服务有解析，
         * 其余（deviceidle / settings / notification / jobscheduler / netstats / wifi …）
         * 全落到 [UNKNOWN] → 详情页既读不到现场，也没有任何方案，用户看到的就是「不可操作」。
         *
         * 现在统一归到这里：现场用「读取子命令」去问系统，方案里给「原样重跑 / 反向子命令 /
         * 自定义执行」三条兜底，保证任何 cmd 子命令都不是死路。
         */
        CMD_RAW;

        /** 界面上显示的分类名。 */
        val label: String
            get() = when (this) {
                SETTINGS -> "系统设置"
                PROP -> "系统属性"
                FILE_WRITE -> "写文件"
                FILE_APPEND -> "追加文件"
                FILE_PERM -> "修改权限"
                FILE_OWNER -> "修改属主"
                FILE_COPY -> "复制文件"
                FILE_MOVE -> "移动文件"
                FILE_DELETE -> "删除文件"
                DIR_CREATE -> "创建目录"
                FILE_LINK -> "创建链接"
                PKG_STATE -> "应用启用状态"
                PKG_INSTALL -> "安装应用"
                PKG_UNINSTALL -> "卸载应用"
                PKG_CLEAR -> "清除数据"
                PKG_PERM -> "运行时权限"
                APP_OPS -> "应用操作权限"
                ACTIVITY -> "应用进程/界面"
                WINDOW -> "屏幕参数"
                DPM -> "设备所有者"
                MOUNT -> "挂载"
                CONTENT -> "ContentProvider"
                OVERLAY -> "RRO 叠加层"
                PROCESS -> "进程信号"
                READ_ONLY -> "只读查询"
                SCAFFOLD -> "脚本脚手架"
                UNKNOWN -> "其他"
                CMD_RAW -> "系统服务子命令"
            }
    }

    /** 重定向类型。 */
    enum class Redirect { WRITE, APPEND }

    /**
     * 一条指令的归一化结果。
     *
     * @param op 子动作（put / delete / disable-user / hide / chmod …）
     * @param target 主目标（settings 键 / 属性名 / 包名 / 挂载点）
     * @param value 写入值（settings 值、setprop 值、写入文件的内容、wm 参数）
     * @param path 文件路径（文件类指令）
     * @param pkg 包名（包管理类指令）
     * @param namespace settings 命名空间（global / secure / system）
     * @param summary 一句话人话描述，用于详情页顶部
     */
    data class Parsed(
        val raw: String,
        val kind: Kind,
        val op: String = "",
        val target: String = "",
        val value: String? = null,
        val path: String? = null,
        val pkg: String? = null,
        val namespace: String? = null,
        val redirect: Redirect? = null,
        val summary: String = "",
    )

    /** 文件内容里命中写入片段的行。 */
    data class MatchLine(val lineNo: Int, val text: String)

    /**
     * 目标现场。
     *
     * @param readable 是否成功读到（false 时看 [note]）
     * @param currentValue 当前值（设置值 / 属性值 / 包启用状态 / 屏幕参数 / 挂载信息）
     * @param extraLines 附加信息（存在、大小、权限、属主…）
     * @param fileContent 文件内容（可能被截断）
     * @param fileTruncated 文件内容是否被截断
     * @param matched 命中写入片段的行
     */
    data class Inspection(
        val readable: Boolean = false,
        val currentValue: String? = null,
        val extraLines: List<Pair<String, String>> = emptyList(),
        val fileContent: String? = null,
        val fileTruncated: Boolean = false,
        val matched: List<MatchLine> = emptyList(),
        val note: String? = null,
    )

    /** 一条可执行的修改方案。 */
    data class Scheme(
        val id: String,
        val label: String,
        val command: String,
        val tone: Tone = Tone.NEUTRAL,
        val hint: String? = null,
    ) {
        enum class Tone { NEUTRAL, RECOVER, DANGER }
    }

    /** 执行结果。 */
    data class ApplyResult(
        val ok: Boolean,
        val code: Int,
        val out: String,
        val err: String,
    )

    // ==================================================================================
    // ① 解析
    // ==================================================================================

    /** 会被剥掉的「包装器」命令：它们只是壳，真正的动作在后面。 */
    private val SHELL_WORDS = setOf("sh", "ash", "mksh", "hush", "dash", "bash")

    /** 纯脚手架关键字：没有任何业务含义，不需要给方案。 */
    private val SCAFFOLD_WORDS = setOf(
        "cd", "export", "local", "declare", "readonly", "typeset", "unset", "shift", "set",
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case",
        "esac", "return", "exit", "true", "false", ":", "source", ".", "eval", "trap",
        "umask", "wait", "break", "continue", "read",
    )

    /** 只读查询类：能读现场但没有可改的东西。 */
    private val READ_ONLY_WORDS = setOf(
        "id", "whoami", "getprop", "dumpsys", "ps", "ls", "cat", "grep", "egrep", "fgrep",
        "head", "tail", "wc", "uname", "date", "pidof", "pgrep", "which", "command", "type",
        "printenv", "stat", "du", "df", "free", "uptime", "sleep", "usleep", "sync",
        "echo", "printf", "log", "logcat", "monkey", "pm", "cmd", "wm", "svc", "service",
    )

    /**
     * 解析一条指令。永远不抛异常；无法识别的归到 [Kind.UNKNOWN]。
     */
    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed(raw = raw, kind = Kind.UNKNOWN, summary = "空指令")
        // 1) 先剥离尾部的写重定向（`2>/dev/null` 这类丢弃 stderr 的不算写）
        val redirectInfo = extractRedirect(text)
        val body = redirectInfo.body

        // 2) 分词 + 剥包装器
        val tokens = tokenize(body).toMutableList()
        if (tokens.isEmpty()) {
            return Parsed(raw = raw, kind = Kind.UNKNOWN, summary = "无法解析的指令")
        }
        stripWrappers(tokens)
        if (tokens.isEmpty()) {
            return Parsed(raw = raw, kind = Kind.UNKNOWN, summary = "无法解析的指令")
        }

        val head = tokens[0].substringAfterLast('/').lowercase()
        val args = tokens.drop(1)
        val redirect = redirectInfo.mode
        val redirectPath = redirectInfo.path

        // `mkdir` 这类「第二段是路径」的解析结果
        val out: Parsed = when (head) {
            // ---------- 系统设置 ----------
            "settings" -> parseSettings(raw, args, redirect, redirectPath)

            // ---------- 只读读取（要能看到「读到了什么」） ----------
            // 【v1.5.5】这一类以前只是被 READ_ONLY_WORDS 兜底成「只读查询」，
            // Parsed 里不带 target/path，于是 inspect() 只能回一句「没有可读取的目标状态」。
            // 用户在详情页里看不到 getprop ro.board.platform / cat /proc/... 究竟读到了什么。
            // 现在把读取对象填进 Parsed，inspect() 就会真的去读并把内容显示出来。
            "getprop" -> {
                val key = args.firstOrNull { !it.startsWith("-") }
                if (key.isNullOrBlank()) {
                    Parsed(
                        raw = raw, kind = Kind.READ_ONLY, op = "getprop",
                        summary = "列出全部系统属性",
                    )
                } else {
                    // 归到 PROP：既能把当前值读出来，也能直接给出「改成其它值 / 删除」的方案。
                    Parsed(
                        raw = raw, kind = Kind.PROP, op = "getprop", target = key,
                        summary = "读取系统属性 $key 的当前值",
                    )
                }
            }

            "cat", "head", "tail", "less", "more", "nl", "cmp" -> {
                val path = args.lastOrNull { !it.startsWith("-") }
                Parsed(
                    raw = raw, kind = Kind.READ_ONLY, op = head,
                    path = path, target = path.orEmpty(),
                    summary = "读取 ${path ?: "标准输入"} 的内容",
                )
            }

            "ls", "du", "df", "find", "stat", "wc", "file", "readlink", "realpath" -> {
                val path = args.lastOrNull { !it.startsWith("-") }
                Parsed(
                    raw = raw, kind = Kind.READ_ONLY, op = head,
                    path = path, target = path.orEmpty(),
                    summary = "查看 ${path ?: "当前目录"} 的信息",
                )
            }

            // 【v1.5.8 需求②】过滤 / 编码类只读工具。
            // 以前这一类没有独立分支，`Parsed.path` 是空的，于是详情页只能显示
            // 「这条指令本身不产生可读取的状态」，用户看不到它到底读了哪个文件、内容是什么。
            // 这里把「最后一个像路径的参数」当作读取目标，inspect 就会真的去读并展示内容。
            "grep", "egrep", "fgrep", "awk", "strings", "xxd", "od", "hexdump",
            "md5sum", "sha1sum", "sha256sum", "base64", "tr", "cut", "sort", "uniq", "diff" -> {
                val path = args.lastOrNull { !it.startsWith("-") && it.contains('/') }
                Parsed(
                    raw = raw, kind = Kind.READ_ONLY, op = head,
                    path = path, target = path.orEmpty(),
                    summary = "读取 ${path ?: "标准输入"} 的内容",
                )
            }

            // `sed -i` 是就地改写（写入），不带 -i 才是过滤读取。必须分开，否则写操作会被当成只读。
            "sed" -> {
                val inPlace = args.any {
                    it == "-i" || it.startsWith("-i") || it.startsWith("--in-place")
                }
                val path = args.lastOrNull { !it.startsWith("-") && it.contains('/') }
                if (inPlace && !path.isNullOrBlank()) {
                    Parsed(
                        raw = raw, kind = Kind.FILE_WRITE, op = "sed",
                        path = path, target = path,
                        summary = "用 sed 就地改写 $path",
                    )
                } else {
                    Parsed(
                        raw = raw, kind = Kind.READ_ONLY, op = "sed",
                        path = path, target = path.orEmpty(),
                        summary = "读取 ${path ?: "标准输入"} 的内容",
                    )
                }
            }

            "dumpsys" -> {
                val svc = args.firstOrNull { !it.startsWith("-") }
                Parsed(
                    raw = raw, kind = Kind.READ_ONLY, op = "dumpsys",
                    target = svc.orEmpty(),
                    summary = "转储 ${svc ?: "全部系统服务"} 的当前状态",
                )
            }

            "ps", "id", "uname", "free", "uptime", "pidof", "pgrep", "printenv",
            "which", "logcat", "vmstat", "iostat", "top", "getenforce", "hostname" -> Parsed(
                raw = raw, kind = Kind.READ_ONLY, op = head,
                target = args.joinToString(" "),
                summary = "查询系统运行状态（$head）",
            )

            // ---------- 系统属性 ----------
            "setprop" -> {
                val key = args.getOrNull(0)
                val value = args.drop(1).joinToString(" ").ifBlank { null }
                if (key.isNullOrBlank()) mkUnknown(raw, "setprop 缺少属性名")
                else Parsed(
                    raw = raw,
                    kind = Kind.PROP,
                    op = "setprop",
                    target = key,
                    value = value,
                    summary = "把系统属性 $key 设为 ${value ?: "（空）"}",
                )
            }

            "resetprop" -> {
                val key = args.firstOrNull { !it.startsWith("-") }
                val value = args.dropWhile { it != key }.drop(1).joinToString(" ").ifBlank { null }
                if (key.isNullOrBlank()) mkUnknown(raw, "resetprop 缺少属性名")
                else Parsed(
                    raw = raw,
                    kind = Kind.PROP,
                    op = if (args.any { it == "--delete" || it == "-d" }) "delete" else "resetprop",
                    target = key,
                    value = value,
                    summary = "重置系统属性 $key",
                )
            }

            // ---------- 文件写入（重定向 / tee / dd / sed -i） ----------
            "echo", "printf", "print" -> {
                val value = args.joinToString(" ")
                if (redirect != null && !redirectPath.isNullOrBlank()) {
                    Parsed(
                        raw = raw,
                        kind = if (redirect == Redirect.APPEND) Kind.FILE_APPEND else Kind.FILE_WRITE,
                        op = if (redirect == Redirect.APPEND) "append" else "write",
                        value = unquote(value),
                        path = redirectPath,
                        redirect = redirect,
                        summary = (if (redirect == Redirect.APPEND) "向 " else "把 ") +
                            "$redirectPath " + (if (redirect == Redirect.APPEND) "追加内容" else "改写为指定内容"),
                    )
                } else {
                    Parsed(raw = raw, kind = Kind.READ_ONLY, op = "echo", value = value, summary = "纯输出，不改任何状态")
                }
            }

            "tee" -> {
                val append = args.any { it == "-a" || it == "--append" }
                val path = args.lastOrNull { !it.startsWith("-") }
                if (path.isNullOrBlank()) mkUnknown(raw, "tee 缺少目标文件")
                else Parsed(
                    raw = raw,
                    kind = if (append) Kind.FILE_APPEND else Kind.FILE_WRITE,
                    op = if (append) "append" else "write",
                    path = path,
                    summary = "把标准输入${if (append) "追加" else "写入"}到 $path",
                )
            }

            "dd" -> {
                val of = args.firstOrNull { it.startsWith("of=") }?.removePrefix("of=")
                val ifArg = args.firstOrNull { it.startsWith("if=") }?.removePrefix("if=")
                if (of.isNullOrBlank()) mkUnknown(raw, "dd 缺少 of= 目标")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_WRITE,
                    op = "dd",
                    path = of,
                    value = null,
                    summary = "用 dd 覆写 $of（块级写入，原始内容通常无法恢复）" +
                        if (!ifArg.isNullOrBlank()) "，源：$ifArg" else "",
                )
            }

            "sed" -> {
                if (args.any { it == "-i" || it.startsWith("-i") || it.contains("--in-place") }) {
                    val path = args.lastOrNull { !it.startsWith("-") }
                    val expr = args.firstOrNull { !it.startsWith("-") && it != path }
                    Parsed(
                        raw = raw,
                        kind = Kind.FILE_WRITE,
                        op = "sed",
                        path = path,
                        value = expr,
                        summary = "用 sed 原地改写 ${path ?: "文件"}",
                    )
                } else {
                    Parsed(raw = raw, kind = Kind.READ_ONLY, op = "sed", summary = "sed 只读处理，不落盘")
                }
            }

            "touch" -> {
                val path = args.firstOrNull { !it.startsWith("-") }
                if (path.isNullOrBlank()) mkUnknown(raw, "touch 缺少目标文件")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_WRITE,
                    op = "touch",
                    path = path,
                    summary = "创建/触碰文件 $path",
                )
            }

            // ---------- 文件属性 / 结构 ----------
            "chmod" -> {
                val mode = args.getOrNull(0)
                val path = args.getOrNull(1)
                if (mode.isNullOrBlank() || path.isNullOrBlank()) mkUnknown(raw, "chmod 参数不完整")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_PERM,
                    op = "chmod",
                    path = path,
                    value = mode,
                    summary = "把 $path 的权限改为 $mode",
                )
            }

            "chown" -> {
                val owner = args.getOrNull(0)
                val path = args.getOrNull(1)
                if (owner.isNullOrBlank() || path.isNullOrBlank()) mkUnknown(raw, "chown 参数不完整")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_OWNER,
                    op = "chown",
                    path = path,
                    value = owner,
                    summary = "把 $path 的属主改为 $owner",
                )
            }

            "cp" -> {
                val dst = args.lastOrNull { !it.startsWith("-") }
                val src = args.firstOrNull { !it.startsWith("-") }
                if (dst.isNullOrBlank()) mkUnknown(raw, "cp 缺少目标")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_COPY,
                    op = "cp",
                    path = dst,
                    value = src,
                    summary = "复制 ${src ?: "文件"} 到 $dst",
                )
            }

            "mv" -> {
                val dst = args.lastOrNull { !it.startsWith("-") }
                val src = args.firstOrNull { !it.startsWith("-") }
                if (dst.isNullOrBlank()) mkUnknown(raw, "mv 缺少目标")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_MOVE,
                    op = "mv",
                    path = dst,
                    value = src,
                    summary = "移动/改名 ${src ?: "文件"} 到 $dst",
                )
            }

            "rm", "rmdir", "unlink" -> {
                val path = args.lastOrNull { !it.startsWith("-") }
                if (path.isNullOrBlank()) mkUnknown(raw, "rm 缺少目标")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_DELETE,
                    op = head,
                    path = path,
                    summary = "删除 $path（不可逆）",
                )
            }

            "mkdir" -> {
                val path = args.lastOrNull { !it.startsWith("-") }
                if (path.isNullOrBlank()) mkUnknown(raw, "mkdir 缺少目录")
                else Parsed(
                    raw = raw,
                    kind = Kind.DIR_CREATE,
                    op = "mkdir",
                    path = path,
                    summary = "创建目录 $path",
                )
            }

            "ln" -> {
                val dst = args.lastOrNull { !it.startsWith("-") }
                if (dst.isNullOrBlank()) mkUnknown(raw, "ln 缺少目标")
                else Parsed(
                    raw = raw,
                    kind = Kind.FILE_LINK,
                    op = "ln",
                    path = dst,
                    summary = "创建链接 $dst",
                )
            }

            // ---------- 包管理 ----------
            "pm" -> parsePm(raw, args)

            // ---------- 活动/进程 ----------
            "am" -> {
                val sub = args.firstOrNull()?.lowercase().orEmpty()
                val pkg = guessPkg(args)
                when (sub) {
                    "force-stop" -> Parsed(
                        raw = raw, kind = Kind.ACTIVITY, op = "force-stop", pkg = pkg,
                        summary = "强制停止 ${pkg ?: "应用"}",
                    )
                    "kill", "kill-all" -> Parsed(
                        raw = raw, kind = Kind.ACTIVITY, op = sub, pkg = pkg,
                        summary = "结束 ${pkg ?: "应用"} 的后台进程",
                    )
                    "start", "startservice", "start-activity", "broadcast" -> Parsed(
                        raw = raw, kind = Kind.ACTIVITY, op = sub, pkg = pkg,
                        summary = "启动/唤醒 ${pkg ?: "组件"}",
                    )
                    else -> Parsed(raw = raw, kind = Kind.ACTIVITY, op = sub, pkg = pkg, summary = "am $sub")
                }
            }

            "cmd" -> parseCmd(raw, args)

            // ---------- 屏幕参数 ----------
            "wm" -> {
                val sub = args.firstOrNull()?.lowercase().orEmpty()
                val value = args.drop(1).joinToString(" ").ifBlank { null }
                Parsed(
                    raw = raw,
                    kind = Kind.WINDOW,
                    op = sub,
                    target = sub,
                    value = value,
                    summary = when (sub) {
                        "size" -> "把屏幕分辨率改为 ${value ?: "(复位)"}"
                        "density" -> "把屏幕密度改为 ${value ?: "(复位)"}"
                        "overscan" -> "设置屏幕 overscan 为 ${value ?: "(复位)"}"
                        else -> "wm $sub"
                    },
                )
            }

            // ---------- 设备所有者 ----------
            "axeron-dpm" -> {
                val sub = args.firstOrNull()?.lowercase().orEmpty()
                val target = args.getOrNull(1)
                Parsed(
                    raw = raw,
                    kind = Kind.DPM,
                    op = sub,
                    target = target.orEmpty(),
                    pkg = target,
                    value = args.drop(2).joinToString(" ").ifBlank { null },
                    summary = when (sub) {
                        "hide" -> "隐藏应用 ${target ?: ""}"
                        "unhide" -> "取消隐藏应用 ${target ?: ""}"
                        "suspend" -> "挂起应用 ${target ?: ""}"
                        "unsuspend" -> "取消挂起应用 ${target ?: ""}"
                        "block-uninstall" -> "禁止卸载 ${target ?: ""}"
                        "unblock-uninstall" -> "允许卸载 ${target ?: ""}"
                        "cleardata" -> "清除数据 ${target ?: ""}（不可逆）"
                        "set-global", "set-secure" -> "写入${if (sub == "set-global") "全局" else "安全"}设置 ${target ?: ""}"
                        "set-anim" -> "设置动画倍速 ${target ?: ""}"
                        "grant" -> "授予权限 ${target ?: ""}"
                        "deny" -> "拒绝权限 ${target ?: ""}"
                        "force-stop" -> "强制停止 ${target ?: ""}"
                        "reboot" -> "重启设备"
                        "locknow" -> "立即锁屏"
                        else -> "axeron-dpm $sub"
                    },
                )
            }

            // ---------- 挂载 ----------
            "mount" -> {
                val target = args.lastOrNull { !it.startsWith("-") && !it.contains("=") }
                Parsed(
                    raw = raw,
                    kind = Kind.MOUNT,
                    op = "mount",
                    target = target.orEmpty(),
                    path = target,
                    value = args.joinToString(" "),
                    summary = "挂载 ${target ?: "分区"}",
                )
            }

            "umount" -> {
                val target = args.lastOrNull { !it.startsWith("-") }
                Parsed(
                    raw = raw,
                    kind = Kind.MOUNT,
                    op = "umount",
                    target = target.orEmpty(),
                    path = target,
                    summary = "卸载挂载点 ${target ?: ""}",
                )
            }

            // ---------- ContentProvider ----------
            "content" -> {
                val sub = args.firstOrNull()?.lowercase().orEmpty()
                val uri = args.firstOrNull { it.startsWith("--uri") }
                    ?.substringAfter("=")?.trim('\'', '"')
                    ?: args.getOrNull(1)
                Parsed(
                    raw = raw,
                    kind = Kind.CONTENT,
                    op = sub,
                    target = uri.orEmpty(),
                    summary = "content $sub ${uri ?: ""}",
                )
            }

            // ---------- 进程信号 ----------
            "kill", "pkill", "killall" -> Parsed(
                raw = raw,
                kind = Kind.PROCESS,
                op = head,
                target = args.lastOrNull { !it.startsWith("-") }.orEmpty(),
                summary = "向进程发送信号（不可逆）",
            )

            else -> when {
                head in SCAFFOLD_WORDS -> Parsed(raw = raw, kind = Kind.SCAFFOLD, op = head, summary = "脚本脚手架，无系统改动")
                head in READ_ONLY_WORDS -> {
                    // 【v1.5.8 需求②】只读类指令尽量补一个「读取目标路径」。
                    // 不补的话 inspectReadOnly 拿不到 path，只能回一句
                    // 「这条指令本身不产生可读取的状态」，用户永远看不到它读到了什么。
                    // 只认「以 / 开头的参数」，`sleep 5` / `echo ok` 这类不会被误当成文件。
                    val p = args.firstOrNull { it.startsWith("/") && it.length > 1 }
                    Parsed(
                        raw = raw, kind = Kind.READ_ONLY, op = head,
                        path = p,
                        summary = if (p != null) "读取 $p 的内容" else "只读查询，无系统改动",
                    )
                }
                else -> Parsed(raw = raw, kind = Kind.UNKNOWN, op = head, summary = "未归类的指令（$head）")
            }
        }

        // 兜底：带写重定向且当前被判定为只读/未知 → 一律按「写文件」处理（真写入不能漏）
        return if (out.kind == Kind.READ_ONLY || out.kind == Kind.UNKNOWN) {
            if (redirect != null && !redirectPath.isNullOrBlank()) {
                out.copy(
                    kind = if (redirect == Redirect.APPEND) Kind.FILE_APPEND else Kind.FILE_WRITE,
                    path = redirectPath,
                    redirect = redirect,
                    value = out.value ?: unquote(tokens.drop(1).joinToString(" ")),
                    summary = "写入文件 $redirectPath",
                )
            } else out
        } else out
    }

    private fun parseSettings(
        raw: String,
        args: List<String>,
        redirect: Redirect?,
        redirectPath: String?,
    ): Parsed {
        val sub = args.getOrNull(0)?.lowercase().orEmpty()
        val ns = args.getOrNull(1)?.lowercase()
        val key = args.getOrNull(2)
        val value = args.drop(3).joinToString(" ").ifBlank { null }
        return when (sub) {
            "get" -> Parsed(
                raw = raw, kind = Kind.SETTINGS, op = "get", namespace = ns,
                target = key.orEmpty(), summary = "读取 ${ns ?: ""} 设置 $key",
            )
            "put" -> Parsed(
                raw = raw, kind = Kind.SETTINGS, op = "put", namespace = ns,
                target = key.orEmpty(), value = value,
                summary = "把 ${ns ?: ""} 设置 $key 改为 ${value ?: "（空）"}",
            )
            "delete" -> Parsed(
                raw = raw, kind = Kind.SETTINGS, op = "delete", namespace = ns,
                target = key.orEmpty(), summary = "删除 ${ns ?: ""} 设置 $key",
            )
            "list" -> Parsed(
                raw = raw, kind = Kind.READ_ONLY, op = "list", namespace = ns,
                summary = "列出 ${ns ?: ""} 设置（只读）",
            )
            else -> if (redirect != null && !redirectPath.isNullOrBlank()) {
                Parsed(
                    raw = raw, kind = Kind.FILE_WRITE, op = "write", path = redirectPath,
                    redirect = redirect, summary = "把 settings 输出写入 $redirectPath",
                )
            } else {
                mkUnknown(raw, "settings $sub")
            }
        }
    }

    private fun parsePm(raw: String, args: List<String>): Parsed {
        val sub = args.firstOrNull()?.lowercase().orEmpty()
        val pkg = guessPkg(args)
        return when (sub) {
            "disable-user", "disable", "set-enabled-setting" -> Parsed(
                raw = raw, kind = Kind.PKG_STATE, op = "disable", pkg = pkg,
                summary = "停用应用 ${pkg ?: ""}",
            )
            "enable" -> Parsed(
                raw = raw, kind = Kind.PKG_STATE, op = "enable", pkg = pkg,
                summary = "启用应用 ${pkg ?: ""}",
            )
            "uninstall" -> Parsed(
                raw = raw, kind = Kind.PKG_UNINSTALL, op = "uninstall", pkg = pkg,
                summary = "卸载应用 ${pkg ?: ""}",
            )
            "install", "install-existing", "install-create", "install-write", "install-commit" -> Parsed(
                raw = raw, kind = Kind.PKG_INSTALL, op = sub, pkg = pkg,
                summary = "安装应用 ${pkg ?: ""}",
            )
            "clear" -> Parsed(
                raw = raw, kind = Kind.PKG_CLEAR, op = "clear", pkg = pkg,
                summary = "清除应用数据 ${pkg ?: ""}（不可逆）",
            )
            "grant", "revoke" -> Parsed(
                raw = raw, kind = Kind.PKG_PERM, op = sub, pkg = args.getOrNull(1),
                target = args.getOrNull(2).orEmpty(),
                summary = "${if (sub == "grant") "授予" else "收回"}权限 ${args.getOrNull(2) ?: ""}",
            )
            "hide", "unhide", "suspend", "unsuspend" -> Parsed(
                raw = raw, kind = Kind.PKG_STATE, op = sub, pkg = pkg,
                summary = "pm $sub ${pkg ?: ""}",
            )
            else -> Parsed(raw = raw, kind = Kind.READ_ONLY, op = "pm $sub", pkg = pkg, summary = "pm $sub（只读）")
        }
    }

    private fun parseCmd(raw: String, args: List<String>): Parsed {
        val service = args.getOrNull(0)?.lowercase().orEmpty()
        val sub = args.getOrNull(1)?.lowercase().orEmpty()
        return when (service) {
            "package" -> {
                val pkg = guessPkg(args)
                when (sub) {
                    "set-enabled", "enable", "disable" -> Parsed(
                        raw = raw, kind = Kind.PKG_STATE, op = if (sub == "disable") "disable" else "enable",
                        pkg = pkg, summary = "cmd package $sub ${pkg ?: ""}",
                    )
                    "install-existing" -> Parsed(
                        raw = raw, kind = Kind.PKG_INSTALL, op = sub, pkg = pkg,
                        summary = "恢复安装 ${pkg ?: ""}",
                    )
                    "uninstall" -> Parsed(
                        raw = raw, kind = Kind.PKG_UNINSTALL, op = sub, pkg = pkg,
                        summary = "卸载 ${pkg ?: ""}",
                    )
                    "clear" -> Parsed(
                        raw = raw, kind = Kind.PKG_CLEAR, op = sub, pkg = pkg,
                        summary = "清除数据 ${pkg ?: ""}（不可逆）",
                    )
                    "grant", "revoke" -> Parsed(
                        raw = raw, kind = Kind.PKG_PERM, op = sub, pkg = args.getOrNull(2),
                        target = args.getOrNull(3).orEmpty(),
                        summary = "cmd package $sub ${args.getOrNull(3) ?: ""}",
                    )
                    "hide", "unhide", "suspend", "unsuspend" -> Parsed(
                        raw = raw, kind = Kind.PKG_STATE, op = sub, pkg = pkg,
                        summary = "cmd package $sub ${pkg ?: ""}",
                    )
                    else -> Parsed(raw = raw, kind = Kind.CMD_RAW, op = "package $sub", pkg = pkg, summary = "cmd package $sub")
                }
            }
            "appops" -> {
                val pkg = args.getOrNull(2)
                val op = args.getOrNull(3).orEmpty()
                val mode = args.getOrNull(4)
                when (sub) {
                    "set" -> Parsed(
                        raw = raw, kind = Kind.APP_OPS, op = "set", pkg = pkg, target = op,
                        value = mode, summary = "把 $pkg 的 $op 权限设为 ${mode ?: ""}",
                    )
                    "reset" -> Parsed(
                        raw = raw, kind = Kind.APP_OPS, op = "reset", pkg = pkg, target = op,
                        summary = "重置 $pkg 的 $op 权限",
                    )
                    else -> Parsed(raw = raw, kind = Kind.APP_OPS, op = sub, pkg = pkg, target = op, summary = "appops $sub")
                }
            }
            "overlay" -> Parsed(
                raw = raw, kind = Kind.OVERLAY, op = sub,
                pkg = args.lastOrNull { it.contains(".") },
                summary = "RRO 叠加层 $sub",
            )
            "activity" -> Parsed(
                raw = raw, kind = Kind.ACTIVITY, op = "cmd activity $sub", pkg = guessPkg(args),
                summary = "cmd activity $sub",
            )
            "window" -> Parsed(
                raw = raw, kind = Kind.WINDOW, op = sub, target = sub,
                value = args.drop(2).joinToString(" ").ifBlank { null },
                summary = "屏幕参数 $sub",
            )
            else -> {
                // 【v1.5.5】其它 cmd 服务：deviceidle / settings / notification / jobscheduler /
                // netstats / wifi / bluetooth_manager / connectivity / power / input / media /
                // telephony / role / storage / uimode / user / display / battery / alarm / privacy …
                // 以前全部落到 UNKNOWN → 详情页只显示「不可操作」。现在统一按「系统服务子命令」
                // 处理：现场用读取子命令去问，方案给原样重跑 / 反向子命令 / 自定义执行。
                val opText = listOf(service, sub).filter { it.isNotBlank() }.joinToString(" ")
                Parsed(
                    raw = raw, kind = Kind.CMD_RAW, op = opText, target = opText,
                    pkg = guessPkg(args),
                    summary = if (opText.isBlank()) "cmd" else "系统服务 cmd $opText",
                )
            }
        }
    }

    private fun mkUnknown(raw: String, why: String): Parsed =
        Parsed(raw = raw, kind = Kind.UNKNOWN, summary = "未识别：$why")

    /** 按空格分词，处理单/双引号与反斜杠转义。 */
    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        var esc = false
        for (ch in s) {
            when {
                esc -> {
                    sb.append(ch)
                    esc = false
                }
                ch == '\\' -> esc = true
                quote != null -> if (ch == quote) quote = null else sb.append(ch)
                ch == '\'' || ch == '"' -> quote = ch
                ch.isWhitespace() -> if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 剥掉包装器（busybox / su / sh -c / timeout / nohup / stdbuf / nice / env）。
     *
     * 抓到的指令里常见 `sh -c pm disable-user ...`、`busybox timeout -s 9 10 sh -c ...`，
     * 不剥掉就会把真正的动作判成「sh」而失去全部信息。
     */
    private fun stripWrappers(tokens: MutableList<String>) {
        var guard = 0
        while (guard++ < 8 && tokens.isNotEmpty()) {
            val head = tokens[0].substringAfterLast('/').lowercase()
            when {
                head == "busybox" || head == "toybox" -> tokens.removeAt(0)

                head == "su" -> {
                    tokens.removeAt(0)
                    while (tokens.isNotEmpty() && tokens[0].startsWith("-")) {
                        val opt = tokens.removeAt(0)
                        // su -c <cmd>：-c 后面就是命令本体，继续循环即可
                        if (opt == "-c" || opt == "--command") break
                    }
                }

                head in SHELL_WORDS -> {
                    tokens.removeAt(0)
                    while (tokens.isNotEmpty() && tokens[0].startsWith("-")) {
                        tokens.removeAt(0)
                    }
                }

                head == "timeout" -> {
                    tokens.removeAt(0)
                    if (tokens.isNotEmpty() && !tokens[0].startsWith("-")) tokens.removeAt(0)
                    while (tokens.isNotEmpty() && tokens[0].startsWith("-")) tokens.removeAt(0)
                }

                head == "nohup" || head == "stdbuf" || head == "setsid" -> tokens.removeAt(0)

                head == "nice" || head == "ionice" -> {
                    tokens.removeAt(0)
                    while (tokens.isNotEmpty() && tokens[0].startsWith("-")) tokens.removeAt(0)
                }

                head == "env" -> {
                    tokens.removeAt(0)
                    while (tokens.isNotEmpty() && (tokens[0].contains("=") || tokens[0].startsWith("-"))) {
                        tokens.removeAt(0)
                    }
                }

                else -> return
            }
        }
    }

    /**
     * 从参数里猜包名：优先取「带点的、不以 - 开头」的 token；
     * 找不到再退化成最后一个非选项 token。
     */
    private fun guessPkg(args: List<String>): String? {
        val skipNext = HashSet<Int>()
        args.forEachIndexed { i, a ->
            if (a == "--user" || a == "-u" || a == "--user-id") skipNext.add(i + 1)
        }
        val candidates = args.filterIndexed { i, a ->
            i !in skipNext && !a.startsWith("-") && a != "--user"
        }
        return candidates.firstOrNull { it.contains(".") } ?: candidates.lastOrNull()
    }

    private fun unquote(s: String): String = s.trim().trim('\'', '"')

    private data class RedirectInfo(val body: String, val mode: Redirect?, val path: String?)

    /**
     * 尾部写重定向。
     *
     * 【v1.5.8 修复 · `echo x > /sys/... 2>/dev/null` 被判成「只读查询」】
     * 旧正则要求 `>` 前面是**空白**，于是 `2>/dev/null` 这种 fd 紧贴的写法完全匹配不上：
     * `extractRedirect` 直接返回 null → 前面的真写入 `/sys/...` 也就看不见 →
     * `echo` 命中只读名单 → 整条被判成「只读查询，无系统改动」，界面上既不显示写入值、
     * 也给不出恢复方案。
     * 现在 `\d*` 允许可选的 fd 数字（`>` / `2>` / `10>`），尾部的黑洞重定向会被剥掉后继续往前找。
     * `(?:^|\s)` 仍然要求重定向前面是空白，所以 `echo2>file` 这类不会被误认。
     */
    private val TAIL_REDIRECT = Regex("""(?:^|\s)\d*(>>?)\s*("[^"]*"|'[^']*'|[^\s;|&<>()]+)\s*$""")

    /** 尾部 fd 复制：`2>&1` / `>&2` / `1>&2`，不是写文件。 */
    private val TAIL_FD_COPY = Regex("""(?:^|\s)\d?>&\d+\s*$""")

    /** 这些目标写进去没有业务含义（黑洞 / 终端），从尾部剥掉后继续找真重定向。 */
    private fun isDiscardRedirectTarget(path: String): Boolean {
        val p = path.trim().trim('"', '\'')
        return p == "/dev/null" || p.startsWith("/dev/tty") || p.startsWith("/dev/pts/")
    }

    /**
     * 抽取尾部写重定向。
     *
     * 【v1.5.5 修复】以前只匹配一次尾部：`echo 1 > /a/b 2>/dev/null` 尾部命中的是
     * `>/dev/null`，于是整条被判成「纯输出」——真写入 `/a/b` 丢了，抓取阶段还会被过滤掉。
     * 现在先把尾部的 fd 复制 / 黑洞重定向逐段剥掉，再继续往前找真正的写目标。
     */
    private fun extractRedirect(text: String): RedirectInfo {
        var t = text
        var guard = 0
        while (guard++ < 8) {
            val fdCopy = TAIL_FD_COPY.find(t)
            if (fdCopy != null) {
                t = t.substring(0, fdCopy.range.first).trim()
                continue
            }
            val m = TAIL_REDIRECT.find(t) ?: break
            val mode = if (m.groupValues[1] == ">>") Redirect.APPEND else Redirect.WRITE
            val rawPath = unquote(m.groupValues[2])
            if (rawPath.isBlank() || isDiscardRedirectTarget(rawPath)) {
                t = t.substring(0, m.range.first).trim()
                continue
            }
            // `2>file`：fd 写在 `>` 前面时上面正则不会匹配（前面不是空白），故此处已是干净路径
            return RedirectInfo(t.substring(0, m.range.first).trim(), mode, rawPath)
        }
        return RedirectInfo(t.trim(), null, null)
    }

    // ==================================================================================
    // ② 读取现场
    // ==================================================================================

    /** 读取目标现场。始终返回对象，失败原因写在 [Inspection.note]。 */
    suspend fun inspect(context: Context, parsed: Parsed): Inspection = withContext(Dispatchers.IO) {
        runCatching {
            when (parsed.kind) {
                Kind.SETTINGS -> inspectSettings(context, parsed)
                Kind.PROP -> inspectProp(parsed)
                Kind.FILE_WRITE, Kind.FILE_APPEND, Kind.FILE_PERM, Kind.FILE_OWNER,
                Kind.FILE_COPY, Kind.FILE_MOVE, Kind.FILE_DELETE, Kind.DIR_CREATE,
                Kind.FILE_LINK -> inspectFile(context, parsed)
                Kind.PKG_STATE, Kind.PKG_UNINSTALL, Kind.PKG_INSTALL, Kind.PKG_CLEAR,
                Kind.PKG_PERM -> inspectPackage(context, parsed)
                Kind.APP_OPS -> inspectAppOps(parsed)
                Kind.WINDOW -> inspectWindow(context, parsed)
                Kind.MOUNT -> inspectMount(parsed)
                Kind.DPM -> inspectDpm(context, parsed)
                Kind.ACTIVITY -> inspectActivity(parsed)
                Kind.READ_ONLY -> inspectReadOnly(parsed)
                Kind.CMD_RAW -> inspectCmdService(parsed)
                else -> Inspection(
                    readable = false,
                    note = "这条指令没有可直接比对的目标状态；可在下方按原指令重新执行，或自定义一条命令执行",
                )
            }
        }.getOrElse { t ->
            Log.w(TAG, "inspect 失败", t)
            Inspection(readable = false, note = "读取现场失败：${t.message}")
        }
    }

    private suspend fun inspectSettings(context: Context, parsed: Parsed): Inspection {
        val ns = parsed.namespace ?: return Inspection(note = "无法识别设置命名空间")
        val key = parsed.target
        if (key.isBlank()) return Inspection(note = "无法识别设置键名")

        val direct = runCatching {
            when (ns) {
                "global" -> Settings.Global.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                "system" -> Settings.System.getString(context.contentResolver, key)
                else -> null
            }
        }.getOrNull()

        if (direct != null) {
            return Inspection(
                readable = true,
                currentValue = direct,
                extraLines = listOf("命名空间" to ns, "键名" to key),
            )
        }

        // 应用侧读不到（部分 ROM 限制）→ 退回特权通道确认「真的没有」还是「读不到」
        val shell = shRead("settings get $ns $key")
        if (shell != null) {
            val v = shell.trim().removePrefix("'").removeSuffix("'")
            val empty = v.isBlank() || v == "null"
            return Inspection(
                readable = true,
                currentValue = if (empty) null else v,
                extraLines = listOf("命名空间" to ns, "键名" to key) +
                    if (empty) listOf("当前状态" to "系统里没有这个键（使用默认行为）") else emptyList(),
                note = if (empty) "该键当前不存在" else null,
            )
        }
        return Inspection(
            readable = false,
            extraLines = listOf("命名空间" to ns, "键名" to key),
            note = "读不到当前值：应用侧无权限，特权通道未就绪",
        )
    }

    private fun inspectProp(parsed: Parsed): Inspection {
        val key = parsed.target
        if (key.isBlank()) return Inspection(note = "无法识别属性名")
        val value = readSystemProp(key)
        return Inspection(
            readable = true,
            currentValue = value.ifBlank { null },
            extraLines = listOf("属性" to key) +
                if (value.isBlank()) listOf("当前状态" to "属性不存在或为空") else emptyList(),
        )
    }

    /** 先反射 SystemProperties（免特权），失败退 `getprop` 进程。 */
    private fun readSystemProp(key: String): String {
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val v = (method.invoke(null, key) as? String).orEmpty()
            if (v.isNotBlank()) return v
        }.onFailure { /* 隐藏 API 不可用，走下面的进程通道 */ }
        return runCatching {
            val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            p.destroy()
            out
        }.getOrDefault("")
    }

    private suspend fun inspectFile(context: Context, parsed: Parsed): Inspection {
        val path = parsed.path
        if (path.isNullOrBlank()) return Inspection(note = "无法识别文件路径")
        val f = File(path)
        val extra = ArrayList<Pair<String, String>>()
        val exists = f.exists()
        extra.add("路径" to path)
        extra.add("存在" to if (exists) "是" else "否")
        if (exists) {
            extra.add("类型" to if (f.isDirectory) "目录" else "文件")
            if (f.isFile) extra.add("大小" to humanSize(f.length()))
            extra.add("可读" to if (f.canRead()) "是" else "否（应用侧无权限）")
        }

        var content: String? = null
        var truncated = false
        if (exists && f.isFile) {
            if (f.canRead()) {
                val r = readLimited(f, MAX_TEXT)
                content = r?.first
                truncated = r?.second ?: false
            } else {
                // 应用读不到的系统文件 → 用特权通道
                val shell = shRead("head -c $MAX_TEXT '$path'")
                if (shell != null) {
                    content = shell
                    truncated = shell.length >= MAX_TEXT
                    extra.add("读取通道" to "特权 shell")
                }
            }
        }

        // 权限与属主（toybox stat 支持 -c）
        val stat = shRead("stat -c '%a %U:%G' '$path'")
        if (!stat.isNullOrBlank()) {
            val parts = stat.trim().split(" ")
            if (parts.size >= 2) {
                extra.add("权限" to parts[0])
                extra.add("属主" to parts[1])
            }
        }

        // 「对应的那部分」：写入/追加的值在文件里命中哪些行
        val needle = parsed.value?.trim().orEmpty()
        val matched = if (content != null && needle.isNotEmpty()) {
            findMatches(content, needle)
        } else {
            emptyList()
        }

        return Inspection(
            readable = exists || content != null || stat != null,
            currentValue = null,
            extraLines = extra,
            fileContent = content,
            fileTruncated = truncated,
            matched = matched,
            note = when {
                !exists -> "目标当前不存在（执行该指令后会创建）"
                content == null -> "文件存在但内容读不到：应用侧无权限，特权通道未就绪"
                else -> null
            },
        )
    }

    private suspend fun inspectPackage(context: Context, parsed: Parsed): Inspection {
        val pkg = parsed.pkg
        if (pkg.isNullOrBlank()) return Inspection(note = "无法识别包名")
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val extra = ArrayList<Pair<String, String>>()
        extra.add("包名" to pkg)
        if (appInfo == null) {
            extra.add("安装状态" to "未安装")
            return Inspection(readable = true, extraLines = extra, note = "该应用当前未安装")
        }
        extra.add("安装状态" to "已安装")
        extra.add("启用状态" to if (appInfo.enabled) "启用" else "已停用")

        // pm list packages -d / -e 需要特权通道，能用就补一条更贴近系统真实状态的信息
        val disabledList = shRead("pm list packages -d")
        if (disabledList != null) {
            val isDisabled = disabledList.lineSequence().any { it.trim() == "package:$pkg" }
            extra.add("系统停用列表" to if (isDisabled) "在停用列表中" else "不在停用列表")
        }
        return Inspection(
            readable = true,
            currentValue = if (appInfo.enabled) "enabled" else "disabled",
            extraLines = extra,
        )
    }

    private suspend fun inspectAppOps(parsed: Parsed): Inspection {
        val pkg = parsed.pkg ?: return Inspection(note = "无法识别包名")
        val op = parsed.target
        val text = shRead("cmd appops get $pkg $op")
            ?: return Inspection(
                readable = false,
                extraLines = listOf("包名" to pkg, "权限" to op),
                note = "appops 需要特权通道，当前不可用",
            )
        return Inspection(
            readable = true,
            currentValue = text.trim(),
            extraLines = listOf("包名" to pkg, "权限" to op),
        )
    }

    private suspend fun inspectWindow(context: Context, parsed: Parsed): Inspection {
        val dm = context.resources.displayMetrics
        val size = "${dm.widthPixels}x${dm.heightPixels}"
        val extra = ArrayList<Pair<String, String>>()
        extra.add("动作" to parsed.op)
        extra.add("当前显示尺寸" to size)
        extra.add("当前密度" to "${dm.densityDpi} dpi")
        // wm size / wm density 的真实 override 值只有特权能读
        val override = when (parsed.op) {
            "size" -> shRead("wm size").let { t ->
                t?.lineSequence()?.firstOrNull { it.contains("Override") }?.trim() ?: t?.trim()
            }
            "density" -> shRead("wm density").let { t ->
                t?.lineSequence()?.firstOrNull { it.contains("Override") }?.trim() ?: t?.trim()
            }
            else -> null
        }
        if (!override.isNullOrBlank()) extra.add("系统覆盖值" to override)
        return Inspection(readable = true, extraLines = extra)
    }

    private fun inspectMount(parsed: Parsed): Inspection {
        val path = parsed.path.orEmpty()
        val entries = runCatching {
            File("/proc/mounts").readLines()
        }.getOrNull()
            ?: return Inspection(note = "读不到 /proc/mounts")
        val related = if (path.isBlank()) entries else entries.filter { it.contains(path) }
        val text = related.joinToString("\n").take(MAX_TEXT)
        return Inspection(
            readable = true,
            extraLines = listOf(
                "动作" to parsed.op,
                "挂载条目" to "${related.size} 条",
            ),
            fileContent = text,
        )
    }

    private fun inspectDpm(context: Context, parsed: Parsed): Inspection {
        val extra = ArrayList<Pair<String, String>>()
        extra.add("动作" to parsed.op)
        runCatching {
            val flags = frb.axeron.manager.owner.DeviceOwnerState.queryOwnerFlags(context)
            extra.add("设备所有者" to if (flags.first) "是" else "否")
            extra.add("资料所有者" to if (flags.second) "是" else "否")
        }
        if (!parsed.pkg.isNullOrBlank()) extra.add("目标包名" to parsed.pkg!!)
        return Inspection(readable = true, extraLines = extra)
    }

    private fun inspectActivity(parsed: Parsed): Inspection {
        val pkg = parsed.pkg
        return Inspection(
            readable = true,
            extraLines = buildList {
                add("动作" to parsed.op)
                if (!pkg.isNullOrBlank()) add("包名" to pkg)
                add("说明" to "进程是否在运行无法免特权查询，只能从指令语义判断")
            },
            note = if (parsed.op == "force-stop") "该指令会结束应用进程（应用会按需自动重启）" else null,
        )
    }

    /**
     * 读取文件内容时直接读文件的命令。
     *
     * 【v1.5.8 需求②】把过滤 / 编码类工具也纳入：
     * `grep -q xxx /proc/...` 这类原样执行出来是空（-q 不输出），
     * 用户看到一片空白等于什么都没看到。直接读目标文件反而能看到全貌。
     */
    private val FILE_READ_HEADS = setOf(
        "cat", "head", "tail", "less", "more", "nl", "cmp",
        "grep", "egrep", "fgrep", "awk", "sed", "strings", "xxd", "od", "hexdump",
        "md5sum", "sha1sum", "sha256sum", "base64", "tr", "cut", "sort", "uniq", "diff",
    )

    /**
     * 允许「原样执行取输出」的只读命令白名单。
     *
     * 只收录**不会阻塞、不会产生副作用**的查询命令；`sleep` / `logcat` / `monkey` / `top`
     * 这类会挂住或乱点屏幕的命令一律不执行，避免打开详情页就卡死。
     */
    private val SAFE_QUERY_HEADS = setOf(
        "getprop", "dumpsys", "ps", "ls", "stat", "wc", "du", "df",
        "file", "readlink", "realpath", "find", "id", "uname", "free", "uptime", "pidof",
        "pgrep", "printenv", "which", "getenforce", "hostname", "vmstat", "iostat",
        "pm", "cmd", "wm", "svc", "service", "settings",
    )

    /**
     * 【v1.5.5 需求④】只读读取类指令：把「读到了什么」真正显示出来。
     *
     * 以前这一类全部落到 `else -> 没有可直接读取的目标状态`，用户点开
     * `getprop ro.board.platform`、`cat /proc/cpuinfo` 看不到任何内容。
     * 现在的顺序是：文件类先读文件 → 读不到用特权通道 `head -c` → 目录用 `ls -la`
     *  → 其它查询命令（dumpsys / ps / pm list / cmd xxx get …）原样执行取输出。
     */
    private suspend fun inspectReadOnly(parsed: Parsed): Inspection {
        val extra = ArrayList<Pair<String, String>>()
        val head = parsed.op.ifBlank { parsed.raw.substringBefore(' ') }
            .substringAfterLast('/').lowercase()
        val path = parsed.path?.takeIf { it.isNotBlank() }
        if (!path.isNullOrBlank()) extra.add("读取目标" to path)

        // ① 文件内容类（cat / head / tail …）
        if (!path.isNullOrBlank() && head in FILE_READ_HEADS) {
            val f = File(path)
            var content: String? = null
            var truncated = false
            if (f.isFile && f.canRead()) {
                val r = readLimited(f, MAX_TEXT)
                content = r?.first
                truncated = r?.second ?: false
            } else {
                val shell = shRead("head -c $MAX_TEXT ${shellQuote(path)}")
                if (shell != null) {
                    content = shell
                    truncated = true
                    extra.add("读取通道" to "特权 shell")
                }
            }
            if (content != null) {
                return Inspection(
                    readable = true,
                    extraLines = extra,
                    fileContent = content,
                    fileTruncated = truncated,
                )
            }
            return Inspection(
                readable = false,
                extraLines = extra,
                note = "读不到该路径的内容（应用侧无权限，特权通道未就绪）",
            )
        }

        // ② 路径信息类（ls / du / df / stat / find / wc …）：原样执行最准
        if (!path.isNullOrBlank()) {
            val out = shRead(parsed.raw)
            if (out != null) {
                val text = out.trim()
                return Inspection(
                    readable = true,
                    currentValue = text.lineSequence().firstOrNull { it.isNotBlank() },
                    extraLines = extra + ("读取通道" to "特权 shell"),
                    fileContent = text.take(MAX_TEXT),
                    fileTruncated = text.length > MAX_TEXT,
                )
            }
        }

        // ③ 系统查询类（dumpsys / ps / id / getprop 全量 / pm list / cmd xxx get …）
        if (head in SAFE_QUERY_HEADS) {
            val out = shRead(parsed.raw)
            if (out != null) {
                val text = out.trim()
                return Inspection(
                    readable = true,
                    currentValue = text.lineSequence().firstOrNull { it.isNotBlank() },
                    extraLines = extra + ("读取通道" to "特权 shell"),
                    fileContent = text.take(MAX_TEXT),
                    fileTruncated = text.length > MAX_TEXT,
                )
            }
            return Inspection(
                readable = false,
                extraLines = extra,
                note = "这条读取指令需要特权通道，当前未就绪",
            )
        }

        return Inspection(
            readable = false,
            extraLines = extra,
            note = "这条指令本身不产生可读取的状态（$head）",
        )
    }

    /** `cmd <service>` 的读取子命令（能问出当前状态的）。 */
    private fun isQueryCmdSub(sub: String): Boolean {
        val s = sub.lowercase()
        if (s.isEmpty()) return false
        return s == "get" || s == "list" || s == "query" || s == "dump" || s == "state" ||
            s == "help" || s == "status" || s == "info" || s.startsWith("get-") ||
            s.startsWith("is-") || s.startsWith("has-") || s.endsWith("-get") ||
            s == "print" || s == "show" || s == "version"
    }

    /**
     * 【v1.5.5 需求⑥】`cmd <service> <sub>` 现场读取。
     *
     * 子命令本身是查询（get / list / query / dump / help …）→ 原样执行取输出；
     * 否则（unforce / force / set / add …）→ 用 `cmd <service> help` 列出该服务
     * 支持哪些子命令，让用户看到「这个服务能干什么」，而不是一句「不可操作」。
     */
    private suspend fun inspectCmdService(parsed: Parsed): Inspection {
        val parts = parsed.op.split(' ').filter { it.isNotBlank() }
        val service = parts.getOrNull(0).orEmpty()
        val sub = parts.getOrNull(1).orEmpty()
        val extra = ArrayList<Pair<String, String>>()
        extra.add("服务" to "cmd $service")
        if (sub.isNotBlank()) extra.add("子命令" to sub)

        // ① 查询类子命令：直接执行原命令
        if (sub.isNotBlank() && isQueryCmdSub(sub)) {
            val out = shRead(parsed.raw)
            if (out != null) {
                val text = out.trim()
                return Inspection(
                    readable = true,
                    currentValue = text.lineSequence().firstOrNull { it.isNotBlank() },
                    extraLines = extra + ("读取通道" to "特权 shell"),
                    fileContent = text.take(MAX_TEXT),
                    fileTruncated = text.length > MAX_TEXT,
                )
            }
        }

        // ② 无参时用 help 探这个服务支持哪些子命令
        val helpTarget = if (sub.isBlank()) parsed.raw.trim() else "cmd $service help"
        val help = shRead(helpTarget) ?: shRead("cmd $service help")
        if (!help.isNullOrBlank()) {
            val text = help.trim()
            return Inspection(
                readable = true,
                extraLines = extra + ("说明" to "该子命令以修改为主；下面是这个服务支持的子命令"),
                fileContent = text.take(MAX_TEXT),
                fileTruncated = text.length > MAX_TEXT,
            )
        }

        return Inspection(
            readable = false,
            extraLines = extra,
            note = "该子命令需要特权通道才能读取或执行；可直接用下面的方案执行",
        )
    }

    /** 文件内容里找出包含 needle 的行（最多 40 行，避免刷屏）。 */
    private fun findMatches(content: String, needle: String): List<MatchLine> {
        val key = needle.trim()
        if (key.isEmpty()) return emptyList()
        val out = ArrayList<MatchLine>()
        content.lineSequence().forEachIndexed { idx, line ->
            if (out.size >= 40) return@forEachIndexed
            if (line.contains(key)) out.add(MatchLine(lineNo = idx + 1, text = line.trimEnd()))
        }
        return out
    }

    private fun readLimited(f: File, limit: Int): Pair<String, Boolean>? = runCatching {
        f.bufferedReader().use { br ->
            val sb = StringBuilder()
            val buf = CharArray(4096)
            var truncated = false
            while (sb.length < limit) {
                val n = br.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, min(n, limit - sb.length))
            }
            if (sb.length >= limit) truncated = br.read() != -1
            sb.toString() to truncated
        }
    }.getOrNull()

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** 特权通道读一段文本（失败返回 null，不抛异常）。 */
    private suspend fun shRead(command: String): String? {
        val r = withTimeoutOrNull(READ_TIMEOUT_MS) {
            runCatching {
                AxeronPluginService.execWithIO(
                    command,
                    hideStderr = true,
                    // 内部工具命令：不能再进 AI 拦截挂钩，否则会挂起或被误拦
                    skipAnalyzer = true,
                )
            }.getOrNull()
        } ?: return null
        return if (r.code == 0) r.out else null
    }

    // ==================================================================================
    // ②.5 生效判定
    // ==================================================================================

    /**
     * 本次修改到底有没有真的生效。
     *
     * 为什么需要它：模块脚本大量像 `echo 0 > /sys/.../scaling_min_freq` 这样的写入，
     * 免 Root 下会被系统拒绝，而脚本本身可能不打印任何错误，用户只能靠猜。
     * 详情页既然已经把「当前值」读出来了，就直接拿它和「指令要写入的值」比一次，
     * 明确告诉用户「已生效 / 未生效」，用户再决定要不要给恢复方案。
     *
     * 判定只做**等值比较**，不猜测语义：
     * - settings / 属性：读取值 == 写入值 → 已生效；
     * - 写文件 / 追加：文件内容里出现写入值 → 已生效（追加与多行内容都覆盖）；
     * - 读不到值、或这条指令本身没有「写入值」（如 pm disable）→ [AppliedState.UNKNOWN]。
     */
    enum class AppliedState {
        /** 当前状态与指令要写入的值一致。 */
        APPLIED,

        /** 当前状态与指令要写入的值不一致。 */
        NOT_APPLIED,

        /** 读不到当前值，或这类指令无法用等值比较判断。 */
        UNKNOWN,
    }

    fun judgeApplied(parsed: Parsed, ins: Inspection): AppliedState {
        val expected = parsed.value?.trim().orEmpty()
        if (expected.isEmpty()) return AppliedState.UNKNOWN
        val cur = ins.currentValue?.trim()
        val content = ins.fileContent?.trim()
        return when (parsed.kind) {
            Kind.SETTINGS, Kind.PROP -> when {
                cur.isNullOrEmpty() -> AppliedState.UNKNOWN
                cur == expected -> AppliedState.APPLIED
                else -> AppliedState.NOT_APPLIED
            }

            Kind.FILE_WRITE, Kind.FILE_APPEND -> when {
                content == null -> AppliedState.UNKNOWN
                content == expected -> AppliedState.APPLIED
                content.contains(expected) -> AppliedState.APPLIED
                else -> AppliedState.NOT_APPLIED
            }

            else -> AppliedState.UNKNOWN
        }
    }

    // ==================================================================================
    // ③ 修改方案
    // ==================================================================================

    /**
     * 针对该指令的目标，列出**所有**可选修改方案。
     *
     * 排序原则：先「恢复/撤销」（把系统带回打开这个界面时的样子），再「切换/删除」，
     * 最后才是破坏性的（标 DANGER，界面上需要二次确认）。
     */
    fun buildSchemes(parsed: Parsed, ins: Inspection): List<Scheme> {
        val out = ArrayList<Scheme>()
        val cur = ins.currentValue
        val q = ::shellQuote

        fun add(
            id: String,
            label: String,
            command: String,
            tone: Scheme.Tone = Scheme.Tone.NEUTRAL,
            hint: String? = null,
        ) {
            if (command.isBlank()) return
            if (out.any { it.command == command }) return
            out.add(Scheme(id = id, label = label, command = command, tone = tone, hint = hint))
        }

        when (parsed.kind) {
            Kind.SETTINGS -> {
                val ns = parsed.namespace
                val key = parsed.target
                if (!ns.isNullOrBlank() && key.isNotBlank()) {
                    if (!cur.isNullOrBlank()) {
                        add(
                            "restore", "写回当前值：$cur", "settings put $ns $key ${q(cur)}",
                            Scheme.Tone.RECOVER, "打开这个界面时该键的值",
                        )
                    }
                    add(
                        "delete", "删除该键，回落系统默认", "settings delete $ns $key",
                        Scheme.Tone.RECOVER, "系统没有这个键时会使用默认行为",
                    )
                    if (!parsed.value.isNullOrBlank() && parsed.op == "put") {
                        add(
                            "reapply", "按原指令再写一次：${parsed.value}",
                            "settings put $ns $key ${q(parsed.value)}",
                        )
                    }
                }
            }

            Kind.PROP -> {
                val key = parsed.target
                if (key.isNotBlank()) {
                    if (!cur.isNullOrBlank()) {
                        add(
                            "restore", "写回当前值：$cur", "setprop $key ${q(cur)}",
                            Scheme.Tone.RECOVER,
                        )
                    }
                    // 【v1.5.5】`getprop <key>` 属于读取，不该出现「删除该属性」这类破坏性方案
                    if (parsed.op != "getprop") {
                        add(
                            "resetprop-del", "删除该属性", "resetprop --delete $key",
                            Scheme.Tone.RECOVER, "仅 persist.* 这类可写属性会生效",
                        )
                    }
                    if (!parsed.value.isNullOrBlank()) {
                        add("reapply", "按原指令再写一次：${parsed.value}", "setprop $key ${q(parsed.value)}")
                    }
                }
            }

            Kind.FILE_WRITE, Kind.FILE_APPEND, Kind.FILE_PERM, Kind.FILE_OWNER,
            Kind.FILE_COPY, Kind.FILE_MOVE, Kind.FILE_DELETE, Kind.DIR_CREATE, Kind.FILE_LINK -> {
                val path = parsed.path
                if (!path.isNullOrBlank()) {
                    // ① 恢复为「打开时读到的内容」——只有读到了才给
                    val snapshot = ins.fileContent
                    if (!snapshot.isNullOrEmpty()) {
                        add(
                            "restore-open",
                            "恢复为打开时的内容（${snapshot.length} 字符）",
                            writeFileCommand(path, snapshot),
                            Scheme.Tone.RECOVER,
                            if (ins.fileTruncated) "注意：打开时内容已被截断，只能恢复前 ${MAX_TEXT} 字符" else "写回打开这个界面时文件里的原文",
                        )
                    }
                    // ② 权限/属主恢复
                    if (parsed.kind == Kind.FILE_PERM) {
                        val mode = ins.extraLines.firstOrNull { it.first == "权限" }?.second
                        if (!mode.isNullOrBlank()) {
                            add("restore-perm", "恢复为当前权限：$mode", "chmod $mode ${q(path)}", Scheme.Tone.RECOVER)
                        }
                    }
                    if (parsed.kind == Kind.FILE_OWNER) {
                        val owner = ins.extraLines.firstOrNull { it.first == "属主" }?.second
                        if (!owner.isNullOrBlank()) {
                            add("restore-owner", "恢复为当前属主：$owner", "chown $owner ${q(path)}", Scheme.Tone.RECOVER)
                        }
                    }
                    // ③ 删除「本指令写入的片段」
                    if (parsed.kind == Kind.FILE_APPEND && !parsed.value.isNullOrBlank()) {
                        val frag = parsed.value.trim()
                        add(
                            "drop-append",
                            "删掉包含该片段的所有行",
                            "sed -i '/${escapeSed(frag)}/d' ${q(path)}",
                            Scheme.Tone.DANGER,
                            "会把文件中所有包含「$frag」的行一起删掉",
                        )
                    }
                    // ④ 撤销文件本身
                    if (parsed.kind == Kind.FILE_WRITE || parsed.kind == Kind.FILE_APPEND) {
                        add("delete-file", "删除该文件", "rm -f ${q(path)}", Scheme.Tone.DANGER)
                    }
                    if (parsed.kind == Kind.FILE_COPY || parsed.kind == Kind.FILE_LINK) {
                        add("delete-target", "删除该产物", "rm -f ${q(path)}", Scheme.Tone.DANGER)
                    }
                    if (parsed.kind == Kind.FILE_MOVE && !parsed.value.isNullOrBlank()) {
                        add("move-back", "移回原位置：${parsed.value}", "mv ${q(path)} ${q(parsed.value)}")
                    }
                    if (parsed.kind == Kind.DIR_CREATE) {
                        add("rmdir", "删除该目录（需为空）", "rmdir ${q(path)}", Scheme.Tone.DANGER)
                    }
                    if (parsed.kind == Kind.FILE_DELETE) {
                        add(
                            "recreate-dir", "重建父目录",
                            "mkdir -p ${q(path.substringBeforeLast('/', ""))}",
                            hint = "被删的内容无法找回，只能保证父目录存在",
                        )
                    }
                }
            }

            Kind.PKG_STATE -> {
                val pkg = parsed.pkg
                if (!pkg.isNullOrBlank()) {
                    if (parsed.op == "disable" || parsed.op == "hide" || parsed.op == "suspend") {
                        val inverse = when (parsed.op) {
                            "disable" -> "pm enable $pkg"
                            "hide" -> "pm unhide $pkg"
                            else -> "pm unsuspend $pkg"
                        }
                        add("inverse", "恢复：$inverse", inverse, Scheme.Tone.RECOVER)
                    } else {
                        add("disable", "停用该应用", "pm disable-user --user 0 $pkg", Scheme.Tone.DANGER)
                    }
                    add("details", "查看系统里的启用状态", "pm list packages -e | grep -F $pkg", hint = "只读，用于核对")
                }
            }

            Kind.PKG_UNINSTALL -> {
                val pkg = parsed.pkg
                if (!pkg.isNullOrBlank()) {
                    add("reinstall", "恢复安装：pm install-existing", "pm install-existing $pkg", Scheme.Tone.RECOVER)
                }
            }

            Kind.PKG_INSTALL -> {
                val pkg = parsed.pkg
                if (!pkg.isNullOrBlank()) {
                    add("uninstall", "卸载刚安装的应用", "pm uninstall $pkg", Scheme.Tone.DANGER)
                }
            }

            Kind.PKG_CLEAR -> {
                add(
                    "no-undo", "没有可恢复方案",
                    "echo '应用数据已被清除，无法恢复'",
                    hint = "清除数据不可逆；如需重新使用请让应用自行重建",
                )
            }

            Kind.PKG_PERM -> {
                val pkg = parsed.pkg
                val perm = parsed.target
                if (!pkg.isNullOrBlank() && perm.isNotBlank()) {
                    if (parsed.op == "grant") {
                        add("revoke", "收回该权限", "pm revoke $pkg $perm", Scheme.Tone.RECOVER)
                    } else {
                        add("grant", "重新授予该权限", "pm grant $pkg $perm")
                    }
                }
            }

            Kind.APP_OPS -> {
                val pkg = parsed.pkg
                val op = parsed.target
                if (!pkg.isNullOrBlank() && op.isNotBlank()) {
                    add("default", "恢复为默认", "cmd appops set $pkg $op default", Scheme.Tone.RECOVER)
                    for (mode in listOf("allow", "ignore", "deny")) {
                        if (mode != parsed.value) {
                            add("set-$mode", "改为 $mode", "cmd appops set $pkg $op $mode")
                        }
                    }
                }
            }

            Kind.ACTIVITY -> {
                val pkg = parsed.pkg
                if (!pkg.isNullOrBlank()) {
                    if (parsed.op == "force-stop" || parsed.op == "kill" || parsed.op == "kill-all") {
                        add(
                            "launch", "拉起主界面",
                            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
                            Scheme.Tone.RECOVER, "应用本来就会按需重启，这一步只是立刻拉起",
                        )
                    } else {
                        add("forcestop", "停止该应用", "am force-stop $pkg", Scheme.Tone.DANGER)
                    }
                }
            }

            Kind.WINDOW -> {
                when (parsed.op) {
                    "size" -> add("reset", "恢复默认分辨率", "wm size reset", Scheme.Tone.RECOVER)
                    "density" -> add("reset", "恢复默认密度", "wm density reset", Scheme.Tone.RECOVER)
                    "overscan" -> add("reset", "恢复默认 overscan", "wm overscan reset", Scheme.Tone.RECOVER)
                    else -> Unit
                }
                if (!parsed.value.isNullOrBlank()) {
                    val cmd = when (parsed.op) {
                        "size" -> "wm size ${parsed.value}"
                        "density" -> "wm density ${parsed.value}"
                        else -> ""
                    }
                    add("reapply", "按原指令再设一次：${parsed.value}", cmd)
                }
            }

            Kind.DPM -> {
                val pkg = parsed.pkg
                when (parsed.op) {
                    "hide" -> if (!pkg.isNullOrBlank()) add("inv", "取消隐藏", "axeron-dpm unhide $pkg", Scheme.Tone.RECOVER)
                    "unhide" -> if (!pkg.isNullOrBlank()) add("inv", "重新隐藏", "axeron-dpm hide $pkg")
                    "suspend" -> if (!pkg.isNullOrBlank()) add("inv", "取消挂起", "axeron-dpm unsuspend $pkg", Scheme.Tone.RECOVER)
                    "unsuspend" -> if (!pkg.isNullOrBlank()) add("inv", "重新挂起", "axeron-dpm suspend $pkg")
                    "block-uninstall" -> if (!pkg.isNullOrBlank()) add("inv", "允许卸载", "axeron-dpm unblock-uninstall $pkg", Scheme.Tone.RECOVER)
                    "unblock-uninstall" -> if (!pkg.isNullOrBlank()) add("inv", "禁止卸载", "axeron-dpm block-uninstall $pkg")
                    "grant" -> if (!pkg.isNullOrBlank()) add("inv", "收回权限", "axeron-dpm deny $pkg", Scheme.Tone.RECOVER)
                    "deny" -> if (!pkg.isNullOrBlank()) add("inv", "重新授予权限", "axeron-dpm grant $pkg")
                    "set-global", "set-secure" -> {
                        val ns = if (parsed.op == "set-global") "global" else "secure"
                        val key = parsed.target
                        if (key.isNotBlank() && !cur.isNullOrBlank()) {
                            add("restore", "写回当前值：$cur", "settings put $ns $key ${q(cur)}", Scheme.Tone.RECOVER)
                        }
                        add("delete", "删除该键，回落默认", "settings delete $ns $key", Scheme.Tone.RECOVER)
                    }
                    "set-anim" -> add("reset-anim", "恢复动画倍速为 1", "settings put global animator_duration_scale 1", Scheme.Tone.RECOVER)
                    "cleardata" -> add("no-undo", "没有可恢复方案", "echo '数据已清除，无法恢复'")
                    "reboot", "locknow" -> add("no-op", "无需恢复", "echo '该操作无需也难以恢复'")
                    else -> Unit
                }
            }

            Kind.MOUNT -> {
                val target = parsed.path
                if (!target.isNullOrBlank()) {
                    if (parsed.op == "umount") {
                        add("remount", "重新挂载", "mount $target", hint = "需要原始挂载参数，可能失败")
                    } else {
                        add("umount", "卸载该挂载点", "umount $target", Scheme.Tone.DANGER)
                    }
                    add("show", "查看当前挂载状态", "grep -F $target /proc/mounts", hint = "只读")
                }
            }

            Kind.CONTENT -> {
                add(
                    "manual", "ContentProvider 操作无法自动逆向",
                    "echo '请按 URI 自行核对后再操作'",
                    hint = "这类改动的目标是一行数据库记录，没有通用恢复命令",
                )
            }

            Kind.OVERLAY -> {
                val pkg = parsed.pkg
                if (!pkg.isNullOrBlank()) {
                    // parsed.op 在 cmd overlay 里是子命令，例如 enable / disable / enable-exclusive
                    val isEnable = parsed.op.startsWith("enable")
                    val inverse = if (isEnable) "disable" else "enable"
                    add("inv", "反转为 $inverse", "cmd overlay $inverse $pkg", Scheme.Tone.RECOVER)
                }
            }

            Kind.PROCESS -> {
                add("show", "查看该进程是否还在", "ps -A | grep -F ${q(parsed.target)}", hint = "只读")
                add("no-undo", "杀死进程不可逆", "echo '进程被结束后只能重新启动应用'")
            }

            Kind.CMD_RAW -> {
                // 【v1.5.5】cmd 内部服务子命令：以前一律「不可操作」，现在至少给三条活路。
                val parts = parsed.op.split(' ').filter { it.isNotBlank() }
                val service = parts.getOrNull(0).orEmpty()
                val sub = parts.getOrNull(1).orEmpty()
                val inverse = when (sub) {
                    "force" -> "unforce"
                    "unforce" -> "force"
                    "enable" -> "disable"
                    "disable" -> "enable"
                    "start" -> "stop"
                    "stop" -> "start"
                    "resume" -> "suspend"
                    "suspend" -> "resume"
                    "on" -> "off"
                    "off" -> "on"
                    "add" -> "remove"
                    "remove" -> "add"
                    "set" -> "reset"
                    "arm" -> "disarm"
                    "disarm" -> "arm"
                    else -> null
                }
                if (inverse != null && service.isNotBlank()) {
                    add(
                        "inverse", "反向执行：cmd $service $inverse", "cmd $service $inverse",
                        Scheme.Tone.RECOVER, "把该服务拉回执行前的另一侧状态",
                    )
                }
                if (service.isNotBlank()) {
                    add(
                        "probe", "查看该服务支持的子命令与现场", "cmd $service help",
                        hint = "只读，用于核对当前状态和可用子命令",
                    )
                    if (service == "deviceidle") {
                        add("idle-state", "查看当前 Doze 状态", "dumpsys deviceidle", hint = "只读")
                    }
                }
                add("reapply", "按原指令再执行一次", parsed.raw)
            }

            else -> {
                // 【v1.5.5】可操作兜底：任何指令都不再显示成「不可操作」。
                // 这类指令没有可自动推导的逆操作，但至少能原样重跑（只读指令还能借此拿到输出）。
                if (parsed.raw.isNotBlank()) {
                    add(
                        "reapply", "按原指令再执行一次", parsed.raw,
                        hint = if (parsed.kind == Kind.READ_ONLY) "只读指令，重新执行只为再看一次输出" else null,
                    )
                }
            }
        }
        return out
    }

    // ==================================================================================
    // ④ 执行
    // ==================================================================================

    /** 执行一条命令（用户已经明确点了某个方案）。 */
    suspend fun apply(command: String): ApplyResult = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ApplyResult(false, -1, "", "命令为空")
        val r = withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            runCatching {
                AxeronPluginService.execWithIO(
                    command,
                    hideStderr = false,
                    // 内部工具命令：用户已显式确认，不能再进 AI 拦截挂钩（否则挂起/误拦）
                    skipAnalyzer = true,
                )
            }.getOrNull()
        }
        if (r == null) {
            ApplyResult(
                ok = false,
                code = -1,
                out = "",
                err = "执行超时或特权服务未就绪（请确认已激活，且 AxManagerD 服务在后台运行）",
            )
        } else {
            ApplyResult(
                ok = r.code == 0,
                code = r.code,
                out = r.out.take(6000),
                err = r.err.take(6000),
            )
        }
    }

    /** 生成「整文件覆盖」命令（heredoc，避免引号转义问题）。 */
    fun writeFileCommand(path: String, content: String): String =
        "cat > ${shellQuote(path)} <<'$HEREDOC_END'\n$content\n$HEREDOC_END"

    /** 自定义值 → 具体命令。返回 null 表示这类指令不支持自动拼装。 */
    fun customCommand(parsed: Parsed, custom: String): String? {
        val q = ::shellQuote
        return when (parsed.kind) {
            Kind.SETTINGS -> {
                val ns = parsed.namespace ?: return null
                val key = parsed.target
                if (key.isBlank()) null else "settings put $ns $key ${q(custom)}"
            }
            Kind.PROP -> {
                val key = parsed.target
                if (key.isBlank()) null else "setprop $key ${q(custom)}"
            }
            Kind.FILE_WRITE, Kind.FILE_APPEND, Kind.FILE_PERM, Kind.FILE_OWNER -> {
                val path = parsed.path ?: return null
                when (parsed.kind) {
                    Kind.FILE_PERM -> "chmod ${custom.trim()} ${q(path)}"
                    Kind.FILE_OWNER -> "chown ${custom.trim()} ${q(path)}"
                    else -> writeFileCommand(path, custom)
                }
            }
            Kind.WINDOW -> when (parsed.op) {
                "size" -> "wm size ${custom.trim()}"
                "density" -> "wm density ${custom.trim()}"
                "overscan" -> "wm overscan ${custom.trim()}"
                else -> null
            }
            Kind.APP_OPS -> {
                val pkg = parsed.pkg ?: return null
                val op = parsed.target
                if (op.isBlank()) null else "cmd appops set $pkg $op ${custom.trim()}"
            }
            Kind.CMD_RAW -> {
                // 【v1.5.5】cmd 内部服务：自定义子命令直接拼在服务名后面
                val s = custom.trim()
                if (s.isEmpty()) return null
                val service = parsed.op.split(' ').firstOrNull { it.isNotBlank() }.orEmpty()
                when {
                    service.isBlank() -> s
                    s.startsWith("cmd ") || s.startsWith("$service ") -> s
                    else -> "cmd $service $s"
                }
            }
            else -> {
                // 【v1.5.5】其余指令（含 READ_ONLY / UNKNOWN）：自定义内容就是一条完整命令，
                // 保证任何指令都能在软件内被手工改写后执行，而不是「不可操作」。
                custom.trim().ifBlank { null }
            }
        }
    }

    /** 自定义编辑区的标题（不同指令改的东西不一样）。 */
    fun customLabel(parsed: Parsed): String = when (parsed.kind) {
        Kind.SETTINGS -> "自定义该设置的值"
        Kind.PROP -> "自定义该属性的值"
        Kind.FILE_WRITE, Kind.FILE_APPEND -> "自定义文件内容"
        Kind.FILE_PERM -> "自定义权限（如 644 / 755）"
        Kind.FILE_OWNER -> "自定义属主（如 root:shell）"
        Kind.WINDOW -> "自定义屏幕参数"
        Kind.APP_OPS -> "自定义权限模式（allow / ignore / deny / default）"
        Kind.CMD_RAW -> "自定义子命令（含参数，如 unforce / force / set …）"
        else -> "自定义完整命令（以特权身份执行）"
    }

    /** 用单引号安全包裹 shell 参数。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** sed 正则里需要转义的字符。 */
    private fun escapeSed(s: String): String =
        s.replace("\\", "\\\\").replace("/", "\\/").replace(".", "\\.")
            .replace("*", "\\*").replace("[", "\\[").replace("]", "\\]")
            .replace("^", "\\^").replace("$", "\\$")
}