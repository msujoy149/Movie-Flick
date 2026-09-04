package com.movieflick.kh

ulnaplex

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KhulnaPlexPlugin : BasePlugin() {

    override fun load() {
        registerMainAPI(KhulnaPlex())
    }
}
