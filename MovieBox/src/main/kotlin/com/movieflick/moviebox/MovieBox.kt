package com.movieflick.moviebox

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MovieBox : MainAPI() {

    override var mainUrl = "https://themoviebox.xyz/"
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
        mainUrl to "MovieBox"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        return try {

            val document = app.get(mainUrl).document

            val results = mutableListOf<SearchResponse>()

            document
                .select("a[href]")
                .forEach { element ->

                    if (results.size >= 40) {
                        return@forEach
                    }

                    val url = element.attr("abs:href").trim()

                    if (!isMovieUrl(url)) {
                        return@forEach
                    }

                    val title = element
                        .text()
                        .trim()

                    if (title.isBlank()) {
                        return@forEach
                    }

                    val poster = element
                        .selectFirst("img")
                        ?.attr("abs:src")
                        ?.takeIf { it.isNotBlank() }

                    results.add(
                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        ) {
                            posterUrl = poster
                        }
                    )
                }

            newHomePageResponse(
                listOf(
                    HomePageList(
                        "MovieBox",
                        results,
                        false
                    )
                ),
                false
            )

        } catch (_: Exception) {

            newHomePageResponse(
                emptyList(),
                false
            )
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        if (page > 1) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        return try {

            val encodedQuery = URLEncoder.encode(
                cleanQuery,
                "UTF-8"
            )

            val searchUrls = listOf(
                "$mainUrl/search?keyword=$encodedQuery",
                "$mainUrl/search?q=$encodedQuery",
                "$mainUrl/?s=$encodedQuery"
            )

            val results = mutableListOf<SearchResponse>()

            for (searchUrl in searchUrls) {

                if (results.size >= 50) {
                    break
                }

                try {

                    val document = app.get(
                        searchUrl
                    ).document

                    document
                        .select("a[href]")
                        .forEach { element ->

                            if (results.size >= 50) {
                                return@forEach
                            }

                            val url = element
                                .attr("abs:href")
                                .trim()

                            if (!isMovieUrl(url)) {
                                return@forEach
                            }

                            val title = element
                                .text()
                                .trim()

                            if (
                                title.isBlank() ||
                                !title.contains(
                                    cleanQuery,
                                    ignoreCase = true
                                )
                            ) {
                                return@forEach
                            }

                            if (
                                results.any {
                                    it.url == url
                                }
                            ) {
                                return@forEach
                            }

                            val poster = element
                                .selectFirst("img")
                                ?.attr("abs:src")
                                ?.takeIf {
                                    it.isNotBlank()
                                }

                            results.add(
                                newMovieSearchResponse(
                                    title,
                                    url,
                                    TvType.Movie
                                ) {
                                    posterUrl = poster
                                }
                            )
                        }

                } catch (_: Exception) {
                    continue
                }
            }

            newSearchResponseList(
                results.take(50),
                false
            )

        } catch (_: Exception) {

            newSearchResponseList(
                emptyList(),
                false
            )
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {

        return search(
            query,
            1
        ).items
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document = app.get(url).document

        val title = findTitle(
            document,
            url
        )

        val poster = findPoster(
            document
        )

        val description = document
            .selectFirst(
                "meta[name=description]"
            )
            ?.attr("content")
            ?.trim()

        val year = document
            .selectFirst(
                "[class*=year], [data-year]"
            )
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            posterUrl = poster
            plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.isBlank()) {
            return false
        }

        return try {

            val document = app.get(data).document

            var found = false

            document
                .select("video source, video[src], source[src]")
                .forEach { element ->

                    val sourceUrl = when {

                        element.hasAttr("src") ->
                            element.attr("abs:src")

                        else ->
                            element.attr("src")
                    }.trim()

                    if (sourceUrl.isBlank()) {
                        return@forEach
                    }

                    val lower = sourceUrl
                        .substringBefore("?")
                        .lowercase()

                    val type =
                        if (
                            lower.endsWith(".m3u8")
                        ) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = sourceUrl,
                            type = type
                        ) {
                            referer = data
                            quality =
                                detectQuality(sourceUrl)
                        }
                    )

                    found = true
                }

            document
                .select("iframe[src]")
                .forEach { element ->

                    val iframeUrl = element
                        .attr("abs:src")
                        .trim()

                    if (iframeUrl.isBlank()) {
                        return@forEach
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Embed",
                            url = iframeUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            referer = data
                            quality = Qualities.Unknown.value
                        }
                    )

                    found = true
                }

            found

        } catch (_: Exception) {
            false
        }
    }

    private fun findTitle(
        document: org.jsoup.nodes.Document,
        url: String
    ): String {

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()

        if (!title.isNullOrBlank()) {
            return title
        }

        val ogTitle = document
            .selectFirst(
                "meta[property=og:title]"
            )
            ?.attr("content")
            ?.trim()

        if (!ogTitle.isNullOrBlank()) {
            return ogTitle
        }

        return url
            .trimEnd('/')
            .substringAfterLast('/')
            .replace(
                Regex("[-_]+"),
                " "
            )
            .trim()
            .ifBlank {
                "MovieBox"
            }
    }

    private fun findPoster(
        document: org.jsoup.nodes.Document
    ): String? {

        val ogImage = document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()

        if (!ogImage.isNullOrBlank()) {
            return ogImage
        }

        return document
            .selectFirst("img")
            ?.attr("abs:src")
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun isMovieUrl(
        url: String
    ): Boolean {

        if (url.isBlank()) {
            return false
        }

        val lower = url.lowercase()

        if (
            lower.startsWith("javascript:") ||
            lower.startsWith("#") ||
            lower.startsWith("mailto:")
        ) {
            return false
        }

        return lower.contains(
            "themoviebox.xyz"
        )
    }

    private fun detectQuality(
        url: String
    ): Int {

        val lower = url.lowercase()

        return when {

            "2160" in lower ||
            "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "480" in lower ->
                Qualities.P480.value

            "360" in lower ->
                Qualities.P360.value

            "240" in lower ->
                Qualities.P240.value

            else ->
                Qualities.Unknown.value
        }
    }
}
