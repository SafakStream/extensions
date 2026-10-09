package com.safak.selcuksports

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SelcukSportsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SelcukSportsProvider())
    }
}
