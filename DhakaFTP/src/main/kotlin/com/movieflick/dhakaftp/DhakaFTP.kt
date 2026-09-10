
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

        const val MOVIE_HOME_SIZE = 7
        const val TV_HOME_SIZE = 6
        const val TV_SOURCE_BATCH = 3
        const val SEARCH_PAGE_SIZE = 50

        /*
         * The first synchronous homepage request is intentionally
         * lightweight. The full recursive index has no folder-depth cap.
         */
        const val QUICK_SCAN_DIRECTORY_LIMIT = 120

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
        val kind: ContentKind
    ) {
        val isSeries: Boolean
            get() =
                videos.size > 1 ||
                    videos.any {
                        it.season != null
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
                        MOVIE_HOME_SIZE
                    )

            updatePartialCache(
                root,
                firstGroups
            )

            /*
             * Important: full indexing starts AFTER the small first
             * batch has been prepared.
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

        val offset =
            (page - 1) *
                MOVIE_HOME_SIZE

        val cached =
            validCache(root)

        /*
         * If background indexing has already discovered enough content,
         * return it immediately. This makes normal scrolling feel lazy.
         */
        if (
            cached != null &&
            cached.groups.size > offset
        ) {

            val pageItems =
                cached.groups
                    .drop(offset)
                    .take(MOVIE_HOME_SIZE)

            return newHomePageResponse(
                request,
                pageItems.map {
                    toSearchResponse(it)
                },
                !(
                    cached.complete &&
                        offset +
                        MOVIE_HOME_SIZE >=
                        cached.groups.size
                )
            )
        }

        /*
         * If the user scrolls faster than the background scan, wait for
         * the SAME scan instead of launching another recursive crawl.
         */
        val complete =
            awaitCompleteIndex(root)

        if (
            offset >=
            complete.groups.size
        ) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val pageItems =
            complete.groups
                .drop(offset)
                .take(MOVIE_HOME_SIZE)

        return newHomePageResponse(
            request,
            pageItems.map {
                toSearchResponse(it)
            },
            offset +
                MOVIE_HOME_SIZE <
                complete.groups.size
        )
    }

    private suspend fun getTvShowHomePage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val roots =
            decodeTvShowRoots(
                request.data
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

        if (page == 1) {

            val first =
                getInitialGroups(
                    roots[0],
                    TV_SOURCE_BATCH
                )

            val second =
                getInitialGroups(
                    roots[1],
                    TV_SOURCE_BATCH
                )

            prewarm(roots[0])
            prewarm(roots[1])

            val mixed =
                mixThreeAndThree(
                    first,
                    second
                ).take(
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

        val first =
            awaitCompleteIndex(
                roots[0]
            )

        val second =
            awaitCompleteIndex(
                roots[1]
            )

        val mixed =
            mixThreeAndThree(
                first.groups,
                second.groups
            )

        val offset =
            (page - 1) *
                TV_HOME_SIZE

        if (
            offset >=
            mixed.size
        ) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val result =
            mixed
                .drop(offset)
                .take(TV_HOME_SIZE)

        return newHomePageResponse(
            request,
            result.map {
                toSearchResponse(it)
            },
            offset +
                TV_HOME_SIZE <
                mixed.size
        )
    }

    private suspend fun getInitialGroups(
        root: String,
        limit: Int
    ): List<FtpGroup> {

        val cached =
            validCache(root)

        if (
            cached?.groups
                ?.isNotEmpty() == true
        ) {
            return cached.groups.take(limit)
        }

        val initial =
            scanLatestGroups(
                root,
                limit
            )

        updatePartialCache(
            root,
            initial
        )

        return initial
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

        val indexes =
            coroutineScope {

                searchRoots.map { root ->
                    async {
                        awaitCompleteIndex(
                            normalizeDirectoryUrl(
                                root
                            )
                        )
                    }
                }.awaitAll()
            }

        val groups =
            deduplicateGroups(
                indexes.flatMap {
                    it.groups
                }
            )

        val matches =
            groups.mapNotNull { group ->

                val score =
                    scoreGroup(
                        normalizedQuery,
                        group
                    )

                if (
                    score > 0
                ) {
                    SearchMatch(
                        group,
                        score
                    )
                } else {
                    null
                }
            }

        val sorted =
            matches.sortedWith(
                compareByDescending<SearchMatch> {
                    it.score
                }
                    .thenByDescending {
                        it.group.modifiedAt
                    }
                    .thenBy {
                        it.group.title.lowercase(
                            Locale.getDefault()
                        )
                    }
            )

        val offset =
            (page - 1) *
                SEARCH_PAGE_SIZE

        val result =
            sorted
                .drop(offset)
                .take(SEARCH_PAGE_SIZE)
                .map {
                    toSearchResponse(
                        it.group
                    )
                }

        return newSearchResponseList(
            result,
            offset +
                SEARCH_PAGE_SIZE <
                sorted.size
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

        val group =
            buildGroupFromFolder(
                normalizeDirectoryUrl(
                    url
                )
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
                    scanAllGroups(
                        root
                    )

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
                        scanAllGroups(
                            root
                        )

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
    private suspend fun scanLatestGroups(
        rootRaw: String,
        limit: Int
    ): List<FtpGroup> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val queue =
            PriorityQueue<CrawlNode>(
                compareByDescending<CrawlNode> {
                    it.inheritedModifiedAt
                        ?: 0L
                }
            )

        val visited =
            HashSet<String>()

        val builders =
            LinkedHashMap<
                String,
                GroupBuilder
            >()

        queue.add(
            CrawlNode(
                url = root,
                inheritedPoster = null,
                inheritedModifiedAt = null,
                collectionRoot = null,
                seasonHint = null
            )
        )

        var directoriesVisited =
            0

        var order =
            0L

        while (
            queue.isNotEmpty() &&
            directoriesVisited <
            QUICK_SCAN_DIRECTORY_LIMIT
        ) {

            val node =
                queue.poll()

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

            directoriesVisited++

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

            val modified =
                maxOf(
                    node.inheritedModifiedAt
                        ?: 0L,
                    entries.maxOfOrNull {
                        it.modifiedAt
                            ?: 0L
                    } ?: 0L
                )

            val directories =
                entries.filter {
                    it.isDirectory
                }

            val seasonDirectories =
                directories.filter {
                    isSeasonDirectory(
                        it.name
                    )
                }

            val seriesRoot =
                when {

                    node.collectionRoot !=
                        null ->
                        node.collectionRoot

                    seasonDirectories
                        .isNotEmpty() ->
                        current

                    else ->
                        null
                }

            if (
                seasonDirectories.isNotEmpty()
            ) {

                val builder =
                    builders.getOrPut(
                        current
                    ) {
                        GroupBuilder(
                            title =
                                getFolderTitle(
                                    current
                                ),
                            url =
                                current,
                            kind =
                                detectKind(
                                    root
                                )
                        )
                    }

                if (
                    builder.posterUrl == null
                ) {
                    builder.posterUrl =
                        localPoster
                            ?: node.inheritedPoster
                }

                builder.modifiedAt =
                    maxOf(
                        builder.modifiedAt,
                        modified
                    )
            }

            val directVideos =
                entries.filter {
                    it.isVideo
                }

            if (
                directVideos.isNotEmpty()
            ) {

                val groupRoot =
                    seriesRoot
                        ?: current

                val builder =
                    builders.getOrPut(
                        groupRoot
                    ) {
                        GroupBuilder(
                            title =
                                getFolderTitle(
                                    groupRoot
                                ),
                            url =
                                groupRoot,
                            kind =
                                detectKind(
                                    root
                                )
                        )
                    }

                if (
                    builder.posterUrl == null
                ) {
                    builder.posterUrl =
                        if (
                            groupRoot ==
                            current
                        ) {
                            localPoster
                                ?: node.inheritedPoster
                        } else {
                            poster
                        }
                }

                directVideos.forEach { entry ->

                    val video =
                        makeVideo(
                            entry,
                            builder.posterUrl
                                ?: poster,
                            extractSeasonNumber(
                                current
                            ) ?: node.seasonHint,
                            order++
                        )

                    builder.videos.add(
                        video
                    )

                    builder.modifiedAt =
                        maxOf(
                            builder.modifiedAt,
                            video.modifiedAt
                        )
                }
            }

            directories
                .sortedWith(
                    compareByDescending<FtpEntry> {
                        it.modifiedAt
                            ?: modified
                    }
                )
                .forEach { child ->

                    queue.add(
                        CrawlNode(
                            url =
                                normalizeDirectoryUrl(
                                    child.url
                                ),
                            inheritedPoster =
                                if (
                                    seriesRoot != null
                                ) {
                                    builders[
                                        seriesRoot
                                    ]?.posterUrl
                                        ?: poster
                                } else {
                                    poster
                                },
                            inheritedModifiedAt =
                                maxOf(
                                    child.modifiedAt
                                        ?: 0L,
                                    modified
                                ),
                            collectionRoot =
                                seriesRoot,
                            seasonHint =
                                extractSeasonNumber(
                                    child.name
                                )
                                    ?: extractSeasonNumber(
                                        current
                                    )
                                    ?: node.seasonHint
                        )
                    )
                }

            val groups =
                normalizeBuilders(
                    builders.values.toList()
                )

            if (
                groups.size >= limit
            ) {

                return groups
                    .sortedWith(
                        groupComparator()
                    )
                    .take(limit)
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

    /*
     * ---------------------------------------------------------------
     * COMPLETE INDEX
     * ---------------------------------------------------------------
     *
     * This crawler has NO artificial folder-depth limit.
     */
    private suspend fun scanAllGroups(
        rootRaw: String
    ): List<FtpGroup> {

        val root =
            normalizeDirectoryUrl(
                rootRaw
            )

        val queue =
            ArrayDeque<CrawlNode>()

        val visited =
            HashSet<String>()

        val builders =
            LinkedHashMap<
                String,
                GroupBuilder
            >()

        queue.addLast(
            CrawlNode(
                url = root,
                inheritedPoster = null,
                inheritedModifiedAt = null,
                collectionRoot = null,
                seasonHint = null
            )
        )

        var order =
            0L

        var partialCounter =
            0

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

            val modified =
                maxOf(
                    node.inheritedModifiedAt
                        ?: 0L,
                    entries.maxOfOrNull {
                        it.modifiedAt
                            ?: 0L
                    } ?: 0L
                )

            val directories =
                entries.filter {
                    it.isDirectory
                }

            val seasonDirectories =
                directories.filter {
                    isSeasonDirectory(
                        it.name
                    )
                }

            val seriesRoot =
                when {

                    node.collectionRoot !=
                        null ->
                        node.collectionRoot

                    seasonDirectories
                        .isNotEmpty() ->
                        current

                    else ->
                        null
                }

            if (
                seasonDirectories.isNotEmpty()
            ) {

                val builder =
                    builders.getOrPut(
                        current
                    ) {
                        GroupBuilder(
                            title =
                                getFolderTitle(
                                    current
                                ),
                            url =
                                current,
                            kind =
                                detectKind(
                                    root
                                )
                        )
                    }

                if (
                    builder.posterUrl == null
                ) {
                    builder.posterUrl =
                        localPoster
                            ?: node.inheritedPoster
                }

                builder.modifiedAt =
                    maxOf(
                        builder.modifiedAt,
                        modified
                    )
            }

            val videos =
                entries.filter {
                    it.isVideo
                }

            if (
                videos.isNotEmpty()
            ) {

                val groupRoot =
                    seriesRoot
                        ?: current

                val builder =
                    builders.getOrPut(
                        groupRoot
                    ) {
                        GroupBuilder(
                            title =
                                getFolderTitle(
                                    groupRoot
                                ),
                            url =
                                groupRoot,
                            kind =
                                detectKind(
                                    root
                                )
                        )
                    }

                if (
                    builder.posterUrl == null
                ) {
                    builder.posterUrl =
                        if (
                            groupRoot ==
                            current
                        ) {
                            localPoster
                                ?: node.inheritedPoster
                        } else {
                            poster
                        }
                }

                videos.forEach { entry ->

                    val video =
                        makeVideo(
                            entry,
                            builder.posterUrl
                                ?: poster,
                            extractSeasonNumber(
                                current
                            ) ?: node.seasonHint,
                            order++
                        )

                    builder.videos.add(
                        video
                    )

                    builder.modifiedAt =
                        maxOf(
                            builder.modifiedAt,
                            video.modifiedAt
                        )
                }
            }

            /*
             * If a real poster is discovered later inside a series tree,
             * propagate that single poster to every already-known episode.
             */
            if (
                seriesRoot != null
            ) {

                val builder =
                    builders[
                        seriesRoot
                    ]

                if (
                    builder != null &&
                    builder.posterUrl == null &&
                    poster != null
                ) {

                    builder.posterUrl =
                        poster

                    for (
                        index
                        in builder.videos.indices
                    ) {

                        builder.videos[index] =
                            builder.videos[index]
                                .copy(
                                    posterUrl =
                                        poster
                                )
                    }
                }
            }

            /*
             * Continue crawling every folder.
             */
            directories
                .forEach { child ->

                    queue.addLast(
                        CrawlNode(
                            url =
                                normalizeDirectoryUrl(
                                    child.url
                                ),
                            inheritedPoster =
                                if (
                                    seriesRoot != null
                                ) {
                                    builders[
                                        seriesRoot
                                    ]?.posterUrl
                                        ?: poster
                                } else {
                                    poster
                                },
                            inheritedModifiedAt =
                                maxOf(
                                    child.modifiedAt
                                        ?: 0L,
                                    modified
                                ),
                            collectionRoot =
                                seriesRoot,
                            seasonHint =
                                extractSeasonNumber(
                                    child.name
                                )
                                    ?: extractSeasonNumber(
                                        current
                                    )
                                    ?: node.seasonHint
                        )
                    )
                }

            partialCounter++

            /*
             * Publish already-discovered content into cache while the
             * background scan is still running.
             */
            if (
                partialCounter >= 5
            ) {

                partialCounter =
                    0

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

    /*
     * ---------------------------------------------------------------
     * LOAD ONE FOLDER
     * ---------------------------------------------------------------
     */

    private suspend fun buildGroupFromFolder(
        folderRaw: String
    ): FtpGroup? {

        val folder =
            normalizeDirectoryUrl(
                folderRaw
            )

        val entries =
            safeDirectoryEntries(
                folder
            )

        val kind =
            detectKind(
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
         * Simple movie/content folder.
         *
         * Multiple versions of the same movie are collapsed to the best
         * available copy.
         */
        if (
            seasonFolders.isEmpty() &&
            directVideos.isNotEmpty()
        ) {

            val poster =
                directPoster
                    ?: findNearestPoster(
                        folder
                    )

            val versions =
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

            val finalVideos =
                deduplicateVideos(
                    versions
                )

            return FtpGroup(
                title =
                    getFolderTitle(
                        folder
                    ),
                url =
                    folder,
                posterUrl =
                    poster,
                modifiedAt =
                    finalVideos.maxOfOrNull {
                        it.modifiedAt
                    } ?: 0L,
                videos =
                    finalVideos,
                kind =
                    kind
            )
        }

        /*
         * Explicit multi-season structure.
         */
        if (
            seasonFolders.isNotEmpty()
        ) {

            /*
             * The series poster is inherited by every season.
             */
            val sharedPoster =
                directPoster
                    ?: findNearestPoster(
                        folder
                    )

            val videos =
                mutableListOf<FtpVideo>()

            var order =
                0L

            for (
                seasonFolder in
                seasonFolders.sortedWith(
                    compareBy {
                        extractSeasonNumber(
                            it.name
                        ) ?: Int.MAX_VALUE
                    }
                )
            ) {

                val seasonNumber =
                    extractSeasonNumber(
                        seasonFolder.name
                    ) ?: 1

                val seasonPoster =
                    pickPoster(
                        safeDirectoryEntries(
                            seasonFolder.url
                        )
                    )

                val seasonPosterToUse =
                    sharedPoster
                        ?: seasonPoster

                collectVideosRecursively(
                    root =
                        seasonFolder.url,
                    inheritedPoster =
                        seasonPosterToUse,
                    inheritedSeason =
                        seasonNumber,
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

            if (
                finalVideos.isEmpty()
            ) {
                return null
            }

            val finalPoster =
                sharedPoster
                    ?: finalVideos
                        .firstOrNull {
                            it.posterUrl != null
                        }
                        ?.posterUrl

            return FtpGroup(
                title =
                    getFolderTitle(
                        folder
                    ),
                url =
                    folder,
                posterUrl =
                    finalPoster,
                modifiedAt =
                    finalVideos.maxOf {
                        it.modifiedAt
                    },
                videos =
                    finalVideos.map {
                        it.copy(
                            posterUrl =
                                finalPoster
                                    ?: it.posterUrl
                        )
                    },
                kind =
                    kind
            )
        }

        /*
         * Wrapper folder:
         * keep descending until actual video-containing folders are found.
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

        if (
            finalVideos.isEmpty()
        ) {
            return null
        }

        val poster =
            directPoster
                ?: finalVideos
                    .firstOrNull {
                        it.posterUrl != null
                    }
                    ?.posterUrl
                ?: findNearestPoster(
                    folder
                )

        return FtpGroup(
            title =
                getFolderTitle(
                    folder
                ),
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
         * For SxxExx episodes, season+episode is the strongest identity.
         */
        if (
            video.season != null &&
            video.episode != null
        ) {
            return "S${video.season}-E${video.episode}"
        }

        return normalizeSearchText(
            video.title
        )
            .replace(
                Regex(
                    "\\b(2160p|1440p|1080p|720p|576p|480p|360p|240p|144p)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\b(4k|uhd|fhd|web[- ]?dl|webrip|bluray|brrip|hdtc|hcam|hdrip|dvdrip|hd)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\b(dual\\s*audio|multi\\s*audio|hindi|english|korean|bangla|bengali)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\b(x264|x265|h264|h265|hevc|10bit|8bit|aac|ac3|dts|ddp|eac3|esub|subs?|msub)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\[[^\\]]*]"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\([^)]*\\)"
                ),
                " "
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

    private fun normalizeBuilders(
        builders: List<GroupBuilder>
    ): List<FtpGroup> {

        return builders.mapNotNull { builder ->

            val videos =
                deduplicateVideos(
                    builder.videos
                )

            if (
                videos.isEmpty()
            ) {
                return@mapNotNull null
            }

            val series =
                videos.size > 1 ||
                    videos.any {
                        it.season != null
                    }

            val sharedPoster =
                builder.posterUrl
                    ?: videos.firstOrNull {
                        it.posterUrl != null
                    }?.posterUrl

            FtpGroup(
                title =
                    builder.title,
                url =
                    builder.url,
                posterUrl =
                    sharedPoster,
                modifiedAt =
                    maxOf(
                        builder.modifiedAt,
                        videos.maxOf {
                            it.modifiedAt
                        }
                    ),
                videos =
                    if (
                        series
                    ) {
                        videos.map {
                            it.copy(
                                posterUrl =
                                    sharedPoster
                                        ?: it.posterUrl
                            )
                        }
                    } else {
                        videos
                    },
                kind =
                    if (
                        builder.kind ==
                        ContentKind.ANIME
                    ) {
                        ContentKind.ANIME
                    } else if (
                        series
                    ) {
                        ContentKind.SERIES
                    } else {
                        ContentKind.MOVIE
                    }
            )
        }
    }

    private fun deduplicateGroups(
        groups: List<FtpGroup>
    ): List<FtpGroup> {

        return groups
            .groupBy {
                canonicalGroupKey(
                    it
                )
            }
            .values
            .mapNotNull {
                    candidates ->

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
                )
            }
            .sortedWith(
                groupComparator()
            )
    }

    private fun canonicalGroupKey(
        group: FtpGroup
    ): String {

        return normalizeSearchText(
            group.title
        )
            .replace(
                Regex(
                    "\\b(2160p|1440p|1080p|720p|576p|480p|360p|240p|144p|4k|uhd|fhd|web[- ]?dl|webrip|bluray|brrip|hdtc|hcam|hdrip|dvdrip)\\b"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\b(dual\\s*audio|multi\\s*audio|hindi|english|korean|bangla|bengali)\\b"
                ),
                " "
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
        url: String
    ): List<FtpEntry> {

        return try {
            getDirectoryEntries(
                url
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun getDirectoryEntries(
        urlRaw: String
    ): List<FtpEntry> {

        val url =
            normalizeDirectoryUrl(
                urlRaw
            )

        val response =
            app.get(
                url
            )

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

        return Regex(
            "(?i)^\\s*season\\s*\\d{1,3}\\s*$"
        ).matches(
            decodeSafely(
                value
            ).trim()
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
            "(?i)\\bSeason\\s*(\\d{1,3})\\b"
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
            "(?i)\\bS(\\d{1,3})E\\d{1,4}\\b"
        )
            .find(
                decoded
            )
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
            root.lowercase(
                Locale.getDefault()
            )

        return if (
            normalized.contains(
                "animation%20movies"
            ) ||
            normalized.contains(
                "animation movies"
            )
        ) {
            ContentKind.ANIME
        } else {
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
