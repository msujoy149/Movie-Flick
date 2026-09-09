package com.movieflick.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
        "$mainUrl/ranking-list?id=997144265920760504&page_from=more_SUBJECTS_MOVIE" to POPULAR,
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

        const val POPULAR = "Most Popular"
        const val MOVIES = "Movies"
        const val TV_SHOW = "Tv Show"
        const val ANIME = "Anime"
        const val POPULAR_MOVIES_PATH = "/ranking-list?id=997144265920760504&page_from=more_SUBJECTS_MOVIE"
        const val POPULAR_SERIES_PATH = "/ranking-list?id=1232643093049001320&page_from=more_SUBJECTS_MOVIE"
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

        if (section == POPULAR) {
            val rankingItems = ArrayList<SiteItem>()

            val rankingPaths = listOf(
                POPULAR_MOVIES_PATH,
                POPULAR_SERIES_PATH
            )

            for ((sourceIndex, basePath) in rankingPaths.withIndex()) {
                val forcedPopularType =
                    if (sourceIndex == 0) TvType.Movie else TvType.TvSeries

                for (route in rankingPageRoutes(basePath, currentPage)) {
                    val result = fetchMirrorPage(route) ?: continue
                    val items = parseNuxtListing(result.document, forcedPopularType)
                    if (items.isNotEmpty()) {
                        rankingItems.addAll(items)
                        break
                    }
                }
            }

            val sortedItems = sortHomeItems(rankingItems)

            val pageItems =
                if (currentPage == 1) {
                    interleaveBySourceType(
                        sortedItems
                    ).take(
                        HOME_INITIAL_LIMIT
                    )
                } else {
                    sortedItems
                        .drop(
                            (currentPage - 1) *
                                HOME_PAGE_LIMIT
                        )
                        .take(
                            HOME_PAGE_LIMIT
                        )
                }

            return newHomePageResponse(
                data = request,
                list = pageItems.map { it.toSearchResponse() },
                hasNext = rankingItems.size > (
                    if (currentPage == 1) {
                        HOME_INITIAL_LIMIT
                    } else {
                        currentPage * HOME_PAGE_LIMIT
                    }
                )
            )
        }

        val basePath = when (section) {
            TV_SHOW -> "/tv-series"
            ANIME -> "/animated-series"
            else -> "/film"
        }

        for (route in pageRoutes(basePath, currentPage)) {
            val result = fetchMirrorPage(route) ?: continue

            val forcedType = when (section) {
                TV_SHOW -> TvType.TvSeries
                ANIME -> TvType.Anime
                else -> TvType.Movie
            }

            val items = parseListing(result.document, forcedType)
            if (items.isEmpty()) continue

            val sortedItems = sortHomeItems(items)
            val limit = if (currentPage == 1) HOME_INITIAL_LIMIT else HOME_PAGE_LIMIT

            return newHomePageResponse(
                data = request,
                list = sortedItems.take(limit).map { it.toSearchResponse() },
                hasNext = detectHasNext(result.document, currentPage) || sortedItems.size > limit
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
                it.type == TvType.Movie || it.type == TvType.Anime
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
                    result += movies[movieIndex++]
                }
            }

            if (seriesIndex < series.size) {
                result += series[seriesIndex++]
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
                result += item
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

        return runCatching {
            rankSearchResults(
                value,
                searchNative(mainUrl, value).take(20)
            ).take(8)
        }.getOrDefault(emptyList())
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val original = query.trim()
        if (original.isBlank()) return emptyList()

        for (domain in domains) {
            val nativeItems =
                searchNative(
                    domain,
                    original
                )

            /*
             * Native search is the fast path.
             *
             * If it already returned a useful amount of content, we
             * avoid the heavier sitemap scan. Otherwise sitemap search
             * augments it so search can cover the whole site.
             */
            val combined =
                ArrayList<SiteItem>(
                    nativeItems.size +
                        SITEMAP_RESULT_LIMIT
                )

            combined.addAll(nativeItems)

            if (
                nativeItems.size <
                    SEARCH_RESULT_LIMIT / 2
            ) {
                combined.addAll(
                    searchSitemap(
                        domain,
                        original
                    )
                )
            }

            val ranked =
                rankSearchResults(
                    original,
                    combined
                )

            if (ranked.isNotEmpty()) {
                return ranked
            }
        }

        return emptyList()
    }

    private suspend fun searchNative(
        domain: String,
        query: String
    ): List<SiteItem> {

        val home =
            getDocument(domain, "/")?.document

        val variants =
            buildSearchVariants(query)

        val routes =
            linkedSetOf<String>()

        discoverSearchForms(
            home = home,
            variants = variants,
            routes = routes
        )

        for (variant in variants) {
            val encoded =
                URLEncoder.encode(
                    variant,
                    "UTF-8"
                )

            routes += "/search/suggest?q=$encoded"
            routes += "/search/suggestions?q=$encoded"
            routes += "/search/autocomplete?q=$encoded"
            routes +=
                "/search?q=$encoded"

            routes +=
                "/search?query=$encoded"

            routes +=
                "/search?keyword=$encoded"

            routes +=
                "/search?s=$encoded"
        }

        val results =
            ArrayList<SiteItem>()

        for (route in routes) {

            for (page in 1..SEARCH_NATIVE_PAGES) {

                val result =
                    getDocument(
                        domain,
                        addPageParameter(
                            route,
                            page
                        )
                    ) ?: break

                val nuxtResults = parseNuxtListing(
                    result.document,
                    null
                )

                if (nuxtResults.isNotEmpty()) {
                    results.addAll(nuxtResults)
                } else {
                    val cards = result.document.select(
                        ".movie-card, " +
                            ".flw-item, " +
                            ".film-poster-ahref, " +
                            ".film_list-wrap .flw-item"
                    )

                    if (cards.isEmpty()) break

                    cards.mapNotNull(::parseSearchCard).forEach { item ->
                        // Do not deduplicate search results.
                        results += item
                    }
                }

                if (
                    !detectHasNext(
                        result.document,
                        page
                    )
                ) {
                    break
                }

                if (
                    results.size >=
                        SEARCH_RESULT_LIMIT * 2
                ) {
                    break
                }
            }

            if (
                results.size >=
                    SEARCH_RESULT_LIMIT * 2
            ) {
                break
            }
        }

        return results
    }

    /*
     * ------------------------------------------------------------
     * SEARCH FALLBACK
     * ------------------------------------------------------------
     *
     * Sitemap is used only after native search produced no useful
     * result on the current mirror.
     */
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
                    allPaths +=
                        normalizePath(
                            path
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

                routes +=
                    "$actionPath$separator$queryName=$encoded"
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * LOAD
     * ------------------------------------------------------------
     */
    override suspend fun load(
        url: String
    ): LoadResponse? {

        val path = pathFromUrl(url)

        /*
         * MovieBox currently exposes the content slug/id in the page SSR
         * payload and also uses a backend detail endpoint. The public web
         * route can be /film/... or /movies/... depending on the page/version.
         *
         * Keep the CloudStream item URL as-is, but use every known public
         * detail route as a read-only fallback so a stale web route does not
         * make the title unloadable.
         */
        val candidatePaths = linkedSetOf<String>().apply {
            add(path)

            if (path.startsWith("/film/")) {
                add("/movies/" + path.removePrefix("/film/"))
                derivedPlayPath(path)?.let(::add)
            } else if (path.startsWith("/movies/")) {
                add("/film/" + path.removePrefix("/movies/"))
                add("/play/" + path.removePrefix("/movies/"))
            } else if (path.startsWith("/play/")) {
                val slug = path.removePrefix("/play/")
                if (slug.isNotBlank()) {
                    add("/film/$slug")
                    add("/movies/$slug")
                }
            }
        }

        var page: MirrorPage? = null

        for (candidate in candidatePaths) {
            page = fetchMirrorPage(candidate)
            if (page != null) break
        }

        var document = page?.document

        /*
         * Public MovieBox SSR data also exposes a backend detail URL of the
         * form:
         *
         *   /detail/<slug-id>
         *
         * Fetch that page as an additional public metadata source when the
         * normal website route is unavailable or incomplete.
         */
        if (document == null) {
            val slug = contentSlug(path)
            if (!slug.isNullOrBlank()) {
                document = fetchBackendDetailDocument(slug)
            }
        }

        val title =
            cleanTitle(
                firstNonBlank(
                    document?.selectFirst(
                        "meta[property=og:title]"
                    )?.attr("content"),

                    document?.selectFirst(
                        "h1"
                    )?.text(),

                    document?.selectFirst(
                        ".film-name"
                    )?.text(),

                    document?.title()
                ).orEmpty()
            ).ifBlank {
                titleFromContentPath(path)
            }

        if (title.isBlank()) {
            return null
        }

        val poster =
            firstUsefulUrl(
                document?.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content"),

                document?.selectFirst(
                    "meta[name=twitter:image]"
                )?.attr("content"),

                document?.selectFirst(
                    ".film-poster img, " +
                        ".movie-card img, " +
                        "img"
                )?.let(
                    ::extractImageUrl
                )
            )

        val plot =
            firstNonBlank(
                document?.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content"),

                document?.selectFirst(
                    ".description, " +
                        ".film-description, " +
                        ".description-content"
                )?.text()
            )

        val year =
            if (document != null) {
                extractYear(title, document)
            } else {
                Regex("""\b(19|20)\d{2}\b""")
                    .find(title)
                    ?.value
                    ?.toIntOrNull()
            }

        val type = typeFromPath(path)

        if (
            type == TvType.Movie ||
            type == TvType.Anime
        ) {
            /*
             * Pass the original item URL into loadLinks. loadLinks will
             * independently resolve the current public player/media route
             * on every Play action.
             */
            return newMovieLoadResponse(
                name = title,
                url = canonicalUrl(path),
                type = type,
                dataUrl = canonicalUrl(path)
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        /*
         * A MovieBox TV page's SSR `resource.videoAddress` is trailer/player
         * metadata, not the series episode catalogue. The provided Trigger
         * source, for example, exposes a 94-second trailer resource and a
         * generic /play/trigger-... route. Treating that route as Episode 1
         * is what creates the false short-episode behavior.
         *
         * parseEpisodes therefore accepts only explicit episode routes/markers.
         */
        val playerPageUrl =
            document?.let { extractPlayerPageUrl(it, canonicalUrl(path)) }
                ?: canonicalUrl(derivedPlayPath(path) ?: path)

        val subjectId =
            extractSubjectId(document?.html().orEmpty())
                ?: extractSubjectId(playerPageUrl)

        val episodes =
            if (document != null) {
                parseEpisodes(
                    document = document,
                    playerPageUrl = playerPageUrl,
                    subjectId = subjectId
                )
            } else {
                emptyList()
            }

        return newTvSeriesLoadResponse(
            name = title,
            url = canonicalUrl(path),
            type = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

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

        val parts = rawData.split("||")
        val pageUrl = parts.firstOrNull()?.trim().orEmpty()
        val mode = parts.getOrNull(1)?.trim().orEmpty()
        val subjectId = parts.getOrNull(2)?.trim().orEmpty().takeIf { it.isNotBlank() }
        val season = parts.getOrNull(3)?.toIntOrNull()
        val episode = parts.getOrNull(4)?.toIntOrNull()

        val pagePath = pathFromUrl(pageUrl)
        if (pagePath.isBlank()) return false

        /*
         * Every Play action starts from scratch.
         *
         * We deliberately do not persist a tokenized media URL. The site may
         * return a new signed CDN URL on every request.
         */
        repeat(MAX_PLAYBACK_ATTEMPTS) { attempt ->

            val candidateUrls = linkedSetOf<String>()

            candidateUrls += canonicalUrl(pagePath)

            if (pagePath.startsWith("/film/")) {
                candidateUrls += canonicalUrl(
                    "/movies/" + pagePath.removePrefix("/film/")
                )
            }

            if (pagePath.startsWith("/movies/")) {
                candidateUrls += canonicalUrl(
                    "/film/" + pagePath.removePrefix("/movies/")
                )
            }

            contentSlug(pagePath)?.let { slug ->
                candidateUrls += "$MOVIE_DETAIL_ENDPOINT/$slug"
                candidateUrls += canonicalUrl("/play/$slug")
            }

            if (mode.equals("tv", true) && season != null && episode != null) {
                candidateUrls += withQueryParameters(
                    pageUrl,
                    mapOf(
                        "id" to subjectId,
                        "se" to season.toString(),
                        "ep" to episode.toString()
                    )
                )
            }

            derivedPlayPath(pagePath)?.let { playPath ->
                candidateUrls += canonicalUrl(playPath)
            }

            for (candidateUrl in candidateUrls) {
                if (
                    resolveMovieCandidate(
                        candidateUrl = candidateUrl,
                        sourcePageUrl = pageUrl,
                        subjectId = subjectId,
                        season = season,
                        episode = episode,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                ) {
                    return true
                }
            }
        }

        return false
    }

    private suspend fun resolveMovieCandidate(
        candidateUrl: String,
        sourcePageUrl: String,
        subjectId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response =
            runCatching {
                app.get(
                    addCacheBuster(candidateUrl),
                    headers = browserHeaders(
                        sourcePageUrl
                    ) + mapOf(
                        "Referer" to sourcePageUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to (
                            "text/html,application/xhtml+xml," +
                                "application/json,text/plain,*/*;q=0.8"
                            ),
                        "Cache-Control" to "no-cache",
                        "Pragma" to "no-cache"
                    )
                )
            }.getOrNull()
                ?: return false

        if (response.code !in 200..399) {
            return false
        }

        val html = response.text
        val document = response.document
        val mediaCandidates =
            linkedMapOf<String, MediaSource>()

        fun collect(source: MediaSource) {
            if (!looksLikePlayableMedia(source.url)) return

            val existing = mediaCandidates[source.url]
            if (existing == null) {
                mediaCandidates[source.url] = source
            } else {
                val betterSize =
                    if (source.declaredSize > existing.declaredSize) {
                        source.declaredSize
                    } else {
                        existing.declaredSize
                    }

                val betterDuration =
                    if (source.declaredDurationSeconds > existing.declaredDurationSeconds) {
                        source.declaredDurationSeconds
                    } else {
                        existing.declaredDurationSeconds
                    }

                mediaCandidates[source.url] =
                    existing.copy(
                        declaredSize = betterSize,
                        declaredDurationSeconds = betterDuration
                    )
            }
        }

        /*
         * 1. Direct media / serialized state from the current response.
         */
        extractJsonLdMedia(
            html = html,
            pageUrl = candidateUrl
        ).forEach(::collect)

        extractMediaSources(
            document = document,
            pageUrl = candidateUrl,
            html = html
        ).forEach(::collect)

        /*
         * The public web player can expose the currently selected resource in
         * its HTML, while the same MovieBox ecosystem also exposes a BFF
         * play-info response containing streamList entries. Use it as a fresh
         * quality resolver when a subject id is available, so 480p/720p/1080p
         * streams can all be surfaced when the upstream provides them.
         */
        val resolvedSubjectId =
            subjectId ?: extractPrimarySubjectIdFromNuxt(html)

        val resolvedSeason = season ?: 0
        val resolvedEpisode = episode ?: 0

        if (!resolvedSubjectId.isNullOrBlank()) {
            resolveViaPlayInfo(
                subjectId = resolvedSubjectId,
                season = resolvedSeason,
                episode = resolvedEpisode,
                referer = candidateUrl
            ).forEach(::collect)
        }

        /*
         * 2. The current response may expose a fresh /play/... URL.
         */
        val playUrls =
            linkedSetOf<String>()

        playUrls +=
            extractJsonLdUrls(
                html
            )

        val decodedHtml =
            html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

        Regex(
            """(?i)https?://[^"'<>\s]+/play/[^"'<>\s]+"""
        ).findAll(
            decodedHtml
        ).forEach { match ->
            playUrls += match.value
        }

        Regex(
            """(?i)"(/play/[^"]+)"""
        ).findAll(
            decodedHtml
        ).forEach { match ->
            playUrls +=
                absoluteUrl(
                    match.groupValues[1]
                )
        }

        /*
         * 3. If this is the MovieBox backend detail route, derive the
         * corresponding public player route from the same slug/id.
         */
        if (
            candidateUrl.startsWith(
                MOVIE_DETAIL_ENDPOINT,
                true
            )
        ) {
            val slug =
                contentSlug(
                    sourcePageUrl
                )
                    ?: contentSlug(
                        candidateUrl
                    )

            if (!slug.isNullOrBlank()) {
                playUrls +=
                    canonicalUrl(
                        "/play/$slug"
                    )
            }
        }

        /*
         * 4. Fetch every discovered /play route fresh and inspect it.
         */
        for (playUrl in playUrls) {
            val watchResponse =
                runCatching {
                    app.get(
                        addCacheBuster(playUrl),
                        headers = browserHeaders(
                            candidateUrl
                        ) + mapOf(
                            "Referer" to candidateUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to (
                                "text/html,application/xhtml+xml," +
                                    "application/json,text/plain,*/*;q=0.8"
                                ),
                            "Cache-Control" to "no-cache",
                            "Pragma" to "no-cache"
                        )
                    )
                }.getOrNull()
                    ?: continue

            if (watchResponse.code !in 200..399) {
                continue
            }

            val watchHtml =
                watchResponse.text

            extractJsonLdMedia(
                html = watchHtml,
                pageUrl = playUrl
            ).forEach(::collect)

            extractMediaSources(
                document = watchResponse.document,
                pageUrl = playUrl,
                html = watchHtml
            ).forEach(::collect)

            extractSubtitles(
                watchResponse.document
            ).forEach { subtitle ->
                subtitleCallback(
                    newSubtitleFile(
                        subtitle.first,
                        subtitle.second
                    )
                )
            }
        }

        val enrichedCandidates = LinkedHashMap<String, MediaSource>()
        for (source in mediaCandidates.values) {
            val enriched = enrichMediaSource(
                source = source,
                referer = source.referer
            )
            val existing = enrichedCandidates[enriched.url]
            enrichedCandidates[enriched.url] = if (existing == null) {
                enriched
            } else {
                existing.copy(
                    quality = max(existing.quality, enriched.quality),
                    declaredSize = max(existing.declaredSize, enriched.declaredSize),
                    declaredDurationSeconds = max(
                        existing.declaredDurationSeconds,
                        enriched.declaredDurationSeconds
                    )
                )
            }
        }

        val usableCandidates =
            enrichedCandidates.values
                .filter { looksLikePlayableMedia(it.url) }
                .filterNot { isObviousTrailerOrPreview(it.url) }
                .ifEmpty {
                    mediaCandidates.values.filter { looksLikePlayableMedia(it.url) }
                }

        if (usableCandidates.isEmpty()) {
            return false
        }

        val master = usableCandidates
            .filter { isLikelyMasterPlaylist(it.url) }
            .maxByOrNull {
                max(it.declaredSize, 0L) * 1000L + max(it.declaredDurationSeconds, 0L)
            }

        if (master != null) {
            emitSource(master, callback)
            return true
        }

        /*
         * Keep every distinct playable quality that the current player/page
         * exposes. When the upstream gives 480p + 720p + 1080p, all three are
         * forwarded to CloudStream so the user can choose. When only one exists,
         * only that one is forwarded. A short preview is never preferred simply
         * because it was discovered first.
         */
        val byQuality = linkedMapOf<Int, MediaSource>()
        val unknown = ArrayList<MediaSource>()

        usableCandidates.forEach { source ->
            val q = source.quality
            if (q > 0 && q != Qualities.Unknown.value) {
                val existing = byQuality[q]
                if (existing == null || mediaSourceScore(source) > mediaSourceScore(existing)) {
                    byQuality[q] = source
                }
            } else {
                unknown += source
            }
        }

        val selected = ArrayList<MediaSource>()
        selected += byQuality
            .toSortedMap(compareByDescending { it })
            .values

        if (selected.isEmpty()) {
            selected += unknown
                .sortedWith(compareByDescending<MediaSource> { mediaSourceScore(it) }.thenBy { it.url })
                .take(1)
        }

        if (selected.isEmpty()) return false

        selected.forEach { source ->
            emitSource(source, callback)
        }

        return true
    }

    private suspend fun resolveViaPlayInfo(
        subjectId: String,
        season: Int,
        episode: Int,
        referer: String
    ): List<MediaSource> {
        val qualities = listOf(1080, 720, 480)
        val results = LinkedHashMap<String, MediaSource>()
        val hosts = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com"
        )

        fun addFromResponse(text: String, qualityHint: Int) {
            val decoded = text
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            val urlRegex = Regex(
                """(?i)\"(?:url|streamUrl|playUrl|contentUrl|mediaUrl)\"\s*:\s*\"((?:https?:)?//[^\"]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^\"]*)?)\""""
            )

            urlRegex.findAll(decoded).forEach { match ->
                val url = normalizeMediaUrl(match.groupValues[1], referer) ?: return@forEach
                val context = decoded.substring(
                    max(0, match.range.first - 900),
                    min(decoded.length, match.range.last + 900)
                )
                val quality = max(
                    qualityHint,
                    max(qualityFromText(url), qualityFromText(context))
                )
                val size = Regex(
                    """(?i)\"(?:size|fileSize|contentLength)\"\s*:\s*(\d{4,})"""
                ).find(context)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
                val duration = Regex(
                    """(?i)\"(?:duration|durationSeconds)\"\s*:\s*\"?([0-9]+(?:\.[0-9]+)?)"""
                ).find(context)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toLong() ?: -1L

                results[url] = MediaSource(
                    url = url,
                    quality = if (quality > 0) quality else Qualities.Unknown.value,
                    label = if (quality > 0) "MovieBox ${quality}p" else "MovieBox Stream",
                    referer = referer,
                    declaredSize = size,
                    declaredDurationSeconds = duration
                )
            }
        }

        for (quality in qualities) {
            var found = false
            for (host in hosts) {
                val query =
                    "subjectId=${URLEncoder.encode(subjectId, "UTF-8")}" +
                        "&se=$season" +
                        "&ep=$episode" +
                        "&quality=$quality"
                val url =
                    "$host/wefeed-mobile-bff/subject-api/play-info?$query"

                val response = runCatching {
                    app.get(
                        addCacheBuster(url),
                        headers = browserHeaders(referer) + mapOf(
                            "Accept" to "application/json,text/plain,*/*;q=0.8",
                            "Cache-Control" to "no-cache",
                            "Pragma" to "no-cache"
                        )
                    )
                }.getOrNull() ?: continue

                if (response.code !in 200..399) continue
                val before = results.size
                addFromResponse(response.text, quality)
                if (results.size > before) {
                    found = true
                    break
                }
            }
            if (!found) continue
        }

        return results.values.toList()
    }

    private fun extractPrimarySubjectIdFromNuxt(html: String): String? {
        val payload = extractNuxtPayload(html) ?: return null
        val table = NuxtTable(payload)

        for (index in 0 until payload.size()) {
            val node = payload[index]
            if (!node.isObject) continue
            if (node.get("subjectId") == null || node.get("title") == null) continue
            val subjectId = table.textField(node, "subjectId") ?: continue
            if (subjectId.length >= 10 && subjectId.all { it.isDigit() }) {
                return subjectId
            }
        }

        return null
    }

    private suspend fun enrichMediaSource(
        source: MediaSource,
        referer: String
    ): MediaSource {
        val lower = source.url.lowercase(Locale.ROOT)
        if (!lower.contains(".mp4") && !lower.contains(".m4v") && !lower.contains(".webm")) {
            return source
        }

        val response = runCatching {
            app.get(
                source.url,
                headers = browserHeaders(referer) + mapOf(
                    "Range" to "bytes=0-0",
                    "Accept" to "video/mp4,video/webm,video/*;q=0.9,*/*;q=0.5",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return source

        val disposition = response.headers["Content-Disposition"].orEmpty()
        val contentRange = response.headers["Content-Range"].orEmpty()
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L

        val filename = Regex(
            "(?i)filename\\s*=\\s*(?:\"([^\"]+)\"|([^;]+))"
        ).find(disposition)?.let {
            firstNonBlank(it.groupValues.getOrNull(1), it.groupValues.getOrNull(2))
        }.orEmpty()

        val totalFromRange = Regex(
            """/(\d+)\s*$"""
        ).find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L

        val totalSize = max(
            source.declaredSize,
            max(totalFromRange, contentLength)
        )
        val detectedQuality = max(
            source.quality,
            max(qualityFromText(filename), qualityFromText(disposition))
        )

        return source.copy(
            quality = if (detectedQuality > 0) detectedQuality else source.quality,
            declaredSize = totalSize
        )
    }

    private fun mediaSourceScore(source: MediaSource): Long {
        val size = max(source.declaredSize, 0L)
        val duration = max(source.declaredDurationSeconds, 0L)
        val quality = max(source.quality, 0).toLong()
        return size * 1000L + duration * 10L + quality
    }

    private fun isLikelyMasterPlaylist(url: String): Boolean {
        val value = url.lowercase(Locale.ROOT)
        return value.contains(".m3u8") && !value.contains("/segment/") && !value.contains("chunklist")
    }

    private suspend fun emitSource(
        source: MediaSource,
        callback: (ExtractorLink) -> Unit
    ) {
        val displayLabel =
            if (source.quality > 0 && source.quality != Qualities.Unknown.value) {
                if (source.label.contains("${source.quality}p", true)) {
                    source.label
                } else {
                    "${source.label} ${source.quality}p"
                }
            } else {
                source.label
            }

        callback(
            newExtractorLink(
                "MovieBox",
                displayLabel,
                source.url,
                ExtractorLinkType.VIDEO
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
                result += m.groupValues[1].replace("\\/", "/")
            }
        }

        val directPlay = Regex(
            """(?i)https?://[^\"'<>\s]+/play/[^\"'<>\s]+"""
        )
        directPlay.findAll(html).forEach { m ->
            result += m.value.replace("\\/", "/")
        }

        Regex(
            """(?i)"(/play/[^\"]+)"""
        ).findAll(html).forEach { m ->
            result += absoluteUrl(m.groupValues[1])
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

            val detectedQuality = max(
                qualityFromText(url),
                qualityFromText(nearby)
            )

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
                    quality = detectedQuality,
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
            """(?i)\"(?:sourceUrl|sniffUrl|playUrl|streamUrl|videoUrl|contentUrl|mediaUrl|url)\"\s*:\s*\"((?:https?:)?//[^\"]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"]*)?)\""""
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
        val payload = extractNuxtPayload(document.html()) ?: return emptyList()
        val table = NuxtTable(payload)
        val results = ArrayList<SiteItem>()
        val seen = HashSet<String>()

        for (index in 0 until payload.size()) {
            val node = payload[index]
            if (!node.isObject) continue

            val title = table.textField(node, "title")?.let(::cleanTitle) ?: continue
            val detailPathRaw = table.textField(node, "detailPath") ?: continue
            val rawPath = normalizePathFromAny(detailPathRaw)

            val inferredType = if (isLikelyContentPath(rawPath)) {
                typeFromPath(rawPath)
            } else {
                when (table.textField(node, "subjectType")?.toIntOrNull()) {
                    2, 7 -> TvType.TvSeries
                    else -> TvType.Movie
                }
            }

            val type = forcedType ?: inferredType
            val normalizedPath =
                if (isLikelyContentPath(rawPath)) {
                    rawPath
                } else {
                    val slug = rawPath.trim().trim('/').substringAfterLast('/')
                    when (type) {
                        TvType.TvSeries -> "/tv-series/$slug"
                        TvType.Anime -> "/animated-series/$slug"
                        else -> "/film/$slug"
                    }
                }

            if (normalizedPath.count { it == '/' } < 2) continue

            val subjectId = table.textField(node, "subjectId")
            val poster = table.imageField(node, "cover")
                ?: table.imageField(node, "poster")

            val key = contentKey(normalizedPath) + "|" + type.name
            if (!seen.add(key)) continue

            results += SiteItem(
                title = title,
                url = canonicalUrl(normalizedPath),
                poster = poster,
                type = type,
                languageRank = languageRank(title),
                subjectId = subjectId
            )
        }

        return results
    }

    private fun extractNuxtPayload(html: String): JsonNode? {
        val regex = Regex(
            "(?is)<script[^>]*id=\\\"__NUXT_DATA__\\\"[^>]*>(.*?)</script>"
        )
        val payloadText = regex.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("(?is)<script[^>]*data-nuxt-data[^>]*>(.*?)</script>")
                .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: return null

        return runCatching {
            ObjectMapper().readTree(payloadText)
        }.getOrNull()
    }

    private class NuxtTable(
        private val root: JsonNode
    ) {
        private val cache = HashMap<Int, JsonNode?>()

        fun resolve(value: JsonNode?, depth: Int = 0): JsonNode? {
            if (value == null || depth > 40) return null
            if (value.isIntegralNumber) {
                val index = value.asInt()
                if (index < 0 || index >= root.size()) return value
                cache[index]?.let { return it }
                val node = root[index]
                val resolved = when {
                    node.isIntegralNumber -> resolve(node, depth + 1)
                    node.isArray && node.size() == 2 && node[0].isTextual &&
                        node[0].asText() in setOf(
                            "ShallowReactive", "Reactive", "Set", "Map", "Date", "RegExp"
                        ) -> resolve(node[1], depth + 1)
                    else -> node
                }
                cache[index] = resolved
                return resolved
            }
            return when {
                value.isArray && value.size() == 2 && value[0].isTextual &&
                    value[0].asText() in setOf(
                        "ShallowReactive", "Reactive", "Set", "Map", "Date", "RegExp"
                    ) -> resolve(value[1], depth + 1)
                else -> value
            }
        }

        fun textField(obj: JsonNode, key: String): String? {
            val raw = obj.get(key) ?: return null
            val resolved = resolve(raw) ?: return null
            return when {
                resolved.isTextual -> resolved.asText()
                resolved.isNumber -> resolved.asText()
                else -> null
            }
        }

        fun imageField(obj: JsonNode, key: String): String? {
            val raw = obj.get(key) ?: return null
            val resolved = resolve(raw) ?: return null
            if (resolved.isTextual) return firstUsefulUrl(resolved.asText())
            if (resolved.isObject) {
                return firstUsefulUrl(
                    textField(resolved, "url"),
                    textField(resolved, "thumbnail"),
                    textField(resolved, "src")
                )
            }
            return null
        }

        fun listField(obj: JsonNode, key: String): List<JsonNode> {
            val raw = obj.get(key) ?: return emptyList()
            val resolved = resolve(raw) ?: return emptyList()
            if (!resolved.isArray) return emptyList()
            return resolved.mapNotNull { resolve(it) }
        }
    }

    private fun normalizePathFromAny(raw: String): String {
        val absolute = absoluteUrl(raw)
        return pathFromUrl(absolute)
    }

    private fun extractPlayerPageUrl(
        document: Document,
        fallback: String
    ): String {
        val html = document.html()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val canonical = document.selectFirst("link[rel=canonical]")?.attr("href")
        if (canonical?.contains("/play/", true) == true) {
            return absoluteUrl(canonical)
        }

        val jsonLdPlay = Regex(
            "(?i)https?://[^\"'<>\\s]+/play/[^\"'<>\\s]+"
        ).find(html)?.value
        if (!jsonLdPlay.isNullOrBlank()) return jsonLdPlay

        val relativePlay = Regex(
            "(?i)\\\"(/play/[^\\\"]+)\\\""
        ).find(html)?.groupValues?.getOrNull(1)
        if (!relativePlay.isNullOrBlank()) return absoluteUrl(relativePlay)

        return fallback
    }

    private fun extractSubjectId(value: String): String? {
        Regex("(?i)(?:[?&]id=|\\\"subjectId\\\"\\s*:\\s*\\\")([0-9]{10,})")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }

        val htmlMatch = Regex(
            "(?i)\\\"subjectId\\\"\\s*:\\s*(\\\"?)([0-9]{10,})\\1"
        ).find(value)
        return htmlMatch?.groupValues?.getOrNull(2)
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

        val windowStart =
            max(
                0,
                start - 9000
            )

        val windowEnd =
            min(
                source.length,
                end + 3500
            )

        if (windowEnd <= windowStart) return null

        val window =
            source.substring(
                windowStart,
                windowEnd
            )

        data class PosterCandidate(
            val url: String,
            val width: Int,
            val height: Int,
            val assetScore: Int,
            val distance: Int
        )

        /*
         * MovieBox's Nuxt/devalue media objects commonly serialize the poster
         * dimensions around the image URL:
         *
         * "jpg",HEIGHT,SIZE,"URL",WIDTH
         *
         * This lets us prefer a true portrait cover over a landscape banner or
         * thumbnail, which is what was causing the first Movie/TV cards to
         * render shorter than the other poster cards.
         */
        val dimensionRegex =
            Regex(
                """(?is)"(?:jpg|jpeg|png|webp)",(\d{3,5}),\d{2,10},"(https?://[^"]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"]*)?)",(\d{3,5})"""
            )

        val dimensionCandidates =
            dimensionRegex
                .findAll(window)
                .mapNotNull { match ->
                    val height =
                        match.groupValues[1]
                            .toIntOrNull()
                            ?: return@mapNotNull null

                    val width =
                        match.groupValues[3]
                            .toIntOrNull()
                            ?: return@mapNotNull null

                    val rawUrl =
                        match.groupValues[2]

                    val url =
                        rawUrl
                            .replace("\\/", "/")
                            .replace("\\:", ":")
                            .replace("\\\\", "\\")

                    if (
                        url.contains("logo", true) ||
                        url.contains("icon", true) ||
                        url.contains("avatar", true)
                    ) {
                        return@mapNotNull null
                    }

                    val assetScore =
                        when {
                            url.contains("/media/vone/", true) -> 30
                            url.contains("/image/", true) -> 20
                            else -> 10
                        }

                    PosterCandidate(
                        url = url,
                        width = width,
                        height = height,
                        assetScore = assetScore,
                        distance =
                            kotlin.math.abs(
                                (windowStart + match.range.first) - end
                            )
                    )
                }
                .toList()

        if (dimensionCandidates.isNotEmpty()) {
            return dimensionCandidates
                .sortedWith(
                    compareByDescending<PosterCandidate> {
                        when {
                            it.height >= it.width * 1.05 -> 40
                            it.height >= it.width -> 25
                            else -> 5
                        }
                    }
                        .thenByDescending {
                            it.assetScore
                        }
                        .thenBy {
                            it.distance
                        }
                )
                .first()
                .url
        }

        /*
         * Fallback for pages where dimension metadata is not serialized.
         */
        val regex =
            Regex(
                """https?:(?:/|\\/){2}[^"'<>\s]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'<>\s]*)?""",
                RegexOption.IGNORE_CASE
            )

        val candidates =
            regex.findAll(window)
                .map { match ->
                    val raw = match.value

                    val normalized =
                        raw
                            .replace("\\/", "/")
                            .replace("\\:", ":")
                            .replace("\\\\", "\\")

                    val score =
                        when {
                            normalized.contains("/media/vone/", true) -> 30
                            normalized.contains("/image/", true) -> 20
                            else -> 10
                        }

                    PosterCandidate(
                        url = normalized,
                        width = 0,
                        height = 0,
                        assetScore = score,
                        distance =
                            kotlin.math.abs(
                                (windowStart + match.range.first) - end
                            )
                    )
                }
                .filter {
                    !it.url.contains("logo", true) &&
                        !it.url.contains("icon", true) &&
                        !it.url.contains("avatar", true)
                }
                .toList()

        return candidates
            .sortedWith(
                compareByDescending<PosterCandidate> {
                    it.assetScore
                }.thenBy {
                    it.distance
                }
            )
            .firstOrNull()
            ?.url
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
        document: Document,
        playerPageUrl: String,
        subjectId: String?
    ): List<Episode> {
        val payload = extractNuxtPayload(document.html())
        val table = payload?.let(::NuxtTable)
        val seasonCounts = linkedMapOf<Int, Int>()

        if (payload != null && table != null) {
            for (index in 0 until payload.size()) {
                val node = payload[index]
                if (!node.isObject) continue
                if (node.get("allEp") == null || node.get("resolutions") == null) continue

                val allEp = table.textField(node, "allEp")?.toIntOrNull()
                    ?: continue
                val maxEp = table.textField(node, "maxEp")?.toIntOrNull() ?: 0
                val count = maxOf(allEp, maxEp)
                if (count <= 0) continue

                val rawSeason = table.textField(node, "se")?.toIntOrNull()
                val season = rawSeason ?: (seasonCounts.size + 1)
                val previous = seasonCounts[season] ?: 0
                seasonCounts[season] = maxOf(previous, count)
            }
        }

        if (seasonCounts.isEmpty()) {
            val html = document.html()
            Regex(
                "(?is)\\\"(?:allEp|maxEp)\\\"\\s*:\\s*(?:\\\"(\\d+)\\\"|(\\d+))"
            ).findAll(html).mapNotNull { match ->
                val value = (match.groupValues.getOrNull(1).orEmpty() + match.groupValues.getOrNull(2).orEmpty())
                    .toIntOrNull()
                value?.takeIf { it > 0 }
            }.maxOrNull()?.let { seasonCounts[1] = it }
        }

        if (seasonCounts.isEmpty()) return emptyList()

        val basePlayUrl = playerPageUrl.ifBlank { document.location().orEmpty() }
        val episodes = ArrayList<Episode>()

        for ((season, count) in seasonCounts.entries.sortedBy { it.key }) {
            for (episodeNumber in 1..count) {
                val data = buildEpisodeData(
                    basePlayUrl = basePlayUrl,
                    subjectId = subjectId,
                    season = season,
                    episode = episodeNumber
                )

                episodes += newEpisode(data) {
                    name = "Episode $episodeNumber"
                    this.season = season
                    this.episode = episodeNumber
                }
            }
        }

        return episodes
    }

    private fun buildEpisodeData(
        basePlayUrl: String,
        subjectId: String?,
        season: Int,
        episode: Int
    ): String {
        return listOf(
            basePlayUrl,
            "tv",
            subjectId.orEmpty(),
            season.toString(),
            episode.toString()
        ).joinToString("||")
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
     * Hindi > English > Bangla > Other.
     *
     * No duplicate filtering is applied.
     */
    private fun rankSearchResults(
        query: String,
        items: List<SiteItem>
    ): List<SearchResponse> {

        return items
            .map { item ->

                val score =
                    searchScore(
                        query,
                        item.title
                    )

                Triple(
                    item,
                    score,
                    item.languageRank
                )
            }
            .filter {
                it.second >= 0.42
            }
            .sortedWith(
                /*
                 * Language priority is absolute:
                 *
                 * 1 = Hindi
                 * 2 = English
                 * 3 = Bangla
                 * 4 = Other
                 *
                 * Relevance is used only inside the same language
                 * group. This guarantees Hindi appears before English
                 * and Bangla when matching results exist.
                 */
                compareBy<
                    Triple<
                        SiteItem,
                        Double,
                        Int
                    >
                > {
                    it.third
                }.thenByDescending {
                    it.second
                }.thenBy {
                    it.first.title
                        .lowercase(
                            Locale.ROOT
                        )
                }
            )
            .take(
                SEARCH_RESULT_LIMIT
            )
            .map {
                it.first.toSearchResponse()
            }
    }

    private fun languageRank(
        text: String
    ): Int {

        val normalized =
            normalizeSearch(text)

        /*
         * Hindi
         */
        if (
            Regex(
                """\b(hindi|hindi dubbed|hindi audio|dubbed in hindi)\b"""
            ).containsMatchIn(
                normalized
            ) ||
            text.contains("हिन्दी") ||
            text.contains("हिंदी")
        ) {
            return 1
        }

        /*
         * English
         */
        if (
            Regex(
                """\b(english|eng|english audio)\b"""
            ).containsMatchIn(
                normalized
            )
        ) {
            return 2
        }

        /*
         * Bangla
         */
        if (
            Regex(
                """\b(bangla|bengali|bangla dubbed|bangla audio)\b"""
            ).containsMatchIn(
                normalized
            ) ||
            text.contains("বাংলা") ||
            text.contains("বাঙ্গালী")
        ) {
            return 3
        }

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

    private fun withQueryParameters(
        url: String,
        values: Map<String, String?>
    ): String {
        var result = url
        val fragment = result.substringAfter('#', "")
        if (fragment.isNotEmpty()) result = result.substringBefore('#')

        values.forEach { (key, value) ->
            if (value.isNullOrBlank()) return@forEach
            val encoded = URLEncoder.encode(value, "UTF-8")
            val regex = Regex("(?i)([?&])" + Regex.escape(key) + "=[^&]*")
            result = if (regex.containsMatchIn(result)) {
                result.replace(regex, "\$1$key=$encoded")
            } else {
                val separator = if (result.contains('?')) "&" else "?"
                result + separator + key + "=" + encoded
            }
        }

        return if (fragment.isNotEmpty()) "$result#$fragment" else result
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

    private fun cleanTitle(
        raw: String
    ): String {

        return raw
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
            .removeSuffix(
                "| MovieBox"
            )
            .removeSuffix(
                "- MovieBox"
            )
            .trim()
    }

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
                """(?i)(2160|1440|1080|720|576|480|360)(?:p\b|[ _-]?P\b)"""
            ).find(text)

            ?: Regex(
                """(?i)(?:resolution|quality)\s*[=:]\s*["']?(2160|1440|1080|720|576|480|360)"""
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
