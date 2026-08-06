package com.example.akwam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AkwamProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AkwamProvider())
    }
}
