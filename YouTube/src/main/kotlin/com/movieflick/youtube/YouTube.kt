package com.movieflick.youtube

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
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
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo

class YouTube : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    /*
     * YouTube home sections.
     */
    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movies & Shows",
        "trending_music" to "Music",
        "trending_gaming" to "Gaming",
        "trending_podcasts_episodes" to "Podcasts",
        "live" to "Live"
    )

    /*
     * NewPipe uses continuation pages.
     * CloudStream uses page numbers, so we keep the
     * NewPipe continuation token for each request.
     */
    private val mainPageCache =
        mutableMapOf<String, Page?>()

    private val searchPageCache =
        mutableMapOf<String, Page?>()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val key = request.data

        if (page == 1) {
            mainPageCache.remove(key)
        }

        val extractor = getKioskExtractor(request.data)

        val pageData = try {
            if (page == 1) {
                extractor.fetchPage()

                extractor.initialPage.also {
                    mainPageCache[key] = it.nextPage
                }
            } else {
                val nextPage = mainPageCache[key]
                    ?: return newHomePageResponse(
                        emptyList(),
                        false
                    )

                extractor.getPage(nextPage).also {
                    mainPageCache[key] = it.nextPage
                }
            }
        } catch (_: Exception) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val results = pageData.items.mapNotNull {
            it.toSearchResponse()
        }

        val headerName = try {
            extractor.name.ifBlank {
                request.name
            }
        } catch (_: Exception) {
            request.name
        }.ifBlank {
            "YouTube"
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    headerName,
                    results,
                    true
                )
            ),
            pageData.hasNextPage()
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val extractor = service.getSearchExtractor(query)

        val pageData = try {
            if (page == 1 || !searchPageCache.containsKey(query)) {
                extractor.fetchPage()

                extractor.initialPage.also {
                    searchPageCache[query] = it.nextPage
                }
            } else {
                val nextPage = searchPageCache[query]
                    ?: return newSearchResponseList(
                        emptyList(),
                        false
                    )

                extractor.getPage(nextPage).also {
                    searchPageCache[query] = it.nextPage
                }
            }
        } catch (_: Exception) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val results = pageData.items.mapNotNull {
            it.toSearchResponse()
        }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun getKioskExtractor(
        kioskId: String?
    ): KioskExtractor<out InfoItem> {

        return if (kioskId.isNullOrBlank()) {
            service.kioskList.getDefaultKioskExtractor(null)
        } else {
            service.kioskList.getExtractorById(
                kioskId,
                null
            )
        }
    }

    private fun InfoItem.toSearchResponse(): SearchResponse? {

        val itemUrl = url ?: return null
        val itemName = name ?: return null

        return newMovieSearchResponse(
            itemName,
            itemUrl,
            TvType.Others
        ) {
            posterUrl = thumbnails
                .lastOrNull()
                ?.url
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        return when (getUrlType(url)) {
            UrlType.Playlist -> loadPlaylist(url)
            UrlType.Video -> loadVideo(url)
            UrlType.Channel -> loadChannel(url)
            UrlType.Unknown -> {
                throw RuntimeException(
                    "Unsupported YouTube URL"
                )
            }
        }
    }

    private enum class UrlType {
        Video,
        Channel,
        Playlist,
        Unknown
    }

    /*
     * Detect YouTube URL type.
     *
     * Playlist is checked before normal video URLs so
     * watch?v=...&list=... is correctly handled as a playlist.
     */
    private fun getUrlType(url: String): UrlType {

        val cleanUrl = url.lowercase()

        return when {
            cleanUrl.contains("/playlist?list=") ->
                UrlType.Playlist

            cleanUrl.contains("&list=") &&
                cleanUrl.contains("/watch?v=") ->
                UrlType.Playlist

            cleanUrl.contains("/watch?v=") ->
                UrlType.Video

            cleanUrl.contains("youtu.be/") ->
                UrlType.Video

            cleanUrl.contains("/channel/") ->
                UrlType.Channel

            cleanUrl.contains("/@") ->
                UrlType.Channel

            cleanUrl.contains("/c/") ->
                UrlType.Channel

            cleanUrl.contains("/user/") ->
                UrlType.Channel

            else ->
                UrlType.Unknown
        }
    }

    private suspend fun loadVideo(
        url: String
    ): LoadResponse {

        val extractor =
            service.getStreamExtractor(url)

        extractor.fetchPage()

        val info =
            StreamInfo.getInfo(extractor)

        val isLive =
            info.streamType?.name
                ?.contains("LIVE", ignoreCase = true)
                == true

        return newMovieLoadResponse(
            info.name,
            url,
            if (isLive) {
                TvType.Live
            } else {
                TvType.Others
            },
            url
        ) {

            plot = info.description
                .content
                .toString()

            posterUrl =
                info.thumbnails
                    .lastOrNull()
                    ?.url

            if (info.duration > 0) {
                duration = info.duration.toInt()
            }

            info.uploaderName
                ?.takeIf { it.isNotBlank() }
                ?.let { uploader ->

                    actors = listOf(
                        ActorData(
                            Actor(
                                uploader,
                                info.uploaderAvatars
                                    .lastOrNull()
                                    ?.url
                                    ?: ""
                            )
                        )
                    )
                }

            tags = info.tags
                ?.take(5)
                ?.toList()
        }
    }

    private suspend fun loadChannel(
        url: String
    ): LoadResponse {

        val extractor =
            service.getChannelExtractor(url)

        extractor.fetchPage()

        val channelName =
            extractor.name.ifBlank {
                "YouTube Channel"
            }

        val channelDescription =
            extractor.description

        val channelAvatar =
            extractor.avatars
                .lastOrNull()
                ?.url

        val channelBanner =
            extractor.banners
                .lastOrNull()
                ?.url

        val videosTab =
            extractor.tabs
                .firstOrNull {
                    it.url.contains(
                        "/videos",
                        ignoreCase = true
                    )
                }
                ?: extractor.tabs.firstOrNull()
                ?: throw RuntimeException(
                    "No YouTube channel videos tab found"
                )

        val videosExtractor =
            service.getChannelTabExtractor(
                videosTab
            )

        videosExtractor.fetchPage()

        val episodes =
            mutableListOf<Episode>()

        var page =
            videosExtractor.initialPage

        addChannelEpisodes(
            episodes,
            page
        )

        /*
         * Load several continuation pages.
         * This avoids downloading an unlimited number
         * of channel videos at once.
         */
        var pagesLoaded = 1
        val maxPages = 5

        while (
            page.hasNextPage() &&
            pagesLoaded < maxPages
        ) {

            page =
                videosExtractor.getPage(
                    page.nextPage
                )

            addChannelEpisodes(
                episodes,
                page
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot = channelDescription

            posterUrl =
                channelAvatar ?: channelBanner

            backgroundPosterUrl =
                channelBanner

            tags = listOf("YouTube", "Channel")

            actors = listOf(
                ActorData(
                    Actor(
                        channelName,
                        channelAvatar ?: ""
                    )
                )
            )
        }
    }

    private fun addChannelEpisodes(
        episodes: MutableList<Episode>,
        page: Page
    ) {

        page.items.forEach { item ->

            val itemUrl =
                item.url ?: return@forEach

            val itemName =
                item.name ?: "YouTube Video"

            episodes.add(
                newEpisode(itemUrl) {

                    name = itemName

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            )
        }
    }

    private suspend fun loadPlaylist(
        url: String
    ): LoadResponse {

        val extractor =
            service.getPlaylistExtractor(url)

        extractor.fetchPage()

        val playlistName =
            extractor.name.ifBlank {
                "YouTube Playlist"
            }

        val playlistDescription =
            extractor.description
                .content
                .toString()

        val playlistThumbnail =
            extractor.thumbnails
                .lastOrNull()
                ?.url

        val uploaderName =
            extractor.uploaderName

        val episodes =
            mutableListOf<Episode>()

        var page =
            extractor.getInitialPage()

        addPlaylistEpisodes(
            episodes,
            page
        )

        var pagesLoaded = 1
        val maxPages = 5

        while (
            page.hasNextPage() &&
            pagesLoaded < maxPages
        ) {

            page =
                extractor.getPage(
                    page.nextPage
                )

            addPlaylistEpisodes(
                episodes,
                page
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot = playlistDescription

            posterUrl =
                playlistThumbnail

            tags =
                if (uploaderName.isNotBlank()) {
                    listOf(
                        "YouTube",
                        "Playlist",
                        "Channel: $uploaderName"
                    )
                } else {
                    listOf(
                        "YouTube",
                        "Playlist"
                    )
                }

            if (uploaderName.isNotBlank()) {

                actors = listOf(
                    ActorData(
                        Actor(
                            uploaderName,
                            extractor.uploaderAvatars
                                .lastOrNull()
                                ?.url
                                ?: ""
                        )
                    )
                )
            }
        }
    }

    private fun addPlaylistEpisodes(
        episodes: MutableList<Episode>,
        page: Page
    ) {

        page.items.forEach { item ->

            val itemUrl =
                item.url ?: return@forEach

            val itemName =
                item.name ?: "YouTube Video"

            episodes.add(
                newEpisode(itemUrl) {

                    name = itemName

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            )
        }
    }

    /*
     * Pass the YouTube video ID back to CloudStream's
     * extractor system.
     *
     * This does not create our own streaming proxy.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return loadExtractor(
            "https://www.youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )
    }
}
