package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    override val mainPage = mainPageOf(
        "$mainUrl/year/2026/" to "Latest Movies",
        "online://movies" to "Movies",
        "$mainUrl/tv-show/" to "TV Show"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean = false
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(
                title = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title = title,
                url = url,
                type = TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    private val pageHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val sourceUrl = when (request.data) {

            "online://movies" -> {
                when (page.coerceAtLeast(1)) {
                    1 -> "$mainUrl/drama/"
                    else -> "$mainUrl/drama/page/${page}/"
                }
            }

            else -> {
                pageUrl(request.data, page)
            }
        }

        val document = getDocument(sourceUrl)
            ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        val items = parseItems(
            document = document,
            sourceUrl = sourceUrl,
            forceSeries = request.name.equals("TV Show", true)
        )

        return newHomePageResponse(
            request,
            items
                .take(30)
                .map { it.toSearchResponse() },
            hasNextPage(document)
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val q = query.trim()
        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val normalized = normalizeSearchText(q)
        val encoded = URLEncoder.encode(
            q,
            StandardCharsets.UTF_8.toString()
        )

        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/?search=$encoded"
        )

        val results = linkedMapOf<String, SiteItem>()

        for (url in searchUrls) {
            val document = getDocument(url) ?: continue

            parseItems(
                document = document,
                sourceUrl = url
            ).forEach { item ->
                results.putIfAbsent(
                    item.url,
                    item
                )
            }

            if (results.isNotEmpty()) {
                break
            }
        }

        val ranked = results.values
            .map { item ->
                item to searchScore(
                    normalized,
                    normalizeSearchText(item.title)
                )
            }
            .filter { it.second >= 0.30 }
            .sortedByDescending { it.second }
            .map { it.first }

        val pageSize = 30
        val start = (page.coerceAtLeast(1) - 1) * pageSize

        return newSearchResponseList(
            ranked
                .drop(start)
                .take(pageSize)
                .map { it.toSearchResponse() },
            start + pageSize < ranked.size
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val cleanUrl = url.trim()

        val document = getDocument(cleanUrl)

        if (document == null) {
            return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                TvType.Movie,
                cleanUrl
            )
        }

        val title = extractPageTitle(document)
            .ifBlank {
                titleFromUrl(cleanUrl)
            }

        val poster = extractPoster(
            document,
            cleanUrl
        )

        val isTv = cleanUrl.contains(
            "/tv/",
            ignoreCase = true
        ) || cleanUrl.contains(
            "/tv-show/",
            ignoreCase = true
        ) || document.text()
            .contains("TV Show", ignoreCase = true)

        return if (isTv) {

            newTvSeriesLoadResponse(
                title,
                cleanUrl,
                TvType.TvSeries,
                emptyList()
            ) {
                posterUrl = poster
            }

        } else {

            newMovieLoadResponse(
                title,
                cleanUrl,
                TvType.Movie,
                cleanUrl
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    private suspend fun getDocument(
        url: String
    ): Document? {

        if (url.isBlank()) return null

        return runCatching {
            app.get(
                url,
                headers = pageHeaders
            ).document
        }.getOrNull()
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String,
        forceSeries: Boolean = false
    ): List<SiteItem> {

        val result = linkedMapOf<String, SiteItem>()

        document.select(
            "article, .item, .movie-item, .post, " +
            ".gmr-item-module, .gmr-movie-module, " +
            ".item-content"
        ).forEach { element ->

            val link = element.selectFirst(
                "a[href]"
            ) ?: return@forEach

            val href = link.attr("href")
                .trim()

            if (href.isBlank()) return@forEach

            val absolute = absoluteUrl(
                href,
                sourceUrl
            )

            val rawTitle =
                link.attr("title")
                    .ifBlank {
                        element.selectFirst(
                            ".entry-title, .title, h2, h3, h4"
                        )?.text()
                            .orEmpty()
                    }
                    .ifBlank {
                        link.text()
                    }
                    .trim()

            val title = cleanTitle(rawTitle)

            if (title.isBlank()) return@forEach

            val image = element.selectFirst(
                "img[src], img[data-src], img[data-lazy-src]"
            )

            val poster = image?.let {
                val raw = it.attr("data-src")
                    .ifBlank {
                        it.attr("data-lazy-src")
                    }
                    .ifBlank {
                        it.attr("src")
                    }

                raw.takeIf { value ->
                    value.isNotBlank()
                }?.let { value ->
                    absoluteUrl(
                        value,
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
                    "/tv-show/",
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

        return result.values.toList()
    }

    private fun extractPageTitle(
        document: Document
    ): String {

        return document.selectFirst(
            "meta[property='og:title']"
        )?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(
                "h1.entry-title, h1"
            )?.text()
                ?.trim()
                .orEmpty()
    }

    private fun extractPoster(
        document: Document,
        baseUrl: String
    ): String? {

        val raw =
            document.selectFirst(
                "meta[property='og:image']"
            )?.attr("content")
                ?.trim()
                .orEmpty()

        if (raw.isBlank()) return null

        return absoluteUrl(
            raw,
            baseUrl
        )
    }

    private fun cleanTitle(
        value: String
    ): String {

        return value
            .replace(Regex("\\s+"), " ")
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
            .replace("&", " and ")
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
            IntArray(b.length + 1) { it }

        var current =
            IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1

            for (j in b.indices) {

                val cost =
                    if (a[i] == b[j]) 0 else 1

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

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        if (query.isBlank() || title.isBlank()) {
            return 0.0
        }

        if (query == title) {
            return 1.0
        }

        if (title.contains(query)) {
            return 0.95
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

    private fun hasNextPage(
        document: Document
    ): Boolean {

        return document.selectFirst(
            "a.next, a.next-page, .pagination a.next"
        ) != null ||
            document.selectFirst(
                "link[rel='next']"
            ) != null
    }
}
