package com.safak.beyazelma

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.lazy
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONObject

class BeyazElmaProvider : MainAPI() {
    override var mainUrl = "https://beyazelma78.com/"
    override var name = "BeyazElma"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Live)

    // Chrome Android UA (cs3'teki ile aynı)
    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    // CloudflareKiller — işte sır bu!
    private val cfKiller: CloudflareKiller by lazy { CloudflareKiller() }

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "kanallar" to "Canlı Kanallar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // CloudflareKiller ile istek at
        val response = app.get(
            "$mainUrl/kanallar",
            headers = defaultHeaders,
            interceptor = cfKiller
        )
        val document = response.document
        val list = document.select("a.site-channel-row").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
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
        val response = app.get(
            "$mainUrl/kanallar",
            headers = defaultHeaders,
            interceptor = cfKiller
        )
        return response.document.select("a.site-channel-row")
            .mapNotNull { it.toSearchResult() }
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
            // 1. Kanal sayfasını çek (CloudflareKiller ile)
            val document = app.get(data, headers = defaultHeaders, interceptor = cfKiller).document

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
            if (embedUrl == null) return false

            // 3. Embed sayfasını çek
            val embedDoc = app.get(embedUrl, headers = defaultHeaders, interceptor = cfKiller).document
            val html = embedDoc.html()

            // 4. StreamPlayer.mount regex'i ile slug'ı al
            val slugMatch = Regex("""StreamPlayer\.mount\([^,]+,\s*\{[^}]*["']slug["']\s*:\s*["']([^"']+)["']""").find(html)
            val slug = slugMatch?.groupValues?.get(1)

            if (slug != null) {
                // 5. PlayOrigin'u bul
                val embedOrigin = try {
                    java.net.URI(embedUrl).let { "${it.scheme}://${it.authority}" }
                } catch (e: Exception) {
                    "https://embed.beyazelma78.com"
                }

                // 6. PlayUrl'e istek at
                val playUrl = "$embedOrigin/api/play/$slug"
                val playResponse = app.get(
                    playUrl,
                    headers = defaultHeaders + mapOf("Referer" to "$embedOrigin/", "Origin" to embedOrigin),
                    interceptor = cfKiller
                )

                if (playResponse.code == 200) {
                    val playJson = JSONObject(playResponse.text)

                    // 7. Primary m3u8 linkini al
                    val primary = playJson.optString("primary", "")
                    if (primary.isNotBlank()) {
                        val primaryWithM3u8 = when {
                            primary.contains(".m3u8") -> primary
                            primary.contains("?") -> "$primary&format=.m3u8"
                            else -> "$primary?format=.m3u8"
                        }
                        callback(newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = primaryWithM3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = embedUrl
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to embedUrl,
                                "Origin" to embedOrigin
                            )
                        })
                    }

                    // 8. Fallbacks listesini al
                    val fallbacks = playJson.optJSONArray("fallbacks")
                    if (fallbacks != null) {
                        for (i in 0 until fallbacks.length()) {
                            val fb = fallbacks.optString(i)
                            if (fb.isNotBlank()) {
                                val fbWithM3u8 = when {
                                    fb.contains(".m3u8") -> fb
                                    fb.contains("?") -> "$fb&format=.m3u8"
                                    else -> "$fb?format=.m3u8"
                                }
                                callback(newExtractorLink(
                                    source = this.name,
                                    name = "${this.name} (Yedek $i)",
                                    url = fbWithM3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = embedUrl
                                    this.headers = mapOf(
                                        "User-Agent" to userAgent,
                                        "Referer" to embedUrl,
                                        "Origin" to embedOrigin
                                    )
                                })
                            }
                        }
                    }

                    return true
                }
            }

            // 9. Alternatif: /api/stream?src=BASE64 linkini ara
            var streamUrl: String? = null
            val streamMatch = Regex("""["'](/api/stream\?src=[^"']+)["']""").find(html)
            if (streamMatch != null) {
                streamUrl = "https://beyazelma.xtrahut.xyz" + streamMatch.groupValues[1]
            }

            if (streamUrl != null) {
                callback(newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embedUrl
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to embedUrl,
                        "Origin" to embedOrigin
                    )
                })
                return true
            }

            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
