package frb.axeron.manager.ai

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 运行时指令采集器（方案 A：运行中抓取模块「真实执行过的指令」）。
 *
 * 背景：加密/混淆脚本（base64、openssl、eval、shc 编译的二进制等）在静态时是
 * 乱码，无法直接读出真实行为。但无论怎么加密，脚本最终都要通过 shell 真实执行，
 * 只要我们开启 shell 的 xtrace（`set -x`），就能捕获到「解密/展开后真实执行的
 * 每一条命令」，这才是模块真正在做什么。
 *
 * 本采集器本身不执行任何命令，只负责：
 *  1. 把 xtrace 输出行（形如 `+ echo performance > /sys/...`）清洗成干净指令；
 *  2. 累积保存，供 AI 在提问时作为「真实执行指令流」数据源。
 *
 * 这样抓到的指令会**真正喂给 AI**（见 AIEnvironment.payloadContext / AIAnalysisActivity），
 * 而不是仅仅打印到日志里。
 */
object RuntimeCommandTracer {
    /** 一次运行采集到的真实指令流（线程安全，供中断/结束后读取） */
    private val captured = CopyOnWriteArrayList<String>()
    /** 去重集合：加速 sleep 后常驻脚本（while true）会在 timeout 内重复执行核心指令，
     *  大量重复指令若不拦截会快速占满 5000 上限，把真正的关键指令挤掉。 */
    private val seen = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** 是否已经开启 xtrace 采集 */
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * 生成带 xtrace 的执行命令。
     * 在原始命令前注入 `set -x`，让 sh 把后续每条真实执行的命令
     * （包括脚本内部逐行、及加密脚本解密后真正展开的命令）打到 stderr。
     *
     * 返回「包裹后的完整命令」，以及用于识别 xtrace 行的前缀。
     */
    fun wrapWithXtrace(cmd: String): String {
        // 去掉原命令自己末尾的 exit，避免提前退出 xtrace 会话
        // 我们统一在开头 set -x，结尾 set +x 关闭追踪
        return "set -x; $cmd; set +x 2>/dev/null"
    }

    /** 判断某行是否为 xtrace 输出（即以 `+` 开头的追踪行） */
    fun isTraceLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("+ ") || t.startsWith("++")
    }

    /**
     * 清洗一条 xtrace 行，抽出真实指令文本。
     * 例如 `+ echo performance > /sys/.../scaling_governor 2>/dev/null`
     *   -> `echo performance > /sys/.../scaling_governor`
     */
    fun cleanTraceLine(line: String): String {
        var s = line.trim()
        // 去掉 xtrace 前缀
        while (s.startsWith("+")) {
            s = s.removePrefix("+").trimStart()
        }
        // 去掉重复的引导符
        s = s.trim()
        // 去掉行号类噪声（有些 shell 会带 eval 等）
        return s
    }

    /**
     * 把一条真实执行指令记录到采集器（供 AI 读取）。
     * 会做强去噪：跳过空行、`set +x`、系统底层探测噪声，以及
     * rm/chmod/mkdir/cp/mv/tar/base64 等「部署/文件操作/文本处理」噪声，
     * 只保留有业务含义的核心指令（pm/setprop/axeron-dpm/mount/settings/am 等）。
     */
    fun addTrace(line: String) {
        val cmd = cleanTraceLine(line)
        if (cmd.isBlank()) return
        if (cmd == "set +x" || cmd == "set -x") return
        if (isNoise(cmd)) return
        // 复用 strace 通道的强黑名单：sh -x（xtrace）同样会抓到大量 rm -rf、
        // chmod 777、mkdir、cp、tar、base64、grep/sed/awk 等「解析/部署/文本处理」噪声，
        // 若不与 strace 通道统一过滤，这些会淹没 am/cmd/pm/setprop/mount 等核心指令。
        if (isStraceNoise(cmd)) return
        if (captured.size >= 5000) return
        if (!seen.add(cmd)) return
        captured.add(cmd)
    }
    /** 判断一条指令是否为系统噪声（非模块核心行为），应被过滤。 */
    private fun isNoise(cmd: String): Boolean {
        val c = cmd.trim()
        // 关键修正：性能/温控类模块的核心行为恰恰是「写」内核节点（echo xx > /sys/... 等）。
        // 之前这里对 /sys/ /proc/ /dev/ 一刀切过滤，会把模块真正要做的优化操作（写
        // scaling_governor、thermal_zone、调度参数等）误当成噪声丢弃，导致「抓不到核心指令」。
        //
        // 新规则：只有「只读探测（cat/read/ls 内核节点，不带写入）」才是噪声；
        // 带 `>` 或 `tee` 等写操作的指令是核心行为，必须保留。
        val touchesKernelNode = c.contains("/sys/") || c.contains("/proc/") || c.contains("/dev/")
        if (touchesKernelNode) {
            // 写入内核节点（echo ... > /sys/... 或 | tee /sys/...）→ 核心行为，保留
            val isWriteOp = c.contains(">") || c.contains("tee ")
            if (!isWriteOp) return true  // 只读探测内核节点 → 噪声
        }
        // read/write 内核调试接口的噪声模式（无写入则属只读探测）
        if (c.startsWith("read ") || c.startsWith("write ")) return true
        // 纯 read/cat 系统文件（不带业务含义）：仅当【无写入操作】时才视为噪声。
        // 带 `>` 或 `tee`（写 thermal_zone/tpath 等内核节点）是温控/性能模块的核心行为，必须保留。
        if (c.contains("tpath") || c.contains("thermal_zone")) {
            val isWriteOp = c.contains(">") || c.contains("tee ")
            if (!isWriteOp) return true
        }
        return false
    }
    /** 把一条 strace execve 输出行解析成真实命令并记录（供 AI 读取）。 */
    fun addStraceLine(line: String) {
        val cmd = StraceHelper.parseExecveLine(line) ?: return
        if (cmd.isBlank()) return
        if (isNoise(cmd)) return
        if (isStraceNoise(cmd)) return
        if (captured.size >= 5000) return
        if (!seen.add(cmd)) return
        captured.add(cmd)
    }

    /**
     * 判断一条真实执行指令是否为「解析/部署/环境探测」噪声，应被过滤。
     *
     * 关键原则（拦截 + 读取，而非解密）：
     *  - 我们只负责拦截模块「启用时真正执行」的指令并读出来给 AI/用户看，不关心也无需去
     *    解密。加密壳（YTAS 三层壳等）自身在解密/解包阶段会产生大量「脚手架」指令——
     *    tar/zcat/base64/xxd/tr 解密链、chmod/mkdir/rm 部署清理、id/grep/head/cut/tail
     *    等探测截取、以及 sh 入口/解密脚本本身——这些是「解析阶段的无效指令」，必须过滤，
     *    否则会淹没模块真正要做的核心动作（am/cmd/pm/setprop/mount/settings/axeron-dpm）。
     *  - strace 抓 execve 能穿透壳、看到「解密后真正 exec 出来的命令」，那才是我们要的
     *    核心指令，必须保留。
     */
    private fun isStraceNoise(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.isBlank()) return true
        // 取第一条命令的 basename 作为分类依据
        val firstToken = c.substringBefore(' ').substringBefore('\t')
        val base = firstToken.substringAfterLast('/')
        // 关键修正：`echo xx > /sys/...` / `printf xx > /proc/...` 是性能/温控模块的
        // 「写内核节点」核心指令，之前被 echo/printf 黑名单直接误杀，导致抓不到核心操作。
        // 带 `>`（写文件/内核节点）或 `tee` 的 echo/printf 是核心行为，必须保留。
        if (base in setOf("echo", "printf")) {
            val isWriteOp = c.contains(">") || c.contains("tee ")
            if (isWriteOp) return false
        }
        // 解密/解包链 + 通用文件/文本处理 + 环境探测（壳脚手架，无业务含义）
        if (base in setOf(
                "tar", "zcat", "gzip", "gunzip", "base64", "xxd", "tr",
                "head", "cut", "tail", "grep", "sed", "awk", "cat", "chmod",
                "mkdir", "rm", "cp", "mv", "id", "read", "echo", "printf", "sleep",
                "unzip", "uname", "test", "[", "expr", "wc", "sort", "uniq",
                "touch", "ln", "sh", "dirname", "basename", "mktemp", "dd"
            )
        ) {
            return true
        }
        // 纯只读查询类（不改变系统状态，只探测环境）
        if (c.startsWith("pm list") || c.startsWith("pm path") || c.startsWith("cmd package list") ||
            c.startsWith("dumpsys") || c.startsWith("getprop") || c.startsWith("ls ") ||
            c.startsWith("find ") || c.startsWith("which ") || c.startsWith("ps ")
        ) return true
        // 纯中转唤醒（content call / app_process 本身无业务，只是壳的进程壳/中转）
        if (c.startsWith("content call") || c.startsWith("app_process")) return true
        return false
    }
    /** 把一条「脚本源命令」（非 xtrace，而是 action.sh 里的原始行）记录到采集器。 */
    fun addTraceLine(line: String) {
        val t = line.trim()
        if (t.isBlank()) return
        // 跳过注释行、shebang、纯结构噪声
        if (t.startsWith("#")) return
        if (t == "{" || t == "}" || t == "fi" || t == "done" || t == "esac" || t == "else") return
        if (captured.size >= 5000) return
        if (!seen.add(t)) return
        captured.add(t)
    }
/** 读取当前累计的全部真实指令流（复制一份，避免并发修改） */
    fun snapshot(): List<String> = captured.toList()

    /** 生成供 AI 阅读的指令流文本块 */
    fun toPromptBlock(): String {
        val snapshot = snapshot()
        return if (snapshot.isEmpty()) {
            "（本次运行未捕获到真实执行指令）"
        } else {
            val sb = StringBuilder()
            sb.appendLine("【运行时真实执行指令流（共 ${snapshot.size} 条）】")
            snapshot.forEachIndexed { i, cmd ->
                sb.appendLine("  ${i + 1}. $cmd")
            }
            sb.toString()
        }
    }

    /** 是否已捕获到有效真实指令 */
    fun hasTrace(): Boolean = captured.isNotEmpty()

    /** 开始一次新的采集（清空历史） */
    fun begin() {
        captured.clear()
        seen.clear()
        enabled = true
    }

    /** 结束采集 */
    fun end() {
        enabled = false
    }
}
