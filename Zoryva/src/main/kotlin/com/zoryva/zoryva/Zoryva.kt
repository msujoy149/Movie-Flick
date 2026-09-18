package com.zoryva.zoryva

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        const val MAX_SEARCH_ITEMS = 50
        const val MAX_SERVER_PAGES = 12
        const val MAX_CRAWL_DEPTH = 2
        const val SOURCE_PROBE_TIMEOUT_MS = 7000L
        const val SERVER_PAGE_TIMEOUT_MS = 9000L
        const val HOME_PREFETCH_TIMEOUT_MS = 5000L
        const val HOME_CATALOG_CACHE_TTL_MS = 15000L

        // Advanced search tuning. Normal searches should finish from the
        // website's native search page; the heavier global fallback is used
        // only when native search cannot produce a useful match.
        const val SEARCH_RESULT_LIMIT = 50
        const val SEARCH_NATIVE_TIMEOUT_MS = 4500L
        const val SEARCH_FALLBACK_TIMEOUT_MS = 8500L
        const val SEARCH_SITEMAP_LIMIT = 5000
        const val SEARCH_SITEMAP_CHILD_LIMIT = 16
        const val SEARCH_VERIFY_LIMIT = 8
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
        val type: TvType
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

    private data class CachedCatalog(
        val createdAt: Long,
        val items: List<SiteItem>
    )

    private val catalogCache = ConcurrentHashMap<String, CachedCatalog>()
    private val catalogPrefetchMutex = Mutex()

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
             * Prefetch all four Home sections together the first time Home is
             * opened. The cache contains catalog metadata only; it never stores
             * playback URLs or signed media tokens. This lets Trending, Movies,
             * TV Show and Anime become available together instead of waiting for
             * four independent cold starts.
             */
            prefetchHomeCatalogs()

            val cached = getCachedCatalog(route)
            val items = cached?.items ?: run {
                val document = getDocument(route) ?: return newHomePageResponse(
                    request,
                    emptyList(),
                    false
                )

                parseBrowseItems(document, route)
                    .distinctBy { cleanUrl(it.url) }
                    .also { storeCatalog(route, it) }
            }

            val visible = items.take(MAX_HOME_ITEMS)

            return newHomePageResponse(
                request,
                visible.map { it.toSearchResponse() },
                items.size > MAX_HOME_ITEMS || visible.size >= MAX_HOME_ITEMS
            )
        }

        /*
         * Later scroll pages are requested concurrently. We use the site's
         * normal ?page=N route and, when that route simply repeats the first
         * catalog, fall back to a deterministic window over the initial live
         * catalog. This prevents duplicate cards on client-side paginated builds.
         */
        val pagedUrl = buildBrowseUrl(route, currentPage)
        val (baseDocument, pagedDocument) = coroutineScope {
            val baseJob = async {
                getCachedCatalog(route)?.let { cached ->
                    if (System.currentTimeMillis() - cached.createdAt <= HOME_CATALOG_CACHE_TTL_MS) {
                        null
                    } else {
                        getDocument(route)
                    }
                } ?: getDocument(route)
            }
            val pagedJob = async { getDocument(pagedUrl) }
            baseJob.await() to pagedJob.await()
        }

        val baseItems = getCachedCatalog(route)?.items
            ?: baseDocument
                ?.let { parseBrowseItems(it, route) }
                ?.distinctBy { cleanUrl(it.url) }
                .orEmpty()

        if (baseItems.isNotEmpty()) {
            storeCatalog(route, baseItems)
        }

        val pagedItems = pagedDocument
            ?.let { parseBrowseItems(it, pagedUrl) }
            ?.distinctBy { cleanUrl(it.url) }
            .orEmpty()

        val serverPaginationWorks =
            pagedItems.isNotEmpty() && !sameCatalog(pagedItems, baseItems)

        val visible = if (serverPaginationWorks) {
            pagedItems.take(MAX_HOME_ITEMS)
        } else {
            baseItems
                .drop((currentPage - 1) * MAX_HOME_ITEMS)
                .take(MAX_HOME_ITEMS)
        }

        val hasMore = if (serverPaginationWorks) {
            pagedItems.size >= MAX_HOME_ITEMS || hasExplicitNextPage(pagedDocument)
        } else {
            baseItems.size > currentPage * MAX_HOME_ITEMS
        }

        return newHomePageResponse(
            request,
            visible.map { it.toSearchResponse() },
            hasMore && visible.isNotEmpty()
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

            val fetched = coroutineScope {
                homeRoutes.map { route ->
                    async {
                        route to withTimeoutOrNull(HOME_PREFETCH_TIMEOUT_MS) {
                            getDocument(route)
                        }
                    }
                }.awaitAll()
            }

            fetched.forEach { (route, document) ->
                document ?: return@forEach
                val items = parseBrowseItems(document, route)
                    .distinctBy { cleanUrl(it.url) }
                if (items.isNotEmpty()) {
                    storeCatalog(route, items)
                }
            }
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
        return overlap >= maxOf(1, minOf(firstKeys.size, secondKeys.size) * 0.75).toInt()
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

        /*
         * The website is a Next.js application. We therefore avoid depending
         * on one generated CSS class and instead look for canonical media links.
         */
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

            val poster = extractCardPoster(anchor, baseUrl)
            val key = cleanUrl(absolute)

            result.putIfAbsent(
                key,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    type = type
                )
            )
        }

        if (result.isNotEmpty()) {
            return result.values.toList()
        }

        /*
         * Next.js Flight/RSC can carry canonical href strings even when a
         * renderer changes the final DOM shape. Use a conservative fallback
         * over the raw HTML so a future class-name/layout change does not make
         * an entire Home section disappear.
         */
        val html = document.html()
        val hrefRegex = Regex("(?i)(?:href=\"|href=')((?:/|https?://)[^\"']+?/((?:movie)|(?:tv)|(?:anime))/[A-Za-z0-9_-]+(?:/[^\"']*)?)")
        hrefRegex.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            val absolute = normalizeExtractedUrl(raw, baseUrl) ?: return@forEach
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")
            val type = when {
                isMoviePath(path) -> TvType.Movie
                isAnimePath(path) -> TvType.Anime
                isTvPath(path) && !isEpisodePath(path) -> TvType.TvSeries
                else -> return@forEach
            }

            val nearby = html.substring(
                maxOf(0, match.range.first - 1800),
                minOf(html.length, match.range.last + 1800)
            )
            val title = Regex("(?is)<img[^>]+(?:alt|title)=\"([^\"]+)\"|<img[^>]+(?:alt|title)='([^']+)'")
                .find(nearby)
                ?.let { firstNonBlank(it.groupValues[1], it.groupValues[2]) }
                ?.let(::cleanCardTitle)
                .orEmpty()
                .ifBlank { titleFromPath(path) }

            if (title.isBlank()) return@forEach

            val posterRaw = Regex("(?i)(?:src|data-src|data-lazy-src)=[\"']([^\"']+)[\"']")
                .find(nearby)
                ?.groupValues
                ?.getOrNull(1)
            val poster = normalizeExtractedUrl(posterRaw, baseUrl)

            result.putIfAbsent(
                cleanUrl(absolute),
                SiteItem(title, absolute, poster, type)
            )
        }

        return result.values.toList()
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
         * SEARCH STRATEGY
         *
         * 1) Ask Zoryva's own /search route first. This is the fastest path
         *    because the website itself already knows its live catalog.
         * 2) Rank those real site results locally with exact, token, partial,
         *    typo-tolerant and order-independent matching.
         * 3) If native search cannot produce a useful match, retry a very small
         *    set of normalized query variants concurrently.
         * 4) Only when those still fail, use a lightweight sitemap fallback.
         *    Sitemap entries are real Zoryva media URLs, not invented results.
         *    The best fuzzy candidates are then verified against their actual
         *    Zoryva detail pages so the emitted title/poster come from the site.
         *
         * No external catalog is used and no media URL is fabricated here.
         */
        val native = withTimeoutOrNull(SEARCH_NATIVE_TIMEOUT_MS) {
            searchNative(original)
        }

        if (!native.isNullOrEmpty()) {
            return native
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
                item to searchScore(original, item.title)
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

        val mediaObject = extractInitialMediaObject(document.html())

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
            parseEpisodes(mediaObject)
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

        /*
         * IMPORTANT:
         * Every Play action starts from a fresh current page. No tokenized URL
         * from an earlier Play call is retained in class state or disk cache.
         */
        val directPage = withTimeoutOrNull(
            SERVER_PAGE_TIMEOUT_MS
        ) {
            getDocument(pageUrl)
        } ?: return false

        val serverPages = discoverServerPages(
            document = directPage,
            pageUrl = pageUrl
        )

        val directResult = inspectPageForSources(
            page = directPage,
            pageUrl = pageUrl,
            serverName = "Zoryva direct"
        )

        val allTargets = linkedMapOf<String, String>()

        for ((url, name) in serverPages) {
            allTargets.putIfAbsent(cleanUrl(url), name)
        }

        /*
         * The direct page is always inspected as well. If it already exposes
         * a current proxy/m3u8 URL, we do not need another server request.
         */
        val results = ArrayList<ServerResult>()
        if (directResult.sources.isNotEmpty()) {
            results += directResult
        }

        coroutineScope {
            val jobs = allTargets.entries.map { entry ->
                async {
                    inspectServerTarget(
                        url = entry.key,
                        name = entry.value,
                        depth = 0
                    )
                }
            }

            results += jobs.awaitAll().flatten()
        }

        val usable = results
            .filter { result -> result.sources.isNotEmpty() }
            .sortedBy { it.latencyMs }

        if (usable.isEmpty()) return false

        val emitted = linkedSetOf<String>()
        val subtitleSeen = linkedSetOf<String>()

        /*
         * Fastest usable server is emitted first. Other working servers remain
         * available as fallback choices. This differs intentionally from a
         * single-mirror failover chain.
         */
        for (server in usable) {
            for ((language, subtitleUrl) in server.subtitles) {
                val key = "$language|$subtitleUrl"
                if (subtitleSeen.add(key)) {
                    subtitleCallback(
                        newSubtitleFile(language, subtitleUrl)
                    )
                }
            }

            for (source in server.sources.sortedWith(
                compareByDescending<MediaCandidate> { it.quality }
                    .thenBy { it.latencyMs }
            )) {
                val proxyUrl = buildZoryvaProxyUrl(
                    sourceUrl = source.url,
                    referer = source.referer,
                    origin = source.origin
                )

                val key = "${source.quality}|${normalizeMediaIdentity(source.url)}"
                if (!emitted.add(key)) continue

                callback(
                    newExtractorLink(
                        name,
                        buildSourceName(source),
                        proxyUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        quality = if (source.quality > 0) {
                            source.quality
                        } else {
                            Qualities.Unknown.value
                        }
                        referer = source.referer
                    }
                )
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun inspectServerTarget(
        url: String,
        name: String,
        depth: Int
    ): List<ServerResult> {
        if (depth > MAX_CRAWL_DEPTH) return emptyList()
        if (url.isBlank()) return emptyList()
        if (isIgnoredHost(url)) return emptyList()
        if (isObviouslyPromotional(url)) return emptyList()

        val started = System.currentTimeMillis()

        val document = withTimeoutOrNull(SERVER_PAGE_TIMEOUT_MS) {
            getDocument(url)
        } ?: return emptyList()

        val current = inspectPageForSources(
            page = document,
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
            pageUrl = url
        ).take(MAX_SERVER_PAGES)

        if (nested.isEmpty()) return emptyList()

        return coroutineScope {
            nested.map { (nestedUrl, nestedName) ->
                async {
                    inspectServerTarget(
                        url = nestedUrl,
                        name = nestedName,
                        depth = depth + 1
                    )
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun inspectPageForSources(
        page: Document,
        pageUrl: String,
        serverName: String
    ): ServerResult {
        val started = System.currentTimeMillis()
        val candidates = extractMediaCandidates(
            document = page,
            pageUrl = pageUrl,
            serverName = serverName
        )

        val valid = ArrayList<MediaCandidate>()
        val subtitles = linkedSetOf<Pair<String, String>>()

        for (candidate in candidates.distinctBy { normalizeMediaIdentity(it.url) }) {
            val info = if (candidate.url.contains(".m3u8", true)) {
                inspectHls(candidate)
            } else {
                null
            }

            val playable = withTimeoutOrNull(SOURCE_PROBE_TIMEOUT_MS) {
                probeSource(candidate, info)
            } ?: false

            if (!playable) continue

            val finalCandidate = if (info != null) {
                candidate.copy(
                    quality = if (info.isMaster) {
                        maxOf(candidate.quality, info.maxQuality)
                    } else {
                        maxOf(candidate.quality, info.maxQuality)
                    },
                    isHlsMaster = info.isMaster,
                    audioLabel = info.audioLabel
                )
            } else {
                candidate
            }

            valid += finalCandidate

            if (info != null) {
                subtitles.addAll(info.subtitles)
            }

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
        document: Document,
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
         */
        document.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-manifest], " +
                "a[href*='.m3u8'], a[href*='.mp4'], a[href*='.mpd']"
        ).forEach { element ->
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
        val normalizedHtml = normalizeEmbeddedText(document.html())

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
                    proxy,
                    "",
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
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to ACCEPT,
                "Referer" to candidate.referer,
                "Origin" to candidate.origin,
                "Cache-Control" to "no-cache, no-store, max-age=0",
                "Pragma" to "no-cache"
            )

            val response = if (candidate.url.contains(".m3u8", true)) {
                app.get(candidate.url, headers = headers)
            } else {
                app.get(
                    candidate.url,
                    headers = headers + ("Range" to "bytes=0-1")
                )
            }

            if (response.code !in 200..399) return false

            if (candidate.url.contains(".m3u8", true)) {
                if (hlsInfo != null) return true
                response.text.contains("#EXTM3U", true)
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
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
        val safeReferer = referer.ifBlank { BASE_URL + "/" }
        val safeOrigin = origin.ifBlank { originOf(safeReferer) }

        val headersJson = JSONObject()
            .put("Referer", safeReferer)
            .put("Origin", safeOrigin)
            .put("User-Agent", USER_AGENT)
            .put("Accept", ACCEPT)
            .put("Accept-Language", "en-US,en;q=0.9")
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
        document: Document,
        pageUrl: String
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

        document.select(
            "iframe[src], embed[src], " +
                "button[data-server], button[data-source], " +
                "[data-server-url], [data-source-url], [data-player-url], " +
                "[data-iframe], [data-embed-url]"
        ).forEach { element ->
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
                    element.attr("data-embed-url")
                ),
                firstNonBlank(element.attr("title"), element.text())
            )
        }

        document.select("a[href]").forEach { anchor ->
            add(
                anchor.absUrl("href"),
                anchor.text().trim()
            )
        }

        val normalizedHtml = normalizeEmbeddedText(document.html())
        Regex(
            """https?://[^\"'<>\s]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedHtml).forEach { match ->
            val url = match.value
            if (url.contains("vidrock", true) ||
                url.contains("player", true) ||
                url.contains("embed", true) ||
                url.contains("stream", true)
            ) {
                add(url, "Zoryva Source")
            }
        }

        return result.entries
            .take(MAX_SERVER_PAGES)
            .map { it.key to it.value }
    }

    private fun discoverNestedPlayerPages(
        document: Document,
        pageUrl: String
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()

        document.select("iframe[src], embed[src], a[href]").forEach { element ->
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
        html: String
    ): JSONObject? {
        /*
         * Zoryva is Next.js. The media detail data is embedded in an RSC payload
         * under initialMedia. The helper below decodes one JavaScript-string
         * escaping layer and then extracts the balanced JSON object.
         */
        val decoded = decodeRscOneLayer(html)
        val marker = "\"initialMedia\":"
        val markerIndex = decoded.indexOf(marker)
        if (markerIndex < 0) return null

        val start = decoded.indexOf('{', markerIndex + marker.length)
        if (start < 0) return null

        val json = extractBalancedJson(
            decoded,
            start,
            '{',
            '}'
        ) ?: return null

        return runCatching {
            JSONObject(json)
        }.getOrNull()
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

    private suspend fun getDocument(
        url: String
    ): Document? {
        val clean = cleanUrl(url)
        if (clean.isBlank()) return null

        return runCatching {
            val response = app.get(
                clean,
                headers = PAGE_HEADERS + mapOf(
                    "Referer" to "$BASE_URL/"
                )
            )

            if (response.code !in 200..399) {
                null
            } else {
                response.document.apply {
                    if (baseUri().isBlank()) {
                        setBaseUri(clean)
                    }
                }
            }
        }.getOrNull()
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
            .replace(Regex("[^a-z0-9]+"), " ")
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

        return MEDIA_EXTENSIONS.any { lower.contains(it) }
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
