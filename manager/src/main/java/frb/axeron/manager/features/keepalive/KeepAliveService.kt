package frb.axeron.manager.features.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.ActivityManager
import android.os.IBinder
import android.util.Log
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 保活加固：若本应用是 Device Owner，则利用 DO 特权降低被系统冻结/清理的概率。
        // 失败不影响常规保活（前台服务 + START_STICKY 仍生效）。
        applyDeviceOwnerHardening()
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
        }
        startForeground(NOTIFICATION_ID, buildNotification())
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
            .setContentText(getString(R.string.keep_alive_desc))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
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
