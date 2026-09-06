package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
     * ============================================================
     * MAIN PAGE
     * ============================================================
     *
     * Exact requested order:
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
     * ============================================================
     * MOVIE GENRES
     * ============================================================
     *
     * The website has no single direct Movies archive in the
     * structure supplied by the user. Therefore all requested
     * genres are merged into the Movies section.
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

    /*
     * ============================================================
     * HTTP HEADERS
     * ============================================================
     */
    private val pageHeaders = mapOf(
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

    /*
     * ============================================================
     * INTERNAL DATA MODEL
     * ============================================================
     */
    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean
    )

    private data class EpisodeInfo(
        val url: String,
        val name: String?,
        val season: Int?,
        val episode: Int?
    )

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage = page.coerceAtLeast(1)

        return when (request.data) {

            /*
             * ----------------------------------------------------
             * COMBINED MOVIES
             * ----------------------------------------------------
             */
            "onlinemovies://movies" -> {

                val merged =
                    linkedMapOf<String, SiteItem>()

                movieGenreUrls.forEach { genreUrl ->

                    val pageUrl =
                        buildPageUrl(
                            genreUrl,
                            currentPage
                        )

                    val document =
                        getDocument(pageUrl)
                            ?: return@forEach

                    parseArchiveItems(
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

                val results =
                    merged.values
                        .take(MAX_ITEMS_PER_PAGE)
                        .map { item ->
                            item.toSearchResponse()
                        }

                return newHomePageResponse(
                    request,
                    results,
                    currentPage < MAX_MOVIE_COMBINED_PAGES
                )
            }

            /*
             * ----------------------------------------------------
             * LATEST MOVIES / TV SHOW
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

                val forceSeries =
                    request.name.equals(
                        "TV Show",
                        ignoreCase = true
                    )

                val items =
                    parseArchiveItems(
                        document = document,
                        sourceUrl = url,
                        forceSeries = forceSeries
                    )

                val results =
                    items
                        .take(MAX_ITEMS_PER_PAGE)
                        .map { item ->
                            item.toSearchResponse()
                        }

                return newHomePageResponse(
                    request,
                    results,
                    hasNextPage(document)
                )
            }
        }
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     *
     * Search order:
     *
     * 1. Native WordPress search
     * 2. Query normalization
     * 3. Punctuation variants
     * 4. Movie genre fallback
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
            normalizeSearchText(
                original
            )

        val compact =
            compactSearchText(
                original
            )

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
                        .replace(":", " ")
                        .replace("-", " ")
                        .replace("_", " ")
                )

                add(
                    original
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                )
            }
                .filter {
                    it.isNotBlank()
                }

        val candidates =
            linkedMapOf<String, SiteItem>()

        /*
         * --------------------------------------------------------
         * Native WordPress search
         * --------------------------------------------------------
         */
        for (variant in variants) {

            val encoded =
                URLEncoder.encode(
                    variant,
                    StandardCharsets.UTF_8.toString()
                )

            val urls =
                listOf(
                    "$mainUrl/?s=$encoded",
                    "$mainUrl/?search=$encoded",
                    "$mainUrl/search/$encoded/"
                ).distinct()

            for (url in urls) {

                val document =
                    getDocument(url)
                        ?: continue

                parseArchiveItems(
                    document = document,
                    sourceUrl = url
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (
                    candidates.size >=
                    SEARCH_NATIVE_LIMIT
                ) {
                    break
                }
            }

            if (
                candidates.size >=
                SEARCH_NATIVE_LIMIT
            ) {
                break
            }
        }

        /*
         * --------------------------------------------------------
         * Genre fallback
         * --------------------------------------------------------
         */
        if (
            candidates.size <
            SEARCH_FALLBACK_MINIMUM
        ) {

            movieGenreUrls.forEach { genreUrl ->

                val pageUrl =
                    buildPageUrl(
                        genreUrl,
                        1
                    )

                val document =
                    getDocument(pageUrl)
                        ?: return@forEach

                parseArchiveItems(
                    document = document,
                    sourceUrl = pageUrl
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (
                    candidates.size >=
                    SEARCH_FALLBACK_LIMIT
                ) {
                    return@forEach
                }
            }
        }

        /*
         * --------------------------------------------------------
         * TV fallback
         * --------------------------------------------------------
         */
        if (
            candidates.size <
            SEARCH_FALLBACK_LIMIT
        ) {

            val tvUrl =
                buildPageUrl(
                    "$mainUrl/tv-show/",
                    1
                )

            val document =
                getDocument(tvUrl)

            if (document != null) {

                parseArchiveItems(
                    document = document,
                    sourceUrl = tvUrl,
                    forceSeries = true
                ).forEach { item ->

                    candidates.putIfAbsent(
                        item.url,
                        item.copy(
                            isSeries = true
                        )
                    )
                }
            }
        }

        /*
         * --------------------------------------------------------
         * Ranking
         * --------------------------------------------------------
         */
        val ranked =
            candidates.values
                .map { item ->

                    val score =
                        searchScore(
                            normalized,
                            normalizeSearchText(
                                item.title
                            )
                        )

                    item to score
                }
                .filter {
                    it.second >=
                        SEARCH_MIN_SCORE
                }
                .sortedWith(
                    compareByDescending<
                        Pair<SiteItem, Double>
                        > {
                        it.second
                    }.thenBy {
                        it.first.title
                    }
                )
                .map {
                    it.first
                }

        val pageSize =
            MAX_ITEMS_PER_PAGE

        val currentPage =
            page.coerceAtLeast(1)

        val start =
            (currentPage - 1) *
                pageSize

        val pageResults =
            ranked
                .drop(start)
                .take(pageSize)

        return newSearchResponseList(
            pageResults.map {
                it.toSearchResponse()
            },
            start + pageSize <
                ranked.size
        )
    }

    /*
     * ============================================================
     * LOAD DETAIL
     * ============================================================
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        val clean =
            cleanUrlLocal(url)

        if (clean.isBlank()) {

            return newMovieLoadResponse(
                "Online Movies",
                mainUrl,
                TvType.Movie,
                mainUrl
            )
        }

        /*
         * Do not use generic page text such as "TV Show" to
         * classify a page. URL + actual episode links are stronger.
         */
        val document =
            getDocument(clean)

        if (document == null) {

            return if (
                looksLikeTvUrl(clean)
            ) {

                newTvSeriesLoadResponse(
                    titleFromUrlLocal(clean),
                    clean,
                    TvType.TvSeries,
                    emptyList()
                )

            } else {

                newMovieLoadResponse(
                    titleFromUrlLocal(clean),
                    clean,
                    TvType.Movie,
                    clean
                )
            }
        }

        val title =
            extractDetailTitle(
                document
            ).ifBlank {
                titleFromUrlLocal(
                    clean
                )
            }

        val poster =
            extractPoster(
                document,
                clean
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

        val episodeLinks =
            parseEpisodeLinks(
                document,
                clean
            )

        val isSeries =
            looksLikeTvUrl(clean) ||
            episodeLinks.isNotEmpty()

        /*
         * --------------------------------------------------------
         * TV SERIES
         * --------------------------------------------------------
         */
        if (isSeries) {

            val episodes =
                episodeLinks.mapIndexed {
                    index,
                    info ->

                    newEpisode(
                        info.url
                    ) {

                        name =
                            info.name

                        season =
                            info.season

                        episode =
                            info.episode
                                ?: (index + 1)

                        posterUrl =
                            poster

                        description =
                            null
                    }
                }

            return newTvSeriesLoadResponse(
                title,
                clean,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        /*
         * --------------------------------------------------------
         * MOVIE
         * --------------------------------------------------------
         */
        return newMovieLoadResponse(
            title,
            clean,
            TvType.Movie,
            clean
        ) {

            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    /*
     * ============================================================
     * PLAYBACK
     * ============================================================
     *
     * Playback source resolution is intentionally left out here.
     *
     * This means the provider can browse, search and load metadata
     * without exposing third-party media extraction behavior.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    /*
     * ============================================================
     * SEARCH RESPONSE
     * ============================================================
     */
    private fun SiteItem.toSearchResponse():
        SearchResponse {

        return if (isSeries) {

            newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * ============================================================
     * HTTP
     * ============================================================
     */
    private suspend fun getDocument(
        url: String
    ): Document? {

        val clean =
            cleanUrlLocal(
                url
            )

        if (clean.isBlank()) {
            return null
        }

        return runCatching {

            app.get(
                clean,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            ).document

        }.getOrNull()
    }

    /*
     * ============================================================
     * ARCHIVE PARSER
     * ============================================================
     *
     * Based on the supplied site structure:
     *
     * article
     *   content-thumbnail
     *      a[href]
     *          img
     *   entry-title
     *      a[href]
     *
     * The supplied source also exposes title, image and category
     * information in each archive card.
     */
    private fun parseArchiveItems(
        document: Document,
        sourceUrl: String,
        forceSeries: Boolean = false
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        /*
         * --------------------------------------------------------
         * Primary: article cards
         * --------------------------------------------------------
         */
        document.select(
            "article"
        ).forEach { article ->

            val anchor =
                article.selectFirst(
                    "p.entry-title a[href], " +
                    ".entry-title a[href], " +
                    "a[rel='bookmark'][href]"
                )
                    ?: article.selectFirst(
                        "a[href]"
                    )
                    ?: return@forEach

            val href =
                anchor.attr(
                    "href"
                ).trim()

            if (href.isBlank()) {
                return@forEach
            }

            val absolute =
                absoluteUrlLocal(
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

            val poster =
                extractCardPoster(
                    article,
                    sourceUrl
                )

            val isSeries =
                forceSeries ||
                looksLikeTvUrl(
                    absolute
                )

            result.putIfAbsent(
                absolute,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    isSeries = isSeries
                )
            )
        }

        /*
         * --------------------------------------------------------
         * Secondary fallback
         * --------------------------------------------------------
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
                    anchor.attr(
                        "href"
                    ).trim()

                if (href.isBlank()) {
                    return@forEach
                }

                val absolute =
                    absoluteUrlLocal(
                        href,
                        sourceUrl
                    )

                if (!isContentUrl(absolute)) {
                    return@forEach
                }

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

                if (title.isBlank()) {
                    return@forEach
                }

                val poster =
                    extractCardPoster(
                        element,
                        sourceUrl
                    )

                result.putIfAbsent(
                    absolute,
                    SiteItem(
                        title = title,
                        url = absolute,
                        poster = poster,
                        isSeries =
                            forceSeries ||
                                looksLikeTvUrl(
                                    absolute
                                )
                    )
                )
            }
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * POSTER PARSER
     * ============================================================
     *
     * Supports:
     * src
     * data-src
     * data-lazy-src
     * data-original
     * srcset
     */
    private fun extractCardPoster(
        element: Element,
        sourceUrl: String
    ): String? {

        val image =
            element.selectFirst(
                "img[src], " +
                "img[data-src], " +
                "img[data-lazy-src], " +
                "img[data-original]"
            )
                ?: return null

        val preferred =
            image.attr(
                "data-src"
            ).ifBlank {
                image.attr(
                    "data-lazy-src"
                )
            }.ifBlank {
                image.attr(
                    "data-original"
                )
            }.ifBlank {
                image.attr(
                    "src"
                )
            }.trim()

        if (preferred.isNotBlank()) {

            return absoluteUrlLocal(
                preferred,
                sourceUrl
            )
        }

        val srcSet =
            image.attr(
                "srcset"
            ).trim()

        if (srcSet.isNotBlank()) {

            val largest =
                srcSet
                    .split(",")
                    .map {
                        it.trim()
                    }
                    .lastOrNull()
                    ?.substringBefore(
                        " "
                    )
                    ?.trim()

            if (!largest.isNullOrBlank()) {

                return absoluteUrlLocal(
                    largest,
                    sourceUrl
                )
            }
        }

        return null
    }

    /*
     * ============================================================
     * EPISODE PARSER
     * ============================================================
     *
     * Supplied TV source contains:
     *
     * .gmr-listseries
     *   S1 Eps1
     *   S1 Eps2
     *   S1 Eps3
     *   ...
     */
    private fun parseEpisodeLinks(
        document: Document,
        baseUrl: String
    ): List<EpisodeInfo> {

        val result =
            linkedMapOf<String, EpisodeInfo>()

        document.select(
            ".gmr-listseries a[href]"
        ).forEachIndexed {
            index,
            anchor ->

            val href =
                anchor.attr(
                    "href"
                ).trim()

            if (href.isBlank()) {
                return@forEachIndexed
            }

            val absolute =
                absoluteUrlLocal(
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

            val visibleText =
                anchor.text()
                    .trim()

            val titleAttribute =
                anchor.attr(
                    "title"
                ).trim()

            val sourceName =
                visibleText
                    .ifBlank {
                        titleAttribute
                    }

            val parsed =
                parseSeasonEpisode(
                    sourceName
                )

            val season =
                parsed?.first

            val episode =
                parsed?.second
                    ?: (index + 1)

            result.putIfAbsent(
                absolute,
                EpisodeInfo(
                    url = absolute,
                    name =
                        buildEpisodeName(
                            season,
                            episode
                        ),
                    season = season,
                    episode = episode
                )
            )
        }

        /*
         * Fallback in case the theme changes the wrapper class.
         */
        if (result.isEmpty()) {

            document.select(
                "a[href*='/eps/']"
            ).forEachIndexed {
                index,
                anchor ->

                val href =
                    anchor.attr(
                        "href"
                    ).trim()

                if (href.isBlank()) {
                    return@forEachIndexed
                }

                val absolute =
                    absoluteUrlLocal(
                        href,
                        baseUrl
                    )

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

        val shortPattern =
            Regex(
                """(?i)\bS\s*(\d+)\s*E(?:p(?:s)?)?\s*(\d+)\b"""
            )

        val shortMatch =
            shortPattern.find(value)

        if (shortMatch != null) {

            return Pair(
                shortMatch.groupValues[1]
                    .toIntOrNull()
                    ?: 1,

                shortMatch.groupValues[2]
                    .toIntOrNull()
                    ?: 1
            )
        }

        val longPattern =
            Regex(
                """(?i)\bSeason\s*(\d+).*?\bEpisode\s*(\d+)\b"""
            )

        val longMatch =
            longPattern.find(value)

        if (longMatch != null) {

            return Pair(
                longMatch.groupValues[1]
                    .toIntOrNull()
                    ?: 1,

                longMatch.groupValues[2]
                    .toIntOrNull()
                    ?: 1
            )
        }

        return null
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
     * ============================================================
     * DETAIL TITLE
     * ============================================================
     */
    private fun extractDetailTitle(
        document: Document
    ): String {

        val ogTitle =
            document.selectFirst(
                "meta[property='og:title']"
            )
                ?.attr("content")
                ?.trim()

        if (!ogTitle.isNullOrBlank()) {

            return cleanDetailTitle(
                ogTitle
            )
        }

        val h1 =
            document.selectFirst(
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

        return document
            .selectFirst(
                "meta[name='description']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractPoster(
        document: Document,
        baseUrl: String
    ): String? {

        val raw =
            document.selectFirst(
                "meta[property='og:image']"
            )
                ?.attr("content")
                ?.trim()

        if (raw.isNullOrBlank()) {
            return null
        }

        return absoluteUrlLocal(
            raw,
            baseUrl
        )
    }

    private fun extractYear(
        document: Document,
        title: String
    ): Int? {

        val date =
            document.selectFirst(
                "time[datetime]"
            )
                ?.attr("datetime")
                ?.trim()

        val dateYear =
            date
                ?.let {
                    Regex(
                        """\b(19|20)\d{2}\b"""
                    )
                        .find(it)
                        ?.value
                        ?.toIntOrNull()
                }

        if (dateYear != null) {
            return dateYear
        }

        return Regex(
            """\b(19|20)\d{2}\b"""
        )
            .find(title)
            ?.value
            ?.toIntOrNull()
    }

    /*
     * ============================================================
     * URL / CONTENT HELPERS
     * ============================================================
     */
    private fun cleanUrlLocal(
        url: String
    ): String {

        return url
            .trim()
            .replace(
                "&amp;",
                "&"
            )
            .replace(
                "\\/",
                "/"
            )
            .substringBefore("#")
            .trim()
    }

    private fun absoluteUrlLocal(
        rawUrl: String,
        baseUrl: String
    ): String {

        var value =
            rawUrl
                .trim()
                .replace(
                    "&amp;",
                    "&"
                )

        if (value.isBlank()) {
            return baseUrl
        }

        value =
            value
                .replace(
                    "\\/",
                    "/"
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
                    URI(baseUrl)
                        .scheme
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
                value.startsWith(
                    "/"
                )
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

    private fun buildPageUrl(
        baseUrl: String,
        page: Int
    ): String {

        val current =
            page.coerceAtLeast(1)

        if (current == 1) {
            return baseUrl
        }

        val clean =
            baseUrl.trimEnd('/')

        return "$clean/page/$current/"
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
                "youtube.com"
            ) ||
            lower.contains(
                "youtu.be"
            )
        ) {
            return false
        }

        return lower.contains("/eps/") ||
            lower.contains("/tv/") ||
            lower.contains("/tv-show/") ||
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
            "/eps/"
        ) ||
        lower.contains(
            "/tv/"
        ) ||
        lower.contains(
            "/tv-show/"
        )
    }

    private fun titleFromUrlLocal(
        url: String
    ): String {

        return runCatching {

            val path =
                URI(url)
                    .path
                    .orEmpty()
                    .trim('/')

            val last =
                path
                    .substringAfterLast(
                        '/'
                    )
                    .ifBlank {
                        "Online Movies"
                    }

            last
                .replace(
                    Regex(
                        "[-_]+"
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "\\s+"
                    ),
                    " "
                )
                .trim()

        }.getOrElse {
            "Online Movies"
        }
    }

    /*
     * ============================================================
     * TITLE CLEANUP
     * ============================================================
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

    /*
     * ============================================================
     * SEARCH NORMALIZATION
     * ============================================================
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
                Regex(
                    "\\s+"
                ),
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
     * ============================================================
     * FUZZY SEARCH
     * ============================================================
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
            ) {
                it
            }

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
            a.contains(b) ||
            b.contains(a)
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

        val maxLength =
            maxOf(
                a.length,
                b.length
            )

        if (maxLength == 0) {
            return 0.0
        }

        return (
            1.0 -
                distance.toDouble() /
                maxLength.toDouble()
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

        val qc =
            compactSearchText(
                q
            )

        val tc =
            compactSearchText(
                t
            )

        if (
            qc.isNotBlank() &&
            qc == tc
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
            qc.isNotBlank() &&
            tc.contains(
                qc
            )
        ) {

            val ratio =
                qc.length.toDouble() /
                    tc.length
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
            ).filter {
                it.length >= 2
            }

        val tTokens =
            t.split(
                ' '
            ).filter {
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
                    qc,
                    tc
                )
            )

        return score.coerceIn(
            0.0,
            1.0
        )
    }

    /*
     * ============================================================
     * PAGINATION
     * ============================================================
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
            "a[rel='next']"
        ) != null ||

        document.selectFirst(
            ".pagination a.next"
        ) != null ||

        document.selectFirst(
            ".nav-links a.next"
        ) != null
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */
    private companion object {

        const val MAX_ITEMS_PER_PAGE =
            30

        const val MAX_MOVIE_COMBINED_PAGES =
            12

        const val SEARCH_NATIVE_LIMIT =
            80

        const val SEARCH_FALLBACK_MINIMUM =
            20

        const val SEARCH_FALLBACK_LIMIT =
            100

        const val SEARCH_MIN_SCORE =
            0.32
    }
}
