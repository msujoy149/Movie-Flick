package com.movieflick.dhakaftp

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
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
     * Keep the current Movie-Flick category roots.
     * The actual DhakaFlix site contains deeper folders under these roots,
     * including year folders, collections and Season folders.
     */
    override val mainPage = mainPageOf(
        "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/" to "English Movies",
        "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/" to "Hindi Movies",
        "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/" to "Kolkata Bangla Movies",
        "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/" to "South Indian Hindi Dubbed",
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/" to "TV Web Series",
        "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/" to "K-Drama",
        "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/" to "Anime"
    )

    private val videoExtensions = setOf(
        ".mkv", ".mp4", ".webm", ".avi", ".mov", ".m4v", ".m3u8"
    )

    private val imageExtensions = setOf(
        ".jpg", ".jpeg", ".png", ".webp"
    )

    private val initialPageSize = 7
    private val searchPageSize = 40
    private val maxDirectoriesToScan = 750
    private val maxFilesToCollect = 6000
    private val maxSearchDistance = 2
    private val cacheDurationMs = TimeUnit.MINUTES.toMillis(5)

    private data class FtpEntry(
        val name: String,
        val url: String,
        val isVideo: Boolean,
        val isImage: Boolean,
        val isDirectory: Boolean,
        val modifiedAt: Long?,
        val order: Long
    )

    private data class DirectoryNode(
        val url: String,
        val inheritedPoster: String?,
        val modifiedAt: Long?,
        val depth: Int
    )

    private data class FtpVideo(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val modifiedAt: Long,
        val order: Long
    )

    private data class CacheEntry(
        val createdAt: Long,
        val complete: Boolean,
        val videos: List<FtpVideo>
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val scanJobs = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val root = normalizeDirectoryUrl(request.data)

        if (page < 1) {
            return newHomePageResponse(request, emptyList(), false)
        }

        if (page == 1) {
            val cached = validCache(root)
            val firstPage = if (cached != null) {
                cached.videos.take(initialPageSize)
            } else {
                scanLatestVideos(root, initialPageSize)
            }

            // Start filling the full in-memory index without making the first
            // homepage response wait for the complete recursive scan.
            prewarm(root)

            val results = firstPage.map { toMovieSearch(it) }
            return newHomePageResponse(
                request,
                results,
                cached?.let { !it.complete || it.videos.size > initialPageSize } ?: true
            )
        }

        val state = validCache(root).takeIf { it?.complete == true } ?: run {
            // When the user scrolls before prewarming finishes, complete the
            // scan now. This keeps scrolling deterministic instead of returning
            // an empty page while the background index is still being built.
            val videos = scanAllVideos(root)
            cache[root] = CacheEntry(System.currentTimeMillis(), true, videos)
            cache[root]!!
        }

        val offset = (page - 1) * initialPageSize
        if (offset >= state.videos.size) {
            return newHomePageResponse(request, emptyList(), false)
        }

        val next = state.videos
            .drop(offset)
            .take(initialPageSize)
            .map { toMovieSearch(it) }

        val hasNext = offset + initialPageSize < state.videos.size || !state.complete
        return newHomePageResponse(request, next, hasNext)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val normalizedQuery = normalizeSearchText(query)
        if (normalizedQuery.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val allResults = mutableListOf<ScoredResult>()

        // Use completed indexes first. Missing roots are prewarmed in the
        // background; if this is the first-ever search, do a real scan so the
        // search remains comprehensive instead of pretending a partial cache is
        // the whole library.
        for (rootData in mainPage) {
            val root = normalizeDirectoryUrl(rootData.data)
            val existing = validCache(root)
            val videos = if (existing?.complete == true) {
                existing.videos
            } else {
                prewarm(root)
                scanAllVideos(root).also {
                    cache[root] = CacheEntry(System.currentTimeMillis(), true, it)
                }
            }

            for (video in videos) {
                val score = scoreMatch(normalizedQuery, video)
                if (score > 0) {
                    allResults.add(ScoredResult(video, score))
                }
            }
        }

        val sorted = allResults
            .distinctBy { it.video.url }
            .sortedWith(
                compareByDescending<ScoredResult> { it.score }
                    .thenByDescending { it.video.modifiedAt }
                    .thenBy { it.video.title.lowercase(Locale.getDefault()) }
            )

        val offset = (page - 1) * searchPageSize
        val pageItems = sorted
            .drop(offset)
            .take(searchPageSize)
            .map { toMovieSearch(it.video) }

        val hasNext = offset + searchPageSize < sorted.size
        return newSearchResponseList(pageItems, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        if (isVideo(url)) {
            val parent = normalizeDirectoryUrl(url.substringBeforeLast('/'))
            val title = getTitleFromUrl(url)
            val poster = findPosterForDirectory(parent, inheritedPoster = null)

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
            }
        }

        val folder = normalizeDirectoryUrl(url)
        val entries = getDirectoryEntries(folder)

        // If this is a movie/collection folder, prefer its direct video files.
        // For a TV folder, recursively collect episodes and keep the folder
        // poster as the series poster.
        val directVideos = entries.filter { it.isVideo }
        val hasSeasonFolder = entries.any {
            it.isDirectory && normalizeSearchText(it.name).startsWith("season")
        }

        val poster = findPosterForDirectory(folder, inheritedPoster = null)

        if (directVideos.isNotEmpty() && !hasSeasonFolder) {
            val primary = directVideos.sortedWith(
                compareByDescending<FtpEntry> { it.modifiedAt ?: 0L }
                    .thenBy { it.order }
            ).first()

            return newMovieLoadResponse(
                getFolderTitle(folder),
                folder,
                TvType.Movie,
                primary.url
            ) {
                posterUrl = poster
            }
        }

        val episodes = scanEpisodes(folder, poster)
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                getFolderTitle(folder),
                folder,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
            }
        }

        return newMovieLoadResponse(
            getFolderTitle(folder),
            folder,
            TvType.Movie,
            folder
        ) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val cleanUrl = data.substringBefore("?").lowercase(Locale.getDefault())
        val type = if (cleanUrl.endsWith(".m3u8")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        val quality = when {
            "2160" in cleanUrl || "4k" in cleanUrl -> Qualities.P2160.value
            "1440" in cleanUrl -> Qualities.P1440.value
            "1080" in cleanUrl -> Qualities.P1080.value
            "720" in cleanUrl -> Qualities.P720.value
            "480" in cleanUrl -> Qualities.P480.value
            "360" in cleanUrl -> Qualities.P360.value
            "240" in cleanUrl -> Qualities.P240.value
            "144" in cleanUrl -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = type
            ) {
                referer = refererFor(data)
                this.quality = quality
            }
        )

        return true
    }

    private fun toMovieSearch(video: FtpVideo): SearchResponse {
        return newMovieSearchResponse(
            video.title,
            video.url,
            TvType.Movie
        ) {
            posterUrl = video.posterUrl
        }
    }

    private data class ScoredResult(
        val video: FtpVideo,
        val score: Int
    )

    private fun validCache(root: String): CacheEntry? {
        val entry = cache[root] ?: return null
        val fresh = System.currentTimeMillis() - entry.createdAt <= cacheDurationMs
        return if (fresh) entry else null
    }

    private fun prewarm(root: String) {
        if (!scanJobs.add(root)) return

        scope.launch {
            try {
                val existing = cache[root]
                if (existing?.complete == true &&
                    System.currentTimeMillis() - existing.createdAt <= cacheDurationMs
                ) {
                    return@launch
                }

                val videos = scanAllVideos(root)
                cache[root] = CacheEntry(
                    createdAt = System.currentTimeMillis(),
                    complete = true,
                    videos = videos
                )
            } catch (_: Exception) {
                // Keep the lightweight first-page result usable even if a
                // background root scan is temporarily unavailable.
            } finally {
                scanJobs.remove(root)
            }
        }
    }

    private suspend fun scanLatestVideos(
        root: String,
        limit: Int
    ): List<FtpVideo> {
        val queue = ArrayDeque<DirectoryNode>()
        val visited = HashSet<String>()
        val output = mutableListOf<FtpVideo>()
        var orderCounter = 0L
        var directoriesScanned = 0

        queue.addLast(
            DirectoryNode(
                url = root,
                inheritedPoster = null,
                modifiedAt = null,
                depth = 0
            )
        )

        while (
            queue.isNotEmpty() &&
            output.size < limit &&
            directoriesScanned < 80
        ) {
            val node = queue.removeFirst()
            val key = normalizeDirectoryUrl(node.url)
            if (!visited.add(key)) continue
            directoriesScanned++

            val entries = try {
                getDirectoryEntries(key)
            } catch (_: Exception) {
                emptyList()
            }

            val localPoster = pickPoster(entries) ?: node.inheritedPoster

            entries
                .filter { it.isVideo }
                .sortedWith(
                    compareByDescending<FtpEntry> { it.modifiedAt ?: node.modifiedAt ?: 0L }
                        .thenBy { it.order }
                )
                .take(limit - output.size)
                .forEach { entry ->
                    output.add(
                        FtpVideo(
                            title = cleanTitle(entry.name, entry.url),
                            url = entry.url,
                            posterUrl = localPoster,
                            modifiedAt = entry.modifiedAt ?: node.modifiedAt ?: 0L,
                            order = orderCounter++
                        )
                    )
                }

            if (output.size >= limit) break

            entries
                .filter { it.isDirectory }
                .sortedWith(
                    compareByDescending<FtpEntry> { it.modifiedAt ?: node.modifiedAt ?: 0L }
                        .thenBy { it.order }
                )
                .take(20)
                .forEach { child ->
                    queue.addLast(
                        DirectoryNode(
                            url = normalizeDirectoryUrl(child.url),
                            inheritedPoster = localPoster,
                            modifiedAt = child.modifiedAt ?: node.modifiedAt,
                            depth = node.depth + 1
                        )
                    )
                }
        }

        return output.sortedWith(
            compareByDescending<FtpVideo> { it.modifiedAt }
                .thenBy { it.order }
        ).take(limit)
    }

    private suspend fun scanAllVideos(root: String): List<FtpVideo> {
        val queue = ArrayDeque<DirectoryNode>()
        val visited = HashSet<String>()
        val discovered = mutableListOf<FtpVideo>()
        var orderCounter = 0L
        var directoriesScanned = 0

        queue.addLast(DirectoryNode(root, null, null, 0))

        while (
            queue.isNotEmpty() &&
            directoriesScanned < maxDirectoriesToScan &&
            discovered.size < maxFilesToCollect
        ) {
            val node = queue.removeFirst()
            val key = normalizeDirectoryUrl(node.url)
            if (!visited.add(key)) continue

            directoriesScanned++
            val entries = try {
                getDirectoryEntries(key)
            } catch (_: Exception) {
                emptyList()
            }

            val localPoster = pickPoster(entries) ?: node.inheritedPoster

            for (entry in entries) {
                if (discovered.size >= maxFilesToCollect) break

                if (entry.isVideo) {
                    discovered.add(
                        FtpVideo(
                            title = cleanTitle(entry.name, entry.url),
                            url = entry.url,
                            posterUrl = localPoster,
                            modifiedAt = entry.modifiedAt ?: node.modifiedAt ?: 0L,
                            order = orderCounter++
                        )
                    )
                }
            }

            val nextDirs = entries
                .filter { it.isDirectory }
                .sortedWith(
                    compareByDescending<FtpEntry> { it.modifiedAt ?: node.modifiedAt ?: 0L }
                        .thenBy { it.order }
                )

            for (child in nextDirs) {
                if (child.url.equals(key, ignoreCase = true)) continue
                queue.addLast(
                    DirectoryNode(
                        url = normalizeDirectoryUrl(child.url),
                        inheritedPoster = localPoster,
                        modifiedAt = child.modifiedAt ?: node.modifiedAt,
                        depth = node.depth + 1
                    )
                )
            }
        }

        return discovered
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<FtpVideo> { it.modifiedAt }
                    .thenBy { it.order }
            )
    }

    private suspend fun scanEpisodes(
        root: String,
        inheritedPoster: String?
    ): List<Episode> {
        val queue = ArrayDeque<DirectoryNode>()
        val visited = HashSet<String>()
        val videos = mutableListOf<FtpVideo>()
        var orderCounter = 0L
        var directoriesScanned = 0

        queue.addLast(DirectoryNode(root, inheritedPoster, null, 0))

        while (queue.isNotEmpty() && directoriesScanned < 500 && videos.size < 1500) {
            val node = queue.removeFirst()
            val key = normalizeDirectoryUrl(node.url)
            if (!visited.add(key)) continue
            directoriesScanned++

            val entries = try {
                getDirectoryEntries(key)
            } catch (_: Exception) {
                emptyList()
            }

            val localPoster = pickPoster(entries) ?: node.inheritedPoster

            entries.filter { it.isVideo }.forEach { entry ->
                videos.add(
                    FtpVideo(
                        title = cleanTitle(entry.name, entry.url),
                        url = entry.url,
                        posterUrl = localPoster,
                        modifiedAt = entry.modifiedAt ?: node.modifiedAt ?: 0L,
                        order = orderCounter++
                    )
                )
            }

            entries
                .filter { it.isDirectory }
                .sortedWith(compareBy<FtpEntry> { extractSeasonNumber(it.name) ?: Int.MAX_VALUE })
                .forEach { child ->
                    queue.addLast(
                        DirectoryNode(
                            normalizeDirectoryUrl(child.url),
                            localPoster,
                            child.modifiedAt ?: node.modifiedAt,
                            node.depth + 1
                        )
                    )
                }
        }

        return videos
            .distinctBy { it.url }
            .sortedWith(
                compareBy<FtpVideo> { extractSeasonNumber(it.url) ?: 1 }
                    .thenBy { extractEpisodeNumber(it.title) ?: Int.MAX_VALUE }
                    .thenBy { it.order }
            )
            .mapIndexed { index, video ->
                newEpisode(video.url) {
                    name = video.title
                    episode = extractEpisodeNumber(video.title) ?: index + 1
                    season = extractSeasonNumber(video.url) ?: 1
                }
            }
    }

    private suspend fun getDirectoryEntries(url: String): List<FtpEntry> {
        val response = app.get(url)
        val document = response.document
        val anchors = document.select("a[href]")
        val result = mutableListOf<FtpEntry>()
        var order = 0L

        for (element in anchors) {
            val href = element.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") ||
                href.startsWith("javascript:", true) ||
                href.startsWith("mailto:", true)
            ) continue

            val absolute = resolveUrl(url, href)
            if (absolute.isBlank()) continue

            val normalized = if (absolute.endsWith('/')) {
                normalizeDirectoryUrl(absolute)
            } else {
                absolute
            }

            if (isParentDirectoryLink(href, normalized, url)) continue
            if (normalized.equals(normalizeDirectoryUrl(url), true)) continue

            val textName = element.text().trim().ifBlank { getTitleFromUrl(normalized) }
            val decodedName = decodeSafely(textName)
            val cleanUrl = normalized.substringBefore("?").lowercase(Locale.getDefault())

            val isVideo = videoExtensions.any { cleanUrl.endsWith(it) }
            val isImage = imageExtensions.any { cleanUrl.endsWith(it) }
            val isDirectory = normalized.substringBefore("?").endsWith('/')

            if (!isVideo && !isImage && !isDirectory) continue

            result.add(
                FtpEntry(
                    name = decodedName,
                    url = normalized,
                    isVideo = isVideo,
                    isImage = isImage,
                    isDirectory = isDirectory,
                    modifiedAt = findModifiedTime(element),
                    order = order++
                )
            )
        }

        return result
    }

    private fun pickPoster(entries: List<FtpEntry>): String? {
        return entries
            .asSequence()
            .filter { it.isImage }
            .map { it.url }
            .firstOrNull()
    }

    private suspend fun findPosterForDirectory(
        directory: String,
        inheritedPoster: String?
    ): String? {
        val direct = try {
            val entries = getDirectoryEntries(directory)
            pickPoster(entries)
        } catch (_: Exception) {
            null
        }

        if (direct != null) return direct
        if (inheritedPoster != null) return inheritedPoster

        val parent = directory.trimEnd('/').substringBeforeLast('/').takeIf { it.contains('/') }
            ?: return null
        return try {
            pickPoster(getDirectoryEntries(normalizeDirectoryUrl(parent)))
        } catch (_: Exception) {
            null
        }
    }

    private fun scoreMatch(query: String, video: FtpVideo): Int {
        val title = normalizeSearchText(video.title)
        val path = normalizeSearchText(video.url)
        if (title.isBlank() && path.isBlank()) return 0

        val queryTokens = query.split(' ').filter { it.isNotBlank() }
        var score = 0

        if (title == query) score += 3000
        if (title.contains(query)) score += 2200
        if (path.contains(query)) score += 700

        val titleTokens = title.split(' ').filter { it.isNotBlank() }
        for (qt in queryTokens) {
            var best = 0
            for (tt in titleTokens) {
                best = max(best, tokenScore(qt, tt))
            }
            if (best == 0 && path.contains(qt)) best = 250
            score += best
        }

        if (score == 0) return 0
        return score + latestBonus(video.modifiedAt)
    }

    private fun tokenScore(queryToken: String, titleToken: String): Int {
        if (queryToken == titleToken) return 900
        if (titleToken.startsWith(queryToken)) return 750
        if (titleToken.contains(queryToken)) return 650
        if (queryToken.length >= 4 && queryToken.contains(titleToken)) return 500

        if (queryToken.length >= 4 && titleToken.length >= 4) {
            val distance = levenshtein(queryToken, titleToken)
            if (distance <= maxSearchDistance) return 420 - distance * 80
        }
        return 0
    }

    private fun normalizeSearchText(value: String): String {
        val decoded = decodeSafely(value)
            .lowercase(Locale.getDefault())
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return decoded.replace(Regex("\\s+"), " ")
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[b.length]
    }

    private fun latestBonus(modifiedAt: Long): Int {
        if (modifiedAt <= 0L) return 0
        val age = System.currentTimeMillis() - modifiedAt
        return when {
            age <= TimeUnit.DAYS.toMillis(1) -> 140
            age <= TimeUnit.DAYS.toMillis(7) -> 90
            age <= TimeUnit.DAYS.toMillis(30) -> 50
            else -> 0
        }
    }

    private fun findModifiedTime(element: Element): Long? {
        var current: Element? = element
        repeat(5) {
            val text = current?.text()?.trim().orEmpty()
            parseListingDate(text)?.let { return it }
            current = current?.parent()
        }
        return null
    }

    private fun parseListingDate(text: String): Long? {
        val patterns = listOf(
            Regex("\\b\\d{4}-\\d{2}-\\d{2}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            Regex("\\b\\d{1,2}-\\d{1,2}-\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            Regex("\\b[A-Za-z]{3,9}\\s+\\d{1,2},\\s+\\d{4}\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\b")
        )
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
            "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm",
            "MMM d, yyyy HH:mm:ss", "MMM d, yyyy HH:mm",
            "MMMM d, yyyy HH:mm:ss", "MMMM d, yyyy HH:mm"
        )

        for (regex in patterns) {
            val match = regex.find(text)?.value ?: continue
            for (pattern in formats) {
                try {
                    val formatter = SimpleDateFormat(pattern, Locale.US)
                    formatter.isLenient = false
                    val date = formatter.parse(match)
                    if (date != null) return date.time
                } catch (_: Exception) {
                    // Try the next supported format.
                }
            }
        }
        return null
    }

    private fun extractEpisodeNumber(value: String): Int? {
        val match = Regex("(?i)\\bS\\d{1,3}E(\\d{1,4})\\b").find(value)
            ?: Regex("(?i)\\bE(\\d{1,4})\\b").find(value)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractSeasonNumber(value: String): Int? {
        val match = Regex("(?i)\\bSeason\\s*(\\d{1,3})\\b").find(decodeSafely(value))
            ?: Regex("(?i)\\bS(\\d{1,3})E\\d{1,4}\\b").find(decodeSafely(value))
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isParentDirectoryLink(
        href: String,
        absoluteUrl: String,
        currentUrl: String
    ): Boolean {
        val cleanHref = href.substringBefore('#').trim()
        if (cleanHref == ".." || cleanHref == "../" || cleanHref.equals("./", true)) {
            return true
        }

        val current = normalizeDirectoryUrl(currentUrl)
        val parent = normalizeDirectoryUrl(resolveUrl(current, "../"))
        return absoluteUrl.equals(parent, true)
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        return try {
            URI(baseUrl).resolve(href).toString()
        } catch (_: Exception) {
            try {
                URI(normalizeDirectoryUrl(baseUrl)).resolve(href).toString()
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun normalizeDirectoryUrl(url: String): String {
        val clean = url.substringBefore("#")
        return if (clean.endsWith('/')) clean else "$clean/"
    }

    private fun cleanTitle(rawName: String, url: String): String {
        val cleaned = decodeSafely(rawName)
            .trim()
            .removeSuffix("/")
            .removeSuffix("\\")

        if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            return cleaned
        }
        return getTitleFromUrl(url)
    }

    private fun getTitleFromUrl(url: String): String {
        val fileName = url
            .substringBefore("?")
            .trimEnd('/')
            .substringAfterLast('/')
        val withoutExtension = fileName.substringBeforeLast('.', fileName)
        return decodeSafely(withoutExtension)
    }

    private fun getFolderTitle(url: String): String {
        val name = url.trimEnd('/').substringAfterLast('/')
        if (name.isBlank()) return "DhakaFTP"
        return decodeSafely(name)
    }

    private fun isVideo(url: String): Boolean {
        val clean = url.substringBefore("?").lowercase(Locale.getDefault())
        return videoExtensions.any { clean.endsWith(it) }
    }

    private fun refererFor(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme ?: return mainUrl
            val authority = uri.rawAuthority ?: return mainUrl
            "$scheme://$authority/"
        } catch (_: Exception) {
            mainUrl
        }
    }

    private fun decodeSafely(value: String): String {
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
