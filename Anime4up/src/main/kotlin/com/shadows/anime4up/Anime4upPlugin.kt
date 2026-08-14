package com.shadows.anime4up

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Anime4upPlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(Anime4upProvider())
    }
}
