package frb.axeron.manager.ai.dev

import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「制作模块」的 AI 记忆（严格按工程隔离）。
 *
 * 设计取舍：
 *  - **一个模块一份**，不汇总：做 A 模块时的约定（比如"必须用 POSIX sh"）
 *    对 B 模块往往是错的，混在一起只会让 AI 乱套；
 *  - 存 App 私有目录，不进工程 zip；
 *  - 内容就是一行行自然语言，用户能直接改，不做结构化字段 ——
 *    记忆的价值在"人也能看懂"，不需要 schema。
 */
object DevMemoryStore {

    data class Memory(
        val id: String,
        val text: String,
        val updatedAt: Long,
    )

    /** 单个工程最多保留的记忆条数（超出后丢最旧的）。 */
    private const val MAX_KEEP = 40

    private fun dir(): File {
        val d = File(axeronApp.filesDir, "module_dev_meta/memory")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(projectId: String): File = File(dir(), "$projectId.json")

    fun load(projectId: String): List<Memory> {
        if (projectId.isBlank()) return emptyList()
        val f = fileOf(projectId)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "").trim()
                if (text.isEmpty()) return@mapNotNull null
                Memory(
                    id = o.optString("id", ""),
                    text = text,
                    updatedAt = o.optLong("at", 0L),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveAll(projectId: String, list: List<Memory>) {
        if (projectId.isBlank()) return
        runCatching {
            val arr = JSONArray()
            list.takeLast(MAX_KEEP).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("text", m.text)
                        .put("at", m.updatedAt)
                )
            }
            fileOf(projectId).writeText(arr.toString())
        }
    }

    fun add(projectId: String, text: String) {
        val t = text.trim()
        if (projectId.isBlank() || t.isEmpty()) return
        val next = load(projectId) + Memory(
            id = "m" + System.currentTimeMillis(),
            text = t,
            updatedAt = System.currentTimeMillis(),
        )
        saveAll(projectId, next)
    }

    fun update(projectId: String, id: String, text: String) {
        val t = text.trim()
        if (projectId.isBlank() || id.isBlank() || t.isEmpty()) return
        saveAll(
            projectId,
            load(projectId).map {
                if (it.id == id) it.copy(text = t, updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    fun delete(projectId: String, id: String) {
        if (projectId.isBlank() || id.isBlank()) return
        saveAll(projectId, load(projectId).filterNot { it.id == id })
    }

    /** 用 AI 梳理出来的条目整段替换（用户确认后才走到这里）。 */
    fun replaceAll(projectId: String, texts: List<String>) {
        if (projectId.isBlank()) return
        val now = System.currentTimeMillis()
        // 反过来写，保证展示顺序与模型输出顺序一致（saveAll 会取后 N 条）
        val list = texts.filter { it.isNotBlank() }.reversed().mapIndexed { i, t ->
            Memory(id = "m" + (now + i), text = t.trim(), updatedAt = now)
        }.reversed()
        saveAll(projectId, list)
    }
}
