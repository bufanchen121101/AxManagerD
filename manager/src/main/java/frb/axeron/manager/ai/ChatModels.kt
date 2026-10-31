package frb.axeron.manager.ai

/**
 * 一条对话消息的角色。
 *
 * 为什么单独放一个文件：这两个类型原先定义在 `ChatScreen.kt` 里，那个页面已经
 * 下线（对话能力并入「模块 → 制作模块 → AI 辅助」），但历史上保存的会话
 * （[ChatSessionStore]）以及诊断页仍然需要它们，不能跟着页面一起删掉。
 */
enum class ChatRole { USER, AI }

/** 一条对话消息（存档 / 恢复用）。 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
)