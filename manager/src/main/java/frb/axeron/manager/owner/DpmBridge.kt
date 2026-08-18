package frb.axeron.manager.owner

import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Looper

/**
 * `axeron-dpm` 命令的 shell 桥接入口。
 *
 * 模块的 action.sh 在 Axeron server 派生的 shell 里执行（普通 shell 子进程，不天然具备
 * Device Owner 特权）。本类通过 `app_process` 启动，先利用 [ActivityThread.systemMain]
 * 拿到 manager 的真实 Context（与 [frb.axeron.server.AxeronService.getContext] 同一套机制），
 * 再调用 [DeviceOwnerPrivilege.execute] 执行三级路由命令。
 *
 * ⚠ 关键：`ActivityThread.systemMain()` 内部会创建 Handler，要求当前线程已调用
 * `Looper.prepare()`；而 `app_process` 拉起的进程里 main 线程没有 Looper。
 * 因此必须先 `Looper.prepareMainLooper()`（与 AxeronService.main 的做法一致），
 * 否则会抛 "Can't create handler inside thread ... that has not called Looper.prepare()"。
 *
 * 用法（由 assets/scripts/axeron-dpm 脚本调用）：
 *   app_process / frb.axeron.manager.owner.DpmBridge hide com.example.app
 */
object DpmBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        DdmHandleAppName.setAppName("axeron_dpm", 0)

        // 先准备 main Looper，否则 ActivityThread.systemMain() 会因无 Looper 崩溃。
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()

        if (args.isEmpty()) {
            println("Usage: axeron-dpm <hide|unhide|suspend|unsuspend|force-stop|uninstall> ...")
            kotlin.system.exitProcess(1)
        }

        val context = try {
            obtainContext()
        } catch (t: Throwable) {
            System.err.println("Error: failed to obtain manager context (${t.message})")
            System.err.flush()
            kotlin.system.exitProcess(1)
            return
        }

        val (code, output) = DeviceOwnerPrivilege.execute(context, args.toList())
        if (output.isNotEmpty()) {
            println(output)
        }
        kotlin.system.exitProcess(code)
    }

    /**
     * 通过 ActivityThread.systemMain() 拿到 manager 的 Context。
     * 这里不引入 hidden-api stub（UserHandleHidden/ContextHidden），
     * 直接用 systemContext —— 它同样能拿到 DEVICE_POLICY_SERVICE 与 ContentResolver。
     */
    private fun obtainContext(): Context {
        val activityThread = ActivityThread.systemMain()
        return activityThread.systemContext
    }
}
