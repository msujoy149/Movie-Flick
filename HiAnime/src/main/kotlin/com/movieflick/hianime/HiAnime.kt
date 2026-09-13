package com.movieflick.hianime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class HiAnime : MainAPI() {

    override var mainUrl = "https://hianime.at"
    override var name = "Hi Anime"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/recently-updated" to "Latest Episode",
        "$mainUrl/dubbed-anime" to "Dubbed Anime",
        "$mainUrl/movie" to "Anime",
        "$mainUrl/tv" to "TV Show"
    )

    private val browserHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

    private fun absoluteUrl(
        raw: String,
        base: String = mainUrl
    ): String {
        val value = raw
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (value.isBlank()) return value

        return when {
            value.startsWith("http://", true) ||
                value.startsWith("https://", true) -> value

            value.startsWith("//") -> "https:$value"

            value.startsWith("/") ->
                base.trimEnd('/') + value

            else ->
                base.trimEnd('/') + "/" + value
        }
    }

    private fun cleanText(value: String?): String =
        value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private fun cleanTitle(value: String?): String =
        cleanText(value)
            .replace(
                Regex("""\s*\|\s*HiAnime\s*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(
                Regex("""\s*[-–—]\s*HiAnime\s*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()

    private fun posterFrom(element: Element?): String? {
        if (element == null) return null

        return listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-original"),
            element.attr("data-lazy-src")
        )
            .firstOrNull { it.isNotBlank() }
            ?.let(::absoluteUrl)
    }

    private fun extractPoster(document: Document): String? {
        return listOf(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content"),
            posterFrom(
                document.selectFirst(
                    ".anisc-poster img, .film-poster img, img.film-poster-img"
                )
            )
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.let(::absoluteUrl)
    }

    private fun inferType(
        url: String,
        element: Element? = null
    ): TvType {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault("")

        val text = cleanText(
            element
                ?.selectFirst(".film-stats, .fd-infor, .fdi-item")
                ?.text()
        ).uppercase(Locale.ROOT)

        return when {
            path.contains("/movie") || text.contains("MOVIE") ->
                TvType.Movie

            path.contains("/tv") || text.contains("TV") ->
                TvType.TvSeries

            else ->
                TvType.Anime
        }
    }

    private fun extractYear(document: Document): Int? {
        val text = buildString {
            append(' ')
            append(document.selectFirst(".anisc-detail")?.text().orEmpty())
            append(' ')
            append(document.selectFirst(".film-stats")?.text().orEmpty())
            append(' ')
            append(
                document
                    .selectFirst("meta[property=og:description]")
                    ?.attr("content")
                    .orEmpty()
            )
        }

        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val cards = document.select(
            ".flw-item, .film_list-grid .flw-item, .film_list-wrap .flw-item"
        )

        return cards.mapNotNull { card ->
            val link = card.selectFirst(
                "a[href*='/watch/']"
            ) ?: return@mapNotNull null

            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val url = absoluteUrl(href)

            val title = cleanTitle(
                link.attr("title").ifBlank {
                    link.text()
                }
            )

            if (title.isBlank()) return@mapNotNull null

            val poster = posterFrom(
                card.selectFirst(
                    "img.film-poster-img, .film-poster img, img"
                )
            )

            when (inferType(url, card)) {
                TvType.Movie -> newMovieSearchResponse(
                    title,
                    url,
                    TvType.Movie
                ) {
                    posterUrl = poster
                }

                TvType.TvSeries -> newTvSeriesSearchResponse(
                    title,
                    url,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }

                else -> newAnimeSearchResponse(
                    title,
                    url
                ) {
                    posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trim()
        if (base.isBlank()) {
            return newHomePageResponse(
                request,
                emptyList(),
                false
            )
        }

        val target = if (page <= 1) {
            base
        } else {
            "$base?page=${page.coerceAtLeast(1)}"
        }

        val response = runCatching {
            app.get(
                target,
                headers = browserHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull()
            ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        val items = parseCards(response.document)

        val hasNext = response.document
            .select("a.page-link[href], .pagination a[href]")
            .any {
                val text = cleanText(it.text())
                text.equals("Next", true) ||
                    text == "›" ||
                    text == "»"
            }

        return newHomePageResponse(
            request,
            items,
            hasNext
        )
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

        val encoded = URLEncoder.encode(
            q,
            StandardCharsets.UTF_8.toString()
        )

        val target =
            "$mainUrl/search?keyword=$encoded" +
                if (page > 1) "&page=$page" else ""

        val response = runCatching {
            app.get(
                target,
                headers = browserHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull()
            ?: return newSearchResponseList(
                emptyList(),
                false
            )

        val items = parseCards(response.document)

        val hasNext = response.document
            .select("a.page-link[href], .pagination a[href]")
            .any {
                val text = cleanText(it.text())
                text.equals("Next", true) ||
                    text == "›" ||
                    text == "»"
            }

        return newSearchResponseList(
            items,
            hasNext
        )
    }

    private fun extractEpisodeNumber(
        link: Element
    ): Int? {
        link.attr("data-number")
            .toIntOrNull()
            ?.let { return it }

        val text = cleanText(
            link.attr("title").ifBlank {
                link.text()
            }
        )

        return Regex(
            """(?:episode|ep\.?)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseEpisodes(
        document: Document
    ): List<Episode> {
        val links = document.select(
            "#episodes-content a[href]",
            ".ss-list a[href]",
            ".ep-item[href]",
            "a[href*='/watch/'][data-number]"
        )

        if (links.isEmpty()) return emptyList()

        return links.mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val url = absoluteUrl(href)

            val title = cleanText(
                link.attr("title").ifBlank {
                    link.text()
                }
            )

            if (title.isBlank()) return@mapNotNull null

            newEpisode(url) {
                name = title
                episode = extractEpisodeNumber(link)
                season = 1
                data = url
            }
        }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: 1
                }.thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
            )
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absoluteUrl(url)

        val response = runCatching {
            app.get(
                pageUrl,
                headers = browserHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return null

        val document = response.document

        val canonical = document
            .selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)
            ?: pageUrl

        val title = cleanTitle(
            document
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
        ).ifBlank {
            cleanTitle(
                document.selectFirst(".film-name")?.text()
            )
        }.ifBlank {
            cleanTitle(
                document.selectFirst("h1")?.text()
            )
        }.ifBlank {
            cleanTitle(document.title())
        }

        if (title.isBlank()) return null

        val poster = extractPoster(document)

        val plot = cleanText(
            document
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
        ).ifBlank {
            cleanText(
                document.selectFirst(
                    ".film-description, .description-content, .description"
                )?.text()
            )
        }

        val year = extractYear(document)
        val type = inferType(canonical)
        val episodes = parseEpisodes(document)

        if (
            type == TvType.Movie &&
            episodes.isEmpty()
        ) {
            return newMovieLoadResponse(
                title,
                canonical,
                TvType.Movie,
                canonical
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(
                title,
                canonical,
                if (type == TvType.TvSeries) {
                    TvType.TvSeries
                } else {
                    TvType.Anime
                },
                episodes
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(
            title,
            canonical,
            type,
            canonical
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun isMediaUrl(url: String): Boolean {
        val path = url
            .substringBefore('?')
            .lowercase(Locale.ROOT)

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".mpd") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mov") ||
            path.endsWith(".mkv")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)

        return when {
            "2160" in lower || "4k" in lower ->
                Qualities.P2160.value

            "1440" in lower ->
                Qualities.P1440.value

            "1080" in lower ->
                Qualities.P1080.value

            "720" in lower ->
                Qualities.P720.value

            "576" in lower ->
                Qualities.P576.value

            "480" in lower ->
                Qualities.P480.value

            "360" in lower ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private fun mediaType(url: String): ExtractorLinkType =
        when {
            url.contains(".m3u8", true) ->
                ExtractorLinkType.M3U8

            url.contains(".mpd", true) ->
                ExtractorLinkType.DASH

            else ->
                ExtractorLinkType.VIDEO
        }

    private suspend fun emitMediaLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "Hi Anime Direct",
                url = url,
                type = mediaType(url)
            ) {
                this.referer = referer
                this.quality = qualityFromUrl(url)
            }
        )
    }

    private fun extractDirectMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val value = raw
                .trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim('"', '\'', '`', ',', ';', ')', ']', '}')

            if (value.isBlank()) return

            val resolved = absoluteUrl(
                value,
                baseUrl
            )

            if (isMediaUrl(resolved)) {
                found.add(resolved)
            }
        }

        document.select(
            "video[src], video source[src], source[src], " +
                "[data-video], [data-source], [data-src], [data-file]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-video"))
            add(element.attr("data-source"))
            add(element.attr("data-src"))
            add(element.attr("data-file"))
        }

        val normalized = html
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        listOf(
            Regex(
                """(?is)(?:https?:)?//[^"'<>\s\\]+?\.(?:m3u8|mp4|mpd|webm|m4v|mov|mkv)(?:\?[^"'<>\s\\]*)?"""
            ),
            Regex(
                """(?is)(?:src|source|file|url|video|videoUrl|stream|streamUrl|playlist|manifest)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4|mpd|webm|m4v|mov|mkv)(?:\?[^"']*)?)["']"""
            )
        ).forEach { pattern ->
            pattern.findAll(normalized).forEach { match ->
                add(
                    match.groupValues
                        .getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                        ?: match.value
                )
            }
        }

        return found.toList()
    }

    private fun extractEmbeds(
        document: Document,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        document.select(
            "iframe[src], iframe[data-src], " +
                "[data-embed], [data-embed-url], " +
                "[data-video], [data-source]"
        ).forEach { element ->
            listOf(
                element.attr("src"),
                element.attr("data-src"),
                element.attr("data-embed"),
                element.attr("data-embed-url"),
                element.attr("data-video"),
                element.attr("data-source")
            ).forEach { raw ->
                if (raw.isNotBlank()) {
                    found.add(
                        absoluteUrl(
                            raw,
                            baseUrl
                        )
                    )
                }
            }
        }

        return found.toList()
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
            emitMediaLink(
                input,
                "$mainUrl/",
                callback
            )
            return true
        }

        val response = runCatching {
            app.get(
                absoluteUrl(input),
                headers = browserHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return false

        /*
         * The supplied Hi Anime pages show the player shell and empty
         * #servers-content/#episodes-content containers. The page also
         * exposes /api/theme/ as its AJAX base and loads watch.min.js after
         * the HTML response. The exact server action/JSON response is not
         * contained in the supplied page source, so this resolver only emits
         * media/embed URLs that are actually present in the response.
         */

        val direct = extractDirectMediaUrls(
            document = response.document,
            html = response.text,
            baseUrl = input
        )

        var emitted = false

        for (media in direct) {
            emitMediaLink(
                media,
                input,
                callback
            )
            emitted = true
        }

        if (emitted) return true

        val embeds = extractEmbeds(
            response.document,
            input
        )

        for (embed in embeds) {
            runCatching {
                loadExtractor(
                    embed,
                    input,
                    subtitleCallback,
                    callback
                )
                emitted = true
            }
        }

        return emitted
    }
}
