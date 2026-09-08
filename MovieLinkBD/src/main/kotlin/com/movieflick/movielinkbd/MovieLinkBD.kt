package com.movieflick.movielinkbd

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class MovieLinkBD : MainAPI() {
    private companion object {
        const val PRIMARY = "https://movielinkbd.tv"
        const val FALLBACK_1 = "https://vacj4n.movielinkbd.li"
        const val FALLBACK_2 = "https://movielinkbd.one"
        val DOMAINS = listOf(PRIMARY, FALLBACK_1, FALLBACK_2)

        const val RECENTLY = "recently"
        const val MOVIES = "movies"
        const val DUAL_AUDIO = "dual_audio"
        const val ONGOING = "ongoing"
        const val TV_SHOW = "tv_show"
        const val ANIME = "anime"
        const val PLAYER_JSON_SELECTOR = "#mlbdInlinePlayerData"
        const val PRIORITY_CACHE_MS = 120_000L
    }

    override var name: String = "Movie Link BD"
    override var mainUrl: String = PRIMARY
    override var lang: String = "bn"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "Recently Uploads" to RECENTLY,
        "Movies" to MOVIES,
        "Dual Audio" to DUAL_AUDIO,
        "Ongoing Series" to ONGOING,
        "TV Show" to TV_SHOW,
        "Anime" to ANIME
    )

    private var priorityCacheTime = 0L
    private val recentlyKeys = linkedSetOf<String>()
    private val ongoingKeys = linkedSetOf<String>()
    private val dualAudioKeys = linkedSetOf<String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = request.data
        val routes = when (category) {
            RECENTLY -> listOf(if (page <= 1) "/" else "/page/$page/")
            MOVIES -> listOf("/type/movies", "/bollywood", "/language/hindi-dubbed", "/genre/action")
            DUAL_AUDIO -> listOf("/language/dual-audio")
            ONGOING -> listOf("/ongoing")
            TV_SHOW -> listOf("/drama", "/type/series")
            ANIME -> listOf("/anime", "/genre/animation")
            else -> emptyList()
        }

        val merged = linkedMapOf<String, SearchResponse>()
        for (route in routes) {
            val document = getDocumentWithFallback(route) ?: continue
            val cards = if (category == RECENTLY && page == 1) {
                recentlyUpdatedCards(document)
            } else {
                document.select(".movie-cards-container .movie-card")
            }

            cards.forEach { card ->
                parseCard(card)?.let { merged.putIfAbsent(contentKey(it.url), it) }
            }
        }

        val filtered = when (category) {
            RECENTLY -> merged.values.toList()
            ONGOING -> {
                refreshPriorityCachesIfNeeded()
                merged.values.filter { contentKey(it.url) !in recentlyKeys }
            }
            DUAL_AUDIO -> {
                refreshPriorityCachesIfNeeded()
                merged.values.filter { contentKey(it.url) !in (recentlyKeys + ongoingKeys) }
            }
            MOVIES, TV_SHOW, ANIME -> {
                refreshPriorityCachesIfNeeded()
                merged.values.filter {
                    contentKey(it.url) !in (recentlyKeys + ongoingKeys + dualAudioKeys)
                }
            }
            else -> merged.values.toList()
        }

        return newHomePageResponse(request.name, filtered, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val document = getDocumentWithFallback("/search?q=$encoded") ?: return emptyList()
        val merged = linkedMapOf<String, SearchResponse>()

        document.select(".movie-cards-container .movie-card").forEach { card ->
            parseCard(card)?.let { merged.putIfAbsent(contentKey(it.url), it) }
        }

        return merged.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val path = pathFromUrl(url)
        val document = getDocumentWithFallback(path) ?: return null
        val json = parsePlayerJson(document)

        val title = json?.optString("title")?.trim().takeUnless { it.isNullOrBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("•").trim()

        val poster = json?.optString("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val contentType = json?.optString("content_type")?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            ?: when {
                path.startsWith("/movie/") -> "movie"
                path.startsWith("/anime/") -> "anime"
                path.startsWith("/series/") || path.startsWith("/drama/") -> "series"
                else -> "movie"
            }

        if (contentType == "movie" || path.startsWith("/movie/")) {
            return newMovieLoadResponse(
                title,
                absolutePrimary(path),
                TvType.Movie,
                absolutePrimary(path)
            ) {
                posterUrl = poster
                plot = extractPlot(document)
                year = extractYear(title, document)
            }
        }

        val episodes = parseEpisodes(json, absolutePrimary(path))
        return newTvSeriesLoadResponse(
            title,
            absolutePrimary(path),
            if (contentType == "anime" || path.startsWith("/anime/")) TvType.Anime else TvType.TvSeries,
            episodes
        ) {
            posterUrl = poster
            plot = extractPlot(document)
            year = extractYear(title, document)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cut = data.indexOf("||")
        val pageUrl = if (cut >= 0) data.substring(0, cut) else data
        val episodeId = if (cut >= 0) data.substring(cut + 2).trim() else null

        val path = pathFromUrl(pageUrl)
        val document = getDocumentWithFallback(path) ?: return false
        val json = parsePlayerJson(document) ?: return false
        val episodes = json.optJSONArray("episodes") ?: return false

        var emitted = false

        for (i in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(i) ?: continue
            if (!episodeId.isNullOrBlank() && episode.optString("id") != episodeId) continue

            val sources = episode.optJSONArray("sources") ?: continue

            for (j in 0 until sources.length()) {
                val sourceObject = sources.optJSONObject(j) ?: continue
                val streamUrl = sourceObject.optString("url").trim()
                if (streamUrl.isBlank()) continue

                val quality = parseQuality(sourceObject.opt("quality"))
                val audio = sourceObject.optString("audio").trim()
                val provider = sourceObject.optString("provider").trim().ifBlank { "MLBD CDN" }

                val linkName = buildString {
                    append(provider)
                    if (quality > 0 && quality != Qualities.Unknown.value) {
                        append(" - ${quality}p")
                    }
                    if (audio.isNotBlank()) {
                        append(" - $audio")
                    }
                }

                callback(
                    newExtractorLink(name, linkName, streamUrl, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                        this.referer = absolutePrimary(path)
                    }
                )
                emitted = true

                sourceObject.optJSONArray("external_subtitles")?.let { subs ->
                    for (k in 0 until subs.length()) {
                        val sub = subs.optJSONObject(k) ?: continue
                        val subUrl = sub.optString("url").trim()
                        if (subUrl.isBlank()) continue

                        val language = sub.optString("label")
                            .ifBlank { sub.optString("language") }
                            .ifBlank { "Subtitle" }

                        subtitleCallback(newSubtitleFile(language, subUrl))
                    }
                }
            }

            if (!episodeId.isNullOrBlank()) break
        }

        return emitted
    }

    private fun parseCard(card: Element): SearchResponse? {
        val link = card.selectFirst("a.title") ?: return null
        val title = link.text().trim()
        val href = link.attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null

        val url = absolutePrimary(href)
        val poster = card.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val path = pathFromUrl(url)
        return when {
            path.startsWith("/anime/") ->
                newMovieSearchResponse(title, url, TvType.Anime) { posterUrl = poster }

            path.startsWith("/series/") || path.startsWith("/drama/") ->
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }

            else ->
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun recentlyUpdatedCards(document: Document): List<Element> {
        val heading = document.select(".mlbd-page-head").firstOrNull {
            it.selectFirst("h1")?.text()?.trim()?.equals("RECENTLY UPDATED", true) == true
        }

        val grid = heading?.nextElementSibling()
        return if (grid != null && grid.select(".movie-card").isNotEmpty()) {
            grid.select(".movie-card")
        } else {
            document.select(".movie-cards-container .movie-card")
        }
    }

    private fun parseEpisodes(json: JSONObject?, pageUrl: String): List<Episode> {
        val array = json?.optJSONArray("episodes") ?: return emptyList()
        val result = ArrayList<Episode>()

        for (i in 0 until array.length()) {
            val ep = array.optJSONObject(i) ?: continue
            if (ep.optString("kind").equals("movie", true)) continue

            val id = ep.optString("id").trim()
            if (id.isBlank()) continue

            val label = ep.optString("label").trim().ifBlank { "Episode ${i + 1}" }
            val number = ep.optInt("number", 0).takeIf { it > 0 }
                ?: extractEpisodeNumber(label, i + 1)
            val season = ep.optInt("season", 0).takeIf { it > 0 }

            result += newEpisode("$pageUrl||$id") {
                this.name = label
                this.episode = number
                this.season = season
            }
        }

        return result.sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
        )
    }

    private fun parsePlayerJson(document: Document): JSONObject? {
        val script = document.selectFirst(PLAYER_JSON_SELECTOR) ?: return null
        val raw = script.data().ifBlank { script.html() }.trim()
        if (raw.isBlank()) return null

        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun getDocumentWithFallback(path: String): Document? {
        val normalized = normalizePath(path)

        for (domain in DOMAINS) {
            try {
                val response = app.get(domain + normalized)
                if (response.code !in 200..399) continue

                val doc = response.document
                val valid = doc.selectFirst("#mlbdInlinePlayerData, .movie-card, .movie-cards-container") != null
                if (valid || normalized == "/") return doc
            } catch (_: Throwable) {
                // Try the next mirror.
            }
        }

        return null
    }

    private suspend fun refreshPriorityCachesIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - priorityCacheTime < PRIORITY_CACHE_MS) return

        recentlyKeys.clear()
        ongoingKeys.clear()
        dualAudioKeys.clear()

        getDocumentWithFallback("/")?.let { doc ->
            recentlyUpdatedCards(doc).forEach {
                parseCard(it)?.let { item -> recentlyKeys += contentKey(item.url) }
            }
        }

        getDocumentWithFallback("/ongoing")?.let { doc ->
            doc.select(".movie-cards-container .movie-card").forEach {
                parseCard(it)?.let { item -> ongoingKeys += contentKey(item.url) }
            }
        }

        getDocumentWithFallback("/language/dual-audio")?.let { doc ->
            doc.select(".movie-cards-container .movie-card").forEach {
                parseCard(it)?.let { item -> dualAudioKeys += contentKey(item.url) }
            }
        }

        priorityCacheTime = now
    }

    private fun parseQuality(raw: Any?): Int {
        if (raw == null || raw == JSONObject.NULL) return Qualities.Unknown.value

        return when (raw) {
            is Number -> raw.toInt().takeIf { it > 0 } ?: Qualities.Unknown.value
            else -> getQualityFromName(raw.toString())
        }
    }

    private fun contentKey(url: String): String = pathFromUrl(url)
        .substringBefore("#")
        .substringBefore("?")
        .removeSuffix("/")
        .lowercase(Locale.ROOT)

    private fun pathFromUrl(url: String): String = try {
        val uri = URI(url)
        buildString {
            append(uri.rawPath.ifBlank { "/" })
            if (!uri.rawQuery.isNullOrBlank()) {
                append("?").append(uri.rawQuery)
            }
        }.let(::normalizePath)
    } catch (_: Throwable) {
        val noScheme = url.substringAfter("://", url)
        val slash = noScheme.indexOf('/')
        normalizePath(if (slash >= 0) noScheme.substring(slash) else "/")
    }

    private fun normalizePath(path: String): String {
        val clean = path.trim()
        if (clean.isBlank()) return "/"
        if (clean.startsWith("http://") || clean.startsWith("https://")) return pathFromUrl(clean)
        return if (clean.startsWith("/")) clean else "/$clean"
    }

    private fun absolutePrimary(href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) href
        else PRIMARY + normalizePath(href)

    private fun extractPlot(document: Document): String? {
        for (selector in listOf(
            ".story-text",
            ".storyline-box .story-text",
            ".movie-extra-info",
            "meta[name=description]"
        )) {
            val element = document.selectFirst(selector) ?: continue
            val text = if (element.tagName().equals("meta", true)) {
                element.attr("content")
            } else {
                element.text()
            }

            if (text.isNotBlank()) return text.trim()
        }

        return null
    }

    private fun extractYear(title: String, document: Document): Int? {
        val texts = mutableListOf(title)
        texts += document.select("meta[property=og:title], h1").map {
            it.attr("content").ifBlank { it.text() }
        }

        return texts.asSequence()
            .mapNotNull { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }
            .firstOrNull()
    }

    private fun extractEpisodeNumber(text: String, fallback: Int): Int =
        Regex("(?:episode|ep|e)\\s*[-._]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: fallback
}
