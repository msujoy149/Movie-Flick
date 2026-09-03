package com.movieflick.dhakaftp

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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

class DhakaFTP : MainAPI() {

    override var mainUrl = "http://172.16.50.7/"
    override var name = "DhakaFTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val videoExtensions = setOf(
        ".mkv",
        ".mp4",
        ".webm",
        ".avi",
        ".mov",
        ".m4v"
    )

    private val rootFolders = listOf(
        "English Movies" to
            "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/",

        "Hindi Movies" to
            "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/",

        "Kolkata Bangla Movies" to
            "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/",

        "South Indian Hindi Dubbed" to
            "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/",

        "TV Web Series" to
            "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/",

        "K-Drama" to
            "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/",

        "Anime" to
            "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/"
    )

    override val mainPage = mainPageOf(
        *rootFolders.toTypedArray()
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

        val items = mutableListOf<SearchResponse>()

        try {
            getDirectoryEntries(request.data)
                .forEach { element ->

                    val url = element.absoluteUrl()

                    if (!isVideo(url)) {
                        return@forEach
                    }

                    parseVideo(element)?.let { item ->
                        items.add(item)
                    }
                }
        } catch (_: Exception) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    items.take(40),
                    false
                )
            ),
            false
        )
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

        val results = mutableListOf<SearchResponse>()

        for ((_, rootUrl) in rootFolders) {

            if (results.size >= 50) {
                break
            }

            try {
                searchDirectory(
                    rootUrl,
                    query.trim(),
                    results,
                    0
                )
            } catch (_: Exception) {
                continue
            }
        }

        return newSearchResponseList(
            results.take(50),
            false
        )
    }

    private suspend fun searchDirectory(
        directoryUrl: String,
        query: String,
        results: MutableList<SearchResponse>,
        depth: Int
    ) {

        if (
            depth > 6 ||
            results.size >= 50
        ) {
            return
        }

        val entries = try {
            getDirectoryEntries(directoryUrl)
        } catch (_: Exception) {
            return
        }

        for (element in entries) {

            if (results.size >= 50) {
                return
            }

            val url = element.absoluteUrl()

            if (url.isBlank()) {
                continue
            }

            if (isVideo(url)) {

                val title = element.text()
                    .trim()
                    .ifBlank {
                        url.substringAfterLast("/")
                            .substringBeforeLast(".")
                    }

                if (
                    query.isBlank() ||
                    title.contains(
                        query,
                        ignoreCase = true
                    )
                ) {
                    parseVideo(element)?.let { item ->
                        results.add(item)
                    }
                }

            } else if (isDirectory(url)) {

                val folderName = element.text()
                    .trim()

                if (
                    folderName.isNotBlank() &&
                    folderName.contains(
                        query,
                        ignoreCase = true
                    )
                ) {
                    results.add(
                        newMovieSearchResponse(
                            folderName,
                            url,
                            TvType.TvSeries
                        )
                    )
                }

                searchDirectory(
                    url,
                    query,
                    results,
                    depth + 1
                )
            }
        }
    }

    private suspend fun getDirectoryEntries(
        url: String
    ): List<Element> {

        return app.get(url)
            .document
            .select("a[href]")
    }

    private fun parseVideo(
        element: Element
    ): SearchResponse? {

        val url = element.absoluteUrl()

        if (!isVideo(url)) {
            return null
        }

        val title = element.text()
            .trim()
            .ifBlank {
                url.substringAfterLast("/")
                    .substringBeforeLast(".")
            }

        return newMovieSearchResponse(
            title,
            url,
            TvType.Movie
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        if (isVideo(url)) {

            val title = url
                .substringAfterLast("/")
                .substringBeforeLast(".")

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            )
        }

        val entries = getDirectoryEntries(url)

        val episodes = mutableListOf<Episode>()

        entries
            .filter { isVideo(it.absoluteUrl()) }
            .forEach { element ->

                val videoUrl = element.absoluteUrl()

                val title = element.text()
                    .trim()
                    .ifBlank {
                        videoUrl
                            .substringAfterLast("/")
                            .substringBeforeLast(".")
                    }

                episodes.add(
                    newEpisode(videoUrl) {
                        name = title
                    }
                )
            }

        val folderName = try {
            URLDecoder.decode(
                url.trimEnd('/').substringAfterLast('/'),
                "UTF-8"
            )
        } catch (_: Exception) {
            url.trimEnd('/').substringAfterLast('/')
        }

        return newTvSeriesLoadResponse(
            folderName.ifBlank {
                "DhakaFTP"
            },
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = "DhakaFTP"
            tags = listOf("DhakaFTP")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        callback(
            newExtractorLink(
                name,
                name,
                data,
                com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            ) {
                referer = data
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    private fun isVideo(
        url: String
    ): Boolean {

        val cleanUrl = url
            .substringBefore("?")
            .lowercase()

        return videoExtensions.any {
            cleanUrl.endsWith(it)
        }
    }

    private fun isDirectory(
        url: String
    ): Boolean {

        return url.endsWith("/") &&
            !isVideo(url)
    }

    private fun Element.absoluteUrl(): String {

        val href = attr("href")
            .trim()

        if (href.isBlank()) {
            return ""
        }

        return when {
            href.startsWith("http://") ||
                href.startsWith("https://") -> href

            else -> {
                try {
                    URI(this@DhakaFTP.mainUrl)
                        .resolve(href)
                        .toString()
                } catch (_: Exception) {
                    href
                }
            }
        }
    }
}
