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
     * ALLOWED LIVE CHANNELS
     * --------------------------------------------------
     *
     * Only these channels are allowed in the curated
     * Live section.
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
     * 1. Kolkata / Indian Bengali
     * 2. Bengali dubbed
     * 3. Hindi
     *
     * Bangladeshi Bengali movie search is intentionally
     * excluded.
     */
    private val movieQueries = listOf(
        "Kolkata Bengali full movie",
        "Indian Bengali full movie",
        "Bengali dubbed full movie",
        "Bangla dubbed full movie",
        "Hindi full movie"
    )

    /*
     * Strong Bangladesh-specific words.
     *
     * These are used only as a secondary filter so that
     * obviously Bangladeshi movie results do not enter
     * the curated Movies section.
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

        /*
         * Process queries in priority order.
         *
         * Kolkata Bengali comes first.
         * Bengali dubbed comes second.
         * Hindi comes last.
         */
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

                for (item in extractor.initialPage.items) {

                    if (results.size >= 40) {
                        break
                    }

                    if (item !is StreamInfoItem) {
                        continue
                    }

                    /*
                     * Movies section should not contain live
                     * streams.
                     */
                    if (
                        item.streamType !=
                        StreamType.VIDEO_STREAM
                    ) {
                        continue
                    }

                    /*
                     * Do not show Shorts in Movies.
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
                     * Remove obvious Bangladesh-specific
                     * movie results.
                     */
                    if (
                        isBangladeshMovie(title)
                    ) {
                        continue
                    }

                    val thumbnail =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    results.add(
                        newMovieSearchResponse(
                            title,
                            url,
                            TvType.Movie
                        ) {

                            /*
                             * Keep YouTube's actual thumbnail.
                             */
                            posterUrl =
                                thumbnail
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
     * Every allowed channel is searched individually.
     *
     * For each channel:
     *
     *   - only LIVE_STREAM is accepted
     *   - uploader must match the allowed channel
     *   - if multiple live streams exist,
     *     the oldest/earliest-started live is selected
     *
     * Therefore every channel contributes at most ONE
     * live stream.
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
         * One channel -> one selected live stream.
         */
        for (channel in allowedLiveChannels) {

            try {

                val extractor =
                    service.getSearchExtractor(
                        "$channel live"
                    )

                extractor.fetchPage()

                val selected =
                    selectOldestLiveForChannel(
                        channel,
                        extractor.initialPage.items
                    )

                if (selected == null) {
                    continue
                }

                val url =
                    selected.url
                        ?: continue

                if (url.isBlank()) {
                    continue
                }

                if (!seenUrls.add(url)) {
                    continue
                }

                val title =
                    selected.name
                        ?.trim()
                        ?: continue

                if (title.isBlank()) {
                    continue
                }

                val thumbnail =
                    selected.thumbnails
                        .lastOrNull()
                        ?.url
                        ?.takeIf {
                            it.isNotBlank()
                        }

                results.add(
                    newMovieSearchResponse(
                        title,
                        url,
                        TvType.Live
                    ) {

                        posterUrl =
                            thumbnail
                    }
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

    /*
     * --------------------------------------------------
     * SELECT OLDEST LIVE
     * --------------------------------------------------
     *
     * The YouTube/NewPipe extractor exposes the live
     * stream start timestamp through uploadDate for
     * currently-running live streams.
     *
     * The earliest timestamp is therefore selected.
     */
    private fun selectOldestLiveForChannel(
        allowedChannel: String,
        items: List<InfoItem>
    ): StreamInfoItem? {

        val candidates =
            mutableListOf<StreamInfoItem>()

        for (item in items) {

            if (item !is StreamInfoItem) {
                continue
            }

            /*
             * Must be a currently running live stream.
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

            candidates.add(item)
        }

        if (candidates.isEmpty()) {
            return null
        }

        /*
         * Prefer the earliest known start/upload date.
         *
         * If a date is unavailable, keep the item as a
         * fallback but do not let it replace a known date.
         */
        return candidates.minWithOrNull(
            compareBy<StreamInfoItem> {
                it.uploadDate
                    ?.instant
                    ?.toEpochMilli()
                    ?: Long.MAX_VALUE
            }
        )
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
     * Users can search for channels, videos, playlists,
     * movies, etc.
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
     * INFO ITEM -> CLOUDSTREAM SEARCH RESPONSE
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
     * VIDEO LOAD
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
     * Playback strategy:
     *
     * 1. VOD -> DASH adaptive stream
     * 2. Live -> HLS stream
     * 3. If manifest extraction fails -> CloudStream
     *    generic extractor fallback
     *
     * This preserves the previous working playback path
     * while giving the player a direct adaptive manifest.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.isBlank()) {
            return false
        }

        val extractor = try {
            service.getStreamExtractor(
                data
            )
        } catch (_: Exception) {
            return loadExtractor(
                data,
                subtitleCallback,
                callback
            )
        }

        try {

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

            /*
             * --------------------------------------------------
             * LIVE -> HLS
             * --------------------------------------------------
             *
             * YouTube's HLS manifest is especially useful
             * for live streams.
             */
            if (isLive) {

                val hlsUrl =
                    runCatching {
                        info.hlsUrl
                    }.getOrNull()

                if (
                    !hlsUrl.isNullOrBlank()
                ) {

                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Live",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {

                            referer =
                                "https://www.youtube.com/"

                            quality =
                                Qualities.Unknown.value
                        }
                    )

                    return true
                }
            }

            /*
             * --------------------------------------------------
             * VOD -> DASH
             * --------------------------------------------------
             *
             * DASH allows adaptive video/audio selection
             * instead of locking playback to one fixed
             * progressive stream.
             */
            val dashUrl =
                runCatching {
                    info.dashMpdUrl
                }.getOrNull()

            if (
                !dashUrl.isNullOrBlank()
            ) {

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Adaptive",
                        url = dashUrl,
                        type = ExtractorLinkType.DASH
                    ) {

                        referer =
                            "https://www.youtube.com/"

                        quality =
                            Qualities.Unknown.value
                    }
                )

                return true
            }

            /*
             * HLS fallback for non-live videos if DASH is
             * unavailable.
             */
            val hlsUrl =
                runCatching {
                    info.hlsUrl
                }.getOrNull()

            if (
                !hlsUrl.isNullOrBlank()
            ) {

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {

                        referer =
                            "https://www.youtube.com/"

                        quality =
                            Qualities.Unknown.value
                    }
                )

                return true
            }

        } catch (_: Exception) {
            /*
             * Fall through to the generic CloudStream
             * YouTube extractor below.
             */
        }

        /*
         * Final fallback.
         *
         * This is the same playback system that was already
         * working previously.
         */
        return loadExtractor(
            data,
            subtitleCallback,
            callback
        )
    }
}
