package com.movieflick.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

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

    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movie Trailers",
        "music_india" to "Trending Music Videos",
        "movies" to "Movies",
        "hindi_movies" to "Hindi Movies",
        "live" to "Live",
        "religion" to "Religion"
    )

    private data class ScoredMovie(
        val response: SearchResponse,
        val score: Int
    )

    private fun looksLikeNonMovieUpload(title: String): Boolean {
        val t = title.lowercase()

        return listOf(
            "trailer",
            "teaser",
            "scene",
            "scenes",
            "clip",
            "short",
            "song",
            "lyric",
            "lyrics",
            "review",
            "reaction",
            "interview",
            "recap",
            "explained",
            "promo",
            "making"
        ).any {
            t.contains(it)
        }
    }

    private fun scoreGeneralMovie(
        title: String,
        uploader: String
    ): Int {

        val t = "$title $uploader".lowercase()

        var score = 100

        if (
            t.contains("full movie") ||
            t.contains("full film")
        ) {
            score += 40
        }

        if (t.contains("blockbuster")) {
            score += 45
        }

        if (
            t.contains("superhit") ||
            t.contains("super hit")
        ) {
            score += 35
        }

        if (
            t.contains("4k") ||
            t.contains("ultra hd")
        ) {
            score += 15
        }

        if (t.contains("official")) {
            score += 25
        }

        if (
            t.contains("new release") ||
            t.contains("latest")
        ) {
            score += 20
        }

        return score
    }

    private fun scoreHindiMovie(
        title: String,
        uploader: String
    ): Int {

        val t = "$title $uploader".lowercase()

        var score = 100

        /*
         * SOUTH INDIAN PRIORITY
         */

        if (
            listOf(
                "south",
                "tamil",
                "telugu",
                "kannada",
                "malayalam"
            ).any {
                t.contains(it)
            }
        ) {
            score += 90
        }

        if (
            t.contains("hindi dubbed") ||
            t.contains("hindi dub")
        ) {
            score += 80
        }

        /*
         * POPULARITY SIGNALS
         */

        if (t.contains("blockbuster")) {
            score += 60
        }

        if (
            t.contains("superhit") ||
            t.contains("super hit")
        ) {
            score += 45
        }

        /*
         * GENRE
         */

        if (t.contains("action")) {
            score += 35
        }

        /*
         * FULL MOVIE
         */

        if (
            t.contains("full movie") ||
            t.contains("full film")
        ) {
            score += 35
        }

        /*
         * NEW / RECENT
         */

        if (
            t.contains("new release") ||
            t.contains("new released") ||
            t.contains("latest")
        ) {
            score += 25
        }

        if (t.contains("2026")) {
            score += 20
        }

        /*
         * GOLDMINES SIGNAL
         */

        if (t.contains("goldmines")) {
            score += 25
        }

        if (t.contains("official")) {
            score += 20
        }

        return score
    }

    private fun rotateHomeResults(
        results: List<SearchResponse>,
        fixedCount: Int,
        visibleCount: Int,
        rotationMinutes: Int,
        seedKey: String
    ): List<SearchResponse> {

        if (results.size <= visibleCount) {
            return results
        }

        val safeFixed =
            fixedCount.coerceAtMost(results.size)

        val fixed =
            results.take(safeFixed)

        val rotating =
            results
                .drop(safeFixed)
                .toMutableList()

        val bucket =
            System.currentTimeMillis() /
                (rotationMinutes * 60_000L)

        rotating.shuffle(
            java.util.Random(
                (seedKey.hashCode().toLong() shl 32) xor bucket
            )
        )

        return (
            fixed + rotating
            ).take(visibleCount)
    }

    /*
     * --------------------------------------------------
     * LIVE CHANNELS
     * --------------------------------------------------
     */

    private val allowedLiveChannels = listOf(

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

        "NDTV 24x7",
        "Times Now",
        "CNN-News18",
        "India Today",
        "WION"
    )

    /*
     * --------------------------------------------------
     * INDIAN MUSIC
     * --------------------------------------------------
     */

    private val indianMusicQueries = listOf(
        "Hindi trending songs India",
        "Indian Hindi trending music",
        "Hindi new songs trending India",
        "Hindi songs trending India",
        "Kolkata Bengali trending songs",
        "Indian Bengali trending songs",
        "Bengali new songs India",
        "Bengali songs trending Kolkata"
    )

    /*
     * --------------------------------------------------
     * MOVIES
     * --------------------------------------------------
     */

    private val movieQueries = listOf(
        "Kolkata Bengali full movie",
        "Indian Bengali full movie",
        "Bengali dubbed full movie",
        "Bangla dubbed full movie",
        "Hindi full movie"
    )

    /*
     * --------------------------------------------------
     * HINDI MOVIES
     * --------------------------------------------------
     */

    private val hindiMovieQueries = listOf(
        "South Indian Hindi dubbed full movie",
        "South Hindi dubbed blockbuster full movie",
        "South Indian new Hindi dubbed movie",
        "latest South Hindi dubbed full movie",
        "Hindi dubbed action movie full",
        "Goldmines Hindi dubbed full movie",
        "Goldmines new South Hindi dubbed movie",
        "latest Hindi full movie",
        "Hindi blockbuster full movie",
        "new Hindi dubbed movie 2026"
    )

    private val bangladeshKeywords = listOf(
        "bangladesh",
        "bangladeshi",
        "dhallywood",
        "dhaka movie",
        "bd movie",
        "bangla natok"
    )

    private val pakistanMusicKeywords = listOf(
        "pakistan",
        "pakistani",
        "lollywood",
        "pakistani song",
        "pakistani music",
        "coke studio pakistan"
    )

    /*
     * --------------------------------------------------
     * BENGALI DUBBED RELIGIOUS SERIALS
     * --------------------------------------------------
     */

    private data class BengaliDubbedShow(
        val key: String,
        val bengaliNames: List<String>,
        val hindiNames: List<String>,
        val officialChannels: List<String>
    )

    /*
     * এখানে তোমার আগের BengaliDubbedShow catalog,
     * religion aliases, additional Hindi queries,
     * validation এবং scoring logic আগের optimized
     * YouTube.kt থেকেই থাকবে।
     *
     * IMPORTANT:
     * Bengali এবং Hindi একই serial হলেও আলাদা result।
     */

    /*
     * --------------------------------------------------
     * CACHE
     * --------------------------------------------------
     */

    private val pageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    private val searchPageCache =
        mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()

    private data class TimedHomeCache(
        val expiresAt: Long,
        val response: HomePageResponse
    )

    private val musicHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val movieHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val liveHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private val religionHomeCache =
        ConcurrentHashMap<String, TimedHomeCache>()

    private fun getCachedHomePage(
        cache: ConcurrentHashMap<String, TimedHomeCache>,
        key: String
    ): HomePageResponse? {

        val cached =
            cache[key] ?: return null

        return if (
            cached.expiresAt >
            System.currentTimeMillis()
        ) {
            cached.response
        } else {
            cache.remove(key, cached)
            null
        }
    }

    private fun putCachedHomePage(
        cache: ConcurrentHashMap<String, TimedHomeCache>,
        key: String,
        response: HomePageResponse,
        ttlMs: Long
    ) {

        cache[key] =
            TimedHomeCache(
                System.currentTimeMillis() + ttlMs,
                response
            )
    }

    /*
     * --------------------------------------------------
     * FAST SEARCH
     * --------------------------------------------------
     *
     * Maximum 6 simultaneous searches.
     * Individual search timeout = 8 seconds.
     */

    private suspend fun fetchSearchItemsInBatches(
        queries: List<String>,
        batchSize: Int = 6
    ): List<List<InfoItem>> {

        val cleanQueries =
            queries
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val results =
            mutableListOf<List<InfoItem>>()

        for (
            batch in cleanQueries.chunked(batchSize)
        ) {

            val batchResults =
                coroutineScope {

                    batch.map { query ->

                        async(Dispatchers.IO) {

                            withTimeoutOrNull(
                                8_000L
                            ) {

                                try {

                                    val extractor =
                                        service.getSearchExtractor(
                                            query
                                        )

                                    extractor.fetchPage()

                                    extractor
                                        .initialPage
                                        .items
                                        .toList()

                                } catch (_: Exception) {

                                    emptyList()

                                }

                            } ?: emptyList()
                        }

                    }.awaitAll()
                }

            results.addAll(
                batchResults
            )
        }

        return results
    }

    /*
     * --------------------------------------------------
     * MAIN PAGE
     * --------------------------------------------------
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        when (request.data) {

            "music_india" ->
                return getIndianMusicPage(page)

            "movies" ->
                return getMoviesPage(page)

            "hindi_movies" ->
                return getHindiMoviesPage(page)

            "live" ->
                return getCuratedLivePage(page)

            "religion" ->
                return getReligionPage(page)
        }

        val key =
            request.data

        if (page == 1) {
            pageCache.remove(key)
        }

        val extractor =
            try {
                getKioskExtractor(
                    request.data
                )
            } catch (_: Exception) {

                return newHomePageResponse(
                    emptyList(),
                    false
                )
            }

        val pageData =
            try {

                if (page == 1) {

                    extractor.fetchPage()

                    extractor.initialPage.also {
                        pageCache[key] =
                            it.nextPage
                    }

                } else {

                    val next =
                        pageCache[key]
                            ?: return newHomePageResponse(
                                emptyList(),
                                false
                            )

                    extractor
                        .getPage(next)
                        .also {
                            pageCache[key] =
                                it.nextPage
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
                request.name
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
     * MUSIC
     * --------------------------------------------------
     */

    private suspend fun getIndianMusicPage(
        page: Int
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        getCachedHomePage(
            musicHomeCache,
            "music"
        )?.let {
            return it
        }

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                indianMusicQueries,
                6
            )
        ) {

            if (results.size >= 80) {
                break
            }

            for (item in items) {

                if (results.size >= 80) {
                    break
                }

                if (
                    item !is StreamInfoItem
                ) {
                    continue
                }

                if (
                    item.streamType !=
                    StreamType.VIDEO_STREAM
                ) {
                    continue
                }

                if (
                    item.isShortFormContent
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (title.isBlank()) {
                    continue
                }

                if (
                    containsAny(
                        title,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                if (
                    containsAny(
                        title,
                        pakistanMusicKeywords
                    )
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    containsAny(
                        uploader,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                if (
                    containsAny(
                        uploader,
                        pakistanMusicKeywords
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
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                    }
                )
            }
        }

        val rotated =
            rotateHomeResults(
                results = results,
                fixedCount = 8,
                visibleCount = 40,
                rotationMinutes = 15,
                seedKey = "music"
            )

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Trending Music Videos",
                        rotated,
                        false
                    )
                ),
                false
            )

        putCachedHomePage(
            musicHomeCache,
            "music",
            response,
            15 * 60 * 1000L
        )

        return response
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

        getCachedHomePage(
            movieHomeCache,
            "movies"
        )?.let {
            return it
        }

        val candidates =
            mutableListOf<ScoredMovie>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                movieQueries,
                5
            )
        ) {

            for (item in items) {

                if (
                    item !is StreamInfoItem
                ) {
                    continue
                }

                if (
                    item.streamType !=
                    StreamType.VIDEO_STREAM
                ) {
                    continue
                }

                if (
                    item.isShortFormContent
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (title.isBlank()) {
                    continue
                }

                if (
                    containsAny(
                        title,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    containsAny(
                        uploader,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                if (
                    looksLikeNonMovieUpload(
                        title
                    )
                ) {
                    continue
                }

                candidates.add(
                    ScoredMovie(
                        response =
                            newMovieSearchResponse(
                                title,
                                url,
                                TvType.Movie
                            ) {

                                posterUrl =
                                    item.thumbnails
                                        .lastOrNull()
                                        ?.url
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                            },
                        score =
                            scoreGeneralMovie(
                                title,
                                uploader
                            )
                    )
                )
            }
        }

        val ranked =
            candidates
                .sortedByDescending {
                    it.score
                }
                .map {
                    it.response
                }

        val rotated =
            rotateHomeResults(
                results = ranked,
                fixedCount = 10,
                visibleCount = 40,
                rotationMinutes = 15,
                seedKey = "movies"
            )

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Movies",
                        rotated,
                        false
                    )
                ),
                false
            )

        putCachedHomePage(
            movieHomeCache,
            "movies",
            response,
            15 * 60 * 1000L
        )

        return response
    }

    /*
     * --------------------------------------------------
     * HINDI MOVIES
     * --------------------------------------------------
     */

    private suspend fun getHindiMoviesPage(
        page: Int
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        getCachedHomePage(
            movieHomeCache,
            "hindi_movies"
        )?.let {
            return it
        }

        val candidates =
            mutableListOf<ScoredMovie>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            items in fetchSearchItemsInBatches(
                hindiMovieQueries,
                6
            )
        ) {

            for (item in items) {

                if (
                    item !is StreamInfoItem
                ) {
                    continue
                }

                if (
                    item.streamType !=
                    StreamType.VIDEO_STREAM
                ) {
                    continue
                }

                if (
                    item.isShortFormContent
                ) {
                    continue
                }

                val url =
                    item.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    item.name
                        ?.trim()
                        ?: continue

                if (title.isBlank()) {
                    continue
                }

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                val combined =
                    "$title $uploader"

                if (
                    containsAny(
                        combined,
                        bangladeshKeywords
                    )
                ) {
                    continue
                }

                if (
                    looksLikeNonMovieUpload(
                        title
                    )
                ) {
                    continue
                }

                candidates.add(
                    ScoredMovie(
                        response =
                            newMovieSearchResponse(
                                title,
                                url,
                                TvType.Movie
                            ) {

                                posterUrl =
                                    item.thumbnails
                                        .lastOrNull()
                                        ?.url
                                        ?.takeIf {
                                            it.isNotBlank()
                                        }
                            },
                        score =
                            scoreHindiMovie(
                                title,
                                uploader
                            )
                    )
                )
            }
        }

        val ranked =
            candidates
                .sortedByDescending {
                    it.score
                }
                .map {
                    it.response
                }

        val rotated =
            rotateHomeResults(
                results = ranked,
                fixedCount = 12,
                visibleCount = 40,
                rotationMinutes = 15,
                seedKey = "hindi_movies"
            )

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Hindi Movies",
                        rotated,
                        false
                    )
                ),
                false
            )

        putCachedHomePage(
            movieHomeCache,
            "hindi_movies",
            response,
            15 * 60 * 1000L
        )

        return response
    }

    /*
     * --------------------------------------------------
     * LIVE
     * --------------------------------------------------
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

        getCachedHomePage(
            liveHomeCache,
            "live"
        )?.let {
            return it
        }

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            batch in allowedLiveChannels.chunked(8)
        ) {

            val found =
                coroutineScope {

                    batch.map { channel ->

                        async(Dispatchers.IO) {

                            withTimeoutOrNull(
                                8_000L
                            ) {

                                try {

                                    val extractor =
                                        service.getSearchExtractor(
                                            "$channel live"
                                        )

                                    extractor.fetchPage()

                                    selectOldestLiveForChannel(
                                        channel,
                                        extractor
                                            .initialPage
                                            .items
                                    )

                                } catch (_: Exception) {

                                    null
                                }

                            }
                        }

                    }.awaitAll()
                }

            for (
                selected in found.filterNotNull()
            ) {

                val url =
                    selected.url
                        ?.trim()
                        ?: continue

                if (
                    url.isBlank() ||
                    !seenUrls.add(url)
                ) {
                    continue
                }

                val title =
                    selected.name
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
                            selected.thumbnails
                                .lastOrNull()
                                ?.url
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                    }
                )
            }
        }

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Live",
                        results,
                        false
                    )
                ),
                false
            )

        putCachedHomePage(
            liveHomeCache,
            "live",
            response,
            60 * 1000L
        )

        return response
    }

    private fun selectOldestLiveForChannel(
        allowedChannel: String,
        items: List<InfoItem>
    ): StreamInfoItem? {

        val candidates =
            mutableListOf<StreamInfoItem>()

        for (item in items) {

            if (
                item !is StreamInfoItem
            ) {
                continue
            }

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

        return candidates.minWithOrNull(
            compareBy<StreamInfoItem> {

                it.uploadDate
                    ?.instant
                    ?.toEpochMilli()
                    ?: Long.MAX_VALUE
            }
        )
    }

    /*
     * --------------------------------------------------
     * RELIGION
     * --------------------------------------------------
     */

    private enum class ReligionLanguage {
        BENGALI,
        HINDI
    }

    private data class ReligionPlaylistCandidate(
        val title: String,
        val url: String,
        val thumbnail: String?,
        val uploader: String,
        val language: ReligionLanguage,
        val seriesKey: String,
        val score: Int
    )

    private fun religionCandidateToSearchResponse(
        candidate: ReligionPlaylistCandidate
    ): SearchResponse {

        return newMovieSearchResponse(
            candidate.title,
            candidate.url,
            TvType.TvSeries
        ) {

            posterUrl =
                candidate.thumbnail
        }
    }

    /*
     * --------------------------------------------------
     * RELIGION
     *
     * Bengali first
     * Hindi second
     *
     * Each language is deduplicated separately.
     * --------------------------------------------------
     */

    private suspend fun getReligionPage(
        page: Int
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        getCachedHomePage(
            religionHomeCache,
            "religion"
        )?.let {
            return it
        }

        val bengaliResults =
            mutableListOf<ReligionPlaylistCandidate>()

        val hindiResults =
            mutableListOf<ReligionPlaylistCandidate>()

        for (
            batch in bengaliDubbedShows.chunked(4)
        ) {

            val found =
                coroutineScope {

                    batch.map { show ->

                        async {
                            findBestBengaliPlaylist(
                                show
                            )
                        }

                    }.awaitAll()
                }

            found
                .filterNotNull()
                .forEach {
                    bengaliResults.add(it)
                }
        }

        for (
            batch in bengaliDubbedShows.chunked(4)
        ) {

            val found =
                coroutineScope {

                    batch.map { show ->

                        async {
                            findBestHindiPlaylist(
                                show
                            )
                        }

                    }.awaitAll()
                }

            found
                .filterNotNull()
                .forEach {
                    hindiResults.add(it)
                }
        }

        val existingHindiKeys =
            hindiResults
                .map {
                    it.seriesKey
                }
                .toMutableSet()

        for (
            batch in additionalHindiReligionShows.chunked(6)
        ) {

            if (
                hindiResults.size >= 30
            ) {
                break
            }

            val found =
                coroutineScope {

                    batch.map { query ->

                        async {

                            findBestAdditionalHindiPlaylist(
                                query
                            )
                        }

                    }.awaitAll()
                }

            for (
                candidate in found.filterNotNull()
            ) {

                if (
                    hindiResults.size >= 30
                ) {
                    break
                }

                if (
                    !existingHindiKeys.add(
                        candidate.seriesKey
                    )
                ) {
                    continue
                }

                hindiResults.add(
                    candidate
                )
            }
        }

        val finalResults =
            mutableListOf<SearchResponse>()

        finalResults.addAll(
            bengaliResults
                .distinctBy {
                    it.seriesKey
                }
                .map {
                    religionCandidateToSearchResponse(
                        it
                    )
                }
        )

        finalResults.addAll(
            hindiResults
                .distinctBy {
                    it.seriesKey
                }
                .map {
                    religionCandidateToSearchResponse(
                        it
                    )
                }
        )

        val response =
            newHomePageResponse(
                listOf(
                    HomePageList(
                        "Religion",
                        finalResults,
                        false
                    )
                ),
                false
            )

        putCachedHomePage(
            religionHomeCache,
            "religion",
            response,
            30 * 60 * 1000L
        )

        return response
    }

    /*
     * --------------------------------------------------
     * NOTE
     * --------------------------------------------------
     *
     * তোমার existing:
     *
     * BengaliDubbedShow catalog
     * additionalHindiReligionShows
     * religion aliases
     * Bengali validation
     * Hindi validation
     * playlist scoring
     * detectReligionSeriesKey
     * search()
     * load()
     * loadChannel()
     * loadPlaylist()
     * loadLinks()
     *
     * সব আগের optimized version-এর অংশ হিসেবেই থাকবে।
     *
     * Playback-এর direct DASH/HLS logic পরিবর্তন করা হয়নি।
     */
}
