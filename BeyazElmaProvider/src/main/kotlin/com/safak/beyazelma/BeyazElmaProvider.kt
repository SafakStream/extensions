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
            // 1. Kanal sayfasını çek
            val document = app.get(data).document

            // 2. /api/embed linkini bul
            var embedUrl: String? = null

            // link rel=preload içinde ara
            document.select("link[rel=preload]").forEach { link ->
                val href = link.attr("href")
                if (href.contains("/api/embed")) {
                    embedUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + href
                }
            }

            // iframe içinde ara
            if (embedUrl == null) {
                document.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.contains("/api/embed")) {
                        embedUrl = if (src.startsWith("http")) src else mainUrl.trimEnd('/') + src
                    }
                }
            }

            // Tüm sayfada regex ile ara
            if (embedUrl == null) {
                val match = Regex("""/api/embed\?u=[^"'\s&]+""").find(document.html())
                if (match != null) {
                    embedUrl = mainUrl.trimEnd('/') + match.value
                }
            }

            if (embedUrl == null) return false

            // 3. Embed sayfasını çek
            val embedDoc = app.get(embedUrl).document
            val html = embedDoc.html()

            // 4. Şifreli JS bloğunu bul: _if5=[...]
            val arrayMatch = Regex("""_if5=\[([0-9,]+)\]""").find(html)
            if (arrayMatch == null) {
                // Alternatif: _ue0=[...] bloğunu dene
                val altMatch = Regex("""_ue0=\[([0-9,]+)\]""").find(html)
                if (altMatch == null) return false
                val numbers = altMatch.groupValues[1].split(",").map { it.trim().toInt() }
                // _ue0 için: key=105, shift=194
                val decoded = numbers.map {
                    ((it xor 105) - 194 + 256) % 256
                }.map { it.toChar() }.joinToString("")
                return extractAndSend(decoded, callback)
            }

            val numbers = arrayMatch.groupValues[1].split(",").map { it.trim().toInt() }

            // 5. Şifre çözme: ((byte XOR 47) - 88 + 256) % 256
            val decoded = numbers.map {
                ((it xor 47) - 88 + 256) % 256
            }.map { it.toChar() }.joinToString("")

            // 6. decoded içinde m3u8 linkini ara
            return extractAndSend(decoded, callback)

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractAndSend(decoded: String, callback: (ExtractorLink) -> Unit): Boolean {
        // m3u8 linkini ara
        val m3u8Match = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(decoded)
        if (m3u8Match == null) {
            // Alternatif: file: "..." içinde ara
            val fileMatch = Regex("""file:\s*["']([^"']+)["']""").find(decoded)
            if (fileMatch == null) return false
            val url = fileMatch.groupValues[1]
            sendLink(url, callback)
            return true
        }

        var m3u8Url = m3u8Match.value

        // Eğer link /api/stream?src=... şeklindeyse, src parametresini base64 decode et
        if (m3u8Url.contains("/api/stream?src=")) {
            val srcParam = m3u8Url.substringAfter("src=").substringBefore("&")
            try {
                val decodedSrc = String(android.util.Base64.decode(srcParam, android.util.Base64.DEFAULT))
                m3u8Url = decodedSrc
            } catch (e: Exception) {
                // base64 değilse olduğu gibi bırak
            }
        }

        sendLink(m3u8Url, callback)
        return true
    }

    private fun sendLink(url: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(
            source = this.name,
            name = this.name,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://beyazelma.xtrahut.xyz/"
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
                "Referer" to "https://beyazelma.xtrahut.xyz/",
                "Origin" to "https://beyazelma.xtrahut.xyz"
            )
        })
    }
}
