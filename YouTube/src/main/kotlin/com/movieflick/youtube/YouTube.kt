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
     * HOME
     * --------------------------------------------------
     */

    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movie Trailers",
        "music_india" to "Trending Music Videos",
        "movies" to "Movies",
        "live" to "Live",
        "religion" to "Religion"
    )

    /*
     * --------------------------------------------------
     * LIVE CHANNELS
     * --------------------------------------------------
     */

    private val allowedLiveChannels = listOf(

        // Indian Bengali News
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

        // Bangladeshi Bengali News
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

        // Indian Hindi News
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

        // Indian English News
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
     * BENGALI RELIGION PLAYLIST SEARCH
     * --------------------------------------------------
     *
     * Bengali is always searched first.
     */

    private val bengaliReligionQueries = listOf(
        "বাংলা মহাভারত সম্পূর্ণ পর্ব playlist",
        "বাংলা মহাভারত সিরিয়াল playlist",
        "বাংলা রামায়ণ সম্পূর্ণ পর্ব playlist",
        "বাংলা রামায়ণ সিরিয়াল playlist",
        "বাংলা জয় হনুমান সিরিয়াল playlist",
        "বাংলা শ্রীকৃষ্ণ সিরিয়াল playlist",
        "বাংলা কৃষ্ণ সিরিয়াল সম্পূর্ণ পর্ব playlist",
        "বাংলা বিষ্ণু পুরাণ সিরিয়াল playlist",
        "বাংলা মহাদেব সিরিয়াল playlist",
        "বাংলা শিব পুরাণ সিরিয়াল playlist",
        "বাংলা গণেশ সিরিয়াল playlist",
        "বাংলা রাধাকৃষ্ণ সিরিয়াল playlist",
        "বাংলা সীতারাম সিরিয়াল playlist",
        "বাংলা হিন্দু পৌরাণিক সিরিয়াল playlist",

        "Bengali Mahabharat full episodes playlist",
        "Bengali Ramayan full episodes playlist",
        "Bengali Jai Hanuman serial full episodes playlist",
        "Bengali Shri Krishna serial full episodes playlist",
        "Bengali Hindu mythological serial full episodes playlist",
        "Bengali Hindu religious serial playlist"
    )

    /*
     * --------------------------------------------------
     * HINDI RELIGION PLAYLIST SEARCH
     * --------------------------------------------------
     */

    private val hindiReligionQueries = listOf(
        "Mahabharat Hindi serial full episodes playlist",
        "Mahabharat Star Plus full episodes playlist",
        "Ramayan Hindi serial full episodes playlist",
        "Ramayan full episodes Hindi playlist",
        "Jai Hanuman Hindi serial full episodes playlist",
        "Jai Shri Krishna Hindi serial full episodes playlist",
        "Shree Krishna Hindi serial full episodes playlist",
        "Vishnu Puran Hindi serial full episodes playlist",
        "Devon Ke Dev Mahadev full episodes playlist",
        "Om Namah Shivay Hindi serial full episodes playlist",
        "Shree Ganesh Hindi serial full episodes playlist",
        "RadhaKrishn Hindi serial full episodes playlist",
        "Siya Ke Ram full episodes playlist",
        "Suryaputra Karn full episodes playlist",
        "Mahakali Anth Hi Aarambh Hai full episodes playlist",
        "Vighnaharta Ganesh full episodes playlist",
        "Ram Siya Ke Luv Kush full episodes playlist",
        "Hindu mythological serial full episodes playlist Hindi"
    )

    /*
     * --------------------------------------------------
     * RELIGION FILTERS
     * --------------------------------------------------
     */

    private val religionExcludeKeywords = listOf(
        "bhajan",
        "bhajans",
        "aarti",
        "aartis",
        "mantra",
        "mantras",
        "song",
        "songs",
        "music",
        "playlist songs",
        "status",
        "shorts",
        "remix",
        "dj"
    )

    private val religionIncludeKeywords = listOf(
        "mahabharat",
        "mahabharata",
        "মহাভারত",

        "ramayan",
        "ramayana",
        "রামায়ণ",
        "রামায়ণ",

        "hanuman",
        "হনুমান",
        "bajrang",

        "krishna",
        "কৃষ্ণ",
        "shri krishna",
        "শ্রীকৃষ্ণ",

        "vishnu",
        "বিষ্ণু",
        "vishnu puran",

        "mahadev",
        "মহাদেব",
        "devon ke dev",

        "shiv",
        "shiva",
        "শিব",
        "om namah shivay",

        "ganesh",
        "ganesha",
        "গণেশ",
        "vighnaharta",

        "radha",
        "রাধা",
        "radha krishn",

        "sita",
        "সীতা",
        "siya ke ram",

        "suryaputra karn",
        "karn",
        "karna",

        "mahakali",
        "durga",
        "দুর্গা",
        "parvati",
        "পার্বতী",

        "shani",
        "narayan",
        "নারায়ণ",
        "ram siya",

        "mythological",
        "পৌরাণিক",
        "ধর্মীয়",
        "ধর্মীয়"
    )

    /*
     * --------------------------------------------------
     * OFFICIAL / MAIN SOURCE SIGNALS
     * --------------------------------------------------
     *
     * These words increase the score of an official
     * broadcaster / main publisher playlist.
     */

    private val officialSourceKeywords = listOf(
        "official",
        "official channel",
        "official playlist",
        "star jalsha",
        "jalsha",
        "starplus",
        "star plus",
        "sony",
        "sony tv",
        "sony entertainment",
        "sony sab",
        "sab tv",
        "colors",
        "colors tv",
        "colors bangla",
        "zee",
        "zee bangla",
        "zee tv",
        "zee5",
        "sagar pictures",
        "sagar films",
        "t series",
        "t-series",
        "shemaroo",
        "shemaroo tv",
        "epic",
        "epic tv",
        "dd national",
        "doordarshan",
        "dd bangla",
        "ramayan",
        "sita ram",
        "star",
        "viacom",
        "network18"
    )

    /*
     * Words commonly associated with personal/reposted
     * playlists. These receive a penalty.
     */

    private val userPlaylistPenaltyKeywords = listOf(
        "my playlist",
        "my collection",
        "collection",
        "saved",
        "favourite",
        "favorites",
        "favorite",
        "best episodes",
        "all episodes collection",
        "fan made",
        "fanmade",
        "fans",
        "fan club",
        "personal",
        "backup",
        "reupload",
        "re-upload",
        "archive",
        "clips",
        "mixed",
        "mix",
        "part 1",
        "part 2",
        "part 3"
    )

    /*
     * Known serial groups.
     *
     * The key is used to make sure the same serial
     * cannot appear repeatedly.
     */

    private val religionSeriesAliases = mapOf(

        "mahabharat" to listOf(
            "mahabharat",
            "mahabharata",
            "মহাভারত"
        ),

        "ramayan" to listOf(
            "ramayan",
            "ramayana",
            "রামায়ণ",
            "রামায়ণ"
        ),

        "jai_hanuman" to listOf(
            "jai hanuman",
            "জয় হনুমান",
            "জয় হনুমান"
        ),

        "krishna" to listOf(
            "jai shri krishna",
            "shree krishna",
            "shri krishna",
            "radha krishna",
            "radha krishn",
            "শ্রীকৃষ্ণ",
            "রাধাকৃষ্ণ"
        ),

        "vishnu_puran" to listOf(
            "vishnu puran",
            "বিষ্ণু পুরাণ"
        ),

        "devon_ke_dev_mahadev" to listOf(
            "devon ke dev mahadev",
            "devon ke dev mahadev",
            "mahadev"
        ),

        "om_namah_shivay" to listOf(
            "om namah shivay",
            "om namah shivaya"
        ),

        "ganesh" to listOf(
            "vighnaharta ganesh",
            "shree ganesh",
            "shri ganesh",
            "ganesh",
            "গণেশ"
        ),

        "siya_ke_ram" to listOf(
            "siya ke ram",
            "ram siya ke luv kush",
            "siya ke ram"
        ),

        "suryaputra_karn" to listOf(
            "suryaputra karn",
            "suryaputra karna",
            "suryaputra"
        ),

        "mahakali" to listOf(
            "mahakali",
            "mahakali anth hi aarambh hai"
        ),

        "durga" to listOf(
            "durga",
            "দুর্গা"
        ),

        "parvati" to listOf(
            "parvati",
            "পার্বতী"
        ),

        "shani" to listOf(
            "shani",
            "shani dev",
            "শনি"
        )
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

        when (request.data) {

            "music_india" ->
                return getIndianMusicPage(page)

            "movies" ->
                return getMoviesPage(page)

            "live" ->
                return getCuratedLivePage(page)

            "religion" ->
                return getReligionPage(page)
        }

        val key = request.data

        if (page == 1) {
            pageCache.remove(key)
        }

        val extractor = try {
            getKioskExtractor(request.data)
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

                extractor.getPage(next).also {
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
     * INDIAN MUSIC
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

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

        for (query in indianMusicQueries) {

            if (results.size >= 40) {
                break
            }

            try {

                val extractor =
                    service.getSearchExtractor(query)

                extractor.fetchPage()

                for (item in extractor.initialPage.items) {

                    if (results.size >= 40) {
                        break
                    }

                    if (item !is StreamInfoItem) {
                        continue
                    }

                    if (
                        item.streamType !=
                        StreamType.VIDEO_STREAM
                    ) {
                        continue
                    }

                    if (item.isShortFormContent) {
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
                    "Trending Music Videos",
                    results,
                    false
                )
            ),
            false
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

        for (query in movieQueries) {

            if (results.size >= 40) {
                break
            }

            try {

                val extractor =
                    service.getSearchExtractor(query)

                extractor.fetchPage()

                for (item in extractor.initialPage.items) {

                    if (results.size >= 40) {
                        break
                    }

                    if (item !is StreamInfoItem) {
                        continue
                    }

                    if (
                        item.streamType !=
                        StreamType.VIDEO_STREAM
                    ) {
                        continue
                    }

                    if (item.isShortFormContent) {
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

                    if (
                        containsAny(
                            title,
                            bangladeshKeywords
                        )
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

        val results =
            mutableListOf<SearchResponse>()

        val seenUrls =
            mutableSetOf<String>()

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
     * OLDEST LIVE STREAM
     * --------------------------------------------------
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
     *
     * IMPORTANT:
     *
     * Bengali candidates are collected first.
     * Hindi candidates are collected second.
     *
     * Duplicate serials are removed by SERIES KEY,
     * NOT by playlist URL.
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

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenPlaylistUrls =
            mutableSetOf<String>()

        /*
         * ----------------------------------------------
         * BENGALI FIRST
         * ----------------------------------------------
         */

        collectReligionCandidates(
            queries = bengaliReligionQueries,
            language = ReligionLanguage.BENGALI,
            candidates = candidates,
            seenPlaylistUrls = seenPlaylistUrls
        )

        /*
         * ----------------------------------------------
         * HINDI SECOND
         * ----------------------------------------------
         */

        collectReligionCandidates(
            queries = hindiReligionQueries,
            language = ReligionLanguage.HINDI,
            candidates = candidates,
            seenPlaylistUrls = seenPlaylistUrls
        )

        /*
         * ----------------------------------------------
         * DEDUPLICATE BY SERIAL
         * ----------------------------------------------
         */

        val selected =
            selectBestReligionPlaylists(
                candidates
            )

        val results =
            selected.map { candidate ->

                newMovieSearchResponse(
                    candidate.title,
                    candidate.url,
                    TvType.TvSeries
                ) {
                    posterUrl =
                        candidate.thumbnail
                }
            }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Religion",
                    results,
                    false
                )
            ),
            false
        )
    }

    /*
     * --------------------------------------------------
     * RELIGION CANDIDATE
     * --------------------------------------------------
     */

    private data class ReligionPlaylistCandidate(
        val title: String,
        val url: String,
        val thumbnail: String?,
        val uploader: String,
        val language: ReligionLanguage,
        val seriesKey: String,
        val score: Int
    )

    private enum class ReligionLanguage {
        BENGALI,
        HINDI
    }

    /*
     * --------------------------------------------------
     * COLLECT RELIGION CANDIDATES
     * --------------------------------------------------
     */

    private suspend fun collectReligionCandidates(
        queries: List<String>,
        language: ReligionLanguage,
        candidates: MutableList<ReligionPlaylistCandidate>,
        seenPlaylistUrls: MutableSet<String>
    ) {

        for (query in queries) {

            try {

                val extractor =
                    service.getSearchExtractor(query)

                extractor.fetchPage()

                for (item in extractor.initialPage.items) {

                    if (item !is PlaylistInfoItem) {
                        continue
                    }

                    val url =
                        item.url
                            ?.trim()
                            ?: continue

                    if (url.isBlank()) {
                        continue
                    }

                    if (
                        !url.contains(
                            "playlist?list=",
                            ignoreCase = true
                        )
                    ) {
                        continue
                    }

                    if (
                        !seenPlaylistUrls.add(url)
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
                            religionExcludeKeywords
                        )
                    ) {
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
                        !containsAny(
                            title,
                            religionIncludeKeywords
                        )
                    ) {
                        continue
                    }

                    val uploader =
                        item.uploaderName
                            ?.trim()
                            ?: ""

                    /*
                     * Determine which serial this playlist
                     * belongs to.
                     */

                    val seriesKey =
                        detectReligionSeriesKey(
                            title
                        )

                    if (seriesKey == null) {
                        continue
                    }

                    val thumbnail =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    val score =
                        calculateReligionPlaylistScore(
                            title = title,
                            uploader = uploader,
                            language = language,
                            seriesKey = seriesKey
                        )

                    candidates.add(
                        ReligionPlaylistCandidate(
                            title = title,
                            url = url,
                            thumbnail = thumbnail,
                            uploader = uploader,
                            language = language,
                            seriesKey = seriesKey,
                            score = score
                        )
                    )
                }

            } catch (_: Exception) {
                continue
            }
        }
    }

    /*
     * --------------------------------------------------
     * SELECT BEST PLAYLISTS
     * --------------------------------------------------
     *
     * This is the important duplicate-removal step.
     *
     * Same seriesKey = same serial.
     *
     * Only ONE playlist is retained for each serial
     * and language.
     */

    private fun selectBestReligionPlaylists(
        candidates: List<ReligionPlaylistCandidate>
    ): List<ReligionPlaylistCandidate> {

        /*
         * First group by:
         *
         * serial + language
         *
         * This means Bengali Mahabharat and Hindi
         * Mahabharat can both exist, because they are
         * different language versions.
         */

        val groups =
            candidates.groupBy {
                "${it.language.name}:"
                    .plus(it.seriesKey)
            }

        val selected =
            mutableListOf<ReligionPlaylistCandidate>()

        for ((_, group) in groups) {

            val best =
                group.maxWithOrNull(
                    compareBy<ReligionPlaylistCandidate> {
                        it.score
                    }.thenByDescending {
                        it.title.length
                    }
                )

            if (best != null) {
                selected.add(best)
            }
        }

        /*
         * Bengali groups FIRST.
         * Hindi groups SECOND.
         */

        return selected.sortedWith(
            compareBy<ReligionPlaylistCandidate> {
                when (it.language) {
                    ReligionLanguage.BENGALI -> 0
                    ReligionLanguage.HINDI -> 1
                }
            }.thenByDescending {
                it.score
            }
        )
    }

    /*
     * --------------------------------------------------
     * DETECT SERIAL KEY
     * --------------------------------------------------
     */

    private fun detectReligionSeriesKey(
        title: String
    ): String? {

        val normalized =
            normalizeReligionText(title)

        /*
         * Check longer/specific aliases first.
         */

        val ordered =
            religionSeriesAliases.entries.sortedByDescending {
                it.value.maxOf { alias ->
                    alias.length
                }
            }

        for ((key, aliases) in ordered) {

            for (alias in aliases) {

                val normalizedAlias =
                    normalizeReligionText(
                        alias
                    )

                if (
                    normalized.contains(
                        normalizedAlias
                    )
                ) {
                    return key
                }
            }
        }

        return null
    }

    /*
     * --------------------------------------------------
     * PLAYLIST SCORE
     * --------------------------------------------------
     *
     * Higher score = more likely to be the main /
     * official playlist.
     */

    private fun calculateReligionPlaylistScore(
        title: String,
        uploader: String,
        language: ReligionLanguage,
        seriesKey: String
    ): Int {

        var score = 0

        val combined =
            "$title $uploader"
                .lowercase()

        /*
         * Bengali gets an explicit language bonus.
         *
         * The ordering itself is already Bengali first,
         * but this also helps select the better Bengali
         * candidate when multiple Bengali playlists exist.
         */

        if (
            language ==
            ReligionLanguage.BENGALI
        ) {
            score += 20
        } else {
            score += 10
        }

        /*
         * Official / broadcaster signals.
         */

        for (keyword in officialSourceKeywords) {

            if (
                combined.contains(
                    keyword.lowercase()
                )
            ) {
                score += 25
            }
        }

        /*
         * Explicit official wording.
         */

        if (
            combined.contains("official")
        ) {
            score += 35
        }

        /*
         * Complete/full episode signals.
         */

        if (
            combined.contains(
                "full episodes"
            )
        ) {
            score += 15
        }

        if (
            combined.contains(
                "complete episodes"
            )
        ) {
            score += 15
        }

        if (
            combined.contains(
                "all episodes"
            )
        ) {
            score += 10
        }

        if (
            combined.contains(
                "সম্পূর্ণ"
            )
        ) {
            score += 15
        }

        if (
            combined.contains(
                "সব পর্ব"
            )
        ) {
            score += 10
        }

        /*
         * Penalty for obvious personal/copied playlists.
         */

        for (keyword in userPlaylistPenaltyKeywords) {

            if (
                combined.contains(
                    keyword.lowercase()
                )
            ) {
                score -= 30
            }
        }

        /*
         * Very generic playlist titles get a small penalty.
         */

        val normalizedTitle =
            normalizeReligionText(title)

        if (
            normalizedTitle == "playlist"
        ) {
            score -= 20
        }

        if (
            normalizedTitle.length < 8
        ) {
            score -= 5
        }

        /*
         * If uploader is present, give a small bonus.
         */

        if (uploader.isNotBlank()) {
            score += 5
        }

        /*
         * Specific serial aliases in the title are useful.
         */

        val aliases =
            religionSeriesAliases[
                seriesKey
            ].orEmpty()

        if (
            aliases.any {
                normalizedTitle.contains(
                    normalizeReligionText(it)
                )
            }
        ) {
            score += 10
        }

        return score
    }

    /*
     * --------------------------------------------------
     * NORMALIZE RELIGION TEXT
     * --------------------------------------------------
     */

    private fun normalizeReligionText(
        value: String
    ): String {

        return value
            .lowercase()
            .replace(
                Regex("[^\\p{L}\\p{N}]+"),
                ""
            )
    }

    /*
     * --------------------------------------------------
     * STRING FILTER
     * --------------------------------------------------
     */

    private fun containsAny(
        text: String,
        keywords: List<String>
    ): Boolean {

        val lower =
            text.lowercase()

        return keywords.any {
            lower.contains(
                it.lowercase()
            )
        }
    }

    /*
     * --------------------------------------------------
     * CHANNEL COMPARISON
     * --------------------------------------------------
     */

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

                extractor.getPage(next).also {
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
     * INFO ITEM
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
            service.getStreamExtractor(url)

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
            service.getChannelExtractor(url)

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
            }
                ?: tabs.firstOrNull()
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
     * PLAYLIST LOAD
     * --------------------------------------------------
     */

    private suspend fun loadPlaylist(
        url: String
    ): LoadResponse {

        val extractor =
            service.getPlaylistExtractor(url)

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

        /*
         * First playlist page.
         */

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

        /*
         * Additional playlist pages.
         */

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
     * IMPORTANT:
     *
     * DO NOT CHANGE THIS SECTION.
     *
     * VOD -> DASH
     * LIVE -> HLS
     * fallback -> CloudStream extractor
     *
     * This keeps the current high-speed playback path.
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
             * LIVE -> HLS
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
             * VOD -> DASH
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
             * HLS fallback.
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
             * Fallback to CloudStream's working
             * YouTube extractor.
             */
        }

        return loadExtractor(
            data,
            subtitleCallback,
            callback
        )
    }
}
