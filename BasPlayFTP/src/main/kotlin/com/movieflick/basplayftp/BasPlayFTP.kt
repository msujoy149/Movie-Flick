package com.movieflick.basplayftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class BasPlayFTP : MainAPI() {
    override var mainUrl = "http://10.20.30.40"
    override var name = "Bas Play FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "basplay://trending" to "Trending Now",
        "basplay://movies" to "Movies",
        "basplay://tv" to "Tv Show",
        "basplay://anime" to "Anime"
    )

    private companion object {
        const val BASE_URL = "http://10.20.30.40"

        const val INITIAL_BATCH = 6
        const val CONTINUE_BATCH = 10
        const val TV_PAGE_SIZE = 24
        const val CATEGORY_PAGE_SIZE = 24

        const val MOVIES_URL = "$BASE_URL/index.php"
        const val TV_URL = "$BASE_URL/tv.php"
        const val ANIME_MOVIES_URL = "$BASE_URL/category.php?category=Animation"
        const val ANIME_TV_URL = "$BASE_URL/tv.php?category=ANIMATED+TV+SERIES"
    }

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val sortTime: Long = 0L,
        val order: Long = 0L
    )

    private data class PageResult(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)
        val result = when (request.data) {
            "basplay://trending" -> loadTrending(pageNumber)
            "basplay://movies" -> loadMovies(pageNumber)
            "basplay://tv" -> loadTv(pageNumber)
            "basplay://anime" -> loadAnime(pageNumber)
            else -> PageResult(emptyList(), false)
        }

        return newHomePageResponse(
            request,
            result.items.map { it.toSearchResponse() },
            result.hasNext
        )
    }

    private suspend fun loadTrending(page: Int): PageResult {
        val document = getDocument(mainUrl) ?: return PageResult(emptyList(), false)

        // BAS PLAY exposes Trending Now as a dedicated row of real movie cards.
        // Parse that row only; never fall back to the full movie grid here.
        val candidates = document.select(
            "#trendRow > a.trend-card, .trend-row > a.trend-card, .trend-row .trend-card, .trend-row .cp-card"
        )

        val parsed = parseItems(
            candidates = candidates,
            sourceUrl = mainUrl,
            defaultType = TvType.Movie,
            forceSeries = false
        ).filterNot { isSeriesUrl(it.url) }

        return paginate(parsed.distinctBy { movieDedupeKey(it) }, page)
    }

    private suspend fun loadMovies(page: Int): PageResult {
        val url = pagedUrl(MOVIES_URL, page)
        val document = getDocument(url) ?: return PageResult(emptyList(), false)

        // Movies must remain movie-only. A TV item is never admitted to this row,
        // even when the server markup happens to contain a generic .cp-card.
        val items = parseItems(
            candidates = document.select(
                ".cp-card, .movie-card, .movie-grid .movie-card, a[href*='view.php'], a[href*='player.php']"
            ),
            sourceUrl = url,
            defaultType = TvType.Movie,
            forceSeries = false
        )
            .filterNot { isSeriesUrl(it.url) }
            .distinctBy { movieDedupeKey(it) }

        val batch = paginate(items, page)
        val hasServerNext = detectNextPage(document, page)
        return batch.copy(hasNext = batch.hasNext || hasServerNext)
    }

    private suspend fun loadTv(page: Int): PageResult {
        val url = pagedUrl(TV_URL, page)
        val document = getDocument(url) ?: return PageResult(emptyList(), false)

        val items = parseItems(
            candidates = document.select(
                ".cp-card, .movie-card, a[href*='tview.php']"
            ),
            sourceUrl = url,
            defaultType = TvType.TvSeries,
            forceSeries = true
        ).map { it.copy(type = TvType.TvSeries) }

        val batch = paginate(items, page)
        val hasServerNext = detectNextPage(document, page)
        return batch.copy(hasNext = batch.hasNext || hasServerNext)
    }

    private suspend fun loadAnime(page: Int): PageResult {
        // Both configured Anime sources are fetched on every page request.
        // Interleave them so one source cannot consume the whole initial row;
        // the merged stream remains deterministic for stable lazy loading.
        val first = getCategoryPage(ANIME_MOVIES_URL, page, TvType.Anime, false)
        val second = getCategoryPage(ANIME_TV_URL, page, TvType.TvSeries, true)

        val merged = mergeInterleaved(
            first.items.distinctBy { itemKey(it) },
            second.items.distinctBy { itemKey(it) }
        )

        val deduped = linkedMapOf<String, SiteItem>()
        merged.forEach { item ->
            deduped.putIfAbsent(itemKey(item), item)
        }

        val paged = paginate(deduped.values.toList(), page)
        return paged.copy(hasNext = paged.hasNext || first.hasNext || second.hasNext)
    }

    private suspend fun getCategoryPage(
        source: String,
        page: Int,
        defaultType: TvType,
        forceSeries: Boolean
    ): PageResult {
        val url = pagedUrl(source, page)
        val document = getDocument(url) ?: return PageResult(emptyList(), false)

        val selectors = if (forceSeries) {
            ".cp-card, .movie-card, a[href*='tview.php']"
        } else {
            ".cp-card, .movie-card, .movie-grid .movie-card, a[href*='player.php'], a[href*='download.php']"
        }

        val items = parseItems(
            candidates = document.select(selectors),
            sourceUrl = url,
            defaultType = defaultType,
            forceSeries = forceSeries
        )

        return PageResult(
            items = items,
            hasNext = detectNextPage(document, page) || items.size >= CATEGORY_PAGE_SIZE
        )
    }

    private fun paginate(items: List<SiteItem>, page: Int): PageResult {
        if (items.isEmpty()) return PageResult(emptyList(), false)

        val offset = if (page <= 1) 0 else INITIAL_BATCH + ((page - 2) * CONTINUE_BATCH)
        val take = if (page <= 1) INITIAL_BATCH else CONTINUE_BATCH
        val batch = items.drop(offset).take(take)

        return PageResult(
            items = batch,
            hasNext = offset + batch.size < items.size
        )
    }

    private fun parseItems(
        candidates: List<Element>,
        sourceUrl: String,
        defaultType: TvType,
        forceSeries: Boolean
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()
        var order = 0L

        for (element in candidates) {
            val href = extractContentUrl(element) ?: continue
            val absolute = absoluteUrl(href, sourceUrl)

            if (!isUsefulContentUrl(absolute)) continue

            val card = findCard(element)
            val title = cleanTitle(
                extractCardTitle(element, card)
                    .ifBlank { titleFromUrl(absolute) }
            )
            if (title.isBlank() || isNavigationTitle(title)) continue

            val series = forceSeries || isSeriesUrl(absolute)
            val type = when {
                series -> TvType.TvSeries
                defaultType == TvType.Anime -> TvType.Anime
                else -> TvType.Movie
            }

            val poster = extractPoster(card ?: element, sourceUrl)
            val key = itemKeyFromUrl(absolute)

            result.putIfAbsent(
                key,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    type = type,
                    order = order++
                )
            )
        }

        return result.values.sortedBy { it.order }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        // BAS PLAY pages use ordinary server-rendered HTML. We search the main
        // movie and TV indexes separately, then rank their visible card titles.
        val sources = listOf(
            SearchSource(MOVIES_URL, TvType.Movie, false),
            SearchSource(TV_URL, TvType.TvSeries, true),
            SearchSource(ANIME_MOVIES_URL, TvType.Anime, false),
            SearchSource(ANIME_TV_URL, TvType.TvSeries, true)
        )

        val all = linkedMapOf<String, SiteItem>()
        for (source in sources) {
            val document = getDocument(source.url) ?: continue
            parseItems(
                candidates = document.select(
                    ".cp-card, .movie-card, a[href*='view.php'], a[href*='player.php'], a[href*='tview.php']"
                ),
                sourceUrl = source.url,
                defaultType = source.type,
                forceSeries = source.forceSeries
            )
                .filter { source.forceSeries || !isSeriesUrl(it.url) }
                .forEach { item ->
                    all.putIfAbsent(
                        if (item.type == TvType.Movie) movieDedupeKey(item) else itemKey(item),
                        item
                    )
                }
        }

        val ranked = all.values
            .map { item -> item to searchScore(q, item.title) }
            .filter { it.second >= 0.34 }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
            .map { it.first }

        val pageSize = 24
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val pageItems = ranked.drop(offset).take(pageSize)

        return newSearchResponseList(
            pageItems.map { it.toSearchResponse() },
            offset + pageItems.size < ranked.size
        )
    }

    private data class SearchSource(
        val url: String,
        val type: TvType,
        val forceSeries: Boolean
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
            TvType.Anime -> newMovieSearchResponse(title, url, TvType.Anime) {
                posterUrl = poster
            }
            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val input = absoluteUrl(url, mainUrl)

        // Direct media is accepted as a playable movie. We never use a Trailer
        // URL here; trailer links are filtered during extraction.
        if (isMediaUrl(input)) {
            return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                inferTvType(input),
                input
            )
        }

        val document = getDocument(input)
        if (document == null) {
            return fallbackLoadResponse(input)
        }

        val title = extractPageTitle(document).ifBlank { titleFromUrl(input) }
        val poster = extractPoster(document, input)
        val series = isSeriesUrl(input) || looksLikeSeriesPage(document)

        if (series) {
            val episodes = parseAllSeasonEpisodes(document, input, poster)
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

        // The movie detail/player page contains a direct <video><source>
        // playable URL. Pass the detail page to loadLinks() so the latest source
        // (including a rotated token/signature) is discovered at play time.
        return newMovieLoadResponse(
            title,
            input,
            inferTvType(input),
            "basplay:movie:${encodeToken(input)}"
        ) {
            posterUrl = poster
        }
    }

    private suspend fun fallbackLoadResponse(url: String): LoadResponse {
        return newMovieLoadResponse(
            titleFromUrl(url),
            url,
            inferTvType(url),
            url
        )
    }

    private suspend fun parseAllSeasonEpisodes(
        firstDocument: Document,
        seriesUrl: String,
        fallbackPoster: String?
    ): List<Episode> {
        val seasonLinks = linkedSetOf<String>()

        // The source page exposes the real season selector. Build the exact
        // season URLs from those options instead of inventing season numbers.
        firstDocument.select("#seasonSelect option, select[name*=season] option").forEach { option ->
            val value = option.attr("value").trim()
            if (value.isNotBlank()) {
                seasonLinks += setQueryParam(seriesUrl, "season", value).let {
                    removeQueryParam(it, "episode")
                }
            }
        }

        if (seasonLinks.isEmpty()) seasonLinks += removeQueryParam(seriesUrl, "episode")

        val documents = linkedMapOf<String, Document>()
        documents[removeQueryParam(seriesUrl, "episode")] = firstDocument

        for (seasonUrl in seasonLinks) {
            if (documents.containsKey(seasonUrl)) continue
            getDocument(seasonUrl)?.let { documents[seasonUrl] = it }
        }

        val result = mutableListOf<Episode>()
        for ((seasonUrl, document) in documents) {
            result += parseEpisodesFromDocument(document, seasonUrl, fallbackPoster)
        }

        return result
            .distinctBy { "${it.season ?: 1}:${it.episode ?: 0}:${it.data}" }
            .sortedWith(
                compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
                    .thenBy { (it.name ?: "").lowercase(Locale.ROOT) }
            )
    }

    private fun parseEpisodesFromDocument(
        document: Document,
        seriesUrl: String,
        fallbackPoster: String?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val selectors = listOf(
            ".ep-item[data-src]",
            "a[data-epnum][data-src]",
            "[data-episode][data-src]",
            "a[href*=episode]"
        )

        val elements = linkedSetOf<Element>()
        selectors.forEach { selector -> elements.addAll(document.select(selector)) }

        for (element in elements) {
            val directRaw = element.attr("data-src").trim()
            val directMedia = if (directRaw.isNotBlank()) {
                absoluteUrl(directRaw, seriesUrl)
            } else {
                extractDirectMediaFromElement(element, seriesUrl).orEmpty()
            }

            val hrefRaw = element.attr("href").trim()
            val episodePage = if (hrefRaw.isNotBlank() && hrefRaw != "#") {
                absoluteUrl(hrefRaw, seriesUrl)
            } else {
                setQueryParam(
                    removeQueryParam(seriesUrl, "episode"),
                    "episode",
                    element.attr("data-epnum").trim()
                )
            }

            val pageFallback = episodePage.takeIf { it.contains("tview.php", true) }
            val playable = when {
                isPlayableMedia(directMedia) && !isTrailerUrl(directMedia) -> directMedia
                else -> ""
            }

            if (playable.isBlank() && pageFallback == null) continue
            if (isTrailerUrl(playable) || isDownloadOnlyUrl(playable)) continue

            val text = cleanTitle(
                element.attr("title")
                    .ifBlank { element.selectFirst(".text-sm, .cp-title, h1,h2,h3,h4,h5")?.text().orEmpty() }
                    .ifBlank { element.text() }
                    .ifBlank { titleFromUrl(playable.ifBlank { episodePage }) }
            )
            if (text.isBlank() || isNavigationTitle(text)) continue

            val explicitEpisode = element.attr("data-epnum").toIntOrNull()
            val episodeNumber = explicitEpisode ?: extractEpisodeNumber(text, playable.ifBlank { episodePage }) ?: continue
            val season = element.attr("data-season").toIntOrNull()
                ?: extractSeasonNumber(text, playable.ifBlank { episodePage })
                ?: extractSeasonNumber("$seriesUrl", seriesUrl)
                ?: 1

            val data = if (playable.isNotBlank()) {
                // Keep the exact source present on BAS PLAY. This is the source
                // used by the website itself for this episode.
                "basplay:direct:${encodeToken(playable)}"
            } else {
                // For pages that do not expose a direct source in the list,
                // resolve the episode page at playback time.
                "basplay:episode:${encodeToken(episodePage)}"
            }

            episodes += newEpisode(data) {
                name = text
                this.season = season
                this.episode = episodeNumber
                posterUrl = extractPoster(element, seriesUrl) ?: fallbackPoster
            }
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = data.trim()
        if (input.isBlank()) return false

        when {
            input.startsWith("basplay:direct:") -> {
                val media = decodeToken(input.substringAfter("basplay:direct:"))
                if (media.isBlank()) return false
                emitMedia(media, mainUrl, callback)
                return true
            }

            input.startsWith("basplay:movie:") -> {
                val pageUrl = decodeToken(input.substringAfter("basplay:movie:"))
                return resolvePlayableFromPage(pageUrl, callback)
            }

            input.startsWith("basplay:episode:") -> {
                val episodePage = decodeToken(input.substringAfter("basplay:episode:"))
                return resolvePlayableFromPage(episodePage, callback)
            }
        }

        if (isMediaUrl(input) && !isTrailerUrl(input)) {
            emitMedia(input, mainUrl, callback)
            return true
        }

        if (looksLikePlayerOrContentPage(input)) {
            return resolvePlayableFromPage(input, callback)
        }

        return false
    }

    private suspend fun resolvePlayableFromPage(
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(pageUrl) ?: return false

        val mediaCandidates = linkedSetOf<String>()

        // 1) DOM source/video tags — highest confidence.
        document.select(
            "video source[src], video[src], source[src], source[data-src], " +
                "[data-src], [data-video], [data-file], [data-url], [data-source], " +
                "[data-stream], [data-video-url], [data-manifest]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-video"),
                element.attr("data-file"),
                element.attr("data-url"),
                element.attr("data-source"),
                element.attr("data-stream"),
                element.attr("data-video-url"),
                element.attr("data-manifest")
            ).forEach { value ->
                val absolute = absoluteUrl(value, pageUrl)
                if (isPlayableMedia(absolute) && !isTrailerUrl(absolute)) {
                    mediaCandidates.add(absolute)
                }
            }
        }

        // 2) Player JavaScript fallback. This deliberately extracts the current
        // URL/token from the page at playback time instead of hard-coding one.
        val html = document.html()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u003A", ":")
            .replace("\\u003a", ":")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val directRegex = Regex(
            """(?i)(?:https?://|/|\.\.?/)[^\"'<>\s]+\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^\"'<>\s]*)?"""
        )
        directRegex.findAll(html).forEach {
            val absolute = absoluteUrl(it.value, pageUrl)
            if (isPlayableMedia(absolute) && !isTrailerUrl(absolute)) mediaCandidates.add(absolute)
        }

        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl|manifest|hls|dash)\s*[:=]\s*[\"']([^\"']+)[\"']"""
        )
        keyRegex.findAll(html).forEach {
            val absolute = absoluteUrl(it.groupValues[1], pageUrl)
            if (isPlayableMedia(absolute) && !isTrailerUrl(absolute)) mediaCandidates.add(absolute)
        }

        // 3) Prefer the actual player source, never Trailer/teaser/sample/download-only.
        val selected = mediaCandidates
            .filterNot(::isTrailerUrl)
            .filterNot(::isDownloadOnlyUrl)
            .sortedByDescending(::mediaScore)
            .firstOrNull()
            ?: return false

        emitMedia(selected, pageUrl, callback)
        return true
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = mediaUrl.substringBefore("#").trim()
        if (clean.isBlank() || isTrailerUrl(clean) || isDownloadOnlyUrl(clean)) return

        val lower = clean.substringBefore("?").lowercase(Locale.ROOT)
        val type = when {
            lower.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            lower.endsWith(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        // BAS PLAY serves the media from the same private HTTP host as the page.
        // Keep the browser-like request headers and the originating page referer
        // on the ExtractorLink so Android/ExoPlayer receives the same request
        // context as the website player.
        val headers = pageHeaders(referer).toMutableMap().apply {
            this["Accept"] = "video/*,application/octet-stream;q=0.9,*/*;q=0.8"
        }

        callback(
            newExtractorLink(
                source = name,
                name = "Bas Play Direct",
                url = clean,
                type = type
            ) {
                this.referer = referer
                this.headers = headers
                this.quality = detectQuality(lower)
            }
        )
    }

    private fun detectQuality(value: String): Int = when {
        Regex("(?i)\\b2160p\\b|\\b4k\\b").containsMatchIn(value) -> Qualities.P2160.value
        Regex("(?i)\\b1440p\\b").containsMatchIn(value) -> Qualities.P1440.value
        Regex("(?i)\\b1080p\\b").containsMatchIn(value) -> Qualities.P1080.value
        Regex("(?i)\\b720p\\b").containsMatchIn(value) -> Qualities.P720.value
        Regex("(?i)\\b480p\\b").containsMatchIn(value) -> Qualities.P480.value
        Regex("(?i)\\b360p\\b").containsMatchIn(value) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun mediaScore(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        var score = 0
        if ("trailer" in lower) score -= 5000
        if ("teaser" in lower) score -= 4000
        if ("preview" in lower) score -= 4000
        if ("sample" in lower) score -= 3500
        if ("clip" in lower) score -= 3000
        if ("download" in lower) score -= 2000
        if (lower.endsWith(".m3u8")) score += 1000
        if (lower.endsWith(".mpd")) score += 900
        if (lower.endsWith(".mp4")) score += 800
        if (lower.contains("2160") || lower.contains("4k")) score += 40
        else if (lower.contains("1440")) score += 35
        else if (lower.contains("1080")) score += 30
        else if (lower.contains("720")) score += 20
        else if (lower.contains("480")) score += 10
        return score
    }

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)
        if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        } else if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        }

        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = pageHeaders(candidate)).document
            }.getOrNull()
            if (document != null) return document
        }
        return null
    }

    private fun pagedUrl(base: String, page: Int): String {
        val clean = base.substringBefore("#")
        if (page <= 1 && !clean.contains("page=", true)) return clean
        val separator = if (clean.contains("?")) "&" else "?"
        val withoutPage = clean.replace(Regex("([&?])page=\\d+"), "")
        val sep = if (withoutPage.contains("?")) "&" else "?"
        return "$withoutPage${sep}page=$page"
    }

    private fun detectNextPage(document: Document, page: Int): Boolean {
        val next = document.select(
            "a[href*='page=${page + 1}'], a[rel='next'], a:matchesOwn((?i)^Next$)"
        )
        return next.isNotEmpty()
    }

    private fun extractContentUrl(element: Element): String? {
        val attrs = listOf(
            element.attr("href"),
            element.attr("data-href"),
            element.attr("data-url"),
            element.attr("data-link"),
            element.attr("onclick")
        )

        for (raw in attrs) {
            if (raw.isBlank()) continue
            val extracted = extractUrlFromAttribute(raw) ?: raw
            if (extracted.isBlank()) continue
            if (
                extracted.contains("tview.php", true) ||
                extracted.contains("player.php", true) ||
                extracted.contains("download.php", true) ||
                extracted.contains("view.php", true)
            ) {
                return extracted
            }
        }
        return null
    }

    private fun extractUrlFromAttribute(value: String): String? {
        val patterns = listOf(
            Regex("[\\\"']((?:https?://|/|\\.\\.?/)[^\\\"']+)[\\\"']"),
            Regex("(?i)(?:location\\.href|window\\.location|openMovie|openSeries)\\s*\\(?[\\\"']?([^\\\"')]+)")
        )
        for (pattern in patterns) {
            pattern.find(value)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private fun findCard(element: Element): Element? {
        return element.closest(".cp-card")
            ?: element.closest(".movie-card")
            ?: element.closest(".trend-card")
            ?: element.closest("a[href]")
    }

    private fun extractCardTitle(element: Element, card: Element?): String {
        val root = card ?: element
        return sequenceOf(
            root.selectFirst(".cp-title")?.text(),
            root.attr("title").takeIf { it.isNotBlank() },
            root.attr("aria-label").takeIf { it.isNotBlank() },
            root.selectFirst("img[alt]")?.attr("alt"),
            root.selectFirst("h1,h2,h3,h4,h5")?.text(),
            element.text()
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun extractPoster(element: Element, baseUrl: String): String? {
        val img = element.selectFirst(
            "img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]"
        ) ?: return null

        val candidates = buildList {
            add(img.attr("src"))
            add(img.attr("data-src"))
            add(img.attr("data-lazy-src"))
            add(img.attr("data-original"))
            val srcset = img.attr("srcset")
            if (srcset.isNotBlank()) {
                add(srcset.substringBefore(',').trim().substringBefore(' '))
            }
        }

        return candidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { absoluteUrl(it, baseUrl) }
            .firstOrNull { it.isNotBlank() && !it.contains("dummyimage", true) && !it.startsWith("data:", true) }
    }

    private fun extractPageTitle(document: Document): String {
        return sequenceOf(
            document.selectFirst("h1")?.text(),
            document.selectFirst(".title")?.text(),
            document.selectFirst(".movie-title")?.text(),
            document.selectFirst("title")?.text()
        ).filterNotNull().map { cleanTitle(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" • BAS PLAY")
            .removeSuffix(" - Player")
            .trim()
    }

    private fun titleFromUrl(url: String): String {
        return runCatching {
            val path = URI(url).path.orEmpty()
            URLDecoder.decode(path.substringAfterLast('/'), StandardCharsets.UTF_8.toString())
                .substringBeforeLast('.')
                .replace(Regex("[_-]+"), " ")
                .trim()
        }.getOrDefault("Untitled")
    }

    private fun setQueryParam(url: String, key: String, value: String): String {
        val clean = removeQueryParam(url, key)
        val separator = if (clean.contains("?")) "&" else "?"
        return "$clean${separator}${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
    }

    private fun removeQueryParam(url: String, key: String): String {
        val hash = url.substringAfter("#", "")
        val base = url.substringBefore("#")
        val queryIndex = base.indexOf('?')
        if (queryIndex < 0) return url
        val path = base.substring(0, queryIndex)
        val query = base.substring(queryIndex + 1)
        val filtered = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { part -> part.substringBefore('=').equals(key, true) }
        val rebuilt = if (filtered.isEmpty()) path else "$path?${filtered.joinToString("&")}"
        return if (hash.isBlank()) rebuilt else "$rebuilt#$hash"
    }

    private fun absoluteUrl(raw: String, base: String): String {
        val value = raw.trim().replace("&amp;", "&")
        if (value.isBlank()) return ""
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        val safeValue = value.replace(" ", "%20")
        return runCatching { URI(base).resolve(safeValue).toString() }
            .getOrElse { "$mainUrl/${safeValue.trimStart('/')}" }
    }

    private fun isUsefulContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (lower.contains("javascript:")) return false
        if (lower.contains("#")) return false
        return lower.contains("tview.php") ||
            lower.contains("player.php") ||
            lower.contains("download.php") ||
            lower.contains("view.php")
    }

    private fun isSeriesUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("tview.php") || lower.contains("tv.php")
    }

    private fun looksLikeSeriesPage(document: Document): Boolean {
        return document.select(
            "[class*=episode], [id*=episode], [class*=season], [id*=season], a[href*=tview.php]"
        ).isNotEmpty()
    }

    private fun isPlayableOrPlayerUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("player.php") || lower.contains("watch.php") || isPlayableMedia(lower)
    }

    private fun looksLikePlayerOrContentPage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("player.php") || lower.contains("tview.php") || lower.contains("view.php") || lower.contains("download.php")
    }

    private fun isPlayableMedia(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT).substringBefore("?")
        return lower.endsWith(".m3u8") ||
            lower.endsWith(".mpd") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".m4v") ||
            lower.endsWith(".avi") ||
            lower.endsWith(".flv") ||
            lower.endsWith(".ts")
    }

    private fun isMediaUrl(url: String): Boolean = isPlayableMedia(url)

    private fun isTrailerUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return listOf("trailer", "teaser", "preview", "sample", "clip").any { it in lower }
    }

    private fun isDownloadOnlyUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("download.php") && !isPlayableMedia(lower)
    }

    private fun extractDirectMediaFromElement(element: Element, baseUrl: String): String? {
        val candidates = listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-video"),
            element.attr("data-file"),
            element.attr("data-url"),
            element.attr("data-source"),
            element.attr("data-stream"),
            element.attr("data-video-url"),
            element.attr("data-manifest")
        )
        return candidates
            .map { absoluteUrl(it, baseUrl) }
            .firstOrNull { isPlayableMedia(it) && !isTrailerUrl(it) }
    }

    private fun extractPoster(document: Document, baseUrl: String): String? =
        extractPoster(document.body(), baseUrl)

    private fun itemKey(item: SiteItem): String = itemKeyFromUrl(item.url)

    private fun itemKeyFromUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            "${uri.host.orEmpty().lowercase(Locale.ROOT)}:${uri.path.orEmpty().lowercase(Locale.ROOT)}:${uri.query.orEmpty()}"
        }.getOrDefault(url.lowercase(Locale.ROOT))
    }

    private fun mergeInterleaved(
        first: List<SiteItem>,
        second: List<SiteItem>
    ): List<SiteItem> {
        val out = mutableListOf<SiteItem>()
        val max = maxOf(first.size, second.size)
        for (index in 0 until max) {
            first.getOrNull(index)?.let(out::add)
            second.getOrNull(index)?.let(out::add)
        }
        return out
    }

    private fun movieDedupeKey(item: SiteItem): String {
        val normalizedTitle = normalizeSearch(item.title)
        if (normalizedTitle.isNotBlank()) return "movie:title:$normalizedTitle"
        return "movie:url:${itemKey(item)}"
    }

    private fun searchScore(query: String, title: String): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        if (t.contains(q)) return 0.96

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        val tokenScore = qTokens.map { qt ->
            tTokens.maxOfOrNull { tt ->
                when {
                    tt == qt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.90
                    else -> similarity(qt, tt)
                }
            } ?: 0.0
        }.average()

        return tokenScore.coerceIn(0.0, 1.0)
    }

    private fun normalizeSearch(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
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
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun inferTvType(url: String): TvType =
        if (isSeriesUrl(url)) TvType.TvSeries else TvType.Movie

    private fun encodeToken(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun decodeToken(value: String): String =
        runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
        }.getOrDefault(value)

    private fun extractSeasonNumber(text: String, url: String): Int? {
        val source = "$text $url"
        val match = Regex("(?i)(?:season|s)[ ._-]*(\\d{1,2})").find(source)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        val source = "$text $url"
        val patterns = listOf(
            Regex("(?i)(?:episode|ep|e)[ ._-]*(\\d{1,4})"),
            Regex("(?i)s\\d{1,2}e(\\d{1,4})")
        )
        return patterns.asSequence()
            .mapNotNull { it.find(source)?.groupValues?.lastOrNull()?.toIntOrNull() }
            .firstOrNull()
    }

    private fun isNavigationTitle(title: String): Boolean {
        return title.equals("next", true) ||
            title.equals("previous", true) ||
            title.equals("home", true) ||
            title.equals("movies", true) ||
            title.equals("tv", true) ||
            title.equals("anime", true)
    }
}
