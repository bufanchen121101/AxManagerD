package frb.axeron.manager.features.runtime.quick

/**
 * 快捷指令总表（54 条，对应设计文档 §4.5）。
 *
 * 分组：CPU 10 / 内存 10 / 温度 7 / 电池 8 / 网络 7 / 存储 4 / 屏幕 4 / 进程 4。
 */
object QuickCommandRegistry {

    /** CPU 组（10 条）。 */
    private val CPU = listOf(
        QuickCommandSpec("cpu.load", QuickGroup.CPU, "负载均值", "cat /proc/loadavg"),
        QuickCommandSpec("cpu.stat", QuickGroup.CPU, "总体统计", "cat /proc/stat | head -n 1"),
        QuickCommandSpec("cpu.online", QuickGroup.CPU, "在线核心", "cat /sys/devices/system/cpu/online"),
        QuickCommandSpec("cpu.present", QuickGroup.CPU, "全部核心", "cat /sys/devices/system/cpu/present"),
        QuickCommandSpec("cpu.freq", QuickGroup.CPU, "各核频率", "for c in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do echo \"\$c=\$(cat \$c)\"; done"),
        QuickCommandSpec("cpu.gov", QuickGroup.CPU, "调频策略", "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"),
        QuickCommandSpec("cpu.govlist", QuickGroup.CPU, "可用策略", "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"),
        QuickCommandSpec("cpu.minmax", QuickGroup.CPU, "频率上下限", "echo min=\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq); echo max=\$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq)"),
        QuickCommandSpec("cpu.uptime", QuickGroup.CPU, "开机时长", "cat /proc/uptime"),
        QuickCommandSpec("cpu.info", QuickGroup.CPU, "硬件信息", "grep -m2 -E 'model name|Hardware' /proc/cpuinfo"),
    )

    /** 内存组（10 条）。 */
    private val MEMORY = listOf(
        QuickCommandSpec("mem.info", QuickGroup.MEMORY, "内存概览", "cat /proc/meminfo | head -n 5"),
        QuickCommandSpec("mem.total", QuickGroup.MEMORY, "总内存", "grep MemTotal /proc/meminfo"),
        QuickCommandSpec("mem.avail", QuickGroup.MEMORY, "可用内存", "grep MemAvailable /proc/meminfo"),
        QuickCommandSpec("mem.free", QuickGroup.MEMORY, "空闲内存", "grep MemFree /proc/meminfo"),
        QuickCommandSpec("mem.cached", QuickGroup.MEMORY, "缓存", "grep -E '^Cached|^Buffers' /proc/meminfo"),
        QuickCommandSpec("mem.swap", QuickGroup.MEMORY, "交换分区", "grep -E 'SwapTotal|SwapFree' /proc/meminfo"),
        QuickCommandSpec("mem.slab", QuickGroup.MEMORY, "内核 slab", "grep -E '^Slab|^SReclaimable' /proc/meminfo"),
        QuickCommandSpec("mem.hwm", QuickGroup.MEMORY, "水位线", "grep -E 'min|low|high' /proc/zoneinfo | head -n 6"),
        QuickCommandSpec("mem.vmstat", QuickGroup.MEMORY, "vmstat", "vmstat 1 2 | tail -n 1"),
        QuickCommandSpec("mem.pressure", QuickGroup.MEMORY, "压力指标", "cat /proc/pressure/memory"),
    )

    /** 温度组（7 条）。 */
    private val THERMAL = listOf(
        QuickCommandSpec("temp.zones", QuickGroup.THERMAL, "全部温区", "for z in /sys/class/thermal/thermal_zone*; do echo \"\$z=\$(cat \$z/temp 2>/dev/null)\"; done"),
        QuickCommandSpec("temp.cpu", QuickGroup.THERMAL, "CPU 温度", "cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null"),
        QuickCommandSpec("temp.types", QuickGroup.THERMAL, "温区类型", "for z in /sys/class/thermal/thermal_zone*; do cat \$z/type 2>/dev/null; done"),
        QuickCommandSpec("temp.cooling", QuickGroup.THERMAL, "散热设备", "cat /sys/class/thermal/cooling_device*/type 2>/dev/null"),
        QuickCommandSpec("temp.trip", QuickGroup.THERMAL, "触发点", "cat /sys/class/thermal/thermal_zone0/trip_point_*_temp 2>/dev/null"),
        QuickCommandSpec("temp.batt", QuickGroup.THERMAL, "电池温度", "cat /sys/class/power_supply/battery/temp 2>/dev/null"),
        QuickCommandSpec("temp.policy", QuickGroup.THERMAL, "热策略", "cat /sys/class/thermal/thermal_zone0/policy 2>/dev/null"),
    )

    /** 电池组（8 条）。 */
    private val BATTERY = listOf(
        QuickCommandSpec("batt.level", QuickGroup.BATTERY, "电量", "cat /sys/class/power_supply/battery/capacity 2>/dev/null"),
        QuickCommandSpec("batt.status", QuickGroup.BATTERY, "充电状态", "cat /sys/class/power_supply/battery/status 2>/dev/null"),
        QuickCommandSpec("batt.temp", QuickGroup.BATTERY, "温度", "cat /sys/class/power_supply/battery/temp 2>/dev/null"),
        QuickCommandSpec("batt.volt", QuickGroup.BATTERY, "电压", "cat /sys/class/power_supply/battery/voltage_now 2>/dev/null"),
        QuickCommandSpec("batt.cur", QuickGroup.BATTERY, "电流", "cat /sys/class/power_supply/battery/current_now 2>/dev/null"),
        QuickCommandSpec("batt.health", QuickGroup.BATTERY, "健康度", "cat /sys/class/power_supply/battery/health 2>/dev/null"),
        QuickCommandSpec("batt.wl", QuickGroup.BATTERY, "唤醒锁", "dumpsys power | grep -i wakelock | head -n 10"),
        QuickCommandSpec("batt.stats", QuickGroup.BATTERY, "耗电排行", "dumpsys batterystats --charged | head -n 20"),
    )

    /** 第一批（CPU + 内存 + 温度 + 电池 = 35 条）。 */
    private val PART1 = CPU + MEMORY + THERMAL + BATTERY

    /** 网络组（7 条）。 */
    private val NETWORK = listOf(
        QuickCommandSpec("net.traffic", QuickGroup.NETWORK, "流量统计", "cat /proc/net/dev"),
        QuickCommandSpec("net.tcp", QuickGroup.NETWORK, "TCP 连接", "cat /proc/net/tcp"),
        QuickCommandSpec("net.conn", QuickGroup.NETWORK, "连接详情", "ss -tunap 2>/dev/null || netstat -tunap 2>/dev/null"),
        QuickCommandSpec("net.wifi", QuickGroup.NETWORK, "WiFi 信息", "dumpsys wifi | grep -m5 -i ssid"),
        QuickCommandSpec("net.ping", QuickGroup.NETWORK, "延迟测试", "ping -c 4 1.1.1.1"),
        QuickCommandSpec("net.dns", QuickGroup.NETWORK, "DNS 服务器", "getprop | grep -i dns"),
        QuickCommandSpec("net.proxy", QuickGroup.NETWORK, "代理设置", "settings get global http_proxy"),
    )

    /** 存储组（4 条）。 */
    private val STORAGE = listOf(
        QuickCommandSpec("disk.df", QuickGroup.STORAGE, "分区用量", "df -h"),
        QuickCommandSpec("disk.io", QuickGroup.STORAGE, "IO 统计", "cat /proc/diskstats | head -n 10"),
        QuickCommandSpec("disk.big", QuickGroup.STORAGE, "大目录", "du -sh /data/* 2>/dev/null | sort -h | tail -n 10"),
        QuickCommandSpec("disk.mount", QuickGroup.STORAGE, "挂载点", "cat /proc/mounts | head -n 20"),
    )

    /** 屏幕组（4 条）。 */
    private val SCREEN = listOf(
        QuickCommandSpec("scr.size", QuickGroup.SCREEN, "分辨率", "wm size"),
        QuickCommandSpec("scr.density", QuickGroup.SCREEN, "像素密度", "wm density"),
        QuickCommandSpec("scr.state", QuickGroup.SCREEN, "亮灭屏", "dumpsys power | grep -m2 -i 'mWakefulness\\|mScreenOn'"),
        QuickCommandSpec("scr.fps", QuickGroup.SCREEN, "刷新率", "dumpsys SurfaceFlinger | grep -m5 -i fps"),
    )

    /** 进程组（4 条）。 */
    private val PROCESS = listOf(
        QuickCommandSpec("ps.top", QuickGroup.PROCESS, "Top 进程", "top -b -n 1 -o %CPU,%MEM,CMDLINE 2>/dev/null | head -n 11"),
        QuickCommandSpec("ps.axeron", QuickGroup.PROCESS, "模块进程", "ps -A 2>/dev/null | grep -i axeron"),
        QuickCommandSpec("ps.zombie", QuickGroup.PROCESS, "僵尸进程", "ps -A -o STAT,PID,NAME 2>/dev/null | grep '^Z'"),
        QuickCommandSpec("ps.self", QuickGroup.PROCESS, "当前进程", "echo \$\$; ps -p \$\$ 2>/dev/null"),
    )

    /** 全部指令（54 条）。 */
    val ALL: List<QuickCommandSpec> = PART1 + NETWORK + STORAGE + SCREEN + PROCESS

    /** 按分组返回。 */
    fun byId(id: String): QuickCommandSpec? = ALL.firstOrNull { it.id == id }

    /** 分组列表（保持顺序）。 */
    val GROUPS: List<String> = listOf(
        QuickGroup.CPU,
        QuickGroup.MEMORY,
        QuickGroup.THERMAL,
        QuickGroup.BATTERY,
        QuickGroup.NETWORK,
        QuickGroup.STORAGE,
        QuickGroup.SCREEN,
        QuickGroup.PROCESS,
    )
}