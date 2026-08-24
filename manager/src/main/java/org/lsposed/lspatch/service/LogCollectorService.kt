package org.lsposed.lspatch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import frb.axeron.manager.R
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi

/**
 * Keeps LSPatch collecting logs whenever it is alive.
 *
 * Two jobs in one service. The ongoing notification is what keeps the app's process from being reaped in the background
 * — collection is only continuous if the process that holds the Shizuku binding stays up — and a supervisor loop starts
 * the shell-side `logcat -f` collector once Shizuku is granted and restarts it if it ever dies (the buffer was cleared,
 * the logcat was killed). The collector itself runs as the shell user and rotates its own files; see [ShizukuService].
 *
 * The service is deliberately cheap: once the collector is healthy the loop is a binder round trip every few seconds
 * that does nothing, and the notification sits at minimum importance with no badge, so it is present in the shade but
 * never intrudes.
 */
class LogCollectorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        scope.launch {
            while (isActive) {
                // refresh() rather than ensureReady(): this tick repeats forever, and a device
                // without Shizuku would otherwise report the same thing to the reader on a loop.
                // The Logs screen already explains an absent Shizuku in place.
                if (ShizukuApi.refresh() && !ShizukuApi.isLogCollectorRunning()) {
                    ShizukuApi.startLogCollector(LOG_DIR, relevantUids(this@LogCollectorService))
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        // Restarted by the system if it is killed, so collection resumes without the user reopening
        // the app. The collector is re-established by the loop above on the next tick.
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Turning monitoring off should not leave a logcat pinned to the buffer. Best effort on a
        // detached scope, since this one is already cancelled.
        CoroutineScope(Dispatchers.IO).launch {
            ShizukuApi.stopLogCollector()
            // And hand the shell process back: nothing else in the app needs it while monitoring is
            // off, and an unbound one would sit there until the device reboots.
            ShizukuApi.releaseUserService()
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        // API 34 requires a declared foreground-service type at the call site; log collection maps
        // to none of the standard buckets, so it is "special use" (declared in the manifest too).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.log_service_title))
            .setContentText(getString(R.string.log_service_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.log_service_channel),
                    NotificationManager.IMPORTANCE_MIN,
                )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        /** Shell-owned; the app reads parts back through Shizuku, never directly (cross-UID). */
        const val LOG_DIR = "/data/local/tmp/lspatch-logs"

        /**
         * The uids whose lines belong in the framework stream: the manager itself, and every patched app and module.
         * Each is read straight off its [android.content.pm.ApplicationInfo], so no extra PackageManager round trip is
         * needed; the collector matches lines by these.
         */
        fun relevantUids(context: Context): IntArray {
            // The manager's own uid is written first and the collector reads it positionally: its
            // lines are filtered more tightly than a patched app's, because everything its UI
            // process renders would otherwise drown the stream.
            val own = context.applicationInfo.uid
            val others = LinkedHashSet<Int>()
            LSPPackageManager.appList
                .filter { it.isModule || it.app.metaData?.containsKey("lspatch") == true }
                .forEach { if (it.app.uid != own) others.add(it.app.uid) }
            return intArrayOf(own) + others.toIntArray()
        }

        private const val CHANNEL_ID = "lspatch_log_monitor"
        private const val NOTIF_ID = 0x15
        private const val CHECK_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, LogCollectorService::class.java)
            // Background-start restrictions (Android 12+) throw when there is no foreground reason to
            // start; the caller starts this from a user-visible launch, and the guard keeps a stray
            // background start from taking the app down.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LogCollectorService::class.java)) }
        }
    }
}
