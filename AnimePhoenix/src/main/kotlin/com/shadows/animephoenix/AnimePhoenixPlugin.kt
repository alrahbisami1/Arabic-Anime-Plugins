package com.shadows.animephoenix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimePhoenixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimePhoenixProvider())
    }
}
