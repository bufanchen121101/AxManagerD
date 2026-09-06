package frb.axeron.api.ai

import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult
import frb.axeron.api.ai.CommandAnalyzer.AnalyzeResult.Risk
import frb.axeron.api.core.AxeronSettings

object RuleEngine {

    /** 内置危险代码库开关的持久化 KEY */
    private const val KEY_BUILTIN_ENABLED = "ai_danger_builtin_enabled"

    data class Rule(
        val name: String,
        val pattern: Regex,
        val risk: Risk,
    )

    private val BUILTIN_RULES: List<Rule> = listOf(
        Rule("递归删除根目录", Regex("""rm\s+-rf\s+/"""), Risk.HIGH),
        Rule("强制删除", Regex("""rm\s+-rf"""), Risk.HIGH),
        Rule("清空磁盘", Regex("""\bdd\s+if="""), Risk.HIGH),
        Rule("提权到 root", Regex("""\bsu\b"""), Risk.HIGH),
        Rule("关闭 SELinux", Regex("""setenforce\s+0"""), Risk.HIGH),
        Rule("安装系统包", Regex("""pm\s+install"""), Risk.MEDIUM),
        Rule("卸载应用", Regex("""pm\s+uninstall"""), Risk.MEDIUM),
        Rule("清除应用数据", Regex("""pm\s+clear"""), Risk.MEDIUM),
        Rule("强停应用", Regex("""am\s+force-stop"""), Risk.MEDIUM),
        Rule("修改系统设置", Regex("""settings\s+(put|delete)"""), Risk.MEDIUM),
        Rule("下载远程文件", Regex("""\bcurl\b|\bwget\b"""), Risk.MEDIUM),
        Rule("base64 混淆", Regex("""\bbase64\b"""), Risk.HIGH),
        Rule("eval 执行", Regex("""\beval\b"""), Risk.HIGH),
        Rule("十六进制混淆", Regex("""\bxxd\b"""), Risk.HIGH),
        Rule("写入系统分区", Regex("""mount\s+-o\s+remount"""), Risk.HIGH),
        Rule("修改文件权限", Regex("""chmod\s+777"""), Risk.MEDIUM),
        Rule("修改文件属主", Regex("""chown\b"""), Risk.MEDIUM),
        Rule("加载内核模块", Regex("""\binsmod\b|\bmodprobe\b"""), Risk.HIGH),
        Rule("卸载内核模块", Regex("""\brmmod\b"""), Risk.HIGH),
        Rule("修改 boot 属性", Regex("""resetprop"""), Risk.MEDIUM),
        Rule("隐藏应用", Regex("""axeron-dpm\s+hide"""), Risk.MEDIUM),
        Rule("锁定屏幕", Regex("""axeron-dpm\s+locknow"""), Risk.LOW),
        Rule("重启设备", Regex("""axeron-dpm\s+reboot|\breboot\b"""), Risk.MEDIUM),

        // ============ v1.1.0 补充：破坏性存储 / 厂商内核魔改高危黑名单 ============
        // 1. dd 直写块设备 / 分区（刷坏 eMMC/UFS/SD 卡，不可逆）
        Rule("dd 直写块设备", Regex("""\bdd\b[^\n]*(of\s*=\s*/dev/block|of\s*=\s*/dev/sd|of\s*=\s*/dev/mmcblk|of\s*=\s*/dev/nvme)"""), Risk.HIGH),
        // 2. 直接写 boot/recovery/system 等 by-name 分区节点（烧坏引导，变砖）
        Rule("写 by-name 分区", Regex("""/dev/block/(by-name|bootdevice|platform)[^\s]*"""), Risk.HIGH),
        // 3. 创建/格式化文件系统（mkfs/mke2fs/mkfs.ext4/newfs 清空整块存储）
        Rule("格式化文件系统", Regex("""\b(mkfs|mke2fs|mkfs\.(ext[234]|vfat|f2fs)|newfs)\b"""), Risk.HIGH),
        // 4. 直接写 mmcblk/eMMC 原始块设备（绕过块层，损坏分区表）
        Rule("写 mmcblk 原始块", Regex("""(of|>|tee)[^\n]*/dev/mmcblk\d+\b"""), Risk.HIGH),
        // 5. 刷机/引导镜像写入（flash_image / fastboot flash / heimdall 刷入）
        Rule("刷写引导镜像", Regex("""\b(flash_image|fastboot\s+flash|heimdall\s+flash)\b"""), Risk.HIGH),
        // 6. 擦除块设备（erase / blkdiscard / wipefs 破坏数据）
        Rule("擦除块设备", Regex("""\b(blkdiscard|wipefs)\b|erase\s+block"""), Risk.HIGH),
        // 7. 写 /sys/block 下的底层控制器节点（厂商内核魔改：触发掉盘/损坏写放大）
        Rule("写底层块控制器", Regex("""(>|tee|echo)[^\n]*/sys/block/(mmcblk|sd[a-z]|nvme|loop)"""), Risk.HIGH),
        // 8. fork炸弹（`:(){ :|:& };:` 经典形态，耗尽进程/内存，设备卡死）
        Rule("fork 炸弹", Regex("""\(\)\s*\{[^}]*\|[^}]*&[^}]*\}"""), Risk.HIGH),
        // 9. 递归 777/chmod 000 波及 /system /vendor /data 等系统目录
        Rule("递归改系统目录权限", Regex("""chmod\s+-R[^\n]*(/system|/vendor|/data|/)\b"""), Risk.HIGH),
        // 10. remount 可写系统分区后直写（mount -o remount,rw /system 等）
        Rule("remount 系统分区可写", Regex("""mount\s+-o\s+[^\s]*remount,rw[^\n]*\s/(system|vendor|product|system_ext)\b"""), Risk.HIGH),
        // 11. 写内核节点 /proc/sys 破坏性参数（关闭 fsync、写 /proc/sysrq-trigger 触发重启等）
        Rule("写内核 sysrq 强制操作", Regex("""(>|tee|echo)[^\n]*/proc/sysrq-trigger"""), Risk.HIGH),
        // 12. 关闭文件系统校验 / 强制卸载根分区（umount -f / 导致系统崩溃）
        Rule("强制卸载根分区", Regex("""umount\s+(-f|-l|--force)[^\n]*\s/(system|/|/data)"""), Risk.HIGH),
        // 13. 清空分区表 / 全盘写零（dd if=/dev/zero of=/dev/block ... 全盘覆写）
        Rule("全盘写零", Regex("""\bdd\b[^\n]*if=/dev/zero[^\n]*(of\s*=\s*/dev/)"""), Risk.HIGH),
    )

    private val extraRules = mutableListOf<Rule>()

    private val rules: List<Rule>
        get() = if (isBuiltinEnabled()) BUILTIN_RULES + extraRules else extraRules

    /** 内置危险代码库是否启用（默认开启）。关闭时仅使用用户自定义规则。 */
    fun isBuiltinEnabled(): Boolean =
        AxeronSettings.getPreferences().getBoolean(KEY_BUILTIN_ENABLED, true)

    fun setBuiltinEnabled(enabled: Boolean) {
        AxeronSettings.getPreferences().edit().putBoolean(KEY_BUILTIN_ENABLED, enabled).apply()
    }

    fun addRule(rule: Rule) { extraRules.add(rule) }
    fun clearExtraRules() { extraRules.clear() }

    /** 获取所有用户自定义规则（危险代码库），按名称去重返回副本。 */
    fun getExtraRules(): List<Rule> = extraRules.toList()

    /** 按名称删除一条自定义规则，返回是否删除成功。 */
    fun removeExtraRule(name: String): Boolean =
        extraRules.removeIf { it.name == name }

    fun analyze(cmd: String): AnalyzeResult {
        val matched = mutableListOf<AnalyzeResult.MatchedRule>()
        for (rule in rules) {
            val m = rule.pattern.find(cmd)
            if (m != null) {
                matched.add(
                    AnalyzeResult.MatchedRule(
                        name = rule.name,
                        pattern = rule.pattern.pattern,
                        matchedText = m.value,
                        risk = rule.risk,
                    )
                )
            }
        }
        val risk = when {
            matched.any { it.risk == Risk.HIGH } -> Risk.HIGH
            matched.any { it.risk == Risk.MEDIUM } -> Risk.MEDIUM
            matched.any { it.risk == Risk.LOW } -> Risk.LOW
            else -> Risk.SAFE
        }
        return AnalyzeResult(
            allow = risk != Risk.HIGH,
            risk = risk,
            matchedRules = matched,
            summary = buildSummary(matched, risk),
        )
    }

    private fun buildSummary(matched: List<AnalyzeResult.MatchedRule>, risk: Risk): String {
        if (matched.isEmpty()) return "未发现危险操作"
        return matched.joinToString("\n") { "[" + riskName(it.risk) + "] " + it.name + ": " + it.matchedText }
    }

    private fun riskName(risk: Risk): String = when (risk) {
        Risk.SAFE -> "安全"
        Risk.LOW -> "低"
        Risk.MEDIUM -> "中"
        Risk.HIGH -> "高"
    }
}
