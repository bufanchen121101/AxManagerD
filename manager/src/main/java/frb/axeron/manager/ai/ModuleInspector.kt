package frb.axeron.manager.ai

import frb.axeron.api.Axeron
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import java.io.File

/**
 * 模块解析工具集（Module Inspector）。
 *
 * 目的：不再把"整段脚本文本"直接甩给 AI（那样又慢又解析不到重点），
 * 而是在安装/运行时由本工具真实地探测模块目录，抽取其「真实执行指令流」，
 * 产出结构化摘要，供 AI 精准回答"这个模块会做什么/优化什么"。
 *
 * 特点：
 * - 纯本地文件探测，不执行任何命令、不联网，速度极快（毫秒级）。
 * - 自动识别多种脚本语言/入口（action.sh / install.sh / customize.sh /
 *   META-INF / system/bin 二进制 / service.sh 等），不局限于 action.sh。
 * - 识别加密/混淆脚本特征并如实标注，避免 AI 瞎猜。
 * - 抽取关键命令（setprop/write/mount/chmod/resetprop/cp/rm/mkswap 等）及参数。
 */
object ModuleInspector {

    /** 单条抽取出的关键指令 */
    data class ExtractedCommand(
        val type: String,      // 命令类型：setprop / write / mount / chmod / ...
        val target: String,    // 目标：属性名 / 文件路径 / 挂载点
        val value: String,     // 值 / 参数
        val line: Int,         // 所在行号（1-based）
    )

    /** 探测结果 */
    data class InspectionResult(
        val pluginDir: String,
        val entryFiles: List<String>,          // 识别到的入口脚本文件（相对路径）
        val language: String,                  // 脚本语言/类型描述
        val encryptedHint: String,             // 加密/混淆提示（空串表示未加密）
        val rawText: String,                   // 拼合后的可读脚本文本（供 AI 精读）
        val commands: List<ExtractedCommand>,  // 抽取出的关键指令
        val fileList: List<String>,            // 模块目录文件清单（前 N 个）
    ) {
        fun toPromptBlock(): String {
            val sb = StringBuilder()
            sb.appendLine("【模块解析结果】")
            sb.appendLine("模块目录：$pluginDir")
            sb.appendLine("识别语言/类型：$language")
            if (encryptedHint.isNotBlank()) {
                sb.appendLine("加密/混淆提示：$encryptedHint")
            }
            sb.appendLine("入口脚本：${if (entryFiles.isEmpty()) "（未识别到脚本入口）" else entryFiles.joinToString(", ")}")
            sb.appendLine("关键执行指令（共 ${commands.size} 条）：")
            commands.forEach { c ->
                sb.appendLine("  L${c.line} [${c.type}] ${c.target} = ${c.value}")
            }
            if (commands.isEmpty()) {
                sb.appendLine("  （未抽取到明确的关键指令，请结合下方脚本内容分析）")
            }
            sb.appendLine("模块文件清单：${if (fileList.isEmpty()) "（空）" else fileList.take(30).joinToString(", ")}")
            if (rawText.isNotBlank()) {
                sb.appendLine("---- 脚本内容（供精读）----")
                sb.append(rawText)
            }
            return sb.toString()
        }
    }

    /** 根据 dirId 解析模块实际目录（root/非 root 两种工作路径） */
    fun resolvePluginDir(dirId: String?): File? {
        if (dirId.isNullOrBlank()) return null
        val root = Axeron.getAxeronInfo().isRoot()
        val parent = PathHelper.getWorkingPath(root, AxeronApiConstant.folder.PARENT_PLUGIN)
        return File(parent, dirId)
    }

    /**
     * 探测一个模块目录，产出结构化摘要。
     *
     * @param dirId 模块目录 ID（pluginDirId）
     */
    fun inspect(dirId: String?): InspectionResult? {
        val dir = resolvePluginDir(dirId) ?: return null
        if (!dir.exists() || !dir.isDirectory) return null
        return inspectDir(dir)
    }

    /** 直接对目录 File 进行探测 */
    fun inspectDir(dir: File): InspectionResult {
        val allFiles = collectFiles(dir).map { it.relativeTo(dir).path }

        // 1. 识别入口脚本（按优先级）
        val entryCandidates = listOf(
            "action.sh",
            "install.sh",
            "customize.sh",
            "service.sh",
            "uninstall.sh",
            "post-fs-data.sh",
            "META-INF/com/google/android/update-binary",
            "META-INF/com/google/android/updater-script",
        )
        val entryFiles = entryCandidates.filter { rel ->
            allFiles.any { it == rel }
        }

        // 2. 读取全部可读脚本内容（限体积，避免超大文件拖慢）
        val scriptTexts = StringBuilder()
        val inspectedFiles = (if (entryFiles.isNotEmpty()) entryFiles else allFiles)
            .filter { isScriptName(it) }
        for (rel in inspectedFiles) {
            val f = File(dir, rel)
            if (!f.isFile || f.length() > 512_000) continue
            val text = runCatching { f.readText() }.getOrNull()
            if (text.isNullOrBlank()) continue
            scriptTexts.appendLine("===== 文件：$rel =====")
            scriptTexts.append(text)
            scriptTexts.appendLine()
        }
        val rawText = scriptTexts.toString()
            .take(60_000) // 截断，避免把超长文本塞给 AI

        // 3. 抽取关键指令
        val commands = extractCommands(dir, inspectedFiles)

        // 4. 语言识别
        val language = detectLanguage(rawText, allFiles)

        // 5. 加密/混淆提示
        val encryptedHint = detectEncryption(dir, allFiles, rawText)

        return InspectionResult(
            pluginDir = dir.absolutePath,
            entryFiles = entryFiles,
            language = language,
            encryptedHint = encryptedHint,
            rawText = rawText,
            commands = commands,
            fileList = allFiles,
        )
    }

    // ---------- 辅助 ----------

    /** 收集目录下所有文件（相对路径），限制数量与深度 */
    private fun collectFiles(dir: File, max: Int = 200): List<File> {
        val out = mutableListOf<File>()
        fun walk(d: File, depth: Int) {
            if (out.size >= max || depth > 6) return
            val children = d.listFiles() ?: return
            children.sortedBy { it.name }.forEach { f ->
                if (out.size >= max) return@forEach
                if (f.isFile) {
                    out.add(f)
                } else if (f.isDirectory) {
                    walk(f, depth + 1)
                }
            }
        }
        walk(dir, 0)
        return out
    }

    /** 判断是否为脚本文件名 */
    private fun isScriptName(rel: String): Boolean {
        val lower = rel.lowercase()
        return lower.endsWith(".sh") ||
            lower.endsWith(".bash") ||
            lower.endsWith(".py") ||
            lower.endsWith(".lua") ||
            lower.endsWith(".js") ||
            lower.endsWith(".php") ||
            lower.endsWith(".conf") ||
            lower.endsWith(".prop") ||
            lower.endsWith("-script") ||
            lower.contains("update-binary") ||
            lower.contains("updater-script") ||
            lower == "action" ||
            lower == "service" ||
            lower == "customize"
    }

    /** 语言/类型识别 */
    private fun detectLanguage(rawText: String, allFiles: List<String>): String {
        val hasPy = allFiles.any { it.lowercase().endsWith(".py") }
        val hasLua = allFiles.any { it.lowercase().endsWith(".lua") }
        val hasJs = allFiles.any { it.lowercase().endsWith(".js") }
        val hasBinary = allFiles.any {
            it.startsWith("system/bin/") || it.startsWith("system/xbin/")
        }
        val hasShell = rawText.contains("#!/") ||
            rawText.contains("echo ") ||
            rawText.contains("setprop") ||
            rawText.contains("mount ") ||
            rawText.contains("sh ")

        val langs = mutableListOf<String>()
        if (hasShell) langs.add("Shell")
        if (hasPy) langs.add("Python")
        if (hasLua) langs.add("Lua")
        if (hasJs) langs.add("JavaScript")
        if (hasBinary) langs.add("附带二进制(bin)")
        if (langs.isEmpty()) langs.add("未知/未识别")
        return langs.joinToString(" + ")
    }

    /** 加密/混淆特征识别 */
    private fun detectEncryption(dir: File, allFiles: List<String>, rawText: String): String {
        val hints = mutableListOf<String>()
        // 有脚本入口但读出来是乱码/二进制
        val hasEntry = allFiles.any {
            it == "action.sh" || it == "install.sh" || it == "customize.sh"
        }
        if (hasEntry) {
            val f = File(dir, "action.sh")
            if (f.isFile) {
                val head = runCatching {
                    val bytes = f.readBytes()
                    if (bytes.isEmpty()) ""
                    else String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8)
                }.getOrNull() ?: ""
                val looksBinary = head.any { it.code < 9 || (it.code in 14..31) } && !head.startsWith("#!")
                if (looksBinary) {
                    hints.add("action.sh 疑似二进制/加密（非明文脚本）")
                } else if (head.contains("base64") || head.contains("openssl") || head.contains("enc")) {
                    hints.add("脚本包含 base64/openssl/enc 等编码或加密调用")
                }
            }
        }
        if (allFiles.any { it.lowercase().endsWith(".enc") || it.lowercase().endsWith(".bin") || it.lowercase().endsWith(".dat") }) {
            hints.add("存在 .enc/.bin/.dat 等疑似加密资源文件")
        }
        return hints.joinToString("；")
    }

    /** 抽取关键指令（基于正则，纯内存，极快） */
    private fun extractCommands(dir: File, scriptFiles: List<String>): List<ExtractedCommand> {
        val out = mutableListOf<ExtractedCommand>()
        for (rel in scriptFiles) {
            val f = File(dir, rel)
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val lines = text.lines()
            lines.forEachIndexed { idx, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val ln = idx + 1

                // setprop / resetprop
                matchSetProp(line, ln, out)

                // write 文件
                matchWrite(line, ln, out)

                // mount
                matchMount(line, ln, out)

                // chmod / chown
                matchChmod(line, ln, out)

                // cp / mv / rm / dd / mkswap
                matchFileOps(line, ln, out)

                // echo 重定向进 sysfs/proc
                matchSysfsEcho(line, ln, out)
            }
            if (out.size >= 120) break
        }
        return out.take(120)
    }

    private fun matchSetProp(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        val re = Regex("""(?:setprop|resetprop)\s+([\w.:$\[\]]+)\s+(.+)""")
        val m = re.find(line) ?: return
        out.add(ExtractedCommand("setprop", m.groupValues[1], m.groupValues[2].trim(), ln))
    }

    private fun matchWrite(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        // 仅捕获明确含路径的 write 命令
        val m = Regex("""write\s+([/\w$\[.:-]+)\s+([\w"'`.$<>/+:-]*)""").find(line) ?: return
        out.add(ExtractedCommand("write", m.groupValues[1], m.groupValues[2].trim(), ln))
    }

    private fun matchMount(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        val m = Regex("""mount\s+([^\s]+)\s+([^\s]+)""").find(line) ?: return
        out.add(ExtractedCommand("mount", m.groupValues[1], m.groupValues[2], ln))
    }

    private fun matchChmod(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        val m = Regex("""(?:chmod|chown)\s+([^\s]+)\s+([^\s]+)""").find(line) ?: return
        out.add(ExtractedCommand(m.groupValues[0].substringBefore(' '), m.groupValues[1], m.groupValues[2], ln))
    }

    private fun matchFileOps(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        val m = Regex("""\b(cp|mv|rm|dd|mkswap|ln|mkdir)\s+([^\s|;&]+)""").find(line) ?: return
        val rest = line.substringAfter(m.value).trim().take(60)
        out.add(ExtractedCommand(m.groupValues[1], m.groupValues[2], rest, ln))
    }

    private fun matchSysfsEcho(line: String, ln: Int, out: MutableList<ExtractedCommand>) {
        // echo x > /sys/...  或  echo x > /proc/...
        val m = Regex("""echo\s+([^>]+)>\s*(/sys/|/proc/)[^\s;|]*""").find(line) ?: return
        out.add(ExtractedCommand("sysfs", m.groupValues[2], m.groupValues[1].trim(), ln))
    }
}