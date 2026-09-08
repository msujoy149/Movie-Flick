package com.movieflick.mojaloss

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
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

        while (
            merged.size < MOVIES_SECTION_BATCH_SIZE &&
            attempts < MOVIES_FILL_MAX_EXTRA_PAGES
        ) {
            mergeSources(
                listOf(
                    makePagedCategoryUrl("movies", sourcePage),
                    makePagedCategoryUrl("hindi", sourcePage)
                )
            ).forEach { item ->
                if (
                    item.type == TvType.Movie &&
                    !excludedRecentMovieUrls.contains(
                        canonicalPageKey(item.url)
                    )
                ) {
                    merged.putIfAbsent(
                        canonicalPageKey(item.url),
                        item
                    )
                }
            }

            sourcePage++
            attempts++

            if (
                attempts == 1 &&
                merged.size >= MOVIES_SECTION_BATCH_SIZE
            ) {
                break
            }
        }

        return merged.values.take(
            MOVIES_SECTION_BATCH_SIZE
        )
    }

    private suspend fun getRecentMovieExclusionUrls(): Set<String> {
        val excluded = linkedSetOf<String>()
        var scannedItems = 0

        for (page in 1..RECENT_SCAN_MAX_PAGES) {
            if (
                scannedItems >=
                RECENT_MOVIE_EXCLUSION_LIMIT
            ) {
                break
            }

            val recentItems =
                getPageItems(
                    "$mainUrl/page/$page/"
                )

            if (recentItems.isEmpty()) break

            for (item in recentItems) {

                if (
                    scannedItems >=
                    RECENT_MOVIE_EXCLUSION_LIMIT
                ) {
                    break
                }

                if (item.type == TvType.Movie) {
                    excluded.add(
                        canonicalPageKey(item.url)
                    )

                    scannedItems++
                }
            }
        }

        return excluded
    }

    private fun canonicalPageKey(
        url: String
    ): String {
        return url.substringBefore("#")
            .trim()
            .trimEnd('/')
            .lowercase(Locale.ROOT)
    }

    private suspend fun mergeSources(
        sources: List<String>
    ): List<SiteItem> {

        val merged =
            linkedMapOf<String, SiteItem>()

        for (source in sources) {
            getPageItems(source)
                .forEach { item ->
                    merged.putIfAbsent(
                        item.url,
                        item
                    )
                }
        }

        return merged.values.toList()
    }

    private fun pageHeaders(
        referer: String = "$mainUrl/"
    ): Map<String, String> {

        return mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",

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

        val clean =
            url.trim()

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

        val document =
            getDocument(url)
                ?: return emptyList()

        return parseCards(document)
    }

    private fun parseCards(
        document: Document
    ): List<SiteItem> {

        val result =
            linkedMapOf<String, SiteItem>()

        document.select(
            "a.mj-fp-card, a.movie-card"
        ).forEach { card ->

            val href =
                card.attr("href")
                    .trim()

            if (href.isBlank()) {
                return@forEach
            }

            val absolute =
                absoluteUrl(
                    href,
                    document.location()
                )

            if (!isMojaPageUrl(absolute)) {
                return@forEach
            }

            val title =
                firstNonBlank(
                    card.attr("data-prev-title"),
                    card.selectFirst(
                        ".mj-fp-card-title"
                    )?.text(),
                    card.selectFirst(
                        ".movie-title"
                    )?.text(),
                    card.text()
                ).trim()

            if (title.isBlank()) {
                return@forEach
            }

            val poster =
                extractPoster(card)

            val rawType =
                firstNonBlank(
                    card.attr("data-prev-type"),
                    card.selectFirst(
                        ".mj-fp-card-badge, .movie-card-badge"
                    )?.text()
                )
                    .trim()
                    .lowercase(Locale.ROOT)

            val type =
                if (
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

        val image =
            card.selectFirst("img")

        val direct =
            firstNonBlank(
                image?.attr("src"),
                image?.attr("data-src")
            )

        if (direct.isNotBlank()) {
            return absoluteUrl(
                direct,
                mainUrl
            )
        }

        val style =
            card.selectFirst(
                ".mj-fp-card-art, .movie-card-art"
            )?.attr("style").orEmpty()

        val match =
            Regex(
                """url\(['"]?([^'")]+)['"]?\)"""
            ).find(style)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.let {
                absoluteUrl(
                    it,
                    mainUrl
                )
            }
    }

    private fun SiteItem.toSearchResponse():
        SearchResponse {

        return if (
            type == TvType.TvSeries
        ) {

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

        val q =
            query.trim()

        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val found =
            linkedMapOf<String, SiteItem>()

        for (scanPage in 1..4) {

            getPageItems(
                "$mainUrl/page/$scanPage/"
            ).forEach {
                found.putIfAbsent(
                    it.url,
                    it
                )
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
                )
            ).forEach {
                found.putIfAbsent(
                    it.url,
                    it
                )
            }

            getPageItems(
                makePagedCategoryUrl(
                    "international-movies",
                    scanPage
                )
            ).forEach {
                found.putIfAbsent(
                    it.url,
                    it
                )
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
                    it.copy(
                        type = TvType.TvSeries
                    )
                )
            }
        }

        val ranked =
            found.values
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
                    > {
                        it.second
                    }.thenBy {
                        it.first.title
                    }
                )
                .map {
                    it.first
                }

        val perPage = 30

        val start =
            ((page - 1).coerceAtLeast(0)) *
                perPage

        val result =
            ranked
                .drop(start)
                .take(perPage)
                .map {
                    it.toSearchResponse()
                }

        return newSearchResponseList(
            result,
            start + perPage <
                ranked.size
        )
    }

    private fun searchScore(
        query: String,
        title: String
    ): Double {

        val q =
            normalizeSearchText(query)

        val t =
            normalizeSearchText(title)

        if (
            q.isBlank() ||
            t.isBlank()
        ) {
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
                .filter {
                    it.length >= 2
                }

        val tTokens =
            t.split(" ")
                .filter {
                    it.length >= 2
                }

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
                            similarity(
                                qt,
                                tt
                            )
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
            .replace(
                "&",
                " and "
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

        if (a == b) {
            return 0
        }

        if (a.isEmpty()) {
            return b.length
        }

        if (b.isEmpty()) {
            return a.length
        }

        var prev =
            IntArray(
                b.length + 1
            ) {
                it
            }

        var curr =
            IntArray(
                b.length + 1
            )

        for (i in a.indices) {

            curr[0] = i + 1

            for (j in b.indices) {

                val cost =
                    if (
                        a[i] == b[j]
                    ) {
                        0
                    } else {
                        1
                    }

                curr[j + 1] =
                    minOf(
                        curr[j] + 1,
                        prev[j + 1] + 1,
                        prev[j] + cost
                    )
            }

            val tmp =
                prev

            prev =
                curr

            curr =
                tmp
        }

        return prev[b.length]
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val cleanUrl =
            url.substringBefore("#")
                .trim()

        val response =
            runCatching {
                app.get(
                    cleanUrl,
                    headers = pageHeaders(cleanUrl),
                    timeout = 20_000
                )
            }.getOrNull()

        val document =
            response?.document

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

        val html =
            response.text

        val tvConfig =
            extractTvConfig(
                document,
                html
            )

        val isTvPage =
            looksLikeTvPage(
                document,
                html
            )

        if (isTvPage) {

            val episodes =
                linkedMapOf<String, Episode>()

            tvConfig
                ?.let {
                    buildEpisodes(
                        it,
                        cleanUrl
                    )
                }
                ?.forEach { episode ->

                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}",
                        episode
                    )
                }

            if (episodes.isEmpty()) {

                parseTvProgressEpisodes(
                    document,
                    cleanUrl
                ).forEach { episode ->

                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}",
                        episode
                    )
                }
            }

            if (episodes.isEmpty()) {

                parseTvEpisodesFromRawHtml(
                    html,
                    cleanUrl
                ).forEach { episode ->

                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}",
                        episode
                    )
                }
            }

            if (episodes.isEmpty()) {

                parseEpisodeLinks(
                    document,
                    cleanUrl
                ).forEach { episode ->

                    episodes.putIfAbsent(
                        "${episode.season ?: 1}-${episode.episode ?: 0}-${episode.name}",
                        episode
                    )
                }
            }

            val episodeList =
                episodes.values.sortedWith(
                    compareBy<Episode> {
                        it.season ?: 1
                    }.thenBy {
                        it.episode
                            ?: Int.MAX_VALUE
                    }
                )

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
        document: Document,
        rawHtml: String = document.html()
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

        if (
            ogType.contains(
                "tv_show"
            )
        ) {
            return true
        }

        if (
            document.select(
                ".plyr-tv-container, " +
                    ".mj-tv-progress-row-wrap, " +
                    ".mj-tv-progress-ep"
            ).isNotEmpty()
        ) {
            return true
        }

        val html =
            rawHtml.lowercase(Locale.ROOT)

        return html.contains(
            "id=\"plyr-tv-config\""
        ) ||
            html.contains(
                "id='plyr-tv-config'"
            ) ||
            html.contains(
                "video.tv_show"
            ) ||
            html.contains(
                "class=\"plyr-tv-container"
            ) ||
            html.contains(
                "class='plyr-tv-container"
            ) ||
            html.contains(
                "data-mj-season="
            )
    }

    private fun parseTvEpisodesFromRawHtml(
        html: String,
        detailUrl: String
    ): List<Episode> {

        if (html.isBlank()) {
            return emptyList()
        }

        val normalized =
            normalizeTvHtml(html)

        val scriptMatch =
            Regex(
                "(?is)<script[^>]*id=[\\\"']plyr-tv-config[\\\"'][^>]*>(.*?)</script>"
            ).find(normalized)
                ?: return emptyList()

        var json =
            scriptMatch.groupValues
                .getOrNull(1)
                ?.trim()
                .orEmpty()

        if (json.isBlank()) {
            return emptyList()
        }

        val firstBrace =
            json.indexOf('{')

        val lastBrace =
            json.lastIndexOf('}')

        if (
            firstBrace >= 0 &&
            lastBrace > firstBrace
        ) {
            json =
                json.substring(
                    firstBrace,
                    lastBrace + 1
                )
        }

        val result =
            linkedMapOf<String, Episode>()

        val seasonPattern =
            Regex(
                "(?is)\\\"num\\\"\\s*:\\s*(\\d+)\\s*,\\s*\\\"folder\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?\\\"episode_files\\\"\\s*:\\s*\\{(.*?)\\}\\s*,\\s*\\\"dash_manifests\\\""
            )

        seasonPattern
            .findAll(json)
            .forEach { seasonMatch ->

                val season =
                    seasonMatch.groupValues
                        .getOrNull(1)
                        ?.toIntOrNull()
                        ?: return@forEach

                val episodeBlock =
                    seasonMatch.groupValues
                        .getOrNull(3)
                        .orEmpty()

                val episodePattern =
                    Regex(
                        "\\\"(\\d+)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\""
                    )

                episodePattern
                    .findAll(episodeBlock)
                    .forEach { episodeMatch ->

                        val episode =
                            episodeMatch
                                .groupValues
                                .getOrNull(1)
                                ?.toIntOrNull()
                                ?: return@forEach

                        val filename =
                            episodeMatch
                                .groupValues
                                .getOrNull(2)
                                ?.trim()
                                .orEmpty()

                        if (
                            episode <= 0 ||
                            filename.isBlank()
                        ) {
                            return@forEach
                        }

                        val cleanFilename =
                            filename
                                .substringBeforeLast(
                                    '.',
                                    filename
                                )
                                .trim()

                        val label =
                            if (
                                cleanFilename.isBlank()
                            ) {
                                "Episode $episode"
                            } else {
                                "E$episode $cleanFilename"
                            }

                        val key =
                            "S$season-E$episode"

                        result.putIfAbsent(
                            key,
                            newEpisode(
                                buildEpisodeData(
                                    detailUrl,
                                    season,
                                    episode
                                )
                            ) {
                                name = label
                                this.season = season
                                this.episode = episode
                            }
                        )
                    }
            }

        return result.values.sortedWith(
            compareBy<Episode> {
                it.season ?: 1
            }.thenBy {
                it.episode
                    ?: Int.MAX_VALUE
            }
        )
    }

    private fun parseTvProgressEpisodes(
        document: Document,
        detailUrl: String
    ): List<Episode> {

        val result =
            linkedMapOf<String, Episode>()

        document.select(
            ".mj-tv-progress-row-wrap[data-mj-season], " +
                "[data-mj-row-wrap][data-mj-season]"
        ).forEach { seasonRow ->

            val season =
                findNumber(
                    seasonRow.attr(
                        "data-mj-season"
                    )
                )
                    ?: extractSeasonNumberFromText(
                        seasonRow.text()
                    )
                    ?: 1

            seasonRow.select(
                ".mj-tv-progress-ep[data-mj-ep], " +
                    "[data-mj-ep]"
            ).forEach { button ->

                val episode =
                    findNumber(
                        button.attr(
                            "data-mj-ep"
                        )
                    )
                        ?: return@forEach

                val label =
                    firstNonBlank(
                        button.attr(
                            "aria-label"
                        ),
                        button.attr(
                            "title"
                        ),
                        button.text()
                    ).trim()

                val key =
                    "S$season-E$episode"

                result.putIfAbsent(
                    key,
                    newEpisode(
                        buildEpisodeData(
                            detailUrl,
                            season,
                            episode
                        )
                    ) {
                        name =
                            label.ifBlank {
                                "S$season E$episode"
                            }

                        this.season =
                            season

                        this.episode =
                            episode
                    }
                )
            }
        }

        return result.values.sortedWith(
            compareBy<Episode> {
                it.season ?: 1
            }.thenBy {
                it.episode
                    ?: Int.MAX_VALUE
            }
        )
    }

    private fun extractSeasonNumberFromText(
        text: String
    ): Int? {

        return Regex(
            "(?i)\\bseason\\s*[-._ ]?(\\d+)\\b"
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun buildEpisodes(
        config: TvConfig,
        detailUrl: String
    ): List<Episode> {

        val result =
            mutableListOf<Episode>()

        config.seasons
            .sortedBy {
                it.number
            }
            .forEach { season ->

                season.episodeFiles
                    .toSortedMap()
                    .forEach {
                        (episode, filename) ->

                        val cleanFilename =
                            filename
                                .substringBeforeLast(
                                    '.',
                                    filename
                                )
                                .trim()

                        val label =
                            if (
                                cleanFilename.isBlank()
                            ) {
                                "Episode $episode"
                            } else {
                                "E$episode $cleanFilename"
                            }

                        result +=
                            newEpisode(
                                buildEpisodeData(
                                    detailUrl,
                                    season.number,
                                    episode
                                )
                            ) {

                                name =
                                    label

                                this.season =
                                    season.number

                                this.episode =
                                    episode
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

        val separator =
            if (detailUrl.contains("?")) {
                "&"
            } else {
                "?"
            }

        return detailUrl +
            separator +
            "mj_episode=1&mj_season=$season&mj_ep=$episode"
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
                ).trim()

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
                    element.attr(
                        "data-season"
                    )
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
                    element.attr(
                        "data-episode"
                    )
                )
                    ?: findNumber(
                        element.attr(
                            "data-ep"
                        )
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
                }.thenBy {
                    it.episode
                        ?: Int.MAX_VALUE
                }
            )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        val input =
            data.trim()

        if (input.isBlank()) {
            return false
        }

        /*
         * TV EPISODE PLAYBACK
         */
        val episode =
            parseEpisodeFragment(
                input
            )

        if (episode != null) {

            val detailUrl =
                input.substringBefore("#")

            val response =
                runCatching {
                    app.get(
                        detailUrl,
                        headers =
                            pageHeaders(
                                detailUrl
                            ),
                        timeout = 20_000
                    )
                }.getOrNull()
                    ?: return false

            val config =
                extractTvConfig(
                    response.document,
                    response.text
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
         * Fetch the detail page again when Play is pressed. MojaLoss
         * generates a signed mediaToken in the page HTML, so using the
         * current page response avoids stale playback URLs.
         */
        val moviePlayer =
            getFreshMoviePlayerData(input)

        if (moviePlayer != null) {

            val media =
                if (moviePlayer.mediaToken.isNullOrBlank()) {
                    null
                } else {
                    buildMovieMedia(
                        moviePlayer.defaultSource,
                        moviePlayer.mediaToken.orEmpty(),
                        moviePlayer.subtitleSource
                    )
                }

            if (media != null) {

                media.subtitleUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { subtitleUrl ->

                        subtitleCallback(
                            SubtitleFile(
                                lang = "English",
                                url = subtitleUrl
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

            moviePlayer.alreadyPlayableSource
                ?.let { directUrl ->

                    emitMediaLink(
                        directUrl,
                        callback,
                        "Moja Loss Direct"
                    )

                    return true
                }
        }

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

    private data class MoviePlayerData(
        val defaultSource: String,
        val mediaToken: String?,
        val subtitleSource: String?,
        val alreadyPlayableSource: String?
    )

    private suspend fun getFreshMoviePlayerData(
        detailUrl: String
    ): MoviePlayerData? {

        val cleanUrl =
            detailUrl
                .substringBefore("#")
                .trim()

        if (cleanUrl.isBlank()) {
            return null
        }

        val requestUrls =
            linkedSetOf<String>()

        requestUrls += addCacheBuster(cleanUrl)
        requestUrls += cleanUrl

        for (requestUrl in requestUrls) {

            val response =
                runCatching {
                    app.get(
                        requestUrl,
                        headers = pageHeaders(cleanUrl),
                        timeout = 20_000
                    )
                }.getOrNull()
                    ?: continue

            val document = response.document
            val html = response.text

            val video =
                document.selectFirst(
                    "video[data-default-src][data-media-token]"
                ) ?: document.selectFirst(
                    "#movie-video, video[data-default-src], video"
                )

            val defaultSource =
                firstNonBlank(
                    video?.attr("data-default-src"),
                    video?.selectFirst("source[src]")?.attr("src"),
                    extractRawAttribute(
                        html,
                        "data-default-src"
                    ),
                    extractRawPlayableSource(html)
                )
                    .let(::decodeHtmlEntities)
                    .trim()

            if (defaultSource.isBlank()) {
                continue
            }

            val mediaToken =
                firstNonBlank(
                    video?.attr("data-media-token"),
                    extractRawAttribute(
                        html,
                        "data-media-token"
                    )
                )
                    .let(::decodeHtmlEntities)
                    .trim()
                    .takeIf { it.isNotBlank() }

            val subtitleSource =
                firstNonBlank(
                    video?.attr("data-default-subtitle-src"),
                    extractRawAttribute(
                        html,
                        "data-default-subtitle-src"
                    )
                )
                    .let(::decodeHtmlEntities)
                    .trim()
                    .takeIf { it.isNotBlank() }

            val alreadyPlayable =
                defaultSource.takeIf {
                    isAlreadyPlayableMediaUrl(it)
                }

            return MoviePlayerData(
                defaultSource = defaultSource,
                mediaToken = mediaToken,
                subtitleSource = subtitleSource,
                alreadyPlayableSource = alreadyPlayable
            )
        }

        return null
    }

    private fun addCacheBuster(
        url: String
    ): String {

        val separator =
            if (url.contains("?")) {
                "&"
            } else {
                "?"
            }

        return url +
            separator +
            "mj_cs_refresh=" +
            System.currentTimeMillis()
    }

    private fun extractRawAttribute(
        html: String,
        attribute: String
    ): String {

        if (html.isBlank()) {
            return ""
        }

        return runCatching {
            Regex(
                "(?is)\\b" +
                    Regex.escape(attribute) +
                    "\\s*=\\s*[\"']([^\"']+)[\"']"
            )
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }.getOrDefault("")
    }

    private fun extractRawPlayableSource(
        html: String
    ): String {

        if (html.isBlank()) {
            return ""
        }

        return runCatching {

            Regex(
                "(?is)https?://(?:www\\.)?mojaloss\\.stream/directlink/[^\"'<>\\s]+\\.(?:mp4|mkv|webm)(?:\\?[^\"'<>\\s]*)?"
            )
                .find(html)
                ?.value
                .orEmpty()
                .ifBlank {

                    Regex(
                        "(?is)https?://media\\.mojaloss\\.stream/dl/[^\"'<>\\s]+\\.(?:mp4|mkv|webm)(?:\\?[^\"'<>\\s]*)?"
                    )
                        .find(html)
                        ?.value
                        .orEmpty()
                }

        }.getOrDefault("")
    }

    private fun decodeHtmlEntities(
        value: String
    ): String {

        return value
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&#x26;", "&")
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
    }

    private fun isAlreadyPlayableMediaUrl(
        url: String
    ): Boolean {

        val lower =
            url.lowercase(Locale.ROOT)

        return lower.contains(
            "media.mojaloss.stream/dl/"
        ) &&
            lower.contains("?") &&
            (
                ".mp4" in lower ||
                    ".mkv" in lower ||
                    ".webm" in lower ||
                    ".m3u8" in lower ||
                    ".mpd" in lower
            )
    }

    private fun extractTvConfig(
        document: Document,
        rawHtml: String
    ): TvConfig? {

        val candidates =
            linkedSetOf<String>()

        document.selectFirst(
            "script#plyr-tv-config, " +
                "script[type='application/json']#plyr-tv-config"
        )?.let { script ->

            script.data()
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    candidates::add
                )

            script.html()
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    candidates::add
                )

            script.text()
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    candidates::add
                )
        }

        val normalizedHtml =
            normalizeTvHtml(
                rawHtml
            )

        Regex(
            "(?is)<script[^>]*id=[\\\"']plyr-tv-config[\\\"'][^>]*>(.*?)</script>"
        )
            .find(normalizedHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let(
                candidates::add
            )

        for (candidate in candidates) {

            val json =
                extractJsonObject(
                    candidate
                )
                    ?: continue

            runCatching {
                parseTvConfigJson(
                    json
                )
            }
                .getOrNull()
                ?.let { config ->

                    if (
                        config.seasons.any {
                            it.episodeFiles.isNotEmpty()
                        }
                    ) {
                        return config
                    }
                }
        }

        return null
    }

    private fun normalizeTvHtml(
        value: String
    ): String {

        return value
            .replace(
                "\\/",
                "/"
            )
            .replace(
                "\\u002F",
                "/"
            )
            .replace(
                "\\u002f",
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
                "\\u003a",
                ":"
            )
            .replace(
                "&amp;",
                "&"
            )
            .replace(
                "&quot;",
                "\""
            )
            .replace(
                "&#34;",
                "\""
            )
            .replace(
                "&#39;",
                "'"
            )
    }

    private fun extractJsonObject(
        value: String
    ): String? {

        val normalized =
            normalizeTvHtml(
                value
            ).trim()

        if (normalized.isBlank()) {
            return null
        }

        val firstBrace =
            normalized.indexOf(
                '{'
            )

        val lastBrace =
            normalized.lastIndexOf(
                '}'
            )

        if (
            firstBrace < 0 ||
            lastBrace <= firstBrace
        ) {
            return null
        }

        return normalized.substring(
            firstBrace,
            lastBrace + 1
        )
    }

    private fun parseTvConfigJson(
        raw: String
    ): TvConfig? {

        val objectJson =
            JSONObject(raw)

        val baseUrl =
            objectJson
                .optString(
                    "baseUrl"
                )
                .trim()

        val mediaToken =
            objectJson
                .optString(
                    "mediaToken"
                )
                .trim()

        if (baseUrl.isBlank()) {
            return null
        }

        val seasons =
            mutableListOf<TvSeason>()

        val seasonArray =
            objectJson.optJSONArray(
                "seasons"
            )
                ?: return null

        for (
            index in
            0 until seasonArray.length()
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
                    .optString(
                        "folder"
                    )
                    .trim()

            if (
                number <= 0 ||
                folder.isBlank()
            ) {
                continue
            }

            val episodeFiles =
                mutableMapOf<
                    Int,
                    String
                >()

            seasonObject
                .optJSONObject(
                    "episode_files"
                )
                ?.let { obj ->

                    val keys =
                        obj.keys()

                    while (
                        keys.hasNext()
                    ) {

                        val key =
                            keys.next()

                        val episode =
                            key.toIntOrNull()
                                ?: continue

                        val filename =
                            obj.optString(
                                key
                            ).trim()

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
                mutableMapOf<
                    Int,
                    String
                >()

            seasonObject
                .optJSONObject(
                    "subtitle_files"
                )
                ?.let { obj ->

                    val keys =
                        obj.keys()

                    while (
                        keys.hasNext()
                    ) {

                        val key =
                            keys.next()

                        val episode =
                            key.toIntOrNull()
                                ?: continue

                        val filename =
                            obj.optString(
                                key
                            ).trim()

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
                episodeFiles =
                    episodeFiles,
                subtitleFiles =
                    subtitleFiles
            )
        }

        if (
            seasons.none {
                it.episodeFiles.isNotEmpty()
            }
        ) {
            return null
        }

        return TvConfig(
            baseUrl = baseUrl,
            mediaToken = mediaToken,
            seasons = seasons
        )
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
            encodePathPart(
                folder
            )

        val encodedFilename =
            encodePathPart(
                filename
            )

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
                            encodePathPart(
                                it
                            )

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

        return if (
            url.contains("?")
        ) {
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
        ).replace(
            "+",
            "%20"
        )
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        callback: (
            ExtractorLink
        ) -> Unit,
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

        val query =
            runCatching {
                URI(url)
                    .rawQuery
                    .orEmpty()
            }.getOrDefault("")

        val fragment =
            runCatching {
                URI(url)
                    .rawFragment
                    .orEmpty()
            }.getOrDefault("")

        val source =
            listOf(query, fragment)
                .filter { it.isNotBlank() }
                .joinToString("&")

        if (source.isBlank()) {
            return null
        }

        val season =
            Regex(
                "(?i)(?:^|&)mj_season=(\\d+)"
            ).find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex(
                "(?i)(?:^|&)season=(\\d+)"
            ).find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val episode =
            Regex(
                "(?i)(?:^|&)mj_ep=(\\d+)"
            ).find(source)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex(
                "(?i)(?:^|&)episode=(\\d+)"
            ).find(source)
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
            .replace(
                "-",
                " "
            )
            .replace(
                "_",
                " "
            )
            .replace(
                Regex(
                    "\\b\\d{4}\\b"
                ),
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

        } catch (
            _: Exception
        ) {
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

        } catch (
            _: Exception
        ) {

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
