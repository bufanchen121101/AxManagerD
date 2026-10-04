package frb.axeron.manager.features.overlay

import android.content.Context
import android.net.Uri
import frb.axeron.manager.features.runtime.registry.RuntimeModuleCapabilities
import frb.axeron.manager.util.OverlayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/**
 * 安装包能力探测（第三期）。
 *
 * 背景：模块必须在 `module.prop` 里**预先声明**自己要用的能力
 * （见 [RuntimeModuleCapabilities]），App 才能「安装时」就知道该模块是否需要
 * 修改核心文件，从而在**安装流程中弹出授权弹窗**；
 * 未声明的模块一律不弹窗、不授权。
 *
 * 本类只做一件事：从**待安装的 ZIP**（content URI）里取出 `module.prop` 并解析出
 *   - 模块 id（安装后即为目录名，与授权记录的文件名一致）
 *   - 模块名（展示用）
 *   - 能力集合（[RuntimeModuleCapabilities]）
 *
 * 设计约束：
 *  - 只读，绝不修改任何文件；不依赖 Axeron/shell（纯 App 侧 ContentResolver）。
 *  - 任何异常都降级为 null（读取失败 = 视为未声明 → 不弹窗、不授权，安全默认）。
 */
object InstallerModuleProbe {

    /** 探测结果。 */
    data class Probed(
        val id: String,
        val name: String,
        val caps: Set<String>,
    ) {
        /** 是否声明了「修改核心文件」能力。 */
        val declaresOverlay: Boolean get() = RuntimeModuleCapabilities.declaresOverlay(caps)
    }

    /**
     * 读取 ZIP 内的 `module.prop` 并解析。
     *
     * @return 解析结果；ZIP 不可读 / 无 module.prop 时返回 null。
     */
    suspend fun probe(context: Context, uri: Uri): Probed? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        // 兼容 "./module.prop" 这类前缀写法
                        val name = entry.name.trimStart('.', '/', '\\')
                        if (name.equals("module.prop", ignoreCase = true)) {
                            // 单文件体积上限保护：module.prop 只可能是几百字节
                            val text = readLimited(zip, 64 * 1024)
                            return@withContext parse(text)
                        }
                    }
                }
            }
            null
        }.onFailure {
            OverlayLog.w("InstallerModuleProbe 读取失败: uri=$uri err=$it")
        }.getOrNull()
    }

    /**
     * 解析 `module.prop` 文本。
     *
     * 格式与 [frb.axeron.manager.features.runtime.registry.RuntimeModuleDetector.parsePropFile]
     * 保持一致：`key=value` 逐行，忽略空行与 `#` 注释。
     */
    fun parse(text: String): Probed {
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return@forEach
            val i = s.indexOf('=')
            if (i <= 0) return@forEach
            map[s.substring(0, i).trim()] = s.substring(i + 1).trim()
        }
        val id = map["id"].orEmpty().trim()
        val name = map["name"].orEmpty().trim().ifBlank { id }
        return Probed(
            id = id,
            name = name,
            caps = RuntimeModuleCapabilities.fromProp(map),
        )
    }

    /** 从 ZIP 流里读取当前 entry 的内容，最多 [limit] 字节。 */
    private fun readLimited(zip: ZipInputStream, limit: Int): String {
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        var total = 0
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            total += n
            sb.append(String(buf, 0, n, Charsets.UTF_8))
            if (total >= limit) break
        }
        return sb.toString()
    }
}
