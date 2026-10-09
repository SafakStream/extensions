package com.safak.selcuksports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Element

class SelcukSportsProvider : MainAPI() {
    override var mainUrl = "https://www.selcuksportshd01390f9702.xyz/"
    override var name = "SelcukSports"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "tab1" to "Futbol",
        "tab2" to "Basketbol",
        "tab3" to "Tenis",
        "tab4" to "Çoklu Ekran",
        "tab5" to "7/24 TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val list = document.select("div#${request.data} ul li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = this.selectFirst("div.name")?.text() ?: return null
        val time = this.selectFirst("time.time")?.text() ?: ""
        val href = aTag.attr("data-url")

        if (href.isBlank()) return null

        return newMovieSearchResponse("$title ($time)", href, TvType.Live) {
            this.posterUrl = ""
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        return document.select("div.channel-list ul li").mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        return newMovieLoadResponse("SelcukSports Maç", url, TvType.Live, url) {
            this.posterUrl = ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = 720
            }
        )
        return true
    }
}
