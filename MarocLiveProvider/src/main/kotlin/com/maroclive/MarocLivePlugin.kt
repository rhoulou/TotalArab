package com.maroclive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class marocLivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MarocLiveProvider())
    }
}
