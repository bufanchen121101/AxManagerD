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

    /**
     * 【v1.4.1 新增】「命令模板」去重集合。
     *
     * 用户反馈「一个内存占用不大的脚本居然拦截出几千条指令，有很多重复的」。
     * 根因：模块里 `while read line; do echo "$x" > /sys/...; done < file` 这类循环，
     * 每次迭代的命令**文本不同**（变量展开后值不同：`echo 1 > ...` / `echo 2 > ...`），
     * `seen`（精确文本去重）拦不住，于是同一模式被重复记录上千遍，把真正不同的
     * 关键指令挤到 5000 上限之外。
     *
     * 解决：把命令归约成「模板」——抹掉数字、路径尾部、引号内的字面值等易变部分，
     * 只保留命令结构与工具名，按模板去重。第一个出现的保留原文，后续同模板的丢弃。
     * 例如：`echo 1 > /sys/devices/system/cpu/cpu0/...` 与
     *       `echo 2 > /sys/devices/system/cpu/cpu1/...` 归为同一模板，只留第一条。
     */
    private val seenTemplate = java.util.concurrent.CopyOnWriteArraySet<String>()

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

    /**
     * 【v1.4.1 新增】判断是否是「无实际用途的纯输出/提示类」命令，应过滤。
     *
     * 用户明确要求：「请不要把输出这样的 echo "" 的没有实际用途的也列出来，
     * 但是 echo > 这样写入的还是要的」。
     *
     * 规则（仅针对 echo / printf，且**必须不带写重定向**）：
     *   - `echo`（裸）、`echo ""`、`echo ''`、`echo -e`、`echo -n`、`echo $var`、
     *     纯提示文本（无 `>` 重定向、无 `tee`）→ 只是往屏幕打印，无副作用 → 过滤
     *   - `echo 1 > /sys/...`、`printf ... > /proc/...`、`echo x | tee f` → 写操作 → 保留
     *
     * 也过滤 `clear`、单独的 `:`（no-op）、`true`/`false` 这类无副作用语句。
     */
    private fun isUselessOutput(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.isEmpty()) return true
        // 无副作用的 no-op / 清屏
        if (c == ":" || c == "clear" || c == "true" || c == "false") return true
        val firstToken = c.substringBefore(' ').substringBefore('\t').substringAfterLast('/')
        if (firstToken == "echo" || firstToken == "printf") {
            // 带写重定向或 tee 的是核心写入行为，保留
            val isWriteOp = c.contains(">") || c.contains("tee ")
            if (isWriteOp) return false
            // 无写操作：纯屏幕输出/提示 → 过滤（无论内容是空串、变量还是文本）
            return true
        }
        return false
    }

    /**
     * 【v1.4.1 新增】把命令归约成「模板」，用于模糊去重（消除循环里变量展开造成的重复）。
     *
     * 做法：
     *   - 所有连续数字 → `#`      （cpu0/cpu1、0/1/2、1568000 等）
     *   - 引号内的字面内容 → `''`  （避免 `echo "cpu$i"` vs `echo "cpu1"` 不同）
     *   - 去除首尾空白
     * 例如：
     *   `echo 1 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor`
     *   `echo 2 > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor`
     *   → 同为 `echo # > /sys/devices/system/cpu/cpu#/cpufreq/scaling_governor`
     */
    private fun toTemplate(cmd: String): String {
        var s = cmd.trim()
        // 抹掉引号内容（单双引号）
        s = s.replace(Regex("'[^']*'"), "''").replace(Regex("\"[^\"]*\""), "\"\"")
        // 连续数字统一成 #
        s = s.replace(Regex("[0-9]+"), "#")
        // 折叠空白
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
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
        // 【v1.4.1】过滤无实际用途的纯输出（echo ""/echo 裸/提示文本等），
        // 但 `echo > file` 这类写操作保留。
        if (isUselessOutput(cmd)) return
        if (isNoise(cmd)) return
        // 复用 strace 通道的强黑名单：sh -x（xtrace）同样会抓到大量 rm -rf、
        // chmod 777、mkdir、cp、tar、base64、grep/sed/awk 等「解析/部署/文本处理」噪声，
        // 若不与 strace 通道统一过滤，这些会淹没 am/cmd/pm/setprop/mount 等核心指令。
        if (isStraceNoise(cmd)) return
        if (captured.size >= 800) return
        if (!seen.add(cmd)) return
        // 【v1.4.1】模板级去重：循环里变量展开后的同模式命令只保留第一条，
        // 避免"一个脚本几千条、大量重复"。
        if (!seenTemplate.add(toTemplate(cmd))) return
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
        // 【v1.4.0 修复：execve 行不带重定向，不能套用 isNoise 的内核节点判定】
        // isNoise() 靠 `>` / `tee` 判断"是否写内核节点"，但 parseExecveLine 只取 argv、
        // **天然丢失 `> /sys/...` 重定向**，导致 `echo 1 > /sys/...` 被误判为「只读探测」过滤掉。
        // 因此对 strace execve 行：只要首 token 不是纯只读工具（cat/grep/ls），就保留。
        // 因为能被 execve 出来的是「真外部命令」，本身就有业务含义（内建命令不会出现在这）。
        val firstToken = cmd.substringBefore(' ').substringAfterLast('/')
        val readOnlyTools = setOf("cat", "grep", "ls", "find", "which", "ps", "tail", "head", "cut", "wc", "getprop")
        if (firstToken in readOnlyTools) {
            // 只读工具：仍需过黑名单（可能是壳的探测）
            if (isStraceNoise(cmd)) return
        } else {
            // 非只读工具：只过窄黑名单（解密链）
            if (isStraceNoise(cmd)) return
        }
        if (captured.size >= 800) return
        if (!seen.add(cmd)) return
        // 【v1.4.1】模板级去重（同 addTrace）
        if (!seenTemplate.add(toTemplate(cmd))) return
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
        // 【v1.4.0 修复：黑名单收窄，不再误杀业务指令】
        // 用户反馈「拦截到的指令不准确（非加密模块）」。根因是旧黑名单把大量**模块真实业务
        // 操作**当成壳脚手架误杀，导致 AI/用户看到的指令流与实际行为对不上（不是内容错，
        // 是整类真指令被吞了）。具体误杀项及后果：
        //   - `cp`/`mv`/`rm`：模块替换/备份系统配置文件（如换 thermal.conf）→ 被吞
        //   - `dd`：模块写分区/烧写镜像（危险操作！）→ 被吞（最严重）
        //   - `chmod`/`ln`/`touch`/`mkdir`：模块部署关键文件/改权限/建软链 → 被吞
        //   - `[`/`test`：`if [ "$x" = "1" ]` 判断分支 → 被吞（断链，后续指令看起来"凭空出现"）
        //   - `cat`/`head`/`cut`/`grep`：模块读取当前配置值再决定写入 → 被吞（丢掉上下文）
        //   - `uname`/`id`/`expr`/`uniq`/`sort`/`wc`：模块品牌/机型判定分支 → 被吞
        // 现只保留「纯解密/解包脚手架」这一窄类——它们确实是加密壳产物，无业务含义。
        // 注意：`echo`/`printf`/`sleep` 已在上面单独处理（带写操作保留）。
        if (base in setOf(
                "tar", "zcat", "gzip", "gunzip", "xxd",
                "awk", "sed", "tr", "mktemp"
            )
        ) {
            return true
        }
        // base64：仅当作为「解码/编码链」使用时过滤；`base64 file` 读文件后解码属解密链。
        // 但 `base64 -d <<< ...` 也可能是模块自己的数据编码（非壳），故只在带 -d/--decode 时过滤。
        if (base == "base64" && (c.contains(" -d") || c.contains("--decode"))) return true
        // unzip/zip：解包链（壳脚手架）。模块业务很少直接 unzip，除非安装资源包——
        // 但那条属于部署噪声，且解包产物会以其他方式出现，故保留过滤。
        if (base == "unzip" || base == "zip") return true
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
        if (captured.size >= 800) return
        if (!seen.add(t)) return
        // 【v1.4.1】模板级去重（同 addTrace）
        if (!seenTemplate.add(toTemplate(t))) return
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
        // 【v1.4.1】模板去重集合必须同步清空，否则跨次运行会误杀本次的新指令
        seenTemplate.clear()
        enabled = true
    }

    /** 结束采集 */
    fun end() {
        enabled = false
    }
}
