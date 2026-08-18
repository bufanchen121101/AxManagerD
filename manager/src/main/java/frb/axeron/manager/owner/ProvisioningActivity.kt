package frb.axeron.manager.owner

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 设备所有者（Device Owner）预配置 Activity。
 *
 * 作用：响应系统 Device Owner provisioning 流程中 GET_PROVISIONING_MODE 的请求，
 * 声明本应用希望成为「完全托管设备」（FULLY_MANAGED_DEVICE）的所有者。
 *
 * 该 Activity 本身不含 UI——它在系统发起 provisioning 时被拉起，
 * 立即返回 [DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE] 供系统确认。
 * 真正的激活由 `adb shell dpm set-device-owner frb.axeron.manager/.owner.DeviceOwnerReceiver`
 * 命令触发，激活结果由 [DeviceOwnerReceiver] 接收并同步。
 */
class ProvisioningActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        result.putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
        )
        setResult(RESULT_OK, result)
        finish()
    }
}