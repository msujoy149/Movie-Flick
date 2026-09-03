package com.movieflick.dhakaftp

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DhakaFTPPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(DhakaFTP())
    }
}
