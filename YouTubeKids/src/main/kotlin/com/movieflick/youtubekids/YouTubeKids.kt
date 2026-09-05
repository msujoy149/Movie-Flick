package com.movieflick.youtubekids

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class YouTubeKids : MainAPI() {

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Kids"
    override var lang = "bn-IN"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    override val mainPage = mainPageOf(
        "recommended" to "Recommended for Kids",
        "bangla" to "বাংলা Kids",
        "learning" to "Learning",
        "rhymes" to "Nursery Rhymes",
        "cartoons" to "Cartoons",
        "stories" to "Stories",
        "music" to "Kids Music",
        "animals" to "Animals & Nature",
        "cars" to "Cars, Trucks & Machines",
        "play" to "Play & Create",
        "explore" to "Explore"
    )

    private companion object {
        const val FAST_VISIBLE_COUNT = 6
        const val FULL_CACHE_COUNT = 30
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val FAST_TOTAL_TIMEOUT_MS = 2200L
        const val FAST_QUERY_TIMEOUT_MS = 1700L
        const val BACKGROUND_QUERY_TIMEOUT_MS = 8000L

        val PREWARM_STARTED = AtomicBoolean(false)
    }

    private val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class CachedSection(
        val expiresAt: Long,
        val items: List<SearchResponse>
    )

    private val cache = ConcurrentHashMap<String, CachedSection>()
    private val refreshRunning = ConcurrentHashMap<String, Boolean>()

    private val preferredBanglaChannels = listOf(
        "Shemaroo Bangla",
        "Sony AATH",
        "Green Gold",
        "ChuChu TV Bangla",
        "Kiddiestv Bangla",
        "Bangla Kids",
        "Bangla Rhymes",
        "Bangla Nursery Rhymes"
    )

    private val preferredKidsChannels = listOf(
        "Cocomelon",
        "CoComelon",
        "Super Simple Songs",
        "Little Baby Bum",
        "Pinkfong",
        "Baby Shark",
        "ChuChu TV",
        "Peppa Pig",
        "Blippi",
        "Sesame Street",
        "Pocoyo",
        "Numberblocks",
        "Alphablocks",
        "PBS Kids",
        "Bluey",
        "Thomas & Friends",
        "Gecko's Garage",
        "Gracie's Corner",
        "Ms Rachel",
        "Mother Goose Club",
        "Dave and Ava",
        "Bebefinn",
        "BabyBus",
        "LooLoo Kids",
        "Masha and the Bear",
        "WildBrain Kids"
    )

    private val unsafeSignals = listOf(
        "18+", "adult", "nsfw", "violence", "violent", "weapon", "weapons",
        "gun", "guns", "blood", "murder", "crime", "horror", "terror",
        "terrorist", "war", "death", "kill", "killing", "drugs", "alcohol",
        "beer", "casino", "gambling", "dating", "sexy", "sex", "xxx", "porn",
        "politics", "breaking news", "true crime", "podcast", "reaction",
        "roast", "shorts", "short video"
    )

    private val lowQualitySignals = listOf(
        "reupload", "re-upload", "fan made", "fanmade", "mega compilation",
        "24/7", "movie explained", "episode explained", "reaction", "review",
        "commentary", "clickbait"
    )

    private val ageSignals = listOf(
        "kids", "kid", "children", "child", "toddler", "preschool",
        "preschoolers", "nursery", "rhymes", "rhyme", "abc", "alphabet",
        "phonics", "numbers", "counting", "learn", "learning", "educational",
        "education", "colors", "colours", "shapes", "animals", "cars",
        "trucks", "vehicles", "cartoon", "cartoons", "story", "stories",
        "bedtime", "fairy tale", "fairytale", "music", "song", "songs",
        "dance", "play", "drawing", "craft", "bangla", "bengali", "বাংলা",
        "শিশু", "বাচ্চা", "ছড়া", "গান", "কার্টুন", "গল্প", "শেখা"
    )

    private val sectionQueries = mapOf(
        "recommended" to listOf(
            "Bangla kids learning cartoons age 3 4 5 6",
            "Bangla kids nursery rhymes preschool",
            "Bengali kids educational videos",
            "kids learning ABC numbers colors preschool",
            "safe educational cartoons for kids 3 4 5 6",
            "kids animals learning preschool",
            "kids cars trucks machines preschool"
        ),
        "bangla" to listOf(
            "Bangla kids cartoon",
            "Bangla nursery rhymes kids",
            "Bangla baby learning",
            "বাংলা শিশুদের ছড়া",
            "বাংলা শিশুদের গান",
            "বাংলা বাচ্চাদের কার্টুন",
            "বাংলা শিশুদের গল্প"
        ),
        "learning" to listOf(
            "kids learning ABC phonics age 3 4 5 6",
            "kids learning numbers counting colors shapes preschool",
            "preschool educational videos 3 4 5 6",
            "kids science learning preschool",
            "kids vocabulary learning English preschool",
            "Bangla kids learning ABC numbers"
        ),
        "rhymes" to listOf(
            "Bangla nursery rhymes kids",
            "Bangla baby rhymes",
            "nursery rhymes preschool kids",
            "ABC songs for kids",
            "numbers songs for kids",
            "colors songs for kids",
            "animal songs for kids"
        ),
        "cartoons" to listOf(
            "Bangla kids cartoon",
            "safe cartoons for preschool kids",
            "educational cartoons age 3 4 5 6",
            "Peppa Pig kids",
            "Pocoyo kids",
            "Bluey kids",
            "Masha and the Bear kids"
        ),
        "stories" to listOf(
            "Bangla kids stories",
            "Bangla bedtime stories for children",
            "kids story age 3 4 5 6",
            "preschool bedtime stories",
            "fairy tales for kids",
            "educational stories for children"
        ),
        "music" to listOf(
            "Bangla kids songs",
            "Bangla baby songs",
            "kids nursery songs",
            "Cocomelon kids songs",
            "Super Simple Songs",
            "Pinkfong kids songs",
            "Baby Shark kids"
        ),
        "animals" to listOf(
            "animals for kids preschool",
            "animal sounds for kids",
            "Bangla animals for children",
            "wild animals learning kids",
            "farm animals for kids",
            "ocean animals for kids",
            "dinosaur learning kids"
        ),
        "cars" to listOf(
            "cars trucks machines for kids",
            "construction vehicles for kids",
            "excavator truck tractor kids",
            "toy cars for kids",
            "Gecko's Garage",
            "Thomas and Friends kids",
            "vehicle learning preschool"
        ),
        "play" to listOf(
            "kids drawing craft preschool",
            "play and learn kids age 3 4 5 6",
            "pretend play kids",
            "kids toys educational play",
            "easy crafts for preschool kids",
            "kids coloring learning"
        ),
        "explore" to listOf(
            "kids science experiments preschool",
            "space for kids preschool",
            "nature for kids educational",
            "world for kids learning",
            "dinosaurs for kids educational",
            "how things work for kids"
        )
    )

    init {
        if (PREWARM_STARTED.compareAndSet(false, true)) {
            backgroundScope.launch {
                val sections = sectionQueries.keys.toList()
                for (batch in sections.chunked(3)) {
                    coroutineScope {
                        batch.map { section ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    buildSection(section, false)
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }

    private fun containsAny(text: String, words: List<String>): Boolean {
        val value = text.lowercase()
        return words.any { value.contains(it.lowercase()) }
    }

    private fun preferredBangla(uploader: String): Boolean {
        return uploader.isNotBlank() && preferredBanglaChannels.any {
            uploader.contains(it, ignoreCase = true)
        }
    }

    private fun preferredKids(uploader: String): Boolean {
        return uploader.isNotBlank() && preferredKidsChannels.any {
            uploader.contains(it, ignoreCase = true)
        }
    }

    private fun isKidsCandidate(item: StreamInfoItem): Boolean {
        if (item.streamType != StreamType.VIDEO_STREAM) return false
        if (item.isShortFormContent) return false

        val title = item.name?.trim().orEmpty()
        val uploader = item.uploaderName?.trim().orEmpty()
        if (title.isBlank()) return false

        val combined = "$title $uploader".lowercase()

        if (containsAny(combined, unsafeSignals)) return false
        if (containsAny(combined, lowQualitySignals)) return false

        val duration = item.duration
        if (duration in 1L..25L) return false
        if (duration > 60L * 60L) return false

        return containsAny(combined, ageSignals) ||
            preferredKids(uploader) ||
            preferredBangla(uploader)
    }

    private fun score(item: StreamInfoItem, section: String): Int {
        val title = item.name?.trim().orEmpty()
        val uploader = item.uploaderName?.trim().orEmpty()
        val combined = "$title $uploader".lowercase()

        var score = 100

        if (containsAny(combined, listOf("bangla", "bengali", "বাংলা"))) score += 160
        if (preferredBangla(uploader)) score += 220
        if (preferredKids(uploader)) score += 180

        if (containsAny(combined, listOf(
                "learning", "educational", "education", "learn", "abc",
                "alphabet", "phonics", "numbers", "counting", "colors",
                "colours", "shapes", "science"
            ))) score += 90

        if (containsAny(combined, listOf(
                "preschool", "preschooler", "toddler", "3 year old",
                "4 year old", "5 year old", "6 year old"
            ))) score += 100

        when (section) {
            "bangla" -> if (containsAny(combined, listOf("bangla", "bengali", "বাংলা"))) score += 140
            "learning" -> if (containsAny(combined, listOf("learn", "learning", "education", "abc", "numbers", "phonics"))) score += 100
            "rhymes" -> if (containsAny(combined, listOf("rhyme", "rhymes", "nursery", "song"))) score += 100
            "cartoons" -> if (containsAny(combined, listOf("cartoon", "cartoons", "animation", "animated"))) score += 100
            "stories" -> if (containsAny(combined, listOf("story", "stories", "bedtime", "fairy tale", "fairytale"))) score += 100
            "music" -> if (containsAny(combined, listOf("song", "songs", "music", "rhymes"))) score += 100
            "animals" -> if (containsAny(combined, listOf("animal", "animals", "dinosaur", "farm", "ocean"))) score += 100
            "cars" -> if (containsAny(combined, listOf("car", "cars", "truck", "trucks", "tractor", "excavator", "vehicle"))) score += 100
            "play" -> if (containsAny(combined, listOf("play", "drawing", "craft", "coloring", "colouring", "toy"))) score += 100
            "explore" -> if (containsAny(combined, listOf("science", "space", "nature", "dinosaur", "how things work"))) score += 100
        }

        if (item.duration in 60L..12L * 60L) score += 35
        if (item.duration in 12L * 60L..30L * 60L) score += 20

        return score
    }

    private suspend fun fetchSearchItems(
        queries: List<String>,
        fastMode: Boolean
    ): List<StreamInfoItem> {
        val clean = queries.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return emptyList()

        val selectedQueries = if (fastMode) clean.take(2) else clean

        val grouped: List<List<InfoItem>> =
            if (fastMode) {
                withTimeoutOrNull(FAST_TOTAL_TIMEOUT_MS) {
                    coroutineScope {
                        selectedQueries.map { query ->
                            async(Dispatchers.IO) {
                                withTimeoutOrNull(FAST_QUERY_TIMEOUT_MS) {
                                    runCatching {
                                        val extractor = service.getSearchExtractor(query)
                                        extractor.fetchPage()
                                        extractor.initialPage.items.toList()
                                    }.getOrElse { emptyList() }
                                } ?: emptyList()
                            }
                        }.awaitAll()
                    }
                } ?: emptyList()
            } else {
                val output = mutableListOf<List<InfoItem>>()
                for (batch in selectedQueries.chunked(3)) {
                    val result = coroutineScope {
                        batch.map { query ->
                            async(Dispatchers.IO) {
                                withTimeoutOrNull(BACKGROUND_QUERY_TIMEOUT_MS) {
                                    runCatching {
                                        val extractor = service.getSearchExtractor(query)
                                        extractor.fetchPage()
                                        extractor.initialPage.items.toList()
                                    }.getOrElse { emptyList() }
                                } ?: emptyList()
                            }
                        }.awaitAll()
                    }
                    output.addAll(result)
                }
                output
            }

        return grouped
            .flatten()
            .filterIsInstance<StreamInfoItem>()
            .distinctBy { it.url }
    }

    private suspend fun buildSection(
        section: String,
        fastMode: Boolean
    ): HomePageResponse {
        val queries = sectionQueries[section] ?: sectionQueries["recommended"].orEmpty()
        val items = fetchSearchItems(queries, fastMode)

        val ranked = items
            .filter { isKidsCandidate(it) }
            .distinctBy { it.url }
            .map { score(it, section) to it }
            .sortedByDescending { it.first }

        val stableCount = minOf(4, ranked.size)
        val stable = ranked.take(stableCount)
        val rotating = ranked.drop(stableCount).toMutableList()

        val bucket = System.currentTimeMillis() / (10 * 60 * 1000L)
        rotating.shuffle(Random(section.hashCode().toLong() xor bucket))

        val limit = if (fastMode) FAST_VISIBLE_COUNT else FULL_CACHE_COUNT
        val selected = (stable + rotating).take(limit)

        val results = selected.mapNotNull { (_, item) ->
            val title = item.name?.trim().orEmpty()
            val url = item.url?.trim().orEmpty()
            if (title.isBlank() || url.isBlank()) return@mapNotNull null

            newMovieSearchResponse(title, url, TvType.Others) {
                posterUrl = item.thumbnails.lastOrNull()?.url
            }
        }

        if (results.isNotEmpty()) {
            cache[section] = CachedSection(
                System.currentTimeMillis() + CACHE_TTL_MS,
                results.distinctBy { it.url }.take(FULL_CACHE_COUNT)
            )
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    sectionDisplayName(section),
                    results,
                    false
                )
            ),
            false
        )
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(emptyList(), false)
        }

        val section = request.data
        val cached = cache[section]
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.items
            ?.take(FULL_CACHE_COUNT)
            .orEmpty()

        if (cached.isNotEmpty()) {
            scheduleRefresh(section)
            return newHomePageResponse(
                listOf(HomePageList(sectionDisplayName(section), cached, false)),
                false
            )
        }

        val fast = buildSection(section, true)
        scheduleRefresh(section)
        return fast
    }

    private fun scheduleRefresh(section: String) {
        if (refreshRunning.putIfAbsent(section, true) != null) return

        backgroundScope.launch {
            try {
                buildSection(section, false)
            } catch (_: Exception) {
            } finally {
                refreshRunning.remove(section)
            }
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val clean = query.trim()
        if (clean.isBlank()) return newSearchResponseList(emptyList(), false)

        val extractor = try {
            service.getSearchExtractor(clean)
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        val pageData = try {
            extractor.fetchPage()
            extractor.initialPage
        } catch (_: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        val results = pageData.items
            .filterIsInstance<StreamInfoItem>()
            .filter { isKidsCandidate(it) }
            .mapNotNull { item ->
                val title = item.name?.trim().orEmpty()
                val url = item.url?.trim().orEmpty()
                if (title.isBlank() || url.isBlank()) null
                else newMovieSearchResponse(title, url, TvType.Others) {
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            }

        return newSearchResponseList(results, pageData.hasNextPage())
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            isVideoUrl(url) -> loadVideo(url)
            isChannelUrl(url) -> loadChannel(url)
            isPlaylistUrl(url) -> loadPlaylist(url)
            else -> throw RuntimeException("Unsupported YouTube Kids URL")
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("/watch?v=") ||
            value.contains("youtu.be/") ||
            value.contains("/shorts/")
    }

    private fun isChannelUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("/channel/") ||
            value.contains("/@") ||
            value.contains("/c/") ||
            value.contains("/user/")
    }

    private fun isPlaylistUrl(url: String): Boolean {
        return url.lowercase().contains("/playlist?list=")
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()
        val info = StreamInfo.getInfo(extractor)
        val isLive = info.streamType?.name?.contains("LIVE") == true

        return newMovieLoadResponse(
            info.name,
            url,
            if (isLive) TvType.Live else TvType.Others,
            url
        ) {
            plot = info.description.content.toString()
            posterUrl = info.thumbnails.lastOrNull()?.url
            if (info.duration > 0) duration = info.duration.toInt()
            info.uploaderName?.takeIf { it.isNotBlank() }?.let { uploader ->
                actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            info.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }
            tags = listOf("Kids", "Age 3-6")
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val extractor = service.getChannelExtractor(url)
        extractor.fetchPage()

        val channelName = extractor.name
        val description = extractor.description
        val avatar = extractor.avatars.lastOrNull()?.url
        val banner = extractor.banners.lastOrNull()?.url

        val videosTab = extractor.tabs.firstOrNull {
            it.url.contains("/videos")
        } ?: extractor.tabs.firstOrNull()
            ?: throw RuntimeException("No videos tab found")

        val videosExtractor = service.getChannelTabExtractor(videosTab)
        val episodes = mutableListOf<Episode>()

        var pageData = videosExtractor.initialPage
        var pages = 0

        while (pages < 5) {
            pageData.items
                .filterIsInstance<StreamInfoItem>()
                .filter { isKidsCandidate(it) }
                .forEach { item ->
                    val itemUrl = item.url?.trim().orEmpty()
                    if (itemUrl.isNotBlank()) {
                        episodes.add(newEpisode(itemUrl) {
                            name = item.name
                            posterUrl = item.thumbnails.lastOrNull()?.url
                        })
                    }
                }

            if (!pageData.hasNextPage()) break
            pageData = videosExtractor.getPage(pageData.nextPage)
            pages++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = description
            posterUrl = banner ?: avatar
            backgroundPosterUrl = banner
            tags = listOf("Kids", "Age 3-6", "Channel")
            actors = listOf(
                ActorData(
                    Actor(channelName, avatar ?: "")
                )
            )
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val extractor = service.getPlaylistExtractor(url)
        extractor.fetchPage()

        val playlistName = extractor.name
        val description = extractor.description.content.toString()
        val thumbnail = extractor.thumbnails.lastOrNull()?.url
        val uploader = extractor.uploaderName

        val episodes = mutableListOf<Episode>()
        var pageData = extractor.getInitialPage()
        var pages = 0

        while (pages < 10) {
            pageData.items
                .filterIsInstance<StreamInfoItem>()
                .filter { isKidsCandidate(it) }
                .forEach { item ->
                    val itemUrl = item.url?.trim().orEmpty()
                    if (itemUrl.isNotBlank()) {
                        episodes.add(newEpisode(itemUrl) {
                            name = item.name
                            posterUrl = item.thumbnails.lastOrNull()?.url
                        })
                    }
                }

            if (!pageData.hasNextPage()) break
            pageData = extractor.getPage(pageData.nextPage)
            pages++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = description
            posterUrl = thumbnail
            tags = listOf("Kids", "Age 3-6", "Playlist")
            if (uploader.isNotBlank()) {
                actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            extractor.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val cleanUrl = canonicalYouTubeUrl(data)

        try {
            val extractor = service.getStreamExtractor(cleanUrl)
            extractor.fetchPage()

            val info = StreamInfo.getInfo(extractor)
            val live = info.streamType?.name?.contains("LIVE") == true

            if (live) {
                val hls = runCatching { info.hlsUrl }.getOrNull()
                if (!hls.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Live",
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "https://www.youtube.com/"
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

            val dash = runCatching { info.dashMpdUrl }.getOrNull()
            if (!dash.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Adaptive",
                        url = dash,
                        type = ExtractorLinkType.DASH
                    ) {
                        referer = "https://www.youtube.com/"
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            val hls = runCatching { info.hlsUrl }.getOrNull()
            if (!hls.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = hls,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "https://www.youtube.com/"
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {
        }

        return runCatching {
            loadExtractor(cleanUrl, subtitleCallback, callback)
        }.getOrDefault(false)
    }

    private fun canonicalYouTubeUrl(url: String): String {
        val value = url.trim()

        val watchIndex = value.indexOf("v=")
        if (watchIndex >= 0) {
            val id = value.substring(watchIndex + 2)
                .substringBefore("&")
                .substringBefore("#")
                .trim()

            if (id.length >= 6) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        val shortPrefix = "youtu.be/"
        val shortIndex = value.indexOf(shortPrefix, ignoreCase = true)
        if (shortIndex >= 0) {
            val id = value.substring(shortIndex + shortPrefix.length)
                .substringBefore("?")
                .substringBefore("&")
                .substringBefore("#")
                .trim()

            if (id.length >= 6) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        val shortsPrefix = "/shorts/"
        val shortsIndex = value.indexOf(shortsPrefix, ignoreCase = true)
        if (shortsIndex >= 0) {
            val id = value.substring(shortsIndex + shortsPrefix.length)
                .substringBefore("?")
                .substringBefore("&")
                .substringBefore("#")
                .trim()

            if (id.length >= 6) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        return value
    }

    private fun sectionDisplayName(section: String): String {
        return when (section) {
            "recommended" -> "Recommended for Kids"
            "bangla" -> "বাংলা Kids"
            "learning" -> "Learning"
            "rhymes" -> "Nursery Rhymes"
            "cartoons" -> "Cartoons"
            "stories" -> "Stories"
            "music" -> "Kids Music"
            "animals" -> "Animals & Nature"
            "cars" -> "Cars, Trucks & Machines"
            "play" -> "Play & Create"
            "explore" -> "Explore"
            else -> section
        }
    }
}
