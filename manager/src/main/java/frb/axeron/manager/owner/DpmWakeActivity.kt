package frb.axeron.manager.owner

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * `axeron-dpm` 命令的「无 UI 进程唤醒」入口。
 *
 * 背景（Android 8.0+ 后台启动限制）：
 *   模块 action.sh 在 Axeron server 派生的 shell 子进程（uid 2000）里调用
 *   `axeron-dpm`，脚本用 `am startservice` 拉起 [DpmCommandService]。
 *   但当 manager 进程处于「后台 / 已 force-stop」状态时，AMS 会拒绝从 shell
 *   发起的 Service 启动，报：
 *     "Error: app is in background uid null"（或冷启动时的 "Not found"）。
 *
 * 解决：
 *   先用 `am start` 拉起一个「透明、无 UI、立即 finish」的本 Activity，
 *   让 manager 进程进入前台可见状态（绕过后台启动限制），随后 `am startservice`
 *   复用同一进程即可正常执行 [DpmCommandService]，并把结果文件回写给脚本。
 *
 * 为什么不用主界面 AxActivity 唤醒：
 *   AxActivity 会真正渲染完整 Compose 主界面，导致模块执行时「闪一下 AxManager
 *   界面」。本 Activity 使用 Theme.Transparent.NoUI 主题，全程无任何可见 UI，
 *   是开源正式版对无闪屏体验的干净实现。
 *
 * 与 DO 身份的关系：
 *   本 Activity 只负责「进程生命周期」层面的唤醒，不参与 [DeviceOwnerPrivilege]
 *   的 Dhizuku 授权链路；真正以 DO 身份执行命令的是同进程内的
 *   [DpmCommandService]，其逻辑完全不变。
 */
class DpmWakeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无 UI：立即结束，仅起到把进程带入前台可见状态的作用。
        finish()
    }
}
