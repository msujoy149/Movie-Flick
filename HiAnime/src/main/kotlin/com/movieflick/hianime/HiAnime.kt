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
            text.contains("MOVIE") ||
                path.contains("/movie") ->
                TvType.Movie

            text.contains("TV") ||
                path.contains("/tv") ||
                path.contains("/watch/") && (
                    element?.selectFirst(
                        ".fdi-item, .film-stats .item"
                    )?.text()
                        ?.contains("TV", true) == true
                    ) ->
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
                url
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
        html: String,
        seasonNumber: Int = 1
    ): List<Episode> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(
            html,
            "$mainUrl/"
        )

        val links = document.select(
            "a.ssl-item[data-id][href], " +
                "a.ep-item[data-id][href], " +
                "#episodes-content a[data-id][href]"
        )

        /*
         * HiAnime's episode-list response is authoritative: every returned
         * anchor has its own data-id and href. We never manufacture missing
         * episodes from a numeric count.
         */
        return links.mapNotNull { link ->
            val episodeId = link.attr("data-id").trim()
            val href = link.attr("href").trim()

            if (episodeId.isBlank() || href.isBlank()) {
                return@mapNotNull null
            }

            val number =
                link.attr("data-number").toIntOrNull()
                    ?: link.attr("data-episode-number").toIntOrNull()
                    ?: Regex(
                        """(?:episode|ep\.?)\s*(\d+)""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(
                            cleanText(
                                link.attr("title").ifBlank {
                                    link.text()
                                }
                            )
                        )
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

            val title = cleanText(
                link.selectFirst(
                    ".ep-name.e-dynamic-name, " +
                        ".ep-name, " +
                        ".e-dynamic-name"
                )?.text().orEmpty()
            ).ifBlank {
                cleanText(link.attr("title"))
            }.ifBlank {
                cleanText(link.text())
            }.ifBlank {
                number?.let { "Episode $it" } ?: "Episode"
            }

            newEpisode(
                absoluteUrl(href)
            ) {
                name = title
                episode = number
                season = seasonNumber

                // Save the exact site-provided episode ID.
                data = "${absoluteUrl(href)}||$episodeId"
            }
        }
            .distinctBy { it.data }
            .sortedWith(
                compareBy<Episode> {
                    it.season ?: seasonNumber
                }.thenBy {
                    it.episode ?: Int.MAX_VALUE
                }
            )
    }

    private suspend fun getEpisodesFromApi(
        animeId: String,
        seasonNumber: Int = 1,
        referer: String = "$mainUrl/"
    ): List<Episode> {

        val url = "$mainUrl/ajax/v2/episode/list/$animeId"

        val requestHeaders = pageHeaders + mapOf(
            "Referer" to referer,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val response = runCatching {
            app.get(
                url,
                headers = requestHeaders
            )
        }.getOrNull() ?: return emptyList()

        val raw = response.text.trim()
        if (raw.isBlank()) return emptyList()

        /*
         * Normal response:
         *   {"html":"<a class=\"ssl-item\" data-id=\"...\">...</a>"}
         *
         * Some deployments/API wrappers may return a different top-level
         * JSON object, so first try the normal JSON "html" field, then scan
         * the decoded object text for an HTML-looking fragment.
         */
        val html = runCatching {
            JSONObject(raw)
                .optString("html")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
            ?: runCatching {
                JSONObject(raw)
                    .keys()
                    .asSequence()
                    .mapNotNull { key ->
                        JSONObject(raw).optString(key)
                            .takeIf { value ->
                                value.contains("ssl-item") ||
                                    value.contains("data-id")
                            }
                    }
                    .firstOrNull()
            }.getOrNull()
            ?: ""

        if (html.isBlank()) return emptyList()

        return parseEpisodeItems(
            html = html,
            seasonNumber = seasonNumber
        )
    }

    private fun episodeCountFromDocument(
        document: Document
    ): Int? {
        val candidates = listOf(
            document.selectFirst(".film-stats .tick-eps")?.text(),
            document.selectFirst(".tick-eps")?.text(),
            document.selectFirst(".fd-infor .tick-eps")?.text()
        )

        return candidates
            .mapNotNull { cleanText(it).toIntOrNull() }
            .firstOrNull { it > 0 }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val pageUrl = absoluteUrl(
            url.substringBefore("||").trim()
        )

        val response = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders + mapOf(
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
                document.selectFirst(
                    ".film-name, h1"
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
         * HiAnime's actual watch-page source identifies TV episodes with:
         *
         *   #ani_detail[data-anime-id="240"][data-id="4402"]
         *   [data-episode-number="1"]
         *
         * and the page's film stats contain "TV".
         *
         * Therefore an episode URL such as:
         *   /watch/attack-on-titan-240?ep=4402
         * MUST resolve to the parent TV series, not a Movie response.
         */
        val aniDetail = document.selectFirst("#ani_detail")
        val animeId = animeIdFromDocument(document)
        val episodeNumber =
            aniDetail
                ?.attr("data-episode-number")
                ?.toIntOrNull()
                ?: episodeIdFromUrl(canonical)
                    ?.let { null }

        val isTvPage =
            document
                .selectFirst(".film-stats")
                ?.text()
                ?.contains("TV", true) == true ||
                document
                    .selectFirst("#main-wrapper")
                    ?.classNames()
                    ?.contains("layout-page-watchtv") == true ||
                aniDetail?.attr("data-episode-number")
                    ?.isNotBlank() == true

        val currentEpisodeId =
            aniDetail
                ?.attr("data-id")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: episodeIdFromUrl(canonical)

        /*
         * For TV:
         *
         * 1. Strip ?ep=... so CloudStream's series URL is stable.
         * 2. Fetch the complete episode list from the site's episode API.
         * 3. Always return TvSeries.
         *
         * This is the key fix for the "Play Movie / No Links Found" screen
         * shown in the user's test.
         */
        if (
            isTvPage &&
            animeId != null
        ) {
            val seriesUrl = canonical
                .substringBefore("?ep=")
                .substringBefore("&ep=")

            val seasonNumber =
                document
                    .selectFirst(
                        ".other-season .os-item.active .title"
                    )
                    ?.text()
                    ?.let { seasonText ->
                        Regex(
                            """Season\s+(\d+)""",
                            RegexOption.IGNORE_CASE
                        )
                            .find(seasonText)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    }
                    ?: 1

            val expectedCount =
                episodeCountFromDocument(document)

            /*
             * The episode-list endpoint is the source of truth for the
             * individual episode IDs. The page's .tick-eps is only used as
             * a validation check.
             */
            var episodes =
                getEpisodesFromApi(
                    animeId = animeId,
                    seasonNumber = seasonNumber,
                    referer = seriesUrl
                )

            /*
             * Retry once with a clean referer when the first AJAX request was
             * intercepted or returned incomplete HTML.
             */
            if (
                episodes.isEmpty() ||
                (
                    expectedCount != null &&
                        episodes.size < expectedCount
                    )
            ) {
                episodes =
                    getEpisodesFromApi(
                        animeId = animeId,
                        seasonNumber = seasonNumber,
                        referer = pageUrl
                    )
            }

            /*
             * Do NOT create fake Episode 1 when the endpoint fails.
             * That was the reason the previous build showed only one episode.
             *
             * If the site says 25 episodes but the API does not return the
             * corresponding 25 episode records, we refuse to invent the
             * missing IDs and return no TV series response rather than display
             * incorrect episode data.
             */
            if (
                episodes.isEmpty() ||
                (
                    expectedCount != null &&
                        episodes.size != expectedCount
                    )
            ) {
                return null
            }

            return newTvSeriesLoadResponse(
                title,
                seriesUrl,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        /*
         * Normal non-TV content.
         */
        val pageType =
            if (
                document
                    .selectFirst(".film-stats")
                    ?.text()
                    ?.contains("MOVIE", true) == true
            ) {
                TvType.Movie
            } else {
                TvType.Anime
            }

        return newMovieLoadResponse(
            title,
            canonical,
            pageType,
            canonical
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
