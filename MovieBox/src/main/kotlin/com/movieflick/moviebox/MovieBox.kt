package com.movieflick.moviebox

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

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

    private val movieBoxHosts = listOf(
        "api6.aoneroom.com",
        "api5.aoneroom.com",
        "api4.aoneroom.com",
        "api4sg.aoneroom.com",
        "api3.aoneroom.com"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "MovieBox"
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

                    val url = element.absUrl("href")
                        .ifBlank {
                            element.attr("href")
                        }

                    val title = element.text().trim()

                    if (
                        title.isNotBlank() &&
                        isMovieBoxPage(url)
                    ) {
                        results.add(
                            newMovieSearchResponse(
                                title,
                                url,
                                TvType.Movie
                            )
                        )
                    }
                }

            newHomePageResponse(
                listOf(
                    HomePageList(
                        "MovieBox",
                        results.distinctBy { it.url },
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

        return try {

            val encodedQuery =
                java.net.URLEncoder.encode(
                    query,
                    "UTF-8"
                )

            val searchUrl =
                "$mainUrl/search?keyword=$encodedQuery"

            val document =
                app.get(searchUrl).document

            val results =
                document
                    .select("a[href]")
                    .mapNotNull { element ->

                        val url =
                            element.absUrl("href")
                                .ifBlank {
                                    element.attr("href")
                                }

                        val title =
                            element.text().trim()

                        if (
                            title.isBlank() ||
                            !isMovieBoxPage(url)
                        ) {
                            return@mapNotNull null
                        }

                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        )
                    }
                    .distinctBy { it.url }

            newSearchResponseList(
                results,
                false
            )

        } catch (_: Exception) {

            newSearchResponseList(
                emptyList(),
                false
            )
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val document =
            app.get(url).document

        val title =
            document
                .selectFirst(
                    "meta[property=og:title]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: document
                    .selectFirst("title")
                    ?.text()
                    ?.trim()
                    ?: "MovieBox"

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.trim()

        val description =
            document
                .selectFirst(
                    "meta[property=og:description]"
                )
                ?.attr("content")
                ?.trim()

        val videoLinks =
            document
                .select("a[href], video[src], source[src]")
                .mapNotNull { element ->

                    val link =
                        when {
                            element.hasAttr("href") ->
                                element.absUrl("href")

                            element.hasAttr("src") ->
                                element.absUrl("src")

                            else ->
                                null
                        }

                    link?.takeIf {
                        isVideoUrl(it)
                    }
                }
                .distinct()

        if (videoLinks.isNotEmpty()) {

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                posterUrl = poster
                plot = description
            }
        }

        val episodes =
            document
                .select("a[href]")
                .mapNotNull { element ->

                    val episodeUrl =
                        element.absUrl("href")

                    val episodeName =
                        element.text().trim()

                    if (
                        episodeName.isBlank() ||
                        !isMovieBoxPage(episodeUrl)
                    ) {
                        return@mapNotNull null
                    }

                    newEpisode(episodeUrl) {
                        name = episodeName
                    }
                }
                .distinctBy { it.data }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {

            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            loadExtractor(
                data,
                subtitleCallback,
                callback
            )

        } catch (_: Exception) {

            false
        }
    }

    private fun isMovieBoxPage(
        url: String
    ): Boolean {

        val clean =
            url.lowercase()

        return clean.startsWith(
            "https://themoviebox.xyz"
        ) ||
            clean.startsWith(
                "http://themoviebox.xyz"
            )
    }

    private fun isVideoUrl(
        url: String
    ): Boolean {

        val clean =
            url
                .substringBefore("?")
                .lowercase()

        return clean.endsWith(".mp4") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".webm") ||
            clean.endsWith(".mov")
    }
}
