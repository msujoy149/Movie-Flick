package com.movieflick.cineplexftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class CinePlexFTP : MainAPI() {

    override var mainUrl = "http://cineplexbd.net"

    override var name = "Cine Plex FTP"

    override var lang = "bn"

    override val hasMainPage = true

    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * ============================================================
     * HOME MENU
     * ============================================================
     *
     * CloudStream will show only:
     *
     * Movies
     * Dual Audio
     * Hindi
     * TV Shows
     * Anime
     *
     * The website categories inside each section are merged.
     */

    override val mainPage = mainPageOf(

        /*
         * MOVIES
         *
         * Main Movies
         * Indian Bangla
         * Korean
         * 3D Movies
         * Bangla Dubbed
         * Bangla Movies
         * English
         */
        "$mainUrl/movies" to "Movies",

        /*
         * DUAL AUDIO
         *
         * Dual Audio
         * Hindi Dubbed
         */
        "$mainUrl/category.php?category=Dual+Audio" to "Dual Audio",

        /*
         * HINDI
         */
        "$mainUrl/category.php?category=Hindi" to "Hindi",

        /*
         * TV SHOWS
         */
        "$mainUrl/tvs.php" to "TV Shows",

        /*
         * ANIME
         *
         * Anime + Animation are merged.
         *
         * The special "anime" data marker is handled in
         * getMainPage().
         */
        "cineplex://anime" to "Anime"
    )

    /*
     * ============================================================
     * MERGED MOVIE CATEGORY SOURCES
     * ============================================================
     */

    private val movieSources = listOf(
        "$mainUrl/movies",
        "$mainUrl/category.php?category=Indian+Bangla",
        "$mainUrl/category.php?category=Korean",
        "$mainUrl/category.php?category=3D+Movies",
        "$mainUrl/category.php?category=Bangla+Dubbed",
        "$mainUrl/category.php?category=Bangla+Movies",
        "$mainUrl/category.php?category=English"
    )

    /*
     * ============================================================
     * MERGED DUAL AUDIO SOURCES
     * ============================================================
     */

    private val dualAudioSources = listOf(
        "$mainUrl/category.php?category=Dual+Audio",
        "$mainUrl/category.php?category=Hindi+Dubbed"
    )

    /*
     * ============================================================
     * MERGED ANIME SOURCES
     * ============================================================
     */

    private val animeSources = listOf(
        "$mainUrl/category.php?category=Anime",
        "$mainUrl/category.php?category=Animation"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean,
        val category: String = ""
    )

    /*
     * ============================================================
     * SEARCH RESPONSE
     * ============================================================
     */

    private fun SiteItem.toSearchResponse(): SearchResponse {

        return if (isSeries) {

            newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * ============================================================
     * PAGE HEADERS
     * ============================================================
     */

    private fun pageHeaders(
        referer: String = "$mainUrl/"
    ): Map<String, String> {

        return mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                    "AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36",

            "Accept" to
                "text/html,application/xhtml+xml," +
                    "application/xml;q=0.9,*/*;q=0.8",

            "Accept-Language" to
                "en-US,en;q=0.9",

            "Referer" to referer
        )
    }

    /*
     * ============================================================
     * DOCUMENT LOADER
     * ============================================================
     */

    private suspend fun getDocument(
        url: String
    ): Document? {

        val normalized = url.trim()

        if (normalized.isBlank()) {
            return null
        }

        val candidates = linkedSetOf<String>()

        candidates.add(normalized)

        /*
         * Cine Plex currently uses HTTP.
         * HTTPS is kept only as a fallback for page loading.
         */
        if (normalized.startsWith("http://", true)) {

            candidates.add(
                "https://" +
                    normalized.removePrefix("http://")
            )

        } else if (normalized.startsWith("https://", true)) {

            candidates.add(
                "http://" +
                    normalized.removePrefix("https://")
            )
        }

        for (candidate in candidates) {

            val document = runCatching {

                app.get(
                    candidate,
                    headers = pageHeaders(candidate)
                ).document

            }.getOrNull()

            if (document != null) {
                return document
            }
        }

        return null
    }

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val source = request.data

        /*
         * Anime is a special merged section.
         */
        if (source == "cineplex://anime") {

            val merged = collectSources(
                animeSources,
                page
            )

            return newHomePageResponse(
                request,
                merged
                    .distinctBy { it.url }
                    .take(MAX_HOME_ITEMS)
                    .map { it.toSearchResponse() },
                false
            )
        }

        /*
         * Movies section.
         */
        if (source == "$mainUrl/movies") {

            val merged = collectSources(
                movieSources,
                page
            )

            return newHomePageResponse(
                request,
                merged
                    .distinctBy { it.url }
                    .take(MAX_HOME_ITEMS)
                    .map { it.toSearchResponse() },
                false
            )
        }

        /*
         * Dual Audio section.
         */
        if (source == "$mainUrl/category.php?category=Dual+Audio") {

            val merged = collectSources(
                dualAudioSources,
                page
            )

            return newHomePageResponse(
                request,
                merged
                    .distinctBy { it.url }
                    .take(MAX_HOME_ITEMS)
                    .map { it.toSearchResponse() },
                false
            )
        }

        /*
         * Normal category / TV page.
         */

        val url = pageUrl(
            source,
            page
        )

        val document = getDocument(url)
            ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        val items = parseItems(
            document = document,
            sourceUrl = url,
            sectionName = request.name
        )

        return newHomePageResponse(
            request,
            items
                .take(MAX_HOME_ITEMS)
                .map { it.toSearchResponse() },
            false
        )
    }

    /*
     * ============================================================
     * COLLECT MULTIPLE CATEGORY SOURCES
     * ============================================================
     */

    private suspend fun collectSources(
        sources: List<String>,
        page: Int
    ): List<SiteItem> {

        val result = linkedMapOf<String, SiteItem>()

        for (source in sources) {

            /*
             * To prevent an unnecessarily heavy request,
             * only the requested page is fetched for each source.
             */
            val url = pageUrl(
                source,
                page
            )

            val document = getDocument(url)
                ?: continue

            val items = parseItems(
                document = document,
                sourceUrl = url,
                sectionName = ""
            )

            for (item in items) {

                if (!result.containsKey(item.url)) {

                    result[item.url] =
                        item
                }
            }
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * ADVANCED SEARCH
     * ============================================================
     *
     * Search is designed in layers:
     *
     * 1. Cine Plex native search
     * 2. alternate search parameter fallback
     * 3. fuzzy scoring
     * 4. duplicate removal
     *
     * Matching tolerates:
     *
     * - case difference
     * - punctuation difference
     * - extra/missing spaces
     * - partial words
     * - token overlap
     * - character similarity
     * - typo-like variations
     */

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val cleanQuery = query.trim()

        if (cleanQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        /*
         * Page > 1 currently uses the same search endpoint
         * because the provided Cine Plex source does not expose
         * a confirmed search pagination parameter.
         */
        if (page > 1) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val encoded = URLEncoder.encode(
            cleanQuery,
            StandardCharsets.UTF_8.toString()
        )

        val searchUrls = listOf(
            "$mainUrl/search.php?q=$encoded",
            "$mainUrl/search.php?query=$encoded",
            "$mainUrl/search.php?search=$encoded"
        )

        val candidates = linkedMapOf<String, SiteItem>()

        for (searchUrl in searchUrls) {

            val document = getDocument(searchUrl)
                ?: continue

            val items = parseItems(
                document = document,
                sourceUrl = searchUrl,
                sectionName = "Search"
            )

            for (item in items) {

                if (!candidates.containsKey(item.url)) {

                    candidates[item.url] = item
                }
            }

            /*
             * One successful search endpoint is enough.
             */
            if (items.isNotEmpty()) {
                break
            }
        }

        /*
         * If native search produces nothing, do a lightweight
         * cross-category fallback.
         */
        if (candidates.isEmpty()) {

            val fallbackSources =
                (
                    movieSources +
                        dualAudioSources +
                        listOf("$mainUrl/category.php?category=Hindi") +
                        animeSources +
                        listOf("$mainUrl/tvs.php")
                    ).distinct()

            for (source in fallbackSources) {

                val document = getDocument(source)
                    ?: continue

                val items = parseItems(
                    document = document,
                    sourceUrl = source,
                    sectionName = ""
                )

                for (item in items) {

                    if (!candidates.containsKey(item.url)) {
                        candidates[item.url] = item
                    }
                }
            }
        }

        /*
         * Advanced fuzzy ranking.
         */
        val ranked = candidates
            .values
            .map { item ->

                val score = fuzzyScore(
                    cleanQuery,
                    item.title
                )

                item to score
            }
            .filter {
                it.second >= SEARCH_MIN_SCORE
            }
            .sortedByDescending {
                it.second
            }
            .map {
                it.first
            }
            .take(MAX_SEARCH_RESULTS)

        return newSearchResponseList(
            ranked.map {
                it.toSearchResponse()
            },
            false
        )
    }

    /*
     * ============================================================
     * LOAD
     * ============================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse {

        val input = url.trim()

        if (input.isBlank()) {

            return newMovieLoadResponse(
                "Cine Plex",
                input,
                TvType.Movie,
                input
            )
        }

        /*
         * Direct video URL.
         */
        if (isMediaUrl(input)) {

            return newMovieLoadResponse(
                titleFromMediaUrl(input),
                input,
                TvType.Movie,
                input
            )
        }

        val document = getDocument(input)

        if (document == null) {

            return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                TvType.Movie,
                input
            )
        }

        val title =
            extractPageTitle(document)
                .ifBlank {
                    titleFromUrl(input)
                }

        val poster =
            extractPoster(
                document,
                input
            )

        /*
         * TV series.
         */
        if (isSeriesUrl(input) ||
            looksLikeSeriesPage(document)
        ) {

            val episodes =
                parseEpisodes(
                    document,
                    input
                )

            if (episodes.isNotEmpty()) {

                return newTvSeriesLoadResponse(
                    title,
                    input,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        /*
         * Movie.
         *
         * IMPORTANT:
         * The player page itself remains the canonical playback
         * page. loadLinks() will fetch it again at Play time.
         *
         * Therefore a temporary md5/expires URL is NOT cached here.
         */
        return newMovieLoadResponse(
            title,
            input,
            if (isAnimeUrl(input)) {
                TvType.Anime
            } else {
                TvType.Movie
            },
            input
        ) {
            posterUrl = poster
        }
    }

    /*
     * ============================================================
     * LOAD LINKS
     * ============================================================
     *
     * This is the most important Cine Plex part.
     *
     * The website generates a fresh:
     *
     * /v/m/...mp4?md5=XXXX&expires=XXXX
     *
     * inside the player page JavaScript.
     *
     * We fetch the player page NOW and extract the current
     * videoSrc instead of storing an old token.
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val input = data.trim()

        if (input.isBlank()) {
            return false
        }

        /*
         * Direct media URL.
         */
        if (isMediaUrl(input)) {

            emitMediaLink(
                input,
                input,
                callback
            )

            return true
        }

        /*
         * Fetch player page at Play time.
         */
        val response = runCatching {

            app.get(
                input,
                headers = pageHeaders(input)
            )

        }.getOrNull() ?: return false

        val html = response.text

        /*
         * Primary Cine Plex extraction:
         *
         * const videoSrc = "/v/m/...mp4?md5=...&expires=...";
         */
        val freshSources =
            extractCinePlexVideoSources(
                html = html,
                baseUrl = input
            )

        if (freshSources.isNotEmpty()) {

            freshSources
                .distinct()
                .forEach { mediaUrl ->

                    emitMediaLink(
                        mediaUrl = mediaUrl,
                        referer = input,
                        callback = callback
                    )
                }

            /*
             * Subtitles from the player page.
             */
            extractSubtitles(
                response.document,
                input
            ).forEach { subtitle ->

                subtitleCallback(
                    subtitle
                )
            }

            return true
        }

        /*
         * Secondary generic extraction.
         *
         * Useful if Cine Plex changes the JS variable name in
         * the future but still exposes the source via video/source.
         */
        val fallbackSources =
            extractMediaUrls(
                document = response.document,
                html = html,
                baseUrl = input
            )
                .distinct()

        if (fallbackSources.isNotEmpty()) {

            fallbackSources.forEach { mediaUrl ->

                emitMediaLink(
                    mediaUrl = mediaUrl,
                    referer = input,
                    callback = callback
                )
            }

            extractSubtitles(
                response.document,
                input
            ).forEach { subtitle ->

                subtitleCallback(
                    subtitle
                )
            }

            return true
        }

        /*
         * Final iframe fallback.
         */
        val iframes =
            response.document
                .select(
                    "iframe[src], iframe[data-src]"
                )
                .mapNotNull { iframe ->

                    val raw =
                        iframe.attr("src")
                            .ifBlank {
                                iframe.attr("data-src")
                            }
                            .trim()

                    if (raw.isBlank()) {
                        null
                    } else {
                        absoluteUrl(
                            raw,
                            input
                        )
                    }
                }
                .distinct()

        for (iframe in iframes) {

            val extracted =
                runCatching {

                    loadExtractor(
                        iframe,
                        subtitleCallback,
                        callback
                    )

                }.getOrDefault(false)

            if (extracted) {
                return true
            }
        }

        return false
    }

    /*
     * ============================================================
     * CINE PLEX VIDEO SOURCE EXTRACTION
     * ============================================================
     *
     * Current confirmed source:
     *
     * const videoSrc = "/v/m/...mp4?md5=...&expires=...";
     *
     * We intentionally capture the WHOLE URL including query.
     *
     * That is critical because md5/expires are part of the
     * temporary playable URL.
     */

    private fun extractCinePlexVideoSources(
        html: String,
        baseUrl: String
    ): List<String> {

        val found =
            linkedSetOf<String>()

        val cleaned =
            cleanHtml(
                html
            )

        /*
         * Exact current Cine Plex variable.
         */
        val videoSrcRegex =
            Regex(
                """(?is)\bvideoSrc\s*=\s*["']([^"']+)["']"""
            )

        videoSrcRegex
            .findAll(cleaned)
            .forEach { match ->

                val raw =
                    match.groupValues
                        .getOrNull(1)
                        .orEmpty()

                val absolute =
                    absoluteUrl(
                        decodeJsUrl(raw),
                        baseUrl
                    )

                if (isMediaUrl(absolute)) {

                    found.add(
                        absolute
                    )
                }
            }

        /*
         * HTML/JS escaped alternative.
         */
        if (found.isEmpty()) {

            val genericVideoSrcRegex =
                Regex(
                    """(?is)\b(?:videoSrc|video_url|videoUrl|mediaUrl|fileUrl)\s*[:=]\s*["']([^"']+)["']"""
                )

            genericVideoSrcRegex
                .findAll(cleaned)
                .forEach { match ->

                    val raw =
                        match.groupValues
                            .getOrNull(1)
                            .orEmpty()

                    val absolute =
                        absoluteUrl(
                            decodeJsUrl(raw),
                            baseUrl
                        )

                    if (isMediaUrl(absolute)) {

                        found.add(
                            absolute
                        )
                    }
                }
        }

        return found.toList()
    }

    /*
     * ============================================================
     * GENERIC MEDIA URL EXTRACTION
     * ============================================================
     */

    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {

        val found =
            linkedSetOf<String>()

        fun addCandidate(
            raw: String?
        ) {

            if (raw.isNullOrBlank()) {
                return
            }

            val cleaned =
                decodeJsUrl(
                    cleanUrl(raw)
                )

            if (
                cleaned.startsWith(
                    "data:",
                    true
                ) ||
                cleaned.startsWith(
                    "javascript:",
                    true
                )
            ) {
                return
            }

            val absolute =
                absoluteUrl(
                    cleaned,
                    baseUrl
                )

            if (isMediaUrl(absolute)) {

                found.add(
                    absolute
                )
            }
        }

        /*
         * DOM sources first.
         */
        document
            .select(
                "video[src], " +
                    "video source[src], " +
                    "source[src], " +
                    "[data-src], " +
                    "[data-video], " +
                    "[data-file], " +
                    "[data-url], " +
                    "[data-source], " +
                    "[data-stream], " +
                    "[data-file-url], " +
                    "[data-video-url], " +
                    "[data-playlist], " +
                    "[data-manifest]"
            )
            .forEach { element ->

                addCandidate(
                    element.attr("src")
                )

                addCandidate(
                    element.attr("data-src")
                )

                addCandidate(
                    element.attr("data-video")
                )

                addCandidate(
                    element.attr("data-file")
                )

                addCandidate(
                    element.attr("data-url")
                )

                addCandidate(
                    element.attr("data-source")
                )

                addCandidate(
                    element.attr("data-stream")
                )

                addCandidate(
                    element.attr("data-file-url")
                )

                addCandidate(
                    element.attr("data-video-url")
                )

                addCandidate(
                    element.attr("data-playlist")
                )

                addCandidate(
                    element.attr("data-manifest")
                )
            }

        val cleanedHtml =
            cleanHtml(html)

        /*
         * Direct media URL fallback.
         *
         * Includes query strings such as:
         *
         * ?md5=XXXX&expires=XXXX
         */
        val directRegex =
            Regex(
                """(?i)(?:https?://|/|\.{0,2}/)(?:[^"'<>\s\\]+)\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?"""
            )

        directRegex
            .findAll(cleanedHtml)
            .forEach { match ->

                addCandidate(
                    match.value
                )
            }

        /*
         * JavaScript player object fallback.
         */
        val keyRegex =
            Regex(
                """(?is)\b(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']"""
            )

        keyRegex
            .findAll(cleanedHtml)
            .forEach { match ->

                addCandidate(
                    match.groupValues[1]
                )
            }

        return found.toList()
    }

    /*
     * ============================================================
     * SUBTITLE EXTRACTION
     * ============================================================
     */

    private fun extractSubtitles(
        document: Document,
        baseUrl: String
    ): List<SubtitleFile> {

        val result =
            mutableListOf<SubtitleFile>()

        document
            .select(
                "track[src], track[data-src]"
            )
            .forEach { track ->

                val raw =
                    track.attr("src")
                        .ifBlank {
                            track.attr("data-src")
                        }
                        .trim()

                if (raw.isBlank()) {
                    return@forEach
                }

                val url =
                    absoluteUrl(
                        raw,
                        baseUrl
                    )

                val label =
                    track.attr("label")
                        .ifBlank {
                            track.attr("srclang")
                        }
                        .ifBlank {
                            "English"
                        }

                result.add(
                    SubtitleFile(
                        label,
                        url
                    )
                )
            }

        return result
    }

    /*
     * ============================================================
     * MEDIA LINK EMITTER
     * ============================================================
     */

    private fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val type =
            when {

                mediaUrl.contains(
                    ".m3u8",
                    true
                ) ->
                    ExtractorLinkType.M3U8

                mediaUrl.contains(
                    ".mpd",
                    true
                ) ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }

        val lower =
            mediaUrl.lowercase(
                Locale.ROOT
            )

        val quality =
            when {

                lower.contains("2160") ||
                    lower.contains("4k") ->
                    Qualities.P2160.value

                lower.contains("1440") ->
                    Qualities.P1440.value

                lower.contains("1080") ->
                    Qualities.P1080.value

                lower.contains("720") ->
                    Qualities.P720.value

                lower.contains("480") ->
                    Qualities.P480.value

                lower.contains("360") ->
                    Qualities.P360.value

                else ->
                    Qualities.Unknown.value
            }

        callback(
            newExtractorLink(
                source = name,
                name = "Cine Plex Direct",
                url = mediaUrl,
                type = type
            ) {

                this.referer = referer

                this.quality =
                    quality
            }
        )
    }

    /*
     * ============================================================
     * PARSE LISTING ITEMS
     * ============================================================
     *
     * Current Cine Plex cards use patterns such as:
     *
     * player.php?id=77031&h=
     * view.php?id=77414&h=
     *
     * and poster:
     *
     * /uploads/....
     *
     * title:
     *
     * img alt="Babu Shona"
     *
     * The source provided confirms this structure.
     */

    private fun parseItems(
        document: Document,
        sourceUrl: String,
        sectionName: String
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        val anchors =
            document.select(
                "a[href]"
            )

        for (anchor in anchors) {

            val href =
                anchor.attr(
                    "href"
                ).trim()

            if (href.isBlank()) {
                continue
            }

            /*
             * Ignore menus and external links.
             */
            if (
                !looksLikeContentLink(
                    href
                )
            ) {
                continue
            }

            val absolute =
                absoluteUrl(
                    cleanUrl(href),
                    sourceUrl
                )

            val card =
                findCard(anchor)

            val title =
                cleanTitle(
                    extractCardTitle(
                        anchor,
                        card
                    )
                )

            if (
                title.isBlank() ||
                isNavigationTitle(title)
            ) {
                continue
            }

            val poster =
                extractPosterFromElement(
                    card,
                    sourceUrl
                )

            /*
             * IMPORTANT:
             *
             * The player page is a better canonical playback
             * target than a temporary /v/m URL.
             *
             * Therefore, whenever a player.php link exists,
             * use that as the CloudStream data URL.
             */
            val playbackUrl =
                when {

                    absolute.contains(
                        "player.php",
                        true
                    ) ->
                        absolute

                    else -> {

                        val player =
                            extractPlayerIdUrl(
                                card,
                                sourceUrl
                            )

                        player ?: absolute
                    }
                }

            val series =
                isSeriesUrl(
                    playbackUrl
                ) ||
                    sectionName.contains(
                        "TV",
                        true
                    ) ||
                    sectionName.contains(
                        "Series",
                        true
                    ) ||
                    sourceUrl.contains(
                        "tvs.php",
                        true
                    ) ||
                    sourceUrl.contains(
                        "tcategory.php",
                        true
                    )

            if (
                !result.containsKey(
                    playbackUrl
                )
            ) {

                result[playbackUrl] =
                    SiteItem(
                        title = title,
                        url = playbackUrl,
                        poster = poster,
                        isSeries = series,
                        category = sectionName
                    )
            }
        }

        return result.values.toList()
    }

    /*
     * ============================================================
     * FIND PLAYER URL FROM CARD
     * ============================================================
     */

    private fun extractPlayerIdUrl(
        card: Element,
        baseUrl: String
    ): String? {

        val player =
            card
                .select("a[href*='player.php']")
                .firstOrNull()
                ?.attr("href")
                ?.trim()

        if (!player.isNullOrBlank()) {

            return absoluteUrl(
                cleanUrl(player),
                baseUrl
            )
        }

        return null
    }

    /*
     * ============================================================
     * FIND CARD CONTAINER
     * ============================================================
     */

    private fun findCard(
        anchor: Element
    ): Element {

        var current:
            Element? = anchor

        repeat(8) {

            val element =
                current
                    ?: return@repeat

            val className =
                element
                    .className()
                    .lowercase(
                        Locale.ROOT
                    )

            if (
                element.select("img")
                    .isNotEmpty() ||
                className.contains("card") ||
                className.contains("movie") ||
                className.contains("item") ||
                className.contains("poster")
            ) {

                return element
            }

            current =
                element.parent()
        }

        return anchor
    }

    /*
     * ============================================================
     * EXTRACT TITLE
     * ============================================================
     */

    private fun extractCardTitle(
        anchor: Element,
        card: Element
    ): String {

        val selectors =
            listOf(
                ".title",
                ".movie-title",
                ".movie_name",
                ".name",
                "h1",
                "h2",
                "h3",
                "h4",
                "strong"
            )

        for (selector in selectors) {

            val value =
                card
                    .selectFirst(
                        selector
                    )
                    ?.text()
                    ?.trim()
                    .orEmpty()

            if (value.isNotBlank()) {
                return value
            }
        }

        val aria =
            anchor
                .attr("aria-label")
                .trim()

        if (aria.isNotBlank()) {
            return aria
        }

        val alt =
            card
                .selectFirst("img")
                ?.attr("alt")
                ?.trim()

        if (!alt.isNullOrBlank()) {
            return alt
        }

        return anchor.text().trim()
    }

    /*
     * ============================================================
     * POSTER
     * ============================================================
     */

    private fun extractPoster(
        document: Document,
        pageUrl: String
    ): String? {

        return extractPosterFromElement(
            document,
            pageUrl
        )
    }

    private fun extractPosterFromElement(
        element: Element,
        pageUrl: String
    ): String? {

        /*
         * OG image.
         */
        val meta =
            element
                .selectFirst(
                    "meta[property=og:image], " +
                        "meta[name=twitter:image]"
                )
                ?.attr("content")
                ?.trim()

        if (!meta.isNullOrBlank()) {

            return absoluteUrl(
                meta,
                pageUrl
            )
        }

        /*
         * Standard Cine Plex poster.
         */
        val image =
            element
                .select(
                    "img[src], " +
                        "img[data-src], " +
                        "img[data-lazy-src], " +
                        "img[data-original], " +
                        "img[data-poster]"
                )
                .firstOrNull()

        if (image != null) {

            val source =
                imageSource(
                    image
                )

            if (!source.isNullOrBlank()) {

                return absoluteUrl(
                    source,
                    pageUrl
                )
            }
        }

        /*
         * Background image fallback.
         */
        val style =
            element
                .select("[style*=background]")
                .map {
                    it.attr("style")
                }
                .firstOrNull {
                    it.contains(
                        "url(",
                        true
                    )
                }

        if (!style.isNullOrBlank()) {

            val match =
                Regex(
                    """(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)"""
                )
                    .find(style)

            val value =
                match
                    ?.groupValues
                    ?.getOrNull(1)

            if (!value.isNullOrBlank()) {

                return absoluteUrl(
                    value,
                    pageUrl
                )
            }
        }

        return null
    }

    private fun imageSource(
        image: Element
    ): String? {

        return sequenceOf(

            image.attr(
                "data-poster"
            ),

            image.attr(
                "data-src"
            ),

            image.attr(
                "data-lazy-src"
            ),

            image.attr(
                "data-original"
            ),

            image.attr(
                "src"
            )

        ).firstOrNull {
            it.isNotBlank()
        }
    }

    /*
     * ============================================================
     * CONTENT LINK DETECTION
     * ============================================================
     */

    private fun looksLikeContentLink(
        href: String
    ): Boolean {

        val lower =
            href.lowercase(
                Locale.ROOT
            )

        if (
            lower.isBlank() ||
            lower.startsWith("#") ||
            lower.startsWith(
                "javascript:"
            ) ||
            lower.startsWith(
                "mailto:"
            )
        ) {
            return false
        }

        return lower.contains(
            "player.php"
        ) ||
            lower.contains(
                "view.php"
            ) ||
            lower.contains(
                "movie.php"
            ) ||
            lower.contains(
                "series.php"
            ) ||
            lower.contains(
                "show.php"
            ) ||
            lower.contains(
                "watch.php"
            ) ||
            lower.contains(
                "details.php"
            ) ||
            lower.contains(
                "type=movie"
            ) ||
            lower.contains(
                "type=series"
            ) ||
            lower.contains(
                "type=tv"
            )
    }

    /*
     * ============================================================
     * EPISODES
     * ============================================================
     */

    private fun parseEpisodes(
        document: Document,
        baseUrl: String
    ): List<Episode> {

        val result =
            linkedMapOf<String, Episode>()

        document
            .select("a[href]")
            .forEach { anchor ->

                val href =
                    anchor.attr(
                        "href"
                    )
                        .trim()

                if (href.isBlank()) {
                    return@forEach
                }

                if (
                    !looksLikeEpisodeLink(
                        href
                    )
                ) {
                    return@forEach
                }

                val absolute =
                    absoluteUrl(
                        href,
                        baseUrl
                    )

                val title =
                    cleanTitle(
                        anchor.text()
                            .ifBlank {
                                "Episode"
                            }
                    )

                result[absolute] =
                    newEpisode(
                        absolute
                    ) {

                        name = title

                        season = extractSeasonNumber(
                            anchor,
                            title,
                            absolute
                        )

                        episode =
                            extractEpisodeNumber(
                                anchor,
                                title,
                                absolute
                            )
                    }
            }

        /*
         * Some simple series pages may expose player links
         * without explicit episode text.
         */
        if (result.isEmpty()) {

            document
                .select(
                    "a[href*='player.php'], " +
                        "a[href*='view.php']"
                )
                .forEach { anchor ->

                    val href =
                        anchor.attr("href")
                            .trim()

                    if (href.isBlank()) {
                        return@forEach
                    }

                    val absolute =
                        absoluteUrl(
                            href,
                            baseUrl
                        )

                    val title =
                        cleanTitle(
                            anchor.text()
                                .ifBlank {
                                    "Episode"
                                }
                        )

                    result[absolute] =
                        newEpisode(
                            absolute
                        ) {

                            name = title

                            season = 1

                            episode =
                                extractEpisodeNumber(
                                    anchor,
                                    title,
                                    absolute
                                )
                        }
                }
        }

        return result.values
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }.thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
            )
    }

    private fun looksLikeEpisodeLink(
        href: String
    ): Boolean {

        val lower =
            href.lowercase(
                Locale.ROOT
            )

        return looksLikeContentLink(href) &&
            (
                lower.contains(
                    "episode"
                ) ||
                    lower.contains(
                        "ep="
                    ) ||
                    lower.contains(
                        "episode="
                    ) ||
                    lower.contains(
                        "season="
                    ) ||
                    lower.contains(
                        "type=episode"
                    )
                )
    }

    private fun extractSeasonNumber(
        anchor: Element,
        title: String,
        url: String
    ): Int {

        val candidates =
            listOf(

                Regex(
                    """(?i)\bseason\s*([0-9]+)"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1),

                Regex(
                    """(?i)\bs\s*([0-9]+)\b"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1),

                Regex(
                    """(?i)\bseason\s*([0-9]+)"""
                )
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1),

                anchor.attr(
                    "data-season"
                )
                    .takeIf {
                        it.isNotBlank()
                    }
            )

        return candidates
            .firstNotNullOfOrNull {
                it?.toIntOrNull()
            }
            ?: 1
    }

    private fun extractEpisodeNumber(
        anchor: Element,
        title: String,
        url: String
    ): Int {

        val candidates =
            listOf(

                Regex(
                    """(?i)\bepisode\s*([0-9]+)"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1),

                Regex(
                    """(?i)\bep\s*([0-9]+)"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1),

                Regex(
                    """(?i)\be\s*([0-9]+)\b"""
                )
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1),

                Regex(
                    """(?i)(?:episode|ep)=([0-9]+)"""
                )
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1),

                anchor.attr(
                    "data-episode"
                )
                    .takeIf {
                        it.isNotBlank()
                    }
            )

        return candidates
            .firstNotNullOfOrNull {
                it?.toIntOrNull()
            }
            ?: 1
    }

    /*
     * ============================================================
     * PAGE TITLE
     * ============================================================
     */

    private fun extractPageTitle(
        document: Document
    ): String {

        val selectors =
            listOf(
                "h1",
                "h2",
                ".movie-title",
                ".movie_name",
                ".title",
                "meta[property=og:title]"
            )

        for (selector in selectors) {

            val element =
                document.selectFirst(
                    selector
                )
                    ?: continue

            val text =
                if (
                    element.tagName()
                        .equals(
                            "meta",
                            true
                        )
                ) {
                    element.attr(
                        "content"
                    )
                } else {
                    element.text()
                }

            if (text.isNotBlank()) {

                return cleanTitle(
                    text
                )
            }
        }

        return cleanTitle(
            document.title()
        )
    }

    /*
     * ============================================================
     * URL HELPERS
     * ============================================================
     */

    private fun absoluteUrl(
        raw: String,
        base: String
    ): String {

        val value =
            raw.trim()

        if (value.isBlank()) {
            return value
        }

        if (
            value.startsWith(
                "//"
            )
        ) {

            val scheme =
                runCatching {
                    URI(base)
                        .scheme
                }
                    .getOrNull()
                    ?: "http"

            return "$scheme:$value"
        }

        if (
            value.startsWith(
                "http://",
                true
            ) ||
            value.startsWith(
                "https://",
                true
            )
        ) {
            return value
        }

        return runCatching {

            URI(base)
                .resolve(value)
                .toString()

        }.getOrElse {

            if (value.startsWith("/")) {
                "$mainUrl$value"
            } else {
                "$mainUrl/$value"
            }
        }
    }

    private fun pageUrl(
        base: String,
        page: Int
    ): String {

        if (page <= 1) {
            return base
        }

        return if (
            base.contains("?")
        ) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    private fun cleanUrl(
        raw: String
    ): String {

        return raw
            .trim()
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "\\u0026",
                "&"
            )
            .replace(
                "&amp;",
                "&"
            )
            .trim(
                '"',
                '\'',
                '`'
            )
            .trimEnd(
                ',',
                ';',
                ')',
                ']',
                '}'
            )
    }

    private fun decodeJsUrl(
        raw: String
    ): String {

        var value =
            cleanUrl(raw)

        repeat(2) {

            value =
                runCatching {

                    URLDecoder.decode(
                        value,
                        StandardCharsets.UTF_8
                            .toString()
                    )

                }.getOrElse {
                    value
                }
        }

        return value
            .replace(
                "\\u0026",
                "&"
            )
            .replace(
                "\\u003F",
                "?"
            )
            .replace(
                "\\u003D",
                "="
            )
            .replace(
                "\\/",
                "/"
            )
    }

    private fun cleanHtml(
        html: String
    ): String {

        return html
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "\\u0026",
                "&"
            )
            .replace(
                "\\u003A",
                ":"
            )
            .replace(
                "\\u003F",
                "?"
            )
            .replace(
                "\\u003D",
                "="
            )
            .replace(
                "&amp;",
                "&"
            )
    }

    /*
     * ============================================================
     * MEDIA
     * ============================================================
     */

    private val mediaExtensions =
        setOf(
            ".m3u8",
            ".mpd",
            ".mp4",
            ".mkv",
            ".webm",
            ".mov",
            ".m4v",
            ".avi",
            ".flv",
            ".ts"
        )

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val path =
            runCatching {

                URI(url)
                    .path
                    .orEmpty()
                    .lowercase(
                        Locale.ROOT
                    )

            }.getOrElse {

                url.lowercase(
                    Locale.ROOT
                )
            }

        return mediaExtensions.any {
            path.endsWith(it)
        }
    }

    private fun titleFromMediaUrl(
        url: String
    ): String {

        val decoded =
            runCatching {

                URLDecoder.decode(
                    url,
                    StandardCharsets.UTF_8
                        .toString()
                )

            }.getOrElse {
                url
            }

        val file =
            runCatching {

                URI(decoded)
                    .path
                    .substringAfterLast('/')

            }.getOrElse {
                ""
            }

        if (file.isBlank()) {
            return "Cine Plex"
        }

        return cleanTitle(
            file
                .substringBeforeLast(
                    "."
                )
                .replace(
                    "_",
                    " "
                )
        )
    }

    private fun titleFromUrl(
        url: String
    ): String {

        val decoded =
            runCatching {

                URLDecoder.decode(
                    url,
                    StandardCharsets.UTF_8
                        .toString()
                )

            }.getOrElse {
                url
            }

        /*
         * Cine Plex player URL:
         *
         * player.php?id=77031
         *
         * The actual title is normally on the fetched page,
         * so this is only a fallback.
         */
        return cleanTitle(
            decoded
                .substringAfterLast("/")
                .substringBefore("?")
                .ifBlank {
                    "Cine Plex"
                }
        )
    }

    /*
     * ============================================================
     * SERIES / ANIME
     * ============================================================
     */

    private fun isSeriesUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return lower.contains(
            "tvs.php"
        ) ||
            lower.contains(
                "tcategory.php"
            ) ||
            lower.contains(
                "series.php"
            ) ||
            lower.contains(
                "show.php"
            ) ||
            lower.contains(
                "type=series"
            ) ||
            lower.contains(
                "type=tv"
            )
    }

    private fun isAnimeUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return lower.contains(
            "category=anime"
        ) ||
            lower.contains(
                "category=animation"
            ) ||
            lower.contains(
                "anime"
            ) ||
            lower.contains(
                "animation"
            )
    }

    private fun looksLikeSeriesPage(
        document: Document
    ): Boolean {

        val text =
            document.text()
                .lowercase(
                    Locale.ROOT
                )

        return (
            text.contains(
                "season"
            ) &&
                text.contains(
                    "episode"
                )
            )
    }

    /*
     * ============================================================
     * TITLE CLEANUP
     * ============================================================
     */

    private fun cleanTitle(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .replace(
                Regex(
                    "(?i)\\s*[-|•]+\\s*" +
                        "(watch|download|play)\\s*$"
                ),
                ""
            )
            .trim()
    }

    private fun isNavigationTitle(
        value: String
    ): Boolean {

        return value
            .lowercase(
                Locale.ROOT
            ) in setOf(

            "home",
            "movie",
            "movies",
            "tv",
            "tv shows",
            "tv series",
            "anime",
            "animation",
            "search",
            "software",
            "top watch",
            "download",
            "download now",
            "next",
            "previous"
        )
    }

    /*
     * ============================================================
     * ADVANCED FUZZY SEARCH ENGINE
     * ============================================================
     */

    private fun fuzzyScore(
        query: String,
        title: String
    ): Double {

        val q =
            normalizeSearchText(
                query
            )

        val t =
            normalizeSearchText(
                title
            )

        if (
            q.isBlank() ||
            t.isBlank()
        ) {
            return 0.0
        }

        /*
         * Exact normalized match.
         */
        if (q == t) {
            return 1.0
        }

        /*
         * Whole phrase containment.
         */
        if (t.contains(q)) {
            return 0.96
        }

        /*
         * Token-based similarity.
         */
        val qTokens =
            q.split(' ')
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val tTokens =
            t.split(' ')
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        var tokenScore =
            0.0

        if (
            qTokens.isNotEmpty() &&
            tTokens.isNotEmpty()
        ) {

            val scores =
                qTokens.map { queryToken ->

                    tTokens.maxOfOrNull { titleToken ->

                        tokenSimilarity(
                            queryToken,
                            titleToken
                        )

                    } ?: 0.0
                }

            tokenScore =
                scores.average()
        }

        /*
         * Full string edit similarity.
         */
        val fullSimilarity =
            normalizedLevenshteinSimilarity(
                q,
                t
            )

        /*
         * Compact string similarity.
         *
         * This helps cases such as:
         *
         * balanboy
         * balan boy
         */
        val compactQ =
            q.replace(
                " ",
                ""
            )

        val compactT =
            t.replace(
                " ",
                ""
            )

        val compactSimilarity =
            normalizedLevenshteinSimilarity(
                compactQ,
                compactT
            )

        /*
         * First-token / partial matching.
         */
        val partialTokenScore =
            partialTokenSimilarity(
                qTokens,
                tTokens
            )

        /*
         * Weighted final score.
         */
        return (
            tokenScore * 0.36 +
                partialTokenScore * 0.20 +
                fullSimilarity * 0.24 +
                compactSimilarity * 0.20
            )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun normalizeSearchText(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}]+"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    private fun tokenSimilarity(
        queryToken: String,
        titleToken: String
    ): Double {

        if (
            queryToken == titleToken
        ) {
            return 1.0
        }

        if (
            titleToken.contains(
                queryToken
            ) ||
            queryToken.contains(
                titleToken
            )
        ) {

            val shorter =
                min(
                    queryToken.length,
                    titleToken.length
                )

            val longer =
                max(
                    queryToken.length,
                    titleToken.length
                )

            if (longer == 0) {
                return 0.0
            }

            return (
                shorter.toDouble() /
                    longer.toDouble()
                ) * 0.95
        }

        return normalizedLevenshteinSimilarity(
            queryToken,
            titleToken
        )
    }

    private fun partialTokenSimilarity(
        queryTokens: List<String>,
        titleTokens: List<String>
    ): Double {

        if (
            queryTokens.isEmpty() ||
            titleTokens.isEmpty()
        ) {
            return 0.0
        }

        var matched =
            0.0

        for (queryToken in queryTokens) {

            val best =
                titleTokens.maxOfOrNull { titleToken ->

                    when {

                        titleToken.startsWith(
                            queryToken
                        ) ->
                            0.92

                        queryToken.startsWith(
                            titleToken
                        ) ->
                            0.88

                        titleToken.contains(
                            queryToken
                        ) ->
                            0.82

                        queryToken.contains(
                            titleToken
                        ) ->
                            0.80

                        else ->
                            normalizedLevenshteinSimilarity(
                                queryToken,
                                titleToken
                            )
                    }

                } ?: 0.0

            matched += best
        }

        return (
            matched /
                queryTokens.size.toDouble()
            )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun normalizedLevenshteinSimilarity(
        first: String,
        second: String
    ): Double {

        if (first == second) {
            return 1.0
        }

        if (first.isEmpty() || second.isEmpty()) {
            return 0.0
        }

        val distance =
            levenshteinDistance(
                first,
                second
            )

        val longest =
            max(
                first.length,
                second.length
            )

        return (
            1.0 -
                distance.toDouble() /
                    longest.toDouble()
            )
            .coerceIn(
                0.0,
                1.0
            )
    }

    private fun levenshteinDistance(
        a: String,
        b: String
    ): Int {

        if (a == b) {
            return 0
        }

        if (a.isEmpty()) {
            return b.length
        }

        if (b.isEmpty()) {
            return a.length
        }

        var previous =
            IntArray(
                b.length + 1
            ) { it }

        var current =
            IntArray(
                b.length + 1
            )

        for (i in 1..a.length) {

            current[0] = i

            for (j in 1..b.length) {

                val cost =
                    if (
                        a[i - 1] ==
                        b[j - 1]
                    ) {
                        0
                    } else {
                        1
                    }

                current[j] =
                    min(
                        min(
                            current[j - 1] + 1,
                            previous[j] + 1
                        ),
                        previous[j - 1] + cost
                    )
            }

            val swap =
                previous

            previous =
                current

            current =
                swap
        }

        return previous[b.length]
    }

    /*
     * ============================================================
     * CONSTANTS
     * ============================================================
     */

    companion object {

        private const val MAX_HOME_ITEMS =
            40

        private const val MAX_SEARCH_RESULTS =
            50

        /*
         * Lower than CTG's previously used threshold so that
         * typo/near-match searches have a better chance of
         * returning a useful result.
         */
        private const val SEARCH_MIN_SCORE =
            0.34
    }
}
