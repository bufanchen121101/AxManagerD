package frb.axeron.manager.owner

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import frb.axeron.server.util.Logger

/**
 * 设备所有者（Device Owner）激活接收器。
 *
 * 这是 AxManager 引入 Dhizuku 机制的核心入口：
 * 通过 `adb shell dpm set-device-owner frb.axeron.manager/.owner.DeviceOwnerReceiver`
 * 激活后，本 App 即成为非 root 情况下的 Device Owner，从而获得
 * 远超单纯 ADB shell 的系统级能力。
 *
 * 说明：
 * - 该 receiver 在 manifest 中需声明 android:permission="android.permission.BIND_DEVICE_ADMIN"
 *   且 meta-data 指向 device_owner_admin.xml。
 * - 激活成功后，会通过 [DeviceOwnerState] 同步状态，便于 server 侧判断运行身份。
 */
class DeviceOwnerReceiver : DeviceAdminReceiver() {
    companion object {
        private val LOGGER = Logger("DeviceOwnerReceiver")

        // hidden API 常量值（android.app.admin.DevicePolicyManager 的 @SystemApi 常量，
        // 标准 SDK 不可见，硬编码规避）
        private const val ACTION_DEVICE_OWNER_CHANGED = "android.app.action.DEVICE_OWNER_CHANGED"
        private const val ACTION_PROFILE_OWNER_CHANGED = "android.app.action.PROFILE_OWNER_CHANGED"

        @JvmStatic
        fun componentName(): ComponentName = DeviceOwnerState.admin

        /**
         * 【v1.4.6】带 Context 的重载：优先用传入 Context 的真实包名
         * （`manages` 变体的 applicationId 是 `frb.axerond.manages`，
         *  不能再用硬编码的 `frb.axeron.manager`）。
         */
        @JvmStatic
        fun componentName(context: Context): ComponentName =
            ComponentName(context.packageName, DeviceOwnerReceiver::class.java.name)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        // 【v1.9.0 闪退加固】onEnabled 会在「DO 激活成功」瞬间由系统回调；
        // 一旦这里抛出未捕获异常，进程会直接闪退（用户看到「激活成功但闪退」）。
        // 因此整体包 runCatching —— 同步失败只影响权限预授予，不影响激活结果。
        super.onEnabled(context, intent)
        LOGGER.i("Device admin enabled")
        // 通知状态管理器同步 Device Owner / Profile Owner 状态
        runCatching { DeviceOwnerState.sync(context) }
            .onFailure { LOGGER.w("sync failed in onEnabled", it) }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        LOGGER.i("Device admin disabled")
        runCatching { DeviceOwnerState.sync(context) }
            .onFailure { LOGGER.w("sync failed in onDisabled", it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        // 当 Device Owner / Profile Owner 身份发生转移时（不会触发 onEnabled/onDisabled），
        // 需在此重新同步身份状态。（onEnabled/onDisabled 已覆盖激活/取消场景）
        // 注：ACTION_DEVICE_OWNER_CHANGED / ACTION_PROFILE_OWNER_CHANGED 属于
        // android.app.admin.DevicePolicyManager 的 hidden API（@SystemApi），标准 SDK
        // 及 Rikka hidden stub 中均不可见，故此处硬编码其字符串值以规避编译期不确定性。
        when (action) {
            ACTION_DEVICE_OWNER_CHANGED,
            ACTION_PROFILE_OWNER_CHANGED -> {
                LOGGER.i("device owner state changed: $action")
                // 【v1.9.0 闪退加固】转移广播同样在系统回调线程执行，异常即闪退。
                runCatching { DeviceOwnerState.sync(context) }
                    .onFailure { LOGGER.w("sync failed in onReceive", it) }
            }
        }
    }
}