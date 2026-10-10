package frb.axeron.manager.shizuku

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 【v1.3.1】Shizuku binder **主动拉取**兼容层（修复「只能用 Stellar 授权」）。
 *
 * ## 背景（本机实测 + 反编译取证）
 *
 * Shizuku 系管理器（原版 Shizuku / Stellar）给客户端下发服务 binder 走的是
 * **推送**模式：管理器监听 UID / 进程状态变化，在其认为「客户端 App 启动」时
 * 调用 `getContentProviderExternal("<包名>.shizuku")`，再把 binder 塞进我们
 * manifest 里声明的 `rikka.shizuku.ShizukuProvider`。
 *
 * 推送模式有两个先天缺口，这正是「部分用户看不到授权入口」的原因：
 *
 * 1. 推送只在 UID 发生 active / idle / cached **状态跃迁**时触发一次；
 *    若那次触发正好赶在我们 App 冷启动、provider 尚未 attach（管理器会
 *    `forceStopPackage` 后重试一次，仍失败就彻底放弃），本次开机就不会再送；
 * 2. 同时装了原版 Shizuku 与 Stellar 时，两者各自跑一套 UID 观察者，
 *    先到者写进 `Shizuku` 静态 binder，后来者被
 *    `ShizukuProvider.handleSendBinder()` 的 `if (Shizuku.pingBinder()) return;`
 *    直接丢弃；先到那台的 server 若随后退出，通道即断且不会再重推。
 *
 * ## 修法：改用「主动拉取」（不是新协议，是管理器已公开的既有入口）
 *
 * 原版 Shizuku 的 manifest 里有一个 **exported 且无权限保护** 的接收器：
 *
 * ```xml
 * <receiver android:name="moe.shizuku.manager.receiver.ShizukuReceiver"
 *           android:exported="true">
 *     <intent-filter>
 *         <action android:name="rikka.shizuku.intent.action.REQUEST_BINDER" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * 其处理逻辑（反编译 `rikka/shizuku/An.smali#g` 逐行核对）：
 *
 * ```java
 * Bundle data = intent.getBundleExtra("data");
 * IBinder cb  = data.getBinder("binder");          // 调用方提供的回调 binder
 * Parcel p = Parcel.obtain();
 * p.writeStrongBinder(Shizuku.getBinder());        // ← 管理器把服务 binder 写进来
 * p.writeString(context.getApplicationInfo().sourceDir);
 * cb.transact(1, p, null, IBinder.FLAG_ONEWAY);    // 单向往回调 transact 回去
 * ```
 *
 * 也就是说：**任何应用都可以用这个公开接收器向管理器「要」一次 binder**，
 * 管理器会用 shell/root 身份把它的服务 binder 回写给我们。这不是提权、不是
 * 漏洞利用 —— 拿到 binder 之后依然要照常走 `Shizuku.requestPermission()`
 * 由用户点「允许」，与推送模式下完全一致。
 *
 * ## 实现要点
 *
 * - 回调 binder 用最朴素的 [Binder] + `onTransact(1, ...)`，只读一个 strong binder；
 * - 拉到后统一交给 [Shizuku.onBinderReceived]，因此**后续所有逻辑
 *   （attach、checkSelfPermission、requestPermission、newProcess）一行都不用改**，
 *   原版 Shizuku 与 Stellar 共用同一条通道；
 * - 只在「当前没有可用 binder」时才发广播，并用 [MIN_INTERVAL_MS] 限流，
 *   避免页面 onResume 频繁触发时刷屏；
 * - 管理器未安装 / 广播被拦截时静默失败（返回 false），**不影响**原有推送通道。
 *
 * 【隔离约定】本文件为新增文件，不修改 `rikka.shizuku.*`、
 * `ShizukuProvider`、`ShizukuApi` 等任何既有实现的既有行为。
 */
object ShizukuBinderPuller {

    private const val TAG = "ShizukuBinderPuller"

    /** 原版 Shizuku 管理器里公开的「请求 binder」广播 action（实测其 manifest exported=true）。 */
    const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"

    /** 原版 Shizuku 管理器包名（Stellar 走的是自己的推送通道，无需本层）。 */
    const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    /** 管理器要求的 extras 键名（反编译确认，勿改）。 */
    private const val EXTRA_DATA = "data"

    /** 管理器要求的回调 binder 键名（反编译确认，勿改）。 */
    private const val EXTRA_BINDER = "binder"

    /** 管理器回写用的 transact code（反编译确认，勿改）。 */
    private const val TRANSACTION_BINDER_REPLY = 1

    /** 两次拉取之间的最小间隔，避免频繁广播。 */
    private const val MIN_INTERVAL_MS = 1500L

    @Volatile
    private var lastPullAt = 0L

    /**
     * 尝试主动向原版 Shizuku 管理器拉取一次服务 binder。
     *
     * 本方法**不会阻塞等待结果**：管理器是异步 `transact` 回写的，binder 到达后
     * 由 [Shizuku.onBinderReceived] 触发既有监听链路。调用方如需立刻用到 binder，
     * 应在调用后稍等再 `Shizuku.pingBinder()`（见 `ActivateViewModel.refreshShizukuState`）。
     *
     * @param context 任意 Context（内部只用其 `sendBroadcast` 与包名）
     * @return 是否真的发出了拉取广播（已有 binder / 管理器未安装 / 被限流时为 false）
     */
    fun pull(context: Context): Boolean {
        // ① 已经有活着的 binder：无需拉取（推送通道已生效）
        if (Shizuku.pingBinder()) return false

        // ② 限流：同一秒内多次 onResume 只发一次
        val now = System.currentTimeMillis()
        if (now - lastPullAt < MIN_INTERVAL_MS) return false
        lastPullAt = now

        // ③ 管理器未安装则直接返回，避免无意义的广播
        if (!isPackageInstalled(context, SHIZUKU_MANAGER_PACKAGE)) {
            Log.d(TAG, "未安装 $SHIZUKU_MANAGER_PACKAGE，跳过主动拉取")
            return false
        }

        return runCatching {
            val packageName = context.packageName
            // 管理器会用 transact(1) 回写服务 binder —— 我们只在这里接住它
            val callback = object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int
                ): Boolean {
                    if (code == TRANSACTION_BINDER_REPLY) {
                        val binder = runCatching { data.readStrongBinder() }.getOrNull()
                        if (binder != null) {
                            Log.i(TAG, "主动拉取成功：收到 Shizuku 服务 binder")
                            // 交给官方 API 的既有入口，后续状态机完全复用
                            runCatching { Shizuku.onBinderReceived(binder, packageName) }
                                .onFailure { Log.w(TAG, "onBinderReceived 失败", it) }
                        } else {
                            Log.w(TAG, "管理器回写的 binder 为 null（服务可能未启动）")
                        }
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val data = Bundle().apply { putBinder(EXTRA_BINDER, callback) }
            val intent = Intent(ACTION_REQUEST_BINDER)
                .setPackage(SHIZUKU_MANAGER_PACKAGE)
                .putExtra(EXTRA_DATA, data)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(intent)
            Log.i(TAG, "已发送 REQUEST_BINDER 广播给 $SHIZUKU_MANAGER_PACKAGE")
            true
        }.onFailure {
            // 广播被 ROM 拦截 / 管理器版本过旧不处理：静默降级，推送通道仍然有效
            Log.w(TAG, "主动拉取广播发送失败", it)
        }.getOrDefault(false)
    }

    /** 管理器是否安装。任意异常都按「未安装」处理，不影响主流程。 */
    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
