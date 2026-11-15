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
    /**
     * 【1.4.2 自适应过滤】过滤强度。
     *
     * 用户反馈：**未加密（未混淆）模块**运行时只拦截到 `echo` 提示行，真正的
     * `chmod / cp / cat / settings` 等业务指令全被聚合/黑名单吞掉；而混淆模块又确实
     * 需要重过滤，否则解密脚手架会淹掉核心动作。
     *
     * 因此改为「按抓取到的真实执行行自动判定」：
     *  - 起始一律 [LENIENT]（少过滤，尽量保留真实指令）；
     *  - 边抓边给每一行做混淆打分（解密/解包工具、超长行、`\xNN` 密度、
     *    脚手架占比、不可打印字符）；分数达到 [OBF_ESCALATE_AT] 即判定为混淆/加密
     *    模块，自动切到 [STRICT]（= 原有重过滤方案），并把已抓到的原始行
     *    **重放一遍**重新过滤，保证前后口径一致。
     *
     * 判定完全基于「拦截抓取到的内容」，不读取模块脚本原文。
     */
    enum class FilterMode { LENIENT, STRICT }

    /** 当前过滤强度（可被混淆打分自动升级；[begin] 会重置为 LENIENT）。 */
    @Volatile
    var mode: FilterMode = FilterMode.LENIENT
        private set

    /** 混淆判定分数（由抓取到的真实执行行累积，供日志排查）。 */
    @Volatile
    var obfuscationScore: Int = 0
        private set

    /** 达到该分数即判定为混淆/加密模块，升级到 [FilterMode.STRICT]。 */
    private const val OBF_ESCALATE_AT = 2

    /**
     * 【v1.5.2】调用方已知的「混淆 / 加密模块」标记。
     *
     * 只由 [begin] 显式传入（默认 false），不会自己变 true，也不会跨 [begin] 残留 ——
     * 这样同一个采集器被别的模块复用时不会把普通模块误判成加密模块。
     */
    @Volatile
    private var encryptedHint = false

    /** 已命中的解密/解包工具类别。 */
    private val cryptoSeen = HashSet<String>()
    /** 脚手架行数 / 参与评分的行数。 */
    private var scaffoldLines = 0
    private var scoredLines = 0

    /** 顺序 B（命令在前、重定向在后）时暂存的纯输出命令，等下一行重定向片段来拼。 */
    @Volatile
    private var pendingOutputCmd: String? = null

    /**
     * 给一条真实命令行打混淆分。
     *
     * 命中项（各计 1 分，解密工具类最多计 2 分）：
     *  - `base64 -d` / `openssl` / `xxd` / `zcat` / `gzip -d` / `eval` / `tr -d`
     *  - 超长行（> 800 字符，壳常把整个 payload 塞一行）
     *  - 密集 `\xNN`（≥ 8 处）
     *  - 不可打印字符占比 > 10%
     *  - 脚手架（变量赋值 / 控制结构）占比 > 75%（样本 > 40 行时）
     *
     * 实测：正常性能模块流 0 分；base64+zcat+eval+超长行的混淆壳流 5 分。
     */
    private fun scoreObfuscation(cmd: String) {
        val b = cmd.trim()
        if (b.isEmpty()) return
        scoredLines++
        // ① 解密 / 解包 / 编码链（预编译，避免每行重复构造正则）
        for ((regex, name) in CRYPTO_PATTERNS) {
            if (name in cryptoSeen) continue
            if (regex.containsMatchIn(b)) {
                cryptoSeen.add(name)
                if (cryptoSeen.size <= 2) {
                    obfuscationScore++
                }
            }
        }
        // ② 超长单行
        if (b.length > 800) obfuscationScore++
        // ③ \xNN 转义密度
        if (REGEX_HEX_ESC.findAll(b).count() >= 8) obfuscationScore++
        // ④ 脚手架占比
        val first = b.split(' ').firstOrNull()?.substringAfterLast('/') ?: ""
        if (REGEX_ASSIGN.containsMatchIn(b) || first in SCAFFOLD_WORDS) scaffoldLines++
        if (scoredLines > 40 && scaffoldLines.toFloat() / scoredLines > 0.75f) {
            obfuscationScore++
        }
        // ⑤ 不可打印字符占比
        if (b.length > 40) {
            val bad = b.count { it.code < 32 && it != '\t' }
            if (bad.toFloat() / b.length > 0.10f) obfuscationScore++
        }
        // 达到阈值 → 判定混淆：切到重过滤，并把**已经收进来的宽松结果单向收紧**。
        // 注意：绝不重建结果 —— 重建只能重放 xtrace 行，会把 strace（execve）通道
        // 抓到的业务指令一起抹掉，只剩壳的 base64 中间产物（v1.4.1 的回归）。
        if (obfuscationScore >= OBF_ESCALATE_AT && mode == FilterMode.LENIENT) {
            mode = FilterMode.STRICT
            tightenCollected()
        }
    }

    private val REGEX_HEX_ESC = Regex("\\\\x[0-9a-fA-F]{2}")

    /** 【v1.5.2】`[ ... ]` / `[[ ... ]]` 纯判断语句（容忍 mksh xtrace 加的引号与粘连）。 */
    private val REGEX_TEST_STMT = Regex("""^['"]?\[{1,2}['"]?(\s|$|\d)""")

    /** 混淆壳「解密 / 解包 / 编码」工具清单（预编译）。 */
    private val CRYPTO_PATTERNS = listOf(
        Regex("\\bbase64\\s+(-d|--decode)") to "base64-d",
        Regex("\\bopenssl\\b") to "openssl",
        Regex("\\bxxd\\b") to "xxd",
        Regex("\\bzcat\\b") to "zcat",
        Regex("\\b(gzip\\s+-d|gunzip)\\b") to "gzip-d",
        Regex("\\beval\\b") to "eval",
        Regex("\\btr\\s+-d") to "tr-d",
    )

    private val REGEX_ASSIGN = Regex(
        "^(export\\s+|local\\s+|declare\\s+|readonly\\s+|typeset\\s+)?" +
            "[A-Za-z_][A-Za-z0-9_]*(\\[[^\\]]*\\])?(\\+)?="
    )

    private val SCAFFOLD_WORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until",
        "do", "done", "case", "esac", "in"
    )

    /**
     * 升级到 [FilterMode.STRICT] 后，把**已经收进来的宽松结果**单向收紧一次：
     * 把「严格模式下会被丢弃」的行从 captured / effectCmd / writeIntents 里剔除，
     * 其余**原样保留**（顺序不变）。
     *
     * 为什么不做「清空 + 重放」：本采集有两条通道（sh -x 的 xtrace、strace 的 execve），
     * 重放只能重放 xtrace 行，而大量业务指令是 strace 先抓到的 ——
     * 重建会把它们一起抹掉，结果只剩壳的 base64 中间产物（v1.4.1 的真实回归原因）。
     */
    private fun tightenCollected() {
        val kept = captured.filter { !isStrictRejected(it) }
        if (kept.size != captured.size) {
            captured.clear()
            captured.addAll(kept)
            // 去重集合按保留项重建，避免被剔除的行继续压制同名命令
            seen.clear()
            seen.addAll(kept)
            seenTemplate.clear()
            kept.forEach { seenTemplate.add(toTemplate(it)) }
        }
        // 写入后果：来源命令被判为噪声的条目一并删除（否则会显示壳的中间产物）
        val dropEffectKeys = effectCmd.entries.filter { isStrictRejected(it.value) }.map { it.key }
        dropEffectKeys.forEach { effectCmd.remove(it) }
        // 【v1.5.3 · 沙箱写意图不参与收紧】
        // writeIntents 全部来自预演沙箱实际拦下的写调用日志（`pm disable-user` / `settings put` /
        // `axeron-dpm suspend` …），这批命令是「模块真的想对系统做什么」的权威记录 ——
        // v1.4.0 起就没有对它们做过二次清洗。之前这里按 isStrictRejected 剔一遍，
        // 一旦命中名单（例如把整条命令当成只读探测）就再也无法从别处恢复：
        // strace 只跟踪 execve，拿不到写入目标，等于真指令被永久抹掉。
        // 因此这里不再做剔除（行为与 v1.4.0 对齐）。
    }

    /** 严格模式（[FilterMode.STRICT]）下这条命令会不会被丢弃（纯判定，无副作用）。 */
    private fun isStrictRejected(cmd: String): Boolean {
        if (cmd.isBlank()) return false
        return isUselessOutput(cmd) ||
            isXtraceScaffold(cmd) ||
            isReadOnlyProbe(cmd) ||
            isNoise(cmd) ||
            isStraceNoise(cmd)
    }

    /** 清空所有「派生结果」。 */
    private fun resetDerived() {
        captured.clear()
        seen.clear()
        seenTemplate.clear()
        synchronized(straceCmds) {
            straceCmds.clear()
            straceCryptoSeen.clear()
        }
        synchronized(probeLock) {
            probeCounts.clear()
            probeSamples.clear()
        }
        clearRedirectFragments()
        pendingOutputCmd = null
        effectCmd.clear()
        writeIntents.clear()
        synchronized(xtraceRaw) {
            xtraceRaw.clear()
            xtraceRawSeen.clear()
        }
        synchronized(xtracePending) { xtracePending.clear() }
        // 【v1.5.7】`-v` 原文行缓存也要清，避免跨轮次把上一轮的原文配给本轮的失败写入
        pendingVerbatim = null
        synchronized(verbatimByTarget) {
            verbatimByTarget.clear()
            verbatimTemplates.clear()
        }
    }

    /** 供抓取结束时打日志：本次判定结果。 */
    fun filterSummary(): String =
        "mode=" + mode.name + " score=" + obfuscationScore + " lines=" + scoredLines


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

    /**
     * 【v1.4.7 新增 · 修复「AI 拦截过滤仍有噪声」】
     *
     * 问题：即使经过 isXtraceScaffold / isStraceNoise 过滤，混淆/加密模块的
     * **探测阶段**（`cat /proc/...`、`getprop`、`grep`、`sed`（无 -i）、`awk`、
     * `ls`、`ps`、`cut`、`head`、`wc` …）仍会逐条进入「核心指令流」，动辄上百条，
     * 把模块真正的核心动作（写内核节点、pm/am/settings/axeron-dpm）淹到后面，
     * 用户看到的「拦截结果」就仍旧是一屏噪声。
     *
     * 这些探测指令**并非完全没有信息量**（它们能还原模块的判断依据），
     * 因此本版不再「一刀切删除」，而是改为**同类聚合**：
     *   - 只读探测类不再逐条占用核心指令流（captured）；
     *   - 按模板（toTemplate，抹掉数字与字面值）聚合计数，保留一条样本；
     *   - 在 toPromptBlock() 末尾以「环境探测（同类已聚合）」独立区块输出。
     *
     * 效果：AI 与界面仍能看到探测内容，但核心指令不再被淹没；
     * 同时核心指令流的 800 条上限不再被探测浪费。
     *
     * 安全边界：带写重定向（`>` / `>>` / `| tee`）与 `sed -i` 这类**真实写入**行为
     * 一律视为核心指令，绝不聚合（见 isReadOnlyProbe）。
     */
    private const val MAX_PROBE_TEMPLATES = 40
    private val probeLock = Any()
    /** 模板 → 出现次数（保序：用同步的 LinkedHashMap） */
    private val probeCounts = LinkedHashMap<String, Int>()
    /** 模板 → 首条原文样本（供展示） */
    private val probeSamples = HashMap<String, String>()

    /**
     * 【v1.4.8 关键修复】Android 真机 mksh 把「重定向」单独打成一行 trace。
     *
     * 实测（`/system/bin/sh` = mksh）：
     *   `echo 1 > /data/local/tmp/z1` 的 xtrace 是**两行**——
     *     `+ >/data/local/tmp/z1`
     *     `+ echo 1`
     * （dash/bash 才会把重定向并进命令行里；Android 上不会。）
     *
     * 旧实现把第一行当命令记录、第二行交给 isUselessOutput 判为「无重定向的纯输出」
     * 直接丢弃 —— 于是每一次写操作都产出「一条裸 `>/path` 垃圾 + 一条被误杀的真指令」，
     * 用户看到的就是「大量无效指令，而且没有正确的指令」。
     *
     * 修复：把「只由重定向构成的 trace 行」先缓存，拼到紧接着的那条命令后面，
     * 还原成 `echo 1 >/data/local/tmp/z1`。此后下游判定（isUselessOutput /
     * isReadOnlyProbe / isNoise 全部靠 `>` 判断「是否真实写入」）才真正成立。
     *
     * 只认 `>` / `>>`（写）；`<` / `<<`（读重定向，含 heredoc）丢弃不拼；
     * `2>&1` 这类 fd 复制不是文件写入，丢弃；`>/dev/null` 不算真实写入，也不拼
     * （这样 `echo "提示" >/dev/null` 仍会被当作纯输出过滤掉）。
     */
    private val pendingLock = Any()
    private val pendingFragments = ArrayList<String>()

    /** 「纯重定向片段」形态：可选 fd 数字 + `<`/`>`（1~2 个）+ 无空格目标。 */
    private val redirectFragmentRe = Regex("^([0-9]*)(<{1,2}|>{1,2})(\\S*)$")

    /**
     * 【v1.5.6】mksh 写重定向被系统拒绝时的报错行。
     *
     * 实测（Android mksh）：
     *   `./action.sh[41]: can't create /sys/.../scaling_governor: Permission denied`
     */
    private val deniedWriteRe = Regex("""(?:can't|cannot) create\s+(\S+?):""")

    /**
     * 【v1.5.7】`-v`（verbose）通道：最近一条「脚本原文行」。
     *
     * 存在原因：mksh 在写重定向失败时**不会**打印 `+ <命令>`（重定向先建立、失败即放弃，
     * 命令根本没有执行，也就没有东西可 `-x`）。实测唯一还能拿到命令本体的通道是
     * `sh -v`——它在**读入行时**就打印原文，与执行成败无关：
     *
     *   -v : `echo 0 > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null`
     *   -x : `+ >/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq`
     *   错误: `xx.sh[4]: can't create /sys/.../scaling_min_freq: Permission denied`
     *
     * 这里只暂存「含引号外写重定向」的原文行，命中「写被拒」时把它还原成命令本体，
     * 用完即清。**只服务于失败写入的回填**，不参与正常指令流，不会污染已抓到的命令。
     */
    @Volatile
    private var pendingVerbatim: String? = null

    /**
     * 【v1.5.7】`-v` 原文行按「字面写目标」索引：目标路径 → 命令原文。
     *
     * 为什么要按目标索引：`sh -v` 会把脚本原文**按读入顺序**整份打印一遍，
     * 执行阶段中间可能插入别的原文行（`if`、`chmod`、函数体 …）。只靠「最近一条」配对，
     * 对**字面路径**的写法（`echo 0 > /sys/.../scaling_min_freq`）就不够精确。
     * 这里对「目标像路径」的原文行建索引，写失败时优先按目标精确取原文；
     * 变量目标（`> "$file"`）建不了索引，仍走「最近一条」兜底。
     */
    private val verbatimByTarget = HashMap<String, String>()

    /**
     * 【v1.5.7】`-v` 原文行的「变量目标模板」索引：模板正则 → 命令原文。
     *
     * 存在原因：循环里的写法是
     *   `echo performance > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor 2>/dev/null`
     * 而报错给出的是展开后的真实路径 `…/cpu0/…`。字面索引查不到它，只能退回「最近一条原文」，
     * 结果就是：循环只跑完首轮就再也配不上原文（cpu2/cpu3 整条消失），而且被配到的是
     * **最后一条**原文（`echo performance` 被串成 `echo 0`）。
     *
     * 这里把路径里的 `$变量` 当成通配段（`[^/\s]+`）编成模板，任何 `cpu0..cpuN`
     * 都能命中自己对应的那条原文，不再依赖「顺序」也不再消耗一次性配额。
     * 只接受**以 `/` 开头且固定部分足够长**的目标，防止 `/$file` 这类模板匹配一切路径。
     */
    private val verbatimTemplates = ArrayList<Pair<Regex, String>>()

    /**
     * 暂存一行「脚本原文行」（来自 `sh -v`），供写失败时回填命令本体。
     *
     * 过滤：xtrace 行（`+ …`）、报错行、注释行、不含写重定向的行一律忽略 ——
     * 只有「看起来真的在往文件写」的原文行才值得配对。
     */
    private fun captureVerbatim(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return
        if (isTraceLine(t)) return
        if (t.startsWith("#")) return
        // 报错行（`can't create …`）不是命令原文
        if (deniedWriteRe.containsMatchIn(t)) return
        // 不含引号外写重定向 → 不是写命令，与本通道无关
        if (!hasWriteRedirect(t)) return
        pendingVerbatim = t
        // 建索引：字面目标走精确表；含 `$变量` 的目标走模板表
        writeTargets(t).forEach { p ->
            synchronized(verbatimByTarget) {
                if (p.contains('$')) {
                    if (verbatimTemplates.size < MAX_EXTRA_EXEC * 2) {
                        val re = buildTemplateRegex(p)
                        if (re != null && verbatimTemplates.none { it.first.pattern == re.pattern }) {
                            verbatimTemplates.add(re to t)
                        }
                    }
                } else if (!verbatimByTarget.containsKey(p) && verbatimByTarget.size < MAX_EXTRA_EXEC * 2) {
                    verbatimByTarget[p] = t
                }
            }
        }
    }

    /**
     * 【v1.5.7】把一个「含 `$变量` 的写目标」编成路径模板。
     *
     * `…/cpu$i/cpufreq/scaling_governor` → `^…/cpu[^/\s]+/cpufreq/scaling_governor$`
     *
     * 拒绝建模板的两种情况（避免一条模板配到所有路径、造出假指令）：
     * ① 不以 `/` 开头（`"$file"` / `"$dir/x"` 这类整体变量，交给「最近一条」兜底）；
     * ② 去掉变量后固定字符太少（`/$x`），辨识度不足。
     */
    private fun buildTemplateRegex(raw: String): Regex? {
        if (!raw.startsWith("/") || !raw.contains('$')) return null
        val sb = StringBuilder("^")
        var i = 0
        var fixed = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '$') {
                var j = i + 1
                if (j < n && raw[j] == '{') {
                    j++
                    while (j < n && raw[j] != '}') j++
                    if (j < n) j++
                } else {
                    while (j < n && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                }
                // 光秃秃的 `$`（后面不是变量名）：按普通字符处理
                if (j == i + 1) {
                    sb.append("\\$")
                    fixed++
                    i++
                    continue
                }
                sb.append("[^/\\s]+")
                i = j
                continue
            }
            sb.append(if (c in "\\.^$|?*+()[]{}") "\\$c" else c.toString())
            if (c != '/') fixed++
            i++
        }
        if (fixed < 8) return null
        sb.append('$')
        return Regex(sb.toString())
    }

    /**
     * 【v1.5.7】给一个「展开后的真实写入目标」找对应的 `-v` 原文行。
     *
     * 顺序：字面精确命中 → 变量模板命中 → null（由调用方决定是否走「最近一条」兜底）。
     */
    private fun findVerbatimFor(target: String): String? {
        synchronized(verbatimByTarget) {
            verbatimByTarget[target]?.let { return it }
            for ((re, text) in verbatimTemplates) {
                if (re.matches(target)) return text
            }
        }
        return null
    }

    /**
     * 【v1.5.6 修复 · 拦截结果漏掉大量 `echo x > /sys/...`】
     *
     * 真机实测：免 Root（shell 身份）下模块的写内核节点操作会被系统拒绝，此时 xtrace
     * **只打印两行**：
     *   `+ >/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor`
     *   `./action.sh[41]: can't create /sys/...: Permission denied`
     * 命令本体 `+ echo performance` 根本不会出现（重定向失败 → 命令根本没执行）。
     *
     * 旧实现遇到这行报错只做「清空片段」，于是这些写入**整条消失** ——
     * 用户看到的就是「拦截结果里几乎没有 echo> 写节点，而 pm / settings 之类都在」
     * （后者是外部命令，由 strace 的 execve 通道抓到，不吃 xtrace 的亏）。
     *
     * 现在在清空前把挂起的写片段落成「未生效的写入」，保证结果与模块脚本一一对应。
     */
    private fun flushDeniedWrites(line: String) {
        if (!deniedWriteRe.containsMatchIn(line)) return
        val frags = ArrayList<String>()
        synchronized(xtracePending) {
            frags.addAll(xtracePending)
            xtracePending.clear()
        }
        synchronized(pendingLock) {
            frags.addAll(pendingFragments)
            pendingFragments.clear()
        }
        // 【v1.5.7】取出与之配对的 `-v` 原文行。
        // 注意：这里**不清空** pendingVerbatim —— `-v` 每执行一行都会重新打印，
        // 保留它可以让同一个函数里连续的多次写失败（CGO 的 write_val 会被调用二十多次）
        // 每次都配上原文；只有在 captureVerbatim 读到新的写原文行时才替换。
        val verbatim = pendingVerbatim
        var verbatimUsed = false
        frags.forEach { fragment ->
            val m = redirectFragmentRe.matchEntire(fragment.trim()) ?: return@forEach
            val op = m.groupValues[2]
            val target = m.groupValues[3].trim('\'', '"')
            if (!op.startsWith(">") || target.isEmpty() || target.startsWith("&")) return@forEach
            if (isIgnoredEffectTarget(target)) return@forEach
            val key = "写入|$target"
            if (effectCmd.containsKey(key)) return@forEach
            if (effectCmd.size >= MAX_EFFECTS) return@forEach
            // 【v1.5.7】用 `-v` 原文行还原命令本体（`echo 0 > /sys/...`）。
            // 之前只写「[未生效] 写入 <路径>」，界面上看不到是哪条命令 → 用户看到的就是
            // 「echo > 一条都没有」。
            // 优先按「字面目标」精确匹配原文（脚本里写死的路径最准）；
            // 匹配不到（目标来自变量）再退回「最近一条原文」，且只配给本次第一条片段，
            // 避免一条原文被摊到多条片段上造出假指令。
            val fallback = "[未生效] 写入 $target（被系统拒绝）"
            val exact = findVerbatimFor(target)
            val useNearest = exact.isNullOrBlank() && !verbatimUsed && !verbatim.isNullOrBlank()
            if (useNearest) verbatimUsed = true
            // 回退文本刻意**不写成可执行的重定向**（不带 `>`），避免这条记录被当成命令再次执行：
            // 单独一条 `>/sys/...` 会把该节点截断。这里只做“这次尝试过写它”的展示。
            effectCmd[key] = when {
                !exact.isNullOrBlank() -> materializeVerbatim(exact, target)
                useNearest -> materializeVerbatim(verbatim ?: fallback, target)
                else -> fallback
            }
        }
    }

    /**
     * 【v1.5.7】把 `-v` 原文行里的「未展开变量目标」替换成本次真实的写入路径。
     *
     * `sh -v` 打印的是**变量展开前**的原文，所以循环里的写法会是：
     *   `echo performance > /sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor 2>/dev/null`
     * 而 `-x` 与失败报错给出的是展开后的真实路径 `…/cpu0/…`。
     * 这里用真实 target 把原文里的 `$变量` 目标替换掉，还原成一条能直接读懂的指令。
     *
     * 只做「含 `$` 的目标子串替换」，不含变量时原样返回 —— 不会有任何额外改写。
     */
    private fun materializeVerbatim(verbatim: String, target: String): String {
        if (verbatim.isBlank()) return verbatim
        if (!verbatim.contains('$')) return verbatim
        val raw = scanRedirectTargets(verbatim)
        for (p in raw) {
            if (p != target && p.contains('$')) return verbatim.replace(p, target)
        }
        return verbatim
    }

    /** 若该行是纯重定向片段：缓存/丢弃它并返回 true（不作为命令记录）。 */
    private fun consumeRedirectFragment(cmd: String): Boolean {
        val m = redirectFragmentRe.matchEntire(cmd.trim()) ?: return false
        val op = m.groupValues[2]
        val target = m.groupValues[3]
        // 裸 `>` / `>>` / `<`：无目标，丢弃
        if (target.isEmpty()) return true
        // 读重定向（`<f` / `<<EOF`）：与「模块改了什么」无关，丢弃
        if (op.startsWith("<")) return true
        // `2>&1` / `>&2` 这类 fd 复制：不是写文件，丢弃
        if (target.startsWith("&")) return true
        // 写 /dev/null：等于没有写入，不拼（保留原命令，让纯输出过滤继续生效）
        if (target == "/dev/null") return true
        // 注意：必须保留完整的重定向片段文本（含 `>` / `2>` 等操作符）。
        // 只存路径会拼出 `echo x /sys/...` 这种「不是写操作」的假指令，
        // 反而会被 isUselessOutput 当成纯输出丢掉 —— 与修复目标相反。
        val fragment = cmd.trim()
        // 【1.4.2】顺序 B（命令在前、重定向在后）：上一条纯输出命令刚被丢弃，
        // 这里立刻把两者拼回一条真实写入，避免真写操作凭空消失。
        // 已经拼给上一条命令了，**不能再缓存片段** —— 否则它会被拼到后面某条无关命令上，
        // 造出一条现实中不存在的假指令。
        val pending = pendingOutputCmd
        if (pending != null) {
            pendingOutputCmd = null
            recordSynthesizedWrite("$pending $fragment")
            return true
        }
        synchronized(pendingLock) {
            if (pendingFragments.size < 8 && !pendingFragments.contains(fragment)) {
                pendingFragments.add(fragment)
            }
        }
        return true
    }

    /** 取出并清空已缓存的重定向目标（真实写入路径列表）。 */
    private fun drainRedirectFragments(): List<String> = synchronized(pendingLock) {
        if (pendingFragments.isEmpty()) return emptyList()
        val fragments = pendingFragments.toList()
        pendingFragments.clear()
        fragments
    }

    /** 清空缓存（换通道 / 新一轮采集时必须调用，避免把上一行的重定向拼到无关命令上）。 */
    private fun clearRedirectFragments() {
        synchronized(pendingLock) { pendingFragments.clear() }
    }

    /**
     * 【v1.4.8】stderr 出现「非 trace 行」时调用：打断「重定向 → 命令」的相邻关系。
     *
     * 真机实测：`echo x > /sys/...` 若重定向失败（权限不足），mksh **只打印**
     *   `+ >/sys/.../scaling_governor`
     *   `sh: can't create /sys/...: Permission denied`
     * 命令本身根本不会执行（也就没有对应的 `+ echo x` 行）。
     * 若不在这里清空，这个失败路径会被拼到**后面那条无关命令**上，
     * 造出一条现实中不存在的「假指令」。因此只要中间插入了非 trace 行，就丢弃缓存。
     */
    fun onInterleavedStderrLine(line: String) {
        if (line.isBlank()) return
        if (!isTraceLine(line)) {
            // 【v1.5.7】同 noteXtrace：非 trace 行可能是 `sh -v` 打印的脚本原文行
            captureVerbatim(line)
            // 【v1.5.6】被系统拒绝的写入先落成「未生效」项，再清空片段（避免整条消失）
            flushDeniedWrites(line)
            // 【1.4.2】非 trace 行同时打断「纯输出命令 → 重定向片段」的相邻关系，
            // 否则那个暂存命令会被拼到后面某条无关的重定向上，造出假指令。
            pendingOutputCmd = null
            clearRedirectFragments()
        }
    }

    /** 只读探测 / 纯文本处理类工具（不含任何写操作时视为探测）。 */
    private val probeTools = setOf(
        "cat", "tac", "head", "tail", "cut", "wc", "grep", "egrep", "fgrep", "zgrep",
        "sort", "uniq", "tr", "awk", "gawk", "sed", "strings",
        "ls", "find", "which", "whereis", "stat", "readlink", "realpath", "file",
        "du", "df", "ps", "id", "uname", "getprop", "dumpsys", "getenforce", "lsattr"
    )

    /**
     * 【v1.4.7 新增】文件部署 / 权限调整 / 清理类工具。
     *
     * 模块（尤其带解密壳的）解包后会逐文件 `chmod`/`cp`/`mkdir`/`ln`，
     * 一次性刷出几十上百条同模式指令；它们**不是业务核心**，但删除又会丢信息。
     * 故同样归入「同类聚合」区块。
     *
     * 刻意**不包含** `rm`：删除属于破坏性动作，必须逐条留在核心指令流里，
     * 避免看起来被"淡化"（危险检测虽另走规则匹配，但展示上也应显眼）。
     */
    private val deployTools = setOf(
        "cp", "mv", "mkdir", "rmdir", "chmod", "chown", "chgrp",
        "touch", "ln", "install", "unlink", "truncate"
    )

    /**
     * 【v1.4.7 新增】判断一条指令是否为「只读探测 / 纯文本处理」类。
     *
     * 判定顺序（从严保护核心动作）：
     *  1. 含 `>` / `>>` / `| tee` → 有真实写入 → **不是**探测（核心，保留）；
     *  2. `sed -i` / `sed --in-place` → 就地写文件 → **不是**探测（核心，保留）；
     *  3. 首 token（取 basename）属于 probeTools → 探测；
     *  4. `pm list …` / `pm path …` / `cmd package list …` / `dumpsys …` → 只读查询 → 探测。
     *
     * 注意：本函数只影响「是否进入核心指令流」，探测内容仍会以聚合形式出现在
     * toPromptBlock() 的独立区块里，因此不会丢信息。
     */
    private fun isReadOnlyProbe(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.isEmpty()) return false
        // ① 写重定向 / tee → 核心
        if (c.contains(">") || c.contains("| tee ") || c.contains("tee -")) return false
        // ② sed 就地写 → 核心
        if (c.contains("sed") && (c.contains(" -i ") || c.contains(" -i'") || c.contains(" -i\"") ||
                c.contains("--in-place"))) return false
        val first = c.substringBefore(' ').substringBefore('\t')
            .substringBefore(';').substringAfterLast('/')
        if (first in probeTools) return true
        // ③ 文件部署 / 权限调整类（批量 chmod / cp / mkdir …）→ 同样聚合
        if (first in deployTools) return true
        if (c.startsWith("pm list") || c.startsWith("pm path") || c.startsWith("pm dump") ||
            c.startsWith("cmd package list") || c.startsWith("dumpsys")
        ) return true
        return false
    }

    /** 记录一条探测指令（同类聚合计数，保留首条样本）。 */
    private fun recordProbe(cmd: String) {
        val t = toTemplate(cmd)
        synchronized(probeLock) {
            val cur = probeCounts[t]
            if (cur == null) {
                if (probeCounts.size >= MAX_PROBE_TEMPLATES) return
                probeCounts[t] = 1
                probeSamples[t] = cmd
            } else {
                probeCounts[t] = cur + 1
            }
        }
    }

    /** 生成「环境探测（同类已聚合）」区块文本；无探测时返回空串。 */
    private fun probeBlock(): String {
        val entries: List<Pair<String, Int>>
        synchronized(probeLock) {
            if (probeCounts.isEmpty()) return ""
            entries = probeCounts.entries.map { it.key to it.value }
        }
        val sb = StringBuilder()
        sb.appendLine("【环境探测 / 纯文本处理类指令（同类已聚合，共 ${entries.size} 类，仅供还原上下文）】")
        entries.forEach { (t, n) ->
            val sample = probeSamples[t] ?: t
            sb.appendLine("  - $sample" + if (n > 1) "（同类 ×$n）" else "")
        }
        return sb.toString()
    }

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
            // 【1.4.2 关键修复】以前用 `contains(">")` 判断「是否写操作」，
            // 于是 `echo '>>开始CPU优化'`（`>>` 在引号里）被当成写文件保留下来，
            // 而它其实是纯提示 —— 这正是用户看到「只抓到 echo」的直接原因。
            // 现在只认引号外的真实重定向 / tee（写目标还必须像路径）。
            //
            // 【v1.5.5 修复】用户反馈「echo > 就是写入，为什么抓取过滤时被过滤掉」：
            // 上一版用 writeTargets() 判断，而 writeTargets() 要求目标「像路径」
            // （`/` 开头 / 含 `/` / `name.ext`），于是 `echo 1 > $OUT`、`echo x > out`
            // 这类真实写入被判成「纯输出」直接丢掉。现在改用宽松判定：
            // 只要引号外有真实重定向（不排除变量、裸文件名）就保留。
            return !hasWriteRedirect(c)
        }
        return false
    }

    /**
     * 【v1.5.5】宽松判定：命令里是否存在「引号外的真实写重定向」。
     *
     * 与 [writeTargets] 的区别：**不要求目标像路径**（`> $OUT` / `> out` 也算写入），
     * 只排除 fd 复制（`>&1`）与黑洞/终端（`/dev/null`、`/dev/tty*`）。
     * 目的：echo / printf 只要真的往某个文件写，就绝不能在抓取阶段被当成「纯输出」丢掉。
     */
    private fun hasWriteRedirect(cmd: String): Boolean {
        var i = 0
        val n = cmd.length
        var quote: Char? = null
        while (i < n) {
            val ch = cmd[i]
            if (quote != null) {
                if (ch == quote) quote = null
                i++
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                i++
                continue
            }
            if (ch != '>') {
                i++
                continue
            }
            var j = i + 1
            if (j < n && cmd[j] == '>') j++
            // `>&1` / `2>&1`：fd 复制，不是写文件
            if (j < n && cmd[j] == '&') {
                i = j + 1
                continue
            }
            var k = j
            while (k < n && cmd[k].isWhitespace()) k++
            var m = k
            while (m < n && !cmd[m].isWhitespace() && cmd[m] !in ";|&<>()") m++
            val tgt = cmd.substring(k, m).trim('\'', '"')
            if (tgt.isNotEmpty() && !isIgnoredEffectTarget(tgt)) return true
            i = if (m > i) m else i + 1
        }
        // `| tee [-a] path`
        if (TEE_TARGET_RE.containsMatchIn(cmd)) return true
        return false
    }

    /**
     * 该行是否是「可能被 mksh 拆到重定向前面的纯输出命令」（echo / printf，且无真实写入）。
     * 只有这种行需要在 [pendingOutputCmd] 里暂存一行，等紧随的重定向片段拼回。
     */
    private fun isRedirectableOutput(cmd: String): Boolean {
        val first = cmd.trim().substringBefore(' ').substringAfterLast('/')
        if (first != "echo" && first != "printf") return false
        // 【v1.5.5】用宽松判定：只要已经带了真实重定向，就不需要再等片段拼回
        return !hasWriteRedirect(cmd)
    }

    /**
     * 【1.4.2】顺序 B 回补：把「被丢弃的纯输出命令 + 紧随其后的重定向片段」合成一条真实写入。
     *
     * 合成输入全部来自真实抓到的 trace 行，不会凭空产生指令；
     * 片段与命令之间若插入了任何非 trace 行，[onInterleavedStderrLine] 会清掉暂存，避免错拼。
     */
    private fun recordSynthesizedWrite(cmd: String) {
        writeTargets(cmd).forEach { path ->
            val key = "写入|" + path
            if (!effectCmd.containsKey(key) && effectCmd.size < MAX_EFFECTS) effectCmd[key] = cmd
        }
        if (captured.size >= 800) return
        if (!seen.add(cmd)) return
        if (!seenTemplate.add(toTemplate(cmd))) return
        captured.add(cmd)
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
        var cmd = cleanTraceLine(line)
        if (cmd.isBlank()) return
        if (cmd == "set +x" || cmd == "set -x") {
            clearRedirectFragments()
            return
        }
        // 【v1.4.8 关键修复】Android 真机 mksh 把重定向单独打成一行 trace（`+ >/data/...`），
        // 命令本体在下一行且不带重定向。这里先把重定向片段缓存下来，拼到紧随其后的命令上，
        // 否则每个写操作都会变成「一条裸 `>/path` 垃圾 + 一条被 isUselessOutput 误杀的真指令」。
        if (consumeRedirectFragment(cmd)) return
        val redirects = drainRedirectFragments()
        if (redirects.isNotEmpty()) cmd = "$cmd ${redirects.joinToString(" ")}"
        // 【1.4.2】混淆打分：输入是「我们抓到的真实执行行」，不是模块脚本原文。
        // 命中阈值会自动升级到 STRICT 并把已抓到的行重放一遍。
        scoreObfuscation(cmd)
        // 【v1.4.1】过滤无实际用途的纯输出（echo ""/echo 裸/提示文本等），
        // 但 `echo > file` 这类写操作保留（现在按「引号外的重定向」判定）。
        if (isUselessOutput(cmd)) {
            // 【1.4.2】顺序 B：mksh 有可能把命令打在重定向片段**之前**
            // （`+ echo 1` 然后 `+ >/sys/...`）。此时先暂存这一行，
            // 等紧随其后的重定向片段来拼回真实写入；拼不上就当纯提示丢弃。
            pendingOutputCmd = if (isRedirectableOutput(cmd)) cmd else null
            return
        }
        // 非纯输出的真实命令：打断「纯输出命令 → 重定向片段」的相邻关系，
        // 避免那个暂存行被拼到后面某条无关的重定向上（会造出假指令）。
        pendingOutputCmd = null
        // 【v1.4.2 关键修复 · 混淆模块「大量与模块无关的指令」】
        // xtrace 通道会把脚本执行的**每一行**都打印出来：变量赋值、`[ ]` 条件判断、
        // for/while 循环头、函数定义……未混淆的小脚本行数少，问题不明显；
        // 而混淆/加密模块脚本体积大（逐字节解密、循环拼接），这类**无副作用**行会
        // 成百上千条地涌入，把真正核心的动作（写内核节点、pm/am/settings）淹没。
        // 因此 xtrace 通道统一过滤脚手架；真外部命令由 strace 通道（execve）负责，
        // 这里过滤不会丢失核心指令。
        if (isXtraceScaffold(cmd)) return
        // 【1.4.2 自适应】以下三项重过滤只在 STRICT（混淆 / 加密模块）启用。
        // 未混淆模块走 LENIENT：真实业务指令（chmod / cp / cat / getprop /
        // settings / pm list …）全部保留，只过滤非命令的脚手架与纯提示输出。
        if (mode == FilterMode.STRICT) {
            // 【v1.4.7 新增】只读探测 / 纯文本处理类（cat/getprop/grep/awk/sed 无 -i …）
            // 不再逐条进入核心指令流，改为同类聚合后在 toPromptBlock() 末尾单独列出：
            // 信息不丢，核心动作不被淹没。
            if (isReadOnlyProbe(cmd)) {
                recordProbe(cmd)
                return
            }
            if (isNoise(cmd)) return
            // 复用 strace 通道的强黑名单：sh -x（xtrace）同样会抓到大量 rm -rf、
            // chmod 777、mkdir、cp、tar、base64、grep/sed/awk 等「解析/部署/文本处理」噪声，
            // 若不与 strace 通道统一过滤，这些会淹没 am/cmd/pm/setprop/mount 等核心指令。
            if (isStraceNoise(cmd)) return
        }
        if (captured.size >= 800) return
        if (!seen.add(cmd)) return
        // 【v1.4.1】模板级去重：循环里变量展开后的同模式命令只保留第一条，
        // 避免"一个脚本几千条、大量重复"。
        if (!seenTemplate.add(toTemplate(cmd))) return
        captured.add(cmd)
    }
    /**
     * 【v1.4.2 新增】判断一行 **xtrace（`sh -x`）** 输出是否属于「壳脚手架 / 无副作用」语句。
     *
     * 背景（用户实测反馈）：**混淆/加密模块**的脚本会拦截出「大量与模块功能无关的指令」，
     * 而未混淆模块正常。原因不是抓错了，而是 xtrace 会把脚本执行的**每一行**都打印：
     *   - 变量赋值：`s="${s}x"`、`i=0`、`n=$(...)`
     *   - 条件判断：`[ "$a" = "$b" ]`、`[[ -z "$x" ]]`
     *   - 控制结构：`if` / `for ... in ...` / `while` / `case` / `do` / `done`
     *   - 只读探测：`cat`、`head`、`cut`、`grep`、`sed`（壳在逐字节处理后解密）
     * 混淆壳的解读过程会产生成百上千条这类行，把模块真正做的核心动作淹没。
     * 这些行**没有任何实际副作用**，对「模块做了什么」零信息量，全部过滤。
     *
     * 安全边界（避免误杀）：
     *   1. 带写重定向（`>` / `>>` / `tee`）的命令**永远保留** —— 那是真实写入行为；
     *   2. 本函数**只作用于 xtrace 通道**；strace（execve）通道是真实外部命令，另行判定；
     *   3. 变量赋值、控制结构等**不是命令**，过滤它们不影响任何真实动作的可见性。
     */
    private fun isXtraceScaffold(cmd: String): Boolean {
        val c = cmd.trim()
        if (c.isEmpty()) return true
        // ① 带写重定向 / tee 的一律保留（真实写入内核节点、配置文件）
        if (c.contains(">") || c.contains("| tee ") || c.contains("tee -")) return false
        // ①-2 【v1.5.2】`[` / `]` 是 glob 特殊字符，mksh 的 xtrace 会给它加引号打印，
        // 而且可能与后一个 token 粘连（真机抓到的形态：`'['2000 -ne0 ']'`）。
        // 只看首 token 会漏，故用整串正则兜住 `'[' 2000 …`、`'['2000 …`、`[ 2000 …`、`[[ …`。
        if (REGEX_TEST_STMT.containsMatchIn(c)) return true
        // ② 变量赋值（含 export/local/declare/readonly/typeset 前缀，含数组下标与 +=）
        if (Regex("^(export\\s+|local\\s+|declare\\s+|readonly\\s+|typeset\\s+)?[A-Za-z_][A-Za-z0-9_]*(\\[[^\\]]*\\])?(\\+)?=")
                .containsMatchIn(c)
        ) return true
        // 【v1.5.2 修复】首 token 先剥掉引号：mksh 的 xtrace 会把 `[` / `]` 这类特殊字符
        // 打印成 `'['` / `']'`，不剥引号时首 token 是 `'['`、不等于 `[`，
        // `[ ... ]` 纯判断行就逃过了第 ⑤ 条判定。
        // 真实截图（加密模块）：`'['2000 -ne0 ']'` 直接占了「真实指令」的第 1 条。
        val first = c.split(Regex("[\\s;]+"), limit = 2).firstOrNull()
            ?.substringAfterLast('/')?.trim('\'', '"') ?: ""
        // ③ 控制结构关键字
        if (first in setOf(
                "if", "then", "else", "elif", "fi",
                "for", "while", "until", "do", "done",
                "case", "esac", "in"
            )
        ) return true
        // ④ 括号 / 函数定义 / 子 shell
        if (first in setOf("{", "}", "(", ")", "()")) return true
        if (Regex("^[A-Za-z_][A-Za-z0-9_]*\\(\\)\\s*\\{?").containsMatchIn(c)) return true
        // ⑤ 纯判断 / 跳转（无副作用）
        if (first in setOf("[", "[[", "test", "return", "break", "continue", "exit")) return true
        // ⑥ 无副作用内建 / 环境操作
        if (first in setOf(
                ":", "shift", "unset", "trap", "set", "ulimit", "wait",
                "umask", "hash", "times", "jobs", "bg", "fg", "getopts", "let"
            )
        ) return true
        // ⑦ 【v1.4.3 修复】**不再按工具名过滤**。
        //
        // v1.4.2 曾在此把 cat/head/cut/grep/sed/awk/tr/… 一并过滤，结果用户反馈
        // 「拦截结果里没有任何核心代码」—— 因为模块改配置、读参数、写节点的真实动作
        // 往往就落地在这些工具上（`sed -i`、`cat > f`、`awk ... > f`）。
        // 壳解密链的噪声改由 strace 通道的窄黑名单 + 模板级去重处理，此处不再重复过滤。
        return false
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
        // 【v1.4.8】strace 通道是另一条采集来源，行之间没有 mksh 的「重定向先行」关系，
        // 进入本通道前清空重定向缓存，避免把 xtrace 残留的 `>/path` 拼到 execve 命令上。
        clearRedirectFragments()
        val cmd = StraceHelper.parseExecveLine(line) ?: return
        if (cmd.isBlank()) return
        // 【1.4.3】混淆判定必须覆盖 strace（execve）通道：加密壳通过外部进程
        // （base64 / xxd / gzip …）解密，这些行只出现在 execve 通道。若只在 xtrace
        // 通道评分，加密模块会永远停在 LENIENT，壳的中间产物就会被当成业务指令留下来。
        scoreObfuscation(cmd)
        // 【v1.4.3 修复 · 撤销 v1.4.2 在此处对 isUselessOutput 的调用（过度过滤）】
        //
        // 原因：execve 行**天然丢失写重定向** —— parseExecveLine 只取 argv，
        // `echo 1 > /sys/devices/system/cpu/cpu0/.../scaling_governor` 解析出来就是 `echo 1`，
        // 在 isUselessOutput 眼里成了「无写操作的纯输出」而被过滤掉 ——
        // 而这恰恰是性能/温控模块的**核心指令**（用户反馈：拦截结果里没有核心代码了）。
        //
        // 结论：strace 通道没有重定向上下文，就不能用 isUselessOutput 判断"是否纯输出"。
        // 真外部命令（能被 execve 出来的）本身就有业务含义，交给 isStraceNoise 的窄黑名单处理即可。
        // 【v1.4.0 修复：execve 行不带重定向，不能套用 isNoise 的内核节点判定】
        // isNoise() 靠 `>` / `tee` 判断"是否写内核节点"，但 parseExecveLine 只取 argv、
        // **天然丢失 `> /sys/...` 重定向**，导致 `echo 1 > /sys/...` 被误判为「只读探测」过滤掉。
        // 因此对 strace execve 行：只要首 token 不是纯只读工具（cat/grep/ls），就保留。
        // 因为能被 execve 出来的是「真外部命令」，本身就有业务含义（内建命令不会出现在这）。
        // 【v1.4.7 修复】只读探测类（cat/grep/ls/getprop/ps/awk/sed 无写入…）原先会
        // 逐条进入核心指令流：混淆壳的探测阶段能刷出上百条，把真正的核心动作淹没。
        // 现在改为「不丢弃、不逐条」——归入同类聚合区块（toPromptBlock() 末尾单独列出）。
        if (mode == FilterMode.STRICT) {
            if (isReadOnlyProbe(cmd)) {
                recordProbe(cmd)
                return
            }
            // 非只读工具：过窄黑名单（纯解密/解包链）后保留。
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
                // ——壳「解密/解包/编码/校验」脚手架（混淆模块垃圾指令的主要来源）——
                // 【v1.4.3 收窄】只保留**纯加解密/解包/校验**工具（几乎不可能承载模块业务）。
                // 已移除的过度过滤项（v1.4.2 引入，会导致「拦截结果没有核心代码」）：
                //   - `awk` / `sed` / `tr`：模块改配置（`sed -i`）与文本处理的主力；
                //   - `sleep` / `usleep` / `stdbuf` / `flock` / `nice` / `ionice` /
                //     `xargs` / `sync` / `rmdir`：性能类模块的真实行为（刷盘、批量执行、调优先级）。
                "tar", "zcat", "gzip", "gunzip", "xz", "xzcat", "lzma", "unlzma",
                "zstd", "bzip2", "bunzip2", "lz4", "unlz4", "cpio", "ar", "rpm2cpio", "7z",
                "sqfs", "unsquashfs", "simg2img", "openssl", "gpg",
                "uudecode", "uuencode", "base32",
                "xxd", "od", "strings", "split", "csplit",
                "cksum", "md5sum", "sha1sum", "sha256sum", "sha512sum", "mktemp"
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
        // 【v1.4.8】带写重定向（`pm list packages > /data/...` 之类）属于真实写入，不能当只读丢弃。
        val isWriteOp = c.contains(">") || c.contains("tee ")
        if (!isWriteOp && (c.startsWith("pm list") || c.startsWith("pm path") ||
                c.startsWith("cmd package list") ||
                c.startsWith("dumpsys") || c.startsWith("getprop") || c.startsWith("ls ") ||
                c.startsWith("find ") || c.startsWith("which ") || c.startsWith("ps "))
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
    // =====================================================================
    // 【v1.5.0 后果优先采集（effect-first）】
    //
    // 为什么换思路：v1.4.x 一路都在「按命令文本 + 手写工具名单」判断谁算核心指令，
    // 结果两头都错 —— 名单外的只读工具被当核心（噪声淹没），核心动作换个拼写
    // （busybox pm / 绝对路径 / 变量拼命令 / sh -c）就匹配不到而被丢掉；再加上
    // captured 的 800 条上限先到先得，混淆壳前段刷出的脚手架会把脚本后段真正
    // 执行的 pm/am/settings 直接挤出名额。
    //
    // 现改为按「后果」判定，文本只用来回填说明：
    //   1) 写意图（预演沙箱拦下的写调用）：与拼写无关，只要走 PATH 就记下来；
    //   2) 写后果（xtrace 里带真实写重定向 / sed -i 的命令）：`> /sys/...`、`| tee /path`
    //      是 shell 内建写，strace 的 execve 看不到，只能从语法结构上认「它写了哪个文件」；
    //   3) 其余命令不再删除，只按工具名计数 —— 噪声永远不占核心名额。
    // =====================================================================

    /** 每条后果上限（独立预算：与噪声条数彻底解耦，不再被 800 上限挤掉） */
    private const val MAX_EFFECTS = 200

    /** strace 通道展示副本上限（独立预算，不与 captured 的 800 争名额） */
    private const val MAX_STRACE_CMDS = 600

    /** 写意图上限 */
    private const val MAX_INTENTS = 200

    /** 原始 xtrace 行缓存上限（仅用于给后果回填命令文本） */
    private const val MAX_XTRACE_RAW = 3000

    /**
     * 【v1.5.7】影响报告里「其余真实执行指令」的展示上限。
     *
     * 独立预算：只限制展示条数，不动采集上限（captured 的 800 条仍然照旧）。
     */
    private const val MAX_EXTRA_EXEC = 200

    /** 后果：key = 动作|目标（对象级去重，`for` 循环写三个路径就是三条） */
    private val effectCmd = LinkedHashMap<String, String>()

    /** 沙箱拦下的写调用（权威写意图，保序去重） */
    private val writeIntents = LinkedHashSet<String>()

    /** 已执行的外部命令（保序去重），用于统计「未产生变更」的条数 */
    private val execOrder = LinkedHashSet<String>()

    /**
     * 【v1.5.2 修复 · 只给混淆/加密模块用】strace（execve）通道抓到的命令副本。
     *
     * v1.9.1「后果优先」重构把 [addStraceEvent] 抓到的 execve 命令只塞进 [execOrder] 统计桶、
     * 再没有进入过任何展示出口，于是**加密模块**（payload 在子 shell 里跑、`sh -x` 看不进去）
     * 的真实动作（`axeron-dpm suspend …` / `pm disable-user …`）被整体丢弃，
     * 界面上只剩壳最外层的几行脚手架。
     *
     * 这里补一份副本；**是否输出由 [narrowOutput] 决定** —— 普通模块（LENIENT 且无解密链）
     * 完全不读它，行为与修复前逐字节一致。
     */
    private val straceCmds = LinkedHashSet<String>()

    /** strace 通道里出现过的「解密 / 解包 / 编码」类别（仅用于识别加密模块，不参与过滤打分）。 */
    private val straceCryptoSeen = HashSet<String>()

    /** xtrace 原始行（只增不删，供后果回填命令文本） */
    private val xtraceRaw = ArrayList<String>()
    private val xtraceRawSeen = HashSet<String>()

    /** sh 内建写的「重定向片段」缓存（mksh 会把 `> /path` 单独打一行） */
    private val xtracePending = ArrayList<String>()

    /** 一条核心项：动作描述 + 命中的命令原文 */
    private data class CoreItem(val action: String, val cmd: String)

    /** `| tee path` / `| tee -a path` */
    private val TEE_TARGET_RE = Regex("""\|\s*tee\s+(?:-[a-zA-Z]+\s+)*([^\s;|&<>]+)""")

    /** `sed -i` / `sed --in-place`（就地写文件） */
    private val SED_INPLACE_RE = Regex("""\bsed\s+(?:-\S+\s+)*--?i""")

    /**
     * 记录一行 strace 事件（execve / 文件写入 / 删除 / 重命名 / 挂载）。
     *
     * execve 只进「命令清单」与统计，不在采集期判定核心与否 —— 判定统一放到
     * [coreItems]，输入是「有没有后果」，不是「命令长得像不像」。
     */
    fun addStraceEvent(line: String) {
        if (line.isBlank()) return
        val cmd = StraceHelper.parseExecveLine(line)
        if (cmd != null) {
            if (cmd.isNotBlank()) {
                if (execOrder.size < 4000) execOrder.add(cmd)
                // 【v1.5.2 修复 · 加密模块真指令被丢弃】
                // v1.9.1 重构后 execve 命令只进 execOrder（统计桶），没有任何展示出口；
                // 而加密模块的 payload 在子 shell 里跑、`sh -x` 看不进去，真动作**只**在这里。
                // 故补一份展示副本，是否输出由 [narrowOutput] 决定（普通模块不读）。
                synchronized(straceCmds) {
                    if (straceCmds.size < MAX_STRACE_CMDS) straceCmds.add(cmd)
                    // 只把「解密/解包/编码」工具当作「这是加密模块」的证据：
                    // 不做打分、不改 mode，避免影响普通模块的过滤口径。
                    CRYPTO_PATTERNS.forEach { (regex, name) ->
                        if (regex.containsMatchIn(cmd)) straceCryptoSeen.add(name)
                    }
                }
            }
            return
        }
        // 阶段②（打开 openat/unlinkat/renameat/mount）时才会命中这里；
        // 阶段①只跟踪 execve，不会走到下面。
        val ev = StraceHelper.parseWriteEvent(line) ?: return
        if (isIgnoredEffectTarget(ev.target)) return
        val key = ev.kind + "|" + ev.target + (if (ev.extra.isBlank()) "" else "|" + ev.extra)
        if (effectCmd.containsKey(key)) return
        if (effectCmd.size >= MAX_EFFECTS) return
        effectCmd[key] = ""
    }

    /** 记录预演沙箱拦下的写调用（每行就是模块原样想执行的一条写命令）。 */
    fun addSandboxLog(text: String) {
        if (text.isBlank()) return
        text.split('\n').forEach { raw ->
            val t = raw.trim()
            if (t.isBlank()) return@forEach
            if (writeIntents.size >= MAX_INTENTS) return@forEach
            writeIntents.add(t)
        }
    }

    /**
     * 记录一条 xtrace 原始行（**必须在 [addTrace] 之前调用**）。
     *
     * 这里只做两件事：把 mksh 拆行的重定向拼回完整命令、从**语法结构**上认出写入目标。
     * 不看工具名、不看名单 —— 认的是「它往哪个文件写了」。
     */
    fun noteXtrace(line: String) {
        if (!isTraceLine(line)) {
            // 【v1.5.7】先把它当作可能的 `sh -v` 脚本原文行暂存（写失败时用来还原命令本体，
            // 见 captureVerbatim / flushDeniedWrites）。
            captureVerbatim(line)
            // 【v1.5.6】清空前先把「被系统拒绝的写入」落成未生效项（见 flushDeniedWrites）。
            flushDeniedWrites(line)
            // 非 trace 行会打断「重定向 → 命令」的相邻关系，丢弃未匹配片段
            pendingOutputCmd = null
            synchronized(xtracePending) { xtracePending.clear() }
            return
        }
        val body = cleanTraceLine(line).trim()
        if (body.isBlank()) return
        // mksh：纯重定向片段（`>/path`）先缓存，拼到下一条命令后面
        val m = redirectFragmentRe.matchEntire(body)
        if (m != null) {
            val op = m.groupValues[2]
            val target = m.groupValues[3]
            if (op.startsWith(">") && target.isNotEmpty() &&
                !target.startsWith("&") && !isIgnoredEffectTarget(target)
            ) {
                // 【1.4.2】顺序 B 回补：命令在前、重定向在后时补记写入后果。
                // 这里只 peek 不清空（随后 addTrace 会合成并消费它）；
                // 已回补过的片段**不缓存**，否则会被拼到后面无关命令上。
                val pending = pendingOutputCmd
                if (pending != null) {
                    val full = "$pending $body"
                    writeTargets(full).forEach { path ->
                        val key = "写入|" + path
                        if (!effectCmd.containsKey(key) && effectCmd.size < MAX_EFFECTS) {
                            effectCmd[key] = full
                        }
                    }
                    return
                }
                synchronized(xtracePending) {
                    if (xtracePending.size < 8 && !xtracePending.contains(body)) xtracePending.add(body)
                }
            }
            return
        }
        val frags = synchronized(xtracePending) {
            val f = xtracePending.toList()
            xtracePending.clear()
            f
        }
        // 非片段的真实命令：打断「纯输出命令 → 重定向片段」的相邻关系，
        // 避免暂存行被拼到后面某条无关的重定向上（会造出假指令）。
        pendingOutputCmd = null
        val full = if (frags.isEmpty()) body else (body + " " + frags.joinToString(" ")).trim()
        // 1) 写入目标（真实写后果）
        writeTargets(full).forEach { path ->
            val key = "写入|" + path
            if (!effectCmd.containsKey(key) && effectCmd.size < MAX_EFFECTS) effectCmd[key] = full
        }
        // 2) 原文缓存（阶段②给 strace 后果回填命令文本）
        synchronized(xtraceRaw) {
            if (xtraceRaw.size < MAX_XTRACE_RAW && xtraceRawSeen.add(full)) xtraceRaw.add(full)
        }
    }

    /**
     * 从命令文本里按「语法结构」抽出真实写入目标（不认工具名）。
     *
     * 覆盖：`> path` / `>> path` / `2> path` / `| tee path` / `sed -i path`。
     * 排除：`>&1` 这类 fd 复制、`/dev/null`、`/dev/tty` 系列、`/dev/pts` 下的终端、
     * `/proc/self` 下的自身状态、trace 日志。
     */
    private fun writeTargets(cmd: String): List<String> {
        val out = ArrayList<String>()
        // ① 引号外的 `>` / `>>`（`>&1`、`/dev/null`、`/dev/tty*` 不算）
        scanRedirectTargets(cmd).forEach { p ->
            if (!isIgnoredEffectTarget(p)) out.add(p)
        }
        // ② `sed -i` 就地写
        if (SED_INPLACE_RE.containsMatchIn(cmd)) {
            val last = cmd.split(' ').lastOrNull { it.isNotBlank() && !it.startsWith("-") }
                ?.trim('\'', '"')
            val looksLikePath = last != null && (
                last.startsWith("/") || last.startsWith("./") || last.startsWith("../") ||
                    (last.contains('/') && !last.contains(' ') && !last.startsWith("s/"))
                )
            if (looksLikePath && last != null && !last.contains("=") && !isIgnoredEffectTarget(last)) {
                out.add(last)
            }
        }
        return out
    }

    /**
     * 【1.4.2 关键修复】按「引号外」扫描真实写重定向目标。
     *
     * 旧实现用正则 `(?:^|[^0-9&>])>{1,2}\s*([^\s;|&<>()]+)` 直接扫文本、不认引号 ——
     * `echo '>>开始CPU优化'` 里的 `>>` 被当成写重定向，目标被记成 `开始CPU优化`，
     * 于是「纯提示行」被算成了「写文件」的核心动作（用户看到"只抓到 echo"的直接原因）。
     *
     * 现在逐字符扫描、跳过引号内容，且目标必须像路径（`/` 开头、含 `/` 或 `name.ext`），
     * 避免把提示文本里的符号误判成写入对象。
     */
    private fun scanRedirectTargets(cmd: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = cmd.length
        var quote: Char? = null
        while (i < n) {
            val ch = cmd[i]
            if (quote != null) {
                if (ch == quote) quote = null
                i++
                continue
            }
            if (ch == '\'' || ch == '"') {
                quote = ch
                i++
                continue
            }
            if (ch != '>') {
                i++
                continue
            }
            var j = i + 1
            if (j < n && cmd[j] == '>') j++
            // `>&1` / `2>&1`：fd 复制，不是写文件
            if (j < n && cmd[j] == '&') {
                i = j + 1
                continue
            }
            var k = j
            while (k < n && cmd[k].isWhitespace()) k++
            var m = k
            while (m < n && !cmd[m].isWhitespace() && cmd[m] !in ";|&<>()") m++
            val tgt = cmd.substring(k, m).trim('\'', '"')
            if (tgt.isNotEmpty() && looksLikePath(tgt)) out.add(tgt)
            i = if (m > i) m else i + 1
        }
        // `| tee [-a] path`
        TEE_TARGET_RE.findAll(cmd).forEach { mt ->
            val t = mt.groupValues[1].trim('\'', '"')
            if (t.isNotEmpty() && looksLikePath(t)) out.add(t)
        }
        return out.distinct()
    }

    /** 目标是否像文件 / 内核节点路径（避免把普通词误判成写入对象）。 */
    private fun looksLikePath(p: String): Boolean {
        if (p.isEmpty() || p.contains(' ')) return false
        if (p.startsWith("/") || p.startsWith("./") || p.startsWith("../")) return true
        if (p.contains('/')) return true
        // 相对文件名：out.log / config.bak
        return Regex("^[A-Za-z0-9_.\\-]+\\.[A-Za-z0-9]{1,8}$").matches(p)
    }

    /** 这些目标的「写入」没有业务含义（黑洞、终端、自身状态、trace 日志）。 */
    private fun isIgnoredEffectTarget(path: String): Boolean {
        val p = path.trim().trim('"', '\'')
        if (p.isEmpty()) return true
        return p == "/dev/null" || p.startsWith("/dev/tty") || p.startsWith("/dev/pts/") ||
            p.startsWith("/proc/self/") || p.startsWith("/data/local/tmp/ax_trace") ||
            p == DryRunSandbox.LOG
    }

    /**
     * 汇总本次采集的核心项：后果（写入/删除/改名/挂载）+ 沙箱写意图。
     *
     * 顺序即真实执行顺序；同一条命令只出现一次（按命令原文去重）。
     */
    private fun coreItems(): List<CoreItem> {
        val items = ArrayList<CoreItem>()
        effectCmd.forEach { (key, cmd) ->
            val parts = key.split("|")
            val kind = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "" }
            val extra = parts.getOrElse(2) { "" }
            val resolved = if (cmd.isNotBlank()) cmd else matchXtrace(target)
            val action = when (kind) {
                "写入" -> "写入文件 $target"
                "删除" -> "删除文件 $target"
                "重命名" -> "重命名 $target → $extra"
                "挂载" -> "挂载 $target → $extra"
                else -> "$kind $target"
            }
            items.add(CoreItem(action, resolved))
        }
        val seenCmd = items.map { it.cmd }.filter { it.isNotBlank() }.toHashSet()
        writeIntents.forEach { intent ->
            if (seenCmd.contains(intent)) return@forEach
            items.add(CoreItem("执行系统写操作", intent))
            seenCmd.add(intent)
        }
        return items
    }

    /** 用 xtrace 里同路径的写命令，给 strace 后果回填「是哪条命令写的」。 */
    private fun matchXtrace(path: String): String {
        if (path.isBlank()) return ""
        synchronized(xtraceRaw) {
            for (i in xtraceRaw.indices.reversed()) {
                val l = xtraceRaw[i]
                if (l.contains(path) && (l.contains(">") || l.contains("tee "))) return l
            }
            for (i in xtraceRaw.indices.reversed()) {
                val l = xtraceRaw[i]
                if (l.contains(path)) return l
            }
        }
        return ""
    }

    /**
     * 「未产生系统变更」的命令统计：只给条数与工具聚合，不逐行列出。
     *
     * 这是噪声的**唯一**去处 —— 不删、不占核心名额、也不会把核心挤出上限。
     */
    private fun noEffectSummary(): Pair<Int, String> {
        val coreCmds = HashSet<String>()
        effectCmd.values.forEach { if (it.isNotBlank()) coreCmds.add(it) }
        coreCmds.addAll(writeIntents)
        val counts = LinkedHashMap<String, Int>()
        var total = 0
        execOrder.forEach { cmd ->
            if (coreCmds.contains(cmd)) return@forEach
            total++
            val base = cmd.substringBefore(' ').substringAfterLast('/')
            if (base.isNotBlank()) counts[base] = (counts[base] ?: 0) + 1
        }
        val top = counts.entries.sortedByDescending { it.value }.take(15)
            .joinToString("、") { "${it.key}×${it.value}" }
        return total to top
    }

    /** 生成「影响报告」：核心变更逐条（附命令原文），其余命令只给聚合计数。 */
    private fun effectBlock(withSummary: Boolean): String {
        val items = coreItems()
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("【模块对系统的影响（${items.size} 项，按执行顺序）】")
        items.forEachIndexed { i, it ->
            val cmdText = if (it.cmd.isNotBlank()) "  ← ${it.cmd}" else ""
            sb.appendLine("  ${i + 1}. ${it.action}$cmdText")
        }
        if (withSummary) {
            val (total, top) = noEffectSummary()
            if (total > 0) {
                sb.appendLine()
                sb.appendLine("【其余命令：已执行但未产生系统变更（共 $total 条，明细已省略）】")
                if (top.isNotBlank()) sb.appendLine("  $top")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 【v1.5.7 关键修复 · 「一个模块只能拦截到那么几条固定指令」】
     *
     * 旧实现：`toDisplayBlock()` / `toPromptBlock()` 只要 `effectBlock()` 非空就**整段返回**，
     * 把 [captured]（xtrace 抓到的全部真实执行指令）直接丢弃。
     *
     * 后果（真机可复现）：CGO 这类模块的「预演沙箱写意图」有几十条（`cmd …` / `settings put …` /
     * `su …`），影响清单立刻非空 → 界面上只剩这几类「固定指令」，
     * 而 `echo x > /sys/...`、`find …`、`chmod …` 等真实执行过的命令**一条都不显示**。
     * 用户看到的就是「漏了非常多 echo > 命令，只拦截到那么几条固定的」。
     *
     * 现在：影响清单在前（后果优先，保持原口径），后面**追加**清单之外的真实执行指令。
     * 只改展示，不改采集过滤，也不会把已抓到的指令丢掉。
     */
    private fun executedExtraBlock(items: List<CoreItem>): String {
        val listed = HashSet<String>()
        items.forEach { if (it.cmd.isNotBlank()) listed.add(it.cmd) }
        listed.addAll(writeIntents)
        val extra = captured.toList().filter { it.isNotBlank() && !listed.contains(it) }
        if (extra.isEmpty()) return ""
        val shown = if (extra.size > MAX_EXTRA_EXEC) extra.subList(0, MAX_EXTRA_EXEC) else extra
        val omitted = extra.size - shown.size
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine(
            "【其余真实执行指令（共 ${extra.size} 条" +
                (if (omitted > 0) "，已省略 $omitted 条" else "") + "）】"
        )
        shown.forEachIndexed { i, cmd -> sb.appendLine("  ${i + 1}. $cmd") }
        return sb.toString().trimEnd()
    }

    /**
     * 【v1.5.2 修复】是否使用「混淆 / 加密模块」的展示口径。
     *
     * 判定条件（任一成立）：
     *  1. 过滤强度已自动升级到 [FilterMode.STRICT]；
     *  2. strace（execve）通道里出现了 ≥2 类解密 / 解包 / 编码工具 ——
     *     这是加密壳的硬证据，且**不需要改动 score/mode**，因此不会影响普通模块。
     *
     * 普通模块两条都不成立，[outputCommands] 会原样返回 captured，行为与修复前一致。
     */
    private fun narrowOutput(): Boolean {
        if (encryptedHint) return true
        if (mode == FilterMode.STRICT) return true
        return synchronized(straceCmds) { straceCryptoSeen.size } >= OBF_ESCALATE_AT
    }

    /**
     * 【v1.5.2】本次采集是否判定为「混淆 / 加密模块」。
     *
     * 供调用方在分析阶段结束后留存结论（加密模块的真指令只在分析阶段的 strace 通道里，
     * 真实运行段只有 `sh -x`，拿不到 payload 内部的命令）。
     */
    fun isEncryptedModule(): Boolean = narrowOutput()

    /**
     * 【v1.5.2 修复】输出用的命令流（供界面展示 / 喂 AI / 落盘）。
     *
     * - 普通模块：**原样返回 captured**（逐字节不变）；
     * - 混淆 / 加密模块：captured 只能看到壳最外层几行（payload 在子 shell 里跑），
     *   真正的动作只存在于 strace 的 execve 通道，这里把它按真实顺序补在其后。
     *
     * 注意：本函数只改「展示什么」，**不参与采集过滤**，不会减少任何已抓到的业务指令。
     */
    private fun outputCommands(): List<String> {
        val base = captured.toList()
        if (!narrowOutput()) return base
        // 【v1.5.3 · 混淆/加密模块恢复 v1.4.0 输出口径】
        // v1.4.0 的两个出口只有两种结果：
        //   ① coreItems()（= 预演沙箱拦下的真实写命令 + 后果项）非空 → 只给这些；
        //   ② 一条都没有 → 原样给 captured。
        // 中间没有任何展示层剔除，也不把 strace(execve) 副本追加进来。
        // 之前的实现（v1.4.1 起）在加密模块上额外套了 isStraceDisplayRejected，
        // 把 captured 里仅剩的壳入口一并剔掉，最终两者皆空 →
        // 界面直接显示「本次未捕获到可解析的真实执行指令」，即用户看到的"更离谱"。
        val items = coreItems()
        if (items.isNotEmpty()) {
            return items.map { if (it.cmd.isNotBlank()) it.cmd else "[" + it.action + "]" }
        }
        return base
    }

    /**
     * 【v1.5.3】原「展示层剔除壳脚手架」的判定已删除（isStraceDisplayRejected / 临时目录正则）。
     *
     * 原因：用户要求「混淆/加密模块的拦截结果恢复成 v1.4.0 那样」，而 v1.4.0 的口径是
     * **不做展示层剔除**：
     *   - 有沙箱写意图 / 后果 → 直接给这些真实写命令；
     *   - 一条都没有 → 原样给 captured（哪怕里面是壳的入口）。
     * 继续剔除会让两个出口同时变空，界面只剩「本次未捕获到可解析的真实执行指令」这句提示，
     * 反而比 v1.4.0 更差。采集期过滤（[isXtraceScaffold] / [isReadOnlyProbe] / [isStraceNoise]）
     * 一字未改，因此这里不会再减少任何已抓到的业务指令。
     */

    /**
     * 读取本次采集到的「核心动作」命令文本（供喂 AI / 落盘 / 卸载回滚脚本生成）。
     *
     * 【v1.5.0】优先返回「后果 / 写意图」对应的命令原文；没有任何后果证据时回退旧的
     * captured，保证调用方永远拿到可用内容（不再出现空结果）。
     */
    fun snapshot(): List<String> {
        val items = coreItems()
        // 【v1.5.2】输出统一走 [outputCommands]：普通模块与 captured 逐字节一致；
        // 混淆/加密模块把 strace（execve）通道的真指令补回来（此前被整体丢弃）。
        val cmds = outputCommands()
        // ① 没有任何后果证据：直接给抓到的命令流
        if (items.isEmpty()) return cmds
        // ② 有后果证据：以「模块对系统的影响」为主（与上一版口径一致），
        // 但补上**没有写入后果的业务指令**（settings / pm / am / setprop / axeron-dpm …）——
        // 旧实现「只返回后果项」会把它们整段漏掉，而把 captured 全量倒出来又会带出壳的中间产物。
        val out = ArrayList<String>(items.size + cmds.size)
        val effectCmds = HashSet<String>()
        items.forEach { item ->
            if (item.cmd.isNotBlank()) {
                effectCmds.add(item.cmd)
                if (out.none { it == item.cmd }) out.add(item.cmd)
            } else {
                out.add("[" + item.action + "]")
            }
        }
        cmds.forEach { c ->
            if (c.isNotBlank() && !effectCmds.contains(c) &&
                writeTargets(c).isEmpty() && out.none { it == c }
            ) {
                out.add(c)
            }
        }
        return out
    }

    /**
     * 生成供 AI 阅读的影响报告。
     *
     * 【v1.5.0 后果优先】只要采到「后果 / 写意图」，就输出「实际变更 + 其余命令聚合计数」；
     * 一条后果证据都没有时才回退旧格式，保证不出现空结果。
     */
    fun toPromptBlock(): String {
        val items = coreItems()
        val block = effectBlock(withSummary = true)
        // 【v1.5.7】影响清单之后**追加**其余真实执行指令（不再整段遮蔽 captured）
        if (block.isNotBlank()) return block + executedExtraBlock(items)
        return legacyPromptBlock()
    }

    /** 旧格式（核心指令流 + 环境探测聚合）。仅在「没有任何后果证据」时兜底。 */
    private fun legacyPromptBlock(): String {
        // 【v1.5.2】加密模块在这里补回 strace 通道的真指令（普通模块与 captured 一致）
        val snapshot = outputCommands()
        val probes = probeBlock()
        // 【v1.4.7】核心指令 + 环境探测（聚合）双区块输出：
        // 核心指令在前（AI 优先读），探测在后（同类已聚合，避免噪声淹没核心）。
        if (snapshot.isEmpty() && probes.isEmpty()) {
            return "（本次运行未捕获到真实执行指令）"
        }
        val sb = StringBuilder()
        if (snapshot.isNotEmpty()) {
            sb.appendLine("【运行时真实执行指令流（共 ${snapshot.size} 条）】")
            snapshot.forEachIndexed { i, cmd ->
                sb.appendLine("  ${i + 1}. $cmd")
            }
        } else {
            sb.appendLine("【运行时真实执行指令流（核心动作 0 条，仅捕获到环境探测）】")
        }
        if (probes.isNotEmpty()) {
            sb.appendLine()
            sb.append(probes)
        }
        return sb.toString()
    }

    /**
     * 【v1.4.0 修复④】生成「界面展示用」的指令流文本块：**只含核心指令流**。
     *
     * 背景（用户反馈）：运行 / 启用界面里拦截到的内容「大量无效杂乱」，而「恢复卸载」
     * 界面（读的正是同一次落盘的 [snapshot]）看到的内容却是正确、干净的。
     *
     * 根因不是抓取数据不同（两者同源），而是**展示时把
     * 「环境探测 / 纯文本处理类指令（同类已聚合）」区块也混进了正文** ——
     * 那一块最多 120 类，逐行列出 cat/grep/getprop/ps 之类的探测样本，
     * 在用户眼里就是"一堆和模块功能无关的噪声"，把真正的核心指令淹掉了。
     *
     * 因此把两个出口拆开：
     *  - [toDisplayBlock]：界面展示用，只有核心指令流（与落盘内容一致，用户可逐条核对）；
     *  - [toPromptBlock]：喂 AI 用，核心指令 + 探测区块（保留上下文，信息不丢）。
     *
     * 注意：这里只是「展示分组」，**没有新增任何过滤规则** —— 核心指令流的采集规则
     * 一字未改，避免用户反复强调的"一味收紧过滤导致真指令被误滤"。
     */
    fun toDisplayBlock(): String {
        // 【v1.5.0 后果优先】界面与 AI 用同一份「影响报告」，避免两边说法不一致。
        val items = coreItems()
        val block = effectBlock(withSummary = false)
        // 【v1.5.7】影响清单之后**追加**其余真实执行指令（不再整段遮蔽 captured）
        if (block.isNotBlank()) return block + executedExtraBlock(items)
        return legacyDisplayBlock()
    }

    /** 旧格式（仅核心指令流）。没有任何后果证据时兜底。 */
    private fun legacyDisplayBlock(): String {
        // 【v1.5.2】加密模块在这里补回 strace 通道的真指令（普通模块与 captured 一致）
        val list = outputCommands()
        if (list.isEmpty()) {
            return "（本次运行未捕获到真实执行指令）"
        }
        val sb = StringBuilder()
        sb.appendLine("【运行时真实执行指令流（共 ${list.size} 条）】")
        list.forEachIndexed { i, cmd ->
            sb.appendLine("  ${i + 1}. $cmd")
        }
        return sb.toString()
    }

    /** 是否已捕获到有效真实指令 */
    fun hasTrace(): Boolean =
        captured.isNotEmpty() || effectCmd.isNotEmpty() || writeIntents.isNotEmpty()

    /**
     * 开始一次新的采集（清空历史）。
     *
     * @param forceMode 传 null = 自适应：起始 [FilterMode.LENIENT]（少过滤），
     *   抓到混淆特征自动升级 [FilterMode.STRICT] 并重放已抓到的行；
     *   传 [FilterMode.STRICT] 可强制走重过滤方案（调试用）。
     */
    fun begin(forceMode: FilterMode? = null, encryptedHint: Boolean = false) {
        mode = forceMode ?: FilterMode.LENIENT
        // 【v1.5.2】加密模块标记只由调用方显式传入；默认 false，绝不跨轮次残留。
        this.encryptedHint = encryptedHint
        obfuscationScore = 0
        cryptoSeen.clear()
        scaffoldLines = 0
        scoredLines = 0
        execOrder.clear()
        resetDerived()
        enabled = true
    }

    /** 结束采集 */
    fun end() {
        enabled = false
    }
}
