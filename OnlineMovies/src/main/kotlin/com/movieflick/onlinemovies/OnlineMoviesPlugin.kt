package com.movieflick.onlinemovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlineMoviesPlugin : Plugin() {

    override fun load(context: Context) {
        // Register the Online Movies provider
        registerMainAPI(OnlineMovies())
    }
}
