package com.safak.selcuksports

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    // Video sunucusunun base URL'si (kaynak koddan alındı)
    private val streamBaseUrl = "https://dga1op10s1u3lea.82250d06d39d38.click/live/"

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

        return newLiveSearchResponse("$title ($time)", href, TvType.Live) {
            this.posterUrl = ""
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(mainUrl).document
        return document.select("div.channel-list ul li").mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        return newLiveStreamLoadResponse(
            name = "SelcukSports Maç",
            url = url,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data-url'den id parametresini çıkar
            // Örnek: https://main.uxsyplayer6859599e6c.click/index.php?id=selcukbeinsports1
            val id = data.substringAfter("id=", "").substringBefore("&").substringBefore("#")

            if (id.isBlank()) {
                return false
            }

            // m3u8 linkini oluştur
            val m3u8Url = "$streamBaseUrl$id/playlist.m3u8"

            callback(newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://main.uxsyplayer6859599e6c.click/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                    "Referer" to "https://main.uxsyplayer6859599e6c.click/",
                    "Origin" to "https://main.uxsyplayer6859599e6c.click"
                )
            })
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
