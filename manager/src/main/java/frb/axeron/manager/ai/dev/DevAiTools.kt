package frb.axeron.manager.ai.dev

import frb.axeron.manager.ai.AIConfigStore
import frb.axeron.manager.ai.VisionService
import frb.axeron.manager.ai.dev.ModuleDevStore.Project
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 模块开发 AI 的「工具层」（新增文件，不改动任何既有公共方法）。
 *
 * 背景：此前 AI 辅助只有一种能力——把 `<<<FILE:path>>>…<<<END>>>` 整文件写进工程。
 * 由此产生三个实际缺口：
 *   1. 无法创建**空目录**（写文件时才顺带 mkdirs，没有文件就建不了目录）；
 *   2. 无法查看工程内已有文件（上下文是固定预算自动拼的，看不到别的文件）；
 *   3. 无法运行工程内脚本、无法联网查资料。
 *
 * 用**文本工具块**补齐（不用 function calling：官方免费模型那条路会把 system 截到
 * 1200 字符、prompt 截到 6000 字符，塞不下 tools JSON schema）。
 *
 * 协议（块外可正常解释，块内不要出现 `>>>`）：
 * ```
 * <<<MKDIR:overlay/system/etc>>>                 建目录（含父目录）
 * <<<LS:overlay>>>                               列目录（最多 100 项）
 * <<<READ:module.prop>>>                         读工程内文件（最多 8000 字符）
 * <<<DEL:tmp/a.sh>>>                             删文件/目录（需允许写）
 * <<<RUN:action.sh>>>                            运行工程内 .sh（需允许运行）
 * <<<SEARCH:android selinux untrusted_app>>>     联网搜索（最多 5 条）
 * ```
 *
 * 安全边界：
 *   - 所有路径都过 [ModuleDevStore.resolve]，拒绝空路径、`..` 与工程目录之外的落点；
 *   - `DEL` 不允许删工程根目录；
 *   - `RUN` 只允许工程内 `.sh`，工作目录固定为工程根，超时 20s 强杀；
 *   - `SEARCH` 只发 GET，不携带任何本地信息；
 *   - 写 / 删 / 运行默认关闭，由调用方按用户开关传入 allowWrite / allowRun。
 */
object DevAiTools {

    enum class Kind { MKDIR, LS, READ, DEL, RUN, SEARCH, GH, FIND, VISION }

    data class Call(val kind: Kind, val arg: String)

    /** 单条工具结果。 */
    data class Result(val call: Call, val ok: Boolean, val text: String)

    private const val READ_LIMIT = 8000
    private const val LS_LIMIT = 100
    private const val RUN_TIMEOUT_MS = 20_000L
    private const val RUN_OUT_LIMIT = 6000
    private const val SEARCH_LIMIT = 5

    /** 单条回复最多执行的工具数：防止模型一次刷几十条把 UI 卡死。 */
    private const val MAX_CALLS = 8

    /** 通用工具标记（FILE 不在此列，由 ModuleDevStore 自己解析）。 */
    private val TOOL_RE = Regex("<<<([A-Za-z]+):([^>\\r\\n]+?)>>>")

    /** 文件写入块：块内是文件正文，必须整体隔离，绝不能当命令解析。 */
    private val FILE_BLOCK_RE = Regex(
        "<<<FILE:[^>\\r\\n]*>>>[\\s\\S]*?<<<END>>>",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从 AI 回复里抽出所有工具块，**严格保持出现顺序**。
     *
     * 顺序很重要：AI 常先 `<<<READ:a.sh>>>` 再 `<<<FILE:a.sh>>>...`，
     * 若按标签分组抽取（MKDIR 全部在前、READ 在后）就会读到改之前的旧内容。
     */
    fun parse(reply: String): List<Call> {
        if (reply.isBlank() || !reply.contains("<<<")) return emptyList()
        // 先剔除 FILE 块：块内是文件内容，可能恰好写着 <<<RUN:...>>> 之类的示例文本，
        // 把它当命令执行属于「AI 写的文档被当成脚本跑」的严重误动作。
        val scoped = FILE_BLOCK_RE.replace(reply, "\n")
        val out = ArrayList<Call>()
        TOOL_RE.findAll(scoped).forEach { m ->
            val kind = when (m.groupValues[1].uppercase()) {
                "MKDIR" -> Kind.MKDIR
                "LS" -> Kind.LS
                "READ" -> Kind.READ
                "DEL" -> Kind.DEL
                "RUN" -> Kind.RUN
                "SEARCH" -> Kind.SEARCH
                "GH" -> Kind.GH
                "FIND" -> Kind.FIND
                "VISION" -> Kind.VISION
                else -> null
            } ?: return@forEach
            val a = m.groupValues[2].trim()
            if (a.isNotEmpty()) out.add(Call(kind, a))
        }
        return out.take(MAX_CALLS)
    }

    /** 把工具块从正文里抹掉，避免气泡里露出裸标记；FILE 块内部原样保留（那是文件正文）。 */
    /**
     * 去掉回复里的工具标记；FILE 写入块原样保留。
     *
     * [keepToolIndex] 为 true 时不再「直接删掉」工具标记，而是在原位置留下
     * `<<<TOOLIDX:n>>>` 占位（n = 该标记在 [parse] 结果里的序号）。
     * 气泡渲染据此把「工具行」放回它原本出现的位置，而不是一律堆到正文末尾。
     * 默认 false（历史存档、纯文本展示走旧行为），不影响其它调用方。
     */
    fun stripMarkers(reply: String, keepToolIndex: Boolean = false): String {
        val keep = ArrayList<String>()
        val held = FILE_BLOCK_RE.replace(reply) { m ->
            keep.add(m.value)
            "\u0000F" + (keep.size - 1) + "\u0000"
        }
        var toolIdx = 0
        val cleaned = TOOL_RE.replace(held) { m ->
            val tag = m.groupValues[1].uppercase()
            // 【1.5.0】已经写进正文的 `<<<TOOLIDX:n>>>` 占位必须原样保留。
            // 二次 strip（识图回灌 / 按格式重试）时它也会命中 TOOL_RE，
            // 之前会被当成「未知标记」删掉，于是气泡里所有工具行都被挤到正文末尾。
            if (tag == "TOOLIDX") {
                return@replace if (keepToolIndex) m.value else ""
            }
            val isTool = when (tag) {
                "MKDIR", "LS", "READ", "DEL", "RUN", "SEARCH", "GH", "FIND", "VISION" -> true
                else -> false
            }
            if (keepToolIndex && isTool) {
                // 前后各留一个换行：占位符单独成段，正文排版不被粘连
                "\n<<<TOOLIDX:" + (toolIdx++) + ">>>\n"
            } else {
                ""
            }
        }
        return Regex("\u0000F(\\d+)\u0000").replace(cleaned) { m ->
            val i = m.groupValues[1].toIntOrNull() ?: -1
            if (i in keep.indices) keep[i] else ""
        }
    }

    /** 顺序执行全部工具调用（磁盘 / 网络都在 IO 线程）。 */
    suspend fun runAll(
        project: Project,
        calls: List<Call>,
        allowWrite: Boolean,
        allowRun: Boolean,
        /**
         * 【1.4.3】本次用户消息附带的图片（data URL，顺序 = 附图顺序）。
         * VISION 工具优先用它，这样主 AI 可以「把刚才那张图交给识图 AI 再看一遍」。
         */
        visionImages: List<String> = emptyList(),
    ): List<Result> = withContext(Dispatchers.IO) {
        calls.map { c ->
            when (c.kind) {
                Kind.MKDIR -> if (AIConfigStore.toolFileEnabled) mkdir(project, c, allowWrite) else disabled(c, "文件工具")
                Kind.LS -> if (AIConfigStore.toolFileEnabled) list(project, c) else disabled(c, "文件工具")
                Kind.READ -> if (AIConfigStore.toolFileEnabled) read(project, c) else disabled(c, "文件工具")
                Kind.DEL -> if (AIConfigStore.toolFileEnabled) delete(project, c, allowWrite) else disabled(c, "文件工具")
                Kind.RUN -> if (AIConfigStore.toolRunEnabled) run(project, c, allowRun) else disabled(c, "运行脚本")
                Kind.SEARCH -> if (AIConfigStore.toolSearchEnabled) search(c) else disabled(c, "联网搜索")
                Kind.GH -> if (AIConfigStore.toolGithubEnabled) gh(c) else disabled(c, "GitHub 查询")
                Kind.FIND -> if (AIConfigStore.toolFindEnabled) find(c) else disabled(c, "开源检索")
                Kind.VISION -> if (VisionService.isAvailable()) vision(project, c, visionImages) else disabled(c, "识图")
            }
        }
    }

    /** 【1.4.2】工具被用户在设置里关掉时的统一回复。 */
    private fun disabled(c: Call, name: String): Result =
        Result(c, false, "$name 已在「对话设置 → 上下文与世界 → 小工具」中关闭")

    // ------------------------------------------------------------------
    // 各工具实现
    // ------------------------------------------------------------------

    private fun mkdir(project: Project, c: Call, allowWrite: Boolean): Result {
        if (!allowWrite) return Result(c, false, "建目录已被设置关闭（允许写文件为关）")
        val f = ModuleDevStore.resolve(project, c.arg)
            ?: return Result(c, false, "路径非法：${c.arg}")
        if (f.isDirectory) return Result(c, true, "目录已存在：${c.arg}")
        val ok = runCatching { f.mkdirs() || f.isDirectory }.getOrDefault(false)
        return if (ok) Result(c, true, "已创建目录：${c.arg}")
        else Result(c, false, "创建目录失败：${c.arg}")
    }

    private fun list(project: Project, c: Call): Result {
        val base = if (c.arg.isBlank() || c.arg == "." || c.arg == "/") project.dir
        else ModuleDevStore.resolve(project, c.arg) ?: return Result(c, false, "路径非法：${c.arg}")
        if (!base.isDirectory) return Result(c, false, "不是目录：${c.arg}")
        val files = base.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ).orEmpty()
        if (files.isEmpty()) return Result(c, true, "目录为空：${if (c.arg.isBlank()) "." else c.arg}")
        val sb = StringBuilder()
        files.take(LS_LIMIT).forEach { f ->
            sb.append(if (f.isDirectory) "[DIR]  " else "[FILE] ").append(f.name)
            if (f.isFile) sb.append("  (").append(f.length()).append(" B)")
            sb.append('\n')
        }
        if (files.size > LS_LIMIT) sb.append("…（其余 ").append(files.size - LS_LIMIT).append(" 项已省略）")
        return Result(c, true, sb.toString().trimEnd())
    }

    private fun read(project: Project, c: Call): Result {
        val f = ModuleDevStore.resolve(project, c.arg) ?: return Result(c, false, "路径非法：${c.arg}")
        if (!f.isFile) return Result(c, false, "文件不存在：${c.arg}")
        val text = runCatching { f.readText() }.getOrNull()
            ?: return Result(c, false, "读取失败（可能是二进制或权限问题）：${c.arg}")
        val body = if (text.length > READ_LIMIT)
            text.take(READ_LIMIT) + "\n…（已截断，共 ${text.length} 字符）"
        else text
        return Result(c, true, body)
    }

    private fun delete(project: Project, c: Call, allowWrite: Boolean): Result {
        if (!allowWrite) return Result(c, false, "删除已被设置关闭（允许写文件为关）")
        val f = ModuleDevStore.resolve(project, c.arg) ?: return Result(c, false, "路径非法：${c.arg}")
        if (f.canonicalPath == project.dir.canonicalPath) return Result(c, false, "不允许删除工程根目录")
        if (!f.exists()) return Result(c, false, "不存在：${c.arg}")
        val ok = runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false)
        return if (ok) Result(c, true, "已删除：${c.arg}") else Result(c, false, "删除失败：${c.arg}")
    }

    private fun run(project: Project, c: Call, allowRun: Boolean): Result {
        if (!allowRun) return Result(c, false, "运行脚本已被设置关闭（允许运行脚本为关）")
        val f = ModuleDevStore.resolve(project, c.arg) ?: return Result(c, false, "路径非法：${c.arg}")
        if (!f.isFile) return Result(c, false, "脚本不存在：${c.arg}")
        if (!f.name.endsWith(".sh")) return Result(c, false, "只允许运行 .sh 脚本：${c.arg}")
        val proc = runCatching {
            ProcessBuilder("/system/bin/sh", f.absolutePath)
                .directory(project.dir)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return Result(c, false, "启动失败：${c.arg}")

        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { ln ->
                        if (out.length < RUN_OUT_LIMIT) out.append(ln).append('\n')
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = proc.waitFor(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { proc.destroyForcibly() }
            runCatching { reader.join(1000) }
            return Result(
                c, false,
                "超时（${RUN_TIMEOUT_MS / 1000}s）已强制结束：${c.arg}\n" + out.toString().trimEnd()
            )
        }
        runCatching { reader.join(1500) }
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        val body = out.toString().trimEnd()
        val text = buildString {
            append("退出码 ").append(code)
            if (body.isNotEmpty()) append('\n').append(body)
        }
        return Result(c, code == 0, text)
    }

    // ------------------------------------------------------------------
    // 联网搜索（DuckDuckGo HTML 版，无需 Key）
    // ------------------------------------------------------------------

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 一条搜索结果。 */
    private data class Item(val title: String, val snippet: String)

    /**
     * 联网搜索。
     *
     * 数据源顺序：`cn.bing.com` → `duckduckgo.com/html`。
     * 实测 DDG 从国内网络直接连不上（curl code=000），只留它等于「搜索按钮是坏的」，
     * 所以默认走国内可达的 Bing，仅当 Bing 取不到结果时再试 DDG（海外网络/被拦时兜底）。
     */
    private fun search(c: Call): Result {
        val q = c.arg.trim()
        if (q.isEmpty()) return Result(c, false, "搜索词为空")
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val sources = listOf(
            "https://cn.bing.com/search?q=" + enc + "&count=10",
            "https://duckduckgo.com/html/?q=" + enc,
        )
        var reason = ""
        for (u in sources) {
            val html = fetch(u)
            if (html == null) {
                reason = "网络不可用"
                continue
            }
            val items = parseBing(html).ifEmpty { parseDdg(html) }
            if (items.isEmpty()) {
                reason = "没有搜到结果"
                continue
            }
            val sb = StringBuilder()
            items.take(SEARCH_LIMIT).forEachIndexed { i, it ->
                sb.append(i + 1).append(". ").append(it.title).append('\n')
                if (it.snippet.isNotBlank()) sb.append("   ").append(it.snippet).append('\n')
            }
            return Result(c, true, sb.toString().trimEnd())
        }
        return Result(c, false, "搜索失败：$q（" + reason + "）")
    }

    /** GET 一个页面，返回正文；非 2xx 或异常返回 null。 */
    private fun fetch(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            // 桌面 UA：Bing 对移动 UA 会返回精简/重定向页，桌面版结构稳定
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.string()
        }
    }.getOrNull()

    private val h2Re = Regex("<h2[^>]*>([\\s\\S]*?)</h2>")
    private val bingSnipRe = Regex("b_caption[\\s\\S]{0,600}?<p[^>]*>([\\s\\S]*?)</p>")
    private val ddgTitleRe = Regex("class=\"result__a\"[^>]*>([\\s\\S]*?)</a>")
    private val ddgSnipRe = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>")

    /** Bing 结果页：每条结果是一个 `<li class="b_algo">` 块，块内第一个 h2 是标题。 */
    private fun parseBing(html: String): List<Item> {
        if (!html.contains("b_algo")) return emptyList()
        val out = ArrayList<Item>()
        html.split("<li class=\"b_algo\"").drop(1).forEach { block ->
            val t = h2Re.find(block)?.groupValues?.get(1) ?: return@forEach
            val title = clean(t)
            if (title.isEmpty()) return@forEach
            val snip = bingSnipRe.find(block)?.groupValues?.get(1).orEmpty()
            out.add(Item(title, clean(snip)))
        }
        return out
    }

    /** DuckDuckGo HTML 版（兜底）。 */
    private fun parseDdg(html: String): List<Item> {
        val titles = ddgTitleRe.findAll(html).map { clean(it.groupValues[1]) }.toList()
        if (titles.isEmpty()) return emptyList()
        val snips = ddgSnipRe.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return titles.mapIndexed { i, t ->
            Item(t, snips.getOrElse(i) { "" })
        }
    }

    /** 去标签 + 解 HTML 实体（Bing 摘要里常见 `&#0183;` / `&ensp;`）。 */
    private fun clean(s: String): String {
        var t = s.replace(Regex("<[^>]+>"), "")
        t = t.replace("&ensp;", " ").replace("&nbsp;", " ")
            .replace("\u0026quot;", "\"").replace("\u0026#x27;", "'").replace("\u0026#39;", "'")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        t = Regex("&#(\\d+);").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace ""
            if (code in 1..0xFFFF) code.toChar().toString() else ""
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    // ------------------------------------------------------------------
    // GitHub / 开源站点快速检索（公共 API，无需 Key；限流 60 次/小时）
    // ------------------------------------------------------------------
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private data class Http(val code: Int, val body: String?)

    private fun get(url: String, accept: String = "application/vnd.github+json"): Http =
        runCatching {
            val b = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", accept)
            // 可选 PAT：未配置时 GitHub 按 IP 限流 60 次/小时（实测常 429），配置后 5000 次/小时
            val token = AIConfigStore.githubToken
            if (token.isNotBlank()) b.header("Authorization", "Bearer " + token)
            val req = b.get().build()
            http.newCall(req).execute().use { r -> Http(r.code, r.body?.string()) }
        }.getOrDefault(Http(-1, null))

    private fun rateLimited(code: Int): Boolean = code == 403 || code == 429

    /**
     * 访问 GitHub：
     *   `<<<GH:owner/repo>>>`        → 仓库概览 + 根目录
     *   `<<<GH:owner/repo/路径>>>`    → 目录列表，或文件正文（≤8000 字符）
     */
    private fun gh(c: Call): Result {
        // 模型经常直接贴完整 URL，必须先剥掉主机名与 query，
        // 否则 "https:" 会被当成 owner、后面全部 404。
        val raw = c.arg.trim()
            .substringBefore('?')
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .trim('/')
        val parts = raw.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return Result(c, false, "用法：<<<GH:owner/repo>>> 或 <<<GH:owner/repo/路径>>>")
        }
        val owner = parts[0]
        val repo = parts[1]
        val path = if (parts.size > 2) parts.drop(2).joinToString("/") else ""
        val repoUrl = "https://api.github.com/repos/$owner/$repo"
        val r0 = get(repoUrl)
        if (r0.code == 404) return Result(c, false, "仓库不存在：$owner/$repo")
        if (rateLimited(r0.code)) return Result(c, false, "GitHub 未认证限流（60 次/小时），请稍后再试")
        val rj = runCatching { JSONObject(r0.body ?: "") }.getOrNull()
            ?: return Result(c, false, "GitHub 仓库信息读取失败（HTTP ${r0.code}）")
        val branch = rj.optString("default_branch", "main")
        val head = buildString {
            append(owner).append('/').append(repo)
            append("  ★").append(rj.optInt("stargazers_count"))
            append("  默认分支 ").append(branch)
            val desc = rj.optString("description", "")
            if (desc.isNotEmpty()) append('\n').append(desc)
            if (rj.optBoolean("archived")) append("\n（该仓库已归档）")
            append('\n')
        }
        if (path.isEmpty()) {
            val lr = get("https://api.github.com/repos/$owner/$repo/contents/")
            val list = parseContentList(lr.body)
            val listing = if (list.isEmpty()) "（根目录为空或读取失败）" else list.joinToString("\n")
            return Result(c, true, head + "\n" + listing)
        }
        val encPath = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8")
        }
        val cr = get("https://api.github.com/repos/$owner/$repo/contents/$encPath")
        if (cr.code == 404) return Result(c, false, "路径不存在：$owner/$repo/$path")
        if (rateLimited(cr.code)) return Result(c, false, "GitHub 未认证限流（60 次/小时），请稍后再试")
        val body = cr.body ?: return Result(c, false, "网络不可用")
        if (body.trimStart().startsWith("[")) {
            val list = parseContentList(body)
            val listing = if (list.isEmpty()) "（空目录）" else list.joinToString("\n")
            return Result(c, true, head + "\n" + path + " 目录：\n" + listing)
        }
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result(c, false, "内容解析失败：$path")
        val type = o.optString("type", "file")
        val size = o.optLong("size", 0)
        val dl = o.optString("download_url", "")
        if (type != "file" || dl.isEmpty()) return Result(c, false, "不是文件：$path（$type）")
        if (size > 200_000) return Result(c, false, "文件过大（" + size + " B），只读 200KB 以内的文本")
        val fr = get(dl, accept = "*/*")
        val text = fr.body ?: return Result(c, false, "下载失败：$path")
        val out = if (text.length > READ_LIMIT) {
            text.take(READ_LIMIT) + "\n…（已截断，共 " + text.length + " 字符）"
        } else {
            text
        }
        return Result(c, true, head + "\n" + path + "（" + size + " B）：\n" + out)
    }

    private fun parseContentList(json: String?): List<String> {
        if (json == null) return emptyList()
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 40)) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isEmpty()) continue
            out.add(
                (if (o.optString("type") == "dir") "[DIR]  " else "[FILE] ") +
                    name + "  (" + o.optLong("size") + " B)"
            )
        }
        if (arr.length() > 40) out.add("…（其余 " + (arr.length() - 40) + " 项已省略）")
        return out
    }

    /**
     * 开源站点快速检索：GitHub 仓库（按 star 排序）+ Stack Overflow 问答。
     *
     * 两个都是公开 API、无需 Key；GitHub 未认证 60 次/小时，超了会明确提示。
     */
    private fun find(c: Call): Result {
        val q = c.arg.trim()
        if (q.isEmpty()) return Result(c, false, "搜索词为空")
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val sb = StringBuilder()

        val ghRes = get(
            "https://api.github.com/search/repositories?q=" + enc + "&sort=stars&per_page=3"
        )
        val items = runCatching {
            JSONObject(ghRes.body ?: "").optJSONArray("items")
        }.getOrNull()
        if (items != null && items.length() > 0) {
            sb.append("【GitHub 仓库】\n")
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(o.optString("full_name"))
                    .append("  ★").append(o.optInt("stargazers_count"))
                    .append("  ").append(o.optString("html_url")).append('\n')
                val d = o.optString("description", "")
                if (d.isNotEmpty()) sb.append("   ").append(d.take(160)).append('\n')
            }
        } else if (ghRes.code == 403 || ghRes.code == 429) {
            sb.append("【GitHub 仓库】未认证限流（60 次/小时），稍后再试\n")
        }

        val so = get(
            "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance" +
                "&pagesize=3&site=stackoverflow&q=" + enc,
            accept = "*/*"
        )
        val soItems = runCatching {
            JSONObject(so.body ?: "").optJSONArray("items")
        }.getOrNull()
        if (soItems != null && soItems.length() > 0) {
            sb.append("\n【Stack Overflow】\n")
            for (i in 0 until soItems.length()) {
                val o = soItems.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(clean(o.optString("title"))).append('\n')
                sb.append("   ").append(o.optString("link"))
                if (o.optBoolean("is_answered")) sb.append("  （已解决）")
                sb.append('\n')
            }
        }

        if (sb.isEmpty()) return Result(c, false, "没有找到相关资料：$q")
        return Result(c, true, sb.toString().trimEnd())
    }

    /**
     * 【1.4.3】识图工具：主 AI 看不清图片时，把图交给视觉模型（nemotron / 自定义），
     * 再把识别出来的文字回灌给主 AI 继续干活。
     *
     * 三种用法：
     *  - `<<<VISION:要求>>>`            → 识别本次消息附带的图片（最多 3 张）；
     *  - `<<<VISION:#1|要求>>>`         → 只识别第 1 张附图；
     *  - `<<<VISION:assets/a.png|要求>>>` → 识别工程内图片文件（路径必须在工程内）。
     *
     * 没有「要求」时默认按 OCR 处理。识图不可用（官方 nemotron 关且未配自定义 API）时，
     * runAll 根本不会调用到这里；这里再兜一层，防止直接调用绕过检查。
     */
    private suspend fun vision(
        project: Project,
        c: Call,
        attached: List<String>,
    ): Result {
        if (!VisionService.isAvailable()) {
            return Result(
                c, false,
                "识图不可用：官方 nemotron-3.5-super 识图未开启，且自定义识图 API 未配置"
            )
        }
        val raw = c.arg.trim()
        val sep = raw.indexOf('|')
        val head = if (sep >= 0) raw.substring(0, sep).trim() else ""
        val ask = (if (sep >= 0) raw.substring(sep + 1).trim() else raw)
            .ifBlank { VisionService.PROMPT_OCR }
        val urls = ArrayList<String>()
        if (head.isNotEmpty() && !head.startsWith("#")) {
            // 工程内图片路径
            val f = ModuleDevStore.resolve(project, head)
                ?: return Result(c, false, "图片路径非法：$head（只能读模块工程内的文件）")
            if (!f.isFile) return Result(c, false, "工程内没有这个文件：$head")
            urls.add(
                VisionService.fileToDataUrl(f)
                    ?: return Result(c, false, "不是可解码的图片：$head")
            )
        } else if (head.startsWith("#")) {
            // 第 n 张附图（从 1 开始）
            val idx = head.drop(1).toIntOrNull()
            if (idx == null || idx < 1 || idx > attached.size) {
                return Result(
                    c, false,
                    "图片序号无效：$head（本次随消息附带了 ${attached.size} 张图片）"
                )
            }
            urls.add(attached[idx - 1])
        } else {
            urls.addAll(attached.take(3))
        }
        if (urls.isEmpty()) {
            return Result(
                c, false,
                "没有可识别的图片：请先在输入条用「+」附上图片，" +
                    "或用 <<<VISION:工程内图片路径|要求>>> 指定工程内图片"
            )
        }
        val sb = StringBuilder()
        urls.forEachIndexed { i, url ->
            val r = VisionService.recognize(url, ask)
            if (urls.size > 1) sb.append("【图片").append(i + 1).append("】\n")
            if (r.ok) {
                sb.append(r.text.trim())
            } else {
                sb.append("识别失败：").append(r.error ?: "未知错误")
            }
            sb.append("\n\n")
        }
        return Result(c, true, sb.toString().trimEnd().take(6000))
    }
}

