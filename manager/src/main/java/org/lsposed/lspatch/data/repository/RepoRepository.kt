package org.lsposed.lspatch.data.repository

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.matrix.vector.ui.store.OnlineModule
import org.matrix.vector.ui.store.RepoVersion
import org.matrix.vector.ui.store.StoreCatalog
import org.matrix.vector.ui.store.StoreDataSource
import org.lsposed.lspatch.util.LSPPackageManager
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The Store's data: the online catalogue, and what this device already has of it.
 *
 * A no-OkHttp, no-daemon port of Vector's RepoRepository. The full `modules.json` is served only by
 * `backup.modules.lsposed.org`; `modules.lsposed.org` answers that path with a 403, but does serve
 * per-module `module/<package>.json`, so the public host is a genuine fallback for detail only.
 *
 * Networking is plain [HttpURLConnection] on [Dispatchers.IO]; parsing is Gson, streamed entry by
 * entry so one malformed module costs that module rather than the whole catalogue.
 */
class RepoRepository private constructor(context: Context) : StoreDataSource {

    private val appContext = context.applicationContext
    private val gson = Gson()

    /** Own scope so [refreshInstalled] can re-read versions without a caller's coroutine. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _catalog = MutableStateFlow(StoreCatalog())
    override val catalog: StateFlow<StoreCatalog> = _catalog.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _installed = MutableStateFlow<Map<String, RepoVersion>>(emptyMap())

    /** What each installed package on this device is at, keyed by package name. */
    override val installedVersions: StateFlow<Map<String, RepoVersion>> = _installed.asStateFlow()

    /** Held for the length of a refresh; a second caller is a no-op rather than a queued duplicate. */
    private val refreshing = Mutex()

    /**
     * Reloads the catalogue from the network, then re-reads installed versions. LSPatch has no
     * on-disk catalogue cache, so [force] is accepted for the [StoreDataSource] contract but a
     * refresh always reloads regardless.
     */
    override suspend fun refresh(force: Boolean) {
        if (!refreshing.tryLock()) return
        try {
            _isRefreshing.value = true
            withContext(Dispatchers.IO) {
                loadInstalled()
                val fetched = LIST_MIRRORS.firstNotNullOfOrNull { fetchCatalog(it) }
                when {
                    fetched != null -> _catalog.value = fetched
                    // Nothing on the network: `loaded` still flips so the screen can say the
                    // repository is unreachable instead of spinning forever. A previously loaded
                    // catalogue is kept rather than wiped.
                    else -> _catalog.value = _catalog.value.copy(loaded = true)
                }
            }
        } finally {
            _isRefreshing.value = false
            refreshing.unlock()
        }
    }

    /**
     * The full record for one module: its README, and every release rather than only the newest.
     * Returns null when no mirror answers; callers fall back to the catalogue entry they hold.
     */
    override suspend fun details(packageName: String): OnlineModule? =
        withContext(Dispatchers.IO) {
            DETAIL_MIRRORS.firstNotNullOfOrNull { fetchDetails(it, packageName) }
        }

    /** Re-read installed versions from the device (e.g. after an install/uninstall). */
    override fun refreshInstalled() {
        scope.launch { loadInstalled() }
    }

    private fun fetchCatalog(baseUrl: String): StoreCatalog? {
        val url = baseUrl + "modules.json"
        return try {
            open(url) { reader ->
                val parsed = parseCatalog(reader)
                if (parsed.isEmpty()) return@open null
                Log.i(TAG, "store: ${parsed.size} modules from $url")
                StoreCatalog(
                    modules = usable(parsed),
                    loaded = true,
                    loadedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "store: $url unavailable", e)
            null
        }
    }

    private fun fetchDetails(baseUrl: String, packageName: String): OnlineModule? {
        val url = "${baseUrl}module/$packageName.json"
        return try {
            open(url) { reader -> gson.fromJson(reader, OnlineModule::class.java) }
        } catch (e: Exception) {
            Log.w(TAG, "store: $url unavailable", e)
            null
        }
    }

    /**
     * Opens [url], hands the decoded (possibly gzip) body to [block] as a reader, and always closes.
     * Returns null on any non-2xx response.
     */
    private fun <T> open(url: String, block: (JsonReader) -> T?): T? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "store: $url returned HTTP ${connection.responseCode}")
                return null
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use(block)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Reads the catalogue one entry at a time, and survives a bad one: binding the whole array in a
     * single call would fail the entire Store on one unexpected field.
     */
    private fun parseCatalog(reader: JsonReader): List<OnlineModule> {
        val modules = ArrayList<OnlineModule>(1024)
        var rejected = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val element = JsonParser.parseReader(reader)
            val module = runCatching { gson.fromJson(element, OnlineModule::class.java) }
            if (module.isSuccess) module.getOrNull()?.let(modules::add) else rejected++
        }
        reader.endArray()
        if (rejected > 0) Log.w(TAG, "store: skipped $rejected unreadable entries")
        return modules
    }

    /** Hidden entries and entries with no release dropped; duplicate package names collapsed. */
    private fun usable(parsed: List<OnlineModule>): List<OnlineModule> =
        parsed.asSequence()
            .filter { it.hide != true }
            .filter { !it.releases.isNullOrEmpty() }
            .distinctBy { it.name }
            .toList()

    /**
     * Installed versions, keyed by package name, read from LSPatch's own package source
     * ([LSPPackageManager.appList]) with versions resolved from the platform.
     */
    private suspend fun loadInstalled() {
        if (LSPPackageManager.appList.isEmpty()) {
            runCatching { LSPPackageManager.fetchAppList() }
        }
        val pm = appContext.packageManager
        val versions = HashMap<String, RepoVersion>()
        LSPPackageManager.appList.forEach { appInfo ->
            val pkg = appInfo.app.packageName
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@forEach
            versions[pkg] = RepoVersion(PackageInfoCompat.getLongVersionCode(info), info.versionName.orEmpty())
        }
        _installed.value = versions
    }

    companion object {
        private const val TAG = "RepoRepository"
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "LSPatch-Manager"

        /** The only host serving the full list. */
        private val LIST_MIRRORS = listOf("https://backup.modules.lsposed.org/")

        /** Detail is served by both hosts, so here the public site is a genuine fallback. */
        private val DETAIL_MIRRORS =
            listOf("https://backup.modules.lsposed.org/", "https://modules.lsposed.org/")

        @Volatile
        private var instance: RepoRepository? = null

        /** Process-wide singleton, so the catalogue survives ViewModel recreation on tab switches. */
        fun getInstance(context: Context): RepoRepository =
            instance ?: synchronized(this) {
                instance ?: RepoRepository(context).also { instance = it }
            }
    }
}
