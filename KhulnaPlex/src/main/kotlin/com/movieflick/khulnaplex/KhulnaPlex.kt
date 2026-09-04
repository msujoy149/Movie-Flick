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

        /*
         * The exact Khulna Plex MP4 URL has been verified to play in Android
         * Chrome. Therefore we deliberately pass the exact URL through to the
         * native CloudStream player. We do NOT invent an HTTPS duplicate and
         * we do NOT turn download.php into an automatic playback source.
         */
        if (isMediaUrl(data)) {
            emitMediaLink(
                mediaUrl = data,
                referer = mainUrl,
                callback = callback
            )
            return true
        }

        val pageUrl = data.trim()
        val response = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders(pageUrl)
            )
        }.getOrNull() ?: return false

        val document = response.document
        val html = response.text

        /*
         * Read the actual <source src> in the order supplied by the website.
         * Khulna Plex currently exposes its playable files here, e.g.
         * /uploads/videos/1788517057_Neru_2023.mp4.
         */
        val mediaSources = extractMediaUrls(document, html, pageUrl)

        /* Prefer the direct media source exactly as published by the page. */
        val directSource = mediaSources.firstOrNull {
            !it.contains(".m3u8", true) && !it.contains(".mpd", true)
        }

        if (!directSource.isNullOrBlank()) {
            emitMediaLink(
                mediaUrl = directSource,
                referer = pageUrl,
                callback = callback
            )
            return true
        }

        /* HLS/DASH fallback for titles that use a manifest instead of a file. */
        val manifest = mediaSources.firstOrNull {
            it.contains(".m3u8", true) || it.contains(".mpd", true)
        }

        if (!manifest.isNullOrBlank()) {
            emitMediaLink(
                mediaUrl = manifest,
                referer = pageUrl,
                callback = callback
            )
            return true
        }

        /* Embedded player fallback. */
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

        return false
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
            val value = cleanUrl(raw).replace("\\x2F", "/")
            if (value.startsWith("http://") || value.startsWith("https://") ||
                value.startsWith("//") || value.startsWith("/") ||
                value.startsWith("../") || value.startsWith("./")) {
                val fixed = absoluteUrl(value, baseUrl)
                if (isMediaUrl(fixed)) found.add(fixed)
            }
        }

        document.select(
            "video, video source, source, " +
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
        }

        val cleanedHtml = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003A", ":")
            .replace("&amp;", "&")

        val directRegex = Regex(
            """(?i)(?:(?:https?:)?//|/|\.\.?/)[^"'<>\s\\]+?\.(?:m3u8|mpd|mp4|mkv|webm|mov|m4v|avi|flv|ts)(?:\?[^"'<>\s\\]*)?"""
        )
        directRegex.findAll(cleanedHtml).forEach { add(it.value) }

        val keyRegex = Regex(
            """(?i)(?:file|src|source|url|video|videoUrl|media|mediaUrl|fileUrl|video_url|stream|streamUrl)\s*[:=]\s*["']([^"']+)["']"""
        )
        keyRegex.findAll(cleanedHtml).forEach { add(it.groupValues[1]) }

        return found.toList()
    }

    private fun emitMediaLink(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        label: String = "Khulna Plex Direct"
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
