package frb.axeron.manager.ai

import android.util.Log
import frb.axeron.api.core.AxeronSettings

/**
 * 对话历史存储（本地 SharedPreferences，JSON 序列化）。
 *
 * 参考 Operit 的会话模型：会话（session）→ 多条消息（user / assistant）。
 * 这里只保留最小必要字段：标题、更新时间、消息列表，避免引入数据库依赖。
 *
 * 约定：
 * - 最多保留 [MAX_SESSIONS] 个会话（超出按更新时间淘汰最旧的）；
 * - 单会话消息最多保留 [MAX_MESSAGES_PER_SESSION] 条（避免 SharedPreferences 过大）；
 * - 读写都在调用方所在的线程完成（调用方负责放到 IO）。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    /** 绑定的「模块开发」工程 id；null = 普通对话（不绑定模块）。 */
    val projectId: String? = null,
    /** 绑定的工程名（只用于界面显示，工程改名后以 store 里的最新名为准）。 */
    val projectName: String? = null,
)

object ChatSessionStore {

    private const val TAG = "ChatSessionStore"
    private const val KEY_SESSIONS = "ai_chat_sessions_v1"
    private const val MAX_SESSIONS = 30
    private const val MAX_MESSAGES_PER_SESSION = 200

    private val prefs get() = AxeronSettings.getPreferences()

    fun newId(): String = "s" + System.currentTimeMillis()

    /** 读取全部会话（按更新时间倒序）。 */
    fun loadAll(): List<ChatSession> {
        val raw = try {
            prefs.getString(KEY_SESSIONS, null)
        } catch (t: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<ChatSession>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val msgs = ArrayList<ChatMessage>()
                val ma = o.optJSONArray("messages")
                if (ma != null) {
                    for (j in 0 until ma.length()) {
                        val m = ma.optJSONObject(j) ?: continue
                        val content = m.optString("content")
                        val role = if (m.optString("role") == "user") ChatRole.USER else ChatRole.AI
                        msgs.add(ChatMessage(role, content))
                    }
                }
                out.add(
                    ChatSession(
                        id = id,
                        title = o.optString("title").ifBlank { "新会话" },
                        updatedAt = o.optLong("updatedAt", 0L),
                        messages = msgs,
                        projectId = o.optString("projectId").takeIf { it.isNotBlank() },
                        projectName = o.optString("projectName").takeIf { it.isNotBlank() },
                    )
                )
            }
            out.sortedByDescending { it.updatedAt }
        } catch (t: Throwable) {
            Log.w(TAG, "loadAll 解析失败", t)
            emptyList()
        }
    }

    /** 覆盖写入全部会话（内部会做条数裁剪）。 */
    fun saveAll(sessions: List<ChatSession>) {
        try {
            val trimmed = sessions.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
            val arr = org.json.JSONArray()
            trimmed.forEach { s ->
                val msgs = if (s.messages.size > MAX_MESSAGES_PER_SESSION) {
                    s.messages.takeLast(MAX_MESSAGES_PER_SESSION)
                } else {
                    s.messages
                }
                val ma = org.json.JSONArray()
                msgs.forEach { m ->
                    ma.put(
                        org.json.JSONObject()
                            .put("role", if (m.role == ChatRole.USER) "user" else "assistant")
                            .put("content", m.content)
                    )
                }
                val jo = org.json.JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", ma)
                // 只有绑定了模块的会话才写这两个键，普通对话保持旧格式（向前兼容）
                if (!s.projectId.isNullOrBlank()) jo.put("projectId", s.projectId)
                if (!s.projectName.isNullOrBlank()) jo.put("projectName", s.projectName)
                arr.put(jo)
            }
            prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "saveAll 失败", t)
        }
    }

    /** 该工程下最近的会话（用于「切到某个模块就自动切到它的聊天记录」）。 */
    fun findByProject(projectId: String): ChatSession? =
        loadAll().firstOrNull { it.projectId == projectId }

    /** 新增或更新一个会话。 */
    fun upsert(session: ChatSession) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == session.id }
        if (idx >= 0) all[idx] = session else all.add(session)
        saveAll(all)
    }

    fun delete(id: String) {
        saveAll(loadAll().filterNot { it.id == id })
    }

    /** 清空全部会话（「对话设置 → 清空所有对话记录」用），返回删除前会话数量。 */
    fun clearAll(): Int {
        val n = loadAll().size
        try {
            prefs.edit().remove(KEY_SESSIONS).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clearAll 失败", t)
            return 0
        }
        return n
    }
}