package com.safakstream.dizipal

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal1587.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private val passphrase = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"

    private val TAG = "DiziPal"

    inner class CloudflareInterceptor(private val killer: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return runBlocking {
                killer.intercept(chain)
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Yeni Diziler",
        "$mainUrl/hd-film-izle" to "Yeni Filmler",
        "$mainUrl/kanal/netflix" to "Netflix",
        "$mainUrl/kanal/exxen" to "Exxen",
        "$mainUrl/kanal/max" to "Max",
        "$mainUrl/kanal/disney" to "Disney+",
        "$mainUrl/kanal/amazon" to "Amazon Prime",
        "$mainUrl/kanal/tod" to "TOD (beIN)",
        "$mainUrl/kanal/tabii" to "Tabii",
        "$mainUrl/kanal/hulu" to "Hulu",
    )

    private fun getHeaders(baseUrl: String): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to baseUrl
    )

    // ---- Yardımcı: Ana sayfa kartları ----

    private fun diziler(element: Element): SearchResponse? {
        val title = element.selectFirst("img")?.attr("alt") ?: return null
        val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun sonBolumler(element: Element): SearchResponse? {
        val name = element.selectFirst("img")?.attr("alt") ?: return null
        val episode = element.selectFirst("div.episode")?.text()?.trim()
            ?.replace(". Sezon ", "x")
            ?.replace(". Bölüm", "")
            ?: ""

        val title = if (episode.isNotEmpty()) "$name $episode" else name

        val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun toPostSearchResult(item: SearchItem): SearchResponse {
        val title = item.title
        val href = "$mainUrl/${item.slug}"
        val posterUrl = item.poster

        return if (item.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ---- Ana sayfa ----

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.contains("/kanal/")) {
            if (request.data.contains("?")) "${request.data}&sayfa=$page"
            else "${request.data}?sayfa=$page"
        } else request.data

        val document = app.get(
            url,
            headers = getHeaders(mainUrl),
            interceptor = interceptor
        ).document

        val home = mutableListOf<SearchResponse>()

        if (!request.data.contains("/kanal/") || page == 1) {
            if (request.data.contains("/yabanci-dizi-izle") || request.data.contains("/hd-film-izle")) {
                home.addAll(
                    document.select("div.new-added-list div.bg-\\[\\#22232a\\]")
                        .mapNotNull { sonBolumler(it) }
                )
            } else {
                home.addAll(
                    document.select("div.bg-\\[\\#22232a\\]")
                        .mapNotNull { diziler(it) }
                )
            }
        }

        // Kanal sayfası ise AJAX ile içerik çek
        if (request.data.contains("/kanal/")) {
            val channelIdFromDoc = document.selectFirst("input[name=channelId]")?.attr("value")
                ?: Regex("channelId\\s*[:=]\\s*(\\d+)")
                    .find(document.html())?.groupValues?.get(1)

            val channelSlug = request.data.substringAfterLast("/")

            try {
                val apiResponse = app.post(
                    "$mainUrl/bg/getserielistbychannel",
                    headers = getHeaders(mainUrl) + mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf(
                        "cKey" to "c61f91c5141d178450934fe81c0a2029",
                        "cValue" to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                        "curPage" to page.toString(),
                        "channelId" to (channelIdFromDoc ?: "1"),
                        "languageId" to "2,3,4",
                        "slug" to channelSlug
                    )
                ).text

                val mapper = jacksonObjectMapper()
                val rootNode = mapper.readTree(apiResponse)
                val htmlNode = rootNode.at("/data/html")

                if (!htmlNode.isMissingNode) {
                    val parsedDoc = org.jsoup.Jsoup.parse(htmlNode.asText())
                    val apiResults = parsedDoc.select("div.bg-\\[\\#22232a\\]")
                        .mapNotNull { diziler(it) }

                    apiResults.forEach { res ->
                        if (home.none { it.url == res.url }) {
                            home.add(res)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API Hatası: ${e.message}")
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // ---- Arama ----

    override suspend fun search(query: String): List<SearchResponse>? {
        val responseRaw = app.post(
            "$mainUrl/bg/searchcontent",
            headers = getHeaders(mainUrl) + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            data = mapOf(
                "cKey" to "c61f91c5141d178450934fe81c0a2029",
                "cValue" to "MTc4NDQwNzIwMDhkMzJhNTc1YzUwOGU1ZjQwMjdjMjIyOWVjOGVhMTcwNGQyM2FjODM2YTI4YTU0NjUyMjI2ZmVjMzFkYzBkMWQyMWY4YzdiNA==",
                "type" to "hepsi",
                "searchterm" to query
            )
        ).text

        val mapper = jacksonObjectMapper()
        val rootNode = mapper.readTree(responseRaw)
        val resultArrayNode = rootNode.at("/data/result")

        if (resultArrayNode.isMissingNode || !resultArrayNode.isArray) {
            return emptyList()
        }

        val searchItems: List<SearchItem> = mapper.readValue(resultArrayNode.traverse())
        return searchItems.map { toPostSearchResult(it) }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    // ---- Detay yükleme ----

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = getHeaders(mainUrl),
            interceptor = interceptor
        ).document

        val poster = document.selectFirst("div.page-top img[alt]")?.attr("src")
        val year = document.selectXpath("//div[text()='Yıl']//following-sibling::div").text()
            .trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags = document.selectXpath("//div[text()='Kategoriler']//following-sibling::div").text()
            .trim().split(" ").map { it.trim() }
        val duration = Regex("(\\d+)")
            .find(document.selectXpath("//div[text()='Süre']//following-sibling::div").text())
            ?.value?.toIntOrNull()

        if (url.contains("/series/")) {
            val title = document.selectFirst("div.flex h2")?.text() ?: return null

            val episodes = document.select("div.relative.w-full.flex.items-start.gap-4").mapNotNull { element ->
                val linkElement = element.selectFirst("a[data-dizipal-pageloader]") ?: return@mapNotNull null
                val epHref = fixUrlNull(linkElement.attr("href")) ?: return@mapNotNull null

                val epName = linkElement.selectFirst("h2")?.text()?.trim() ?: "Bölüm"
                val infoText = linkElement.selectFirst("div.text-white.text-sm.opacity-80")?.text()?.trim() ?: ""

                val epSeason = Regex("(\\d+)\\.\\s*Sezon").find(infoText)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("(\\d+)\\.\\s*Bölüm").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epEpisode
                    this.season = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        } else {
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.duration = duration
            }
        }
    }

    // ---- Video linkleri ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "--> loadLinks ÇAĞRILDI. Gelen URL: $data")

        val doc = app.get(data, headers = getHeaders(mainUrl), interceptor = interceptor).document

        val encryptedText = doc.selectFirst("div[data-rm-k=true]")?.text() ?: ""
        Log.d(TAG, "--> Şifreli metin uzunluğu: ${encryptedText.length}")

        val iframeUrl = if (encryptedText.isNotEmpty()) {
            Log.d(TAG, "--> Şifreli veri bulundu, decrypt işlemine geçiliyor...")
            decryptDizipalData(encryptedText)
        } else {
            Log.w(TAG, "--> DİKKAT: Şifreli veri DOM'da YOK! Fallback iframe aranıyor...")
            doc.selectFirst("iframe")?.attr("src") ?: ""
        }

        Log.d(TAG, "--> Elde edilen Ham Iframe URL: $iframeUrl")

        if (iframeUrl.isEmpty()) {
            Log.e(TAG, "--> HATA: iframeUrl tamamen BOŞ. Video linki bulunamadı!")
            return true
        }

        val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
        Log.d(TAG, "--> Extractor'a gönderilen Final URL: $finalUrl")

        DizipalPlayer().getUrl(finalUrl, data, subtitleCallback, callback)
        return true
    }

    // ---- AES decrypt ----

    private fun decodeHex(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string çift uzunlukta olmalıdır" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun decryptDizipalData(rawJsonText: String): String {
        try {
            val ciphertextRegex = Regex("\"ciphertext\"\\s*:\\s*\"([^\"]+)\"")
            val ivRegex = Regex("\"iv\"\\s*:\\s*\"([^\"]+)\"")
            val saltRegex = Regex("\"salt\"\\s*:\\s*\"([^\"]+)\"")

            val ctMatch = ciphertextRegex.find(rawJsonText)?.groupValues?.get(1)?.also {
                Log.d(TAG, "--> Regex başarılı. Key türetiliyor...")
            } ?: run {
                Log.e(TAG, "--> HATA: Regex 'ciphertext' değerini bulamadı!")
                return ""
            }

            val ivMatch = ivRegex.find(rawJsonText)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "--> HATA: Regex 'iv' değerini bulamadı!")
                return ""
            }

            val saltMatch = saltRegex.find(rawJsonText)?.groupValues?.get(1) ?: run {
                Log.e(TAG, "--> HATA: Regex 'salt' değerini bulamadı!")
                return ""
            }

            val salt = decodeHex(saltMatch)
            val iv = decodeHex(ivMatch)
            val ciphertext = Base64.decode(ctMatch, Base64.DEFAULT)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val secretKey = factory.generateSecret(spec)
            val secret = SecretKeySpec(secretKey.encoded, "AES")

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)

            val rawUrl = String(decryptedBytes, Charsets.UTF_8).replace("\\/", "/")
            Log.d(TAG, "--> AES Çözümleme Başarılı. İlk Çıktı: $rawUrl")

            val finalUrl = when {
                rawUrl.startsWith("://") -> "https$rawUrl"
                rawUrl.startsWith("//") -> "https:$rawUrl"
                !rawUrl.startsWith("http") -> "https://$rawUrl"
                else -> rawUrl
            }

            return finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "--> HATA: Decryption sırasında Exception fırlatıldı! Mesaj: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }
}
