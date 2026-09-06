package frb.axeron.manager.dhizuku

import android.content.ComponentName
import android.content.Context
import com.rosan.dhizuku.aidl.IDhizukuClient
import com.rosan.dhizuku.server_api.DhizukuService
import frb.axeron.manager.owner.DeviceOwnerState
import frb.axeron.server.util.Logger

/**
 * AxManagerD 的 Dhizuku 兼容 Daemon 服务实现。
 *
 * 授权校验逻辑（[checkCallingPermission]）与官方 MyDhizukuService 对齐：
 * 1. AxManagerD 必须是 Device/Profile Owner（否则拒绝所有调用）。
 * 2. 调用方 uid 必须在授权存储中有记录。
 * 3. 记录必须 allowApi=true 且 blocked=false。
 *
 * 注意：官方 Dhizuku 不做签名校验（只按 uid + allowApi 判断）。此前此处额外加了
 * 「当前签名 vs 授权时签名」严格比对，导致 InstallerX 等用 split APK / V2+V3 多签名的
 * 安装器在授权成功后仍因签名不一致而被拒（表现为 remote_transact not allowed），
 * 故移除该步以与官方行为一致。
 */
class AxManagerDhizukuService(
    context: Context,
    admin: ComponentName?,
    client: IDhizukuClient?,
) : DhizukuService(context, admin, client) {
    private val LOGGER = Logger("AxManagerDhizukuService")

    override fun checkCallingPermission(func: String?, callingUid: Int, callingPid: Int): Boolean {
        // 1. 必须是 Owner，否则不提供特权。
        //    注意：不依赖可能过期的静态缓存 DeviceOwnerState.isOwner（它在进程重启后若
        //    sync() 未被调用会停留在 false，导致 `axeron-dpm` 这类「激活后直接调用」的
        //    场景被判 not owner）。这里实时查 DPM 判断 Owner 身份，与官方 Dhizuku 一致。
        if (!isOwnerNow()) {
            LOGGER.w("checkCallingPermission: not owner, deny uid=$callingUid func=$func")
            return false
        }

        // 2. 授权记录必须存在。
        val entity = DhizukuAuthStore.get(mContext, callingUid)
        if (entity == null) {
            return false
        }

        // 3. allowApi 且未 blocked。
        if (!entity.allowApi || entity.blocked) {
            return false
        }
        return true
    }

    /**
     * 实时判断本进程是否为 Device Owner / Profile Owner（不依赖静态缓存）。
     */
    private fun isOwnerNow(): Boolean {
        return try {
            val dpm = mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager ?: return false
            val admin = mAdmin ?: DeviceOwnerState.admin
            dpm.isDeviceOwnerApp(admin.packageName) || dpm.isProfileOwnerApp(admin.packageName)
        } catch (t: Throwable) {
            LOGGER.w("isOwnerNow failed: ${t.message}")
            // 兜底：回落到静态缓存判断。
            DeviceOwnerState.isOwner
        }
    }
}