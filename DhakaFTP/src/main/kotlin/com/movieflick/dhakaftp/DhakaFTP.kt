package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
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

    override val mainPage = mainPageOf(
        "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/" to
            "English Movies",

        "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/" to
            "Hindi Movies",

        "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/" to
            "Kolkata Bangla Movies",

        "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/" to
            "South Indian Hindi Dubbed",

        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/" to
            "TV Web Series",

        "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/" to
            "K-Drama",

        "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/" to
            "Anime"
    )

    private val videoExtensions = setOf(
        ".mkv",
        ".mp4",
        ".webm",
        ".avi",
        ".mov",
        ".m4v"
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

        val results = mutableListOf<SearchResponse>()

        try {
            val entries = getDirectoryEntries(request.data)

            for (entry in entries) {
                val url = entry.absoluteUrl()

                if (!isVideo(url)) {
                    continue
                }

                val title = getEntryTitle(entry, url)

                results.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    )
                )

                if (results.size >= 40) {
                    break
                }
            }

        } catch (_: Exception) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        return newHomePageResponse(
            request,
            results,
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

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val results = mutableListOf<SearchResponse>()

        for (root in mainPage) {

            if (results.size >= 50) {
                break
            }

            searchDirectory(
                root.data,
                cleanQuery,
                results,
                0
            )
        }

        return newSearchResponseList(
            results.take(50),
            false
        )
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query,
            1
        ).items
    }

    private suspend fun searchDirectory(
        directoryUrl: String,
        query: String,
        results: MutableList<SearchResponse>,
        depth: Int
    ) {

        if (depth > 6) {
            return
        }

        if (results.size >= 50) {
            return
        }

        val entries = try {
            getDirectoryEntries(directoryUrl)
        } catch (_: Exception) {
            return
        }

        for (entry in entries) {

            if (results.size >= 50) {
                return
            }

            val url = entry.absoluteUrl()

            if (url.isBlank()) {
                continue
            }

            if (isVideo(url)) {

                val title = getEntryTitle(
                    entry,
                    url
                )

                if (
                    title.contains(
                        query,
                        ignoreCase = true
                    )
                ) {

                    results.add(
                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        )
                    )
                }

            } else if (isDirectory(url)) {

                val folderName = entry.text().trim()

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

    override suspend fun load(
        url: String
    ): LoadResponse {

        if (isVideo(url)) {

            val title = getTitleFromUrl(url)

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            )
        }

        val entries = try {
            getDirectoryEntries(url)
        } catch (_: Exception) {
            emptyList()
        }

        val videoEntries = entries.filter {
            isVideo(it.absoluteUrl())
        }

        if (videoEntries.isNotEmpty()) {

            val episodes = mutableListOf<Episode>()

            videoEntries.forEachIndexed { index, entry ->

                val videoUrl = entry.absoluteUrl()

                if (videoUrl.isBlank()) {
                    return@forEachIndexed
                }

                val episode = newEpisode(
                    videoUrl
                ) {
                    name = getEntryTitle(
                        entry,
                        videoUrl
                    )

                    episode = index + 1
                    season = 1
                }

                episodes.add(
                    episode
                )
            }

            val folderTitle = getFolderTitle(url)

            return newTvSeriesLoadResponse(
                folderTitle,
                url,
                TvType.TvSeries,
                episodes
            )
        }

        return newMovieLoadResponse(
            getFolderTitle(url),
            url,
            TvType.Movie,
            url
        )
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

        val cleanUrl = data
            .substringBefore("?")
            .lowercase()

        val type =
            if (cleanUrl.endsWith(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

        val quality = when {
            "2160" in cleanUrl || "4k" in cleanUrl ->
                Qualities.P2160.value

            "1440" in cleanUrl ->
                Qualities.P1440.value

            "1080" in cleanUrl ->
                Qualities.P1080.value

            "720" in cleanUrl ->
                Qualities.P720.value

            "480" in cleanUrl ->
                Qualities.P480.value

            "360" in cleanUrl ->
                Qualities.P360.value

            "240" in cleanUrl ->
                Qualities.P240.value

            "144" in cleanUrl ->
                Qualities.P144.value

            else ->
                Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = type
            ) {
                this.referer = mainUrl
                this.quality = quality
            }
        )

        return true
    }

    private fun Element.absoluteUrl(): String {

        val href = attr("href").trim()

        if (href.isBlank()) {
            return ""
        }

        return if (
            href.startsWith(
                "http://",
                ignoreCase = true
            ) ||
            href.startsWith(
                "https://",
                ignoreCase = true
            )
        ) {
            href
        } else {
            attr("abs:href").trim()
        }
    }

    private fun getEntryTitle(
        element: Element,
        url: String
    ): String {

        val text = element
            .text()
            .trim()

        if (text.isNotBlank()) {
            return text
        }

        return getTitleFromUrl(url)
    }

    private fun getTitleFromUrl(
        url: String
    ): String {

        val fileName = url
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast('/')

        val withoutExtension =
            fileName.substringBeforeLast(
                ".",
                fileName
            )

        return try {
            URLDecoder.decode(
                withoutExtension,
                "UTF-8"
            )
        } catch (_: Exception) {
            withoutExtension
        }
    }

    private fun getFolderTitle(
        url: String
    ): String {

        val folderName = url
            .trimEnd('/')
            .substringAfterLast('/')

        if (folderName.isBlank()) {
            return "DhakaFTP"
        }

        return try {
            URLDecoder.decode(
                folderName,
                "UTF-8"
            )
        } catch (_: Exception) {
            folderName
        }
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
}
