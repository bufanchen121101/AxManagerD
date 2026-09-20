package frb.axeron.manager.features.overlay

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.util.OverlayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Overlay 权限存储。
 *
 * 设计要点（来自设计文档 §4）：
 *
 *  **三层控制**
 *   1. 全局总开关（App 设置页）—— 关闭时一票否决，无论模块是否单独授权；
 *   2. 模块授权（授权页 / 申请弹窗）—— 按模块 id 粒度；
 *   3. 免责确认 —— 首次进入授权页必须同意。
 *
 *  **双写问题**
 *   全局开关与免责确认同时写入两处：
 *   - App 私有 SP（UI 读取，快）；
 *   - shell 域 `perm/global.json`（模块侧 `axoverlay check` 判定）。
 *   SP 变更后必须同步落盘 global.json，否则模块侧看不到开关变化。
 *
 *  **存储位置**（shell 域，App 不能直接读写，必须经 shell 中转）：
 *   - `perm/global.json`
 *   - `perm/pending/<moduleId>.json`
 *   - `perm/granted/<moduleId>.json`
 */
object OverlayPermissionStore {

    private const val SP_NAME = "overlay_settings"
    const val KEY_ENABLED = "overlay_enabled"
    const val KEY_DISCLAIMER = "overlay_disclaimer_accepted"

    /** 「仅本次」授权的会话有效期（毫秒）。进程重启后过期。 */
    private const val ONCE_SESSION_MS = 12L * 60L * 60L * 1000L

    private const val TIMEOUT_MS = 5_000L

    private val gson = Gson()

    // -----------------------------------------------------------------------
    // 授权模式
    // -----------------------------------------------------------------------

    enum class GrantMode { ALWAYS, ONCE, DENIED }

    /**
     * 单个模块的授权记录。
     *
     * @param moduleId 模块 id（= runtime_plugins 下的目录名）
     * @param mode 授权模式
     * @param expiresAt 过期时间戳（秒）；0 = 永不过期
     * @param reason 模块申请时给出的理由（可空）
     */
    data class Grant(
        val moduleId: String,
        val mode: GrantMode,
        val expiresAt: Long = 0L,
        val reason: String = "",
    ) {
        /** 当前时刻是否仍然有效（仅看有效期，不含全局开关判定）。 */
        fun isValidNow(): Boolean {
            if (mode == GrantMode.DENIED) return false
            if (mode == GrantMode.ALWAYS) return true
            // ONCE：允许到 expiresAt 之前
            return expiresAt <= 0L || System.currentTimeMillis() / 1000L < expiresAt
        }
    }

    // -----------------------------------------------------------------------
    // SP（App 侧镜像）
    // -----------------------------------------------------------------------

    private fun sp(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 全局总开关（App SP 镜像，默认关闭）。 */
    fun isEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENABLED, false)

    /** 免责声明是否已同意（App SP 镜像，默认未同意）。 */
    fun isDisclaimerAccepted(context: Context): Boolean =
        sp(context).getBoolean(KEY_DISCLAIMER, false)

    /**
     * 设置全局总开关。
     *
     * 同时写 SP 与 shell 域 `perm/global.json`（双写，见类注释）。
     */
    suspend fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // 镜像到 api 层 settings（shell/server 进程读取，供 ensureScripts 判定）
        runCatching { AxeronSettings.setEnableModuleOverlay(enabled) }
            .onFailure { OverlayLog.w("mirror enableModuleOverlay failed: $it") }
        syncGlobalJson(context)
    }

    /**
     * 记录免责声明已同意。
     */
    suspend fun acceptDisclaimer(context: Context) {
        sp(context).edit().putBoolean(KEY_DISCLAIMER, true).apply()
        syncGlobalJson(context)
    }

    /**
     * 将 App 侧 SP 状态同步到 shell 域 `perm/global.json`。
     *
     * 这是模块侧 `axoverlay check` 能否看到开关的关键。失败不抛异常，
     * 只记录日志（Axeron 未激活时属正常情况）。
     */
    suspend fun syncGlobalJson(context: Context) {
        val enabled = isEnabled(context)
        val accepted = isDisclaimerAccepted(context)
        val now = System.currentTimeMillis() / 1000L
        val json = buildString {
            append("{\n")
            append("  \"enabled\": ").append(enabled).append(",\n")
            append("  \"disclaimerAccepted\": ").append(accepted).append(",\n")
            append("  \"disclaimerAcceptedAt\": ").append(if (accepted) now else 0).append("\n")
            append("}\n")
        }
        val dir = OverlayManager.permDir()
        val file = "$dir/global.json"
        // 真机实测：heredoc 在 `sh -c` 下静默失败（写 0 字节），改用 base64 单行写入。
        writeFileViaShell(file, json)
    }

    /**
     * 从 shell 域 `perm/global.json` 反向读取（用于 App 启动时校准 SP）。
     *
     * @return Pair(enabled, disclaimerAccepted)；读不到返回 null
     */
    suspend fun readGlobalJson(context: Context): Pair<Boolean, Boolean>? = withContext(Dispatchers.IO) {
        val file = "${OverlayManager.permDir()}/global.json"
        val text = runCatching {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", "cat '$file' 2>/dev/null"),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_MS,
            )
            if (r.exitCode == 0) r.stdout else null
        }.getOrNull() ?: return@withContext null

        runCatching {
            val obj = gson.fromJson(text, JsonObject::class.java) ?: return@withContext null
            val enabled = obj.get("enabled")?.asBoolean ?: false
            val accepted = obj.get("disclaimerAccepted")?.asBoolean ?: false
            enabled to accepted
        }.getOrNull()
    }

    // -----------------------------------------------------------------------
    // 全局判定
    // -----------------------------------------------------------------------

    /**
     * 完整判定：该模块当前是否有写权限。
     *
     * 判定顺序（与设计文档 §4.7 一致）：
     *   ① 全局开关     关 → 拒绝
     *   ② 免责已同意   否 → 拒绝
     *   ③ 模块已授权   否 → 拒绝
     *   ④ 授权未过期   过期 → 拒绝
     */
    suspend fun isGranted(context: Context, moduleId: String): Boolean {
        if (!isEnabled(context)) return false
        if (!isDisclaimerAccepted(context)) return false
        val g = getGrant(context, moduleId) ?: return false
        return g.isValidNow()
    }

    // -----------------------------------------------------------------------
    // 模块授权读写（shell 域 JSON）
    // -----------------------------------------------------------------------

    /** 读取某模块的授权记录。 */
    suspend fun getGrant(context: Context, moduleId: String): Grant? = withContext(Dispatchers.IO) {
        val file = "${OverlayManager.permDir()}/granted/$moduleId.json"
        val text = runCatching {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", "cat '$file' 2>/dev/null"),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_MS,
            )
            if (r.exitCode == 0 && r.stdout.isNotBlank()) r.stdout else null
        }.getOrNull() ?: return@withContext null
        parseGrant(moduleId, text)
    }

    /**
     * 读取所有已授权模块（用于授权页列表渲染）。
     */
    suspend fun allGrants(context: Context): List<Grant> = withContext(Dispatchers.IO) {
        val dir = "${OverlayManager.permDir()}/granted"
        val names = runCatching {
            val r = AxeronPluginService.execProcessSafeWithTimeout(
                cmd = arrayOf("/system/bin/sh", "-c", "ls -1 '$dir' 2>/dev/null"),
                env = Axeron.getEnvironment(),
                timeoutMs = TIMEOUT_MS,
            )
            if (r.exitCode == 0) r.stdout else ""
        }.getOrDefault("")

        // 注意：mapNotNull 的 lambda 不是 suspend 上下文，不能在里面调用
        // suspend 的 getGrant()。改为先取出 id 列表，再在协程内显式循环。
        val ids = names.lineSequence()
            .map { it.trim() }
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .toList()

        val result = mutableListOf<Grant>()
        for (id in ids) {
            getGrant(context, id)?.let { result.add(it) }
        }
        result
    }

    /** 读取模块的申请记录（pending）。 */
    suspend fun getPending(context: Context, moduleId: String): Pair<String, Long>? =
        withContext(Dispatchers.IO) {
            val file = "${OverlayManager.permDir()}/pending/$moduleId.json"
            val text = runCatching {
                val r = AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "cat '$file' 2>/dev/null"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_MS,
                )
                if (r.exitCode == 0 && r.stdout.isNotBlank()) r.stdout else null
            }.getOrNull() ?: return@withContext null

            runCatching {
                val obj = gson.fromJson(text, JsonObject::class.java) ?: return@withContext null
                val reason = obj.get("reason")?.asString ?: ""
                val at = obj.get("requestedAt")?.asLong ?: 0L
                reason to at
            }.getOrNull()
        }

    /**
     * 写入授权结果。
     *
     * @param mode ALWAYS / ONCE / DENIED
     */
    suspend fun putGrant(
        context: Context,
        moduleId: String,
        mode: GrantMode,
        reason: String = "",
    ): String? = withContext(Dispatchers.IO) {
        if (!isValidModuleId(moduleId)) return@withContext "非法模块 id"

        val dir = "${OverlayManager.permDir()}/granted"
        val file = "$dir/$moduleId.json"
        val now = System.currentTimeMillis() / 1000L

        OverlayLog.i("putGrant 开始: id=$moduleId mode=$mode file=$file")

        if (mode == GrantMode.DENIED) {
            // 拒绝 = 删除授权文件（同时清掉 pending）
            runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf(
                        "/system/bin/sh", "-c",
                        "rm -f '$file' '${OverlayManager.permDir()}/pending/$moduleId.json'"
                    ),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_MS,
                )
            }
            return@withContext null
        }

        val expiresAt = if (mode == GrantMode.ONCE) now + ONCE_SESSION_MS / 1000L else 0L
        val obj = JsonObject().apply {
            addProperty("id", moduleId)
            addProperty("mode", if (mode == GrantMode.ALWAYS) "always" else "once")
            addProperty("grantedAt", now)
            addProperty("expiresAt", expiresAt)
            addProperty("grantedBy", "user")
            addProperty("reason", reason)
            add(
                "caps",
                com.google.gson.JsonArray().apply {
                    add("assets_read")
                    add("assets_write")
                }
            )
        }
        val json = gson.toJson(obj) + "\n"

        // 真机实测：heredoc 在 `sh -c` 下静默失败（写 0 字节），改用 base64 单行写入。
        return@withContext writeFileViaShell(file, json).also { err ->
            if (err == null) {
                // 授权成功后清掉 pending
                runCatching {
                    AxeronPluginService.execProcessSafeWithTimeout(
                        cmd = arrayOf("/system/bin/sh", "-c", "rm -f '${OverlayManager.permDir()}/pending/$moduleId.json'"),
                        env = Axeron.getEnvironment(),
                        timeoutMs = TIMEOUT_MS,
                    )
                }
            }
        }
    }

    /** 撤销授权。 */
    suspend fun revoke(context: Context, moduleId: String): String? =
        putGrant(context, moduleId, GrantMode.DENIED)

    // -----------------------------------------------------------------------
    // 底层写文件（真机必需：禁止 heredoc）
    // -----------------------------------------------------------------------

    /**
     * 把 [content] 写到 shell 域 [path]（自动 mkdir 父目录）。
     *
     * **为什么不用 heredoc**：真机 `/system/bin/sh`(mksh) 在
     * `sh -c "<含真换行的多行字符串>"` 调用下，heredoc 会静默失败
     * （实测 exit=1 且写出 0 字节文件），导致授权状态读不回来。
     *
     * 因此这里改为 **base64 单行管道**：内容先 base64 编码（只含
     * `A-Za-z0-9+/=`，**不含任何需要转义的字符**），再用
     * `printf '%s' '<b64>' | base64 -d > '<path>'` 写入。
     * 已实测在真机 sh -c 下稳定写出正确字节。
     *
     * @return null 表示成功，否则为错误信息
     */
    private suspend fun writeFileViaShell(path: String, content: String): String? =
        withContext(Dispatchers.IO) {
            val dir = path.substringBeforeLast('/')
            val b64 = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            // 单行命令；b64 与 path 都用单引号包裹，因两者均不含单引号，安全。
            val cmd = "mkdir -p '$dir' && printf '%s' '$b64' | base64 -d > '$path'"
            val r = runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", cmd),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_MS,
                )
            }.getOrNull()
            if (r == null || r.exitCode != 0) {
                val msg = r?.stderr?.trim().orEmpty().ifEmpty { "写入失败(exit=${r?.exitCode})" }
                OverlayLog.w("writeFileViaShell 失败: path=$path exit=${r?.exitCode} msg=$msg")
                return@withContext msg
            }
            // 回读校验：确认字节数 > 0（防止再次出现「写了但 0 字节」）
            val verify = runCatching {
                AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("/system/bin/sh", "-c", "wc -c < '$path' 2>/dev/null"),
                    env = Axeron.getEnvironment(),
                    timeoutMs = TIMEOUT_MS,
                )
            }.getOrNull()
            val size = verify?.stdout?.trim()?.toLongOrNull() ?: -1L
            OverlayLog.i("writeFileViaShell 成功: path=$path 字节=$size (期望=${content.toByteArray(Charsets.UTF_8).size})")
            if (size <= 0L) return@withContext "写入校验失败：文件为空"
            null
        }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    private fun parseGrant(moduleId: String, text: String): Grant? = runCatching {
        val obj = gson.fromJson(text, JsonObject::class.java) ?: return null
        val modeRaw = obj.get("mode")?.asString?.lowercase() ?: return null
        val mode = when (modeRaw) {
            "always" -> GrantMode.ALWAYS
            "once" -> GrantMode.ONCE
            else -> GrantMode.DENIED
        }
        Grant(
            moduleId = moduleId,
            mode = mode,
            expiresAt = obj.get("expiresAt")?.asLong ?: 0L,
            reason = obj.get("reason")?.asString ?: "",
        )
    }.getOrNull()

    /**
     * 模块 id 合法性：必须形如 `com.demo.appkeeper` 或普通目录名。
     *
     * 拒绝路径分隔符、`..`、空串，防止越权写到其他目录。
     */
    fun isValidModuleId(id: String): Boolean {
        if (id.isBlank()) return false
        if (id.contains('/') || id.contains('\\')) return false
        if (id.contains("..")) return false
        if (id.startsWith(".")) return false
        if (id.any { it.code < 0x20 }) return false
        return true
    }
}