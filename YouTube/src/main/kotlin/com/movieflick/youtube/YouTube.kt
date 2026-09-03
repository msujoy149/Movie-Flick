package com.movieflick.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

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
     * --------------------------------------------------
     * HOME CATEGORIES
     * --------------------------------------------------
     *
     * Gaming has been removed.
     *
     * Movies replaces Gaming.
     */
    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movie Trailers",
        "trending_music" to "Music",
        "movies" to "Movies",
        "live" to "Live",
        "trending_podcasts_episodes" to "Podcasts"
    )

    /*
     * --------------------------------------------------
     * CURATED LIVE CHANNELS
     * --------------------------------------------------
     *
     * Only these channels are allowed in the Live section.
     *
     * If a channel is not live, it disappears.
     *
     * If the same channel becomes live again, it can
     * automatically appear again after the home page
     * is refreshed.
     */
    private val allowedLiveChannels = listOf(

        // 🇮🇳 Indian Bengali News
        "Republic Bangla",
        "ABP Ananda",
        "News18 Bangla",
        "TV9 Bangla",
        "Kolkata TV",
        "Calcutta News",
        "R Plus News",
        "News Time Bangla",
        "Zee 24 Ghanta",
        "Ei Samay",

        // 🇧🇩 Bangladeshi Bengali News
        "Jamuna TV",
        "Somoy TV",
        "Ekattor TV",
        "ATN News",
        "Channel 24",
        "DBC News",
        "Independent Television",
        "News24",
        "RTV",
        "NTV",
        "Desh TV",
        "BanglaVision",
        "Nagorik TV",
        "Maasranga TV",

        // 🇮🇳 Indian Hindi News
        "Aaj Tak",
        "Republic Bharat",
        "ABP News",
        "News18 India",
        "Zee News",
        "TV9 Bharatvarsh",
        "India TV",
        "Times Now Navbharat",
        "NDTV India",
        "DD News",
        "News24",
        "News Nation",
        "India News",
        "Good News Today",

        // 🌐 Indian English News
        "NDTV 24x7",
        "Times Now",
        "CNN-News18",
        "India Today",
        "WION"
    )

    /*
     * --------------------------------------------------
     * MOVIE SEARCH PRIORITY
     * --------------------------------------------------
     *
     * Priority:
     *
     * 1. Kolkata / Indian Bengali
     * 2. Bengali dubbed
     * 3. Hindi
     *
     * Bangladeshi Bengali movies are intentionally not
     * searched here.
     */
    private val movieQueries = listOf(
        "Kolkata Bengali full movie",
        "Indian Bengali full movie",
        "Bengali dubbed full movie",
        "Bangla dubbed full movie",
        "Hindi full movie"
    )

    /*
     * Words which strongly indicate Bangladeshi movie
     * content. Those results are removed from Movies.
     */
    private val bangladeshMovieKeywords = listOf(
        "bangladesh",
        "bangladeshi",
        "dhallywood",
        "dhaka movie",
        "bd movie",
        "bangla natok"
    )

    private val pageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    private val searchPageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    /*
     * --------------------------------------------------
     * HOME PAGE
     * --------------------------------------------------
     */
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        /*
         * Custom Movies section.
         */
        if (request.data == "movies") {
            return getMoviesPage(page)
        }

        /*
         * Custom curated Live section.
         */
        if (request.data == "live") {
            return getCuratedLivePage(page)
        }

        /*
         * Standard YouTube categories.
         */
        val key = request.data

        if (page == 1) {
            pageCache.remove(key)
        }

        val extractor = try {
            getKioskExtractor(
                request.data
            )
        } catch (_: Exception) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val pageData = try {

            if (page == 1) {

                extractor.fetchPage()

                extractor.initialPage.also {
                    pageCache[key] = it.nextPage
                }

            } else {

                val next =
                    pageCache[key]
                        ?: return newHomePageResponse(
                            emptyList(),
                            false
                        )

                extractor.getPage(
                    next
                ).also {
                    pageCache[key] = it.nextPage
                }
            }

        } catch (_: Exception) {

            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val results =
            pageData.items.map {
                it.toSearchResponse()
            }

        val headerName =
            try {
                extractor.name.ifEmpty {
                    request.name
                }
            } catch (_: Exception) {
                request.name
            }.ifEmpty {
                "Trending"
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

    /*
     * --------------------------------------------------
     * MOVIES
     * --------------------------------------------------
     *
     * Bengali / Kolkata results are added first.
     *
     * Bengali dubbed results come next.
     *
     * Hindi results come last.
     *
     * Bangladesh-specific movie results are filtered.
     */
    private suspend fun getMoviesPage(
        page: Int
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        for (query in movieQueries) {

            if (results.size >= 40) {
                break
            }

            try {

                val extractor =
                    service.getSearchExtractor(
                        query
                    )

                extractor.fetchPage()

                val items =
                    extractor.initialPage.items

                for (item in items) {

                    if (results.size >= 40) {
                        break
                    }

                    if (item !is StreamInfoItem) {
                        continue
                    }

                    /*
                     * Do not put live streams inside Movies.
                     */
                    if (
                        item.streamType !=
                        StreamType.VIDEO_STREAM
                    ) {
                        continue
                    }

                    /*
                     * Skip YouTube Shorts.
                     */
                    if (
                        item.isShortFormContent
                    ) {
                        continue
                    }

                    val url =
                        item.url
                            ?: continue

                    if (url.isBlank()) {
                        continue
                    }

                    if (!seenUrls.add(url)) {
                        continue
                    }

                    val title =
                        item.name
                            ?.trim()
                            ?: continue

                    if (title.isBlank()) {
                        continue
                    }

                    /*
                     * Remove obvious Bangladeshi movie
                     * results.
                     */
                    if (
                        isBangladeshMovie(
                            title
                        )
                    ) {
                        continue
                    }

                    results.add(
                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        ) {

                            posterUrl =
                                item.thumbnails
                                    .lastOrNull()
                                    ?.url
                        }
                    )
                }

            } catch (_: Exception) {
                continue
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Movies",
                    results,
                    false
                )
            ),
            false
        )
    }

    private fun isBangladeshMovie(
        title: String
    ): Boolean {

        val lower =
            title.lowercase()

        return bangladeshMovieKeywords.any {
            lower.contains(it)
        }
    }

    /*
     * --------------------------------------------------
     * CURATED LIVE
     * --------------------------------------------------
     *
     * Each allowed channel is searched individually.
     *
     * Only:
     *
     *   StreamType.LIVE_STREAM
     *
     * is accepted.
     *
     * Therefore:
     *
     * Offline channel -> not shown
     * Live channel     -> shown
     * Other channel    -> never shown
     *
     * A refresh creates a fresh list again.
     */
    private suspend fun getCuratedLivePage(
        page: Int
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        /*
         * Search every approved channel separately.
         *
         * This is intentionally sequential so YouTube
         * is not hit with dozens of simultaneous requests.
         */
        for (channel in allowedLiveChannels) {

            if (results.size >= 40) {
                break
            }

            try {

                val extractor =
                    service.getSearchExtractor(
                        "$channel live"
                    )

                extractor.fetchPage()

                collectLiveChannelResults(
                    channel,
                    extractor.initialPage.items,
                    results,
                    seenUrls
                )

            } catch (_: Exception) {
                continue
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Live",
                    results,
                    false
                )
            ),
            false
        )
    }

    private fun collectLiveChannelResults(
        allowedChannel: String,
        items: List<InfoItem>,
        results: MutableList<SearchResponse>,
        seenUrls: MutableSet<String>
    ) {

        for (item in items) {

            if (results.size >= 40) {
                return
            }

            if (item !is StreamInfoItem) {
                continue
            }

            /*
             * Must be a currently live video.
             */
            if (
                item.streamType !=
                StreamType.LIVE_STREAM
            ) {
                continue
            }

            val uploader =
                item.uploaderName
                    ?.trim()
                    ?: continue

            /*
             * Exact normalized channel matching.
             */
            if (
                !isSameChannel(
                    uploader,
                    allowedChannel
                )
            ) {
                continue
            }

            val url =
                item.url
                    ?: continue

            if (url.isBlank()) {
                continue
            }

            if (!seenUrls.add(url)) {
                continue
            }

            val title =
                item.name
                    ?.trim()
                    ?: continue

            if (title.isBlank()) {
                continue
            }

            results.add(
                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Live
                ) {

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            )
        }
    }

    private fun isSameChannel(
        first: String,
        second: String
    ): Boolean {

        return normalizeChannelName(first) ==
            normalizeChannelName(second)
    }

    private fun normalizeChannelName(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex("[^a-z0-9]+"),
                ""
            )
    }

    /*
     * --------------------------------------------------
     * SEARCH
     * --------------------------------------------------
     *
     * Normal YouTube search remains unrestricted.
     *
     * This means users can still search for any channel,
     * movie or video that YouTube/NewPipe can find.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cacheKey =
            cleanQuery.lowercase()

        val extractor =
            service.getSearchExtractor(
                cleanQuery
            )

        val pageData = try {

            if (
                page == 1 ||
                !searchPageCache.containsKey(
                    cacheKey
                )
            ) {

                extractor.fetchPage()

                extractor.initialPage.also {
                    searchPageCache[cacheKey] =
                        it.nextPage
                }

            } else {

                val next =
                    searchPageCache[cacheKey]
                        ?: return newSearchResponseList(
                            emptyList(),
                            false
                        )

                extractor.getPage(
                    next
                ).also {
                    searchPageCache[cacheKey] =
                        it.nextPage
                }
            }

        } catch (_: Exception) {

            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val results =
            pageData.items.map {
                it.toSearchResponse()
            }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
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

    /*
     * --------------------------------------------------
     * KIOSK
     * --------------------------------------------------
     */
    private fun getKioskExtractor(
        kioskId: String?
    ): KioskExtractor<out InfoItem> {

        return if (
            kioskId.isNullOrBlank()
        ) {

            service.kioskList
                .getDefaultKioskExtractor(
                    null
                )

        } else {

            service.kioskList
                .getExtractorById(
                    kioskId,
                    null
                )
        }
    }

    /*
     * --------------------------------------------------
     * SEARCH ITEM -> CLOUDSTREAM
     * --------------------------------------------------
     */
    private fun InfoItem.toSearchResponse():
        SearchResponse {

        val itemName =
            name ?: "Unknown"

        val itemUrl =
            url ?: ""

        return newMovieSearchResponse(
            itemName,
            itemUrl,
            TvType.Others
        ) {

            posterUrl =
                thumbnails
                    .lastOrNull()
                    ?.url
        }
    }

    /*
     * --------------------------------------------------
     * LOAD
     * --------------------------------------------------
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        return when (
            getUrlType(url)
        ) {

            UrlType.Video ->
                loadVideo(url)

            UrlType.Channel ->
                loadChannel(url)

            UrlType.Playlist ->
                loadPlaylist(url)

            UrlType.Unknown ->
                throw RuntimeException(
                    "Unsupported YouTube URL"
                )
        }
    }

    private enum class UrlType {
        Video,
        Channel,
        Playlist,
        Unknown
    }

    private fun getUrlType(
        url: String
    ): UrlType {

        val cleanUrl =
            url.lowercase()

        return when {

            cleanUrl.contains(
                "/watch?v="
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "youtu.be/"
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "/shorts/"
            ) ->
                UrlType.Video

            cleanUrl.contains(
                "/channel/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/@"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/c/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/user/"
            ) ->
                UrlType.Channel

            cleanUrl.contains(
                "/playlist?list="
            ) ->
                UrlType.Playlist

            else ->
                UrlType.Unknown
        }
    }

    /*
     * --------------------------------------------------
     * VIDEO
     * --------------------------------------------------
     */
    private suspend fun loadVideo(
        url: String
    ): LoadResponse {

        val extractor =
            service.getStreamExtractor(
                url
            )

        extractor.fetchPage()

        val info =
            StreamInfo.getInfo(
                extractor
            )

        val isLive =
            info.streamType
                ?.name
                ?.contains(
                    "LIVE"
                ) == true

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

            plot =
                info.description
                    .content
                    .toString()

            posterUrl =
                info.thumbnails
                    .lastOrNull()
                    ?.url

            if (info.duration > 0) {
                duration =
                    info.duration.toInt()
            }

            info.uploaderName
                ?.takeIf {
                    it.isNotBlank()
                }
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

            tags =
                info.tags
                    ?.take(5)
                    ?.toList()
        }
    }

    /*
     * --------------------------------------------------
     * CHANNEL
     * --------------------------------------------------
     */
    private suspend fun loadChannel(
        url: String
    ): LoadResponse {

        val extractor =
            service.getChannelExtractor(
                url
            )

        extractor.fetchPage()

        val channelName =
            extractor.name

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

        val tabs =
            extractor.tabs

        val videosTab =
            tabs.firstOrNull {
                it.url.contains(
                    "/videos"
                )
            } ?: tabs.firstOrNull()
            ?: throw RuntimeException(
                "No videos tab found"
            )

        val videosExtractor =
            service.getChannelTabExtractor(
                videosTab
            )

        val episodes =
            mutableListOf<Episode>()

        var page =
            videosExtractor.initialPage

        episodes.addAll(
            page.items.map { item ->

                newEpisode(
                    item.url
                ) {

                    name =
                        item.name

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            }
        )

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

            episodes.addAll(
                page.items.map { item ->

                    newEpisode(
                        item.url
                    ) {

                        name =
                            item.name

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                    }
                }
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot =
                channelDescription

            posterUrl =
                channelBanner

            backgroundPosterUrl =
                channelBanner

            tags =
                listOf(
                    "Channel"
                )

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

    /*
     * --------------------------------------------------
     * PLAYLIST
     * --------------------------------------------------
     */
    private suspend fun loadPlaylist(
        url: String
    ): LoadResponse {

        val extractor =
            service.getPlaylistExtractor(
                url
            )

        extractor.fetchPage()

        val playlistName =
            extractor.name

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

        episodes.addAll(
            page.items.map { item ->

                newEpisode(
                    item.url
                ) {

                    name =
                        item.name

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                }
            }
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

            episodes.addAll(
                page.items.map { item ->

                    newEpisode(
                        item.url
                    ) {

                        name =
                            item.name

                        posterUrl =
                            item.thumbnails
                                .lastOrNull()
                                ?.url
                }
                }
            )

            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {

            plot =
                playlistDescription

            posterUrl =
                playlistThumbnail

            tags =
                if (
                    uploaderName.isNotBlank()
                ) {
                    listOf(
                        "Channel: $uploaderName"
                    )
                } else {
                    listOf(
                        "Playlist"
                    )
                }

            if (
                uploaderName.isNotBlank()
            ) {

                actors = listOf(
                    ActorData(
                        Actor(
                            uploaderName,
                            extractor
                                .uploaderAvatars
                                .lastOrNull()
                                ?.url
                                ?: ""
                        )
                    )
                )
            }
        }
    }

    /*
     * --------------------------------------------------
     * PLAYBACK
     * --------------------------------------------------
     *
     * Keep the working YouTube extractor playback system.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return loadExtractor(
            data,
            subtitleCallback,
            callback
        )
    }
}
