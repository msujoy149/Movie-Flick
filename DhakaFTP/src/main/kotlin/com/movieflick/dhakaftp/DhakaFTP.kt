.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
     * Each tab is a real FTP root.
     *
     * IMPORTANT:
     * The home page does NOT only inspect the files directly
     * inside these roots. It recursively scans their folders,
     * so newly uploaded files inside newer/nested folders can
     * appear on the front page.
     */
    override val mainPage = mainPageOf(
        "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/" to
            "English Movies",
        "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/" to
            "Hindi Movies",
        "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/" to
            "Kolkata Bangla Movies",
        "http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/" to
            "South Indian Hindi Dubbed",
        "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/" to
            "TV Web Series",
        "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/" to
            "K-Drama",
        "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/" to
            "Anime"
    )

    private val videoExtensions = setOf(
        ".mkv",
        ".mp4",
        ".webm",
        ".avi",
        ".mov",
        ".m4v"
    )

    /*
     * Small in-memory cache.
     *
     * FTP directories can be large. A short cache keeps the
     * CloudStream home page responsive while still allowing
     * newly uploaded content to appear regularly.
     */
    private data class CacheEntry(
        val expiresAt: Long,
        val videos: List<FtpVideo>
    )

    private data class DirectoryNode(
        val url: String,
        val modifiedAt: Long?,
        val depth: Int
    )

    private data class FtpEntry(
        val name: String,
        val url: String,
        val isVideo: Boolean,
        val isDirectory: Boolean,
        val modifiedAt: Long?,
        val order: Long
    )

    private data class FtpVideo(
        val title: String,
        val url: String,
        val modifiedAt: Long,
        val order: Long
    )

    private val latestCache =
        mutableMapOf<String, CacheEntry>()

    private val cacheLock = Any()

    private val cacheDurationMs =
        TimeUnit.MINUTES.toMillis(2)

    private val maxDirectoryDepth = 12
    private val maxDirectoriesToScan = 500
    private val maxFilesToCollect = 4000
    private val maxHomeResults = 40
    private val maxLoadEpisodes = 1000

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                emptyList(),
                false
            )
        }

        val videos = try {
            getLatestVideos(
                request.data,
                maxHomeResults
            )
        } catch (_: Exception) {
            emptyList()
        }

        val results =
            videos.map { video ->
                newMovieSearchResponse(
                    video.title,
                    video.url,
                    TvType.Movie
                )
            }

        return newHomePageResponse(
            request,
            results,
            false
        )
    }

    /*
     * SEARCH
     *
     * Search is performed against file names AND their full
     * folder path. Therefore a movie uploaded several folders
     * deep can still be found from the normal CloudStream search.
     *
     * Results are scored so an exact filename/title match comes
     * before a looser path match.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        if (page > 1) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val cleanQuery =
            query.trim()

        if (cleanQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val queryLower =
            cleanQuery.lowercase()

        val matches =
            mutableListOf<ScoredSearchResult>()

        for (root in mainPage) {
            if (matches.size >= 100) {
                break
            }

            val videos =
                try {
                    getLatestVideos(
                        root.data,
                        maxFilesToCollect
                    )
                } catch (_: Exception) {
                    emptyList()
                }

            for (video in videos) {
                val titleLower =
                    video.title.lowercase()

                val urlLower =
                    video.url.lowercase()

                val titleScore =
                    when {
                        titleLower == queryLower -> 1000
                        titleLower.startsWith(queryLower) -> 900
                        titleLower.contains(queryLower) -> 800
                        else -> 0
                    }

                val pathScore =
                    if (
                        titleScore == 0 &&
                        urlLower.contains(queryLower)
                    ) {
                        300
                    } else {
                        0
                    }

                val score =
                    titleScore +
                        pathScore +
                        latestBonus(video.modifiedAt)

                if (score > 0) {
                    matches.add(
                        ScoredSearchResult(
                            video = video,
                            score = score
                        )
                    )
                }
            }
        }

        val results =
            matches
                .distinctBy {
                    it.video.url
                }
                .sortedWith(
                    compareByDescending<ScoredSearchResult> {
                        it.score
                    }.thenByDescending {
                        it.video.modifiedAt
                    }.thenBy {
                        it.video.order
                    }
                )
                .take(50)
                .map { item ->
                    newMovieSearchResponse(
                        item.video.title,
                        item.video.url,
                        TvType.Movie
                    )
                }

        return newSearchResponseList(
            results,
            false
        )
    }

    private data class ScoredSearchResult(
        val video: FtpVideo,
        val score: Int
    )

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> {
        return search(
            query,
            1
        ).items
    }

    /*
     * LOAD
     *
     * A video URL opens directly.
     *
     * A folder URL is treated as a collection. We recursively
     * collect the videos inside it and expose them as episodes.
     * This is what makes arbitrary folder depth usable instead
     * of stopping at the first directory level.
     */
    override suspend fun load(
        url: String
    ): LoadResponse {

        if (isVideo(url)) {
            val title =
                getTitleFromUrl(url)

            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            )
        }

        val videos =
            try {
                getLatestVideos(
                    url,
                    maxLoadEpisodes
                )
            } catch (_: Exception) {
                emptyList()
            }

        if (videos.isNotEmpty()) {
            val episodes =
                videos.mapIndexed { index, video ->
                    newEpisode(
                        video.url
                    ) {
                        name = video.title
                        episode = index + 1
                        season = 1
                    }
                }

            return newTvSeriesLoadResponse(
                getFolderTitle(url),
                url,
                TvType.TvSeries,
                episodes
            )
        }

        /*
         * If a directory is empty/unreachable, still return a
         * valid load response instead of crashing CloudStream.
         */
        return newMovieLoadResponse(
            getFolderTitle(url),
            url,
            TvType.Movie,
            url
        )
    }

    /*
     * DIRECT PLAYBACK
     *
     * The FTP file URL is passed directly to ExoPlayer through
     * CloudStream. No proxy/server is added.
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

        val cleanUrl =
            data
                .substringBefore("?")
                .lowercase()

        val type =
            if (cleanUrl.endsWith(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

        val quality =
            when {
                "2160" in cleanUrl ||
                    "4k" in cleanUrl ->
                    Qualities.P2160.value

                "1440" in cleanUrl ->
                    Qualities.P1440.value

                "1080" in cleanUrl ->
                    Qualities.P1080.value

                "720" in cleanUrl ->
                    Qualities.P720.value

                "480" in cleanUrl ->
                    Qualities.P480.value

                "360" in cleanUrl ->
                    Qualities.P360.value

                "240" in cleanUrl ->
                    Qualities.P240.value

                "144" in cleanUrl ->
                    Qualities.P144.value

                else ->
                    Qualities.Unknown.value
            }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = data,
                type = type
            ) {
                this.referer = refererFor(data)
                this.quality = quality
            }
        )

        return true
    }

    /*
     * RECURSIVE FTP SCANNER
     *
     * We scan folders instead of assuming that the movie file
     * is directly under the category root.
     *
     * Directory order is retained as a final fallback because
     * some FTP web indexes do not expose timestamps. When a
     * timestamp is present in the directory listing, that
     * timestamp always wins.
     */
    private suspend fun getLatestVideos(
        rootUrl: String,
        limit: Int
    ): List<FtpVideo> {

        val normalizedRoot =
            normalizeDirectoryUrl(rootUrl)

        val now =
            System.currentTimeMillis()

        synchronized(cacheLock) {
            val cached =
                latestCache[normalizedRoot]

            if (
                cached != null &&
                cached.expiresAt > now &&
                cached.videos.isNotEmpty()
            ) {
                return cached.videos.take(limit)
            }
        }

        val queue =
            ArrayDeque<DirectoryNode>()

        val visitedDirectories =
            mutableSetOf<String>()

        val discoveredVideos =
            mutableListOf<FtpVideo>()

        queue.add(
            DirectoryNode(
                url = normalizedRoot,
                modifiedAt = null,
                depth = 0
            )
        )

        var directoryCount = 0
        var orderCounter = 0L

        while (
            queue.isNotEmpty() &&
            directoryCount < maxDirectoriesToScan &&
            discoveredVideos.size < maxFilesToCollect
        ) {
            val directory =
                queue.removeFirst()

            val directoryKey =
                normalizeDirectoryUrl(
                    directory.url
                )

            if (
                !visitedDirectories.add(
                    directoryKey
                )
            ) {
                continue
            }

            if (
                directory.depth >
                maxDirectoryDepth
            ) {
                continue
            }

            directoryCount++

            val entries =
                try {
                    getDirectoryEntries(
                        directoryKey
                    )
                } catch (_: Exception) {
                    emptyList()
                }

            for (entry in entries) {

                if (
                    discoveredVideos.size >=
                    maxFilesToCollect
                ) {
                    break
                }

                if (entry.isVideo) {
                    val effectiveModified =
                        entry.modifiedAt
                            ?: directory.modifiedAt
                            ?: 0L

                    discoveredVideos.add(
                        FtpVideo(
                            title =
                                cleanEntryTitle(
                                    entry.name,
                                    entry.url
                                ),
                            url = entry.url,
                            modifiedAt =
                                effectiveModified,
                            order =
                                orderCounter++
                        )
                    )

                    continue
                }

                if (
                    entry.isDirectory &&
                    directory.depth <
                    maxDirectoryDepth
                ) {
                    queue.addLast(
                        DirectoryNode(
                            url =
                                normalizeDirectoryUrl(
                                    entry.url
                                ),
                            modifiedAt =
                                entry.modifiedAt
                                    ?: directory.modifiedAt,
                            depth =
                                directory.depth + 1
                        )
                    )
                }
            }
        }

        /*
         * If the FTP server's directory listing does not expose
         * timestamps, ask the server for Last-Modified only for
         * files that still have no usable timestamp.
         *
         * This is intentionally limited so a very large FTP
         * library does not cause hundreds/thousands of HEAD
         * requests on every refresh.
         */
        val enriched =
            enrichMissingDates(
                discoveredVideos
            )

        val sorted =
            enriched
                .distinctBy {
                    it.url
                }
                .sortedWith(
                    compareByDescending<FtpVideo> {
                        it.modifiedAt
                    }.thenBy {
                        it.order
                    }
                )

        synchronized(cacheLock) {
            latestCache[normalizedRoot] =
                CacheEntry(
                    expiresAt =
                        System.currentTimeMillis() +
                            cacheDurationMs,
                    videos = sorted
                )

            /*
             * Prevent unbounded cache growth if many arbitrary
             * folder URLs are opened through search.
             */
            if (latestCache.size > 32) {
                val oldestKey =
                    latestCache.entries
                        .minByOrNull {
                            it.value.expiresAt
                        }
                        ?.key

                if (oldestKey != null) {
                    latestCache.remove(
                        oldestKey
                    )
                }
            }
        }

        return sorted.take(limit)
    }

    private suspend fun enrichMissingDates(
        videos: List<FtpVideo>
    ): List<FtpVideo> {

        if (videos.isEmpty()) {
            return videos
        }

        /*
         * Directory listing timestamps are normally enough.
         * Only unresolved entries are checked with HEAD.
         */
        val unresolved =
            videos
                .withIndex()
                .filter {
                    it.value.modifiedAt <= 0L
                }
                .take(250)

        if (unresolved.isEmpty()) {
            return videos
        }

        val updates =
            mutableMapOf<Int, Long>()

        for (item in unresolved) {
            val timestamp =
                try {
                    app.head(
                        item.value.url,
                        timeout = 3
                    ).headers["Last-Modified"]
                        ?.let {
                            parseHttpDate(it)
                        }
                } catch (_: Exception) {
                    null
                }

            if (timestamp != null) {
                updates[item.index] =
                    timestamp
            }
        }

        if (updates.isEmpty()) {
            return videos
        }

        return videos.mapIndexed { index, video ->
            val updated =
                updates[index]

            if (updated != null) {
                video.copy(
                    modifiedAt = updated
                )
            } else {
                video
            }
        }
    }

    private fun parseHttpDate(
        value: String
    ): Long? {

        val formats =
            listOf(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEE, dd-MMM-yyyy HH:mm:ss zzz",
                "EEE MMM dd HH:mm:ss yyyy"
            )

        for (pattern in formats) {
            try {
                val format =
                    SimpleDateFormat(
                        pattern,
                        Locale.US
                    )

                format.isLenient = false

                val parsed =
                    format.parse(value)

                if (parsed != null) {
                    return parsed.time
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    /*
     * DIRECTORY PARSER
     *
     * Dhaka FTP's public directory pages commonly expose a
     * "Last modified" column. We inspect the anchor's parent
     * row/cell text and support several common date formats.
     *
     * If no date can be extracted, the scanner falls back to
     * the HTTP Last-Modified header later.
     */
    private suspend fun getDirectoryEntries(
        url: String
    ): List<FtpEntry> {

        val response =
            app.get(url)

        val document =
            response.document

        val anchors =
            document.select(
                "a[href]"
            )

        val results =
            mutableListOf<FtpEntry>()

        var order = 0L

        for (element in anchors) {

            val href =
                element
                    .attr("href")
                    .trim()

            if (
                href.isBlank() ||
                href.startsWith(
                    "#"
                ) ||
                href.startsWith(
                    "javascript:",
                    ignoreCase = true
                ) ||
                href.startsWith(
                    "mailto:",
                    ignoreCase = true
                )
            ) {
                continue
            }

            val absolute =
                resolveUrl(
                    url,
                    href
                )

            if (absolute.isBlank()) {
                continue
            }

            val normalized =
                if (
                    isDirectory(
                        absolute
                    )
                ) {
                    normalizeDirectoryUrl(
                        absolute
                    )
                } else {
                    absolute
                }

            if (
                normalized.equals(
                    normalizeDirectoryUrl(url),
                    ignoreCase = true
                )
            ) {
                continue
            }

            /*
             * Skip parent-directory links.
             */
            if (
                isParentDirectoryLink(
                    href,
                    normalized,
                    url
                )
            ) {
                continue
            }

            val entryName =
                element
                    .text()
                    .trim()
                    .ifBlank {
                        getTitleFromUrl(
                            normalized
                        )
                    }

            val modifiedAt =
                findModifiedTime(
                    element
                )

            results.add(
                FtpEntry(
                    name = entryName,
                    url = normalized,
                    isVideo =
                        isVideo(normalized),
                    isDirectory =
                        isDirectory(normalized),
                    modifiedAt =
                        modifiedAt,
                    order =
                        order++
                )
            )
        }

        return results
    }

    private fun findModifiedTime(
        element: Element
    ): Long? {

        var current: Element? =
            element

        repeat(4) {
            val text =
                current
                    ?.text()
                    ?.trim()
                    .orEmpty()

            if (text.isNotBlank()) {
                val parsed =
                    parseListingDate(
                        text
                    )

                if (parsed != null) {
                    return parsed
                }
            }

            current =
                current?.parent()
        }

        return null
    }

    private fun parseListingDate(
        text: String
    ): Long? {

        val regexes =
            listOf(
                Regex(
                    """\b\d{4}-\d{2}-\d{2}\s+\d{1,2}:\d{2}(?::\d{2})?\b"""
                ),
                Regex(
                    """\b\d{1,2}/\d{1,2}/\d{4}\s+\d{1,2}:\d{2}(?::\d{2})?\b"""
                ),
                Regex(
                    """\b\d{1,2}-\d{1,2}-\d{4}\s+\d{1,2}:\d{2}(?::\d{2})?\b"""
                ),
                Regex(
                    """\b[A-Za-z]{3,9}\s+\d{1,2},\s+\d{4}\s+\d{1,2}:\d{2}(?::\d{2})?\b"""
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

        for (regex in regexes) {
            val match =
                regex.find(text)
                    ?: continue

            val dateText =
                match.value

            for (pattern in formats) {
                try {
                    val format =
                        SimpleDateFormat(
                            pattern,
                            Locale.US
                        )

                    format.isLenient = false

                    val parsed =
                        format.parse(
                            dateText
                        )

                    if (parsed != null) {
                        return parsed.time
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        }

        return null
    }

    private fun isParentDirectoryLink(
        href: String,
        absoluteUrl: String,
        currentUrl: String
    ): Boolean {

        val cleanHref =
            href
                .substringBefore("#")
                .trim()

        if (
            cleanHref == ".." ||
            cleanHref == "../" ||
            cleanHref.equals(
                "./",
                ignoreCase = true
            )
        ) {
            return true
        }

        val current =
            normalizeDirectoryUrl(
                currentUrl
            )

        val parent =
            normalizeDirectoryUrl(
                resolveUrl(
                    current,
                    "../"
                )
            )

        return absoluteUrl.equals(
            parent,
            ignoreCase = true
        )
    }

    private fun resolveUrl(
        baseUrl: String,
        href: String
    ): String {

        return try {
            URI(baseUrl)
                .resolve(href)
                .toString()
        } catch (_: Exception) {
            try {
                URI(
                    normalizeDirectoryUrl(
                        baseUrl
                    )
                )
                    .resolve(href)
                    .toString()
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun normalizeDirectoryUrl(
        url: String
    ): String {
        return if (
            url.endsWith("/")
        ) {
            url
        } else {
            "$url/"
        }
    }

    private fun cleanEntryTitle(
        rawName: String,
        url: String
    ): String {

        val cleaned =
            rawName
                .trim()
                .removeSuffix("/")

        if (
            cleaned.isNotBlank() &&
            cleaned != ".." &&
            cleaned != "."
        ) {
            return cleaned
        }

        return getTitleFromUrl(
            url
        )
    }

    private fun getTitleFromUrl(
        url: String
    ): String {

        val fileName =
            url
                .substringBefore("?")
                .trimEnd('/')
                .substringAfterLast('/')

        val withoutExtension =
            fileName.substringBeforeLast(
                ".",
                fileName
            )

        return try {
            URLDecoder.decode(
                withoutExtension,
                "UTF-8"
            )
        } catch (_: Exception) {
            withoutExtension
        }
    }

    private fun getFolderTitle(
        url: String
    ): String {

        val folderName =
            url
                .trimEnd('/')
                .substringAfterLast('/')

        if (
            folderName.isBlank()
        ) {
            return "DhakaFTP"
        }

        return try {
            URLDecoder.decode(
                folderName,
                "UTF-8"
            )
        } catch (_: Exception) {
            folderName
        }
    }

    private fun isVideo(
        url: String
    ): Boolean {

        val cleanUrl =
            url
                .substringBefore("?")
                .lowercase()

        return videoExtensions.any {
            cleanUrl.endsWith(it)
        }
    }

    private fun isDirectory(
        url: String
    ): Boolean {
        return url
            .substringBefore("?")
            .endsWith("/")
    }

    private fun refererFor(
        url: String
    ): String {

        return try {
            val uri =
                URI(url)

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

    private fun latestBonus(
        modifiedAt: Long
    ): Int {

        if (modifiedAt <= 0L) {
            return 0
        }

        val age =
            System.currentTimeMillis() -
                modifiedAt

        return when {
            age <=
                TimeUnit.DAYS.toMillis(1) ->
                80

            age <=
                TimeUnit.DAYS.toMillis(7) ->
                50

            age <=
                TimeUnit.DAYS.toMillis(30) ->
                20

            else ->
                0
        }
    }
}
