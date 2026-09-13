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

    private val movieMarker = "mf_movie=1"
    private val tvMarker = "mf_tv=1"

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

    private fun isMediaUrl(url: String): Boolean {
        val value = url
            .trim()
            .lowercase(Locale.ROOT)
            .substringBefore("#")

        if (
            !value.startsWith("http://") &&
            !value.startsWith("https://")
        ) {
            return false
        }

        val path = value.substringBefore("?")

        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m3u") ||
            path.endsWith(".mpd") ||
            path.endsWith(".webm") ||
            path.endsWith(".m4v") ||
            path.endsWith(".mov") ||
            path.endsWith(".mkv") ||
            path.contains("/hls/") ||
            path.contains("/dash/") ||
            path.contains(".m3u8/")
    }

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
                ?.selectFirst(".fd-infor, .film-stats, .fdi-item, .item")
                ?.text()
        ).uppercase(Locale.ROOT)

        return when {
            text.contains("MOVIE") ||
                path.matches(
                    Regex(""".*/movie/.*""")
                ) ->
                TvType.Movie

            text.contains("TV") ||
                path.contains("/tv") ->
                TvType.TvSeries

            else ->
                TvType.Anime
        }
    }

    private fun markUrl(
        url: String,
        type: TvType
    ): String {
        return when (type) {
            TvType.Movie -> {
                if (url.contains("?")) "$url&$movieMarker"
                else "$url?$movieMarker"
            }

            TvType.TvSeries -> {
                if (url.contains("?")) "$url&$tvMarker"
                else "$url?$tvMarker"
            }

            else -> url
        }
    }

    private fun hasMarker(
        url: String,
        marker: String
    ): Boolean =
        Regex(
            """(?:\?|&)${Regex.escape(marker)}(?:&|$)"""
        ).containsMatchIn(url)

    private fun stripMarkers(url: String): String {
        return url
            .replace(Regex("""([?&])mf_movie=1(?=&|$)"""), "$1")
            .replace(Regex("""([?&])mf_tv=1(?=&|$)"""), "$1")
            .replace(Regex("""\?&"""), "?")
            .replace(Regex("""[?&]$"""), "")
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
                url = markUrl(url, type),
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
        val normalizedBase = base
            .trimEnd('/')
            .lowercase(Locale.ROOT)

        val forcedType = when {
            normalizedBase == "$mainUrl/movie".lowercase(Locale.ROOT) ->
                TvType.Movie

            normalizedBase == "$mainUrl/tv".lowercase(Locale.ROOT) ->
                TvType.TvSeries

            else ->
                null
        }

        val items = parseCards(
            response.document,
            forceType = forcedType
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
        val selectors = listOf(
            "#ani_detail[data-anime-id]",
            ".anis-content[data-anime-id]",
            "[data-anime-id]"
        )

        for (selector in selectors) {
            document
                .selectFirst(selector)
                ?.attr("data-anime-id")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        /*
         * Standard HiAnime watch pages also expose:
         * <script id="syncData">{"anime_id":"..." ...}</script>
         */
        document.select("script").forEach { script ->
            val raw = script.data().trim()
            if (raw.isBlank()) return@forEach

            val match = Regex(
                """"anime_id"\s*:\s*"?(\d+)"?""",
                RegexOption.IGNORE_CASE
            ).find(raw)

            match
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

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
                "a[data-id][href*='/watch/'], " +
                "#episodes-content a[data-id][href]"
        )

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
                )?.text()
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

    private fun episodesFromJson(
        rawJson: String,
        seasonNumber: Int
    ): List<Episode> {
        val result = ArrayList<Episode>()

        val json = runCatching {
            org.json.JSONObject(rawJson)
        }.getOrNull() ?: return result

        fun readArray(obj: org.json.JSONObject): org.json.JSONArray? {
            val direct = obj.optJSONArray("episodes")
            if (direct != null) return direct

            val data = obj.optJSONObject("data")
            data?.optJSONArray("episodes")?.let { return it }

            val resultObj = obj.optJSONObject("result")
            resultObj?.optJSONArray("episodes")?.let { return it }

            return null
        }

        val array = readArray(json) ?: return result

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue

            val id = item.optString(
                "id",
                item.optString(
                    "episodeId",
                    item.optString("episode_id")
                )
            ).trim()

            if (id.isBlank()) continue

            val number =
                item.optInt(
                    "number",
                    item.optInt(
                        "episodeNumber",
                        item.optInt("episode_number", index + 1)
                    )
                )

            val title =
                cleanText(
                    item.optString(
                        "title",
                        item.optString(
                            "name",
                            "Episode $number"
                        )
                    )
                ).ifBlank {
                    "Episode $number"
                }

            val href =
                item.optString(
                    "href",
                    item.optString("url")
                ).trim().ifBlank {
                    null
                }

            val episodeUrl = if (href != null) {
                absoluteUrl(href)
            } else {
                /*
                 * Some API wrappers return the episode id but not href.
                 * Only build the canonical watch URL when the response also
                 * supplies a usable anime slug/path elsewhere.
                 */
                continue
            }

            result += newEpisode(episodeUrl) {
                name = title
                episode = number
                season = seasonNumber
                data = "$episodeUrl||$id"
            }
        }

        return result
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private suspend fun getEpisodesFromApi(
        animeId: String,
        seasonNumber: Int = 1,
        referer: String = "$mainUrl/"
    ): List<Episode> {

        /*
         * The HTML source supplied for hianime.at exposes both:
         *   window.hianime_ajax.rest_url
         *   window.hianime_ep_ajax.rest_url
         * and the page loads watch.min.js.
         *
         * The deployed site is not guaranteed to expose the old hianime.to
         * AJAX paths unchanged, so try the standard endpoint first and then
         * the site's /api/theme/ REST-style variants.
         */
        val endpoints = listOf(
            "$mainUrl/ajax/v2/episode/list/$animeId",
            "$mainUrl/api/theme/episode/list/$animeId",
            "$mainUrl/api/theme/episodes/$animeId",
            "$mainUrl/api/theme/anime/$animeId/episodes",
            "$mainUrl/api/theme/episode/list?anime_id=$animeId",
            "$mainUrl/api/theme/episodes?anime_id=$animeId"
        ).distinct()

        val headers = pageHeaders + mapOf(
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        for (url in endpoints) {
            val response = runCatching {
                app.get(
                    url,
                    headers = headers
                )
            }.getOrNull() ?: continue

            val raw = response.text.trim()
            if (raw.isBlank()) continue

            /*
             * First: an HTML fragment returned by the classical HiAnime
             * episode endpoint.
             */
            val htmlCandidates = linkedSetOf<String>()

            runCatching {
                val json = org.json.JSONObject(raw)

                json.optString("html")
                    .takeIf { it.isNotBlank() }
                    ?.let { htmlCandidates.add(it) }

                json.optJSONObject("data")
                    ?.optString("html")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { htmlCandidates.add(it) }

                json.keys().forEach { key ->
                    val value = json.optString(key)
                    if (
                        value.contains(
                            "ssl-item",
                            ignoreCase = true
                        ) ||
                        value.contains(
                            "data-id",
                            ignoreCase = true
                        )
                    ) {
                        htmlCandidates.add(value)
                    }
                }
            }

            if (
                raw.contains(
                    "ssl-item",
                    ignoreCase = true
                )
            ) {
                htmlCandidates.add(raw)
            }

            for (html in htmlCandidates) {
                val episodes = parseEpisodeItems(
                    html = html,
                    seasonNumber = seasonNumber
                )

                if (episodes.isNotEmpty()) {
                    return episodes
                }
            }

            /*
             * Second: some API wrappers return a JSON array of episode
             * objects instead of the HTML fragment.
             */
            val jsonEpisodes = episodesFromJson(
                rawJson = raw,
                seasonNumber = seasonNumber
            )

            if (jsonEpisodes.isNotEmpty()) {
                return jsonEpisodes
            }
        }

        return emptyList()
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {
        val rawInput = url.substringBefore("||").trim()

        val forcedMovie = hasMarker(
            rawInput,
            movieMarker
        )
        val forcedTv = hasMarker(
            rawInput,
            tvMarker
        )

        val rawPageUrl = absoluteUrl(
            stripMarkers(rawInput)
        )

        val pageUrl = rawPageUrl
            .substringBefore("#")
            .let { value ->
                value
                    .substringBefore("?ep=")
                    .substringBefore("&ep=")
            }

        val response = runCatching {
            app.get(
                rawPageUrl,
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
            !forcedMovie && (
                forcedTv ||
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
            )

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
            val seriesUrl = pageUrl

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

            /*
             * First try the live episode AJAX source. This is the
             * authoritative source for the actual episode IDs.
             */
            var episodes =
                getEpisodesFromApi(
                    animeId = animeId,
                    seasonNumber = seasonNumber,
                    referer = seriesUrl
                )

            /*
             * If the AJAX endpoint is unavailable, the page may already
             * contain an episode fragment (for example after an internal
             * server-side render). Use only real anchors from that fragment.
             */
            if (episodes.isEmpty()) {
                episodes = parseEpisodeItems(
                    html = document
                        .selectFirst("#episodes-content")
                        ?.html()
                        .orEmpty(),
                    seasonNumber = seasonNumber
                )
            }

            /*
             * Never invent Episode 1. If there is no authoritative episode
             * record we still know this is a TV page, so keep the correct
             * TvSeries type instead of turning it into a Movie.
             */
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
                forcedMovie ||
                document
                    .selectFirst(".film-stats")
                    ?.text()
                    ?.contains("MOVIE", true) == true ||
                canonical.lowercase(Locale.ROOT)
                    .contains("/movie/")
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

    private fun parseServerHtml(
        html: String
    ): List<ServerInfo> {
        if (html.isBlank()) return emptyList()

        val document = Jsoup.parse(
            html,
            "$mainUrl/"
        )

        return document.select(
            "div.server-item[data-id], " +
                ".server-item[data-id], " +
                "[data-id].server-item"
        ).mapNotNull { item ->
            val id = item.attr("data-id").trim()
            if (id.isBlank()) return@mapNotNull null

            val name = cleanText(
                item.selectFirst(
                    ".server-name, .server-item-name, .name"
                )?.text()
            ).ifBlank {
                cleanText(item.text())
            }.ifBlank {
                "Server $id"
            }

            val type = item.attr("data-type")
                .ifBlank {
                    cleanText(
                        item.parents()
                            .firstOrNull {
                                it.hasAttr("data-type")
                            }
                            ?.attr("data-type")
                    )
                }
                .ifBlank {
                    val value = cleanText(
                        item.parent()?.text()
                    ).lowercase(Locale.ROOT)

                    when {
                        value.contains("dub") -> "dub"
                        value.contains("raw") -> "raw"
                        else -> "sub"
                    }
                }

            ServerInfo(
                id = id,
                name = name,
                type = type
            )
        }.distinctBy {
            "${it.id}:${it.type}"
        }
    }

    private fun parseServerJson(
        raw: String
    ): List<ServerInfo> {
        val result = ArrayList<ServerInfo>()

        val root = runCatching {
            JSONObject(raw)
        }.getOrNull() ?: return result

        fun consumeArray(
            array: org.json.JSONArray?,
            type: String
        ) {
            if (array == null) return

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue

                val id = obj.optString(
                    "id",
                    obj.optString(
                        "serverId",
                        obj.optString("server_id")
                    )
                ).trim()

                if (id.isBlank()) continue

                val name = cleanText(
                    obj.optString(
                        "name",
                        obj.optString(
                            "serverName",
                            "Server $id"
                        )
                    )
                ).ifBlank {
                    "Server $id"
                }

                result += ServerInfo(
                    id = id,
                    name = name,
                    type = type
                )
            }
        }

        fun consumeObject(
            obj: JSONObject?,
            type: String
        ) {
            if (obj == null) return

            consumeArray(
                obj.optJSONArray("servers"),
                type
            )

            consumeArray(
                obj.optJSONArray("data"),
                type
            )

            consumeArray(
                obj.optJSONArray(type),
                type
            )
        }

        consumeObject(root.optJSONObject("data"), "sub")
        consumeObject(root.optJSONObject("result"), "sub")

        consumeArray(
            root.optJSONArray("sub"),
            "sub"
        )
        consumeArray(
            root.optJSONArray("dub"),
            "dub"
        )
        consumeArray(
            root.optJSONArray("raw"),
            "raw"
        )
        consumeArray(
            root.optJSONArray("mixed"),
            "mixed"
        )

        return result.distinctBy {
            "${it.id}:${it.type}"
        }
    }

    private suspend fun getEpisodeServers(
        episodeId: String,
        referer: String
    ): List<ServerInfo> {
        /*
         * This is the exact endpoint observed in the user's Chrome Network tab:
         *
         * GET https://hianime.at/api/theme/episode/servers?episodeId=13116
         */
        val endpoints = listOf(
            "$mainUrl/api/theme/episode/servers?episodeId=$episodeId",
            "$mainUrl/ajax/v2/episode/servers?episodeId=$episodeId"
        ).distinct()

        val headers = pageHeaders + mapOf(
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        for (url in endpoints) {
            val response = runCatching {
                app.get(
                    url,
                    headers = headers
                )
            }.getOrNull() ?: continue

            val raw = response.text.trim()
            if (raw.isBlank()) continue

            val htmlServers = runCatching {
                JSONObject(raw).optString("html")
            }.getOrNull()
                ?.let(::parseServerHtml)
                .orEmpty()

            if (htmlServers.isNotEmpty()) {
                return htmlServers
            }

            val jsonServers = parseServerJson(raw)
            if (jsonServers.isNotEmpty()) {
                return jsonServers
            }

            val directHtml = parseServerHtml(raw)
            if (directHtml.isNotEmpty()) {
                return directHtml
            }
        }

        return emptyList()
    }

    private fun collectMediaUrls(
        value: Any?,
        found: MutableSet<String>
    ) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()

                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)

                    if (child is String) {
                        val candidate = child.trim()
                        if (isMediaUrl(candidate)) {
                            found.add(candidate)
                        }
                    }

                    collectMediaUrls(
                        child,
                        found
                    )
                }
            }

            is org.json.JSONArray -> {
                for (i in 0 until value.length()) {
                    collectMediaUrls(
                        value.opt(i),
                        found
                    )
                }
            }
        }
    }

    private fun directMediaFromJson(
        raw: String
    ): List<String> {
        val json = runCatching {
            JSONObject(raw)
        }.getOrNull() ?: return emptyList()

        val found = linkedSetOf<String>()

        collectMediaUrls(
            json,
            found
        )

        /*
         * Also accept raw JSON strings where escaping hides the URL.
         */
        val normalized = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")

        Regex(
            """https?://[^"\\\s]+?\.(?:m3u8|mp4|mpd|webm|m4v|mov|mkv)(?:\?[^"\\\s]*)?"""
        ).findAll(normalized).forEach {
            found.add(it.value)
        }

        return found.toList()
    }

    private fun sourceLinkFromJson(
        raw: String
    ): List<String> {
        val json = runCatching {
            JSONObject(raw)
        }.getOrNull() ?: return emptyList()

        val result = linkedSetOf<String>()

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()

                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)

                        if (child is String) {
                            val str = child.trim()

                            if (
                                key.equals("link", true) ||
                                key.equals("url", true) ||
                                key.equals("embed", true) ||
                                key.equals("iframe", true) ||
                                key.equals("source", true)
                            ) {
                                if (
                                    str.startsWith("http://") ||
                                    str.startsWith("https://")
                                ) {
                                    result.add(str)
                                }
                            }
                        }

                        walk(child)
                    }
                }

                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        walk(value.opt(i))
                    }
                }
            }
        }

        walk(json)

        return result.toList()
    }

    private suspend fun getEpisodeSources(
        serverId: String,
        referer: String
    ): List<String> {
        /*
         * The exact servers request is confirmed by the user's browser.
         * Source implementations across the HiAnime ecosystem use the
         * server-id -> episode/sources step. We try the current /api/theme/
         * forms first and retain the established ajax/v2 fallback.
         */
        val endpoints = listOf(
            "$mainUrl/api/theme/episode/sources?id=$serverId",
            "$mainUrl/api/theme/episode/source?id=$serverId",
            "$mainUrl/api/theme/episode/sources?serverId=$serverId",
            "$mainUrl/ajax/v2/episode/sources?id=$serverId"
        ).distinct()

        val headers = pageHeaders + mapOf(
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val links = linkedSetOf<String>()

        for (url in endpoints) {
            val response = runCatching {
                app.get(
                    url,
                    headers = headers
                )
            }.getOrNull() ?: continue

            val raw = response.text.trim()
            if (raw.isBlank()) continue

            directMediaFromJson(raw).forEach {
                links.add(it)
            }

            if (links.isNotEmpty()) {
                return links.toList()
            }

            sourceLinkFromJson(raw).forEach {
                links.add(it)
            }

            if (links.isNotEmpty()) {
                return links.toList()
            }
        }

        return links.toList()
    }

    private fun isEmbedUrl(
        url: String
    ): Boolean {
        val lower = url.lowercase(Locale.ROOT)

        if (
            lower.startsWith("http://") ||
            lower.startsWith("https://")
        ) {
            return !isMediaUrl(lower) &&
                (
                    lower.contains("zokoanime") ||
                        lower.contains("megacloud") ||
                        lower.contains("vidstream") ||
                        lower.contains("vidnest") ||
                        lower.contains("stream")
                )
        }

        return false
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
         * Fresh resolution on EVERY Play:
         *
         * watch page
         *   -> episode id
         *   -> /api/theme/episode/servers
         *   -> fresh server id
         *   -> fresh source request
         *   -> current m3u8/mp4/embed
         *
         * Nothing is stored between Play actions, so expiring media tokens
         * are not reused.
         */
        val pageData = data.substringBefore("||").trim()
        val storedEpisodeId =
            data.substringAfter(
                "||",
                ""
            ).trim().takeIf { it.isNotBlank() }

        if (pageData.isBlank()) return false

        /*
         * Direct media URL.
         */
        if (isMediaUrl(pageData)) {
            emitDirect(
                pageData,
                "$mainUrl/",
                callback
            )
            return true
        }

        val pageUrl = absoluteUrl(pageData)

        /*
         * Fresh watch-page request first. This also establishes the same
         * site session/referer context used by the browser.
         */
        val watchResponse = runCatching {
            app.get(
                pageUrl,
                headers = pageHeaders + mapOf(
                    "Referer" to "$mainUrl/"
                )
            )
        }.getOrNull() ?: return false

        /*
         * Prefer the episode ID already stored by parseEpisodeItems().
         * If unavailable, recover it from the watch page or URL.
         */
        val episodeId =
            storedEpisodeId
                ?: watchResponse.document
                    .selectFirst("#ani_detail[data-id]")
                    ?.attr("data-id")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: episodeIdFromUrl(pageUrl)

        if (episodeId.isNullOrBlank()) {
            /*
             * Single movie fallback: if the page itself exposes a media
             * URL, accept it. Otherwise there is no trustworthy source ID.
             */
            val direct = directMediaUrls(
                document = watchResponse.document,
                html = watchResponse.text,
                baseUrl = pageUrl
            )

            direct.forEach {
                emitDirect(
                    it,
                    pageUrl,
                    callback
                )
            }

            return direct.isNotEmpty()
        }

        /*
         * Exact current endpoint observed in the user's browser.
         */
        val servers =
            getEpisodeServers(
                episodeId = episodeId,
                referer = pageUrl
            )

        if (servers.isEmpty()) {
            return false
        }

        /*
         * Prefer normal SUB servers, then DUB, while still trying every
         * available server when the first one cannot resolve.
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

        for (server in orderedServers) {
            val sources =
                getEpisodeSources(
                    serverId = server.id,
                    referer = pageUrl
                )

            if (sources.isEmpty()) {
                continue
            }

            for (source in sources) {
                val cleanSource =
                    source
                        .replace("\\/", "/")
                        .replace("&amp;", "&")
                        .trim()

                /*
                 * Best case: the source API gives the actual HLS/MP4 URL.
                 * This is exactly what the browser ultimately requests:
                 * master.m3u8 -> variant index.m3u8 -> .ts segments.
                 */
                if (isMediaUrl(cleanSource)) {
                    emitDirect(
                        cleanSource,
                        if (
                            cleanSource.contains(
                                "hls2.aniwatchtv.uk",
                                true
                            )
                        ) {
                            "https://zokoanime.video/"
                        } else {
                            pageUrl
                        },
                        callback
                    )

                    return true
                }

                /*
                 * Otherwise the source is an embed page. Let CloudStream's
                 * extractor framework resolve it. This avoids treating an ad
                 * page as a video URL.
                 */
                if (
                    isEmbedUrl(cleanSource) ||
                    cleanSource.startsWith(
                        "https://",
                        true
                    )
                ) {
                    var emitted = false

                    runCatching {
                        loadExtractor(
                            cleanSource,
                            pageUrl,
                            subtitleCallback,
                            callback
                        )
                        emitted = true
                    }

                    if (emitted) {
                        return true
                    }
                }
            }
        }

        return false
    }

}
