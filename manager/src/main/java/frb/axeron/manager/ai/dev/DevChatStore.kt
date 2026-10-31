package frb.axeron.manager.ai.dev

import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「制作模块」AI 对话的本地存档（按工程隔离，工程内可分多个会话）。
 *
 * 为什么按工程隔离：用户切到某个模块，看到的就是「做这个模块时说过的话」，
 * 不同模块的对话互不串台。
 *
 * 为什么还要分会话：原「AI 设置 → 云端对话」页有聊天记录抽屉（新建 / 切换 /
 * 重命名 / 删除），这套能力搬到本页后不能丢 —— 同一个模块可能会分几次做，
 * 每次留一个会话比把所有内容堆成一条线更好翻。
 *
 * 存放位置是 App 私有目录（`files/module_dev_meta/chat/<工程id>/<会话id>.json`），
 * 刻意**不放进工程目录**：
 *  - 工程目录会被导出成模块 zip，聊天记录不该进包；
 *  - 工程目录里的每个文件都会出现在「工程文件」列表里，会干扰编辑。
 */
object DevChatStore {
    /** 一条消息的存档形态（与界面上的 DevMsg 一一对应，避免循环依赖）。 */
    data class Rec(
        val role: String,
        val content: String,
        val applied: Int = 0,
        val failed: Int = 0,
    )

    /** 一个会话：标题 + 最近更新时间 + 消息。 */
    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<Rec>,
    )

    /** 单会话最多保留的消息条数：再长既用不上，也会拖慢读盘与渲染。 */
    private const val MAX_KEEP = 200

    /** 单工程最多保留的会话数：超出后丢掉最久没动过的。 */
    private const val MAX_SESSIONS = 50

    private fun root(): File {
        val d = File(axeronApp.filesDir, "module_dev_meta/chat")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 工程 / 会话 id 直接当目录名/文件名：只留安全字符，避免路径穿越与非法名。 */
    private fun safeName(id: String): String =
        id.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .ifBlank { "unknown" }

    private fun dirOf(projectId: String): File = File(root(), safeName(projectId))

    private fun fileOf(projectId: String, sessionId: String): File =
        File(dirOf(projectId), safeName(sessionId) + ".json")

    /** 新会话 id：时间戳 + 三位随机，够用且不会撞。 */
    fun newId(): String =
        "s" + System.currentTimeMillis().toString(36) + "-" +
            (100 + (Math.random() * 900).toInt()).toString()

    /** 从第一条用户消息自动生成会话标题。 */
    fun autoTitle(text: String): String {
        val one = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val cleaned = one.replace("\n", " ")
        return when {
            cleaned.isBlank() -> "新对话"
            cleaned.length <= 18 -> cleaned
            else -> cleaned.take(18) + "…"
        }
    }

    /** 读某工程的全部会话（最近更新的排在前面）。首次读取时自动迁移旧版单文件存档。 */
    fun loadSessions(projectId: String): List<Session> {
        if (projectId.isBlank()) return emptyList()
        migrateLegacy(projectId)
        val d = dirOf(projectId)
        if (!d.isDirectory) return emptyList()
        return d.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { parseSession(it) }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    private fun parseSession(f: File): Session? = runCatching {
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("messages") ?: JSONArray()
        Session(
            id = o.optString("id", f.nameWithoutExtension),
            title = o.optString("title", "新对话").ifBlank { "新对话" },
            updatedAt = o.optLong("updatedAt", f.lastModified()),
            messages = readArray(arr),
        )
    }.getOrNull()

    private fun readArray(arr: JSONArray): List<Rec> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val content = o.optString("content", "")
        // 空内容（被中断的那一轮）没有存档价值
        if (content.isBlank()) return@mapNotNull null
        Rec(
            role = o.optString("role", "AI"),
            content = content,
            applied = o.optInt("applied", 0),
            failed = o.optInt("failed", 0),
        )
    }

    fun save(projectId: String, sessionId: String, title: String, list: List<Rec>) {
        if (projectId.isBlank() || sessionId.isBlank()) return
        runCatching {
            val arr = JSONArray()
            list.takeLast(MAX_KEEP).forEach { r ->
                arr.put(
                    JSONObject()
                        .put("role", r.role)
                        .put("content", r.content)
                        .put("applied", r.applied)
                        .put("failed", r.failed)
                )
            }
            val obj = JSONObject()
                .put("id", sessionId)
                .put("title", title.ifBlank { "新对话" })
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", arr)
            val d = dirOf(projectId)
            if (!d.exists()) d.mkdirs()
            fileOf(projectId, sessionId).writeText(obj.toString())
            trim(d)
        }
    }

    /** 只改标题，不动消息内容（重命名）。 */
    fun rename(projectId: String, sessionId: String, title: String) {
        if (projectId.isBlank() || sessionId.isBlank()) return
        runCatching {
            val f = fileOf(projectId, sessionId)
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            o.put("title", title.ifBlank { "新对话" })
            o.put("updatedAt", System.currentTimeMillis())
            f.writeText(o.toString())
        }
    }

    fun delete(projectId: String, sessionId: String) {
        if (projectId.isBlank() || sessionId.isBlank()) return
        runCatching { fileOf(projectId, sessionId).delete() }
    }

    fun clearAll(projectId: String) {
        if (projectId.isBlank()) return
        runCatching {
            dirOf(projectId).listFiles()?.forEach { f -> runCatching { f.delete() } }
            // 兼容旧版单文件存档（<工程id>.json）
            File(root(), safeName(projectId) + ".json").delete()
        }
    }

    private fun trim(d: File) {
        val files = d.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        if (files.size <= MAX_SESSIONS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_SESSIONS)
            .forEach { f -> runCatching { f.delete() } }
    }

    /**
     * 旧版本把整个工程存成单文件 `<工程id>.json`（纯消息数组）。
     * 升级后第一次读取时把它搬成一个会话，避免老用户的历史记录凭空消失。
     */
    private fun migrateLegacy(projectId: String) {
        val legacy = File(root(), safeName(projectId) + ".json")
        if (!legacy.isFile) return
        runCatching {
            val text = legacy.readText().trim()
            if (text.startsWith("[")) {
                val recs = readArray(JSONArray(text))
                if (recs.isNotEmpty()) {
                    val firstUser = recs.firstOrNull { it.role == "USER" }?.content.orEmpty()
                    save(projectId, "legacy", autoTitle(firstUser), recs)
                }
            }
            legacy.delete()
        }
    }
}
