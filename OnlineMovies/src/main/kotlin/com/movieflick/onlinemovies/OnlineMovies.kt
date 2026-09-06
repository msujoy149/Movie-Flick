package com.movieflick.onlinemovies

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList

class OnlineMovies : MainAPI() {

    override var mainUrl = "https://111.90.159.132"
    override var name = "Online Movies"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/year/2026/" to "Latest Movies",
        "$mainUrl/year/2026/" to "Movies",
        "$mainUrl/tv-show/" to "TV Show"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return newHomePageResponse(
            request,
            emptyList(),
            false
        )
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        return newSearchResponseList(
            emptyList(),
            false
        )
    }
}
