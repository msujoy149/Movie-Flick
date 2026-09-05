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

    private val mediaExtensions = setOf(
        ".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".flv", ".ts"
    )

    private val pageHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = getDocument(url)
            ?: return newHomePageResponse(request, emptyList(), false)

        val items = parseItems(document, url, request.data)
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
        val clean = query.trim()
        if (clean.isBlank()) return newSearchResponseList(emptyList(), false)

        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.toString())
        val candidates = listOf(
            "$mainUrl/search?q=$encoded${pageSuffix(page)}",
            "$mainUrl/search?query=$encoded${pageSuffix(page)}",
            "$mainUrl/search?keyword=$encoded${pageSuffix(page)}",
            "$mainUrl/search/$encoded${pageSuffix(page)}"
        ).distinct()

        for (url in candidates) {
            val document = getDocument(url) ?: continue
            val items = parseItems(document, url, "search")
                .take(30)

            if (items.isNotEmpty()) {
                return newSearchResponseList(
                    items.map { it.toSearchResponse() },
                    hasNextPage(document, page)
                )
            }
        }

        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = cleanUrl(url)
        val type = typeFromUrl(cleanUrl)

        if (isMediaUrl(cleanUrl)) {
            return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                type,
                cleanUrl
            )
        }

        val document = getDocument(cleanUrl)
            ?: return newMovieLoadResponse(
                titleFromUrl(cleanUrl),
                cleanUrl,
                type,
                cleanUrl
            )

        val title = extractPageTitle(document)
            .ifBlank { titleFromUrl(cleanUrl) }
        val poster = extractPoster(document, cleanUrl)

        if (type == TvType.TvSeries) {
            val episodes = parseEpisodes(document, cleanUrl)
            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    title,
                    cleanUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                }
            }
        }

        return newMovieLoadResponse(
            title,
            cleanUrl,
            type,
            cleanUrl
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
        val input = data.trim()
        if (input.isBlank()) return false

        if (isMediaUrl(input)) {
            emitMediaLink(input, input, callback)
            return true
        }

        val response = runCatching {
            app.get(input, headers = pageHeaders + ("Referer" to "$mainUrl/"))
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        // 1) Real media embedded directly in the page.
        val direct = extractMediaUrls(document, html, input)
            .distinct()

        if (direct.isNotEmpty()) {
            direct.forEach { media ->
                emitMediaLink(media, input, callback)
            }
            return true
        }

        // 2) Common player/file attributes and download endpoints.
        val recovered = recoverDownloadMediaUrls(document, html, input)
            .distinct()

        if (recovered.isNotEmpty()) {
            recovered.forEach { media ->
                emitMediaLink(media, input, callback)
            }
            return true
        }

        // 3) Embedded players; let CloudStream's registered extractors do the heavy lifting.
        val iframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                val raw = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }
                    .trim()
                raw.takeIf { it.isNotBlank() }?.let { absoluteUrl(it, input) }
            }
            .distinct()

        for (iframe in iframes) {
            val extracted = runCatching {
                loadExtractor(iframe, input, subtitleCallback, callback)
            }.getOrDefault(false)
            if (extracted) return true
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
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        val lower = url.lowercase(Locale.ROOT)
        val quality = when {
            "4320" in lower || "8k" in lower -> 4320
            "2160" in lower || "4k" in lower -> Qualities.P2160.value
            "1440" in lower || "2k" in lower -> Qualities.P1440.value
            "1080" in lower -> Qualities.P1080.value
            "720" in lower -> Qualities.P720.value
            "480" in lower -> Qualities.P480.value
            "360" in lower -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }

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

        val candidates = linkedSetOf<String>()
        candidates.add(normalized)

        // HTTPS is the canonical site scheme; retain HTTP only as a compatibility fallback.
        if (normalized.startsWith("https://", true)) {
            candidates.add("http://" + normalized.removePrefix("https://"))
        } else if (normalized.startsWith("http://", true)) {
            candidates.add("https://" + normalized.removePrefix("http://"))
        }

        for (candidate in candidates) {
            val document = runCatching {
                app.get(candidate, headers = pageHeaders + ("Referer" to "$mainUrl/"))
            }.getOrNull()?.document

            if (document != null) return document
        }

        return null
    }

    private fun parseItems(
        document: Document,
        sourceUrl: String,
        section: String
    ): List<SiteItem> {
        val result = linkedMapOf<String, SiteItem>()

        val anchors = document.select(
            "a[href], [data-href], [data-url], [data-link], [onclick]"
        )

        anchors.forEach { anchor ->
            val raw = sequenceOf(
                anchor.attr("href"),
                anchor.attr("data-href"),
                anchor.attr("data-url"),
                anchor.attr("data-link"),
                anchor.attr("onclick")
            ).firstOrNull { it.isNotBlank() }

            val contentUrl = raw?.let { extractContentUrl(it) } ?: return@forEach
            val absolute = absoluteUrl(contentUrl, sourceUrl)
            val path = runCatching { URI(absolute).path.orEmpty() }.getOrDefault("")

            val type = when {
                path.startsWith("/tv/", true) -> TvType.TvSeries
                path.startsWith("/anime/", true) -> TvType.Anime
                path.startsWith("/movies/", true) -> TvType.Movie
                else -> return@forEach
            }

            if (isListingUrl(absolute)) return@forEach

            val card = findCard(anchor)
            val title = cleanTitle(
                extractCardTitle(anchor, card)
                    .ifBlank { titleFromUrl(absolute) }
            )

            if (title.isBlank() || isNavigationTitle(title)) return@forEach

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

    private fun parseEpisodes(
        document: Document,
        baseUrl: String
    ): List<Episode> {
        val result = linkedMapOf<String, Episode>()

        document.select("a[href], [data-href], [data-url]").forEach { element ->
            val raw = sequenceOf(
                element.attr("href"),
                element.attr("data-href"),
                element.attr("data-url")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach

            val content = extractContentUrl(raw) ?: return@forEach
            val absolute = absoluteUrl(content, baseUrl)
            val lower = absolute.lowercase(Locale.ROOT)
            val text = element.text().trim()

            val episodeLike =
                (lower.contains("/episode") ||
                    lower.contains("ep-") ||
                    lower.contains("ep=") ||
                    lower.contains("episode=") ||
                    lower.contains("season=") ||
                    text.contains(Regex("(?i)\\b(?:s\\d+\\s*e\\d+|episode\\s*\\d+|ep\\s*\\d+)\\b")))

            if (!episodeLike) return@forEach

            val episodeNumber = extractEpisodeNumber(text, absolute)
            result.putIfAbsent(
                absolute,
                newEpisode(absolute) {
                    name = if (text.isBlank()) "Episode ${episodeNumber ?: result.size + 1}" else cleanTitle(text)
                    season = extractSeasonNumber(text, absolute) ?: 1
                    episode = episodeNumber ?: (result.size + 1)
                }
            )
        }

        // Some sites place all playable episodes inside script/JSON instead of <a> tags.
        if (result.isEmpty()) {
            val html = document.html().replace("\\/", "/").replace("&amp;", "&")
            val regex = Regex(
                """(?i)(https?://[^\"'<>\\s]+|/(?:tv|episode)/[^\"'<>\\s]+)"""
            )
            regex.findAll(html).forEach { match ->
                val absolute = absoluteUrl(match.value, baseUrl)
                if (absolute.contains("/episode", true) || absolute.contains("/tv/", true)) {
                    val episode = extractEpisodeNumber("", absolute)
                    result.putIfAbsent(
                        absolute,
                        newEpisode(absolute) {
                            name = "Episode ${episode ?: result.size + 1}"
                            season = extractSeasonNumber("", absolute) ?: 1
                            this.episode = episode ?: (result.size + 1)
                        }
                    )
                }
            }
        }

        // A TV detail page with one direct media URL is still playable as Episode 1.
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

        return result.values.sortedWith(
            compareBy<Episode> { it.season ?: 1 }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

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
                .replace("\\u003A", ":")
                .replace("&amp;", "&")
                .trim('"', '\'', '`')
                .trimEnd(',', ';', ')', ']', '}')

            if (value.isBlank() || value.startsWith("data:", true) || value.startsWith("javascript:", true)) return

            val fixed = absoluteUrl(value, baseUrl)
            if (isMediaUrl(fixed)) found.add(fixed)
        }

        document.select(
            "video[src], video[poster], video source[src], source[src], " +
                "[src], [data-src], [data-video], [data-file], [data-url], " +
                "[data-source], [data-stream], [data-file-url], [data-video-url], " +
                "[data-playlist], [data-manifest]"
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
            add(element.attr("data-playlist"))
            add(element.attr("data-manifest"))
            add(element.attr("href"))
        }

        val cleaned = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("&amp;", "&")

        val directRegex = Regex(
            """(?i)(?:https?://[^\"'<>\\s\\\\]+|(?:/|\\.\\.?/)?(?:uploads/|videos/|media/|files/|storage/)[^\"'<>\\s\\\\]+)\\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\\?[^\"'<>\\s\\\\]*)?"""
        )
        directRegex.findAll(cleaned).forEach { add(it.value) }

        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl|playlist|manifest)\\s*[:=]\\s*[\"']([^\"']+)[\"']"""
        )
        keyRegex.findAll(cleaned).forEach { add(it.groupValues[1]) }

        return found.toList()
    }

    private fun recoverDownloadMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun addDownload(raw: String?) {
            if (raw.isNullOrBlank()) return
            val absolute = absoluteUrl(cleanUrl(raw), baseUrl)
            if (!absolute.contains("download", true) && !absolute.contains("file=", true)) return

            val query = runCatching { URI(absolute).rawQuery.orEmpty() }.getOrDefault("")
            query.split('&').forEach { pair ->
                val key = pair.substringBefore('=', "")
                if (!key.equals("file", true) && !key.equals("url", true) && !key.equals("src", true)) return@forEach

                val encoded = pair.substringAfter('=', "")
                val decoded = runCatching {
                    URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
                }.getOrNull().orEmpty()

                val direct = absoluteUrl(decoded, baseUrl)
                if (isMediaUrl(direct)) found.add(direct)
            }
        }

        document.select("a[href], [data-href], [data-url], [data-file]").forEach {
            addDownload(it.attr("href"))
            addDownload(it.attr("data-href"))
            addDownload(it.attr("data-url"))
            addDownload(it.attr("data-file"))
        }

        val regex = Regex(
            """(?i)(?:https?://[^\"'<>\\s]+|(?:/|\\.\\.?/)?[^\"'<>\\s]+)?(?:download|stream)[^\"'<>\\s)]*"""
        )
        regex.findAll(html).forEach { addDownload(it.value) }

        return found.toList()
    }

    private fun findCard(anchor: Element): Element {
        var current: Element? = anchor
        repeat(8) {
            val element = current ?: return@repeat
            val cls = element.className().lowercase(Locale.ROOT)
            if (element.select("img").isNotEmpty() ||
                cls.contains("card") ||
                cls.contains("movie") ||
                cls.contains("poster") ||
                cls.contains("item")
            ) return element
            current = element.parent()
        }
        return anchor
    }

    private fun extractCardTitle(anchor: Element, card: Element): String {
        val selectors = listOf(
            ".title", ".movie-title", ".movie_name", ".name", ".card-title",
            ".item-title", "h1", "h2", "h3", "h4", "strong"
        )

        selectors.forEach { selector ->
            val text = card.selectFirst(selector)?.text()?.trim().orEmpty()
            if (text.isNotBlank()) return text
        }

        val aria = anchor.attr("aria-label").trim()
        if (aria.isNotBlank()) return aria

        val alt = card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        if (alt.isNotBlank()) return alt

        return anchor.text().trim()
    }

    private fun extractPoster(document: Document, pageUrl: String): String? =
        extractPosterFromElement(document, pageUrl)

    private fun extractPosterFromElement(element: Element, pageUrl: String): String? {
        element.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return absoluteUrl(it, pageUrl) }

        val image = element.select(
            "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-poster]"
        ).firstOrNull()

        if (image != null) {
            val src = sequenceOf(
                image.attr("data-poster"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
                image.attr("src")
            ).firstOrNull { it.isNotBlank() }
            if (!src.isNullOrBlank()) return absoluteUrl(src, pageUrl)
        }

        val style = element.select("[style*=background]")
            .map { it.attr("style") }
            .firstOrNull { it.contains("url(", true) }

        if (!style.isNullOrBlank()) {
            Regex("""(?i)url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)""")
                .find(style)?.groupValues?.getOrNull(1)
                ?.let { return absoluteUrl(it, pageUrl) }
        }

        return null
    }

    private fun typeFromUrl(url: String): TvType {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)
        return when {
            path.startsWith("/tv/") -> TvType.TvSeries
            path.startsWith("/anime/") -> TvType.Anime
            else -> TvType.Movie
        }
    }

    private fun isListingUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower == "$mainUrl/movies" ||
            lower == "$mainUrl/tv" ||
            lower == "$mainUrl/anime" ||
            lower.contains("/movies?sort=") ||
            lower.contains("/tv?sort=") ||
            lower.contains("/anime?sort=")
    }

    private fun extractContentUrl(value: String): String? {
        val cleaned = cleanUrl(value)
        if (cleaned.isBlank()) return null

        if (cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true)) {
            return cleaned
        }

        val patterns = listOf(
            Regex("""(?i)(?:openMovie|openTv|openSeries|openAnime|watch|play)\\s*\\(\\s*['\"]([^'\"]+)['\"]"""),
            Regex("""(?i)(?:url|href|link|src)\\s*[:=]\\s*['\"]([^'\"]+)['\"]""")
        )

        patterns.forEach { regex ->
            regex.find(cleaned)?.groupValues?.getOrNull(1)?.let { return it }
        }

        if (cleaned.startsWith("/")) return cleaned
        if (cleaned.startsWith("./")) return cleaned

        return null
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        return document.select("a[href]").any { anchor ->
            val text = anchor.text().trim().lowercase(Locale.ROOT)
            val rel = anchor.attr("rel").lowercase(Locale.ROOT)
            val href = anchor.attr("href")
            text == "next" ||
                text.contains("next") ||
                rel == "next" ||
                anchor.attr("aria-label").contains("next", true) ||
                href.contains("page=${page + 1}") ||
                href.contains("p=${page + 1}")
        }
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return if (base.contains("?")) "$base&page=$page" else "$base?page=$page"
    }

    private fun pageSuffix(page: Int): String = if (page <= 1) "" else "&page=$page"

    private fun extractPageTitle(document: Document): String {
        return document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()
    }

    private fun titleFromUrl(url: String): String {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val last = path.trim('/').substringAfterLast('/').replace('-', ' ').replace('_', ' ')
        return last.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }.trim()
    }

    private fun extractEpisodeNumber(text: String, url: String): Int? {
        val hay = "$text $url"
        Regex("(?i)\\b(?:episode|ep|e)\\s*[-_.#]?\\s*(\\d+)\\b")
            .find(hay)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }
        Regex("(?i)[?&](?:episode|ep)=([0-9]+)").find(hay)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun extractSeasonNumber(text: String, url: String): Int? {
        val hay = "$text $url"
        Regex("(?i)\\bseason\\s*[-_.#]?\\s*(\\d+)\\b")
            .find(hay)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("(?i)\\bs(\\d{1,2})\\b").find(hay)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("(?i)[?&]season=([0-9]+)").find(hay)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun cleanTitle(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private fun isNavigationTitle(value: String): Boolean {
        return value.trim().lowercase(Locale.ROOT) in setOf(
            "home", "movies", "tv", "tv shows", "anime", "games", "all", "newest", "popular", "top rated", "next", "prev", "previous"
        )
    }

    private fun isMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return mediaExtensions.any { ext -> lower.substringBefore('?').substringBefore('#').endsWith(ext) }
    }

    private fun cleanUrl(url: String): String =
        url.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', '`')
            .trimEnd(',', ';', ')', ']', '}')

    private fun absoluteUrl(value: String, base: String): String {
        val raw = cleanUrl(value)
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        if (raw.startsWith("//")) {
            return "https:$raw"
        }
        return runCatching {
            URI(base).resolve(raw).toString()
        }.getOrDefault(raw)
    }

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
}
