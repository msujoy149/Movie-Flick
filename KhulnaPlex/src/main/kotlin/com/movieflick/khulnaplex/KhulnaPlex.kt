package com.movieflick.khulnaplex

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KhulnaPlex : MainAPI() {

    override var mainUrl = "https://khulnaplex.com"
    override var name = "Khulna Plex"
    override var lang = "bn"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

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
        ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".flv", ".ts"
    )

    private val maxHomeItems = 25

    private fun commonHeaders(referer: String): MutableMap<String, String> = mutableMapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer,
        "Connection" to "keep-alive"
    )

    private fun pageHeaders(referer: String = "$mainUrl/"): Map<String, String> =
        commonHeaders(referer).apply {
            this["Accept"] =
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            this["Sec-Fetch-Dest"] = "document"
            this["Sec-Fetch-Mode"] = "navigate"
            this["Sec-Fetch-Site"] = "same-origin"
        }

    private fun mediaHeaders(referer: String): Map<String, String> =
        commonHeaders(referer).apply {
            this["Accept"] = "*/*"
            this["Origin"] = originOf(referer)
            this["Sec-Fetch-Dest"] = "video"
            this["Sec-Fetch-Mode"] = "cors"
            this["Sec-Fetch-Site"] = "same-origin"
            this["Accept-Encoding"] = "identity"
        }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }.getOrElse { mainUrl }

    private suspend fun getDocument(url: String): Document? {
        val normalized = url.trim()
        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        } else if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        }

        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = pageHeaders(candidate)).document
            }.getOrNull()
            if (document != null) return document
        }

        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = getDocument(url)
            ?: return newHomePageResponse(request, emptyList(), false)

        /*
         * KhulnaPlex already puts many movie cards on one listing page.
         * Do NOT fetch 10-12 pages here: that makes CloudStream slow and can
         * cause the home request to time out. We parse every card on the
         * current page and expose up to 25 of them.
         */
        val items = parseItems(document, request.data, request.name, page)
            .take(maxHomeItems)

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
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)

        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.toString())
        val candidates = listOf(
            "$mainUrl/search.php?q=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?query=$encoded${if (page > 1) "&page=$page" else ""}",
            "$mainUrl/search.php?search=$encoded${if (page > 1) "&page=$page" else ""}"
        )

        for (url in candidates) {
            val document = getDocument(url) ?: continue
            val items = parseItems(document, url, "Search", page)
            if (items.isNotEmpty()) {
                return newSearchResponseList(
                    items.take(maxHomeItems).map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), false)
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

        val document = getDocument(url)
        if (document == null) {
            return newMovieLoadResponse(titleFromUrl(url), url, TvType.Movie, url)
        }

        val title = extractPageTitle(document).ifBlank { titleFromUrl(url) }
        val poster = extractPoster(document, url)
        val series = isSeriesUrl(url) || looksLikeSeriesPage(document)

        if (series) {
            val episodes = parseEpisodes(document, url)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                }
            }
        }

        // Keep the watch page as the canonical data. loadLinks() resolves the
        // playable source from the same page when Play is pressed, so the
        // player always receives a fresh MP4/MKV/M3U8/download URL.
        return newMovieLoadResponse(
            title,
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

        if (isMediaUrl(data)) {
            emitMediaAlternatives(
                urls = listOf(data),
                referer = "$mainUrl/",
                callback = callback
            )
            return true
        }

        val pageUrls = linkedSetOf<String>()
        pageUrls.add(data)
        if (data.startsWith("https://", true)) {
            pageUrls.add("http://" + data.removePrefix("https://"))
        } else if (data.startsWith("http://", true)) {
            pageUrls.add("https://" + data.removePrefix("http://"))
        }

        /*
         * IMPORTANT:
         * 1. Fetch the actual watch page.
         * 2. Read the <video><source> / script URLs.
         * 3. Read the site's download.php URL.
         * 4. Put the site's download endpoint FIRST because it is the
         *    server-supported delivery route for the same file.
         * 5. Add the raw media URL as a direct fallback.
         *
         * The previous versions emitted the raw MP4 first. When that endpoint
         * returned a non-2xx response to Media3, the player stopped with
         * ERROR_CODE_IO_BAD_HTTP_STATUS (2004) instead of getting to the
         * site's own download endpoint.
         */
        for (pageUrl in pageUrls) {
            val response = runCatching {
                app.get(pageUrl, headers = pageHeaders(pageUrl))
            }.getOrNull() ?: continue

            val document = response.document
            val html = response.text

            val downloadUrls = extractDownloadUrls(document, html, pageUrl)
            val directMedia = extractMediaUrls(document, html, pageUrl)
                .sortedBy { mediaPriority(it) }

            val ordered = linkedSetOf<String>()
            downloadUrls.forEach { ordered.add(it) }
            directMedia.forEach { ordered.add(it) }

            if (ordered.isNotEmpty()) {
                emitMediaAlternatives(
                    urls = ordered.toList(),
                    referer = pageUrl,
                    callback = callback
                )
                return true
            }

            val iframes = document.select("iframe[src], iframe[data-src]")
                .mapNotNull { iframe ->
                    iframe.attr("src")
                        .ifBlank { iframe.attr("data-src") }
                        .takeIf { it.isNotBlank() }
                        ?.let { absoluteUrl(it, pageUrl) }
                }
                .distinct()

            for (iframe in iframes) {
                if (runCatching {
                        loadExtractor(iframe, pageUrl, subtitleCallback, callback)
                    }.getOrDefault(false)) {
                    return true
                }
            }
        }

        return false
    }

    private suspend fun emitMediaAlternatives(
        urls: List<String>,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val unique = linkedSetOf<String>()

        // Prefer the exact scheme already used by the watch page, then add a
        // same-host scheme fallback. This avoids blindly preferring HTTPS when
        // the site is currently serving its media over HTTP, or vice versa.
        urls.forEach { original ->
            val candidates = linkedSetOf<String>()
            candidates.add(original)

            if (original.startsWith("http://", true) && original.contains("khulnaplex.com", true)) {
                candidates.add("https://" + original.removePrefix("http://"))
            } else if (original.startsWith("https://", true) && original.contains("khulnaplex.com", true)) {
                candidates.add("http://" + original.removePrefix("https://"))
            }

            candidates.forEach { candidate ->
                if (unique.add(candidate)) {
                    emitMediaLink(
                        mediaUrl = candidate,
                        referer = referer,
                        callback = callback,
                        label = if (candidate.contains("download.php", true)) {
                            "Khulna Plex Download"
                        } else if (candidate.contains(".m3u8", true)) {
                            "Khulna Plex HLS"
                        } else {
                            "Khulna Plex Direct"
                        }
                    )
                }
            }
        }
    }

    private fun extractDownloadUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        document.select("a[href*='download.php'], a.download-btn").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isNotBlank() && href.contains("download.php", true)) {
                found.add(absoluteUrl(cleanUrl(href), baseUrl))
            }
        }

        val cleanHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val regex = Regex(
            """(?i)(?:https?://[^"'<>\s]+|(?:/|\.\.?/)?download\.php\?[^"'<>\s)]+)"""
        )

        regex.findAll(cleanHtml).forEach { match ->
            val value = cleanUrl(match.value)
            if (value.contains("download.php", true)) {
                found.add(absoluteUrl(value, baseUrl))
            }
        }

        // If the page exposes only the raw /uploads/videos/... file, build the
        // same site's download endpoint from that exact file path as a fallback.
        if (found.isEmpty()) {
            val rawFile = extractMediaUrls(document, cleanHtml, baseUrl)
                .firstOrNull { it.contains("/uploads/videos/", true) }
            if (rawFile != null) {
                val path = runCatching { URI(rawFile).rawPath.trimStart('/') }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (path != null) {
                    val encoded = URLEncoder.encode(path, StandardCharsets.UTF_8.toString())
                        .replace("+", "%20")
                    found.add(absoluteUrl("/download.php?file=$encoded", baseUrl))
                }
            }
        }

        return found.distinct()
    }

    private fun cleanUrl(raw: String): String = raw
        .trim()
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
        .trim('"', '\'', '`')
        .trimEnd(',', ';', ')', ']', '}')

    private fun extractMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            var value = raw.trim()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim('"', '\'', '`')
                .trimEnd(',', ';', ')', ']', '}')

            // Remove JavaScript string wrappers sometimes found around URLs.
            value = value.replace("\\x2F", "/")
            if (value.startsWith("http://") || value.startsWith("https://") ||
                value.startsWith("//") || value.startsWith("/") || value.startsWith("../") ||
                value.startsWith("./")) {
                val fixed = absoluteUrl(value, baseUrl)
                if (isMediaUrl(fixed)) found.add(fixed)
            }
        }

        document.select(
            "video, video source, source, a[href], " +
                "[src], [data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-file-url], [data-video-url]"
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
            add(element.attr("href"))
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("&amp;", "&")

        // Catch direct URLs such as:
        // /uploads/videos/1788502957_Coyote_vs._Acme_2026.mp4
        // https://khulnaplex.com/uploads/videos/...mp4
        val directRegex = Regex(
            """(?i)(?:(?:https?:)?//|/|\.\.?/)[^"'<>\s\\]+?\.(?:m3u8|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?"""
        )
        directRegex.findAll(cleanedHtml).forEach { add(it.value) }

        // Catch JavaScript player objects even when the key is unusual.
        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']"""
        )
        keyRegex.findAll(cleanedHtml).forEach { add(it.groupValues[1]) }

        return found.toList()
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String,
        sectionName: String,
        page: Int
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()
        var order = page.toLong() * 1_000_000L

        // KhulnaPlex uses JavaScript cards such as:
        //   <div class="movie-card" onclick="openMovie(994)">
        //   <div class="movie-card" onclick="openSeries(99)">
        // Those IDs are converted to the real watch.php URL by
        // extractContentUrl(). Normal <a href> cards are also supported.
        val elements = document.select(
            ".movie-card, .movie-grid .movie-card, " +
                "a[href], [data-href], [data-url], [data-link], [onclick]"
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
            if (!looksLikeContentLink(absolute)) return@forEach

            val card = findCard(element)
            val title = cleanTitle(
                extractCardTitle(element, card)
                    .ifBlank { titleFromUrl(absolute) }
            )

            if (title.isBlank() || isNavigationTitle(title)) return@forEach

            val series = isSeriesUrl(absolute) ||
                sectionName.contains("TV", ignoreCase = true) ||
                absolute.contains("type=series", ignoreCase = true) ||
                absolute.contains("type=tv", ignoreCase = true)

            if (!result.containsKey(absolute)) {
                result[absolute] = SiteItem(
                    title = title,
                    url = absolute,
                    poster = extractPosterFromElement(card, sourceUrl),
                    isSeries = series,
                    sortTime = extractSortTime(card, element),
                    discoveryOrder = order++
                )
            }
        }

        val values = result.values.toList()

        // KhulnaPlex already orders its listing cards newest-first. We preserve
        // that order unless the site exposes a real upload/created timestamp.
        val hasRealTimestamp = values.any { it.sortTime > 0L }

        return if (hasRealTimestamp) {
            values.sortedWith(
                compareByDescending<SiteItem> { it.sortTime }
                    .thenBy { it.discoveryOrder }
            )
        } else {
            values.sortedBy { it.discoveryOrder }
        }
    }

    private fun extractSortTime(card: Element, anchor: Element): Long {
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
            card.selectFirst("time[datetime]")?.attr("datetime")
        ).filterNotNull().filter { it.isNotBlank() }

        for (value in values) {
            val clean = value.trim()

            /*
             * Ignore a plain four-digit year such as 2026. That is the
             * movie's release year, not its upload time.
             */
            if (Regex("""^\d{4}$""").matches(clean)) continue

            clean.toLongOrNull()?.let { number ->
                return if (number < 10_000_000_000L) {
                    number * 1000L
                } else {
                    number
                }
            }

            parseDate(clean)?.let { return it }
        }

        return 0L
    }

    private fun parseDate(value: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd-MM-yyyy",
            "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy",
            "MMM dd, yyyy HH:mm:ss", "MMM dd, yyyy HH:mm", "MMM dd, yyyy"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val result = linkedMapOf<String, Episode>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            if (!looksLikeEpisodeLink(href)) return@forEach
            val absolute = absoluteUrl(href, baseUrl)
            if (absolute == baseUrl) return@forEach
            val title = cleanTitle(anchor.text().ifBlank { "Episode" })
            result[absolute] = newEpisode(absolute) {
                name = title
                season = 1
                episode = episodeNumber(anchor, title, absolute)
            }
        }

        if (result.isEmpty()) {
            val direct = extractMediaUrls(document, document.html(), baseUrl).firstOrNull()
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
        repeat(8) {
            val element = current ?: return@repeat
            val cls = element.className().lowercase(Locale.ROOT)
            if (element.select("img").isNotEmpty() ||
                cls.contains("card") || cls.contains("movie") ||
                cls.contains("item") || cls.contains("poster")) return element
            current = element.parent()
        }
        return anchor
    }

    private fun extractCardTitle(anchor: Element, card: Element): String {
        val selectors = listOf(
            ".title", ".movie-title", ".movie_name", ".name",
            "h1", "h2", "h3", "h4", "strong"
        )
        for (selector in selectors) {
            val text = card.selectFirst(selector)?.text()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }
        anchor.attr("aria-label").trim().takeIf { it.isNotBlank() }?.let { return it }
        card.selectFirst("img")?.attr("alt")?.trim().takeIf { !it.isNullOrBlank() }?.let { return it }
        return anchor.text().trim()
    }

    private fun extractPoster(document: Document, pageUrl: String): String? =
        extractPosterFromElement(document, pageUrl)

    private fun extractPosterFromElement(element: Element, pageUrl: String): String? {
        val videoPoster = element.select("video[poster], video[data-poster]")
            .asSequence()
            .mapNotNull { it.attr("poster").ifBlank { it.attr("data-poster") }.takeIf(String::isNotBlank) }
            .map { absoluteUrl(it, pageUrl) }
            .firstOrNull()
        if (!videoPoster.isNullOrBlank()) return videoPoster

        val meta = element.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.trim()
        if (!meta.isNullOrBlank()) return absoluteUrl(meta, pageUrl)

        val images = element.select("img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-poster]")
        val preferred = images.firstOrNull { image ->
            val all = "${imageSource(image)} ${image.attr("alt")} ${image.className()}".lowercase(Locale.ROOT)
            all.contains("poster") || all.contains("cover") || all.contains("thumb") || all.contains("movie")
        } ?: images.firstOrNull()
        if (preferred != null) imageSource(preferred)?.let { return absoluteUrl(it, pageUrl) }

        val style = element.select("[style*=background]")
            .map { it.attr("style") }
            .firstOrNull { it.contains("url(", true) }
        if (!style.isNullOrBlank()) {
            Regex("(?i)url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)").find(style)?.groupValues?.getOrNull(1)?.let {
                return absoluteUrl(it, pageUrl)
            }
        }
        return null
    }

    private fun imageSource(image: Element): String? = sequenceOf(
        image.attr("data-poster"), image.attr("data-src"), image.attr("data-lazy-src"),
        image.attr("data-original"), image.attr("src")
    ).firstOrNull { it.isNotBlank() }

    private fun looksLikeContentLink(href: String): Boolean {
        val lower = href.lowercase(Locale.ROOT)
        if (lower.isBlank() || lower.startsWith("#") || lower.startsWith("javascript:")) return false
        return lower.contains("watch.php") || lower.contains("movie.php") ||
            lower.contains("series.php") || lower.contains("show.php") ||
            lower.contains("details.php") || lower.contains("movie?id=") ||
            lower.contains("type=movie") || lower.contains("type=series") || lower.contains("type=tv")
    }

    private fun looksLikeEpisodeLink(href: String): Boolean {
        val lower = href.lowercase(Locale.ROOT)
        return looksLikeContentLink(href) && (
            lower.contains("episode") || lower.contains("ep=") ||
                lower.contains("episode=") || lower.contains("season=") || lower.contains("type=episode")
            )
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean {
        return document.select("a[href]").any { anchor ->
            val text = anchor.text().trim().lowercase(Locale.ROOT)
            val rel = anchor.attr("rel").lowercase(Locale.ROOT)
            text == "next" || text.contains("next") || rel == "next" ||
                anchor.attr("aria-label").contains("next", true) ||
                anchor.attr("href").contains("page=${currentPage + 1}")
        }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
    }

    private fun isSeriesUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("series.php") || lower.contains("show.php") ||
            lower.contains("type=series") || lower.contains("type=tv")
    }

    private fun isAnimeUrl(url: String): Boolean = url.contains("category=animation", true)

    private fun looksLikeSeriesPage(document: Document): Boolean {
        val text = document.text().lowercase(Locale.ROOT)
        return text.contains("season") && text.contains("episode")
    }

    private fun isMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }
            .getOrElse { url.lowercase(Locale.ROOT) }
        return mediaExtensions.any { path.endsWith(it) }
    }

    private fun mediaPriority(url: String): Int {
        val path = runCatching { URI(url).path.lowercase(Locale.ROOT) }
            .getOrElse { url.lowercase(Locale.ROOT) }
        return when {
            path.endsWith(".m3u8") -> 0
            path.endsWith(".mpd") -> 1
            path.endsWith(".mp4") -> 2
            path.endsWith(".mkv") -> 3
            path.endsWith(".webm") -> 4
            path.endsWith(".mov") -> 5
            path.endsWith(".m4v") -> 6
            path.endsWith(".flv") -> 7
            path.endsWith(".ts") -> 8
            path.endsWith(".avi") -> 9
            else -> 99
        }
    }

    private suspend fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        label: String = "Direct"
    ) {
        val type = when {
            mediaUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val lower = mediaUrl.lowercase(Locale.ROOT)
        val quality = when {
            "2160" in lower || "4k" in lower -> Qualities.P2160.value
            "1440" in lower -> Qualities.P1440.value
            "1080" in lower -> Qualities.P1080.value
            "720" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
        callback(newExtractorLink(name, label, mediaUrl, type) {
            this.referer = referer
            this.quality = quality
            this.headers = mediaHeaders(referer)
        })
    }

    private fun extractContentUrl(raw: String): String? {
        if (raw.isBlank()) return null

        val cleaned = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()

        // This is the important KhulnaPlex mapping. The website's cards do not
        // contain watch.php hrefs; they call these JavaScript functions instead.
        Regex("""(?i)openMovie\s*\(\s*(\d+)\s*\)""")
            .find(cleaned)?.groupValues?.getOrNull(1)?.let { id ->
                return "/watch.php?id=$id&type=movie"
            }

        Regex("""(?i)openSeries\s*\(\s*(\d+)\s*\)""")
            .find(cleaned)?.groupValues?.getOrNull(1)?.let { id ->
                return "/watch.php?id=$id&type=series&season=1&episode=1"
            }

        val patterns = listOf(
            Regex("""(?i)https?://[^"'<>\s)]+"""),
            Regex("""(?i)(?:/|\.\.?/)?watch\.php\?[^"'<>\s)]+"""),
            Regex("""(?i)(?:/|\.\.?/)?(?:movie|series|show|details)\.php\?[^"'<>\s)]+""")
        )

        for (pattern in patterns) {
            val match = pattern.find(cleaned)?.value ?: continue
            return match
                .trim(',', ';', ')', ']', '}', '"', '\'')
                .replace("&amp;", "&")
        }

        return null
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

    private fun extractPageTitle(document: Document): String {
        for (selector in listOf("h1", "h2", ".movie-title", ".movie_name", ".title", "meta[property=og:title]")) {
            val e = document.selectFirst(selector) ?: continue
            val text = if (e.tagName() == "meta") e.attr("content") else e.text()
            if (text.isNotBlank()) return cleanTitle(text)
        }
        return cleanTitle(document.title())
    }

    private fun titleFromUrl(url: String): String {
        return runCatching {
            URI(url).query?.split('&')?.firstOrNull { it.startsWith("id=") }?.substringAfter('=')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Khulna Plex"
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(?i)\\s*[-|•]+\\s*(watch|download|play)\\s*$"), "")
        .trim()

    private fun isNavigationTitle(value: String): Boolean = value.lowercase(Locale.ROOT) in setOf(
        "home", "movies", "tv shows", "tv series", "live tv", "search",
        "genres", "software", "request", "next", "previous"
    )

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
