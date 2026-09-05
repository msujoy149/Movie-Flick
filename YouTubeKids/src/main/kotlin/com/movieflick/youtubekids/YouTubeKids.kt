package com.movieflick.youtubekids

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URLDecoder
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

    /*
     * ============================================================
     * HOME
     * ============================================================
     *
     * The devotional religion row is intentionally separate from general kids music.
     * "Religion". This is a separate Hindu/Sanatan kids-devotional
     * feed, not a generic music feed.
     */

    override val mainPage = mainPageOf(
        "recommended" to "Recommended for Kids",
        "bangla" to "বাংলা Kids",
        "learning" to "Learning",
        "religion" to "Religion",
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
     * PERFORMANCE / CACHE
     * ============================================================
     */

    private companion object {
        const val FAST_VISIBLE_COUNT = 6
        const val FULL_CACHE_COUNT = 35

        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val FAST_TOTAL_TIMEOUT_MS = 2_200L
        const val FAST_QUERY_TIMEOUT_MS = 1_700L
        const val BACKGROUND_QUERY_TIMEOUT_MS = 8_000L

        // Give CloudStream time to paint the first screen before the
        // all-category background worker starts.
        const val BACKGROUND_START_DELAY_MS = 1_200L

        val PREWARM_STARTED = AtomicBoolean(false)

        /*
         * Specialized sections run before Recommended in the
         * background owner pass. This reduces cross-category
         * duplication because specific content gets claimed by the
         * specific row before the broad Recommended row is enriched.
         */
        val BACKGROUND_SECTION_ORDER = listOf(
            "bangla",
            "learning",
            "religion",
            "cartoons",
            "stories",
            "music",
            "animals",
            "cars",
            "play",
            "explore",
            "recommended"
        )
    }

    private val backgroundScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private data class CachedSection(
        val expiresAt: Long,
        val items: List<SearchResponse>
    )

    private data class ProgressiveCandidate(
        val section: String,
        val id: String,
        val score: Int,
        val item: StreamInfoItem
    )

    private val cache =
        ConcurrentHashMap<String, CachedSection>()

    private val refreshRunning =
        ConcurrentHashMap<String, Boolean>()

    private val queryRunning =
        ConcurrentHashMap<String, Boolean>()

    private val backgroundProgress =
        ConcurrentHashMap<String, Int>()

    /*
     * Global home ownership:
     *
     * A YouTube video id can belong to one home section at a time.
     * This is what stops the same exact video from being sprayed
     * across every category just because multiple YouTube searches
     * return it.
     */
    private val homeVideoOwner =
        ConcurrentHashMap<String, String>()

    private val ownerLock = Any()

    /*
     * ============================================================
     * CHANNEL SIGNALS
     * ============================================================
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
     * CONTENT SAFETY
     * ============================================================
     */

    private val unsafeSignals = listOf(
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

    private val lowQualitySignals = listOf(
        "reupload",
        "re-upload",
        "fan made",
        "fanmade",
        "mega compilation",
        "24/7",
        "full movie",
        "movie explained",
        "episode explained",
        "reaction",
        "review",
        "commentary",
        "clickbait"
    )

    private val ageSignals = listOf(
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
        "animation",
        "animated",
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
     * HINDU / SANATAN RELIGION SIGNALS
     * ============================================================
     *
     * This feed is intentionally narrower than ordinary kids music.
     */

    private val religionSignals = listOf(
        "hindu",
        "hinduism",
        "sanatan",
        "sanatana",
        "devotional",
        "devotion",
        "bhajan",
        "bhajans",
        "kirtan",
        "kirtana",
        "mantra",
        "stotram",
        "stotra",
        "vandana",
        "aarti",
        "arti",
        "krishna",
        "radha",
        "govinda",
        "hare krishna",
        "ganesh",
        "ganesha",
        "shiva",
        "shiv",
        "mahadev",
        "om namah shivaya",
        "durga",
        "maa durga",
        "lakshmi",
        "laxmi",
        "saraswati",
        "saraswathi",
        "hanuman",
        "rama",
        "ram",
        "sita",
        "ramayana",
        "ramayan",
        "kali",
        "kalika",
        "jagannath",
        "narayan",
        "vishnu",
        "bal gopal",
        "kanha",
        "murlidhar",
        "অঞ্জলি",
        "ভক্তিগীতি",
        "ভক্তিগান",
        "ভজন",
        "কীর্তন",
        "মন্ত্র",
        "আরতি",
        "আরাধনা",
        "কৃষ্ণ",
        "রাধা",
        "গোবিন্দ",
        "গণেশ",
        "শিব",
        "মহাদেব",
        "ওঁ নমঃ শিবায়",
        "দুর্গা",
        "লক্ষ্মী",
        "সরস্বতী",
        "হনুমান",
        "রাম",
        "সীতা",
        "রামায়ণ",
        "কালী",
        "জগন্নাথ",
        "নারায়ণ",
        "বিষ্ণু"
    )

    /*
     * ============================================================
     * SECTION QUERIES
     * ============================================================
     *
     * Each row has several independent search intents.
     * The first two are the fast path; the remainder are
     * progressively fetched one query at a time in background.
     */

    private val sectionQueries = mapOf(
        "recommended" to listOf(
            "Bangla kids learning cartoons age 3 4 5 6",
            "Bangla kids songs stories learning preschool",
            "Bengali kids educational videos preschool",
            "safe educational cartoons for kids 3 4 5 6",
            "kids animals learning preschool",
            "kids cars trucks machines preschool",
            "kids drawing craft play preschool",
            "kids science space nature preschool"
        ),

        "bangla" to listOf(
            "Bangla kids cartoon",
            "বাংলা শিশুদের গান কার্টুন",
            "বাংলা শিশুদের গল্প",
            "বাংলা শিশুশিক্ষা ABC সংখ্যা",
            "Bangla baby learning preschool",
            "বাংলা শিশুদের ছড়া গান",
            "Bangla kids bedtime story",
            "Bangla preschool educational videos"
        ),

        "learning" to listOf(
            "kids learning ABC phonics age 3 4 5 6",
            "kids learning numbers counting colors shapes preschool",
            "preschool educational videos 3 4 5 6",
            "kids science learning preschool",
            "kids vocabulary learning English preschool",
            "Bangla kids learning ABC numbers",
            "kids problem solving learning preschool",
            "kids general knowledge learning age 3 4 5 6"
        ),

        "religion" to listOf(
            "Hindu devotional songs for kids",
            "Sanatan bhajan children",
            "Krishna bhajan kids",
            "Bal Krishna songs for children",
            "Ganesh bhajan kids",
            "Saraswati vandana kids",
            "Hanuman bhajan children",
            "Shiva bhajan kids",
            "Durga bhajan kids",
            "Lakshmi bhajan kids",
            "Rama bhajan kids",
            "বাংলা হিন্দু শিশুদের ভক্তিগীতি",
            "বাংলা কৃষ্ণের গান শিশুদের",
            "বাংলা গণেশের গান শিশুদের",
            "বাংলা সরস্বতী বন্দনা শিশু"
        ),

        "cartoons" to listOf(
            "Bangla kids cartoon",
            "safe cartoons for preschool kids",
            "educational cartoons age 3 4 5 6",
            "Peppa Pig kids",
            "Pocoyo kids",
            "Bluey kids",
            "Masha and the Bear kids",
            "Bebefinn kids cartoon"
        ),

        "stories" to listOf(
            "Bangla kids stories",
            "Bangla bedtime stories for children",
            "kids story age 3 4 5 6",
            "preschool bedtime stories",
            "fairy tales for kids",
            "educational stories for children",
            "moral stories for kids",
            "Panchatantra stories for children"
        ),

        "music" to listOf(
            "Bangla kids songs",
            "Bangla baby songs",
            "kids songs preschool",
            "Cocomelon kids songs",
            "Super Simple Songs",
            "Pinkfong kids songs",
            "Baby Shark kids",
            "Bebefinn songs kids"
        ),

        "animals" to listOf(
            "animals for kids preschool",
            "animal sounds for kids",
            "Bangla animals for children",
            "wild animals learning kids",
            "farm animals for kids",
            "ocean animals for kids",
            "dinosaur learning kids",
            "birds insects nature for kids"
        ),

        "cars" to listOf(
            "cars trucks machines for kids",
            "construction vehicles for kids",
            "excavator truck tractor kids",
            "toy cars for kids",
            "Gecko's Garage",
            "Thomas and Friends kids",
            "vehicle learning preschool",
            "garbage truck fire truck ambulance kids"
        ),

        "play" to listOf(
            "kids drawing craft preschool",
            "play and learn kids age 3 4 5 6",
            "pretend play kids",
            "kids toys educational play",
            "easy crafts for preschool kids",
            "kids coloring learning",
            "lego building kids preschool",
            "sensory play kids"
        ),

        "explore" to listOf(
            "kids science experiments preschool",
            "space for kids preschool",
            "nature for kids educational",
            "world for kids learning",
            "dinosaurs for kids educational",
            "how things work for kids",
            "weather for kids preschool",
            "simple geography for kids"
        )
    )

    /*
     * ============================================================
     * STARTUP BACKGROUND ENRICHMENT
     * ============================================================
     *
     * Delayed round-robin:
     *   category A query 1
     *   category B query 1
     *   category C query 1
     *   ...
     *   category A query 2
     *   category B query 2
     *   ...
     *
     * This avoids the old failure mode where only one category was
     * allowed to consume all background work first.
     */

    init {
        if (PREWARM_STARTED.compareAndSet(false, true)) {
            backgroundScope.launch {
                delay(BACKGROUND_START_DELAY_MS)
                runRoundRobinBackgroundFill()
            }
        }
    }

    private suspend fun runRoundRobinBackgroundFill() {
        val maxQueries =
            BACKGROUND_SECTION_ORDER
                .maxOfOrNull {
                    sectionQueries[it]?.size ?: 0
                }
                ?: 0

        for (queryIndex in 0 until maxQueries) {
            for (section in BACKGROUND_SECTION_ORDER) {
                if (queryIndex >= (sectionQueries[section]?.size ?: 0)) {
                    continue
                }

                runCatching {
                    backgroundFetchOneQuery(
                        section = section,
                        queryIndex = queryIndex
                    )
                }

                /*
                 * Give the runtime a scheduling point after every
                 * individual category request.
                 */
                kotlinx.coroutines.yield()
            }
        }
    }

    /*
     * ============================================================
     * CACHE
     * ============================================================
     */

    private fun getCached(section: String): List<SearchResponse> {
        val entry = cache[section] ?: return emptyList()

        if (entry.expiresAt <= System.currentTimeMillis()) {
            return emptyList()
        }

        return entry.items.take(FULL_CACHE_COUNT)
    }

    private fun putCached(
        section: String,
        items: List<SearchResponse>
    ) {
        val unique =
            items
                .filter { it.url.isNotBlank() }
                .distinctBy {
                    videoIdFromUrl(it.url) ?: it.url
                }
                .take(FULL_CACHE_COUNT)

        if (unique.isEmpty()) {
            return
        }

        cache[section] =
            CachedSection(
                expiresAt =
                    System.currentTimeMillis() +
                        CACHE_TTL_MS,
                items = unique
            )
    }

    private fun clearExpiredSectionOwnership(section: String) {
        val current =
            cache[section]

        if (
            current == null ||
            current.expiresAt <= System.currentTimeMillis()
        ) {
            synchronized(ownerLock) {
                homeVideoOwner.entries
                    .filter { it.value == section }
                    .forEach {
                        homeVideoOwner.remove(it.key, section)
                    }
            }
        }
    }

    /*
     * ============================================================
     * FAST SEARCH
     * ============================================================
     */

    private suspend fun fetchSearchItemsFast(
        queries: List<String>
    ): List<StreamInfoItem> {
        val clean =
            queries
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(2)

        if (clean.isEmpty()) {
            return emptyList()
        }

        val grouped =
            withTimeoutOrNull(
                FAST_TOTAL_TIMEOUT_MS
            ) {
                coroutineScope {
                    clean.map { query ->
                        async(Dispatchers.IO) {
                            withTimeoutOrNull(
                                FAST_QUERY_TIMEOUT_MS
                            ) {
                                runCatching {
                                    fetchSearchItems(
                                        query = query
                                    )
                                }.getOrElse {
                                    emptyList()
                                }
                            } ?: emptyList()
                        }
                    }.awaitAll()
                }
            } ?: emptyList()

        return grouped
            .flatten()
            .filter { isSupportedStreamItem(it) }
            .distinctBy {
                videoIdFromUrl(it.url) ?: it.url
            }
    }

    private suspend fun fetchSearchItems(
        query: String
    ): List<StreamInfoItem> {
        return runCatching {
            val extractor =
                service.getSearchExtractor(query)

            extractor.fetchPage()

            extractor
                .initialPage
                .items
                .filterIsInstance<StreamInfoItem>()
                .toList()
        }.getOrElse {
            emptyList()
        }
    }

    /*
     * ============================================================
     * BACKGROUND SINGLE-QUERY FETCH
     * ============================================================
     *
     * This is intentionally different from the old "fetch everything,
     * then cache once" design. The cache is updated after each query.
     */

    private suspend fun backgroundFetchOneQuery(
        section: String,
        queryIndex: Int
    ) {
        val queries =
            sectionQueries[section]
                ?: return

        if (queryIndex >= queries.size) {
            return
        }

        val currentProgress =
            backgroundProgress[section]
                ?: 0

        /*
         * Fast category request may already have consumed query 0/1.
         * Never redo those as background work.
         */
        if (queryIndex < currentProgress) {
            return
        }

        if (
            queryRunning.putIfAbsent(
                section,
                true
            ) != null
        ) {
            return
        }

        try {
            val query =
                queries[queryIndex]

            val fresh =
                withTimeoutOrNull(
                    BACKGROUND_QUERY_TIMEOUT_MS
                ) {
                    fetchSearchItems(query)
                } ?: emptyList()

            val candidates =
                fresh
                    .filter {
                        isSectionCandidate(
                            item = it,
                            section = section
                        )
                    }

            mergeCandidatesIntoSection(
                section = section,
                candidates = candidates
            )

            backgroundProgress.compute(
                section
            ) { _, old ->
                maxOf(
                    old ?: 0,
                    queryIndex + 1
                )
            }
        } finally {
            queryRunning.remove(section)
        }
    }

    /*
     * ============================================================
     * SECTION CANDIDATE FILTERING
     * ============================================================
     */

    private fun isSupportedStreamItem(
        item: StreamInfoItem
    ): Boolean {
        if (
            item.streamType !=
            StreamType.VIDEO_STREAM
        ) {
            return false
        }

        if (item.isShortFormContent) {
            return false
        }

        val title =
            item.name
                ?.trim()
                .orEmpty()

        if (title.isBlank()) {
            return false
        }

        return true
    }

    private fun isSectionCandidate(
        item: StreamInfoItem,
        section: String
    ): Boolean {
        if (!isSupportedStreamItem(item)) {
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

        val combined =
            "$title $uploader"
                .lowercase()

        if (containsAny(combined, unsafeSignals)) {
            return false
        }

        if (containsAny(combined, lowQualitySignals)) {
            return false
        }

        val duration =
            item.duration

        if (duration in 1L..25L) {
            return false
        }

        if (duration > 60L * 60L) {
            return false
        }

        return when (section) {
            "religion" ->
                isReligionCandidate(
                    combined = combined,
                    duration = duration
                )

            "learning" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    (
                        containsAny(
                            combined,
                            listOf(
                                "learn",
                                "learning",
                                "education",
                                "educational",
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
                        ) ||
                            preferredKids(uploader)
                    )

            "cartoons" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    (
                        containsAny(
                            combined,
                            listOf(
                                "cartoon",
                                "cartoons",
                                "animation",
                                "animated",
                                "peppa",
                                "pocoyo",
                                "bluey",
                                "masha",
                                "bebefinn"
                            )
                        ) ||
                            preferredKids(uploader)
                    )

            "stories" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "story",
                            "stories",
                            "bedtime",
                            "fairy tale",
                            "fairytale",
                            "panchatantra",
                            "moral story"
                        )
                    )

            "animals" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "animal",
                            "animals",
                            "dinosaur",
                            "farm",
                            "ocean",
                            "wildlife",
                            "bird",
                            "birds",
                            "nature"
                        )
                    )

            "cars" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
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
                            "vehicle",
                            "ambulance",
                            "fire truck",
                            "garbage truck"
                        )
                    )

            "play" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "play",
                            "drawing",
                            "craft",
                            "coloring",
                            "colouring",
                            "toy",
                            "lego",
                            "sensory"
                        )
                    )

            "explore" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "science",
                            "space",
                            "nature",
                            "dinosaur",
                            "how things work",
                            "weather",
                            "geography"
                        )
                    )

            "music" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                ) &&
                    containsAny(
                        combined,
                        listOf(
                            "song",
                            "songs",
                            "music",
                            "sing",
                            "dance"
                        )
                    )

            "bangla" ->
                containsAny(
                    combined,
                    listOf(
                        "bangla",
                        "bengali",
                        "বাংলা",
                        "শিশু",
                        "বাচ্চা"
                    )
                ) &&
                    isKidsCandidateGeneral(
                        combined = combined,
                        uploader = uploader
                    )

            "recommended" ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                )

            else ->
                isKidsCandidateGeneral(
                    combined = combined,
                    uploader = uploader
                )
        }
    }

    private fun isReligionCandidate(
        combined: String,
        duration: Long
    ): Boolean {
        if (!containsAny(combined, religionSignals)) {
            return false
        }

        /*
         * Religion items are allowed to omit words such as "kids"
         * because many genuine child-friendly bhajans do not put
         * "kids" in the title. Safety and duration are still enforced.
         */
        if (duration > 60L * 60L) {
            return false
        }

        return true
    }

    private fun isKidsCandidateGeneral(
        combined: String,
        uploader: String
    ): Boolean {
        return containsAny(
            combined,
            ageSignals
        ) ||
            preferredKids(uploader) ||
            preferredBangla(uploader)
    }

    /*
     * ============================================================
     * SCORING
     * ============================================================
     */

    private fun scoreItem(
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
            score += 90
        }

        if (preferredBangla(uploader)) {
            score += 180
        }

        if (preferredKids(uploader)) {
            score += 140
        }

        if (
            containsAny(
                combined,
                listOf(
                    "preschool",
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
            score += 80
        }

        when (section) {
            "religion" -> {
                if (
                    containsAny(
                        combined,
                        religionSignals
                    )
                ) {
                    score += 350
                }

                if (
                    containsAny(
                        combined,
                        listOf(
                            "kids",
                            "children",
                            "child",
                            "bal",
                            "baby"
                        )
                    )
                ) {
                    score += 80
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
                            "educational",
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
                    score += 220
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
                            "animated",
                            "peppa",
                            "pocoyo",
                            "bluey",
                            "masha",
                            "bebefinn"
                        )
                    )
                ) {
                    score += 220
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
                            "fairytale",
                            "panchatantra",
                            "moral story"
                        )
                    )
                ) {
                    score += 220
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
                            "sing",
                            "dance"
                        )
                    )
                ) {
                    score += 180
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
                            "wildlife",
                            "birds",
                            "nature"
                        )
                    )
                ) {
                    score += 220
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
                            "vehicle",
                            "ambulance",
                            "fire truck",
                            "garbage truck"
                        )
                    )
                ) {
                    score += 220
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
                            "toy",
                            "lego",
                            "sensory"
                        )
                    )
                ) {
                    score += 220
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
                            "how things work",
                            "weather",
                            "geography"
                        )
                    )
                ) {
                    score += 220
                }
            }

            "bangla" -> {
                score +=
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
                        220
                    } else {
                        0
                    }
            }

            "recommended" -> {
                /*
                 * Broad feed: do not over-reward highly specialized
                 * titles. Specialized rows get first ownership in the
                 * background round-robin.
                 */
                if (
                    containsAny(
                        combined,
                        listOf(
                            "learning",
                            "cartoon",
                            "story",
                            "bhajan",
                            "krishna",
                            "ganesh",
                            "shiva",
                            "durga",
                            "craft",
                            "vehicle"
                        )
                    )
                ) {
                    score -= 25
                }
            }
        }

        val duration =
            item.duration

        if (duration in 60L..12L * 60L) {
            score += 35
        }

        if (duration in 12L * 60L..30L * 60L) {
            score += 20
        }

        return score
    }

    /*
     * ============================================================
     * OWNERSHIP / DEDUP
     * ============================================================
     */

    private fun reserveVideoIds(
        section: String,
        itemList: List<StreamInfoItem>
    ): List<StreamInfoItem> {
        synchronized(ownerLock) {
            val output =
                mutableListOf<StreamInfoItem>()

            for (item in itemList) {
                val id =
                    videoIdFromUrl(item.url)
                        ?: continue

                val owner =
                    homeVideoOwner[id]

                if (
                    owner != null &&
                    owner != section
                ) {
                    continue
                }

                homeVideoOwner[id] = section
                output.add(item)
            }

            return output
        }
    }

    private fun mergeCandidatesIntoSection(
        section: String,
        candidates: List<StreamInfoItem>
    ) {
        if (candidates.isEmpty()) {
            return
        }

        val existingResponses =
            cache[section]
                ?.items
                .orEmpty()

        val existingIds =
            existingResponses
                .mapNotNull {
                    videoIdFromUrl(it.url)
                }
                .toSet()

        /*
         * Do not let a video that is already owned by another section
         * enter this section. Videos already owned by this same section
         * are allowed to remain.
         */
        val available =
            candidates
                .filter {
                    val id =
                        videoIdFromUrl(it.url)

                    id != null &&
                        id !in existingIds &&
                        (
                            homeVideoOwner[id] == null ||
                                homeVideoOwner[id] == section
                            )
                }
                .sortedByDescending {
                    scoreItem(
                        item = it,
                        section = section
                    )
                }

        if (available.isEmpty()) {
            return
        }

        /*
         * Convert only the newly accepted items to SearchResponse.
         * Existing SearchResponses are kept verbatim, so the cache really
         * grows after each background query instead of being replaced by
         * only the newest query.
         */
        val newItems =
            available
                .take(
                    maxOf(
                        0,
                        FULL_CACHE_COUNT -
                            existingResponses.size
                    )
                )

        val newResponses =
            newItems
                .mapNotNull {
                    infoItemToSearchResponse(it)
                }

        if (newResponses.isEmpty()) {
            return
        }

        /*
         * Only reserve IDs that actually enter this section's cache.
         * Merely seeing a search result must NOT block another category.
         */
        synchronized(ownerLock) {
            for (response in newResponses) {
                val id =
                    videoIdFromUrl(response.url)
                        ?: continue

                val owner =
                    homeVideoOwner[id]

                if (
                    owner == null ||
                    owner == section
                ) {
                    homeVideoOwner[id] = section
                }
            }
        }

        val merged =
            existingResponses +
                newResponses

        val unique =
            merged
                .distinctBy {
                    videoIdFromUrl(it.url) ?: it.url
                }

        /*
         * Search-response objects do not contain the full StreamInfoItem
         * score metadata, so the background cache preserves existing order
         * and rotates only the tail. Newer background items are appended
         * before the rotation pass.
         */
        val rotated =
            rotateSearchResponses(
                items = unique,
                section = section
            )

        putCached(
            section = section,
            items = rotated
        )
    }

    private fun rotateSearchResponses(
        items: List<SearchResponse>,
        section: String
    ): List<SearchResponse> {
        if (items.size <= FAST_VISIBLE_COUNT) {
            return items.take(FULL_CACHE_COUNT)
        }

        val stableCount =
            minOf(
                4,
                items.size
            )

        val stable =
            items.take(stableCount)

        val rotating =
            items
                .drop(stableCount)
                .toMutableList()

        val bucket =
            System.currentTimeMillis() /
                (10 * 60 * 1000L)

        rotating.shuffle(
            Random(
                section.hashCode()
                    .toLong()
                    xor bucket
            )
        )

        return (
            stable + rotating
            ).take(FULL_CACHE_COUNT)
    }

    /*
     * ============================================================
     * FAST SECTION BUILD
     * ============================================================
     */

    private suspend fun buildFastSection(
        section: String
    ): HomePageResponse {
        val queries =
            sectionQueries[section]
                ?: return emptyHomePage()

        clearExpiredSectionOwnership(section)

        val items =
            fetchSearchItemsFast(queries)
                .filter {
                    isSectionCandidate(
                        item = it,
                        section = section
                    )
                }
                .distinctBy {
                    videoIdFromUrl(it.url) ?: it.url
                }
                .sortedByDescending {
                    scoreItem(
                        item = it,
                        section = section
                    )
                }

        val available =
            synchronized(ownerLock) {
                items.filter { item ->
                    val id =
                        videoIdFromUrl(item.url)
                            ?: return@filter false

                    val owner =
                        homeVideoOwner[id]

                    owner == null ||
                        owner == section
                }
            }

        val selected =
            rotateResults(
                ranked = available,
                section = section
            ).take(FAST_VISIBLE_COUNT)

        synchronized(ownerLock) {
            selected.forEach { item ->
                videoIdFromUrl(item.url)?.let { id ->
                    homeVideoOwner[id] = section
                }
            }
        }

        val results =
            selected.mapNotNull {
                infoItemToSearchResponse(it)
            }

        if (results.isNotEmpty()) {
            putCached(
                section = section,
                items = results
            )
        }

        /*
         * The first two search intents have now been consumed by the
         * fast path. Background starts from query #3.
         */
        backgroundProgress[section] = 2

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

    /*
     * ============================================================
     * ROTATION
     * ============================================================
     */

    private fun rotateResults(
        ranked: List<StreamInfoItem>,
        section: String
    ): List<StreamInfoItem> {
        if (ranked.size <= FAST_VISIBLE_COUNT) {
            return ranked
        }

        val stableCount =
            minOf(
                4,
                ranked.size
            )

        val stable =
            ranked.take(stableCount)

        val rotating =
            ranked
                .drop(stableCount)
                .toMutableList()

        val bucket =
            System.currentTimeMillis() /
                (10 * 60 * 1000L)

        rotating.shuffle(
            Random(
                section.hashCode()
                    .toLong()
                    xor bucket
            )
        )

        return stable + rotating
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
        if (page > 1) {
            return emptyHomePage()
        }

        val section =
            request.data

        val cached =
            getCached(section)

        if (cached.isNotEmpty()) {
            startSectionBackgroundRefresh(section)

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

        val fast =
            buildFastSection(section)

        startSectionBackgroundRefresh(section)

        return fast
    }

    private fun startSectionBackgroundRefresh(
        section: String
    ) {
        if (
            sectionQueries[section].isNullOrEmpty()
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

        backgroundScope.launch {
            try {
                /*
                 * Do not aggressively fetch everything again immediately.
                 * The global round-robin startup worker handles long-lived
                 * enrichment. This per-section worker is primarily for
                 * sections opened by the user after startup or after cache
                 * expiry.
                 */
                var index =
                    backgroundProgress[section] ?: 2

                val queries =
                    sectionQueries[section]
                        ?: emptyList()

                while (
                    index < queries.size
                ) {
                    backgroundFetchOneQuery(
                        section = section,
                        queryIndex = index
                    )

                    index++
                    backgroundProgress[section] = index

                    kotlinx.coroutines.yield()
                }
            } catch (_: Exception) {
                // Background work must never break the visible UI.
            } finally {
                refreshRunning.remove(section)
            }
        }
    }

    private fun emptyHomePage(): HomePageResponse {
        return newHomePageResponse(
            emptyList(),
            false
        )
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */

    private val searchPageCache =
        ConcurrentHashMap<
            String,
            org.schabi.newpipe.extractor.Page?
            >()

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val clean =
            query.trim()

        if (clean.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cacheKey =
            clean.lowercase()

        val extractor =
            try {
                service.getSearchExtractor(
                    clean
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
                    !searchPageCache.containsKey(cacheKey)
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

        val results =
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .mapNotNull {
                    infoItemToSearchResponse(it)
                }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun isKidsCandidateForSearch(
        item: StreamInfoItem
    ): Boolean {
        if (!isSupportedStreamItem(item)) {
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

        val combined =
            "$title $uploader"
                .lowercase()

        if (containsAny(combined, unsafeSignals)) {
            return false
        }

        if (containsAny(combined, lowQualitySignals)) {
            return false
        }

        return isKidsCandidateGeneral(
            combined = combined,
            uploader = uploader
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
     * ============================================================
     * INFO ITEM -> SEARCH RESPONSE
     * ============================================================
     *
     * IMPORTANT:
     * Home/search cards now store a canonical YouTube watch URL.
     * This prevents player failures caused by short or parameterized
     * search-result URLs.
     */

    private fun infoItemToSearchResponse(
        item: StreamInfoItem
    ): SearchResponse? {
        val title =
            item.name
                ?.trim()
                ?: return null

        val rawUrl =
            item.url
                ?.trim()
                ?: return null

        val url =
            canonicalYouTubeUrl(
                rawUrl
            )

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
                    ?.takeIf {
                        it.isNotBlank()
                    }
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
                loadVideo(
                    canonicalYouTubeUrl(url)
                )

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

    private fun isVideoUrl(
        url: String
    ): Boolean {
        return videoIdFromUrl(url) != null
    }

    private fun isChannelUrl(
        url: String
    ): Boolean {
        val value =
            url.lowercase()

        return value.contains("/channel/") ||
            value.contains("/@") ||
            value.contains("/c/") ||
            value.contains("/user/")
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
        val clean =
            canonicalYouTubeUrl(url)

        val extractor =
            service.getStreamExtractor(
                clean
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
                    "LIVE",
                    ignoreCase = true
                ) == true

        return newMovieLoadResponse(
            info.name,
            clean,
            if (isLive) {
                TvType.Live
            } else {
                TvType.Others
            },
            clean
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
     * CHANNEL
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

        val videosTab =
            extractor.tabs
                .firstOrNull {
                    it.url.contains(
                        "/videos"
                    )
                }
                ?: extractor.tabs.firstOrNull()
                ?: throw RuntimeException(
                    "No videos tab found"
                )

        val videosExtractor =
            service.getChannelTabExtractor(
                videosTab
            )

        val episodes =
            mutableListOf<Episode>()

        var pageData =
            videosExtractor.initialPage

        var pages = 0

        while (pages < 5) {
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .forEach { item ->
                    val itemUrl =
                        item.url
                            ?.trim()
                            .orEmpty()

                    if (itemUrl.isNotBlank()) {
                        episodes.add(
                            newEpisode(
                                canonicalYouTubeUrl(
                                    itemUrl
                                )
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
                }

            if (
                !pageData.hasNextPage()
            ) {
                break
            }

            pageData =
                videosExtractor.getPage(
                    pageData.nextPage
                )

            pages++
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
                    ?: avatar

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
     * PLAYLIST
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

        var pageData =
            extractor.getInitialPage()

        var pages = 0

        while (pages < 10) {
            pageData
                .items
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    isKidsCandidateForSearch(it)
                }
                .forEach { item ->
                    val itemUrl =
                        item.url
                            ?.trim()
                            .orEmpty()

                    if (itemUrl.isNotBlank()) {
                        episodes.add(
                            newEpisode(
                                canonicalYouTubeUrl(
                                    itemUrl
                                )
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
                }

            if (
                !pageData.hasNextPage()
            ) {
                break
            }

            pageData =
                extractor.getPage(
                    pageData.nextPage
                )

            pages++
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
                    "Age 3-6",
                    "Playlist"
                )

            if (uploader.isNotBlank()) {
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
     * There are now THREE independent layers:
     *
     *   1) Delegate to the already-working main YouTube provider.
     *   2) Direct NewPipe extraction in this provider.
     *      - progressive/muxed VIDEO stream first
     *      - LIVE HLS
     *      - DASH
     *      - HLS
     *   3) CloudStream's registered YouTube extractors.
     *
     * Stream URLs are always resolved when the user clicks Play.
     * No signed media URL is stored in the home cache.
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

        val original =
            data.trim()

        val canonical =
            canonicalYouTubeUrl(
                original
            )

        /*
         * ----------------------------------------------------------
         * PATH 1: DIRECT NEWPIPE
         * ----------------------------------------------------------
         *
         * This is the same underlying YouTube/NewPipe extractor path
         * used by the working YouTube provider. Media URLs are resolved
         * only when Play is pressed.
         */

        val directUrls =
            linkedSetOf(
                canonical,
                original
            )

        for (videoUrl in directUrls) {
            val emitted =
                runCatching {
                    val extractor =
                        service.getStreamExtractor(
                            videoUrl
                        )

                    extractor.fetchPage()

                    val info =
                        StreamInfo.getInfo(
                            extractor
                        )

                    // Resolve LIVE status first. For VOD, prefer the adaptive
                    // DASH manifest below because YouTube's muxed progressive
                    // streams are commonly limited to low resolutions.
                    val isLive =
                        info.streamType
                            ?.name
                            ?.contains(
                                "LIVE",
                                ignoreCase = true
                            ) == true

                    /*
                     * LIVE -> HLS
                     */
                    if (isLive) {
                        val hls =
                            runCatching {
                                info.hlsUrl
                            }.getOrNull()

                        if (
                            !hls.isNullOrBlank()
                        ) {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name Live",
                                    url = hls,
                                    type =
                                        ExtractorLinkType.M3U8
                                ) {
                                    referer =
                                        "https://www.youtube.com/"

                                    headers =
                                        mapOf(
                                            "User-Agent" to
                                                USER_AGENT
                                        )

                                    quality =
                                        Qualities.Unknown.value
                                }
                            )

                            return true
                        }
                    }

                    /*
                     * VOD -> DASH FIRST
                     *
                     * The adaptive DASH manifest is the important path for
                     * HD / Full HD / 2K / 4K / higher source resolutions.
                     * Do not replace it with a fixed low-resolution muxed
                     * stream when DASH is available.
                     */
                    val dash =
                        runCatching {
                            info.dashMpdUrl
                        }.getOrNull()

                    if (
                        !dash.isNullOrBlank()
                    ) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Adaptive",
                                url = dash,
                                type =
                                    ExtractorLinkType.DASH
                            ) {
                                referer =
                                    "https://www.youtube.com/"

                                headers =
                                    mapOf(
                                        "User-Agent" to
                                            USER_AGENT
                                    )

                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        return true
                    }

                    /*
                     * HLS fallback
                     */
                    val hls =
                        runCatching {
                            info.hlsUrl
                        }.getOrNull()

                    if (
                        !hls.isNullOrBlank()
                    ) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name HLS",
                                url = hls,
                                type =
                                    ExtractorLinkType.M3U8
                            ) {
                                referer =
                                    "https://www.youtube.com/"

                                headers =
                                    mapOf(
                                        "User-Agent" to
                                            USER_AGENT
                                    )

                                quality =
                                    Qualities.Unknown.value
                            }
                        )

                        return true
                    }

                    // Last resort only: muxed progressive video. This path is
                    // intentionally after DASH/HLS so it cannot downgrade an
                    // HD-capable source to the low-resolution progressive stream.
                    if (
                        emitProgressiveVideoStreams(
                            info = info,
                            callback = callback
                        )
                    ) {
                        return true
                    }

                    false
                }.getOrDefault(false)

            if (emitted) {
                return true
            }
        }

        /*
         * ----------------------------------------------------------
         * PATH 3: REGISTERED CLOUDSTREAM EXTRACTORS
         * ----------------------------------------------------------
         *
         * We intentionally try multiple YouTube URL forms because
         * CloudStream can have more than one compatible extractor
         * installed.
         */
        val fallbackUrls =
            linkedSetOf(
                canonical,
                mobileYouTubeUrl(canonical),
                noCookieYouTubeUrl(canonical),
                original
            )

        for (videoUrl in fallbackUrls) {
            var fallbackLinkFound =
                false

            runCatching {
                loadExtractor(
                    videoUrl,
                    subtitleCallback
                ) { link ->
                    fallbackLinkFound = true
                    callback(link)
                }
            }

            if (fallbackLinkFound) {
                return true
            }
        }

        return false
    }

    /*
     * ============================================================
     * PROGRESSIVE / MUXED VIDEO STREAM EXTRACTION
     * ============================================================
     *
     * NewPipe's VideoStream model exposes the media URL/content,
     * resolution and video-only flag directly. This implementation
     * uses the typed API instead of reflection, so Kotlin nullability
     * inference cannot degrade the stream object to Any?.
     *
     * A muxed progressive stream already contains audio + video and
     * is therefore the simplest possible input for ExoPlayer.
     */

    private suspend fun emitProgressiveVideoStreams(
        info: StreamInfo,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        /*
         * NewPipe exposes normal video streams through getVideoStreams().
         * VideoStream entries marked video-only are skipped because a
         * single VIDEO link must already contain the audio track.
         */
        val streams: List<VideoStream> =
            runCatching {
                info.videoStreams
            }.getOrDefault(emptyList())

        if (streams.isEmpty()) {
            return false
        }

        data class Candidate(
            val url: String,
            val resolution: Int
        )

        val candidates =
            streams
                .asSequence()
                .filter { stream ->
                    stream.isUrl &&
                        !stream.isVideoOnly &&
                        stream.content.isNotBlank() &&
                        stream.content.startsWith(
                            "http",
                            ignoreCase = true
                        )
                }
                .map { stream ->
                    Candidate(
                        url = stream.content.trim(),
                        resolution = parseResolution(
                            stream.resolution
                        )
                    )
                }
                .distinctBy { it.url }
                .sortedByDescending { it.resolution }
                .toList()

        if (candidates.isEmpty()) {
            return false
        }

        // If this fallback is reached, still use the highest muxed
        // progressive stream available; never deliberately choose 320p.
        val preferred =
            candidates.first()

        callback(
            newExtractorLink(
                source = name,
                name =
                    if (preferred.resolution > 0) {
                        "$name ${preferred.resolution}p"
                    } else {
                        "$name Direct"
                    },
                url = preferred.url,
                type = ExtractorLinkType.VIDEO
            ) {
                referer =
                    "https://www.youtube.com/"

                headers =
                    mapOf(
                        "User-Agent" to USER_AGENT
                    )

                quality =
                    if (preferred.resolution > 0) {
                        preferred.resolution
                    } else {
                        Qualities.Unknown.value
                    }
            }
        )

        return true
    }

    private fun parseResolution(
        value: String
    ): Int {
        val match =
            Regex(
                "(\\d{3,4})"
            ).find(value)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    /*
     * ============================================================
     * URL NORMALIZATION
     * ============================================================
     *
     * Handles:
     *   watch?v=
     *   &v=
     *   youtu.be/
     *   /shorts/
     *   /live/
     *   /embed/
     *   URL-encoded attribution links
     */

    private fun canonicalYouTubeUrl(
        url: String
    ): String {
        var value =
            url.trim()

        if (value.isBlank()) {
            return value
        }

        value =
            runCatching {
                URLDecoder.decode(
                    value,
                    "UTF-8"
                )
            }.getOrElse {
                value
            }

        val id =
            videoIdFromUrl(value)

        return if (
            !id.isNullOrBlank()
        ) {
            "https://www.youtube.com/watch?v=$id"
        } else {
            value
        }
    }

    private fun videoIdFromUrl(
        url: String?
    ): String? {
        if (url.isNullOrBlank()) {
            return null
        }

        var value =
            url.trim()

        value =
            runCatching {
                URLDecoder.decode(
                    value,
                    "UTF-8"
                )
            }.getOrElse {
                value
            }

        val patterns =
            listOf(
                Regex(
                    "[?&]v=([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "youtu\\.be/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/shorts/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/live/([A-Za-z0-9_-]{6,})"
                ),
                Regex(
                    "/embed/([A-Za-z0-9_-]{6,})"
                )
            )

        for (pattern in patterns) {
            val id =
                pattern
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()

            if (
                !id.isNullOrBlank()
            ) {
                return id
            }
        }

        return null
    }

    private fun mobileYouTubeUrl(
        canonical: String
    ): String {
        val id =
            videoIdFromUrl(canonical)
                ?: return canonical

        return "https://m.youtube.com/watch?v=$id"
    }

    private fun noCookieYouTubeUrl(
        canonical: String
    ): String {
        val id =
            videoIdFromUrl(canonical)
                ?: return canonical

        return "https://www.youtube-nocookie.com/embed/$id"
    }

    /*
     * ============================================================
     * BASIC HELPERS
     * ============================================================
     */

    private fun containsAny(
        text: String,
        words: List<String>
    ): Boolean {
        val value =
            text.lowercase()

        return words.any {
            value.contains(
                it.lowercase()
            )
        }
    }

    private fun preferredBangla(
        uploader: String
    ): Boolean {
        return uploader.isNotBlank() &&
            preferredBanglaChannels.any {
                uploader.contains(
                    it,
                    ignoreCase = true
                )
            }
    }

    private fun preferredKids(
        uploader: String
    ): Boolean {
        return uploader.isNotBlank() &&
            preferredKidsChannels.any {
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

            "religion" ->
                "Religion"

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
