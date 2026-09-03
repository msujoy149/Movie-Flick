package com.movieflick.moviebox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieBoxPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(MovieBox())
    }
}
