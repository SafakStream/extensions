package com.recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class BeyazElmaProvider : MainAPI() {
    override var mainUrl = "https://beyazelma78.com"
    override var name = "BeyazElma"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/kanallar").document
        val homeItems = mutableListOf<SearchResponse>()

        document.select("a[href^='/kanal/']").forEach { element ->
            val title = element.selectFirst("p")?.text()?.trim() ?: element.text().trim()
            val href = fixUrl(element.attr("href"))
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

            if (title.isNotBlank()) {
                homeItems.add(
                    LiveSearchResponse(
                        name = title,
                        url = href,
                        apiName = this@BeyazElmaProvider.name,
                        type = TvType.Live,
                        posterUrl = poster
                    )
                )
            }
        }

        return HomePageResponse(
            listOf(HomePageList("Canlı Kanallar", homeItems.distinctBy { it.url }))
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Canlı Kanal"
        val poster = document.selectFirst("img[alt='$title']")?.attr("src")?.let { fixUrl(it) }

        return LiveStreamLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            dataUrl = url,
            posterUrl = poster
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelResponse = app.get(data)
        val channelHtml = channelResponse.text

        val embedPathRegex = Regex("""(?:streamUrl|preload"\s+href)="(/api/embed\?u=[^"&]+)""")
        val embedMatch = embedPathRegex.find(channelHtml) 
            ?: Regex("""(/api/embed\?u=[a-zA-Z0-9_-]+)""").find(channelHtml)

        val embedPath = embedMatch?.groupValues?.get(1) ?: return false
        val embedUrl = fixUrl(embedPath)

        val embedResponse = app.get(
            embedUrl,
            headers = mapOf(
                "Referer" to data,
                "User-Agent" to userAgent
            )
        )
        val embedHtml = embedResponse.text

        val streamRegex = Regex("""(?:var\s+src\s*=\s*|["'])(/api/stream\?u=[^"'\s]+)["']""")
        val streamMatch = streamRegex.find(embedHtml) ?: return false

        val streamPath = streamMatch.groupValues[1]
        val finalStreamUrl = fixUrl(streamPath)

        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} Canlı",
                url = finalStreamUrl,
                referer = embedUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to userAgent
                )
            )
        )

        return true
    }
}
