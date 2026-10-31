package frb.axeron.manager.ai.dev

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import frb.axeron.api.Axeron

import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.ai.AIConfigStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 模块开发工程的本地存储层。
 *
 * 设计要点（为什么这么存）：
 *  - 工程放在 App 私有目录 `files/module_dev/<模块id>/`：**不需要任何存储权限**，
 *    沙箱内可任意增删文件，也不会被用户误删。
 *  - 运行时模块目录 `/data/user_de/0/com.android.shell/axeron/runtime_plugins/<id>`
 *    对 App 进程不可读（SELinux `shell_data_file` vs `untrusted_app`），因此「安装」
 *    不做直接写入，而是**导出 zip 到系统下载目录**，再由用户通过模块页 `+` 安装
 *    （走既有安装链路，签名/校验/备份都不变）。
 *  - 导出走 MediaStore.Downloads（Android 10+ 无需权限）；Android 9 及以下退回到
 *    公共 Download 目录（App 有 legacy 写权限，失败时给出可读提示）。
 *
 * 该对象只做文件与元数据，不含任何 UI。
 */
object ModuleDevStore {
    /** 单个文本文件可读入/送 AI 的上限，超过视为二进制或过大，不参与编辑。 */
    const val MAX_TEXT_BYTES = 512 * 1024

    /** 上传模块图片的上限，读取选择器内容时也使用同一个上限，避免无界读入内存。 */
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** 通用文件导入上限：16 MiB（整个文件读进内存，过大容易 OOM）。 */
    const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

    /** zip 导入配额：条目数 / 单条目 / 解压总量，防止 zip 炸弹把内存吃光。 */
    private const val MAX_ZIP_ENTRIES = 2000
    private const val MAX_ZIP_ENTRY_BYTES = 16 * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 64L * 1024 * 1024

    /** 不允许导入的压缩包扩展名：模块包必须由「导出」生成，避免塞入来路不明的包。 */
    private val ARCHIVE_EXTENSIONS = setOf(
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "apk", "jar",
    )

    /** AI 上下文中，单个文件正文的最大字符数。 */
    private const val AI_FILE_CHARS = 1500

    /** AI 上下文总字符上限（放进用户消息，官方模型对 prompt 有 6000 字符上限）。 */
    private const val AI_TOTAL_CHARS = 3500

    /** 导出目录名：/storage/emulated/0/AxManagerD（用户指定的固定位置）。 */
    private const val EXPORT_DIR_NAME = "AxManagerD"

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /**
     * 一个模块开发工程。
     *
     * 元信息优先从 `module.prop` 读取（与真实模块一致），缺失时回退到目录名/id。
     */
    data class Project(
        val dir: File,
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val fileCount: Int,
        val updatedAt: Long,
    ) {
        val absolutePath: String get() = dir.absolutePath
    }

    /** 文件树节点（扁平化 + 层级），供 UI 直接渲染。 */
    data class Node(
        val file: File,
        val relPath: String,
        val isDir: Boolean,
        val depth: Int,
    )

    /** 模板类型。 */
    enum class Template(val title: String, val desc: String) {
        SCRIPT("脚本模块", "module.prop + action.sh（点「运行」时执行）"),
        SERVICE("常驻模块", "module.prop + service.sh（后台常驻）+ action.sh + uninstall.sh"),
        OVERLAY("覆盖模块", "module.prop + capabilities=overlay + overlay/ 目录"),
        RUNTIME("运行时模块", "module.prop + runtime.json + onactivate.sh + onstop.sh（+ entry.sh 常驻）"),
        BLANK("空白工程", "只生成 module.prop，其余自己写"),
    }

    /** AI 返回的一个文件写入指令。 */
    data class FileEdit(val path: String, val content: String)

    /**
     * 导出结果。
     *
     * [location] 是用户可以照着去找的路径描述；[dirPath] 是期望目录（/…/AxManagerD）；
     * [needsAllFiles] 为 true 表示没拿到「所有文件访问权限」，zip 被放到了「下载/AxManagerD」
     * —— Android 11 起普通应用不能写手机存储根目录，界面据此弹一次授权引导，
     * 授权后再导出就会直接落到 /AxManagerD。
     */
    data class ExportResult(
        val location: String,
        val fileName: String,
        val needsAllFiles: Boolean = false,
        val dirPath: String = "",
    )

    /**
     * 导入 zip 的结果。
     *
     * [kind] 是按 module.prop 判定的模块类型（运行时模块 / shell 模块），
     * 界面直接显示，用户不必自己分辨。
     */
    data class ImportResult(
        val project: Project,
        val kind: String,
        val fileCount: Int,
        val note: String = "",
    )

    // ------------------------------------------------------------------
    // 「正在开发的模块」标记（对话页据此自动切到该模块的聊天记录）
    // ------------------------------------------------------------------
    private const val KEY_ACTIVE_PROJECT = "module_dev_active_project"

    private val prefs get() = frb.axeron.api.core.AxeronSettings.getPreferences()

    /** 记录当前正在开发的工程；id 传 null 表示清除绑定。 */
    fun setActiveProject(id: String?, name: String? = null) {
        try {
            val e = prefs.edit()
            if (id.isNullOrBlank()) {
                e.remove(KEY_ACTIVE_PROJECT)
            } else {
                e.putString(KEY_ACTIVE_PROJECT, id + "\u0001" + (name ?: ""))
            }
            e.apply()
        } catch (t: Throwable) {
            // 绑定失败不影响主流程
        }
    }

    /** 当前正在开发的工程（id to name）；没有则返回 null。 */
    fun activeProject(): Pair<String, String>? {
        val raw = try {
            prefs.getString(KEY_ACTIVE_PROJECT, null)
        } catch (t: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('\u0001')
        val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        return id to parts.getOrNull(1).orEmpty()
    }

    fun activeProjectId(): String? = activeProject()?.first

    // ------------------------------------------------------------------
    // 根目录
    // ------------------------------------------------------------------

    /** 工程根目录（App 私有目录，无需存储权限）。 */
    fun rootDir(): File {
        val d = File(axeronApp.filesDir, "module_dev")
        if (!d.exists()) d.mkdirs()
        return d
    }

    // ------------------------------------------------------------------
    // 工程列表 / 读取
    // ------------------------------------------------------------------

    /** 列出全部工程（按最近修改倒序）。 */
    fun listProjects(): List<Project> =
        rootDir()
            .listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { toProject(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    /** 读取单个工程（目录不存在返回 null）。 */
    fun loadProject(dir: File): Project? = if (dir.isDirectory) toProject(dir) else null

    private fun toProject(dir: File): Project? {
        if (!dir.isDirectory) return null
        val propFile = File(dir, "module.prop")
        val map = if (propFile.isFile) {
            runCatching { parseProp(propFile.readText()) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return Project(
            dir = dir,
            id = map["id"]?.takeIf { it.isNotBlank() } ?: dir.name,
            name = map["name"]?.takeIf { it.isNotBlank() } ?: dir.name,
            version = map["version"]?.takeIf { it.isNotBlank() } ?: "1.0",
            author = map["author"].orEmpty(),
            description = map["description"].orEmpty(),
            fileCount = countFiles(dir),
            updatedAt = latestModified(dir),
        )
    }

    /** 解析 `key=value` 形式的 module.prop（忽略注释与空行）。 */
    fun parseProp(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return map
    }

    private fun countFiles(dir: File): Int {
        var n = 0
        fun walk(f: File) {
            val children = f.listFiles() ?: return
            children.forEach { c -> if (c.isDirectory) walk(c) else n++ }
        }
        walk(dir)
        return n
    }

    private fun latestModified(dir: File): Long {
        var max = dir.lastModified()
        fun walk(f: File) {
            (f.listFiles() ?: return).forEach { c ->
                if (c.lastModified() > max) max = c.lastModified()
                if (c.isDirectory) walk(c)
            }
        }
        walk(dir)
        return max
    }

    // ------------------------------------------------------------------
    // 新建 / 删除
    // ------------------------------------------------------------------
    /** 规范化模块 id：只保留字母数字与 `. _ -`，并转小写。 */
    fun sanitizeId(raw: String): String {
        val sb = StringBuilder()
        raw.trim().lowercase(Locale.ROOT).forEach { c ->
            if (c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '_' || c == '-') sb.append(c)
        }
        return sb.toString().trim('.', '_', '-')
    }

    /**
     * 模板的默认 id 前缀。
     *
     * 只在「用户没给出可用英文 id」时使用（纯中文名、或清洗后为空）。
     * 保证创建出的是一个可读、可长期使用的目录名。
     */
    private fun defaultIdPrefix(template: Template): String = when (template) {
        Template.SCRIPT -> "script_module"
        Template.SERVICE -> "service_module"
        Template.OVERLAY -> "overlay_module"
        Template.RUNTIME -> "runtime_module"
        Template.BLANK -> "blank_module"
    }

    /**
     * 取一个尚未被占用的工程目录。
     *
     * 语义：工程目录名必须唯一，但模块名可以重复。
     * 冲突时依次尝试 `<baseId>-2`、`<baseId>-3`…，最多 999 次；
     * 极端情况下（上千个同名工程）追加时间戳兜底，绝不返回已存在的目录。
     */
    private fun uniqueProjectDir(baseId: String): File {
        val root = rootDir()
        val first = File(root, baseId)
        if (!first.exists()) return first
        for (n in 2..999) {
            val candidate = File(root, "$baseId-$n")
            if (!candidate.exists()) return candidate
        }
        return File(root, "$baseId-${System.currentTimeMillis()}")
    }

    /**
     * 写入模板脚本。
     *
     * 模板里的 `%MOD_ID%` 占位符会替换成真实模块 id，
     * 这样脚本内的兜底路径不会残留「你的id」这种占位文字。
     */
    private fun writeScript(dir: File, name: String, template: String, id: String): Boolean =
        writeText(File(dir, name), template.replace("%MOD_ID%", id))


    /**
     * 新建工程。
     *
     * 重名规则（v1.4.7 修正）：
     *  - 模块「名字」允许重复，只有「工程目录名」必须唯一；
     *  - 目录已存在时不再直接失败，而是自动派生 `<id>-2`、`<id>-3`…，
     *    避免「同类型/同名模块只能创建一个」；
     *  - 纯中文名清洗后可能为空或只剩数字（「测试模块2」→「2」），
     *    这类名字回落到模板默认前缀，再由唯一化自动编号。
     *
     * @return 成功返回工程；失败返回 error（建目录失败）。
     */
    fun createProject(
        name: String,
        rawId: String,
        version: String,
        author: String,
        description: String,
        template: Template,
    ): Result<Project> {
        val source = rawId.ifBlank { name }
        val cleaned = sanitizeId(source)
        // 中文名里的字全部被 sanitizeId 过滤掉，只剩数字甚至空串；
        // 这种 ID 既难辨认又极易撞车，统一用模板前缀兜底。
        val lostInSanitize = source.any { it.code > 127 } && cleaned.length < 3
        val baseId = if (cleaned.isBlank() || lostInSanitize) {
            defaultIdPrefix(template)
        } else {
            cleaned
        }
        val dir = uniqueProjectDir(baseId)
        if (!dir.mkdirs()) {
            return Result.failure(IOException("创建工程目录失败"))
        }
        // 真实目录名才是模块 id（可能与用户输入不同：同名时自动加了 -2 后缀）。
        val id = dir.name
        val propOk = writeText(
            File(dir, "module.prop"),
            buildProp(id, name, version, author, description, template)
        )
        if (!propOk) {
            dir.deleteRecursively()
            return Result.failure(IOException("写入 module.prop 失败"))
        }
        when (template) {
            Template.SCRIPT -> {
                if (!writeScript(dir, "action.sh", TEMPLATE_ACTION, id)) {
                    dir.deleteRecursively()
                    return Result.failure(IOException("写入 action.sh 失败"))
                }
            }
            Template.SERVICE -> {
                // 常驻模块的完整文件集（缺一个都会影响安装/停止/卸载）：
                //   module.prop    安装器读取（含 axeronPlugin，缺了会被安装器直接拒绝）
                //   action.sh      用户点「运行」的手动入口
                //   service.sh     后台常驻主体（Igniter 用 busybox setsid 拉起）
                //   uninstall.sh   卸载清理（Igniter 删除目录前执行）
                val ok = writeScript(dir, "action.sh", TEMPLATE_ACTION, id) &&
                        writeScript(dir, "service.sh", TEMPLATE_SERVICE, id) &&
                        writeScript(dir, "uninstall.sh", TEMPLATE_UNINSTALL, id)
                if (!ok) {
                    dir.deleteRecursively()
                    return Result.failure(IOException("写入常驻模块脚本失败"))
                }
            }
            Template.OVERLAY -> {
                val ov = File(dir, "overlay")
                ov.mkdirs()
                writeText(File(ov, "README.txt"), TEMPLATE_OVERLAY_README)
            }
            Template.RUNTIME -> {
                // 运行时模块：管理器按 module.prop 里的 `AxmanagerdID=runtime` 识别，
                // 并要求 module.prop / runtime.json / onactivate.sh / onstop.sh 四个文件；
                // runModel=DAEMON/HYBRID 时还要 entry.sh（且必须自己写 entry.pid）。
                val ok = writeScript(dir, "onactivate.sh", TEMPLATE_ON_ACTIVATE, id) &&
                        writeScript(dir, "onstop.sh", TEMPLATE_ON_STOP, id) &&
                        writeScript(dir, "entry.sh", TEMPLATE_RUNTIME_ENTRY, id) &&
                        writeText(File(dir, "runtime.json"), TEMPLATE_RUNTIME_JSON)
                if (!ok) {
                    dir.deleteRecursively()
                    return Result.failure(IOException("写入运行时模块文件失败"))
                }
            }
            Template.BLANK -> Unit
        }
        val project = toProject(dir) ?: return Result.failure(IOException("工程创建后无法读取"))
        return Result.success(project)
    }

    /**
     * 从 zip 导入一个模块工程（模块开发主页的「导入模块」）。
     *
     * 行为：
     *  - 只接受 zip（按文件头判定），限制 16 MiB / 2000 个条目 / 解压后 64 MiB，防 zip 炸弹；
     *  - 拒绝含 `..` 的条目（不会写到工程目录外）；
     *  - zip 里若有一层公共根目录（`xxx/module.prop`），自动剥掉这层；
     *  - 必须有 module.prop，否则判定「不是模块包」；
     *  - 目录名取 module.prop 的 id（非法/冲突时自动派生），不覆盖已有工程。
     *
     * 分类依据与运行时模块校验一致：module.prop 有 `AxmanagerdID=runtime`（或带 runtime.json）
     * → 运行时模块；否则 → shell 模块。
     */
    fun importModuleZip(sourceName: String?, bytes: ByteArray): Result<ImportResult> {
        if (bytes.isEmpty()) return Result.failure(IllegalArgumentException("文件内容为空"))
        if (bytes.size > MAX_IMPORT_BYTES) {
            return Result.failure(IllegalArgumentException("压缩包太大（上限 16 MiB）"))
        }
        if (!isZipMagic(bytes)) {
            return Result.failure(IllegalArgumentException("不是有效的 zip 文件"))
        }
        // ---- 1. 解压到内存（带配额） ----
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(bytes.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val rawName = entry.name.replace('\\', '/').trimStart('/')
                    if (entry.isDirectory || rawName.isBlank()) continue
                    if (rawName.contains("..")) {
                        return Result.failure(IllegalArgumentException("压缩包里有非法路径：$rawName"))
                    }
                    if (entries.size >= MAX_ZIP_ENTRIES) {
                        return Result.failure(IllegalArgumentException("压缩包条目过多（上限 $MAX_ZIP_ENTRIES）"))
                    }
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (out.size() > MAX_ZIP_ENTRY_BYTES) {
                            return Result.failure(IllegalArgumentException("压缩包内单个文件过大：$rawName"))
                        }
                        if (total > MAX_ZIP_TOTAL_BYTES) {
                            return Result.failure(IllegalArgumentException("解压后体积过大（上限 64 MiB）"))
                        }
                    }
                    entries[rawName] = out.toByteArray()
                    zis.closeEntry()
                }
            }
        } catch (t: Throwable) {
            return Result.failure(IOException("解压失败：${t.message ?: t.javaClass.simpleName}"))
        }
        if (entries.isEmpty()) return Result.failure(IllegalArgumentException("压缩包里没有文件"))
        // ---- 2. 找 module.prop，剥掉公共根目录 ----
        val propPath = entries.keys.firstOrNull { it.equals("module.prop", true) }
            ?: entries.keys.firstOrNull { it.endsWith("/module.prop", true) }
            ?: return Result.failure(
                IllegalArgumentException("不是模块包：压缩包里没有 module.prop")
            )
        val prefix = propPath.substringBeforeLast('/', "")
        val stripped = LinkedHashMap<String, ByteArray>()
        if (prefix.isBlank()) {
            stripped.putAll(entries)
        } else {
            entries.forEach { (k, v) ->
                if (k.startsWith("$prefix/")) stripped[k.removePrefix("$prefix/")] = v
            }
        }
        val propBytes = stripped["module.prop"]
            ?: stripped.entries.firstOrNull { it.key.equals("module.prop", true) }?.value
            ?: return Result.failure(IllegalArgumentException("module.prop 读取失败"))
        val prop = parseProp(runCatching { String(propBytes, Charsets.UTF_8) }.getOrDefault(""))
        if (prop.isEmpty()) {
            return Result.failure(IllegalArgumentException("module.prop 内容为空或格式不对"))
        }
        // ---- 3. 建工程目录并落盘 ----
        val baseId = sanitizeId(prop["id"].orEmpty()).ifBlank {
            sanitizeId(sourceName?.substringBeforeLast('.') ?: "").ifBlank { "imported_module" }
        }
        val dir = uniqueProjectDir(baseId)
        if (!dir.mkdirs()) return Result.failure(IOException("创建工程目录失败"))
        val project = Project(
            dir = dir,
            id = dir.name,
            name = prop["name"].orEmpty(),
            version = prop["version"].orEmpty(),
            author = prop["author"].orEmpty(),
            description = prop["description"].orEmpty(),
            fileCount = 0,
            updatedAt = 0L,
        )
        var written = 0
        try {
            stripped.forEach { (rel, data) ->
                val target = resolve(project, rel)
                if (target != null) {
                    target.parentFile?.mkdirs()
                    target.writeBytes(data)
                    written++
                }
            }
        } catch (t: Throwable) {
            runCatching { dir.deleteRecursively() }
            return Result.failure(IOException("写入失败：${t.message ?: t.javaClass.simpleName}"))
        }
        if (written == 0) {
            runCatching { dir.deleteRecursively() }
            return Result.failure(IOException("没有写入任何文件"))
        }
        val isRuntime = prop["AxmanagerdID"].equals("runtime", true) ||
            stripped.keys.any { it.equals("runtime.json", true) }
        val kind = if (isRuntime) "运行时模块" else "shell 模块"
        val note = if (prop["axeronPlugin"].isNullOrBlank()) {
            "module.prop 缺 axeronPlugin 字段，安装前需补 axeronPlugin=12"
        } else {
            ""
        }
        val loaded = toProject(dir) ?: return Result.failure(IOException("工程导入后无法读取"))
        return Result.success(ImportResult(loaded, kind, written, note))
    }

    /** 删除整个工程。 */
    fun deleteProject(project: Project): Boolean = runCatching {
        project.dir.deleteRecursively()
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 文件树 / 编辑
    // ------------------------------------------------------------------

    /** 列出工程内全部节点（目录优先、按名称升序），带层级深度。 */
    fun listNodes(project: Project): List<Node> {
        val out = ArrayList<Node>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
            ) ?: return
            children.forEach { c ->
                val rel = c.relativeTo(project.dir).path.replace('\\', '/')
                out.add(Node(c, rel, c.isDirectory, depth))
                if (c.isDirectory) walk(c, depth + 1)
            }
        }
        walk(project.dir, 0)
        return out
    }

    /** 读取文本文件内容；非文件 / 二进制 / 超大返回 null。 */
    fun readText(file: File): String? {
        if (!file.isFile) return null
        if (file.length() > MAX_TEXT_BYTES) return null
        return runCatching {
            val text = file.readText()
            // 简单二进制判定：前 512 字符里控制字符过多即视为二进制
            val head = text.take(512)
            val ctrl = head.count { it.code < 9 || (it.code in 14..31) }
            if (ctrl > 8) null else text
        }.getOrNull()
    }

    /** 写文本文件（自动建父目录）。 */
    fun writeText(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text)
        true
    }.getOrDefault(false)

    /** 在工程内新建文件；路径非法返回 null。 */
    fun createFile(project: Project, relPath: String, content: String = ""): File? {
        val f = resolve(project, relPath) ?: return null
        if (f.isDirectory) return null
        return if (writeText(f, content)) f else null
    }

    /**
     * 把系统图片选择器读出的字节保存到当前工程的 `images/`。
     *
     * 只依据文件头识别 png/jpg/gif/bmp/webp，不能靠伪造扩展名写入其它文件；
     * 文件名去掉路径与特殊字符，最终仍经 [resolve] 做工程目录边界校验。
     *
     * @return 可写进 `module.prop` 的 `icon=...` 相对路径；失败返回 null。
     */
    fun importImage(project: Project, sourceName: String?, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        val extension = detectImageExtension(bytes) ?: return null
        val displayName = sourceName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val base = displayName.substringBeforeLast('.').map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') {
                c
            } else {
                '_'
            }
        }.joinToString("").trim('_').take(64).ifBlank { "icon" }
        val relPath = "images/$base.$extension"
        val target = resolve(project, relPath) ?: return null
        if (target.isDirectory) return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            relPath
        }.getOrNull()
    }

    /**
     * 把任意「非压缩包」文件导入当前工程根目录。
     *
     * 用途：模块开发页的「导入文件」按钮 —— 允许把 `.sh`、`.prop`、`.json`、
     * `.html`、图片、字体等散装文件直接放进工程，不必先拷到手机再手动找。
     *
     * 明确拒绝压缩包（zip/rar/7z/tar/gz/apk/jar…）：模块包只能由「导出」生成，
     * 避免把来路不明的包改名塞进工程后再次导出。
     *
     * 同名文件不覆盖：依次派生 `name_2.ext`、`name_3.ext`…（与导出的编号策略一致）。
     *
     * @return 成功返回导入后的相对路径；失败返回 error（message 可直接展示给用户）。
     */
    fun importFile(project: Project, sourceName: String?, bytes: ByteArray): Result<String> {
        if (bytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("文件内容为空"))
        }
        if (bytes.size > MAX_IMPORT_BYTES) {
            return Result.failure(IllegalArgumentException("文件太大（上限 16 MiB）"))
        }
        val raw = sourceName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        val name = sanitizeImportName(raw)
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("文件名不合法"))
        }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in ARCHIVE_EXTENSIONS) {
            return Result.failure(
                IllegalArgumentException("不支持导入压缩包，模块包请用「导出」生成")
            )
        }
        // 有些压缩包/安装包没有扩展名，按文件头再兜底判一次（zip/apk 都是 PK\x03\x04）。
        if (isZipMagic(bytes)) {
            return Result.failure(
                IllegalArgumentException("检测到压缩包内容，模块包请用「导出」生成")
            )
        }
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        val baseName = if (ext.isEmpty()) name else name.substringBeforeLast('.')
        var candidate = name
        var n = 2
        while (resolve(project, candidate)?.exists() == true) {
            if (n > 999) {
                return Result.failure(IllegalStateException("同名文件过多，请先重命名再导入"))
            }
            candidate = "${baseName}_$n$suffix"
            n++
        }
        val target = resolve(project, candidate)
            ?: return Result.failure(IllegalArgumentException("文件名不合法"))
        if (target.isDirectory) {
            return Result.failure(IllegalStateException("同名的目录已存在"))
        }
        return try {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            Result.success(candidate)
        } catch (t: Throwable) {
            Result.failure(IOException("写入失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /** 导入文件名清洗：保留中英文/数字与 `. _ -`，其它字符换 `_`，限长 80。 */
    private fun sanitizeImportName(raw: String): String {
        val sb = StringBuilder()
        raw.forEach { c ->
            val keep = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-'
            sb.append(if (keep) c else '_')
        }
        return sb.toString().trim('.', '_', '-').take(80)
    }

    /** zip/apk 的文件头 `PK\x03\x04`（同时覆盖空包 `PK\x05\x06`、跨卷 `PK\x07\x08`）。 */
    private fun isZipMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) return false
        return when {
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte() -> true
            bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte() -> true
            bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte() -> true
            else -> false
        }
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        fun asciiAt(offset: Int, expected: String): Boolean {
            if (offset < 0 || offset + expected.length > bytes.size) return false
            return expected.indices.all { i -> bytes[offset + i].toInt() == expected[i].code }
        }
        return when {
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
                bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte() -> "png"
            bytes.size >= 3 &&
                bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
                bytes[2] == 0xff.toByte() -> "jpg"
            asciiAt(0, "GIF87a") || asciiAt(0, "GIF89a") -> "gif"
            asciiAt(0, "BM") -> "bmp"
            bytes.size >= 12 && asciiAt(0, "RIFF") && asciiAt(8, "WEBP") -> "webp"
            else -> null
        }
    }

    /** 在工程内新建目录；路径非法返回 null。 */
    fun createDir(project: Project, relPath: String): File? {
        val f = resolve(project, relPath) ?: return null
        if (f.exists() && !f.isDirectory) return null
        return if (f.exists() || f.mkdirs()) f else null
    }

    /** 删除工程内的文件或目录（禁止删工程根）。 */
    fun deletePath(project: Project, relPath: String): Boolean {
        val f = resolve(project, relPath) ?: return false
        if (f.absolutePath == project.dir.absolutePath) return false
        return runCatching { f.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 把相对路径安全地解析为工程内文件。
     *
     * 拒绝：空路径、包含 `..`、解析后落在工程目录之外（防目录穿越）。
     */
    fun resolve(project: Project, relPath: String): File? {
        val clean = relPath.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return null
        return runCatching {
            val f = File(project.dir, clean)
            val root = project.dir.canonicalPath
            val target = f.canonicalPath
            if (target == root || target.startsWith(root + File.separator)) f else null
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // 导出 ZIP
    // ------------------------------------------------------------------

    /**
     * 把工程打包成可安装的模块 zip，并放到系统「下载」目录。
     *
     * zip 内部结构：`module.prop`、`action.sh`… 全部在**根层**（不再套一层目录），
     * 与模块安装器读取 module.prop 的位置一致。
     */
    /** 用户要求的目标目录：/storage/emulated/0/AxManagerD（没有就建）。 */
    fun exportDir(): File = File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME)

    /** 目标目录的绝对路径（界面提示用）。 */
    fun exportDirPath(): String = exportDir().absolutePath

    /** 是否已获得「所有文件访问权限」（Android 11+ 才能在手机存储根目录写文件）。 */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    /**
     * 把工程打包成可安装的模块 zip，优先放到 `/storage/emulated/0/AxManagerD/`。
     *
     * 三级落盘策略（从「用户想要的位置」退到「系统一定允许的位置」）：
     *  1. 直接写 `/storage/emulated/0/AxManagerD/`：Android 11+ 需要「所有文件访问权限」，
     *     授权后就能拿到用户要的确切路径；
     *  2. 写不了 → 走 MediaStore 放到 `下载/AxManagerD/`（Android 10+ 免权限），
     *     并把 [ExportResult.needsAllFiles] 置 true，界面据此引导授权；
     *  3. Android 9 及以下没存储权限 → App 专属外部目录。
     *
     * zip 内部结构：`module.prop`、`action.sh`… 全部在**根层**（不再套一层目录），
     * 与模块安装器读取 module.prop 的位置一致。
     */
    fun exportZip(project: Project): Result<ExportResult> = runCatching {
        // 【1.4.5】文件名用模块名（module.prop 的 name），不再是「id_版本_时间戳」。
        // 同一个模块重复导出时自动加 _2/_3…，不覆盖之前导出的包。
        val baseName = exportBaseName(project)
        val dir = exportDir()
        // ---- 1. 直接写用户指定目录 ----
        val direct: File? = runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录")
            val target = uniqueExportFile(dir, baseName)
            // 先写临时文件再改名：中途失败不会在目录里留下半个 zip
            val tmp = File(dir, target.name + ".part")
            tmp.delete()
            tmp.outputStream().use { writeZip(project.dir, it) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("无法写入目标目录")
            }
            target
        }.getOrNull()
        if (direct != null) {
            return@runCatching ExportResult(
                location = direct.absolutePath,
                fileName = direct.name,
                needsAllFiles = false,
                dirPath = dir.absolutePath,
            )
        }
        // ---- 2. MediaStore（Android 10+ 免权限） ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore 由系统处理重名，这里直接用模块名。
            val fileName = "$baseName.zip"
            val resolver = axeronApp.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR_NAME
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("无法写入下载文件")
                out.use { writeZip(project.dir, it) }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            return@runCatching ExportResult(
                location = "下载/$EXPORT_DIR_NAME/$fileName",
                fileName = fileName,
                needsAllFiles = !hasAllFilesAccess(),
                dirPath = dir.absolutePath,
            )
        }
        // ---- 3. Android 9 及以下：App 专属外部目录 ----
        val fallback = File(
            axeronApp.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: axeronApp.filesDir,
            EXPORT_DIR_NAME,
        )
        if (!fallback.exists() && !fallback.mkdirs()) throw IOException("无法创建导出目录")
        val target = uniqueExportFile(fallback, baseName)
        target.outputStream().use { writeZip(project.dir, it) }
        ExportResult(
            location = target.absolutePath,
            fileName = target.name,
            // 此分支只会到 Android 9 及以下；「所有文件访问权限」是 Android 11+ 的概念，
            // 不能因为落进 App 专属目录就误弹一个本机不适用的授权引导。
            needsAllFiles = false,
            dirPath = dir.absolutePath,
        )
    }

    /**
     * 【1.4.5】导出文件的主名：优先用模块名（用户看得懂），去掉文件系统不允许的字符。
     * 模块名为空时退回模块 id，再空则用 module，保证永远不会生成 ".zip" 这种空名文件。
     */
    private fun exportBaseName(project: Project): String {
        val cleaned = project.name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .filter { it >= ' ' }
            .trim()
            .trim('.')
            .take(60)
            .trim()
        return cleaned.ifBlank { project.id.trim().ifBlank { "module" } }
    }

    /** 同一模块重复导出不覆盖旧包：已存在同名文件时依次尝试 _2、_3…。 */
    private fun uniqueExportFile(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.zip")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$index.zip")
            index++
        }
        return candidate
    }

    /** 把目录内容写成 zip（根层平铺，不含顶层目录名）。 */
    private fun writeZip(root: File, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zos ->
            val files = ArrayList<File>()
            fun walk(f: File) {
                if (f.isDirectory) {
                    (f.listFiles() ?: return).forEach { walk(it) }
                } else {
                    files.add(f)
                }
            }
            walk(root)
            val prefix = root.toURI().toString()
            files.sortedBy { it.absolutePath }.forEach { f ->
                val name = f.toURI().toString().removePrefix(prefix)
                if (name.isBlank()) return@forEach
                val entry = ZipEntry(name)
                entry.time = f.lastModified()
                zos.putNextEntry(entry)
                f.inputStream().use { input -> input.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------
    // AI：上下文与文件写入协议
    // ------------------------------------------------------------------

    /**
     * 为 AI 生成工程上下文：模块规范速查 + 文件树 + 关键文件正文。
     *
     * 关键文件（module.prop / action.sh / service.sh / customize.sh / uninstall.sh /
     * post-fs-data.sh / *.md）优先给全文，其余文本文件按大小补足，总量受
     * [AI_TOTAL_CHARS] 限制。
     */
    fun buildAiContext(project: Project, focusFile: String? = null): String {
        val sb = StringBuilder()
        sb.appendLine("【工程信息】")
        sb.appendLine("模块 id：${project.id}")
        sb.appendLine("模块名称：${project.name}")
        sb.appendLine("版本：${project.version}")
        if (project.author.isNotBlank()) sb.appendLine("作者：${project.author}")
        if (project.description.isNotBlank()) sb.appendLine("描述：${project.description}")
        sb.appendLine()

        val nodes = listNodes(project)
        sb.appendLine("【文件树】")
        nodes.forEach { n ->
            val indent = "  ".repeat(n.depth)
            sb.appendLine("$indent${if (n.isDir) n.relPath + "/" else n.relPath}")
        }
        sb.appendLine()

        val priority = listOf(
            "module.prop", "action.sh", "service.sh", "customize.sh",
            "uninstall.sh", "post-fs-data.sh",
            // 运行时模块关键文件：不列出来的话 AI 看不到它们，改起来只能凭空猜。
            "runtime.json", "onactivate.sh", "onstop.sh", "entry.sh"
        )
        val files = nodes.filter { !it.isDir }
        val chosen = ArrayList<Node>()
        // 1) 优先：当前聚焦文件
        if (!focusFile.isNullOrBlank()) {
            files.firstOrNull { it.relPath == focusFile }?.let { chosen.add(it) }
        }
        // 2) 优先：模块规范文件
        priority.forEach { p ->
            files.firstOrNull { it.relPath == p && chosen.none { c -> c.relPath == p } }
                ?.let { chosen.add(it) }
        }
        // 3) 补足：小文本文件（最多 8 个）
        files.filter { f -> chosen.none { it.relPath == f.relPath } }
            .filter { it.file.length() <= 24 * 1024 }
            .take(8)
            .forEach { chosen.add(it) }

        var budget = AI_TOTAL_CHARS
        chosen.forEach { n ->
            if (budget <= 0) return@forEach
            val text = readText(n.file) ?: return@forEach
            val body = if (text.length > AI_FILE_CHARS) {
                text.take(AI_FILE_CHARS) + "\n…（已截断，共 ${text.length} 字符）"
            } else {
                text
            }
            if (body.length > budget) return@forEach
            budget -= body.length
            sb.appendLine("【文件：${n.relPath}】")
            sb.appendLine(body)
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * 模块开发规范（AI 的 system prompt）。
     *
     * 注意：官方免费模型对 system 只保留 1200 字符（见 AIChatService 的裁剪逻辑），
     * 所以这里必须写得足够短；工程上下文用 [buildAiContext] 拼进用户消息
     * （prompt 上限 6000 字符）。
     */
    fun moduleDevSystemPrompt(attachedImages: Int = 0): String = buildString {
        // 【1.5.0】极简提示词：只讲身份、边界、两类模块的必需文件与写文件协议。
        // 官方免费模型对 system 只保留 1200 字符，所以这里不写任何废话；
        // 工程上下文仍由 [buildAiContext] 拼进用户消息。
        val visionOk = AIConfigStore.visionAvailable()
        val isRoot = runCatching { Axeron.getAxeronInfo().isRoot() }.getOrDefault(false)
        appendLine("你是 axmd 小助手，只负责帮用户制作 AxManagerD 模块。用中文简短回答，不聊其它话题。")
        if (isRoot) {
            appendLine("当前权限：root（uid 0）。可直接读写系统分区，但改前必须备份原值，并在 onstop/uninstall 里还原。")
        } else {
            appendLine("当前权限：shell（uid 2000）。只能读写模块自己的目录；改系统只能用 settings/setprop/cmd 等公开接口，不能直接读写 /data 系统分区。")
        }
        appendLine("1) shell 模块：module.prop（id/name/version/versionCode/author/description/axeronPlugin=12 必填）+ action.sh（点「运行」执行，必须退出）；常驻再加 service.sh（前台循环、trap TERM、单实例）+ uninstall.sh。")
        appendLine("2) 运行时模块：module.prop 再加 AxmanagerdID=runtime，另需 runtime.json + onactivate.sh（快退出）+ onstop.sh（还原改动）；常驻再加 entry.sh，必须自己写 entry.pid、退出时删掉。")
        appendLine("脚本首行 #!/system/bin/sh，不用 bash；必须幂等；改系统前先备份；临时文件只写 /data/local/tmp。")
        appendLine("写文件用下面的格式，一块一个文件，块外可以解释：")
        appendLine("<<<FILE:相对路径>>>")
        appendLine("文件完整内容")
        appendLine("<<<END>>>")
        appendLine("路径相对模块根目录；块内不要出现 <<<END>>>；不需要改文件就直接回答。")
        appendLine("可用工具（写在块外）：<<<MKDIR:目录>>> <<<LS:目录>>> <<<READ:文件>>> <<<DEL:路径>>> <<<RUN:x.sh>>> <<<SEARCH:关键词>>> <<<GH:owner/repo[/路径]>>> <<<FIND:关键词>>>")
        if (visionOk) {
            appendLine("<<<VISION:#序号|要求>>> 让识图模型看附图并返回文字（本次附图 $attachedImages 张）。")
            if (attachedImages <= 0) appendLine("VISION 也可用工程内图片路径。")
        }
    }.trimEnd()

    /**
     * 解析 AI 回复里的文件写入块。
     *
     * 兼容两种情况：`<<<FILE:path>>>…<<<END>>>`（推荐）与行首路径的代码块
     * ` ```path `…` ``` `（部分模型会自作主张改用 markdown）。
     */
    fun parseFileEdits(reply: String): List<FileEdit> {
        val out = ArrayList<FileEdit>()
        val primary = Regex(
            "<<<FILE:\\s*([^>\\r\\n]+?)\\s*>>>\\r?\\n([\\s\\S]*?)<<<END>>>",
            RegexOption.DOT_MATCHES_ALL
        )
        primary.findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = m.groupValues[2]
            if (path.isNotEmpty()) out.add(FileEdit(path, content))
        }
        if (out.isNotEmpty()) return out

        // 兜底：```路径\n内容```
        val fenced = Regex(
            "```(?:[a-zA-Z0-9_+-]*)?\\s*([A-Za-z0-9_./-]+\\.[A-Za-z0-9_]+)\\r?\\n([\\s\\S]*?)```",
            RegexOption.DOT_MATCHES_ALL
        )
        fenced.findAll(reply).forEach { m ->
            val path = m.groupValues[1].trim()
            val content = m.groupValues[2]
            if (path.isNotEmpty()) out.add(FileEdit(path, content))
        }
        return out
    }

    /**
     * 把 AI 给出的文件写进工程。
     *
     * @return 成功写入的文件数 + 失败的路径列表
     */
    fun applyEdits(project: Project, edits: List<FileEdit>): Pair<Int, List<String>> {
        var ok = 0
        val failed = ArrayList<String>()
        edits.forEach { e ->
            val f = resolve(project, e.path)
            if (f == null) {
                failed.add(e.path)
            } else if (writeText(f, e.content)) {
                ok++
            } else {
                failed.add(e.path)
            }
        }
        return ok to failed
    }

    // ------------------------------------------------------------------
    // 模板内容
    // ------------------------------------------------------------------

    private fun buildProp(
        id: String,
        name: String,
        version: String,
        author: String,
        description: String,
        template: Template,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# 模块元信息（安装器读取本文件）")
        sb.appendLine("id=$id")
        sb.appendLine("name=${name.ifBlank { id }}")
        sb.appendLine("version=${version.ifBlank { "1.0" }}")
        sb.appendLine("versionCode=1")
        sb.appendLine("author=${author.ifBlank { "unknown" }}")
        sb.appendLine("description=${description.ifBlank { "由 AxManagerD 模块开发创建" }}")
        // 【安装必需】安装器会读取 axeronPlugin；缺这个字段会直接
        // abort「This module not supporting AxManager Plugin!」（除非用户手工开兼容模式）。
        // 取值 = 最低管理器版本编号：新编码是简单递增整数（当前 1.4.0 = 12）；
        // 旧编码（major*10000+minor*1000+patch，>= 10000）安装器同样放行。
        sb.appendLine("axeronPlugin=$TEMPLATE_MIN_MANAGER_CODE")
        sb.appendLine("# 可选：先用「上传图片」放入 images/，再取消下一行注释并改成实际路径")
        sb.appendLine("# icon=images/icon.png")
        if (template == Template.OVERLAY) {
            sb.appendLine("capabilities=overlay")
            sb.appendLine("priority=100")
        }
        if (template == Template.RUNTIME) {
            // 运行时模块的识别位 + 校验字段。
            // minManagerCode 取 9：既满足当前（12）也兼容较早的 9 版本管理器。
            sb.appendLine("AxmanagerdID=runtime")
            sb.appendLine("minManagerCode=9")
            sb.appendLine("aliveCheckIntervalMs=8000")
        }
        return sb.toString()
    }

    /**
     * 生成模板时写入的最低管理器版本编号（对应 apiVersionCode）。
     *
     * 必须与 `api/manifest.gradle.kts` 的 `apiVersionCode` 同步：
     * 安装器按 `[ "$axeronPlugin" -gt "$AXERONVER" ]` 判断是否拒绝安装。
     */
    private const val TEMPLATE_MIN_MANAGER_CODE = 12

    /**
     * 手动入口脚本（脚本模块 / 常驻模块共用）。
     *
     * 由「运行」按钮执行，等价于 `cd 模块目录 && sh ./action.sh <参数>`；
     * 必须执行完就退出，常驻逻辑放 `service.sh`。
     */
    private val TEMPLATE_ACTION = """
        #!/system/bin/sh
        # 用户点「运行」时执行本脚本；必须执行完就退出（不要在这里写死循环）。
        # 参数由「运行」传入，可能为空；用 "${'$'}@" 透传可保留空格。
        # 自己所在目录的标准写法（普通模块在 plugins/<id>，不是 runtime_plugins）：
        MODDIR="$(cd "$(dirname "${'$'}0")" 2>/dev/null && pwd)"
        [ -n "${'$'}MODDIR" ] || MODDIR="/data/user_de/0/com.android.shell/axeron/plugins/%MOD_ID%"

        echo "[模块] MODDIR=${'$'}MODDIR"
        echo "[模块] 参数：${'$'}*"

        # TODO: 在这里写你的逻辑，例如：
        #  - 修改某个 App 的配置文件
        #  - 调整系统参数（改之前先备份原值）
        #  - 备份/恢复文件
        # 返回码约定：0 成功 / 1 参数错 / 3 权限不足 / 4 其他异常

        echo "[模块] 执行完成"
        exit 0
    """.trimIndent()

    /**
     * 常驻服务主体。
     *
     * 管理器（Igniter）的真实启动方式：
     *   busybox setsid sh -c 'exec busybox sh <service.sh>' >/dev/null 2>&1 &
     *   pid=${'$'}!  → resetprop log.tag.service.<id> ${'$'}pid
     * 停止方式：kill -TERM -${'$'}pid（打整个进程组）。
     * 因此脚本必须：前台循环、不自建后台、捕获 TERM 收尾、单实例幂等、日志自己写文件。
     */
    private val TEMPLATE_SERVICE = """
        #!/system/bin/sh
        # 常驻服务：由管理器「启用」时在后台拉起，停止时会收到 TERM。
        # 必须遵守：
        #   1) 保持前台循环，不要自己加 & / setsid（否则管理器记的 pid 杀不到真正的子进程）；
        #   2) 用 trap 捕获 TERM/INT/HUP，先收尾再退出；
        #   3) 标准输出被丢弃（>/dev/null 2>&1），要看日志就自己写文件；
        #   4) 幂等：已在运行就直接退出，避免启用两次起两份。
        MODDIR="$(cd "$(dirname "${'$'}0")" 2>/dev/null && pwd)"
        [ -n "${'$'}MODDIR" ] || MODDIR="/data/user_de/0/com.android.shell/axeron/plugins/%MOD_ID%"
        PIDFILE="${'$'}MODDIR/.service.pid"
        LOGFILE="${'$'}MODDIR/service.log"

        log() { echo "[$(date '+%m-%d %H:%M:%S')] ${'$'}*" >>"${'$'}LOGFILE" 2>/dev/null; }

        RUN=1
        on_stop() {
            RUN=0
            log "收到停止信号，准备退出"
        }
        trap on_stop TERM INT HUP

        # 单实例：pid 文件里的进程还活着就不重复启动
        if [ -f "${'$'}PIDFILE" ]; then
            old=$(cat "${'$'}PIDFILE" 2>/dev/null)
            if [ -n "${'$'}old" ] && [ -d "/proc/${'$'}old" ]; then
                log "已在运行（pid=${'$'}old），本次不重复启动"
                exit 0
            fi
        fi
        echo ${'$'}${'$'} >"${'$'}PIDFILE"

        # 日志超过 256KB 就轮转一次，避免长期运行把存储写满
        if [ -f "${'$'}LOGFILE" ]; then
            SIZE=$(wc -c <"${'$'}LOGFILE" 2>/dev/null | tr -d ' ')
            [ -n "${'$'}SIZE" ] || SIZE=0
            [ "${'$'}SIZE" -gt 262144 ] && mv -f "${'$'}LOGFILE" "${'$'}LOGFILE.1" 2>/dev/null
        fi
        log "服务启动 pid=${'$'}${'$'}"

        FAIL=0
        while [ "${'$'}RUN" = "1" ]; do
            # ---- TODO: 你的常驻逻辑（一轮只做一件短任务，别长阻塞）----
            # 结果码存到 rc，便于下面统一做失败退避：
            rc=0
            # if [ -f "${'$'}MODDIR/enable_xxx" ]; then
            #     some_command || rc=${'$'}?
            # fi

            # 连续失败时逐步拉长间隔，避免打爆 CPU / 耗电
            if [ "${'$'}rc" -ne 0 ]; then
                FAIL=$((FAIL + 1))
                [ "${'$'}FAIL" -gt 5 ] && FAIL=5
            else
                FAIL=0
            fi
            SLEEP=60
            [ "${'$'}FAIL" -gt 0 ] && SLEEP=$((60 * FAIL))

            # 放后台再 wait：这样即使只收到发给本进程的 TERM（不是整组），
            # wait 也会立即被打断并执行 trap，不必等 sleep 走完。
            sleep "${'$'}SLEEP" &
            wait ${'$'}!
        done

        rm -f "${'$'}PIDFILE"
        log "服务已退出"
        exit 0
    """.trimIndent()

    /**
     * 卸载清理脚本。
     *
     * Igniter 在删除模块目录前执行（若存在），必须幂等：
     * 只清理本模块留下的东西，不碰别人的文件。
     */
    private val TEMPLATE_UNINSTALL = """
        #!/system/bin/sh
        # 卸载前执行：清理本模块留下的痕迹（可能被重复执行，必须幂等）。
        MODDIR="$(cd "$(dirname "${'$'}0")" 2>/dev/null && pwd)"
        [ -n "${'$'}MODDIR" ] || MODDIR="/data/user_de/0/com.android.shell/axeron/plugins/%MOD_ID%"
        PIDFILE="${'$'}MODDIR/.service.pid"

        # 1) 兜底停掉常驻服务（管理器已 kill 进程组，这里防止 pid 文件残留）
        if [ -f "${'$'}PIDFILE" ]; then
            pid=$(cat "${'$'}PIDFILE" 2>/dev/null)
            [ -n "${'$'}pid" ] && kill -TERM "${'$'}pid" 2>/dev/null
            rm -f "${'$'}PIDFILE"
        fi

        # 2) TODO: 还原你改动过的系统设置/属性/文件（只还原自己备份过的）
        # resetprop ro.some.key "" 2>/dev/null
        # rm -f /data/local/tmp/your_tmp_file

        echo "[uninstall] 清理完成"
        exit 0
    """.trimIndent()

    /** 运行时模块：采集/触发声明。 */
    private val TEMPLATE_RUNTIME_JSON = """
        {
          "schemaVersion": 1,
          "runModel": "DAEMON",
          "sensors": [
            { "type": "MEMORY", "intervalMs": 30000 }
          ],
          "triggers": [],
          "permissions": []
        }
    """.trimIndent()

    /** 运行时模块：启用钩子（必须快速退出）。 */
    private val TEMPLATE_ON_ACTIVATE = """
        #!/system/bin/sh
        # 启用/启动前执行；必须快速退出（管理器最多等 10 秒）。
        MODDIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" 2>/dev/null && pwd)"
        [ -n "${'$'}MODDIR" ] || MODDIR="${'$'}AX_MODULE_DIR"
        echo "[onactivate] ${'$'}MODDIR"
        exit 0
    """.trimIndent()

    /** 运行时模块：停用/卸载钩子。 */
    private val TEMPLATE_ON_STOP = """
        #!/system/bin/sh
        # 停用/卸载/退出时执行；模块改过的东西在这里恢复。
        MODDIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" 2>/dev/null && pwd)"
        echo "[onstop] ${'$'}MODDIR"
        exit 0
    """.trimIndent()

    /**
     * 运行时模块：常驻主体。
     *
     * 管理器用 `setsid busybox ash entry.sh` 拉起，再读模块目录里的 entry.pid
     * 做存活探测（kill -0）。**必须自己写 entry.pid**，否则管理器只能回退
     * pgrep，常驻模块容易被误判成「未启动」。
     */
    private val TEMPLATE_RUNTIME_ENTRY = """
        #!/system/bin/sh
        # 常驻主体：前台循环 + trap 收尾；退出时清掉 entry.pid。
        MODDIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" 2>/dev/null && pwd)"
        [ -n "${'$'}MODDIR" ] || MODDIR="${'$'}AX_MODULE_DIR"
        cd "${'$'}MODDIR" || exit 1
        echo ${'$'}${'$'} > entry.pid
        trap 'rm -f entry.pid; exit 0' INT TERM HUP
        while true; do
          # TODO: 在这里写你的常驻逻辑
          sleep 30
        done
    """.trimIndent()

    private val TEMPLATE_OVERLAY_README = """
        把要覆盖到其他软件的文件按「相对路径」放在本目录下，例如：
          overlay/system/etc/xxx.conf

        对应模块内的 overlay/ 目录结构会被映射到系统根目录同名路径。
        注意：
          1) module.prop 里必须有 capabilities=overlay；
          2) 冲突时按 priority 数字大者优先；
          3) 覆盖系统文件有风险，建议先备份原文件。
    """.trimIndent()
}
