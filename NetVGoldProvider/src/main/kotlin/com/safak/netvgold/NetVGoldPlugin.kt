package com.safak.netvgold

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NetVGoldPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NetVGoldProvider())
    }
}
