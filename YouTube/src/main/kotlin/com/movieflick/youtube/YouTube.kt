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
     * BENGALI DUBBED RELIGIOUS SERIALS
     * --------------------------------------------------
     *
     * ONLY Hindi-origin religious/mythological shows
     * that have Bengali dubbed versions are targeted here.
     *
     * Direct Kolkata-original religious serials are NOT
     * the priority.
     *
     * Bengali candidates are always collected first.
     */

    private data class BengaliDubbedShow(
        val key: String,
        val bengaliNames: List<String>,
        val hindiNames: List<String>,
        val officialChannels: List<String>
    )

    private val bengaliDubbedShows = listOf(

        BengaliDubbedShow(
            key = "mahabharat",
            bengaliNames = listOf(
                "মহাভারত",
                "Mahabharat Bangla",
                "Mahabharat Bengali",
                "Mahabharat Bengali dubbed",
                "Mahabharat Bangla dubbed"
            ),
            hindiNames = listOf(
                "Mahabharat 2013",
                "Mahabharat Star Plus"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Plus",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "siya_ke_ram",
            bengaliNames = listOf(
                "সীতা",
                "Sita Bangla",
                "Sita Bengali",
                "Siya Ke Ram Bengali",
                "Siya Ke Ram Bangla"
            ),
            hindiNames = listOf(
                "Siya Ke Ram",
                "Siya Ke Ram Star Plus"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Plus"
            )
        ),

        BengaliDubbedShow(
            key = "jai_shri_krishna",
            bengaliNames = listOf(
                "জয় শ্রী কৃষ্ণ",
                "জয় শ্রী কৃষ্ণ",
                "Jai Shri Krishna Bengali",
                "Jai Shri Krishna Bangla"
            ),
            hindiNames = listOf(
                "Jai Shri Krishna",
                "Jai Shri Krishna Colors"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV",
                "Sagar Pictures"
            )
        ),

        BengaliDubbedShow(
            key = "devadidev_mahadev",
            bengaliNames = listOf(
                "দেবাদিদেব মহাদেব",
                "দেবাদিদেব মহাদেব বাংলা",
                "Devadidev Mahadev Bangla",
                "Devon Ke Dev Mahadev Bengali",
                "Devon Ke Dev Mahadev Bangla"
            ),
            hindiNames = listOf(
                "Devon Ke Dev Mahadev",
                "Devon Ke Dev Mahadev Life OK"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Life OK",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "sankatmochan_hanuman",
            bengaliNames = listOf(
                "মহাবলী হনুমান",
                "মহাবলী হনুমান বাংলা",
                "Sankatmochan Mahabali Hanuman Bengali",
                "Sankatmochan Mahabali Hanuman Bangla"
            ),
            hindiNames = listOf(
                "Sankatmochan Mahabali Hanuman",
                "Mahabali Hanuman Sony"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "radhakrishn",
            bengaliNames = listOf(
                "রাধা কৃষ্ণ",
                "রাধাকৃষ্ণ",
                "Radha Krishna Bengali",
                "RadhaKrishn Bangla",
                "RadhaKrishn Bengali"
            ),
            hindiNames = listOf(
                "RadhaKrishn",
                "Radha Krishn Star Bharat"
            ),
            officialChannels = listOf(
                "Star Jalsha",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "karmaphal_shani",
            bengaliNames = listOf(
                "কর্মফল দাতা শনি",
                "কর্মফলদাতা শনি",
                "জয় জয় শনি দেব",
                "Karmaphal Daata Shani Bengali",
                "Karmaphal Daata Shani Bangla"
            ),
            hindiNames = listOf(
                "Karmaphal Daata Shani",
                "Shani Colors TV"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "vighnaharta_ganesh",
            bengaliNames = listOf(
                "বিঘ্নহর্তা শ্রী গণেশ",
                "বিঘ্নহর্তা গণেশ",
                "Vighnaharta Ganesh Bengali",
                "Vighnaharta Ganesh Bangla"
            ),
            hindiNames = listOf(
                "Vighnaharta Ganesh",
                "Vighnaharta Shree Ganesh"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "shrimad_ramayan",
            bengaliNames = listOf(
                "শ্রীমদ রামায়ণ",
                "শ্রীমদ রামায়ণ",
                "Shrimad Ramayan Bangla",
                "Shrimad Ramayan Bengali"
            ),
            hindiNames = listOf(
                "Shrimad Ramayan",
                "Shrimad Ramayan Sony"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television",
                "Sony LIV"
            )
        ),

        BengaliDubbedShow(
            key = "legend_of_hanuman",
            bengaliNames = listOf(
                "The Legend of Hanuman Bengali",
                "The Legend of Hanuman Bangla",
                "Legend of Hanuman Bangla"
            ),
            hindiNames = listOf(
                "The Legend of Hanuman",
                "Legend of Hanuman Hindi"
            ),
            officialChannels = listOf(
                "Disney",
                "DisneyPlusHotstar",
                "Hotstar"
            )
        ),

        BengaliDubbedShow(
            key = "suryaputra_karn",
            bengaliNames = listOf(
                "সূর্যপুত্র কর্ণ",
                "Suryaputra Karn Bengali",
                "Suryaputra Karn Bangla"
            ),
            hindiNames = listOf(
                "Suryaputra Karn"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television",
                "Sony Pal"
            )
        ),

        BengaliDubbedShow(
            key = "dwarkadheesh",
            bengaliNames = listOf(
                "দ্বারকাধীশ",
                "দ্বারকাধীশ ভগবান শ্রী কৃষ্ণ",
                "Dwarkadheesh Bengali",
                "Dwarkadheesh Bangla"
            ),
            hindiNames = listOf(
                "Dwarkadheesh Bhagwaan Shree Krishna",
                "Dwarkadheesh"
            ),
            officialChannels = listOf(
                "Life OK",
                "Star Bharat"
            )
        ),

        BengaliDubbedShow(
            key = "mahakali",
            bengaliNames = listOf(
                "মহাকালী",
                "মহাকালী অন্ত হি আরম্ভ হ্যায়",
                "Mahakali Bengali",
                "Mahakali Bangla"
            ),
            hindiNames = listOf(
                "Mahakali Anth Hi Aarambh Hai",
                "Mahakali Colors"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "jai_hanuman",
            bengaliNames = listOf(
                "জয় হনুমান",
                "জয় হনুমান",
                "Jai Hanuman Bengali",
                "Jai Hanuman Bangla"
            ),
            hindiNames = listOf(
                "Jai Hanuman"
            ),
            officialChannels = listOf(
                "Sony AATH",
                "Sony Entertainment Television"
            )
        ),

        BengaliDubbedShow(
            key = "mahima_shani",
            bengaliNames = listOf(
                "জয় জয় শনি দেব",
                "মহিমা শনি দেব কি বাংলা",
                "Mahima Shani Dev Ki Bengali",
                "Mahima Shani Dev Ki Bangla"
            ),
            hindiNames = listOf(
                "Mahima Shani Dev Ki"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "bal_krishna",
            bengaliNames = listOf(
                "বাল কৃষ্ণ",
                "Bal Krishna Bengali",
                "Bal Krishna Bangla"
            ),
            hindiNames = listOf(
                "Bal Krishna",
                "Bal Krishna Hindi serial"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        ),

        BengaliDubbedShow(
            key = "yashomati_nandlala",
            bengaliNames = listOf(
                "যশোমতী মাইয়া কে নন্দলালা",
                "Yashomati Maiyaa Ke Nandlala Bengali",
                "Yashomati Maiyaa Ke Nandlala Bangla"
            ),
            hindiNames = listOf(
                "Yashomati Maiyaa Ke Nandlala"
            ),
            officialChannels = listOf(
                "Colors Bangla",
                "Colors TV"
            )
        )
    )

    /*
     * --------------------------------------------------
     * ADDITIONAL HINDI RELIGIOUS SERIALS
     * --------------------------------------------------
     *
     * These are used after the Bengali-dubbed section.
     */

    private val additionalHindiReligionShows = listOf(
        "Ramayan Ramanand Sagar",
        "Shri Krishna Ramanand Sagar",
        "Mahabharat 1988",
        "Om Namah Shivay",
        "Vishnu Puran",
        "Jai Shri Krishna",
        "Devon Ke Dev Mahadev",
        "Vighnaharta Ganesh",
        "Jai Hanuman",
        "RadhaKrishn",
        "Siya Ke Ram",
        "Suryaputra Karn",
        "Karmaphal Daata Shani",
        "Mahakali Anth Hi Aarambh Hai",
        "Sankatmochan Mahabali Hanuman",
        "Dwarkadheesh Bhagwaan Shree Krishna",
        "Shani Dev",
        "Karamphal Data Shani",
        "Ganesh Leela",
        "Ram Siya Ke Luv Kush",
        "Jag Janani Maa Durga",
        "Jai Jag Janani Maa Durga",
        "Santoshi Maa",
        "Sita",
        "Sita Ram",
        "Luv Kush",
        "Radha Krishna",
        "Krishna Arjun",
        "Kahat Hanuman Jai Shri Ram",
        "Sharabha",
        "Paramavatar Shri Krishna",
        "RadhaKrishn full episodes",
        "Mahabharat full episodes Hindi",
        "Ramayan full episodes Hindi"
    )

    /*
     * --------------------------------------------------
     * RELIGION EXCLUDE
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
        "devotional songs",
        "playlist songs",
        "status",
        "shorts",
        "remix",
        "dj",
        "edit",
        "fan edit",
        "fan made",
        "fanmade",
        "reaction",
        "review",
        "explained",
        "story explained",
        "recap"
    )

    /*
     * --------------------------------------------------
     * BANGLADESH EXCLUDE
     * --------------------------------------------------
     */

    private val religionBangladeshKeywords = listOf(
        "bangladesh",
        "bangladeshi",
        "dhallywood",
        "dhaka",
        "bd",
        "bangla natok"
    )

    /*
     * --------------------------------------------------
     * RELIGION SERIES ALIASES
     * --------------------------------------------------
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

        "siya_ke_ram" to listOf(
            "siya ke ram",
            "sita",
            "সীতা"
        ),

        "jai_shri_krishna" to listOf(
            "jai shri krishna",
            "shree krishna",
            "shri krishna",
            "জয় শ্রী কৃষ্ণ",
            "জয় শ্রী কৃষ্ণ"
        ),

        "devadidev_mahadev" to listOf(
            "devon ke dev mahadev",
            "devadidev mahadev",
            "devadidev",
            "mahadev",
            "দেবাদিদেব মহাদেব",
            "মহাদেব"
        ),

        "sankatmochan_hanuman" to listOf(
            "sankatmochan mahabali hanuman",
            "mahabali hanuman",
            "মহাবলী হনুমান"
        ),

        "radhakrishn" to listOf(
            "radhakrishn",
            "radha krishna",
            "radha krishn",
            "রাধাকৃষ্ণ",
            "রাধা কৃষ্ণ"
        ),

        "karmaphal_shani" to listOf(
            "karmaphal daata shani",
            "karmphal data shani",
            "karmaphal shani",
            "কর্মফল দাতা শনি"
        ),

        "vighnaharta_ganesh" to listOf(
            "vighnaharta ganesh",
            "shree ganesh",
            "shri ganesh",
            "বিঘ্নহর্তা গণেশ",
            "গণেশ"
        ),

        "shrimad_ramayan" to listOf(
            "shrimad ramayan",
            "শ্রীমদ রামায়ণ",
            "শ্রীমদ রামায়ণ"
        ),

        "legend_of_hanuman" to listOf(
            "legend of hanuman"
        ),

        "suryaputra_karn" to listOf(
            "suryaputra karn",
            "suryaputra karna",
            "সূর্যপুত্র কর্ণ"
        ),

        "dwarkadheesh" to listOf(
            "dwarkadheesh",
            "dwarkadhish",
            "দ্বারকাধীশ"
        ),

        "mahakali" to listOf(
            "mahakali",
            "mahakali anth hi aarambh hai",
            "মহাকালী"
        ),

        "jai_hanuman" to listOf(
            "jai hanuman",
            "জয় হনুমান",
            "জয় হনুমান"
        ),

        "mahima_shani" to listOf(
            "mahima shani dev ki",
            "mahima shani",
            "জয় জয় শনি দেব"
        ),

        "bal_krishna" to listOf(
            "bal krishna",
            "বাল কৃষ্ণ"
        ),

        "yashomati_nandlala" to listOf(
            "yashomati maiyaa ke nandlala",
            "yashomati",
            "যশোমতী"
        ),

        "om_namah_shivay" to listOf(
            "om namah shivay",
            "om namah shivaya"
        ),

        "vishnu_puran" to listOf(
            "vishnu puran",
            "বিষ্ণু পুরাণ"
        ),

        "ram_siyaa_ke_luv_kush" to listOf(
            "ram siya ke luv kush",
            "ram siya ke luvkush"
        ),

        "jag_janani_durga" to listOf(
            "jag janani maa durga",
            "jag janani durga"
        ),

        "santoshi_maa" to listOf(
            "santoshi maa",
            "santoshi ma"
        ),

        "paramavatar_shri_krishna" to listOf(
            "paramavatar shri krishna",
            "paramavatar krishna"
        ),

        "kahat_hanuman" to listOf(
            "kahat hanuman jai shri ram"
        ),

        "shani_dev" to listOf(
            "shani dev",
            "shani"
        ),

        "krishna_arjun" to listOf(
            "krishna arjun"
        ),

        "ganesh_leela" to listOf(
            "ganesh leela"
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
     * OLDEST LIVE
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
     * FINAL ORDER:
     *
     * 1. Bengali dubbed religious serials
     * 2. Hindi versions of those serials
     * 3. Other Hindi religious/mythological serials
     *
     * IMPORTANT:
     *
     * Bengali Mahabharat != Hindi Mahabharat
     *
     * Therefore both can appear.
     *
     * But:
     *
     * Bengali Mahabharat playlist x10
     * -> ONLY ONE Bengali Mahabharat
     *
     * Hindi Mahabharat playlist x10
     * -> ONLY ONE Hindi Mahabharat
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

        /*
         * ----------------------------------------------
         * BENGALI DUBBED SECTION
         * ----------------------------------------------
         */

        val bengaliResults =
            mutableListOf<ReligionPlaylistCandidate>()

        for (show in bengaliDubbedShows) {

            if (bengaliResults.size >= 18) {
                break
            }

            val best =
                findBestBengaliPlaylist(
                    show
                )

            if (best != null) {
                bengaliResults.add(best)
            }
        }

        /*
         * ----------------------------------------------
         * HINDI VERSION OF SAME POPULAR SHOWS
         * ----------------------------------------------
         *
         * Even if Bengali version exists, Hindi version
         * MUST also be allowed.
         */

        val hindiResults =
            mutableListOf<ReligionPlaylistCandidate>()

        for (show in bengaliDubbedShows) {

            if (hindiResults.size >= 18) {
                break
            }

            val best =
                findBestHindiPlaylist(
                    show
                )

            if (best != null) {
                hindiResults.add(best)
            }
        }

        /*
         * ----------------------------------------------
         * ADDITIONAL HINDI SHOWS
         * ----------------------------------------------
         */

        if (hindiResults.size < 30) {

            val existingHindiKeys =
                hindiResults
                    .map {
                        it.seriesKey
                    }
                    .toMutableSet()

            for (query in additionalHindiReligionShows) {

                if (hindiResults.size >= 30) {
                    break
                }

                try {

                    val candidate =
                        findBestAdditionalHindiPlaylist(
                            query
                        )
                            ?: continue

                    if (
                        candidate.seriesKey in
                        existingHindiKeys
                    ) {
                        continue
                    }

                    existingHindiKeys.add(
                        candidate.seriesKey
                    )

                    hindiResults.add(
                        candidate
                    )

                } catch (_: Exception) {
                    continue
                }
            }
        }

        /*
         * ----------------------------------------------
         * FINAL ORDER
         * ----------------------------------------------
         *
         * Bengali first.
         * Hindi second.
         *
         * Target:
         * Bengali <= 18
         * Hindi <= 30
         *
         * Total target <= 48.
         */

        val finalResults =
            mutableListOf<SearchResponse>()

        finalResults.addAll(
            bengaliResults.map {
                it.toSearchResponse()
            }
        )

        finalResults.addAll(
            hindiResults.map {
                it.toSearchResponse()
            }
        )

        return newHomePageResponse(
            listOf(
                HomePageList(
                    "Religion",
                    finalResults,
                    false
                )
            ),
            false
        )
    }

    /*
     * --------------------------------------------------
     * RELIGION PLAYLIST CANDIDATE
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
    ) {

        fun toSearchResponse(): SearchResponse {

            return newMovieSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {

                posterUrl =
                    thumbnail
            }
        }
    }

    private enum class ReligionLanguage {
        BENGALI,
        HINDI
    }

    /*
     * --------------------------------------------------
     * BEST BENGALI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestBengaliPlaylist(
        show: BengaliDubbedShow
    ): ReligionPlaylistCandidate? {

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        /*
         * Search the exact Bengali dubbed name first.
         */

        val queries =
            mutableListOf<String>()

        for (name in show.bengaliNames) {

            queries.add(
                "$name full episodes playlist"
            )

            queries.add(
                "$name all episodes playlist"
            )
        }

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

                    val uploader =
                        item.uploaderName
                            ?.trim()
                            ?: ""

                    if (
                        !isValidBengaliDubbedPlaylist(
                            title,
                            uploader,
                            show
                        )
                    ) {
                        continue
                    }

                    val thumbnail =
                        item.thumbnails
                            .lastOrNull()
                            ?.url

                    val score =
                        calculateBengaliPlaylistScore(
                            title = title,
                            uploader = uploader,
                            show = show
                        )

                    candidates.add(
                        ReligionPlaylistCandidate(
                            title = title,
                            url = url,
                            thumbnail = thumbnail,
                            uploader = uploader,
                            language = ReligionLanguage.BENGALI,
                            seriesKey = show.key,
                            score = score
                        )
                    )
                }

            } catch (_: Exception) {
                continue
            }
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * BEST HINDI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestHindiPlaylist(
        show: BengaliDubbedShow
    ): ReligionPlaylistCandidate? {

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        val queries =
            mutableListOf<String>()

        for (name in show.hindiNames) {

            queries.add(
                "$name full episodes playlist"
            )

            queries.add(
                "$name all episodes playlist"
            )

            queries.add(
                "$name complete episodes playlist"
            )
        }

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

                    val uploader =
                        item.uploaderName
                            ?.trim()
                            ?: ""

                    if (
                        !isValidHindiPlaylist(
                            title,
                            uploader,
                            show
                        )
                    ) {
                        continue
                    }

                    val thumbnail =
                        item.thumbnails
                            .lastOrNull()
                            ?.url

                    val score =
                        calculateHindiPlaylistScore(
                            title = title,
                            uploader = uploader,
                            show = show
                        )

                    candidates.add(
                        ReligionPlaylistCandidate(
                            title = title,
                            url = url,
                            thumbnail = thumbnail,
                            uploader = uploader,
                            language = ReligionLanguage.HINDI,
                            seriesKey = show.key,
                            score = score
                        )
                    )
                }

            } catch (_: Exception) {
                continue
            }
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * ADDITIONAL HINDI PLAYLIST
     * --------------------------------------------------
     */

    private suspend fun findBestAdditionalHindiPlaylist(
        query: String
    ): ReligionPlaylistCandidate? {

        val candidates =
            mutableListOf<ReligionPlaylistCandidate>()

        val seenUrls =
            mutableSetOf<String>()

        try {

            val extractor =
                service.getSearchExtractor(
                    "$query full episodes playlist"
                )

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

                val uploader =
                    item.uploaderName
                        ?.trim()
                        ?: ""

                if (
                    !isValidGeneralHindiReligionPlaylist(
                        title,
                        uploader
                    )
                ) {
                    continue
                }

                val seriesKey =
                    detectReligionSeriesKey(
                        "$query $title"
                    )
                        ?: continue

                val thumbnail =
                    item.thumbnails
                        .lastOrNull()
                        ?.url

                val score =
                    calculateGeneralHindiScore(
                        title,
                        uploader
                    )

                candidates.add(
                    ReligionPlaylistCandidate(
                        title = title,
                        url = url,
                        thumbnail = thumbnail,
                        uploader = uploader,
                        language = ReligionLanguage.HINDI,
                        seriesKey = seriesKey,
                        score = score
                    )
                )
            }

        } catch (_: Exception) {
            return null
        }

        return candidates.maxWithOrNull(
            compareBy<ReligionPlaylistCandidate> {
                it.score
            }.thenByDescending {
                it.title.length
            }
        )
    }

    /*
     * --------------------------------------------------
     * BENGALI DUB VALIDATION
     * --------------------------------------------------
     */

    private fun isValidBengaliDubbedPlaylist(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Boolean {

        val combined =
            "$title $uploader".lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        /*
         * Must match the intended show.
         */

        val matchesShow =
            show.bengaliNames.any {
                combined.contains(
                    it.lowercase()
                )
            }

        if (!matchesShow) {
            return false
        }

        return true
    }

    /*
     * --------------------------------------------------
     * HINDI VALIDATION
     * --------------------------------------------------
     */

    private fun isValidHindiPlaylist(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Boolean {

        val combined =
            "$title $uploader".lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        val matchesShow =
            show.hindiNames.any {
                combined.contains(
                    it.lowercase()
                )
            }

        if (!matchesShow) {
            return false
        }

        return true
    }

    /*
     * --------------------------------------------------
     * GENERAL HINDI VALIDATION
     * --------------------------------------------------
     */

    private fun isValidGeneralHindiReligionPlaylist(
        title: String,
        uploader: String
    ): Boolean {

        val combined =
            "$title $uploader".lowercase()

        if (
            containsAny(
                combined,
                religionExcludeKeywords
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                religionBangladeshKeywords
            )
        ) {
            return false
        }

        /*
         * Hindi/Indian signal.
         */

        val hasHindiSignal =
            combined.contains("hindi") ||
                combined.contains("india") ||
                combined.contains("serial") ||
                combined.contains("episodes") ||
                combined.contains("star plus") ||
                combined.contains("colors") ||
                combined.contains("sony") ||
                combined.contains("life ok")

        if (!hasHindiSignal) {
            return false
        }

        /*
         * Must match a known religious/mythological show.
         */

        return detectReligionSeriesKey(
            combined
        ) != null
    }

    /*
     * --------------------------------------------------
     * BENGALI SCORE
     * --------------------------------------------------
     */

    private fun calculateBengaliPlaylistScore(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Int {

        val combined =
            "$title $uploader".lowercase()

        var score = 100

        /*
         * Strong Bengali-dub signal.
         */

        if (
            combined.contains("bangla")
        ) {
            score += 35
        }

        if (
            combined.contains("bengali")
        ) {
            score += 35
        }

        if (
            combined.contains("বাংলা")
        ) {
            score += 40
        }

        if (
            combined.contains("dub")
        ) {
            score += 20
        }

        if (
            combined.contains("ডাব")
        ) {
            score += 20
        }

        /*
         * Full playlist signals.
         */

        if (
            combined.contains(
                "full episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "complete episodes"
            )
        ) {
            score += 25
        }

        if (
            combined.contains(
                "all episodes"
            )
        ) {
            score += 20
        }

        if (
            combined.contains(
                "সম্পূর্ণ"
            )
        ) {
            score += 20
        }

        /*
         * Official broadcaster.
         */

        for (channel in show.officialChannels) {

            if (
                combined.contains(
                    channel.lowercase()
                )
            ) {
                score += 80
            }
        }

        /*
         * Explicit official signal.
         */

        if (
            combined.contains("official")
        ) {
            score += 100
        }

        /*
         * Penalty for personal/copy playlists.
         */

        if (
            combined.contains("my playlist")
        ) {
            score -= 100
        }

        if (
            combined.contains("fan made")
        ) {
            score -= 100
        }

        if (
            combined.contains("fanmade")
        ) {
            score -= 100
        }

        if (
            combined.contains("collection")
        ) {
            score -= 60
        }

        if (
            combined.contains("saved")
        ) {
            score -= 60
        }

        if (
            combined.contains("backup")
        ) {
            score -= 60
        }

        if (
            combined.contains("reupload")
        ) {
            score -= 80
        }

        if (
            combined.contains("archive")
        ) {
            score -= 50
        }

        return score
    }

    /*
     * --------------------------------------------------
     * HINDI SCORE
     * --------------------------------------------------
     */

    private fun calculateHindiPlaylistScore(
        title: String,
        uploader: String,
        show: BengaliDubbedShow
    ): Int {

        val combined =
            "$title $uploader".lowercase()

        var score = 100

        if (
            combined.contains("hindi")
        ) {
            score += 25
        }

        if (
            combined.contains("full episodes")
        ) {
            score += 25
        }

        if (
            combined.contains("complete episodes")
        ) {
            score += 25
        }

        if (
            combined.contains("all episodes")
        ) {
            score += 20
        }

        for (channel in show.officialChannels) {

            if (
                combined.contains(
                    channel.lowercase()
                )
            ) {
                score += 80
            }
        }

        if (
            combined.contains("official")
        ) {
            score += 100
        }

        if (
            combined.contains("my playlist")
        ) {
            score -= 100
        }

        if (
            combined.contains("fan made")
        ) {
            score -= 100
        }

        if (
            combined.contains("fanmade")
        ) {
            score -= 100
        }

        if (
            combined.contains("collection")
        ) {
            score -= 60
        }

        if (
            combined.contains("saved")
        ) {
            score -= 60
        }

        if (
            combined.contains("backup")
        ) {
            score -= 60
        }

        if (
            combined.contains("reupload")
        ) {
            score -= 80
        }

        return score
    }

    /*
     * --------------------------------------------------
     * GENERAL HINDI SCORE
     * --------------------------------------------------
     */

    private fun calculateGeneralHindiScore(
        title: String,
        uploader: String
    ): Int {

        val combined =
            "$title $uploader".lowercase()

        var score = 100

        if (
            combined.contains("official")
        ) {
            score += 100
        }

        if (
            combined.contains("full episodes")
        ) {
            score += 30
        }

        if (
            combined.contains("complete episodes")
        ) {
            score += 30
        }

        if (
            combined.contains("all episodes")
        ) {
            score += 20
        }

        if (
            combined.contains("star plus")
        ) {
            score += 70
        }

        if (
            combined.contains("colors")
        ) {
            score += 70
        }

        if (
            combined.contains("sony")
        ) {
            score += 70
        }

        if (
            combined.contains("life ok")
        ) {
            score += 60
        }

        if (
            combined.contains("star bharat")
        ) {
            score += 70
        }

        if (
            combined.contains("fan")
        ) {
            score -= 80
        }

        if (
            combined.contains("collection")
        ) {
            score -= 60
        }

        if (
            combined.contains("reupload")
        ) {
            score -= 80
        }

        return score
    }

    /*
     * --------------------------------------------------
     * DETECT SERIES KEY
     * --------------------------------------------------
     */

    private fun detectReligionSeriesKey(
        text: String
    ): String? {

        val normalized =
            normalizeReligionText(text)

        val ordered =
            religionSeriesAliases.entries
                .sortedByDescending {
                    it.value.maxOfOrNull {
                        alias ->
                        alias.length
                    } ?: 0
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
     * STRING HELPERS
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
     * NORMAL SEARCH
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
     * VIDEO
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
     * PLAYLIST
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
     * This section is kept unchanged.
     *
     * VOD -> DASH
     * LIVE -> HLS
     * fallback -> CloudStream extractor
     *
     * No proxy/server is added.
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
             * Fall back to CloudStream's working
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
