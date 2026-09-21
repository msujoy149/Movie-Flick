package com.movieflick.moviebox

import android.content.Context
import dalvik.system.DexClassLoader
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Movie-Flick unified MovieBox facade.
 *
 * Important design decision:
 * - We do NOT reimplement the MovieBox backend here.
 * - The two proven MovieBox providers are bundled unchanged as DEX assets.
 * - CloudStream registers only this one facade.
 * - Search calls both backends and merges the results.
 * - Home/load/loadLinks use global first and transparently fail over to IN.
 *
 * This keeps the existing Movie-Flick repository interface small while using
 * the proven MovieBox implementations for search, metadata, seasons, episodes,
 * streams and subtitles.
 */
class MovieBox(private val appContext: Context) : MainAPI() {

    override var mainUrl: String = "https://movieboxonline.net"
    override var name: String = "MovieBox"
    override var lang: String = "ta"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * These values are resolved from the proven global provider at runtime.
     * Keeping this lazy means plugin installation itself does not perform any
     * network work.
     */
    override val mainPage: List<MainPageData> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        firstWorkingDelegate()?.let { delegate ->
            readProperty(delegate.provider, "getMainPage") as? List<MainPageData>
        } ?: emptyList()
    }

    private data class Delegate(
        val id: Int,
        val className: String,
        val assetName: String,
        val dexName: String,
        val loader: ClassLoader,
        val provider: MainAPI
    )

    private data class LoadedAsset(
        val dexFile: File,
        val optimizedDir: File
    )

    private val ownerByUrl = ConcurrentHashMap<String, Int>()

    /**
     * Data strings are often episode-specific.  We cannot rely on their format
     * because that belongs to the delegated provider, so we remember which
     * backend produced the last successful load and still keep the opposite
     * backend as the runtime fallback.
     */
    private val ownerByLoadedUrl = ConcurrentHashMap<String, Int>()

    private val delegates: List<Delegate?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        listOf(
            buildDelegate(
                id = 0,
                className = GLOBAL_CLASS,
                assetName = GLOBAL_ASSET,
                dexName = "moviebox_global.dex"
            ),
            buildDelegate(
                id = 1,
                className = INDIA_CLASS,
                assetName = INDIA_ASSET,
                dexName = "moviebox_in.dex"
            )
        )
    }

    private companion object {
        const val GLOBAL_CLASS = "com.cncverse.MovieBoxProvider"
        const val INDIA_CLASS = "com.cncverse.MovieBoxProviderIN"

        const val GLOBAL_ASSET = "moviebox_global.bin"
        const val INDIA_ASSET = "moviebox_in.bin"

        const val MAX_SEARCH_RESULTS = 100
        const val DEFAULT_BUFFER = 16 * 1024
    }

    // ---------------------------------------------------------------------
    // CloudStream entry points
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val order = preferredOrderForRequest(request.name)

        for (id in order) {
            val delegate = delegateAt(id) ?: continue
            val response = runCatching {
                invokeSuspend(
                    delegate.provider,
                    findSuspendMethod(delegate.provider, "getMainPage", 2)
                        ?: return@runCatching null,
                    arrayOf(page, request)
                ) as? HomePageResponse
            }.getOrNull()

            if (response != null && response.items.any { it.list.isNotEmpty() }) {
                rememberHomeOwners(response, id)
                return response
            }
        }

        return null
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return mergedSearch(query, quick = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return mergedSearch(query, quick = true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val hinted = ownerByUrl[url]
        val order = hinted?.let { listOf(it, 1 - it) } ?: listOf(0, 1)

        for (id in order) {
            val delegate = delegateAt(id) ?: continue
            val response = runCatching {
                invokeSuspend(
                    delegate.provider,
                    findSuspendMethod(delegate.provider, "load", 1)
                        ?: return@runCatching null,
                    arrayOf(url)
                ) as? LoadResponse
            }.getOrNull()

            if (response != null) {
                ownerByLoadedUrl[response.url] = id
                // Some CloudStream providers rewrite the URL during load().
                ownerByUrl[response.url] = id
                return response
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hinted = ownerByLoadedUrl[data]
        val order = hinted?.let { listOf(it, 1 - it) } ?: listOf(0, 1)

        for (id in order) {
            val delegate = delegateAt(id) ?: continue

            val collectedLinks = mutableListOf<ExtractorLink>()
            val collectedSubs = mutableListOf<SubtitleFile>()

            val success = runCatching {
                invokeSuspend(
                    delegate.provider,
                    findSuspendMethod(delegate.provider, "loadLinks", 4)
                        ?: return@runCatching false,
                    arrayOf(
                        data,
                        isCasting,
                        { subtitle: SubtitleFile ->
                            collectedSubs.add(subtitle)
                            Unit
                        },
                        { link: ExtractorLink ->
                            collectedLinks.add(link)
                            Unit
                        }
                    )
                ) as? Boolean ?: false
            }.getOrDefault(false)

            /**
             * Treat a successful invocation with zero links as a failed
             * resolution. This is important for transparent fallback.
             */
            if (success && collectedLinks.isNotEmpty()) {
                collectedSubs.forEach(subtitleCallback)
                collectedLinks
                    .distinctBy { it.url }
                    .forEach(callback)
                return true
            }

            if (collectedLinks.isNotEmpty()) {
                collectedSubs.forEach(subtitleCallback)
                collectedLinks.distinctBy { it.url }.forEach(callback)
                return true
            }
        }

        return false
    }

    // ---------------------------------------------------------------------
    // Unified search
    // ---------------------------------------------------------------------

    private suspend fun mergedSearch(
        query: String,
        quick: Boolean
    ): List<SearchResponse>? = coroutineScope {
        val jobs = delegates.mapIndexedNotNull { index, delegate ->
            delegate ?: return@mapIndexedNotNull null

            async(Dispatchers.IO) {
                val method = findSuspendMethod(
                    delegate.provider,
                    if (quick) "quickSearch" else "search",
                    1
                ) ?: return@async index to emptyList<SearchResponse>()

                val result = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    invokeSuspend(
                        delegate.provider,
                        method,
                        arrayOf(query)
                    ) as? List<SearchResponse>
                }.getOrNull().orEmpty()

                index to result
            }
        }

        val resultsByDelegate = jobs.awaitAll()

        val merged = resultsByDelegate
            .flatMap { (owner, results) ->
                results.map { result ->
                    ownerByUrl[result.url] = owner
                    owner to result
                }
            }
            .distinctBy { it.second.url }
            .sortedWith(
                compareByDescending<Pair<Int, SearchResponse>> {
                    searchRelevance(query, it.second.name)
                }.thenBy {
                    languageRank(it.second.name)
                }.thenBy { it.second.name.lowercase() }
            )
            .take(MAX_SEARCH_RESULTS)
            .map { it.second }

        merged.ifEmpty { null }
    }

    /**
     * The old providers already perform provider-side matching/normalization.
     * This second-stage score only merges the two proven result sets without
     * replacing their own search engine.
     */
    private fun searchRelevance(query: String, title: String): Int {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)

        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 1000
        if (t.startsWith(q)) return 900
        if (t.contains(q)) return 800

        val qTokens = q.split(' ').filter { it.length >= 2 }
        if (qTokens.isEmpty()) return 100

        val matched = qTokens.count { token -> t.contains(token) }
        return 100 + (matched * 100 / qTokens.size)
    }

    private fun languageRank(title: String): Int {
        val lower = title.lowercase()
        return when {
            Regex("(?:\\[|\\(|\\s)(hindi|hin)(?:\\]|\\)|\\s|$)").containsMatchIn(lower) -> 0
            Regex("(?:\\[|\\(|\\s)(bengali|bangla|ben)(?:\\]|\\)|\\s|$)").containsMatchIn(lower) -> 2
            Regex("(?:\\[|\\(|\\s)(tamil|telugu|malayalam|kannada|arabic|spanish|french|russian)(?:\\]|\\)|\\s|$)").containsMatchIn(lower) -> 3
            else -> 1
        }
    }

    private fun normalizeSearch(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ---------------------------------------------------------------------
    // Delegate lifecycle / loading
    // ---------------------------------------------------------------------

    private fun firstWorkingDelegate(): Delegate? {
        return delegateAt(0) ?: delegateAt(1)
    }

    private fun delegateAt(id: Int): Delegate? = delegates.getOrNull(id)

    private fun preferredOrderForRequest(requestName: String): List<Int> {
        // Global provider is the stable default because it contains the full
        // category layout seen in the original MovieBox implementation. IN is
        // always retained as transparent runtime fallback.
        return listOf(0, 1)
    }

    private fun rememberHomeOwners(response: HomePageResponse, owner: Int) {
        response.items.forEach { homeList ->
            homeList.list.forEach { result ->
                ownerByUrl[result.url] = owner
            }
        }
    }

    private fun buildDelegate(
        id: Int,
        className: String,
        assetName: String,
        dexName: String
    ): Delegate? {
        return runCatching {
            val loaded = extractDex(assetName, dexName)
            val classLoader = DexClassLoader(
                loaded.dexFile.absolutePath,
                loaded.optimizedDir.absolutePath,
                null,
                appContext.classLoader
            )

            val clazz = classLoader.loadClass(className)
            val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
            val instance = constructor.newInstance()
            require(instance is MainAPI) {
                "$className is not a CloudStream MainAPI"
            }

            // The original provider's init() normally gets called by the
            // CloudStream plugin manager. Because we are delegating to it from
            // one facade, explicitly initialise the embedded provider.
            instance.init()

            Delegate(
                id = id,
                className = className,
                assetName = assetName,
                dexName = dexName,
                loader = classLoader,
                provider = instance
            )
        }.getOrNull()
    }

    private fun extractDex(assetName: String, dexName: String): LoadedAsset {
        val optimizedDir = File(appContext.cacheDir, "moviebox-dex-opt")
        if (!optimizedDir.exists()) optimizedDir.mkdirs()

        val dexFile = File(optimizedDir, dexName)

        // The assets are bundled in this plugin with the original .cs3 ZIP
        // bytes. Extract only classes.dex for DexClassLoader.
        appContext.assets.open(assetName).use { assetInput ->
            val temp = File(optimizedDir, "$dexName.tmp")
            FileOutputStream(temp).use { output ->
                assetInput.copyTo(output, DEFAULT_BUFFER)
                output.fd.sync()
            }

            ZipFile(temp).use { zip ->
                val entry = zip.getEntry("classes.dex")
                    ?: error("$assetName does not contain classes.dex")
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(dexFile).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER)
                        output.fd.sync()
                    }
                }
            }

            temp.delete()
        }

        return LoadedAsset(
            dexFile = dexFile,
            optimizedDir = optimizedDir
        )
    }

    // ---------------------------------------------------------------------
    // Reflection helpers for Kotlin suspend methods
    // ---------------------------------------------------------------------

    private fun findSuspendMethod(
        target: Any,
        name: String,
        explicitParameterCount: Int
    ): Method? {
        return target.javaClass.methods.firstOrNull { method ->
            method.name == name &&
                method.parameterTypes.size == explicitParameterCount + 1 &&
                Continuation::class.java.isAssignableFrom(method.parameterTypes.last())
        }
    }

    private suspend fun invokeSuspend(
        target: Any,
        method: Method,
        args: Array<Any?>
    ): Any? = suspendCancellableCoroutine { continuation ->
        val bridge = object : Continuation<Any?> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<Any?>) {
                if (continuation.isCompleted) return
                result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) }
                )
            }
        }

        try {
            val fullArgs = args + bridge
            val result = method.invoke(target, *fullArgs)

            if (result !== COROUTINE_SUSPENDED && !continuation.isCompleted) {
                continuation.resume(result)
            }
        } catch (error: InvocationTargetException) {
            if (!continuation.isCompleted) {
                continuation.resumeWithException(error.targetException ?: error)
            }
        } catch (error: Throwable) {
            if (!continuation.isCompleted) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun readProperty(target: Any, getterName: String): Any? {
        return runCatching {
            target.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterTypes.isEmpty()
            }?.invoke(target)
        }.getOrNull()
    }

    private fun <T> List<T>.mapIndexedNotNullSafe(
        block: (Int, T?) -> Pair<Int, T>?
    ): List<Pair<Int, T>> {
        val output = ArrayList<Pair<Int, T>>(size)
        for (index in indices) {
            output += block(index, getOrNull(index)) ?: continue
        }
        return output
    }

}
