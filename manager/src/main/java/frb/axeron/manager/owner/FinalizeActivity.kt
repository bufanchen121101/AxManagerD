package frb.axeron.manager.owner

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 设备所有者（Device Owner）最终确认 Activity。
 *
 * 作用：响应系统 Device Owner provisioning 流程中 ADMIN_POLICY_COMPLIANCE 的请求，
 * 表示本应用的管理策略已满足系统要求，同意完成激活流程。
 *
 * 该 Activity 本身不含 UI——被系统拉起后立即 setResult(RESULT_OK) 并结束，
 * 告知系统「策略合规，可继续完成 Device Owner 激活」。
 */
class FinalizeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}