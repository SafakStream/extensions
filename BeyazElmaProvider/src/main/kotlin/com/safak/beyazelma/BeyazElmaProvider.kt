package com.safak.beyazelma

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
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

    // 1. ANA SAYFA: Kanal listesini çek
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

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/kanallar").document
        return document.select("a.site-channel-row").mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    // 3. DETAY: Kanal sayfasından embed linkini bul
    override suspend fun load(url: String): LoadResponse? {
        // url zaten kanal sayfası (örn: https://beyazelma78.com/kanal/atv)
        return newLiveStreamLoadResponse(
            name = "BeyazElma Kanal",
            url = url,
            dataUrl = url
        )
    }

    // 4. VİDEO LİNKİNİ ÇIKAR
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Kanal sayfasını çek
            val document = app.get(data).document

            // /api/embed?u=... linkini bul
            // Next.js sayfasında bu link genellikle bir <iframe> veya <link rel="preload"> içinde olur
            var embedUrl: String? = null

            // Önce iframe'lerde ara
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("/api/embed")) {
                    embedUrl = if (src.startsWith("http")) src else mainUrl.trimEnd('/') + src
                }
            }

            // Bulamazsa link rel=preload'larda ara
            if (embedUrl == null) {
                document.select("link[rel=preload]").forEach { link ->
                    val href = link.attr("href")
                    if (href.contains("/api/embed")) {
                        embedUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + href
                    }
                }
            }

            // Bulamazsa tüm sayfada regex ile ara
            if (embedUrl == null) {
                val match = Regex("""/api/embed\?u=[^"'\s]+""").find(document.html())
                if (match != null) {
                    embedUrl = mainUrl.trimEnd('/') + match.value
                }
            }

            if (embedUrl == null) {
                return false
            }

            // Embed linkini CloudStream'in extractor'ına gönder
            loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
