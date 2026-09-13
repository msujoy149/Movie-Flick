package com.movieflick.hianime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
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

    private val pageHeaders: Map<String, String>
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

    private fun absoluteUrl(
        raw: String,
        base: String = mainUrl
    ): String {
        val value = raw
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\u0026", "&")
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

    private fun extractPoster(document: Document): String? =
        listOf(
            document.selectFirst("meta[property=og:image]")
                ?.attr("content"),
            posterFrom(
                document.selectFirst(
                    ".anisc-poster img, .film-poster img, img.film-poster-img"
                )
            )
        )
            .mapNotNull {
                it?.trim()?.takeIf(String::isNotBlank)
            }
            .firstOrNull()
            ?.let(::absoluteUrl)

    private fun inferType(
        url: String,
        element: Element? = null
    ): TvType {
        val path = runCatching {
            URI(url).path.orEmpty().lowercase(Locale.ROOT)
        }.getOrDefault("")

        val text = cleanText(
            element
                ?.selectFirst(".fd-infor, .film-stats, .fdi-item")
                ?.text()
        ).uppercase(Locale.ROOT)

        return when {
            path.contains("/movie") ||
                text.contains("MOVIE") ->
                TvType.Movie

            text.contains("TV") ||
                path.contains("/tv") ->
                TvType.TvSeries

            else ->
                TvType.Anime
        }
    }

    private fun cardSearchResponse(
        title: String,
        url: String,
        poster: String?,
        type: TvType
    ): SearchResponse {
        return newMovieSearchResponse(
            title = title,
            url = url,
            type = type,
            posterUrl = poster
        )
    }

    private fun parseCards(
        document: Document,
        forceType: TvType? = null
    ): List<SearchResponse> {
        val cards = document.select(
            ".flw-item, " +
                ".film_list-grid .flw-item, " +
                ".film_list-wrap .flw-item"
        )

        return cards.mapNotNull { card ->
            val link =
                card.selectFirst("a[href*='/watch/'], a.dynamic-name[href]")
                    ?: return@mapNotNull null

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

            val type = forceType ?: inferType(url, card)

            cardSearchResponse(
                title = title,
                url = url,
                poster = poster,
                type = type
            )
        }.distinctBy { it.url }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.select(
            "a.page-link[href], .pagination a[href], a[href].page-link"
        ).any {
            val text = cleanText(it.text())
            text.equals("Next", true) ||
                text == "›" ||
                text == "»"
        }
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

        val pageNumber = page.coerceAtLeast(1)

        val target = when {
            pageNumber == 1 ->
                base

            base.contains("?") ->
                "$base&page=$pageNumber"

            else ->
                "$base?page=$pageNumber"
        }

        val response = runCatching {
            app.get(
                target,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull()
            ?: return newHomePageResponse(
                request,
                emptyList(),
                false
            )

        /*
         * TV must contain series cards, not episode cards.
         * The supplied HiAnime TV page identifies entries as TV series
         * and their detail pages provide the episode list.
         */
        val forceTvType =
            if (base.trimEnd('/').equals("$mainUrl/tv", true)) {
                TvType.TvSeries
            } else {
                null
            }

        val items = parseCards(
            response.document,
            forceType = forceTvType
        )

        return newHomePageResponse(
            request,
            items,
            hasNextPage(response.document)
        )
    }

    /*
     * HiAnime's public search navigation uses /browse?keyword=...
     * Keep /search?keyword=... only as a compatibility fallback.
     */
    private suspend fun searchDocument(
        query: String,
        page: Int
    ): Document? {
        val encoded = URLEncoder.encode(
            query.trim(),
            StandardCharsets.UTF_8.toString()
        )

        val candidates = linkedSetOf(
            "$mainUrl/browse?keyword=$encoded" +
                if (page > 1) "&page=$page" else "",

            "$mainUrl/search?keyword=$encoded" +
                if (page > 1) "&page=$page" else ""
        )

        for (url in candidates) {
            val document = runCatching {
                app.get(
                    url,
                    headers = pageHeaders + mapOf(
                        "Referer" to "$mainUrl/"
                    )
                ).document
            }.getOrNull()

            if (document != null) {
                val cards = parseCards(document)
                if (cards.isNotEmpty()) {
                    return document
                }
            }
        }

        return null
    }

    private fun searchScore(
        query: String,
        title: String
    ): Int {
        val q = query
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\p{L}]+"), "")
        val t = title
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\p{L}]+"), "")

        if (q.isBlank() || t.isBlank()) return 0
        if (q == t) return 1000
        if (t.startsWith(q)) return 900
        if (t.contains(q)) return 800

        val qTokens = query
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        val tLower = title.lowercase(Locale.ROOT)

        return qTokens.count {
            tLower.contains(it)
        } * 100
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = cleanText(query)

        if (q.isBlank()) {
            return newSearchResponseList(
                emptyList(),
                false
            )
        }

        val document = searchDocument(
            query = q,
            page = page.coerceAtLeast(1)
        ) ?: return newSearchResponseList(
            emptyList(),
            false
        )

        val results = parseCards(document)
            .sortedByDescending {
                searchScore(q, it.name)
            }

        return newSearchResponseList(
            results,
            hasNextPage(document)
        )
    }

    private fun animeIdFromDocument(
        document: Document
    ): String? {
        document
            .selectFirst("meta[name=hi-anime-id]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        document
            .selectFirst("#ani_detail")
            ?.attr("data-anime-id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    private fun episodeIdFromUrl(
        url: String
    ): String? {
        return runCatching {
            URI(url).rawQuery
                ?.split('&')
                ?.firstOrNull {
                    it.substringBefore('=')
                        .equals("ep", true)
                }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun parseEpisodeItems(
        html: String
    ): List<Episode> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(
            html,
            "$mainUrl/"
        )

        return document
            .select(
                "a.ssl-item[href], " +
                    "a.ep-item[href], " +
                    "#episodes-content a[href]"
            )
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                val id = link.attr("data-id").trim()

                if (href.isBlank() || id.isBlank()) {
                    return@mapNotNull null
                }

                val title =
                    cleanText(
                        link.attr("title").ifBlank {
                            link.selectFirst(
                                ".ep-name, .e-dynamic-name"
                            )?.text()
                        }.ifBlank {
                            link.text()
                        }
                    )

                if (title.isBlank()) return@mapNotNull null

                val number =
                    link.attr("data-number")
                        .toIntOrNull()
                        ?: Regex(
                            """(?:episode|ep\.?)\s*(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(title)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                newEpisode(
                    absoluteUrl(href)
                ) {
                    name = title
                    episode = number
                    season = 1

                    /*
                     * Store the real episode id together with the watch URL.
                     * This lets loadLinks() call the site's server API fresh
                     * on every Play action.
                     */
                    data = "${
                        absoluteUrl(href)
                    }||$id"
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

    private suspend fun getEpisodesFromApi(
        animeId: String
    ): List<Episode> {
        val url = "$mainUrl/ajax/v2/episode/list/$animeId"

        val response = runCatching {
            app.get(
                url,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return emptyList()

        val json = runCatching {
            JSONObject(response.text)
        }.getOrNull() ?: return emptyList()

        val html = json.optString("html")

        return parseEpisodeItems(html)
    }

    private fun episodeNumberFromWatchUrl(
        url: String
    ): Int? {
        return runCatching {
            URI(url).rawQuery
                ?.split('&')
                ?.firstOrNull {
                    it.substringBefore('=')
                        .equals("ep", true)
                }
                ?.substringAfter('=')
                ?.toIntOrNull()
        }.getOrNull()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        /*
         * An episode data URL is "watchUrl||episodeId".
         */
        val originalUrl = url.substringBefore("||").trim()
        val pageUrl = absoluteUrl(originalUrl)

        val response = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return null

        val document = response.document

        val canonical =
            document
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
                document.selectFirst(
                    "h2.film-name, .film-name, h1"
                )?.text()
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
                    ".film-description, " +
                        ".description-content, " +
                        ".description"
                )?.text()
            )
        }

        val year = extractYear(document)

        /*
         * The episode list is lazy-loaded on HiAnime's watch page, so the
         * important source is the anime ID from the page itself. The supplied
         * page exposes it as hi-anime-id / data-anime-id.
         */
        val animeId = animeIdFromDocument(document)

        if (animeId != null) {
            val episodes = getEpisodesFromApi(animeId)

            if (episodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(
                    name = title,
                    url = canonical,
                    type = TvType.TvSeries,
                    episodes = episodes
                ) {
                    posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
            }
        }

        val pageType =
            if (
                document.selectFirst(
                    ".film-stats .fdi-item"
                )?.text()
                    ?.contains("MOVIE", true) == true
            ) {
                TvType.Movie
            } else {
                inferType(
                    canonical,
                    null
                )
            }

        return newMovieLoadResponse(
            name = title,
            url = canonical,
            type = pageType,
            dataUrl = canonical
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    private fun extractYear(
        document: Document
    ): Int? {
        val text = buildString {
            append(' ')
            append(
                document.selectFirst(".anisc-detail")
                    ?.text()
                    .orEmpty()
            )
            append(' ')
            append(
                document.selectFirst(".film-stats")
                    ?.text()
                    .orEmpty()
            )
            append(' ')
            append(
                document
                    .selectFirst(
                        "meta[property=og:description]"
                    )
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

    private data class ServerInfo(
        val id: String,
        val name: String,
        val type: String
    )

    private fun parseServers(
        html: String
    ): List<ServerInfo> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(
            html,
            "$mainUrl/"
        )

        return document
            .select(
                ".server-item[data-id], " +
                    "div.server-item[data-id]"
            )
            .mapNotNull { item ->
                val id = item.attr("data-id").trim()
                if (id.isBlank()) return@mapNotNull null

                val name = cleanText(item.text())
                    .ifBlank { "Server $id" }

                val type =
                    item.attr("data-type")
                        .ifBlank {
                            item.parents()
                                .firstOrNull {
                                    cleanText(it.attr("data-type"))
                                        .isNotBlank()
                                }
                                ?.attr("data-type")
                                .orEmpty()
                        }
                        .ifBlank {
                            val parentText =
                                cleanText(
                                    item.parent()?.text()
                                ).lowercase(Locale.ROOT)

                            if (
                                parentText.contains("dub")
                            ) {
                                "dub"
                            } else {
                                "sub"
                            }
                        }

                ServerInfo(
                    id = id,
                    name = name,
                    type = type
                )
            }
            .distinctBy { "${it.type}:${it.id}" }
    }

    private suspend fun getEpisodeServers(
        episodeId: String
    ): List<ServerInfo> {
        val url =
            "$mainUrl/ajax/v2/episode/servers" +
                "?episodeId=$episodeId"

        val response = runCatching {
            app.get(
                url,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return emptyList()

        val json = runCatching {
            JSONObject(response.text)
        }.getOrNull() ?: return emptyList()

        return parseServers(
            json.optString("html")
        )
    }

    private suspend fun getEpisodeSourceLink(
        serverId: String
    ): String? {
        val url =
            "$mainUrl/ajax/v2/episode/sources?id=$serverId"

        val response = runCatching {
            app.get(
                url,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return null

        val json = runCatching {
            JSONObject(response.text)
        }.getOrNull() ?: return null

        /*
         * HiAnime-style source endpoint returns an embed link in "link".
         * A direct file is also accepted when a deployment provides one.
         */
        return json.optString("link")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun qualityFromUrl(
        url: String
    ): Int {
        val value = url.lowercase(Locale.ROOT)

        return when {
            "2160" in value || "4k" in value ->
                Qualities.P2160.value

            "1440" in value ->
                Qualities.P1440.value

            "1080" in value ->
                Qualities.P1080.value

            "720" in value ->
                Qualities.P720.value

            "480" in value ->
                Qualities.P480.value

            "360" in value ->
                Qualities.P360.value

            else ->
                Qualities.Unknown.value
        }
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type =
            when {
                url.contains(".m3u8", true) ->
                    ExtractorLinkType.M3U8

                url.contains(".mpd", true) ->
                    ExtractorLinkType.DASH

                else ->
                    ExtractorLinkType.VIDEO
            }

        callback(
            newExtractorLink(
                source = name,
                name = "Hi Anime Direct",
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = qualityFromUrl(url)
            }
        )
    }

    private fun directMediaUrls(
        document: Document,
        html: String,
        baseUrl: String
    ): List<String> {
        val found = linkedSetOf<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return

            val resolved =
                absoluteUrl(
                    raw
                        .trim()
                        .replace("\\/", "/")
                        .replace("\\u002F", "/")
                        .replace("\\u002f", "/")
                        .replace("\\u0026", "&")
                        .replace("&amp;", "&")
                        .trim(
                            '"',
                            '\'',
                            '`',
                            ',',
                            ';',
                            ')',
                            ']',
                            '}'
                        ),
                    baseUrl
                )

            val path =
                resolved.substringBefore('?')
                    .lowercase(Locale.ROOT)

            if (
                path.endsWith(".m3u8") ||
                path.endsWith(".mp4") ||
                path.endsWith(".mpd") ||
                path.endsWith(".webm") ||
                path.endsWith(".m4v") ||
                path.endsWith(".mov") ||
                path.endsWith(".mkv")
            ) {
                found.add(resolved)
            }
        }

        document.select(
            "video[src], video source[src], source[src], " +
                "[data-src], [data-file], [data-video], [data-source]"
        ).forEach { element ->
            add(element.attr("src"))
            add(element.attr("data-src"))
            add(element.attr("data-file"))
            add(element.attr("data-video"))
            add(element.attr("data-source"))
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
                """(?is)(?:src|file|url|video|videoUrl|stream|streamUrl|playlist|manifest)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4|mpd|webm|m4v|mov|mkv)(?:\?[^"']*)?)["']"""
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

    private fun episodeIdFromData(
        data: String
    ): Pair<String, String?> {
        val separator = data.indexOf("||")

        if (separator < 0) {
            return data.trim() to episodeIdFromUrl(data)
        }

        return data.substring(0, separator).trim() to
            data.substring(separator + 2)
                .trim()
                .takeIf { it.isNotBlank() }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        /*
         * IMPORTANT:
         * No media URL or token is cached here.
         *
         * Every Play action reaches the Hi Anime server API again:
         * episode -> fresh server list -> fresh source link -> extractor.
         *
         * That makes signed/temporary playback URLs refresh naturally.
         */
        val (pageUrl, storedEpisodeId) =
            episodeIdFromData(data)

        if (pageUrl.isBlank()) return false

        /*
         * Direct media fallback.
         */
        val path = pageUrl
            .substringBefore('?')
            .lowercase(Locale.ROOT)

        if (
            path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".mpd") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mov") ||
            path.endsWith(".mkv")
        ) {
            emitDirect(
                pageUrl,
                "$mainUrl/",
                callback
            )
            return true
        }

        val episodeId =
            storedEpisodeId
                ?: episodeIdFromUrl(pageUrl)

        /*
         * Fetch the watch page first. This also gives us a fresh page
         * session/referer for the following API calls.
         */
        val watchResponse = runCatching {
            app.get(
                absoluteUrl(pageUrl),
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return false

        /*
         * If the page itself contains a direct media URL, use it before
         * touching any external embed.
         */
        val direct = directMediaUrls(
            document = watchResponse.document,
            html = watchResponse.text,
            baseUrl = pageUrl
        )

        if (direct.isNotEmpty()) {
            direct.forEach {
                emitDirect(
                    it,
                    pageUrl,
                    callback
                )
            }
            return true
        }

        val realEpisodeId =
            episodeId
                ?: return false

        /*
         * Fresh server list on every click.
         */
        val servers =
            getEpisodeServers(
                realEpisodeId
            )

        if (servers.isEmpty()) {
            return false
        }

        /*
         * Prefer the first subbed server, then try all remaining servers.
         * Dub servers remain available as fallbacks.
         */
        val orderedServers =
            servers.sortedWith(
                compareBy<ServerInfo> {
                    when {
                        it.type.equals("sub", true) -> 0
                        it.type.equals("dub", true) -> 1
                        else -> 2
                    }
                }.thenBy {
                    it.name
                }
            )

        var emitted = false

        for (server in orderedServers) {
            val sourceLink =
                getEpisodeSourceLink(
                    server.id
                ) ?: continue

            /*
             * The returned "link" is normally the player/embed URL.
             * Hand it to CloudStream's extractor framework rather than
             * attempting to reproduce or bypass the external player.
             *
             * Ads are not emitted as video links by this resolver.
             */
            runCatching {
                loadExtractor(
                    sourceLink,
                    pageUrl,
                    subtitleCallback,
                    callback
                )
                emitted = true
            }

            if (emitted) {
                break
            }
        }

        return emitted
    }
}
