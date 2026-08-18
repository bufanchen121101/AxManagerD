package frb.axeron.manager.owner

import android.app.Service
import android.content.Intent
import android.os.IBinder
import frb.axeron.server.util.Logger
import java.io.File

/**
 * `axeron-dpm` 命令的 Device Owner 执行入口（运行在 manager app 进程）。
 *
 * 与 [DpmCommandReceiver] 的职责相同，但改用「前台可拉起的 Service」而非 BroadcastReceiver。
 *
 * 背景：在部分设备 / Android 版本上，从 shell（uid 2000）用 `am broadcast` 发出的显式广播，
 * 其 manifest receiver 会被 system_server 的广播队列 Skip 掉（BroadcastRecord dump 里出现
 * `Skipped #0: (manifest)` + `receiver=`），导致 [DpmCommandReceiver.onReceive] 根本不执行，
 * 脚本始终拿不到结果文件、报 "no response from manager"。
 *
 * 而 `am startservice` 对 exported=true 的 Service 能够可靠拉起 app 进程并执行 onStartCommand，
 * 不受上述广播 skip 限制。本 Service 在执行完 [DeviceOwnerPrivilege.execute] 后写结果文件并
 * 立即 stopSelf，脚本轮询读回结果文件即可。
 *
 * 结果文件路径由脚本通过 extra `result` 传入，缺省使用本 app 的 external files dir
 * （/sdcard/Android/data/frb.axeron.manager/files/axeron-dpm.result），这是 app 进程一定可写、
 * 且模块 shell（含 Shizuku、无 root）也能读写的唯一可靠路径。
 */
class DpmCommandService : Service() {
    companion object {
        private val LOGGER = Logger("DpmCommandService")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val appContext = applicationContext

        // 结果文件路径（由脚本通过 --es result <path> 传入）。
        val resultPath = intent.getStringExtra("result")
            ?: File(appContext.getExternalFilesDir(null), "axeron-dpm.result").absolutePath

        // 命令参数：脚本把原始 argv 通过 --esa args 传入（如 "suspend,com.larus.nova"）。
        // 注意：`am --esa` 在不同 Android 版本上既可能给 ArrayList<String> 也可能给 String[]，
        // 直接 getStringArrayListExtra 在拿到 String[] 时会抛 ClassCastException 并返回 null，
        // 导致 args 退化成默认值。因此这里优先用 getStringArrayExtra（String[] 兼容性最好），
        // 再退化为 ArrayList，最后才回落到单参数的 cmd extra。
        val args: List<String> = intent.getStringArrayExtra("args")?.toList()
            ?: intent.getStringArrayListExtra("args")?.toList()
            ?: listOf(intent.getStringExtra("cmd") ?: "hide")

        val (code, output) = try {
            DeviceOwnerPrivilege.execute(appContext, args)
        } catch (t: Throwable) {
            LOGGER.w("execute failed", t)
            4 to "Error: ${t.message ?: t.javaClass.simpleName}"
        }

        // 写结果：第一行退出码，剩余为输出文本
        try {
            val f = File(resultPath)
            f.parentFile?.mkdirs()
            f.writeText("$code\n$output\n")
        } catch (t: Throwable) {
            LOGGER.w("write result failed", t)
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }
}
