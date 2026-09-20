package frb.axeron.manager.features.overlay

import android.content.Context
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.util.OverlayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模块授权申请的「待处理队列」。
 *
 * 背景：模块调用 `axoverlay request --reason="..."` 后只会往
 * `perm/pending/<id>.json` 写一个文件 —— 这是纯文件协议，
 * 模块本身没有任何途径「推」给 App。因此 App 侧必须主动轮询。
 *
 * 轮询策略：
 *  - 授权页在前台时按 [POLL_INTERVAL_MS] 轮询；
 *  - 发现新的 pending 记录就回调，由 UI 弹窗；
 *  - 已处理过的 id 记录在内存 [handled] 里，避免同一申请反复弹。
 *
 * 注意：本类不做授权写入，只负责「发现申请」。
 */
object OverlayRequestWatcher {

    /** 前台轮询间隔。 */
    const val POLL_INTERVAL_MS = 3_000L

    private const val TIMEOUT_MS = 5_000L

    /** 本次进程生命周期内已弹过窗的模块 id（避免重复弹）。 */
    private val handled = mutableSetOf<String>()

    /** 一条待处理的申请。 */
    data class Request(
        val moduleId: String,
        val reason: String,
        val requestedAt: Long,
    )

    /** 标记某申请已处理。 */
    @Synchronized
    fun markHandled(moduleId: String) {
        handled.add(moduleId)
    }

    /** 某申请是否已处理过。 */
    @Synchronized
    fun isHandled(moduleId: String): Boolean = moduleId in handled

    /** 清空已处理记录（供测试/重置用）。 */
    @Synchronized
    fun reset() {
        handled.clear()
    }

    /**
     * 扫描 `perm/pending/` 下所有申请记录。
     *
     * @param excludeHandled true 时跳过 [handled] 中已记录的模块
     */
    suspend fun scan(context: Context, excludeHandled: Boolean = true): List<Request> =
        withContext(Dispatchers.IO) {
            val dir = "${OverlayManager.permDir()}/pending"
            val out = runCatching {
                val r = AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "ls -1 '$dir' 2>/dev/null"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_MS,
                )
                OverlayLog.d("scan pending: dir=$dir exit=${r.exitCode} out=${r.stdout.trim().replace("\n", ",")}")
                if (r.exitCode == 0) r.stdout else ""
            }.getOrElse {
                OverlayLog.w("scan pending failed: dir=$dir err=$it")
                ""
            }

            // 先取 id 列表，再逐个读（lambda 不是 suspend 上下文，不能用 map）
            val ids = out.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .filter { OverlayPermissionStore.isValidModuleId(it) }
                .toList()

            val result = mutableListOf<Request>()
            for (id in ids) {
                if (excludeHandled && isHandled(id)) continue
                val p = OverlayPermissionStore.getPending(context, id) ?: continue
                result.add(Request(moduleId = id, reason = p.first, requestedAt = p.second))
            }
            result.sortedByDescending { it.requestedAt }
        }

    /**
     * 取最早的一条待处理申请（弹窗一次只展示一条，避免叠窗）。
     */
    suspend fun peek(context: Context): Request? = scan(context, excludeHandled = true).firstOrNull()

    /**
     * 处理一条申请。
     *
     * @param allow true = 允许，false = 拒绝
     * @param always true = 始终允许（ALWAYS），false = 仅本次（ONCE）
     */
    suspend fun resolve(
        context: Context,
        moduleId: String,
        allow: Boolean,
        always: Boolean,
        reason: String = "",
    ): String? {
        markHandled(moduleId)
        if (!allow) {
            return OverlayPermissionStore.putGrant(
                context, moduleId, OverlayPermissionStore.GrantMode.DENIED
            )
        }
        val mode = if (always) OverlayPermissionStore.GrantMode.ALWAYS
        else OverlayPermissionStore.GrantMode.ONCE
        return OverlayPermissionStore.putGrant(context, moduleId, mode, reason)
    }
}