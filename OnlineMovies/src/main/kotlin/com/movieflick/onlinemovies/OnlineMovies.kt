package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
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
     * EXACT TOP-LEVEL CATEGORY ORDER
     *
     * 1. Latest Movies
     * 2. Movies
     * 3. TV Show
     */
    override val mainPage = mainPageOf(
        "$mainUrl/year/2026/" to "Latest Movies",
        "online-movies://movies" to "Movies",
        "$mainUrl/tv-show/" to "TV Show"
    )

    /*
     * Genre sources used to build the single "Movies" section.
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
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val currentPage = page.coerceAtLeast(1)

        return when (request.data) {

            "online-movies://movies" -> {
                val merged = linkedMapOf<String, SiteItem>()

                /*
                 * Build one Movies row by merging all supplied genres.
                 */
                for (genreUrl in movieGenreUrls) {

                    val pageUrl = buildPageUrl(
                        genreUrl,
                        currentPage
                    )

                    val document = getDocument(pageUrl)
                        ?: continue

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

                val results = merged.values
                    .take(30)
                    .map { item ->
                        toSearchResponse(item)
                    }

                newHomePageResponse(
                    request,
                    results,
                    currentPage < 20
                )
            }

            else -> {

                val url = buildPageUrl(
                    request.data,
                    currentPage
                )

                val document = getDocument(url)
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

                val items = parseItems(
                    document = document,
                    sourceUrl = url,
                    forceSeries = forceSeries
                )

                newHomePageResponse(
                    request,
                    items
                        .take(30)
                        .map { item ->
                            toSearchResponse(item)
                        },
                    hasNextPage(document)
                )
            }
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val original = query.trim()

        if (original.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val normalized = normalizeSearchText(original)

        val variants = linkedSetOf<String>()

        variants.add(original)

        if (normalized.isNotBlank()) {
            variants.add(normalized)
        }

        variants.add(
            original
                .replace(":", " ")
                .replace("-", " ")
                .replace("_", " ")
        )

        variants.add(
            original.replace(
                Regex("\\s+"),
                " "
            )
        )

        val results = linkedMapOf<String, SiteItem>()

        /*
         * WordPress-style site search.
         */
        for (variant in variants) {

            val encoded = URLEncoder.encode(
                variant,
                StandardCharsets.UTF_8.toString()
            )

            val candidates = listOf(
                "$mainUrl/?s=$encoded",
                "$mainUrl/?search=$encoded",
                "$mainUrl/search/$encoded/"
            )

            for (url in candidates) {

                val document = getDocument(url)
                    ?: continue

                parseItems(
                    document = document,
                    sourceUrl = url
                ).forEach { item ->

                    results.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (results.size >= 60) {
                    break
                }
            }

            if (results.size >= 60) {
                break
            }
        }

        /*
         * Fallback:
         * search through the configured genre pages and TV pages.
         */
        if (results.isEmpty()) {

            for (genreUrl in movieGenreUrls) {

                val url = buildPageUrl(
                    genreUrl,
                    1
                )

                val document = getDocument(url)
                    ?: continue

                parseItems(
                    document = document,
                    sourceUrl = url
                ).forEach { item ->

                    results.putIfAbsent(
                        item.url,
                        item
                    )
                }

                if (results.size >= 80) {
                    break
                }
            }

            val tvUrl = buildPageUrl(
                "$mainUrl/tv-show/",
                1
            )

            getDocument(tvUrl)?.let { document ->

                parseItems(
                    document = document,
                    sourceUrl = tvUrl,
                    forceSeries = true
                ).forEach { item ->

                    results.putIfAbsent(
                        item.url,
                        item
                    )
                }
            }
        }

        val ranked = results.values
            .map { item ->
                item to searchScore(
                    normalized,
                    normalizeSearchText(item.title)
                )
            }
            .filter { pair ->
                pair.second >= 0.30
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
        val currentPage = page.coerceAtLeast(1)
        val start = (currentPage - 1) * pageSize

        return newSearchResponseList(
            ranked
                .drop(start)
                .take(pageSize)
                .map { item ->
                    toSearchResponse(item)
                },
            start + pageSize < ranked.size
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val clean = url.trim()

        if (clean.isBlank()) {
            return newMovieLoadResponse(
                "Online Movies",
                mainUrl,
                TvType.Movie,
                mainUrl
            )
        }

        val document = getDocument(clean)

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
            extractTitle(document)
                .ifBlank {
                    titleFromUrlLocal(clean)
                }

        val poster =
            extractPoster(
                document,
                clean
            )

        val isTv =
            looksLikeTvUrl(clean) ||
            document.text().contains(
                "TV Show",
                ignoreCase = true
            ) ||
            document.select(
                "a[href*='/eps/']"
            ).isNotEmpty()

        return if (isTv) {

            /*
             * Metadata-only TV response for now.
             *
             * We intentionally do not resolve third-party media
             * URLs here.
             */
            newTvSeriesLoadResponse(
                title,
                clean,
                TvType.TvSeries,
                emptyList()
            ) {
                posterUrl = poster
            }

        } else {

            newMovieLoadResponse(
                title,
                clean,
                TvType.Movie,
                clean
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * Playback deliberately remains disabled in this implementation.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    private fun toSearchResponse(
        item: SiteItem
    ): SearchResponse {

        return if (item.isSeries) {

            newTvSeriesSearchResponse(
                name = item.title,
                url = item.url,
                type = TvType.TvSeries
            ) {
                posterUrl = item.poster
            }

        } else {

            newMovieSearchResponse(
                name = item.title,
                url = item.url,
                type = TvType.Movie
            ) {
                posterUrl = item.poster
            }
        }
    }

    private suspend fun getDocument(
        url: String
    ): Document? {

        if (url.isBlank()) {
            return null
        }

        return runCatching {

            app.get(
                url,
                headers = browserHeaders
            ).document

        }.getOrNull()
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String,
        forceSeries: Boolean = false
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        /*
         * Several selectors are intentionally supported because
         * WordPress themes commonly use more than one card class.
         */
        val elements = document.select(
            "article, " +
            ".item, " +
            ".post, " +
            ".movie-item, " +
            ".gmr-item-module, " +
            ".gmr-movie-module, " +
            ".entry-item, " +
            ".row-item, " +
            ".item-content"
        )

        for (element in elements) {

            val anchor =
                element.selectFirst(
                    "a[href]"
                ) ?: continue

            val href =
                anchor.attr("href")
                    .trim()

            if (href.isBlank()) {
                continue
            }

            val absolute =
                absoluteUrlLocal(
                    href,
                    sourceUrl
                )

            val title =
                extractCardTitle(element)
                    .ifBlank {
                        anchor.text().trim()
                    }

            if (title.isBlank()) {
                continue
            }

            val image =
                element.selectFirst(
                    "img[src], " +
                    "img[data-src], " +
                    "img[data-lazy-src], " +
                    "img[data-original]"
                )

            val poster =
                image?.let {

                    val raw =
                        it.attr("data-src")
                            .ifBlank {
                                it.attr("data-lazy-src")
                            }
                            .ifBlank {
                                it.attr("data-original")
                            }
                            .ifBlank {
                                it.attr("src")
                            }

                    raw.takeIf { value ->
                        value.isNotBlank()
                    }?.let { value ->
                        absoluteUrlLocal(
                            value,
                            sourceUrl
                        )
                    }
                }

            val isSeries =
                forceSeries ||
                absolute.contains(
                    "/tv/",
                    ignoreCase = true
                ) ||
                absolute.contains(
                    "/tv-show/",
                    ignoreCase = true
                ) ||
                absolute.contains(
                    "/eps/",
                    ignoreCase = true
                )

            result.putIfAbsent(
                absolute,
                SiteItem(
                    title = cleanTitle(title),
                    url = absolute,
                    poster = poster,
                    isSeries = isSeries
                )
            )
        }

        return result.values.toList()
    }

    private fun extractCardTitle(
        element: org.jsoup.nodes.Element
    ): String {

        val candidates = listOf(
            element.selectFirst(
                ".entry-title"
            )?.text(),

            element.selectFirst(
                ".title"
            )?.text(),

            element.selectFirst(
                "h2"
            )?.text(),

            element.selectFirst(
                "h3"
            )?.text(),

            element.selectFirst(
                "h4"
            )?.text(),

            element.selectFirst(
                "img[alt]"
            )?.attr("alt"),

            element.selectFirst(
                "a[title]"
            )?.attr("title")
        )

        return candidates
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
            .orEmpty()
    }

    private fun extractTitle(
        document: Document
    ): String {

        return document
            .selectFirst(
                "meta[property='og:title']"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    "h1.entry-title, h1"
                )
                ?.text()
                ?.trim()
                .orEmpty()
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
                .orEmpty()

        if (raw.isBlank()) {
            return null
        }

        return absoluteUrlLocal(
            raw,
            baseUrl
        )
    }

    private fun buildPageUrl(
        baseUrl: String,
        page: Int
    ): String {

        val currentPage =
            page.coerceAtLeast(1)

        if (currentPage == 1) {
            return baseUrl
        }

        val clean =
            baseUrl
                .trimEnd('/')

        return "$clean/page/$currentPage/"
    }

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
        ) != null
    }

    private fun looksLikeTvUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(Locale.ROOT)

        return lower.contains("/tv/") ||
            lower.contains("/tv-show/") ||
            lower.contains("/eps/")
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
                    .substringAfterLast('/')
                    .ifBlank {
                        "Online Movies"
                    }

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
                .split(' ')
                .joinToString(" ") { word ->
                    word.replaceFirstChar {
                        if (it.isLowerCase()) {
                            it.titlecase(Locale.ROOT)
                        } else {
                            it.toString()
                        }
                    }
                }

        }.getOrElse {
            "Online Movies"
        }
    }

    private fun absoluteUrlLocal(
        rawUrl: String,
        baseUrl: String
    ): String {

        val value =
            rawUrl
                .trim()
                .replace(
                    "&amp;",
                    "&"
                )

        if (value.isBlank()) {
            return baseUrl
        }

        if (
            value.startsWith(
                "http://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "https://",
                ignoreCase = true
            )
        ) {
            return value
        }

        return runCatching {
            URI(baseUrl).resolve(value).toString()
        }.getOrElse {
            if (value.startsWith("/")) {
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

    private fun cleanTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun normalizeSearchText(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(Locale.ROOT)
            .replace(
                "&",
                " and "
            )
            .replace(
                Regex("[\\u2010-\\u2015\\u2212]"),
                "-"
            )
            .replace(
                Regex("[^a-z0-9\\p{L}]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
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
                    if (a[i] == b[j]) {
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

        return previous[b.length]
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        if (
            query.isBlank() ||
            title.isBlank()
        ) {
            return 0.0
        }

        if (query == title) {
            return 1.0
        }

        if (
            title.contains(
                query,
                ignoreCase = true
            )
        ) {
            return 0.96
        }

        val compactQuery =
            query.replace(
                " ",
                ""
            )

        val compactTitle =
            title.replace(
                " ",
                ""
            )

        if (
            compactQuery.isNotBlank() &&
            compactTitle.contains(
                compactQuery,
                ignoreCase = true
            )
        ) {
            return 0.90
        }

        val distance =
            levenshtein(
                query,
                title
            )

        val maxLength =
            maxOf(
                query.length,
                title.length
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
}
