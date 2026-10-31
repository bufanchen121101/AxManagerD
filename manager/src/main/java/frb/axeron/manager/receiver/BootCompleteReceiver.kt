package frb.axeron.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import frb.axeron.api.Axeron
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.manager.adb.AdbStarter
import frb.axeron.manager.features.bootstart.BootStartService
import frb.axeron.manager.features.keepalive.KeepAliveService
import frb.axeron.manager.adb.AdbStateInfo
import frb.axeron.manager.owner.DeviceOwnerAdbActivator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BootCompleteReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Hanya handle BOOT_COMPLETED
        val isBootAction =
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
                    intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED

        if (!isBootAction) {
            return
        }
        if (AxeronSettings.getEnableKeepAlive()) {
            try {
                KeepAliveService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start keep-alive service", e)
            }
        }
        // 【v1.9.1 修复 · root 开机自启】
        //
        // 旧实现把 root 分支放在所有开关之后，导致两类失效：
        //  ① 用户一旦开过「开机自动启动（无线调试预热）」，上面的
        //     `getBootStartService()` 分支就会 start + return，root 路径永远执行不到；
        //  ② 判据只看 lastLaunchMode，而它会被后来的 ADB / DO 激活覆盖，
        //     root 用户只要用过一次其它激活方式，开机就不再走 root。
        //
        // 现在：只要「已缓存的 shell 就是 root」或「上次启动方式记录为 ROOT」，
        // 就优先走 root 启动并 return，不再被无线调试预热分支吞掉。
        if (AxeronSettings.getStartOnBoot() &&
            !context.packageManager.isSafeMode && !Axeron.pingBinder() &&
            (isRootShellCached() ||
                AxeronSettings.getLastLaunchMode() == AxeronSettings.LaunchMethod.ROOT)
        ) {
            Log.d(TAG, "startRoot (root-first boot start)")
            val pending = goAsync()
            startRoot(pending)
            return
        }

        // 【开机自启动修复】「开机自动启动（无线调试预热）」是**独立开关**
        // （见 AxeronSettings.BOOT_START_SERVICE 的注释），旧实现却把它放在下面的
        // `getStartOnBoot()` 之后 —— 用户一旦关掉「开机自激活」，预热分支就永远
        // 执行不到。这里先按独立开关处理，保证两者互不牵连。
        if (AxeronSettings.getBootStartService() &&
            !context.packageManager.isSafeMode &&
            !Axeron.pingBinder()
        ) {
            Log.d(TAG, "start BootStartService (independent boot-start switch)")
            BootStartService.start(context)
            return
        }

        if (!AxeronSettings.getStartOnBoot()) {
            return
        }

        if (context.packageManager.isSafeMode) {
            Log.d(TAG, "safeMode: ${context.packageManager.isSafeMode}")
            return
        }

        if (Axeron.pingBinder()) {
            Log.d(TAG, "status: ${Axeron.pingBinder()}")
            return
        }

        if (AxeronSettings.getLastLaunchMode() == AxeronSettings.LaunchMethod.DEVICE_OWNER ||
            AxeronSettings.getBootStartService()
        ) {
            // ── 无线调试预热分支（v1.6.1）────────────────────────────────
            // 交由前台服务完成「开 ADB / 无线调试 → 发现端口 → 回连 127.0.0.1」，
            // 与 Stellar 的 AdbStartWorker 同一套思路。**不再**走 v1.6.0 的
            // startDeviceOwner()：那条路假定 adbd 监听固定端口 5555，而无线调试
            // 打开后 adbd 实际监听的是随机 TLS 端口，所以必然探测失败。
            Log.d(TAG, "start BootStartService (wireless prewarm)")
            BootStartService.start(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
            AxeronSettings.getLastLaunchMode() == AxeronSettings.LaunchMethod.ADB
        ) {
            val pending = goAsync()
            startAdb(context) {
                safeFinish(pending)
            }
        } else if (AxeronSettings.getLastLaunchMode() == AxeronSettings.LaunchMethod.ROOT) {
            val pending = goAsync()
            startRoot(pending)
        } else {
            Log.w(TAG, "No support start on boot")
        }

        Log.d(TAG, "onReceive: ${intent.action}")
    }

    /**
     * 【v1.9.1】轻量 root 判据：只读 libsu 的**已缓存** shell，不触发 su 握手。
     *
     * 广播接收器在主线程，不能在这里调用 [Shell.getShell]（会同步等待 su，
     * 开机早期可能数秒甚至超时）。缓存为空时返回 false，由
     * `lastLaunchMode == ROOT` 作为第二判据兜底。
     */
    private fun isRootShellCached(): Boolean = runCatching {
        Shell.getCachedShell()?.isRoot == true
    }.getOrDefault(false)

    /**
     * 【v1.9.1 重写】以 root 身份拉起 Axeron 服务（开机自启动）。
     *
     * 旧实现的两个致命问题：
     *  - 直接在主线程调用 `Shell.getShell()`：开机广播期间 su 守护可能尚未就绪，
     *    同步等待会卡住 onReceive（ANR 风险），且**一次失败就永久放弃**；
     *  - 成功后不写 lastLaunchMode，导致下一次开机若走别的分支就丢失 root 身份。
     *
     * 现在：放到独立后台线程（goAsync 的 pending 允许异步 finish），最多 3 轮重试，
     * 每轮失败都关闭缓存 shell 以重建；libsu 全失败后再用 `su -c` 直接兜底一次。
     */
    private fun startRoot(pending: PendingResult) {
        Thread {
            var ok = false
            for (attempt in 1..3) {
                ok = runCatching {
                    val shell = Shell.getShell()
                    if (!shell.isRoot) {
                        Shell.getCachedShell()?.close()
                        return@runCatching false
                    }
                    val result = Shell.cmd(Starter.internalCommand).exec()
                    if (!result.isSuccess) {
                        Shell.getCachedShell()?.close()
                    }
                    result.isSuccess
                }.getOrDefault(false)
                Log.d(TAG, "startRoot attempt $attempt -> $ok")
                if (ok) break
                if (attempt < 3) Thread.sleep(2000)
            }

            // 兜底：libsu 未初始化 / su 管理器响应慢时，直接走 su 二进制。
            // 注意 `su -c` 后面必须是**一个**参数（整条命令），ProcessBuilder 的多参
            // 形式正好满足；退出码 0 且进程正常结束即视为成功。
            if (!ok) {
                ok = runCatching {
                    val p = ProcessBuilder("su", "-c", Starter.internalCommand)
                        .redirectErrorStream(true)
                        .start()
                    val finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) {
                        p.destroyForcibly()
                        false
                    } else {
                        p.exitValue() == 0
                    }
                }.getOrDefault(false)
                Log.d(TAG, "startRoot su -c fallback -> $ok")
            }

            if (ok) {
                runCatching {
                    AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.ROOT)
                }.onFailure { Log.w(TAG, "setLastLaunchMode(ROOT) failed", it) }
            } else {
                Log.w(TAG, "startRoot failed after retries")
            }
            safeFinish(pending)
        }.start()
    }

    /**
     * （保留）设备所有者模式开机自启动的老实现。
     *
     * 目前 [onReceive] 已不再调用它 —— 原因见上面 DEVICE_OWNER 分支的注释：
     * `enableAdbAndBindTcp` 让 adbd 监听的是**固定端口**，而无线调试场景下
     * 端口是随机的，导致绑定结果恒为失败。留作其它有线场景的参考。
     */
    @Suppress("unused")
    private fun startDeviceOwner(
        context: Context,
        finish: (AdbStateInfo) -> Unit
    ) = runBlocking(Dispatchers.IO) {
        val maxAttempts = 3
        var lastMessage = ""
        for (attempt in 1..maxAttempts) {
            if (!DeviceOwnerAdbActivator.isOwner(context)) {
                Log.w(TAG, "startDeviceOwner: not a device owner, abort")
                finish(AdbStateInfo.Failed("Device Owner not active"))
                return@runBlocking
            }
            val bind = DeviceOwnerAdbActivator.enableAdbAndBindTcp(context)
            if (bind.success && bind.port > 0) {
                Log.d(TAG, "startDeviceOwner: adb bound on port ${bind.port} (attempt $attempt)")
                val channel = kotlinx.coroutines.channels.Channel<AdbStateInfo>(1)
                val job = launch {
                    AdbStarter.startAdbClient(context, bind.port) { channel.trySend(it) }
                }
                val result = withTimeoutOrNull(20000) { channel.receive() }
                    ?: AdbStateInfo.Failed("Timeout waiting for connection")
                job.cancel()
                if (result is AdbStateInfo.Success) {
                    // startAdbClient 成功时会写入 ADB，这里覆盖回 DEVICE_OWNER 以便下次重启仍走 DO 分支
                    AxeronSettings.setLastLaunchMode(AxeronSettings.LaunchMethod.DEVICE_OWNER)
                    finish(result)
                    return@runBlocking
                }
                lastMessage = (result as? AdbStateInfo.Failed)?.message ?: ""
            } else {
                lastMessage = bind.message
            }
            Log.w(TAG, "startDeviceOwner attempt $attempt failed: $lastMessage")
            if (attempt < maxAttempts) delay(3000)
        }
        finish(AdbStateInfo.Failed(lastMessage.ifBlank { "Device Owner auto-activation failed" }))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAdb(
        context: Context,
        finish: (AdbStateInfo) -> Unit
    ) = runBlocking(Dispatchers.IO) {
        AdbStarter.startAdbWireless(context, finish)
    }

    fun safeFinish(pending: PendingResult) {
        try {
            pending.finish()
        } catch (e: Exception) {
            Log.e(TAG, "safeFinish failed", e)
        }
    }
}