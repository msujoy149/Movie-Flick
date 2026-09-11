
package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

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

    /*
     * FINAL HOMEPAGE:
     *
     * English Movies
     * Hindi Movies
     * Kolkata Bangla Movies
     * South Indian Hindi Dubbed
     * TV Show
     * Anime
     *
     * TV Show is one CloudStream section backed by two real roots.
     */
    override val mainPage = listOf(
        MainPageData(
            "English Movies",
            ROOT_ENGLISH
        ),
        MainPageData(
            "Hindi Movies",
            ROOT_HINDI
        ),
        MainPageData(
            "Kolkata Bangla Movies",
            ROOT_KOLKATA
        ),
        MainPageData(
            "South Indian Hindi Dubbed",
            ROOT_SOUTH
        ),
        MainPageData(
            "TV Show",
            TV_SHOW_ROOTS
        ),
        MainPageData(
            "Anime",
            ROOT_ANIME
        )
    )

    private companion object {

        const val ROOT_ENGLISH =
            "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/"

        const val ROOT_HINDI =
            "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/"

        const val ROOT_KOLKATA =
            "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"

        const val ROOT_SOUTH =
            "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/"

        const val ROOT_TV_1 =
            "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/"

        const val ROOT_TV_2 =
            "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/"

        const val ROOT_ANIME =
            "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/"

        const val TV_SHOW_PREFIX =
            "__DHakaFTP_TV_SHOW__::"

        const val TV_SHOW_SEPARATOR =
            "\u001F"

        val TV_SHOW_ROOTS =
            TV_SHOW_PREFIX +
                ROOT_TV_1 +
                TV_SHOW_SEPARATOR +
                ROOT_TV_2

        const val MOVIE_HOME_SIZE = 6
        const val TV_HOME_SIZE = 6
        const val TV_SOURCE_BATCH = 3
        const val SEARCH_PAGE_SIZE = 50
        const val QUICK_SEARCH_NATIVE_TIMEOUT_MS = 5000L
        const val QUICK_SEARCH_DIRECTORY_TIMEOUT_MS = 1200L

        /*
         * Compatibility alias. This does not remove or change the existing
         * search timeout; it only supplies the name used by the deep loader.
         */
        const val SEARCH_DIRECTORY_TIMEOUT_MS =
            QUICK_SEARCH_DIRECTORY_TIMEOUT_MS

        /*
         * The first synchronous homepage request is intentionally
         * lightweight. The full recursive index has no folder-depth cap.
         */
        const val QUICK_SCAN_DIRECTORY_LIMIT = 96

        /* First-page synchronous probe budget. */
        const val QUICK_DIRECTORY_TIMEOUT_MS = 1250L

        /* Directories fetched concurrently during a quick probe. */
        const val QUICK_SCAN_BATCH_SIZE = 16

        const val CACHE_MINUTES = 10L

        val VIDEO_EXTENSIONS = setOf(
            ".mkv",
            ".mp4",
            ".webm",
            ".avi",
            ".mov",
            ".m4v",
            ".m3u8"
        )

        val IMAGE_EXTENSIONS = setOf(
            ".jpg",
            ".jpeg",
            ".png",
            ".webp"
        )
    }

    private enum class ContentKind {
        MOVIE,
        ANIME,
        SERIES
    }

    private data class FtpEntry(
        val name: String,
        val url: String,
        val isVideo: Boolean,
        val isImage: Boolean,
        val isDirectory: Boolean,
        val modifiedAt: Long?,
        val sizeBytes: Long?,
        val order: Long
    )

    private data class CrawlNode(
        val url: String,
        val inheritedPoster: String?,
        val inheritedModifiedAt: Long?,
        val collectionRoot: String?,
        val seasonHint: Int?
    )

    private data class FtpVideo(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val sizeBytes: Long?,
        val season: Int?,
        val episode: Int?,
        val dualAudio: Boolean,
        val resolution: Int,
        val bitrateMbps: Double?,
        val order: Long
    )

    private data class FtpGroup(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val videos: List<FtpVideo>,
        val kind: ContentKind,
        val isSeasonCard: Boolean = false,
        val seasonNumber: Int? = null
    ) {
        /*
         * Movie categories NEVER turn multiple files in one folder into
         * CloudStream episodes. TV Show is the episode-based category.
         * Anime remains episode-based only when multiple real files exist.
         */
        val isSeries: Boolean
            get() =
                when (kind) {
                    ContentKind.SERIES ->
                        videos.size > 1 ||
                            videos.any {
                                it.season != null ||
                                    it.episode != null
                            }

                    ContentKind.ANIME ->
                        videos.size > 1 ||
                            videos.any {
                                it.season != null ||
                                    it.episode != null
                            }

                    ContentKind.MOVIE ->
                        false
                }

        val hasDualAudio: Boolean
            get() =
                videos.any {
                    it.dualAudio
                }

        val maxResolution: Int
            get() =
                videos.maxOfOrNull {
                    it.resolution
                } ?: 0

        val maxSizeBytes: Long
            get() =
                videos.maxOfOrNull {
                    it.sizeBytes ?: 0L
                } ?: 0L
    }

    private data class CacheEntry(
        val createdAt: Long,
        val complete: Boolean,
        val groups: List<FtpGroup>
    )

    private data class SearchMatch(
        val group: FtpGroup,
        val score: Int
    )

    private class GroupBuilder(
        val title: String,
        val url: String,
        val kind: ContentKind
    ) {
        var posterUrl: String? = null
        var modifiedAt: Long = 0L
        val videos = mutableListOf<FtpVideo>()
    }

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    private val cache =
        ConcurrentHashMap<String, CacheEntry>()

    private val scanJobs =
        ConcurrentHashMap<String, Deferred<List<FtpGroup>>>()

    private val searchRoots by lazy {
        listOf(
            ROOT_ENGLISH,
            ROOT_HINDI,
            ROOT_KOLKATA,
            ROOT_SOUTH,
            ROOT_TV_1,
            ROOT_TV_2,
            ROOT_ANIME
        ).distinct()
    }

    /*
     * ---------------------------------------------------------------
     * HOMEPAGE
     * ---------------------------------------------------------------
     */


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (
            request.data.startsWith(
                TV_SHOW_PREFIX
            )
        ) {
            return getTvShowHomePage(
                page,
                request
            )
        }

        val root =
            normalizeDirectoryUrl(
                request.data
            )

        /*
         * ------------------------------------------------------------
         * FIRST PAGE
         * ------------------------------------------------------------
         */
        if (page == 1) {

            val cached =
                validCache(root)

            val firstGroups =
                cached
                    ?.groups
                    ?.take(MOVIE_HOME_SIZE)
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: scanLatestGroups(
                        root,
                        MOVIE_HOME_SIZE * 2
                    ).take(MOVIE_HOME_SIZE)

            if (
                firstGroups.isNotEmpty()
            ) {
                updatePartialCache(
                    root,
                    firstGroups
                )
            }

            /*
             * The full index starts AFTER the initial card batch has
             * been prepared. The first page never waits for it.
             */
            prewarm(root)

            return newHomePageResponse(
                request,
                firstGroups.map {
                    toSearchResponse(it)
                },
                true
            )
        }

        /*
         * ------------------------------------------------------------
         * SCROLL / LAZY LOAD
         * ------------------------------------------------------------
         *
         * Never wait indefinitely for the full recursive index.
         * A short wait gives the background scanner a chance to publish
         * the next batch. If it is not ready, return whatever has been
         * discovered and keep hasNext=true so CloudStream can continue
         * requesting pages as the user scrolls.
         */
        val offset =
            (page - 1) *
                MOVIE_HOME_SIZE

        prewarm(root)

        waitForPartialIndex(
            root = root,
            requiredCount =
                offset + 1,
            maxWaitMs = 850L
        )

        var current =
            validCache(root)

        if (
            current != null &&
            current.groups.size <= offset &&
            !current.complete
        ) {

            /*
             * A bounded second probe is preferable to blocking on the
             * entire library. It is triggered only when scrolling has
             * reached a not-yet-discovered range.
             */
            val probed =
                scanLatestGroups(
                    root,
                    offset + MOVIE_HOME_SIZE
                )

            if (
                probed.isNotEmpty()
            ) {
                updatePartialCache(
                    root,
                    probed
                )

                current =
                    validCache(root)
            }
        }

        current =
            current ?: validCache(root)

        if (
            current == null ||
            current.groups.isEmpty()
        ) {

            /*
             * Keep pagination alive while the background crawl runs.
             */
            return newHomePageResponse(
                request,
                emptyList(),
                true
            )
        }

        if (
            offset >=
            current.groups.size
        ) {

            return newHomePageResponse(
                request,
                emptyList(),
                !current.complete
            )
        }

        val pageItems =
            current.groups
                .drop(offset)
                .take(
                    MOVIE_HOME_SIZE
                )

        return newHomePageResponse(
            request,
            pageItems.map {
                toSearchResponse(it)
            },
            !(
                current.complete &&
                    offset +
                    MOVIE_HOME_SIZE >=
                    current.groups.size
            )
        )
    }


    private suspend fun getTvShowHomePage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val roots =
            decodeTvShowRoots(
                request.data
            ) ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        if (
            roots.size < 2
        ) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        /*
         * FIRST PAGE:
         * 3 latest Season cards from source A + 3 latest Season cards
         * from source B.
         */
        if (page == 1) {

            val (first, second) =
                coroutineScope {

                    val a =
                        async {
                            getInitialGroups(
                                roots[0],
                                TV_SOURCE_BATCH * 10
                            )
                        }

                    val b =
                        async {
                            getInitialGroups(
                                roots[1],
                                TV_SOURCE_BATCH * 10
                            )
                        }

                    a.await() to b.await()
                }

            prewarm(roots[0])
            prewarm(roots[1])

            val mixed =
                mixThreeAndThree(
                    latestSeasonPerShow(first),
                    latestSeasonPerShow(second)
                )
                    .take(
                        TV_HOME_SIZE
                    )

            return newHomePageResponse(
                request,
                mixed.map {
                    toSearchResponse(it)
                },
                true
            )
        }

        val offset =
            (page - 1) *
                TV_HOME_SIZE

        prewarm(roots[0])
        prewarm(roots[1])

        /*
         * Give both background scanners a short opportunity to publish
         * more Season cards, but do not wait for full indexing.
         */
        coroutineScope {

            val a =
                async {
                    waitForPartialIndex(
                        roots[0],
                        1,
                        850L
                    )
                }

            val b =
                async {
                    waitForPartialIndex(
                        roots[1],
                        1,
                        850L
                    )
                }

            a.await()
            b.await()
        }

        var first =
            validCache(
                roots[0]
            )

        var second =
            validCache(
                roots[1]
            )

        var mixed =
            mixThreeAndThree(
                latestSeasonPerShow(
                    first?.groups.orEmpty()
                ),
                latestSeasonPerShow(
                    second?.groups.orEmpty()
                )
            )

        if (
            mixed.size <= offset
        ) {

            /*
             * Trigger bounded quick probes only when the current scroll
             * position has not yet been discovered.
             */
            val quick =
                coroutineScope {

                    val a =
                        async {
                            scanLatestGroups(
                                roots[0],
                                (offset + TV_HOME_SIZE)
                            )
                        }

                    val b =
                        async {
                            scanLatestGroups(
                                roots[1],
                                (offset + TV_HOME_SIZE)
                            )
                        }

                    a.await() to b.await()
                }

            if (
                quick.first.isNotEmpty()
            ) {
                updatePartialCache(
                    roots[0],
                    quick.first
                )
            }

            if (
                quick.second.isNotEmpty()
            ) {
                updatePartialCache(
                    roots[1],
                    quick.second
                )
            }

            first =
                validCache(
                    roots[0]
                )

            second =
                validCache(
                    roots[1]
                )

            mixed =
                mixThreeAndThree(
                    latestSeasonPerShow(
                        first?.groups.orEmpty()
                    ),
                    latestSeasonPerShow(
                        second?.groups.orEmpty()
                    )
                )
        }

        if (
            mixed.size <= offset
        ) {

            val complete =
                (
                    first?.complete == true &&
                        second?.complete == true
                    )

            return newHomePageResponse(
                request,
                emptyList(),
                !complete
            )
        }

        val pageItems =
            mixed
                .drop(offset)
                .take(
                    TV_HOME_SIZE
                )

        val complete =
            first?.complete == true &&
                second?.complete == true

        return newHomePageResponse(
            request,
            pageItems.map {
                toSearchResponse(it)
            },
            !(
                complete &&
                    offset +
                    TV_HOME_SIZE >=
                    mixed.size
            )
        )
    }

    private suspend fun waitForPartialIndex(
        root: String,
        requiredCount: Int,
        maxWaitMs: Long
    ) {

        val start =
            System.currentTimeMillis()

        while (
            System.currentTimeMillis() -
                start <
            maxWaitMs
        ) {

            val cached =
                validCache(root)

            if (
                cached != null &&
                (
                    cached.complete ||
                        cached.groups.size >=
                        requiredCount
                    )
            ) {
                return
            }

            kotlinx.coroutines.delay(
                60L
            )
        }
    }

    private fun latestSeasonPerShow(
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        /*
         * TV homepage rule:
         * - if a show has Season folders, show ONLY its newest Season card
         * - older Seasons remain searchable
         * - if no Season folder exists, keep its direct-episode show card
         */
        val seasonCards =
            groups.filter {
                it.kind == ContentKind.SERIES &&
                    it.isSeasonCard
            }

        val latestByShow =
            LinkedHashMap<String, FtpGroup>()

        seasonCards.forEach { card ->

            val showRoot =
                normalizeDirectoryUrl(
                    parentDirectory(card.url) ?: card.url
                )

            val existing =
                latestByShow[showRoot]

            if (
                existing == null ||
                card.modifiedAt > existing.modifiedAt ||
                (
                    card.modifiedAt == existing.modifiedAt &&
                    (card.seasonNumber ?: 0) >
                        (existing.seasonNumber ?: 0)
                )
            ) {
                latestByShow[showRoot] = card
            }
        }

        val seasonShowRoots =
            latestByShow.keys.map {
                normalizeDirectoryUrl(it)
            }.toSet()

        val fallback =
            groups.filter { group ->

                if (
                    group.kind != ContentKind.SERIES ||
                    group.isSeasonCard
                ) {
                    true
                } else {
                    normalizeDirectoryUrl(group.url) !in
                        seasonShowRoots
                }
            }

        return (latestByShow.values + fallback)
            .distinctBy {
                it.url.lowercase(Locale.getDefault())
            }
            .sortedWith(groupComparator())
    }

    private suspend fun getInitialGroups(
        root: String,
        limit: Int
    ): List<FtpGroup> {

        val cached =
            validCache(root)

        if (
            cached?.groups?.isNotEmpty() == true
        ) {
            return cached.groups.take(limit)
        }

        /*
         * Collect a few extra candidates because duplicate filtering or
         * latest-season collapsing may remove some of them.
         */
        val probeLimit =
            maxOf(limit, limit * 2)

        val initial =
            scanLatestGroups(
                root,
                probeLimit
            )

        if (
            initial.isNotEmpty()
        ) {
            updatePartialCache(
                root,
                initial
            )
        }

        return initial.take(limit)
    }

    private fun mixThreeAndThree(
        first: List<FtpGroup>,
        second: List<FtpGroup>
    ): List<FtpGroup> {

        val result =
            mutableListOf<FtpGroup>()

        var a = 0
        var b = 0

        while (
            a < first.size ||
            b < second.size
        ) {

            repeat(
                TV_SOURCE_BATCH
            ) {
                if (
                    a < first.size
                ) {
                    result.add(
                        first[a++]
                    )
                }
            }

            repeat(
                TV_SOURCE_BATCH
            ) {
                if (
                    b < second.size
                ) {
                    result.add(
                        second[b++]
                    )
                }
            }
        }

        return result
    }

    /*
     * ---------------------------------------------------------------
     * SEARCH
     * ---------------------------------------------------------------
     */

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val normalizedQuery =
            normalizeSearchText(query)

        if (
            normalizedQuery.isBlank()
        ) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        /*
         * PRIMARY ENGINE:
         *
         * DhakaFlix uses h5ai. Its server-side search can recursively walk
         * the real filesystem without us downloading every directory page
         * over HTTP. This is dramatically faster for deep searches such as
         * an Aquaman file buried several folders below the root.
         */
        val nativeResults =
            coroutineScope {

                searchRoots.map { root ->
                    async {
                        searchNativeH5ai(
                            root = root,
                            query = normalizedQuery
                        )
                    }
                }.awaitAll().flatten()
            }

        /*
         * Do not launch a full-library crawl on every search.
         *
         * If the server-side h5ai search is unavailable, use whatever index
         * is already present in memory and return immediately. A background
         * homepage prewarm will continue to build the index for later use.
         */
        val results =
            if (
                nativeResults.isNotEmpty()
            ) {
                nativeResults
            } else {
                searchFromAvailableCache(
                    normalizedQuery
                )
            }

        val sorted =
            results
                .sortedWith(
                    compareByDescending<NativeSearchResult> {
                        it.score
                    }
                        .thenByDescending {
                            it.modifiedAt
                        }
                        .thenBy {
                            it.title.lowercase(
                                Locale.getDefault()
                            )
                        }
                )
                .distinctBy {
                    it.url.lowercase(
                        Locale.getDefault()
                    )
                }

        val offset =
            (page - 1) *
                SEARCH_PAGE_SIZE

        val pageItems =
            sorted
                .drop(offset)
                .take(
                    SEARCH_PAGE_SIZE
                )
                .map {
                    it.response
                }

        return newSearchResponseList(
            pageItems,
            offset +
                SEARCH_PAGE_SIZE <
                sorted.size
        )
    }

    private data class NativeSearchResult(
        val root: String,
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val score: Int,
        val response: SearchResponse
    )

    private data class H5aiSearchHit(
        val href: String,
        val time: Long?,
        val size: Long?,
        val isDirectory: Boolean
    )

    private val jsonMapper =
        ObjectMapper()


    private suspend fun searchNativeH5ai(
        root: String,
        query: String
    ): List<NativeSearchResult> {

        var hits =
            nativeH5aiSearchHits(
                root,
                query
            )

        /*
         * One bounded fallback keeps multi-word queries useful when a
         * server-side matcher cannot find the entire phrase in a single
         * filename/folder.
         */
        if (
            hits.isEmpty()
        ) {

            val fallback =
                buildSearchFallbackQuery(
                    query
                )

            if (
                fallback.isNotBlank() &&
                fallback != query
            ) {

                hits =
                    nativeH5aiSearchHits(
                        root,
                        fallback
                    )
            }
        }

        if (
            hits.isEmpty()
        ) {
            return emptyList()
        }

        val kind =
            detectKind(
                root
            )

        /*
         * Keep both file and directory hits available.
         * TV/Anime can need a Season directory hit even when the query
         * itself is "Season 1" rather than an episode filename.
         */
        val videoHits =
            hits.filter {
                !it.isDirectory &&
                    isVideo(
                        it.href
                    )
            }

        val parents =
            (
                videoHits.map {
                    normalizeDirectoryUrl(
                        it.href.substringBeforeLast(
                            "/"
                        )
                    )
                } +
                hits.filter {
                    it.isDirectory
                }.map {
                    normalizeDirectoryUrl(
                        it.href
                    )
                }
            )
                .distinct()

        val parentEntries =
            coroutineScope {

                parents.map { parent ->

                    async {

                        parent to
                            safeDirectoryEntries(
                                parent,
                                QUICK_SEARCH_DIRECTORY_TIMEOUT_MS
                            )
                    }

                }.awaitAll()
                    .toMap()
            }

        /*
         * TV Show:
         * always return Season cards, never raw episode cards.
         */
        if (
            kind ==
            ContentKind.SERIES
        ) {

            return buildTvNativeSearchResults(
                hits,
                parentEntries,
                query
            )
        }

        if (
            videoHits.isEmpty()
        ) {
            return emptyList()
        }

        /*
         * Movie categories:
         * every real video is its own playable result.
         */
        if (
            kind ==
            ContentKind.MOVIE
        ) {

            return videoHits.mapNotNull { hit ->

                val parent =
                    normalizeDirectoryUrl(
                        hit.href.substringBeforeLast(
                            "/"
                        )
                    )

                val poster =
                    pickPoster(
                        parentEntries[parent]
                            ?: emptyList()
                    )

                val title =
                    getTitleFromUrl(
                        hit.href
                    )

                val score =
                    scoreSearchCandidate(
                        query,
                        title
                    )

                if (
                    score <= 0
                ) {
                    null
                } else {

                    val response =
                        newMovieSearchResponse(
                            title,
                            hit.href,
                            TvType.Movie
                        ) {

                            posterUrl =
                                poster
                        }

                    NativeSearchResult(
                        root =
                            root,
                        title =
                            title,
                        url =
                            hit.href,
                        posterUrl =
                            poster,
                        modifiedAt =
                            hit.time ?: 0L,
                        score =
                            score +
                                if (
                                    detectDualAudio(
                                        title
                                    )
                                ) {
                                    150
                                } else {
                                    0
                                },
                        response =
                            response
                    )
                }
            }
        }

        /*
         * Anime:
         * explicit Season paths are season cards.
         * Plain single videos remain individual playable items.
         */
        return buildAnimeNativeSearchResults(
            videoHits,
            parentEntries,
            query
        )
    }

    private fun buildSearchFallbackQuery(
        query: String
    ): String {

        val metadataWords =
            setOf(
                "movie",
                "movies",
                "film",
                "films",
                "series",
                "season",
                "episode",
                "ep",
                "hd",
                "hdtc",
                "web",
                "webdl",
                "webrip",
                "bluray",
                "dual",
                "audio"
            )

        val tokens =
            normalizeSearchText(
                query
            )
                .split(" ")
                .filter {
                    it.length >= 2 &&
                        it !in metadataWords
                }

        if (
            tokens.isEmpty()
        ) {
            return ""
        }

        return tokens
            .take(3)
            .joinToString(" ")
    }

    private suspend fun nativeH5aiSearchHits(
        rootRaw: String,
        query: String
    ): List<H5aiSearchHit> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val uri =
            try {
                URI(
                    root
                )
            } catch (_: Exception) {
                return emptyList()
            }

        val scheme =
            uri.scheme
                ?: return emptyList()

        val authority =
            uri.rawAuthority
                ?: return emptyList()

        val searchHref =
            uri.rawPath
                ?.ifBlank {
                    "/"
                }
                ?: "/"

        /*
         * h5ai's public API lives beneath /_h5ai/public/index.php.
         */
        val endpoint =
            "$scheme://$authority/_h5ai/public/index.php" +
                "?action=get" +
                "&search=1" +
                "&search.href=" +
                encodeQueryValue(
                    searchHref
                ) +
                "&search.pattern=" +
                encodeQueryValue(
                    query
                ) +
                "&search.ignorecase=1"

        val response =
            try {

                kotlinx.coroutines.withTimeoutOrNull(
                    QUICK_SEARCH_NATIVE_TIMEOUT_MS
                ) {
                    app.get(
                        endpoint
                    )
                }

            } catch (_: Exception) {
                null
            }
                ?: return emptyList()

        return parseH5aiSearchJson(
            response.text
        )
    }

    private fun parseH5aiSearchJson(
        text: String
    ): List<H5aiSearchHit> {

        return try {

            val json =
                jsonMapper.readTree(
                    text
                )

            val array =
                when {

                    json.isArray ->
                        json

                    json.has("search") &&
                        json["search"].isArray ->
                        json["search"]

                    else ->
                        return emptyList()
                }

            array.mapNotNull { item ->

                val href =
                    item["href"]
                        ?.asText()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return@mapNotNull null

                H5aiSearchHit(
                    href =
                        href,
                    time =
                        item["time"]
                            ?.takeIf {
                                !it.isNull
                            }
                            ?.asLong(),
                    size =
                        item["size"]
                            ?.takeIf {
                                !it.isNull
                            }
                            ?.asLong(),
                    isDirectory =
                        href.endsWith(
                            "/"
                        )
                )
            }

        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeQueryValue(
        value: String
    ): String =
        URLEncoder
            .encode(
                value,
                "UTF-8"
            )
            .replace(
                "+",
                "%20"
            )


    private suspend fun buildTvNativeSearchResults(
        videoHits: List<H5aiSearchHit>,
        parentEntries: Map<String, List<FtpEntry>>,
        query: String
    ): List<NativeSearchResult> {

        /*
         * TV search is SEASON-level, never raw-episode-level.
         *
         * Matching episodes are grouped by their actual Season folder.
         * The search result URL is that Season folder, so load() can
         * expose the episodes for that season.
         */
        val seasonRootsFromVideos =
            videoHits
                .mapNotNull {
                    findSeasonFolderRoot(
                        it.href
                    )
                }

        /*
         * If the query itself matches a Season folder (for example
         * "Season 1"), h5ai may return the directory but not every
         * episode filename. Keep those directory hits too.
         */
        val seasonRootsFromDirectories =
            videoHits
                .filter {
                    it.isDirectory &&
                        isSeasonDirectory(
                            getFolderTitle(
                                it.href
                            )
                        )
                }
                .map {
                    normalizeDirectoryUrl(
                        it.href
                    )
                }

        val seasonRoots =
            (
                seasonRootsFromVideos +
                    seasonRootsFromDirectories
                )
                .distinct()

        /*
         * Also search the complete hit list for Season directories.
         */
        val allSeasonDirectories =
            seasonRoots +
                videoHits
                    .filter {
                        it.isDirectory
                    }
                    .mapNotNull {
                        val decoded =
                            getFolderTitle(
                                it.href
                            )

                        if (
                            isSeasonDirectory(
                                decoded
                            )
                        ) {
                            normalizeDirectoryUrl(
                                it.href
                            )
                        } else {
                            null
                        }
                    }

        return allSeasonDirectories
            .distinct()
            .mapNotNull { seasonRoot ->

                val seasonEntries =
                    safeDirectoryEntries(
                        seasonRoot,
                        QUICK_SEARCH_DIRECTORY_TIMEOUT_MS
                    )

                val seasonNumber =
                    extractSeasonNumber(
                        getFolderTitle(
                            seasonRoot
                        )
                    )

                val showRoot =
                    parentDirectory(
                        seasonRoot
                    ) ?: return@mapNotNull null

                val showTitle =
                    getFolderTitle(
                        showRoot
                    )

                val title =
                    if (
                        seasonNumber != null
                    ) {
                        "$showTitle Season $seasonNumber"
                    } else {
                        "$showTitle Season"
                    }

                val poster =
                    pickPoster(
                        seasonEntries
                    ) ?: findNearestPoster(
                        showRoot
                    )

                val matching =
                    videoHits.filter {
                        findSeasonFolderRoot(
                            it.href
                        )?.equals(
                            seasonRoot,
                            true
                        ) == true
                    }

                val score =
                    maxOf(
                        scoreSearchCandidate(
                            query,
                            title
                        ),
                        scoreSearchCandidate(
                            query,
                            showTitle
                        ),
                        matching.maxOfOrNull {
                            scoreSearchCandidate(
                                query,
                                getTitleFromUrl(
                                    it.href
                                )
                            )
                        } ?: 0
                    )

                if (
                    score <= 0
                ) {
                    return@mapNotNull null
                }

                val response =
                    newTvSeriesSearchResponse(
                        title,
                        seasonRoot,
                        TvType.TvSeries
                    ) {
                        posterUrl =
                            poster
                    }

                val modifiedAt =
                    findFolderModifiedTime(
                        safeDirectoryEntries(
                            showRoot,
                            QUICK_SEARCH_DIRECTORY_TIMEOUT_MS
                        ),
                        seasonRoot
                    )
                        ?: matching.maxOfOrNull {
                            it.time ?: 0L
                        }
                        ?: 0L

                NativeSearchResult(
                    root =
                        seasonRoot,
                    title =
                        title,
                    url =
                        seasonRoot,
                    posterUrl =
                        poster,
                    modifiedAt =
                        modifiedAt,
                    score =
                        score +
                            if (
                                matching.any {
                                    detectDualAudio(
                                        getTitleFromUrl(
                                            it.href
                                        )
                                    )
                                }
                            ) {
                                60
                            } else {
                                0
                            },
                    response =
                        response
                )
            }
    }

    private suspend fun findNearestPosterFromMap(
        root: String,
        parentEntries: Map<String, List<FtpEntry>>
    ): String? {

        var current =
            normalizeDirectoryUrl(
                root
            )

        repeat(32) {

            parentEntries[current]
                ?.let {
                    pickPoster(it)
                }
                ?.let {
                    return it
                }

            val entries =
                safeDirectoryEntries(
                    current,
                    QUICK_SEARCH_DIRECTORY_TIMEOUT_MS
                )

            pickPoster(
                entries
            )?.let {
                return it
            }

            val parent =
                parentDirectory(
                    current
                )
                    ?: return null

            if (
                parent.equals(
                    current,
                    true
                )
            ) {
                return null
            }

            current =
                parent
        }

        return null
    }


    private suspend fun buildAnimeNativeSearchResults(
        videoHits: List<H5aiSearchHit>,
        parentEntries: Map<String, List<FtpEntry>>,
        query: String
    ): List<NativeSearchResult> {

        val result =
            mutableListOf<NativeSearchResult>()

        val seasonRoots =
            videoHits
                .mapNotNull {
                    findSeasonFolderRoot(
                        it.href
                    )
                }
                .distinct()

        seasonRoots.forEach { seasonRoot ->

            val seasonEntries =
                parentEntries[
                    normalizeDirectoryUrl(
                        seasonRoot
                    )
                ].orEmpty()

            val seasonNumber =
                extractSeasonNumber(
                    getFolderTitle(
                        seasonRoot
                    )
                )

            val showRoot =
                parentDirectory(
                    seasonRoot
                ) ?: seasonRoot

            val showTitle =
                getFolderTitle(
                    showRoot
                )

            val title =
                if (
                    seasonNumber != null
                ) {
                    "$showTitle Season $seasonNumber"
                } else {
                    showTitle
                }

            val poster =
                pickPoster(
                    seasonEntries
                ) ?: findNearestPosterFromMap(
                    showRoot,
                    parentEntries
                )

            val matching =
                videoHits.filter {
                    findSeasonFolderRoot(
                        it.href
                    )?.equals(
                        seasonRoot,
                        true
                    ) == true
                }

            val score =
                maxOf(
                    scoreSearchCandidate(
                        query,
                        title
                    ),
                    matching.maxOfOrNull {
                        scoreSearchCandidate(
                            query,
                            getTitleFromUrl(
                                it.href
                            )
                        )
                    } ?: 0
                )

            if (
                score > 0
            ) {

                val response =
                    newTvSeriesSearchResponse(
                        title,
                        seasonRoot,
                        TvType.Anime
                    ) {
                        posterUrl =
                            poster
                    }

                result.add(
                    NativeSearchResult(
                        root =
                            seasonRoot,
                        title =
                            title,
                        url =
                            seasonRoot,
                        posterUrl =
                            poster,
                        modifiedAt =
                            matching.maxOfOrNull {
                                it.time ?: 0L
                            } ?: 0L,
                        score =
                            score,
                        response =
                            response
                    )
                )
            }
        }

        videoHits
            .filter {
                findSeasonFolderRoot(
                    it.href
                ) == null &&
                    !it.isDirectory
            }
            .forEach { hit ->

                val parent =
                    normalizeDirectoryUrl(
                        hit.href.substringBeforeLast(
                            "/"
                        )
                    )

                val title =
                    getTitleFromUrl(
                        hit.href
                    )

                val score =
                    scoreSearchCandidate(
                        query,
                        title
                    )

                if (
                    score > 0
                ) {

                    val response =
                        newMovieSearchResponse(
                            title,
                            hit.href,
                            TvType.Anime
                        ) {
                            posterUrl =
                                pickPoster(
                                    parentEntries[
                                        parent
                                    ].orEmpty()
                                )
                        }

                    result.add(
                        NativeSearchResult(
                            root =
                                parent,
                            title =
                                title,
                            url =
                                hit.href,
                            posterUrl =
                                null,
                            modifiedAt =
                                hit.time ?: 0L,
                            score =
                                score,
                            response =
                                response
                        )
                    )
                }
            }

        return result
    }

    private fun findSeasonFolderRoot(
        videoUrl: String
    ): String? {

        var current =
            normalizeDirectoryUrl(
                videoUrl.substringBeforeLast("/")
            )

        var steps = 0

        while (steps++ < 64) {

            if (
                isSeasonDirectory(
                    getFolderTitle(current)
                )
            ) {
                return current
            }

            val parent =
                parentDirectory(current)
                    ?: return null

            if (
                parent.equals(
                    current,
                    true
                )
            ) {
                return null
            }

            current = parent
        }

        return null
    }


    private fun findSeasonSeriesRoot(
        videoUrl: String
    ): String? {

        val seasonRoot =
            findSeasonFolderRoot(
                videoUrl
            ) ?: return null

        return parentDirectory(
            seasonRoot
        )
    }

    private fun searchFromAvailableCache(
        query: String
    ): List<NativeSearchResult> {

        return cache.values
            .flatMap {
                it.groups
            }
            .mapNotNull { group ->

                val score =
                    scoreGroup(
                        query,
                        group
                    )

                if (
                    score <= 0
                ) {
                    null
                } else {

                    NativeSearchResult(
                        root =
                            group.url,
                        title =
                            group.title,
                        url =
                            group.url,
                        posterUrl =
                            group.posterUrl,
                        modifiedAt =
                            group.modifiedAt,
                        score =
                            score,
                        response =
                            toSearchResponse(
                                group
                            )
                    )
                }
            }
    }

    private fun scoreSearchCandidate(
        query: String,
        candidate: String
    ): Int {

        val q =
            normalizeSearchText(
                query
            )

        val c =
            normalizeSearchText(
                candidate
            )

        if (
            q.isBlank() ||
            c.isBlank()
        ) {
            return 0
        }

        if (
            c == q
        ) {
            return 7000
        }

        if (
            c.startsWith(
                q
            )
        ) {
            return 5800
        }

        if (
            c.contains(
                q
            )
        ) {
            return 5000
        }

        val qTokens =
            q.split(
                " "
            ).filter {
                it.isNotBlank()
            }

        val cTokens =
            c.split(
                " "
            ).filter {
                it.isNotBlank()
            }

        if (
            qTokens.isEmpty()
        ) {
            return 0
        }

        var matched =
            0

        var total =
            0

        qTokens.forEach { token ->

            val best =
                cTokens.maxOfOrNull {
                    tokenSimilarityScore(
                        token,
                        it
                    )
                } ?: 0

            if (
                best > 0
            ) {
                matched++
                total +=
                    best
            }
        }

        /*
         * For 3+ query words, at least two meaningful terms must match.
         */
        if (
            qTokens.size >= 3 &&
            matched < 2
        ) {
            return 0
        }

        if (
            matched == 0
        ) {
            return 0
        }

        val coverage =
            matched.toDouble() /
                qTokens.size.toDouble()

        return (
            total *
                (0.60 + coverage * 0.40)
            ).toInt() +
            if (
                matched == qTokens.size
            ) {
                1000
            } else {
                0
            }
    }

    private fun tokenSimilarityScore(
        query: String,
        candidate: String
    ): Int {

        if (
            query == candidate
        ) {
            return 1200
        }

        if (
            candidate.startsWith(
                query
            )
        ) {
            return 1000
        }

        if (
            candidate.contains(
                query
            )
        ) {
            return 900
        }

        /*
         * User explicitly wants 2/3/4-character discovery.
         */
        if (
            query.length in 2..4
        ) {
            return if (
                candidate.contains(
                    query
                )
            ) {
                850
            } else {
                0
            }
        }

        /*
         * Typo tolerance for longer words.
         */
        if (
            query.length >= 5 &&
            candidate.length >= 5
        ) {

            val distance =
                levenshtein(
                    query,
                    candidate
                )

            val allowed =
                maxOf(
                    2,
                    query.length / 4
                )

            if (
                distance <= allowed
            ) {
                return 750 -
                    minOf(
                        450,
                        distance * 100
                    )
            }
        }

        return 0
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
     * ---------------------------------------------------------------
     * LOAD
     * ---------------------------------------------------------------
     */

    override suspend fun load(
        url: String
    ): LoadResponse {

        if (
            isVideo(url)
        ) {
            return loadDirectVideo(
                url
            )
        }

        val folder =
            normalizeDirectoryUrl(url)

        val directKind =
            detectKind(folder)

        if (
            directKind == ContentKind.SERIES &&
            !isSeasonDirectory(
                getFolderTitle(folder)
            )
        ) {

            val latestSeason =
                findLatestSeasonFolderDeep(folder)

            if (latestSeason != null) {
                return loadSeasonFolder(
                    latestSeason,
                    ContentKind.SERIES
                )
            }
        }

        val group =
            buildGroupFromFolder(
                folder
            )

        if (
            group == null
        ) {

            return newMovieLoadResponse(
                getFolderTitle(url),
                url,
                TvType.Movie,
                url
            )
        }

        if (
            group.isSeries
        ) {

            val seasons =
                group.videos
                    .map {
                        it.season ?: 1
                    }
                    .distinct()
                    .sorted()

            val episodes =
                group.videos
                    .sortedWith(
                        compareBy<FtpVideo> {
                            it.season ?: 1
                        }
                            .thenBy {
                                it.episode
                                    ?: Int.MAX_VALUE
                            }
                            .thenBy {
                                it.order
                            }
                    )
                    .mapIndexed {
                            index,
                            video ->

                        val season =
                            video.season ?: 1

                        val episode =
                            video.episode
                                ?: index + 1

                        newEpisode(
                            video.url
                        ) {

                            name =
                                video.title

                            this.season =
                                season

                            this.episode =
                                episode

                            posterUrl =
                                group.posterUrl
                                    ?: video.posterUrl

                            /*
                             * This is what CloudStream renders in the
                             * episode metadata/description area.
                             */
                            description =
                                if (
                                    video.dualAudio
                                ) {
                                    "Season $season • Dual Audio"
                                } else {
                                    "Season $season"
                                }

                            date =
                                video.modifiedAt
                        }
                    }

            return newTvSeriesLoadResponse(
                group.title,
                group.url,
                if (
                    group.kind ==
                    ContentKind.ANIME
                ) {
                    TvType.Anime
                } else {
                    TvType.TvSeries
                },
                episodes
            ) {

                posterUrl =
                    group.posterUrl

                /*
                 * Series-level plot:
                 *
                 * Season 1
                 * Season 2
                 * Season 3
                 *
                 * Dual Audio
                 */
                plot =
                    buildSeriesPlot(
                        seasons,
                        group.hasDualAudio
                    )
            }
        }

        val video =
            group.videos.firstOrNull()

        if (
            video == null
        ) {

            return newMovieLoadResponse(
                group.title,
                group.url,
                if (
                    group.kind ==
                    ContentKind.ANIME
                ) {
                    TvType.Anime
                } else {
                    TvType.Movie
                },
                group.url
            ) {

                posterUrl =
                    group.posterUrl
            }
        }

        return newMovieLoadResponse(
            group.title,
            group.url,
            if (
                group.kind ==
                ContentKind.ANIME
            ) {
                TvType.Anime
            } else {
                TvType.Movie
            },
            video.url
        ) {

            posterUrl =
                group.posterUrl

            plot =
                if (
                    video.dualAudio
                ) {
                    "Dual Audio"
                } else {
                    null
                }
        }
    }

    private suspend fun loadDirectVideo(
        url: String
    ): LoadResponse {

        val cleanUrl =
            url.substringBefore("?")

        val parent =
            normalizeDirectoryUrl(
                cleanUrl.substringBeforeLast(
                    "/"
                )
            )

        val poster =
            findNearestPoster(
                parent
            )

        val title =
            getTitleFromUrl(
                cleanUrl
            )

        val dualAudio =
            detectDualAudio(title) ||
                detectDualAudio(parent)

        return newMovieLoadResponse(
            title,
            cleanUrl,
            TvType.Movie,
            cleanUrl
        ) {

            posterUrl =
                poster

            plot =
                if (
                    dualAudio
                ) {
                    "Dual Audio"
                } else {
                    null
                }
        }
    }

    /*
     * ---------------------------------------------------------------
     * PLAYBACK
     * ---------------------------------------------------------------
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (
            data.isBlank()
        ) {
            return false
        }

        val cleanUrl =
            data.substringBefore("?")

        val lowered =
            cleanUrl.lowercase(
                Locale.getDefault()
            )

        val type =
            if (
                lowered.endsWith(
                    ".m3u8"
                )
            ) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanUrl,
                type = type
            ) {

                referer =
                    refererFor(
                        cleanUrl
                    )

                quality =
                    detectQuality(
                        lowered
                    )
            }
        )

        return true
    }

    /*
     * ---------------------------------------------------------------
     * SEARCH RESPONSE
     * ---------------------------------------------------------------
     */

    private fun toSearchResponse(
        group: FtpGroup
    ): SearchResponse {

        if (
            group.isSeries
        ) {

            return newTvSeriesSearchResponse(
                group.title,
                group.url,
                if (
                    group.kind ==
                    ContentKind.ANIME
                ) {
                    TvType.Anime
                } else {
                    TvType.TvSeries
                }
            ) {

                posterUrl =
                    group.posterUrl
            }
        }

        return newMovieSearchResponse(
            group.title,
            group.url,
            if (
                group.kind ==
                ContentKind.ANIME
            ) {
                TvType.Anime
            } else {
                TvType.Movie
            }
        ) {

            posterUrl =
                group.posterUrl
        }
    }

    /*
     * ---------------------------------------------------------------
     * INDEX / CACHE
     * ---------------------------------------------------------------
     */

    private fun validCache(
        rootRaw: String
    ): CacheEntry? {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val cacheEntry =
            cache[root]
                ?: return null

        return if (
            System.currentTimeMillis() -
                cacheEntry.createdAt <=
            TimeUnit.MINUTES.toMillis(
                CACHE_MINUTES
            )
        ) {
            cacheEntry
        } else {
            null
        }
    }

    private fun updatePartialCache(
        rootRaw: String,
        groups: List<FtpGroup>
    ) {

        if (
            groups.isEmpty()
        ) {
            return
        }

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val previous =
            cache[root]
                ?.groups
                ?: emptyList()

        cache[root] =
            CacheEntry(
                createdAt =
                    System.currentTimeMillis(),
                complete = false,
                groups =
                    deduplicateGroups(
                        previous + groups
                    )
            )
    }

    private fun prewarm(
        rootRaw: String
    ) {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        if (
            validCache(root)
                ?.complete == true
        ) {
            return
        }

        scanJobs.computeIfAbsent(
            root
        ) {

            scope.async {

                val result =
                    try {
                        scanAllGroups(
                            root
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }

                cache[root] =
                    CacheEntry(
                        createdAt =
                            System.currentTimeMillis(),
                        complete = true,
                        groups =
                            deduplicateGroups(
                                result
                            )
                    )

                cache[root]?.groups
                    ?: emptyList()
            }
        }
    }

    private suspend fun awaitCompleteIndex(
        rootRaw: String
    ): CacheEntry {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        validCache(root)
            ?.takeIf {
                it.complete
            }
            ?.let {
                return it
            }

        val job =
            scanJobs.computeIfAbsent(
                root
            ) {

                scope.async {

                    val result =
                        try {
                            scanAllGroups(
                                root
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }

                    val final =
                        deduplicateGroups(
                            result
                        )

                    cache[root] =
                        CacheEntry(
                            createdAt =
                                System.currentTimeMillis(),
                            complete = true,
                            groups =
                                final
                        )

                    final
                }
            }

        val groups =
            job.await()

        val current =
            validCache(root)

        return current
            ?: CacheEntry(
                createdAt =
                    System.currentTimeMillis(),
                complete = true,
                groups =
                    deduplicateGroups(
                        groups
                    )
            )
    }

    /*
     * ---------------------------------------------------------------
     * QUICK INDEX
     * ---------------------------------------------------------------
     *
     * This is only for the first screen.
     *
     * Folder depth itself is NOT limited.
     */
    private fun directoryPriority(
        entry: FtpEntry
    ): Long {

        val name =
            decodeSafely(
                entry.name
            ).trim()

        val year =
            Regex(
                "(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)"
            )
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()

        return when {
            year != null ->
                year * 1_000_000L

            name.contains(
                "latest",
                ignoreCase = true
            ) ||
                name.contains(
                    "new",
                    ignoreCase = true
                ) ->
                900_000L

            else ->
                0L
        }
    }


    private suspend fun scanLatestGroups(
        rootRaw: String,
        limit: Int
    ): List<FtpGroup> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val kind =
            detectKind(
                root
            )

        val queue =
            PriorityQueue<CrawlNode>(
                compareByDescending<CrawlNode> {
                    it.inheritedModifiedAt ?: 0L
                }.thenBy {
                    it.seasonHint ?: Int.MAX_VALUE
                }
            )

        val visited =
            HashSet<String>()

        val builders =
            LinkedHashMap<String, GroupBuilder>()

        queue.add(
            CrawlNode(
                url = root,
                inheritedPoster = null,
                inheritedModifiedAt = null,
                collectionRoot = null,
                seasonHint = null
            )
        )

        var directoriesVisited = 0
        var order = 0L

        while (
            queue.isNotEmpty() &&
            directoriesVisited < QUICK_SCAN_DIRECTORY_LIMIT
        ) {

            val batch =
                mutableListOf<CrawlNode>()

            repeat(
                QUICK_SCAN_BATCH_SIZE
            ) {
                if (
                    queue.isNotEmpty() &&
                    directoriesVisited +
                    batch.size <
                    QUICK_SCAN_DIRECTORY_LIMIT
                ) {
                    batch.add(
                        queue.poll()
                    )
                }
            }

            if (batch.isEmpty()) {
                break
            }

            val fetched =
                coroutineScope {
                    batch.map { node ->
                        async {
                            node to
                                safeDirectoryEntries(
                                    node.url,
                                    QUICK_DIRECTORY_TIMEOUT_MS
                                )
                        }
                    }.awaitAll()
                }

            for (
                (node, entries)
                in fetched
            ) {

                directoriesVisited++

                val current =
                    normalizeDirectoryUrl(
                        node.url
                    )

                if (!visited.add(current)) {
                    continue
                }

                val directPoster =
                    pickPoster(entries)

                val inheritedPoster =
                    directPoster
                        ?: node.inheritedPoster

                val modified =
                    maxOf(
                        node.inheritedModifiedAt ?: 0L,
                        entries.maxOfOrNull {
                            it.modifiedAt ?: 0L
                        } ?: 0L
                    )

                val directories =
                    entries.filter {
                        it.isDirectory
                    }

                val currentIsSeason =
                    kind != ContentKind.MOVIE &&
                        isSeasonDirectory(
                            getFolderTitle(current)
                        )

                val collectionRoot =
                    if (currentIsSeason) {
                        current
                    } else {
                        node.collectionRoot
                    }

                /*
                 * A Season folder is itself a logical homepage/search card.
                 */
                if (currentIsSeason) {

                    val season =
                        extractSeasonNumber(
                            getFolderTitle(current)
                        ) ?: 1

                    val showRoot =
                        parentDirectory(current)
                            ?: current

                    val showTitle =
                        getFolderTitle(showRoot)

                    val builder =
                        builders.getOrPut(current) {
                            GroupBuilder(
                                title =
                                    "$showTitle Season $season",
                                url = current,
                                kind =
                                    if (
                                        kind ==
                                        ContentKind.ANIME
                                    ) {
                                        ContentKind.ANIME
                                    } else {
                                        ContentKind.SERIES
                                    }
                            )
                        }

                    builder.posterUrl =
                        builder.posterUrl
                            ?: directPoster
                            ?: node.inheritedPoster

                    /*
                     * IMPORTANT:
                     * Season card ordering uses the Season folder date,
                     * inherited from the parent directory listing.
                     */
                    builder.modifiedAt =
                        maxOf(
                            builder.modifiedAt,
                            node.inheritedModifiedAt
                                ?: modified
                        )
                }

                val videos =
                    entries.filter {
                        it.isVideo
                    }

                if (videos.isNotEmpty()) {

                    val groupRoot =
                        when {
                            kind == ContentKind.MOVIE ->
                                current

                            else ->
                                collectionRoot
                                    ?: current
                        }

                    val builder =
                        builders.getOrPut(groupRoot) {

                            val title =
                                if (
                                    kind != ContentKind.MOVIE &&
                                    isSeasonDirectory(
                                        getFolderTitle(
                                            groupRoot
                                        )
                                    )
                                ) {

                                    val showRoot =
                                        parentDirectory(
                                            groupRoot
                                        )

                                    val season =
                                        extractSeasonNumber(
                                            getFolderTitle(
                                                groupRoot
                                            )
                                        ) ?: 1

                                    "${
                                        showRoot?.let {
                                            getFolderTitle(it)
                                        } ?: "TV Show"
                                    } Season $season"

                                } else {

                                    getFolderTitle(
                                        groupRoot
                                    )
                                }

                            GroupBuilder(
                                title = title,
                                url = groupRoot,
                                kind = kind
                            )
                        }

                    if (
                        builder.posterUrl == null
                    ) {
                        builder.posterUrl =
                            directPoster
                                ?: inheritedPoster
                    }

                    videos.forEach { entry ->

                        builder.videos.add(
                            makeVideo(
                                entry = entry,
                                poster =
                                    builder.posterUrl
                                        ?: inheritedPoster,
                                seasonHint =
                                    if (
                                        kind !=
                                            ContentKind.MOVIE
                                    ) {
                                        extractSeasonNumber(
                                            getFolderTitle(
                                                current
                                            )
                                        ) ?: node.seasonHint
                                    } else {
                                        null
                                    },
                                order =
                                    order++
                            )
                        )
                    }

                    /*
                     * Movie ordering follows actual file dates.
                     * Season ordering does not follow episode dates.
                     */
                    if (
                        !(
                            kind != ContentKind.MOVIE &&
                            isSeasonDirectory(
                                getFolderTitle(groupRoot)
                            )
                        )
                    ) {

                        builder.modifiedAt =
                            maxOf(
                                builder.modifiedAt,
                                videos.maxOfOrNull {
                                    it.modifiedAt ?: 0L
                                } ?: 0L
                            )
                    }
                }

                directories
                    .sortedWith(
                        compareByDescending<FtpEntry> {
                            it.modifiedAt ?: modified
                        }.thenBy {
                            it.order
                        }
                    )
                    .forEach { child ->

                        val childIsSeason =
                            kind != ContentKind.MOVIE &&
                                isSeasonDirectory(
                                    child.name
                                )

                        queue.add(
                            CrawlNode(
                                url =
                                    normalizeDirectoryUrl(
                                        child.url
                                    ),
                                inheritedPoster =
                                    inheritedPoster,
                                inheritedModifiedAt =
                                    maxOf(
                                        child.modifiedAt ?: 0L,
                                        modified,
                                        directoryPriority(child)
                                    ),
                                collectionRoot =
                                    if (
                                        childIsSeason
                                    ) {
                                        normalizeDirectoryUrl(
                                            child.url
                                        )
                                    } else {
                                        collectionRoot
                                    },
                                seasonHint =
                                    if (
                                        childIsSeason
                                    ) {
                                        extractSeasonNumber(
                                            child.name
                                        )
                                    } else {
                                        extractSeasonNumber(
                                            child.name
                                        ) ?: extractSeasonNumber(
                                            current
                                        ) ?: node.seasonHint
                                    }
                            )
                        )
                    }

                val normalized =
                    normalizeBuilders(
                        builders.values.toList()
                    )

                if (
                    normalized.isNotEmpty()
                ) {
                    updatePartialCache(
                        root,
                        normalized
                    )
                }

                if (
                    normalized.count {
                        it.videos.isNotEmpty()
                    } >= limit
                ) {

                    return normalized
                        .sortedWith(
                            groupComparator()
                        )
                        .take(limit)
                }
            }
        }

        return normalizeBuilders(
            builders.values.toList()
        )
            .sortedWith(
                groupComparator()
            )
            .take(limit)
    }


    private suspend fun scanAllGroups(
        rootRaw: String
    ): List<FtpGroup> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val kind =
            detectKind(
                root
            )

        val queue =
            ArrayDeque<CrawlNode>()

        val visited =
            HashSet<String>()

        val builders =
            LinkedHashMap<String, GroupBuilder>()

        queue.addLast(
            CrawlNode(
                url = root,
                inheritedPoster = null,
                inheritedModifiedAt = null,
                collectionRoot = null,
                seasonHint = null
            )
        )

        var order = 0L
        var partialCounter = 0

        while (
            queue.isNotEmpty()
        ) {

            val node =
                queue.removeFirst()

            val current =
                normalizeDirectoryUrl(
                    node.url
                )

            if (!visited.add(current)) {
                continue
            }

            val entries =
                safeDirectoryEntries(
                    current
                )

            val localPoster =
                pickPoster(entries)

            val inheritedPoster =
                localPoster
                    ?: node.inheritedPoster

            val modified =
                maxOf(
                    node.inheritedModifiedAt ?: 0L,
                    entries.maxOfOrNull {
                        it.modifiedAt ?: 0L
                    } ?: 0L
                )

            val directories =
                entries.filter {
                    it.isDirectory
                }

            val currentIsSeason =
                kind != ContentKind.MOVIE &&
                    isSeasonDirectory(
                        getFolderTitle(current)
                    )

            val collectionRoot =
                if (currentIsSeason) {
                    current
                } else {
                    node.collectionRoot
                }

            /*
             * Register each Season as its own logical content group.
             */
            if (currentIsSeason) {

                val season =
                    extractSeasonNumber(
                        getFolderTitle(current)
                    ) ?: 1

                val showRoot =
                    parentDirectory(current)
                        ?: current

                val showTitle =
                    getFolderTitle(showRoot)

                val builder =
                    builders.getOrPut(current) {
                        GroupBuilder(
                            title =
                                "$showTitle Season $season",
                            url = current,
                            kind =
                                if (
                                    kind ==
                                    ContentKind.ANIME
                                ) {
                                    ContentKind.ANIME
                                } else {
                                    ContentKind.SERIES
                                }
                        )
                    }

                builder.posterUrl =
                    builder.posterUrl
                        ?: localPoster
                        ?: node.inheritedPoster

                /*
                 * Season card date = Season directory date.
                 * Episode dates are deliberately ignored here.
                 */
                builder.modifiedAt =
                    maxOf(
                        builder.modifiedAt,
                        node.inheritedModifiedAt
                            ?: modified
                    )
            }

            val videos =
                entries.filter {
                    it.isVideo
                }

            if (videos.isNotEmpty()) {

                val groupRoot =
                    when {
                        kind == ContentKind.MOVIE ->
                            current

                        else ->
                            collectionRoot
                                ?: current
                    }

                val builder =
                    builders.getOrPut(
                        groupRoot
                    ) {

                        val title =
                            if (
                                kind != ContentKind.MOVIE &&
                                isSeasonDirectory(
                                    getFolderTitle(
                                        groupRoot
                                    )
                                )
                            ) {

                                val showRoot =
                                    parentDirectory(
                                        groupRoot
                                    )

                                val season =
                                    extractSeasonNumber(
                                        getFolderTitle(
                                            groupRoot
                                        )
                                    ) ?: 1

                                "${
                                    showRoot?.let {
                                        getFolderTitle(it)
                                    } ?: "TV Show"
                                } Season $season"

                            } else {

                                getFolderTitle(
                                    groupRoot
                                )
                            }

                        GroupBuilder(
                            title = title,
                            url = groupRoot,
                            kind = kind
                        )
                    }

                if (builder.posterUrl == null) {
                    builder.posterUrl =
                        localPoster
                            ?: inheritedPoster
                }

                videos.forEach { entry ->

                    val video =
                        makeVideo(
                            entry = entry,
                            poster =
                                builder.posterUrl
                                    ?: inheritedPoster,
                            seasonHint =
                                if (
                                    kind !=
                                        ContentKind.MOVIE
                                ) {
                                    extractSeasonNumber(
                                        getFolderTitle(
                                            current
                                        )
                                    ) ?: node.seasonHint
                                } else {
                                    null
                                },
                            order = order++
                        )

                    builder.videos.add(
                        video
                    )

                    /*
                     * Only movie groups use individual file dates for
                     * homepage ordering. Season groups keep folder date.
                     */
                    if (
                        !(
                            kind != ContentKind.MOVIE &&
                            isSeasonDirectory(
                                getFolderTitle(groupRoot)
                            )
                        )
                    ) {
                        builder.modifiedAt =
                            maxOf(
                                builder.modifiedAt,
                                video.modifiedAt
                            )
                    }
                }
            }

            /*
             * Walk every child directory; no content-depth cap.
             */
            directories.forEach { child ->

                val childIsSeason =
                    kind != ContentKind.MOVIE &&
                        isSeasonDirectory(
                            child.name
                        )

                queue.addLast(
                    CrawlNode(
                        url =
                            normalizeDirectoryUrl(
                                child.url
                            ),
                        inheritedPoster =
                            inheritedPoster,
                        inheritedModifiedAt =
                            maxOf(
                                child.modifiedAt ?: 0L,
                                modified
                            ),
                        collectionRoot =
                            if (
                                childIsSeason
                            ) {
                                normalizeDirectoryUrl(
                                    child.url
                                )
                            } else {
                                collectionRoot
                            },
                        seasonHint =
                            if (
                                childIsSeason
                            ) {
                                extractSeasonNumber(
                                    child.name
                                )
                            } else {
                                extractSeasonNumber(
                                    child.name
                                ) ?: extractSeasonNumber(
                                    current
                                ) ?: node.seasonHint
                            }
                    )
                )
            }

            partialCounter++

            if (
                partialCounter >= 5
            ) {

                partialCounter = 0

                updatePartialCache(
                    root,
                    normalizeBuilders(
                        builders.values.toList()
                    )
                )
            }
        }

        return normalizeBuilders(
            builders.values.toList()
        )
            .sortedWith(
                groupComparator()
            )
    }


    private suspend fun findLatestSeasonFolderDeep(
        rootRaw: String
    ): String? {

        val root =
            normalizeDirectoryUrl(rootRaw)

        val queue =
            ArrayDeque<Pair<String, Int>>()

        val visited =
            HashSet<String>()

        val candidates =
            mutableListOf<Pair<String, Long>>()

        queue.addLast(root to 0)

        while (queue.isNotEmpty()) {

            val (current, depth) =
                queue.removeFirst()

            if (!visited.add(current)) {
                continue
            }

            val entries =
                safeDirectoryEntries(
                    current,
                    SEARCH_DIRECTORY_TIMEOUT_MS
                )

            entries
                .filter { it.isDirectory }
                .forEach { entry ->

                    val child =
                        normalizeDirectoryUrl(entry.url)

                    if (isSeasonDirectory(entry.name)) {
                        candidates.add(
                            child to (entry.modifiedAt ?: 0L)
                        )
                    } else if (depth < 10) {
                        queue.addLast(
                            child to (depth + 1)
                        )
                    }
                }
        }

        return candidates
            .maxWithOrNull(
                compareBy<Pair<String, Long>> { it.second }
                    .thenBy {
                        extractSeasonNumber(
                            getFolderTitle(it.first)
                        ) ?: 0
                    }
            )
            ?.first
    }

    /*
     * Direct Season loader.
     *
     * Homepage/search points to a Season directory. Clicking that card
     * must open a TvSeries response containing only that Season's episodes.
     */
    private suspend fun loadSeasonFolder(
        seasonFolderRaw: String,
        kind: ContentKind
    ): LoadResponse {

        val seasonFolder =
            normalizeDirectoryUrl(
                seasonFolderRaw
            )

        val group =
            buildGroupFromFolder(
                seasonFolder
            )

        if (
            group == null ||
            group.videos.isEmpty()
        ) {
            return newMovieLoadResponse(
                getFolderTitle(
                    seasonFolder
                ),
                seasonFolder,
                if (
                    kind == ContentKind.ANIME
                ) {
                    TvType.Anime
                } else {
                    TvType.TvSeries
                },
                seasonFolder
            ) {
                posterUrl =
                    group?.posterUrl
            }
        }

        val season =
            group.seasonNumber
                ?: extractSeasonNumber(
                    getFolderTitle(
                        seasonFolder
                    )
                )
                ?: 1

        val videos =
            deduplicateVideos(
                group.videos.map {
                    it.copy(
                        season = season,
                        posterUrl =
                            group.posterUrl
                                ?: it.posterUrl
                    )
                }
            ).sortedWith(
                compareBy<FtpVideo> {
                    it.episode ?: Int.MAX_VALUE
                }.thenBy {
                    it.order
                }
            )

        val episodes =
            videos.mapIndexed {
                    index,
                    video ->

                val episode =
                    video.episode
                        ?: index + 1

                newEpisode(
                    video.url
                ) {
                    name =
                        episodeDisplayName(
                            video.title,
                            episode
                        )

                    this.season =
                        season

                    this.episode =
                        episode

                    posterUrl =
                        group.posterUrl
                            ?: video.posterUrl

                    description =
                        if (
                            video.dualAudio
                        ) {
                            "Season $season • Dual Audio"
                        } else {
                            "Season $season"
                        }

                    date =
                        video.modifiedAt
                }
            }

        return newTvSeriesLoadResponse(
            group.title,
            seasonFolder,
            if (
                kind == ContentKind.ANIME
            ) {
                TvType.Anime
            } else {
                TvType.TvSeries
            },
            episodes
        ) {
            posterUrl =
                group.posterUrl

            plot =
                "Season $season" +
                    if (
                        videos.any {
                            it.dualAudio
                        }
                    ) {
                        "\nDual Audio"
                    } else {
                        ""
                    }
        }
    }

    private suspend fun buildGroupFromFolder(
        folderRaw: String
    ): FtpGroup? {

        val folder =
            normalizeDirectoryUrl(
                folderRaw
            )

        val kind =
            detectKind(
                folder
            )

        /*
         * ------------------------------------------------------------
         * SEASON CARD LOAD
         * ------------------------------------------------------------
         *
         * Search/homepage returns a Season folder URL for TV Show.
         * Clicking it must open the episode list for that Season.
         */
        if (
            kind != ContentKind.MOVIE &&
            isSeasonDirectory(
                getFolderTitle(folder)
            )
        ) {

            val seasonNumber =
                extractSeasonNumber(
                    getFolderTitle(folder)
                ) ?: 1

            val showRoot =
                parentDirectory(folder)
                    ?: folder

            val showTitle =
                getFolderTitle(showRoot)

            val seasonEntries =
                safeDirectoryEntries(folder)

            val seasonPoster =
                pickPoster(
                    seasonEntries
                )

            val showPoster =
                if (seasonPoster == null) {
                    pickPoster(
                        safeDirectoryEntries(
                            showRoot
                        )
                    )
                } else {
                    null
                }

            val poster =
                seasonPoster
                    ?: showPoster
                    ?: findNearestPoster(
                        showRoot
                    )

            val videos =
                mutableListOf<FtpVideo>()

            var order =
                0L

            collectVideosRecursively(
                root =
                    folder,
                inheritedPoster =
                    poster,
                inheritedSeason =
                    seasonNumber,
                destination =
                    videos,
                nextOrder = {
                    order++
                }
            )

            val finalVideos =
                deduplicateVideos(
                    videos
                )

            if (finalVideos.isEmpty()) {
                return null
            }

            /*
             * Season card ordering uses the Season folder date.
             */
            val seasonModified =
                folderModifiedAt(
                    showRoot,
                    folder
                ) ?: 0L

            return FtpGroup(
                title =
                    "$showTitle Season $seasonNumber",
                url =
                    folder,
                posterUrl =
                    poster,
                modifiedAt =
                    seasonModified,
                videos =
                    finalVideos.map {
                        it.copy(
                            season =
                                seasonNumber,
                            posterUrl =
                                poster
                                    ?: it.posterUrl
                        )
                    },
                kind =
                    if (
                        kind == ContentKind.ANIME
                    ) {
                        ContentKind.ANIME
                    } else {
                        ContentKind.SERIES
                    },
                isSeasonCard = true,
                seasonNumber = seasonNumber
            )
        }

        val entries =
            safeDirectoryEntries(
                folder
            )

        val directPoster =
            pickPoster(
                entries
            )

        val seasonFolders =
            entries.filter {
                it.isDirectory &&
                    isSeasonDirectory(
                        it.name
                    )
            }

        val directVideos =
            entries.filter {
                it.isVideo
            }

        /*
         * ------------------------------------------------------------
         * MOVIE CATEGORIES
         * ------------------------------------------------------------
         *
         * Multiple files in one folder are NOT episodes.
         * normalizeBuilders() splits them into separate movie cards.
         */
        if (
            kind == ContentKind.MOVIE &&
            directVideos.isNotEmpty()
        ) {

            val poster =
                directPoster
                    ?: findNearestPoster(
                        folder
                    )

            val videos =
                directVideos.mapIndexed {
                        index,
                        entry ->

                    makeVideo(
                        entry,
                        poster,
                        null,
                        index.toLong()
                    )
                }

            return FtpGroup(
                title =
                    getFolderTitle(folder),
                url =
                    folder,
                posterUrl =
                    poster,
                modifiedAt =
                    videos.maxOfOrNull {
                        it.modifiedAt
                    } ?: 0L,
                videos =
                    deduplicateVideos(
                        videos
                    ),
                kind =
                    ContentKind.MOVIE
            )
        }

        /*
         * Backward-compatible direct Show/Anime folder load.
         * Normally homepage/search points directly to a Season card.
         */
        if (
            seasonFolders.isNotEmpty()
        ) {

            val sharedPoster =
                directPoster
                    ?: findNearestPoster(
                        folder
                    )

            val videos =
                mutableListOf<FtpVideo>()

            var order =
                0L

            seasonFolders
                .sortedBy {
                    extractSeasonNumber(
                        it.name
                    ) ?: Int.MAX_VALUE
                }
                .forEach { seasonFolder ->

                    val season =
                        extractSeasonNumber(
                            seasonFolder.name
                        ) ?: 1

                    val seasonEntries =
                        safeDirectoryEntries(
                            seasonFolder.url
                        )

                    val seasonPoster =
                        pickPoster(
                            seasonEntries
                        )

                    collectVideosRecursively(
                        root =
                            seasonFolder.url,
                        inheritedPoster =
                            sharedPoster
                                ?: seasonPoster,
                        inheritedSeason =
                            season,
                        destination =
                            videos,
                        nextOrder = {
                            order++
                        }
                    )
                }

            val finalVideos =
                deduplicateVideos(
                    videos
                )

            if (finalVideos.isEmpty()) {
                return null
            }

            return FtpGroup(
                title =
                    getFolderTitle(folder),
                url =
                    folder,
                posterUrl =
                    sharedPoster
                        ?: finalVideos.firstOrNull {
                            it.posterUrl != null
                        }?.posterUrl,
                modifiedAt =
                    finalVideos.maxOf {
                        it.modifiedAt
                    },
                videos =
                    finalVideos,
                kind =
                    if (
                        kind == ContentKind.ANIME
                    ) {
                        ContentKind.ANIME
                    } else {
                        ContentKind.SERIES
                    }
            )
        }

        /*
         * Generic wrapper: keep walking until actual video locations.
         */
        val collected =
            mutableListOf<FtpVideo>()

        var order =
            0L

        collectVideosRecursively(
            root =
                folder,
            inheritedPoster =
                directPoster,
            inheritedSeason =
                null,
            destination =
                collected,
            nextOrder = {
                order++
            }
        )

        val finalVideos =
            deduplicateVideos(
                collected
            )

        if (finalVideos.isEmpty()) {
            return null
        }

        val poster =
            directPoster
                ?: finalVideos.firstOrNull {
                    it.posterUrl != null
                }?.posterUrl
                ?: findNearestPoster(
                    folder
                )

        return FtpGroup(
            title =
                getFolderTitle(folder),
            url =
                folder,
            posterUrl =
                poster,
            modifiedAt =
                finalVideos.maxOf {
                    it.modifiedAt
                },
            videos =
                finalVideos.map {
                    it.copy(
                        posterUrl =
                            poster
                                ?: it.posterUrl
                    )
                },
            kind =
                kind
        )
    }

    private suspend fun folderModifiedAt(
        parentUrl: String,
        childUrl: String
    ): Long? {

        return findFolderModifiedTime(
            safeDirectoryEntries(
                parentUrl
            ),
            childUrl
        )
    }

    private suspend fun collectVideosRecursively(
        root: String,
        inheritedPoster: String?,
        inheritedSeason: Int?,
        destination: MutableList<FtpVideo>,
        nextOrder: () -> Long
    ) {

        val queue =
            ArrayDeque<CrawlNode>()

        val visited =
            HashSet<String>()

        queue.addLast(
            CrawlNode(
                url =
                    normalizeDirectoryUrl(
                        root
                    ),
                inheritedPoster =
                    inheritedPoster,
                inheritedModifiedAt =
                    null,
                collectionRoot =
                    null,
                seasonHint =
                    inheritedSeason
            )
        )

        while (
            queue.isNotEmpty()
        ) {

            val node =
                queue.removeFirst()

            val current =
                normalizeDirectoryUrl(
                    node.url
                )

            if (
                !visited.add(
                    current
                )
            ) {
                continue
            }

            val entries =
                safeDirectoryEntries(
                    current
                )

            val localPoster =
                pickPoster(
                    entries
                )

            val poster =
                localPoster
                    ?: node.inheritedPoster

            val season =
                extractSeasonNumber(
                    current
                )
                    ?: node.seasonHint

            entries
                .filter {
                    it.isVideo
                }
                .forEach { entry ->

                    destination.add(
                        makeVideo(
                            entry,
                            poster,
                            season,
                            nextOrder()
                        )
                    )
                }

            entries
                .filter {
                    it.isDirectory
                }
                .forEach { child ->

                    queue.addLast(
                        CrawlNode(
                            url =
                                normalizeDirectoryUrl(
                                    child.url
                                ),
                            inheritedPoster =
                                poster,
                            inheritedModifiedAt =
                                child.modifiedAt,
                            collectionRoot =
                                null,
                            seasonHint =
                                extractSeasonNumber(
                                    child.name
                                ) ?: season
                        )
                    )
                }
        }
    }

    /*
     * ---------------------------------------------------------------
     * VIDEO + DUPLICATE LOGIC
     * ---------------------------------------------------------------
     */

    private fun makeVideo(
        entry: FtpEntry,
        poster: String?,
        seasonHint: Int?,
        order: Long
    ): FtpVideo {

        val title =
            cleanTitle(
                entry.name,
                entry.url
            )

        val season =
            seasonHint
                ?: extractSeasonNumber(
                    title
                )
                ?: extractSeasonNumber(
                    entry.url
                )

        return FtpVideo(
            title =
                title,
            url =
                entry.url,
            posterUrl =
                poster,
            modifiedAt =
                entry.modifiedAt
                    ?: 0L,
            sizeBytes =
                entry.sizeBytes,
            season =
                season,
            episode =
                extractEpisodeNumber(
                    title
                ),
            dualAudio =
                detectDualAudio(
                    title
                ) ||
                    detectDualAudio(
                        entry.url
                    ),
            resolution =
                resolutionFromName(
                    title
                ),
            bitrateMbps =
                bitrateFromName(
                    title
                ),
            order =
                order
        )
    }

    private fun deduplicateVideos(
        videos: List<FtpVideo>
    ): List<FtpVideo> {

        return videos
            .groupBy {
                canonicalVideoKey(
                    it
                )
            }
            .values
            .mapNotNull { candidates ->

                candidates.maxWithOrNull(
                    compareByDescending<FtpVideo> {
                        if (
                            it.dualAudio
                        ) {
                            1
                        } else {
                            0
                        }
                    }
                        .thenByDescending {
                            it.resolution
                        }
                        .thenByDescending {
                            it.bitrateMbps
                                ?: 0.0
                        }
                        .thenByDescending {
                            it.sizeBytes
                                ?: 0L
                        }
                        .thenByDescending {
                            it.modifiedAt
                        }
                )
            }
            .sortedWith(
                compareBy<FtpVideo> {
                    it.season ?: 1
                }
                    .thenBy {
                        it.episode
                            ?: Int.MAX_VALUE
                    }
                    .thenBy {
                        it.order
                    }
            )
    }

    private fun canonicalVideoKey(
        video: FtpVideo
    ): String {

        /*
         * Episode duplicate identity:
         *
         * same season + same episode + same resolution
         *
         * Dual Audio is intentionally ignored in the key so:
         *   Episode 1 720p
         *   Episode 1 720p Dual Audio
         *
         * become one item and Dual Audio wins.
         *
         * But:
         *   Episode 1 720p
         *   Episode 1 1080p
         *
         * remain two different versions.
         */
        if (
            video.season != null &&
            video.episode != null
        ) {

            return "EP:" +
                video.season +
                ":" +
                video.episode +
                ":R" +
                video.resolution
        }

        /*
         * Movie duplicate identity:
         *
         * base title + resolution.
         *
         * Dual Audio is a preference, NOT a unique identity.
         * Therefore:
         *
         * Kotlin 720p
         * Kotlin 720p Dual Audio
         *
         * collapse to one, Dual Audio wins.
         *
         * Kotlin 720p
         * Kotlin 1080p
         *
         * are kept separately.
         */
        val base =
            normalizeSearchText(
                video.title
            )
                .replace(
                    Regex(
                        "(?i)\\bdual\\s*audio\\b"
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "(?i)\\bmulti\\s*audio\\b"
                    ),
                    " "
                )
                .replace(
                    Regex(
                        "(?i)\\s+"
                    ),
                    " "
                )
                .trim()

        return "MOVIE:" +
            base +
            ":R" +
            video.resolution
    }

    private fun normalizeBuilders(
        builders: List<GroupBuilder>
    ): List<FtpGroup> {

        val output =
            mutableListOf<FtpGroup>()

        builders.forEach { builder ->

            val videos =
                deduplicateVideos(
                    builder.videos
                )

            if (
                videos.isEmpty()
            ) {
                return@forEach
            }

            val commonPoster =
                builder.posterUrl
                    ?: videos.firstOrNull {
                        it.posterUrl != null
                    }?.posterUrl

            when (
                builder.kind
            ) {

                /*
                 * MOVIE CATEGORIES:
                 *
                 * every real video = one separate movie card.
                 *
                 * This is deliberately NOT episode-based.
                 */
                ContentKind.MOVIE -> {

                    videos.forEach { video ->

                        val poster =
                            video.posterUrl
                                ?: commonPoster

                        output.add(
                            FtpGroup(
                                title =
                                    video.title,
                                url =
                                    video.url,
                                posterUrl =
                                    poster,
                                modifiedAt =
                                    video.modifiedAt,
                                videos =
                                    listOf(
                                        video.copy(
                                            posterUrl =
                                                poster
                                        )
                                    ),
                                kind =
                                    ContentKind.MOVIE
                            )
                        )
                    }
                }

                /*
                 * TV SHOW:
                 * all seasons/episodes remain one series.
                 */
                ContentKind.SERIES -> {

                    output.add(
                        FtpGroup(
                            title =
                                builder.title,
                            url =
                                builder.url,
                            posterUrl =
                                commonPoster,
                            modifiedAt =
                                maxOf(
                                    builder.modifiedAt,
                                    videos.maxOf {
                                        it.modifiedAt
                                    }
                                ),
                            videos =
                                videos.map {
                                    it.copy(
                                        posterUrl =
                                            commonPoster
                                                ?: it.posterUrl
                                    )
                                },
                            kind =
                                ContentKind.SERIES
                        )
                    )
                }

                /*
                 * ANIME:
                 * one video = playable item
                 * multiple videos = episodes/series.
                 */
                ContentKind.ANIME -> {

                    val episodeMode =
                        videos.size > 1 ||
                            videos.any {
                                it.season != null ||
                                    it.episode != null
                            }

                    if (
                        episodeMode
                    ) {

                        output.add(
                            FtpGroup(
                                title =
                                    builder.title,
                                url =
                                    builder.url,
                                posterUrl =
                                    commonPoster,
                                modifiedAt =
                                    maxOf(
                                        builder.modifiedAt,
                                        videos.maxOf {
                                            it.modifiedAt
                                        }
                                    ),
                                videos =
                                    videos.map {
                                        it.copy(
                                            posterUrl =
                                                commonPoster
                                                    ?: it.posterUrl
                                        )
                                    },
                                kind =
                                    ContentKind.ANIME
                            )
                        )

                    } else {

                        videos.forEach { video ->

                            output.add(
                                FtpGroup(
                                    title =
                                        video.title,
                                    url =
                                        video.url,
                                    posterUrl =
                                        video.posterUrl
                                            ?: commonPoster,
                                    modifiedAt =
                                        video.modifiedAt,
                                    videos =
                                        listOf(
                                            video
                                        ),
                                    kind =
                                        ContentKind.ANIME
                                )
                            )
                        }
                    }
                }
            }
        }

        return output
            .sortedWith(
                groupComparator()
            )
    }

    /*
     * ---------------------------------------------------------------
     * GROUP-LEVEL DUPLICATE RESOLUTION
     * ---------------------------------------------------------------
     *
     * IMPORTANT MOVIE RULE:
     *
     * Same title + same resolution:
     *     normal
     *     Dual Audio
     *         -> one result, Dual Audio wins.
     *
     * Same title + different resolution:
     *     720p
     *     1080p
     *         -> BOTH remain separate results.
     *
     * This layer is intentionally conservative. It does not remove
     * items merely because their titles look similar.
     */
    private fun deduplicateGroups(
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        return groups
            .groupBy {
                canonicalGroupKey(it)
            }
            .values
            .mapNotNull { candidates ->

                /*
                 * Only candidates with the exact same canonical key
                 * reach this point. Therefore Dual Audio can safely
                 * win without collapsing different resolutions.
                 */
                candidates.maxWithOrNull(
                    compareByDescending<FtpGroup> {
                        if (
                            it.hasDualAudio
                        ) {
                            1
                        } else {
                            0
                        }
                    }
                        .thenByDescending {
                            it.maxResolution
                        }
                        .thenByDescending {
                            it.maxSizeBytes
                        }
                        .thenByDescending {
                            it.modifiedAt
                        }
                        .thenBy {
                            it.url
                        }
                )
            }
            .sortedWith(
                groupComparator()
            )
    }

    private fun canonicalGroupKey(
        group: FtpGroup
    ): String {

        /*
         * Movie / anime single-item cards:
         *
         * title + resolution are the duplicate identity.
         *
         * Dual Audio is intentionally NOT part of the key, because
         * the Dual Audio copy must replace the non-Dual-Audio copy
         * when everything else is the same.
         *
         * Resolution IS part of the key, so 720p and 1080p are never
         * collapsed into one item.
         */
        if (
            group.kind != ContentKind.SERIES &&
            group.videos.size == 1
        ) {

            val video =
                group.videos.first()

            val base =
                normalizeSearchText(
                    video.title
                )
                    .replace(
                        Regex(
                            "(?i)\\bdual\\s*audio\\b"
                        ),
                        " "
                    )
                    .replace(
                        Regex(
                            "(?i)\\bmulti\\s*audio\\b"
                        ),
                        " "
                    )
                    .replace(
                        Regex(
                            "(?i)\\s+"
                        ),
                        " "
                    )
                    .trim()

            return group.kind.name +
                ":MOVIE:" +
                base +
                ":R" +
                video.resolution
        }

        /*
         * Series/show identity is intentionally conservative.
         * Same title coming from two separate source paths is not
         * treated as a duplicate automatically.
         */
        return group.kind.name +
            ":SERIES:" +
            normalizeSearchText(
                group.title
            ) +
            ":" +
            normalizeSearchText(
                group.url
            )
    }


    private fun findFolderModifiedTime(
        entries: List<FtpEntry>,
        folderUrl: String
    ): Long? {

        val target =
            normalizeDirectoryUrl(
                folderUrl
            )

        return entries
            .firstOrNull {
                normalizeDirectoryUrl(
                    it.url
                ).equals(
                    target,
                    true
                )
            }
            ?.modifiedAt
    }

    private fun groupComparator():
        Comparator<FtpGroup> {

        return compareByDescending<FtpGroup> {
            it.modifiedAt
        }
            .thenByDescending {
                if (
                    it.hasDualAudio
                ) {
                    1
                } else {
                    0
                }
            }
            .thenByDescending {
                it.maxResolution
            }
            .thenBy {
                it.title.lowercase(
                    Locale.getDefault()
                )
            }
    }

    /*
     * ---------------------------------------------------------------
     * SEARCH RANKING
     * ---------------------------------------------------------------
     */

    private fun scoreGroup(
        query: String,
        group: FtpGroup
    ): Int {

        val title =
            normalizeSearchText(
                group.title
            )

        val path =
            normalizeSearchText(
                group.url
            )

        var score =
            scoreText(
                query,
                title
            )

        if (
            path.contains(
                query
            )
        ) {
            score =
                max(
                    score,
                    600
                )
        }

        for (
            video in group.videos
        ) {

            score =
                max(
                    score,
                    scoreText(
                        query,
                        normalizeSearchText(
                            video.title
                        )
                    )
                )

            score =
                max(
                    score,
                    scoreText(
                        query,
                        normalizeSearchText(
                            video.url
                        )
                    )
                )
        }

        return if (
            score > 0
        ) {
            score +
                if (
                    group.hasDualAudio
                ) {
                    40
                } else {
                    0
                }
        } else {
            0
        }
    }

    private fun scoreText(
        query: String,
        candidate: String
    ): Int {

        if (
            candidate.isBlank()
        ) {
            return 0
        }

        if (
            candidate == query
        ) {
            return 5000
        }

        if (
            candidate.startsWith(
                query
            )
        ) {
            return 4300
        }

        if (
            candidate.contains(
                query
            )
        ) {
            return 3600
        }

        val queryTokens =
            query.split(
                ' '
            ).filter {
                it.isNotBlank()
            }

        val candidateTokens =
            candidate.split(
                ' '
            ).filter {
                it.isNotBlank()
            }

        var total =
            0

        for (
            token in queryTokens
        ) {

            var best =
                0

            for (
                candidateToken
                in candidateTokens
            ) {

                best =
                    max(
                        best,
                        tokenScore(
                            token,
                            candidateToken
                        )
                    )
            }

            total +=
                best
        }

        return total
    }

    private fun tokenScore(
        query: String,
        candidate: String
    ): Int {

        if (
            query ==
            candidate
        ) {
            return 1000
        }

        if (
            candidate.startsWith(
                query
            )
        ) {
            return 850
        }

        if (
            candidate.contains(
                query
            )
        ) {
            return 750
        }

        /*
         * User explicitly requested 2/3/4-character discovery.
         */
        if (
            query.length in 2..3
        ) {
            return if (
                candidate.contains(
                    query
                )
            ) {
                700
            } else {
                0
            }
        }

        /*
         * Fuzzy matching only for longer tokens, to avoid
         * overwhelming results for short queries.
         */
        if (
            query.length >= 4 &&
            candidate.length >= 4
        ) {

            val distance =
                levenshtein(
                    query,
                    candidate
                )

            if (
                distance <= 2
            ) {
                return 600 -
                    distance * 100
            }
        }

        return 0
    }

    private fun normalizeSearchText(
        value: String
    ): String {

        return decodeSafely(
            value
        )
            .lowercase(
                Locale.getDefault()
            )
            .replace(
                "&",
                " and "
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}]+"
                ),
                " "
            )
            .trim()
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        if (
            a == b
        ) {
            return 0
        }

        if (
            a.isEmpty()
        ) {
            return b.length
        }

        if (
            b.isEmpty()
        ) {
            return a.length
        }

        var previous =
            IntArray(
                b.length + 1
            ) {
                it
            }

        var current =
            IntArray(
                b.length + 1
            )

        for (
            i in a.indices
        ) {

            current[0] =
                i + 1

            for (
                j in b.indices
            ) {

                val cost =
                    if (
                        a[i] ==
                        b[j]
                    ) {
                        0
                    } else {
                        1
                    }

                current[j + 1] =
                    minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + cost
                    )
            }

            val tmp =
                previous

            previous =
                current

            current =
                tmp
        }

        return previous[
            b.length
        ]
    }

    /*
     * ---------------------------------------------------------------
     * POSTER DISCOVERY
     * ---------------------------------------------------------------
     *
     * Same final content folder first.
     * Then inherited/nearest parent.
     *
     * h5ai's fallback UI images are <img>, not content <a href> entries,
     * so they are naturally excluded from this poster selection.
     */

    private fun pickPoster(
        entries: List<FtpEntry>
    ): String? {

        val images =
            entries.filter {
                it.isImage
            }

        if (
            images.isEmpty()
        ) {
            return null
        }

        /*
         * Real DhakaFlix content commonly uses a_AL_.jpg/a_VL_.jpg.
         */
        val preferred =
            images.firstOrNull {

                val name =
                    it.name.lowercase(
                        Locale.getDefault()
                    )

                name == "a_al_.jpg" ||
                    name == "a_vl_.jpg" ||
                    name == "poster.jpg" ||
                    name == "poster.png" ||
                    name.contains(
                        "poster"
                    ) ||
                    name.contains(
                        "cover"
                    )
            }

        if (
            preferred != null
        ) {
            return preferred.url
        }

        /*
         * Otherwise select one stable image.
         */
        return images
            .maxWithOrNull(
                compareBy<FtpEntry> {
                    it.sizeBytes ?: 0L
                }
                    .thenByDescending {
                        it.order
                    }
            )
            ?.url
    }

    private suspend fun findNearestPoster(
        directoryRaw: String
    ): String? {

        var current =
            normalizeDirectoryUrl(
                directoryRaw
            )

        /*
         * No "3 folders", "5 folders", or "10 folders" hard limit.
         * Walk upward within the same host until a real image is found.
         */
        while (true) {

            pickPoster(
                safeDirectoryEntries(
                    current
                )
            )?.let {
                return it
            }

            val parent =
                parentDirectory(
                    current
                )
                    ?: return null

            if (
                hostOf(parent) !=
                hostOf(current)
            ) {
                return null
            }

            if (
                parent.equals(
                    current,
                    true
                )
            ) {
                return null
            }

            current =
                parent
        }
    }

    /*
     * ---------------------------------------------------------------
     * DIRECTORY PARSER / H5AI
     * ---------------------------------------------------------------
     */

    private suspend fun safeDirectoryEntries(
        url: String,
        timeoutMs: Long? = null
    ): List<FtpEntry> {

        return try {
            getDirectoryEntries(
                url,
                timeoutMs
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getDirectoryEntries(
        urlRaw: String,
        timeoutMs: Long? = null
    ): List<FtpEntry> {

        val url =
            normalizeDirectoryUrl(
                urlRaw
            )

        val response =
            if (timeoutMs == null) {
                app.get(
                    url
                )
            } else {
                kotlinx.coroutines.withTimeoutOrNull(
                    timeoutMs
                ) {
                    app.get(
                        url
                    )
                }
            } ?: return emptyList()

        val anchors =
            response.document.select(
                "a[href]"
            )

        val result =
            mutableListOf<FtpEntry>()

        var order =
            0L

        for (
            element in anchors
        ) {

            val href =
                element
                    .attr(
                        "href"
                    )
                    .trim()

            if (
                href.isBlank() ||
                href.startsWith("#") ||
                href.startsWith(
                    "javascript:",
                    true
                ) ||
                href.startsWith(
                    "mailto:",
                    true
                )
            ) {
                continue
            }

            val absolute =
                resolveUrl(
                    url,
                    href
                )

            if (
                absolute.isBlank()
            ) {
                continue
            }

            val normalized =
                if (
                    absolute.endsWith("/")
                ) {
                    normalizeDirectoryUrl(
                        absolute
                    )
                } else {
                    absolute
                }

            if (
                isParentDirectoryLink(
                    href,
                    normalized,
                    url
                )
            ) {
                continue
            }

            if (
                normalized.equals(
                    normalizeDirectoryUrl(
                        url
                    ),
                    true
                )
            ) {
                continue
            }

            val name =
                decodeSafely(
                    element
                        .text()
                        .trim()
                        .ifBlank {
                            getTitleFromUrl(
                                normalized
                            )
                        }
                )

            val lowered =
                normalized
                    .substringBefore("?")
                    .lowercase(
                        Locale.getDefault()
                    )

            val isVideo =
                VIDEO_EXTENSIONS.any {
                    lowered.endsWith(it)
                }

            val isImage =
                IMAGE_EXTENSIONS.any {
                    lowered.endsWith(it)
                }

            val isDirectory =
                normalized
                    .substringBefore("?")
                    .endsWith("/")

            if (
                !isVideo &&
                !isImage &&
                !isDirectory
            ) {
                continue
            }

            result.add(
                FtpEntry(
                    name =
                        name,
                    url =
                        normalized,
                    isVideo =
                        isVideo,
                    isImage =
                        isImage,
                    isDirectory =
                        isDirectory,
                    modifiedAt =
                        findModifiedTime(
                            element
                        ),
                    sizeBytes =
                        findSizeBytes(
                            element
                        ),
                    order =
                        order++
                )
            )
        }

        return result
    }

    private fun findModifiedTime(
        element: Element
    ): Long? {

        var current:
            Element? =
            element

        repeat(
            6
        ) {

            parseListingDate(
                current
                    ?.text()
                    ?.trim()
                    .orEmpty()
            )?.let {
                return it
            }

            current =
                current?.parent()
        }

        return null
    }

    private fun findSizeBytes(
        element: Element
    ): Long? {

        var current:
            Element? =
            element

        repeat(
            6
        ) {

            parseSize(
                current
                    ?.text()
                    ?.trim()
                    .orEmpty()
            )?.let {
                return it
            }

            current =
                current?.parent()
        }

        return null
    }

    private fun parseListingDate(
        text: String
    ): Long? {

        val patterns =
            listOf(
                Regex(
                    "\\b\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"
                ),
                Regex(
                    "\\b\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"
                ),
                Regex(
                    "\\b\\d{1,2}-\\d{1,2}-\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"
                ),
                Regex(
                    "\\b[A-Za-z]{3,9}\\s+\\d{1,2},\\s+\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"
                )
            )

        val formats =
            listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm",
                "dd-MM-yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm",
                "MMM d, yyyy HH:mm:ss",
                "MMM d, yyyy HH:mm",
                "MMMM d, yyyy HH:mm:ss",
                "MMMM d, yyyy HH:mm"
            )

        for (
            regex in patterns
        ) {

            val match =
                regex.find(
                    text
                )?.value
                    ?: continue

            for (
                format in formats
            ) {

                try {

                    val parser =
                        SimpleDateFormat(
                            format,
                            Locale.US
                        )

                    parser.isLenient =
                        false

                    parser.parse(
                        match
                    )?.let {
                        return it.time
                    }

                } catch (_: Exception) {
                }
            }
        }

        return null
    }

    private fun parseSize(
        text: String
    ): Long? {

        val match =
            Regex(
                "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(KB|MB|GB|TB|B)\\b"
            ).find(
                text
            ) ?: return null

        val value =
            match
                .groupValues
                .getOrNull(1)
                ?.toDoubleOrNull()
                ?: return null

        return when (
            match
                .groupValues
                .getOrNull(2)
                ?.uppercase(
                    Locale.getDefault()
                )
        ) {

            "TB" ->
                (
                    value *
                        1024.0 *
                        1024.0 *
                        1024.0 *
                        1024.0
                    ).toLong()

            "GB" ->
                (
                    value *
                        1024.0 *
                        1024.0 *
                        1024.0
                    ).toLong()

            "MB" ->
                (
                    value *
                        1024.0 *
                        1024.0
                    ).toLong()

            "KB" ->
                (
                    value *
                        1024.0
                    ).toLong()

            else ->
                value.toLong()
        }
    }

    private fun isParentDirectoryLink(
        href: String,
        absoluteUrl: String,
        currentUrl: String
    ): Boolean {

        val clean =
            href
                .substringBefore("#")
                .trim()

        if (
            clean == ".." ||
            clean == "../" ||
            clean.equals(
                "./",
                true
            )
        ) {
            return true
        }

        val parent =
            parentDirectory(
                currentUrl
            )

        return parent != null &&
            absoluteUrl.equals(
                parent,
                true
            )
    }

    /*
     * ---------------------------------------------------------------
     * MISC HELPERS
     * ---------------------------------------------------------------
     */


    private fun isSeasonDirectory(
        value: String
    ): Boolean {

        val decoded =
            decodeSafely(
                value
            ).trim()

        return Regex(
            "(?i)^\\s*(?:season\\s*[-._]?\\s*\\d{1,3}|s\\s*\\d{1,3})\\s*$"
        ).matches(
            decoded
        )
    }


    private fun extractSeasonNumber(
        value: String
    ): Int? {

        val decoded =
            decodeSafely(
                value
            )

        Regex(
            "(?i)\\bSeason\\s*[-._]?\\s*(\\d{1,3})\\b"
        )
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        Regex(
            "(?i)\\bS\\s*(\\d{1,3})\\s*E\\s*\\d{1,4}\\b"
        )
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return Regex(
            "(?i)^\\s*S\\s*(\\d{1,3})\\s*$"
        )
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractEpisodeNumber(
        value: String
    ): Int? {

        val decoded =
            decodeSafely(
                value
            )

        Regex(
            "(?i)\\bS\\d{1,3}E(\\d{1,4})\\b"
        )
            .find(
                decoded
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let {
                return it
            }

        return Regex(
            "(?i)\\b(?:EP|EPISODE|E)[ ._-]*(\\d{1,4})\\b"
        )
            .find(
                decoded
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun detectDualAudio(
        value: String
    ): Boolean =
        Regex(
            "(?i)\\bdual[\\s_-]*audio\\b"
        ).containsMatchIn(
            decodeSafely(
                value
            )
        )

    private fun resolutionFromName(
        value: String
    ): Int {

        val decoded =
            decodeSafely(
                value
            )

        return Regex(
            "(?i)\\b(2160|1440|1080|720|576|480|360|240|144)p\\b"
        )
            .find(
                decoded
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: if (
                Regex(
                    "(?i)\\b4k\\b"
                ).containsMatchIn(
                    decoded
                )
            ) {
                2160
            } else {
                0
            }
    }

    private fun bitrateFromName(
        value: String
    ): Double? {

        val decoded =
            decodeSafely(
                value
            )

        return Regex(
            "(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:mbps|mb/s|mbit)\\b"
        )
            .find(
                decoded
            )
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun detectQuality(
        value: String
    ): Int {

        return when {

            "2160p" in value ||
                "4k" in value ->
                Qualities.P2160.value

            "1440p" in value ->
                Qualities.P1440.value

            "1080p" in value ->
                Qualities.P1080.value

            "720p" in value ->
                Qualities.P720.value

            "576p" in value ->
                Qualities.P480.value

            "480p" in value ->
                Qualities.P480.value

            "360p" in value ->
                Qualities.P360.value

            "240p" in value ->
                Qualities.P240.value

            "144p" in value ->
                Qualities.P144.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun buildSeriesPlot(
        seasons: List<Int>,
        dualAudio: Boolean
    ): String {

        val seasonsText =
            if (
                seasons.isEmpty()
            ) {
                "Season 1"
            } else {
                seasons.joinToString(
                    "\n"
                ) {
                    "Season $it"
                }
            }

        return if (
            dualAudio
        ) {
            "$seasonsText\nDual Audio"
        } else {
            seasonsText
        }
    }

    private fun getFolderTitle(
        url: String
    ): String {

        val title =
            decodeSafely(
                url
                    .trimEnd('/')
                    .substringAfterLast('/')
            )

        return title.ifBlank {
            "DhakaFTP"
        }
    }

    private fun getTitleFromUrl(
        url: String
    ): String {

        return decodeSafely(
            url
                .substringBefore("?")
                .trimEnd('/')
                .substringAfterLast('/')
        ).removeVideoExtension()
    }

    private fun cleanTitle(
        name: String,
        url: String
    ): String {

        val cleaned =
            decodeSafely(
                name
            )
                .trim()
                .removeSuffix("/")
                .removeSuffix("\\")

        return if (
            cleaned.isNotBlank()
        ) {
            cleaned.removeVideoExtension()
        } else {
            getTitleFromUrl(
                url
            )
        }
    }

    private fun String.removeVideoExtension():
        String {

        val lowered =
            lowercase(
                Locale.getDefault()
            )

        val extension =
            VIDEO_EXTENSIONS.firstOrNull {
                lowered.endsWith(
                    it
                )
            }

        return if (
            extension != null
        ) {
            substring(
                0,
                length -
                    extension.length
            )
        } else {
            this
        }
    }

    private fun isVideo(
        url: String
    ): Boolean {

        val lowered =
            url
                .substringBefore("?")
                .lowercase(
                    Locale.getDefault()
                )

        return VIDEO_EXTENSIONS.any {
            lowered.endsWith(it)
        }
    }

    private fun refererFor(
        url: String
    ): String {

        return try {

            val uri =
                URI(
                    url
                )

            val scheme =
                uri.scheme
                    ?: return mainUrl

            val authority =
                uri.rawAuthority
                    ?: return mainUrl

            "$scheme://$authority/"

        } catch (_: Exception) {
            mainUrl
        }
    }

    private fun normalizeDirectoryUrl(
        value: String
    ): String {

        val clean =
            value
                .substringBefore("#")
                .trim()

        return if (
            clean.endsWith("/")
        ) {
            clean
        } else {
            "$clean/"
        }
    }

    private fun parentDirectory(
        rawUrl: String
    ): String? {

        val url =
            normalizeDirectoryUrl(
                rawUrl
            )
                .trimEnd('/')

        val schemeIndex =
            url.indexOf(
                "://"
            )

        if (
            schemeIndex < 0
        ) {
            return null
        }

        val authorityEnd =
            url.indexOf(
                '/',
                schemeIndex + 3
            )

        if (
            authorityEnd < 0
        ) {
            return null
        }

        val path =
            url.substring(
                authorityEnd
            )

        val slash =
            path.lastIndexOf(
                '/'
            )

        return if (
            slash <= 0
        ) {
            url.substring(
                0,
                authorityEnd + 1
            )
        } else {
            url.substring(
                0,
                authorityEnd +
                    slash +
                    1
            )
        }
    }

    private fun hostOf(
        url: String
    ): String? =
        try {
            URI(url).host
        } catch (_: Exception) {
            null
        }

    private fun resolveUrl(
        baseUrl: String,
        href: String
    ): String =
        try {
            URI(
                normalizeDirectoryUrl(
                    baseUrl
                )
            )
                .resolve(
                    href
                )
                .toString()
        } catch (_: Exception) {
            try {
                URI(baseUrl)
                    .resolve(
                        href
                    )
                    .toString()
            } catch (_: Exception) {
                ""
            }
        }

    private fun decodeSafely(
        value: String
    ): String =
        try {
            URLDecoder.decode(
                value.replace(
                    "+",
                    "%2B"
                ),
                "UTF-8"
            )
        } catch (_: Exception) {
            value
        }


    private fun detectKind(
        root: String
    ): ContentKind {

        val normalized =
            decodeSafely(
                root
            ).lowercase(
                Locale.getDefault()
            )

        return when {

            normalized.contains("tv-web-series") ||
                normalized.contains("tv web series") ||
                normalized.contains("korean tv") ||
                normalized.contains("korean%20tv") ||
                normalized.contains("web series") ->
                ContentKind.SERIES

            normalized.contains("animation movies") ||
                normalized.contains("animation%20movies") ->
                ContentKind.ANIME

            else ->
                ContentKind.MOVIE
        }
    }

    private fun encodeTvShowRoots(
        first: String,
        second: String
    ): String =
        TV_SHOW_PREFIX +
            first +
            TV_SHOW_SEPARATOR +
            second

    private fun decodeTvShowRoots(
        value: String
    ): List<String> {

        if (
            !value.startsWith(
                TV_SHOW_PREFIX
            )
        ) {
            return emptyList()
        }

        return value
            .removePrefix(
                TV_SHOW_PREFIX
            )
            .split(
                TV_SHOW_SEPARATOR
            )
            .filter {
                it.isNotBlank()
            }
    }
}
