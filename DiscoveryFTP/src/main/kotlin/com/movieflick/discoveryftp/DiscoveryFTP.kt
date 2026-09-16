package com.movieflick.discoveryftp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiscoveryFTP : MainAPI() {

    override var mainUrl = BASE_URL
    override var name = "Discovery FTP"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * Only these five rows are exposed to CloudStream.
     *
     * 1. Movies
     * 2. Dual Audio
     * 3. Hindi Movies
     * 4. TV Show
     * 5. Anime
     */
    override val mainPage = mainPageOf(
        "discovery://movies" to "Movies",
        "discovery://dual" to "Dual Audio",
        "discovery://hindi" to "Hindi Movies",
        "discovery://tv" to "TV Show",
        "discovery://anime" to "Anime"
    )

    private companion object {
        const val BASE_URL = "https://movies.discoveryftp.net"
        const val INITIAL_BATCH = 6
        const val CONTINUE_BATCH = 10
        const val SOURCE_PREFETCH = 4
        const val SEARCH_MAX_PAGES = 5
        const val DUPLICATE_INDEX_MAX_PAGES = 6

        val DUAL_SOURCES = listOf(
            Source("$BASE_URL/s/category/Dubbed", SourceKind.SERIES),
            Source("$BASE_URL/m/dual/Animation", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Bangla", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/English", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Others", SourceKind.MOVIE),
            Source("$BASE_URL/m/dual/Tamil", SourceKind.MOVIE)
        )

        val ANIME_SOURCES = listOf(
            Source("$BASE_URL/m/category/Animation", SourceKind.ANIME_MOVIE),
            Source("$BASE_URL/s/category/Animation", SourceKind.ANIME_SERIES)
        )

        val SEARCH_SOURCES = listOf(
            Source("$BASE_URL/m", SourceKind.MOVIE),
            Source("$BASE_URL/s", SourceKind.SERIES),
            Source("$BASE_URL/m/category/Animation", SourceKind.ANIME_MOVIE),
            Source("$BASE_URL/s/category/Animation", SourceKind.ANIME_SERIES)
        )
    }

    private enum class SourceKind {
        MOVIE,
        SERIES,
        ANIME_MOVIE,
        ANIME_SERIES
    }

    private data class Source(
        val url: String,
        val kind: SourceKind
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val type: TvType,
        val source: String,
        val order: Int,
        val year: Int? = null,
        val qualityLabel: String = "",
        val qualityRank: Int = 0
    )

    private val pageCache =
        ConcurrentHashMap<String, List<SiteItem>>()

    private val protectedIndexMutex = Mutex()
    @Volatile
    private var protectedDuplicateKeys: Set<String>? = null

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

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/153.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,bn;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to referer
    )

    private fun SiteItem.toSearchResponse(): SearchResponse {
        val displayTitle = formattedDisplayTitle()

        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(
                displayTitle,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }

            TvType.Anime -> newMovieSearchResponse(
                displayTitle,
                url,
                TvType.Anime
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }

            else -> newMovieSearchResponse(
                displayTitle,
                url,
                TvType.Movie
            ) {
                posterUrl = poster
                this.year = year
                if (qualityLabel.isNotBlank()) {
                    addQuality(qualityLabel)
                }
            }
        }
    }

    private fun SiteItem.formattedDisplayTitle(): String {
        val clean = title
            .replace(Regex("\\s+"), " ")
            .trim()

        if (qualityLabel.isBlank()) return clean

        return "$clean [$qualityLabel]"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val pageNumber = page.coerceAtLeast(1)

        return when (request.data) {
            "discovery://movies" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/m", SourceKind.MOVIE),
                excludeProtectedDuplicates = true
            )

            "discovery://dual" -> buildMergedHome(
                request,
                pageNumber,
                DUAL_SOURCES
            )

            "discovery://hindi" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/m/dual/Hindi", SourceKind.MOVIE),
                excludeProtectedDuplicates = true
            )

            "discovery://tv" -> buildSingleSourceHome(
                request,
                pageNumber,
                Source("$BASE_URL/s", SourceKind.SERIES)
            )

            "discovery://anime" -> buildMergedHome(
                request,
                pageNumber,
                ANIME_SOURCES
            )

            else -> newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }
    }

    private suspend fun buildSingleSourceHome(
        request: MainPageRequest,
        page: Int,
        source: Source,
        excludeProtectedDuplicates: Boolean = false
    ): HomePageResponse {
        val required = requiredCount(page)
        val excluded = if (excludeProtectedDuplicates) {
            getProtectedDuplicateKeys()
        } else {
            emptySet()
        }

        val all = getItemsUpTo(
            source = source,
            requiredCount = required,
            excludedKeys = excluded
        )

        val offset = homeOffset(page)
        val take = homeTake(page)
        val current = all.drop(offset).take(take)

        val hasNext = all.size > offset + current.size ||
            fetchPage(source, page + 1).isNotEmpty()

        return newHomePageResponse(
            request,
            current.map { it.toSearchResponse() },
            hasNext
        )
    }

    private suspend fun buildMergedHome(
        request: MainPageRequest,
        page: Int,
        sources: List<Source>
    ): HomePageResponse = coroutineScope {
        if (sources.isEmpty()) {
            return@coroutineScope newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val required = requiredCount(page)

        /*
         * Every source contributes in round-robin order. The order within
         * each source is preserved, so the site's newest-first ordering is
         * not alphabetized or randomly shuffled.
         */
        val perSource = ((required + sources.size - 1) / sources.size) + SOURCE_PREFETCH

        val sourceLists = sources.map { source ->
            async {
                getItemsUpTo(source, perSource)
            }
        }.awaitAll()

        val merged = collapseByTitle(interleave(sourceLists))

        val offset = homeOffset(page)
        val take = homeTake(page)
        val current = merged.drop(offset).take(take)

        val hasNext = merged.size > offset + current.size ||
            sources.any { fetchPage(it, page + 1).isNotEmpty() }

        newHomePageResponse(
            request,
            current.map { it.toSearchResponse() },
            hasNext
        )
    }

    private fun requiredCount(page: Int): Int =
        if (page <= 1) INITIAL_BATCH
        else INITIAL_BATCH + ((page - 1) * CONTINUE_BATCH)

    private fun homeOffset(page: Int): Int =
        if (page <= 1) 0
        else INITIAL_BATCH + ((page - 2) * CONTINUE_BATCH)

    private fun homeTake(page: Int): Int =
        if (page <= 1) INITIAL_BATCH else CONTINUE_BATCH

    private suspend fun getItemsUpTo(
        source: Source,
        requiredCount: Int,
        excludedKeys: Set<String> = emptySet()
    ): List<SiteItem> {
        if (requiredCount <= 0) return emptyList()

        val result = mutableListOf<SiteItem>()
        var serverPage = 1

        while (result.size < requiredCount && serverPage <= 50) {
            val pageItems = fetchPage(source, serverPage)

            if (pageItems.isEmpty()) break

            result += pageItems.filterNot {
                excludedKeys.contains(contentKey(it)) ||
                    excludedKeys.contains(urlKey(it.url))
            }

            serverPage++
        }

        return collapseByTitle(result)
            .take(requiredCount)
    }

    private suspend fun fetchPage(
        source: Source,
        page: Int
    ): List<SiteItem> {
        val url = pagedUrl(source.url, page)

        pageCache[url]?.let { return it }

        val document = getDocument(url)
            ?: return emptyList()

        val items = parseListing(
            document = document,
            pageUrl = url,
            source = source
        )

        pageCache.putIfAbsent(url, items)
        return pageCache[url] ?: items
    }

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        if (normalized.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates += normalized

        /*
         * Discovery's listing/detail HTML is HTTPS in the supplied
         * website URLs, but keep HTTP fallback for deployments where
         * redirects are configured differently.
         */
        if (normalized.startsWith("https://", true)) {
            candidates += "http://" + normalized.removePrefix("https://")
        } else if (normalized.startsWith("http://", true)) {
            candidates += "https://" + normalized.removePrefix("http://")
        }

        for (candidate in candidates) {
            val result = runCatching {
                app.get(
                    candidate,
                    headers = pageHeaders(candidate)
                ).document
            }.getOrNull()

            if (result != null) return result
        }

        return null
    }

    private fun parseListing(
        document: Document,
        pageUrl: String,
        source: Source
    ): List<SiteItem> {
        val result = mutableListOf<SiteItem>()
        val seenContainers = HashSet<String>()

        /*
         * Discovery movie cards can contain several quality links inside one
         * .quality_stack. Those links represent the SAME movie, not separate
         * home cards. We therefore parse the card once and keep the highest
         * quality variant as the playable URL.
         */
        val containers = document.select("div.card, div.fcard")

        containers.forEachIndexed { index, card ->
            val cardIdentity = card.outerHtml().hashCode().toString()
            if (!seenContainers.add(cardIdentity)) return@forEachIndexed

            val viewAnchors = card.select(
                "a[href*='/m/view/'], a[href*='/s/view/']"
            )

            if (viewAnchors.isEmpty()) return@forEachIndexed

            val title = cleanTitle(
                firstNonBlank(
                    card.selectFirst(".details h3")?.text(),
                    card.selectFirst(".ftitle")?.text(),
                    card.selectFirst("h3")?.text(),
                    card.selectFirst("h4")?.ownText()
                )
            )

            if (title.isBlank()) return@forEachIndexed

            val poster = extractPoster(card, pageUrl)

            val variants = viewAnchors.mapNotNull { anchor ->
                val absolute = absoluteUrl(
                    anchor.attr("href").trim(),
                    pageUrl
                )

                if (!absolute.contains("/m/view/") &&
                    !absolute.contains("/s/view/")
                ) {
                    return@mapNotNull null
                }

                val label = cleanQualityLabel(
                    firstNonBlank(
                        anchor.selectFirst(".movie_details_span_end")?.text(),
                        if (anchor.hasClass("movie_details_span_end")) anchor.text() else null,
                        anchor.attr("title")
                    )
                )

                SiteVariant(
                    url = absolute,
                    qualityLabel = label,
                    qualityRank = qualityRank(label, absolute)
                )
            }

            val bestVariant = variants
                .maxWithOrNull(
                    compareBy<SiteVariant> { it.qualityRank }
                        .thenBy { it.url }
                )
                ?: return@forEachIndexed

            val type = when (source.kind) {
                SourceKind.SERIES,
                SourceKind.ANIME_SERIES -> TvType.TvSeries

                SourceKind.ANIME_MOVIE -> TvType.Anime

                SourceKind.MOVIE -> TvType.Movie
            }

            val year = card
                .selectFirst(".details .feedback span[title='views']")
                ?.text()
                ?.trim()
                ?.toIntOrNull()

            result += SiteItem(
                title = title,
                url = bestVariant.url,
                poster = poster,
                type = type,
                source = source.url,
                order = index,
                year = year,
                qualityLabel = bestVariant.qualityLabel,
                qualityRank = bestVariant.qualityRank
            )
        }

        /*
         * Fallback for unusual cards where no .card/.fcard wrapper exists.
         */
        if (result.isEmpty()) {
            document.select("a[href*='/m/view/'], a[href*='/s/view/']")
                .forEachIndexed { index, anchor ->
                    val absolute = absoluteUrl(anchor.attr("href").trim(), pageUrl)
                    if (!absolute.contains("/m/view/") && !absolute.contains("/s/view/")) {
                        return@forEachIndexed
                    }

                    val card = findCard(anchor)
                    val title = cleanTitle(
                        firstNonBlank(
                            card?.selectFirst(".details h3")?.text(),
                            card?.selectFirst(".ftitle")?.text(),
                            anchor.attr("title"),
                            titleFromUrl(absolute)
                        )
                    )

                    if (title.isBlank()) return@forEachIndexed

                    val label = cleanQualityLabel(
                        firstNonBlank(
                            anchor.selectFirst(".movie_details_span_end")?.text(),
                            if (anchor.hasClass("movie_details_span_end")) anchor.text() else null,
                            anchor.attr("title"),
                            card?.selectFirst(".poster[title]")?.attr("title")
                        )
                    )

                    val type = when (source.kind) {
                        SourceKind.SERIES, SourceKind.ANIME_SERIES -> TvType.TvSeries
                        SourceKind.ANIME_MOVIE -> TvType.Anime
                        SourceKind.MOVIE -> TvType.Movie
                    }

                    result += SiteItem(
                        title = title,
                        url = absolute,
                        poster = card?.let { extractPoster(it, pageUrl) }
                            ?: extractPoster(anchor, pageUrl),
                        type = type,
                        source = source.url,
                        order = index,
                        qualityLabel = label,
                        qualityRank = qualityRank(label, absolute)
                    )
                }
        }

        return collapseByTitle(result)
    }

    private data class SiteVariant(
        val url: String,
        val qualityLabel: String,
        val qualityRank: Int
    )

    private fun findCard(anchor: Element): Element? {
        var current: Element? = anchor

        repeat(10) {
            val element = current ?: return@repeat
            val className = element.className().lowercase(Locale.ROOT)

            if (className.contains("card") || className.contains("fcard")) {
                return element
            }

            if (element.selectFirst(".details h3") != null ||
                element.selectFirst(".ftitle") != null
            ) {
                return element
            }

            current = element.parent()
        }

        return anchor.parent()
    }

    private fun extractPoster(
        element: Element,
        baseUrl: String
    ): String? {
        val image = element.selectFirst("img[src]")
            ?: element.selectFirst("img[data-src]")
            ?: return null

        val raw = image.attr("src")
            .ifBlank { image.attr("data-src") }
            .trim()

        if (raw.isBlank()) return null

        return absoluteUrl(raw, baseUrl)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val rawQuery = query.trim()
        if (rawQuery.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val q = normalizeSearchQuery(rawQuery)
        if (q.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val pageNumber = page.coerceAtLeast(1)

        /*
         * The supplied site source exposes the search input, but the
         * backend search endpoint itself is not part of the provided source.
         * We therefore search the known listing sources locally.
         *
         * Season tokens such as "S1", "S01", "Season 1" are removed from
         * the query so "Mirzapur", "Mirzapur S1" and "Mirzapur Season 3"
         * can all resolve to the same base series. The load response then
         * exposes all available seasons.
         */
        val allMatches = mutableListOf<SiteItem>()

        for (source in SEARCH_SOURCES) {
            for (serverPage in 1..SEARCH_MAX_PAGES) {
                val items = fetchPage(source, serverPage)

                allMatches += items.filter {
                    val titleKey = normalizeTitleKey(it.title)
                    titleKey.contains(q) || q.split(Regex("\\s+"))
                        .filter { token -> token.isNotBlank() }
                        .all { token -> titleKey.contains(token) }
                }

                if (items.isEmpty()) break
            }
        }

        val ranked = collapseByTitle(allMatches)
            .sortedWith(
                compareByDescending<SiteItem> {
                    searchScore(q, normalizeTitleKey(it.title))
                }.thenBy {
                    it.order
                }
            )

        val pageSize = 24
        val offset = (pageNumber - 1) * pageSize
        val pageItems = ranked
            .drop(offset)
            .take(pageSize)

        return newSearchResponseList(
            pageItems.map { it.toSearchResponse() },
            offset + pageSize < ranked.size
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse {
        val input = url.trim()

        if (isMediaUrl(input)) {
            return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                if (input.contains("/s/", true)) TvType.TvSeries else TvType.Movie,
                input
            )
        }

        val document = getDocument(input)
            ?: return newMovieLoadResponse(
                titleFromUrl(input),
                input,
                if (input.contains("/s/view/", true)) TvType.TvSeries else TvType.Movie,
                input
            )

        val title = firstNonBlank(
            document.selectFirst(".movie-detail-content-test h3")?.text(),
            document.selectFirst(".movie-detail-content h3")?.text(),
            document.selectFirst("h1")?.text(),
            document.selectFirst("title")?.text(),
            titleFromUrl(input)
        ).trim()

        val poster = extractDetailPoster(
            document,
            input
        )

        val isSeries = input.contains("/s/view/", true) ||
            input.contains("/s/category/", true)

        if (isSeries) {
            val seasonLinks = extractSeasonLinks(
                document,
                input
            )

            val episodes = if (seasonLinks.isNotEmpty()) {
                coroutineScope {
                    seasonLinks.map { seasonLink ->
                        async {
                            val seasonDocument =
                                getDocument(seasonLink.url)

                            parseEpisodes(
                                document = seasonDocument,
                                seasonUrl = seasonLink.url,
                                defaultSeason = seasonLink.season,
                                fallbackPoster = poster
                            )
                        }
                    }.awaitAll().flatten()
                }
            } else {
                parseEpisodes(
                    document = document,
                    seasonUrl = input,
                    defaultSeason = 1,
                    fallbackPoster = poster
                )
            }

            val distinctEpisodes = episodes
                .distinctBy { episodeKey(it) }

            return newTvSeriesLoadResponse(
                title,
                input,
                TvType.TvSeries,
                distinctEpisodes
            ) {
                posterUrl = poster
                seasonNames = seasonLinks
                    .map { it.season }
                    .distinct()
                    .sortedDescending()
                    .map { season ->
                        SeasonData(
                            season = season,
                            name = "Season $season",
                            displaySeason = season
                        )
                    }
            }
        }

        val anime = input.contains("/dual/Animation", true) ||
            input.contains("/category/Animation", true)

        return newMovieLoadResponse(
            title,
            input,
            if (anime) TvType.Anime else TvType.Movie,
            input
        ) {
            posterUrl = poster
        }
    }

    private data class SeasonLink(
        val url: String,
        val season: Int
    )

    private fun extractSeasonLinks(
        document: Document,
        baseUrl: String
    ): List<SeasonLink> {
        val result = linkedMapOf<String, SeasonLink>()

        document.select("a[href*='/s/view/']").forEach { anchor ->
            val href = absoluteUrl(
                anchor.attr("href").trim(),
                baseUrl
            )

            val season = Regex(
                "(?i)(?:/|season\\s*)0*(\\d+)\\s*$"
            ).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("(?i)\\bseason\\s*0*(\\d+)\\b")
                    .find(anchor.text())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            if (season != null) {
                result.putIfAbsent(
                    "$href#$season",
                    SeasonLink(href, season)
                )
            }
        }

        return result.values
            .sortedByDescending { it.season }
            .toList()
    }

    private fun parseEpisodes(
        document: Document?,
        seasonUrl: String,
        defaultSeason: Int,
        fallbackPoster: String?
    ): List<Episode> {
        if (document == null) return emptyList()

        val episodes = mutableListOf<Episode>()

        /*
         * Discovery's supplied TV season source places the actual
         * downloadable/playable media URL in the episode card:
         *
         * h5 -> "S1 | EP 1"
         * a[href="...mkv"] -> media
         * h4 -> episode title
         */
        val mediaAnchors = document.select(
            "a[href]"
        ).filter { anchor ->
            val href = anchor.attr("href").trim()
            isMediaUrl(href)
        }

        mediaAnchors.forEachIndexed { index, anchor ->
            val mediaUrl = absoluteUrl(
                anchor.attr("href").trim(),
                seasonUrl
            )

            val card = findEpisodeCard(anchor)

            val headerText = cleanTitle(
                card?.selectFirst("h5")?.text()
                    ?: anchor.parent()?.selectFirst("h5")?.text()
                    ?: ""
            )

            val episodeTitle = cleanTitle(
                firstNonBlank(
                    card?.selectFirst("h4")?.ownText(),
                    card?.selectFirst("h4")?.text(),
                    cleanEpisodeTitle(headerText),
                    "Episode ${index + 1}"
                )
            )

            val season = parseSeasonNumber(
                headerText,
                mediaUrl,
                defaultSeason
            )

            val episodeNumber = parseEpisodeNumber(
                headerText,
                mediaUrl,
                index + 1
            )

            val episodeDisplayName = if (episodeTitle.isBlank()) {
                "S$season | EP $episodeNumber"
            } else {
                "$episodeTitle [S$season | EP $episodeNumber]"
            }

            episodes += newEpisode(mediaUrl) {
                name = episodeDisplayName
                this.season = season
                this.episode = episodeNumber
                posterUrl = fallbackPoster
                description = card?.selectFirst(".season_overview p")?.text()?.trim()
            }
        }

        /*
         * Fallback for pages where the episode media is published via
         * a normal episode page instead of directly in the href.
         */
        if (episodes.isEmpty()) {
            val episodeAnchors = document.select(
                "a[href*='/s/view/']"
            )

            episodeAnchors.forEachIndexed { index, anchor ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@forEachIndexed

                val absolute = absoluteUrl(
                    href,
                    seasonUrl
                )

                val text = cleanTitle(
                    firstNonBlank(
                        anchor.attr("title"),
                        anchor.text(),
                        "Episode ${index + 1}"
                    )
                )

                episodes += newEpisode(absolute) {
                    name = text
                    season = parseSeasonNumber(
                        text,
                        absolute,
                        defaultSeason
                    )
                    episode = parseEpisodeNumber(
                        text,
                        absolute,
                        index + 1
                    )
                    posterUrl = fallbackPoster
                }
            }
        }

        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: defaultSeason }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun findEpisodeCard(anchor: Element): Element? {
        var current: Element? = anchor

        repeat(10) {
            val element = current ?: return@repeat
            val text = element.text().lowercase(Locale.ROOT)

            if (
                element.select("h5").isNotEmpty() ||
                element.select("h4").isNotEmpty() ||
                text.contains("ep ")
            ) {
                return element
            }

            current = element.parent()
        }

        return anchor.parent()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val input = data.trim()
        if (input.isBlank()) return false

        /*
         * TV episode source files supplied by the user are already direct
         * CDN media URLs, so pass those exact URLs to CloudStream.
         */
        if (isMediaUrl(input)) {
            emitMedia(
                mediaUrl = input,
                callback = callback
            )
            return true
        }

        /*
         * Movie detail page source explicitly publishes:
         * <video>
         *   <source src="https://cdn....mkv">
         * </video>
         *
         * Resolve the exact published source only when Play is pressed.
         */
        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders(input)
            )
        }.getOrNull() ?: return false

        val document = response.document

        val mediaUrls = linkedSetOf<String>()

        document.select(
            "video[src], video source[src], source[src]"
        ).forEach { element ->
            val raw = element.attr("src").trim()
            if (raw.isNotBlank()) {
                val absolute = absoluteUrl(
                    raw,
                    input
                )
                if (isMediaUrl(absolute)) {
                    mediaUrls += absolute
                }
            }
        }

        /*
         * A few deployments expose the media URL in data-src/data-video.
         */
        document.select(
            "[data-src], [data-video], [data-file]"
        ).forEach { element ->
            listOf(
                element.attr("data-src"),
                element.attr("data-video"),
                element.attr("data-file")
            ).forEach { raw ->
                if (raw.isBlank()) return@forEach

                val absolute = absoluteUrl(
                    raw,
                    input
                )

                if (isMediaUrl(absolute)) {
                    mediaUrls += absolute
                }
            }
        }

        /*
         * Last HTML-level fallback: scan the page source for direct media URLs.
         * This is useful when the source is rendered inside inline JavaScript.
         */
        val html = response.text
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val regex = Regex(
            """(?i)https?://[^"'<>\\s]+(?:${mediaExtensions.joinToString("|").replace(".", "\\.")})(?:\\?[^"'<>\\s]*)?"""
        )

        regex.findAll(html).forEach { match ->
            val candidate = cleanUrl(match.value)
            if (isMediaUrl(candidate)) {
                mediaUrls += candidate
            }
        }

        if (mediaUrls.isEmpty()) return false

        mediaUrls
            .distinct()
            .forEach { media ->
                emitMedia(
                    mediaUrl = media,
                    callback = callback
                )
            }

        return true
    }

    private suspend fun emitMedia(
        mediaUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = mediaUrl.lowercase(Locale.ROOT)

        val type = when {
            ".m3u8" in lower ->
                ExtractorLinkType.M3U8

            ".mpd" in lower ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

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
                name = "Discovery FTP",
                url = mediaUrl,
                type = type
            ) {
                this.quality = quality
            }
        )
    }

    private fun extractDetailPoster(
        document: Document,
        baseUrl: String
    ): String? {
        val selectors = listOf(
            ".movie-detail-banner img[src]",
            ".movie-detail img[src]",
            ".movie-detail-content img[src]",
            "meta[property='og:image']",
            "img[src]"
        )

        for (selector in selectors) {
            val element = document.selectFirst(selector)
                ?: continue

            val raw = when (element.tagName()) {
                "meta" -> element.attr("content")
                else -> element.attr("src")
            }.trim()

            if (raw.isNotBlank()) {
                return absoluteUrl(raw, baseUrl)
            }
        }

        return null
    }

    private fun isMediaUrl(url: String): Boolean {
        val clean = url
            .substringBefore('?')
            .lowercase(Locale.ROOT)

        return mediaExtensions.any { clean.endsWith(it) }
    }

    private fun pagedUrl(
        baseUrl: String,
        page: Int
    ): String {
        if (page <= 1) return baseUrl.removeSuffix("/")

        return baseUrl.removeSuffix("/") + "/$page"
    }

    private fun interleave(
        lists: List<List<SiteItem>>
    ): List<SiteItem> {
        if (lists.isEmpty()) return emptyList()

        val result = mutableListOf<SiteItem>()
        var index = 0

        while (true) {
            var added = false

            lists.forEach { list ->
                if (index < list.size) {
                    result += list[index]
                    added = true
                }
            }

            if (!added) break
            index++
        }

        return result
    }

    private fun collapseByTitle(items: List<SiteItem>): List<SiteItem> {
        if (items.isEmpty()) return emptyList()

        val result = LinkedHashMap<String, SiteItem>()

        items.forEach { item ->
            val key = contentKey(item) + "|" + item.type.name
            val existing = result[key]

            if (existing == null || isBetterVariant(item, existing)) {
                result[key] = if (existing == null) {
                    item
                } else {
                    item.copy(order = minOf(item.order, existing.order))
                }
            }
        }

        return result.values.toList()
    }

    private fun isBetterVariant(candidate: SiteItem, existing: SiteItem): Boolean {
        if (candidate.qualityRank != existing.qualityRank) {
            return candidate.qualityRank > existing.qualityRank
        }

        return candidate.order < existing.order
    }

    private fun contentKey(item: SiteItem): String =
        normalizeTitleKey(item.title)

    private fun urlKey(url: String): String =
        "url:" + dedupeKey(url)

    private fun normalizeTitleKey(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\(\\(\\d{4}\\)\\)"), " ")
            .replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeSearchQuery(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("\\bseason\\s*0*\\d+\\b"), " ")
            .replace(Regex("\\bs\\s*0*\\d+\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { normalizeTitleKey(it) }
    }

    private fun cleanQualityLabel(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("\\s{2,}"), " ")
    }

    private fun qualityRank(label: String, url: String): Int {
        val lower = "$label $url".lowercase(Locale.ROOT)

        return when {
            "2160" in lower || "4k" in lower -> 2160
            "1440" in lower -> 1440
            "1080" in lower -> 1080
            "720" in lower -> 720
            "480" in lower -> 480
            "360" in lower -> 360
            "hd" in lower -> 720
            else -> 0
        }
    }

    private suspend fun getProtectedDuplicateKeys(): Set<String> {
        protectedDuplicateKeys?.let { return it }

        return protectedIndexMutex.withLock {
            protectedDuplicateKeys?.let { return@withLock it }

            val sources = buildList {
                addAll(DUAL_SOURCES)
                add(Source("$BASE_URL/s", SourceKind.SERIES))
                addAll(ANIME_SOURCES)
            }

            val keys = coroutineScope {
                sources.map { source ->
                    async {
                        val local = mutableSetOf<String>()
                        var serverPage = 1

                        while (serverPage <= DUPLICATE_INDEX_MAX_PAGES) {
                            val items = fetchPage(source, serverPage)
                            if (items.isEmpty()) break

                            items.forEach { item ->
                                local += contentKey(item)
                                local += urlKey(item.url)
                            }

                            serverPage++
                        }

                        local
                    }
                }.awaitAll().fold(mutableSetOf()) { acc, set ->
                    acc.apply { addAll(set) }
                }
            }

            keys.also { protectedDuplicateKeys = it }
        }
    }

    private fun dedupeKey(url: String): String =
        url.substringBefore("#")
            .trim()
            .lowercase(Locale.ROOT)

    private fun episodeKey(
        episode: Episode
    ): String =
        listOf(
            episode.season ?: 1,
            episode.episode ?: 0,
            episode.data
        ).joinToString("|")

    private fun parseSeasonNumber(
        text: String,
        url: String,
        fallback: Int
    ): Int {
        val values = listOf(
            Regex("(?i)\\bS\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bSeason\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bS\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bSeason\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1)
        )

        return values.firstNotNullOfOrNull { it?.toIntOrNull() }
            ?: fallback
    }

    private fun parseEpisodeNumber(
        text: String,
        url: String,
        fallback: Int
    ): Int {
        val values = listOf(
            Regex("(?i)\\bEP\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bEpisode\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bE\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bEP\\s*0*(\\d+)").find(url)
                ?.groupValues?.getOrNull(1),
            Regex("(?i)\\bE\\s*0*(\\d+)").find(text)
                ?.groupValues?.getOrNull(1)
        )

        return values.firstNotNullOfOrNull { it?.toIntOrNull() }
            ?: fallback
    }

    private fun cleanEpisodeTitle(header: String): String {
        return header
            .replace(
                Regex("(?i)\\bS\\d+\\s*\\|\\s*EP\\s*\\d+\\b"),
                ""
            )
            .replace(
                Regex("(?i)\\bSeason\\s*\\d+\\s*\\|\\s*Episode\\s*\\d+\\b"),
                ""
            )
            .trim(' ', '|', '-', ':')
    }

    private fun titleFromUrl(url: String): String {
        val last = url
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')

        if (last.isBlank()) return "Discovery FTP"

        val decoded = runCatching {
            URLDecoder.decode(
                last,
                StandardCharsets.UTF_8.toString()
            )
        }.getOrDefault(last)

        return decoded
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('.', ' ')
            .trim()
    }

    private fun cleanTitle(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cleanUrl(value: String): String =
        value.trim()
            .trim('"', '\'')

    private fun absoluteUrl(
        raw: String,
        baseUrl: String
    ): String {
        val value = cleanUrl(raw)
        if (value.isBlank()) return ""

        if (
            value.startsWith("http://", true) ||
            value.startsWith("https://", true)
        ) {
            return value
        }

        return runCatching {
            URI(baseUrl)
                .resolve(value)
                .toString()
        }.getOrElse {
            when {
                value.startsWith("//") ->
                    "https:$value"

                value.startsWith("/") ->
                    URI(baseUrl)
                        .resolve(value)
                        .toString()

                else ->
                    baseUrl.trimEnd('/') + "/" + value
            }
        }
    }

    private fun firstNonBlank(
        vararg values: String?
    ): String {
        return values.firstOrNull {
            !it.isNullOrBlank()
        } ?: ""
    }

    private fun searchScore(
        query: String,
        title: String
    ): Int {
        val q = query.lowercase(Locale.ROOT).trim()
        val t = title.lowercase(Locale.ROOT).trim()

        if (t == q) return 100
        if (t.startsWith(q)) return 80
        if (t.contains(q)) return 60

        val queryWords = q.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        return queryWords.count {
            t.contains(it)
        } * 10
    }

}
