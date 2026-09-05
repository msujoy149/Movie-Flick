package com.movieflick.ctgftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * CTG FTP v5 — advanced fuzzy search + movie playback fix
 *
 * Movie playback:
 * detail -> watch -> serialized links[] -> actual media URL -> ExtractorLink
 *
 * Existing TV/Anime parsing and fallback playback paths are preserved.
 */
class CTGFTP : MainAPI() {

    override var mainUrl = "https://ctgmovies.com"
    override var name = "CTG FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * CTG FTP deliberately exposes only the three categories requested:
     * Movies, TV Shows and Anime.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tv" to "TV Shows",
        "$mainUrl/anime" to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    private val pageHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = getDocument(url)
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseItems(document, url)
            .take(30)

        return newHomePageResponse(
            request,
            items.map { it.toSearchResponse() },
            hasNextPage(document, page)
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        /*
         * Search strategy:
         *
         * 1. Keep CTG's native search first for speed.
         * 2. Try normalized query variants so punctuation differences such as
         *    "Balan: The Boy" vs "Balan - The Boy" do not block a result.
         * 3. If CTG's search still returns nothing useful, perform a local
         *    fuzzy search over the three existing provider categories.
         *
         * Nothing else in the provider is changed by this search fallback.
         */
        val normalizedQuery = normalizeSearchText(q)

        val queryVariants = linkedSetOf(
            q,
            q.replace(':', ' '),
            q.replace('-', ' '),
            q.replace('_', ' '),
            q.replace(':', ' ').replace('-', ' '),
            q.replace(Regex("""\s+"""), " ").trim()
        ).filter { it.isNotBlank() }

        val nativeResults = linkedMapOf<String, SearchResponse>()

        for (variant in queryVariants) {
            val encoded = URLEncoder.encode(
                variant,
                StandardCharsets.UTF_8.toString()
            )

            val candidates = listOf(
                "$mainUrl/search?q=$encoded${pageSuffix(page)}",
                "$mainUrl/search?query=$encoded${pageSuffix(page)}",
                "$mainUrl/search?search=$encoded${pageSuffix(page)}"
            ).distinct()

            for (url in candidates) {
                val document = getDocument(url) ?: continue
                val items = parseItems(document, url)

                items.forEach { item ->
                    nativeResults.putIfAbsent(
                        item.url,
                        item.toSearchResponse()
                    )
                }

                /*
                 * Prefer the site's native search if it gives a strong match.
                 * A normalized exact match is stronger than the raw site's
                 * punctuation-sensitive matching.
                 */
                val strongNative = items.any {
                    normalizeSearchText(it.title) == normalizedQuery ||
                        normalizeSearchText(it.title)
                            .contains(normalizedQuery) ||
                        normalizedQuery.contains(
                            normalizeSearchText(it.title)
                        )
                }

                if (strongNative) {
                    return newSearchResponseList(
                        nativeResults.values
                            .take(30)
                            .toList(),
                        hasNextPage(document, page)
                    )
                }
            }
        }

        if (nativeResults.isNotEmpty()) {
            return newSearchResponseList(
                rankSearchResponses(
                    query = q,
                    responses = nativeResults.values.toList()
                ).take(30),
                false
            )
        }

        /*
         * ================================================================
         * LOCAL FUZZY FALLBACK
         * ================================================================
         *
         * CTG's server-side search can be punctuation-sensitive/exact.
         * When that happens, scan the same three category pages already used
         * by the provider and rank their titles against the user's query.
         *
         * Examples that now match:
         *
         *   Balan: The Boy
         *   Balan - The Boy
         *   Balan The Boy
         *
         * as well as small spelling/word-order differences.
         */
        val allItems = linkedMapOf<String, SiteItem>()

        val categoryUrls = listOf(
            "$mainUrl/movies",
            "$mainUrl/tv",
            "$mainUrl/anime"
        )

        for (categoryUrl in categoryUrls) {
            val document = getDocument(
                pageUrl(categoryUrl, page)
            ) ?: continue

            parseItems(
                document,
                document.location().ifBlank { categoryUrl }
            ).forEach { item ->
                allItems.putIfAbsent(item.url, item)
            }
        }

        if (allItems.isEmpty()) {
            return newSearchResponseList(emptyList(), false)
        }

        val ranked = allItems.values
            .map { item ->
                SearchCandidate(
                    item = item,
                    score = searchScore(
                        query = q,
                        title = item.title
                    )
                )
            }
            .filter { it.score >= SEARCH_MIN_SCORE }
            .sortedWith(
                compareByDescending<SearchCandidate> { it.score }
                    .thenBy { it.item.title.length }
            )
            .take(30)

        return newSearchResponseList(
            ranked.map { it.item.toSearchResponse() },
            false
        )
    }

    private data class SearchCandidate(
        val item: SiteItem,
        val score: Double
    )

    private val SEARCH_MIN_SCORE = 0.38

    private fun normalizeSearchText(
        value: String
    ): String {
        return value
            .lowercase(Locale.ROOT)
            /*
             * Keep letters/digits/whitespace only. This deliberately makes
             * punctuation variations such as colon, hyphen, apostrophe and
             * brackets insignificant.
             */
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun searchTokens(
        value: String
    ): List<String> {
        return normalizeSearchText(value)
            .split(' ')
            .filter { it.length >= 2 }
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val qNorm = normalizeSearchText(query)
        val tNorm = normalizeSearchText(title)

        if (qNorm.isBlank() || tNorm.isBlank()) {
            return 0.0
        }

        if (qNorm == tNorm) {
            return 1.0
        }

        if (tNorm.contains(qNorm)) {
            return 0.96
        }

        if (qNorm.contains(tNorm)) {
            return 0.90
        }

        val qTokens = searchTokens(query).distinct()
        val tTokens = searchTokens(title).distinct()

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return 0.0
        }

        /*
         * Token overlap handles punctuation and word-order differences.
         * "Balan: The Boy" and "Balan - The Boy" therefore score very high.
         */
        val matchedTokens = qTokens.count { qToken ->
            tTokens.any { tToken ->
                tToken == qToken ||
                    tToken.startsWith(qToken) ||
                    qToken.startsWith(tToken) ||
                    normalizedLevenshtein(
                        qToken,
                        tToken
                    ) >= 0.78
            }
        }

        val overlap = matchedTokens.toDouble() /
            maxOf(qTokens.size, tTokens.size)

        /*
         * Character-level similarity catches small typos while remaining
         * conservative enough to avoid unrelated titles.
         */
        val characterSimilarity =
            normalizedLevenshtein(qNorm, tNorm)

        /*
         * Give more weight to token overlap because movie titles often differ
         * only by punctuation, subtitles, or small suffixes.
         */
        return (overlap * 0.65) +
            (characterSimilarity * 0.35)
    }

    private fun normalizedLevenshtein(
        first: String,
        second: String
    ): Double {
        if (first == second) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0

        var previous = IntArray(second.length + 1) {
            it
        }
        var current = IntArray(second.length + 1)

        for (i in first.indices) {
            current[0] = i + 1

            for (j in second.indices) {
                val cost = if (first[i] == second[j]) 0 else 1

                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        val distance = previous[second.length]
        val maxLength = maxOf(
            first.length,
            second.length
        )

        return 1.0 - (
            distance.toDouble() / maxLength.toDouble()
        )
    }

    private fun rankSearchResponses(
        query: String,
        responses: List<SearchResponse>
    ): List<SearchResponse> {
        val queryLower = normalizeSearchText(query)

        return responses
            .map { response ->
                val score = searchScore(
                    query = queryLower,
                    title = response.name
                )

                response to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = cleanUrl(url)

        if (isMediaUrl(clean)) {
            return newMovieLoadResponse(
                titleFromUrl(clean),
                clean,
                TvType.Movie,
                clean
            )
        }

        val document = getDocument(clean)
            ?: return newMovieLoadResponse(
                titleFromUrl(clean),
                clean,
                typeFromUrl(clean),
                clean
            )

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(clean) }

        val poster = extractPoster(document, clean)
        val plot = extractPlot(document)
        val year = extractYear(document)

        when (typeFromUrl(clean)) {
            TvType.TvSeries -> {
                val episodes = parseEpisodes(document, clean)

                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(
                        title,
                        clean,
                        TvType.TvSeries,
                        episodes
                    ) {
                        posterUrl = poster
                        this.plot = plot
                        this.year = year
                    }
                }
            }

            TvType.Anime -> {
                val episodes = parseEpisodes(document, clean)

                if (episodes.isNotEmpty()) {
                    return newAnimeLoadResponse(
                        title,
                        clean,
                        TvType.Anime
                    ) {
                        posterUrl = poster
                        this.plot = plot
                        this.year = year
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            }

            else -> Unit
        }

        /*
         * Movies use a two-step playback chain:
         *
         *   /movies/<slug>
         *       -> /watch/<id>?type=movie
         *       -> serialized CTG links[]
         *       -> actual media URL(s)
         *
         * Keep the movie detail URL as the CloudStream data. loadLinks()
         * resolves the current watch page and playback sources at Play time.
         * TV/Anime episode data remains unchanged above.
         */
        return newMovieLoadResponse(
            title,
            clean,
            typeFromUrl(clean),
            clean
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = cleanUrl(data)
        if (input.isBlank()) return false

        /*
         * Direct media:
         * Keep the existing behavior for TV/Anime/direct episode sources.
         */
        if (isMediaUrl(input)) {
            emitMediaLink(
                mediaUrl = input,
                referer = mainUrl,
                callback = callback
            )
            return true
        }

        /*
         * ============================================================
         * MOVIE-SPECIFIC PLAYBACK
         * ============================================================
         *
         * Movies on CTG are different from normal episode pages:
         *
         *   movie detail
         *       -> watch page
         *       -> Next.js serialized `links`
         *       -> link.url / link.hls_url
         *
         * The previous implementation fetched only the watch page and then
         * searched for normal HTML <source>/<video> URLs. CTG's actual movie
         * sources are serialized inside the Next.js payload, so that approach
         * can return zero sources even though the browser player has them.
         */
        val isMovieDetail = runCatching {
            URI(input).path.orEmpty().lowercase(Locale.ROOT)
                .startsWith("/movies/")
        }.getOrDefault(false)

        if (isMovieDetail) {
            val detailResponse = runCatching {
                app.get(
                    input,
                    headers = pageHeaders + ("Referer" to "$mainUrl/")
                )
            }.getOrNull()

            if (detailResponse != null) {
                val detailDocument = detailResponse.document

                val watchUrl = extractPlaybackPageUrl(
                    document = detailDocument,
                    baseUrl = input
                )

                if (!watchUrl.isNullOrBlank()) {
                    val watchResponse = runCatching {
                        app.get(
                            watchUrl,
                            headers = pageHeaders + ("Referer" to input)
                        )
                    }.getOrNull()

                    if (watchResponse != null) {
                        val ctgSources = extractCtgPlaybackLinks(
                            html = watchResponse.text,
                            baseUrl = watchUrl
                        )

                        if (ctgSources.isNotEmpty()) {
                            var emitted = false

                            ctgSources.forEach { source ->
                                val mediaUrl = source.url
                                if (!isMediaUrl(mediaUrl)) return@forEach

                                emitMediaLink(
                                    mediaUrl = mediaUrl,
                                    referer = watchUrl,
                                    qualityHint = source.quality,
                                    sourceName = source.sourceName,
                                    callback = callback
                                )
                                emitted = true
                            }

                            if (emitted) return true
                        }

                        /*
                         * Keep the existing generic fallback as a secondary path.
                         * This protects against a CTG markup change where a direct
                         * <video>/<source> suddenly becomes available again.
                         */
                        val fallbackSources = extractMediaUrls(
                            document = watchResponse.document,
                            html = watchResponse.text,
                            baseUrl = watchUrl
                        ).distinct()

                        if (fallbackSources.isNotEmpty()) {
                            fallbackSources.forEach { source ->
                                emitMediaLink(
                                    mediaUrl = source,
                                    referer = watchUrl,
                                    callback = callback
                                )
                            }
                            return true
                        }
                    }
                }
            }
        }

        /*
         * Existing generic playback path for TV/Anime and any non-movie page.
         */
        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        /*
         * Priority 1: explicit video/source/data-* values and direct media URLs.
         */
        val sources = extractMediaUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (sources.isNotEmpty()) {
            sources.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Priority 2: links such as Download/Server buttons whose query or
         * encoded value points to the actual media file.
         */
        val recovered = recoverPlayableUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (recovered.isNotEmpty()) {
            recovered.forEach { source ->
                emitMediaLink(
                    mediaUrl = source,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * Priority 3: embedded player fallback.
         */
        val iframes = document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                val raw = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .trim()

                raw.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(it, input) }
            }
            .distinct()

        for (iframe in iframes) {
            val loaded = runCatching {
                loadExtractor(
                    iframe,
                    subtitleCallback,
                    callback
                )
            }.getOrDefault(false)

            if (loaded) return true
        }

        return false
    }

    private data class CtgPlaybackSource(
        val url: String,
        val quality: String?,
        val sourceName: String?
    )

    /*
     * Extract the real CTG `links[]` payload from the Next.js serialized
     * server-rendered data.
     *
     * CTG's watch page contains data shaped like:
     *
     *   "target": {...},
     *   "links": [
     *      {
     *          "quality": "1080p WebRip",
     *          "url": "https://...",
     *          "hls_url": null,
     *          "type": "download",
     *          "source": "auto:serverA"
     *      },
     *      ...
     *   ],
     *   "initialTime": 0
     *
     * This parser does not try to parse the complete Next.js document as JSON.
     * It only isolates the `links` array and extracts its individual URL fields.
     */
    private fun extractCtgPlaybackLinks(
        html: String,
        baseUrl: String
    ): List<CtgPlaybackSource> {
        if (html.isBlank()) return emptyList()

        val normalized = normalizeCtgPayload(html)
        val arrayText = extractJsonArrayAfterKey(
            normalized,
            "\"links\""
        ) ?: return emptyList()

        val result = mutableListOf<CtgPlaybackSource>()

        /*
         * Each link object is small enough that a targeted object scan is safer
         * than a giant cross-document regex.
         */
        extractTopLevelJsonObjects(arrayText).forEach { objectText ->
            val url = extractJsonString(objectText, "url")
            val hlsUrl = extractJsonString(objectText, "hls_url")
            val quality = extractJsonString(objectText, "quality")
            val source = extractJsonString(objectText, "source")

            /*
             * Prefer CTG's explicit `url`. If it is absent, use the CTG-provided
             * hls_url. This follows the site's own source priority.
             */
            val candidates = listOfNotNull(
                url,
                hlsUrl
            )

            candidates.forEach { raw ->
                val media = absoluteUrl(
                    cleanUrl(raw),
                    baseUrl
                )

                if (isMediaUrl(media)) {
                    result.add(
                        CtgPlaybackSource(
                            url = media,
                            quality = quality,
                            sourceName = source
                        )
                    )
                }
            }
        }

        /*
         * Preserve source order while removing duplicates.
         */
        val seen = linkedSetOf<String>()
        return result.filter { seen.add(it.url) }
    }

    private fun normalizeCtgPayload(
        html: String
    ): String {
        return html
            .replace("\\\\\"", "\"")
            .replace("\\\"", "\"")
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun extractJsonArrayAfterKey(
        text: String,
        key: String
    ): String? {
        val startKey = text.indexOf(key)
        if (startKey < 0) return null

        val arrayStart = text.indexOf('[', startKey + key.length)
        if (arrayStart < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in arrayStart until text.length) {
            val ch = text[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(
                            arrayStart,
                            index + 1
                        )
                    }
                }
            }
        }

        return null
    }

    private fun extractTopLevelJsonObjects(
        arrayText: String
    ): List<String> {
        val result = mutableListOf<String>()

        var depth = 0
        var objectStart = -1
        var inString = false
        var escaped = false

        for (index in arrayText.indices) {
            val ch = arrayText[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) {
                        objectStart = index
                    }
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && objectStart >= 0) {
                            result.add(
                                arrayText.substring(
                                    objectStart,
                                    index + 1
                                )
                            )
                            objectStart = -1
                        }
                    }
                }
            }
        }

        return result
    }

    private fun extractJsonString(
        objectText: String,
        key: String
    ): String? {
        val marker = "\"$key\""
        val keyIndex = objectText.indexOf(marker)
        if (keyIndex < 0) return null

        val colonIndex = objectText.indexOf(
            ':',
            keyIndex + marker.length
        )
        if (colonIndex < 0) return null

        val quoteStart = objectText.indexOf(
            '"',
            colonIndex + 1
        )
        if (quoteStart < 0) return null

        val value = StringBuilder()
        var escaped = false

        for (index in quoteStart + 1 until objectText.length) {
            val ch = objectText[index]

            if (escaped) {
                when (ch) {
                    '"' -> value.append('"')
                    '\\' -> value.append('\\')
                    '/' -> value.append('/')
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000C')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        if (index + 4 < objectText.length) {
                            val hex = objectText.substring(
                                index + 1,
                                index + 5
                            )
                            val decoded = hex.toIntOrNull(16)
                            if (decoded != null) {
                                value.append(decoded.toChar())
                                escaped = false
                                continue
                            }
                        }
                        value.append('u')
                    }
                    else -> value.append(ch)
                }
                escaped = false
                continue
            }

            when (ch) {
                '\\' -> escaped = true
                '"' -> return value.toString()
                else -> value.append(ch)
            }
        }

        return null
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        qualityHint: String? = null,
        sourceName: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = cleanUrl(mediaUrl)
        if (!isMediaUrl(url)) return

        val type = when {
            url.contains(".m3u8", ignoreCase = true) ->
                ExtractorLinkType.M3U8

            url.contains(".mpd", ignoreCase = true) ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

        val quality =
            qualityFromHint(qualityHint)
                ?: qualityFromUrl(
                    buildString {
                        append(url)
                        if (!qualityHint.isNullOrBlank()) {
                            append(' ')
                            append(qualityHint)
                        }
                    }
                )

        val labelSuffix = when {
            !sourceName.isNullOrBlank() ->
                " ${sourceName.trim()}"
            type == ExtractorLinkType.M3U8 ->
                " HLS"
            type == ExtractorLinkType.DASH ->
                " DASH"
            else ->
                " Direct"
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name$labelSuffix",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    }

    private suspend fun getDocument(url: String): Document? {
        val normalized = cleanUrl(url)
        if (normalized.isBlank()) return null

        return runCatching {
            app.get(
                normalized,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            ).document
        }.getOrNull()
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        /*
         * CTG currently exposes content through URL paths such as:
         * /movies/<slug>
         * /tv/<slug>
         * /anime/<slug>
         *
         * We intentionally parse normal links rather than relying on a
         * fragile giant regex.
         */
        document.select(
            "a[href], [data-href], [data-url], [data-link]"
        ).forEach { element ->

            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-link")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val absolute = absoluteUrl(
                cleanUrl(raw),
                sourceUrl
            )

            val type = typeFromUrl(absolute)
                ?: return@forEach

            if (!isContentUrl(absolute)) return@forEach

            val card = findCard(element)

            val title = cleanTitle(
                firstNonBlank(
                    card.selectFirst(".title")?.text(),
                    card.selectFirst(".movie-title")?.text(),
                    card.selectFirst(".movie_name")?.text(),
                    card.selectFirst(".name")?.text(),
                    card.selectFirst("h1")?.text(),
                    card.selectFirst("h2")?.text(),
                    card.selectFirst("h3")?.text(),
                    element.attr("aria-label"),
                    card.selectFirst("img")?.attr("alt"),
                    element.text(),
                    titleFromUrl(absolute)
                )
            )

            if (title.isBlank() || isNavigationTitle(title)) {
                return@forEach
            }

            result.putIfAbsent(
                absolute,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractPosterFromElement(card, sourceUrl),
                    type = type
                )
            )
        }

        return result.values.toList()
    }

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }

            TvType.Anime -> newAnimeSearchResponse(
                title,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
            }

            else -> newMovieSearchResponse(
                title,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    /*
     * Find CTG's actual playback page from a movie/series/anime detail page.
     *
     * The supplied CTG source shows the Movie Play button using:
     *   href="/watch/<id>?type=movie"
     *
     * We use the DOM link instead of a giant regex.
     */
    private fun extractPlaybackPageUrl(
        document: Document,
        baseUrl: String
    ): String? {
        val elements = document.select(
            "a[href], [data-href], [data-url]"
        )

        /*
         * Prefer an explicit Play/Watch link.
         */
        for (element in elements) {
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: continue

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            val path = runCatching {
                URI(absolute).path.orEmpty().lowercase(Locale.ROOT)
            }.getOrElse {
                absolute.lowercase(Locale.ROOT)
            }

            val label = element.text()
                .trim()
                .lowercase(Locale.ROOT)

            if (
                path.startsWith("/watch/") &&
                (
                    label.contains("play") ||
                    label.contains("watch") ||
                    absolute.contains("type=movie", true) ||
                    absolute.contains("type=series", true) ||
                    absolute.contains("type=tv", true) ||
                    absolute.contains("type=anime", true)
                )
            ) {
                return absolute
            }
        }

        /*
         * Fallback for buttons/links whose visible label is rendered by JS.
         */
        for (element in elements) {
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: continue

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            val path = runCatching {
                URI(absolute).path.orEmpty().lowercase(Locale.ROOT)
            }.getOrElse {
                absolute.lowercase(Locale.ROOT)
            }

            if (path.startsWith("/watch/")) {
                return absolute
            }
        }

        return null
    }

    private fun parseEpisodes(
        document: Document,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        /*
         * First pass: normal episode links.
         */
        document.select(
            "a[href], [data-href], [data-url], [data-link]"
        ).forEach { element ->
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url"),
                element.attr("data-link")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            if (!looksLikeEpisode(element, absolute, baseUrl)) {
                return@forEach
            }

            val label = cleanTitle(
                firstNonBlank(
                    element.text(),
                    element.attr("aria-label"),
                    "Episode ${episodeNumber(element, absolute)}"
                )
            )

            val season = seasonNumber(element, absolute)
            val episode = episodeNumber(element, absolute)

            result[absolute] = newEpisode(absolute) {
                name = label
                this.season = season
                this.episode = episode
            }
        }

        /*
         * Second pass: common data-* player/episode buttons.
         */
        document.select(
            "[data-episode][data-url], " +
            "[data-episode][data-href], " +
            "[data-ep][data-url], " +
            "[data-ep][data-href]"
        ).forEach { element ->
            val raw = sequenceOf(
                element.attr("data-url"),
                element.attr("data-href")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val absolute = absoluteUrl(
                cleanUrl(raw),
                baseUrl
            )

            val episode = element.attr("data-episode")
                .ifBlank { element.attr("data-ep") }
                .toIntOrNull()
                ?: episodeNumber(element, absolute)

            val season = element.attr("data-season")
                .toIntOrNull()
                ?: seasonNumber(element, absolute)

            val label = cleanTitle(
                element.text().ifBlank {
                    "Episode $episode"
                }
            )

            result[absolute] = newEpisode(absolute) {
                name = label
                this.season = season
                this.episode = episode
            }
        }

        /*
         * If a series page exposes a single media file directly, make it
         * Episode 1 instead of showing a broken empty series.
         */
        if (result.isEmpty()) {
            val media = extractMediaUrls(
                document,
                document.html(),
                baseUrl
            ).firstOrNull()

            if (media != null) {
                result[media] = newEpisode(media) {
                    name = "Episode 1"
                    season = 1
                    episode = 1
                }
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun looksLikeEpisode(
        element: Element,
        url: String,
        baseUrl: String
    ): Boolean {
        if (url == baseUrl) return false

        val text = element.text().trim().lowercase(Locale.ROOT)
        val lowerUrl = url.lowercase(Locale.ROOT)

        val sameContent =
            lowerUrl.startsWith("$mainUrl/tv/") ||
            lowerUrl.startsWith("$mainUrl/anime/")

        if (!sameContent) return false

        if (
            lowerUrl.contains("episode=") ||
            lowerUrl.contains("ep=") ||
            lowerUrl.contains("season=") ||
            lowerUrl.contains("/episode/")
        ) {
            return true
        }

        return text.contains("episode") ||
            text.matches(Regex("""(?i).*\bep\.?\s*\d+.*""")) ||
            text.matches(Regex("""(?i).*\bs\d{1,2}\s*e\d{1,3}.*"""))
    }

    private fun episodeNumber(
        element: Element,
        url: String
    ): Int {
        val data = sequenceOf(
            element.attr("data-episode"),
            element.attr("data-ep")
        ).firstOrNull { it.isNotBlank() }

        if (data != null) {
            data.toIntOrNull()?.let { return it }
        }

        val candidates = listOf(
            Regex("""(?i)episode[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)\bep[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)episode=(\d+)""").find(url),
            Regex("""(?i)[?&]ep=(\d+)""").find(url),
            Regex("""(?i)/episode/(\d+)""").find(url),
            Regex("""(?i)\bs\d{1,2}\s*e(\d{1,3})""").find(element.text())
        )

        return candidates.firstNotNullOfOrNull {
            it?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }

    private fun seasonNumber(
        element: Element,
        url: String
    ): Int {
        element.attr("data-season")
            .toIntOrNull()
            ?.let { return it }

        val candidates = listOf(
            Regex("""(?i)season[\s._-]*(\d+)""").find(element.text()),
            Regex("""(?i)\bS(\d{1,2})E\d{1,3}\b""").find(element.text()),
            Regex("""(?i)season=(\d+)""").find(url)
        )

        return candidates.firstNotNullOfOrNull {
            it?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
    }

    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val value = cleanUrl(raw)
            if (
                value.isBlank() ||
                value.startsWith("data:", true) ||
                value.startsWith("javascript:", true)
            ) {
                return
            }

            val absolute = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(absolute)) {
                found.add(absolute)
            }
        }

        document.select(
            "video[src], " +
            "video[poster], " +
            "video source[src], " +
            "source[src], " +
            "[data-src], " +
            "[data-file], " +
            "[data-video], " +
            "[data-video-url], " +
            "[data-file-url], " +
            "[data-stream], " +
            "[data-manifest]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-file"))
            add(element.attr("data-video"))
            add(element.attr("data-video-url"))
            add(element.attr("data-file-url"))
            add(element.attr("data-stream"))
            add(element.attr("data-manifest"))
        }

        /*
         * Small, valid media-only URL regex. It does not contain nested
         * optional groups like the broken CTG implementation.
         */
        val mediaRegex = Regex(
            """(?i)https?://[^"'<>\s]+\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s]*)?"""
        )

        mediaRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
        ).forEach { match ->
            add(match.value)
        }

        /*
         * Common JavaScript key/value forms.
         */
        val keyRegex = Regex(
            """(?i)(?:file|src|source|video|videoUrl|media|mediaUrl|fileUrl|stream|streamUrl|playlist|manifest)\s*[:=]\s*["']([^"']+)["']"""
        )

        keyRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
        ).forEach { match ->
            add(match.groupValues[1])
        }

        return found.toList()
    }

    private fun recoverDownloadUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        document.select("a[href], button[data-url], [data-file-url]").forEach { element ->
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-url"),
                element.attr("data-file-url")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            recoverQueryMedia(
                raw,
                baseUrl
            )?.let(found::add)
        }

        /*
         * Also inspect the HTML without trying to match the whole JavaScript
         * structure. This keeps the parser safe when the site's markup changes.
         */
        html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .split('"', '\'', ' ', '\n', '\r', '\t', '<', '>', '(', ')')
            .forEach { token ->
                if (
                    token.contains("download", true) ||
                    token.contains("stream", true) ||
                    token.contains("file=", true)
                ) {
                    recoverQueryMedia(token, baseUrl)?.let(found::add)
                }
            }

        return found.toList()
    }

    private fun recoverQueryMedia(
        raw: String,
        baseUrl: String
    ): String? {
        val absolute = absoluteUrl(
            cleanUrl(raw),
            baseUrl
        )

        val query = runCatching {
            URI(absolute).rawQuery.orEmpty()
        }.getOrDefault("")

        if (query.isBlank()) return null

        query.split('&').forEach { part ->
            val key = part.substringBefore('=')
                .trim()
                .lowercase(Locale.ROOT)

            if (
                key != "file" &&
                key != "url" &&
                key != "src" &&
                key != "video" &&
                key != "stream" &&
                key != "source" &&
                key != "fileurl" &&
                key != "videourl"
            ) {
                return@forEach
            }

            val rawValue = part.substringAfter('=', "")
            val value = runCatching {
                URLDecoder.decode(
                    rawValue,
                    StandardCharsets.UTF_8.toString()
                )
            }.getOrNull()?.trim().orEmpty()

            if (value.isBlank()) return@forEach

            val candidate = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(candidate)) {
                return candidate
            }
        }

        return null
    }

    /*
     * Recover actual media URLs from CTG's server/download controls.
     *
     * This handles:
     * - direct FTP URLs
     * - percent-encoded FTP URLs
     * - file/url/src/video/stream query parameters
     * - href/data-* attributes
     *
     * It deliberately does not require a specific server name.
     */
    private fun recoverPlayableUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            if (raw.isNullOrBlank()) return

            var value = cleanUrl(raw)

            repeat(2) {
                value = runCatching {
                    URLDecoder.decode(
                        value,
                        StandardCharsets.UTF_8.toString()
                    )
                }.getOrElse {
                    value
                }
            }

            val absolute = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(absolute)) {
                found.add(absolute)
            }
        }

        document.select(
            "a[href], " +
            "[data-url], " +
            "[data-href], " +
            "[data-file], " +
            "[data-src], " +
            "[data-video], " +
            "[data-video-url], " +
            "[data-file-url], " +
            "[data-stream]"
        ).forEach { element ->
            addCandidate(element.attr("href"))
            addCandidate(element.attr("data-url"))
            addCandidate(element.attr("data-href"))
            addCandidate(element.attr("data-file"))
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-video"))
            addCandidate(element.attr("data-video-url"))
            addCandidate(element.attr("data-file-url"))
            addCandidate(element.attr("data-stream"))

            val rawHref = element.attr("href")
            if (rawHref.contains("download", true) ||
                rawHref.contains("stream", true)
            ) {
                recoverQueryMedia(
                    rawHref,
                    baseUrl
                )?.let(found::add)
            }
        }

        /*
         * Search the raw HTML for ftp.ctgfun.com first. This is the actual
         * storage host shown by the supplied working browser URL.
         */
        val ftpRegex = Regex(
            """(?i)https?://ftp\.ctgfun\.com/[^"'<>\s\\]+"""
        )

        ftpRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        ).forEach { match ->
            addCandidate(match.value)
        }

        /*
         * Decode any percent-encoded FTP URL embedded in the page.
         */
        val encodedFtpRegex = Regex(
            """(?i)(?:https?%3A%2F%2F|https?%253A%252F%252F)ftp%\.ctgfun\.com%2F[^"'<>\s]+"""
        )

        encodedFtpRegex.findAll(
            html
                .replace("\\/", "/")
                .replace("&amp;", "&")
        ).forEach { match ->
            addCandidate(match.value)
        }

        /*
         * Finally inspect common query parameters.
         */
        html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .split(
                '"', '\'', ' ', '\n', '\r', '\t',
                '<', '>', '(', ')'
            )
            .forEach { token ->
                recoverQueryMedia(
                    token,
                    baseUrl
                )?.let(found::add)
            }

        return found.toList()
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
        val og = element.selectFirst(
            "meta[property=og:image], meta[name=twitter:image]"
        )?.attr("content")

        if (!og.isNullOrBlank()) {
            return absoluteUrl(
                og,
                pageUrl
            )
        }

        val image = element.select(
            "img[src], " +
            "img[data-src], " +
            "img[data-lazy-src], " +
            "img[data-original], " +
            "img[data-poster]"
        ).firstOrNull()

        if (image != null) {
            val source = sequenceOf(
                image.attr("data-poster"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src")
            ).firstOrNull { it.isNotBlank() }

            if (!source.isNullOrBlank()) {
                return absoluteUrl(
                    source,
                    pageUrl
                )
            }
        }

        return null
    }

    private fun findCard(
        element: Element
    ): Element {
        var current: Element? = element

        repeat(8) {
            val node = current ?: return@repeat

            val className = node.className()
                .lowercase(Locale.ROOT)

            if (
                node.select("img").isNotEmpty() ||
                className.contains("card") ||
                className.contains("movie") ||
                className.contains("poster") ||
                className.contains("item")
            ) {
                return node
            }

            current = node.parent()
        }

        return element
    }

    private fun extractPageTitle(
        document: Document
    ): String {
        val candidates = listOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst("h2")?.text(),
            document.selectFirst(".title")?.text(),
            document.selectFirst(".movie-title")?.text(),
            document.selectFirst(".movie_name")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.title()
        )

        return cleanTitle(
            candidates.firstOrNull {
                !it.isNullOrBlank()
            }.orEmpty()
        )
    }

    private fun extractPlot(
        document: Document
    ): String? {
        val values = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst(".description")?.text(),
            document.selectFirst(".plot")?.text(),
            document.selectFirst(".overview")?.text()
        )

        return values.firstOrNull {
            !it.isNullOrBlank()
        }?.trim()
    }

    private fun extractYear(
        document: Document
    ): Int? {
        val text = document.text()

        return Regex("""(?<!\\d)(19\\d{2}|20\\d{2}|21\\d{2})(?!\\d)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun typeFromUrl(
        url: String
    ): TvType {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return when {
            path.startsWith("/tv/") -> TvType.TvSeries
            path.startsWith("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }
    }

    private fun isContentUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return path.startsWith("/movies/") ||
            path.startsWith("/tv/") ||
            path.startsWith("/anime/")
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrElse {
            url.lowercase(Locale.ROOT)
        }

        return mediaExtensions.any {
            path.endsWith(it)
        } || path.contains(".mp4") ||
            path.contains(".mkv") ||
            path.contains(".m3u8") ||
            path.contains(".mpd")
    }

    private fun qualityFromHint(
        quality: String?
    ): Int? {
        val lower = quality
            ?.lowercase(Locale.ROOT)
            ?: return null

        return when {
            lower.contains("4320") || lower.contains("8k") ->
                4320

            lower.contains("2160") || lower.contains("4k") ->
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
                null
        }
    }

    private fun qualityFromUrl(
        url: String
    ): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            lower.contains("4320") || lower.contains("8k") ->
                4320

            lower.contains("2160") || lower.contains("4k") ->
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
    }

    private fun hasNextPage(
        document: Document,
        currentPage: Int
    ): Boolean {
        val next = document.select("a[href]").firstOrNull { anchor ->
            val text = anchor.text()
                .trim()
                .lowercase(Locale.ROOT)

            val rel = anchor.attr("rel")
                .lowercase(Locale.ROOT)

            val href = anchor.attr("href")
                .lowercase(Locale.ROOT)

            text.contains("next") ||
                rel.contains("next") ||
                href.contains("page=${currentPage + 1}")
        }

        return next != null
    }

    private fun pageUrl(
        base: String,
        page: Int
    ): String {
        if (page <= 1) return base

        return if (base.contains("?")) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    private fun pageSuffix(
        page: Int
    ): String {
        return if (page > 1) {
            "&page=$page"
        } else {
            ""
        }
    }

    private fun absoluteUrl(
        raw: String,
        base: String
    ): String {
        val value = cleanUrl(raw)

        if (value.startsWith("//")) {
            val scheme = runCatching {
                URI(base).scheme
            }.getOrNull() ?: "https"

            return "$scheme:$value"
        }

        if (
            value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        ) {
            return value
        }

        return runCatching {
            URI(base).resolve(value).toString()
        }.getOrElse {
            value
        }
    }

    private fun titleFromUrl(
        url: String
    ): String {
        val path = runCatching {
            URI(url).path.orEmpty()
        }.getOrElse {
            url
        }

        val slug = path
            .trimEnd('/')
            .substringAfterLast('/')

        return slug
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase(Locale.ROOT)
                } else {
                    it.toString()
                }
            }
            .ifBlank {
                "CTG FTP"
            }
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(Regex("""\s+"""), " ")
            .trim()
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

    private fun firstNonBlank(
        vararg values: String?
    ): String {
        return values.firstOrNull {
            !it.isNullOrBlank()
        }?.trim().orEmpty()
    }

    private fun isNavigationTitle(
        value: String
    ): Boolean {
        return value.lowercase(Locale.ROOT) in setOf(
            "home",
            "movies",
            "tv",
            "tv shows",
            "anime",
            "games",
            "search",
            "all",
            "newest",
            "popular",
            "top rated",
            "next",
            "previous",
            "details",
            "play"
        )
    }
}
