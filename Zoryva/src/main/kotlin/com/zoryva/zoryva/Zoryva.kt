package com.zoryva.zoryva

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zoryva CloudStream provider.
 *
 * Design goals:
 * - Keep the Home layout limited to the four requested sections.
 * - Read the Zoryva website structure instead of inventing a second catalog.
 * - Treat Movies as movies and TV/Anime as episode-based series.
 * - Read fresh detail/player pages on every Play action.
 * - Never persist signed/tokenized media URLs between Play actions.
 * - Discover multiple playable servers and prefer the fastest usable source.
 * - Keep additional working servers available as fallbacks.
 * - Preserve the upstream Referer/Origin context when using Zoryva's proxy.
 * - Detect real HLS qualities from playlist metadata or URL paths.
 * - Prefer HLS masters when they contain audio/subtitle track groups.
 * - Collect external subtitle tracks when the source exposes them.
 * - Reject trailers, teasers, previews, clips and obvious promotional media.
 * - Keep the parser defensive because Zoryva is a Next.js application.
 */
class Zoryva : MainAPI() {

    private companion object {
        const val BASE_URL = "https://zoryva.me"

        const val TRENDING = "$BASE_URL/browse/trending"
        const val MOVIES = "$BASE_URL/browse/movie"
        const val TV_SHOW = "$BASE_URL/browse/tv"
        const val ANIME = "$BASE_URL/browse/anime"

        const val MAX_HOME_ITEMS = 24
        const val INITIAL_HOME_ITEMS = 8
        const val HOME_SITEMAP_CACHE_TTL_MS = 120_000L
        const val HOME_SITEMAP_CHILD_LIMIT = 32
        const val MAX_SEARCH_ITEMS = 50
        const val MAX_SERVER_PAGES = 12
        const val MAX_CRAWL_DEPTH = 2
        const val VIDROCK_ORIGIN = "https://vidrock.net"
        const val SOURCE_PROBE_TIMEOUT_MS = 7000L
        const val SERVER_PAGE_TIMEOUT_MS = 9000L
        const val HOME_PREFETCH_TIMEOUT_MS = 5000L
        const val HOME_CATALOG_CACHE_TTL_MS = 15000L

        // Playback resolver tuning. These values are intentionally bounded so
        // one broken server cannot block the whole player indefinitely.
        const val VIDROCK_KEY_TIMEOUT_MS = 5000L
        const val VIDROCK_API_TIMEOUT_MS = 7000L
        const val VIDROCK_SCRIPT_TIMEOUT_MS = 3500L
        const val VIDROCK_SCRIPT_LIMIT = 18
        const val VIDROCK_MAX_SERVERS = 12
        const val ZORYVA_EXTRACT_TIMEOUT_MS = 9000L
        const val ZORYVA_SCRAPED_TIMEOUT_MS = 6000L
        const val PLAYABILITY_GET_TIMEOUT_MS = 5000L
        const val PLAYABILITY_HEAD_TIMEOUT_MS = 3500L
        const val MAX_EMITTED_FALLBACKS = 8

        // Advanced search tuning. Normal searches should finish from the
        // website's native search page; the heavier global fallback is used
        // only when native search cannot produce a useful match.
        const val SEARCH_RESULT_LIMIT = 50
        const val SEARCH_NATIVE_TIMEOUT_MS = 4500L
        const val SEARCH_HOME_TIMEOUT_MS = 5000L
        const val SEARCH_FALLBACK_TIMEOUT_MS = 8500L
        const val SEARCH_SITEMAP_LIMIT = 5000
        const val SEARCH_SITEMAP_CHILD_LIMIT = 16
        const val SEARCH_VERIFY_LIMIT = 12
        const val SEARCH_LOCAL_SCAN_LIMIT = 1600
        const val DETAIL_FETCH_ATTEMPTS = 3
        const val DETAIL_RETRY_DELAY_MS = 350L
        const val STRONG_SEARCH_SCORE = 0.78
        const val NORMAL_SEARCH_SCORE = 0.42

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        const val ACCEPT = "*/*"

        val PAGE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        val MEDIA_EXTENSIONS = listOf(
            ".m3u8",
            ".mp4",
            ".mkv",
            ".webm",
            ".m4v",
            ".mov",
            ".avi",
            ".flv"
        )

        val TRAILER_WORDS = listOf(
            "trailer",
            "teaser",
            "preview",
            "clip",
            "promo",
            "promotional",
            "featurette",
            "behind-the-scenes",
            "behind the scenes"
        )

        val SEARCH_METADATA_WORDS = setOf(
            "movie", "movies", "film", "films", "series",
            "season", "seasons", "episode", "episodes", "ep",
            "hd", "hdtc", "web", "webdl", "webrip", "bluray",
            "dual", "audio", "dub", "dublado", "sub"
        )

        val IGNORED_HOST_PARTS = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "vimeo.com",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "googlevideo.com"
        )

        val PLAYER_WORDS = listOf(
            "player",
            "watch",
            "embed",
            "stream",
            "source",
            "server",
            "play",
            "video"
        )
    }

    override var mainUrl: String = BASE_URL
    override var name: String = "Zoryva"
    override var lang: String = "hi"

    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /**
     * Zoryva Home deliberately exposes only the four requested sections.
     */
    override val mainPage = mainPageOf(
        TRENDING to "Trending Now",
        MOVIES to "Movies",
        TV_SHOW to "TV Show",
        ANIME to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val aliases: List<String> = emptyList()
    )

    private data class EpisodeInfo(
        val id: String?,
        val name: String,
        val season: Int,
        val episode: Int,
        val overview: String?,
        val poster: String?,
        val runtime: Int?
    )

    private data class DetailInfo(
        val title: String,
        val poster: String?,
        val plot: String?,
        val year: Int?,
        val type: TvType,
        val episodes: List<EpisodeInfo>
    )

    private data class MediaCandidate(
        val url: String,
        val referer: String,
        val origin: String,
        val quality: Int,
        val label: String,
        val server: String,
        val latencyMs: Long,
        val isHlsMaster: Boolean,
        val audioLabel: String
    )

    private data class PlaybackOutput(
        val url: String,
        val referer: String,
        val headers: Map<String, String>
    )

    private data class ServerResult(
        val serverUrl: String,
        val serverName: String,
        val latencyMs: Long,
        val sources: List<MediaCandidate>,
        val subtitles: List<Pair<String, String>>
    )

    private data class HlsInfo(
        val isMaster: Boolean,
        val hasAudioTracks: Boolean,
        val hasSubtitleTracks: Boolean,
        val maxQuality: Int,
        val audioLabel: String,
        val subtitles: List<Pair<String, String>>,
        val variants: List<Pair<String, Int>>
    )

    private data class VidrockServerTarget(
        val url: String,
        val name: String,
        val referer: String = VIDROCK_ORIGIN + "/"
    )

    private data class VidrockResolution(
        val directSources: List<MediaCandidate>,
        val servers: List<VidrockServerTarget>
    )

    private data class ZoryvaExtractResolution(
        val directSources: List<MediaCandidate>,
        val servers: List<VidrockServerTarget>
    )

    private data class MediaProbe(
        val status: Int,
        val contentType: String,
        val acceptRanges: String,
        val contentRange: String,
        val bodyPrefix: String
    )

    private data class CachedCatalog(
        val createdAt: Long,
        val items: List<SiteItem>
    )

    private val catalogCache = ConcurrentHashMap<String, CachedCatalog>()
    private val catalogPrefetchMutex = Mutex()
    private val sitemapPathCache = mutableListOf<String>()
    @Volatile private var sitemapCacheAt: Long = 0L
    private val sitemapMutex = Mutex()

    private val homeRoutes = listOf(
        TRENDING,
        MOVIES,
        TV_SHOW,
        ANIME
    )

    // ---------------------------------------------------------------------
    // HOME
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val route = request.data
        val currentPage = page.coerceAtLeast(1)

        if (currentPage == 1) {
            /*
             * Warm every requested Home section together from the server-rendered
             * Next.js Home RSC payload. This is the reliable catalog bootstrap
             * path for the current Zoryva website: the visible browse pages are
             * client shells and may contain only a loading state in raw HTML.
             */
            prefetchHomeCatalogs()

            val cached = getCachedCatalog(route)
            val items = if (!cached?.items.isNullOrEmpty()) {
                cached?.items.orEmpty()
            } else {
                loadHomeSitemapPage(route, 1)
                    .also { fallback ->
                        if (fallback.isNotEmpty()) {
                            storeCatalog(route, fallback)
                        }
                    }
            }

            val visible = items.take(INITIAL_HOME_ITEMS)

            return newHomePageResponse(
                request,
                visible.map { it.toSearchResponse() },
                items.size > visible.size
            )
        }

        /*
         * First try the website's conventional page parameter. Some deployments
         * expose server pagination even when the first HTML response is a client
         * shell, so this remains the cheapest lazy path.
         */
        val candidateUrls = listOf(
            buildBrowseUrl(route, currentPage),
            "$route?limit=$MAX_HOME_ITEMS&page=$currentPage",
            "$route?page=$currentPage&limit=$MAX_HOME_ITEMS",
            "$route?offset=${(currentPage - 1) * MAX_HOME_ITEMS}&limit=$MAX_HOME_ITEMS"
        ).distinct()

        val fetchedPages = coroutineScope {
            candidateUrls.map { candidate ->
                async {
                    withTimeoutOrNull(HOME_PREFETCH_TIMEOUT_MS) {
                        getDocument(candidate)
                    }
                }
            }.awaitAll()
        }

        val pageItems = fetchedPages
            .mapIndexed { index, document ->
                document?.let {
                    buildBrowseItemsFromDocument(
                        it,
                        candidateUrls.getOrNull(index) ?: route
                    )
                }.orEmpty()
            }
            .flatten()
            .distinctBy { cleanUrl(it.url) }

        val baseItems = getCachedCatalog(route)?.items.orEmpty()
        val nonDuplicatePageItems = pageItems.filterNot { item ->
            baseItems.any { cleanUrl(it.url) == cleanUrl(item.url) }
        }

        if (nonDuplicatePageItems.isNotEmpty()) {
            return newHomePageResponse(
                request,
                nonDuplicatePageItems.take(MAX_HOME_ITEMS).map { it.toSearchResponse() },
                nonDuplicatePageItems.size >= MAX_HOME_ITEMS
            )
        }

        /*
         * The current browse pages are client-rendered. Use the site's sitemap
         * as a real-content index for subsequent lazy pages. Only metadata and
         * canonical Zoryva URLs are cached; every candidate detail page is
         * verified before it becomes a Home result.
         */
        val sitemapItems = loadHomeSitemapPage(
            route = route,
            page = currentPage
        )

        return newHomePageResponse(
            request,
            sitemapItems.map { it.toSearchResponse() },
            sitemapItems.size >= MAX_HOME_ITEMS
        )
    }

    private suspend fun prefetchHomeCatalogs() {
        val now = System.currentTimeMillis()
        val hasFreshCatalog = homeRoutes.all { route ->
            val cached = catalogCache[route]
            cached != null && now - cached.createdAt <= HOME_CATALOG_CACHE_TTL_MS
        }

        if (hasFreshCatalog) return

        catalogPrefetchMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val stillFresh = homeRoutes.all { route ->
                val cached = catalogCache[route]
                cached != null && lockedNow - cached.createdAt <= HOME_CATALOG_CACHE_TTL_MS
            }
            if (stillFresh) return

            /*
             * One Home request provides initialRows + initialHeroItems for all
             * four sections. This is both faster and more reliable than trying
             * to scrape the client-only /browse pages on the first frame.
             */
            val homepage = withTimeoutOrNull(7000L) {
                getDocument(BASE_URL)
            }

            val rscCatalogs = homepage
                ?.let { parseHomepageRscCatalogs(it) }
                .orEmpty()

            rscCatalogs.forEach { (route, items) ->
                if (items.isNotEmpty()) {
                    storeCatalog(route, items.distinctBy { cleanUrl(it.url) })
                }
            }

            /*
             * Keep the real browse routes as a secondary source. If the site
             * changes back to SSR cards in the future, the parser can merge them
             * immediately without changing the Home API.
             */
            val fallbackRoutes = homeRoutes.filter { route ->
                getCachedCatalog(route)?.items.isNullOrEmpty()
            }

            if (fallbackRoutes.isNotEmpty()) {
                val fetched = coroutineScope {
                    fallbackRoutes.map { route ->
                        async {
                            route to withTimeoutOrNull(HOME_PREFETCH_TIMEOUT_MS) {
                                getDocument(route)
                            }
                        }
                    }.awaitAll()
                }

                fetched.forEach { (route, document) ->
                    document ?: return@forEach
                    val items = buildBrowseItemsFromDocument(document, route)
                        .distinctBy { cleanUrl(it.url) }
                    if (items.isNotEmpty()) {
                        storeCatalog(route, items)
                    }
                }
            }
        }
    }

    private fun parseHomepageRscCatalogs(
        document: Document
    ): Map<String, List<SiteItem>> {
        /*
         * IMPORTANT: Zoryva's home catalog is transported through Next.js
         * Flight/RSC scripts. Those scripts contain a JavaScript string inside
         * self.__next_f.push([1, "..."]). A normal HTML/JSON unescape is not
         * enough here because quotes are escaped inside the nested JS string.
         * Decode the actual quoted Flight payload first, then parse initialRows.
         */
        val decodedPayload = extractNextFlightPayload(document)
        val decoded = if (decodedPayload.contains("\"initialRows\":", true)) {
            decodedPayload
        } else {
            /* Keep the legacy path as a defensive fallback for SSR changes. */
            decodeRscForCatalog(document.html())
        }

        if (!decoded.contains("\"initialRows\":", true)) {
            return emptyMap()
        }

        val heroItems = extractRscArray(decoded, "\"initialHeroItems\":")
            ?.let(::parseRscMediaArray)
            .orEmpty()

        val rows = extractRscArray(decoded, "\"initialRows\":") ?: return emptyMap()
        val rowsArray = runCatching { JSONArray(rows) }.getOrNull() ?: return emptyMap()

        val routeRows = linkedMapOf<String, MutableList<SiteItem>>()

        for (i in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(i) ?: continue
            val key = row.optString("key").trim().lowercase(Locale.ROOT)
            if (key.isBlank()) continue

            val items = row.optJSONArray("items") ?: continue
            val target = when {
                key == "trending" -> TRENDING
                key == "popular-movies" ||
                    key == "new-releases" ||
                    key == "top-rated" ||
                    key == "recently-added" ||
                    key == "recommended" -> MOVIES
                key == "popular-tv" ||
                    key == "trending-tv" ||
                    key == "latest-tv" -> TV_SHOW
                key == "popular-anime" ||
                    key == "trending-anime" ||
                    key == "latest-anime" -> ANIME
                else -> null
            } ?: continue

            val output = routeRows.getOrPut(target) { mutableListOf() }

            for (j in 0 until items.length()) {
                val value = items.opt(j)
                val media = when (value) {
                    is JSONObject -> value
                    is String -> resolveRscHeroReference(value, heroItems)
                    else -> null
                } ?: continue

                val item = rscMediaToSiteItem(media, key) ?: continue

                val belongsToCategory = when (target) {
                    MOVIES -> item.type == TvType.Movie
                    TV_SHOW -> item.type == TvType.TvSeries
                    ANIME -> item.type == TvType.Anime
                    TRENDING -> true
                    else -> false
                }
                if (!belongsToCategory) continue

                if (output.none { cleanUrl(it.url) == cleanUrl(item.url) }) {
                    output += item
                }
            }
        }

        return routeRows
            .mapValues { (_, items) -> items.distinctBy { cleanUrl(it.url) } }
    }

    /**
     * Decode the Next.js Flight payloads embedded in <script> tags.
     * The website uses:
     *
     *   self.__next_f.push([1,"..."])
     *
     * The second argument is a JavaScript quoted string and may contain nested
     * JSON/RSC escapes. We parse that quoted string explicitly instead of doing
     * a blind replace operation which would leave \" sequences behind.
     */
    private fun extractNextFlightPayload(
        document: Document
    ): String {
        val output = StringBuilder()
        val marker = "push([1,\""

        document.select("script").forEach { script ->
            val data = script.data().ifBlank { script.html() }
            if (!data.contains("__next_f", true)) return@forEach

            var searchStart = 0
            while (true) {
                val markerIndex = data.indexOf(marker, searchStart)
                if (markerIndex < 0) break

                val quoteStart = markerIndex + "push([1,".length
                val decoded = decodeQuotedJsString(data, quoteStart)
                    ?: break

                if (decoded.contains("initialRows") ||
                    decoded.contains("initialHeroItems") ||
                    decoded.contains("initialMedia")) {
                    output.append(decoded).append('\n')
                }

                val nextIndex = quoteStart + 1
                searchStart = nextIndex
                if (searchStart >= data.length) break
            }
        }

        return output.toString()
    }

    private fun decodeQuotedJsString(
        text: String,
        openingQuoteIndex: Int
    ): String? {
        if (openingQuoteIndex !in text.indices || text[openingQuoteIndex] != '"') {
            return null
        }

        val out = StringBuilder()
        var index = openingQuoteIndex + 1

        while (index < text.length) {
            val c = text[index]

            if (c == '"') {
                return out.toString()
            }

            if (c != '\\') {
                out.append(c)
                index++
                continue
            }

            if (index + 1 >= text.length) return null
            val escaped = text[index + 1]

            when (escaped) {
                '"' -> { out.append('"'); index += 2 }
                '\\' -> { out.append('\\'); index += 2 }
                '/' -> { out.append('/'); index += 2 }
                'b' -> { out.append('\b'); index += 2 }
                'f' -> { out.append('\u000C'); index += 2 }
                'n' -> { out.append('\n'); index += 2 }
                'r' -> { out.append('\r'); index += 2 }
                't' -> { out.append('\t'); index += 2 }
                'u' -> {
                    if (index + 5 >= text.length) return null
                    val hex = text.substring(index + 2, index + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    index += 6
                }
                else -> {
                    /* JavaScript permits escaped non-special characters. */
                    out.append(escaped)
                    index += 2
                }
            }
        }

        return null
    }

    private fun resolveRscHeroReference(
        value: String,
        heroItems: List<JSONObject>
    ): JSONObject? {
        val marker = "initialHeroItems:"
        val index = value.lastIndexOf(marker)
        if (index < 0) return null
        val heroIndex = value.substring(index + marker.length).toIntOrNull() ?: return null
        return heroItems.getOrNull(heroIndex)
    }

    private fun rscMediaToSiteItem(
        media: JSONObject,
        rowKey: String
    ): SiteItem? {
        val title = media.optString("title")
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        val aliases = buildList {
            media.optString("originalTitle")
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(title, true) }
                ?.let(::add)
            media.optString("name")
                .trim()
                .takeIf { it.isNotBlank() && !it.equals(title, true) }
                ?.let(::add)
            media.optString("slug")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.replace('-', ' ')
                ?.replace('_', ' ')
                ?.let(::add)
        }

        val mediaType = media.optString("mediaType")
            .trim()
            .lowercase(Locale.ROOT)

        val type = when {
            mediaType == "movie" -> TvType.Movie
            mediaType == "tv" || mediaType == "series" -> TvType.TvSeries
            mediaType == "anime" || rowKey.contains("anime") -> TvType.Anime
            else -> return null
        }

        val externalId = when (val raw = media.opt("externalId")) {
            is Number -> raw.toLong().toString()
            is String -> raw.trim().takeIf { it.isNotBlank() }
            else -> null
        } ?: media.optString("id")
            .substringAfterLast("-")
            .takeIf { it.matches(Regex("\\d+")) }
            ?: return null

        val path = when (type) {
            TvType.Movie -> "movie"
            TvType.TvSeries -> "tv"
            TvType.Anime -> "anime"
            else -> return null
        }

        val poster = media.optString("posterPath")
            .trim()
            .takeIf { it.startsWith("http", true) }

        return SiteItem(
            title = cleanCardTitle(title),
            url = "$BASE_URL/$path/$externalId",
            poster = poster,
            type = type,
            aliases = aliases.map(::cleanCardTitle)
        )
    }

    private fun extractRscArray(
        decoded: String,
        marker: String
    ): String? {
        val index = decoded.indexOf(marker)
        if (index < 0) return null
        val start = decoded.indexOf('[', index + marker.length)
        if (start < 0) return null
        return extractBalancedJson(decoded, start, '[', ']')
    }

    private fun parseRscMediaArray(
        arrayJson: String
    ): List<JSONObject> {
        val array = runCatching { JSONArray(arrayJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let(::add)
            }
        }
    }

    private fun decodeRscForCatalog(
        input: String
    ): String {
        var text = input
        repeat(2) {
            text = decodeRscOneLayer(text)
                .replace("\\:", ":")
                .replace("\\.", ".")
        }
        return text
    }

    private fun buildBrowseItemsFromDocument(
        document: Document,
        baseUrl: String
    ): List<SiteItem> {
        return parseBrowseItems(document, baseUrl)
            .distinctBy { cleanUrl(it.url) }
    }

    private suspend fun loadHomeSitemapPage(
        route: String,
        page: Int
    ): List<SiteItem> {
        val paths = getSitemapMediaPaths()
        if (paths.isEmpty()) return emptyList()

        val filtered = paths.filter { path ->
            when (route) {
                MOVIES -> isMoviePath(path)
                TV_SHOW -> isTvPath(path) && !isEpisodePath(path)
                ANIME -> isAnimePath(path) && !isEpisodePath(path)
                TRENDING -> true
                else -> false
            }
        }

        val existing = getCachedCatalog(route)?.items.orEmpty()
        val existingKeys = existing.map { cleanUrl(it.url) }.toSet()
        val uniquePaths = filtered.filterNot { path ->
            existingKeys.contains(cleanUrl("$BASE_URL${normalizePathForSearch(path)}"))
        }

        val start = when {
            page <= 1 -> 0
            else -> (page - 2) * MAX_HOME_ITEMS
        }
        if (start >= uniquePaths.size) return emptyList()

        val slice = uniquePaths
            .drop(start)
            .take(MAX_HOME_ITEMS)

        return coroutineScope {
            slice.map { path ->
                async {
                    verifySitemapCandidate(path)
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getSitemapMediaPaths(): List<String> {
        val now = System.currentTimeMillis()
        if (now - sitemapCacheAt <= HOME_SITEMAP_CACHE_TTL_MS) {
            return synchronized(sitemapPathCache) { sitemapPathCache.toList() }
        }

        return sitemapMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (lockedNow - sitemapCacheAt <= HOME_SITEMAP_CACHE_TTL_MS) {
                return@withLock synchronized(sitemapPathCache) { sitemapPathCache.toList() }
            }

            val roots = listOf(
                "$BASE_URL/sitemap.xml",
                "$BASE_URL/sitemap_index.xml",
                "$BASE_URL/sitemap-index.xml"
            )

            var rootDocument: org.jsoup.nodes.Document? = null
            for (root in roots) {
                val candidate = runCatching { getDocument(root) }.getOrNull()
                if (candidate != null && candidate.select("loc").isNotEmpty()) {
                    rootDocument = candidate
                    break
                }
            }

            val document = rootDocument ?: return@withLock emptyList()

            val direct = linkedSetOf<String>()
            val childUrls = linkedSetOf<String>()

            document.select("loc").forEach { element ->
                val url = element.text().trim()
                if (url.isBlank()) return@forEach
                val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
                if (isSearchContentPath(path)) {
                    direct += normalizePathForSearch(path)
                } else if (url.endsWith(".xml", true) || url.contains("sitemap", true)) {
                    childUrls += cleanUrl(url)
                }
            }

            val children = coroutineScope {
                childUrls.take(HOME_SITEMAP_CHILD_LIMIT).map { url ->
                    async { getDocument(url) }
                }.awaitAll()
            }

            children.forEach { document ->
                document ?: return@forEach
                document.select("loc").forEach { element ->
                    val url = element.text().trim()
                    if (url.isBlank()) return@forEach
                    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
                    if (isSearchContentPath(path)) {
                        direct += normalizePathForSearch(path)
                    }
                }
            }

            val result = direct.toList()
            synchronized(sitemapPathCache) {
                sitemapPathCache.clear()
                sitemapPathCache.addAll(result)
            }
            sitemapCacheAt = lockedNow
            result
        }
    }

    private fun getCachedCatalog(
        route: String
    ): CachedCatalog? {
        val cached = catalogCache[route] ?: return null
        return if (System.currentTimeMillis() - cached.createdAt <= HOME_CATALOG_CACHE_TTL_MS) {
            cached
        } else {
            catalogCache.remove(route)
            null
        }
    }

    private fun storeCatalog(
        route: String,
        items: List<SiteItem>
    ) {
        if (items.isEmpty()) return
        catalogCache[route] = CachedCatalog(
            createdAt = System.currentTimeMillis(),
            items = items
        )
    }

    private fun hasExplicitNextPage(
        document: Document?
    ): Boolean {
        if (document == null) return false

        return document.select("a[href]").any { anchor ->
            val text = anchor.text().trim().lowercase(Locale.ROOT)
            val aria = anchor.attr("aria-label").trim().lowercase(Locale.ROOT)
            text == "next" ||
                text.contains("next page") ||
                aria.contains("next")
        }
    }

    private fun sameCatalog(
        first: List<SiteItem>,
        second: List<SiteItem>
    ): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false

        val firstKeys = first.take(12).map { cleanUrl(it.url) }.toSet()
        val secondKeys = second.take(12).map { cleanUrl(it.url) }.toSet()
        if (firstKeys.isEmpty() || secondKeys.isEmpty()) return false

        val overlap = firstKeys.intersect(secondKeys).size
        return overlap >= maxOf(1, (minOf(firstKeys.size, secondKeys.size) * 0.75).toInt())
    }

    private fun buildBrowseUrl(
        route: String,
        page: Int
    ): String {
        if (page <= 1) return route

        val separator = if (route.contains("?")) "&" else "?"
        return "$route${separator}page=$page"
    }

    private fun parseBrowseItems(
        document: Document,
        baseUrl: String = BASE_URL
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        /* Standard SSR/DOM links. */
        document.select("a[href]").forEach { anchor ->
            val rawHref = anchor.attr("href").trim()
            val absolute = normalizeExtractedUrl(rawHref, baseUrl) ?: return@forEach
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")

            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> return@forEach
            }

            val title = extractCardTitle(anchor)
                .ifBlank { titleFromPath(path) }
                .ifBlank { return@forEach }

            result.putIfAbsent(
                cleanUrl(absolute),
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractCardPoster(anchor, baseUrl),
                    type = type
                )
            )
        }

        /*
         * Next.js client-rendered browse pages may contain no media anchors at
         * all. The RSC payload can still expose canonical media objects.
         */
        val decoded = decodeRscForCatalog(document.html())
        addDecodedRscMediaObjects(
            decoded = decoded,
            defaultRoute = baseUrl,
            result = result
        )

        /*
         * Some captured/escaped payloads contain literal markdown-wrapped
         * hrefs. Accept those too, but always normalize them back to the real
         * canonical Zoryva URL before emitting a result.
         */
        val html = document.html()
        val markdownHrefRegex = Regex(
            """(?i)href=\"\[/((?:movie|tv|anime)/[^\"\\]]+)\]\((https?://[^\)]+)\)\""""
        )
        markdownHrefRegex.findAll(html).forEach { match ->
            val absolute = normalizeExtractedUrl(match.groupValues[2], baseUrl) ?: return@forEach
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")
            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) && !isEpisodePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> return@forEach
            }
            val nearby = html.substring(
                maxOf(0, match.range.first - 1800),
                minOf(html.length, match.range.last + 1800)
            )
            val title = Regex("(?is)(?:alt|title)=\\\"([^\\\"]+)\\\"")
                .find(nearby)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::cleanCardTitle)
                .orEmpty()
                .ifBlank { titleFromPath(path) }

            if (title.isNotBlank()) {
                result.putIfAbsent(
                    cleanUrl(absolute),
                    SiteItem(
                        title = title,
                        url = absolute,
                        poster = null,
                        type = type
                    )
                )
            }
        }

        return result.values.toList()
    }

    private fun addDecodedRscMediaObjects(
        decoded: String,
        defaultRoute: String,
        result: MutableMap<String, SiteItem>
    ) {
        val seenStarts = HashSet<Int>()
        val marker = "\"mediaType\":\""
        var searchStart = 0

        while (true) {
            val mediaTypeIndex = decoded.indexOf(marker, searchStart)
            if (mediaTypeIndex < 0) break

            val objectStart = decoded.lastIndexOf('{', mediaTypeIndex)
            if (objectStart < 0 || !seenStarts.add(objectStart)) {
                searchStart = mediaTypeIndex + marker.length
                continue
            }

            val json = extractBalancedJson(decoded, objectStart, '{', '}')
            if (json != null) {
                val media = runCatching { JSONObject(json) }.getOrNull()
                val typeHint = runCatching { URI(defaultRoute).path.orEmpty() }
                    .getOrDefault("")
                    .trim('/')
                    .split('/')
                    .firstOrNull()
                    .orEmpty()
                if (media != null) {
                    val item = rscMediaToSiteItem(media, typeHint)
                    if (item != null) {
                        result.putIfAbsent(cleanUrl(item.url), item)
                    }
                }
            }

            searchStart = mediaTypeIndex + marker.length
        }
    }

    private fun hasMoreBrowsePage(
        document: Document,
        items: List<SiteItem>
    ): Boolean {
        if (items.isEmpty()) return false
        return hasExplicitNextPage(document)
    }

    // ---------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val original = query.trim()
        if (original.isBlank()) return emptyList()

        /*
         * Professional search pipeline:
         *
         * 1) Search Zoryva's own native search endpoint.
         * 2) In parallel, refresh the four-section RSC catalog so an item that
         *    is already visible on Home is searchable even when /search is a
         *    client-rendered shell.
         * 3) Merge both real-Zoryva result sets and rank them locally.
         * 4) Use Jaro-Winkler + token similarity so queries such as
         *    "research" can still match the real title "Reacher".
         * 5) Only then use the sitemap/detail-page fallback.
         *
         * No outside catalog is introduced and no fake media URL is generated.
         */
        val (warm, native) = coroutineScope {
            val warmJob = async {
                withTimeoutOrNull(SEARCH_HOME_TIMEOUT_MS) {
                    prefetchHomeCatalogs()
                    homeRoutes
                        .flatMap { route -> getCachedCatalog(route)?.items.orEmpty() }
                        .distinctBy { cleanUrl(it.url) }
                }.orEmpty()
            }

            val nativeJob = async {
                withTimeoutOrNull(SEARCH_NATIVE_TIMEOUT_MS) {
                    searchNative(original)
                }.orEmpty()
            }

            warmJob.await() to nativeJob.await()
        }

        val localRanked = rankSearchItems(original, warm)

        val nativeItems = native.mapNotNull { response ->
            response.url?.takeIf { it.startsWith(BASE_URL, true) }?.let { url ->
                val parsedType = when {
                    isTvPath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.TvSeries
                    isAnimePath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.Anime
                    isMoviePath(runCatching { URI(url).path.orEmpty() }.getOrDefault("")) -> TvType.Movie
                    else -> return@let null
                }
                val title = response.name
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@let null
                SiteItem(title, cleanUrl(url), null, parsedType)
            }
        }

        val merged = linkedMapOf<String, Pair<SiteItem, Double>>()
        localRanked.forEach { (item, score) ->
            merged[cleanUrl(item.url)] = item to score
        }
        nativeItems.forEach { item ->
            val score = searchScore(original, item.title)
            val key = cleanUrl(item.url)
            val existing = merged[key]
            if (existing == null || score > existing.second) {
                merged[key] = item to score
            }
        }

        val ranked = merged.values
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )

        if (ranked.isNotEmpty()) {
            return ranked
                .take(SEARCH_RESULT_LIMIT)
                .map { it.first.toSearchResponse() }
        }

        return withTimeoutOrNull(SEARCH_FALLBACK_TIMEOUT_MS) {
            searchFallback(original)
        }.orEmpty()
    }

    private suspend fun searchNative(
        original: String
    ): List<SearchResponse> {
        val exactUrl = buildSearchUrl(original)
        val exactDocument = getDocument(exactUrl)

        if (exactDocument != null) {
            val exactItems = parseBrowseItems(exactDocument, exactUrl)
                .distinctBy { cleanUrl(it.url) }
            val ranked = rankSearchItems(original, exactItems)

            /*
             * Strong native matches are trusted immediately. This keeps normal
             * searches fast and avoids unnecessary extra network requests.
             */
            if (ranked.any { it.second >= STRONG_SEARCH_SCORE }) {
                return ranked
                    .take(SEARCH_RESULT_LIMIT)
                    .map { it.first.toSearchResponse() }
            }

            /*
             * The site returned real results, even if the local scorer was not
             * confident enough. Keep those results rather than manufacturing a
             * fuzzy hit from unrelated pages.
             */
            if (exactItems.isNotEmpty()) {
                val useful = ranked
                    .filter { it.second >= NORMAL_SEARCH_SCORE }
                    .take(SEARCH_RESULT_LIMIT)

                if (useful.isNotEmpty()) {
                    return useful.map { it.first.toSearchResponse() }
                }
            }
        }

        /*
         * Small concurrent variant pass. These are normalization variants only;
         * they are not a full-site crawl and therefore remain quick.
         */
        val variants = buildSearchVariants(original)
            .drop(1)
            .take(3)
            .toList()

        if (variants.isEmpty()) return emptyList()

        val documents = coroutineScope {
            variants.map { variant ->
                async {
                    getDocument(buildSearchUrl(variant))
                }
            }.awaitAll()
        }

        val merged = linkedMapOf<String, SiteItem>()
        documents.forEachIndexed { index, document ->
            document ?: return@forEachIndexed
            val variantUrl = buildSearchUrl(variants.getOrNull(index) ?: original)
            parseBrowseItems(document, variantUrl).forEach { item ->
                merged.putIfAbsent(cleanUrl(item.url), item)
            }
        }

        val ranked = rankSearchItems(original, merged.values.toList())
        if (ranked.isEmpty()) return emptyList()

        return ranked
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private suspend fun searchFallback(
        original: String
    ): List<SearchResponse> {
        val paths = fetchSearchSitemapPaths()
        if (paths.isEmpty()) return emptyList()

        val rankedPaths = paths
            .map { path ->
                val titleGuess = titleFromPath(path)
                path to searchScore(original, titleGuess)
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { it.first }
            )
            .take(SEARCH_VERIFY_LIMIT * 2)

        if (rankedPaths.isEmpty()) return emptyList()

        /*
         * Verify the highest-confidence sitemap candidates against their real
         * Zoryva detail pages. This prevents a fuzzy slug-only match from being
         * returned unless the actual page exists and exposes a usable title.
         */
        val verified = coroutineScope {
            rankedPaths.map { (path, score) ->
                async {
                    val item = verifySitemapCandidate(path)
                    if (item == null) null else item to score
                }
            }.awaitAll().filterNotNull()
        }

        return verified
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { pair ->
                    /* Re-score against the verified title for better ordering. */
                    maxOf(pair.second, searchScore(original, pair.first.title))
                }.thenBy { pair ->
                    pair.first.title.lowercase(Locale.ROOT)
                }
            )
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private fun buildSearchUrl(
        query: String
    ): String {
        return "$BASE_URL/search?q=${encode(query)}"
    }

    private fun rankSearchItems(
        original: String,
        items: List<SiteItem>
    ): List<Pair<SiteItem, Double>> {
        return items
            .map { item ->
                val aliasScore = item.aliases.maxOfOrNull { alias ->
                    searchScore(original, alias)
                } ?: 0.0
                item to maxOf(
                    searchScore(original, item.title),
                    aliasScore
                )
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
    }

    private suspend fun fetchSearchSitemapPaths(): List<String> {
        val roots = listOf(
            "$BASE_URL/sitemap.xml",
            "$BASE_URL/sitemap_index.xml",
            "$BASE_URL/sitemap-index.xml"
        )

        var indexDocument: Document? = null
        for (root in roots) {
            val document = getDocument(root) ?: continue
            if (document.select("loc").isNotEmpty()) {
                indexDocument = document
                break
            }
        }

        val document = indexDocument ?: return emptyList()
        val locs = document.select("loc")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank) }

        val directPaths = linkedSetOf<String>()
        val childSitemaps = ArrayList<String>()

        for (loc in locs) {
            val path = runCatching {
                URI(loc).path.orEmpty()
            }.getOrDefault("")

            if (isSearchContentPath(path)) {
                directPaths += normalizePathForSearch(path)
            } else if (
                path.endsWith(".xml", true) ||
                    path.contains("sitemap", true)
            ) {
                childSitemaps += cleanUrl(loc)
            }
        }

        if (directPaths.size >= SEARCH_SITEMAP_LIMIT || childSitemaps.isEmpty()) {
            return directPaths.take(SEARCH_SITEMAP_LIMIT)
        }

        val children = childSitemaps
            .distinct()
            .take(SEARCH_SITEMAP_CHILD_LIMIT)

        val childDocuments = coroutineScope {
            children.map { child ->
                async { getDocument(child) }
            }.awaitAll()
        }

        childDocuments.forEach { child ->
            child ?: return@forEach
            child.select("loc").forEach { loc ->
                val value = loc.text().trim()
                if (value.isBlank()) return@forEach

                val path = runCatching {
                    URI(value).path.orEmpty()
                }.getOrDefault("")

                if (isSearchContentPath(path)) {
                    directPaths += normalizePathForSearch(path)
                }
            }
        }

        return directPaths.take(SEARCH_SITEMAP_LIMIT)
    }

    private suspend fun verifySitemapCandidate(
        path: String
    ): SiteItem? {
        val url = "$BASE_URL${normalizePathForSearch(path)}"
        val document = getDocument(url) ?: return null

        val parsed = parseBrowseItems(document, url)
            .firstOrNull { cleanUrl(it.url) == cleanUrl(url) }
            ?: run {
                parseDetail(document, url)?.let { info ->
                    SiteItem(
                        title = info.title,
                        url = url,
                        poster = info.poster,
                        type = info.type
                    )
                }
            }

        return parsed ?: run {
            val title = firstNonBlank(
                extractMeta(document, "property=og:title"),
                document.selectFirst("h1")?.text(),
                document.title()
            )?.let(::cleanDetailTitle)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            SiteItem(
                title = title,
                url = url,
                poster = firstNonBlank(
                    extractMeta(document, "property=og:image"),
                    extractMeta(document, "name=twitter:image")
                ),
                type = typeFromContentPath(path)
            )
        }
    }

    private fun buildSearchVariants(
        query: String
    ): LinkedHashSet<String> {
        val normalized = normalizeSearch(query)
        val compact = normalized.replace(" ", "")
        val reduced = normalized
            .split(' ')
            .filterNot {
                it in SEARCH_METADATA_WORDS
            }
            .joinToString(" ")

        return linkedSetOf<String>().apply {
            add(query)
            if (normalized.isNotBlank()) add(normalized)
            if (reduced.isNotBlank()) add(reduced)
            if (compact.isNotBlank()) add(compact)
        }
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)

        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0

        var score = 0.0
        val qCompact = q.replace(" ", "")
        val tCompact = t.replace(" ", "")

        if (t.contains(q)) {
            val ratio = q.length.toDouble() / t.length.coerceAtLeast(1)
            score = maxOf(score, 0.96 + minOf(0.04, ratio * 0.04))
        }

        if (qCompact.isNotBlank() && tCompact.contains(qCompact)) {
            val ratio = qCompact.length.toDouble() /
                tCompact.length.coerceAtLeast(1).toDouble()
            score = maxOf(score, 0.88 + minOf(0.10, ratio * 0.10))
        }

        /* Human typos are often better captured by Jaro-Winkler than raw edit distance.
         * Example: "research" vs "reacher" gets a strong similarity despite several
         * insertions/deletions, while exact/substring matches above still dominate.
         */
        if (q.length >= 4 && t.length >= 4) {
            score = maxOf(
                score,
                jaroWinkler(q, t),
                jaroWinkler(qCompact, tCompact)
            )
        }

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return maxOf(score, fullStringSimilarity(q, t))
                .coerceIn(0.0, 1.0)
        }

        val tokenScores = qTokens.map { qt ->
            tTokens.maxOfOrNull { tt ->
                when {
                    qt == tt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.94
                    tt.contains(qt) || qt.contains(tt) -> 0.90
                    else -> tokenSimilarity(qt, tt)
                }
            } ?: 0.0
        }

        val average = tokenScores.average()
        score = maxOf(score, average * 0.94)

        val covered = tokenScores.count { it >= 0.62 }
        val coverage = covered.toDouble() / qTokens.size.toDouble()
        score = maxOf(score, 0.45 + coverage * 0.50)

        /* Reward order-independent adjacency of the main query words. */
        val orderedQuery = qTokens.joinToString("")
        val orderedTitle = tTokens.joinToString("")
        if (orderedTitle.contains(orderedQuery)) {
            score = maxOf(score, 0.91)
        }

        /* Acronym/initials support for titles such as "Game of Thrones". */
        val initials = tTokens
            .mapNotNull { it.firstOrNull() }
            .joinToString("")
        if (qCompact == initials || initials.startsWith(qCompact)) {
            score = maxOf(score, 0.90)
        }

        val queryYear = Regex("\\b(19|20)\\d{2}\\b")
            .find(q)
            ?.value
            ?.toIntOrNull()

        if (queryYear != null) {
            val titleYear = Regex("\\b(19|20)\\d{2}\\b")
                .find(t)
                ?.value
                ?.toIntOrNull()

            when {
                titleYear == queryYear -> score = maxOf(score, 0.97)
                titleYear != null -> score *= 0.92
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun tokenSimilarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        if (longest == 0) return 1.0

        var result = 1.0 - distance.toDouble() / longest.toDouble()

        /* One adjacent transposition is a very common human typo. */
        if (result < 0.85 && a.length == b.length && a.length >= 2) {
            var mismatch = -1
            for (i in 0 until a.length) {
                if (a[i] != b[i]) {
                    mismatch = i
                    break
                }
            }

            if (
                mismatch >= 0 &&
                    mismatch + 1 < a.length &&
                    a[mismatch] == b[mismatch + 1] &&
                    a[mismatch + 1] == b[mismatch]
            ) {
                result = maxOf(result, 0.92)
            }
        }

        return result.coerceIn(0.0, 1.0)
    }

    private fun jaroWinkler(
        first: String,
        second: String
    ): Double {
        if (first == second) return 1.0
        if (first.isBlank() || second.isBlank()) return 0.0

        val a = first
        val b = second
        val matchDistance = (maxOf(a.length, b.length) / 2) - 1
        if (matchDistance < 0) return 0.0

        val aMatches = BooleanArray(a.length)
        val bMatches = BooleanArray(b.length)
        var matches = 0

        for (i in a.indices) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, b.length)
            for (j in start until end) {
                if (bMatches[j] || a[i] != b[j]) continue
                aMatches[i] = true
                bMatches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        val aSequence = CharArray(matches)
        val bSequence = CharArray(matches)
        var ai = 0
        var bi = 0

        for (i in a.indices) {
            if (aMatches[i]) aSequence[ai++] = a[i]
        }
        for (j in b.indices) {
            if (bMatches[j]) bSequence[bi++] = b[j]
        }

        var transpositions = 0
        for (i in 0 until matches) {
            if (aSequence[i] != bSequence[i]) transpositions++
        }

        val m = matches.toDouble()
        val jaro = (
            m / a.length.toDouble() +
                m / b.length.toDouble() +
                (m - transpositions / 2.0) / m
            ) / 3.0

        var prefix = 0
        val prefixLimit = minOf(4, minOf(a.length, b.length))
        while (prefix < prefixLimit && a[prefix] == b[prefix]) {
            prefix++
        }

        return (jaro + prefix * 0.1 * (1.0 - jaro)).coerceIn(0.0, 1.0)
    }

    private fun fullStringSimilarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
    }

    private fun typeFromContentPath(
        path: String
    ): TvType {
        return when {
            isAnimePath(path) -> TvType.Anime
            isTvPath(path) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun isSearchContentPath(
        path: String
    ): Boolean {
        if (path.isBlank()) return false
        if (isEpisodePath(path)) return false
        return isMoviePath(path) || isTvPath(path) || isAnimePath(path)
    }

    private fun normalizePathForSearch(
        path: String
    ): String {
        val normalized = path.trim()
            .removePrefix(BASE_URL)
            .trim()
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }


    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val clean = cleanUrl(url)
        if (clean.isBlank()) return null

        val document = getDocument(clean) ?: return null
        val info = parseDetail(document, clean) ?: return null

        when (info.type) {
            TvType.Movie -> {
                return newMovieLoadResponse(
                    info.title,
                    clean,
                    TvType.Movie,
                    clean
                ) {
                    posterUrl = info.poster
                    plot = info.plot
                    year = info.year
                }
            }

            TvType.Anime,
            TvType.TvSeries -> {
                val episodes = info.episodes.map { ep ->
                    val episodeUrl = buildEpisodeUrl(
                        baseUrl = clean,
                        title = info.title,
                        season = ep.season,
                        episode = ep.episode
                    )

                    newEpisode(
                        if (ep.id.isNullOrBlank()) {
                            episodeUrl
                        } else {
                            "$episodeUrl||${ep.id}"
                        }
                    ) {
                        name = ep.name
                        season = ep.season
                        episode = ep.episode
                    }
                }

                return newTvSeriesLoadResponse(
                    info.title,
                    clean,
                    info.type,
                    episodes
                ) {
                    posterUrl = info.poster
                    plot = info.plot
                    year = info.year
                }
            }

            else -> return null
        }
    }

    private fun parseDetail(
        document: Document,
        pageUrl: String
    ): DetailInfo? {
        val path = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")

        val mediaObject = extractInitialMediaObject(document)

        val mediaType = firstNonBlank(
            mediaObject?.optString("mediaType"),
            mediaObject?.optString("contentType")
        )?.lowercase(Locale.ROOT)

        val type = when {
            isAnimePath(path) || mediaType == "anime" -> TvType.Anime
            isTvPath(path) || mediaType == "tv" || mediaType == "series" -> TvType.TvSeries
            isMoviePath(path) || mediaType == "movie" -> TvType.Movie
            else -> return null
        }

        val title = firstNonBlank(
            mediaObject?.optString("title"),
            extractMeta(document, "property=og:title"),
            document.selectFirst("h1")?.text(),
            titleFromPath(path)
        )?.let(::cleanDetailTitle)
            ?: return null

        val poster = firstNonBlank(
            mediaObject?.optString("posterPath"),
            extractMeta(document, "property=og:image"),
            extractMeta(document, "name=twitter:image")
        )

        val plot = firstNonBlank(
            mediaObject?.optString("overview"),
            extractMeta(document, "property=og:description"),
            document.selectFirst("[class*=overview], [class*=description], p")?.text()
        )

        val year = mediaObject?.optString("releaseYear")
            ?.toIntOrNull()
            ?: Regex("\\b(19|20)\\d{2}\\b")
                .find(document.title())
                ?.value
                ?.toIntOrNull()

        val episodes = if (type == TvType.Movie) {
            emptyList()
        } else {
            mergeEpisodeMetadata(
                embedded = parseEpisodes(mediaObject),
                linked = parseEpisodeLinks(document, pageUrl),
                generated = generateMissingEpisodes(mediaObject)
            )
        }

        return DetailInfo(
            title = title,
            poster = poster,
            plot = plot,
            year = year,
            type = type,
            episodes = episodes
        )
    }

    private fun parseEpisodes(
        mediaObject: JSONObject?
    ): List<EpisodeInfo> {
        val seasons = mediaObject?.optJSONArray("seasons") ?: return emptyList()
        val result = ArrayList<EpisodeInfo>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNumber = season.optInt("seasonNumber", -1)
            if (seasonNumber < 0) continue

            val episodes = season.optJSONArray("episodes") ?: continue

            for (j in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(j) ?: continue
                val number = episode.optInt("episodeNumber", -1)
                if (number < 0) continue

                result += EpisodeInfo(
                    id = episode.optString("id").takeIf { it.isNotBlank() },
                    name = firstNonBlank(
                        episode.optString("name"),
                        "Episode $number"
                    ) ?: "Episode $number",
                    season = seasonNumber,
                    episode = number,
                    overview = episode.optString("overview").takeIf { it.isNotBlank() },
                    poster = episode.optString("stillPath").takeIf { it.isNotBlank() },
                    runtime = episode.optInt("runtime", 0).takeIf { it > 0 }
                )
            }
        }

        return result.sortedWith(
            compareBy<EpisodeInfo> { it.season }
                .thenBy { it.episode }
        )
    }

    private fun parseEpisodeLinks(
        document: Document,
        pageUrl: String
    ): List<EpisodeInfo> {
        val result = ArrayList<EpisodeInfo>()

        document.select("a[href]").forEach { anchor ->
            val href = normalizeExtractedUrl(anchor.attr("href"), pageUrl) ?: return@forEach
            val path = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
            if (!isEpisodePath(path)) return@forEach

            val parts = path.trim('/').split('/').filter { it.isNotBlank() }
            if (parts.size < 4) return@forEach
            val season = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return@forEach
            val episode = parts.lastOrNull()?.toIntOrNull() ?: return@forEach
            if (season < 0 || episode <= 0) return@forEach

            val title = firstNonBlank(
                anchor.attr("aria-label"),
                anchor.attr("title"),
                anchor.text().trim()
            )?.let(::cleanCardTitle)
                ?.takeIf { it.isNotBlank() }
                ?: "Episode $episode"

            val poster = extractCardPoster(anchor, pageUrl)

            result += EpisodeInfo(
                id = null,
                name = title,
                season = season,
                episode = episode,
                overview = null,
                poster = poster,
                runtime = null
            )
        }

        return result
    }

    private fun generateMissingEpisodes(
        mediaObject: JSONObject?
    ): List<EpisodeInfo> {
        val seasons = mediaObject?.optJSONArray("seasons") ?: return emptyList()
        val result = ArrayList<EpisodeInfo>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNumber = season.optInt("seasonNumber", -1)
            val episodeCount = season.optInt("episodeCount", 0)
            if (seasonNumber < 0 || episodeCount <= 0) continue

            val embeddedCount = season.optJSONArray("episodes")?.length() ?: 0
            if (embeddedCount >= episodeCount) continue

            for (number in 1..episodeCount.coerceAtMost(5000)) {
                result += EpisodeInfo(
                    id = null,
                    name = "Episode $number",
                    season = seasonNumber,
                    episode = number,
                    overview = null,
                    poster = season.optString("posterPath").takeIf { it.isNotBlank() },
                    runtime = null
                )
            }
        }

        return result
    }

    private fun mergeEpisodeMetadata(
        embedded: List<EpisodeInfo>,
        linked: List<EpisodeInfo>,
        generated: List<EpisodeInfo>
    ): List<EpisodeInfo> {
        val result = linkedMapOf<String, EpisodeInfo>()

        fun add(item: EpisodeInfo) {
            val key = "${item.season}|${item.episode}"
            val existing = result[key]
            result[key] = when {
                existing == null -> item
                existing.name.startsWith("Episode ") && !item.name.startsWith("Episode ") -> item
                existing.poster.isNullOrBlank() && !item.poster.isNullOrBlank() -> existing.copy(poster = item.poster)
                existing.overview.isNullOrBlank() && !item.overview.isNullOrBlank() -> existing.copy(overview = item.overview)
                else -> existing
            }
        }

        linked.forEach(::add)
        embedded.forEach(::add)
        generated.forEach(::add)

        return result.values
            .sortedWith(compareBy<EpisodeInfo> { it.season }.thenBy { it.episode })
    }

    private fun buildEpisodeUrl(
        baseUrl: String,
        title: String,
        season: Int,
        episode: Int
    ): String {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
            ?: return baseUrl

        val parts = uri.path.orEmpty()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .toMutableList()

        if (parts.size >= 3 &&
            (parts[0].equals("tv", true) || parts[0].equals("anime", true))
        ) {
            parts[0] = parts[0]
            parts.truncateAfter(2)
        }

        if (parts.size < 2) return baseUrl

        val mediaType = parts[0]
        val id = parts[1]
        val slug = parts.getOrNull(2)
            ?: slugify(title).ifBlank { id }

        return "$BASE_URL/$mediaType/$id/$slug/$season/$episode"
    }

    // ---------------------------------------------------------------------
    // LINK LOADER / FRESH SOURCE RESOLUTION
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val separator = data.indexOf("||")

        val pageUrl = if (separator >= 0) {
            data.substring(0, separator).trim()
        } else {
            data.trim()
        }

        val episodeId = if (separator >= 0) {
            data.substring(separator + 2).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }

        if (!pageUrl.startsWith("http", true)) return false

        val playbackContext = parsePlaybackContext(pageUrl)

        /*
         * Every Play action starts from a fresh request. No tokenized media URL
         * is retained between plays.
         */
        val directFetched = withTimeoutOrNull(SERVER_PAGE_TIMEOUT_MS * 2) {
            fetchPage(pageUrl, attempts = DETAIL_FETCH_ATTEMPTS)
        } ?: return false

        val directPage = directFetched.document
        val directText = directFetched.text

        /*
         * Zoryva has an official extraction endpoint used by the browser player.
         * Resolve that endpoint from the same fresh detail-page metadata before
         * falling back to generic server discovery. This is the primary source
         * path because it is the website's own current playback contract.
         */
        val extractResolution = withTimeoutOrNull(ZORYVA_EXTRACT_TIMEOUT_MS) {
            resolveZoryvaExtract(
                document = directPage,
                pageUrl = pageUrl,
                context = playbackContext
            )
        } ?: ZoryvaExtractResolution(emptyList(), emptyList())

        val scrapedResolution = withTimeoutOrNull(ZORYVA_SCRAPED_TIMEOUT_MS) {
            resolveZoryvaScraped(
                document = directPage,
                pageUrl = pageUrl,
                context = playbackContext
            )
        } ?: ZoryvaExtractResolution(emptyList(), emptyList())

        val directResult = inspectPageForSources(
            page = directPage,
            rawText = directText,
            pageUrl = pageUrl,
            serverName = "Zoryva direct"
        )

        val discoveredServerPages = discoverServerPages(
            document = directPage,
            rawText = directText,
            pageUrl = pageUrl,
            episodeId = episodeId
        )

        /*
         * The important change: Vidrock is resolved through its current API,
         * not by guessing /movie/{id} or /tv/{id}/{season}/{episode} pages.
         * The API response is expanded into every current server target and
         * every direct media URL it exposes.
         */
        val vidrock = withTimeoutOrNull(VIDROCK_API_TIMEOUT_MS) {
            resolveVidrock(playbackContext)
        } ?: VidrockResolution(emptyList(), emptyList())

        val targetMap = linkedMapOf<String, VidrockServerTarget>()

        extractResolution.servers.forEach { target ->
            val clean = cleanUrl(target.url)
            if (clean.isNotBlank()) {
                targetMap.putIfAbsent(clean, target)
            }
        }

        scrapedResolution.servers.forEach { target ->
            val clean = cleanUrl(target.url)
            if (clean.isNotBlank()) {
                targetMap.putIfAbsent(clean, target)
            }
        }

        discoveredServerPages.forEach { (url, label) ->
            val clean = cleanUrl(url)
            if (clean.isNotBlank()) {
                targetMap.putIfAbsent(
                    clean,
                    VidrockServerTarget(
                        url = clean,
                        name = label.ifBlank { "Zoryva Server" },
                        referer = pageUrl
                    )
                )
            }
        }

        /*
         * Always include Zoryva's own embedded-player route as a fresh fallback.
         * This mirrors the website's browser flow instead of relying only on
         * server buttons that may be populated dynamically by JavaScript.
         */
        buildZoryvaEmbedUrls(playbackContext).forEach { embedUrl ->
            targetMap.putIfAbsent(
                embedUrl,
                VidrockServerTarget(
                    url = embedUrl,
                    name = "Zoryva Embedded Player",
                    referer = pageUrl
                )
            )
        }

        vidrock.servers.forEach { target ->
            targetMap.putIfAbsent(cleanUrl(target.url), target)
        }

        val results = ArrayList<ServerResult>()
        if (directResult.sources.isNotEmpty()) {
            results += directResult
        }

        /*
         * All discovered servers are resolved concurrently. We do not wait for
         * server 1 before starting server 2/3/4/etc.
         */
        coroutineScope {
            val jobs = targetMap.values
                .filter { it.url.isNotBlank() }
                .take(VIDROCK_MAX_SERVERS)
                .map { target ->
                    async {
                        inspectServerTarget(
                            url = target.url,
                            name = target.name,
                            depth = 0,
                            episodeId = episodeId,
                            referer = target.referer
                        )
                    }
                }

            results += jobs.awaitAll().flatten()
        }

        /*
         * Direct URLs returned by the Vidrock API still need to pass the same
         * real playability validation. A 200 response containing an HTML error
         * page is NOT considered a playable source.
         */
        if (extractResolution.directSources.isNotEmpty()) {
            val extractServer = inspectCandidatesAsServer(
                candidates = extractResolution.directSources,
                serverName = "Zoryva Extract"
            )
            if (extractServer.sources.isNotEmpty()) {
                results += extractServer
            }
        }

        if (scrapedResolution.directSources.isNotEmpty()) {
            val scrapedServer = inspectCandidatesAsServer(
                candidates = scrapedResolution.directSources,
                serverName = "Zoryva Scraped"
            )
            if (scrapedServer.sources.isNotEmpty()) {
                results += scrapedServer
            }
        }

        if (vidrock.directSources.isNotEmpty()) {
            val directServer = inspectCandidatesAsServer(
                candidates = vidrock.directSources,
                serverName = "Vidrock API"
            )
            if (directServer.sources.isNotEmpty()) {
                results += directServer
            }
        }

        val usable = results
            .filter { it.sources.isNotEmpty() }
            .flatMap { result ->
                result.sources.map { source ->
                    result to source
                }
            }
            .sortedWith(
                compareBy<Pair<ServerResult, MediaCandidate>> {
                    it.first.latencyMs
                }
                    .thenByDescending { it.second.quality }
                    .thenBy { it.second.server.lowercase(Locale.ROOT) }
            )

        if (usable.isEmpty()) return false

        val emitted = linkedSetOf<String>()
        val subtitleSeen = linkedSetOf<String>()

        /*
         * Emit only sources that survived actual probing. The first source is
         * the fastest confirmed playable source. Other confirmed playable
         * sources remain available as fallbacks. Dead links are never emitted.
         */
        for ((server, source) in usable) {
            for ((language, subtitleUrl) in server.subtitles) {
                val key = "$language|$subtitleUrl"
                if (subtitleSeen.add(key)) {
                    subtitleCallback(
                        newSubtitleFile(language, subtitleUrl)
                    )
                }
            }

            val sourceKey =
                "${source.quality}|${normalizeMediaIdentity(source.url)}"

            if (!emitted.add(sourceKey)) continue
            if (emitted.size > MAX_EMITTED_FALLBACKS) break

            val output = buildPlaybackOutput(
                source = source,
                pageUrl = pageUrl
            )

            val cleanUrl = output.url
            val lowered = cleanUrl
                .substringBefore('?')
                .lowercase(Locale.ROOT)

            val linkType = when {
                lowered.endsWith(".m3u8") -> ExtractorLinkType.M3U8
                lowered.endsWith(".mpd") -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    name,
                    buildSourceName(source),
                    cleanUrl,
                    linkType
                ) {
                    quality = if (source.quality > 0) {
                        source.quality
                    } else {
                        Qualities.Unknown.value
                    }
                    referer = output.referer
                    headers = output.headers
                }
            )
        }

        return emitted.isNotEmpty()
    }

    private suspend fun inspectCandidatesAsServer(
        candidates: List<MediaCandidate>,
        serverName: String
    ): ServerResult {
        val started = System.currentTimeMillis()

        val inspected = coroutineScope {
            candidates
                .distinctBy { normalizeMediaIdentity(it.url) }
                .map { candidate ->
                    async {
                        val hls = if (candidate.url.contains(".m3u8", true)) {
                            inspectHls(candidate)
                        } else {
                            null
                        }

                        val playable = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                            probeSource(candidate, hls)
                        } ?: false

                        if (!playable) return@async null

                        if (hls != null) {
                            candidate.copy(
                                quality = maxOf(candidate.quality, hls.maxQuality),
                                isHlsMaster = hls.isMaster,
                                audioLabel = hls.audioLabel
                            ) to hls
                        } else {
                            candidate to null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }

        val subtitles = linkedSetOf<Pair<String, String>>()
        inspected.forEach { pair ->
            pair.second?.let { subtitles.addAll(it.subtitles) }
        }

        return ServerResult(
            serverUrl = serverName,
            serverName = serverName,
            latencyMs = System.currentTimeMillis() - started,
            sources = inspected.map { it.first },
            subtitles = subtitles.toList()
        )
    }

    private fun playbackHeadersFor(
        source: MediaCandidate
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()

        /*
         * Keep the browser-like request deliberately small. In particular we
         * do not manufacture Cookie or Authorization values.
         */
        result["User-Agent"] = USER_AGENT
        result["Accept"] = ACCEPT
        result["Accept-Language"] = "en-US,en;q=0.9"
        val sourceOrigin = source.origin.trim()
        if (sourceOrigin.startsWith("http", true)) {
            result["Origin"] = sourceOrigin
        }
        result["Cache-Control"] = "no-cache"
        result["Pragma"] = "no-cache"

        return result
    }

    private data class PlaybackContext(
        val type: String,
        val tmdbId: String,
        val season: Int?,
        val episode: Int?
    )

    private fun parsePlaybackContext(
        pageUrl: String
    ): PlaybackContext? {
        val path = runCatching { URI(pageUrl).path.orEmpty() }
            .getOrDefault("")

        val parts = path
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        if (parts.size < 2) return null

        val type = when {
            parts[0].equals("movie", true) -> "movie"
            parts[0].equals("tv", true) -> "tv"
            parts[0].equals("anime", true) -> "anime"
            else -> return null
        }

        val tmdbId = parts[1]
            .takeIf { it.all(Char::isDigit) }
            ?: return null

        if (type == "movie") {
            return PlaybackContext(
                type = "movie",
                tmdbId = tmdbId,
                season = null,
                episode = null
            )
        }

        /* Canonical Zoryva episode URL: /tv/{id}/{slug}/{season}/{episode} */
        if (parts.size >= 5) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()

            if (season != null && episode != null) {
                return PlaybackContext(
                    type = type,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }
        }

        /* Accept /tv/{id}/{season}/{episode} as a secondary form. */
        if (parts.size >= 4) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                return PlaybackContext(
                    type = type,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode
                )
            }
        }

        return PlaybackContext(
            type = type,
            tmdbId = tmdbId,
            season = null,
            episode = null
        )
    }

    private fun buildZoryvaEmbedUrls(
        context: PlaybackContext?
    ): List<String> {
        context ?: return emptyList()

        val type = if (context.type.equals("movie", true)) "movie" else "tv"
        val base = "$BASE_URL/Api/Embedded"
        val query = StringBuilder()
            .append("?id=")
            .append(URLEncoder.encode(context.tmdbId, "UTF-8"))
            .append("&type=")
            .append(URLEncoder.encode(type, "UTF-8"))

        if (type == "tv" && context.season != null && context.episode != null) {
            query.append("&season=").append(context.season)
            query.append("&episode=").append(context.episode)
        }

        return listOf(base + query.toString())
    }

    private suspend fun resolveZoryvaExtract(
        document: Document?,
        pageUrl: String,
        context: PlaybackContext?
    ): ZoryvaExtractResolution {
        context ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        val mediaObject = extractInitialMediaObject(document)

        val mediaType = when (context.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "tv", "anime" -> "tv"
            else -> return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        val title = firstNonBlank(
            mediaObject?.optString("title"),
            document?.selectFirst("h1")?.text(),
            titleFromPath(URI(pageUrl).path.orEmpty())
        ).orEmpty()

        val originalTitle = firstNonBlank(
            mediaObject?.optString("originalTitle"),
            title
        ).orEmpty()

        val imdbId = firstNonBlank(
            mediaObject?.optString("imdbId"),
            extractImdbId(document?.html().orEmpty())
        )

        val runtime = mediaObject?.optInt("runtime", 0)?.takeIf { it > 0 }
            ?: Regex("""(?i)\"runtime\"\s*:\s*(\d+)""")
                .find(document?.html().orEmpty())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val query = buildPlaybackQuery(
            mediaType = mediaType,
            externalId = context.tmdbId,
            title = title,
            originalTitle = originalTitle,
            imdbId = imdbId,
            runtime = runtime,
            season = context.season,
            episode = context.episode
        )

        val endpoint = "$BASE_URL/api/extract?$query"

        val response = runCatching {
            app.get(
                endpoint,
                headers = PAGE_HEADERS + mapOf(
                    "Referer" to pageUrl,
                    "Accept" to "application/json,text/plain,*/*;q=0.8",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        if (response.code !in 200..399) {
            return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        return parseZoryvaExtractResponse(
            raw = response.text,
            referer = pageUrl
        )
    }

    private suspend fun resolveZoryvaScraped(
        document: Document?,
        pageUrl: String,
        context: PlaybackContext?
    ): ZoryvaExtractResolution {
        context ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        val mediaObject = extractInitialMediaObject(document)
        val mediaType = when (context.type.lowercase(Locale.ROOT)) {
            "movie" -> "movie"
            "tv", "anime" -> "tv"
            else -> return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        val title = firstNonBlank(
            mediaObject?.optString("title"),
            document?.selectFirst("h1")?.text(),
            titleFromPath(URI(pageUrl).path.orEmpty())
        ).orEmpty()

        val originalTitle = firstNonBlank(
            mediaObject?.optString("originalTitle"),
            title
        ).orEmpty()

        val imdbId = firstNonBlank(
            mediaObject?.optString("imdbId"),
            extractImdbId(document?.html().orEmpty())
        )

        val runtime = mediaObject?.optInt("runtime", 0)?.takeIf { it > 0 }

        val query = buildPlaybackQuery(
            mediaType = mediaType,
            externalId = context.tmdbId,
            title = title,
            originalTitle = originalTitle,
            imdbId = imdbId,
            runtime = runtime,
            season = context.season,
            episode = context.episode
        )

        val endpoint = "$BASE_URL/api/scraped?$query"
        val response = runCatching {
            app.get(
                endpoint,
                headers = PAGE_HEADERS + mapOf(
                    "Referer" to pageUrl,
                    "Accept" to "application/json,text/plain,*/*;q=0.8",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return ZoryvaExtractResolution(emptyList(), emptyList())

        if (response.code !in 200..399) {
            return ZoryvaExtractResolution(emptyList(), emptyList())
        }

        return parseZoryvaExtractResponse(
            raw = response.text,
            referer = pageUrl
        )
    }

    private fun buildPlaybackQuery(
        mediaType: String,
        externalId: String,
        title: String,
        originalTitle: String,
        imdbId: String?,
        runtime: Int?,
        season: Int?,
        episode: Int?
    ): String {
        val parts = ArrayList<String>()
        fun add(name: String, value: String?) {
            if (!value.isNullOrBlank()) {
                parts += "${name}=${encode(value)}"
            }
        }

        add("mediaType", mediaType)
        add("externalId", externalId)
        add("title", title)
        add("originalTitle", originalTitle)
        add("imdbId", imdbId)
        runtime?.takeIf { it > 0 }?.let { parts += "runtime=$it" }
        season?.let { parts += "season=$it" }
        episode?.let { parts += "episode=$it" }
        parts += "soft=1"

        return parts.joinToString("&")
    }

    private fun extractImdbId(
        raw: String
    ): String? {
        if (raw.isBlank()) return null
        return Regex("""(?i)\"imdbId\"\s*:\s*\"(tt\d{4,12})\""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""(?i)(?:imdb\s*id|imdbId)[\s:=\"']+(tt\d{4,12})""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
    }

    private fun parseZoryvaExtractResponse(
        raw: String,
        referer: String
    ): ZoryvaExtractResolution {
        val direct = linkedMapOf<String, MediaCandidate>()
        val servers = linkedMapOf<String, VidrockServerTarget>()

        fun addMedia(
            rawUrl: String,
            label: String = "Zoryva Extract",
            sourceReferer: String = referer,
            sourceOrigin: String = originOf(sourceReferer),
            qualityHint: String = "",
            height: Int? = null
        ) {
            val clean0 = cleanUrl(rawUrl)
            if (clean0.isBlank()) return

            val upstream = if (clean0.startsWith("$BASE_URL/api/proxy?", true)) {
                extractProxyParameter(clean0, "url") ?: return
            } else {
                clean0
            }

            val clean = cleanUrl(upstream)
            if (!isPlayableMedia(clean)) return
            if (isObviouslyPromotional(clean) || isIgnoredHost(clean)) return

            val finalReferer = if (clean.contains("peakstorm.top/", true)) {
                firstNonBlank(
                    extractProxyParameter(clean0, "referer"),
                    sourceReferer
                ).orEmpty().ifBlank { "https://speedracelight.com/" }
            } else {
                sourceReferer.ifBlank { referer }
            }

            val finalOrigin = if (clean.contains("peakstorm.top/", true)) {
                firstNonBlank(
                    extractProxyParameter(clean0, "origin"),
                    sourceOrigin
                ).orEmpty().ifBlank { originOf(finalReferer) }
            } else {
                sourceOrigin.ifBlank { originOf(finalReferer) }
            }

            val quality = when {
                height != null && height > 0 -> qualityFromHeight(height)
                else -> qualityFromUrl(clean, qualityHint.ifBlank { label })
            }

            direct.putIfAbsent(
                normalizeMediaIdentity(clean),
                MediaCandidate(
                    url = clean,
                    referer = finalReferer,
                    origin = finalOrigin,
                    quality = quality,
                    label = qualityHint.ifBlank { label },
                    server = label.ifBlank { "Zoryva Extract" },
                    latencyMs = 0L,
                    isHlsMaster = false,
                    audioLabel = ""
                )
            )
        }

        fun addServer(
            rawUrl: String,
            label: String = "Zoryva Server",
            sourceReferer: String = referer
        ) {
            val clean = normalizeExtractedUrl(rawUrl, sourceReferer) ?: return
            if (clean.isBlank()) return
            if (isPlayableMedia(clean)) {
                addMedia(clean, label, sourceReferer)
                return
            }
            if (isObviouslyPromotional(clean) || isIgnoredHost(clean)) return

            servers.putIfAbsent(
                "$label|$clean",
                VidrockServerTarget(
                    url = clean,
                    name = label.ifBlank { "Zoryva Server" },
                    referer = sourceReferer
                )
            )
        }

        fun walkJson(
            value: Any?,
            parentKey: String = "",
            parentReferer: String = referer,
            parentOrigin: String = originOf(referer),
            parentLabel: String = "Zoryva Extract"
        ) {
            when (value) {
                is JSONObject -> {
                    val objectReferer = firstNonBlank(
                        value.optString("referer"),
                        value.optString("referrer"),
                        value.optString("httpReferer")
                    ).orEmpty().ifBlank { parentReferer }

                    val objectOrigin = firstNonBlank(
                        value.optString("origin"),
                        value.optString("httpOrigin")
                    ).orEmpty().ifBlank { parentOrigin }

                    val objectLabel = firstNonBlank(
                        value.optString("name"),
                        value.optString("server"),
                        value.optString("provider"),
                        value.optString("sourceName"),
                        value.optString("source"),
                        value.optString("quality"),
                        parentKey,
                        parentLabel
                    ).orEmpty()

                    val broken = value.optBoolean("broken", false)
                    val disabled = value.optBoolean("disabled", false)
                    if (!broken && !disabled) {
                        val qualityHint = firstNonBlank(
                            value.optString("quality"),
                            value.optString("resolution"),
                            value.optString("label")
                        ).orEmpty()
                        val height = value.optInt("height", 0).takeIf { it > 0 }

                        val directKeys = listOf(
                            "url", "src", "file", "link", "streamUrl",
                            "stream_url", "videoUrl", "video_url", "playUrl",
                            "play_url", "mediaUrl", "media_url", "sourceUrl",
                            "source_url", "manifest", "playlist", "m3u8", "mp4"
                        )

                        directKeys.forEach { key ->
                            val candidate = value.optString(key).trim()
                            if (candidate.isNotBlank()) {
                                if (isPlayableMedia(candidate)) {
                                    addMedia(
                                        candidate,
                                        objectLabel.ifBlank { key },
                                        objectReferer,
                                        objectOrigin,
                                        qualityHint.ifBlank { key },
                                        height
                                    )
                                } else if (
                                    key.contains("server", true) ||
                                    key.contains("source", true) ||
                                    key.contains("player", true) ||
                                    key.contains("embed", true)
                                ) {
                                    addServer(candidate, objectLabel.ifBlank { key }, objectReferer)
                                }
                            }
                        }
                    }

                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        walkJson(
                            value.opt(key),
                            parentKey = key,
                            parentReferer = objectReferer,
                            parentOrigin = objectOrigin,
                            parentLabel = objectLabel
                        )
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walkJson(
                            value.opt(i),
                            parentKey = parentKey,
                            parentReferer = parentReferer,
                            parentOrigin = parentOrigin,
                            parentLabel = parentLabel
                        )
                    }
                }

                is String -> {
                    val text = value.trim()
                    if (text.isBlank()) return

                    extractUrlsFromRawText(text).forEach { found ->
                        if (isPlayableMedia(found)) {
                            addMedia(
                                found,
                                parentLabel,
                                parentReferer,
                                parentOrigin
                            )
                        } else if (
                            parentKey.contains("server", true) ||
                            parentKey.contains("source", true) ||
                            parentKey.contains("player", true) ||
                            parentKey.contains("embed", true)
                        ) {
                            addServer(found, parentLabel, parentReferer)
                        }
                    }
                }
            }
        }

        runCatching { walkJson(JSONObject(raw)) }
        runCatching { walkJson(JSONArray(raw)) }

        /* The endpoint is JSON in the normal case, but keep a raw-text fallback
         * for escaped/serialized response variants. */
        extractUrlsFromRawText(raw).forEach { found ->
            if (isPlayableMedia(found)) {
                addMedia(found)
            } else if (
                found.contains("server", true) ||
                found.contains("embed", true) ||
                found.contains("player", true)
            ) {
                addServer(found)
            }
        }

        return ZoryvaExtractResolution(
            directSources = direct.values.toList(),
            servers = servers.values.toList()
        )
    }

    private suspend fun resolveVidrock(
        context: PlaybackContext?
    ): VidrockResolution {
        context ?: return VidrockResolution(emptyList(), emptyList())

        val key = withTimeoutOrNull(VIDROCK_KEY_TIMEOUT_MS) {
            fetchVidrockKey()
        } ?: return VidrockResolution(emptyList(), emptyList())

        if (key.length < 16) return VidrockResolution(emptyList(), emptyList())

        val mediaKind = when {
            context.type == "movie" -> "movie"
            (context.type == "tv" || context.type == "anime") &&
                context.season != null && context.episode != null -> "tv"
            else -> "movie"
        }

        val plainIdentifier = if (mediaKind == "movie") {
            context.tmdbId
        } else {
            "${context.tmdbId}_${context.season}_${context.episode}"
        }

        val encoded = encryptVidrockIdentifier(
            plain = plainIdentifier,
            key = key
        ) ?: return VidrockResolution(emptyList(), emptyList())

        val apiUrl =
            "$VIDROCK_ORIGIN/api/$mediaKind/$encoded"

        val response = withTimeoutOrNull(VIDROCK_API_TIMEOUT_MS) {
            runCatching {
                app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json,text/plain,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Origin" to VIDROCK_ORIGIN,
                        "Referer" to "$VIDROCK_ORIGIN/"
                    )
                )
            }.getOrNull()
        } ?: return VidrockResolution(emptyList(), emptyList())

        if (response.code !in 200..399) {
            return VidrockResolution(emptyList(), emptyList())
        }

        return parseVidrockResponse(
            raw = response.text,
            key = key
        )
    }

    private suspend fun fetchVidrockKey(): String? {
        val page = withTimeoutOrNull(VIDROCK_SCRIPT_TIMEOUT_MS) {
            runCatching {
                app.get(
                    "$VIDROCK_ORIGIN/",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache",
                        "Referer" to "$VIDROCK_ORIGIN/"
                    )
                )
            }.getOrNull()
        } ?: return null

        if (page.code !in 200..399) return null

        val scriptUrls = linkedSetOf<String>()
        page.document?.select("script[src]")?.forEach { script ->
            val absolute = script.absUrl("src").trim()
            if (absolute.startsWith("http", true)) {
                scriptUrls.add(absolute)
            }
        }

        /* Some crawlers expose chunk URLs only in raw HTML. */
        Regex(
            """(?:https?:)?//[^\"'\\s]+(?:\.js(?:\?[^\"'\\s]*)?)""",
            RegexOption.IGNORE_CASE
        ).findAll(page.text).forEach { match ->
            val raw = match.value
            val absolute = if (raw.startsWith("//")) {
                "https:$raw"
            } else {
                raw
            }
            scriptUrls.add(cleanUrl(absolute))
        }

        val bounded = scriptUrls
            .take(VIDROCK_SCRIPT_LIMIT)
            .toList()

        val scriptBodies = coroutineScope {
            bounded.map { url ->
                async {
                    withTimeoutOrNull(VIDROCK_SCRIPT_TIMEOUT_MS) {
                        runCatching {
                            app.get(
                                url,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "*/*",
                                    "Referer" to "$VIDROCK_ORIGIN/",
                                    "Cache-Control" to "no-cache",
                                    "Pragma" to "no-cache"
                                )
                            ).text
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val keyPatterns = listOf(
            Regex("""(?i)(?:const|let|var)\\s+qw\\s*=\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?i)(?:^|[,{;])\\s*qw\\s*[:=]\\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?i)[\"']qw[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']""")
        )

        for (body in scriptBodies) {
            for (pattern in keyPatterns) {
                val match = pattern.find(body) ?: continue
                val key = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (key.length >= 16) return key
            }
        }

        return null
    }

    private fun encryptVidrockIdentifier(
        plain: String,
        key: String
    ): String? {
        return runCatching {
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            if (keyBytes.size < 16) return null

            val aesKey = when {
                keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32 -> keyBytes
                else -> MessageDigest
                    .getInstance("SHA-256")
                    .digest(keyBytes)
            }

            val ivBytes = key
                .toByteArray(StandardCharsets.UTF_8)
                .copyOfRange(0, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(ivBytes)
            )

            base64UrlNoPadding(
                cipher.doFinal(
                    plain.toByteArray(StandardCharsets.UTF_8)
                )
            )
        }.getOrNull()
    }

    private fun parseVidrockResponse(
        raw: String,
        key: String
    ): VidrockResolution {
        val direct = linkedMapOf<String, MediaCandidate>()
        val servers = linkedMapOf<String, VidrockServerTarget>()

        fun addDirect(
            url: String,
            label: String = "Vidrock",
            referer: String = "$VIDROCK_ORIGIN/"
        ) {
            val clean = normalizeExtractedUrl(url, referer) ?: return
            if (!isPlayableMedia(clean)) return
            if (isObviouslyPromotional(clean)) return
            if (isIgnoredHost(clean)) return

            val resolvedReferer = mediaRefererFor(clean, referer)

            val candidate = MediaCandidate(
                url = clean,
                referer = resolvedReferer,
                origin = originOf(resolvedReferer),
                quality = qualityFromUrl(clean, label),
                label = label,
                server = label,
                latencyMs = 0L,
                isHlsMaster = false,
                audioLabel = ""
            )

            direct.putIfAbsent(
                normalizeMediaIdentity(clean),
                candidate
            )
        }

        fun addServer(
            url: String,
            name: String,
            referer: String = "$VIDROCK_ORIGIN/"
        ) {
            val clean = cleanUrl(url)
            if (clean.isBlank()) return
            if (isPlayableMedia(clean)) {
                addDirect(clean, name, referer)
                return
            }
            if (isIgnoredHost(clean) || isObviouslyPromotional(clean)) return

            val keyId = "$name|$clean"
            servers.putIfAbsent(
                keyId,
                VidrockServerTarget(
                    url = clean,
                    name = name.ifBlank { "Vidrock Server" },
                    referer = referer
                )
            )
        }

        fun normalizePossibleUrl(value: String): String? {
            val cleaned = value
                .trim()
                .trim('"', '\'', '`')
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            return when {
                cleaned.startsWith("http://", true) -> cleaned
                cleaned.startsWith("https://", true) -> cleaned
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("/") -> "$VIDROCK_ORIGIN$cleaned"
                else -> null
            }
        }

        fun walk(value: Any?, parentKey: String = "") {
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val keyName = iterator.next()
                        val child = value.opt(keyName)

                        if (child is String) {
                            val str = child.trim()
                            val lowerKey = keyName.lowercase(Locale.ROOT)
                            val probableServerName = firstNonBlank(
                                value.optString("name"),
                                value.optString("title"),
                                value.optString("server"),
                                value.optString("provider"),
                                keyName
                            ).orEmpty()

                            normalizePossibleUrl(str)?.let { possible ->
                                when {
                                    isPlayableMedia(possible) ->
                                        addDirect(
                                            possible,
                                            probableServerName.ifBlank { "Vidrock" }
                                        )
                                    lowerKey.contains("server") ||
                                        lowerKey.contains("embed") ||
                                        lowerKey.contains("player") ||
                                        lowerKey.contains("source") ||
                                        lowerKey == "url" ||
                                        lowerKey == "link" ->
                                        addServer(
                                            possible,
                                            probableServerName.ifBlank { "Vidrock Server" }
                                        )
                                }
                            }

                            if (lowerKey.contains("m3u8") || lowerKey.contains("mp4")) {
                                normalizePossibleUrl(str)?.let { possible ->
                                    addDirect(
                                        possible,
                                        probableServerName.ifBlank { "Vidrock" }
                                    )
                                }
                            }
                        }

                        walk(child, keyName)
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walk(value.opt(i), parentKey)
                    }
                }

                is String -> {
                    extractUrlsFromRawText(value).forEach { url ->
                        addDirect(url, parentKey.ifBlank { "Vidrock" })
                    }
                }
            }
        }

        /* Plain JSON response first. */
        runCatching {
            walk(JSONObject(raw))
        }

        runCatching {
            val array = JSONArray(raw)
            walk(array)
        }

        /* Always scan the raw body for direct/disguised URLs. */
        extractUrlsFromRawText(raw).forEach { url ->
            addDirect(url)
        }

        /*
         * Current Vidrock implementations can return an encrypted payload.
         * Try JSON-declared encrypted fields first, then bounded candidate
         * strings. The crypto helper supports the common AES-256-GCM layouts
         * without storing any key or decrypted stream between plays.
         */
        val encryptedBlobs = collectEncryptedBlobs(raw)
        for (blob in encryptedBlobs.take(24)) {
            val plaintext = decryptVidrockGcm(
                blob = blob,
                key = key
            ) ?: continue

            parseVidrockDecryptedPayload(
                plaintext,
                direct,
                servers
            )
        }

        /* Some responses may use the older AES-CBC payload format. */
        for (blob in encryptedBlobs.take(12)) {
            val plaintext = decryptVidrockCbc(
                encrypted = blob.data,
                key = key,
                ivOverride = blob.iv
            ) ?: continue

            parseVidrockDecryptedPayload(
                plaintext,
                direct,
                servers
            )
        }

        return VidrockResolution(
            directSources = direct.values.toList(),
            servers = servers.values.toList()
        )
    }

    private data class EncryptedBlob(
        val data: String,
        val iv: String? = null,
        val tag: String? = null
    )

    private fun collectEncryptedBlobs(
        raw: String
    ): List<EncryptedBlob> {
        val result = linkedSetOf<String>()
        val blobs = mutableListOf<EncryptedBlob>()

        fun add(data: String?, iv: String? = null, tag: String? = null) {
            val clean = data?.trim().orEmpty()
            if (clean.length < 20) return
            val fingerprint = "$clean|${iv.orEmpty()}|${tag.orEmpty()}"
            if (result.add(fingerprint)) {
                blobs += EncryptedBlob(clean, iv, tag)
            }
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val dataKeys = listOf(
                        "data", "payload", "encrypted", "ciphertext",
                        "content", "result", "response"
                    )

                    for (dataKey in dataKeys) {
                        val data = value.optString(dataKey, "")
                        if (data.isBlank()) continue

                        val iv = firstNonBlank(
                            value.optString("iv", ""),
                            value.optString("nonce", ""),
                            value.optString("initializationVector", "")
                        )

                        val tag = firstNonBlank(
                            value.optString("tag", ""),
                            value.optString("authTag", "")
                        )

                        add(data, iv, tag)
                    }

                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        walk(value.opt(iterator.next()))
                    }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        walk(value.opt(i))
                    }
                }
            }
        }

        runCatching { walk(JSONObject(raw)) }
        runCatching { walk(JSONArray(raw)) }

        /* Bounded fallback: look for long base64/base64url strings in raw JSON. */
        Regex(
            """(?<![A-Za-z0-9_-])[A-Za-z0-9_+/=-]{32,}(?![A-Za-z0-9_-])"""
        ).findAll(raw).take(24).forEach { match ->
            add(match.value)
        }

        return blobs
    }

    private fun decryptVidrockGcm(
        blob: EncryptedBlob,
        key: String
    ): String? {
        val cipherBytes = base64DecodeAny(blob.data) ?: return null

        val extraTag = blob.tag?.let(::base64DecodeAny)
        val fullCipher = if (extraTag != null) {
            cipherBytes + extraTag
        } else {
            cipherBytes
        }

        val explicitIv = blob.iv?.let(::base64DecodeAny)
        val ivCandidates = linkedSetOf<ByteArray>()
        if (explicitIv != null) ivCandidates.add(explicitIv)
        if (fullCipher.size >= 12) ivCandidates.add(fullCipher.copyOfRange(0, 12))
        if (fullCipher.size >= 16) ivCandidates.add(fullCipher.copyOfRange(0, 16))
        if (fullCipher.size >= 12) ivCandidates.add(fullCipher.copyOfRange(fullCipher.size - 12, fullCipher.size))
        if (fullCipher.size >= 16) ivCandidates.add(fullCipher.copyOfRange(fullCipher.size - 16, fullCipher.size))

        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val keyCandidates = linkedSetOf<ByteArray>()
        if (keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            keyCandidates.add(keyBytes)
        }
        keyCandidates.add(MessageDigest.getInstance("SHA-256").digest(keyBytes))

        for (iv in ivCandidates) {
            if (iv.size !in 12..16) continue

            val payloadCandidates = linkedSetOf<ByteArray>()
            payloadCandidates.add(fullCipher)
            if (explicitIv == null && fullCipher.size > iv.size) {
                if (fullCipher.copyOfRange(0, iv.size).contentEquals(iv)) {
                    payloadCandidates.add(fullCipher.copyOfRange(iv.size, fullCipher.size))
                }
                if (fullCipher.copyOfRange(fullCipher.size - iv.size, fullCipher.size).contentEquals(iv)) {
                    payloadCandidates.add(fullCipher.copyOfRange(0, fullCipher.size - iv.size))
                }
            }

            for (payload in payloadCandidates) {
                if (payload.size < 17) continue
                for (secret in keyCandidates) {
                    val result = runCatching {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(secret, "AES"),
                            GCMParameterSpec(128, iv)
                        )
                        String(
                            cipher.doFinal(payload),
                            StandardCharsets.UTF_8
                        )
                    }.getOrNull() ?: continue

                    if (looksLikeUsefulVidrockPayload(result)) return result
                }
            }
        }

        return null
    }

    private fun decryptVidrockCbc(
        encrypted: String,
        key: String,
        ivOverride: String?
    ): String? {
        val all = base64DecodeAny(encrypted) ?: return null
        if (all.size < 17) return null

        val salt = all.copyOfRange(0, 16)
        val ciphertext = all.copyOfRange(16, all.size)

        val derived = ArrayList<Byte>(48)
        var previous = ByteArray(0)

        while (derived.size < 48) {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(previous)
            md5.update(key.toByteArray(StandardCharsets.UTF_8))
            md5.update(salt)
            previous = md5.digest()
            derived.addAll(previous.toList())
        }

        val aesKey = derived.take(32).toByteArray()
        val iv = ivOverride?.let(::base64DecodeAny)?.takeIf { it.size == 16 }
            ?: derived.drop(32).take(16).toByteArray()

        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(iv)
            )
            val plain = String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
            )
            plain.takeIf(::looksLikeUsefulVidrockPayload)
        }.getOrNull()
    }

    private fun parseVidrockDecryptedPayload(
        plaintext: String,
        direct: MutableMap<String, MediaCandidate>,
        servers: MutableMap<String, VidrockServerTarget>
    ) {
        val normalized = plaintext
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .trim()

        val urls = extractUrlsFromRawText(normalized)
        urls.forEach { url ->
            val clean = cleanUrl(url)
            if (!isPlayableMedia(clean)) {
                servers.putIfAbsent(
                    clean,
                    VidrockServerTarget(
                        url = clean,
                        name = "Vidrock Server",
                        referer = "$VIDROCK_ORIGIN/"
                    )
                )
            } else if (!isObviouslyPromotional(clean) && !isIgnoredHost(clean)) {
                direct.putIfAbsent(
                    normalizeMediaIdentity(clean),
                    MediaCandidate(
                        url = clean,
                        referer = "$VIDROCK_ORIGIN/",
                        origin = VIDROCK_ORIGIN,
                        quality = qualityFromUrl(clean, "Vidrock"),
                        label = "Vidrock",
                        server = "Vidrock",
                        latencyMs = 0L,
                        isHlsMaster = false,
                        audioLabel = ""
                    )
                )
            }
        }

        runCatching {
            val obj = JSONObject(normalized)
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val keyName = iterator.next()
                val child = obj.opt(keyName)
                if (child is String) {
                    val url = child.trim()
                    val possible = normalizeVidrockUrl(url) ?: continue
                    if (isPlayableMedia(possible)) {
                        if (!isObviouslyPromotional(possible) && !isIgnoredHost(possible)) {
                            direct.putIfAbsent(
                                normalizeMediaIdentity(possible),
                                MediaCandidate(
                                    url = possible,
                                    referer = "$VIDROCK_ORIGIN/",
                                    origin = VIDROCK_ORIGIN,
                                    quality = qualityFromUrl(possible, keyName),
                                    label = keyName,
                                    server = keyName,
                                    latencyMs = 0L,
                                    isHlsMaster = false,
                                    audioLabel = ""
                                )
                            )
                        }
                    } else if (
                        keyName.contains("server", true) ||
                        keyName.contains("source", true) ||
                        keyName.contains("embed", true) ||
                        keyName.contains("player", true)
                    ) {
                        servers.putIfAbsent(
                            possible,
                            VidrockServerTarget(
                                url = possible,
                                name = keyName,
                                referer = "$VIDROCK_ORIGIN/"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun normalizeVidrockUrl(
        value: String
    ): String? {
        val cleaned = value
            .trim()
            .trim('"', '\'', '`')
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        return when {
            cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("http://", true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$VIDROCK_ORIGIN$cleaned"
            else -> null
        }
    }

    private fun extractUrlsFromRawText(
        raw: String
    ): List<String> {
        if (raw.isBlank()) return emptyList()

        val result = linkedSetOf<String>()
        val normalized = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        Regex(
            """https?://[^\"'<>\\s\\]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { match ->
            val clean = cleanUrl(match.value)
            if (clean.isNotBlank()) result.add(clean)
        }

        Regex(
            """(?i)(?:^|[\"'=:,])(\\/[^\"'<>\\s]+)(?=$|[\"'`,}])"""
        ).findAll(normalized).forEach { match ->
            val value = match.groupValues[1]
            if (
                value.contains(".m3u8", true) ||
                value.contains(".mp4", true) ||
                value.contains("staticreverie", true) ||
                value.contains("vidrock", true) ||
                value.contains("embed", true) ||
                value.contains("player", true)
            ) {
                result.add(
                    if (value.startsWith("/")) {
                        "$VIDROCK_ORIGIN$value"
                    } else {
                        value
                    }
                )
            }
        }

        return result.toList()
    }

    private fun looksLikeUsefulVidrockPayload(
        plaintext: String
    ): Boolean {
        val value = plaintext.trim()
        if (value.isBlank()) return false
        if (value.startsWith("{") || value.startsWith("[")) return true
        return value.contains("http://", true) ||
            value.contains("https://", true) ||
            value.contains("m3u8", true) ||
            value.contains("mp4", true) ||
            value.contains("staticreverie", true)
    }

    private suspend fun inspectServerTarget(
        url: String,
        name: String,
        depth: Int,
        episodeId: String? = null,
        referer: String = "$VIDROCK_ORIGIN/"
    ): List<ServerResult> {
        if (depth > MAX_CRAWL_DEPTH) return emptyList()
        if (url.isBlank()) return emptyList()
        if (isIgnoredHost(url)) return emptyList()
        if (isObviouslyPromotional(url)) return emptyList()

        val started = System.currentTimeMillis()

        val fetched = withTimeoutOrNull(SERVER_PAGE_TIMEOUT_MS) {
            fetchPage(
                url = url,
                attempts = 2,
                refererOverride = referer
            )
        } ?: return emptyList()

        val document = fetched.document
        val current = inspectPageForSources(
            page = document,
            rawText = fetched.text,
            pageUrl = url,
            serverName = name
        ).copy(
            latencyMs = System.currentTimeMillis() - started
        )

        if (current.sources.isNotEmpty()) {
            return listOf(current)
        }

        val nested = discoverNestedPlayerPages(
            document = document,
            rawText = fetched.text,
            pageUrl = url
        ).take(MAX_SERVER_PAGES)

        if (nested.isEmpty()) return emptyList()

        return coroutineScope {
            nested.map { (nestedUrl, nestedName) ->
                async {
                    inspectServerTarget(
                        url = nestedUrl,
                        name = nestedName,
                        depth = depth + 1,
                        episodeId = episodeId,
                        referer = url
                    )
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun inspectPageForSources(
        page: Document?,
        rawText: String,
        pageUrl: String,
        serverName: String
    ): ServerResult {
        val started = System.currentTimeMillis()
        val candidates = extractMediaCandidates(
            document = page,
            rawText = rawText,
            pageUrl = pageUrl,
            serverName = serverName
        ).distinctBy { normalizeMediaIdentity(it.url) }
            .take(40)

        val inspected = coroutineScope {
            candidates.map { candidate ->
                async {
                    val info = if (candidate.url.contains(".m3u8", true)) {
                        inspectHls(candidate)
                    } else {
                        null
                    }

                    val playable = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                        probeSource(candidate, info)
                    } ?: false

                    if (!playable) return@async null

                    val finalCandidate = if (info != null) {
                        candidate.copy(
                            quality = maxOf(candidate.quality, info.maxQuality),
                            isHlsMaster = info.isMaster,
                            audioLabel = info.audioLabel
                        )
                    } else {
                        candidate
                    }

                    finalCandidate to info
                }
            }.awaitAll()
        }.filterNotNull()

        val valid = inspected.map { it.first }
        val subtitles = linkedSetOf<Pair<String, String>>()
        inspected.forEach { pair ->
            pair.second?.let { subtitles.addAll(it.subtitles) }
        }

        if (page != null) {
            subtitles.addAll(
                extractExternalSubtitles(
                    page = page,
                    baseUrl = pageUrl
                )
            )
        }

        return ServerResult(
            serverUrl = pageUrl,
            serverName = serverName,
            latencyMs = System.currentTimeMillis() - started,
            sources = valid,
            subtitles = subtitles.toList()
        )
    }

    private fun extractMediaCandidates(
        document: Document?,
        rawText: String,
        pageUrl: String,
        serverName: String
    ): List<MediaCandidate> {
        val result = linkedMapOf<String, MediaCandidate>()

        fun add(
            rawUrl: String?,
            label: String? = null,
            referer: String = pageUrl,
            origin: String = originOf(pageUrl)
        ) {
            val url = normalizeExtractedUrl(rawUrl, pageUrl) ?: return
            if (!isPlayableMedia(url)) return
            if (isObviouslyPromotional(url)) return
            if (isIgnoredHost(url)) return

            val quality = qualityFromUrl(
                url,
                label.orEmpty()
            )

            val key = normalizeMediaIdentity(url)

            result.putIfAbsent(
                key,
                MediaCandidate(
                    url = url,
                    referer = referer,
                    origin = origin,
                    quality = quality,
                    label = label.orEmpty(),
                    server = serverName,
                    latencyMs = 0L,
                    isHlsMaster = false,
                    audioLabel = ""
                )
            )
        }

        /*
         * First read actual HTML media elements and data attributes.
         * Some player/server endpoints return JSON instead of HTML, therefore
         * the raw response text is also scanned below.
         */
        document?.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-manifest], " +
                "a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mpd']"
        )?.forEach { element ->
            val label = firstNonBlank(
                element.attr("label"),
                element.attr("data-quality"),
                element.attr("data-resolution"),
                element.attr("data-quality-label"),
                element.text().takeIf { it.length <= 40 }
            )

            add(element.attr("src"), label)
            add(element.attr("href"), label)
            add(element.attr("data-src"), label)
            add(element.attr("data-video"), label)
            add(element.attr("data-file"), label)
            add(element.attr("data-url"), label)
            add(element.attr("data-source"), label)
            add(element.attr("data-stream"), label)
            add(element.attr("data-manifest"), label)
        }

        /*
         * Zoryva's browser uses /api/proxy?... for playback. If a current proxy
         * URL is already present in a rendered document, keep it as-is instead
         * of inventing a different source.
         */
        val normalizedHtml = buildString {
            if (document != null) append(normalizeEmbeddedText(document.html())).append('\n')
            append(normalizeEmbeddedText(rawText)).append('\n')
            if (document != null) {
                append(extractNextFlightPayload(document)).append('\n')
            }
        }

        val proxyRegex = Regex(
            """https?://[^\"'<>\s]+/api/proxy\?[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        )

        proxyRegex.findAll(normalizedHtml).forEach { match ->
            val proxy = cleanUrl(match.value)
            val upstream = extractProxyParameter(proxy, "url")
            val ref = extractProxyParameter(proxy, "referer")
            val org = extractProxyParameter(proxy, "origin")

            if (isPlayableMedia(upstream ?: "")) {
                add(
                    upstream,
                    "Zoryva upstream",
                    ref ?: pageUrl,
                    org ?: originOf(ref ?: pageUrl)
                )
            }
        }

        /*
         * Absolute media URLs may be stored inside Next.js RSC/JSON payloads.
         */
        val absoluteMediaRegex = Regex(
            """https?://[^\"'<>\s\\]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v|\.mov)(?:\?[^\"'<>\s\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        absoluteMediaRegex.findAll(normalizedHtml).forEach { match ->
            add(match.value)
        }

        /*
         * Relative media paths are useful when a player page exposes a
         * same-origin playlist.
         */
        /* Also capture referer-bound MPEG-TS pages whose suffix is .html. */
        Regex(
            """https?://[^\"'<>\\s]+/file(?:1|2)/[^\"'<>\\s]+?/\\d{3,4}p/page-\\d+\\.html(?:\\?[^\"'<>\\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedHtml).forEach { match ->
            add(match.value)
        }

        val relativeMediaRegex = Regex(
            """(?i)(/[^\"'<>\s]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.m4v|\.mov)(?:\?[^\"'<>\s]*)?)"""
        )

        relativeMediaRegex.findAll(normalizedHtml).forEach { match ->
            add(match.groupValues[1])
        }

        return result.values.toList()
    }

    private suspend fun inspectHls(
        candidate: MediaCandidate
    ): HlsInfo? {
        val response = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
            runCatching {
                app.get(
                    candidate.url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to ACCEPT,
                        "Referer" to candidate.referer,
                        "Origin" to candidate.origin,
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache"
                    )
                )
            }.getOrNull()
        } ?: return null

        if (response.code !in 200..399) return null

        val body = response.text
        if (!body.contains("#EXTM3U", true)) return null

        val isMaster = body.contains("#EXT-X-STREAM-INF", true)
        val hasAudio = body.contains("TYPE=AUDIO", true)
        val hasSubs = body.contains("TYPE=SUBTITLES", true)

        val audioLabels = Regex(
            """(?i)#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*"""
        ).findAll(body).mapNotNull { match ->
            val line = match.value
            firstNonBlank(
                regexAttribute(line, "LANGUAGE"),
                regexAttribute(line, "NAME")
            )
        }.toList().distinct()

        val audioLabel = when {
            audioLabels.size >= 2 -> "Dual Audio (${audioLabels.joinToString(", ")})"
            audioLabels.size == 1 -> "Audio (${audioLabels.first()})"
            hasAudio -> "Audio"
            else -> ""
        }

        val subtitles = parseHlsSubtitleTracks(
            body = body,
            baseUrl = candidate.url
        )

        val variants = ArrayList<Pair<String, Int>>()
        val lines = body.lines()

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) continue

            val resolution = Regex(
                """(?i)RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)"""
            ).find(line)

            val quality = if (resolution != null) {
                resolution.groupValues[2].toIntOrNull()?.let(::qualityFromHeight)
                    ?: qualityFromUrl(line, line)
            } else {
                qualityFromUrl(line, line)
            }

            var j = i + 1
            while (j < lines.size && lines[j].trim().isBlank()) j++

            if (j < lines.size && !lines[j].trim().startsWith("#")) {
                val absolute = absoluteUrlLocal(lines[j].trim(), candidate.url)
                if (!absolute.isNullOrBlank()) {
                    variants += absolute to quality
                }
            }
        }

        return HlsInfo(
            isMaster = isMaster,
            hasAudioTracks = hasAudio,
            hasSubtitleTracks = hasSubs,
            maxQuality = variants.maxOfOrNull { it.second } ?: qualityFromUrl(candidate.url, candidate.label),
            audioLabel = audioLabel,
            subtitles = subtitles,
            variants = variants
        )
    }

    private suspend fun probeSource(
        candidate: MediaCandidate,
        hlsInfo: HlsInfo?
    ): Boolean {
        val clean = cleanUrl(candidate.url)
        if (clean.isBlank()) return false

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to if (clean.contains(".m3u8", true)) {
                "application/vnd.apple.mpegurl,application/x-mpegURL,text/plain,*/*;q=0.8"
            } else {
                ACCEPT
            },
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to candidate.referer,
            "Origin" to candidate.origin,
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        return try {
            if (clean.contains(".m3u8", true)) {
                val response = withTimeoutOrNull(PLAYABILITY_GET_TIMEOUT_MS) {
                    app.get(clean, headers = headers)
                } ?: return false

                if (response.code !in 200..399) return false
                if (!response.text.contains("#EXTM3U", true)) return false

                hlsInfo != null ||
                    response.text.contains("#EXTINF", true) ||
                    response.text.contains("#EXT-X-STREAM-INF", true)
            } else {
                val head = withTimeoutOrNull(PLAYABILITY_HEAD_TIMEOUT_MS) {
                    runCatching {
                        app.head(
                            clean,
                            headers = headers + ("Range" to "bytes=0-0")
                        )
                    }.getOrNull()
                }

                if (head != null && head.code in 200..399) {
                    val contentType = head.headers["Content-Type"].orEmpty()
                    val acceptRanges = head.headers["Accept-Ranges"].orEmpty()
                    val contentRange = head.headers["Content-Range"].orEmpty()

                    val looksVideoType =
                        contentType.startsWith("video/", true) ||
                            contentType.startsWith("audio/", true) ||
                            contentType.contains("mpegurl", true) ||
                            contentType.contains("octet-stream", true)

                    val hasRangeSupport =
                        head.code == 206 ||
                            acceptRanges.contains("bytes", true) ||
                            contentRange.startsWith("bytes ", true)

                    val disguisedStaticReverie = clean.contains("staticreverie.site/", true) &&
                        Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(clean)

                    val signedPeakStorm = clean.contains("peakstorm.top/", true) &&
                        Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                            .containsMatchIn(clean)

                    if (looksVideoType || hasRangeSupport || disguisedStaticReverie || signedPeakStorm || MEDIA_EXTENSIONS.any { clean.contains(it, true) }) {
                        return true
                    }
                }

                val response = withTimeoutOrNull(PLAYABILITY_GET_TIMEOUT_MS) {
                    app.get(
                        clean,
                        headers = headers + ("Range" to "bytes=0-1")
                    )
                } ?: return false

                if (response.code !in 200..399) return false

                val contentType = response.headers["Content-Type"].orEmpty()
                val acceptRanges = response.headers["Accept-Ranges"].orEmpty()
                val contentRange = response.headers["Content-Range"].orEmpty()

                val looksVideoType =
                    contentType.startsWith("video/", true) ||
                        contentType.startsWith("audio/", true) ||
                        contentType.contains("mpegurl", true) ||
                        contentType.contains("octet-stream", true)

                val hasRangeSupport =
                    response.code == 206 ||
                        acceptRanges.contains("bytes", true) ||
                        contentRange.startsWith("bytes ", true)

                val signedPeakStorm = clean.contains("peakstorm.top/", true) &&
                    Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(clean)

                val disguisedStaticReverie = clean.contains("staticreverie.site/", true) &&
                    Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(clean)

                if (looksVideoType || hasRangeSupport || signedPeakStorm || disguisedStaticReverie) {
                    return true
                }

                val prefix = response.text
                    .trimStart()
                    .take(120)
                    .lowercase(Locale.ROOT)

                if (
                    prefix.startsWith("<!doctype html") ||
                    prefix.startsWith("<html") ||
                    prefix.startsWith("<head") ||
                    prefix.startsWith("{\"error") ||
                    prefix.startsWith("{\"message")
                ) {
                    return false
                }

                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun headerValue(
        headers: Map<String, String>,
        name: String
    ): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    private fun base64DecodeAny(
        input: String
    ): ByteArray? {
        val clean = input
            .trim()
            .replace("-", "+")
            .replace("_", "/")
            .filterNot { it.isWhitespace() }

        if (clean.isBlank()) return null

        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = ByteArray(padded.length / 4 * 3)
        var outIndex = 0
        var i = 0

        fun value(c: Char): Int {
            return alphabet.indexOf(c)
        }

        while (i < padded.length) {
            val c0 = padded[i]
            val c1 = padded.getOrNull(i + 1) ?: return null
            val c2 = padded.getOrNull(i + 2) ?: '='
            val c3 = padded.getOrNull(i + 3) ?: '='

            val v0 = value(c0)
            val v1 = value(c1)
            val v2 = if (c2 == '=') 0 else value(c2)
            val v3 = if (c3 == '=') 0 else value(c3)

            if (v0 < 0 || v1 < 0 || (c2 != '=' && v2 < 0) || (c3 != '=' && v3 < 0)) {
                return null
            }

            output[outIndex++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
            if (c2 != '=') {
                output[outIndex++] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
            }
            if (c3 != '=') {
                output[outIndex++] = (((v2 and 0x03) shl 6) or v3).toByte()
            }

            i += 4
        }

        return output.copyOf(outIndex)
    }

    /**
     * Small dependency-free URL-safe Base64 encoder.
     *
     * This deliberately avoids android.util.Base64 because the CloudStream
     * build also validates Zoryva's cross-platform JAR.
     */
    private fun base64UrlNoPadding(input: ByteArray): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val output = StringBuilder(((input.size + 2) / 3) * 4)
        var index = 0

        while (index + 2 < input.size) {
            val b0 = input[index].toInt() and 0xFF
            val b1 = input[index + 1].toInt() and 0xFF
            val b2 = input[index + 2].toInt() and 0xFF

            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            output.append(alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            output.append(alphabet[b2 and 0x3F])

            index += 3
        }

        val remaining = input.size - index
        if (remaining == 1) {
            val b0 = input[index].toInt() and 0xFF
            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[(b0 and 0x03) shl 4])
        } else if (remaining == 2) {
            val b0 = input[index].toInt() and 0xFF
            val b1 = input[index + 1].toInt() and 0xFF
            output.append(alphabet[b0 ushr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            output.append(alphabet[(b1 and 0x0F) shl 2])
        }

        return output.toString()
    }

    private fun buildZoryvaProxyUrl(
        sourceUrl: String,
        referer: String,
        origin: String
    ): String {
        /* Never wrap an already-valid Zoryva proxy URL a second time. */
        if (sourceUrl.startsWith("$BASE_URL/api/proxy?", true)) {
            return sourceUrl
        }

        val safeReferer = referer.ifBlank { BASE_URL + "/" }
        val safeOrigin = origin.ifBlank { originOf(safeReferer) }

        val headersJson = JSONObject()
            .put("Referer", safeReferer)
            .put("Origin", safeOrigin)
            .put("User-Agent", USER_AGENT)
            .put("Accept", ACCEPT)
            .put("Accept-Language", "en-US,en;q=0.9,bn;q=0.8")
            .toString()

        val h = base64UrlNoPadding(
            headersJson.toByteArray(Charsets.UTF_8)
        )

        return buildString {
            append(BASE_URL)
            append("/api/proxy?url=")
            append(encode(sourceUrl))
            append("&referer=")
            append(encode(safeReferer))
            append("&origin=")
            append(encode(safeOrigin))
            append("&ua=")
            append(encode(USER_AGENT))
            append("&h=")
            append(h)
        }
    }

    private fun buildSourceName(
        source: MediaCandidate
    ): String {
        val quality = if (source.quality > 0) {
            "${source.quality}p"
        } else {
            "Auto"
        }

        val audio = source.audioLabel
            .takeIf { it.isNotBlank() }
            ?.let { " • $it" }
            .orEmpty()

        val server = source.server
            .takeIf { it.isNotBlank() }
            ?.let { " • $it" }
            .orEmpty()

        return "Zoryva • $quality$audio$server"
    }

    // ---------------------------------------------------------------------
    // SERVER / PLAYER DISCOVERY
    // ---------------------------------------------------------------------

    private fun discoverServerPages(
        document: Document?,
        rawText: String,
        pageUrl: String,
        episodeId: String? = null
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()

        fun add(url: String?, label: String?) {
            val clean = normalizeExtractedUrl(url, pageUrl) ?: return
            if (isIgnoredHost(clean)) return
            if (isObviouslyPromotional(clean)) return
            if (isPlayableMedia(clean)) return

            val text = listOfNotNull(label)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            val host = hostOf(clean).lowercase(Locale.ROOT)
            val path = runCatching { URI(clean).path.orEmpty().lowercase(Locale.ROOT) }
                .getOrDefault("")

            val likelyPlayer =
                PLAYER_WORDS.any { text.contains(it) } ||
                    PLAYER_WORDS.any { path.contains(it) } ||
                    host.contains("vidrock") ||
                    host.contains("player") ||
                    host.contains("stream")

            if (!likelyPlayer) return

            result.putIfAbsent(
                clean,
                label?.trim().takeUnless { it.isNullOrBlank() } ?: "Zoryva Source"
            )
        }

        document?.select(
            "iframe[src], embed[src], " +
                "button[data-server], button[data-source], " +
                "[data-server-url], [data-source-url], [data-player-url], " +
                "[data-iframe], [data-embed-url], " +
                "[data-server], [data-source]"
        )?.forEach { element ->
            add(
                firstNonBlank(
                    element.absUrl("src"),
                    element.attr("src"),
                    element.absUrl("data-server-url"),
                    element.attr("data-server-url"),
                    element.absUrl("data-source-url"),
                    element.attr("data-source-url"),
                    element.absUrl("data-player-url"),
                    element.attr("data-player-url"),
                    element.absUrl("data-iframe"),
                    element.attr("data-iframe"),
                    element.absUrl("data-embed-url"),
                    element.attr("data-embed-url"),
                    element.attr("data-server"),
                    element.attr("data-source"),
                    element.attr("data-file"),
                    element.attr("data-stream"),
                    element.attr("data-manifest")
                ),
                firstNonBlank(element.attr("title"), element.text())
            )
        }

        document?.select("a[href]")?.forEach { anchor ->
            add(
                anchor.absUrl("href"),
                anchor.text().trim()
            )
        }

        val normalizedHtml = buildString {
            if (document != null) append(normalizeEmbeddedText(document.html())).append('\n')
            append(normalizeEmbeddedText(rawText)).append('\n')
        }
        Regex(
            """https?://[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedHtml).forEach { match ->
            val url = match.value
            val commonMediaHost =
                url.contains("vidrock", true) ||
                    url.contains("staticreverie", true) ||
                    url.contains("file1", true) ||
                    url.contains("file2", true) ||
                    url.contains("player", true) ||
                    url.contains("embed", true) ||
                    url.contains("stream", true) ||
                    url.contains("watch", true)

            if (commonMediaHost) {
                add(url, "Zoryva Source")
            }
        }

        /*
         * Some server buttons expose only an episode/source identifier in the
         * page JSON. Keep the identifier in discovery metadata and prefer URLs
         * that visibly mention it. We do not manufacture a media URL from the
         * identifier; it only improves selection among URLs the site exposed.
         */
        if (!episodeId.isNullOrBlank()) {
            normalizedHtml
                .split('\n')
                .filter { it.contains(episodeId, true) }
                .take(32)
                .forEach { line ->
                    Regex("""https?://[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
                        .findAll(line)
                        .forEach { match ->
                            add(match.value, "Zoryva Episode Source")
                        }
                }
        }

        return result.entries
            .take(MAX_SERVER_PAGES)
            .map { it.key to it.value }
    }

    private fun discoverNestedPlayerPages(
        document: Document?,
        rawText: String,
        pageUrl: String
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()

        document?.select("iframe[src], embed[src], a[href]")?.forEach { element ->
            val url = when {
                element.hasAttr("src") -> element.absUrl("src")
                else -> element.absUrl("href")
            }.trim()

            if (url.isBlank()) return@forEach
            if (isIgnoredHost(url)) return@forEach
            if (isPlayableMedia(url)) return@forEach

            val text = firstNonBlank(
                element.attr("title"),
                element.text()
            ).orEmpty().lowercase(Locale.ROOT)

            val lowerUrl = url.lowercase(Locale.ROOT)
            if (
                PLAYER_WORDS.any { lowerUrl.contains(it) } ||
                PLAYER_WORDS.any { text.contains(it) }
            ) {
                result.putIfAbsent(
                    cleanUrl(url),
                    if (text.isBlank()) "Nested Source" else text
                )
            }
        }

        Regex("""https?://[^\"'<>\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalizeEmbeddedText(rawText))
            .forEach { match ->
                val url = match.value
                val lower = url.lowercase(Locale.ROOT)
                if (
                    (lower.contains("player") || lower.contains("embed") ||
                        lower.contains("stream") || lower.contains("watch") ||
                        lower.contains("vidrock") || lower.contains("staticreverie") ||
                        lower.contains("file1") || lower.contains("file2")) &&
                        !isPlayableMedia(url) && !isIgnoredHost(url)
                ) {
                    result.putIfAbsent(cleanUrl(url), "Nested Source")
                }
            }

        return result.entries
            .take(MAX_SERVER_PAGES)
            .map { it.key to it.value }
    }

    // ---------------------------------------------------------------------
    // HLS SUBTITLES
    // ---------------------------------------------------------------------

    private fun parseHlsSubtitleTracks(
        body: String,
        baseUrl: String
    ): List<Pair<String, String>> {
        val result = linkedSetOf<Pair<String, String>>()

        Regex(
            """(?i)#EXT-X-MEDIA:[^\r\n]*TYPE=SUBTITLES[^\r\n]*"""
        ).findAll(body).forEach { match ->
            val line = match.value
            val uri = regexAttribute(line, "URI") ?: return@forEach
            val absolute = absoluteUrlLocal(uri, baseUrl) ?: return@forEach

            val language = firstNonBlank(
                regexAttribute(line, "LANGUAGE"),
                regexAttribute(line, "NAME")
            ) ?: "Subtitle"

            result += language to absolute
        }

        return result.toList()
    }

    private fun extractExternalSubtitles(
        page: Document,
        baseUrl: String
    ): List<Pair<String, String>> {
        val result = linkedSetOf<Pair<String, String>>()

        page.select(
            "a[href], track[src], [data-subtitle], [data-subtitles], " +
                "[data-caption], [data-caption-url]"
        ).forEach { element ->
            val raw = firstNonBlank(
                element.absUrl("href"),
                element.absUrl("src"),
                element.attr("data-subtitle"),
                element.attr("data-subtitles"),
                element.attr("data-caption"),
                element.attr("data-caption-url")
            ) ?: return@forEach

            val url = normalizeExtractedUrl(raw, baseUrl) ?: return@forEach
            if (!url.contains(".vtt", true) &&
                !url.contains(".srt", true) &&
                !url.contains(".ass", true) &&
                !url.contains(".ssa", true)
            ) {
                return@forEach
            }

            val language = firstNonBlank(
                element.attr("srclang"),
                element.attr("label"),
                element.attr("data-language"),
                element.text().takeIf { it.length <= 30 }
            ) ?: "Subtitle"

            result += language to url
        }

        return result.toList()
    }

    // ---------------------------------------------------------------------
    // HTML / RSC HELPERS
    // ---------------------------------------------------------------------

    private fun extractInitialMediaObject(
        document: Document
    ): JSONObject? {
        /*
         * The live Zoryva detail page transports initialMedia inside a quoted
         * Next.js Flight payload. Reuse the same real Flight-string decoder used
         * by Home instead of trying to unescape the whole HTML blindly.
         */
        val flight = extractNextFlightPayload(document)
        val candidates = listOf(
            flight,
            decodeRscForCatalog(document.html()),
            decodeRscOneLayer(document.html())
        )

        for (decoded in candidates) {
            val marker = "\"initialMedia\":"
            val markerIndex = decoded.indexOf(marker)
            if (markerIndex < 0) continue

            val start = decoded.indexOf('{', markerIndex + marker.length)
            if (start < 0) continue

            val json = extractBalancedJson(
                decoded,
                start,
                '{',
                '}'
            ) ?: continue

            val media = runCatching { JSONObject(json) }.getOrNull()
            if (media != null) return media
        }

        return null
    }

    private fun extractBalancedJson(
        text: String,
        start: Int,
        open: Char,
        close: Char
    ): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }

        return null
    }

    private fun decodeRscOneLayer(
        input: String
    ): String {
        val placeholder = '\u0000'

        return input
            .replace("\\\\", placeholder.toString())
            .replace("\\\"", "\"")
            .replace(placeholder.toString(), "\\")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun normalizeEmbeddedText(
        input: String
    ): String {
        return input
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\\\"", "\\\"")
    }

    private fun extractMeta(
        document: Document,
        selector: String
    ): String? {
        return document.selectFirst("meta[$selector]")?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractCardTitle(
        anchor: Element
    ): String {
        return firstNonBlank(
            anchor.attr("aria-label"),
            anchor.attr("title"),
            anchor.selectFirst("img")?.attr("alt"),
            anchor.selectFirst("img")?.attr("title"),
            anchor.selectFirst("[title]")?.attr("title"),
            anchor.text().trim()
        )?.let(::cleanCardTitle).orEmpty()
    }

    private fun extractCardPoster(
        anchor: Element,
        baseUrl: String
    ): String? {
        val img = anchor.selectFirst("img") ?: return null

        val raw = firstNonBlank(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("srcset")?.substringBefore(',')?.substringBefore(' ')
        )

        return normalizeExtractedUrl(raw, baseUrl)
    }

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

            TvType.Anime -> newMovieSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
            }

            else -> newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    // ---------------------------------------------------------------------
    // URL / TYPE / QUALITY HELPERS
    // ---------------------------------------------------------------------

    private data class FetchedPage(
        val document: Document?,
        val text: String,
        val code: Int
    )

    private suspend fun fetchPage(
        url: String,
        attempts: Int = 2,
        refererOverride: String? = null
    ): FetchedPage? {
        val clean = cleanUrl(url)
        if (clean.isBlank()) return null

        val maxAttempts = attempts.coerceIn(1, DETAIL_FETCH_ATTEMPTS + 1)
        var last: FetchedPage? = null

        repeat(maxAttempts) { attempt ->
            val result = runCatching {
                val pageHost = hostOf(clean).lowercase(Locale.ROOT)
                val contextualOrigin = originOf(clean)
                val pageReferer = refererOverride
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: when {
                        pageHost == "vidrock.net" || pageHost.endsWith(".vidrock.net") ->
                            "$VIDROCK_ORIGIN/"
                        pageHost == hostOf(BASE_URL).lowercase(Locale.ROOT) ->
                            "$BASE_URL/"
                        else ->
                            contextualOrigin.ifBlank { BASE_URL } + "/"
                    }

                val response = app.get(
                    clean,
                    headers = PAGE_HEADERS + mapOf(
                        "Referer" to pageReferer,
                        "Origin" to contextualOrigin,
                        "Sec-Fetch-Dest" to "document",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to if (pageHost == "vidrock.net") "same-origin" else "cross-site",
                        "Upgrade-Insecure-Requests" to "1"
                    )
                )

                val document = runCatching { response.document }.getOrNull()?.apply {
                    if (baseUri().isBlank()) setBaseUri(clean)
                }

                FetchedPage(
                    document = document,
                    text = response.text,
                    code = response.code
                )
            }.getOrNull()

            last = result
            if (result != null && result.code in 200..399) {
                return result
            }

            if (attempt + 1 < maxAttempts) {
                delay(DETAIL_RETRY_DELAY_MS * (attempt + 1L))
            }
        }

        return last?.takeIf { it.code in 200..399 }
    }

    private suspend fun getDocument(
        url: String
    ): Document? {
        return fetchPage(
            url = url,
            attempts = 2
        )?.document
    }

    private fun normalizeExtractedUrl(
        raw: String?,
        baseUrl: String
    ): String? {
        if (raw.isNullOrBlank()) return null

        var value = raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', '`', ',', ';', ')', ']', '}')

        if (value.isBlank()) return null

        if (value.startsWith("//")) {
            value = "https:$value"
        }

        if (value.startsWith("/")) {
            value = absoluteUrlLocal(value, baseUrl) ?: return null
        }

        if (!value.startsWith("http://", true) &&
            !value.startsWith("https://", true)
        ) {
            value = absoluteUrlLocal(value, baseUrl) ?: return null
        }

        return cleanUrl(value)
    }

    private fun cleanUrl(
        url: String
    ): String {
        return url.trim()
            .trim('"', '\'', '`', ',', ';', ')', ']', '}')
    }

    private fun absoluteUrl(
        url: String?
    ): String? {
        if (url.isNullOrBlank()) return null
        return cleanUrl(url)
    }

    private fun absoluteUrlLocal(
        value: String,
        base: String
    ): String? {
        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrNull()
    }

    private fun originOf(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port"
        }.getOrDefault(BASE_URL)
    }

    private fun hostOf(
        url: String
    ): String {
        return runCatching {
            URI(url).host.orEmpty()
        }.getOrDefault("")
    }

    private fun isMoviePath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("movie", true) == true
    }

    private fun isTvPath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("tv", true) == true
    }

    private fun isAnimePath(
        path: String
    ): Boolean {
        return path.trim('/').split('/').firstOrNull()
            ?.equals("anime", true) == true
    }

    private fun isEpisodePath(
        path: String
    ): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        return parts.size >= 4 &&
            (parts[0].equals("tv", true) || parts[0].equals("anime", true)) &&
            parts[parts.size - 1].toIntOrNull() != null
    }

    private fun titleFromPath(
        path: String
    ): String {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val slug = when {
            parts.size >= 3 -> parts[2]
            parts.isNotEmpty() -> parts.last()
            else -> ""
        }

        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
    }

    private fun cleanCardTitle(
        value: String
    ): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanDetailTitle(
        value: String
    ): String {
        return value
            .replace(Regex("(?i)^watch\\s+"), "")
            .replace(Regex("(?i)\\s+(online|in hd|hd)$"), "")
            .replace("— Zoryva X", "")
            .replace("| Zoryva X", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun slugify(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun normalizeSearch(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[b.length]
    }

    private fun qualityFromUrl(
        url: String,
        label: String
    ): Int {
        val value = "$url $label".lowercase(Locale.ROOT)

        return when {
            Regex("(?:2160|4k)").containsMatchIn(value) -> Qualities.P2160.value
            Regex("(?:1440|2k)").containsMatchIn(value) -> Qualities.P1440.value
            Regex("1080").containsMatchIn(value) -> Qualities.P1080.value
            Regex("720").containsMatchIn(value) -> Qualities.P720.value
            Regex("480").containsMatchIn(value) -> Qualities.P480.value
            Regex("360").containsMatchIn(value) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityFromHeight(
        height: Int
    ): Int {
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun isPlayableMedia(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        if (lower.contains("/api/proxy?")) {
            val upstream = extractProxyParameter(url, "url").orEmpty()
            return isPlayableMedia(upstream)
        }

        if (MEDIA_EXTENSIONS.any { lower.contains(it) }) return true
        if (lower.contains(".ts?", true) || lower.contains(".ts#", true) || lower.endsWith(".ts", true)) return true
        if (lower.contains(".m4s?", true) || lower.contains(".m4s#", true) || lower.endsWith(".m4s", true)) return true

        val staticReverie =
            lower.contains("staticreverie.site/") &&
                Regex("""/file(?:1|2)/[^?#\s]*?/\d{3,4}p/page-\d+\.html""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lower)

        /* Current browser playback also uses short-lived extensionless signed
         * media URLs under *.peakstorm.top/r6/s/<token>. */
        val peakStormSigned =
            lower.contains("peakstorm.top/") &&
                Regex("""/r6/s/[^/?#\s]+""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(lower)

        return staticReverie || peakStormSigned
    }

    private fun requiresZoryvaProxy(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("staticreverie.site/") ||
            lower.contains("peakstorm.top/")
    }

    private fun mediaRefererFor(
        url: String,
        currentReferer: String
    ): String {
        return if (url.contains("peakstorm.top/", true)) {
            "https://speedracelight.com/"
        } else {
            currentReferer.ifBlank { "$BASE_URL/" }
        }
    }

    private fun buildPlaybackOutput(
        source: MediaCandidate,
        pageUrl: String
    ): PlaybackOutput {
        /*
         * CloudStream is not subject to browser CORS rules. The original
         * short-lived upstream URL is therefore the preferred output. This is
         * exactly the URL the website player ultimately feeds into its proxy.
         * The required Referer/Origin are preserved as request headers.
         */
        if (requiresZoryvaProxy(source.url)) {
            val upstreamReferer = mediaRefererFor(source.url, source.referer)
            return PlaybackOutput(
                url = source.url,
                referer = upstreamReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to ACCEPT,
                    "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
                    "Referer" to upstreamReferer,
                    "Origin" to originOf(upstreamReferer),
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }

        return PlaybackOutput(
            url = source.url,
            referer = source.referer.ifBlank { pageUrl },
            headers = playbackHeadersFor(source)
        )
    }

    private fun isObviouslyPromotional(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return TRAILER_WORDS.any { lower.contains(it) }
    }

    private fun isIgnoredHost(
        url: String
    ): Boolean {
        val host = hostOf(url).lowercase(Locale.ROOT)
        return IGNORED_HOST_PARTS.any { host == it || host.endsWith(".$it") }
    }

    private fun normalizeMediaIdentity(
        url: String
    ): String {
        val proxyUpstream = extractProxyParameter(url, "url")
        if (!proxyUpstream.isNullOrBlank()) {
            return cleanUrl(proxyUpstream)
        }

        return cleanUrl(url)
    }

    private fun extractProxyParameter(
        url: String,
        name: String
    ): String? {
        return runCatching {
            val uri = URI(url)
            val query = uri.rawQuery ?: return@runCatching null
            query.split('&').firstNotNullOfOrNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) return@firstNotNullOfOrNull null
                if (!pieces[0].equals(name, true)) return@firstNotNullOfOrNull null
                java.net.URLDecoder.decode(pieces[1], "UTF-8")
            }
        }.getOrNull()
    }

    private fun regexAttribute(
        line: String,
        attribute: String
    ): String? {
        return Regex(
            """(?i)$attribute\s*=\s*(?:\"([^\"]+)\"|([^,\s]+))"""
        ).find(line)?.let {
            firstNonBlank(
                it.groupValues.getOrNull(1),
                it.groupValues.getOrNull(2)
            )
        }
    }

    private fun encode(
        value: String
    ): String = URLEncoder.encode(value, "UTF-8")

    private fun firstNonBlank(
        vararg values: String?
    ): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun MutableList<String>.truncateAfter(
        size: Int
    ) {
        while (this.size > size) removeAt(lastIndex)
    }
}
