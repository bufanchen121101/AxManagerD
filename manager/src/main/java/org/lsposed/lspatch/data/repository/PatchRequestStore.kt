package org.lsposed.lspatch.data.repository

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.data.model.PatchRequest
import org.lsposed.lspatch.data.model.PatchTarget
import org.lsposed.lspatch.lspApp
import java.io.File
import java.util.UUID

/**
 * Holds the [PatchRequest] a patch screen is working on, addressed by an opaque token.
 *
 * The screen takes a token, not the request itself, for two reasons. compose-destinations passes
 * navigation arguments through the back stack's `SavedStateHandle`, which is written into the saved
 * instance state Bundle -- so a request carrying apk paths and a module list would be serialised on
 * every process save, and anything `Parcelable` in it would have to stay parcelable forever. And a
 * request on disk means the configure screen can be re-entered after the process is killed, instead
 * of coming back to a `lateinit` that was never initialised.
 */
object PatchRequestStore {

    private const val TAG = "PatchRequestStore"

    /** Written by Gson so [PatchTarget]'s subtype survives the round trip. */
    private const val TARGET_KIND = "kind"

    private val gson =
        GsonBuilder()
            .registerTypeAdapter(
                PatchTarget::class.java,
                JsonSerializer<PatchTarget> { src, _, context ->
                    context.serialize(src, src::class.java).asJsonObject.apply {
                        add(TARGET_KIND, JsonPrimitive(src::class.java.simpleName))
                    }
                },
            )
            .registerTypeAdapter(
                PatchTarget::class.java,
                JsonDeserializer { json, _, context ->
                    val obj = json as JsonObject
                    val type = when (obj.get(TARGET_KIND)?.asString) {
                        "ApkFiles" -> PatchTarget.ApkFiles::class.java
                        "RecoveredOrigin" -> PatchTarget.RecoveredOrigin::class.java
                        else -> PatchTarget.InstalledApp::class.java
                    }
                    context.deserialize<PatchTarget>(obj, type)
                },
            )
            .create()

    private val dir: File
        get() = lspApp.noBackupFilesDir.resolve("patch-requests").also { it.mkdirs() }

    private fun fileFor(token: String) = dir.resolve("$token.json")

    /** Persists [request] under a fresh token and returns it. */
    suspend fun put(request: PatchRequest): String = withContext(Dispatchers.IO) {
        val token = request.token.ifBlank { UUID.randomUUID().toString() }
        val stored = if (token == request.token) request else request.copy(token = token)
        fileFor(token).writeText(gson.toJson(stored))
        token
    }

    /** Overwrites the request already stored under its own token -- used as the draft is edited. */
    suspend fun update(request: PatchRequest) = withContext(Dispatchers.IO) {
        fileFor(request.token).writeText(gson.toJson(request))
        Unit
    }

    suspend fun get(token: String): PatchRequest? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(token)
            if (!file.exists()) null else gson.fromJson(file.readText(), PatchRequest::class.java)
        }.onFailure { Log.w(TAG, "Could not read request $token", it) }.getOrNull()
    }

    suspend fun drop(token: String) = withContext(Dispatchers.IO) {
        fileFor(token).delete()
        Unit
    }

    /**
     * Drops requests older than a day.
     *
     * A request is abandoned the moment its screen is left without patching, and nothing else ever
     * deletes it. They are tiny, so the window is generous -- long enough that a request survives
     * the app being killed and reopened, short enough that they do not accumulate for the life of
     * the install.
     */
    suspend fun prune() = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }.onFailure { Log.w(TAG, "Prune failed", it) }
        Unit
    }
}
