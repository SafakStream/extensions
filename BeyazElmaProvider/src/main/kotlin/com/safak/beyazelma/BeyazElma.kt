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
    val channelHtml = app.get(data).text

    // 2) streamUrl'leri regex ile çıkar
    // Hem escape'li (\"streamUrl\":\"...\") hem escape'siz ("streamUrl":"...") destekle
    val streamUrls = Regex("""\\?"streamUrl2?\\?"\s*:\s*\\?"([^"\\]+)""")
        .findAll(channelHtml)
        .map { it.groupValues[1] }
        .distinct()
        .toList()

    android.util.Log.d("BeyazElma", "Bulunan stream URL sayısı: ${streamUrls.size}")

    if (streamUrls.isEmpty()) {
    // Önce: HTML'de "streamUrl" kelimesi geçiyor mu? (evet/hayır)
    android.util.Log.e("BeyazElma", "streamUrl bulunamadı! HTML'de 'streamUrl' geçiyor mu: ${channelHtml.contains("streamUrl")}")
    
    // Sonra: HTML'in ilk 300 karakterini göster
    android.util.Log.e("BeyazElma", "HTML ilk 300: ${channelHtml.take(300)}")
    
    return false
}

    android.util.Log.d("BeyazElma", "URL'ler: $streamUrls")

    var found = false

    for (streamUrl in streamUrls) {
        try {
            val embedUrl = if (streamUrl.startsWith("http")) streamUrl
                           else "$mainUrl$streamUrl"

            android.util.Log.d("BeyazElma", "Embed deneniyor: $embedUrl")

            val embedHtml = app.get(embedUrl).text

            // 4 farklı yöntem dene
            var m3u8Url: String? = null

            // Yöntem 1: SIGNED_URL direkt
            m3u8Url = Regex("""SIGNED_URL\s*=\s*"([^"]+)"""")
                .find(embedHtml)?.groupValues?.get(1)

            // Yöntem 2: _ja0 XOR çöz
            if (m3u8Url == null) {
                val ja0Match = Regex("""_ja0\s*=\s*\[([\d,\s]+)]""").find(embedHtml)
                if (ja0Match != null) {
                    val numbers = ja0Match.groupValues[1]
                        .split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                    val decoded = numbers.map { n ->
                        ((n xor 120) - 108 + 256) % 256
                    }.map { it.toChar() }.joinToString("")
                    m3u8Url = Regex("""SIGNED_URL\s*=\s*"([^"]+)"""")
                        .find(decoded)?.groupValues?.get(1)
                }
            }

            // Yöntem 3: "primary" JSON alanı (escape'li ve escape'siz)
            if (m3u8Url == null) {
                val primary = Regex("""\\?"primary\\?"\s*:\s*\\?"([^"\\]+)""")
                    .find(embedHtml)?.groupValues?.get(1)
                if (primary != null) {
                    m3u8Url = when {
                        primary.contains(".m3u8") -> primary
                        primary.contains("?") -> "$primary&format=.m3u8"
                        else -> "$primary?format=.m3u8"
                    }
                }
            }

            // Yöntem 4: Direkt .m3u8 linki ara
            if (m3u8Url == null) {
                m3u8Url = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                    .find(embedHtml)?.value
            }

            if (m3u8Url != null) {
                android.util.Log.d("BeyazElma", "✅ m3u8 bulundu: $m3u8Url")

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
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
                            "Referer" to embedUrl,
                            "Origin" to mainUrl
                        )
                    }
                )
                found = true
            } else {
                android.util.Log.w("BeyazElma", "❌ m3u8 bulunamadı. Embed HTML ilk 1000: ${embedHtml.take(1000)}")
            }
        } catch (e: Exception) {
            android.util.Log.e("BeyazElma", "Hata ($streamUrl): ${e.message}", e)
            continue
        }
    }

    return found
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
