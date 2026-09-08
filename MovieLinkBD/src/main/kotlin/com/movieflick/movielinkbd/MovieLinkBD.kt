package com.movieflick.movielinkbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Movie Link BD CloudStream provider.
 *
 * Design goals:
 * - Fast Home loading with real page-based lazy pagination.
 * - Stable six-category Home layout.
 * - Recently -> Ongoing -> Dual Audio priority/deduplication.
 * - Primary domain first, then exact-path mirror failover.
 * - Every Play action performs a fresh page/player-data request.
 * - Signed/tokenized CDN URLs are never cached by this provider.
 * - When mirrors publish different fresh CDN URLs, all fresh candidates can
 *   be offered to CloudStream.
 * - Direct media is returned; webpage advertisements/iframes are not loaded.
 * - Fuzzy, order-independent search with typo tolerance and local fallback.
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

        // Priority cache is only for category deduplication, never for media URLs.
        const val PRIORITY_CACHE_MS = 120_000L

        // Keep Home rows lightweight.
        const val HOME_LIMIT = 25

        // Prevent unlimited scraping while still allowing useful pagination.
        const val RECENTLY_MAX_PAGES = 12
        const val SEARCH_SCAN_PAGES = 3
        const val SEARCH_RESULT_LIMIT = 50

        // Fuzzy-search cutoffs.
        const val STRONG_SEARCH_SCORE = 0.78
        const val NORMAL_SEARCH_SCORE = 0.42
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

    /**
     * CloudStream expects: DATA KEY -> DISPLAY NAME.
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
        val type: TvType
    )

    private data class CategoryResult(
        val items: List<SiteItem>,
        val hasNext: Boolean
    )

    private data class PageResult(
        val domain: String,
        val path: String,
        val document: Document
    ) {
        fun absoluteUrl(): String = domain + path
    }

    private data class FreshSource(
        val streamUrl: String,
        val quality: Int,
        val provider: String,
        val audio: String,
        val referer: String,
        val mirrorIndex: Int,
        val sourceName: String
    )

    private var priorityCacheAt = 0L
    private val recentlyKeys = linkedSetOf<String>()
    private val ongoingKeys = linkedSetOf<String>()
    private val dualAudioKeys = linkedSetOf<String>()

    // ---------------------------------------------------------------------
    // HOME
    // ---------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val category = request.data
        val currentPage = page.coerceAtLeast(1)

        refreshPriorityCachesIfNeeded()

        val result = when (category) {
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
                listOf(
                    "/drama",
                    "/type/series"
                )
            )

            ANIME -> loadMergedCategory(
                currentPage,
                listOf(
                    "/anime",
                    "/genre/animation"
                )
            )

            else -> CategoryResult(emptyList(), false)
        }

        val filtered = applyPriorityRules(category, result.items)
            .distinctBy { contentKey(it.url) }
            .take(HOME_LIMIT)

        /*
         * True page-driven lazy loading:
         * CloudStream only asks for another page when this page returned
         * usable content and the source indicates another page may exist.
         */
        val hasNext = result.hasNext && filtered.isNotEmpty()

        return newHomePageResponse(
            request,
            filtered.map { it.toSearchResponse() },
            hasNext
        )
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

            TvType.Anime -> newMovieSearchResponse(
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

    private suspend fun loadRecently(page: Int): CategoryResult {
        val candidates = if (page <= 1) {
            listOf("/")
        } else {
            listOf(
                "/page/$page/",
                "/page/$page"
            )
        }

        for (route in candidates) {
            val result = getDocumentWithFallback(route) ?: continue

            val cards = if (page <= 1) {
                recentlyUpdatedCards(result.document)
            } else {
                result.document.select(".movie-cards-container .movie-card")
            }

            val items = cards
                .mapNotNull(::parseCard)
                .distinctBy { contentKey(it.url) }

            if (items.isEmpty()) continue

            val next = if (page < RECENTLY_MAX_PAGES) {
                hasNextPage(result.document, page) || items.size >= HOME_LIMIT
            } else {
                false
            }

            return CategoryResult(items, next)
        }

        return CategoryResult(emptyList(), false)
    }

    private suspend fun loadMergedCategory(
        page: Int,
        routes: List<String>
    ): CategoryResult {
        val merged = linkedMapOf<String, SiteItem>()
        var anyNext = false
        var successfulRoute = false

        for (baseRoute in routes) {
            val route = pageRoute(baseRoute, page)
            val result = getDocumentWithFallback(route) ?: continue
            successfulRoute = true

            val cards = result.document
                .select(".movie-cards-container .movie-card")

            cards.mapNotNull(::parseCard).forEach { item ->
                merged.putIfAbsent(contentKey(item.url), item)
            }

            if (hasNextPage(result.document, page) || cards.size >= HOME_LIMIT) {
                anyNext = true
            }
        }

        return CategoryResult(
            merged.values.toList(),
            successfulRoute && anyNext
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
                val key = contentKey(it.url)
                key !in recentlyKeys && key !in ongoingKeys
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
        val original = query.trim()
        if (original.isBlank()) return emptyList()

        val variants = buildSearchVariants(original)
        val merged = linkedMapOf<String, SiteItem>()

        /*
         * Phase 1: use the site's own search with several equivalent query
         * shapes. Do not stop at the first non-empty result; merge all useful
         * native responses so one search endpoint quirk cannot hide content.
         */
        for (variant in variants) {
            val encoded = URLEncoder.encode(variant, "UTF-8")

            val routes = listOf(
                "/search?q=$encoded",
                "/search?query=$encoded",
                "/search?search=$encoded"
            )

            for (route in routes) {
                val result = getDocumentWithFallback(route) ?: continue

                result.document
                    .select(".movie-cards-container .movie-card")
                    .mapNotNull(::parseCard)
                    .forEach { item ->
                        merged.putIfAbsent(contentKey(item.url), item)
                    }
            }
        }

        val bestNativeScore = merged.values
            .maxOfOrNull { searchScore(original, it.title) }
            ?: 0.0

        /*
         * Phase 2: bounded local fallback.
         *
         * This is what makes approximate searches useful when the site's
         * native search is too strict. We deliberately scan only a few pages
         * of the principal feeds to keep search responsive.
         */
        if (merged.isEmpty() || bestNativeScore < STRONG_SEARCH_SCORE) {
            val scanRoutes = listOf(
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

            for (base in scanRoutes) {
                for (page in 1..SEARCH_SCAN_PAGES) {
                    val result = getDocumentWithFallback(
                        pageRoute(base, page)
                    ) ?: break

                    val cards = result.document
                        .select(".movie-cards-container .movie-card")

                    if (cards.isEmpty()) break

                    cards.mapNotNull(::parseCard).forEach { item ->
                        val score = searchScore(original, item.title)

                        /*
                         * Keep reasonably close titles immediately.
                         * All candidates are still ranked below.
                         */
                        if (score >= NORMAL_SEARCH_SCORE) {
                            merged.putIfAbsent(
                                contentKey(item.url),
                                item
                            )
                        }
                    }

                    if (!hasNextPage(result.document, page)) break
                }
            }
        }

        /*
         * Phase 3: fuzzy ranking.
         *
         * Results are not required to contain the query verbatim. Token
         * overlap, token similarity, phrase containment, compact matching
         * and year matching are combined.
         */
        return merged.values
            .map { item ->
                item to searchScore(original, item.title)
            }
            .filter { it.second >= NORMAL_SEARCH_SCORE }
            .sortedWith(
                compareByDescending<Pair<SiteItem, Double>> { it.second }
                    .thenBy { it.first.title.lowercase(Locale.ROOT) }
            )
            .take(SEARCH_RESULT_LIMIT)
            .map { it.first.toSearchResponse() }
    }

    private fun buildSearchVariants(query: String): LinkedHashSet<String> {
        val normalized = normalizeSearch(query)
        val compact = normalized.replace(" ", "")

        return linkedSetOf<String>().apply {
            add(query)
            if (normalized.isNotBlank()) add(normalized)

            /*
             * Remove very common metadata terms so searches like
             * "Mirzapur movie 2026 hd" still find the actual title.
             */
            val reduced = normalized
                .split(' ')
                .filterNot {
                    it in setOf(
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
                }
                .joinToString(" ")

            if (reduced.isNotBlank()) add(reduced)
            if (compact.isNotBlank()) add(compact)
        }
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {
        val q = normalizeSearch(query)
        val t = normalizeSearch(title)

        if (q.isBlank() || t.isBlank()) return 0.0
        if (q == t) return 1.0

        val qCompact = q.replace(" ", "")
        val tCompact = t.replace(" ", "")

        var score = 0.0

        if (t.contains(q)) {
            score = max(score, 0.98)
        }

        if (
            qCompact.isNotBlank() &&
            tCompact.contains(qCompact)
        ) {
            val ratio = qCompact.length.toDouble() /
                tCompact.length.coerceAtLeast(1).toDouble()

            score = max(
                score,
                0.88 + min(0.10, ratio * 0.10)
            )
        }

        val qTokens = q.split(' ').filter { it.length >= 2 }
        val tTokens = t.split(' ').filter { it.length >= 2 }

        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return score.coerceIn(0.0, 1.0)
        }

        /*
         * Each query word finds its best matching title word.
         * This makes word order irrelevant.
         */
        val tokenScores = qTokens.map { queryToken ->
            tTokens.maxOfOrNull { titleToken ->
                when {
                    queryToken == titleToken -> 1.0

                    titleToken.startsWith(queryToken) ||
                        queryToken.startsWith(titleToken) -> 0.93

                    titleToken.contains(queryToken) ||
                        queryToken.contains(titleToken) -> 0.88

                    else -> stringSimilarity(queryToken, titleToken)
                }
            } ?: 0.0
        }

        val averageTokenScore = tokenScores.average()
        score = max(score, averageTokenScore * 0.94)

        /*
         * Coverage bonus: reward searches where most query words were found
         * reasonably well.
         */
        val covered = tokenScores.count { it >= 0.60 }
        if (qTokens.isNotEmpty()) {
            val coverage = covered.toDouble() / qTokens.size.toDouble()
            score = max(score, 0.50 + coverage * 0.45)
        }

        /*
         * Year-aware bonus.
         */
        val queryYear = Regex("\\b(19|20)\\d{2}\\b")
            .find(q)
            ?.value
            ?.toIntOrNull()

        if (queryYear != null) {
            val titleYear = Regex("\\b(19|20)\\d{2}\\b")
                .find(t)
                ?.value
                ?.toIntOrNull()

            when {
                titleYear == queryYear -> score = max(score, 0.97)
                titleYear != null -> score *= 0.92
            }
        }

        return score.coerceIn(0.0, 1.0)
    }

    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isBlank() || b.isBlank()) return 0.0

        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length)

        return if (longest == 0) {
            1.0
        } else {
            1.0 - distance.toDouble() / longest.toDouble()
        }
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

    // ---------------------------------------------------------------------
    // DETAIL / EPISODES
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val path = pathFromUrl(url)
        val result = getDocumentWithFallback(path) ?: return null
        val document = result.document
        val json = parsePlayerJson(document)

        val title = cleanTitle(
            json?.optString("title")
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.title().substringBefore("•").trim()
        ).ifBlank {
            "Movie Link BD"
        }

        val poster = firstUsefulUrl(
            json?.optString("poster"),
            document
                .selectFirst("meta[property=og:image]")
                ?.attr("content"),
            document
                .selectFirst("meta[name=twitter:image]")
                ?.attr("content"),
            document.selectFirst(".movie-card img")
                ?.let(::extractImageUrl)
        )

        val contentType = json
            ?.optString("content_type")
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
            contentType == "anime" ||
            path.startsWith("/anime/", true)
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
    // LINKS / FRESH TOKEN RESOLUTION
    // ---------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val separator = data.indexOf("||")

        val pageUrl = if (separator >= 0) {
            data.substring(0, separator)
        } else {
            data
        }

        val episodeId = if (separator >= 0) {
            data.substring(separator + 2).trim()
        } else {
            null
        }

        val path = pathFromUrl(pageUrl)
        if (path.isBlank()) return false

        /*
         * CRITICAL:
         *
         * We intentionally do NOT cache the playable URL.
         * Every Play action comes through here and forces a fresh request to
         * the MovieLinkBD page(s), so newly generated tokenized CDN URLs can
         * be picked up immediately.
         *
         * We also query all mirrors in priority order. Therefore:
         * .tv works -> use its fresh source
         * .tv works but publishes a different/bad source -> .li/.one are also
         * checked for fresh candidates.
         * .tv is down -> .li -> .one.
         */
        val freshPages = getFreshPlayerPages(path)

        if (freshPages.isEmpty()) return false

        val freshSources = linkedMapOf<String, FreshSource>()

        for ((mirrorIndex, page) in freshPages.withIndex()) {
            val json = parsePlayerJson(page.document)

            /*
             * Primary authoritative source: published JSON player data.
             */
            if (json != null) {
                extractJsonSources(
                    json = json,
                    episodeId = episodeId,
                    page = page,
                    mirrorIndex = mirrorIndex
                ).forEach { source ->
                    freshSources.putIfAbsent(
                        sourceDedupKey(source),
                        source
                    )
                }
            }

            /*
             * Defensive fallback:
             * if a future site revision changes the JSON shape, scan the
             * actual page for direct media candidates instead of returning
             * nothing.
             */
            if (json == null || freshSources.isEmpty()) {
                extractFallbackMediaSources(
                    page.document,
                    page.absoluteUrl(),
                    mirrorIndex
                ).forEach { source ->
                    if (episodeId.isNullOrBlank() || episodeId == source.sourceName) {
                        freshSources.putIfAbsent(
                            sourceDedupKey(source),
                            source
                        )
                    }
                }
            }
        }

        if (freshSources.isEmpty()) return false

        /*
         * Emit every distinct fresh candidate. CloudStream can then use the
         * available working source without us persisting an expired token.
         */
        for (source in freshSources.values) {
            val linkName = buildString {
                append(source.provider)

                if (
                    source.quality > 0 &&
                    source.quality != Qualities.Unknown.value
                ) {
                    append(" - ${source.quality}p")
                }

                if (source.audio.isNotBlank()) {
                    append(" - ${source.audio}")
                }

                /*
                 * Only distinguish mirrors when there is more than one domain
                 * candidate. This makes troubleshooting easier without
                 * changing the actual source URL.
                 */
                if (freshPages.size > 1) {
                    append(" - Mirror ${source.mirrorIndex + 1}")
                }
            }

            callback(
                newExtractorLink(
                    name,
                    linkName,
                    source.streamUrl,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = source.quality
                    this.referer = source.referer
                }
            )
        }

        /*
         * Subtitles are also resolved fresh from the same current player JSON.
         */
        emitFreshSubtitles(
            freshPages,
            episodeId,
            subtitleCallback
        )

        return true
    }

    private suspend fun getFreshPlayerPages(
        path: String
    ): List<PageResult> {
        val normalized = normalizePath(path)
        val result = ArrayList<PageResult>()

        /*
         * We intentionally do not store the returned PageResult in any cache.
         * This method is called on every playback attempt.
         */
        for (domain in DOMAINS) {
            val pageUrl = domain + normalized

            try {
                val response = app.get(
                    pageUrl,
                    headers = browserHeaders(
                        referer = domain + "/"
                    )
                )

                if (response.code !in 200..399) continue

                val document = response.document

                /*
                 * Detail pages need player JSON. Some pages can still be
                 * useful without it if a direct media element exists.
                 */
                if (isUsablePlaybackDocument(document)) {
                    result += PageResult(
                        domain = domain,
                        path = normalized,
                        document = document
                    )
                }
            } catch (_: Throwable) {
                // Continue with the next mirror.
            }
        }

        return result
    }

    private fun extractJsonSources(
        json: JSONObject,
        episodeId: String?,
        page: PageResult,
        mirrorIndex: Int
    ): List<FreshSource> {
        val episodes = json.optJSONArray("episodes")
            ?: return emptyList()

        val output = ArrayList<FreshSource>()

        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue

            if (
                !episodeId.isNullOrBlank() &&
                episode.optString("id") != episodeId
            ) {
                continue
            }

            /*
             * For a normal movie, the JSON contains a single kind=movie
             * episode. For series, episodeId narrows it to one episode.
             */
            val sources = episode.optJSONArray("sources") ?: continue

            for (sourceIndex in 0 until sources.length()) {
                val source = sources.optJSONObject(sourceIndex) ?: continue

                val streamUrl = source.optString("url").trim()
                if (!isHttpUrl(streamUrl)) continue

                val provider = source.optString("provider")
                    .trim()
                    .ifBlank { "MLBD CDN" }

                val audio = source.optString("audio")
                    .trim()
                    .ifBlank {
                        source.optJSONArray("audio_languages")?.let { array ->
                            buildString {
                                for (i in 0 until array.length()) {
                                    val language = array.optString(i).trim()
                                    if (language.isBlank()) continue
                                    if (isNotEmpty()) append(", ")
                                    append(language)
                                }
                            }
                        }.orEmpty()
                    }

                val quality = parseQuality(
                    source.opt("quality"),
                    source.optString("name")
                )

                val sourceName = episode.optString("id")
                    .trim()
                    .ifBlank {
                        episode.optString("label")
                            .trim()
                            .ifBlank { "episode-$index" }
                    }

                output += FreshSource(
                    streamUrl = streamUrl,
                    quality = quality,
                    provider = provider,
                    audio = audio,
                    referer = page.absoluteUrl(),
                    mirrorIndex = mirrorIndex,
                    sourceName = sourceName
                )
            }
        }

        return output
    }

    private fun sourceDedupKey(
        source: FreshSource
    ): String {
        /*
         * Keep URLs distinct because tokenized URLs can represent different
         * fresh sessions. The same exact URL from two mirrors only needs one
         * emitted candidate.
         */
        return source.streamUrl.trim()
    }

    private suspend fun emitFreshSubtitles(
        pages: List<PageResult>,
        episodeId: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emitted = linkedSetOf<String>()

        for (page in pages) {
            val json = parsePlayerJson(page.document) ?: continue
            val episodes = json.optJSONArray("episodes") ?: continue

            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue

                if (
                    !episodeId.isNullOrBlank() &&
                    episode.optString("id") != episodeId
                ) {
                    continue
                }

                val sources = episode.optJSONArray("sources") ?: continue

                for (j in 0 until sources.length()) {
                    val source = sources.optJSONObject(j) ?: continue
                    val subs = source.optJSONArray("external_subtitles")
                        ?: continue

                    for (k in 0 until subs.length()) {
                        val subtitle = subs.optJSONObject(k) ?: continue
                        val url = subtitle.optString("url").trim()

                        if (!isHttpUrl(url)) continue
                        if (!emitted.add(url)) continue

                        val language = subtitle.optString("label")
                            .trim()
                            .ifBlank {
                                subtitle.optString("language").trim()
                            }
                            .ifBlank { "Subtitle" }

                        subtitleCallback(
                            newSubtitleFile(language, url)
                        )
                    }
                }
            }
        }
    }

    private fun extractFallbackMediaSources(
        document: Document,
        baseUrl: String,
        mirrorIndex: Int
    ): List<FreshSource> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val clean = raw
                .trim()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim('"', '\'', '`', ',', ';', ')', ']', '}')

            if (!isHttpUrl(clean)) return
            found.add(clean)
        }

        /*
         * DOM-based fallback.
         */
        document.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-manifest]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-video"))
            add(element.attr("data-file"))
            add(element.attr("data-url"))
            add(element.attr("data-source"))
            add(element.attr("data-stream"))
            add(element.attr("data-manifest"))
        }

        /*
         * Raw HTML fallback for the site's known CDN host and generic media
         * URLs. We do not extract advertising iframe URLs.
         */
        val html = document.html()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val mediaRegex = Regex(
            """https?://[^"'<>\s\\]+(?:\.m3u8|\.mp4|\.mkv|\.webm|\.mov|\.m4v|\.avi|\.flv|\.ts)(?:\?[^"'<>\s\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        mediaRegex.findAll(html).forEach {
            add(it.value)
        }

        return found.map { url ->
            FreshSource(
                streamUrl = url,
                quality = getQualityFromName(url),
                provider = "MLBD Direct",
                audio = "",
                referer = baseUrl,
                mirrorIndex = mirrorIndex,
                sourceName = ""
            )
        }
    }

    // ---------------------------------------------------------------------
    // CARD PARSING / POSTERS
    // ---------------------------------------------------------------------

    private fun parseCard(card: Element): SiteItem? {
        val titleElement = card.selectFirst(
            ".content a.title, " +
                "a.title, " +
                ".content .title, " +
                "h2.title, " +
                "h3.title"
        )

        val href = firstNonBlank(
            titleElement?.attr("href"),
            card.selectFirst(
                "a[href*='/movie/'], " +
                    "a[href*='/series/'], " +
                    "a[href*='/drama/'], " +
                    "a[href*='/anime/']"
            )?.attr("href")
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
        val pictureSource = card.selectFirst(
            "picture source[srcset], " +
                "picture source[data-srcset]"
        )

        val picturePoster = firstNonBlank(
            pictureSource?.attr("data-srcset"),
            pictureSource?.attr("srcset")
        )
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.substringBefore(' ')

        val image = card.selectFirst(
            ".image-container img, img"
        )

        /*
         * MovieLinkBD currently publishes the real image in data-src while
         * src points to /images/mlbd_load.svg.
         */
        val raw = firstNonBlank(
            image?.attr("data-src"),
            image?.attr("data-lazy-src"),
            image?.attr("data-original"),
            image?.attr("data-image"),
            image?.attr("data-poster"),
            image?.attr("data-url"),
            image?.attr("srcset"),
            picturePoster,
            image?.attr("src")
        ) ?: return null

        /*
         * If srcset was selected, keep only the actual URL portion.
         */
        val cleanedRaw = raw
            .trim()
            .split(',')
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?: raw

        val resolved = absoluteResourceUrl(cleanedRaw)

        if (isPlaceholderImage(resolved)) {
            /*
             * One more pass over all known image attributes. This protects
             * against markup variants where the first candidate is the
             * placeholder.
             */
            val alternatives = listOf(
                image?.attr("data-src"),
                image?.attr("data-lazy-src"),
                image?.attr("data-original"),
                image?.attr("data-image"),
                image?.attr("data-poster"),
                picturePoster
            )

            for (candidate in alternatives) {
                val alternative = candidate
                    ?.trim()
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.substringBefore(' ')
                    ?: continue

                val alternativeResolved =
                    absoluteResourceUrl(alternative)

                if (
                    alternativeResolved.isNotBlank() &&
                    !isPlaceholderImage(alternativeResolved)
                ) {
                    return alternativeResolved
                }
            }

            return null
        }

        return resolved
    }

    private fun recentlyUpdatedCards(
        document: Document
    ): List<Element> {
        val heading = document
            .select(".mlbd-page-head")
            .firstOrNull {
                it.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    ?.equals(
                        "RECENTLY UPDATED",
                        ignoreCase = true
                    ) == true
            }

        if (heading != null) {
            var node = heading.nextElementSibling()
            var steps = 0

            while (node != null && steps < 6) {
                val cards = node.select(".movie-card")
                if (cards.isNotEmpty()) return cards

                node = node.nextElementSibling()
                steps++
            }
        }

        return document.select(
            ".movie-cards-container .movie-card"
        )
    }

    // ---------------------------------------------------------------------
    // PRIORITY CACHE
    // ---------------------------------------------------------------------

    private suspend fun refreshPriorityCachesIfNeeded() {
        val now = System.currentTimeMillis()

        if (now - priorityCacheAt < PRIORITY_CACHE_MS) return

        recentlyKeys.clear()
        ongoingKeys.clear()
        dualAudioKeys.clear()

        getDocumentWithFallback("/")?.document?.let { document ->
            recentlyUpdatedCards(document)
                .mapNotNull(::parseCard)
                .forEach {
                    recentlyKeys += contentKey(it.url)
                }
        }

        getDocumentWithFallback("/ongoing")?.document?.let { document ->
            document
                .select(".movie-cards-container .movie-card")
                .mapNotNull(::parseCard)
                .forEach {
                    ongoingKeys += contentKey(it.url)
                }
        }

        getDocumentWithFallback(
            "/language/dual-audio"
        )?.document?.let { document ->
            document
                .select(".movie-cards-container .movie-card")
                .mapNotNull(::parseCard)
                .forEach {
                    dualAudioKeys += contentKey(it.url)
                }
        }

        priorityCacheAt = now
    }

    // ---------------------------------------------------------------------
    // NETWORK / MIRROR FAILOVER
    // ---------------------------------------------------------------------

    private suspend fun getDocumentWithFallback(
        path: String
    ): PageResult? {
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

                if (
                    isUsableDocument(
                        normalized,
                        document
                    )
                ) {
                    return PageResult(
                        domain,
                        normalized,
                        document
                    )
                }
            } catch (_: Throwable) {
                // Exact same path goes to the next mirror.
            }
        }

        return null
    }

    private fun browserHeaders(
        referer: String
    ): Map<String, String> = mapOf(
        "User-Agent" to (
            "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
            ),
        "Accept" to (
            "text/html,application/xhtml+xml," +
                "application/xml;q=0.9,*/*;q=0.8"
            ),
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache, no-store, max-age=0",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private fun isUsableDocument(
        path: String,
        document: Document
    ): Boolean {
        if (path == "/") {
            return document.selectFirst(
                ".movie-cards-container, " +
                    ".mlbd-page-head, " +
                    "body"
            ) != null
        }

        return document.selectFirst(
            "#mlbdInlinePlayerData, " +
                ".movie-cards-container .movie-card, " +
                ".movie-cards-container, " +
                "video, source"
        ) != null
    }

    private fun isUsablePlaybackDocument(
        document: Document
    ): Boolean {
        return document.selectFirst(
            "#mlbdInlinePlayerData, " +
                "video, " +
                "video source, " +
                "source[src]"
        ) != null
    }

    // ---------------------------------------------------------------------
    // JSON / EPISODES
    // ---------------------------------------------------------------------

    private fun parsePlayerJson(
        document: Document
    ): JSONObject? {
        val script = document.selectFirst(
            PLAYER_JSON_SELECTOR
        ) ?: return null

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
        val array = json?.optJSONArray("episodes")
            ?: return emptyList()

        val result = arrayListOf<Episode>()

        for (i in 0 until array.length()) {
            val episode = array.optJSONObject(i) ?: continue

            if (
                episode.optString("kind")
                    .equals("movie", true)
            ) {
                continue
            }

            val id = episode.optString("id").trim()
            if (id.isBlank()) continue

            val label = episode
                .optString("label")
                .trim()
                .ifBlank {
                    "Episode ${i + 1}"
                }

            val number = episode
                .optInt("number", 0)
                .takeIf { it > 0 }
                ?: extractEpisodeNumber(
                    label,
                    i + 1
                )

            val season = episode
                .optInt("season", 0)
                .takeIf { it > 0 }

            result += newEpisode(
                "$pageUrl||$id"
            ) {
                this.name = label
                this.episode = number
                this.season = season
            }
        }

        return result.sortedWith(
            compareBy<Episode> {
                it.season ?: Int.MAX_VALUE
            }.thenBy {
                it.episode ?: Int.MAX_VALUE
            }
        )
    }

    // ---------------------------------------------------------------------
    // UTILITY / URL
    // ---------------------------------------------------------------------

    private fun pageRoute(
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

    private fun hasNextPage(
        document: Document,
        currentPage: Int
    ): Boolean {
        if (
            document.select(".movie-card").size >=
                HOME_LIMIT
        ) {
            return true
        }

        return document
            .select("a[href]")
            .any { anchor ->
                val text = anchor
                    .text()
                    .trim()
                    .lowercase(Locale.ROOT)

                val rel = anchor
                    .attr("rel")
                    .trim()
                    .lowercase(Locale.ROOT)

                val href = anchor.attr("href")

                text == "next" ||
                    text.contains("next") ||
                    rel == "next" ||
                    anchor
                        .attr("aria-label")
                        .contains("next", true) ||
                    href.contains(
                        "page=${currentPage + 1}",
                        true
                    ) ||
                    href.contains(
                        "/page/${currentPage + 1}",
                        true
                    )
            }
    }

    private fun detectContentType(
        path: String,
        document: Document
    ): String {
        return when {
            path.startsWith("/movie/", true) -> "movie"
            path.startsWith("/anime/", true) -> "anime"
            path.startsWith("/series/", true) -> "series"
            path.startsWith("/drama/", true) -> "series"

            document.selectFirst(
                "#mlbdEpisodeDownloadGrid, .ep-card"
            ) != null -> "series"

            else -> "movie"
        }
    }

    private fun isContentPath(
        path: String
    ): Boolean {
        val lower = path.lowercase(Locale.ROOT)

        return lower.startsWith("/movie/") ||
            lower.startsWith("/series/") ||
            lower.startsWith("/drama/") ||
            lower.startsWith("/anime/")
    }

    private fun contentKey(
        url: String
    ): String {
        return pathFromUrl(url)
            .substringBefore('#')
            .substringBefore('?')
            .removeSuffix("/")
            .lowercase(Locale.ROOT)
    }

    private fun pathFromUrl(
        url: String
    ): String {
        return runCatching {
            val uri = URI(url)

            buildString {
                append(
                    uri.rawPath.ifBlank {
                        "/"
                    }
                )

                if (
                    !uri.rawQuery
                        .isNullOrBlank()
                ) {
                    append("?")
                    append(uri.rawQuery)
                }
            }.let(::normalizePath)
        }.getOrElse {
            val noScheme = url
                .substringAfter("://", url)

            val slash = noScheme.indexOf('/')

            normalizePath(
                if (slash >= 0) {
                    noScheme.substring(slash)
                } else {
                    "/"
                }
            )
        }
    }

    private fun normalizePath(
        path: String
    ): String {
        val clean = path.trim()

        if (clean.isBlank()) return "/"

        if (
            clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return pathFromUrl(clean)
        }

        return if (
            clean.startsWith("/")
        ) {
            clean
        } else {
            "/$clean"
        }
    }

    private fun absolutePrimary(
        href: String
    ): String {
        if (
            href.startsWith("http://", true) ||
            href.startsWith("https://", true)
        ) {
            return href
        }

        return PRIMARY + normalizePath(href)
    }

    private fun canonicalPrimaryUrl(
        path: String
    ): String = absolutePrimary(
        normalizePath(path)
    )

    private fun absoluteResourceUrl(
        value: String
    ): String {
        val clean = value
            .trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")

        if (clean.isBlank()) return clean

        if (
            clean.startsWith("http://", true) ||
            clean.startsWith("https://", true)
        ) {
            return clean
        }

        if (clean.startsWith("//")) {
            return "https:$clean"
        }

        return runCatching {
            URI(PRIMARY + "/")
                .resolve(clean)
                .toString()
        }.getOrElse {
            absolutePrimary(clean)
        }
    }

    // ---------------------------------------------------------------------
    // POSTER / METADATA
    // ---------------------------------------------------------------------

    private fun firstUsefulUrl(
        vararg values: String?
    ): String? {
        for (value in values) {
            if (value.isNullOrBlank()) continue

            val resolved = absoluteResourceUrl(value)

            if (resolved.isBlank()) continue
            if (isPlaceholderImage(resolved)) continue

            return resolved
        }

        return null
    }

    private fun extractImageUrl(
        element: Element
    ): String? {
        return firstUsefulUrl(
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-image"),
            element.attr("data-poster"),
            element.attr("src")
        )
    }

    private fun isPlaceholderImage(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        return lower.endsWith("mlbd_load.svg") ||
            lower.contains(
                "/images/mlbd_load.svg"
            ) ||
            lower.contains("placeholder") ||
            lower.contains("spacer.gif") ||
            lower.startsWith("data:image/")
    }

    private fun extractPlot(
        document: Document
    ): String? {
        val selectors = listOf(
            ".story-text",
            ".storyline-box .story-text",
            ".movie-extra-info",
            ".description",
            ".plot",
            "meta[name=description]"
        )

        for (selector in selectors) {
            val element = document
                .selectFirst(selector)
                ?: continue

            val text = if (
                element
                    .tagName()
                    .equals("meta", true)
            ) {
                element.attr("content")
            } else {
                element.text()
            }.trim()

            if (text.isNotBlank()) {
                return text
            }
        }

        return null
    }

    private fun extractYear(
        title: String,
        document: Document
    ): Int? {
        val texts = buildList {
            add(title)

            document
                .select(
                    "meta[property=og:title], h1, title"
                )
                .forEach {
                    add(
                        it.attr("content")
                            .ifBlank { it.text() }
                    )
                }
        }

        return texts
            .asSequence()
            .mapNotNull {
                Regex("\\b(19|20)\\d{2}\\b")
                    .find(it)
                    ?.value
                    ?.toIntOrNull()
            }
            .firstOrNull()
    }

    private fun cleanTitle(
        value: String
    ): String {
        return value
            .replace(
                "&nbsp;",
                " ",
                ignoreCase = true
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    // ---------------------------------------------------------------------
    // QUALITY / BASIC SEARCH NORMALIZATION
    // ---------------------------------------------------------------------

    private fun parseQuality(
        raw: Any?,
        name: String
    ): Int {
        when (raw) {
            is Number -> {
                val value = raw.toInt()
                if (value > 0) return value
            }

            is String -> {
                val value = raw.toIntOrNull()

                if (
                    value != null &&
                    value > 0
                ) {
                    return value
                }

                getQualityFromName(raw).let {
                    if (
                        it != Qualities.Unknown.value
                    ) {
                        return it
                    }
                }
            }
        }

        return getQualityFromName(name)
    }

    private fun normalizeSearch(
        value: String
    ): String {
        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(Locale.ROOT)
            .replace('&', ' ')
            .replace(
                Regex(
                    "[\\u2010-\\u2015\\u2212\\u2043\\u30A0\\u30FC]"
                ),
                "-"
            )
            .replace(
                Regex(
                    "[^a-z0-9\\p{L}]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String? {
        return values
            .firstOrNull {
                !it.isNullOrBlank()
            }
            ?.trim()
    }

    private fun isHttpUrl(
        value: String
    ): Boolean {
        return value.startsWith(
            "http://",
            true
        ) || value.startsWith(
            "https://",
            true
        )
    }

    private fun extractEpisodeNumber(
        text: String,
        fallback: Int
    ): Int {
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
}
