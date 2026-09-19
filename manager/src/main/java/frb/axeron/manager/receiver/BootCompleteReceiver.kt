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

    private fun startRoot(pending: PendingResult) {
        if (!Shell.getShell().isRoot) {
            Shell.getCachedShell()?.close()
            safeFinish(pending)
            return
        }

        Shell.cmd(Starter.internalCommand).exec()
        safeFinish(pending)
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