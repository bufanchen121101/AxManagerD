package frb.axeron.manager

import android.os.Build
import android.os.Bundle
import androidx.core.os.bundleOf
import frb.axeron.api.Axeron
import frb.axeron.ktx.workerHandler
import frb.axeron.provider.AxeronProvider
import frb.axeron.server.util.Logger
import frb.axeron.shared.ShizukuApiConstant.USER_SERVICE_ARG_PGID
import frb.axeron.shared.ShizukuApiConstant.USER_SERVICE_ARG_TOKEN
import frb.axeron.manager.owner.DeviceOwnerPrivilege
import moe.shizuku.api.BinderContainer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AxManagerProvider : AxeronProvider() {

    companion object {
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        private const val METHOD_EXEC_DPM = "execDpm"
        private const val EXTRA_ARGS = "args"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_OUTPUT = "output"
        private val LOGGER = Logger("AxManagerProvider")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_EXEC_DPM) {
            return callExecDpm(extras)
        }
        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            LOGGER.d("sendUserService")
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val pgid = extras.getInt(USER_SERVICE_ARG_PGID)
                val binder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(EXTRA_BINDER,  BinderContainer::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable(EXTRA_BINDER)
                }?.binder ?: return null
//                val binder = extras.getParcelableCompat(EXTRA_BINDER, BinderContainer::class.java)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                var reply: Bundle? = Bundle()

                val listener = object : Axeron.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        try {
                            Axeron.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token,
                                USER_SERVICE_ARG_PGID to pgid
                            )
                            )
                            reply!!.putParcelable(EXTRA_BINDER,
                                BinderContainer(Axeron.getShizukuService().asBinder())
                            )
                        } catch (e: Throwable) {
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        }

                        Axeron.removeBinderReceivedListener(this)

                        countDownLatch.countDown()
                    }
                }

                Axeron.addBinderReceivedListenerSticky(listener, workerHandler)

                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS)
                    reply
                } catch (e: TimeoutException) {
                    LOGGER.e(e, "Binder not received in 5s")
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }

    /**
     * `axeron-dpm` 命令的 ContentProvider 同步回传入口。
     *
     * 背景（结果文件路径的权限死局）：
     *   模块 action.sh 在 Axeron server 派生的 shell 子进程（uid=2000、无 root）里执行，
     *   而 DPM 特权命令必须在 manager app 进程（uid 正确、Dhizuku 已授权）里执行。
     *   结果回传若走「文件 + 脚本轮询」，会撞上 Android 11+ scoped storage 限制：
     *   - app 的 external files dir（/sdcard/Android/data/...）app 能写、shell(uid2000) 读不到；
     *   - /data/local/tmp（shell_data_file）shell 能写、app 进程（untrusted_app）被 SELinux 拒绝。
     *   两者无交集，导致结果文件永远无法双向可达，脚本最终报 rc=3。
     *
     * 解决：
     *   改用 ContentProvider 的 call() 同步回传。脚本用系统自带 `content call` 命令
     *   （uid 2000 可调用 exported provider）触发本 method，命令在 app 进程内执行
     *   [DeviceOwnerPrivilege.execute]，结果直接塞进返回 Bundle 由脚本读回，
     *   彻底绕开文件路径权限问题。
     *
     * 线程模型说明：本方法在 binder 线程执行，与 DpmCommandService.onStartCommand 一致，
     *   [DeviceOwnerPrivilege.execute] 内部的 Dhizuku.init（走 ContentResolver.call 请求 binder）
     *   线程安全，因此可在此直接同步执行。
     */
    private fun callExecDpm(extras: Bundle?): Bundle {
        val appContext = context ?: return Bundle().apply {
            putInt(EXTRA_CODE, 4)
            putString(EXTRA_OUTPUT, "Error: provider context unavailable")
        }
        val appCtx = appContext.applicationContext ?: appContext

        // 命令参数：脚本通过 --extra args:s:<a,b,c> 传入，逗号分隔。
        val argsStr = extras?.getString(EXTRA_ARGS)
        val args: List<String> = if (argsStr.isNullOrEmpty()) {
            emptyList()
        } else {
            argsStr.split(',')
        }

        val (code, output) = try {
            DeviceOwnerPrivilege.execute(appCtx, args)
        } catch (t: Throwable) {
            LOGGER.w("execDpm execute failed", t)
            4 to "Error: ${t.message ?: t.javaClass.simpleName}"
        }

        return Bundle().apply {
            putInt(EXTRA_CODE, code)
            putString(EXTRA_OUTPUT, output)
        }
    }
}