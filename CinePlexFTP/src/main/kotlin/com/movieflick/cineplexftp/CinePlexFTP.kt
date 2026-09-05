package com.movieflick.cineplexftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

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
     * HOME SECTIONS
     *
     * Movies -> Cine Plex's real "All Movies" feed.
     * The site renders the first 24 movies on category.php and then
     * appends more using load_more_movies.php?offset=24,48,72,...
     *
     * Other sections remain tied to their real Cine Plex categories.
     */
    /*
     * FIVE TOP-LEVEL SECTIONS
     *
     * Each section can merge several real Cine Plex pages into one
     * CloudStream home row.
     */
    override val mainPage = mainPageOf(
        "cineplex://movies" to "Movies",
        "cineplex://dual-audio" to "Dual Audio",
        "cineplex://hindi" to "Hindi",
        "cineplex://tv" to "TV Shows",
        "cineplex://anime" to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean,
        val sortTime: Long,
        val discoveryOrder: Long
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    private val mediaExtensions = setOf(
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

    private val maxHomeItems = 25

    /*
     * Verified from Cine Plex's own All Movies page JavaScript:
     * the first 24 cards are rendered in the page and the next request
     * starts at offset=24.
     */
    private companion object {
        const val ALL_MOVIES_BATCH_SIZE = 24
    }

    /*
     * Page requests use normal browser headers.
     *
     * IMPORTANT:
     * Media playback intentionally does NOT reuse this full header set.
     * The verified Cine Plex MP4 URL plays directly in Android Chrome,
     * so the safest CloudStream playback request is the exact source URL
     * with minimal metadata rather than an invented browser/CORS profile.
     */
    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        // Only use the alternate scheme for Cine Plex page loading.
        // We do NOT change the scheme of discovered media URLs.
        if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        } else if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        }

        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = pageHeaders(candidate)).document
            }.getOrNull()

            if (document != null) {
                return document
            }
        }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return when (request.data) {
            "cineplex://movies" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php",
                        "$mainUrl/category.php?category=Indian+Bangla",
                        "$mainUrl/category.php?category=Korean",
                        "$mainUrl/category.php?category=3D+Movies",
                        "$mainUrl/category.php?category=Bangla+Dubbed",
                        "$mainUrl/category.php?category=Bangla+Movies",
                        "$mainUrl/category.php?category=English"
                    )
                )
            }

            "cineplex://dual-audio" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Dual+Audio",
                        "$mainUrl/category.php?category=Hindi+Dubbed"
                    )
                )
            }

            "cineplex://hindi" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Hindi"
                    )
                )
            }

            "cineplex://tv" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/tvs.php"
                    )
                )
            }

            "cineplex://anime" -> {
                getMergedHomePage(
                    request = request,
                    page = page,
                    sources = listOf(
                        "$mainUrl/category.php?category=Anime",
                        "$mainUrl/category.php?category=Animation"
                    )
                )
            }

            else -> {
                val url = pageUrl(request.data, page)
                val document = getDocument(url)
                    ?: return newHomePageResponse(
                        request,
                        emptyList(),
                        false
                    )

                val items = parseItems(
                    document = document,
                    sourceUrl = url,
                    sectionName = request.name,
                    page = page
                ).take(maxHomeItems)

                newHomePageResponse(
                    request,
                    items.map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }
    }

    private suspend fun getMergedHomePage(
        request: MainPageRequest,
        page: Int,
        sources: List<String>
    ): HomePageResponse {
        val merged = linkedMapOf<String, SiteItem>()
        var anySourceHasNext = false

        for (source in sources) {
            val items = if (source.equals("$mainUrl/category.php", true)) {
                getAllMoviesItems(page, request.name)
            } else {
                getCategoryItems(
                    sourceUrl = source,
                    page = page,
                    sectionName = request.name
                )
            }

            items.forEach { item ->
                merged.putIfAbsent(item.url, item)
            }

            if (source.equals("$mainUrl/category.php", true)) {
                /*
                 * All Movies uses the site's lazy endpoint. If this page
                 * returned a full 24-item batch, another batch may exist.
                 */
                if (items.size >= ALL_MOVIES_BATCH_SIZE) {
                    anySourceHasNext = true
                }
            } else {
                val document = getDocument(pageUrl(source, page))
                if (document != null && hasNextPage(document, page)) {
                    anySourceHasNext = true
                }
            }
        }

        val responses = merged.values
            .take(maxHomeItems)
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            request,
            responses,
            anySourceHasNext
        )
    }

    private suspend fun getCategoryItems(
        sourceUrl: String,
        page: Int,
        sectionName: String
    ): List<SiteItem> {
        val url = pageUrl(sourceUrl, page)
        val document = getDocument(url) ?: return emptyList()

        return parseItems(
            document = document,
            sourceUrl = url,
            sectionName = sectionName,
            page = page
        )
    }

    private suspend fun getAllMoviesItems(
        page: Int,
        sectionName: String
    ): List<SiteItem> {
        val pageNumber = page.coerceAtLeast(1)

        if (pageNumber == 1) {
            val url = "$mainUrl/category.php"
            val document = getDocument(url) ?: return emptyList()

            return parseItems(
                document = document,
                sourceUrl = url,
                sectionName = sectionName,
                page = 1
            ).take(ALL_MOVIES_BATCH_SIZE)
        }

        val offset = (pageNumber - 1) * ALL_MOVIES_BATCH_SIZE
        val endpoint = "$mainUrl/load_more_movies.php?offset=$offset"

        val response = runCatching {
            app.get(
                endpoint,
                headers = pageHeaders("$mainUrl/category.php")
            )
        }.getOrNull() ?: return emptyList()

        val html = response.text.trim()
        if (html.isBlank()) return emptyList()

        /*
         * load_more_movies.php returns the same movie-card HTML fragment
         * that the site's JavaScript appends to #movieGrid.
         */
        val document = org.jsoup.Jsoup.parse(
            "<html><body>$html</body></html>",
            "$mainUrl/"
        )

        return parseItems(
            document = document,
            sourceUrl = "$mainUrl/category.php",
            sectionName = sectionName,
            page = pageNumber
        ).take(ALL_MOVIES_BATCH_SIZE)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        val native = linkedSetOf<SiteItem>()
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
        val candidates = listOf(
            "$mainUrl/search.php?q=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?query=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?search=$encoded${if (page > 1) "&page=$page" else ""}"
        )

        for (url in candidates) {
            val document = getDocument(url) ?: continue
            native += parseItems(document, url, "Search", page)
        }

        if (native.isNotEmpty()) {
            val ranked = rankSearchResults(q, native.toList())
            return newSearchResponseList(
                ranked.take(maxHomeItems).map { it.toSearchResponse() },
                false
            )
        }

        // Advanced fallback: scan the site's public category pages and rank
        // fuzzy/partial matches locally. This handles punctuation, spacing,
        // spelling differences and partial titles when search.php is strict.
        val fallback = linkedMapOf<String, SiteItem>()
        val sources = listOf(
            "$mainUrl/category.php",
            "$mainUrl/category.php?category=Indian+Bangla",
            "$mainUrl/category.php?category=Korean",
            "$mainUrl/category.php?category=3D+Movies",
            "$mainUrl/category.php?category=Bangla+Dubbed",
            "$mainUrl/category.php?category=Bangla+Movies",
            "$mainUrl/category.php?category=English",
            "$mainUrl/category.php?category=Dual+Audio",
            "$mainUrl/category.php?category=Hindi+Dubbed",
            "$mainUrl/category.php?category=Hindi",
            "$mainUrl/tvs.php",
            "$mainUrl/category.php?category=Anime",
            "$mainUrl/category.php?category=Animation"
        )

        sources.forEach { url ->
            getDocument(url)?.let { doc ->
                parseItems(doc, url, "Search", 1).forEach { item ->
                    fallback.putIfAbsent(item.url, item)
                }
            }
        }

        val ranked = rankSearchResults(q, fallback.values.toList())
        return newSearchResponseList(
            ranked.take(maxHomeItems).map { it.toSearchResponse() },
            false
        )
    }

    private fun normalizeSearchText(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("\u00B7", " ")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun compactSearchText(value: String): String =
        normalizeSearchText(value).replace(" ", "")

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
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a.contains(b) || b.contains(a)) {
            val minLen = minOf(a.length, b.length).toDouble()
            val maxLen = maxOf(a.length, b.length).toDouble()
            return 0.80 + 0.20 * (minLen / maxLen)
        }
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun searchScore(query: String, title: String): Double {
        val qn = normalizeSearchText(query)
        val tn = normalizeSearchText(title)
        if (qn.isBlank() || tn.isBlank()) return 0.0
        if (qn == tn) return 1.0
        if (tn.contains(qn)) return 0.97

        val qc = compactSearchText(query)
        val tc = compactSearchText(title)
        var score = similarity(qc, tc) * 0.55

        val qTokens = qn.split(' ').filter { it.length >= 2 }
        val tTokens = tn.split(' ').filter { it.length >= 2 }
        if (qTokens.isNotEmpty() && tTokens.isNotEmpty()) {
            val tokenScore = qTokens.map { qt ->
                tTokens.maxOfOrNull { tt -> similarity(qt, tt) } ?: 0.0
            }.average()
            score += tokenScore * 0.45
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun rankSearchResults(query: String, items: List<SiteItem>): List<SiteItem> {
        return items
            .map { it to searchScore(query, it.title) }
            .filter { it.second >= 0.34 }
            .sortedWith(compareByDescending<Pair<SiteItem, Double>> { it.second }.thenBy { it.first.title })
            .map { it.first }
    }

    override suspend fun load(url: String): LoadResponse {
        if (isMediaUrl(url)) {
            return newMovieLoadResponse(
                titleFromUrl(url),
                url,
                if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
                url
            )
        }

        val detailUrl = normalizeContentUrl(url)
        val document = getDocument(detailUrl)

        if (document == null) {
            return newMovieLoadResponse(
                titleFromUrl(url),
                detailUrl,
                if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
                detailUrl
            )
        }

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(url) }

        val poster = extractPoster(document, detailUrl)
        val series = isSeriesUrl(url) || looksLikeSeriesPage(document)

        if (series) {
            val episodes = parseEpisodes(document, detailUrl)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    detailUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        /*
         * IMPORTANT:
         * A Cine Plex All Movies card normally points to view.php?id=...
         * The actual full movie player is player.php?id=... .
         * Keep the detail page for metadata, but hand the player page to
         * loadLinks() as the canonical playback data.
         */
        val playbackUrl = toPlayerUrl(detailUrl) ?: detailUrl

        return newMovieLoadResponse(
            title,
            detailUrl,
            if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
            playbackUrl
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
        val input = data.trim()
        if (input.isBlank()) return false

        if (isMediaUrl(input)) {
            if (!isCinePlexFullMediaUrl(input)) return false
            emitMediaLink(input, callback)
            return true
        }

        /*
         * ALWAYS resolve through the real Cine Plex player page.
         * This is critical for All Movies because those cards commonly use
         * view.php?id=..., while the full movie source lives in player.php.
         */
        val playerUrl = toPlayerUrl(input) ?: input
        val response = runCatching {
            app.get(
                playerUrl,
                headers = pageHeaders(playerUrl) + mapOf(
                    "Cache-Control" to "no-cache, no-store, max-age=0",
                    "Pragma" to "no-cache"
                )
            )
        }.getOrNull() ?: return false

        /*
         * PRIMARY SOURCE ONLY:
         * Cine Plex exposes the complete movie as `const videoSrc = ...`
         * on its own player page. We take that exact URL, including md5 and
         * expires. A fresh request is made on every Play, so a changed token
         * is automatically picked up.
         */
        val playerCandidates = extractPlayerVideoSrcCandidates(
            response.text,
            playerUrl
        )
            .filter { isCinePlexFullMediaUrl(it) }
            .sortedByDescending { playerMediaScore(it) }

        val bestPlayerSource = playerCandidates.firstOrNull()
        if (bestPlayerSource != null) {
            emitMediaLink(bestPlayerSource, callback)
            return true
        }

        /*
         * SECONDARY SOURCE:
         * Some Cine Plex player revisions place the same full source directly
         * in <video>/<source> or player metadata. Only Cine Plex /v/m/ or
         * Cine Plex VOD media is accepted. YouTube, trailers, previews and
         * unrelated embeds are deliberately rejected.
         */
        val directSources = extractMediaUrls(
            response.document,
            response.text,
            playerUrl
        )
            .filter { isCinePlexFullMediaUrl(it) }
            .sortedByDescending { playerMediaScore(it) }

        val bestDirectSource = directSources.firstOrNull()
        if (bestDirectSource != null) {
            emitMediaLink(bestDirectSource, callback)
            return true
        }

        /*
         * One forced fresh reload. This handles cases where the first response
         * was cached upstream or returned an already-expired signed token.
         */
        val retryResponse = runCatching {
            app.get(
                playerUrl,
                headers = pageHeaders(playerUrl) + mapOf(
                    "Cache-Control" to "no-cache, no-store, max-age=0",
                    "Pragma" to "no-cache",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        }.getOrNull() ?: return false

        val retrySource = extractPlayerVideoSrcCandidates(
            retryResponse.text,
            playerUrl
        )
            .filter { isCinePlexFullMediaUrl(it) }
            .sortedByDescending { playerMediaScore(it) }
            .firstOrNull()

        if (retrySource != null) {
            emitMediaLink(retrySource, callback)
            return true
        }

        /*
         * DO NOT fall back to external iframes here.
         * Cine Plex pages may contain YouTube trailers, and CloudStream
         * extractors would otherwise expose those trailers as playable links.
         */
        return false
    }

    private fun normalizeContentUrl(url: String): String {
        val cleaned = cleanUrl(url)
        if (cleaned.isBlank()) return cleaned
        return if (
            cleaned.contains("view.php?id=", true) ||
            cleaned.contains("details.php?id=", true) ||
            cleaned.contains("movie.php?id=", true)
        ) {
            cleaned
        } else {
            cleaned
        }
    }

    private fun toPlayerUrl(url: String): String? {
        val cleaned = cleanUrl(url)
        if (cleaned.isBlank()) return null
        if (cleaned.contains("player.php", true)) return cleaned

        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
        val query = uri.rawQuery.orEmpty()
        if (query.isBlank()) return null

        val id = query.split('&')
            .firstOrNull {
                it.substringBefore('=').equals("id", true)
            }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val h = query.split('&')
            .firstOrNull {
                it.substringBefore('=').equals("h", true)
            }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }

        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "http"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: URI(mainUrl).host
        val base = "$scheme://$host"

        return "$base/player.php?id=$id" + if (h != null) "&h=$h" else ""
    }

    private fun extractPlayerVideoSrc(
        html: String,
        baseUrl: String
    ): String? {
        return extractPlayerVideoSrcCandidates(html, baseUrl)
            .filter { isCinePlexFullMediaUrl(it) }
            .maxByOrNull { playerMediaScore(it) }
    }

    private fun extractPlayerVideoSrcCandidates(
        html: String,
        baseUrl: String
    ): List<String> {
        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val found = linkedSetOf<String>()

        val patterns = listOf(
            Regex("""(?is)\b(?:const|let|var)?\s*videoSrc\s*=\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?is)[\"']videoSrc[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?is)\bvideoSrc\s*\+=\s*[\"']([^\"']+)[\"']""")
        )

        for (pattern in patterns) {
            pattern.findAll(cleaned).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (raw.isBlank()) return@forEach

                val resolved = absoluteUrl(raw, baseUrl)
                if (isCinePlexFullMediaUrl(resolved)) {
                    found.add(resolved)
                }
            }
        }

        return found.toList()
    }

    private fun isCinePlexFullMediaUrl(url: String): Boolean {
        val cleaned = cleanUrl(url)
        if (!isMediaUrl(cleaned)) return false

        val uri = runCatching { URI(cleaned) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()

        if (host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith(".youtu.be")) {
            return false
        }

        val cineplexHost = host == "cineplexbd.net" ||
            host.endsWith(".cineplexbd.net")

        if (!cineplexHost) return false

        /* The verified full-movie player path is /v/m/. */
        return path.startsWith("/v/m/") ||
            host == "vod.cineplexbd.net"
    }

    private fun playerMediaScore(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        var score = 0

        if ("preview" in lower) score -= 1000
        if ("trailer" in lower) score -= 1000
        if ("sample" in lower) score -= 900
        if ("clip" in lower) score -= 900
        if ("teaser" in lower) score -= 900

        if ("/v/m/" in lower) score += 1000
        if ("/movies/" in lower) score += 250
        if ("md5=" in lower) score += 100
        if ("expires=" in lower) score += 100

        if (lower.contains("2160") || lower.contains("4k")) score += 40
        else if (lower.contains("1440")) score += 35
        else if (lower.contains("1080")) score += 30
        else if (lower.contains("720")) score += 20
        else if (lower.contains("480")) score += 10

        score += minOf(url.length / 20, 30)
        return score
    }


    /*
     * Emit the Cine Plex source as a native CloudStream video link.
     *
     * This function is intentionally suspend because newExtractorLink(...)
     * is a suspend API in the current CloudStream runtime.
     */
    private suspend fun emitMediaLink(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = when {
            mediaUrl.contains(".m3u8", true) ->
                ExtractorLinkType.M3U8

            mediaUrl.contains(".mpd", true) ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

        val lower = mediaUrl.lowercase(Locale.ROOT)

        val quality = when {
            "2160" in lower || "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "480" in lower ->
                Qualities.P480.value

            "360" in lower ->
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
                this.quality = quality
            }
        )
    }

    private fun extractDirectMediaFromDownloads(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun addDownload(raw: String?) {
            if (raw.isNullOrBlank()) return

            val cleaned = cleanUrl(raw)

            if (!cleaned.contains("download.php", true)) return

            val query = runCatching {
                URI(absoluteUrl(cleaned, baseUrl)).rawQuery.orEmpty()
            }.getOrDefault("")

            val fileValue = query
                .split('&')
                .firstOrNull {
                    it.substringBefore('=')
                        .equals("file", ignoreCase = true)
                }
                ?.substringAfter('=', "")

            if (fileValue.isNullOrBlank()) return

            val decoded = runCatching {
                URLDecoder.decode(
                    fileValue,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()?.trim().orEmpty()

            if (decoded.isBlank()) return

            /*
             * Only recover a real media file path.
             * Example:
             * uploads/videos/1788517057_Neru_2023.mp4
             */
            if (!isMediaUrl("http://example.com/$decoded")) return

            val direct = if (
                decoded.startsWith("http://", true) ||
                decoded.startsWith("https://", true)
            ) {
                decoded
            } else {
                absoluteUrl(
                    if (decoded.startsWith("/")) decoded else "/$decoded",
                    baseUrl
                )
            }

            if (isMediaUrl(direct)) {
                found.add(direct)
            }
        }

        document.select(
            "a[href*='download.php'], " +
                "a.download-btn"
        ).forEach { anchor ->
            addDownload(anchor.attr("href"))
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val regex = Regex(
            """(?i)(?:https?://[^"'<>\s]+|(?:/|\.\.?/)?download\.php\?[^"'<>\s)]+)"""
        )

        regex.findAll(cleanedHtml).forEach { match ->
            addDownload(match.value)
        }

        return found.toList()
    }

    /*
     * Extract every real media URL exposed by the watch page.
     *
     * Supports:
     * - <video src>
     * - <source src>
     * - data-video / data-src / data-file / ...
     * - URLs embedded in JavaScript player objects
     * - direct media files such as .mp4, .mkv, .webm, .m3u8, and .mpd
     */
    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val value = cleanUrl(raw)
                .replace("\\x2F", "/")
                .trim()

            if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("javascript:", true)) {
                return
            }

            /*
             * IMPORTANT: Cine Plex uses plain relative paths such as
             * `uploads/videos/1788517057_Neru_2023.mp4` without a leading `/`.
             * Always resolve relative URLs against the watch-page URL instead
             * of requiring the path to begin with '/', './' or '../'.
             */
            val fixed = absoluteUrl(value, baseUrl)

            if (isCinePlexFullMediaUrl(fixed)) {
                found.add(fixed)
            }
        }

        /*
         * DOM first: this is the most trustworthy source because it matches
         * the website's actual <video>/<source> element.
         */
        document.select(
            "video[src], " +
                "video source[src], " +
                "source[src], " +
                "[src], " +
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
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-video"))
            add(element.attr("data-file"))
            add(element.attr("data-url"))
            add(element.attr("data-source"))
            add(element.attr("data-stream"))
            add(element.attr("data-file-url"))
            add(element.attr("data-video-url"))
            add(element.attr("data-playlist"))
            add(element.attr("data-manifest"))
            add(element.attr("href"))
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("&amp;", "&")

        /*
         * Direct media URL regex.
         */
        val directRegex = Regex(
            """(?i)(?:https?://[^"'<>\s\\]+|(?<![A-Za-z0-9_./-])(?:uploads/|videos/|media/|files/)[^"'<>\s\\]+)\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?"""
        )

        directRegex.findAll(cleanedHtml).forEach {
            add(it.value)
        }

        /*
         * Player object / JavaScript variable fallback.
         */
        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']"""
        )

        keyRegex.findAll(cleanedHtml).forEach {
            add(it.groupValues[1])
        }

        return found.toList()
    }

    /*
     * Parse Cine Plex cards.
     *
     * The site uses:
     *   <div class="movie-card" onclick="openMovie(994)">
     *   <div class="movie-card" onclick="openSeries(99)">
     *
     * Titles come from the card's visible title / heading / image alt,
     * and the poster comes from the card's image.
     */
    private fun parseItems(
        document: Document,
        sourceUrl: String,
        sectionName: String,
        page: Int
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        var order = page.toLong() * 1_000_000L

        val elements = document.select(
            ".movie-card, " +
                ".movie-grid .movie-card, " +
                "a[href], " +
                "[data-href], " +
                "[data-url], " +
                "[data-link], " +
                "[onclick]"
        )

        elements.forEach { element ->
            val rawCandidates = listOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-link"),
                element.attr("onclick")
            ).filter { it.isNotBlank() }

            val href = rawCandidates.asSequence()
                .mapNotNull { extractContentUrl(it) }
                .firstOrNull()
                ?: return@forEach

            val absolute = absoluteUrl(href, sourceUrl)

            if (!looksLikeContentLink(absolute)) {
                return@forEach
            }

            val card = findCard(element)

            val title = cleanTitle(
                extractCardTitle(element, card)
                    .ifBlank { titleFromUrl(absolute) }
            )

            if (title.isBlank() || isNavigationTitle(title)) {
                return@forEach
            }

            val series =
                isSeriesUrl(absolute) ||
                    sectionName.contains("TV", true) ||
                    absolute.contains("type=series", true) ||
                    absolute.contains("type=tv", true)

            if (!result.containsKey(absolute)) {
                result[absolute] = SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractPosterFromElement(
                        card,
                        sourceUrl
                    ),
                    isSeries = series,
                    sortTime = extractSortTime(
                        card,
                        element
                    ),
                    discoveryOrder = order++
                )
            }
        }

        val values = result.values.toList()

        /*
         * Preserve Cine Plex's own listing order.
         *
         * If the site exposes a real upload/created timestamp, use it.
         * Never treat a plain "2026" release year as upload time.
         */
        val hasRealTimestamp = values.any {
            it.sortTime > 0L
        }

        return if (hasRealTimestamp) {
            values.sortedWith(
                compareByDescending<SiteItem> { it.sortTime }
                    .thenBy { it.discoveryOrder }
            )
        } else {
            values.sortedBy {
                it.discoveryOrder
            }
        }
    }

    private fun extractSortTime(
        card: Element,
        anchor: Element
    ): Long {
        val values = listOf(
            card.attr("data-created"),
            card.attr("data-uploaded"),
            card.attr("data-upload-date"),
            card.attr("data-created-at"),
            card.attr("data-uploaded-at"),
            card.attr("data-published"),
            card.attr("data-published-at"),
            card.attr("datetime"),

            anchor.attr("data-created"),
            anchor.attr("data-uploaded"),
            anchor.attr("data-upload-date"),
            anchor.attr("data-created-at"),
            anchor.attr("data-uploaded-at"),
            anchor.attr("data-published"),
            anchor.attr("data-published-at"),
            anchor.attr("datetime"),

            card.selectFirst("time[datetime]")
                ?.attr("datetime")
        )
            .filterNotNull()
            .filter { it.isNotBlank() }

        for (value in values) {
            val clean = value.trim()

            if (Regex("""^\d{4}$""").matches(clean)) {
                continue
            }

            clean.toLongOrNull()?.let { number ->
                return if (number < 10_000_000_000L) {
                    number * 1000L
                } else {
                    number
                }
            }

            parseDate(clean)?.let {
                return it
            }
        }

        return 0L
    }

    private fun parseDate(
        value: String
    ): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",

            "dd-MM-yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "dd-MM-yyyy",

            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",

            "MMM dd, yyyy HH:mm:ss",
            "MMM dd, yyyy HH:mm",
            "MMM dd, yyyy"
        )

        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(
                    pattern,
                    Locale.ENGLISH
                ).parse(value)?.time
            }.getOrNull()

            if (parsed != null) {
                return parsed
            }
        }

        return null
    }

    private fun parseEpisodes(
        document: Document,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()

            if (!looksLikeEpisodeLink(href)) {
                return@forEach
            }

            val absolute = absoluteUrl(
                href,
                baseUrl
            )

            if (absolute == baseUrl) {
                return@forEach
            }

            val title = cleanTitle(
                anchor.text().ifBlank {
                    "Episode"
                }
            )

            result[absolute] = newEpisode(
                absolute
            ) {
                name = title
                season = 1
                episode = episodeNumber(
                    anchor,
                    title,
                    absolute
                )
            }
        }

        /*
         * If the series page itself exposes one direct file,
         * expose it as Episode 1.
         */
        if (result.isEmpty()) {
            val direct = extractMediaUrls(
                document,
                document.html(),
                baseUrl
            ).firstOrNull()

            if (direct != null) {
                result[direct] = newEpisode(
                    direct
                ) {
                    name = "Episode 1"
                    season = 1
                    episode = 1
                }
            }
        }

        return result.values.sortedBy {
            it.episode ?: Int.MAX_VALUE
        }
    }

    private fun findCard(
        anchor: Element
    ): Element {
        var current: Element? = anchor

        repeat(8) {
            val element = current ?: return@repeat

            val cls = element
                .className()
                .lowercase(Locale.ROOT)

            if (
                element.select("img").isNotEmpty() ||
                cls.contains("card") ||
                cls.contains("movie") ||
                cls.contains("item") ||
                cls.contains("poster")
            ) {
                return element
            }

            current = element.parent()
        }

        return anchor
    }

    private fun extractCardTitle(
        anchor: Element,
        card: Element
    ): String {
        val selectors = listOf(
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
            val text = card
                .selectFirst(selector)
                ?.text()
                ?.trim()
                .orEmpty()

            if (text.isNotBlank()) {
                return text
            }
        }

        val aria = anchor
            .attr("aria-label")
            .trim()

        if (aria.isNotBlank()) {
            return aria
        }

        val alt = card
            .selectFirst("img")
            ?.attr("alt")
            ?.trim()

        if (!alt.isNullOrBlank()) {
            return alt
        }

        return anchor.text().trim()
    }

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
        val videoPoster = element
            .select("video[poster], video[data-poster]")
            .asSequence()
            .mapNotNull {
                it.attr("poster")
                    .ifBlank {
                        it.attr("data-poster")
                    }
                    .takeIf(String::isNotBlank)
            }
            .map {
                absoluteUrl(
                    it,
                    pageUrl
                )
            }
            .firstOrNull()

        if (!videoPoster.isNullOrBlank()) {
            return videoPoster
        }

        val meta = element
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

        val images = element.select(
            "img[src], " +
                "img[data-src], " +
                "img[data-lazy-src], " +
                "img[data-original], " +
                "img[data-poster]"
        )

        val preferred = images.firstOrNull { image ->
            val all =
                "${imageSource(image)} " +
                    "${image.attr("alt")} " +
                    "${image.className()}"
                    .lowercase(Locale.ROOT)

            all.contains("poster") ||
                all.contains("cover") ||
                all.contains("thumb") ||
                all.contains("movie")
        } ?: images.firstOrNull()

        if (preferred != null) {
            imageSource(preferred)?.let {
                return absoluteUrl(
                    it,
                    pageUrl
                )
            }
        }

        val style = element
            .select("[style*=background]")
            .map { it.attr("style") }
            .firstOrNull {
                it.contains("url(", true)
            }

        if (!style.isNullOrBlank()) {
            Regex(
                """(?i)url\(\s*['"]?([^'")]+)['"]?\s*\)"""
            ).find(style)
                ?.groupValues
                ?.getOrNull(1)
                ?.let {
                    return absoluteUrl(
                        it,
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
            image.attr("data-poster"),
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        ).firstOrNull {
            it.isNotBlank()
        }
    }

    private fun looksLikeContentLink(
        href: String
    ): Boolean {
        val lower = href.lowercase(Locale.ROOT)

        if (
            lower.isBlank() ||
            lower.startsWith("#") ||
            lower.startsWith("javascript:")
        ) {
            return false
        }

        return lower.contains("view.php?id=") ||
            lower.contains("player.php?id=") ||
            lower.contains("movie.php") ||
            lower.contains("series.php") ||
            lower.contains("show.php") ||
            lower.contains("details.php") ||
            lower.contains("movie?id=") ||
            lower.contains("type=movie") ||
            lower.contains("type=series") ||
            lower.contains("type=tv")
    }

    private fun looksLikeEpisodeLink(
        href: String
    ): Boolean {
        val lower = href.lowercase(Locale.ROOT)

        return looksLikeContentLink(href) && (
            lower.contains("episode") ||
                lower.contains("ep=") ||
                lower.contains("episode=") ||
                lower.contains("season=") ||
                lower.contains("type=episode")
            )
    }

    private fun hasNextPage(
        document: Document,
        currentPage: Int
    ): Boolean {
        return document
            .select("a[href]")
            .any { anchor ->
                val text = anchor
                    .text()
                    .trim()
                    .lowercase(Locale.ROOT)

                val rel = anchor
                    .attr("rel")
                    .lowercase(Locale.ROOT)

                text == "next" ||
                    text.contains("next") ||
                    rel == "next" ||
                    anchor
                        .attr("aria-label")
                        .contains("next", true) ||
                    anchor
                        .attr("href")
                        .contains(
                            "page=${currentPage + 1}"
                        )
            }
    }

    private fun pageUrl(
        base: String,
        page: Int
    ): String {
        if (page <= 1) {
            return base
        }

        return if (base.contains("?")) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    private fun isSeriesUrl(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        return lower.contains("tvs.php") ||
            lower.contains("series.php") ||
            lower.contains("show.php") ||
            lower.contains("type=series") ||
            lower.contains("type=tv")
    }

    private fun isAnimeUrl(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("category=anime") ||
            lower.contains("category=animation") ||
            lower.contains("/anime")
    }

    private fun looksLikeSeriesPage(
        document: Document
    ): Boolean {
        val text = document
            .text()
            .lowercase(Locale.ROOT)

        return text.contains("season") &&
            text.contains("episode")
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url)
                .path
                .lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return mediaExtensions.any {
            path.endsWith(it)
        }
    }

    private fun titleFromUrl(
        url: String
    ): String {
        return runCatching {
            URI(url)
                .query
                ?.split('&')
                ?.firstOrNull {
                    it.startsWith("id=")
                }
                ?.substringAfter('=')
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "Cine Plex"
    }

    private fun cleanUrl(
        raw: String
    ): String {
        return raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'', '`')
            .trimEnd(',', ';', ')', ']', '}')
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(
                Regex("\\s+"),
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
            .lowercase(Locale.ROOT) in
            setOf(
                "home",
                "movies",
                "tv shows",
                "tv series",
                "live tv",
                "search",
                "genres",
                "software",
                "request",
                "next",
                "previous"
            )
    }

    private fun extractContentUrl(
        raw: String
    ): String? {
        if (raw.isBlank()) {
            return null
        }

        val cleaned = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()

        val patterns = listOf(
            Regex("""(?i)https?://[^"'<>\s)]+"""),
            Regex("""(?i)(?:/|\.\.?/)?(?:view|player)\.php\?[^"'<>\s)]+"""),
            Regex("""(?i)(?:/|\.\.?/)?(?:movie|series|show|details)\.php\?[^"'<>\s)]+""")
        )

        for (pattern in patterns) {
            val match = pattern
                .find(cleaned)
                ?.value
                ?: continue

            return match
                .trim(
                    ',',
                    ';',
                    ')',
                    ']',
                    '}',
                    '"',
                    '\''
                )
                .replace("&amp;", "&")
        }

        return null
    }

    private fun absoluteUrl(
        raw: String,
        base: String
    ): String {
        val value = raw.trim()

        if (value.startsWith("//")) {
            val scheme = runCatching {
                URI(base).scheme
            }.getOrNull() ?: "http"

            return "$scheme:$value"
        }

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrElse {
            value
        }
    }

    private fun extractPageTitle(
        document: Document
    ): String {
        val selectors = listOf(
            "h1",
            "h2",
            ".movie-title",
            ".movie_name",
            ".title",
            "meta[property=og:title]"
        )

        for (selector in selectors) {
            val element = document
                .selectFirst(selector)
                ?: continue

            val text =
                if (element.tagName() == "meta") {
                    element.attr("content")
                } else {
                    element.text()
                }

            if (text.isNotBlank()) {
                return cleanTitle(text)
            }
        }

        return cleanTitle(
            document.title()
        )
    }

    private fun episodeNumber(
        anchor: Element,
        title: String,
        url: String
    ): Int {
        val candidates = listOf(
            Regex(
                "(?i)episode\\s*([0-9]+)"
            ).find(title)
                ?.groupValues
                ?.getOrNull(1),

            Regex(
                "(?i)\\bep\\s*([0-9]+)"
            ).find(title)
                ?.groupValues
                ?.getOrNull(1),

            Regex(
                "(?i)(?:episode|ep)=([0-9]+)"
            ).find(url)
                ?.groupValues
                ?.getOrNull(1),

            anchor
                .attr("data-episode")
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
}
