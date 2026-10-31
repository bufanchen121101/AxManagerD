package frb.axeron.manager.ai.axkey

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.BuildConfig
import frb.axeron.manager.ai.AIConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 官方 AI「设备序列号密钥」提供者。
 *
 * 连接流程：
 * 1. [DeviceIntegrity] 在本机对比系统序列号与出厂参考值；
 * 2. 只有校验 PASS 才上传 serial + reference + verdict + APK 签名；
 * 3. 服务端复核后下发该设备专属密钥与本月额度；
 * 4. 聊天请求只使用下发密钥，本地不计算、不保存任何服务端根密钥。
 *
 * 篡改或无法完成校验时直接失败，不使用旧日钥或静态密钥兜底。
 */
object AxKeyProvider {
    private const val TAG = "AxKeyProvider"
    private const val PREF_KEY_VALUE = "axkey_value"
    private const val PREF_KEY_EXPIRE = "axkey_expire_at"
    private const val PREF_QUOTA_LIMIT = "device_quota_limit"
    private const val PREF_QUOTA_USED = "device_quota_used"
    private const val PREF_QUOTA_REMAINING = "device_quota_remaining"
    private const val PREF_QUOTA_MONTH = "device_quota_month"
    private const val PREF_QUOTA_SYNC = "device_quota_sync"
    private const val REFRESH_AHEAD_SECONDS = 6 * 3600L
    /* 软件端限流：申请/校验类请求每分钟最多 5 次，超了不发请求、直接提示等待。
       计数跨进程保留，避免重启后立刻重放；同一分钟内第 6 次起被拦。 */
    private const val PREF_RL_WINDOW = "axkey_rate_window"
    private const val PREF_RL_COUNT = "axkey_rate_count"
    private const val RL_MAX_PER_MINUTE = 5
    private const val RL_WINDOW_SECONDS = 60L

    private val prefs get() = AxeronSettings.getPreferences()
    private val mutex = Mutex()

    @Volatile
    var lastError: String? = null
        private set

    data class KeyState(
        val hasKey: Boolean,
        val expireAt: Long,
        val remainSeconds: Long,
    )

    data class QuotaState(
        val available: Boolean,
        val limit: Long,
        val used: Long,
        val remaining: Long,
        val month: Int,
        val syncedAt: Long,
    )

    sealed interface ConnectResult {
        data class Ok(val key: String, val expireAt: Long) : ConnectResult
        data class Fail(val message: String) : ConnectResult
    }

    fun invalidate() {
        try {
            prefs.edit().remove(PREF_KEY_VALUE).remove(PREF_KEY_EXPIRE).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "invalidate 失败", t)
        }
    }

    /** 取一个请求配额：返回还需等待的秒数，0 = 允许本次请求。 */
    private fun acquireRequestSlot(): Long = try {
        val now = System.currentTimeMillis() / 1000
        val start = prefs.getLong(PREF_RL_WINDOW, 0L)
        var count = prefs.getInt(PREF_RL_COUNT, 0)
        if (start <= 0L || now < start || now - start >= RL_WINDOW_SECONDS) {
            prefs.edit().putLong(PREF_RL_WINDOW, now).putInt(PREF_RL_COUNT, 1).apply()
            0L
        } else if (count >= RL_MAX_PER_MINUTE) {
            (start + RL_WINDOW_SECONDS - now).coerceAtLeast(1L)
        } else {
            count += 1
            prefs.edit().putInt(PREF_RL_COUNT, count).apply()
            0L
        }
    } catch (t: Throwable) {
        0L
    }

    private fun throttleMessage(waitSeconds: Long): String =
        "请求太频繁：每分钟最多申请 ${RL_MAX_PER_MINUTE} 次，请 ${waitSeconds} 秒后再试"

    fun keyState(): KeyState {
        val key = try {
            prefs.getString(PREF_KEY_VALUE, null)
        } catch (t: Throwable) {
            null
        }
        val exp = try {
            prefs.getLong(PREF_KEY_EXPIRE, 0L)
        } catch (t: Throwable) {
            0L
        }
        val now = System.currentTimeMillis() / 1000
        val usable = !key.isNullOrBlank() && exp > now
        return KeyState(
            hasKey = usable,
            expireAt = exp,
            remainSeconds = if (usable) exp - now else 0L,
        )
    }

    /** 最近一次由服务端同步的月度额度；无网络请求。 */
    fun quotaState(): QuotaState {
        val limit = prefs.getLong(PREF_QUOTA_LIMIT, 0L)
        return QuotaState(
            available = limit > 0L,
            limit = limit,
            used = prefs.getLong(PREF_QUOTA_USED, 0L),
            remaining = prefs.getLong(PREF_QUOTA_REMAINING, 0L),
            month = prefs.getInt(PREF_QUOTA_MONTH, 0),
            syncedAt = prefs.getLong(PREF_QUOTA_SYNC, 0L),
        )
    }

    suspend fun connect(forceRefresh: Boolean = false): ConnectResult =
        withContext(Dispatchers.IO) {
            if (forceRefresh) {
                invalidate()
                lastError = null
            }
            val key = ensureKey()
            if (key.isNullOrBlank()) {
                ConnectResult.Fail(lastError ?: "连接失败（原因未知）")
            } else {
                ConnectResult.Ok(key, prefs.getLong(PREF_KEY_EXPIRE, 0L))
            }
        }

    suspend fun ensureKey(): String? {
        // 【脱敏版】服务地址为占位符（未替换）时直接判定不可用，避免拿非法 URL 发请求。
        if (!AIConfigStore.isUsableUrl(AIConfigStore.OFFICIAL_AI_DEVICE_URL)) {
            lastError = "未配置官方 AI 服务地址（源码占位符：Your link）"
            return null
        }
        val now = System.currentTimeMillis() / 1000
        val cached = prefs.getString(PREF_KEY_VALUE, null)
        val cachedExpire = prefs.getLong(PREF_KEY_EXPIRE, 0L)
        if (!cached.isNullOrBlank() && cachedExpire - now > REFRESH_AHEAD_SECONDS) {
            return cached
        }
        return mutex.withLock {
            val now2 = System.currentTimeMillis() / 1000
            val cached2 = prefs.getString(PREF_KEY_VALUE, null)
            val cachedExpire2 = prefs.getLong(PREF_KEY_EXPIRE, 0L)
            if (!cached2.isNullOrBlank() && cachedExpire2 - now2 > REFRESH_AHEAD_SECONDS) {
                return@withLock cached2
            }
            withContext(Dispatchers.IO) { fetchAndStore() }
        }
    }

    /** 用量页打开时同步一次余额；失败时保留上次缓存，不影响本机统计。 */
    suspend fun refreshQuota(): QuotaState = withContext(Dispatchers.IO) {
        val waitSeconds = acquireRequestSlot()
        if (waitSeconds > 0L) {
            lastError = throttleMessage(waitSeconds)
            return@withContext quotaState()
        }
        val key = try {
            prefs.getString(PREF_KEY_VALUE, null).orEmpty()
        } catch (t: Throwable) {
            ""
        }
        if (key.isBlank()) return@withContext quotaState()
        val sig = apkSignatureSha256().orEmpty()
        if (sig.isBlank()) return@withContext quotaState()
        try {
            val body = JSONObject().put("key", key).put("sig", sig)
                .put("ver", BuildConfig.VERSION_CODE).toString()
            val request = Request.Builder()
                .url(AIConfigStore.OFFICIAL_AI_DEVICE_QUOTA_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-apk-sig", sig)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val client = axeronApp.okhttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty()
                    if (text.isNotBlank()) storeQuota(JSONObject(text))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "同步设备额度失败", t)
        }
        quotaState()
    }

    private fun storeQuota(json: JSONObject) {
        val quota = json.optJSONObject("quota") ?: return
        prefs.edit()
            .putLong(PREF_QUOTA_LIMIT, quota.optLong("limit", 0L))
            .putLong(PREF_QUOTA_USED, quota.optLong("used", 0L))
            .putLong(PREF_QUOTA_REMAINING, quota.optLong("remaining", 0L))
            .putInt(PREF_QUOTA_MONTH, quota.optInt("month", 0))
            .putLong(PREF_QUOTA_SYNC, System.currentTimeMillis() / 1000)
            .apply()
    }

    private fun apkSignatureSha256(): String? = try {
        val pm = axeronApp.packageManager
        val pkg = axeronApp.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val der = signatures?.firstOrNull()?.toByteArray()
        if (der == null || der.isEmpty()) {
            null
        } else {
            MessageDigest.getInstance("SHA-256")
                .digest(der)
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取本机签名失败", t)
        null
    }

    private suspend fun fetchAndStore(): String? {
        val waitSeconds = acquireRequestSlot()
        if (waitSeconds > 0L) {
            lastError = throttleMessage(waitSeconds)
            return null
        }
        val report = DeviceIntegrity.check()
        when (report.verdict) {
            DeviceIntegrity.Verdict.TAMPERED -> {
                invalidate()
                lastError = "系统序列号被篡改，已停止连接"
                return null
            }
            DeviceIntegrity.Verdict.UNKNOWN -> {
                lastError = "无法完成系统序列号校验（${report.detail}），未下发密钥"
                return null
            }
            DeviceIntegrity.Verdict.PASS -> Unit
        }
        val sig = apkSignatureSha256()
        if (sig == null) {
            lastError = "无法读取本机签名，官方密钥未下发"
            return null
        }
        return try {
            val body = JSONObject()
                .put("serial", report.serial)
                .put("ref", report.reference)
                .put("verdict", report.verdict.wire)
                .put("source", report.source)
                .put("ver", BuildConfig.VERSION_CODE)
                .put("sig", sig)
                .toString()
            val request = Request.Builder()
                .url(AIConfigStore.OFFICIAL_AI_DEVICE_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("x-apk-sig", sig)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val client = axeronApp.okhttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val code = try {
                        JSONObject(text).optString("code")
                    } catch (t: Throwable) {
                        ""
                    }
                    lastError = when {
                        code == "update_required" || resp.code == 426 ->
                            "请先更新软件后再连接（当前版本过低）"
                        code == "serial_tampered" -> "系统序列号被篡改，已停止连接"
                        code == "integrity_required" || code == "integrity_failed" ->
                            "序列号校验未通过，未下发密钥"
                        code == "apk_sig" -> "官方密钥拒绝下发（客户端签名未通过校验）"
                        resp.code == 401 -> "官方密钥获取被拒绝"
                        resp.code == 429 -> "本月官方额度已用完"
                        else -> "官方密钥服务异常（HTTP ${resp.code}）"
                    }
                    return null
                }
                if (text.isBlank()) {
                    lastError = "官方密钥服务响应为空"
                    return null
                }
                val json = JSONObject(text)
                val first = json.optJSONArray("keys")?.optJSONObject(0)
                val key = first?.optString("key")?.takeIf { it.isNotBlank() }
                if (key == null) {
                    lastError = "官方密钥服务响应格式异常"
                    return null
                }
                prefs.edit()
                    .putString(PREF_KEY_VALUE, key)
                    .putLong(PREF_KEY_EXPIRE, first.optLong("exp", 0L))
                    .apply()
                storeQuota(json)
                lastError = null
                key
            }
        } catch (t: Throwable) {
            lastError = "官方密钥获取网络异常：${t.message ?: t.javaClass.simpleName}"
            Log.w(TAG, "fetchAndStore 异常", t)
            null
        }
    }
}
