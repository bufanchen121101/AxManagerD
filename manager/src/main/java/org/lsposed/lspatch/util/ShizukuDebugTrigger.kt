package org.lsposed.lspatch.util

import android.content.Context

/**
 * The release twin of the debug build's Shizuku failure trigger: nothing.
 *
 * It exists so the call site in the application can stay unconditional while the receiver — which exists only to fake
 * failures — is absent from a shipped build rather than merely unreachable.
 */
object ShizukuDebugTrigger {

    fun register(context: Context) = Unit
}
