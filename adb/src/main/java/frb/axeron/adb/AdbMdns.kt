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
import java.net.ServerSocket

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
     * 判断 mDNS 发现的端口是否「可用」。
     *
     * 判定采用**两种都接受**的策略（两个场景都能过）：
     *   ① `ServerSocket().bind(127.0.0.1, port)` 失败（端口已被占用）
     *      → 端口上确有服务在监听 → 可用。
     *      这是 1.0.1 起一直沿用的、经真机验证能保证「无线调试/TCP 激活」成功的判定。
     *   ② bind 成功（本机 IPv4 回环未被占用）时**再尝试 connect**：
     *      真机实测 adbd 监听在 **IPv6 的 `::`**（`/proc/net/tcp6` 可见），此时
     *      对 IPv4 的 `127.0.0.1` 做 bind 并不会冲突 → 旧判定会返回 false（不可用），
     *      于是 mDNS 反复重启 discovery 直至 45s 超时 —— 这正是「开机自启动
     *      拿不到无线调试端口」的卡点。双栈系统上 `::` 接受 IPv4-mapped 连接，
     *      所以这里 connect 能连上就同样算可用。
     */
    private fun isPortAvailable(port: Int): Boolean {
        val occupied = try {
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", port), 1)
                false
            }
        } catch (e: IOException) {
            true
        }
        if (occupied) return true
        return try {
            java.net.Socket().use {
                it.connect(InetSocketAddress("127.0.0.1", port), 250)
            }
            true
        } catch (e: IOException) {
            false
        }
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