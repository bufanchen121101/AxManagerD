package frb.axeron.manager.features.overlay

/**
 * 安装期「核心文件修改」授权意向（第三期）。
 *
 * 为什么需要它：
 *  安装确认弹窗（`InstallDialog`）与安装执行页（`FlashScreen`）之间只通过
 *  `FlashIt` / `PluginInstaller` 传递数据，而这些对象是 `Parcelable`，
 *  经导航参数往返会丢字段（项目已有先例：`PluginInstaller.runtimeModule`
 *  实测恒为 false，只好用 ViewModel 的进程内标志冗余传递）。
 *
 *  授权意向同样**不能**放在 Parcel 里，因此这里用一个进程内单例暂存：
 *    - 用户在安装弹窗里勾选了「安装时授权」→ [agree]
 *    - 安装成功后 `FlashScreen` 取走并写授权 → [takeAll]
 *    - 取消安装 / 离开安装页 → [clear]
 *
 * 进程被杀则该意向自然失效（下次安装会重新询问），符合「明示同意」的语义。
 */
object OverlayInstallConsent {

    private val consented = mutableSetOf<String>()

    /** 记录「用户同意为该模块授权核心文件修改」。 */
    @Synchronized
    fun agree(moduleId: String) {
        if (moduleId.isNotBlank()) consented.add(moduleId)
    }

    /** 撤销某模块的安装期意向。 */
    @Synchronized
    fun disagree(moduleId: String) {
        consented.remove(moduleId)
    }

    /** 当前已同意的模块 id（只读快照）。 */
    @Synchronized
    fun snapshot(): Set<String> = consented.toSet()

    /** 取出并清空（安装成功后调用，避免重复授权）。 */
    @Synchronized
    fun takeAll(): Set<String> {
        val s = consented.toSet()
        consented.clear()
        return s
    }

    /** 清空（取消安装 / 离开安装页）。 */
    @Synchronized
    fun clear() {
        consented.clear()
    }
}