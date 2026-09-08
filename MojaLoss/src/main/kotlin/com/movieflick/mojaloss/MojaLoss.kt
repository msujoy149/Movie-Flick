package com.movieflick.mojaloss

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class MojaLoss : MainAPI() {

    override var mainUrl = "https://www.mojaloss.stream"
    override var name = "Moja Loss"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * MAIN CATEGORIES
     *
     * 1. Recently Released
     * 2. Movies = Movies + Hindi Movies
     * 3. International Movies
     * 4. TV Show = TV Shows + Korean + English + Hindi
     */
    override val mainPage = mainPageOf(
        "mojaloss://recent" to "Recently Released",
        "mojaloss://movies" to "Movies",
        "mojaloss://international" to "International Movies",
        "mojaloss://tv" to "TV Show"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType
    )

    private data class TvConfig(
        val baseUrl: String,
        val mediaToken: String,
        val seasons: List<TvSeason>
    )

    private data class TvSeason(
        val number: Int,
        val folder: String,
        val episodeFiles: Map<Int, String>,
        val subtitleFiles: Map<Int, String>
    )

    private data class MediaResult(
        val mediaUrl: String,
        val subtitleUrl: String?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageNumber = page.coerceAtLeast(1)

        val items = when (request.data) {

            "mojaloss://recent" -> {
                getPageItems(
                    if (pageNumber == 1)
                        "$mainUrl/page/1/"
                    else
                        "$mainUrl/page/$pageNumber/"
                )
            }

            "mojaloss://movies" -> {
                getMoviesSectionItems(pageNumber)
            }

            "mojaloss://international" -> {
                getPageItems(
                    makePagedCategoryUrl(
                        "international-movies",
                        pageNumber
                    )
                )
            }

            "mojaloss://tv" -> {
                mergeSources(
                    listOf(
                        makePagedCategoryUrl("tv-shows", pageNumber),
                        makePagedCategoryUrl(
                            "tv-shows/korean-tv-shows",
                            pageNumber
                        ),
                        makePagedCategoryUrl(
                            "tv-shows/english-tv-shows",
                            pageNumber
                        ),
                        makePagedCategoryUrl(
                            "tv-shows/hindi-tv-shows",
                            pageNumber
                        )
                    )
                ).map {
                    it.copy(type = TvType.TvSeries)
                }
            }

            else -> {
                getPageItems(request.data)
            }
        }

        val deduped = linkedMapOf<String, SiteItem>()

        items.forEach { item ->
            deduped.putIfAbsent(item.url, item)
        }

        val finalItems = deduped.values
            .take(30)

        return newHomePageResponse(
            request,
            list = finalItems.map { it.toSearchResponse() },
            hasNext = hasNextPage(
                request.data,
                pageNumber,
                items
            )
        )
    }

    private fun makePagedCategoryUrl(
        category: String,
        page: Int
    ): String {
        val base = "$mainUrl/category/$category/"

        return if (page <= 1) {
            base
        } else {
            "$base/page/$page/"
        }
    }

    private fun hasNextPage(
        source: String,
        page: Int,
        items: List<SiteItem>
    ): Boolean {
        if (items.isEmpty()) return false

        /*
         * Moja Loss archive pages are paginated.
         * Continue while populated pages are returned.
         */
        return page < 500
    }

    private companion object {
        const val RECENT_MOVIE_EXCLUSION_LIMIT = 100
        const val MOVIES_SECTION_BATCH_SIZE = 30
        const val RECENT_SCAN_MAX_PAGES = 6
        const val MOVIES_FILL_MAX_EXTRA_PAGES = 4
    }

    private suspend fun getMoviesSectionItems(page: Int): List<SiteItem> {
        val excludedRecentMovieUrls = getRecentMovieExclusionUrls()

        val merged = linkedMapOf<String, SiteItem>()
        var sourcePage = page.coerceAtLeast(1)
        var attempts = 0

        while (merged.size < MOVIES_SECTION_BATCH_SIZE && attempts < MOVIES_FILL_MAX_EXTRA_PAGES) {
            mergeSources(
                listOf(
                    makePagedCategoryUrl("movies", sourcePage),
                    makePagedCategoryUrl("hindi", sourcePage)
                )
            ).forEach { item ->
                if (item.type == TvType.Movie &&
                    !excludedRecentMovieUrls.contains(canonicalPageKey(item.url))
                ) {
                    merged.putIfAbsent(canonicalPageKey(item.url), item)
                }
            }

            sourcePage++
            attempts++

            if (attempts == 1 && merged.size >= MOVIES_SECTION_BATCH_SIZE) {
                break
            }
        }

        return merged.values.take(MOVIES_SECTION_BATCH_SIZE)
    }

    private suspend fun getRecentMovieExclusionUrls(): Set<String> {
        val excluded = linkedSetOf<String>()
        var scannedItems = 0

        for (page in 1..RECENT_SCAN_MAX_PAGES) {
            if (scannedItems >= RECENT_MOVIE_EXCLUSION_LIMIT) break

            val recentItems = getPageItems("$mainUrl/page/$page/")
            if (recentItems.isEmpty()) break

            for (item in recentItems) {
                if (scannedItems >= RECENT_MOVIE_EXCLUSION_LIMIT) break

                if (item.type == TvType.Movie) {
                    excluded.add(canonicalPageKey(item.url))
                    scannedItems++
                }
            }
        }

        return excluded
    }

    private fun canonicalPageKey(url: String): String {
        return url.substringBefore("#")
            .trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private suspend fun mergeSources(
        sources: List<String>
    ): List<SiteItem> {

        val merged = linkedMapOf<String, SiteItem>()

        for (source in sources) {
            getPageItems(source).forEach { item ->
                merged.putIfAbsent(item.url, item)
            }
        }

        return merged.values.toList()
    }

    private fun pageHeaders(
        referer: String = "$mainUrl/"
    ): Map<String, String> {
        return mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36",

            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",

            "Accept-Language" to
                "en-US,en;q=0.9,bn;q=0.8",

            "Cache-Control" to "no-cache",

            "Pragma" to "no-cache",

            "Referer" to referer
        )
    }

    private suspend fun getDocument(
        url: String
    ): Document? {

        val clean = url.trim()

        if (clean.isBlank()) return null

        return runCatching {
            app.get(
                clean,
                headers = pageHeaders(clean),
                timeout = 20_000
            ).document
        }.getOrNull()
    }

    private suspend fun getPageItems(
        url: String
    ): List<SiteItem> {

        val document = getDocument(url)
            ?: return emptyList()

        return parseCards(document)
    }

    private fun parseCards(
        document: Document
    ): List<SiteItem> {

        val result = linkedMapOf<String, SiteItem>()

        /*
         * Moja Loss archive/front-page cards.
         */
        document.select(
            "a.mj-fp-card, a.movie-card"
        ).forEach { card ->

            val href = card.attr("href").trim()

            if (href.isBlank()) return@forEach

            val absolute = absoluteUrl(
                href,
                document.location()
            )

            if (!isMojaPageUrl(absolute)) {
                return@forEach
            }

            val title = firstNonBlank(
                card.attr("data-prev-title"),
                card.selectFirst(".mj-fp-card-title")?.text(),
                card.selectFirst(".movie-title")?.text(),
                card.text()
            ).trim()

            if (title.isBlank()) return@forEach

            val poster = extractPoster(card)

            val rawType = firstNonBlank(
                card.attr("data-prev-type"),
                card.selectFirst(
                    ".mj-fp-card-badge, .movie-card-badge"
                )?.text()
            )
                .trim()
                .lowercase(Locale.ROOT)

            val type = if (
                rawType.contains("tv") ||
                rawType.contains("series")
            ) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }

            result.putIfAbsent(
                absolute,
                SiteItem(
                    title = title,
                    url = absolute,
                    poster = poster,
                    type = type
                )
            )
        }

        return result.values.toList()
    }

    private fun extractPoster(
        card: Element
    ): String? {

        val image = card.selectFirst("img")

        val direct = firstNonBlank(
            image?.attr("src"),
            image?.attr("data-src")
        )

        if (direct.isNotBlank()) {
            return absoluteUrl(
                direct,
                mainUrl
            )
        }

        val style = card.selectFirst(
            ".mj-fp-card-art, .movie-card-art"
        )?.attr("style").orEmpty()

        val match = Regex(
            """url\(['"]?([^'")]+)['"]?\)"""
        ).find(style)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                absoluteUrl(it, mainUrl)
            }
    }

    private fun SiteItem.toSearchResponse(): SearchResponse {

        return if (type == TvType.TvSeries) {

            this@MojaLoss.newTvSeriesSearchResponse(
                name = title,
                url = url,
                type = TvType.TvSeries
            ) {
                posterUrl = poster
            }

        } else {

            this@MojaLoss.newMovieSearchResponse(
                name = title,
                url = url,
                type = type
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val q = query.trim()

        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        /*
         * Search the same four logical groups exposed
         * by the CloudStream homepage.
         */
        val found = linkedMapOf<String, SiteItem>()

        for (scanPage in 1..4) {

            getPageItems(
                "$mainUrl/page/$scanPage/"
            ).forEach {
                found.putIfAbsent(it.url, it)
            }

            mergeSources(
                listOf(
                    makePagedCategoryUrl(
                        "movies",
                        scanPage
                    ),
                    makePagedCategoryUrl(
                        "hindi",
                        scanPage
                    )
                ).map { it }
            ).forEach {
                found.putIfAbsent(it.url, it)
            }

            getPageItems(
                makePagedCategoryUrl(
                    "international-movies",
                    scanPage
                )
            ).forEach {
                found.putIfAbsent(it.url, it)
            }

            mergeSources(
                listOf(
                    makePagedCategoryUrl(
                        "tv-shows",
                        scanPage
                    ),
                    makePagedCategoryUrl(
                        "tv-shows/korean-tv-shows",
                        scanPage
                    ),
                    makePagedCategoryUrl(
                        "tv-shows/english-tv-shows",
                        scanPage
                    ),
                    makePagedCategoryUrl(
                        "tv-shows/hindi-tv-shows",
                        scanPage
                    )
                )
            ).forEach {
                found.putIfAbsent(
                    it.url,
                    it.copy(type = TvType.TvSeries)
                )
            }
        }

        val ranked = found.values
            .map {
                it to searchScore(
                    q,
                    it.title
                )
            }
            .filter {
                it.second >= 0.30
            }
            .sortedWith(
                compareByDescending<
                    Pair<SiteItem, Double>
                    > { it.second }
                    .thenBy {
                        it.first.title
                    }
            )
            .map {
                it.first
            }

        val perPage = 30

        val start =
            ((page - 1).coerceAtLeast(0)) * perPage

        val result = ranked
            .drop(start)
            .take(perPage)
            .map {
                it.toSearchResponse()
            }

        return newSearchResponseList(
            result,
            start + perPage < ranked.size
        )
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        val q = normalizeSearchText(query)
        val t = normalizeSearchText(title)

        if (q.isBlank() || t.isBlank()) {
            return 0.0
        }

        if (q == t) {
            return 1.0
        }

        if (t.contains(q)) {
            return 0.95
        }

        val compactQ =
            q.replace(" ", "")

        val compactT =
            t.replace(" ", "")

        if (
            compactQ.isNotBlank() &&
            compactT.contains(compactQ)
        ) {
            return 0.90
        }

        val qTokens =
            q.split(" ")
                .filter { it.length >= 2 }

        val tTokens =
            t.split(" ")
                .filter { it.length >= 2 }

        if (
            qTokens.isEmpty() ||
            tTokens.isEmpty()
        ) {
            return 0.0
        }

        val tokenScore =
            qTokens.map { qt ->

                tTokens.maxOfOrNull { tt ->

                    when {
                        qt == tt ->
                            1.0

                        tt.startsWith(qt) ||
                            qt.startsWith(tt) ->
                            0.90

                        else ->
                            similarity(qt, tt)
                    }

                } ?: 0.0

            }.average()

        return maxOf(
            tokenScore,
            similarity(q, t)
        ).coerceIn(
            0.0,
            1.0
        )
    }

    private fun normalizeSearchText(
        value: String
    ): String {

        return java.text.Normalizer
            .normalize(
                value,
                java.text.Normalizer.Form.NFKC
            )
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(
                Regex("[^a-z0-9\\p{L}]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun similarity(
        a: String,
        b: String
    ): Double {

        if (a == b) {
            return 1.0
        }

        if (
            a.isBlank() ||
            b.isBlank()
        ) {
            return 0.0
        }

        if (
            a.contains(b) ||
            b.contains(a)
        ) {

            val minLen =
                minOf(
                    a.length,
                    b.length
                ).toDouble()

            val maxLen =
                maxOf(
                    a.length,
                    b.length
                ).toDouble()

            return 0.80 +
                0.20 * (
                    minLen / maxLen
                )
        }

        return 1.0 -
            levenshtein(
                a,
                b
            ).toDouble() /
            maxOf(
                a.length,
                b.length
            )
    }

    private fun levenshtein(
        a: String,
        b: String
    ): Int {

        if (a == b) return 0

        if (a.isEmpty()) {
            return b.length
        }

        if (b.isEmpty()) {
            return a.length
        }

        var prev =
            IntArray(
                b.length + 1
            ) { it }

        var curr =
            IntArray(
                b.length + 1
            )

        for (i in a.indices) {

            curr[0] = i + 1

            for (j in b.indices) {

                val cost =
                    if (a[i] == b[j]) 0 else 1

                curr[j + 1] =
                    minOf(
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

    override suspend fun load(
        url: String
    ): LoadResponse {

        val cleanUrl =
            url.substringBefore("#")
                .trim()

        val episodeInfo =
            parseEpisodeFragment(url)

        val document =
            getDocument(cleanUrl)

        if (document == null) {

            return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                TvType.Movie,
                cleanUrl
            )
        }

        val pageTitle =
            firstNonBlank(
                document.selectFirst(
                    "meta[property='og:title']"
                )?.attr("content"),

                document.selectFirst(
                    "h1"
                )?.text(),

                document.title()
            )
                .trim()
                .ifBlank {
                    titleFromUrl(cleanUrl)
                }

        val poster =
            firstNonBlank(
                document.selectFirst(
                    "meta[property='og:image']"
                )?.attr("content"),

                document.selectFirst(
                    "img"
                )?.attr("src")
            )
                .takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    absoluteUrl(
                        it,
                        cleanUrl
                    )
                }

        val tvConfig =
            extractTvConfig(
                document
            )

        /*
         * TV detection is intentionally driven by the page itself, not only by
         * the JSON config parser. MojaLoss marks TV pages with og:type=video.tv_show
         * and also renders #plyr-tv-config / .plyr-tv-container. If JSON parsing ever
         * fails, the page must still remain a TV series instead of falling through to
         * a Movie response.
         */
        val isTvPage =
            looksLikeTvPage(document)

        if (isTvPage) {

            val episodes = linkedMapOf<String, Episode>()

            tvConfig
                ?.let { buildEpisodes(it, cleanUrl) }
                ?.forEach { episode ->
                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}",
                        episode
                    )
                }

            if (episodes.isEmpty()) {
                parseTvProgressEpisodes(document, cleanUrl).forEach { episode ->
                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}",
                        episode
                    )
                }
            }

            if (episodes.isEmpty()) {
                parseEpisodeLinks(document, cleanUrl).forEach { episode ->
                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}-${episode.name}",
                        episode
                    )
                }
            }

            val episodeList = episodes.values.sortedWith(
                compareBy<Episode> { it.season ?: 1 }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

            /*
             * Always return a TvSeries response for a real TV page.
             * CloudStream otherwise shows the same page as a Movie when a parser
             * temporarily misses the episode JSON.
             */
            return newTvSeriesLoadResponse(
                pageTitle,
                cleanUrl,
                TvType.TvSeries,
                episodeList
            ) {
                posterUrl = poster
            }
        }

        return newMovieLoadResponse(
            pageTitle,
            cleanUrl,
            TvType.Movie,
            cleanUrl
        ) {
            posterUrl = poster
        }
    }

    private fun looksLikeTvPage(
        document: Document
    ): Boolean {

        if (
            document.select(
                "#plyr-tv-config, script#plyr-tv-config"
            ).isNotEmpty()
        ) {
            return true
        }

        val ogType =
            document.selectFirst(
                "meta[property='og:type']"
            )
                ?.attr("content")
                .orEmpty()
                .lowercase(Locale.ROOT)

        if (ogType.contains("tv_show")) {
            return true
        }

        if (
            document.select(
                ".plyr-tv-container, .mj-tv-progress-row-wrap, .mj-tv-progress-ep"
            ).isNotEmpty()
        ) {
            return true
        }

        /*
         * Last-resort HTML fingerprinting. This protects TV detection against
         * minor markup/type-attribute changes while keeping the movie path intact.
         */
        val html = document.html()
            .lowercase(Locale.ROOT)

        return html.contains("id=\"plyr-tv-config\"") ||
            html.contains("video.tv_show") ||
            html.contains("class=\"plyr-tv-container")
    }

    private fun parseTvProgressEpisodes(
        document: Document,
        detailUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        document.select(
            ".mj-tv-progress-row-wrap[data-mj-season], [data-mj-row-wrap][data-mj-season]"
        ).forEach { seasonRow ->
            val season = findNumber(seasonRow.attr("data-mj-season"))
                ?: extractSeasonNumberFromText(seasonRow.text())
                ?: 1

            seasonRow.select(".mj-tv-progress-ep[data-mj-ep], [data-mj-ep]")
                .forEach { button ->
                    val episode = findNumber(button.attr("data-mj-ep"))
                        ?: return@forEach
                    val label = firstNonBlank(
                        button.attr("aria-label"),
                        button.attr("title"),
                        button.text()
                    ).trim()
                    val key = "S$season-E$episode"

                    result.putIfAbsent(
                        key,
                        newEpisode(buildEpisodeData(detailUrl, season, episode)) {
                            name = label.ifBlank { "S$season E$episode" }
                            this.season = season
                            this.episode = episode
                        }
                    )
                }
        }

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun extractSeasonNumberFromText(text: String): Int? {
        return Regex("(?i)\\bseason\\s*[-._ ]?(\\d+)\\b")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun buildEpisodes(
        config: TvConfig,
        detailUrl: String
    ): List<Episode> {
        val result = mutableListOf<Episode>()

        config.seasons.sortedBy { it.number }.forEach { season ->
            season.episodeFiles.toSortedMap().forEach { (episode, filename) ->
                val cleanFilename = filename.substringBeforeLast('.', filename).trim()
                val label = if (cleanFilename.isBlank()) {
                    "Episode $episode"
                } else {
                    "E$episode $cleanFilename"
                }

                result += newEpisode(
                    buildEpisodeData(detailUrl, season.number, episode)
                ) {
                    name = label
                    this.season = season.number
                    this.episode = episode
                }
            }
        }

        return result
    }

    private fun buildEpisodeData(
        detailUrl: String,
        season: Int,
        episode: Int
    ): String {

        return "$detailUrl#season=$season&episode=$episode"
    }

    private fun parseEpisodeLinks(
        document: Document,
        baseUrl: String
    ): List<Episode> {

        val result =
            linkedMapOf<String, Episode>()

        document.select(
            "a[href], [data-href], [data-url], [data-link]"
        ).forEach { element ->

            val raw =
                sequenceOf(
                    element.attr("href"),
                    element.attr("data-href"),
                    element.attr("data-url"),
                    element.attr("data-link")
                )
                    .firstOrNull {
                        it.isNotBlank()
                    }
                    ?: return@forEach

            val absolute =
                absoluteUrl(
                    raw,
                    baseUrl
                )

            val label =
                firstNonBlank(
                    element.text(),
                    element.attr("aria-label"),
                    element.attr("title")
                )
                    .trim()

            val lower =
                absolute.lowercase(
                    Locale.ROOT
                )

            val looksLikeEpisode =
                lower.contains("episode") ||
                lower.contains("ep=") ||
                lower.contains("season=") ||
                Regex(
                    "(?i)\\b(?:episode|ep|e)\\s*[-._ ]?\\d+"
                ).containsMatchIn(label)

            if (!looksLikeEpisode) {
                return@forEach
            }

            val season =
                findNumber(
                    element.attr("data-season")
                )
                    ?: findNumberFromText(
                        label,
                        "season"
                    )
                    ?: findNumberFromUrl(
                        absolute,
                        "season"
                    )
                    ?: 1

            val episode =
                findNumber(
                    element.attr("data-episode")
                )
                    ?: findNumber(
                        element.attr("data-ep")
                    )
                    ?: findNumberFromText(
                        label,
                        "episode"
                    )
                    ?: findEpisodeNumber(
                        label
                    )
                    ?: findNumberFromUrl(
                        absolute,
                        "ep"
                    )
                    ?: 1

            result[absolute] =
                newEpisode(
                    absolute
                ) {
                    name =
                        label.ifBlank {
                            "Episode $episode"
                        }

                    this.season =
                        season

                    this.episode =
                        episode
                }
        }

        return result.values
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }
                    .thenBy {
                        it.episode ?: Int.MAX_VALUE
                    }
            )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val input =
            data.trim()

        if (input.isBlank()) {
            return false
        }

        /*
         * TV EPISODE PLAYBACK
         *
         * We fetch the detail page again at play-time.
         * This is important because Moja Loss exposes a mediaToken
         * that may expire. We never hard-code an old captured token.
         */
        val episode =
            parseEpisodeFragment(
                input
            )

        if (episode != null) {

            val detailUrl =
                input.substringBefore("#")

            val document =
                getDocument(
                    detailUrl
                )
                    ?: return false

            val config =
                extractTvConfig(
                    document
                )
                    ?: return false

            val season =
                config.seasons.firstOrNull {
                    it.number == episode.first
                }
                    ?: return false

            val filename =
                season.episodeFiles[
                    episode.second
                ]
                    ?: return false

            val subtitleFilename =
                season.subtitleFiles[
                    episode.second
                ]

            val media =
                buildTvMedia(
                    config.baseUrl,
                    config.mediaToken,
                    season.folder,
                    filename,
                    subtitleFilename
                )
                    ?: return false

            media.subtitleUrl
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {

                    subtitleCallback(
                        SubtitleFile(
                            lang = "English",
                            url = it
                        )
                    )
                }

            emitMediaLink(
                media.mediaUrl,
                callback,
                "Moja Loss TV"
            )

            return true
        }

        /*
         * MOVIE PLAYBACK
         *
         * Fetch a fresh detail page and read:
         *   data-default-src
         *   data-media-token
         *
         * Then convert the website's /directlink/ path into
         * the observed media.mojaloss.stream/dl/ path.
         */
        val document =
            getDocument(
                input
            )
                ?: return false

        val video =
            document.selectFirst(
                "video[data-default-src][data-media-token]"
            )

        if (video != null) {

            val defaultSource =
                video.attr(
                    "data-default-src"
                )
                    .trim()

            val mediaToken =
                video.attr(
                    "data-media-token"
                )
                    .trim()
                    .replace(
                        "&amp;",
                        "&"
                    )

            if (
                defaultSource.isNotBlank() &&
                mediaToken.isNotBlank()
            ) {

                val subtitleSource =
                    video.attr(
                        "data-default-subtitle-src"
                    )
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }

                val media =
                    buildMovieMedia(
                        defaultSource,
                        mediaToken,
                        subtitleSource
                    )

                if (media != null) {

                    media.subtitleUrl
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {

                            subtitleCallback(
                                SubtitleFile(
                                    lang = "English",
                                    url = it
                                )
                            )
                        }

                    emitMediaLink(
                        media.mediaUrl,
                        callback,
                        "Moja Loss Direct"
                    )

                    return true
                }
            }
        }

        /*
         * Direct media URL fallback.
         */
        if (isMediaUrl(input)) {

            emitMediaLink(
                input,
                callback,
                "Moja Loss Direct"
            )

            return true
        }

        return false
    }

    private fun extractTvConfig(
        document: Document
    ): TvConfig? {

        val script = document.selectFirst(
            "script#plyr-tv-config, script[type='application/json']#plyr-tv-config"
        ) ?: return extractTvConfigFromRawHtml(document.html())

        val raw = script.html().trim().ifBlank { script.data().trim() }
        if (raw.isBlank()) return extractTvConfigFromRawHtml(document.html())

        return runCatching {

            val objectJson =
                JSONObject(raw)

            val baseUrl =
                objectJson
                    .optString("baseUrl")
                    .trim()

            val mediaToken =
                objectJson
                    .optString("mediaToken")
                    .trim()

            if (
                baseUrl.isBlank() ||
                mediaToken.isBlank()
            ) {
                return@runCatching null
            }

            val seasons =
                mutableListOf<TvSeason>()

            val seasonArray =
                objectJson.optJSONArray(
                    "seasons"
                )
                    ?: return@runCatching null

            for (
                index in 0 until seasonArray.length()
            ) {

                val seasonObject =
                    seasonArray.optJSONObject(
                        index
                    )
                        ?: continue

                val number =
                    seasonObject.optInt(
                        "num",
                        0
                    )

                val folder =
                    seasonObject
                        .optString("folder")
                        .trim()

                if (
                    number <= 0 ||
                    folder.isBlank()
                ) {
                    continue
                }

                val episodeFiles =
                    mutableMapOf<Int, String>()

                val episodeObject =
                    seasonObject.optJSONObject(
                        "episode_files"
                    )

                if (episodeObject != null) {

                    val keys =
                        episodeObject.keys()

                    while (keys.hasNext()) {

                        val key =
                            keys.next()

                        val episode =
                            key.toIntOrNull()
                                ?: continue

                        val filename =
                            episodeObject
                                .optString(key)
                                .trim()

                        if (
                            episode > 0 &&
                            filename.isNotBlank()
                        ) {
                            episodeFiles[
                                episode
                            ] = filename
                        }
                    }
                }

                val subtitleFiles =
                    mutableMapOf<Int, String>()

                val subtitleObject =
                    seasonObject.optJSONObject(
                        "subtitle_files"
                    )

                if (subtitleObject != null) {

                    val keys =
                        subtitleObject.keys()

                    while (keys.hasNext()) {

                        val key =
                            keys.next()

                        val episode =
                            key.toIntOrNull()
                                ?: continue

                        val filename =
                            subtitleObject
                                .optString(key)
                                .trim()

                        if (
                            episode > 0 &&
                            filename.isNotBlank()
                        ) {
                            subtitleFiles[
                                episode
                            ] = filename
                        }
                    }
                }

                seasons += TvSeason(
                    number = number,
                    folder = folder,
                    episodeFiles = episodeFiles,
                    subtitleFiles = subtitleFiles
                )
            }

            TvConfig(
                baseUrl = baseUrl,
                mediaToken = mediaToken,
                seasons = seasons
            )

        }.getOrNull() ?: extractTvConfigFromRawHtml(document.html())
    }

    private fun extractTvConfigFromRawHtml(html: String): TvConfig? {
        if (html.isBlank()) return null

        val normalized = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val match = Regex(
            "(?is)<script[^>]*id=[\"']plyr-tv-config[\"'][^>]*>(.*?)</script>"
        ).find(normalized) ?: return null

        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (raw.isBlank()) return null

        return runCatching { parseTvConfigJson(raw) }.getOrNull()
    }

    private fun parseTvConfigJson(raw: String): TvConfig? {
        val objectJson = JSONObject(raw)
        val baseUrl = objectJson.optString("baseUrl").trim()
        val mediaToken = objectJson.optString("mediaToken").trim()
        if (baseUrl.isBlank() || mediaToken.isBlank()) return null

        val seasons = mutableListOf<TvSeason>()
        val seasonArray = objectJson.optJSONArray("seasons") ?: return null

        for (index in 0 until seasonArray.length()) {
            val seasonObject = seasonArray.optJSONObject(index) ?: continue
            val number = seasonObject.optInt("num", 0)
            val folder = seasonObject.optString("folder").trim()
            if (number <= 0 || folder.isBlank()) continue

            val episodeFiles = mutableMapOf<Int, String>()
            seasonObject.optJSONObject("episode_files")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val episode = key.toIntOrNull() ?: continue
                    val filename = obj.optString(key).trim()
                    if (episode > 0 && filename.isNotBlank()) episodeFiles[episode] = filename
                }
            }

            val subtitleFiles = mutableMapOf<Int, String>()
            seasonObject.optJSONObject("subtitle_files")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val episode = key.toIntOrNull() ?: continue
                    val filename = obj.optString(key).trim()
                    if (episode > 0 && filename.isNotBlank()) subtitleFiles[episode] = filename
                }
            }

            seasons += TvSeason(number, folder, episodeFiles, subtitleFiles)
        }

        return TvConfig(baseUrl, mediaToken, seasons)
    }

    private fun buildTvMedia(
        baseUrl: String,
        token: String,
        folder: String,
        filename: String,
        subtitleFilename: String?
    ): MediaResult? {

        val cleanBase =
            baseUrl
                .trim()
                .trimEnd('/')

        if (
            cleanBase.isBlank() ||
            token.isBlank() ||
            folder.isBlank() ||
            filename.isBlank()
        ) {
            return null
        }

        val encodedFolder =
            encodePathPart(folder)

        val encodedFilename =
            encodePathPart(filename)

        val source =
            "$cleanBase/$encodedFolder/$encodedFilename"

        val mediaUrl =
            normalizeDirectMediaUrl(
                appendToken(
                    source,
                    token
                )
            )

        val subtitleUrl =
            subtitleFilename
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {

                    val subtitleSource =
                        "$cleanBase/" +
                        "$encodedFolder/" +
                        encodePathPart(it)

                    normalizeDirectMediaUrl(
                        appendToken(
                            subtitleSource,
                            token
                        )
                    )
                }

        return MediaResult(
            mediaUrl = mediaUrl,
            subtitleUrl = subtitleUrl
        )
    }

    private fun buildMovieMedia(
        defaultSource: String,
        token: String,
        subtitleSource: String?
    ): MediaResult? {

        if (
            defaultSource.isBlank() ||
            token.isBlank()
        ) {
            return null
        }

        val mediaUrl =
            normalizeDirectMediaUrl(
                appendToken(
                    defaultSource,
                    token
                )
            )

        val subtitleUrl =
            subtitleSource
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {

                    normalizeDirectMediaUrl(
                        appendToken(
                            it,
                            token
                        )
                    )
                }

        return MediaResult(
            mediaUrl = mediaUrl,
            subtitleUrl = subtitleUrl
        )
    }

    private fun normalizeDirectMediaUrl(
        url: String
    ): String {

        return url.replace(
            Regex(
                "(?i)^https?://www\\.mojaloss\\.stream/directlink/"
            ),
            "https://media.mojaloss.stream/dl/"
        )
    }

    private fun appendToken(
        url: String,
        token: String
    ): String {

        val cleanToken =
            token
                .trim()
                .removePrefix("?")
                .replace(
                    "&amp;",
                    "&"
                )

        if (cleanToken.isBlank()) {
            return url
        }

        return if (url.contains("?")) {
            "$url&$cleanToken"
        } else {
            "$url?$cleanToken"
        }
    }

    private fun encodePathPart(
        value: String
    ): String {

        return URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.toString()
        )
            .replace("+", "%20")
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit,
        linkName: String
    ) {

        val lower =
            mediaUrl.lowercase(
                Locale.ROOT
            )

        val type =
            when {

                ".m3u8" in lower ->
                    ExtractorLinkType.M3U8

                ".mpd" in lower ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }

        val quality =
            when {

                "2160" in lower ||
                    "4k" in lower ->
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
                name = linkName,
                url = mediaUrl,
                type = type
            ) {

                this.quality =
                    quality

                this.referer =
                    "$mainUrl/"
            }
        )
    }

    private fun parseEpisodeFragment(
        url: String
    ): Pair<Int, Int>? {

        val fragment =
            runCatching {
                URI(url)
                    .rawFragment
                    .orEmpty()
            }.getOrDefault("")

        if (fragment.isBlank()) {
            return null
        }

        val season =
            Regex(
                "(?i)(?:^|&)season=(\\d+)"
            )
                .find(fragment)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val episode =
            Regex(
                "(?i)(?:^|&)episode=(\\d+)"
            )
                .find(fragment)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return if (
            season != null &&
            episode != null
        ) {
            season to episode
        } else {
            null
        }
    }

    private fun findNumber(
        value: String
    ): Int? {

        return value
            .trim()
            .toIntOrNull()
    }

    private fun findNumberFromText(
        text: String,
        keyword: String
    ): Int? {

        return Regex(
            "(?i)\\b$keyword\\s*[-._ ]?(\\d+)\\b"
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun findEpisodeNumber(
        text: String
    ): Int? {

        return Regex(
            "(?i)\\b(?:episode|ep|e)\\s*[-._ ]?(\\d+)\\b"
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun findNumberFromUrl(
        url: String,
        key: String
    ): Int? {

        return Regex(
            "(?i)(?:^|[?&])$key=(\\d+)"
        )
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun titleFromUrl(
        url: String
    ): String {

        val path =
            runCatching {
                URI(url)
                    .path
                    .orEmpty()
            }.getOrDefault("")

        val slug =
            path.trim('/')
                .substringAfterLast('/')
                .ifBlank {
                    "Moja Loss"
                }

        return slug
            .replace("-", " ")
            .replace("_", " ")
            .replace(
                Regex("\\b\\d{4}\\b"),
                ""
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
            .replaceFirstChar {
                if (
                    it.isLowerCase()
                ) {
                    it.titlecase(
                        Locale.ROOT
                    )
                } else {
                    it.toString()
                }
            }
    }

    private fun isMojaPageUrl(
        url: String
    ): Boolean {

        return try {

            val uri =
                URI(url)

            val host =
                uri.host
                    .orEmpty()
                    .lowercase(
                        Locale.ROOT
                    )

            host ==
                URI(mainUrl)
                    .host
                    .lowercase(
                        Locale.ROOT
                    )

        } catch (_: Exception) {
            false
        }
    }

    private fun isMediaUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(
                Locale.ROOT
            )

        return (
            "media.mojaloss.stream" in lower &&
            (
                ".mp4" in lower ||
                ".mkv" in lower ||
                ".webm" in lower ||
                ".m3u8" in lower ||
                ".mpd" in lower
            )
        )
    }

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

        return try {
            URI(base)
                .resolve(value)
                .toString()
        } catch (_: Exception) {
            value
        }
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String {

        return values.firstOrNull {
            !it.isNullOrBlank()
        } ?: ""
    }
}
