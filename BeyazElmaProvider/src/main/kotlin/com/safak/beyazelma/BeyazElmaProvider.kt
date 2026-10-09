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

    // Chrome UA — site tarayıcı kontrolü yapıyor
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "kanallar" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/kanallar", headers = defaultHeaders)

        // DEBUG LOGLARI
        println("BEYAZELMA_DEBUG: Status=${response.code}")
        println("BEYAZELMA_DEBUG: HTML=${response.text.take(3000)}")

        val document = response.document
        val list = document.select("a.site-channel-row").mapNotNull { it.toSearchResult() }

        println("BEYAZELMA_DEBUG: Bulunan kanal sayısı=${list.size}")

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
        val document = app.get("$mainUrl/kanallar", headers = defaultHeaders).document
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
            // 1. Kanal sayfasını çek
            val document = app.get(data, headers = defaultHeaders).document

            // 2. /api/embed linkini bul
            var embedUrl: String? = null
            document.select("link[rel=preload]").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/api/embed")) {
                    embedUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + href
                }
            }
            if (embedUrl == null) {
                val match = Regex("""/api/embed\?u=[^"'\s&]+""").find(document.html())
                if (match != null) embedUrl = mainUrl.trimEnd('/') + match.value
            }
            if (embedUrl == null) {
                println("BEYAZELMA_DEBUG: /api/embed bulunamadı")
                return false
            }
            println("BEYAZELMA_DEBUG: embedUrl=$embedUrl")

            // 3. Embed sayfasını çek
            val embedDoc = app.get(embedUrl, headers = defaultHeaders).document
            val html = embedDoc.html()

            // 4. Önce doğrudan /api/stream linkini ara
            var streamUrl: String? = null
            val streamMatch = Regex("""["'](/api/stream\?src=[^"']+)["']""").find(html)
            if (streamMatch != null) {
                streamUrl = "https://beyazelma.xtrahut.xyz" + streamMatch.groupValues[1]
            }

            // 5. Bulamazsa şifreli bloğu çöz
            if (streamUrl == null) {
                val arrayMatch = Regex("""_if5=\[([0-9,]+)\]""").find(html)
                if (arrayMatch != null) {
                    val numbers = arrayMatch.groupValues[1].split(",").map { it.trim().toInt() }
                    val decoded = numbers.map { ((it xor 47) - 88 + 256) % 256 }.map { it.toChar() }.joinToString("")
                    val m3u8Match = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(decoded)
                    if (m3u8Match != null) streamUrl = m3u8Match.value
                }
            }

            if (streamUrl == null) {
                println("BEYAZELMA_DEBUG: streamUrl bulunamadı")
                return false
            }
            println("BEYAZELMA_DEBUG: streamUrl=$streamUrl")

            // 6. /api/stream?src=BASE64 ise base64 decode et
            var finalUrl = streamUrl
            if (finalUrl.contains("/api/stream?src=")) {
                val srcParam = finalUrl.substringAfter("src=").substringBefore("&")
                try {
                    val decodedSrc = String(java.util.Base64.getDecoder().decode(srcParam))
                    finalUrl = decodedSrc
                } catch (e: Exception) {
                    // decode edilemezse /api/stream linkini kullan
                }
            }
            println("BEYAZELMA_DEBUG: finalUrl=$finalUrl")

            // 7. m3u8 linkini gönder
            callback(newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://beyazelma.xtrahut.xyz/"
                this.headers = mapOf(
                    "User-Agent" to userAgent,
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
