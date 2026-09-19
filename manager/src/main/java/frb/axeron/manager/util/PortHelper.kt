package frb.axeron.manager.util

/**
 * 固定 ADB 端口生成/校验工具。
 *
 * 参考 Stellar 的 `PortBlacklistUtils`：
 *   · 无线调试打开后 adbd 默认监听的是**随机端口**，重启后端口会变，无法靠写死端口连接；
 *   · Stellar 的做法是首次生成一个「安全端口」持久化起来，再用 `tcpip:<port>` 把 adbd
 *     固定切到这个端口，之后每次重启 adbd 都沿用该端口，连接时直接连它即可。
 *
 * 本类只负责「生成一个不会撞上常见服务的安全端口」，持久化由 [frb.axeron.api.core.AxeronSettings] 负责。
 */
object PortHelper {

    /** 端口区间：避开 1024 以下的系统端口，同时避免和常见服务端口冲突。 */
    const val MIN_PORT = 10000
    const val MAX_PORT = 60000

    /**
     * 常见服务端口黑名单：这些端口要么是系统服务、要么是其它 App 的高频监听口，
     * 选作 ADB 端口容易出现「端口被占用 → tcpip 切换失败」。
     */
    private val BLACKLIST_PORTS = setOf(
        // ADB / Android
        5555, 5556, 5557, 5558, 5559, 5037,
        // 常见 Web / 代理
        8080, 8888, 6666, 7777, 1234, 4444,
        // 远程 / 系统
        3389, 22, 23, 21, 80, 443,
        // 数据库
        3306, 5432, 27017, 6379
    )

    /** 是否在黑名单内（UI 手输端口时可以给出提示）。 */
    fun isPortBlacklisted(port: Int): Boolean = port in BLACKLIST_PORTS

    /** 端口是否落在允许区间内。 */
    fun isValidPort(port: Int): Boolean = port in 1..65535

    /**
     * 生成一个安全的随机端口。
     *
     * @return 生成成功的端口；若 [maxAttempts] 次都撞上黑名单则返回 -1。
     */
    fun generateSafeRandomPort(
        minPort: Int = MIN_PORT,
        maxPort: Int = MAX_PORT,
        maxAttempts: Int = 100
    ): Int {
        if (minPort <= 0 || maxPort > 65535 || minPort > maxPort) return -1
        var attempts = 0
        while (attempts < maxAttempts) {
            val port = minPort + (0..(maxPort - minPort)).random()
            if (!isPortBlacklisted(port)) {
                return port
            }
            attempts++
        }
        return -1
    }
}