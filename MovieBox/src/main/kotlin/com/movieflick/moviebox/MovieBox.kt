package com.movieflick.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        val languageRank: Int = 4
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
        val referer: String
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

            for (basePath in rankingPaths) {
                for (route in rankingPageRoutes(basePath, currentPage)) {
                    val result = fetchMirrorPage(route) ?: continue
                    val items = parseNuxtListing(result.document, null)
                    if (items.isNotEmpty()) {
                        rankingItems.addAll(items)
                        break
                    }
                }
            }

            val sortedItems = sortHomeItems(rankingItems)
            val limit = if (currentPage == 1) HOME_INITIAL_LIMIT else HOME_PAGE_LIMIT

            return newHomePageResponse(
                data = request,
                list = sortedItems.take(limit).map { it.toSearchResponse() },
                hasNext = sortedItems.size > limit
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
        val episodes =
            if (document != null) {
                parseEpisodes(
                    document = document
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

        val pageUrl =
            rawData.substringBefore("||").trim()

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

            derivedPlayPath(pagePath)?.let { playPath ->
                candidateUrls += canonicalUrl(playPath)
            }

            for (candidateUrl in candidateUrls) {
                if (
                    resolveMovieCandidate(
                        candidateUrl = candidateUrl,
                        sourcePageUrl = pageUrl,
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
            mediaCandidates.putIfAbsent(
                source.url,
                source
            )
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

        val orderedCandidates =
            mediaCandidates.values
                .filter {
                    looksLikePlayableMedia(
                        it.url
                    )
                }
                .sortedWith(
                    compareByDescending<MediaSource> {
                        it.quality
                    }.thenBy {
                        it.label
                    }
                )

        if (orderedCandidates.isEmpty()) {
            /*
             * A response can contain player configuration that points to
             * another public page but not yet contain the signed CDN URL.
             * Returning false here causes the caller to continue trying other
             * candidate routes / fresh attempts.
             */
            return false
        }

        for (source in orderedCandidates) {
            emitSource(
                source,
                callback
            )
        }

        return true
    }

    private suspend fun emitSource(
        source: MediaSource,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                "MovieBox",
                source.label,
                source.url,
                ExtractorLinkType.VIDEO
            ) {
                quality = source.quality
                referer = source.referer
            }
        )
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
                """(?i)"contentUrl"\s*:\s*"([^"]+)"""
            ).findAll(block).forEach { media ->
                val url = normalizeMediaUrl(media.groupValues[1], pageUrl) ?: return@forEach
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

        fun add(raw: String?, label: String) {
            if (raw.isNullOrBlank()) return
            val url = normalizeMediaUrl(raw, pageUrl) ?: return
            if (url.contains("youtube.com", true) || url.contains("youtu.be", true)) return
            found.putIfAbsent(
                url,
                MediaSource(url, qualityFromText(url), label, pageUrl)
            )
        }

        document.select("video[src], video source[src], source[src]").forEach { element ->
            add(firstNonBlank(element.attr("src"), element.attr("data-src")), "MovieBox Direct")
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
        ).findAll(decoded).forEach { add(it.value, "MovieBox Direct") }

        // Serialized SSR state can carry the current source URL.
        Regex(
            """(?i)\"(?:sourceUrl|sniffUrl|playUrl|streamUrl|videoUrl|contentUrl|mediaUrl|url)\"\s*:\s*\"((?:https?:)?//[^\"]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"]*)?)\""""
        ).findAll(decoded).forEach { match ->
            add(
                match.groupValues[1],
                "MovieBox State"
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
            add(
                match.value,
                "MovieBox CDN"
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

            val routeMatch = Regex(
                """(?i)/(?:film|movies|tv-series|animated-series)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            ).find(window)

            val backendMatch = Regex(
                """(?i)https?://wefeed-h5-bff\.wefeed-prod/detail/([A-Za-z0-9._~%\-]+)"""
            ).find(window)

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
                val slugTitle = candidatePath
                    .substringAfterLast('/')
                    .replace('-', ' ')
                    .replace('_', ' ')
                val score = max(
                    searchScore(title, slugTitle),
                    searchScore(title, cleanTitle(candidatePath))
                )
                val poster = findNearestPoster(source, windowStart, titleIndex)

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

        if (end <= start) return null

        val window = source.substring(start, end)

        val regex =
            Regex(
                """https?:(?:/|\\/){2}[^"'\s]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'\s]*)?""",
                RegexOption.IGNORE_CASE
            )

        val match =
            regex.findAll(window)
                .lastOrNull()
                ?.value
                ?: return null

        return match
            .replace("\\/", "/")
            .replace("\\:", ":")
            .replace("\\\\", "\\")
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
                 * Force a fresh HTML response as well. This is important for
                 * MovieBox because the page may contain newly generated
                 * playback/session information.
                 */
                add(addCacheBuster(normalized))

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
