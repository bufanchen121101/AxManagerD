package frb.axeron.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface

class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Int>
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var restartScheduled = false
    private var attempts = 0
    var indefinite: Boolean = false

    fun start() {
        if (running) return
        running = true
        attempts = 0
        if (!registered) {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (running && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { resolvedService.host.hostAddress == it.hostAddress }
                }
            && isPortAvailable(resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            observer.onChanged(resolvedService.port)
        } else if (running && (indefinite || attempts < 5) && !restartScheduled) {
            attempts++
            restartScheduled = true
            val delay = if (indefinite) 2000L else attempts * 1000L
            handler.postDelayed({
                if (registered) nsdManager.stopServiceDiscovery(listener)
                handler.postDelayed({
                    if (!registered && running) nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    restartScheduled = false
                }, 100L)
            }, delay)
        }
    }

    /**
     * 【v1.6.3 修复】判断 mDNS 发现的端口是否「真的已经在本机监听」。
     *
     * 原实现用的是 `ServerSocket().bind(127.0.0.1, port)` —— 即「端口能否被我占用」，
     * 只有**没被占用**才算通过。但无线调试场景下，这个端口**正是 adbd 自己占着**的，
     * 于是 bind 必然抛「Address already in use」→ 返回 true，看起来也能通过。
     *
     * 问题在于开机早期：此时 adbd 还没起来，端口无人占用 → bind 成功 → 返回 false
     * → 走「重启 discovery」分支，一直空转到 45s 超时。也就是说这个判定在开机时刻
     * 恰好把「adbd 已就绪」判成不可用。（本工程开机自启动的另一个卡点。）
     *
     * 改为直接**尝试连接**：能连上就说明确实有服务在监听（adbd）→ 可用。
     */
    private fun isPortAvailable(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: IOException) {
        false
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}