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

        val encoded = URLEncoder.encode(
            q,
            StandardCharsets.UTF_8.toString()
        )

        /*
         * CTG's public search page is /search. Try the normal q parameter
         * first and keep a small fallback set for site-side changes.
         */
        val candidates = listOf(
            "$mainUrl/search?q=$encoded${pageSuffix(page)}",
            "$mainUrl/search?query=$encoded${pageSuffix(page)}",
            "$mainUrl/search?search=$encoded${pageSuffix(page)}"
        ).distinct()

        for (url in candidates) {
            val document = getDocument(url) ?: continue
            val items = parseItems(document, url)

            if (items.isNotEmpty()) {
                return newSearchResponseList(
                    items.take(30).map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), false)
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
         * Direct media URLs are sent straight to CloudStream's native player.
         */
        if (isMediaUrl(input)) {
            emitMediaLink(
                mediaUrl = input,
                referer = "$mainUrl/",
                callback = callback
            )
            return true
        }

        val response = runCatching {
            app.get(
                input,
                headers = pageHeaders + ("Referer" to "$mainUrl/")
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        /*
         * 1. Exact video/source/media attributes.
         */
        val directSources = extractMediaUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (directSources.isNotEmpty()) {
            directSources.forEach { media ->
                emitMediaLink(
                    mediaUrl = media,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * 2. Some FTP sites put the actual file behind a download endpoint.
         * Recover only the real media URL and never hand download.php itself
         * to the player.
         */
        val recovered = recoverDownloadUrls(
            document = document,
            html = html,
            baseUrl = input
        ).distinct()

        if (recovered.isNotEmpty()) {
            recovered.forEach { media ->
                emitMediaLink(
                    mediaUrl = media,
                    referer = input,
                    callback = callback
                )
            }
            return true
        }

        /*
         * 3. External embedded player fallback.
         */
        val iframes = document.select("iframe[src], iframe[data-src]")
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

    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
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

        val quality = qualityFromUrl(url)

        callback(
            newExtractorLink(
                source = name,
                name = when (type) {
                    ExtractorLinkType.M3U8 -> "$name HLS"
                    ExtractorLinkType.DASH -> "$name DASH"
                    else -> "$name Direct"
                },
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
            """(?i)https?://[^"'<>\\s]+\\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\\?[^"'<>\\s]*)?"""
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
