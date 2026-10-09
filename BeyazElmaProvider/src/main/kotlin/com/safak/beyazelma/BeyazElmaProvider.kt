package com.safak.beyazelma

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class BeyazElmaProvider : MainAPI() {
    override var mainUrl = "https://beyazelma78.com/"
    override var name = "BeyazElma"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

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
        val html = response.text

        println("BEYAZELMA_DEBUG: Status=${response.code}, HTML size=${html.length}")

        // Next.js JSON verisinden kanalları çıkar
        val channels = parseChannelsFromNextJs(html)
        println("BEYAZELMA_DEBUG: Bulunan kanal sayısı=${channels.size}")

        return newHomePageResponse(request.name, channels)
    }

    private fun parseChannelsFromNextJs(html: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Next.js RSC payload'larını bul: self.__next_f.push([1,"..."])
        val regex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")

        for (match in regex.findAll(html)) {
            val rawChunk = match.groupValues[1]
            // Escape karakterlerini çöz
            val chunk = rawChunk
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")

            // "initialChannels":[ {...} ] ara
            val idx = chunk.indexOf("\"initialChannels\":")
            if (idx == -1) continue

            // JSON array başlangıcını bul
            val arrayStart = chunk.indexOf('[', idx)
            if (arrayStart == -1) continue

            // Dengeli parantez ile array sonunu bul
            val arrayStr = extractBalanced(chunk, arrayStart, '[', ']') ?: continue

            try {
                val jsonArray = JSONArray(arrayStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    val slug = obj.optString("slug")
                    val logo = obj.optString("logo")

                    if (name.isBlank() || slug.isBlank()) continue

                    val fullUrl = "$mainUrl/kanal/$slug"
                    val fullLogo = if (logo.startsWith("http")) logo else "$mainUrl$logo"

                    results.add(newLiveSearchResponse(name, fullUrl, TvType.Live) {
                        this.posterUrl = fullLogo
                    })
                }
                // İlk bulduğumuz yeterli
                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                println("BEYAZELMA_DEBUG: JSON parse hatası: ${e.message}")
            }
        }

        // Eğer RSC'den bulamazsak, normal HTML selector'ı dene (yedek)
        if (results.isEmpty()) {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("a.site-channel-row").forEach { el ->
                val href = el.attr("href")
                val name = el.selectFirst("span.site-channel-row-name")?.text() ?: return@forEach
                val logoPath = el.selectFirst("span.site-channel-row-logo img")?.attr("src") ?: ""
                if (href.isBlank()) return@forEach

                val fullUrl = if (href.startsWith("http")) href else mainUrl.trimEnd('/') + href
                val fullLogo = if (logoPath.startsWith("http")) logoPath else mainUrl.trimEnd('/') + logoPath

                results.add(newLiveSearchResponse(name, fullUrl, TvType.Live) {
                    this.posterUrl = fullLogo
                })
            }
        }

        return results
    }

    // Dengeli parantez çıkarıcı
    private fun extractBalanced(s: String, start: Int, open: Char, close: Char): String? {
        if (start >= s.length || s[start] != open) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/kanallar", headers = defaultHeaders)
        return parseChannelsFromNextJs(response.text)
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
