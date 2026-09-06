package frb.axeron.manager.dhizuku

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import frb.axeron.server.util.Logger

/**
 * Dhizuku 兼容 Daemon 的授权存储（基于 SharedPreferences）。
 *
 * 与官方 Dhizuku 的 Room(AppEntity/AppRepo) 方案不同，这里用 SharedPreferences 保存
 * 授权记录，避免引入 Room 的 schema 版本升级复杂度。
 *
 * 每个授权记录包含：uid、包名、签名摘要（sha256）、是否 allowApi、是否 blocked。
 * 存储键：`dhizuku_auth_{uid}` -> 序列化字符串 `packageName|signature|allowApi|blocked`。
 */
object DhizukuAuthStore {
    private val LOGGER = Logger("DhizukuAuthStore")

    private const val PREFS_NAME = "dhizuku_auth"
    private const val KEY_PREFIX = "dhizuku_auth_"

    data class AuthEntry(
        val uid: Int,
        val packageName: String,
        val signature: String,
        val allowApi: Boolean,
        val blocked: Boolean,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 写入/更新一条授权记录。 */
    fun put(context: Context, entry: AuthEntry) {
        val value = listOf(entry.packageName, entry.signature, entry.allowApi.toString(), entry.blocked.toString())
            .joinToString("|")
        prefs(context).edit().putString(KEY_PREFIX + entry.uid, value).apply()
        LOGGER.i("put: uid=${entry.uid} pkg=${entry.packageName} allow=${entry.allowApi} blocked=${entry.blocked}")
    }

    /** 读取授权记录（无则返回 null）。 */
    fun get(context: Context, uid: Int): AuthEntry? {
        val raw = prefs(context).getString(KEY_PREFIX + uid, null) ?: return null
        val parts = raw.split("|")
        if (parts.size < 4) return null
        return try {
            AuthEntry(
                uid = uid,
                packageName = parts[0],
                signature = parts[1],
                allowApi = parts[2].toBoolean(),
                blocked = parts[3].toBoolean(),
            )
        } catch (e: Exception) {
            LOGGER.w("get parse failed uid=$uid: ${e.message}")
            null
        }
    }

    /** 删除授权记录。 */
    fun remove(context: Context, uid: Int) {
        prefs(context).edit().remove(KEY_PREFIX + uid).apply()
    }

    /** 返回所有授权记录（按 uid 升序）。 */
    fun all(context: Context): List<AuthEntry> {
        val p = prefs(context)
        return p.all
            .filterKeys { it.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, _) ->
                val uid = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
                get(context, uid)
            }
            .sortedBy { it.uid }
    }

    // ---------- 签名工具 ----------

    /**
     * 获取 uid 对应包名。
     */
    fun packageNameForUid(context: Context, uid: Int): String? {
        return context.packageManager.getPackagesForUid(uid)?.firstOrNull()
    }

    /**
     * 获取 uid 对应包的签名摘要（sha256 大写 hex）。
     * 跨版本兼容：>= P 用 signingInfo，否则用 signatures。
     */
    fun signatureForUid(context: Context, uid: Int): String? {
        return try {
            val pkg = packageNameForUid(context, uid) ?: return null
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val pi: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, flags)
            }
            signatureFromPackageInfo(pi)
        } catch (e: Exception) {
            LOGGER.w("signatureForUid failed uid=$uid: ${e.message}")
            null
        }
    }

    private fun signatureFromPackageInfo(pi: PackageInfo): String? {
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pi.signatures?.firstOrNull()?.toByteArray()
        }
        return bytes?.let { sha256Hex(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }
}