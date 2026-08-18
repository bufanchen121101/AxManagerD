package frb.axeron.manager.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import frb.axeron.server.util.Logger
import java.io.File

/**
 * `axeron-dpm` 命令的 Device Owner 执行入口（运行在 manager app 进程）。
 *
 * 背景：模块的 action.sh 在 Axeron 派生的 shell 子进程里执行，该进程 uid=2000、
 * 包名为 `android`。而 Dhizuku 的授权绑定的是 `frb.axeron.manager` 自己的 uid，
 * 一旦在 shell 进程里直接调 `Dhizuku.init()`（其内部会 `getContentResolver().call(...)`
 * 去 Dhizuku 的 Provider），AMS 会抛：
 *   SecurityException: Given calling package android does not match caller's uid 2000
 *
 * 因此特权命令必须在 manager app 进程（uid 正确、且已在 UI 授权时初始化过 Dhizuku）
 * 里执行。本 Receiver 由 `axeron-dpm` 脚本通过 `am broadcast` 触发，收到命令后用
 * manager 自己的 context 调用 [DeviceOwnerPrivilege.execute]，并把结果写入脚本可读的
 * 结果文件，脚本随后读回并据此设置退出码。
 *
 * 结果文件路径由 `axeron-dpm` 脚本通过 extra `result` 传入，规避硬编码。
 */
class DpmCommandReceiver : BroadcastReceiver() {
    companion object {
        private val LOGGER = Logger("DpmCommandReceiver")
        // 定义一个 manifest 里引用的 action（脚本用同样的 action 发广播）
        const val ACTION = "frb.axeron.manager.action.EXEC_DPM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val appContext = context.applicationContext

        // 结果文件路径（由脚本通过 --es result <path> 传入）。
        // 缺省使用本 app 的 external files dir（/sdcard/Android/data/frb.axeron.manager/files/），
        // 这是 app 进程一定可写、且模块 shell（含 Shizuku、无 root）也能读写的唯一可靠路径。
        // 注意：不可用 /data/local/tmp（SELinux shell_data_file 拒绝 app 进程写入）。
        val resultPath = intent.getStringExtra("result")
            ?: File(appContext.getExternalFilesDir(null), "axeron-dpm.result").absolutePath

        // 命令参数：脚本把原始 argv 通过 --esa args 传入
        val args: List<String> = intent.getStringArrayListExtra("args")?.toList()
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
    }
}