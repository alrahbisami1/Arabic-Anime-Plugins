package com.shadows.animerco

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimercoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimercoProvider())
    }
}
