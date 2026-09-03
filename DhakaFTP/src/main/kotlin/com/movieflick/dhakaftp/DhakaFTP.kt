package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.mainPageOf
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
        *rootFolders.map { it.first to it.second }.toTypedArray()
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
            val entries = getDirectoryEntries(request.data)

            for (element in entries) {
                val url = element.absoluteUrl()

                if (!isVideo(url)) {
                    continue
                }

                val title = getTitle(element, url)

                items.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Movie
                    )
                )
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
    ): com.lagradost.cloudstream3.SearchResponseList {

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

        for ((_, rootUrl) in rootFolders) {

            if (results.size >= 50) {
                break
            }

            searchDirectory(
                rootUrl,
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

    private suspend fun searchDirectory(
        directoryUrl: String,
        query: String,
        results: MutableList<SearchResponse>,
        depth: Int
    ) {
        if (depth > 6 || results.size >= 50) {
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

                val title = getTitle(element, url)

                if (title.contains(query, ignoreCase = true)) {

                    results.add(
                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        )
                    )
                }

            } else if (isDirectory(url)) {

                val folderName = element.text().trim()

                if (
                    folderName.isNotBlank() &&
                    folderName.contains(query, ignoreCase = true)
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

        val entries = getDirectoryEntries(url)

        val videoEntries = entries.filter {
            isVideo(it.absoluteUrl())
        }

        if (videoEntries.isNotEmpty()) {

            val title = getFolderTitle(url)

            val episodeData = videoEntries.mapIndexed { index, element ->

                val videoUrl = element.absoluteUrl()

                com.lagradost.cloudstream3.newEpisode(
                    videoUrl
                ) {
                    name = getTitle(element, videoUrl)
                    episode = index + 1
                }
            }

            return com.lagradost.cloudstream3.newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodeData
            ) {
                plot = "DhakaFTP"
                tags = listOf("DhakaFTP")
            }
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

        val link = newExtractorLink(
            source = name,
            name = name,
            url = data,
            type = if (data.substringBefore("?")
                    .lowercase()
                    .endsWith(".m3u8")
            ) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        ) {
            referer = data
            quality = getQuality(data)
        }

        callback(link)

        return true
    }

    private fun getQuality(
        url: String
    ): Int {

        val lower = url.lowercase()

        return when {
            "2160" in lower || "4k" in lower ->
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

            "144" in lower ->
                Qualities.P144.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun getTitle(
        element: Element,
        url: String
    ): String {

        val text = element.text().trim()

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

        return try {
            URLDecoder.decode(
                fileName.substringBeforeLast("."),
                "UTF-8"
            )
        } catch (_: Exception) {
            fileName.substringBeforeLast(".")
        }
    }

    private fun getFolderTitle(
        url: String
    ): String {

        val folder = url
            .trimEnd('/')
            .substringAfterLast('/')

        return try {
            URLDecoder.decode(
                folder,
                "UTF-8"
            ).ifBlank {
                "DhakaFTP"
            }
        } catch (_: Exception) {
            folder.ifBlank {
                "DhakaFTP"
            }
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

    private fun Element.absoluteUrl(): String {

        val href = attr("href").trim()

        if (href.isBlank()) {
            return ""
        }

        return when {
            href.startsWith("http://", ignoreCase = true) ||
            href.startsWith("https://", ignoreCase = true) -> {
                href
            }

            else -> {
                try {
                    URI(
                        this@DhakaFTP.mainUrl
                    ).resolve(href).toString()
                } catch (_: Exception) {
                    href
                }
            }
        }
    }
}
