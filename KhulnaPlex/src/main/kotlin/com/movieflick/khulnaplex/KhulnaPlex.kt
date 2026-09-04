package com.movieflick.khulnaplex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class KhulnaPlex : MainAPI() {

    override var mainUrl = "http://khulnaplex.com"
    override var name = "Khulna Plex"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    /*
     * IMPORTANT HOME ORDER
     *
     * Search is intentionally NOT a home-page section.
     * CloudStream's own global search calls search() below.
     *
     * Anime is deliberately LAST.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/movies.php" to "All Movies",
        "$mainUrl/movies.php?category=hindi" to "Hindi Movies",
        "$mainUrl/movies.php?category=english" to "English Movies",
        "$mainUrl/movies.php?category=bangla" to "Bangla Movies",
        "$mainUrl/movies.php?category=dub" to "Dubbed Movies",
        "$mainUrl/series.php" to "TV Shows",
        "$mainUrl/movies.php?category=animation" to "Anime"
    )

    private data class SiteItem(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean
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
        ".m3u8", ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".ts"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = runCatching { app.get(url).document }.getOrElse {
            return newHomePageResponse(request, emptyList(), false)
        }

        val items = parseItems(document, request.data, request.name)
        val responses = items.map { it.toSearchResponse() }
        val hasNext = hasNextPage(document, page)

        return newHomePageResponse(request, responses, hasNext)
    }

    /*
     * CloudStream global search -> KhulnaPlex search.php.
     * No separate Search row is added to the home page.
     *
     * The site URL supplied by the user is search.php, but the exact
     * query parameter is not visible from the screenshots. We therefore
     * try the common parameters in a controlled fallback order.
     */
    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.toString())
        val candidates = if (page <= 1) {
            listOf(
                "$mainUrl/search.php?q=$encoded",
                "$mainUrl/search.php?query=$encoded",
                "$mainUrl/search.php?search=$encoded"
            )
        } else {
            listOf(
                "$mainUrl/search.php?q=$encoded&page=$page",
                "$mainUrl/search.php?query=$encoded&page=$page",
                "$mainUrl/search.php?search=$encoded&page=$page"
            )
        }

        for (url in candidates) {
            val result = runCatching {
                val response = app.get(url)
                val items = parseItems(response.document, url, "Search")
                Triple(items, response.document, url)
            }.getOrNull() ?: continue

            if (result.first.isNotEmpty()) {
                return newSearchResponseList(
                    result.first.map { it.toSearchResponse() },
                    hasNextPage(result.second, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val response = runCatching { app.get(url) }.getOrElse {
            return newMovieLoadResponse(
                titleFromUrl(url),
                url,
                TvType.Movie,
                url
            )
        }

        val document = response.document
        val pageTitle = extractPageTitle(document).ifBlank { titleFromUrl(url) }
        val poster = extractPoster(document, url)

        val isSeries = isSeriesUrl(url) || looksLikeSeriesPage(document)

        if (isSeries) {
            val episodes = parseEpisodes(document, url)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    pageTitle,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        return newMovieLoadResponse(
            pageTitle,
            url,
            if (isAnimeUrl(url)) TvType.Anime else TvType.Movie,
            url
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
        if (data.isBlank()) return false

        // Direct media URLs go straight to CloudStream's native player.
        if (isMediaUrl(data)) {
            emitMediaLink(data, data, callback)
            return true
        }

        val response = runCatching {
            app.get(data, headers = mapOf("Referer" to "$mainUrl/"))
        }.getOrNull() ?: return false

        val document = response.document
        val found = linkedSetOf<String>()

        fun addCandidate(raw: String?) {
            if (raw.isNullOrBlank()) return
            val cleaned = cleanMediaCandidate(raw) ?: return
            val fixed = absoluteUrl(cleaned, data)
            if (isMediaUrl(fixed)) found.add(fixed)
        }

        // 1) Native HTML5 video/source tags.
        document.select("video, video source, source").forEach { element ->
            addCandidate(element.attr("src"))
            addCandidate(element.attr("data-src"))
            addCandidate(element.attr("data-video"))
            addCandidate(element.attr("data-url"))
            addCandidate(element.attr("data-file"))
        }

        // 2) Direct media links hidden in anchors/data attributes.
        document.select("a[href], [data-src], [data-video], [data-file], [data-url]")
            .forEach { element ->
                addCandidate(element.attr("href"))
                addCandidate(element.attr("data-src"))
                addCandidate(element.attr("data-video"))
                addCandidate(element.attr("data-file"))
                addCandidate(element.attr("data-url"))
            }

        // 3) Player configuration embedded in JavaScript.
        val rawHtml = response.text
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val quotedMediaRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl)\s*[:=]\s*[\"']([^\"']+\.(?:m3u8|mp4|mkv|webm|mov|m4v|avi|ts)(?:\?[^\"'<> ]*)?)[\"']"""
        )
        quotedMediaRegex.findAll(rawHtml).forEach { match ->
            addCandidate(match.groupValues[1])
        }

        val absoluteMediaRegex = Regex(
            """(?i)https?://[^\"'<>\s]+?\.(?:m3u8|mp4|mkv|webm|mov|m4v|avi|ts)(?:\?[^\"'<>\s]*)?"""
        )
        absoluteMediaRegex.findAll(rawHtml).forEach { match ->
            addCandidate(match.value)
        }

        val relativeMediaRegex = Regex(
            """(?i)(?:/|\.\.?/)[^\"'<>\s]+?\.(?:m3u8|mp4|mkv|webm|mov|m4v|avi|ts)(?:\?[^\"'<>\s]*)?"""
        )
        relativeMediaRegex.findAll(rawHtml).forEach { match ->
            addCandidate(match.value)
        }

        // Preference: M3U8/HLS -> MP4 -> MKV -> other direct video.
        val bestMedia = found.minByOrNull { mediaPriority(it) }
        if (bestMedia != null) {
            emitMediaLink(bestMedia, data, callback)
            return true
        }

        // 4) External iframe/player fallback through CloudStream extractors.
        val iframeCandidates = document
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { element ->
                val raw = element.attr("src")
                    .ifBlank { element.attr("data-src") }
                raw.takeIf { it.isNotBlank() }?.let { absoluteUrl(it, data) }
            }
            .distinct()

        for (iframe in iframeCandidates) {
            val ok = runCatching {
                loadExtractor(iframe, subtitleCallback, callback)
                true
            }.getOrDefault(false)
            if (ok) return true
        }

        return false
    }

    private fun cleanMediaCandidate(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null

        value = value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim('"', '\'')
            .trimEnd(',', ';', ')', ']', '}')

        return value.takeIf { it.isNotBlank() }
    }

    private fun mediaPriority(url: String): Int {
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }
            .getOrElse { url.lowercase(Locale.ROOT) }

        return when {
            path.endsWith(".m3u8") -> 0
            path.endsWith(".mp4") -> 1
            path.endsWith(".mkv") -> 2
            path.endsWith(".webm") -> 3
            path.endsWith(".mov") -> 4
            path.endsWith(".m4v") -> 5
            path.endsWith(".ts") -> 6
            path.endsWith(".avi") -> 7
            else -> 99
        }
    }

    private fun parseItems(
        document: org.jsoup.nodes.Document,
        sourceUrl: String,
        sectionName: String
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        /* Primary selector: the movie/series cards link to watch.php. */
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!looksLikeContentLink(href)) return@forEach

            val absolute = absoluteUrl(href, sourceUrl)
            val isSeries = isSeriesUrl(absolute) || sectionName.contains("TV", true) ||
                absolute.contains("type=series", true) || absolute.contains("type=tv", true)

            val card = findCard(anchor)
            val poster = extractPosterFromElement(card, sourceUrl)
            val title = extractCardTitle(anchor, card).ifBlank { titleFromUrl(absolute) }

            if (title.isBlank()) return@forEach
            if (isNavigationTitle(title)) return@forEach

            result[absolute] = SiteItem(
                title = cleanTitle(title),
                url = absolute,
                poster = poster,
                isSeries = isSeries
            )
        }

        return result.values.toList()
    }

    private fun parseEpisodes(
        document: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!looksLikeEpisodeLink(href)) return@forEach

            val absolute = absoluteUrl(href, baseUrl)
            if (absolute == baseUrl) return@forEach

            val title = cleanTitle(anchor.text().ifBlank { "Episode" })
            val episodeNumber = episodeNumber(anchor, title, absolute)

            result[absolute] = newEpisode(absolute) {
                name = title
                season = 1
                episode = episodeNumber
            }
        }

        /* If no episode links exist but the page contains a direct video,
         * expose it as one episode instead of producing an empty series. */
        if (result.isEmpty()) {
            val direct = document.select("video source, video, source")
                .mapNotNull { it.attr("src").ifBlank { it.attr("data-src") }.takeIf(String::isNotBlank) }
                .map { absoluteUrl(it, baseUrl) }
                .firstOrNull { isMediaUrl(it) }

            if (direct != null) {
                result[direct] = newEpisode(direct) {
                    name = "Episode 1"
                    season = 1
                    episode = 1
                }
            }
        }

        return result.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun findCard(anchor: Element): Element {
        var current: Element? = anchor
        repeat(6) {
            val element = current ?: return@repeat
            val text = element.text()
            val imageCount = element.select("img").size
            val className = element.className().lowercase(Locale.ROOT)

            if (imageCount > 0 ||
                className.contains("card") ||
                className.contains("movie") ||
                className.contains("item") ||
                className.contains("poster")) {
                return element
            }
            current = element.parent()
        }
        return anchor
    }

    private fun extractCardTitle(anchor: Element, card: Element): String {
        val titleSelectors = listOf(
            ".title",
            ".movie-title",
            ".movie_name",
            ".name",
            "h2",
            "h3",
            "h4",
            "strong"
        )

        for (selector in titleSelectors) {
            val text = card.selectFirst(selector)?.text()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }

        val aria = anchor.attr("aria-label").trim()
        if (aria.isNotBlank()) return aria

        val imgAlt = card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        if (imgAlt.isNotBlank()) return imgAlt

        return anchor.text().trim()
    }

    private fun extractPoster(
        document: org.jsoup.nodes.Document,
        pageUrl: String
    ): String? {
        return extractPosterFromElement(document, pageUrl)
    }

    private fun extractPosterFromElement(
        element: Element,
        pageUrl: String
    ): String? {
        // Use the thumbnail/poster already supplied by KhulnaPlex.
        // No separate/manual poster URL is required.
        val videoPoster = element
            .select("video[poster], video[data-poster]")
            .asSequence()
            .mapNotNull { video ->
                video.attr("poster")
                    .ifBlank { video.attr("data-poster") }
                    .takeIf { it.isNotBlank() }
            }
            .map { absoluteUrl(it, pageUrl) }
            .firstOrNull()

        if (!videoPoster.isNullOrBlank()) return videoPoster

        val ogImage = element
            .selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.trim()

        if (!ogImage.isNullOrBlank()) return absoluteUrl(ogImage, pageUrl)

        val images = element.select("img[src], img[data-src], img[data-lazy-src]")
        if (images.isEmpty()) return null

        val preferred = images.firstOrNull { image ->
            val src = imageSource(image)
            val alt = image.attr("alt")
            val all = "$src $alt".lowercase(Locale.ROOT)
            all.contains("poster") || all.contains("cover") || all.contains("thumb")
        }

        val selected = preferred ?: return null
        return imageSource(selected)?.let { absoluteUrl(it, pageUrl) }
    }

    private fun imageSource(image: Element): String? {
        return sequenceOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        ).firstOrNull { it.isNotBlank() }
    }

    private fun extractPageTitle(document: org.jsoup.nodes.Document): String {
        val selectors = listOf(
            "h1",
            "h2",
            ".movie-title",
            ".movie_name",
            ".title",
            "meta[property=og:title]"
        )

        for (selector in selectors) {
            val element = document.selectFirst(selector) ?: continue
            val text = if (element.tagName() == "meta") {
                element.attr("content")
            } else {
                element.text()
            }.trim()
            if (text.isNotBlank()) return cleanTitle(text)
        }

        return cleanTitle(document.title())
    }

    private fun looksLikeContentLink(href: String): Boolean {
        val lower = href.lowercase(Locale.ROOT)
        if (lower.isBlank() || lower.startsWith("#") || lower.startsWith("javascript:")) return false
        if (lower.startsWith("mailto:") || lower.startsWith("tel:")) return false

        return lower.contains("watch.php") ||
            lower.contains("movie.php") ||
            lower.contains("series.php?id=") ||
            lower.contains("show.php?id=") ||
            lower.contains("details.php?id=") ||
            lower.contains("movie?id=")
    }

    private fun looksLikeEpisodeLink(href: String): Boolean {
        val lower = href.lowercase(Locale.ROOT)
        if (!looksLikeContentLink(href)) return false
        return lower.contains("episode") ||
            lower.contains("ep=") ||
            lower.contains("episode=") ||
            lower.contains("season=") ||
            lower.contains("type=episode")
    }

    private fun hasNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val next = document.select("a[href]").firstOrNull { anchor ->
            val text = anchor.text().trim().lowercase(Locale.ROOT)
            val rel = anchor.attr("rel").lowercase(Locale.ROOT)
            text == "next" || text.contains("next") || rel == "next" ||
                anchor.attr("aria-label").contains("next", true)
        }
        return next != null || document.select("a[href*=page=${currentPage + 1}]").isNotEmpty()
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
    }

    private fun isSeriesUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("series.php") ||
            lower.contains("show.php") ||
            lower.contains("type=series") ||
            lower.contains("type=tv")
    }

    private fun isAnimeUrl(url: String): Boolean {
        return url.contains("category=animation", true)
    }

    private fun looksLikeSeriesPage(document: org.jsoup.nodes.Document): Boolean {
        val text = document.text().lowercase(Locale.ROOT)
        return text.contains("season") && text.contains("episode")
    }

    private fun isMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }.getOrElse { url.lowercase(Locale.ROOT) }
        return mediaExtensions.any { path.endsWith(it) }
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val lower = mediaUrl.lowercase(Locale.ROOT)
        val type = if (lower.contains(".m3u8")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        val quality = when {
            "2160" in lower || "4k" in lower -> Qualities.P2160.value
            "1440" in lower -> Qualities.P1440.value
            "1080" in lower -> Qualities.P1080.value
            "720" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower -> Qualities.P360.value
            "240" in lower -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = mediaUrl,
                type = type
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    }

    private fun absoluteUrl(raw: String, base: String): String {
        val value = raw.trim()
        if (value.startsWith("//")) {
            val scheme = runCatching { URI(base).scheme }.getOrNull() ?: "http"
            return "$scheme:$value"
        }
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { URI(base).resolve(value).toString() }.getOrElse { value }
    }

    private fun titleFromUrl(url: String): String {
        val id = runCatching {
            URI(url).query
                ?.split('&')
                ?.firstOrNull { it.startsWith("id=") }
                ?.substringAfter('=')
        }.getOrNull()

        return id?.takeIf { it.isNotBlank() } ?: "KhulnaPlex"
    }

    private fun cleanTitle(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s*[-|•]+\\s*(watch|download|play)\\s*$"), "")
            .trim()
    }

    private fun isNavigationTitle(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower in setOf(
            "home", "movies", "tv shows", "tv series", "live tv",
            "search", "genres", "software", "request", "next", "previous"
        )
    }

    private fun episodeNumber(anchor: Element, title: String, url: String): Int {
        val candidates = listOf(
            Regex("(?i)episode\\s*([0-9]+)").find(title)?.groupValues?.getOrNull(1),
            Regex("(?i)\\bep\\s*([0-9]+)").find(title)?.groupValues?.getOrNull(1),
            Regex("(?i)(?:episode|ep)=([0-9]+)").find(url)?.groupValues?.getOrNull(1),
            anchor.attr("data-episode").takeIf { it.isNotBlank() }
        )
        return candidates.firstNotNullOfOrNull { it?.toIntOrNull() } ?: 1
    }
}

