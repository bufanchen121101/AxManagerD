package org.lsposed.lspatch.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The [Activity] behind a possibly-wrapped context, or null.
 *
 * `LocalContext.current` is not always the activity: once an in-app language override is active the
 * tree runs under a `LocalizedContext` (a [ContextWrapper]) whose base is the activity, so a blind
 * `context as Activity` throws once a language is chosen. Walking the wrapper chain finds it either
 * way.
 */
fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
