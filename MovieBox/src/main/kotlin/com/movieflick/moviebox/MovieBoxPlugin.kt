package com.movieflick.moviebox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Single MovieBox plugin entry point.
 *
 * The two proven MovieBox implementations are bundled as private assets and
 * are exposed through one CloudStream MainAPI facade.  The facade itself
 * handles runtime fallback; CloudStream only sees one MovieBox plugin.
 */
@CloudstreamPlugin
class MovieBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieBox(context))
    }
}
