package com.safak.beyazelma

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class BeyazElmaProvider : MainAPI() {
    override var mainUrl = "https://beyazelma78.com/"
    override var name = "BeyazElma"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "kanallar" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/kanallar").document
        val list = document.select("a.site-channel-row").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        val name = this.selectFirst("span.site-channel-row-name")?.text() ?: return null
        val logoPath = this.selectFirst("span.site-channel-row-logo img")?.attr("src") ?: ""

        if (href.isBlank()) return null

        val fullUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + href
        val fullLogo = if (logoPath.startsWith("http")) logoPath else mainUrl.trimEnd('/') + logoPath

        return newLiveSearchResponse(name, fullUrl, TvType.Live) {
            this.posterUrl = fullLogo
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/kanallar").document
        return document.select("a.site-channel-row").mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse? {
        return newLiveStreamLoadResponse(
            name = "BeyazElma Kanal",
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
            // data = https://beyazelma78.com/kanal/atv
            // slug'ı çıkar: /kanal/atv -> atv
            val slug = data.substringAfterLast("/").substringBefore("?").substringBefore("#")

            if (slug.isBlank()) return false

            // Doğrudan m3u8 linkini oluştur
            val m3u8Url = "https://beyazelma.xtrahut.xyz/live/$slug/playlist.m3u8"

            callback(newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://beyazelma.xtrahut.xyz/"
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                    "Referer" to "https://beyazelma.xtrahut.xyz/",
                    "Origin" to "https://beyazelma.xtrahut.xyz"
                )
            })

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
