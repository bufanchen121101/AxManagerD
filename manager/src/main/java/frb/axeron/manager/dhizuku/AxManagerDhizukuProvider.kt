package frb.axeron.manager.dhizuku

import com.rosan.dhizuku.aidl.IDhizukuClient
import com.rosan.dhizuku.server_api.DhizukuProvider
import com.rosan.dhizuku.server_api.DhizukuService
import frb.axeron.manager.owner.DeviceOwnerState

/**
 * AxManagerD 的 Dhizuku 兼容 Daemon ContentProvider。
 *
 * authority 必须是 `${applicationId}.dhizuku_server.provider`（即
 * `frb.axeron.manager.dhizuku_server.provider`），这样第三方 app 的 Dhizuku API 客户端在
 * AxManagerD 为 Device Owner 时会自动连到这个 Provider（见 DhizukuVariables.getProviderAuthorityName）。
 */
class AxManagerDhizukuProvider : DhizukuProvider() {
    override fun onCreateService(client: IDhizukuClient): DhizukuService {
        return AxManagerDhizukuService(context!!, DeviceOwnerState.admin, client)
    }
}