package com.safak.beyazelma

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class BeyazElma : MainAPI() {
    override var mainUrl = "https://beyazelma78.com"
    override var name = "BeyazElma"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true

    // ═══════════════════════════════════════════════════════
    // 1. ANA SAYFA: /kanallar HTML'ini çek, kanalları listele
    // ═══════════════════════════════════════════════════════
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/kanallar").document
        val channels = document.select("a[href^=/kanal/]").mapNotNull { el ->
            el.toChannelSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = "Canlı Kanallar",
                    list = channels,
                    isHorizontalImages = false
                )
            ),
            hasNext = false
        )
    }

    // ═══════════════════════════════════════════════════════
    // 2. ARAMA: Aynı liste sayfasından isme göre filtrele
    // ═══════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/kanallar").document
        return document.select("a[href^=/kanal/]").mapNotNull { el ->
            val name = el.selectFirst(".site-channel-row-name")?.text()?.trim()
                ?: return@mapNotNull null
            if (!name.contains(query, ignoreCase = true)) return@mapNotNull null
            el.toChannelSearchResponse()
        }.distinctBy { it.url }
    }

    // ═══════════════════════════════════════════════════════
    // 3. LOAD: Kanal detay sayfası
    // ═══════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: url.substringAfterLast("/").replace("-", " ")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        )
    }

    // ═══════════════════════════════════════════════════════
    // 4. LOADLINKS: iframe → embed → XOR çöz → m3u8
    // ═══════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) Kanal sayfasını çek
        val channelDoc = app.get(data).document

        // 2) iframe src'sini bul: /api/embed?u=TOKEN
        val iframeSrc = channelDoc
            .selectFirst("iframe[src*=/api/embed]")
            ?.attr("src")
            ?: return false

        // 3) Tam URL'ye çevir
        val embedUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"

        // 4) Embed sayfasını çek
        val embedHtml = app.get(embedUrl).text

        // 5) _ja0 array'ini bul (XOR şifreli)
        val ja0Regex = Regex("""_ja0\s*=\s*\[([\d,\s]+)]""")
        val ja0Match = ja0Regex.find(embedHtml) ?: return false
        val numbers = ja0Match.groupValues[1]
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }

        if (numbers.isEmpty()) return false

        // 6) XOR çöz: ((n XOR 120) - 108 + 256) % 256
        val decoded = numbers.map { n ->
            ((n xor 120) - 108 + 256) % 256
        }.map { it.toChar() }.joinToString("")

        // 7) SIGNED_URL'i çıkar
        val signedRegex = Regex("""SIGNED_URL\s*=\s*"([^"]+)"""")
        val m3u8Url = signedRegex.find(decoded)?.groupValues?.get(1)
            ?: return false

        // 8) CloudStream'e ver
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
        )
        return true
    }

    // ═══════════════════════════════════════════════════════
    // YARDIMCI: <a> elementini SearchResponse'a çevir
    // ═══════════════════════════════════════════════════════
    private fun Element.toChannelSearchResponse(): SearchResponse? {
        val href = this.attr("href").trim()
        if (href.isBlank()) return null

        val name = this.selectFirst(".site-channel-row-name")?.text()?.trim()
            ?: this.text().replace("İzle", "").trim()
        if (name.isBlank()) return null

        val logo = this.selectFirst(".site-channel-row-logo img")?.attr("src")?.let {
            if (it.startsWith("http")) it else "$mainUrl$it"
        }

        return newLiveSearchResponse(
            name = name,
            url = if (href.startsWith("http")) href else "$mainUrl$href",
            type = TvType.Live
        ) {
            this.posterUrl = logo
        }
    }
}
