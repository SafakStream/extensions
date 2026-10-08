package com.safak.beyazelma

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BeyazElmaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BeyazElma())
    }
}
