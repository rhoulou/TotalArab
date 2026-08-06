package com.example.wecima

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class WeCimaProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WeCimaProvider())
    }
}
