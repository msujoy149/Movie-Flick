package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class OnlineMovies : MainAPI() {

    override var mainUrl = "https://111.90.159.132"
    override var name = "Online Movies"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /*
     * EXACT MAIN CATEGORY ORDER
     *
     * 1. Latest Movies
     * 2. Movies
     * 3. TV Show
     */
    override val mainPage = mainPageOf(
        "$mainUrl/year/2026/" to "Latest Movies",
        "onlinemovies://movies" to "Movies",
        "$mainUrl/tv-show/" to "TV Show"
    )

    /*
     * Movies does not have one direct "all movies" page.
     * The Movies section is therefore built by merging these
     * genre archives.
     */
    private val movieGenreUrls = listOf(
        "$mainUrl/drama/",
        "$mainUrl/action/",
        "$mainUrl/comedy/",
        "$mainUrl/romance/",
        "$mainUrl/thriller/",
        "$mainUrl/crime/",
        "$mainUrl/horror/",
        "$mainUrl/adventure-movies/",
        "$mainUrl/science-fiction/",
        "$mainUrl/mystery/",
        "$mainUrl/fantasy/"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36",

        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",

        "Accept-Language" to
            "en-US,en;q=0.9",

        "Cache-Control" to
            "no-cache",

        "Pragma" to
            "no-cache"
    )

    private val mediaExtensions = setOf(
        ".m3u8",
        ".mpd",
        ".mp4",
        ".mkv",
        ".webm",
        ".mov",
        ".m4v",
        ".avi",
        ".flv",
        ".ts"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean
    )

    /*
     * ------------------------------------------------------------
     * MAIN PAGE
     * ------------------------------------------------------------
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage = page.coerceAtLeast(1)

        return when (request.data) {

            /*
             * ----------------------------------------------------
             * MOVIES
             * ----------------------------------------------------
             *
             * Merge every supplied genre into one CloudStream row.
             */
            "onlinemovies://movies" -> {

                val merged =
                    linkedMapOf<String, SiteItem>()

                /*
                 * Each genre is fetched for the requested page.
                 * Duplicate movie URLs are removed automatically.
                 */
                movieGenreUrls.forEach { genreUrl ->

                    val pageUrl =
                        buildPageUrl(
                            genreUrl,
                            currentPage
                        )

                    val document =
                        getDocument(pageUrl)
                            ?: return@forEach

                    parseItems(
                        document = document,
                        sourceUrl = pageUrl,
                        forceSeries = false
                    ).forEach { item ->

                        merged.putIfAbsent(
                            item.url,
                            item
                        )
                    }
                }

                val responses =
                    merged.values
                        .take(30)
                        .map {
                            toSearchResponse(it)
                        }

                newHomePageResponse(
                    request,
                    responses,
                    /*
                     * Genre archives normally expose pagination.
                     * Keep paging available while there is a
                     * reasonable next-page window.
                     */
                    currentPage < MAX_COMBINED_MOVIE_PAGES
                )
            }

            /*
             * ----------------------------------------------------
             * NORMAL CATEGORY
             * ----------------------------------------------------
             */
            else -> {

                val url =
                    buildPageUrl(
                        request.data,
                        currentPage
                    )

                val document =
                    getDocument(url)
                        ?: return newHomePageResponse(
                            request,
                            emptyList(),
                            false
                        )

                val isTv =
                    request.name.equals(
                        "TV Show",
                        ignoreCase = true
                    )

                val items =
                    parseItems(
                        document = document,
                        sourceUrl = url,
                        forceSeries = isTv
                    )

                newHomePageResponse(
                    request,
                    items
                        .take(30)
                        .map {
                            toSearchResponse(it)
                        },
                    hasNextPage(document)
                )
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * SEARCH
     * ------------------------------------------------------------
     *
     * Strategy:
     *
     * 1. WordPress native search
     * 2. Normalized query
     * 3. Space / dash / underscore variants
     * 4. Genre fallback
     * 5. TV fallback
     * 6. Fuzzy ranking
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val original =
            query.trim()

        if (original.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val normalized =
            normalizeSearchText(original)

        val compact =
            compactSearchText(original)

        val variants =
            linkedSetOf<String>().apply {

                add(original)

                if (normalized.isNotBlank()) {
                    add(normalized)
                }

                if (compact.isNotBlank()) {
                    add(compact)
                }

                add(
                    original
                        .replace(
                            ":",
                            " "
                        )
                        .replace(
                            "-",
                            " "
                        )
                        .replace(
                            "_",
                            " "
                        )
                )

                add(
                    original.replace(
                        Regex("\\s+"),
                        " "
                    )
                )
            }.filter {
                it.isNotBlank()
            }

        val nativeResults =
            linkedMapOf<String, SiteItem>()

        /*
         * WordPress uses ?s= for its normal search action.
         * This is also exposed in the website's schema.
         */
        for (variant in variants) {

            val encoded =
                URLEncoder.encode(
                    variant,
                    StandardCharsets.UTF_8.toString()
                )

            val candidates =
                listOf(
                    "$mainUrl/?s=$encoded",
                    "$mainUrl/?search=$encoded",
                    "$mainUrl/search/$encoded/"
                ).distinct()

            for (candidate in candidates) {

                val document =
                    getDocument(candidate)
                        ?: continue

                parseItems(
                    document = document,
                    sourceUrl = candidate
                ).forEach { item ->

                    nativeResults.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (nativeResults.size >= 80) {
                    break
                }
            }

            if (nativeResults.size >= 80) {
                break
            }
        }

        /*
         * Fallback scan.
         *
         * This is intentionally separate from native search so a
         * weak WordPress search result cannot hide a valid movie.
         */
        val fallback =
            linkedMapOf<String, SiteItem>()

        nativeResults.values.forEach { item ->
            fallback.putIfAbsent(
                item.url,
                item
            )
        }

        /*
         * Search the configured genre archives.
         */
        if (fallback.size < 20) {

            movieGenreUrls.forEach { genreUrl ->

                val document =
                    getDocument(
                        buildPageUrl(
                            genreUrl,
                            1
                        )
                    ) ?: return@forEach

                parseItems(
                    document = document,
                    sourceUrl = genreUrl
                ).forEach { item ->

                    fallback.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (fallback.size >= 100) {
                    return@forEach
                }
            }
        }

        /*
         * Search TV archive as a final fallback.
         */
        if (fallback.size < 100) {

            val tvUrl =
                buildPageUrl(
                    "$mainUrl/tv-show/",
                    1
                )

            getDocument(tvUrl)?.let { document ->

                parseItems(
                    document = document,
                    sourceUrl = tvUrl,
                    forceSeries = true
                ).forEach { item ->

                    fallback.putIfAbsent(
                        item.url,
                        item.copy(
                            isSeries = true
                        )
                    )
                }
            }
        }

        /*
         * Rank every candidate by title similarity.
         */
        val ranked =
            fallback.values
                .map { item ->

                    item to searchScore(
                        normalized,
                        normalizeSearchText(
                            item.title
                        )
                    )
                }
                .filter { pair ->
                    pair.second >= SEARCH_MIN_SCORE
                }
                .sortedWith(
                    compareByDescending<Pair<SiteItem, Double>> {
                        it.second
                    }.thenBy {
                        it.first.title
                    }
                )
                .map {
                    it.first
                }

        val pageSize = 30

        val currentPage =
            page.coerceAtLeast(1)

        val start =
            (currentPage - 1) * pageSize

        val pageResults =
            ranked
                .drop(start)
                .take(pageSize)

        return newSearchResponseList(
            pageResults.map {
                toSearchResponse(it)
            },
            start + pageSize < ranked.size
        )
    }

    /*
     * ------------------------------------------------------------
     * LOAD DETAIL PAGE
     * ------------------------------------------------------------
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        val cleanUrl =
            cleanUrl(url)

        if (cleanUrl.isBlank()) {

            return newMovieLoadResponse(
                "Online Movies",
                mainUrl,
                TvType.Movie,
                mainUrl
            )
        }

        /*
         * Direct media URL support.
         */
        if (isMediaUrl(cleanUrl)) {

            return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                TvType.Movie,
                cleanUrl
            )
        }

        val document =
            getDocument(cleanUrl)

        if (document == null) {

            return if (
                looksLikeTvUrl(cleanUrl)
            ) {

                newTvSeriesLoadResponse(
                    titleFromUrl(cleanUrl),
                    cleanUrl,
                    TvType.TvSeries,
                    emptyList()
                )

            } else {

                newMovieLoadResponse(
                    titleFromUrl(cleanUrl),
                    cleanUrl,
                    TvType.Movie,
                    cleanUrl
                )
            }
        }

        val title =
            extractDetailTitle(document)
                .ifBlank {
                    titleFromUrl(cleanUrl)
                }

        val poster =
            extractPoster(
                document,
                cleanUrl
            )

        val plot =
            extractPlot(
                document
            )

        val year =
            extractYear(
                document,
                title
            )

        /*
         * IMPORTANT:
         *
         * We do NOT classify a page as TV simply because its
         * text contains "TV Show".
         *
         * URL + actual episode container are stronger indicators.
         */
        val episodeLinks =
            parseEpisodeLinks(
                document,
                cleanUrl
            )

        val isTv =
            looksLikeTvUrl(cleanUrl) ||
            episodeLinks.isNotEmpty()

        if (isTv) {

            val episodes =
                episodeLinks.mapIndexed {
                    index,
                    info ->

                    Episode(
                        data = info.url,
                        name = info.name,
                        season = info.season,
                        episode =
                            info.episode
                                ?: (index + 1),
                        posterUrl = poster,
                        description = null
                    )
                }

            return newTvSeriesLoadResponse(
                title,
                cleanUrl,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        /*
         * Movie:
         *
         * Keep the detail URL as the data passed to loadLinks().
         * loadLinks() will fetch the current page and discover
         * the current media source at Play time.
         */
        return newMovieLoadResponse(
            title,
            cleanUrl,
            TvType.Movie,
            cleanUrl
        ) {

            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /*
     * ------------------------------------------------------------
     * LOAD LINKS / PLAYER
     * ------------------------------------------------------------
     *
     * The website can expose:
     *
     * - MP4
     * - M3U8
     * - MPD
     * - multiple <source> elements
     * - data-src / data-video / data-file style attributes
     *
     * Every discovered source is returned to CloudStream.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val input =
            cleanUrl(data)

        if (input.isBlank()) {
            return false
        }

        /*
         * Direct media URL:
         * this is useful for future changes where an episode
         * itself may already carry the final media URL.
         */
        if (isMediaUrl(input)) {

            emitMediaLink(
                mediaUrl = input,
                referer = mainUrl,
                callback = callback
            )

            return true
        }

        val response =
            runCatching {

                app.get(
                    input,
                    headers = playbackPageHeaders(
                        input
                    )
                )

            }.getOrNull()
                ?: return false

        val document =
            response.document

        val html =
            response.text

        /*
         * --------------------------------------------------------
         * PRIMARY SOURCE DISCOVERY
         * --------------------------------------------------------
         */
        val sources =
            extractMediaUrls(
                document = document,
                html = html,
                baseUrl = input
            ).distinct()

        if (sources.isNotEmpty()) {

            sources.forEach { source ->

                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }

            return true
        }

        /*
         * --------------------------------------------------------
         * SECONDARY: VIDEO/IFRAME / EMBED SOURCES
         * --------------------------------------------------------
         *
         * If the site changes to an embedded player, try the
         * extractor system already available inside CloudStream.
         */
        val iframes =
            document.select(
                "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-lazy-src]"
            )
                .mapNotNull { frame ->

                    val raw =
                        frame.attr("src")
                            .ifBlank {
                                frame.attr(
                                    "data-src"
                                )
                            }
                            .ifBlank {
                                frame.attr(
                                    "data-lazy-src"
                                )
                            }
                            .trim()

                    if (raw.isBlank()) {
                        null
                    } else {
                        absoluteUrl(
                            raw,
                            input
                        )
                    }
                }
                .distinct()

        for (iframe in iframes) {

            val loaded =
                runCatching {

                    loadExtractor(
                        iframe,
                        input,
                        subtitleCallback,
                        callback
                    )

                }.getOrDefault(false)

            if (loaded) {
                return true
            }
        }

        return false
    }

    /*
     * ------------------------------------------------------------
     * CONVERT SITE ITEM -> CLOUDSTREAM RESPONSE
     * ------------------------------------------------------------
     */
    private fun toSearchResponse(
        item: SiteItem
    ): SearchResponse {

        return if (item.isSeries) {

            newTvSeriesSearchResponse(
                name = item.title,
                url = item.url,
                type = TvType.TvSeries
            ) {

                posterUrl =
                    item.poster
            }

        } else {

            newMovieSearchResponse(
                name = item.title,
                url = item.url,
                type = TvType.Movie
            ) {

                posterUrl =
                    item.poster
            }
        }
    }

    /*
     * ------------------------------------------------------------
     * HTTP HELPERS
     * ------------------------------------------------------------
     */
    private suspend fun getDocument(
        url: String
    ): Document? {

        if (url.isBlank()) {
            return null
        }

        return runCatching {

            app.get(
                url,
                headers = browserHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            ).document

        }.getOrNull()
    }

    private fun playbackPageHeaders(
        referer: String
    ): Map<String, String> {

        return browserHeaders + mapOf(
            "Referer" to referer,
            "Accept" to "*/*"
        )
    }

    /*
     * ------------------------------------------------------------
     * PARSE ARCHIVE CARDS
     * ------------------------------------------------------------
     *
     * The supplied source shows:
     *
     * <article ... itemscope="Movie">
     *   <div class="content-thumbnail">
     *      <a href="...">
     *          <img ...>
     *      </a>
     *   </div>
     *   <p class="entry-title">
     *      <a ...>Alpha (2026)</a>
     *   </p>
     * </article>
     */
    private fun parseItems(
        document: Document,
        sourceUrl: String,
        forceSeries: Boolean = false
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        val articles =
            document.select(
                "article"
            )

        articles.forEach { article ->

            val anchor =
                article.selectFirst(
                    "p.entry-title a[href], " +
                    ".entry-title a[href], " +
                    "a[href][rel='bookmark']"
                )
                    ?: article.selectFirst(
                        "a[href]"
                    )
                    ?: return@forEach

            val href =
                anchor.attr("href")
                    .trim()

            if (href.isBlank()) {
                return@forEach
            }

            val absolute =
                absoluteUrl(
                    href,
                    sourceUrl
                )

            if (!isContentUrl(absolute)) {
                return@forEach
            }

            val title =
                cleanArchiveTitle(
                    anchor.text()
                        .trim()
                        .ifBlank {
                            anchor.attr(
                                "title"
                            ).trim()
                        }
                )

            if (title.isBlank()) {
                return@forEach
            }

            val image =
                article.selectFirst(
                    "img[src], " +
                    "img[data-src], " +
                    "img[data-lazy-src], " +
                    "img[data-original]"
                )

            val poster =
                image?.let { img ->

                    val raw =
                        img.attr("data-src")
                            .ifBlank {
                                img.attr(
                                    "data-lazy-src"
                                )
                            }
                            .ifBlank {
                                img.attr(
                                    "data-original"
                                )
                            }
                            .ifBlank {
                                img.attr(
                                    "src"
                                )
                            }
                            .trim()

                    raw.takeIf {
                        it.isNotBlank()
                    }?.let {
                        absoluteUrl(
                            it,
                            sourceUrl
                        )
                    }
                }

            val lowerUrl =
                absolute.lowercase(
                    Locale.ROOT
                )

            val series =
                forceSeries ||
                lowerUrl.contains(
                    "/tv/",
                    true
                ) ||
                lowerUrl.contains(
                    "/tv-show/",
                    true
                ) ||
                lowerUrl.contains(
                    "/eps/",
                    true
                )

            result.putIfAbsent(
                absolute,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    isSeries = series
                )
            )
        }

        /*
         * Some WordPress pages may have cards outside <article>.
         * Use a second fallback selector.
         */
        if (result.isEmpty()) {

            document.select(
                ".gmr-box-content, " +
                ".item-article, " +
                ".content-thumbnail"
            ).forEach { element ->

                val anchor =
                    element.selectFirst(
                        "a[href]"
                    )
                        ?: return@forEach

                val href =
                    anchor.attr("href")
                        .trim()

                if (href.isBlank()) {
                    return@forEach
                }

                val absolute =
                    absoluteUrl(
                        href,
                        sourceUrl
                    )

                val title =
                    cleanArchiveTitle(
                        anchor.attr(
                            "title"
                        ).trim()
                            .ifBlank {
                                anchor.text()
                                    .trim()
                            }
                    )

                if (
                    title.isBlank() ||
                    !isContentUrl(
                        absolute
                    )
                ) {
                    return@forEach
                }

                val image =
                    element.selectFirst(
                        "img[src], " +
                        "img[data-src], " +
                        "img[data-lazy-src]"
                    )

                val poster =
                    image?.let { img ->

                        val raw =
                            img.attr("data-src")
                                .ifBlank {
                                    img.attr(
                                        "data-lazy-src"
                                    )
                                }
                                .ifBlank {
                                    img.attr(
                                        "src"
                                    )
                                }

                        raw.takeIf {
                            it.isNotBlank()
                        }?.let {
                            absoluteUrl(
                                it,
                                sourceUrl
                            )
                        }
                    }

                val series =
                    forceSeries ||
                    absolute.contains(
                        "/tv/",
                        true
                    ) ||
                    absolute.contains(
                        "/eps/",
                        true
                    )

                result.putIfAbsent(
                    absolute,
                    SiteItem(
                        title = title,
                        url = absolute,
                        poster = poster,
                        isSeries = series
                    )
                )
            }
        }

        return result.values.toList()
    }

    /*
     * ------------------------------------------------------------
     * TV EPISODES
     * ------------------------------------------------------------
     *
     * The supplied TV detail source has:
     *
     * <div class="gmr-listseries">
     *     <a ...>S1 Eps1</a>
     *     <a ...>S1 Eps2</a>
     *     ...
     * </div>
     */
    private data class EpisodeInfo(
        val url: String,
        val name: String?,
        val season: Int?,
        val episode: Int?
    )

    private fun parseEpisodeLinks(
        document: Document,
        baseUrl: String
    ): List<EpisodeInfo> {

        val result =
            linkedMapOf<String, EpisodeInfo>()

        document
            .select(
                ".gmr-listseries a[href], " +
                ".gmr-listseries .button[href]"
            )
            .forEachIndexed { index, anchor ->

                val href =
                    anchor.attr("href")
                        .trim()

                if (href.isBlank()) {
                    return@forEachIndexed
                }

                val absolute =
                    absoluteUrl(
                        href,
                        baseUrl
                    )

                if (
                    !absolute.contains(
                        "/eps/",
                        ignoreCase = true
                    )
                ) {
                    return@forEachIndexed
                }

                val visibleName =
                    anchor.text()
                        .trim()

                val titleAttr =
                    anchor.attr(
                        "title"
                    ).trim()

                val parsed =
                    parseSeasonEpisode(
                        visibleName
                            .ifBlank {
                                titleAttr
                            }
                    )

                val season =
                    parsed?.first

                val episode =
                    parsed?.second
                        ?: (index + 1)

                val episodeName =
                    buildEpisodeName(
                        season,
                        episode
                    )

                result.putIfAbsent(
                    absolute,
                    EpisodeInfo(
                        url = absolute,
                        name = episodeName,
                        season = season,
                        episode = episode
                    )
                )
            }

        /*
         * Generic fallback:
         * scan all anchors for /eps/ links in case the theme changes
         * the class surrounding the series buttons.
         */
        if (result.isEmpty()) {

            document
                .select(
                    "a[href*='/eps/']"
                )
                .forEachIndexed {
                    index,
                    anchor ->

                    val absolute =
                        absoluteUrl(
                            anchor.attr(
                                "href"
                            ),
                            baseUrl
                        )

                    if (
                        !absolute.contains(
                            "/eps/",
                            true
                        )
                    ) {
                        return@forEachIndexed
                    }

                    val text =
                        anchor.text()
                            .trim()

                    val parsed =
                        parseSeasonEpisode(
                            text
                        )

                    result.putIfAbsent(
                        absolute,
                        EpisodeInfo(
                            url = absolute,
                            name =
                                buildEpisodeName(
                                    parsed?.first,
                                    parsed?.second
                                        ?: (index + 1)
                                ),
                            season =
                                parsed?.first,
                            episode =
                                parsed?.second
                                    ?: (index + 1)
                        )
                    )
                }
        }

        return result.values.toList()
    }

    private fun parseSeasonEpisode(
        value: String
    ): Pair<Int, Int>? {

        if (value.isBlank()) {
            return null
        }

        val pattern =
            Regex(
                """(?i)\bS\s*(\d+)\s*E(?:p(?:s)?)?\s*(\d+)\b"""
            )

        val match =
            pattern.find(value)

        return if (match != null) {

            Pair(
                match.groupValues[1]
                    .toIntOrNull()
                    ?: 1,

                match.groupValues[2]
                    .toIntOrNull()
                    ?: 1
            )

        } else {

            val alt =
                Regex(
                    """(?i)\bSeason\s*(\d+).*?\bEpisode\s*(\d+)\b"""
                ).find(value)

            if (alt != null) {

                Pair(
                    alt.groupValues[1]
                        .toIntOrNull()
                        ?: 1,

                    alt.groupValues[2]
                        .toIntOrNull()
                        ?: 1
                )

            } else {
                null
            }
        }
    }

    private fun buildEpisodeName(
        season: Int?,
        episode: Int?
    ): String {

        return when {

            season != null &&
                episode != null ->
                "S${season} E${episode}"

            episode != null ->
                "Episode $episode"

            else ->
                "Episode"
        }
    }

    /*
     * ------------------------------------------------------------
     * DETAIL METADATA
     * ------------------------------------------------------------
     */
    private fun extractDetailTitle(
        document: Document
    ): String {

        /*
         * Prefer OG title.
         */
        val og =
            document
                .selectFirst(
                    "meta[property='og:title']"
                )
                ?.attr("content")
                ?.trim()

        if (!og.isNullOrBlank()) {
            return cleanDetailTitle(
                og
            )
        }

        /*
         * Then H1.
         */
        val h1 =
            document
                .selectFirst(
                    "h1.entry-title, h1"
                )
                ?.text()
                ?.trim()

        if (!h1.isNullOrBlank()) {
            return cleanDetailTitle(
                h1
            )
        }

        return ""
    }

    private fun extractPlot(
        document: Document
    ): String? {

        val description =
            document
                .selectFirst(
                    "meta[name='description']"
                )
                ?.attr("content")
                ?.trim()

        return description
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractYear(
        document: Document,
        title: String
    ): Int? {

        val published =
            document
                .selectFirst(
                    "time[itemprop='dateCreated'][datetime]"
                )
                ?.attr("datetime")
                ?.trim()

        val yearFromPublished =
            published
                ?.let {
                    Regex(
                        """\b(19|20)\d{2}\b"""
                    ).find(it)
                        ?.value
                        ?.toIntOrNull()
                }

        if (yearFromPublished != null) {
            return yearFromPublished
        }

        return Regex(
            """\b(19|20)\d{2}\b"""
        )
            .find(title)
            ?.value
            ?.toIntOrNull()
    }

    private fun extractPoster(
        document: Document,
        baseUrl: String
    ): String? {

        val raw =
            document
                .selectFirst(
                    "meta[property='og:image']"
                )
                ?.attr("content")
                ?.trim()

        if (
            raw.isNullOrBlank()
        ) {
            return null
        }

        return absoluteUrl(
            raw,
            baseUrl
        )
    }

    /*
     * ------------------------------------------------------------
     * MEDIA EXTRACTION
     * ------------------------------------------------------------
     *
     * Supported:
     *
     * <video src="">
     * <source src="">
     * data-src
     * data-video
     * data-file
     * data-stream
     * data-source
     * JS player variables
     * escaped HTML/JS
     */
    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {

        val found =
            linkedSetOf<String>()

        fun add(
            raw: String?
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            var value =
                raw.trim()

            value =
                decodeMediaValue(
                    value
                )

            if (value.isBlank()) {
                return
            }

            if (
                value.startsWith(
                    "data:",
                    true
                ) ||
                value.startsWith(
                    "javascript:",
                    true
                )
            ) {
                return
            }

            value =
                value.trim(
                    '"',
                    '\'',
                    '`',
                    ',',
                    ';',
                    ')',
                    ']',
                    '}'
                )

            val absolute =
                absoluteUrl(
                    value,
                    baseUrl
                )

            if (
                isMediaUrl(
                    absolute
                )
            ) {
                found.add(
                    absolute
                )
            }
        }

        /*
         * DOM is the highest-confidence source.
         */
        document.select(
            "video[src], " +
            "video source[src], " +
            "source[src], " +
            "[data-src], " +
            "[data-video], " +
            "[data-file], " +
            "[data-stream], " +
            "[data-source], " +
            "[data-media], " +
            "[data-playlist], " +
            "[data-manifest]"
        ).forEach { element ->

            add(
                element.attr(
                    "src"
                )
            )

            add(
                element.attr(
                    "data-src"
                )
            )

            add(
                element.attr(
                    "data-video"
                )
            )

            add(
                element.attr(
                    "data-file"
                )
            )

            add(
                element.attr(
                    "data-stream"
                )
            )

            add(
                element.attr(
                    "data-source"
                )
            )

            add(
                element.attr(
                    "data-media"
                )
            )

            add(
                element.attr(
                    "data-playlist"
                )
            )

            add(
                element.attr(
                    "data-manifest"
                )
            )
        }

        /*
         * Scan attributes generally for media-looking values.
         */
        document
            .select(
                "[src], [href], [data-src], [data-url], " +
                "[data-file], [data-video], [data-stream]"
            )
            .forEach { element ->

                val attrs =
                    listOf(
                        "src",
                        "href",
                        "data-src",
                        "data-url",
                        "data-file",
                        "data-video",
                        "data-stream"
                    )

                attrs.forEach { attr ->

                    val value =
                        element
                            .attr(attr)
                            .trim()

                    if (
                        value.isNotBlank() &&
                        looksLikeMediaReference(
                            value
                        )
                    ) {
                        add(
                            value
                        )
                    }
                }
            }

        /*
         * Normalize escaped JS/HTML.
         */
        val normalizedVariants =
            linkedSetOf<String>().apply {

                add(html)

                add(
                    html
                        .replace(
                            "\\/",
                            "/"
                        )
                        .replace(
                            "\\x2F",
                            "/"
                        )
                        .replace(
                            "\\u002F",
                            "/"
                        )
                        .replace(
                            "\\u002f",
                            "/"
                        )
                        .replace(
                            "\\u0026",
                            "&"
                        )
                        .replace(
                            "&amp;",
                            "&"
                        )
                )

                runCatching {

                    add(
                        URLDecoder.decode(
                            html,
                            StandardCharsets.UTF_8.toString()
                        )
                    )

                }.getOrNull()
            }

        val patterns =
            listOf(

                /*
                 * Full absolute media URLs.
                 */
                Regex(
                    """(?i)(https?://[^"'<>\s\\]+?\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?)"""
                ),

                /*
                 * Relative media paths.
                 */
                Regex(
                    """(?i)(/(?:[^"'<>\s\\]+/)*[^"'<>\s\\]+\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?)"""
                ),

                /*
                 * videoSrc / videoUrl / streamUrl / file / source / url
                 */
                Regex(
                    """(?is)(?:videoSrc|videoUrl|streamUrl|stream|playlist|manifest|source|file|src|url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"']*)?)["']"""
                ),

                /*
                 * Common JS player objects.
                 */
                Regex(
                    """(?is)["'](?:file|src|url|source|video)["']\s*:\s*["']([^"']+?\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"']*)?)["']"""
                )
            )

        normalizedVariants.forEach { variant ->

            patterns.forEach { pattern ->

                pattern.findAll(
                    variant
                ).forEach { match ->

                    val candidate =
                        match.groupValues
                            .getOrNull(1)
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?: match.value

                    add(
                        candidate
                    )
                }
            }
        }

        return found.toList()
    }

    /*
     * Decode escaped URL forms commonly found in JS.
     */
    private fun decodeMediaValue(
        input: String
    ): String {

        var value =
            input
                .replace(
                    "\\/",
                    "/"
                )
                .replace(
                    "\\x2F",
                    "/"
                )
                .replace(
                    "\\u002F",
                    "/"
                )
                .replace(
                    "\\u002f",
                    "/"
                )
                .replace(
                    "\\u0026",
                    "&"
                )
                .replace(
                    "\\u003A",
                    ":"
                )
                .replace(
                    "\\u003a",
                    ":"
                )
                .replace(
                    "&amp;",
                    "&"
                )

        runCatching {

            value =
                URLDecoder.decode(
                    value,
                    StandardCharsets.UTF_8.toString()
                )

        }

        return value.trim()
    }

    /*
     * ------------------------------------------------------------
     * EMIT PLAYER LINK
     * ------------------------------------------------------------
     */
    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val url =
            cleanUrl(
                mediaUrl
            )

        if (!isMediaUrl(url)) {
            return
        }

        val type =
            when {

                url.contains(
                    ".m3u8",
                    true
                ) ->
                    ExtractorLinkType.M3U8

                url.contains(
                    ".mpd",
                    true
                ) ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }

        val label =
            when (type) {

                ExtractorLinkType.M3U8 ->
                    "$name HLS"

                ExtractorLinkType.DASH ->
                    "$name DASH"

                else ->
                    "$name Direct"
            }

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = type
            ) {

                this.referer =
                    referer

                this.quality =
                    qualityFromUrl(
                        url
                    )

                /*
                 * Keep the playback headers small.
                 * The original source page remains the referer.
                 */
                this.headers =
                    mapOf(
                        "User-Agent" to
                            browserHeaders[
                                "User-Agent"
                            ].orEmpty(),
                        "Accept" to "*/*"
                    )
            }
        )
    }

    /*
     * ------------------------------------------------------------
     * URL HELPERS
     * ------------------------------------------------------------
     */
    private fun buildPageUrl(
        base: String,
        page: Int
    ): String {

        val current =
            page.coerceAtLeast(1)

        if (current == 1) {
            return base
        }

        val clean =
            base.trimEnd('/')

        /*
         * WordPress standard pagination.
         */
        return "$clean/page/$current/"
    }

    private fun absoluteUrl(
        raw: String,
        baseUrl: String
    ): String {

        var value =
            raw.trim()

        if (value.isBlank()) {
            return baseUrl
        }

        value =
            value.replace(
                "&amp;",
                "&"
            )

        if (
            value.startsWith(
                "http://",
                true
            ) ||
            value.startsWith(
                "https://",
                true
            )
        ) {
            return value
        }

        if (
            value.startsWith(
                "//"
            )
        ) {

            val scheme =
                runCatching {
                    URI(baseUrl).scheme
                }.getOrNull()
                    ?: "https"

            return "$scheme:$value"
        }

        return runCatching {

            URI(baseUrl)
                .resolve(value)
                .toString()

        }.getOrElse {

            if (
                value.startsWith("/")
            ) {

                val uri =
                    URI(baseUrl)

                "${uri.scheme}://${uri.authority}$value"

            } else {

                baseUrl.trimEnd('/') +
                    "/" +
                    value.trimStart('/')
            }
        }
    }

    private fun isContentUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        if (
            lower.contains(
                "youtube.com",
                true
            ) ||
            lower.contains(
                "youtu.be",
                true
            )
        ) {
            return false
        }

        return lower.contains(
            "/tv/"
        ) ||
        lower.contains(
            "/tv-show/"
        ) ||
        lower.contains(
            "/eps/"
        ) ||
        movieGenreUrls.any {
            lower.startsWith(
                it.lowercase(
                    Locale.ROOT
                )
            )
        }
    }

    private fun looksLikeTvUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return lower.contains(
            "/tv/"
        ) ||
        lower.contains(
            "/tv-show/"
        ) ||
        lower.contains(
            "/eps/"
        )
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val clean =
            url
                .substringBefore("#")
                .lowercase(
                    Locale.ROOT
                )

        return mediaExtensions.any { ext ->
            clean.contains(
                ext
            )
        }
    }

    private fun looksLikeMediaReference(
        value: String
    ): Boolean {

        val lower =
            value.lowercase(
                Locale.ROOT
            )

        return mediaExtensions.any {
            lower.contains(it)
        }
    }

    private fun qualityFromUrl(
        url: String
    ): Int {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return when {

            Regex(
                """(?:^|[^0-9])2160p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P2160.value

            Regex(
                """(?:^|[^0-9])1440p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P1440.value

            Regex(
                """(?:^|[^0-9])1080p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P1080.value

            Regex(
                """(?:^|[^0-9])720p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P720.value

            Regex(
                """(?:^|[^0-9])480p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P480.value

            Regex(
                """(?:^|[^0-9])360p?(?:[^0-9]|$)"""
            ).containsMatchIn(lower) ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    /*
     * ------------------------------------------------------------
     * TITLE CLEANUP
     * ------------------------------------------------------------
     */
    private fun cleanArchiveTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)^\s*(watch|download)\s*(movie|tv\s*show)?\s*[:\-]?\s*"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)\s*(for\s+free|free\s+watch|watch\s+online)\s*[!.\-]*\s*$"""
                ),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun cleanDetailTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    """(?i)^\s*(watch\s+and\s+download|watch|download)\s+"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)^\s*(movie|tv\s+show)\s+video\s*[:\-]\s*"""
                ),
                ""
            )
            .replace(
                Regex(
                    """(?i)\s*(for\s+free|free\s+here|watch\s+online)\s*[!.\-]*\s*$"""
                ),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun titleFromUrl(
        url: String
    ): String {

        return runCatching {

            val path =
                URI(url)
                    .path
                    .orEmpty()
                    .trim('/')

            val last =
                path.substringAfterLast(
                    '/'
                )

            last
                .replace(
                    Regex("[_-]+"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()
                .ifBlank {
                    "Online Movies"
                }

        }.getOrElse {
            "Online Movies"
        }
    }

    /*
     * ------------------------------------------------------------
     * SEARCH NORMALIZATION
     * ------------------------------------------------------------
     *
     * This makes queries like:
     *
     * TITAN
     * titan
     * Titan
     * Ti Tan
     * TITAN 2026
     * Titan-2026
     *
     * much more tolerant.
     */
    private fun normalizeSearchText(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(
                Locale.ROOT
            )
            .replace(
                "&",
                " and "
            )
            .replace(
                Regex(
                    "[\\u2010-\\u2015\\u2212]"
                ),
                "-"
            )
            .replace(
                Regex(
                    "[^a-z0-9\\p{L}\\p{N}]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun compactSearchText(
        value: String
    ): String {

        return normalizeSearchText(
            value
        ).replace(
            " ",
            ""
        )
    }

    /*
     * ------------------------------------------------------------
     * FUZZY SEARCH
     * ------------------------------------------------------------
     */
    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        if (a == b) {
            return 0
        }

        if (a.isEmpty()) {
            return b.length
        }

        if (b.isEmpty()) {
            return a.length
        }

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

            val temp =
                previous

            previous =
                current

            current =
                temp
        }

        return previous[
            b.length
        ]
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {

        if (a == b) {
            return 1.0
        }

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0.0
        }

        if (
            a.contains(
                b
            ) ||
            b.contains(
                a
            )
        ) {

            val minLength =
                minOf(
                    a.length,
                    b.length
                ).toDouble()

            val maxLength =
                maxOf(
                    a.length,
                    b.length
                ).toDouble()

            return (
                0.80 +
                    0.20 *
                    (
                        minLength /
                            maxLength
                    )
                )
        }

        val distance =
            levenshtein(
                a,
                b
            )

        return (
            1.0 -
                distance.toDouble() /
                maxOf(
                    a.length,
                    b.length
                )
            ).coerceIn(
                0.0,
                1.0
            )
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        val q =
            normalizeSearchText(
                query
            )

        val t =
            normalizeSearchText(
                title
            )

        if (
            q.isBlank() ||
            t.isBlank()
        ) {
            return 0.0
        }

        if (q == t) {
            return 1.0
        }

        val compactQ =
            compactSearchText(
                q
            )

        val compactT =
            compactSearchText(
                t
            )

        if (
            compactQ.isNotBlank() &&
            compactQ == compactT
        ) {
            return 0.995
        }

        var score =
            0.0

        if (
            t.contains(
                q
            )
        ) {
            score =
                maxOf(
                    score,
                    0.97
                )
        }

        if (
            compactQ.isNotBlank() &&
            compactT.contains(
                compactQ
            )
        ) {

            val ratio =
                compactQ.length.toDouble() /
                    compactT.length
                        .coerceAtLeast(
                            1
                        )
                        .toDouble()

            score =
                maxOf(
                    score,
                    0.86 +
                        (
                            ratio * 0.11
                        )
                )
        }

        val qTokens =
            q.split(
                ' '
            )
                .filter {
                    it.length >= 2
                }

        val tTokens =
            t.split(
                ' '
            )
                .filter {
                    it.length >= 2
                }

        if (
            qTokens.isNotEmpty() &&
            tTokens.isNotEmpty()
        ) {

            val tokenScore =
                qTokens.map { qToken ->

                    tTokens.maxOfOrNull {
                        tToken ->

                        when {

                            tToken ==
                                qToken ->
                                1.0

                            tToken.startsWith(
                                qToken
                            ) ||
                            qToken.startsWith(
                                tToken
                            ) ->
                                0.92

                            else ->
                                similarity(
                                    qToken,
                                    tToken
                                )
                        }
                    } ?: 0.0

                }.average()

            score =
                maxOf(
                    score,
                    tokenScore
                )
        }

        score =
            maxOf(
                score,
                similarity(
                    compactQ,
                    compactT
                )
            )

        return score.coerceIn(
            0.0,
            1.0
        )
    }

    /*
     * ------------------------------------------------------------
     * PAGINATION
     * ------------------------------------------------------------
     */
    private fun hasNextPage(
        document: Document
    ): Boolean {

        return document.selectFirst(
            "link[rel='next']"
        ) != null ||
        document.selectFirst(
            "a.next"
        ) != null ||
        document.selectFirst(
            ".pagination a.next"
        ) != null ||
        document.selectFirst(
            ".nav-links a.next"
        ) != null ||
        document.selectFirst(
            "a[rel='next']"
        ) != null
    }

    /*
     * ------------------------------------------------------------
     * CONSTANTS
     * ------------------------------------------------------------
     */
    companion object {

        private const val
            MAX_COMBINED_MOVIE_PAGES = 12

        private const val
            SEARCH_MIN_SCORE = 0.32
    }
}
