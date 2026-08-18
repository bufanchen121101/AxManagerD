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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
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
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L

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

    private val handler = Handler(Looper.getMainLooper())
    private val keepAliveCheckRunnable = object : Runnable {
        override fun run() {
            checkAndRestartServer()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.postDelayed(keepAliveCheckRunnable, CHECK_INTERVAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveCheckRunnable)
        super.onDestroy()
    }

    private fun checkAndRestartServer() {
        // 仅当用户在设置里开启「模块进程保活」时才执行模块/server 检查与重启
        if (!AxeronSettings.getEnableModuleKeepAlive()) return
        runCatching {
            if (!Axeron.pingBinder()) {
                Log.w("KeepAliveService", "Axeron server disconnected, keep-alive restart")
                Axeron.newProcess(Starter.internalCommand)
            }
        }.onFailure { e ->
            Log.e("KeepAliveService", "keep-alive check failed", e)
        }
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
