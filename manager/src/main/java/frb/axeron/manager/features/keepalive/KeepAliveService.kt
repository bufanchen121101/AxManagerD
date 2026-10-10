package frb.axeron.manager.features.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.ActivityManager
import android.os.IBinder
import android.util.Log
import frb.axeron.api.core.AxeronSettings
import frb.axeron.manager.R

/**
 * 常驻前台服务，用于「后台保活」。
 *
 * 采用常规保活方式：前台服务 + 常驻通知 + START_STICKY + 开机自启，
 * 无需 Device Owner（无 Dhizuku / root 权限也可正常使用）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "axmanager_keepalive"
        private const val NOTIFICATION_ID = 0x7A11
        private const val ACTION_START = "frb.axeron.manager.action.KEEP_ALIVE_START"
        private const val ACTION_STOP = "frb.axeron.manager.action.KEEP_ALIVE_STOP"

        /**
         * 【v1.3.1 仿 shevery 通知栏保活】通知展开区「停止」按钮的动作。
         *
         * 与 [ACTION_STOP] 的区别：它由**通知栏按钮**触发，除了停服务，还会把
         * 保活开关一起关掉 —— 否则 [frb.axeron.manager.ui.AxActivity] 的自恢复
         * 逻辑会立刻把服务拉回来，用户会觉得「点了停止，通知又自己回来了」。
         */
        private const val ACTION_STOP_FROM_NOTIFICATION =
            "frb.axeron.manager.action.KEEP_ALIVE_STOP_FROM_NOTIFICATION"

        /** 通知栏「停止」按钮的 PendingIntent 请求码（与通知 id 错开，避免复用同一个）。 */
        private const val REQUEST_CODE_STOP_ACTION = 0x7A12

        /** 判断保活前台服务当前是否处于运行状态。 */
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            return runCatching {
                am.getRunningServices(Int.MAX_VALUE).any {
                    it.service.className == KeepAliveService::class.java.name
                }
            }.getOrDefault(false)
        }
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }

        /**
         * 【v1.3.1 仿 shevery 通知栏保活】按当前开关重建保活通知。
         *
         * 由设置页「保活通知」开关调用：[frb.axeron.manager.ui.screen.Settings] 里
         * 关闭该开关时，通知要退回「纯状态通知」形态（去掉展开区的「停止」按钮）。
         * 实现上就是再 `start()` 一次 —— [onStartCommand] 会重新
         * [buildNotification]，届时按最新的开关值决定是否带按钮。
         *
         * 注意：**服务未运行时也会启动它**，因为「保活通知」开关本身就代表
         * 「要在消息栏看到这条可停止的保活通知」。
         */
        fun refreshNotification(context: Context) {
            start(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        // 保活加固：若本应用是 Device Owner，则利用 DO 特权降低被系统冻结/清理的概率。
        // 失败不影响常规保活（前台服务 + START_STICKY 仍生效）。
        applyDeviceOwnerHardening()
    }

    /**
     * 启动前台服务（带 foregroundServiceType）。
     *
     * 【v1.4.9 闪退修复】必须显式传 `SPECIAL_USE`，且与 AndroidManifest 中该
     * service 声明的 `android:foregroundServiceType="specialUse"` 严格一致。
     *
     * 历史问题：manifest 声明的是 `dataSync`，而 Android 14+ 对 dataSync 施加
     * 「累计 6 小时/天」硬配额，常驻保活服务必然耗尽，随后
     * `startForeground()` 抛 `ForegroundServiceStartNotAllowedException`、
     * 进程被系统以 `ForegroundServiceDidNotStopInTimeException` 干掉 ——
     * 正是崩溃日志里那串反复出现的堆栈。
     *
     * 若 Kotlin 侧不传类型而 manifest 声明了类型，Android 14+ 同样会抛
     * `MissingForegroundServiceTypeException`，所以这里显式对齐。
     */
    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 以 Device Owner 身份做一次保活加固（非 DO 时静默跳过）。 */
    private fun applyDeviceOwnerHardening() {
        runCatching {
            if (!frb.axeron.manager.owner.DeviceOwnerAdbActivator.isOwner(this)) return
            val r = frb.axeron.manager.owner.DeviceOwnerKeepAlive.apply(this)
            Log.i("KeepAliveService", "DO hardening: $r")
        }.onFailure { Log.w("KeepAliveService", "DO hardening failed", it) }
    }
    override fun onDestroy() {
        super.onDestroy()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_FROM_NOTIFICATION -> {
                // 【v1.3.1 仿 shevery 通知栏保活】用户在展开的通知里点了「停止」：
                // ① 关掉保活开关，避免 AxActivity.onResume 的自恢复立刻把服务拉回来；
                // ② 再停前台服务、移除通知（stopForegroundCompat 带 REMOVE）。
                runCatching { AxeronSettings.setEnableKeepAlive(false) }
                    .onFailure { Log.w("KeepAliveService", "disable keep-alive failed", it) }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForegroundCompat()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keep_alive_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = getString(R.string.keep_alive_channel_desc)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, frb.axeron.manager.ui.AxActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 【v1.3.1】正文抽成局部量：展开区（BigTextStyle）与收起态用同一段文案。
        val desc = getString(R.string.keep_alive_desc)
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setSmallIcon(R.drawable.ic_axeron)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(desc)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .apply {
                // 【v1.3.1 仿 shevery 通知栏保活】
                // 打开「保活通知」开关（原「设备所有者保活加固」，本版改名换功能）时：
                // 把通知做成可展开样式，并在展开区放一个「停止」按钮 ——
                // 用户点通知右侧的箭头展开就能看到它，点一下即结束保活。
                // 关闭该开关时保持原样：一条普通的状态通知，不带停止入口。
                if (AxeronSettings.getEnableDoKeepAlive()) {
                    setStyle(Notification.BigTextStyle().bigText(desc))
                    addAction(
                        Notification.Action.Builder(
                            null,
                            getString(R.string.keep_alive_action_stop),
                            buildStopActionPendingIntent()
                        ).build()
                    )
                }
            }
            .build()
    }

    /**
     * 【v1.3.1 仿 shevery 通知栏保活】构造通知展开区「停止」按钮的 PendingIntent。
     *
     * 目标是 [KeepAliveService] 自己（`getService`）：点按钮时系统直接以
     * [ACTION_STOP_FROM_NOTIFICATION] 调起 [onStartCommand]，随后停服务并移除通知。
     * 这样**不需要新增任何广播接收器**，也不需要任何 exported 组件，
     * 比走 BroadcastReceiver 的方案更收敛（少一个对外暴露面）。
     *
     * `FLAG_IMMUTABLE` 是 Android 12+ 的强制要求（minSdk 26，这里显式声明）。
     */
    private fun buildStopActionPendingIntent(): PendingIntent {
        val intent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_STOP_FROM_NOTIFICATION
        }
        return PendingIntent.getService(
            this,
            REQUEST_CODE_STOP_ACTION,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
