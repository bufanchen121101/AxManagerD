package org.lsposed.lspatch.data.model

import org.lsposed.lspatch.share.PatchConfig

/**
 * The two ways an app can be patched, and the one distinction that changes how it is managed
 * afterwards.
 *
 * The patcher and the on-apk config still speak in terms of a `useManager` boolean, because that is
 * the wire format the loader reads. A boolean is a poor thing to thread through a UI, though: at
 * every call site the reader has to remember which way round it goes, and "if (useManager) modules =
 * emptyList()" reads as an implementation detail rather than as the rule it actually encodes. The
 * boolean therefore survives only where it is genuinely the format -- inside [PatchConfig] and in
 * the arguments handed to the patcher -- and everything above that speaks in modes.
 */
enum class PatchMode(val useManager: Boolean) {
    /**
     * Modules are served at runtime by the installed manager. Scope is a live decision, editable
     * without touching the apk, but the app only runs on a device where the manager is present.
     */
    Local(true),

    /**
     * Modules are baked into the apk. The app runs anywhere, with no manager -- and changing which
     * modules it carries means building and installing it again.
     */
    Integrated(false);

    companion object {
        fun of(useManager: Boolean) = if (useManager) Local else Integrated
    }
}

val PatchConfig.mode: PatchMode
    get() = PatchMode.of(useManager)
