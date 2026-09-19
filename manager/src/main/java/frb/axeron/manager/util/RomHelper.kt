package frb.axeron.manager.util

import android.os.Build
import android.os.SystemProperties
import rikka.compatibility.DeviceCompatibility

/**
 * 厂商 ROM 判定工具。
 *
 * 为什么不用 `Build.MANUFACTURER` 单点判断：国产 ROM 的 `ro.product.brand`
 * 与 `ro.product.manufacturer` 经常不一致（例如一加写成 `OnePlus` 但跑 ColorOS、
 * 荣耀独立后仍复用华为的 systemmanager 组件），因此这里以 `ro.build.*` 系列
 * 属性为主、`Build.MANUFACTURER` 为辅做联合判定。
 *
 * 与既有 [rikka.compatibility.DeviceCompatibility] 的关系：那边只提供
 * `isMiui()` 一个布尔，不足以区分 ColorOS / OriginOS / Flyme，所以这里另建
 * 一个覆盖面更全的判定器，供 [AutoStartHelper] 跳转自启动白名单页使用。
 */
object RomHelper {

    enum class Rom {
        MIUI,       // 小米 / 红米（MIUI / HyperOS）
        EMUI,       // 华为 / 荣耀（EMUI / HarmonyOS）
        COLOR_OS,   // OPPO / 一加 / realme（ColorOS）
        VIVO,       // vivo / iQOO（OriginOS / FuntouchOS）
        FLYME,      // 魅族（Flyme）
        OTHER       // 类原生 / 其它
    }

    /** 读取系统属性，失败返回空串。 */
    private fun prop(key: String): String =
        runCatching { SystemProperties.get(key, "") }.getOrDefault("")

    private val manufacturer: String
        get() = (Build.MANUFACTURER ?: "").lowercase()

    private val brand: String
        get() = (Build.BRAND ?: "").lowercase()

    /** 当前 ROM 类型（每次调用实时判定，结果很轻量，不需要缓存）。 */
    fun romType(): Rom {
        // 小米：ro.miui.ui.version.name / ro.miui.ui.version.code，或 brand=xiaomi/redmi
        if (prop("ro.miui.ui.version.name").isNotEmpty() ||
            prop("ro.miui.ui.version.code").isNotEmpty() ||
            brand == "xiaomi" || brand == "redmi" || brand == "poco"
        ) {
            return Rom.MIUI
        }

        // 华为 / 荣耀：ro.build.version.emui / hw_sc.build.platform.version
        if (prop("ro.build.version.emui").isNotEmpty() ||
            prop("hw_sc.build.platform.version").isNotEmpty() ||
            manufacturer == "huawei" || brand == "huawei" || brand == "honor"
        ) {
            return Rom.EMUI
        }

        // OPPO / 一加 / realme：ro.build.version.opporom / ro.oppo.theme.version
        if (prop("ro.build.version.opporom").isNotEmpty() ||
            prop("ro.oppo.theme.version").isNotEmpty() ||
            prop("ro.build.version.oplusrom").isNotEmpty() ||
            brand == "oppo" || brand == "oneplus" || brand == "realme"
        ) {
            return Rom.COLOR_OS
        }

        // vivo / iQOO：ro.vivo.os.version / ro.vivo.rom.version
        if (prop("ro.vivo.os.version").isNotEmpty() ||
            prop("ro.vivo.rom.version").isNotEmpty() ||
            brand == "vivo" || brand == "iqoo"
        ) {
            return Rom.VIVO
        }

        // 魅族：ro.build.display.id 含 Flyme，或 brand=meizu
        if (prop("ro.build.version.flyme").isNotEmpty() ||
            prop("ro.flyme.version").isNotEmpty() ||
            brand == "meizu"
        ) {
            return Rom.FLYME
        }

        return Rom.OTHER
    }

    /** 是否小米系（含 HyperOS）。 */
    fun isMiui(): Boolean = romType() == Rom.MIUI

    /** 是否华为 / 荣耀系。 */
    fun isEmui(): Boolean = romType() == Rom.EMUI

    /** 是否 OPPO / 一加 / realme 系。 */
    fun isColorOs(): Boolean = romType() == Rom.COLOR_OS

    /** 是否 vivo / iQOO 系。 */
    fun isVivo(): Boolean = romType() == Rom.VIVO

    /** 是否魅族系。 */
    fun isFlyme(): Boolean = romType() == Rom.FLYME

    /** 是否已知的、会拦截开机广播的国产 ROM。 */
    fun isAggressiveRom(): Boolean = romType() != Rom.OTHER
}
