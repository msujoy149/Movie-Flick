package com.movieflick.movielinkbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Movie Link BD CloudStream provider.
 *
 * Site mirrors:
 *   1) https://movielinkbd.tv
 *   2) https://vacj4n.movielinkbd.li
 *   3) https://movielinkbd.one
 *
 * The mirrors intentionally use the same paths and page structure.
 * The .tv domain is always attempted first and the same path is retried on
 * the next mirror when the first one fails.
 */
class MovieLinkBD : MainAPI() {

    private companion object {
        const val PRIMARY = "https://movielinkbd.tv"
        const val FALLBACK_1 = "https://vacj4n.movielinkbd.li"
        const val FALLBACK_2 = "https://movielinkbd.one"

        val DOMAINS = listOf(PRIMARY, FALLBACK_1, FALLBACK_2)

        const val RECENTLY = "mlbd_recently"
        const val MOVIES = "mlbd_movies"
        const val DUAL_AUDIO = "mlbd_dual_audio"
        const val ONGOING = "mlbd_ongoing"
        const val TV_SHOW = "mlbd_tv_show"
        const val ANIME = "mlbd_anime"

        const val PLAYER_JSON_SELECTOR = "#mlbdInlinePlayerData"
        const val CACHE_MS = 120_000L
        const val HOME_LIMIT = 25

        const val RECENTLY_MAX_PAGES = 8
        const val SEARCH_MAX_PAGES = 5
    }

    override var name: String = "Movie Link BD"
    override var mainUrl: String = PRIMARY
    override var lang: String = "bn"
    override val hasMainPage: Boolean = true
    override val hasQuickSearch: Boolean = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * IMPORTANT:
     * mainPageOf is KEY -> DISPLAY NAME.
     * Keeping these names explicit prevents CloudStream from showing the
     * internal keys such as "recently" or "dual_audio" in the Home UI.
     */
    override val mainPage = mainPageOf(
        RECENTLY to "Recently Uploads",
        MOVIES to "Movies",
        DUAL_AUDIO to "Dual Audio",
        ONGOING to "Ongoing Series",
        TV_SHOW to "Tv Show",
        ANIME to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val priority: Int = Int.MAX_VALUE
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

    private var priorityCacheAt = 0L
    private val recentlyKeys = linkedSetOf<String>()
    private val ongoingKeys = linkedSetOf<String>()
    private val dualAudioKeys = linkedSetOf<String>()

    // ---------------------------------------------------------------------
    // HOME / LAZY PAGINATION
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val category = request.data
        val currentPage = page.coerceAtLeast(1)

        val routeResults = when (category) {
            RECENTLY -> loadRecently(currentPage)
            MOVIES -> loadMergedCategory(
                currentPage,
                listOf(
                    "/type/movies",
                    "/bollywood",
                    "/language/hindi-dubbed",
                    "/genre/action"
                )
            )

            DUAL_AUDIO -> loadMergedCategory(
                currentPage,
                listOf("/language/dual-audio")
            )

            ONGOING -> loadMergedCategory(
                currentPage,
                listOf("/ongoing")
            )

            TV_SHOW -> loadMergedCategory(
                currentPage,
                listOf("/drama", "/type/series")
            )

            ANIME -> loadMergedCategory(
                currentPage,
                listOf("/anime", "/genre/animation")
            )

            else -> CategoryResult(emptyList(), false)
        }

        refreshPriorityCachesIfNeeded()

        val filtered = applyPriorityRules(category, routeResults.items)
            .distinctBy { contentKey(it.url) }
            .take(HOME_LIMIT)

        /*
         * Lazy loading is deliberately page-driven. We only report another
         * page when the current category actually returned enough information
         * to justify trying again.
         */
        val hasNext = routeResults.hasNext && filtered.isNotEmpty()

        return newHomePageResponse(
            request,
            filtered.map { it.toSearchResponse() },
            hasNext
        )
    }

    private data class CategoryResult(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private suspend fun loadRecently(page: Int): CategoryResult {
        val candidates = if (page == 1) {
            listOf("/")
        } else {
            listOf("/page/$page/", "/page/$page")
        }

        for (route in candidates) {
            val pageResult = getDocumentWithFallback(route) ?: continue
            val cards = if (page == 1) {
                recentlyUpdatedCards(pageResult.document)
            } else {
                pageResult.document.select(".movie-cards-container .movie-card")
            }

            val items = cards.mapNotNull(::parseCard)
                .distinctBy { contentKey(it.url) }

            if (items.isNotEmpty()) {
                val hasNext = if (page < RECENTLY_MAX_PAGES) {
                    hasNextPage(pageResult.document, page) || items.size >= HOME_LIMIT
                } else {
                    false
                }
                return CategoryResult(items, hasNext)
            }
        }

        return CategoryResult(emptyList(), false)
    }

    private suspend fun loadMergedCategory(
        page: Int,
        routes: List<String>
    ): CategoryResult {
        val merged = linkedMapOf<String, SiteItem>()
        var anyNext = false
        var atLeastOneSource = false

        for (baseRoute in routes) {
            val route = pageRoute(baseRoute, page)
            val result = getDocumentWithFallback(route) ?: continue
            atLeastOneSource = true

            val cards = result.document.select(".movie-cards-container .movie-card")
            cards.mapNotNull(::parseCard).forEach { item ->
                merged.putIfAbsent(contentKey(item.url), item)
            }

            anyNext = anyNext || hasNextPage(result.document, page)
            if (cards.size >= HOME_LIMIT) anyNext = true
        }

        return CategoryResult(
            merged.values.toList(),
            atLeastOneSource && anyNext
        )
    }

    private fun applyPriorityRules(
        category: String,
        items: List<SiteItem>
    ): List<SiteItem> {
        return when (category) {
            RECENTLY -> items

            ONGOING -> items.filter {
                contentKey(it.url) !in recentlyKeys
            }

            DUAL_AUDIO -> items.filter {
                contentKey(it.url) !in recentlyKeys &&
                    contentKey(it.url) !in ongoingKeys
            }

            MOVIES, TV_SHOW, ANIME -> items.filter {
                val key = contentKey(it.url)
                key !in recentlyKeys &&
                    key !in ongoingKeys &&
                    key !in dualAudioKeys
            }

            else -> items
        }
    }

    // ---------------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val merged = linkedMapOf<String, SiteItem>()

        val searchRoutes = listOf(
            "/search?q=$encoded",
            "/search?query=$encoded",
            "/search?search=$encoded"
        )

        for (route in searchRoutes) {
            val result = getDocumentWithFallback(route) ?: continue
            result.document.select(".movie-cards-container .movie-card")
                .mapNotNull(::parseCard)
                .forEach { merged.putIfAbsent(contentKey(it.url), it) }

            if (merged.isNotEmpty()) break
        }

        /*
         * The site's native search can change independently of the category
         * pages. Keep a bounded local fallback so search remains useful even
         * when the native endpoint returns an incomplete result set.
         */
        if (merged.isEmpty()) {
            val fallbackRoutes = listOf(
                "/type/movies",
                "/bollywood",
                "/language/hindi-dubbed",
                "/language/dual-audio",
                "/ongoing",
                "/drama",
                "/type/series",
                "/anime",
                "/genre/animation"
            )

            for (base in fallbackRoutes) {
                for (page in 1..SEARCH_MAX_PAGES) {
                    val route = pageRoute(base, page)
                    val result = getDocumentWithFallback(route) ?: break
                    val cards = result.document.select(".movie-cards-container .movie-card")
                    cards.mapNotNull(::parseCard).forEach { item ->
                        if (normalizeSearch(item.title).contains(normalizeSearch(q))) {
                            merged.putIfAbsent(contentKey(item.url), item)
                        }
                    }
                    if (cards.isEmpty() || !hasNextPage(result.document, page)) break
                }
            }
        }

        return merged.values
            .sortedWith(
                compareByDescending<SiteItem> {
                    searchScore(q, it.title)
                }.thenBy { it.title }
            )
            .filter { searchScore(q, it.title) >= 0.30 }
            .take(50)
            .map { it.toSearchResponse() }
    }

    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val path = pathFromUrl(url)
        val result = getDocumentWithFallback(path) ?: return null
        val document = result.document
        val json = parsePlayerJson(document)

        val title = cleanTitle(
            json?.optString("title")?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.title().substringBefore("•").trim()
        ).ifBlank { "Movie Link BD" }

        val poster = firstUsefulUrl(
            json?.optString("poster"),
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[name=twitter:image]")?.attr("content"),
            document.selectFirst(".movie-card img")?.let(::extractImageUrl)
        )

        val contentType = json?.optString("content_type")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: detectContentType(path, document)

        if (contentType == "movie" || path.startsWith("/movie/")) {
            return newMovieLoadResponse(
                title,
                canonicalPrimaryUrl(path),
                TvType.Movie,
                canonicalPrimaryUrl(path)
            ) {
                posterUrl = poster
                plot = extractPlot(document)
                year = extractYear(title, document)
            }
        }

        val tvType = if (
            contentType == "anime" || path.startsWith("/anime/")
        ) {
            TvType.Anime
        } else {
            TvType.TvSeries
        }

        val episodes = parseEpisodes(
            json,
            canonicalPrimaryUrl(path)
        )

        return newTvSeriesLoadResponse(
            title,
            canonicalPrimaryUrl(path),
            tvType,
            episodes
        ) {
            posterUrl = poster
            plot = extractPlot(document)
            year = extractYear(title, document)
        }
    }

    // ---------------------------------------------------------------------
    // LINKS / DIRECT CDN SOURCE
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val separator = data.indexOf("||")
        val pageUrl = if (separator >= 0) data.substring(0, separator) else data
        val episodeId = if (separator >= 0) data.substring(separator + 2).trim() else null

        val path = pathFromUrl(pageUrl)
        val result = getDocumentWithFallback(path) ?: return false
        val json = parsePlayerJson(result.document) ?: return false
        val episodes = json.optJSONArray("episodes") ?: return false

        var emitted = false

        for (i in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(i) ?: continue

            if (!episodeId.isNullOrBlank() && episode.optString("id") != episodeId) {
                continue
            }

            val sources = episode.optJSONArray("sources") ?: continue

            for (j in 0 until sources.length()) {
                val source = sources.optJSONObject(j) ?: continue

                val streamUrl = source.optString("url").trim()
                if (!isHttpUrl(streamUrl)) continue

                val quality = parseQuality(
                    source.opt("quality"),
                    source.optString("name")
                )

                val provider = source.optString("provider")
                    .trim()
                    .ifBlank { "MLBD CDN" }

                val audio = source.optString("audio")
                    .trim()
                    .ifBlank {
                        source.optJSONArray("audio_languages")
                            ?.let { array ->
                                buildString {
                                    for (index in 0 until array.length()) {
                                        if (index > 0) append(", ")
                                        append(array.optString(index))
                                    }
                                }
                            }
                            .orEmpty()
                    }

                val linkName = buildString {
                    append(provider)
                    if (quality > 0 && quality != Qualities.Unknown.value) {
                        append(" - ${quality}p")
                    }
                    if (audio.isNotBlank()) {
                        append(" - $audio")
                    }
                }

                callback(
                    newExtractorLink(
                        name,
                        linkName,
                        streamUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = canonicalPrimaryUrl(path)
                    }
                )

                emitted = true

                val subtitles = source.optJSONArray("external_subtitles")
                if (subtitles != null) {
                    for (k in 0 until subtitles.length()) {
                        val subtitle = subtitles.optJSONObject(k) ?: continue
                        val subtitleUrl = subtitle.optString("url").trim()
                        if (!isHttpUrl(subtitleUrl)) continue

                        val language = subtitle.optString("label")
                            .trim()
                            .ifBlank { subtitle.optString("language").trim() }
                            .ifBlank { "Subtitle" }

                        subtitleCallback(
                            newSubtitleFile(language, subtitleUrl)
                        )
                    }
                }
            }

            if (!episodeId.isNullOrBlank()) break
        }

        return emitted
    }

    // ---------------------------------------------------------------------
    // CARD PARSING / POSTER EXTRACTION
    // ---------------------------------------------------------------------

    private fun parseCard(card: Element): SiteItem? {
        /*
         * MovieLinkBD cards have a dedicated .title anchor. Some page variants
         * can move the title around, so we keep multiple fallbacks instead of
         * depending on a single exact DOM selector.
         */
        val titleElement = card.selectFirst(
            ".content a.title, a.title, .content .title, h2.title, h3.title"
        )

        val href = firstNonBlank(
            titleElement?.attr("href"),
            card.selectFirst("a[href*='/movie/'], a[href*='/series/'], a[href*='/drama/'], a[href*='/anime/']")
                ?.attr("href")
        ) ?: return null

        val title = firstNonBlank(
            titleElement?.text(),
            card.selectFirst("img")?.attr("alt"),
            card.selectFirst("img")?.attr("title")
        )?.let(::cleanTitle)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val url = absolutePrimary(href)
        val path = pathFromUrl(url)

        if (!isContentPath(path)) return null

        /*
         * THIS IS THE IMPORTANT POSTER FIX.
         * The live HTML uses:
         *   data-src = real poster URL
         *   src      = loading placeholder
         * So we always prefer data-src/data-lazy-src/etc. before src.
         */
        val poster = extractCardPoster(card)

        val type = when {
            path.startsWith("/anime/", true) -> TvType.Anime
            path.startsWith("/series/", true) -> TvType.TvSeries
            path.startsWith("/drama/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return SiteItem(
            title = title,
            url = url,
            poster = poster,
            type = type
        )
    }

    private fun extractCardPoster(card: Element): String? {
        val pictureSource = card.selectFirst("picture source[srcset], picture source[data-srcset]")
        val picturePoster = firstNonBlank(
            pictureSource?.attr("data-srcset"),
            pictureSource?.attr("srcset")
        )?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.substringBefore(' ')

        val image = card.selectFirst("img")

        val raw = firstNonBlank(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-image"),
            image?.attr("data-poster"),
            picturePoster,
            image?.attr("src")
        ) ?: return null

        val resolved = absoluteResourceUrl(raw)

        if (isPlaceholderImage(resolved)) {
            return null
        }

        return resolved
    }

    private fun recentlyUpdatedCards(document: Document): List<Element> {
        val heading = document.select(".mlbd-page-head").firstOrNull {
            it.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.equals("RECENTLY UPDATED", ignoreCase = true) == true
        }

        if (heading != null) {
            /* The card grid is the next sibling of the heading block. */
            var node = heading.nextElementSibling()
            var steps = 0
            while (node != null && steps < 4) {
                val cards = node.select(".movie-card")
                if (cards.isNotEmpty()) return cards
                node = node.nextElementSibling()
                steps++
            }
        }

        return document.select(".movie-cards-container .movie-card")
    }

    // ---------------------------------------------------------------------
    // PRIORITY CACHE / DEDUP
    // ---------------------------------------------------------------------

    private suspend fun refreshPriorityCachesIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - priorityCacheAt < CACHE_MS) return

        recentlyKeys.clear()
        ongoingKeys.clear()
        dualAudioKeys.clear()

        getDocumentWithFallback("/")?.document?.let { document ->
            recentlyUpdatedCards(document)
                .mapNotNull(::parseCard)
                .forEach { recentlyKeys += contentKey(it.url) }
        }

        getDocumentWithFallback("/ongoing")?.document?.let { document ->
            document.select(".movie-cards-container .movie-card")
                .mapNotNull(::parseCard)
                .forEach { ongoingKeys += contentKey(it.url) }
        }

        getDocumentWithFallback("/language/dual-audio")?.document?.let { document ->
            document.select(".movie-cards-container .movie-card")
                .mapNotNull(::parseCard)
                .forEach { dualAudioKeys += contentKey(it.url) }
        }

        priorityCacheAt = now
    }

    // ---------------------------------------------------------------------
    // NETWORK / MIRRORS
    // ---------------------------------------------------------------------

    private data class PageResult(
        val domain: String,
        val path: String,
        val document: Document
    )

    private suspend fun getDocumentWithFallback(path: String): PageResult? {
        val normalized = normalizePath(path)

        for (domain in DOMAINS) {
            val url = domain + normalized

            try {
                val response = app.get(
                    url,
                    headers = browserHeaders(domain + "/")
                )

                if (response.code !in 200..399) continue

                val document = response.document
                if (isUsableDocument(normalized, document)) {
                    return PageResult(domain, normalized, document)
                }
            } catch (_: Throwable) {
                // Try the next mirror with the exact same path.
            }
        }

        return null
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
            ),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private fun isUsableDocument(path: String, document: Document): Boolean {
        if (path == "/") {
            return document.selectFirst(
                ".movie-cards-container, .mlbd-page-head, body"
            ) != null
        }

        return document.selectFirst(
            "#mlbdInlinePlayerData, .movie-cards-container .movie-card, .movie-cards-container"
        ) != null
    }

    // ---------------------------------------------------------------------
    // JSON / EPISODES
    // ---------------------------------------------------------------------

    private fun parsePlayerJson(document: Document): JSONObject? {
        val script = document.selectFirst(PLAYER_JSON_SELECTOR) ?: return null
        val raw = script.data()
            .ifBlank { script.html() }
            .trim()

        if (raw.isBlank()) return null

        return runCatching {
            JSONObject(raw)
        }.getOrNull()
    }

    private fun parseEpisodes(
        json: JSONObject?,
        pageUrl: String
    ): List<Episode> {
        val array = json?.optJSONArray("episodes") ?: return emptyList()
        val result = arrayListOf<Episode>()

        for (i in 0 until array.length()) {
            val episode = array.optJSONObject(i) ?: continue
            if (episode.optString("kind").equals("movie", true)) continue

            val id = episode.optString("id").trim()
            if (id.isBlank()) continue

            val label = episode.optString("label")
                .trim()
                .ifBlank { "Episode ${i + 1}" }

            val number = episode.optInt("number", 0)
                .takeIf { it > 0 }
                ?: extractEpisodeNumber(label, i + 1)

            val season = episode.optInt("season", 0)
                .takeIf { it > 0 }

            result += newEpisode("$pageUrl||$id") {
                this.name = label
                this.episode = number
                this.season = season
            }
        }

        return result.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    // ---------------------------------------------------------------------
    // UTILITIES
    // ---------------------------------------------------------------------

    private fun pageRoute(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean {
        if (document.select(".movie-card").size >= HOME_LIMIT) {
            return true
        }

        return document.select("a[href]").any { anchor ->
            val text = anchor.text()
                .trim()
                .lowercase(Locale.ROOT)
            val rel = anchor.attr("rel")
                .trim()
                .lowercase(Locale.ROOT)
            val href = anchor.attr("href")

            text == "next" ||
                text.contains("next") ||
                rel == "next" ||
                anchor.attr("aria-label").contains("next", true) ||
                href.contains("page=${currentPage + 1}", true) ||
                href.contains("/page/${currentPage + 1}", true)
        }
    }

    private fun detectContentType(path: String, document: Document): String {
        return when {
            path.startsWith("/movie/", true) -> "movie"
            path.startsWith("/anime/", true) -> "anime"
            path.startsWith("/series/", true) -> "series"
            path.startsWith("/drama/", true) -> "series"
            document.selectFirst("#mlbdEpisodeDownloadGrid, .ep-card") != null -> "series"
            else -> "movie"
        }
    }

    private fun isContentPath(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.startsWith("/movie/") ||
            lower.startsWith("/series/") ||
            lower.startsWith("/drama/") ||
            lower.startsWith("/anime/")
    }

    private fun contentKey(url: String): String {
        return pathFromUrl(url)
            .substringBefore('#')
            .substringBefore('?')
            .removeSuffix("/")
            .lowercase(Locale.ROOT)
    }

    private fun pathFromUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            buildString {
                append(uri.rawPath.ifBlank { "/" })
                if (!uri.rawQuery.isNullOrBlank()) {
                    append('?').append(uri.rawQuery)
                }
            }.let(::normalizePath)
        }.getOrElse {
            val noScheme = url.substringAfter("://", url)
            val slash = noScheme.indexOf('/')
            normalizePath(
                if (slash >= 0) noScheme.substring(slash) else "/"
            )
        }
    }

    private fun normalizePath(path: String): String {
        val clean = path.trim()
        if (clean.isBlank()) return "/"

        if (clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return pathFromUrl(clean)
        }

        return if (clean.startsWith('/')) clean else "/$clean"
    }

    private fun absolutePrimary(href: String): String {
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            return href
        }
        return PRIMARY + normalizePath(href)
    }

    private fun canonicalPrimaryUrl(path: String): String =
        absolutePrimary(normalizePath(path))

    private fun absoluteResourceUrl(value: String): String {
        val clean = value
            .trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (clean.isBlank()) return clean

        if (clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return clean
        }

        if (clean.startsWith("//")) {
            return "https:$clean"
        }

        return runCatching {
            URI(PRIMARY + "/").resolve(clean).toString()
        }.getOrElse {
            absolutePrimary(clean)
        }
    }

    private fun firstUsefulUrl(vararg values: String?): String? {
        for (value in values) {
            if (value.isNullOrBlank()) continue
            val resolved = absoluteResourceUrl(value)
            if (resolved.isBlank()) continue
            if (isPlaceholderImage(resolved)) continue
            return resolved
        }
        return null
    }

    private fun extractImageUrl(element: Element): String? {
        return firstUsefulUrl(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("data-poster"),
            element.attr("src")
        )
    }

    private fun isPlaceholderImage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith("mlbd_load.svg") ||
            lower.contains("/images/mlbd_load.svg") ||
            lower.contains("placeholder") ||
            lower.contains("spacer.gif") ||
            lower.startsWith("data:image/")
    }

    private fun extractPlot(document: Document): String? {
        val selectors = listOf(
            ".story-text",
            ".storyline-box .story-text",
            ".movie-extra-info",
            ".description",
            ".plot",
            "meta[name=description]"
        )

        for (selector in selectors) {
            val element = document.selectFirst(selector) ?: continue
            val text = if (element.tagName().equals("meta", true)) {
                element.attr("content")
            } else {
                element.text()
            }.trim()

            if (text.isNotBlank()) return text
        }

        return null
    }

    private fun extractYear(title: String, document: Document): Int? {
        val texts = buildList {
            add(title)
            document.select("meta[property=og:title], h1, title").forEach {
                add(it.attr("content").ifBlank { it.text() })
            }
        }

        return texts.asSequence()
            .mapNotNull {
                Regex("\\b(19|20)\\d{2}\\b")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }
            .firstOrNull()
    }

    private fun parseQuality(raw: Any?, name: String): Int {
        when (raw) {
            is Number -> {
                val value = raw.toInt()
                if (value > 0) return value
            }

            is String -> {
                val value = raw.toIntOrNull()
                if (value != null && value > 0) return value

                getQualityFromName(raw)?.let { quality ->
                    if (quality != Qualities.Unknown.value) return quality
                }
            }
        }

        return getQualityFromName(name)
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractEpisodeNumber(text: String, fallback: Int): Int {
        return Regex(
            "(?:episode|ep|e)\\s*[-._#]?\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: fallback
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    private fun normalizeSearch(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(Regex("[^a-z0-9\\p{L}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun searchScore(query: String, title: String): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)
        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0
        if (t.contains(q)) return 0.95

        val queryTokens = q.split(' ').filter { it.length >= 2 }
        val titleTokens = t.split(' ').filter { it.length >= 2 }
        if (queryTokens.isEmpty() || titleTokens.isEmpty()) return 0.0

        val tokenScore = queryTokens.map { qt ->
            titleTokens.maxOfOrNull { tt ->
                when {
                    tt == qt -> 1.0
                    tt.startsWith(qt) || qt.startsWith(tt) -> 0.90
                    else -> stringSimilarity(qt, tt)
                }
            } ?: 0.0
        }.average()

        return tokenScore.coerceIn(0.0, 1.0)
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
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

        return previous[b.length]
    }
}
