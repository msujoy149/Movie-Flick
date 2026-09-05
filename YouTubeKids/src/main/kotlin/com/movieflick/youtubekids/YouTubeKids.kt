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
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    /*
     * ============================================================
     * HOME
     * ============================================================
     */

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

    /*
     * ============================================================
     * PERFORMANCE SETTINGS
     * ============================================================
     */

    private companion object {

        // First paint should stay small.
        const val FAST_VISIBLE_COUNT = 6

        // Full background snapshot.
        const val HOME_CACHE_LIMIT = 30

        // Cache freshness.
        const val CACHE_TTL_MS = 15 * 60 * 1000L

        // Prevent multiple background refreshes for same section.
        const val BACKGROUND_REFRESH_COOLDOWN_MS = 15_000L

        // First-load timeout.
        const val FAST_TOTAL_TIMEOUT_MS = 1_650L

        // Individual first-pass query timeout.
        const val FAST_QUERY_TIMEOUT_MS = 1_350L

        // Normal background query timeout.
        const val NORMAL_QUERY_TIMEOUT_MS = 8_000L

        // Prevent duplicate prewarm.
        val PREWARM_STARTED = AtomicBoolean(false)
    }

    private val backgroundScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private val refreshRunning =
        ConcurrentHashMap<String, Boolean>()

    private val refreshLastStarted =
        ConcurrentHashMap<String, Long>()

    private data class CachedResponses(
        val expiresAt: Long,
        val items: List<SearchResponse>
    )

    private val homeCache =
        ConcurrentHashMap<String, CachedResponses>()

    /*
     * ============================================================
     * BENGALI-FIRST KIDS CHANNELS
     * ============================================================
     *
     * These are priority signals, not hardcoded video URLs.
     * More channels can be added later without changing the
     * underlying loading architecture.
     */

    private val preferredBanglaChannels = listOf(
        "Shemaroo Bangla",
        "Sony AATH",
        "Green Gold",
        "Motu Patlu Bangla",
        "ChuChu TV Bangla",
        "Kiddiestv Bangla",
        "Bangla Kids",
        "Bangla Rhymes",
        "Bangla Nursery Rhymes",
        "শিশুদের গান",
        "শিশুদের ছড়া",
        "বাংলা কার্টুন"
    )

    /*
     * ============================================================
     * GENERAL TRUSTED KIDS CHANNEL SIGNALS
     * ============================================================
     */

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
        "Khan Academy Kids",
        "PBS Kids",
        "Daniel Tiger",
        "Bluey",
        "Thomas & Friends",
        "Thomas and Friends",
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

    /*
     * ============================================================
     * AGE 3-6 SIGNALS
     * ============================================================
     */

    private val ageFriendlyKeywords = listOf(
        "kids",
        "kid",
        "children",
        "child",
        "toddler",
        "preschool",
        "preschoolers",
        "nursery",
        "rhymes",
        "rhyme",
        "abc",
        "alphabet",
        "phonics",
        "numbers",
        "counting",
        "learn",
        "learning",
        "educational",
        "education",
        "colors",
        "colours",
        "shapes",
        "animals",
        "cars",
        "trucks",
        "vehicles",
        "cartoon",
        "cartoons",
        "story",
        "stories",
        "bedtime",
        "fairy tale",
        "fairytale",
        "music",
        "song",
        "songs",
        "dance",
        "play",
        "pretend",
        "drawing",
        "craft",
        "science for kids",
        "bangla",
        "bengali",
        "বাংলা",
        "শিশু",
        "বাচ্চা",
        "ছড়া",
        "গান",
        "কার্টুন",
        "গল্প",
        "শেখা"
    )

    /*
     * ============================================================
     * CONTENT WE DO NOT WANT IN A 3-6 KIDS FEED
     * ============================================================
     */

    private val unsafeOrAdultSignals = listOf(
        "18+",
        "adult",
        "nsfw",
        "violence",
        "violent",
        "fight",
        "fighting",
        "weapon",
        "weapons",
        "gun",
        "guns",
        "blood",
        "murder",
        "crime",
        "horror",
        "scary",
        "scary movie",
        "terror",
        "terrorist",
        "war",
        "death",
        "dead",
        "kill",
        "killing",
        "drugs",
        "alcohol",
        "beer",
        "casino",
        "gambling",
        "dating",
        "sexy",
        "sex",
        "xxx",
        "porn",
        "prank",
        "reaction",
        "roast",
        "politics",
        "news",
        "breaking news",
        "true crime",
        "podcast",
        "vlog",
        "live stream",
        "shorts",
        "short video"
    )

    /*
     * ============================================================
     * LOW QUALITY / NON-KIDS SIGNALS
     * ============================================================
     */

    private val lowQualitySignals = listOf(
        "reupload",
        "re-upload",
        "fan made",
        "fanmade",
        "compilation",
        "mega compilation",
        "10 hours",
        "24 hours",
        "24/7",
        "full movie",
        "movie explained",
        "episode explained",
        "reaction",
        "review",
        "commentary",
        "clickbait"
    )

    /*
     * ============================================================
     * SEARCH QUERIES
     * ============================================================
     *
     * First two queries are the fast path.
     * Remaining queries are background enrichment.
     */

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

    /*
     * ============================================================
     * PREWARM
     * ============================================================
     *
     * Never blocks the first UI.
     */

    init {
        if (PREWARM_STARTED.compareAndSet(false, true)) {
            backgroundScope.launch {

                val sections =
                    sectionQueries.keys.toList()

                // Prepare the full 30-item cache in small background batches.
                // This never blocks the first UI paint.
                for (batch in sections.chunked(3)) {
                    coroutineScope {
                        batch.map { section ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    buildSection(
                                        section = section,
                                        fastMode = false,
                                        forceRefresh = true
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }

    /*
     * ============================================================
     * CACHE
     * ============================================================
     */

    private fun getCached(
        section: String
    ): List<SearchResponse> {

        return homeCache[section]
            ?.items
            ?.take(HOME_CACHE_LIMIT)
            ?: emptyList()
    }

    private fun putCached(
        section: String,
        items: List<SearchResponse>
    ) {

        if (items.isEmpty()) return

        homeCache[section] =
            CachedResponses(
                expiresAt =
                    System.currentTimeMillis() +
                        CACHE_TTL_MS,
                items =
                    items
                        .distinctBy { it.url }
                        .take(HOME_CACHE_LIMIT)
            )
    }

    /*
     * ============================================================
     * BACKGROUND REFRESH
     * ============================================================
     */

    private fun scheduleBackgroundRefresh(
        section: String,
        job: suspend () -> Unit
    ) {

        val now =
            System.currentTimeMillis()

        val last =
            refreshLastStarted[section]
                ?: 0L

        if (
            now - last <
            BACKGROUND_REFRESH_COOLDOWN_MS
        ) {
            return
        }

        if (
            refreshRunning.putIfAbsent(
                section,
                true
            ) != null
        ) {
            return
        }

        refreshLastStarted[section] = now

        backgroundScope.launch {

            try {
                job()
            } catch (_: Exception) {
                // Background refresh must never
                // break the visible UI.
            } finally {
                refreshRunning.remove(section)
            }
        }
    }

    /*
     * ============================================================
     * FAST PARALLEL SEARCH
     * ============================================================
     */

    private suspend fun fetchSearchItems(
        queries: List<String>,
        fastMode: Boolean
    ): List<List<InfoItem>> {

        val cleanQueries =
            queries
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        if (cleanQueries.isEmpty()) {
            return emptyList()
        }

        if (fastMode) {

            val firstQueries =
                cleanQueries.take(2)

            return withTimeoutOrNull(
                FAST_TOTAL_TIMEOUT_MS
            ) {

                coroutineScope {

                    firstQueries
                        .map { query ->

                            async(Dispatchers.IO) {

                                withTimeoutOrNull(
                                    FAST_QUERY_TIMEOUT_MS
                                ) {

                                    runCatching {

                                        val extractor =
                                            service.getSearchExtractor(
                                                query
                                            )

                                        extractor.fetchPage()

                                        extractor
                                            .initialPage
                                            .items
                                            .toList()

                                    }.getOrElse {
                                        emptyList()
                                    }

                                } ?: emptyList()
                            }
                        }
                        .awaitAll()
                }

            } ?: emptyList()
        }

        val results =
            mutableListOf<List<InfoItem>>()

        for (
            batch in cleanQueries.chunked(6)
        ) {

            val batchResults =
                coroutineScope {

                    batch.map { query ->

                        async(Dispatchers.IO) {

                            withTimeoutOrNull(
                                NORMAL_QUERY_TIMEOUT_MS
                            ) {

                                runCatching {

                                    val extractor =
                                        service.getSearchExtractor(
                                            query
                                        )

                                    extractor.fetchPage()

                                    extractor
                                        .initialPage
                                        .items
                                        .toList()

                                }.getOrElse {
                                    emptyList()
                                }

                            } ?: emptyList()
                        }

                    }.awaitAll()
                }

            results.addAll(batchResults)
        }

        return results
    }

    /*
     * ============================================================
     * CONTENT FILTER
     * ============================================================
     */

    private fun isKidsCandidate(
        item: StreamInfoItem
    ): Boolean {

        if (
            item.streamType !=
            StreamType.VIDEO_STREAM
        ) {
            return false
        }

        if (
            item.isShortFormContent
        ) {
            return false
        }

        val title =
            item.name
                ?.trim()
                .orEmpty()

        val uploader =
            item.uploaderName
                ?.trim()
                .orEmpty()

        if (
            title.isBlank()
        ) {
            return false
        }

        val combined =
            "$title $uploader"
                .lowercase()

        /*
         * Strong exclusion first.
         */

        if (
            containsAny(
                combined,
                unsafeOrAdultSignals
            )
        ) {
            return false
        }

        if (
            containsAny(
                combined,
                lowQualitySignals
            )
        ) {
            return false
        }

        /*
         * Avoid very short clips.
         */

        val duration =
            item.duration

        if (
            duration in 1L..25L
        ) {
            return false
        }

        /*
         * Avoid extremely long videos.
         * Long nursery/story videos can still pass.
         */

        if (
            duration > 60L * 60L
        ) {
            return false
        }

        /*
         * Must have at least one kids signal.
         */

        val kidsSignal =
            containsAny(
                combined,
                ageFriendlyKeywords
            ) ||
                isPreferredKidsChannel(
                    uploader
                ) ||
                isPreferredBanglaChannel(
                    uploader
                )

        return kidsSignal
    }

    /*
     * ============================================================
     * SCORING
     * ============================================================
     */

    private fun scoreKidsItem(
        item: StreamInfoItem,
        section: String
    ): Int {

        val title =
            item.name
                ?.trim()
                .orEmpty()

        val uploader =
            item.uploaderName
                ?.trim()
                .orEmpty()

        val combined =
            "$title $uploader"
                .lowercase()

        var score = 100

        /*
         * Bengali gets strongest priority.
         */

        if (
            containsAny(
                combined,
                listOf(
                    "bangla",
                    "bengali",
                    "বাংলা"
                )
            )
        ) {
            score += 160
        }

        if (
            isPreferredBanglaChannel(
                uploader
            )
        ) {
            score += 220
        }

        /*
         * Trusted kids channels.
         */

        if (
            isPreferredKidsChannel(
                uploader
            )
        ) {
            score += 180
        }

        /*
         * Educational content.
         */

        if (
            containsAny(
                combined,
                listOf(
                    "learning",
                    "educational",
                    "education",
                    "learn",
                    "abc",
                    "alphabet",
                    "phonics",
                    "numbers",
                    "counting",
                    "colors",
                    "colours",
                    "shapes",
                    "science"
                )
            )
        ) {
            score += 90
        }

        /*
         * Age range signals.
         */

        if (
            containsAny(
                combined,
                listOf(
                    "preschool",
                    "preschooler",
                    "toddler",
                    "3 year old",
                    "4 year old",
                    "5 year old",
                    "6 year old",
                    "ages 3",
                    "ages 4",
                    "ages 5",
                    "ages 6"
                )
            )
        ) {
            score += 100
        }

        /*
         * Section relevance.
         */

        when (section) {

            "bangla" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "bangla",
                            "bengali",
                            "বাংলা"
                        )
                    )
                ) {
                    score += 140
                }
            }

            "learning" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "learn",
                            "learning",
                            "education",
                            "abc",
                            "numbers",
                            "phonics"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "rhymes" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "rhyme",
                            "rhymes",
                            "nursery",
                            "song"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "cartoons" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "cartoon",
                            "cartoons",
                            "animation",
                            "animated"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "stories" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "story",
                            "stories",
                            "bedtime",
                            "fairy tale",
                            "fairytale"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "music" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "song",
                            "songs",
                            "music",
                            "rhymes"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "animals" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "animal",
                            "animals",
                            "dinosaur",
                            "farm",
                            "ocean",
                            "wildlife"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "cars" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "car",
                            "cars",
                            "truck",
                            "trucks",
                            "tractor",
                            "excavator",
                            "construction",
                            "vehicle"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "play" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "play",
                            "drawing",
                            "craft",
                            "coloring",
                            "colouring",
                            "toy"
                        )
                    )
                ) {
                    score += 100
                }
            }

            "explore" -> {
                if (
                    containsAny(
                        combined,
                        listOf(
                            "science",
                            "space",
                            "nature",
                            "dinosaur",
                            "how things work"
                        )
                    )
                ) {
                    score += 100
                }
            }
        }

        /*
         * Prefer normal educational/video lengths.
         */

        val duration =
            item.duration

        if (
            duration in 60L..12L * 60L
        ) {
            score += 35
        }

        if (
            duration in 12L * 60L..30L * 60L
        ) {
            score += 20
        }

        /*
         * Recent/new wording is only a weak signal.
         * We intentionally do not use DateWrapper/uploadDate.
         */

        if (
            combined.contains("new")
        ) {
            score += 10
        }

        if (
            combined.contains("latest")
        ) {
            score += 10
        }

        /*
         * Small penalty for suspicious upload signals.
         */

        if (
            containsAny(
                combined,
                listOf(
                    "reupload",
                    "fan made",
                    "fanmade"
                )
            )
        ) {
            score -= 150
        }

        return score
    }

    /*
     * ============================================================
     * SECTION BUILDER
     * ============================================================
     */

    private suspend fun buildSection(
        section: String,
        fastMode: Boolean,
        forceRefresh: Boolean
    ): HomePageResponse {

        val queries =
            sectionQueries[section]
                ?: sectionQueries["recommended"]
                ?: emptyList()

        val allItems =
            fetchSearchItems(
                queries = queries,
                fastMode = fastMode
            )
                .flatten()
                .filterIsInstance<StreamInfoItem>()

        val candidates =
            mutableListOf<Pair<Int, StreamInfoItem>>()

        val seenUrls =
            mutableSetOf<String>()

        for (
            item in allItems
        ) {

            if (
                !isKidsCandidate(item)
            ) {
                continue
            }

            val url =
                item.url
                    ?.trim()
                    ?: continue

            if (
                url.isBlank()
            ) {
                continue
            }

            if (
                !seenUrls.add(url)
            ) {
                continue
            }

            candidates.add(
                scoreKidsItem(
                    item,
                    section
                ) to item
            )
        }

        /*
         * Highest quality first.
         */

        val ranked =
            candidates
                .sortedByDescending {
                    it.first
                }

        val limit =
            if (fastMode) {
                FAST_VISIBLE_COUNT
            } else {
                HOME_CACHE_LIMIT
            }

        /*
         * ========================================================
         * ROTATION
         * ========================================================
         *
         * We deliberately do not return exactly the same ordering
         * on every refresh.
         *
         * Top results remain quality-oriented.
         * The remainder gets shuffled using a time bucket.
         */

        val stableCount =
            minOf(
                4,
                ranked.size
            )

        val stable =
            ranked.take(
                stableCount
            )

        val rotating =
            ranked
                .drop(stableCount)
                .toMutableList()

        val rotationBucket =
            System.currentTimeMillis() /
                (10 * 60 * 1000L)

        rotating.shuffle(
            Random(
                (
                    section.hashCode()
                        .toLong() shl 32
                ) xor rotationBucket
            )
        )

        val selected =
            (
                stable + rotating
            ).take(limit)

        val results =
            selected.mapNotNull {
                (_, item) ->

                val title =
                    item.name
                        ?.trim()
                        ?: return@mapNotNull null

                val url =
                    item.url
                        ?.trim()
                        ?: return@mapNotNull null

                if (
                    title.isBlank() ||
                    url.isBlank()
                ) {
                    return@mapNotNull null
                }

                newMovieSearchResponse(
                    title,
                    url,
                    TvType.Others
                ) {

                    posterUrl =
                        item.thumbnails
                            .lastOrNull()
                            ?.url
                            ?.takeIf {
                                it.isNotBlank()
                            }
                }
            }

        if (
            results.isNotEmpty()
        ) {
            putCached(
                section,
                results
            )
        }

        val sectionName =
            sectionDisplayName(
                section
            )

        return newHomePageResponse(
            listOf(
                HomePageList(
                    sectionName,
                    results,
                    false
                )
            ),
            false
        )
    }

    /*
     * ============================================================
     * HOME PAGE
     * ============================================================
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (
            page > 1
        ) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val section =
            request.data

        /*
         * CACHE FIRST.
         *
         * UI gets something immediately.
         * Network refresh happens separately.
         */

        val cached =
            getCached(
                section
            )

        if (
            cached.isNotEmpty()
        ) {

            scheduleBackgroundRefresh(
                section
            ) {

                buildSection(
                    section = section,
                    fastMode = false,
                    forceRefresh = true
                )
            }

            return newHomePageResponse(
                listOf(
                    HomePageList(
                        sectionDisplayName(section),
                        cached,
                        false
                    )
                ),
                false
            )
        }

        /*
         * No cache:
         *
         * Small fast network request.
         */

        val fast =
            buildSection(
                section = section,
                fastMode = true,
                forceRefresh = true
            )

        /*
         * Immediately start a full background refresh.
         */

        scheduleBackgroundRefresh(
            section
        ) {

            buildSection(
                section = section,
                fastMode = false,
                forceRefresh = true
            )
        }

        return fast
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */

    private val searchPageCache =
        ConcurrentHashMap<String, org.schabi.newpipe.extractor.Page?>()

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val cleanQuery =
            query.trim()

        if (
            cleanQuery.isBlank()
        ) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cacheKey =
            cleanQuery.lowercase()

        val extractor =
            try {
                service.getSearchExtractor(
                    cleanQuery
                )
            } catch (_: Exception) {
                return newSearchResponseList(
                    emptyList(),
                    false
                )
            }

        val pageData =
            try {

                if (
                    page == 1 ||
                    !searchPageCache.containsKey(
                        cacheKey
                    )
                ) {

                    extractor.fetchPage()

                    extractor
                        .initialPage
                        .also {

                            searchPageCache[
                                cacheKey
                            ] =
                                it.nextPage
                        }

                } else {

                    val next =
                        searchPageCache[
                            cacheKey
                        ]
                            ?: return newSearchResponseList(
                                emptyList(),
                                false
                            )

                    extractor
                        .getPage(next)
                        .also {

                            searchPageCache[
                                cacheKey
                            ] =
                                it.nextPage
                        }
                }

            } catch (_: Exception) {

                return newSearchResponseList(
                    emptyList(),
                    false
                )
            }

        /*
         * Search results are also filtered for kids safety.
         */

        val results =
            pageData.items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidate(it)
                }
                .mapNotNull {
                    infoItemToSearchResponse(
                        it
                    )
                }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    /*
     * ============================================================
     * QUICK SEARCH
     * ============================================================
     */

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {

        return search(
            query,
            1
        ).items
    }

    /*
     * ============================================================
     * INFO ITEM -> SEARCH RESPONSE
     * ============================================================
     */

    private fun infoItemToSearchResponse(
        item: StreamInfoItem
    ): SearchResponse? {

        val title =
            item.name
                ?.trim()
                ?: return null

        val url =
            item.url
                ?.trim()
                ?: return null

        if (
            title.isBlank() ||
            url.isBlank()
        ) {
            return null
        }

        return newMovieSearchResponse(
            title,
            url,
            TvType.Others
        ) {

            posterUrl =
                item.thumbnails
                    .lastOrNull()
                    ?.url
        }
    }

    /*
     * ============================================================
     * LOAD
     * ============================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse {

        return when {

            isVideoUrl(url) ->
                loadVideo(url)

            isChannelUrl(url) ->
                loadChannel(url)

            isPlaylistUrl(url) ->
                loadPlaylist(url)

            else ->
                throw RuntimeException(
                    "Unsupported YouTube Kids URL"
                )
        }
    }

    /*
     * ============================================================
     * URL TYPE
     * ============================================================
     */

    private fun isVideoUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(
            "/watch?v="
        ) ||
            value.contains(
                "youtu.be/"
            ) ||
            value.contains(
                "/shorts/"
            )
    }

    private fun isChannelUrl(
        url: String
    ): Boolean {

        val value =
            url.lowercase()

        return value.contains(
            "/channel/"
        ) ||
            value.contains(
                "/@"
            ) ||
            value.contains(
                "/c/"
            ) ||
            value.contains(
                "/user/"
            )
    }

    private fun isPlaylistUrl(
        url: String
    ): Boolean {

        return url
            .lowercase()
            .contains(
                "/playlist?list="
            )
    }

    /*
     * ============================================================
     * VIDEO LOAD
     * ============================================================
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

            if (
                info.duration > 0
            ) {

                duration =
                    info.duration
                        .toInt()
            }

            info.uploaderName
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { uploader ->

                    actors =
                        listOf(
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
                listOf(
                    "Kids",
                    "Age 3-6"
                )
        }
    }

    /*
     * ============================================================
     * CHANNEL LOAD
     * ============================================================
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

        val description =
            extractor.description

        val avatar =
            extractor.avatars
                .lastOrNull()
                ?.url

        val banner =
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

        var pagesLoaded =
            0

        val maxPages =
            5

        while (
            pagesLoaded < maxPages
        ) {

            page.items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidate(it)
                }
                .forEach { item ->

                    val itemUrl =
                        item.url
                            ?.trim()
                            ?: return@forEach

                    episodes.add(
                        newEpisode(
                            itemUrl
                        ) {

                            name =
                                item.name

                            posterUrl =
                                item.thumbnails
                                    .lastOrNull()
                                    ?.url
                        }
                    )
                }

            if (
                !page.hasNextPage()
            ) {
                break
            }

            page =
                videosExtractor.getPage(
                    page.nextPage
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
                description

            posterUrl =
                banner

            backgroundPosterUrl =
                banner

            tags =
                listOf(
                    "Kids",
                    "Age 3-6",
                    "Channel"
                )

            actors =
                listOf(
                    ActorData(
                        Actor(
                            channelName,
                            avatar ?: ""
                        )
                    )
                )
        }
    }

    /*
     * ============================================================
     * PLAYLIST LOAD
     * ============================================================
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

        val description =
            extractor.description
                .content
                .toString()

        val thumbnail =
            extractor.thumbnails
                .lastOrNull()
                ?.url

        val uploader =
            extractor.uploaderName

        val episodes =
            mutableListOf<Episode>()

        var page =
            extractor.getInitialPage()

        var pagesLoaded =
            0

        val maxPages =
            10

        while (
            pagesLoaded < maxPages
        ) {

            page.items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidate(it)
                }
                .forEach { item ->

                    val itemUrl =
                        item.url
                            ?.trim()
                            ?: return@forEach

                    episodes.add(
                        newEpisode(
                            itemUrl
                        ) {

                            name =
                                item.name

                            posterUrl =
                                item.thumbnails
                                    .lastOrNull()
                                    ?.url
                        }
                    )
                }

            if (
                !page.hasNextPage()
            ) {
                break
            }

            page =
                extractor.getPage(
                    page.nextPage
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
                description

            posterUrl =
                thumbnail

            tags =
                listOf(
                    "Kids",
                    "Age 3-6"
                )

            if (
                uploader.isNotBlank()
            ) {

                actors =
                    listOf(
                        ActorData(
                            Actor(
                                uploader,
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
     * ============================================================
     * PLAYBACK
     * ============================================================
     *
     * Same direct playback philosophy as the working YouTube
     * provider:
     *
     * VOD  -> DASH
     * LIVE -> HLS
     *
     * No proxy.
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

        val cleanData = data.trim()

        /*
         * First use the same direct NewPipe playback path as the
         * working YouTube provider.
         */
        try {
            val extractor =
                service.getStreamExtractor(cleanData)

            extractor.fetchPage()

            val info =
                StreamInfo.getInfo(extractor)

            val isLive =
                info.streamType
                    ?.name
                    ?.contains("LIVE") == true

            if (isLive) {
                val hlsUrl =
                    runCatching { info.hlsUrl }.getOrNull()

                if (!hlsUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Live",
                            url = hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            referer = "https://www.youtube.com/"
                            quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            }

            val dashUrl =
                runCatching { info.dashMpdUrl }.getOrNull()

            if (!dashUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Adaptive",
                        url = dashUrl,
                        type = ExtractorLinkType.DASH
                    ) {
                        referer = "https://www.youtube.com/"
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            val hlsUrl =
                runCatching { info.hlsUrl }.getOrNull()

            if (!hlsUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "https://www.youtube.com/"
                        quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (_: Exception) {
            // Continue to CloudStream's extractor fallback.
        }

        /*
         * CloudStream's extractor is the second playback path.
         * This is important because YouTube can change the direct
         * stream response independently of the search result.
         */
        try {
            if (loadExtractor(cleanData, subtitleCallback, callback)) {
                return true
            }
        } catch (_: Exception) {
            // Try a canonical YouTube watch URL below.
        }

        /*
         * Some YouTube links contain extra parameters. Rebuild a
         * canonical watch URL when a video id can be extracted.
         */
        val canonicalUrl =
            canonicalYouTubeWatchUrl(cleanData)

        if (canonicalUrl != null && canonicalUrl != cleanData) {
            try {
                if (loadExtractor(canonicalUrl, subtitleCallback, callback)) {
                    return true
                }
            } catch (_: Exception) {
                // No more playback fallbacks available.
            }
        }

        return false
    }

    private fun canonicalYouTubeWatchUrl(
        url: String
    ): String? {
        val value = url.trim()

        val watchIndex =
            value.indexOf("/watch?v=", ignoreCase = true)

        if (watchIndex >= 0) {
            val idStart = watchIndex + "/watch?v=".length
            val id =
                value.substring(idStart)
                    .substringBefore('&')
                    .substringBefore('#')
                    .trim()

            if (id.isNotBlank()) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        val shortMarker =
            "youtu.be/"

        val shortIndex =
            value.indexOf(shortMarker, ignoreCase = true)

        if (shortIndex >= 0) {
            val id =
                value.substring(shortIndex + shortMarker.length)
                    .substringBefore('?')
                    .substringBefore('&')
                    .substringBefore('#')
                    .trim()

            if (id.isNotBlank()) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        val shortsMarker =
            "/shorts/"

        val shortsIndex =
            value.indexOf(shortsMarker, ignoreCase = true)

        if (shortsIndex >= 0) {
            val id =
                value.substring(shortsIndex + shortsMarker.length)
                    .substringBefore('?')
                    .substringBefore('&')
                    .substringBefore('#')
                    .trim()

            if (id.isNotBlank()) {
                return "https://www.youtube.com/watch?v=$id"
            }
        }

        return null
    }

    /*
     * ============================================================
     * HELPERS
     * ============================================================
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

    private fun isPreferredBanglaChannel(
        uploader: String
    ): Boolean {

        if (
            uploader.isBlank()
        ) {
            return false
        }

        return preferredBanglaChannels.any {
            uploader.equals(
                it,
                ignoreCase = true
            ) ||
                uploader.contains(
                    it,
                    ignoreCase = true
                )
        }
    }

    private fun isPreferredKidsChannel(
        uploader: String
    ): Boolean {

        if (
            uploader.isBlank()
        ) {
            return false
        }

        return preferredKidsChannels.any {
            uploader.equals(
                it,
                ignoreCase = true
            ) ||
                uploader.contains(
                    it,
                    ignoreCase = true
                )
        }
    }

    private fun sectionDisplayName(
        section: String
    ): String {

        return when (section) {

            "recommended" ->
                "Recommended for Kids"

            "bangla" ->
                "বাংলা Kids"

            "learning" ->
                "Learning"

            "rhymes" ->
                "Nursery Rhymes"

            "cartoons" ->
                "Cartoons"

            "stories" ->
                "Stories"

            "music" ->
                "Kids Music"

            "animals" ->
                "Animals & Nature"

            "cars" ->
                "Cars, Trucks & Machines"

            "play" ->
                "Play & Create"

            "explore" ->
                "Explore"

            else ->
                section
        }
    }
}
