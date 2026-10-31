package frb.axeron.manager.ai

import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService

/**
 * 【v1.4.0 修复②】拦截抓取「预演沙箱」（dry-run sandbox）。
 *
 * 问题（用户反馈）：
 *   运行 / 启用模块时选「不信任，先分析（拦截抓取）」后，抓取阶段其实是**真的把模块脚本跑了一遍**：
 *     - strace 段：`strace -f -e trace=execve sh ./action.sh` —— 真执行脚本
 *     - sh -x  段：`sh -x ./action.sh`                          —— 又执行一遍
 *   于是「拦截抓取」与「真运行」并不互斥：恶意（或事故性）模块在用户还没看到分析结果、还没点
 *   「继续执行」之前，就已经把自己的动作做完了（改设置、挂起应用、写内核节点…），
 *   分析界面只是「事后展示」。
 *
 * 修复思路（不改变抓取通道，只改变"命令落到哪里"）：
 *   抓取阶段在 PATH 最前面挂一个**沙箱目录**，目录内是我们生成的**同名桩脚本（stub）**：
 *     - 只读调用（如 `pm list packages`、`settings get`、`am get-current-user`）→ `exec` 真实命令，
 *       保证模块内部的分支判断拿到真实输出，不因"沙箱"而走错分支（抓取完整性不受影响）；
 *     - 写调用（如 `pm disable-user`、`settings put`、`am force-stop`、`axeron-dpm suspend`）
 *       → **只把命令行追加到日志、随即 exit 0**，绝不落到系统上。
 *   由于桩脚本位于 PATH 首位，模块脚本里 `pm xxx` / `axeron-dpm xxx` 这类**按名字调用**会命中桩；
 *   而 strace / sh -x 抓到的仍然是「`pm xxx`」这条原始命令文本（strace 打印的是被 exec 的脚本路径，
 *   经 [StraceHelper.parseExecveLine] 取 basename 后与真实命令一致），
 *   因此**抓取结果的文本一条不减**，只是不再真正生效。
 *
 * 边界（必须明确，不能假装万能）：
 *   1. 只拦「按名字经 PATH 解析」的调用。模块若写绝对路径（`/system/bin/pm disable x`），PATH 沙箱
 *      **拦不到**；免 root 下要挡住绝对路径只能靠 seccomp / 只读 mount namespace，代价过大，本版不做。
 *   2. shell 内建里直接写内核节点（`echo 1 > /sys/...`）也不产生 execve、不经过 PATH，同样拦不到。
 *      这两点属于「预演」的已知边界，用户确认后的「真正运行」仍按原样执行，故不影响最终效果。
 *   3. 沙箱只用于**抓取（预演）阶段**；用户确认后的「真正运行 / 启用」走原路径，一次都不少。
 *   4. 被拦下的写调用会追加到 [LOG]，便于排查「策略是否误伤正常模块」。
 */
object DryRunSandbox {

    /** 沙箱目录（与 strace 二进制同放在 /data/local/tmp，shell 身份可写可执行）。 */
    const val DIR = "/data/local/tmp/ax_dryrun"

    /** 被拦下的写调用日志（排查用，不参与抓取结果）。 */
    const val LOG = "/data/local/tmp/ax_dryrun_cmds.log"

    /** 真实命令所在目录（只读调用放行时 exec 它）。 */
    private const val REALBIN = "/system/bin"

    /**
     * 无条件拦截的「系统状态写」命令。
     *
     * 这些命令的**只读用法**极少（不像 pm/am 那样天天用来 list/get），一旦按写执行就会改系统状态，
     * 故一律拦下（连只读用法也拦，避免任何被绕过的可能）。
     */
    private val ALWAYS_BLOCK = listOf(
        "setprop", "resetprop", "axeron-dpm", "content", "svc", "wm",
        "insmod", "rmmod", "reboot", "su", "setenforce", "device_config",
        "stop", "start", "iw", "sysctl"
    )

    /**
     * 「参数里出现写动词才拦」的命令：只读调用交由真实命令执行。
     *
     * 为什么必须放行只读：模块脚本大量用 `pm list packages | grep -q xxx`、
     * `settings get global xxx` 来**决定走哪条分支**。若把这些也只记录不执行，输出为空、
     * 分支走错，模块真正的核心指令就永远抓不到 —— 那正是用户反复反馈过的「抓不全」。
     */
    private val CONDITIONAL = listOf("pm", "cmd", "am", "settings")

    /**
     * 写动词表（shell `case` 通配写法）。
     *
     * 覆盖：pm 的 disable* / enable* / install* / uninstall* / clear* / hide / suspend*、am 的 force-stop / start / stop /
     * broadcast、settings 的 put / delete、以及各种 `set-*`（含 axeron-dpm set-global / set-secure / set-anim）。
     * 刻意**不含** list / path / dump / get-* / query / resolve 等只读词，也不含 package / activity 这类纯命名空间词。
     */
    private const val WRITE_CASE =
        "disable*|enable*|install*|uninstall*|clear*|hide|unhide|suspend*|unsuspend*|grant*|revoke*|" +
            "put|delete|delete-*|add*|remove*|force-stop|kill*|start*|stop|broadcast|reset*|block*|unblock*|" +
            "set-*|set"

    // ---------------------------------------------------------------------
    // 桩脚本模板
    // ---------------------------------------------------------------------

    /** 条件桩：参数含写动词 → 只记录；否则 exec 真实命令（保证只读输出真实）。 */
    private fun conditionalStub(name: String): String = listOf(
        "#!/system/bin/sh",
        "# AxManager 预演沙箱（dry-run）：只读调用放行真实命令，写调用只记录不执行。",
        "BLOCK=0",
        "for a in \"\$@\"; do",
        "  case \"\$a\" in",
        "    $WRITE_CASE) BLOCK=1;;",
        "  esac",
        "done",
        "if [ \"\$BLOCK\" -eq 0 ]; then exec $REALBIN/$name \"\$@\"; fi",
        "printf '%s\\n' \"$name \$*\" >> $LOG",
        "exit 0"
    ).joinToString("\n")

    /** 无条件桩：只记录不执行。 */
    private fun blockStub(name: String): String = listOf(
        "#!/system/bin/sh",
        "# AxManager 预演沙箱（dry-run）：只记录不执行。",
        "printf '%s\\n' \"$name \$*\" >> $LOG",
        "exit 0"
    ).joinToString("\n")

    /** mount 专用桩：裸 `mount`（列出挂载表）放行，带参数的挂载一律拦。 */
    private fun mountStub(): String = listOf(
        "#!/system/bin/sh",
        "# AxManager 预演沙箱：裸 `mount` 是只读列表，放行；带参数（含 remount/rw）一律拦。",
        "if [ \"\$#\" -eq 0 ]; then exec $REALBIN/mount; fi",
        "printf '%s\\n' \"mount \$*\" >> $LOG",
        "exit 0"
    ).joinToString("\n")

    /** dd 专用桩：写块设备（/dev/...）才拦，普通文件 dd 放行（部分模块用它做格式转换）。 */
    private fun ddStub(): String = listOf(
        "#!/system/bin/sh",
        "# AxManager 预演沙箱：写块设备（/dev/...）才拦，普通文件 dd 放行。",
        "BLOCK=0",
        "for a in \"\$@\"; do case \"\$a\" in /dev/*|*/dev/*) BLOCK=1;; esac; done",
        "if [ \"\$BLOCK\" -eq 0 ]; then exec $REALBIN/dd \"\$@\"; fi",
        "printf '%s\\n' \"dd \$*\" >> $LOG",
        "exit 0"
    ).joinToString("\n")

    /** 全部桩：命令名 → 脚本内容。 */
    private val STUBS: List<Pair<String, String>>
        get() = ALWAYS_BLOCK.map { it to blockStub(it) } +
            CONDITIONAL.map { it to conditionalStub(it) } +
            listOf(
                "umount" to blockStub("umount"),
                "mount" to mountStub(),
                "dd" to ddStub()
            )

    // ---------------------------------------------------------------------
    // 对外接口
    // ---------------------------------------------------------------------

    /**
     * 生成「在 shell 侧重建沙箱」的命令。
     *
     * 用带引号的 heredoc（`<<'AXDRY'`）写桩脚本，避免内层 `$@` / `$*` 被外层提前展开。
     * 整条命令由调用方以 `arrayOf("/system/bin/sh","-c",cmd)` 直接作为 argv 传入，
     * 不经过二次 shell 解析，故 `$D` 只在目标 shell 里展开一次。
     */
    fun buildPrepareCmd(): String {
        val sb = StringBuilder()
        sb.append("D=").append(DIR).append("; ")
        sb.append("rm -rf \"\$D\"; mkdir -p \"\$D\" || exit 1; ")
        sb.append(": > ").append(LOG).append("; ")
        for ((name, body) in STUBS) {
            sb.append("cat > \"\$D/").append(name).append("\" <<'AXDRY'\n")
            sb.append(body).append('\n')
            sb.append("AXDRY\n")
            sb.append("chmod 755 \"\$D/").append(name).append("\"; ")
        }
        // 自检：pm 桩必须存在且可执行，否则调用方放弃沙箱（退回原行为，绝不因沙箱失败而抓不到）
        sb.append("test -x \"\$D/pm\" && echo DRYRUN_READY")
        return sb.toString()
    }

    /**
     * 在 shell 侧重建沙箱。成功返回 true（代表抓取阶段可以安全地"预演"）。
     * 失败（无 shell 权限 / 目录不可写）返回 false，调用方应退回不带沙箱的抓取。
     */
    suspend fun prepare(): Boolean = runCatching {
        AxeronPluginService.execProcessSafe(
            cmd = arrayOf("/system/bin/sh", "-c", buildPrepareCmd()),
            env = Axeron.getEnvironment()
        ).stdout.contains("DRYRUN_READY")
    }.getOrDefault(false)

    /** 读取被拦下的写调用日志（排查「沙箱是否误伤」用）。 */
    suspend fun readLogCmd(): String = runCatching {
        AxeronPluginService.execProcessSafe(
            cmd = arrayOf("/system/bin/sh", "-c", "cat $LOG 2>/dev/null"),
            env = Axeron.getEnvironment()
        ).stdout
    }.getOrDefault("")

    /**
     * PATH 前缀（argv 上下文）：命令由 `sh -c <argv>` 直接执行，不经过双引号包裹，
     * 因此 `$PATH` 只需原样写。
     */
    fun pathPrefixRaw(): String = "export PATH=$DIR:\$PATH; "

    /**
     * PATH 前缀（execWithIO 上下文）：命令会被塞进 `busybox sh -c "…"` 的**双引号**里，
     * 外层 shell 会先吃一层转义，故 `$` 必须写成 `\$`（与 [StraceHelper.buildXtraceCmd]
     * 里原有的 `\$PATH` 写法一致）。
     */
    fun pathPrefixQuoted(): String = "export PATH=$DIR:\\\$PATH; "
}
