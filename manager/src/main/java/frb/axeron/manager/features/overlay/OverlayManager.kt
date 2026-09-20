package frb.axeron.manager.features.overlay

import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import java.io.File

/**
 * 模块核心文件 Overlay 管理器。
 *
 * 背景与约束（真机实测结论）：
 *  - 模块目录位于 `/data/user_de/0/com.android.shell/axeron/`，SELinux 域为
 *    `shell_data_file`；App 进程属 `untrusted_app` 域，**即使 0777 也无法访问**。
 *  - 因此本类的所有文件操作都必须以 shell（uid=2000）身份执行，
 *    即经 [AxeronPluginService.execProcessSafeWithTimeout] / `execWithIO` 中转。
 *
 * 本类不负责 UI，也不负责授权弹窗；只提供「读/写/权限判定」的原子能力。
 */
object OverlayManager {

    /** 覆盖层根目录名，位于模块目录下（`<moduleDir>/overlay`）。 */
    const val OVERLAY_DIR_NAME = "overlay"

    /** 单次 shell 调用的默认超时。 */
    private const val TIMEOUT_MS = 5_000L

    /** 覆盖层单文件大小上限（防止模块塞入超大文件拖垮 UI）。 */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    // -----------------------------------------------------------------------
    // 路径解析
    // -----------------------------------------------------------------------

    /** Axeron 工作根目录（按 Root/Shizuku 自适应）。 */
    fun axeronRoot(): String = PathHelper.getWorkingPath(
        Axeron.getAxeronInfo().isRoot(),
        AxeronApiConstant.folder.PARENT
    ).absolutePath

    /** perm 目录。 */
    fun permDir(): String = "${axeronRoot()}/${AxeronApiConstant.folder.PERM}"

    /** 运行时模块根目录（与 RuntimeModuleRegistry.RUNTIME_PLUGIN_FOLDER 一致）。 */
    fun runtimeRoot(): String = "${axeronRoot()}/runtime_plugins"

    /** 某模块的目录。 */
    fun moduleDir(moduleId: String): String = "${runtimeRoot()}/$moduleId"

    /** 某模块的覆盖层目录。 */
    fun overlayDir(moduleId: String): String = "${moduleDir(moduleId)}/$OVERLAY_DIR_NAME"

    // -----------------------------------------------------------------------
    // 底层 shell 执行
    // -----------------------------------------------------------------------

    /** 以 shell 身份执行单行命令，返回 (exitCode, stdout, stderr)。 */
    private suspend fun sh(command: String): Triple<Int, String, String> {
        return try {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", command),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_MS,
            )
            Triple(r.exitCode, r.stdout, r.stderr)
        } catch (e: Throwable) {
            Triple(-1, "", e.toString())
        }
    }

    /** shell 单引号安全包裹。 */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // -----------------------------------------------------------------------
    // 读能力
    // -----------------------------------------------------------------------

    /** 该模块是否对该相对路径有覆盖。 */
    suspend fun has(moduleId: String, relPath: String): Boolean {
        if (!isSafeRel(relPath)) return false
        val p = overlayDir(moduleId) + "/" + relPath
        val (code, out, _) = sh("test -f ${q(p)} && echo YES")
        return code == 0 && out.contains("YES")
    }

    /**
     * 读取覆盖文件内容。
     *
     * 注意：不做大文件保护（调用方应先用 [has] + [sizeOf] 判定），
     * 但会在超过 [MAX_FILE_BYTES] 时返回 null，避免 OOM。
     */
    suspend fun read(moduleId: String, relPath: String): String? {
        if (!isSafeRel(relPath)) return null
        val size = sizeOf(moduleId, relPath) ?: return null
        if (size > MAX_FILE_BYTES) return null
        val p = overlayDir(moduleId) + "/" + relPath
        val (code, out, _) = sh("cat ${q(p)}")
        return if (code == 0) out else null
    }

    /** 覆盖文件字节数；不存在返回 null。 */
    suspend fun sizeOf(moduleId: String, relPath: String): Long? {
        if (!isSafeRel(relPath)) return null
        val p = overlayDir(moduleId) + "/" + relPath
        val (code, out, _) = sh("test -f ${q(p)} && wc -c < ${q(p)}")
        if (code != 0) return null
        return out.trim().toLongOrNull()
    }

    /** 列出该模块覆盖层内所有文件（相对路径）。 */
    suspend fun list(moduleId: String): List<String> {
        val dir = overlayDir(moduleId)
        val (code, out, _) = sh("test -d ${q(dir)} && find ${q(dir)} -type f")
        if (code != 0) return emptyList()
        val prefix = "$dir/"
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
            .toList()
    }

    /** 覆盖层占用（字节）。 */
    suspend fun sizeBytes(moduleId: String): Long {
        val dir = overlayDir(moduleId)
        val (code, out, _) = sh("test -d ${q(dir)} && du -sk ${q(dir)}")
        if (code != 0) return 0L
        val kb = out.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
        return kb * 1024L
    }

    // -----------------------------------------------------------------------
    // 写能力（需授权，权限判定在 OverlayPermissionStore）
    // -----------------------------------------------------------------------

    /**
     * 写入覆盖文件。
     *
     * @return null 表示成功，否则返回错误信息。
     */
    suspend fun write(moduleId: String, relPath: String, content: String): String? {
        if (!isSafeRel(relPath)) return "非法路径"
        val dst = "${overlayDir(moduleId)}/$relPath"
        val dir = dst.substringBeforeLast('/')
        // 用 heredoc 传内容，避免引号/换行转义问题；内容长度不设限（仅受授权层约束）。
        val cmd = buildString {
            append("mkdir -p ")
            append(q(dir))
            append(" && cat > ")
            append(q(dst))
            append(" <<'AXOVERLAY_EOF'\n")
            append(content)
            append("\nAXOVERLAY_EOF\n")
        }
        val (code, _, err) = sh(cmd)
        if (code != 0) return err.trim().ifEmpty { "写入失败 (exit $code)" }
        sh("chmod 644 ${q(dst)}")
        return null
    }

    /** 删除覆盖文件。@return null 成功，否则错误信息。 */
    suspend fun delete(moduleId: String, relPath: String): String? {
        if (!isSafeRel(relPath)) return "非法路径"
        val dst = "${overlayDir(moduleId)}/$relPath"
        val (code, _, err) = sh("rm -f ${q(dst)}")
        if (code != 0) return err.trim().ifEmpty { "删除失败 (exit $code)" }
        // 顺带清理空目录，避免留下空壳
        val dir = dst.substringBeforeLast('/')
        sh("rmdir -p ${q(dir)} 2>/dev/null || true")
        return null
    }

    /** 清空该模块的整个覆盖层。@return null 成功，否则错误信息。 */
    suspend fun clear(moduleId: String): String? {
        val dir = overlayDir(moduleId)
        val (code, _, err) = sh("test -d ${q(dir)} && find ${q(dir)} -mindepth 1 -delete || true")
        if (code != 0) return err.trim().ifEmpty { "清空失败 (exit $code)" }
        return null
    }

    // -----------------------------------------------------------------------
    // 模块枚举
    // -----------------------------------------------------------------------

    /**
     * 列出所有运行时模块 id。
     *
     * 与 [frb.axeron.manager.features.runtime.registry.RuntimeModuleRegistry] 的判定保持一致：
     * 扫描 `runtime_plugins/` 一级子目录，跳过带 `remove` 标记的目录。
     */
    suspend fun installedModuleIds(): List<String> {
        val dir = runtimeRoot()
        val (code, out, _) = sh("ls -1 ${q(dir)} 2>/dev/null")
        if (code != 0) return emptyList()
        // 注意：这里必须在协程内用普通循环逐项判定。
        // Sequence/List 的 filter lambda 不是 suspend 上下文，直接调用 suspend 函数
        // 会报 "Suspension functions can only be called within coroutine body"。
        val result = mutableListOf<String>()
        for (raw in out.lineSequence()) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            val d = "$dir/$id"
            val (c1, _, _) = sh("test -d ${q(d)} && echo Y")
            if (c1 != 0) continue
            // 跳过已标记 remove 的模块（视为已卸载）
            val (c2, o2, _) = sh("test -f ${q("$d/remove")} && echo R")
            if (c2 == 0 && o2.contains("R")) continue
            result.add(id)
        }
        return result.sorted()
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /**
     * 相对路径安全校验：
     *  - 必须非空
     *  - 不得为绝对路径
     *  - 不得含 `..`
     *  - 不得含控制字符
     */
    fun isSafeRel(relPath: String): Boolean {
        if (relPath.isBlank()) return false
        if (relPath.startsWith("/")) return false
        if (relPath.contains("..")) return false
        if (relPath.any { it.code < 0x20 }) return false
        return true
    }

    /** 供 UI 展示的标准路径（仅在 Axeron 激活后有意义）。 */
    fun describe(moduleId: String): String = overlayDir(moduleId)

    /** 兼容旧调用：返回 File 对象（不进行 IO，仅用于展示）。 */
    fun overlayFile(moduleId: String): File = File(overlayDir(moduleId))
}
