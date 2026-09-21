package com.movieflick.moviebox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.Headers
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MovieBox : MainAPI() {

    override var mainUrl = "https://movieboxonline.net"
    override var name = "MovieBox"
    override var lang = "hi"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/film" to MOVIES,
        "$mainUrl/tv-series" to TV_SHOW,
        "$mainUrl/animated-series" to ANIME
    )

    /*
     * Only verified domain(s) are listed here.
     * Additional real MovieBox mirrors can be inserted later in
     * priority order without changing the search/play architecture.
     */
    private val domains = listOf(
        mainUrl
    )

    private companion object {
        const val HOME_INITIAL_LIMIT = 6
        const val HOME_PAGE_LIMIT = 10
        const val SEARCH_NATIVE_PAGES = 2
        const val SEARCH_RESULT_LIMIT = 50
        const val SITEMAP_RESULT_LIMIT = 80
        const val SITEMAP_MAX_BYTES = 2_000_000
        const val MOVIE_DETAIL_ENDPOINT = "http://wefeed-h5-bff.wefeed-prod/detail"
        const val MAX_PLAYBACK_ATTEMPTS = 10

        const val MOVIES = "Movies"
        const val TV_SHOW = "Tv Show"
        const val ANIME = "Anime"
    }

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val languageRank: Int = 4,
        val subjectId: String? = null
    )

    private data class MirrorPage(
        val domain: String,
        val path: String,
        val document: Document
    ) {
        fun absoluteUrl(): String = domain + path
    }

    private data class MediaSource(
        val url: String,
        val quality: Int,
        val label: String,
        val referer: String,
        val declaredSize: Long = -1L,
        val declaredDurationSeconds: Long = -1L
    )

    private fun browserHeaders(
        referer: String = "$mainUrl/"
    ) = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml," +
                "application/xml;q=0.9,*/*;q=0.8"
            ),
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache, no-store, max-age=0",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    /** Exact browser-style TV player referer used by MovieBox H5. */
    private fun websitePlayReferer(subjectId: String, detailPath: String): String {
        val cleanPath = detailPath.trim().trimStart('/').substringBefore('?').substringBefore('#')
        return "$mainUrl/play/$cleanPath?id=${URLEncoder.encode(subjectId, "UTF-8")}&scene=&page_from=type_filter_tv&type=/movie/detail&tab=tv"
    }

    /*
     * ------------------------------------------------------------
     * HOME
     * ------------------------------------------------------------
     *
     * The site order is preserved. No duplicate removal is applied.
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val currentPage = page.coerceAtLeast(1)
        val section = request.name

        val basePath = when (section) {
            MOVIES -> "/film"
            TV_SHOW -> "/tv-series"
            ANIME -> "/animated-series"
            else -> return newHomePageResponse(
                data = request,
                list = emptyList(),
                hasNext = false
            )
        }

        val forcedType = when (section) {
            TV_SHOW -> TvType.TvSeries
            ANIME -> TvType.Anime
            else -> TvType.Movie
        }

        for (route in pageRoutes(basePath, currentPage)) {
            val result = fetchMirrorPage(route) ?: continue
            val items = parseListing(result.document, forcedType)
            if (items.isEmpty()) continue

            val limit = if (currentPage == 1) HOME_INITIAL_LIMIT else HOME_PAGE_LIMIT
            return newHomePageResponse(
                data = request,
                list = items.take(limit).map { it.toSearchResponse() },
                hasNext = detectHasNext(result.document, currentPage) || items.size > limit
            )
        }

        // API listing fallback. The public BFF is queried only when the HTML
        // listing did not yield usable cards. No authentication bypass or
        // private signing secret is used here.
        val apiItems = fetchApiCatalog(
            section = section,
            page = currentPage
        )

        if (apiItems.isNotEmpty()) {
            val limit = if (currentPage == 1) HOME_INITIAL_LIMIT else HOME_PAGE_LIMIT
            return newHomePageResponse(
                data = request,
                list = apiItems.take(limit).map { it.toSearchResponse() },
                hasNext = apiItems.size > limit
            )
        }

        return newHomePageResponse(
            data = request,
            list = emptyList(),
            hasNext = false
        )
    }

    private fun sortHomeItems(items: List<SiteItem>): List<SiteItem> {
        // Keep the website ranking/order intact on home/category pages.
        return items
    }

    private fun interleaveBySourceType(
        items: List<SiteItem>
    ): List<SiteItem> {
        if (items.size < 2) return items

        val movies =
            items.filter {
                it.type == TvType.Movie
            }

        val series =
            items.filter {
                it.type == TvType.TvSeries
            }

        if (movies.isEmpty() || series.isEmpty()) {
            return items
        }

        val result = ArrayList<SiteItem>(
            min(
                items.size,
                HOME_INITIAL_LIMIT * 2
            )
        )

        var movieIndex = 0
        var seriesIndex = 0

        while (
            movieIndex < movies.size ||
            seriesIndex < series.size
        ) {
            repeat(2) {
                if (movieIndex < movies.size) {
                    result.add(movies[movieIndex++])
                }
            }

            if (seriesIndex < series.size) {
                result.add(series[seriesIndex++])
            }

            if (result.size >= items.size) break
        }

        val used =
            result.mapTo(
                HashSet()
            ) {
                contentKey(it.url)
            }

        items.forEach { item ->
            if (used.add(contentKey(item.url))) {
                result.add(item)
            }
        }

        return result
    }

    /*
     * ------------------------------------------------------------
     * SEARCH
     * ------------------------------------------------------------
     *
     * Mirror-first failover:
     *
     *   Mirror 1 -> native search -> return if useful
     *             -> sitemap fallback -> return if useful
     *   Mirror 2 -> same
     *   Mirror 3 -> same
     *
     * We never move to another mirror after a useful result has
     * already been found.
     */
    /**
     * CloudStream can call quickSearch while the user is typing.
     * We use the site's native search fast path here and keep the same
     * language ranking used by full search.
     */
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val value = query.trim()
        if (value.isBlank()) return emptyList()

        val collected = LinkedHashMap<String, SiteItem>()

        runCatching {
            searchWebsiteH5(value, 24).forEach {
                collected.putIfAbsent(contentKey(it.url), it)
            }
        }

        runCatching {
            searchApi(value, 1, 24).forEach {
                collected.putIfAbsent(contentKey(it.url), it)
            }
        }

        runCatching {
            searchNative(mainUrl, value).take(24).forEach {
                collected.putIfAbsent(contentKey(it.url), it)
            }
        }

        if (collected.size < 8) {
            runCatching {
                searchSitemap(mainUrl, value).take(24).forEach {
                    collected.putIfAbsent(contentKey(it.url), it)
                }
            }
        }

        return rankSearchResults(value, collected.values.toList()).take(8)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val original = query.trim()
        if (original.isBlank()) return emptyList()

        val collected = LinkedHashMap<String, SiteItem>()

        runCatching {
            searchWebsiteH5(original, SEARCH_RESULT_LIMIT).forEach {
                collected.putIfAbsent(contentKey(it.url), it)
            }
        }

        runCatching {
            searchApi(original, 1, SEARCH_RESULT_LIMIT).forEach {
                collected.putIfAbsent(contentKey(it.url), it)
            }
        }

        for (domain in domains) {
            runCatching {
                searchNative(domain, original).forEach {
                    collected.putIfAbsent(contentKey(it.url), it)
                }
            }
        }

        if (collected.size < SEARCH_RESULT_LIMIT) {
            for (domain in domains) {
                runCatching {
                    searchSitemap(domain, original).forEach {
                        collected.putIfAbsent(contentKey(it.url), it)
                    }
                }
            }
        }

        return rankSearchResults(original, collected.values.toList())
    }

    private suspend fun searchNative(
        domain: String,
        query: String
    ): List<SiteItem> {
        val home = getDocument(domain, "/")?.document
        val variants = buildSearchVariants(query)
        val routes = linkedSetOf<String>()

        discoverSearchForms(home, variants, routes)

        for (variant in variants) {
            val encoded = URLEncoder.encode(variant, "UTF-8")
            routes.add("/search?q=$encoded")
            routes.add("/search?query=$encoded")
            routes.add("/search?keyword=$encoded")
            routes.add("/search?s=$encoded")
        }

        val results = ArrayList<SiteItem>()
        for (route in routes) {
            for (page in 1..SEARCH_NATIVE_PAGES) {
                val result = getDocument(domain, addPageParameter(route, page)) ?: break
                val nuxtResults = parseNuxtListing(result.document, null)
                if (nuxtResults.isNotEmpty()) {
                    results.addAll(nuxtResults)
                } else {
                    result.document.select(
                        ".movie-card, .flw-item, .film-poster-ahref, .film_list-wrap .flw-item"
                    ).mapNotNull(::parseSearchCard).forEach { results.add(it) }
                }
                if (!detectHasNext(result.document, page) || results.size >= SEARCH_RESULT_LIMIT * 2) break
            }
            if (results.size >= SEARCH_RESULT_LIMIT * 2) break
        }
        return results
    }

    private suspend fun searchSitemap(
        domain: String,
        query: String
    ): List<SiteItem> {

        val sitemapPaths =
            listOf(
                "/sitemap.xml",
                "/sitemap_index.xml",
                "/sitemap-index.xml"
            )

        val allPaths =
            linkedSetOf<String>()

        for (sitemapPath in sitemapPaths) {

            val response =
                runCatching {
                    app.get(
                        domain + sitemapPath,
                        headers =
                            browserHeaders(
                                domain + "/"
                            )
                    )
                }.getOrNull()
                    ?: continue

            if (
                response.code !in 200..399
            ) {
                continue
            }

            val text =
                response.text.take(
                    SITEMAP_MAX_BYTES
                )

            Regex(
                """<loc>\s*(https?://[^<]+)\s*</loc>""",
                RegexOption.IGNORE_CASE
            ).findAll(text).forEach { match ->

                val path =
                    pathFromUrl(
                        match.groupValues[1]
                            .trim()
                    )

                if (
                    isLikelyContentPath(
                        path
                    )
                ) {
                    allPaths.add(
                        normalizePath(
                            path
                        )
                    )
                }
            }

            if (allPaths.isNotEmpty()) {
                break
            }
        }

        if (allPaths.isEmpty()) {
            return emptyList()
        }

        return allPaths
            .map { path ->

                val slug =
                    path
                        .substringAfterLast('/')
                        .substringBefore('?')
                        .replace('-', ' ')
                        .replace('_', ' ')

                val title =
                    cleanTitle(slug)

                SiteItem(
                    title = title,
                    url = canonicalUrl(path),
                    poster = null,
                    type = typeFromPath(path),
                    languageRank =
                        languageRank(title)
                ) to
                    searchScore(
                        query,
                        title
                    )
            }
            .filter {
                it.second >= 0.50
            }
            .sortedWith(
                compareBy<
                    Pair<SiteItem, Double>
                > {
                    it.first.languageRank
                }.thenByDescending {
                    it.second
                }
            )
            .take(
                SITEMAP_RESULT_LIMIT
            )
            .map {
                it.first
            }
    }

    private fun discoverSearchForms(
        home: Document?,
        variants: Set<String>,
        routes: MutableSet<String>
    ) {
        if (home == null) return

        home.select("form").forEach { form ->

            val action =
                firstNonBlank(
                    form.attr("action"),
                    "/search"
                ) ?: return@forEach

            val method =
                form.attr("method")
                    .trim()
                    .uppercase(
                        Locale.ROOT
                    )

            if (
                method.isNotBlank() &&
                method != "GET"
            ) {
                return@forEach
            }

            val inputNames =
                form
                    .select(
                        "input[name], textarea[name]"
                    )
                    .mapNotNull { input ->

                        input.attr("name")
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                    }

            val queryName =
                inputNames.firstOrNull {
                    it.equals("q", true) ||
                        it.equals("query", true) ||
                        it.equals("search", true) ||
                        it.equals("keyword", true) ||
                        it.equals("s", true) ||
                        it.contains(
                            "search",
                            true
                        )
                } ?: "q"

            for (variant in variants) {

                val encoded =
                    URLEncoder.encode(
                        variant,
                        "UTF-8"
                    )

                val actionPath =
                    normalizePath(
                        action
                            .removePrefix(domainRoot(mainUrl))
                    )

                val separator =
                    if (
                        actionPath.contains("?")
                    ) {
                        "&"
                    } else {
                        "?"
                    }

                routes.add(
                    "$actionPath$separator$queryName=$encoded"
                )
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * LOAD
     * ------------------------------------------------------------
     */
    override suspend fun load(url: String): LoadResponse? {
        val path = pathFromUrl(url)
        if (path.isBlank()) return null

        // MovieBox uses /play/<slug> for both Movie and TV. The TV player URL
        // itself carries tab=tv/page_from=type_filter_tv, which is the reliable
        // type signal for a /play route.
        val contentType = typeFromUrl(url, path)
        val slug = contentSlug(path)
        val urlSubjectId = Regex("(?i)[?&]id=(\\d{8,25})\\b")
            .find(url)?.groupValues?.getOrNull(1)

        val candidatePaths = linkedSetOf<String>().apply {
            when (contentType) {
                TvType.TvSeries -> {
                    slug?.takeIf { it.isNotBlank() }?.let { add("/play/$it") }
                    add(path)
                }
                else -> {
                    add(path)
                    when {
                        path.startsWith("/film/") -> {
                            add("/movies/" + path.removePrefix("/film/"))
                            derivedPlayPath(path)?.let(::add)
                        }
                        path.startsWith("/movies/") -> {
                            add("/film/" + path.removePrefix("/movies/"))
                            add("/play/" + path.removePrefix("/movies/"))
                        }
                        path.startsWith("/animated-series/") -> {
                            slug?.takeIf { it.isNotBlank() }?.let { add("/play/$it") }
                        }
                        path.startsWith("/play/") -> {
                            val playSlug = path.removePrefix("/play/")
                            if (playSlug.isNotBlank()) {
                                add("/film/$playSlug")
                                add("/movies/$playSlug")
                                add("/tv-series/$playSlug")
                                add("/animated-series/$playSlug")
                            }
                        }
                    }
                }
            }
        }

        var page: MirrorPage? = null
        var document: Document? = null
        for (candidate in candidatePaths) {
            val candidatePage = fetchMirrorPage(candidate) ?: continue
            if (page == null) page = candidatePage
            document = candidatePage.document
            if (extractWebsiteSubjectData(document, path, titleHintFromDocument(document)) != null) {
                page = candidatePage
                break
            }
        }

        if (document == null) {
            contentSlug(path)?.let { document = fetchBackendDetailDocument(it) }
        }

        val rawTitle = firstNonBlank(
            document?.selectFirst("meta[property=og:title]")?.attr("content"),
            document?.selectFirst("h1")?.text(),
            document?.selectFirst(".film-name")?.text(),
            document?.title()
        ).orEmpty()

        val title = cleanDisplayTitle(rawTitle).ifBlank { cleanDisplayTitle(titleFromContentPath(path)) }
        if (title.isBlank()) return null

        val subjectData = document?.let { extractWebsiteSubjectData(it, path, title) }
        val poster = firstUsefulUrl(
            document?.selectFirst("meta[property=og:image]")?.attr("content"),
            document?.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document?.selectFirst(".film-poster img, .movie-card img, img")?.let(::extractImageUrl),
            subjectData?.poster
        )
        val year = document?.let { extractYear(rawTitle + " " + title, it) }
        val imdbRating = extractImdbRating(rawTitle, document)
        val imdbText = imdbRating?.let { "IMDb ${formatRating(it)}" }

        if (contentType == TvType.TvSeries) {
            val subjectId = urlSubjectId
                ?: subjectData?.subjectId
                ?: findWebsiteSubjectId(title, contentType)
                ?: findApiSubjectId(title)
            val detailPath = subjectData?.detailPath ?: contentSlug(path).orEmpty()
            val episodes = if (!subjectId.isNullOrBlank() && detailPath.isNotBlank()) {
                fetchWebsiteEpisodes(subjectId, detailPath, subjectData, document)
            } else emptyList()

            return newTvSeriesLoadResponse(
                name = title,
                url = canonicalUrl(path),
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                posterUrl = poster
                plot = imdbText
                this.year = year
            }
        }

        val subjectId = subjectData?.subjectId ?: urlSubjectId
        val detailPath = subjectData?.detailPath ?: contentSlug(path).orEmpty()
        val playbackData = if (!subjectId.isNullOrBlank() && detailPath.isNotBlank()) {
            "mbxcontent||$subjectId||$detailPath"
        } else canonicalUrl(path)

        return newMovieLoadResponse(
            name = title,
            url = canonicalUrl(path),
            type = contentType,
            dataUrl = playbackData
        ) {
            posterUrl = poster
            plot = imdbText
            this.year = year
        }
    }

    private fun titleHintFromDocument(document: Document): String =
        cleanDisplayTitle(
            firstNonBlank(
                document.selectFirst("meta[property=og:title]")?.attr("content"),
                document.selectFirst("h1")?.text(),
                document.title()
            ).orEmpty()
        )

    /*
     * ------------------------------------------------------------
     * PLAYBACK
     * ------------------------------------------------------------
     *
     * Fresh request every time.
     *
     * No token/media URL is cached by this provider.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawData = data.trim()
        if (rawData.isBlank()) return false

        if (rawData.startsWith("mbxepisode||")) {
            val parts = rawData.split("||")
            if (parts.size >= 5) {
                return resolveWebsitePlayback(
                    subjectId = parts[1],
                    detailPath = parts[2],
                    season = parts[3].toIntOrNull() ?: 1,
                    episode = parts[4].toIntOrNull() ?: 1,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            // Backward compatibility for older cached episode entries.
            if (parts.size >= 4) {
                return resolveApiPlayback(
                    subjectId = parts[1],
                    season = parts[2].toIntOrNull() ?: 1,
                    episode = parts[3].toIntOrNull() ?: 1,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        if (rawData.startsWith("mbxcontent||")) {
            val parts = rawData.split("||")
            if (parts.size >= 3) {
                if (resolveWebsiteMoviePlayback(parts[1], parts[2], subtitleCallback, callback)) return true
            }
        }

        val pageUrl = rawData.substringBefore("||").trim()
        val pagePath = pathFromUrl(pageUrl)
        if (pagePath.isBlank()) return false

        repeat(MAX_PLAYBACK_ATTEMPTS) {
            val candidateUrls = linkedSetOf<String>()
            candidateUrls.add(canonicalUrl(pagePath))
            if (pagePath.startsWith("/film/")) {
                candidateUrls.add(canonicalUrl("/movies/" + pagePath.removePrefix("/film/")))
            }
            contentSlug(pagePath)?.let { candidateUrls.add(canonicalUrl("/play/$it")) }
            for (candidateUrl in candidateUrls) {
                if (resolvePublicPageCandidate(candidateUrl, pageUrl, subtitleCallback, callback)) return true
            }
        }
        return false
    }

    private suspend fun resolvePublicPageCandidate(
        candidateUrl: String,
        sourcePageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(
                addCacheBuster(candidateUrl),
                headers = browserHeaders(sourcePageUrl) + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/json,text/plain,*/*;q=0.8",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return false

        if (response.code !in 200..399) return false

        val sources = extractMediaSources(response.document, candidateUrl, response.text)
            .filter { looksLikePlayableMedia(it.url) }
            .filterNot { isObviousTrailerOrPreview(it.url) }

        if (sources.isEmpty()) return false

        val best = sources.maxWithOrNull(
            compareBy<MediaSource> { it.declaredSize }
                .thenBy { it.declaredDurationSeconds }
                .thenBy { it.quality }
        ) ?: return false

        emitSource(best, callback)
        for (subtitle in extractSubtitles(response.document)) {
            subtitleCallback(newSubtitleFile(subtitle.first, subtitle.second))
        }
        return true
    }

    private suspend fun emitSource(
        source: MediaSource,
        callback: (ExtractorLink) -> Unit
    ) {
        val displayName = when {
            source.url.contains(".m3u8", true) ||
                source.url.contains(".mpd", true) ->
                "Auto / Multi-Quality"

            source.quality == 1080 ->
                "1080p"

            source.quality == 720 ->
                "720p"

            source.quality == 480 ->
                "480p"

            source.quality > 0 ->
                "${source.quality}p"

            else ->
                source.label
        }

        val sourceType = when {
            source.url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            source.url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        callback(
            newExtractorLink(
                "MovieBox",
                displayName,
                source.url,
                sourceType
            ) {
                quality = source.quality
                referer = source.referer
            }
        )
    }

    private fun isObviousTrailerOrPreview(
        url: String
    ): Boolean {
        val value =
            url.lowercase(
                Locale.ROOT
            )

        return listOf(
            "trailer",
            "teaser",
            "preview",
            "sample",
            "clip",
            "promo"
        ).any {
            value.contains(it)
        }
    }

    /*
     * ------------------------------------------------------------
     * PUBLIC BFF API FALLBACK
     * ------------------------------------------------------------
     *
     * The website is backed by public MovieBox/WeFeed BFF routes. We use only
     * ordinary GET requests with normal client headers here. No application
     * secret, cryptographic gateway key, or private authentication token is
     * embedded in the provider.
     */
    private object ApiConfig {
        const val API_BASE_1 = "https://api6.aoneroom.com"
        const val API_BASE_2 = "https://api5.aoneroom.com"
        const val API_PREFIX = "/wefeed-mobile-bff/subject-api"
        const val JSON_SEARCH = "$API_PREFIX/search"
        const val JSON_SUGGEST = "$API_PREFIX/search-suggest"
        const val JSON_GET = "$API_PREFIX/get"
        const val JSON_SEASON_INFO = "$API_PREFIX/season-info"
        const val JSON_EPISODE_LIST = "$API_PREFIX/episode-list"
        const val JSON_PLAY_INFO = "$API_PREFIX/play-info"
    }

    private val jsonMapper by lazy { ObjectMapper() }

    /*
     * MovieBox H5 uses a short-lived browser session token for the BFF.
     * The website establishes it while loading normal pages; keep only the
     * runtime cookies observed from those responses and reuse them for the
     * same-origin H5 API calls. Nothing is hard-coded.
     */
    private val websiteCookies: MutableMap<String, String> = linkedMapOf()

    private fun apiHeaders() = mapOf(
        "Accept" to "application/json,text/plain,*/*;q=0.8",
        "User-Agent" to (browserHeaders()["User-Agent"] ?: "Mozilla/5.0"),
        "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8,bn;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    private fun websiteCookieHeader(): String =
        websiteCookies.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    private fun captureWebsiteCookies(headers: Headers) {
        headers.values("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';').trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return@forEach

            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (name.isNotBlank() && value.isNotBlank()) {
                websiteCookies[name] = value
            }
        }
    }

    private fun captureRuntimeTokens(html: String) {
        if (html.isBlank()) return

        val tokenRegex = Regex(
            """(?is)[\"'](token|apiToken)[\"']\s*[:=]\s*[\"'](eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)[\"']"""
        )

        tokenRegex.findAll(
            html
                .replace("\\u0022", "\"")
                .replace("\\u003A", ":")
        ).forEach { match ->
            websiteCookies[match.groupValues[1]] = match.groupValues[2]
        }
    }

    private suspend fun ensureWebsiteSession() {
        if (websiteCookies.containsKey("token") || websiteCookies.containsKey("apiToken")) return

        val response = runCatching {
            app.get(
                "$mainUrl/",
                headers = browserHeaders("$mainUrl/") + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "same-origin",
                    "Upgrade-Insecure-Requests" to "1"
                )
            )
        }.getOrNull() ?: return

        if (response.code in 200..399) {
            captureWebsiteCookies(response.headers)
            captureRuntimeTokens(response.text)
        }
    }

    private fun websiteH5Headers(referer: String = "$mainUrl/"): MutableMap<String, String> {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "User-Agent" to (browserHeaders()["User-Agent"] ?: "Mozilla/5.0"),
            "Accept-Language" to "en-US,en;q=0.9,hi;q=0.8,bn;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "X-Request-Lang" to "en",
            "X-Client-Info" to "{\"timezone\":\"UTC\"}",
            "Origin" to mainUrl,
            "Referer" to referer,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        websiteCookieHeader().takeIf { it.isNotBlank() }?.let {
            headers["Cookie"] = it
        }

        val bearer = websiteCookies["token"]
            ?.takeIf { it.startsWith("eyJ") }
            ?: websiteCookies["apiToken"]
                ?.takeIf { it.startsWith("eyJ") }

        bearer?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private suspend fun apiGetJson(
        path: String,
        params: Map<String, String>
    ): JsonNode? {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

        for (base in listOf(ApiConfig.API_BASE_1, ApiConfig.API_BASE_2)) {
            val url = if (query.isBlank()) {
                "$base$path"
            } else {
                "$base$path?$query"
            }

            val response = runCatching {
                app.get(addCacheBuster(url), headers = apiHeaders())
            }.getOrNull() ?: continue

            if (response.code !in 200..399) continue

            return runCatching {
                jsonMapper.readTree(response.text)
            }.getOrNull()
        }

        return null
    }

    private fun nodeText(node: JsonNode?, vararg names: String): String? {
        if (node == null || !node.isObject) return null
        for (name in names) {
            val value = node.get(name) ?: continue
            if (!value.isNull) {
                val text = when {
                    value.isTextual -> value.asText()
                    value.isNumber -> value.numberValue().toString()
                    else -> value.toString()
                }
                if (text.isNotBlank() && text != "null") return text
            }
        }
        return null
    }

    private fun nodeLong(node: JsonNode?, vararg names: String): Long {
        if (node == null || !node.isObject) return -1L
        for (name in names) {
            val value = node.get(name) ?: continue
            if (value.isNumber) return value.asLong()
            value.asText().toLongOrNull()?.let { return it }
        }
        return -1L
    }

    private fun collectJsonObjects(
        node: JsonNode?,
        out: MutableList<JsonNode>
    ) {
        if (node == null) return
        if (node.isObject) {
            val hasTitle = node.has("title") || node.has("name")
            val hasId = node.has("subjectId") || node.has("id")
            if (hasTitle && hasId) out.add(node)
            node.fields().forEachRemaining { (_, child) ->
                collectJsonObjects(child, out)
            }
        } else if (node.isArray) {
            node.forEach { child -> collectJsonObjects(child, out) }
        }
    }

    private fun firstUrlFromJsonNode(node: JsonNode?): String? {
        if (node == null) return null

        if (node.isTextual) {
            val value = node.asText().trim()
            return value.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
        }

        if (node.isObject) {
            val direct = nodeText(node, "url", "src", "href", "path")
            if (!direct.isNullOrBlank() &&
                (direct.startsWith("http://", true) || direct.startsWith("https://", true))
            ) {
                return direct
            }

            val fields = node.fields()
            while (fields.hasNext()) {
                val child = fields.next().value
                val found = firstUrlFromJsonNode(child)
                if (found != null) return found
            }
        } else if (node.isArray) {
            val iterator = node.elements()
            while (iterator.hasNext()) {
                val found = firstUrlFromJsonNode(iterator.next())
                if (found != null) return found
            }
        }

        return null
    }

    private fun apiItemFromNode(node: JsonNode, forcedType: TvType? = null): SiteItem? {
        val title = cleanTitle(
            nodeText(node, "title", "name", "postTitle", "subjectTitle").orEmpty()
        )
        if (title.isBlank()) return null

        val detailPath = nodeText(
            node,
            "detailPath", "detailUrl", "url"
        ).orEmpty()

        val typeValue = nodeText(node, "type", "subjectType")
        val type = forcedType ?: when {
            typeValue.equals("2", true) -> TvType.TvSeries
            typeValue.equals("tv", true) -> TvType.TvSeries
            typeValue.equals("series", true) -> TvType.TvSeries
            typeValue.equals("7", true) -> TvType.Anime
            detailPath.contains("animated-series", true) -> TvType.Anime
            detailPath.contains("tv-series", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        val id = nodeText(node, "subjectId", "id")
        val slug = when {
            detailPath.startsWith("http", true) -> pathFromUrl(detailPath)
            detailPath.startsWith("/") -> detailPath
            !id.isNullOrBlank() -> when (type) {
                TvType.TvSeries -> "/tv-series/${detailPath.substringAfterLast('/').ifBlank { id }}"
                TvType.Anime -> "/animated-series/${detailPath.substringAfterLast('/').ifBlank { id }}"
                else -> "/film/${detailPath.substringAfterLast('/').ifBlank { id }}"
            }
            else -> null
        } ?: return null

        val poster = firstUsefulUrl(
            firstUrlFromJsonNode(node.get("poster")),
            firstUrlFromJsonNode(node.get("cover")),
            firstUrlFromJsonNode(node.get("image")),
            firstUrlFromJsonNode(node.get("thumbnail")),
            nodeText(node, "poster", "cover", "image", "thumbnail")
        )

        val resultUrl = buildString {
            append(canonicalUrl(slug))
            if (!id.isNullOrBlank()) {
                append("?id=")
                append(URLEncoder.encode(id, "UTF-8"))
            }
        }

        return SiteItem(
            title = title,
            url = resultUrl,
            poster = poster,
            type = type,
            languageRank = languageRank(title),
            subjectId = id
        )
    }

    private suspend fun fetchApiCatalog(
        section: String,
        page: Int
    ): List<SiteItem> {
        val query = when (section) {
            TV_SHOW -> "tv"
            ANIME -> "anime"
            else -> "movie"
        }

        val json = apiGetJson(
            ApiConfig.JSON_SEARCH,
            mapOf(
                "q" to query,
                "page" to page.toString(),
                "pageSize" to HOME_PAGE_LIMIT.toString()
            )
        ) ?: return emptyList()

        val forcedType = when (section) {
            TV_SHOW -> TvType.TvSeries
            ANIME -> TvType.Anime
            else -> TvType.Movie
        }

        val nodes = ArrayList<JsonNode>()
        collectJsonObjects(json, nodes)
        return nodes
            .mapNotNull { apiItemFromNode(it) }
            .filter { it.type == forcedType }
    }

    private suspend fun searchApi(
        query: String,
        page: Int,
        pageSize: Int
    ): List<SiteItem> {
        val json = apiGetJson(
            ApiConfig.JSON_SEARCH,
            mapOf(
                "q" to query,
                "page" to page.toString(),
                "pageSize" to pageSize.toString()
            )
        ) ?: return emptyList()

        val nodes = ArrayList<JsonNode>()
        collectJsonObjects(json, nodes)

        val result = nodes.mapNotNull { apiItemFromNode(it) }
        if (result.isNotEmpty()) return result

        val suggest = apiGetJson(
            ApiConfig.JSON_SUGGEST,
            mapOf("q" to query)
        ) ?: return emptyList()

        nodes.clear()
        collectJsonObjects(suggest, nodes)
        return nodes.mapNotNull { apiItemFromNode(it) }
    }

    /** Current MovieBox H5 search BFF used by the website. */
    private suspend fun searchWebsiteH5(
        query: String,
        pageSize: Int = SEARCH_RESULT_LIMIT
    ): List<SiteItem> {
        ensureWebsiteSession()
        val endpoint = "$mainUrl/wefeed-h5api-bff/subject/everyone-search"
        val variants = listOf(
            mapOf("keyword" to query, "page" to "0", "perPage" to pageSize.toString()),
            mapOf("q" to query, "page" to "0", "perPage" to pageSize.toString()),
            mapOf("query" to query, "page" to "0", "perPage" to pageSize.toString()),
            mapOf("keyword" to query, "page" to "1", "pageSize" to pageSize.toString()),
            mapOf("q" to query, "page" to "1", "pageSize" to pageSize.toString())
        )

        for (params in variants) {
            val queryString = params.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            val response = runCatching {
                app.get(
                    addCacheBuster("$endpoint?$queryString"),
                    headers = websiteH5Headers("$mainUrl/").apply {
                        this["Accept"] = "application/json"
                    }
                )
            }.getOrNull() ?: continue
            if (response.code !in 200..399) continue
            val json = runCatching { jsonMapper.readTree(response.text) }.getOrNull() ?: continue
            val nodes = ArrayList<JsonNode>()
            collectJsonObjects(json, nodes)
            val items = nodes.mapNotNull { apiItemFromNode(it) }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private suspend fun findWebsiteSubjectId(
        title: String,
        preferredType: TvType? = null
    ): String? {
        val items = searchWebsiteH5(title, 20)
        return items
            .filter { preferredType == null || it.type == preferredType }
            .filter { !it.subjectId.isNullOrBlank() }
            .map { it.subjectId!! to searchScore(title, it.title) }
            .filter { it.second >= 0.55 }
            .maxByOrNull { it.second }
            ?.first
    }

    private suspend fun findApiSubjectId(title: String): String? {
        val json = apiGetJson(
            ApiConfig.JSON_SEARCH,
            mapOf(
                "q" to title,
                "page" to "1",
                "pageSize" to "20"
            )
        ) ?: return null

        val nodes = ArrayList<JsonNode>()
        collectJsonObjects(json, nodes)

        return nodes
            .mapNotNull { node ->
                val id = nodeText(node, "subjectId") ?: return@mapNotNull null
                val nodeTitle = nodeText(node, "title", "name").orEmpty()
                Triple(id, nodeTitle, searchScore(title, nodeTitle))
            }
            .filter { it.third >= 0.55 }
            .maxByOrNull { it.third }
            ?.first
    }

    private fun episodeCountFromSeasonNode(node: JsonNode): Int {
        return nodeLong(
            node,
            "episodesAvailable",
            "totalEpisode",
            "episodeCount",
            "maxEp",
            "allEp"
        ).toInt().coerceAtLeast(0)
    }

    private data class WebsiteSubjectData(
        val subjectId: String,
        val detailPath: String,
        val seasons: Map<Int, Int> = emptyMap(),
        val imdbRating: Double? = null,
        val poster: String? = null
    )

    private fun extractWebsiteSubjectData(
        document: Document,
        contentPath: String,
        title: String
    ): WebsiteSubjectData? {
        val decoded = document.html()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        if (decoded.isBlank()) return null

        val detailPath = contentSlug(contentPath).orEmpty()
        val normalizedTitle = normalizeSearch(title)
        val candidates = ArrayList<Pair<String, Int>>()

        val subjectRegex = Regex(
            """(?i)(?:[\"']?subjectId[\"']?|subject_id)\s*[:=]\s*[\"']?(\d{8,25})"""
        )

        fun addCandidate(id: String, position: Int, scoreBase: Int) {
            val start = max(0, position - 16000)
            val end = min(decoded.length, position + 16000)
            val window = decoded.substring(start, end)
            var score = scoreBase
            if (detailPath.isNotBlank() && window.contains(detailPath, true)) score += 14
            if (normalizedTitle.isNotBlank() && normalizeSearch(window).contains(normalizedTitle)) score += 10
            if (window.contains("resource", true)) score += 6
            if (window.contains("seasons", true)) score += 7
            if (window.contains("maxEp", true)) score += 6
            if (window.contains("epNum", true)) score += 4
            candidates.add(id to score)
        }

        subjectRegex.findAll(decoded).forEach { match ->
            val id = match.groupValues[1]
            addCandidate(id, match.range.first, 0)
        }

        // Player/detail query URLs are also strong identity hints.
        Regex("(?i)[?&]id=(\\d{8,25})\\b").findAll(decoded).forEach { match ->
            val start = max(0, match.range.first - 9000)
            val end = min(decoded.length, match.range.first + 9000)
            val window = decoded.substring(start, end)
            if (detailPath.isNotBlank() && window.contains(detailPath, true)) {
                candidates.add(match.groupValues[1] to 32)
            }
        }

        val bestId = candidates
            .groupBy { it.first }
            .maxByOrNull { (_, list) -> list.maxOf { it.second } }
            ?.key
            ?: return null

        val bestContext = subjectRegex.findAll(decoded)
            .firstOrNull { it.groupValues[1] == bestId }
            ?.let {
                val start = max(0, it.range.first - 40000)
                val end = min(decoded.length, it.range.first + 40000)
                decoded.substring(start, end)
            }
            ?: decoded

        val localSeasonCounts = parseSeasonCounts(bestContext)
        val seasonCounts = if (localSeasonCounts.isNotEmpty()) localSeasonCounts else parseSeasonCounts(decoded)
        val rating = parseImdbRatingFromText(bestContext)
        val poster = firstUsefulUrl(
            Regex("""(?i)["'](?:poster|cover|image|thumbnail)["']\s*[:=]\s*["'](https?://[^"']+)["']""")
                .find(bestContext)?.groupValues?.getOrNull(1),
            Regex("""(?i)https?://[^\"'\s]+(?:jpg|jpeg|png|webp)(?:\?[^\"'\s]*)?""")
                .find(bestContext)?.value
        )

        return WebsiteSubjectData(
            subjectId = bestId,
            detailPath = detailPath,
            seasons = seasonCounts,
            imdbRating = rating,
            poster = poster
        )
    }

    private fun parseSeasonCounts(context: String): Map<Int, Int> {
        val counts = LinkedHashMap<Int, Int>()
        val countRegex = Regex(
            """(?i)["']?(?:maxEp|maxEpisode|episodeCount|episodesAvailable|totalEpisode|allEp)["']?\s*[:=]\s*["']?(\d{1,4})"""
        )
        val epRegex = Regex(
            """(?i)["']?epNum["']?\s*[:=]\s*["']?(\d{1,4})"""
        )
        val seasonRegex = Regex(
            """(?i)["']?(?:seasonNumber|season|se)["']?\s*[:=]\s*["']?(\d{1,3})"""
        )

        fun seasonNear(position: Int): Int {
            val start = max(0, position - 3500)
            val prefix = context.substring(start, position)
            return seasonRegex.findAll(prefix)
                .lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: 1
        }

        countRegex.findAll(context).forEach { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (count > 0) {
                val season = seasonNear(match.range.first)
                counts[season] = maxOf(counts[season] ?: 0, count)
            }
        }

        epRegex.findAll(context).forEach { match ->
            val ep = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (ep > 0) {
                val season = seasonNear(match.range.first)
                counts[season] = maxOf(counts[season] ?: 0, ep)
            }
        }

        return counts.filterValues { it > 0 }
    }

    private fun parseImdbRatingFromText(text: String): Double? {
        Regex("""(?i)\bIMDb\s*[:|\-]?\s*(\d+(?:\.\d+)?)\b""")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }?.let { return it }

        Regex("""(?i)["'](?:ratingValue|rating)["']\s*[:=]\s*["']?(\d+(?:\.\d+)?)""")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }?.let { return it }

        return null
    }

    private suspend fun fetchWebsiteEpisodes(
        subjectId: String,
        detailPath: String,
        metadata: WebsiteSubjectData?,
        fallbackDocument: Document?
    ): List<Episode> {
        val counts = LinkedHashMap<Int, Int>()

        metadata?.seasons?.forEach { (season, count) ->
            if (count > 0) counts[season] = count
        }

        if (fallbackDocument != null) {
            extractWebsiteSubjectData(fallbackDocument, detailPath, "")?.seasons?.forEach { (season, count) ->
                if (count > 0) counts[season] = maxOf(counts[season] ?: 0, count)
            }

            // The exact subject object is not always the closest object to the
            // subjectId in Nuxt/devalue SSR data. Scan the complete document as
            // a metadata-only fallback before using any playback probe.
            parseSeasonCounts(fallbackDocument.html()).forEach { (season, count) ->
                if (count > 0) counts[season] = maxOf(counts[season] ?: 0, count)
            }
        }

        /*
         * Discover additional seasons that were not represented in SSR metadata.
         * A missing first episode ends the contiguous season scan.
         */
        val firstMissingSeason = (1..20).firstOrNull { season -> !counts.containsKey(season) } ?: 21
        for (season in firstMissingSeason..20) {
            val count = runCatching {
                discoverEpisodeCount(subjectId, detailPath, season, 100)
            }.getOrDefault(0)

            if (count > 0) {
                counts[season] = count
            } else if (counts.isNotEmpty()) {
                break
            }
        }

        if (counts.isEmpty()) return emptyList()

        return counts.toSortedMap().flatMap { (season, count) ->
            (1..count).map { episode ->
                newEpisode(
                    data = "mbxepisode||$subjectId||$detailPath||$season||$episode"
                ) {
                    name = "Episode $episode"
                    this.season = season
                    this.episode = episode
                }
            }
        }
    }

    private suspend fun discoverEpisodeCount(
        subjectId: String,
        detailPath: String,
        season: Int,
        maxProbe: Int
    ): Int {
        if (!websiteEpisodeHasResource(subjectId, detailPath, season, 1)) return 0

        var low = 1
        var high = 2
        while (high <= maxProbe && websiteEpisodeHasResource(subjectId, detailPath, season, high)) {
            low = high
            high *= 2
        }

        if (high > maxProbe) {
            high = maxProbe
            if (websiteEpisodeHasResource(subjectId, detailPath, season, high)) return high
        }

        var left = low + 1
        var right = high - 1
        var best = low
        while (left <= right) {
            val mid = (left + right) ushr 1
            if (websiteEpisodeHasResource(subjectId, detailPath, season, mid)) {
                best = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }
        return best
    }

    private suspend fun websiteEpisodeHasResource(
        subjectId: String,
        detailPath: String,
        season: Int,
        episode: Int
    ): Boolean {
        val json = websitePlayGet(
            mapOf(
                "subjectId" to subjectId,
                "se" to season.toString(),
                "ep" to episode.toString(),
                "detailPath" to detailPath.trim().trimStart('/').substringBefore('?').substringBefore('#')
            ),
            websitePlayReferer(subjectId, detailPath)
        ) ?: return false

        val data = json.path("data")
        if (data.isMissingNode || data.isNull) return false
        if (!data.path("hasResource").asBoolean(false)) return false
        if (data.path("vipLocked").asBoolean(false)) return false
        return data.path("streams").isArray && data.path("streams").size() > 0
    }

    /** Verified from the browser Network trace supplied by the user. */
    private suspend fun resolveWebsitePlayback(
        subjectId: String,
        detailPath: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (subjectId.isBlank() || detailPath.isBlank()) return false

        val cleanDetailPath = detailPath.trim().trimStart('/').substringBefore('?').substringBefore('#')
        val json = websitePlayGet(
            mapOf(
                "subjectId" to subjectId,
                "se" to season.toString(),
                "ep" to episode.toString(),
                "detailPath" to cleanDetailPath
            ),
            websitePlayReferer(subjectId, cleanDetailPath)
        ) ?: return false

        val found = linkedMapOf<String, MediaSource>()
        collectStreamSources(json, found)
        collectApiSubtitles(json, subtitleCallback)

        val playable = found.values
            .filter { looksLikePlayableMedia(it.url) }
            .filterNot { isObviousTrailerOrPreview(it.url) }
            .sortedWith(
                compareByDescending<MediaSource> { it.quality }
                    .thenByDescending { it.declaredDurationSeconds }
                    .thenByDescending { it.declaredSize }
            )

        if (playable.isEmpty()) return false
        playable.forEach { emitSource(it.copy(referer = "$mainUrl/"), callback) }
        return true
    }

    private suspend fun websitePlayGet(
        params: Map<String, String>,
        referer: String = "$mainUrl/"
    ): JsonNode? {
        ensureWebsiteSession()

        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        val url = "$mainUrl/wefeed-h5api-bff/subject/play?$query"

        suspend fun request(): JsonNode? {
            val response = runCatching {
                app.get(url, headers = websiteH5Headers(referer))
            }.getOrNull() ?: return null

            captureWebsiteCookies(response.headers)
            captureRuntimeTokens(response.text)
            if (response.code !in 200..399) return null

            val json = runCatching { jsonMapper.readTree(response.text) }.getOrNull() ?: return null
            return json.takeIf { it.path("code").asInt(-1) == 0 }
        }

        request()?.let { return it }

        // Refresh once if the short-lived website token/session has expired.
        websiteCookies.clear()
        ensureWebsiteSession()
        return request()
    }

    private suspend fun resolveWebsiteMoviePlayback(
        subjectId: String,
        detailPath: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (subjectId.isBlank() || detailPath.isBlank()) return false
        val json = websitePlayGet(
            mapOf(
                "subjectId" to subjectId,
                "detailPath" to detailPath.trim().trimStart('/').substringBefore('?').substringBefore('#')
            )
        ) ?: return false

        val found = linkedMapOf<String, MediaSource>()
        collectStreamSources(json, found)
        collectApiSubtitles(json, subtitleCallback)

        val playable = found.values
            .filter { looksLikePlayableMedia(it.url) }
            .filterNot { isObviousTrailerOrPreview(it.url) }
            .sortedWith(
                compareByDescending<MediaSource> { it.quality }
                    .thenByDescending { it.declaredDurationSeconds }
                    .thenByDescending { it.declaredSize }
            )
        playable.forEach { emitSource(it.copy(referer = "$mainUrl/"), callback) }
        return playable.isNotEmpty()
    }

    private suspend fun fetchApiEpisodes(
        subjectId: String,
        seriesTitle: String
    ): List<Episode> {
        val requests = listOf(
            ApiConfig.JSON_SEASON_INFO to mapOf("subjectId" to subjectId),
            ApiConfig.JSON_EPISODE_LIST to mapOf("subjectId" to subjectId)
        )

        val episodes = LinkedHashMap<String, EpisodeInfoHolder>()

        for ((endpoint, params) in requests) {
            val json = apiGetJson(endpoint, params) ?: continue
            collectEpisodeObjects(json, episodes)
        }

        if (episodes.isEmpty()) return emptyList()

        return episodes.values
            .sortedWith(compareBy<EpisodeInfoHolder> { it.season }.thenBy { it.episode })
            .map { info ->
                newEpisode(
                    data = "mbxepisode||$subjectId||${info.season}||${info.episode}"
                ) {
                    name = info.title.ifBlank { "Episode ${info.episode}" }
                    season = info.season
                    episode = info.episode
                }
            }
    }

    private data class EpisodeInfoHolder(
        val season: Int,
        val episode: Int,
        val title: String = ""
    )

    private fun collectEpisodeObjects(
        node: JsonNode?,
        out: MutableMap<String, EpisodeInfoHolder>,
        inheritedSeason: Int = 1
    ) {
        if (node == null) return

        if (node.isObject) {
            val season = nodeLong(node, "season", "seasonNumber", "se").toInt().let {
                if (it > 0) it else inheritedSeason
            }

            val episode = nodeLong(
                node,
                "episode",
                "episodeNumber",
                "ep",
                "epNum"
            ).toInt()

            if (episode > 0) {
                val key = "$season-$episode"
                out[key] = EpisodeInfoHolder(
                    season = season,
                    episode = episode,
                    title = nodeText(node, "title", "name", "episodeTitle").orEmpty()
                )
            }

            val count = episodeCountFromSeasonNode(node)
            if (count > 0 && (node.has("season") || node.has("seasonNumber") || node.has("se"))) {
                for (ep in 1..count) {
                    val key = "$season-$ep"
                    out.putIfAbsent(key, EpisodeInfoHolder(season, ep))
                }
            }

            node.fields().forEachRemaining { (_, child) ->
                collectEpisodeObjects(child, out, season)
            }
        } else if (node.isArray) {
            node.forEach { child -> collectEpisodeObjects(child, out, inheritedSeason) }
        }
    }

    private suspend fun resolveApiPlayback(
        subjectId: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val qualities = listOf("1080p", "720p", "480p")
        val found = linkedMapOf<String, MediaSource>()

        suspend fun requestPlayback(resourceId: String? = null, quality: String? = null) {
            val params = linkedMapOf(
                "subjectId" to subjectId,
                "se" to season.toString(),
                "ep" to episode.toString()
            )
            if (!quality.isNullOrBlank()) params["quality"] = quality
            if (!resourceId.isNullOrBlank()) params["resourceId"] = resourceId

            val json = apiGetJson(ApiConfig.JSON_PLAY_INFO, params) ?: return
            collectStreamSources(json, found)
            collectApiSubtitles(json, subtitleCallback)
        }

        // First use the normal public resolver.
        for (quality in qualities) {
            requestPlayback(quality = quality)
        }

        // If the public resolver requires a resourceId (dub/source group),
        // discover resourceIds from the public metadata endpoint and retry each
        // quality with those ids. No application secrets are generated here.
        if (found.isEmpty()) {
            val detail = apiGetJson(
                ApiConfig.JSON_GET,
                mapOf(
                    "subjectId" to subjectId,
                    "host" to mainUrl
                )
            )
            val resourceIds = LinkedHashSet<String>()
            collectResourceIds(detail, resourceIds)

            for (resourceId in resourceIds.take(6)) {
                for (quality in qualities) {
                    requestPlayback(resourceId = resourceId, quality = quality)
                }
            }
        }

        if (found.isEmpty()) {
            requestPlayback()
        }

        val playable = found.values
            .filter { looksLikePlayableMedia(it.url) }
            .filterNot { isObviousTrailerOrPreview(it.url) }

        if (playable.isEmpty()) return false

        val explicit = playable
            .filter { it.quality in setOf(480, 720, 1080) }
            .groupBy { it.quality }
            .values
            .mapNotNull { list ->
                list.maxWithOrNull(
                    compareBy<MediaSource> { it.declaredSize }
                        .thenBy { it.declaredDurationSeconds }
                        .thenBy { it.url }
                )
            }

        val selected = if (explicit.isNotEmpty()) {
            explicit.sortedByDescending { it.quality }
        } else {
            playable.maxWithOrNull(
                compareBy<MediaSource> { it.declaredSize }
                    .thenBy { it.declaredDurationSeconds }
                    .thenBy { it.quality }
            )?.let(::listOf) ?: emptyList()
        }

        selected.forEach { emitSource(it, callback) }
        return selected.isNotEmpty()
    }

    private fun collectResourceIds(
        node: JsonNode?,
        out: MutableSet<String>
    ) {
        if (node == null) return
        if (node.isObject) {
            val resourceId = nodeText(node, "resourceId", "rid")
            if (!resourceId.isNullOrBlank()) out.add(resourceId)
            node.fields().forEachRemaining { (_, child) ->
                collectResourceIds(child, out)
            }
        } else if (node.isArray) {
            node.forEach { child -> collectResourceIds(child, out) }
        }
    }

    private fun collectStreamSources(
        node: JsonNode?,
        out: MutableMap<String, MediaSource>,
        context: String = ""
    ) {
        if (node == null) return

        if (node.isObject) {
            val localQuality = nodeText(
                node,
                "quality", "definition", "resolution", "name", "label"
            ).orEmpty()
            val localSize = nodeLong(node, "size", "fileSize", "contentLength")
            val localDuration = nodeLong(node, "duration", "durationSeconds")

            node.fields().forEachRemaining { (key, child) ->
                if (child.isTextual && looksLikePlayableMedia(child.asText())) {
                    val url = normalizeMediaUrl(child.asText(), mainUrl) ?: return@forEachRemaining
                    val quality = qualityFromText("$localQuality $context $url")
                    out[url] = MediaSource(
                        url = url,
                        quality = quality,
                        label = if (quality > 0) "${quality}p" else "MovieBox Stream",
                        referer = mainUrl,
                        declaredSize = maxOf(out[url]?.declaredSize ?: -1L, localSize),
                        declaredDurationSeconds = maxOf(
                            out[url]?.declaredDurationSeconds ?: -1L,
                            localDuration
                        )
                    )
                } else {
                    collectStreamSources(child, out, "$context $key $localQuality")
                }
            }
        } else if (node.isArray) {
            node.forEach { child -> collectStreamSources(child, out, context) }
        }
    }

    private suspend fun collectApiSubtitles(
        node: JsonNode?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        suspend fun walk(value: JsonNode?) {
            if (value == null) return

            if (value.isObject) {
                val url = nodeText(value, "url", "subtitleUrl", "src")
                val language = nodeText(value, "lanName", "language", "name", "lang")

                if (!url.isNullOrBlank() &&
                    (url.contains(".srt", true) || url.contains(".vtt", true))
                ) {
                    val absolute = normalizeMediaUrl(url, mainUrl)
                    if (absolute != null) {
                        subtitleCallback(newSubtitleFile(language ?: "Subtitle", absolute))
                    }
                }

                val fields = value.fields()
                while (fields.hasNext()) {
                    walk(fields.next().value)
                }
            } else if (value.isArray) {
                val iterator = value.elements()
                while (iterator.hasNext()) {
                    walk(iterator.next())
                }
            }
        }

        walk(node)
    }

    private fun extractJsonLdUrls(
        html: String
    ): List<String> {
        val result = linkedSetOf<String>()
        val scriptRegex = Regex(
            """(?is)<script[^>]*application/ld\+json[^>]*>(.*?)</script>"""
        )

        scriptRegex.findAll(html).forEach { match ->
            val block = match.groupValues[1]

            Regex(
                """(?i)"(?:embedUrl|target|url)"\s*:\s*"(https?://[^"]+/play/[^"]+)"""
            ).findAll(block).forEach { m ->
                result.add(m.groupValues[1].replace("\\/", "/"))
            }
        }

        val directPlay = Regex(
            """(?i)https?://[^\"'<>\s]+/play/[^\"'<>\s]+"""
        )
        directPlay.findAll(html).forEach { m ->
            result.add(m.value.replace("\\/", "/"))
        }

        Regex(
            """(?i)"(/play/[^\"]+)"""
        ).findAll(html).forEach { m ->
            result.add(absoluteUrl(m.groupValues[1]))
        }

        return result.toList()
    }

    private fun extractJsonLdMedia(
        html: String,
        pageUrl: String
    ): List<MediaSource> {
        val found = linkedMapOf<String, MediaSource>()
        val scriptRegex = Regex(
            """(?is)<script[^>]*application/ld\+json[^>]*>(.*?)</script>"""
        )

        scriptRegex.findAll(html).forEach { match ->
            val block = match.groupValues[1]
            Regex(
                """(?is)"contentUrl"\s*:\s*"([^"]+)".{0,900}?"duration"\s*:\s*"?(?:PT)?(\d+(?:\.\d+)?)"""
            ).findAll(block).forEach { media ->
                val url =
                    normalizeMediaUrl(
                        media.groupValues[1],
                        pageUrl
                    ) ?: return@forEach

                val duration =
                    media.groupValues
                        .getOrNull(2)
                        ?.toDoubleOrNull()
                        ?.toLong()
                        ?: -1L

                found.putIfAbsent(
                    url,
                    MediaSource(
                        url = url,
                        quality = qualityFromText(url),
                        label = "MovieBox Stream",
                        referer = pageUrl,
                        declaredDurationSeconds = duration
                    )
                )
            }

            Regex(
                """(?i)"contentUrl"\s*:\s*"([^"]+)"""
            ).findAll(block).forEach { media ->
                val url =
                    normalizeMediaUrl(
                        media.groupValues[1],
                        pageUrl
                    ) ?: return@forEach

                found.putIfAbsent(
                    url,
                    MediaSource(
                        url = url,
                        quality = qualityFromText(url),
                        label = "MovieBox Stream",
                        referer = pageUrl
                    )
                )
            }
        }

        return found.values.toList()
    }

    private fun extractMediaSources(
        document: Document,
        pageUrl: String,
        html: String = document.html()
    ): List<MediaSource> {
        val found = linkedMapOf<String, MediaSource>()

        fun add(
            raw: String?,
            label: String,
            sourceContext: String = ""
        ) {
            if (raw.isNullOrBlank()) return

            val url =
                normalizeMediaUrl(
                    raw,
                    pageUrl
                ) ?: return

            if (
                url.contains("youtube.com", true) ||
                url.contains("youtu.be", true)
            ) {
                return
            }

            val nearby =
                if (sourceContext.isBlank()) {
                    ""
                } else {
                    sourceContext
                }

            val declaredSize =
                Regex(
                    """(?i)"(?:size|fileSize|contentLength)"\s*:\s*(\d{4,})"""
                )
                    .find(nearby)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                    ?: -1L

            val declaredDuration =
                Regex(
                    """(?i)"duration(?:Seconds)?"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)"""
                )
                    .find(nearby)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?.toLong()
                    ?: -1L

            found.putIfAbsent(
                url,
                MediaSource(
                    url = url,
                    quality = qualityFromText(url),
                    label = label,
                    referer = pageUrl,
                    declaredSize = declaredSize,
                    declaredDurationSeconds = declaredDuration
                )
            )
        }

        document.select("video[src], video source[src], source[src]").forEach { element ->
            add(firstNonBlank(element.attr("src"), element.attr("data-src")), "MovieBox Direct", element.parent()?.parent()?.outerHtml().orEmpty())
        }

        document.select("[data-src], [data-file], [data-video], [data-url], [data-source], [data-stream], [data-hls]").forEach { element ->
            add(
                firstNonBlank(
                    element.attr("data-src"),
                    element.attr("data-file"),
                    element.attr("data-video"),
                    element.attr("data-url"),
                    element.attr("data-source"),
                    element.attr("data-stream"),
                    element.attr("data-hls")
                ),
                "MovieBox Media"
            )
        }

        val decoded = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        Regex(
            """https?://[^"'<>\s]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(decoded).forEach { match ->
            val context =
                decoded.substring(
                    max(0, match.range.first - 900),
                    min(
                        decoded.length,
                        match.range.last + 900
                    )
                )

            add(
                match.value,
                "MovieBox Direct",
                context
            )
        }

        // Serialized SSR state can carry the current source URL.
        Regex(
            """(?i)"(?:sourceUrl|sniffUrl|playUrl|streamUrl|videoUrl|contentUrl|mediaUrl|url)"\s*:\s*"((?:https?:)?//[^"\s]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"\s]*)?)"""
        ).findAll(decoded).forEach { match ->
            val context =
                decoded.substring(
                    max(0, match.range.first - 900),
                    min(
                        decoded.length,
                        match.range.last + 900
                    )
                )

            add(
                match.groupValues[1],
                "MovieBox State",
                context
            )
        }

        /*
         * Nuxt/devalue payloads can place the actual video URL away from
         * the field name. Scan every absolute HTTP(S) URL that clearly
         * points to a supported media file, including signed CDN URLs.
         */
        Regex(
            """https?://[^"'<>\s]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"'<>\s]*)?"""
        ).findAll(decoded).forEach { match ->
            val context =
                decoded.substring(
                    max(0, match.range.first - 1400),
                    min(
                        decoded.length,
                        match.range.last + 1400
                    )
                )

            add(
                match.value,
                "MovieBox CDN",
                context
            )
        }

        /*
         * Devalue/Nuxt serialization often places:
         * videoId, definition, url, duration, width, height, size...
         * together in one object. The absolute-URL scan above finds the media
         * URL; this supplemental pass enriches that exact URL with duration
         * and size when the surrounding serialized object exposes them.
         */
        Regex(
            """(?is)\{"videoId":[^}]{0,1200}?"url":"(https?://[^"]+?\.(?:mp4|m3u8|m4v|webm|mov|mkv|ts)(?:\?[^"]*)?)"[^}]{0,1200}?("duration":\s*\d+(?:\.\d+)?)?[^}]{0,300}?"size":\s*(\d+)"""
        ).findAll(decoded).forEach { match ->
            val url =
                normalizeMediaUrl(
                    match.groupValues[1],
                    pageUrl
                ) ?: return@forEach

            val duration =
                Regex(
                    """(?i)"duration"\s*:\s*([0-9]+(?:\.[0-9]+)?)"""
                )
                    .find(match.value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toDoubleOrNull()
                    ?.toLong()
                    ?: -1L

            val size =
                match.groupValues
                    .getOrNull(3)
                    ?.toLongOrNull()
                    ?: -1L

            found[url] =
                MediaSource(
                    url = url,
                    quality = qualityFromText(url),
                    label = "MovieBox Resource",
                    referer = pageUrl,
                    declaredSize = size,
                    declaredDurationSeconds = duration
                )
        }

        /*
         * Current MovieBox SSR uses devalue-style references. A typical
         * videoAddress resource is serialized as:
         *
         * {"videoId":...,"definition":...,"url":...,"duration":...,
         *  "width":...,"height":...,"size":...},"video-id",
         *  "https://...mp4",94,640,360,2368261
         *
         * The media URL is immediately followed by its duration/size values.
         * Capture those values so we can select the full-size resource instead
         * of accidentally returning a short preview.
         */
        Regex(
            """(?is)\{"videoId":\d+,"definition":\d+,"url":\d+,"duration":\d+,"width":\d+,"height":\d+,"size":\d+[^}]*\},"[^"]*","(https?://[^"]+?\.(?:m3u8|mp4|m4v|webm|mov|mkv|ts)(?:\?[^"]*)?)",(\d+),(\d+),(\d+),(\d+)"""
        ).findAll(decoded).forEach { match ->
            val url =
                normalizeMediaUrl(
                    match.groupValues[1],
                    pageUrl
                ) ?: return@forEach

            val duration =
                match.groupValues[2]
                    .toLongOrNull()
                    ?: -1L

            val size =
                match.groupValues[5]
                    .toLongOrNull()
                    ?: -1L

            val existing =
                found[url]

            found[url] =
                (existing ?: MediaSource(
                    url = url,
                    quality = qualityFromText(url),
                    label = "MovieBox Resource",
                    referer = pageUrl
                )).copy(
                    declaredSize =
                        max(
                            existing?.declaredSize ?: -1L,
                            size
                        ),
                    declaredDurationSeconds =
                        max(
                            existing?.declaredDurationSeconds ?: -1L,
                            duration
                        )
                )
        }

        return found.values.toList()
    }

    private fun extractSubtitles(
        document: Document
    ): List<Pair<String, String>> {

        val found =
            linkedMapOf<
                String,
                Pair<String, String>
            >()

        document.select(
            "track[src]"
        ).forEach { track ->

            val url =
                firstUsefulUrl(
                    track.attr("src")
                ) ?: return@forEach

            val label =
                firstNonBlank(
                    track.attr("label"),
                    track.attr("srclang"),
                    "Subtitle"
                ) ?: "Subtitle"

            found.putIfAbsent(
                url,
                label to url
            )
        }

        return found.values.toList()
    }

    private fun parseListing(
        document: Document,
        forcedType: TvType
    ): List<SiteItem> {

        /*
         * Current MovieBox is a Nuxt SSR application. Its visible cards
         * are emitted as escaped `video-item` HTML inside __NUXT_DATA__.
         * The old .movie-card/.flw-item selectors therefore return an
         * empty list on the current website.
         *
         * Parse the current SSR structure first. Keep the legacy CSS
         * parser as a fallback for older layouts/mirrors.
         */
        val nuxtItems =
            parseNuxtListing(
                document,
                forcedType
            )

        if (nuxtItems.isNotEmpty()) {
            return nuxtItems
        }

        val selectors =
            listOf(
                ".movie-card",
                ".flw-item",
                ".film_list-wrap .flw-item",
                "[class*='movie-card']"
            )

        return selectors
            .asSequence()
            .flatMap {
                document.select(it).asSequence()
            }
            .distinctBy {
                it.outerHtml()
            }
            .mapNotNull {
                parseCard(
                    it,
                    forcedType
                )
            }
            .toList()
    }

    private fun parseNuxtListing(
        document: Document,
        forcedType: TvType?
    ): List<SiteItem> {

        val source = document.html()
        if (source.isBlank()) return emptyList()

        val payloadMarkers =
            listOf(
                "id=\"__NUXT_DATA__\"",
                "id=\"\\_\\_NUXT_DATA\\_\\_\""
            )

        val payloadStart =
            payloadMarkers
                .map { marker ->
                    source.indexOf(
                        marker,
                        ignoreCase = true
                    )
                }
                .filter { it >= 0 }
                .minOrNull()
                ?: -1

        if (payloadStart < 0) return emptyList()

        val titleRegex =
            Regex(
                """<div class="video-item[^>]*>.*?<span class="line-1">(.*?)</span>""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )

        val domTitles =
            document
                .select(
                    "div.video-item span.line-1"
                )
                .mapNotNull { element ->
                    cleanTitle(
                        element.text()
                    ).takeIf { it.isNotBlank() }
                }

        val titles =
            if (domTitles.isNotEmpty()) {
                domTitles
            } else {
                titleRegex
                    .findAll(source)
                    .mapNotNull { match ->
                        cleanTitle(
                            unescapeSsrText(
                                match.groupValues[1]
                            )
                        ).takeIf { it.isNotBlank() }
                    }
                    .toList()
            }

        if (titles.isEmpty()) return emptyList()

        return titles.mapNotNull { title ->
            resolveNuxtSiteItem(
                source,
                payloadStart,
                title,
                forcedType
            )
        }
    }

    private fun resolveNuxtSiteItem(
        source: String,
        payloadStart: Int,
        title: String,
        forcedType: TvType?
    ): SiteItem? {

        val titleToken = "\"${title.replace("\"", "\\\"")}\""
        var searchFrom = payloadStart
        var best: SiteItem? = null
        var bestScore = -1.0

        while (true) {
            val titleIndex = source.indexOf(titleToken, searchFrom)
            if (titleIndex < 0) break

            val windowStart = max(payloadStart, titleIndex - 8000)
            val windowEnd = min(source.length, titleIndex + titleToken.length + 8000)
            val window = source.substring(windowStart, windowEnd)

            val routePatterns =
                when (forcedType) {
                    TvType.TvSeries -> listOf(
                        Regex("""(?i)/tv-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?""")
                    )

                    TvType.Anime -> listOf(
                        Regex("""(?i)/animated-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?""")
                    )

                    TvType.Movie -> listOf(
                        Regex("""(?i)/film/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""),
                        Regex("""(?i)/movies/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?""")
                    )

                    null -> listOf(
                        Regex("""(?i)/(?:film|movies|tv-series|animated-series)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?""")
                    )

                    else -> emptyList()
                }

            val routeMatch =
                routePatterns
                    .asSequence()
                    .mapNotNull { pattern ->
                        pattern.find(window)
                    }
                    .firstOrNull()

            val backendMatch =
                if (forcedType == null) {
                    Regex(
                        """(?i)https?://wefeed-h5-bff\.wefeed-prod/detail/([A-Za-z0-9._~%\-]+)"""
                    ).find(window)
                } else {
                    null
                }

            val candidatePath =
                routeMatch?.value
                    ?: backendMatch?.groupValues
                        ?.getOrNull(1)
                        ?.let { slug ->
                            when (forcedType) {
                                TvType.TvSeries ->
                                    "/tv-series/$slug"

                                TvType.Anime ->
                                    "/animated-series/$slug"

                                else ->
                                    "/film/$slug"
                            }
                        }
                    ?: findBestDetailPath(
                        window,
                        title
                    )?.third?.let {
                        when (forcedType) {
                            TvType.TvSeries ->
                                "/tv-series/$it"
                            TvType.Anime ->
                                "/animated-series/$it"
                            else ->
                                "/film/$it"
                        }
                    }

            if (candidatePath != null) {
                val detailType = forcedType ?: typeFromPath(candidatePath)

                if (
                    forcedType != null &&
                    detailType != forcedType
                ) {
                    searchFrom =
                        titleIndex + titleToken.length
                    continue
                }

                val slugTitle = candidatePath
                    .substringAfterLast('/')
                    .replace('-', ' ')
                    .replace('_', ' ')
                val score = max(
                    searchScore(title, slugTitle),
                    searchScore(title, cleanTitle(candidatePath))
                )
                val poster = findNearestPoster(source, titleIndex, windowEnd)

                if (score >= 0.20 && (best == null || score > bestScore)) {
                    bestScore = score
                    best = SiteItem(
                        title = title,
                        url = canonicalUrl(candidatePath),
                        poster = poster,
                        type = detailType,
                        languageRank = languageRank(title)
                    )
                }
            }

            searchFrom = titleIndex + titleToken.length
        }

        return best
    }

    private fun findBestDetailPath(
        window: String,
        title: String
    ): Triple<Int, Double, String>? {

        val regex =
            Regex(
                "\"([A-Za-z0-9][A-Za-z0-9._-]{3,}-[A-Za-z0-9]{8,})\"" 
            )

        var best: Triple<Int, Double, String>? = null

        for (match in regex.findAll(window)) {
            val candidate = match.groupValues[1]

            if (candidate.startsWith("http", true)) continue

            val slugBase =
                candidate
                    .replace(
                        Regex("-[A-Za-z0-9]{8,}$"),
                        ""
                    )
                    .replace('-', ' ')
                    .replace('_', ' ')

            val score = searchScore(title, slugBase)
            if (score < 0.70) continue

            val result =
                Triple(
                    match.range.first,
                    score,
                    candidate
                )

            if (best == null || score > best!!.second) {
                best = result
            }
        }

        return best
    }

    private fun findNearestPoster(
        source: String,
        start: Int,
        end: Int
    ): String? {
        val windowStart = max(0, end)
        val windowEnd = min(source.length, end + 9000)
        if (windowEnd <= windowStart) return null

        val window = source.substring(windowStart, windowEnd)

        data class Candidate(
            val url: String,
            val width: Int,
            val height: Int,
            val score: Int,
            val distance: Int
        )

        val candidates = Regex(
            """(?is)"(?:jpg|jpeg|png|webp)",(\d{3,5}),\d{2,10},"(https?://[^"]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"]*)?)",(\d{3,5})"""
        ).findAll(window).mapNotNull { match ->
            val height = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val width = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val url = match.groupValues[2]
                .replace("\\/", "/")
                .replace("\\:", ":")
                .replace("\\\\", "\\")
            if (url.contains("logo", true) || url.contains("icon", true) || url.contains("avatar", true)) return@mapNotNull null
            val score = when {
                url.contains("/media/vone/", true) -> 30
                url.contains("/image/", true) -> 20
                else -> 10
            } + if (height >= width) 25 else 0
            Candidate(url, width, height, score, match.range.first)
        }.toList()

        if (candidates.isNotEmpty()) {
            return candidates.maxWithOrNull(
                compareBy<Candidate> { it.score }
                    .thenByDescending { it.height * it.width }
                    .thenBy { it.distance }
            )?.url
        }

        return Regex(
            """https?:(?:/|\\/){2}[^"'\s]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'\s]*)?""",
            RegexOption.IGNORE_CASE
        ).find(window)?.value?.replace("\\/", "/")
    }

    private fun unescapeSsrText(
        value: String
    ): String {
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\/", "/")
    }

    private fun parseSearchCard(
        card: Element
    ): SiteItem? {
        return parseCard(
            card,
            null
        )
    }

    private fun parseCard(
        card: Element,
        forcedType: TvType?
    ): SiteItem? {

        val href =
            firstNonBlank(
                card.selectFirst(
                    "a[href*='/film/'], " +
                        "a[href*='/tv-series/'], " +
                        "a[href*='/animated-series/'], " +
                        "a[href]"
                )?.attr("href")
            ) ?: return null

        val url =
            absoluteUrl(href)

        val path =
            pathFromUrl(url)

        if (
            !isLikelyContentPath(path)
        ) {
            return null
        }

        val title =
            cleanTitle(
                firstNonBlank(
                    card.selectFirst(
                        ".film-name a, " +
                            ".film-name, " +
                            ".title a, " +
                            ".title, " +
                            "h2, h3, " +
                            "a[title]"
                    )?.text(),

                    card.selectFirst(
                        "img"
                    )?.attr("alt"),

                    card.selectFirst(
                        "img"
                    )?.attr("title")
                ).orEmpty()
            )

        if (title.isBlank()) {
            return null
        }

        return SiteItem(
            title = title,
            url = canonicalUrl(path),
            poster =
                extractPoster(card),
            type =
                forcedType
                    ?: typeFromPath(path),
            languageRank =
                languageRank(title)
        )
    }

    private fun extractPoster(
        card: Element
    ): String? {

        val image =
            card.selectFirst(
                "img"
            ) ?: return null

        return firstUsefulUrl(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("data-poster"),
            image.attr("srcset")
                .substringBefore(',')
                .substringBefore(' ')
                .trim(),
            image.attr("src")
        )
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {

        data class EpisodeInfo(
            val url: String,
            val title: String,
            val season: Int?,
            val episode: Int?
        )

        val found = linkedMapOf<String, EpisodeInfo>()

        fun addEpisode(rawUrl: String?, rawLabel: String?, rawSeason: String? = null, rawEpisode: String? = null) {
            if (rawUrl.isNullOrBlank()) return

            val url = absoluteUrl(rawUrl.trim())
            val label = cleanTitle(rawLabel.orEmpty())
            val context = "$label $url"

            val episodeNumber = (
                rawEpisode?.toIntOrNull()
                    ?: Regex("(?i)(?:season\\s*)?S(\\d{1,3})?[^A-Z0-9]{0,3}E(\\d{1,4})").find(context)?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: Regex("(?i)(?:episode|ep|e)[\\s._-]*(\\d{1,4})\\b").find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
            )

            val seasonNumber = (
                rawSeason?.toIntOrNull()
                    ?: Regex("(?i)\\bS(\\d{1,3})\\b").find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("(?i)(?:season|s)[\\s._-]*(\\d{1,3})\\b").find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
            )

            // IMPORTANT: do not turn generic /play/<slug> trailer URLs into episodes.
            if (episodeNumber == null && !isExplicitEpisodeUrl(url)) return

            val safeEpisode = episodeNumber ?: (found.size + 1)
            val safeSeason = seasonNumber ?: 1
            val title = label.ifBlank {
                if (episodeNumber != null) "Episode $episodeNumber" else "Episode $safeEpisode"
            }

            found.putIfAbsent(
                "$url|$safeSeason|$safeEpisode",
                EpisodeInfo(url, title, safeSeason, safeEpisode)
            )
        }

        // Real episode anchors/buttons, when the page exposes them server-side.
        document.select(
            "a[href], button[data-url], [data-href], [data-play-url], " +
                "[data-episode], [data-episode-number], [data-episode-id]"
        ).forEach { element ->
            val href = firstNonBlank(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-href"),
                element.attr("data-play-url"),
                element.attr("data-src")
            ) ?: return@forEach

            if (!href.contains("/play/", true) && !href.contains("episode", true)) return@forEach

            addEpisode(
                rawUrl = href,
                rawLabel = firstNonBlank(
                    element.text(),
                    element.attr("title"),
                    element.attr("aria-label"),
                    element.attr("data-episode"),
                    element.attr("data-episode-number")
                ),
                rawSeason = element.attr("data-season"),
                rawEpisode = firstNonBlank(
                    element.attr("data-episode"),
                    element.attr("data-episode-number"),
                    element.attr("data-ep")
                )
            )
        }

        // Serialized Nuxt state can contain explicit episode routes.
        // We only accept routes whose nearby serialized context identifies an episode.
        val html = document.html()
        val decoded = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        Regex("(?i)(/play/[A-Za-z0-9._-]+(?:\\?[^\"'<>\\s]*)?)")
            .findAll(decoded)
            .forEach { match ->
                val before = decoded.substring(max(0, match.range.first - 900), match.range.first)
                val after = decoded.substring(match.range.last + 1, min(decoded.length, match.range.last + 900))
                val context = before + after

                val episodeNumber = Regex("(?i)(?:episode|ep|e)[\\s._:-]*(\\d{1,4})\\b")
                    .find(context)?.groupValues?.getOrNull(1)
                val seasonNumber = Regex("(?i)(?:season|s)[\\s._:-]*(\\d{1,3})\\b")
                    .find(context)?.groupValues?.getOrNull(1)

                if (episodeNumber != null || isExplicitEpisodeUrl(match.value)) {
                    addEpisode(
                        match.value,
                        "Episode ${episodeNumber ?: found.size + 1}",
                        seasonNumber,
                        episodeNumber
                    )
                }
            }

        return found.values
            .sortedWith(compareBy<EpisodeInfo> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })
            .map { info ->
                newEpisode(
                    data = info.url + "||" + (info.episode?.toString().orEmpty())
                ) {
                    name = info.title
                    season = info.season
                    episode = info.episode
                }
            }
    }

    private fun isExplicitEpisodeUrl(url: String): Boolean {
        return Regex("(?i)(/episode|/ep[-_./]|episode[-_./]|(?:[?&](?:episode|ep|episodeId)=))").containsMatchIn(url) ||
            Regex("(?i)S\\d{1,3}E\\d{1,4}").containsMatchIn(url)
    }

    /*
     * ------------------------------------------------------------
     * SEARCH RANKING
     * ------------------------------------------------------------
     *
     * Hindi > Bangla > Other labelled languages > Main/unlabelled.
     *
     * No duplicate filtering is applied.
     */
    private fun rankSearchResults(
        query: String,
        items: List<SiteItem>
    ): List<SearchResponse> {
        return items
            .map { item -> Triple(item, searchScore(query, item.title), languageRank(item.title)) }
            .filter { it.second >= 0.42 }
            .sortedWith(
                compareBy<Triple<SiteItem, Double, Int>> {
                    if (isCoreTitleMatch(query, it.first.title)) 0 else 1
                }.thenBy { it.third }
                    .thenByDescending { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
            .distinctBy { contentKey(it.first.url) }
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private fun isCoreTitleMatch(query: String, title: String): Boolean {
        val base = normalizeSearch(baseTitleForSearch(title))
        val q = normalizeSearch(query)
        if (base.isBlank() || q.isBlank()) return false
        if (base == q || base.contains(q)) return true
        val compactBase = base.replace(" ", "")
        val compactQuery = q.replace(" ", "")
        if (compactQuery.isNotBlank() && compactBase.contains(compactQuery)) return true
        return stringSimilarity(base, q) >= 0.72
    }

    private fun baseTitleForSearch(title: String): String {
        return title.trim().replace(
            Regex(
                """(?i)\s*(?:\[(?:hindi|bengali|bangla|english|eng|tamil|telugu|malayalam|kannada|spanish|french|arabic|japanese|korean|chinese)\]|\((?:hindi|bengali|bangla|english|eng|tamil|telugu|malayalam|kannada|spanish|french|arabic|japanese|korean|chinese)\)|[-–]\s*(?:hindi|bengali|bangla|english|eng|tamil|telugu|malayalam|kannada|spanish|french|arabic|japanese|korean|chinese))\s*$"""
            ),
            ""
        ).trim()
    }

    private fun languageRank(text: String): Int {
        val normalized = normalizeSearch(text)
        if (Regex("""\b(hindi|hindi dubbed|hindi audio|dubbed in hindi)\b""").containsMatchIn(normalized) || text.contains("हिन्दी") || text.contains("हिंदी")) return 1
        if (Regex("""\b(bangla|bengali|bangla dubbed|bangla audio)\b""").containsMatchIn(normalized) || text.contains("বাংলা") || text.contains("বাঙ্গালী")) return 2
        if (Regex("""(?i)(?:\[|\(|-|\s)(english|eng|tamil|telugu|malayalam|kannada|spanish|french|arabic|japanese|korean|chinese)\b""").containsMatchIn(normalized)) return 3
        return 4
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        val q =
            normalizeSearch(query)

        val t =
            normalizeSearch(title)

        if (
            q.isBlank() ||
            t.isBlank()
        ) {
            return 0.0
        }

        if (q == t) {
            return 1.0
        }

        var score = 0.0

        if (t.contains(q)) {
            score =
                max(score, 0.98)
        }

        val qCompact =
            q.replace(" ", "")
        val tCompact =
            t.replace(" ", "")

        if (
            qCompact.isNotBlank() &&
            tCompact.contains(qCompact)
        ) {

            val ratio =
                qCompact.length.toDouble() /
                    tCompact.length
                        .coerceAtLeast(1)

            score =
                max(
                    score,
                    0.88 +
                        min(
                            0.10,
                            ratio * 0.10
                        )
                )
        }

        val qTokens =
            q.split(" ")
                .filter {
                    it.length >= 2
                }

        val tTokens =
            t.split(" ")
                .filter {
                    it.length >= 2
                }

        if (
            qTokens.isEmpty() ||
            tTokens.isEmpty()
        ) {
            return score.coerceIn(
                0.0,
                1.0
            )
        }

        val tokenScores =
            qTokens.map { qToken ->

                tTokens.maxOfOrNull { tToken ->

                    when {
                        qToken == tToken ->
                            1.0

                        tToken.startsWith(
                            qToken
                        ) ||
                            qToken.startsWith(
                                tToken
                            ) ->
                            0.93

                        tToken.contains(
                            qToken
                        ) ||
                            qToken.contains(
                                tToken
                            ) ->
                            0.88

                        else ->
                            stringSimilarity(
                                qToken,
                                tToken
                            )
                    }
                } ?: 0.0
            }

        score =
            max(
                score,
                tokenScores.average() *
                    0.94
            )

        val covered =
            tokenScores.count {
                it >= 0.60
            }

        val coverage =
            covered.toDouble() /
                qTokens.size.toDouble()

        score =
            max(
                score,
                0.50 +
                    coverage * 0.45
            )

        return score.coerceIn(
            0.0,
            1.0
        )
    }

    private fun stringSimilarity(
        a: String,
        b: String
    ): Double {

        if (a == b) return 1.0

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0.0
        }

        val distance =
            levenshtein(a, b)

        val longest =
            max(
                a.length,
                b.length
            )

        return 1.0 -
            distance.toDouble() /
                longest.toDouble()
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous =
            IntArray(
                b.length + 1
            ) { it }

        var current =
            IntArray(
                b.length + 1
            )

        for (i in a.indices) {

            current[0] =
                i + 1

            for (j in b.indices) {

                val cost =
                    if (
                        a[i] == b[j]
                    ) {
                        0
                    } else {
                        1
                    }

                current[j + 1] =
                    minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + cost
                    )
            }

            val swap =
                previous

            previous =
                current

            current =
                swap
        }

        return previous[b.length]
    }

    /*
     * ------------------------------------------------------------
     * NETWORK
     * ------------------------------------------------------------
     */
    private suspend fun fetchMirrorPage(
        path: String
    ): MirrorPage? {

        val normalized =
            normalizePath(path)

        for (domain in domains) {

            val page =
                getDocument(
                    domain,
                    normalized
                )

            if (page != null) {
                return page
            }
        }

        return null
    }

    private suspend fun getDocument(
        domain: String,
        path: String
    ): MirrorPage? {

        val normalized =
            normalizePath(path)

        val requestPaths =
            linkedSetOf<String>().apply {
                add(normalized)

                /*
                 * Listing pages should load quickly and may be cached safely.
                 * Fresh cache-busting is reserved for player/detail flows, where
                 * session/media state can change.
                 */
                if (
                    normalized.contains("/play/", true) ||
                    normalized.startsWith("/film/", true) ||
                    normalized.startsWith("/movies/", true) ||
                    normalized.startsWith("/tv-series/", true) ||
                    normalized.startsWith("/animated-series/", true)
                ) {
                    add(addCacheBuster(normalized))
                }

                if (
                    normalized != "/" &&
                    !normalized.endsWith("/")
                ) {
                    add("$normalized/")
                    add(
                        addCacheBuster(
                            "$normalized/"
                        )
                    )
                }
            }

        for (requestPath in requestPaths) {

            val requestUrl =
                domain.trimEnd('/') +
                    requestPath

            val referer =
                if (
                    normalized == "/"
                ) {
                    domain.trimEnd('/') + "/"
                } else {
                    domain.trimEnd('/') +
                        normalized
                }

            val response =
                runCatching {
                    app.get(
                        requestUrl,
                        headers = browserHeaders(
                            referer
                        ) + mapOf(
                            "Accept-Language" to
                                "en-US,en;q=0.9,hi;q=0.8,bn;q=0.7",
                            "Sec-Fetch-Dest" to "document",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "same-origin",
                            "Upgrade-Insecure-Requests" to "1",
                            "Cache-Control" to "no-cache",
                            "Pragma" to "no-cache"
                        )
                    )
                }.getOrNull()
                    ?: continue

            if (
                response.code !in 200..399
            ) {
                continue
            }

            captureWebsiteCookies(response.headers)
            captureRuntimeTokens(response.text)

            return MirrorPage(
                domain = domain,
                path = normalized,
                document = response.document
            )
        }

        return null
    }

    /*
     * ------------------------------------------------------------
     * HELPERS
     * ------------------------------------------------------------
     */
    private fun rankingPageRoutes(
        basePath: String,
        page: Int
    ): List<String> {
        if (page <= 1) return listOf(basePath).distinct()

        val separator = if (basePath.contains("?")) "&" else "?"
        return listOf(
            "$basePath${separator}page=$page",
            "$basePath${separator}p=$page"
        ).distinct()
    }

    private fun pageRoutes(
        basePath: String,
        page: Int
    ): List<String> {

        if (page <= 1) {
            return listOf(
                basePath,
                "$basePath/"
            ).distinct()
        }

        return listOf(
            "$basePath/page/$page",
            "$basePath/page/$page/",
            "$basePath?page=$page",
            "$basePath/?page=$page"
        ).distinct()
    }

    private fun addPageParameter(
        route: String,
        page: Int
    ): String {

        if (page <= 1) {
            return route
        }

        return if (
            route.contains("?")
        ) {
            "$route&page=$page"
        } else {
            "$route?page=$page"
        }
    }

    private fun detectHasNext(
        document: Document,
        currentPage: Int
    ): Boolean {

        if (
            document.selectFirst(
                "a[rel=next], " +
                    "a.next, " +
                    ".pagination a.next, " +
                    ".pagination a[aria-label*='Next']"
            ) != null
        ) {
            return true
        }

        return document
            .select(
                ".pagination a, " +
                    ".page-numbers a, " +
                    ".pagination li"
            )
            .any {
                it.text()
                    .trim()
                    .equals(
                        (
                            currentPage + 1
                        ).toString(),
                        true
                    )
            }
    }

    private fun buildSearchVariants(
        query: String
    ): LinkedHashSet<String> {

        val normalized =
            normalizeSearch(query)

        val compact =
            normalized.replace(
                " ",
                ""
            )

        val reduced =
            normalized
                .split(" ")
                .filterNot {
                    it in setOf(
                        "movie",
                        "movies",
                        "film",
                        "films",
                        "series",
                        "tv",
                        "show",
                        "season",
                        "episode",
                        "ep",
                        "hd",
                        "dubbed",
                        "dual",
                        "audio"
                    )
                }
                .joinToString(" ")

        return linkedSetOf<String>().apply {
            add(query)

            if (
                normalized.isNotBlank()
            ) {
                add(normalized)
            }

            if (
                reduced.isNotBlank()
            ) {
                add(reduced)
            }

            if (
                compact.isNotBlank()
            ) {
                add(compact)
            }
        }
    }

    private fun SiteItem.toSearchResponse():
        SearchResponse {

        return when (type) {

            TvType.TvSeries ->
                newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl =
                        poster
                }

            TvType.Anime ->
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Anime
                ) {
                    posterUrl =
                        poster
                }

            else ->
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    posterUrl =
                        poster
                }
        }
    }

    private fun typeFromUrl(
        url: String,
        path: String
    ): TvType {
        val lowerUrl = url.lowercase(Locale.ROOT)
        if (path.startsWith("/tv-series/", true)) return TvType.TvSeries
        if (path.startsWith("/animated-series/", true)) return TvType.Anime
        if (path.startsWith("/film/", true) || path.startsWith("/movies/", true)) return TvType.Movie

        if (lowerUrl.contains("page_from=type_filter_tv") ||
            lowerUrl.contains("type_filter_tv") ||
            Regex("(?i)[?&]tab=tv(?:[&#]|$)").containsMatchIn(lowerUrl)) {
            return TvType.TvSeries
        }

        if (lowerUrl.contains("page_from=type_filter_animated") ||
            lowerUrl.contains("type_filter_animation") ||
            Regex("(?i)[?&]tab=animated(?:[&#]|$)").containsMatchIn(lowerUrl)) {
            return TvType.Anime
        }

        return typeFromPath(path)
    }

    private fun typeFromPath(
        path: String
    ): TvType {

        val value =
            path.lowercase(
                Locale.ROOT
            )

        return when {

            value.startsWith(
                "/animated-series/"
            ) ->
                TvType.Anime

            value.startsWith(
                "/tv-series/"
            ) ->
                TvType.TvSeries

            else ->
                TvType.Movie
        }
    }

    private fun isLikelyContentPath(
        path: String
    ): Boolean {

        val value =
            path.lowercase(
                Locale.ROOT
            )

        return value.startsWith(
            "/film/"
        ) ||
            value.startsWith(
                "/movies/"
            ) ||
            value.startsWith(
                "/tv-series/"
            ) ||
            value.startsWith(
                "/animated-series/"
            )
    }

    private fun contentSlug(
        contentPath: String
    ): String? {

        val value = contentPath
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')

        if (value.isBlank()) return null

        val path =
            runCatching {
                URI(value).rawPath.orEmpty()
            }.getOrElse {
                value
            }

        val slug =
            path.substringAfterLast('/')
                .trim()

        return slug
            .takeIf {
                it.isNotBlank() &&
                    !it.equals("film", true) &&
                    !it.equals("movies", true) &&
                    !it.equals("play", true) &&
                    !it.equals("detail", true)
            }
    }

    private suspend fun fetchBackendDetailDocument(
        slug: String
    ): Document? {

        val endpoint =
            "$MOVIE_DETAIL_ENDPOINT/$slug"

        return runCatching {
            val response =
                app.get(
                    endpoint,
                    headers = browserHeaders(
                        mainUrl + "/"
                    ) + mapOf(
                        "Accept" to (
                            "text/html,application/json," +
                                "text/plain,*/*;q=0.8"
                            ),
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache"
                    )
                )

            if (response.code in 200..399) {
                response.document
            } else {
                null
            }
        }.getOrNull()
    }

    private fun derivedPlayPath(
        contentPath: String
    ): String? {
        val normalized = normalizePath(contentPath)
        if (!normalized.startsWith("/film/")) return null

        val slug = normalized
            .removePrefix("/film/")
            .substringBefore('?')
            .substringBefore('#')
            .trim()

        if (slug.isBlank()) return null

        return "/play/$slug"
    }

    private fun titleFromContentPath(
        contentPath: String
    ): String {
        val slug = normalizePath(contentPath)
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')

        return slug
            .replace(
                Regex("-[A-Za-z0-9]{8,}$"),
                ""
            )
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
    }

    private fun pathFromUrl(
        raw: String
    ): String {

        return try {

            if (
                raw.startsWith("/")
            ) {
                normalizePath(raw)
            } else {

                val uri =
                    URI(raw)

                normalizePath(
                    uri.rawPath
                        .orEmpty()
                        .ifBlank {
                            "/"
                        }
                )
            }

        } catch (_: Throwable) {

            raw.substringAfter(
                mainUrl,
                raw
            ).ifBlank {
                "/"
            }
        }
    }

    private fun absoluteUrl(
        raw: String
    ): String {

        val value =
            raw.trim()

        return when {

            value.startsWith(
                "https://",
                true
            ) ||
                value.startsWith(
                    "http://",
                    true
                ) -> value

            value.startsWith("//") ->
                "https:$value"

            value.startsWith("/") ->
                mainUrl.trimEnd('/') +
                    value

            else ->
                "$mainUrl/$value"
        }
    }

    private fun canonicalUrl(
        path: String
    ): String {

        return mainUrl.trimEnd('/') +
            normalizePath(path)
    }

    private fun normalizePath(
        path: String
    ): String {

        if (path.isBlank()) {
            return "/"
        }

        val value =
            if (
                path.startsWith("/")
            ) {
                path
            } else {
                "/$path"
            }

        return value.replace(
            Regex(
                "/{2,}"
            ),
            "/"
        ).let {
            if (
                it.length > 1 &&
                it.endsWith("/")
            ) {
                it.dropLast(1)
            } else {
                it
            }
        }
    }

    private fun contentKey(
        url: String
    ): String {

        return pathFromUrl(url)
            .lowercase(
                Locale.ROOT
            )
            .removeSuffix("/")
    }

    private fun normalizeSearch(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                Regex(
                    """[^\p{L}\p{N}]+"""
                ),
                " "
            )
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
    }

    private fun cleanTitle(raw: String): String = cleanDisplayTitle(raw)

    private fun cleanDisplayTitle(raw: String): String {
        var value = raw.replace(Regex("\\s+"), " ").trim()
        if (value.isBlank()) return value

        value = value
            .replace(Regex("(?i)^\\s*(?:watch|stream)\\s+"), "")
            .replace(Regex("(?i)\\s*\\|\\s*moviebox(?:online)?(?:\\s*\\|.*)?$"), "")
            .replace(Regex("(?i)\\s*(?:\\||-)\\s*IMDb\\s*[:\\-]?\\s*\\d+(?:\\.\\d+)?(?:\\s*\\|.*)?$"), "")
            .replace(Regex("(?i)\\s+online(?:\\s*\\|.*)?$"), "")
            .replace(Regex("(?i)\\s*\\|\\s*IMDb\\s*[:\\-]?\\s*\\d+(?:\\.\\d+)?$"), "")
            .trim()

        if (raw.contains("MovieBox", true) || raw.contains("IMDb", true) || Regex("(?i)\\bonline\\b").containsMatchIn(raw)) {
            value = value.replace(Regex("\\s*\\((?:19|20)\\d{2}\\)\\s*$"), "")
        }

        value = value.replace(Regex("(?i)\\s+online\\s*$"), "")
        return value.trim(' ', '|', '-')
    }

    private fun extractImdbRating(rawTitle: String, document: Document?): Double? {
        parseImdbRatingFromText(rawTitle)?.let { return it }
        document?.let { doc ->
            parseImdbRatingFromText(doc.html())?.let { return it }
            doc.selectFirst("[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }?.let { return it }
        }
        return null
    }

    private fun formatRating(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    private fun firstNonBlank(
        vararg values: String?
    ): String? {

        return values
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
    }

    private fun firstUsefulUrl(
        vararg values: String?
    ): String? {

        return values
            .firstOrNull {
                !it.isNullOrBlank() &&
                    (
                        it.startsWith(
                            "http://"
                        ) ||
                            it.startsWith(
                                "https://"
                            )
                    )
            }
            ?.trim()
    }

    private fun extractImageUrl(
        element: Element
    ): String? {

        return firstUsefulUrl(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("src"),
            element.attr("srcset")
                .substringBefore(',')
                .substringBefore(' ')
                .trim()
        )
    }

    private fun normalizeMediaUrl(
        raw: String,
        pageUrl: String
    ): String? {

        var value =
            raw.trim()
                .replace("\\/", "/")
                .replace(
                    "\\u0026",
                    "&"
                )
                .replace(
                    "&amp;",
                    "&"
                )
                .trim(
                    '"',
                    '\'',
                    '`'
                )

        if (value.isBlank()) {
            return null
        }

        if (
            value.startsWith("//")
        ) {
            value =
                "https:$value"
        }

        if (
            value.startsWith("/")
        ) {
            val base =
                URI(pageUrl)

            value =
                "${base.scheme}://${base.host}$value"
        }

        if (
            !value.startsWith(
                "http://",
                true
            ) &&
            !value.startsWith(
                "https://",
                true
            )
        ) {
            return null
        }

        return value
    }

    private fun looksLikePlayableMedia(
        url: String
    ): Boolean {

        val value =
            url.lowercase(
                Locale.ROOT
            )

        return value.contains(
            ".m3u8"
        ) ||
            value.contains(
                ".mp4"
            ) ||
            value.contains(
                ".m4v"
            ) ||
            value.contains(
                ".webm"
            ) ||
            value.contains(
                ".mkv"
            ) ||
            value.contains(
                ".mov"
            ) ||
            value.contains(
                ".ts"
            )
    }

    private fun qualityFromText(
        text: String
    ): Int {

        val match =
            Regex(
                """(?i)(2160|1440|1080|720|576|480|360)p"""
            ).find(text)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun extractYear(
        title: String,
        document: Document
    ): Int? {

        Regex(
            """\b(19|20)\d{2}\b"""
        ).find(title)
            ?.value
            ?.toIntOrNull()
            ?.let { return it }

        return Regex(
            """\b(19|20)\d{2}\b"""
        ).find(
            document.text()
        )?.value
            ?.toIntOrNull()
    }

    private fun addCacheBuster(
        path: String
    ): String {
        val separator =
            if (path.contains("?")) {
                "&"
            } else {
                "?"
            }

        return path +
            separator +
            "_cb=" +
            System.currentTimeMillis()
    }

    private fun domainRoot(
        url: String
    ): String {
        return url.removeSuffix("/")
    }
}
