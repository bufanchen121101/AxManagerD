package frb.axeron.manager.ai

import android.content.pm.PackageManager
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.FileInputStream

/**
 * 特权 shell 执行通道（重置功能专用）。
 *
 * ## 为什么需要它
 * 「按系统默认值自动恢复」要真的改系统设置，必须拿到 **ADB（shell，uid 2000）** 身份。
 * 本应用有两条通道：
 * 1. **已激活（Axeron 服务在跑）** → 直接走 [AxeronPluginService.execProcessSafeWithTimeout]，
 *    这是软件内自带的 ADB 能力，优先使用；
 * 2. **未激活** → 退回 **Shizuku**（用户自己装的 Shizuku 授予 shell 身份）。
 *    `rikka.shizuku.Shizuku.newProcess` 是 private（编译期不可用），因此与
 *    `ActivateViewModel.execViaShizuku` 保持一致：`Shizuku.getBinder()` +
 *    `ShizukuBinderWrapper` + `IShizukuService.Stub.asInterface` 后调 `newProcess`。
 * 3. 两条都没有 → 返回 [Channel.NONE]，由 UI 提示「去激活 / 去授予 Shizuku 权限」。
 *
 * ## 只做执行，不做决策
 * 具体要执行什么由 [RestorePlanner] 决定；这里只负责「用可用身份把一条命令跑完并回报结果」。
 */
object PrivilegedShell {

    private const val TAG = "PrivilegedShell"

    /** 执行通道。 */
    enum class Channel {
        /** 已激活：走 Axeron（软件内 ADB 能力）。 */
        AXERON,

        /** 未激活但已获 Shizuku 授权：走 Shizuku（shell 身份）。 */
        SHIZUKU,

        /** 两条通道都不可用。 */
        NONE,
    }

    /** 一条命令的执行结果。 */
    data class ExecResult(
        val ok: Boolean,
        val code: Int,
        val out: String,
        val err: String,
    )

    /** Axeron 服务 binder 是否可用（= 已激活、服务在跑）。 */
    fun isAxeronAlive(): Boolean = runCatching { Axeron.pingBinder() }.getOrDefault(false)

    /** Shizuku 服务是否在跑（不代表已授权）。 */
    fun isShizukuAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已获得 Shizuku 的真实授权。 */
    fun isShizukuGranted(): Boolean =
        isShizukuAlive() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 当前可用的执行通道。 */
    fun channel(): Channel = when {
        isAxeronAlive() -> Channel.AXERON
        isShizukuGranted() -> Channel.SHIZUKU
        else -> Channel.NONE
    }

    /** 通道名（给 UI 显示用，纯技术标识，不参与文案定制）。 */
    fun channelName(target: Channel = channel()): String = when (target) {
        Channel.AXERON -> "Axeron shell (uid 2000)"
        Channel.SHIZUKU -> "Shizuku shell (uid 2000)"
        Channel.NONE -> "-"
    }

    /** 用当前可用通道执行一条命令。 */
    suspend fun exec(command: String, timeoutMs: Long = 30_000L): ExecResult =
        when (channel()) {
            Channel.AXERON -> execViaAxeron(command, timeoutMs)
            Channel.SHIZUKU -> execViaShizuku(command, timeoutMs)
            Channel.NONE -> ExecResult(
                ok = false,
                code = -1,
                out = "",
                err = "NO_PRIVILEGE_CHANNEL",
            )
        }

    // ==================================================================================
    // ① Axeron（已激活）
    // ==================================================================================

    private suspend fun execViaAxeron(command: String, timeoutMs: Long): ExecResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val env = runCatching { Axeron.getEnvironment() }.getOrNull()
                val r = AxeronPluginService.execProcessSafeWithTimeout(
                    cmd = arrayOf("sh", "-c", command),
                    env = env,
                    timeoutMs = timeoutMs,
                )
                ExecResult(
                    ok = r.exitCode == 0,
                    code = r.exitCode,
                    out = r.stdout,
                    err = r.stderr,
                )
            }.getOrElse { t ->
                Log.w(TAG, "Axeron 执行失败：$command", t)
                ExecResult(false, -1, "", t.message ?: t.toString())
            }
        }

    // ==================================================================================
    // ② Shizuku（未激活但已授权）
    // ==================================================================================

    private suspend fun execViaShizuku(command: String, timeoutMs: Long): ExecResult =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isShizukuGranted()) {
                    return@runCatching ExecResult(false, -1, "", "Shizuku 未运行或未授权")
                }
                val binder = Shizuku.getBinder()
                    ?: return@runCatching ExecResult(false, -1, "", "Shizuku binder 不可用")

                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(
                    rikka.shizuku.ShizukuBinderWrapper(binder)
                )
                val process = service.newProcess(
                    arrayOf("/system/bin/sh", "-c", command),
                    null,
                    null,
                ) ?: return@runCatching ExecResult(false, -1, "", "Shizuku newProcess 返回 null")

                // 先等待退出，再一次性读流：重置命令输出很短，不会撑爆管道缓冲。
                val finished = runCatching {
                    process.waitForTimeout(timeoutMs, "ms")
                }.getOrDefault(false)

                if (!finished) {
                    runCatching { process.destroy() }
                    return@runCatching ExecResult(
                        ok = false,
                        code = -1,
                        out = "",
                        err = "命令超时（${timeoutMs}ms）已终止",
                    )
                }

                val code = runCatching { process.exitValue() }.getOrDefault(-1)
                val out = runCatching {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                    }
                }.getOrDefault("")
                val err = runCatching {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
                    }
                }.getOrDefault("")

                ExecResult(
                    ok = code == 0,
                    code = code,
                    out = out.trim(),
                    err = err.trim(),
                )
            }.getOrElse { t ->
                Log.w(TAG, "Shizuku 执行失败：$command", t)
                ExecResult(false, -1, "", t.message ?: t.toString())
            }
        }

    // ==================================================================================
    // ③ 授权的 UI 入口（UI 侧调用）
    // ==================================================================================

    /**
     * 发起 Shizuku 授权请求。
     *
     * 调用方自行注册 [Shizuku.OnRequestPermissionResultListener] 接收结果；
     * 这里只负责把「没有 binder 时先拉一次 binder」这一既有修复逻辑带上
     * （与 `ActivateViewModel.requestShizukuPermission` 同一口径）。
     */
    fun requestShizukuPermission(requestCode: Int): Result<Unit> = runCatching {
        Shizuku.requestPermission(requestCode)
    }
}
