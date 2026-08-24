package org.lsposed.lspatch.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.vector.ui.store.StoreInstall
import org.matrix.vector.ui.store.StoreSettings

/**
 * LSPatch's in-memory [StoreSettings]. The shared Store screen persists its channel, muted updates
 * and store-tracked installs through this interface; LSPatch has no install-from-store pipeline and
 * no settings store for these, so they are backed by defaults that live only for the process.
 */
object LSPStoreSettings : StoreSettings {

    private val _updateChannel = MutableStateFlow("stable")
    override val updateChannel: StateFlow<String> = _updateChannel.asStateFlow()

    override val mutedUpdates: StateFlow<Set<String>> = MutableStateFlow(emptySet<String>()).asStateFlow()

    override val storeInstalls: StateFlow<Map<String, StoreInstall>> =
        MutableStateFlow(emptyMap<String, StoreInstall>()).asStateFlow()

    override fun setUpdateChannel(token: String) {
        _updateChannel.value = token
    }

    // LSPatch cannot install from the store, so there is nothing to mute.
    override fun setUpdatesMuted(packageName: String, muted: Boolean) {}
}
