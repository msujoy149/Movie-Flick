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
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to FEATURED,
        "$mainUrl" to RECENTLY,
        "$mainUrl/film" to "Movies",
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
        const val HOME_FEATURED_LIMIT = 1
        const val SEARCH_NATIVE_PAGES = 2
        const val SEARCH_RESULT_LIMIT = 50
        const val SITEMAP_RESULT_LIMIT = 80
        const val SITEMAP_MAX_BYTES = 2_000_000

        const val FEATURED = "Featured"
        const val RECENTLY = "Recently Uploads"
        const val TV_SHOW = "Tv Show"
        const val ANIME = "Anime"
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

        if (section == FEATURED || section == RECENTLY) {
            val result = fetchMirrorPage("/") ?: return newHomePageResponse(
                data = request,
                list = emptyList(),
                hasNext = false
            )

            val allItems = parseNuxtListing(
                result.document,
                null
            )

            val ranked = sortHomeItems(allItems)

            val limit = if (section == FEATURED) {
                HOME_FEATURED_LIMIT
            } else if (currentPage == 1) {
                HOME_INITIAL_LIMIT
            } else {
                HOME_PAGE_LIMIT
            }

            val start = if (section == FEATURED) 0 else if (currentPage == 1) 0 else (HOME_INITIAL_LIMIT + (currentPage - 2) * HOME_PAGE_LIMIT)
            val pageItems = if (section == FEATURED) {
                ranked.take(limit)
            } else {
                ranked.drop(start).take(limit)
            }

            return newHomePageResponse(
                data = request,
                list = pageItems.map { it.toSearchResponse() },
                hasNext = if (section == FEATURED) false else ranked.size > start + pageItems.size
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

            val items = parseListing(
                result.document,
                forcedType
            )

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
        return items.sortedBy { it.languageRank }
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

        val path =
            pathFromUrl(url)

        val page =
            fetchMirrorPage(path)
                ?: return null

        val document =
            page.document

        val title =
            cleanTitle(
                firstNonBlank(
                    document.selectFirst(
                        "meta[property=og:title]"
                    )?.attr("content"),

                    document.selectFirst(
                        "h1"
                    )?.text(),

                    document.selectFirst(
                        ".film-name"
                    )?.text(),

                    document.title()
                ).orEmpty()
            )

        if (title.isBlank()) {
            return null
        }

        val poster =
            firstUsefulUrl(
                document.selectFirst(
                    "meta[property=og:image]"
                )?.attr("content"),

                document.selectFirst(
                    "meta[name=twitter:image]"
                )?.attr("content"),

                document.selectFirst(
                    ".film-poster img, " +
                        ".movie-card img, " +
                        "img"
                )?.let(
                    ::extractImageUrl
                )
            )

        val plot =
            firstNonBlank(
                document.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content"),

                document.selectFirst(
                    ".description, " +
                        ".film-description, " +
                        ".description-content"
                )?.text()
            )

        val year =
            extractYear(
                title,
                document
            )

        val type =
            typeFromPath(path)

        if (
            type == TvType.Movie ||
            type == TvType.Anime
        ) {

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

        val episodes =
            parseEpisodes(
                document = document
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

        val separator = rawData.indexOf("||")
        val pageUrl = if (separator >= 0) rawData.substring(0, separator) else rawData
        val pagePath = pathFromUrl(pageUrl)
        if (pagePath.isBlank()) return false

        val freshHeaders = browserHeaders(mainUrl + pagePath) +
            ("X-Requested-With" to "XMLHttpRequest")

        val detailResponse = runCatching {
            app.get(
                canonicalUrl(pagePath),
                headers = freshHeaders
            )
        }.getOrNull() ?: return false

        val detailHtml = detailResponse.text
        val detailDocument = detailResponse.document

        val candidatePlayUrls = linkedSetOf<String>()
        candidatePlayUrls += extractJsonLdUrls(detailHtml)

        val directCandidates = extractMediaSources(
            document = detailDocument,
            pageUrl = canonicalUrl(pagePath),
            html = detailHtml
        )

        var emitted = false

        // Prefer fresh direct media from the current page.
        for (source in directCandidates) {
            if (!looksLikePlayableMedia(source.url)) continue
            emitSource(source, callback)
            emitted = true
        }

        // Then follow the site's own freshly generated /play/... URL.
        for (playUrl in candidatePlayUrls) {
            val watch = runCatching {
                app.get(
                    playUrl,
                    headers = browserHeaders(pageUrl) + ("Referer" to canonicalUrl(pagePath))
                )
            }.getOrNull() ?: continue

            val sources = extractMediaSources(
                document = watch.document,
                pageUrl = playUrl,
                html = watch.text
            )

            for (source in sources) {
                if (!looksLikePlayableMedia(source.url)) continue
                emitSource(source, callback)
                emitted = true
            }

            extractSubtitles(watch.document).forEach { subtitle ->
                subtitleCallback(newSubtitleFile(subtitle.first, subtitle.second))
            }
        }

        // Final fallback: some MovieBox pages expose a public `contentUrl`
        // in JSON-LD. Use the freshly fetched value only when the player page
        // did not expose a better playable source.
        if (!emitted) {
            extractJsonLdMedia(
                html = detailHtml,
                pageUrl = canonicalUrl(pagePath)
            ).forEach { source ->
                if (!looksLikePlayableMedia(source.url)) return@forEach
                emitSource(source, callback)
                emitted = true
            }
        }

        if (!emitted) {
            extractSubtitles(detailDocument).forEach { subtitle ->
                subtitleCallback(newSubtitleFile(subtitle.first, subtitle.second))
            }
        }

        return emitted
    }

    private fun emitSource(
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
            .replace("\/", "/")
            .replace("\u0026", "&")
            .replace("&amp;", "&")

        Regex(
            """https?://[^"'<>\s]+(?:\.m3u8|\.mp4|\.m4v|\.webm|\.mov|\.mkv|\.ts)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(decoded).forEach { add(it.value, "MovieBox Direct") }

        // Serialized SSR state can carry the current source URL.
        Regex(
            """(?i)\"(?:sourceUrl|sniffUrl|playUrl|streamUrl|videoUrl)\"\s*:\s*\"((?:https?:)?//[^\"]+)\""""
        ).findAll(decoded).forEach { add(it.groupValues[1], "MovieBox State") }

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
                """(?i)/(?:film|tv-series|animated-series)/[A-Za-z0-9._~%\-]+(?:\?[A-Za-z0-9._~%=&\-]*)?"""
            ).find(window)

            val candidatePath = routeMatch?.value
                ?: findBestDetailPath(window, title)?.third?.let { "/film/$it" }

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

        document.select(
            "a[href*='episode'], " +
                ".episode a[href], " +
                ".episodes a[href], " +
                ".ep-item a[href], " +
                "[class*='episode'] a[href]"
        ).forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank()) return@forEach

            val url = absoluteUrl(href)
            val label = cleanTitle(
                firstNonBlank(
                    anchor.text(),
                    anchor.attr("title")
                ).orEmpty()
            )
            val episodeNumber = Regex(
                """(?i)(?:episode|ep|e)[\s._-]*(\d+)"""
            ).find("$label $url")?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNumber = Regex(
                """(?i)(?:season|s)[\s._-]*(\d+)"""
            ).find("$label $url")?.groupValues?.getOrNull(1)?.toIntOrNull()

            found.putIfAbsent(
                "$url|${seasonNumber ?: 1}|${episodeNumber ?: found.size + 1}",
                EpisodeInfo(
                    url,
                    label.ifBlank { "Episode ${episodeNumber ?: found.size + 1}" },
                    seasonNumber,
                    episodeNumber
                )
            )
        }

        // Nuxt SSR often serializes the episode list without rendering <a> tags.
        // Capture explicit /play/... URLs together with nearby episode numbers.
        val html = document.html()
        val playRegex = Regex(
            """(?i)(/play/[A-Za-z0-9._-]+(?:\?[^\"'<>\s]*)?)"""
        )
        playRegex.findAll(html).forEach { match ->
            val url = absoluteUrl(match.groupValues[1])
            val before = html.substring(max(0, match.range.first - 600), match.range.first)
            val after = html.substring(match.range.last + 1, min(html.length, match.range.last + 601))
            val context = before + after
            val episodeNumber = Regex("""(?i)(?:episode|ep|e)[\s._-]*(\d+)""").find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNumber = Regex("""(?i)(?:season|s)[\s._-]*(\d+)""").find(context)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val title = "Episode ${episodeNumber ?: found.size + 1}"
            found.putIfAbsent(
                "$url|${seasonNumber ?: 1}|${episodeNumber ?: found.size + 1}",
                EpisodeInfo(url, title, seasonNumber, episodeNumber)
            )
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

        return try {

            val response =
                app.get(
                    domain + normalized,
                    headers =
                        browserHeaders(
                            domain + "/"
                        )
                )

            if (
                response.code !in
                    200..399
            ) {
                null
            } else {
                MirrorPage(
                    domain = domain,
                    path = normalized,
                    document =
                        response.document
                )
            }

        } catch (_: Throwable) {
            null
        }
    }

    /*
     * ------------------------------------------------------------
     * HELPERS
     * ------------------------------------------------------------
     */
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
                "/tv-series/"
            ) ||
            value.startsWith(
                "/animated-series/"
            )
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
