package com.movieflick.moviebox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

    private val mobileApiHosts = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    private val jsonMapper = ObjectMapper()

    private companion object {
        const val HOME_INITIAL_LIMIT = 6
        const val HOME_PAGE_LIMIT = 10
        const val SEARCH_NATIVE_PAGES = 2
        const val SEARCH_RESULT_LIMIT = 50
        const val SITEMAP_RESULT_LIMIT = 80
        const val SITEMAP_MAX_BYTES = 2_000_000
        const val MOVIE_DETAIL_ENDPOINT = "http://wefeed-h5-bff.wefeed-prod/detail"
        const val MAX_PLAYBACK_ATTEMPTS = 8
        const val SEARCH_API_PAGE_SIZE = 20
        const val API_RETRY_ATTEMPTS = 3
        const val MAX_EPISODES_PER_SEASON = 500
        const val MOBILE_VERSION = "16.2.1"

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

            val parsedItems = parseListing(
                result.document,
                forcedType
            )
            if (parsedItems.isEmpty()) continue

            val items = repairPosters(
                parsedItems
            )
            val sortedItems = sortHomeItems(items)
            val limit = if (currentPage == 1) HOME_INITIAL_LIMIT else HOME_PAGE_LIMIT

            return newHomePageResponse(
                data = request,
                list = sortedItems.take(limit).map { it.toSearchResponse() },
                hasNext = detectHasNext(
                    result.document,
                    currentPage
                ) || sortedItems.size > limit
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
    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {
        val value = query.trim()
        if (value.isBlank()) return emptyList()

        val apiItems = searchMobileApi(value)
        if (apiItems.isNotEmpty()) {
            return rankSearchResults(value, apiItems).take(8)
        }

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

        val apiItems = searchMobileApi(original)
        if (apiItems.isNotEmpty()) {
            return rankSearchResults(original, apiItems)
        }

        for (domain in domains) {
            val nativeItems = searchNative(domain, original)
            val combined = ArrayList<SiteItem>(nativeItems.size + SITEMAP_RESULT_LIMIT)
            combined.addAll(nativeItems)

            if (nativeItems.size < SEARCH_RESULT_LIMIT / 2) {
                combined.addAll(searchSitemap(domain, original))
            }

            val ranked = rankSearchResults(original, combined)
            if (ranked.isNotEmpty()) return ranked
        }

        return emptyList()
    }

    private suspend fun searchMobileApi(
        query: String
    ): List<SiteItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        for (host in mobileApiHosts) {
            repeat(API_RETRY_ATTEMPTS) {
                val response = runCatching {
                    app.get(
                        "$host/wefeed-mobile-bff/subject-api/search?q=$encoded&page=1&pageSize=$SEARCH_API_PAGE_SIZE",
                        headers = mobileApiHeaders(host)
                    )
                }.getOrNull() ?: return@repeat

                if (response.code !in 200..399) return@repeat

                val items = parseMobileSearchResponse(response.text)
                if (items.isNotEmpty()) return items
            }
        }

        return emptyList()
    }

    private fun parseMobileSearchResponse(
        body: String
    ): List<SiteItem> {
        val root = runCatching {
            jsonMapper.readTree(body)
        }.getOrNull() ?: return emptyList()

        val arrays = mutableListOf<JsonNode>()

        fun walk(node: JsonNode) {
            if (node.isObject) {
                node.fields().forEachRemaining { entry ->
                    if (
                        entry.value.isArray &&
                        (
                            entry.key.equals("items", true) ||
                                entry.key.equals("results", true) ||
                                entry.key.equals("subjects", true) ||
                                entry.key.equals("data", true)
                        )
                    ) {
                        arrays += entry.value
                    }
                    walk(entry.value)
                }
            } else if (node.isArray) {
                node.forEach(::walk)
            }
        }

        walk(root)

        val source = arrays
            .asSequence()
            .maxByOrNull { it.size() }
            ?: return emptyList()

        return source.mapNotNull { item ->
            val title = cleanTitle(
                firstJsonText(item, "title", "name").orEmpty()
            )
            if (title.isBlank()) return@mapNotNull null

            val subjectId = firstJsonText(
                item,
                "subjectId",
                "id"
            )?.takeIf {
                it.matches(Regex("""\d{8,}"""))
            } ?: return@mapNotNull null

            val detailPath = firstJsonText(
                item,
                "detailPath",
                "detailUrl",
                "url"
            ).orEmpty()

            val type = when {
                detailPath.contains(
                    "/animated-series/",
                    true
                ) -> TvType.Anime

                detailPath.contains(
                    "/tv-series/",
                    true
                ) -> TvType.TvSeries

                firstJsonText(
                    item,
                    "subjectType"
                ) == "2" -> TvType.TvSeries

                firstJsonText(
                    item,
                    "type"
                )?.equals("tv", true) == true -> TvType.TvSeries

                else -> TvType.Movie
            }

            val path = when {
                detailPath.startsWith("http", true) ->
                    pathFromUrl(detailPath)

                detailPath.startsWith("/") ->
                    detailPath

                else ->
                    when (type) {
                        TvType.TvSeries ->
                            "/tv-series/${slugifyForUrl(title)}-$subjectId"

                        TvType.Anime ->
                            "/animated-series/${slugifyForUrl(title)}-$subjectId"

                        else ->
                            "/film/${slugifyForUrl(title)}-$subjectId"
                    }
            }

            if (!isLikelyContentPath(path)) return@mapNotNull null

            val poster = firstUsefulUrl(
                firstJsonText(item, "poster"),
                firstJsonText(item, "cover"),
                firstJsonText(item, "image"),
                firstJsonText(item, "thumbnail")
            )

            SiteItem(
                title = title,
                url = canonicalUrl(path),
                poster = poster,
                type = type,
                languageRank = languageRank(title),
                subjectId = subjectId
            )
        }
    }

private fun firstJsonText(
        node: JsonNode,
        vararg names: String
    ): String? {
        for (name in names) {
            val value = node.get(name) ?: continue
            if (value.isTextual) return value.asText()
            if (value.isNumber) return value.asText()
            if (value.isBoolean) return value.asText()
        }
        return null
    }

    private fun slugifyForUrl(title: String): String {
        return normalizeSearch(title)
            .replace(" ", "-")
            .replace(Regex("[^a-z0-9\\-]"), "")
            .trim('-')
            .ifBlank { "title" }
    }

    private fun mobileApiHeaders(
        host: String
    ): Map<String, String> {
        return browserHeaders("$host/") + mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json;charset=UTF-8",
            "X-M-Version" to MOBILE_VERSION,
            "X-Play-Mode" to "2",
            "Origin" to mainUrl,
            "Referer" to mainUrl + "/"
        )
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
        if (path.isBlank()) return null

        val candidatePaths = linkedSetOf<String>().apply {
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

                path.startsWith("/tv-series/") ||
                    path.startsWith("/animated-series/") -> {
                    val slug = path.substringAfterLast('/')
                    if (slug.isNotBlank()) {
                        add("/play/$slug")
                    }
                }

                path.startsWith("/play/") -> {
                    val slug = path.removePrefix("/play/")
                    if (slug.isNotBlank()) {
                        add("/film/$slug")
                        add("/movies/$slug")
                        add("/tv-series/$slug")
                        add("/animated-series/$slug")
                    }
                }
            }
        }

        var page: MirrorPage? = null
        for (candidate in candidatePaths) {
            page = fetchMirrorPage(candidate)
            if (page != null) break
        }

        var document = page?.document
        if (document == null) {
            contentSlug(path)?.let {
                document = fetchBackendDetailDocument(it)
            }
        }

        val title = cleanTitle(
            firstNonBlank(
                document?.selectFirst("meta[property=og:title]")?.attr("content"),
                document?.selectFirst("h1")?.text(),
                document?.selectFirst(".film-name")?.text(),
                document?.title()
            ).orEmpty()
        ).ifBlank {
            titleFromContentPath(path)
        }

        if (title.isBlank()) return null

        val type = typeFromPath(path)

        var resolvedSubjectId =
            findSubjectIdFromPage(document, title)

        if (resolvedSubjectId.isNullOrBlank()) {
            val apiMatches = searchMobileApi(title)
            resolvedSubjectId = apiMatches
                .asSequence()
                .filter {
                    when (type) {
                        TvType.TvSeries -> it.type == TvType.TvSeries
                        TvType.Anime -> it.type == TvType.Anime
                        else -> it.type == TvType.Movie
                    }
                }
                .map {
                    it to searchScore(title, it.title)
                }
                .filter { it.second >= 0.50 }
                .maxByOrNull { it.second }
                ?.first
                ?.subjectId
        }

        val poster = firstUsefulUrl(
            document?.selectFirst("meta[property=og:image]")?.attr("content"),
            document?.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document?.selectFirst(
                ".film-poster img, .movie-card img, img"
            )?.let(::extractImageUrl)
        )

        val plot = firstNonBlank(
            document?.selectFirst("meta[property=og:description]")?.attr("content"),
            document?.selectFirst(
                ".description, .film-description, .description-content"
            )?.text()
        )

        val year = document?.let {
            extractYear(title, it)
        } ?: Regex("""\b(19|20)\d{2}\b""")
            .find(title)
            ?.value
            ?.toIntOrNull()

        if (type == TvType.TvSeries) {
            val episodes = resolveMobileEpisodes(
                subjectId = resolvedSubjectId,
                title = title,
                fallbackDocument = document
            )

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

        return newMovieLoadResponse(
            name = title,
            url = canonicalUrl(path),
            type = type,
            dataUrl = if (!resolvedSubjectId.isNullOrBlank()) {
                "MBMV|$resolvedSubjectId|${canonicalUrl(path)}"
            } else {
                canonicalUrl(path)
            }
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

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



private suspend fun resolveMobilePlayInfo(
        subjectId: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val found = linkedMapOf<String, MediaSource>()

        suspend fun requestEndpoint(
            endpoint: String,
            params: Map<String, String>
        ) {
            val query = params.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=" +
                    URLEncoder.encode(it.value, "UTF-8")
            }

            for (host in mobileApiHosts) {
                val response = runCatching {
                    app.get(
                        addCacheBuster(
                            "$host$endpoint?$query"
                        ),
                        headers = mobileApiHeaders(host)
                    )
                }.getOrNull() ?: continue

                if (response.code !in 200..399) continue

                parsePlayInfoResponse(
                    response.text,
                    host
                ).forEach { source ->
                    val current = found[source.url]
                    if (
                        current == null ||
                        compareMediaSources(
                            source,
                            current
                        ) > 0
                    ) {
                        found[source.url] = source
                    }
                }

                extractJsonSubtitles(
                    response.text
                ).forEach {
                    subtitleCallback(
                        newSubtitleFile(
                            it.first,
                            it.second
                        )
                    )
                }
            }
        }

        val qualities = listOf(
            1080 to "1080p",
            720 to "720p",
            480 to "480p"
        )

        // Ask explicitly for each resolution. The upstream resource system is
        // resolution-filtered, so one request is not enough to discover all
        // available quality variants.
        for ((resolution, quality) in qualities) {
            requestEndpoint(
                ApiConfig.JSON_PLAY_INFO,
                linkedMapOf(
                    "subjectId" to subjectId,
                    "se" to season.toString(),
                    "ep" to episode.toString(),
                    "quality" to quality,
                    "resolution" to resolution.toString()
                )
            )

            // Some deployments expose the underlying resource endpoint rather
            // than returning everything from play-info.
            requestEndpoint(
                "${ApiConfig.API_PREFIX}/resource",
                linkedMapOf(
                    "subjectId" to subjectId,
                    "se" to season.toString(),
                    "ep" to episode.toString(),
                    "resolution" to resolution.toString(),
                    "perPage" to "10",
                    "page" to "1"
                )
            )
        }

        // If the resolver returned nothing, try the page's known resource groups
        // and re-query each quality. This remains ordinary public GET traffic.
        if (found.isEmpty()) {
            val detail = apiGetJson(
                ApiConfig.JSON_GET,
                mapOf(
                    "subjectId" to subjectId,
                    "host" to mainUrl
                )
            )

            val resourceIds = LinkedHashSet<String>()
            collectResourceIds(
                detail,
                resourceIds
            )

            for (resourceId in resourceIds.take(8)) {
                for ((resolution, quality) in qualities) {
                    requestEndpoint(
                        ApiConfig.JSON_PLAY_INFO,
                        linkedMapOf(
                            "subjectId" to subjectId,
                            "se" to season.toString(),
                            "ep" to episode.toString(),
                            "quality" to quality,
                            "resolution" to resolution.toString(),
                            "resourceId" to resourceId
                        )
                    )
                }
            }
        }

        val playable = found.values
            .filter {
                looksLikePlayableMedia(
                    it.url
                )
            }
            .filterNot {
                isObviousTrailerOrPreview(
                    it.url
                )
            }

        if (playable.isEmpty()) return false

        val selected = playable
            .groupBy {
                when {
                    it.quality in setOf(480, 720, 1080) ->
                        it.quality

                    else ->
                        0
                }
            }
            .mapNotNull { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<MediaSource> {
                        it.declaredDurationSeconds
                    }.thenBy {
                        it.declaredSize
                    }.thenBy {
                        it.quality
                    }.thenBy {
                        it.url
                    }
                )
            }
            .sortedByDescending {
                it.quality
            }

        if (selected.isEmpty()) return false

        selected.forEach {
            emitSource(
                it,
                callback
            )
        }

        return true
    }

private fun parsePlayInfoResponse(
        body: String,
        host: String
    ): List<MediaSource> {
        val root = runCatching {
            jsonMapper.readTree(body)
        }.getOrNull() ?: return emptyList()

        val result = linkedMapOf<String, MediaSource>()

        fun parseDuration(value: String?): Long {
            if (value.isNullOrBlank()) return -1L

            value.toLongOrNull()?.let { raw ->
                return if (raw > 100_000L) raw / 1000L else raw
            }

            Regex(
                """(?i)^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$"""
            ).matchEntire(value.trim())?.let { m ->
                val h = m.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
                val min = m.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
                val sec = m.groupValues.getOrNull(3)?.toDoubleOrNull()?.toLong() ?: 0L
                return h * 3600L + min * 60L + sec
            }

            Regex(
                """^(\d{1,2}):(\d{2})(?::(\d{2}))?$"""
            ).matchEntire(value.trim())?.let { m ->
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].toLong()
                val c = m.groupValues.getOrNull(3)?.toLongOrNull()
                return if (c != null) a * 3600L + b * 60L + c else a * 60L + b
            }

            return -1L
        }

        fun parseSize(node: JsonNode): Long {
            firstJsonLong(
                node,
                "size",
                "fileSize",
                "contentLength",
                "totalSize"
            )?.let { return it }

            val value = firstJsonText(
                node,
                "size",
                "fileSize",
                "contentLength"
            ) ?: return -1L

            val m = Regex(
                """(?i)([0-9]+(?:\.[0-9]+)?)\s*(KB|MB|GB)"""
            ).find(value) ?: return -1L

            val amount = m.groupValues[1].toDoubleOrNull() ?: return -1L
            return when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "GB" -> (amount * 1024 * 1024 * 1024).toLong()
                "MB" -> (amount * 1024 * 1024).toLong()
                else -> (amount * 1024).toLong()
            }
        }

        fun walk(
            node: JsonNode?,
            inheritedQuality: String = "",
            inheritedSize: Long = -1L,
            inheritedDuration: Long = -1L,
            inheritedName: String = ""
        ) {
            if (node == null) return

            if (node.isObject) {
                val localQuality = firstJsonText(
                    node,
                    "quality",
                    "qualityName",
                    "resolution",
                    "definition",
                    "label",
                    "name"
                ).orEmpty()

                val localName = firstJsonText(
                    node,
                    "name",
                    "title",
                    "label"
                ).orEmpty()

                val localSize = maxOf(
                    inheritedSize,
                    parseSize(node)
                )

                val localDuration = maxOf(
                    inheritedDuration,
                    parseDuration(
                        firstJsonText(
                            node,
                            "duration",
                            "durationSeconds",
                            "length"
                        )
                    )
                )

                val disposition = firstJsonText(
                    node,
                    "contentDisposition",
                    "content-disposition",
                    "filename",
                    "fileName"
                ).orEmpty()

                val contextText = listOf(
                    inheritedQuality,
                    inheritedName,
                    localQuality,
                    localName,
                    disposition
                ).filter { it.isNotBlank() }.joinToString(" ")

                val url = firstJsonText(
                    node,
                    "url",
                    "playUrl",
                    "streamUrl",
                    "videoUrl",
                    "contentUrl",
                    "src"
                )

                if (
                    !url.isNullOrBlank() &&
                    looksLikePlayableMedia(url)
                ) {
                    val normalized = normalizeMediaUrl(
                        url,
                        host + "/"
                    )

                    if (normalized != null) {
                        val quality = qualityFromText(
                            "$contextText $normalized"
                        )

                        val source = MediaSource(
                            url = normalized,
                            quality = quality,
                            label = if (quality > 0) {
                                "${quality}p"
                            } else {
                                "MovieBox Stream"
                            },
                            referer = mainUrl + "/",
                            declaredSize = localSize,
                            declaredDurationSeconds = localDuration
                        )

                        val old = result[normalized]
                        if (
                            old == null ||
                            compareMediaSources(source, old) > 0
                        ) {
                            result[normalized] = source
                        }
                    }
                }

                node.fields().forEachRemaining { (_, child) ->
                    walk(
                        child,
                        inheritedQuality = contextText,
                        inheritedSize = localSize,
                        inheritedDuration = localDuration,
                        inheritedName = if (localName.isBlank()) inheritedName else localName
                    )
                }
            } else if (node.isArray) {
                node.forEach { child ->
                    walk(
                        child,
                        inheritedQuality,
                        inheritedSize,
                        inheritedDuration,
                        inheritedName
                    )
                }
            }
        }

        walk(root)
        return result.values.toList()
    }

private fun firstJsonLong(
        node: JsonNode,
        vararg names: String
    ): Long? {
        for (name in names) {
            val value = node.get(name) ?: continue
            if (value.isNumber) return value.asLong()
            if (value.isTextual) {
                val cleaned = value.asText().trim().replace(Regex("(?i)(mb|gb|kb)"), "")
                cleaned.toDoubleOrNull()?.let {
                    return when {
                        value.asText().contains("gb", true) -> (it * 1024 * 1024 * 1024).toLong()
                        value.asText().contains("mb", true) -> (it * 1024 * 1024).toLong()
                        value.asText().contains("kb", true) -> (it * 1024).toLong()
                        else -> it.toLong()
                    }
                }
            }
        }
        return null
    }

    private fun compareMediaSources(a: MediaSource, b: MediaSource): Int {
        return mediaSourceComparator().compare(a, b)
    }

    private fun mediaSourceComparator(): Comparator<MediaSource> {
        return compareByDescending<MediaSource> { it.declaredSize }
            .thenByDescending { it.declaredDurationSeconds }
            .thenByDescending { it.quality }
    }

    private fun extractJsonSubtitles(
        body: String
    ): List<Pair<String, String>> {
        val root = runCatching { jsonMapper.readTree(body) }.getOrNull() ?: return emptyList()
        val result = linkedMapOf<String, Pair<String, String>>()

        fun walk(node: JsonNode) {
            if (node.isObject) {
                val url = firstJsonText(node, "url", "src", "subtitleUrl", "captionUrl", "file")
                val looksSubtitle = node.fieldNames().asSequence().any { it.contains("sub", true) || it.contains("caption", true) }
                if (looksSubtitle && !url.isNullOrBlank() && (url.contains(".srt", true) || url.contains(".vtt", true))) {
                    val label = firstJsonText(node, "langName", "language", "lang", "label", "name") ?: "Subtitle"
                    result[url] = label to url
                }
                node.fields().forEachRemaining { walk(it.value) }
            } else if (node.isArray) {
                node.forEach(::walk)
            }
        }

        walk(root)
        return result.values.toList()
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
                .filterNot {
                    isObviousTrailerOrPreview(it.url)
                }
                .toList()

        val usableCandidates =
            if (orderedCandidates.isNotEmpty()) {
                orderedCandidates
            } else {
                mediaCandidates.values
                    .filter {
                        looksLikePlayableMedia(
                            it.url
                        )
                    }
            }

        if (usableCandidates.isEmpty()) {
            /*
             * A response can contain player configuration that points to
             * another public page but not yet contain the signed CDN URL.
             * Returning false here causes the caller to continue trying other
             * candidate routes / fresh attempts.
             */
            return false
        }

        /*
         * IMPORTANT:
         * MovieBox pages can expose several media files. Some are previews,
         * low-definition clips, or alternate resources. We do not emit all
         * candidates anymore.
         *
         * Prefer the resource with the largest declared file size. On
         * MovieBox's Nuxt/devalue payload, videoAddress resources expose
         * duration and size alongside the media URL. Duration is only a
         * secondary tie-breaker so a larger full file wins over a small
         * preview/trailer.
         *
         * We intentionally do not download media just to measure it.
         */
        val rankedCandidates =
            usableCandidates
                .sortedWith(
                    compareByDescending<MediaSource> {
                        it.declaredSize
                    }
                        .thenByDescending {
                            it.declaredDurationSeconds
                        }
                        .thenByDescending {
                            it.quality
                        }
                        .thenBy {
                            it.url
                        }
                )

        val best =
            rankedCandidates
                .firstOrNull()
                ?: return false

        emitSource(
            best,
            callback
        )

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
        // The live website is Nuxt SSR and the most reliable pairing is always
        // title + href + image from the same rendered/serialized card.
        val directItems = parseVisibleVideoItems(
            document,
            forcedType
        )
        if (directItems.isNotEmpty()) {
            return directItems
        }

        val serializedItems = parseSerializedRouteItems(
            document,
            forcedType
        )
        if (serializedItems.isNotEmpty()) {
            return serializedItems
        }

        val nuxtItems = parseNuxtListing(
            document,
            forcedType
        )
        if (nuxtItems.isNotEmpty()) {
            return nuxtItems
        }

        val selectors = listOf(
            ".movie-card",
            ".flw-item",
            ".film_list-wrap .flw-item",
            "[class*='movie-card']"
        )

        return selectors
            .asSequence()
            .flatMap { document.select(it).asSequence() }
            .distinctBy { it.outerHtml() }
            .mapNotNull { parseCard(it, forcedType) }
            .toList()
    }

private fun parseVisibleVideoItems(
        document: Document,
        forcedType: TvType?
    ): List<SiteItem> {
        val cards = document.select("div.video-item, .video-item")
        if (cards.isEmpty()) return emptyList()

        return cards.mapNotNull { card ->
            val anchor = card.selectFirst("a[href]")
            val href = anchor?.attr("href")?.trim() ?: return@mapNotNull null
            val path = pathFromUrl(absoluteUrl(href))
            if (!isLikelyContentPath(path)) return@mapNotNull null

            val title = cleanTitle(
                firstNonBlank(
                    card.selectFirst(".line-1, .film-name, h2, h3")?.text(),
                    anchor.attr("title"),
                    card.selectFirst("img")?.attr("alt")
                ).orEmpty()
            )
            if (title.isBlank()) return@mapNotNull null

            SiteItem(
                title = title,
                url = canonicalUrl(path),
                poster = firstUsefulUrl(
                    card.selectFirst("img")?.attr("data-src"),
                    card.selectFirst("img")?.attr("data-original"),
                    card.selectFirst("img")?.attr("src")
                ),
                type = forcedType ?: typeFromPath(path),
                languageRank = languageRank(title)
            )
        }
    }

    
    private fun parseSerializedRouteItems(
        document: Document,
        forcedType: TvType
    ): List<SiteItem> {
        val source = document.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")

        if (source.isBlank()) return emptyList()

        val routeRegex = when (forcedType) {
            TvType.Movie -> Regex(
                """(?i)/(?:film|movies)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            TvType.TvSeries -> Regex(
                """(?i)/tv-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            TvType.Anime -> Regex(
                """(?i)/animated-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            else -> return emptyList()
        }

        val seen = HashSet<String>()
        val result = ArrayList<SiteItem>()

        for (match in routeRegex.findAll(source)) {
            val path = normalizePath(match.value)
            if (!seen.add(path.lowercase(Locale.ROOT))) continue

            val center = match.range.first
            val start = max(0, center - 1800)
            val end = min(source.length, match.range.last + 1800)
            val window = source.substring(start, end)

            val title = extractLocalSerializedTitle(
                window,
                path
            ).ifBlank {
                cleanTitle(
                    path.substringAfterLast('/')
                        .substringBefore('?')
                        .replace(Regex("-[A-Za-z0-9]{8,}$"), "")
                        .replace('-', ' ')
                        .replace('_', ' ')
                )
            }

            if (title.isBlank()) continue

            val poster = extractBestLocalPoster(
                window
            )

            result += SiteItem(
                title = title,
                url = canonicalUrl(path),
                poster = poster,
                type = forcedType,
                languageRank = languageRank(title)
            )
        }

        return result
    }

    private fun extractLocalSerializedTitle(
        window: String,
        path: String
    ): String {
        val patterns = listOf(
            Regex(
                """(?is)<span[^>]*class=["'][^"']*line-1[^"']*["'][^>]*>(.*?)</span>"""
            ),
            Regex(
                """(?is)class=["'][^"']*line-1[^"']*["'][^>]*>(.*?)<"""
            ),
            Regex(
                """(?i)"title"\s*:\s*"([^"]{2,180})"""
            ),
            Regex(
                """(?i)"name"\s*:\s*"([^"]{2,180})"""
            )
        )

        for (pattern in patterns) {
            pattern.find(window)?.groupValues?.getOrNull(1)?.let {
                val value = cleanTitle(
                    unescapeSsrText(it)
                )
                if (
                    value.isNotBlank() &&
                    !value.contains("MovieBoxOnline", true)
                ) {
                    return value
                }
            }
        }

        return ""
    }

    private fun extractBestLocalPoster(
        window: String
    ): String? {
        data class Candidate(
            val url: String,
            val portrait: Boolean,
            val score: Int
        )

        val regex = Regex(
            """(?i)https?://[^"'<>\s\\]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'<>\s\\]*)?"""
        )

        return regex.findAll(window)
            .mapNotNull { match ->
                val url = match.value
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")

                if (
                    url.contains("logo", true) ||
                    url.contains("icon", true) ||
                    url.contains("avatar", true) ||
                    url.contains("favicon", true)
                ) {
                    return@mapNotNull null
                }

                val contextStart = max(
                    0,
                    match.range.first - 1000
                )
                val contextEnd = min(
                    window.length,
                    match.range.last + 1000
                )
                val context = window.substring(
                    contextStart,
                    contextEnd
                )

                val width = Regex(
                    """(?i)"(?:width|w)"\s*[:=]\s*(\d{2,5})"""
                ).find(context)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 0

                val height = Regex(
                    """(?i)"(?:height|h)"\s*[:=]\s*(\d{2,5})"""
                ).find(context)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 0

                val portrait =
                    height > 0 &&
                        width > 0 &&
                        height >= width * 1.05

                var score = 0
                if (portrait) score += 100
                if (width >= 300) score += 20
                if (height >= 400) score += 20
                if (url.contains("/image/", true)) score += 15
                if (url.contains("pbcdn", true)) score += 10

                Candidate(
                    url,
                    portrait,
                    score
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.portrait }
            )
            .firstOrNull()
            ?.url
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
        val routePattern = when (forcedType) {
            TvType.TvSeries -> Regex(
                """(?i)/tv-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            TvType.Anime -> Regex(
                """(?i)/animated-series/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            TvType.Movie -> Regex(
                """(?i)/(?:film|movies)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            null -> Regex(
                """(?i)/(?:film|movies|tv-series|animated-series)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._%=&\-]*)?"""
            )
            else -> return null
        }

        val titleToken = "\"${title.replace("\"", "\\\"")}\""
        var searchFrom = payloadStart
        var best: SiteItem? = null
        var bestScore = -1.0

        while (true) {
            val titleIndex = source.indexOf(
                titleToken,
                searchFrom
            )
            if (titleIndex < 0) break

            // Small locality window; never mix an unrelated nearby card.
            val start = max(payloadStart, titleIndex - 1600)
            val end = min(
                source.length,
                titleIndex + titleToken.length + 1600
            )
            val window = source.substring(
                start,
                end
            )

            val route = routePattern
                .findAll(window)
                .maxByOrNull { match ->
                    val slugTitle = cleanTitle(
                        match.value
                            .substringAfterLast('/')
                            .substringBefore('?')
                            .replace(
                                Regex("-[A-Za-z0-9]{8,}$"),
                                ""
                            )
                            .replace('-', ' ')
                            .replace('_', ' ')
                    )
                    searchScore(title, slugTitle)
                }

            if (route != null) {
                val candidatePath = route.value
                val slugTitle = cleanTitle(
                    candidatePath
                        .substringAfterLast('/')
                        .substringBefore('?')
                        .replace(
                            Regex("-[A-Za-z0-9]{8,}$"),
                            ""
                        )
                        .replace('-', ' ')
                        .replace('_', ' ')
                )

                val score = searchScore(
                    title,
                    slugTitle
                )

                val poster = extractBestLocalPoster(
                    window
                )

                if (
                    score >= 0.60 &&
                    (best == null || score > bestScore)
                ) {
                    bestScore = score
                    best = SiteItem(
                        title = title,
                        url = canonicalUrl(candidatePath),
                        poster = poster,
                        type = forcedType ?: typeFromPath(candidatePath),
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
        anchor: Int,
        end: Int
    ): String? {
        val center = when {
            anchor >= 0 -> anchor
            end >= 0 -> end
            else -> return null
        }

        val windowStart = max(
            0,
            center - 1800
        )
        val windowEnd = min(
            source.length,
            center + 1800
        )

        if (windowEnd <= windowStart) return null

        val window = source.substring(
            windowStart,
            windowEnd
        )

        return extractBestLocalPoster(
            window
        )
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

    private suspend fun resolveMobileEpisodes(
        subjectId: String?,
        title: String,
        fallbackDocument: Document?
    ): List<Episode> {
        if (!subjectId.isNullOrBlank()) {
            val seasons = fetchSeasonCounts(subjectId)
            if (seasons.isNotEmpty()) {
                return seasons.flatMap { seasonInfo ->
                    (1..seasonInfo.second).map { ep ->
                        newEpisode(
                            data = "MBEP|$subjectId|${seasonInfo.first}|$ep|$title"
                        ) {
                            name = "Episode $ep"
                            season = seasonInfo.first
                            episode = ep
                        }
                    }
                }
            }
        }

        return fallbackDocument?.let(::parseEpisodes).orEmpty()
    }

    private suspend fun fetchSeasonCounts(
        subjectId: String
    ): List<Pair<Int, Int>> {
        val encodedId = URLEncoder.encode(subjectId, "UTF-8")
        val combined = linkedMapOf<Int, Int>()

        for (host in mobileApiHosts) {
            repeat(API_RETRY_ATTEMPTS) {
                val endpoints = listOf(
                    "$host/wefeed-mobile-bff/subject-api/season-info?subjectId=$encodedId",
                    "$host/wefeed-mobile-bff/subject-api/episode-list?subjectId=$encodedId"
                )

                for (endpoint in endpoints) {
                    val response = runCatching {
                        app.get(
                            addCacheBuster(endpoint),
                            headers = mobileApiHeaders(host)
                        )
                    }.getOrNull() ?: continue

                    if (response.code !in 200..399) continue

                    parseSeasonCounts(response.text).forEach { (season, count) ->
                        if (count > 0) {
                            combined[season] = maxOf(
                                combined[season] ?: 0,
                                count
                            )
                        }
                    }
                }

                if (combined.isNotEmpty()) {
                    return@repeat
                }
            }
        }

        return combined.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

private fun parseSeasonCounts(
        body: String
    ): List<Pair<Int, Int>> {
        val root = runCatching {
            jsonMapper.readTree(body)
        }.getOrNull() ?: return emptyList()

        val result = linkedMapOf<Int, Int>()

        fun update(season: Int?, count: Int?) {
            if (season == null || season <= 0) return
            if (count == null || count <= 0) return
            val safe = count.coerceAtMost(MAX_EPISODES_PER_SEASON)
            result[season] = maxOf(
                result[season] ?: 0,
                safe
            )
        }

        fun walk(node: JsonNode, inheritedSeason: Int = 1) {
            if (node.isObject) {
                val season = firstJsonLong(
                    node,
                    "season",
                    "seasonNumber",
                    "se",
                    "seasonNo"
                )?.toInt() ?: inheritedSeason

                val count = firstJsonLong(
                    node,
                    "episodesAvailable",
                    "totalEpisode",
                    "totalEpisodes",
                    "episodeCount",
                    "maxEp",
                    "allEp",
                    "total",
                    "count"
                )?.toInt()

                update(season, count)

                listOf(
                    "episodes",
                    "episodeList",
                    "epList",
                    "episodeItems"
                ).forEach { key ->
                    val arr = node.get(key)
                    if (
                        arr != null &&
                        arr.isArray
                    ) {
                        val numbers = arr.mapNotNull { child ->
                            firstJsonLong(
                                child,
                                "episode",
                                "episodeNumber",
                                "ep",
                                "epNum"
                            )?.toInt()
                        }
                        update(
                            season,
                            if (numbers.isNotEmpty()) {
                                numbers.maxOrNull()
                            } else {
                                arr.size()
                            }
                        )
                    }
                }

                val resolutions = node.get("resolutions")
                if (
                    resolutions != null &&
                    resolutions.isArray
                ) {
                    resolutions.forEach { resolution ->
                        update(
                            season,
                            firstJsonLong(
                                resolution,
                                "epNum",
                                "episodesAvailable",
                                "maxEp",
                                "episodeCount"
                            )?.toInt()
                        )
                    }
                }

                node.fields().forEachRemaining { (_, child) ->
                    walk(
                        child,
                        season
                    )
                }
            } else if (node.isArray) {
                node.forEach {
                    walk(
                        it,
                        inheritedSeason
                    )
                }
            }
        }

        walk(root)

        return result.entries
            .sortedBy { it.key }
            .map { it.key to it.value }
    }

private fun findSubjectIdFromPage(
        document: Document?,
        title: String
    ): String? {
        if (document == null) return null

        val html = document.html()
        val normalizedTitle = normalizeSearch(title)

        fun validId(value: String?): String? {
            val id = value?.trim() ?: return null
            return id.takeIf { it.matches(Regex("""\d{12,22}""")) }
        }

        // 1) Prefer a subjectId that appears in the same small context as the
        // exact page title. This avoids accidentally taking a cast/staff ID.
        val titleCandidates = Regex(
            """(?is)(.{0,1800}${Regex.escape(title)}.{0,1800})"""
        ).findAll(html).toList()

        titleCandidates.forEach { match ->
            val context = match.value
            Regex("""(?i)"subjectId"\s*:\s*"?(\d{12,22})""")
                .find(context)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::validId)
                ?.let { return it }

            Regex("""(?i)(?:[?&]id=|["']id["']\s*:\s*["']?)(\d{12,22})""")
                .find(context)
                ?.groupValues
                ?.getOrNull(1)
                ?.let(::validId)
                ?.let { return it }
        }

        // 2) The player route on MovieBox exposes the subject id in its query
        // string. Prefer an id attached to a /play/ URL over unrelated numbers.
        Regex(
            """(?i)/play/[A-Za-z0-9._~-]+[^"'<>\s]{0,500}?[?&]id=(\d{12,22})"""
        ).findAll(html).forEach { match ->
            validId(match.groupValues.getOrNull(1))?.let { return it }
        }

        // 3) Then look for a subjectId adjacent to a detailPath/route.
        Regex(
            """(?is)"subjectId"\s*:\s*"?(\d{12,22})"?[^}]{0,1500}"detailPath"\s*:\s*"([^"]+)"""
        ).findAll(html).forEach { match ->
            val route = match.groupValues.getOrNull(2).orEmpty()
            if (
                route.contains(normalizedTitle.replace(" ", "-"), true) ||
                route.contains(normalizedTitle.replace(" ", "_"), true)
            ) {
                validId(match.groupValues.getOrNull(1))?.let { return it }
            }
        }

        return null
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

    private suspend fun repairPosters(
        items: List<SiteItem>
    ): List<SiteItem> {
        if (items.size <= 1) return items

        val posterCounts = items
            .mapNotNull { it.poster?.takeIf { p -> p.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        val repaired = items.toMutableList()

        for (index in items.indices) {
            val item = items[index]
            val poster = item.poster

            val brokenOrRepeated =
                poster.isNullOrBlank() ||
                    (posterCounts[poster] ?: 0) > 1

            if (!brokenOrRepeated) continue

            val candidates = runCatching {
                searchMobileApi(item.title)
            }.getOrDefault(emptyList())

            val best = candidates
                .asSequence()
                .filter { candidate ->
                    candidate.poster?.isNotBlank() == true &&
                        candidate.type == item.type
                }
                .map {
                    it to searchScore(
                        item.title,
                        it.title
                    )
                }
                .filter { it.second >= 0.70 }
                .maxByOrNull { it.second }
                ?.first

            if (best?.poster.isNullOrBlank()) continue

            repaired[index] = item.copy(
                poster = best?.poster
            )
        }

        return repaired
    }

private fun apiItemFromNode(
        node: JsonNode,
        forcedType: TvType? = null
    ): SiteItem? {
        val title = cleanTitle(
            nodeText(
                node,
                "title",
                "name"
            ).orEmpty()
        )
        if (title.isBlank()) return null

        val detailPath = nodeText(
            node,
            "detailPath",
            "detailUrl",
            "url"
        ).orEmpty()

        val type = forcedType ?: when {
            detailPath.contains(
                "/animated-series/",
                true
            ) -> TvType.Anime

            detailPath.contains(
                "/tv-series/",
                true
            ) -> TvType.TvSeries

            nodeText(
                node,
                "subjectType"
            ).equals("2", true) ||
                nodeText(
                    node,
                    "type"
                ).equals("tv", true) ||
                nodeText(
                    node,
                    "type"
                ).equals("series", true) ->
                TvType.TvSeries

            else -> TvType.Movie
        }

        val id = nodeText(
            node,
            "subjectId",
            "id"
        )

        val slug = when {
            detailPath.startsWith(
                "http",
                true
            ) -> pathFromUrl(detailPath)

            detailPath.startsWith("/") ->
                detailPath

            !id.isNullOrBlank() ->
                when (type) {
                    TvType.TvSeries ->
                        "/tv-series/${detailPath.substringAfterLast('/').ifBlank { slugifyForUrl(title) + "-" + id }}"

                    TvType.Anime ->
                        "/animated-series/${detailPath.substringAfterLast('/').ifBlank { slugifyForUrl(title) + "-" + id }}"

                    else ->
                        "/film/${detailPath.substringAfterLast('/').ifBlank { slugifyForUrl(title) + "-" + id }}"
                }

            else -> null
        } ?: return null

        if (!isLikelyContentPath(slug)) return null

        val poster = firstUsefulUrl(
            firstUrlFromJsonNode(
                node.get("poster")
            ),
            firstUrlFromJsonNode(
                node.get("cover")
            ),
            firstUrlFromJsonNode(
                node.get("image")
            ),
            firstUrlFromJsonNode(
                node.get("thumbnail")
            ),
            nodeText(
                node,
                "poster",
                "cover",
                "image",
                "thumbnail"
            )
        )

        return SiteItem(
            title = title,
            url = canonicalUrl(slug),
            poster = poster,
            type = type,
            languageRank = languageRank(title),
            subjectId = id
        )
    }

}
