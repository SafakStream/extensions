package com.safak.inatbox

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class InatBox : MainAPI() {
    override var mainUrl = InatBoxCrypto.DEFAULT_CATEGORY_URL
    override var name = "InatBox"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()

    @Volatile
    private var catalogLoaded = false

    @Volatile
    private var cachedHomePageResponse: HomePageResponse? = null

    override val mainPage = mainPageOf(
        InatBoxCrypto.DEFAULT_CATEGORY_URL to "InatBox"
    )

    // ================================================================
    // ANA SAYFA
    // ================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        ensureCatalogLoaded()
        return cachedHomePageResponse
            ?: throw ErrorLoadingException("Kategorilere ulaşılamadı!")
    }

    private suspend fun ensureCatalogLoaded() {
        if (catalogLoaded && cachedHomePageResponse != null) return
        synchronized(this) {
            if (catalogLoaded && cachedHomePageResponse != null) return
        }
        loadCatalog()
    }

    private suspend fun loadCatalog() {
        val bootstrapDomain = try {
            InatBoxCrypto.fetchBootstrapDomain()
        } catch (e: Exception) {
            null
        }
        val targetCategoryUrl = bootstrapDomain?.dc2 ?: InatBoxCrypto.DEFAULT_CATEGORY_URL

        val decryptedCategoriesJson = try {
            makeInatRequest(targetCategoryUrl)
        } catch (e: Exception) {
            null
        } ?: return

        val allCategories: List<Kategoriler> = try {
            val mapper = jacksonObjectMapper()
            mapper.readValue(
                decryptedCategoriesJson,
                object : TypeReference<List<Kategoriler>>() {}
            )
        } catch (e: Exception) {
            return
        }

        val filteredCategories: List<Kategoriler> = allCategories.filter { kategori ->
            val catType = kategori.catType ?: ""
            val catName = kategori.catName ?: ""
            val catUrl = kategori.catUrl ?: ""

            if (catType == "link" || catType == "destek") return@filter false
            if (catName == "Hata Bildir" || catName == "Derbiler") return@filter false
            if (catUrl.contains("destek_mode") || catUrl.contains("inattv")) return@filter false
            if (catUrl.contains("x.com/")) return@filter false
            if (catName.contains("4k") || catUrl.contains("/4k/")) return@filter false
            if (catName.contains("Liste 3") || catUrl.contains("list3.php")) return@filter false

            catUrl.isNotBlank()
        }

        val homePageLists = mutableListOf<HomePageList>()
        for (kategori in filteredCategories) {
            val catUrl = kategori.catUrl ?: continue
            val catName = kategori.catName ?: continue

            val items = try {
                val response = makeInatRequest(catUrl) ?: continue
                getSearchResponseList(response, 30)
            } catch (e: Exception) {
                emptyList()
            }

            if (items.isEmpty()) continue

            homePageLists.add(
                HomePageList(
                    name = catName,
                    list = items,
                    isHorizontalImages = true
                )
            )
        }

        cachedHomePageResponse = newHomePageResponse(homePageLists)
        catalogLoaded = true
    }

    // ================================================================
    // ARAMA
    // ================================================================

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return emptyList()
        if (!catalogLoaded) ensureCatalogLoaded()

        val normalizedQuery = normalizeForSearch(trimmedQuery)

        return synchronized(urlToSearchResponse) {
            urlToSearchResponse.values
                .filter { sr ->
                    sr.name.contains(trimmedQuery, ignoreCase = true) ||
                    (normalizedQuery.isNotEmpty() &&
                     normalizeForSearch(sr.name).contains(normalizedQuery, ignoreCase = true))
                }
                .distinctBy { normalizeForSearch(it.name) }
        }
    }

    private fun getSearchResponseList(jsonResponse: String, maxItems: Int = -1): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)
            val limit = if (maxItems > 0) minOf(jsonArray.length(), maxItems) else jsonArray.length()

            for (i in 0 until limit) {
                try {
                    val item = jsonArray.getJSONObject(i)
                    if (!inatContentAllowed(item)) continue

                    val searchResponse = if (item.has("diziType")) {
                        val name = item.optString("diziName")
                        val type = item.optString("diziType")
                        val poster = item.optString("diziImg")
                        when {
                            type.contains("dizi", true) -> newTvSeriesSearchResponse(
                                name = name,
                                url = item.toString(),
                                type = TvType.TvSeries
                            ) { this.posterUrl = poster }

                            type.contains("film", true) -> newMovieSearchResponse(
                                name = name,
                                url = item.toString(),
                                type = TvType.Movie
                            ) { this.posterUrl = poster }

                            else -> null
                        }
                    } else if (item.has("chName") && item.has("chUrl")) {
                        val name = item.optString("chName")
                        val poster = item.optString("chImg")
                        val chType = item.optString("chType")

                        val isLive = chType.contains("live", true) ||
                                chType.contains("cable", true) ||
                                chType.contains("tekli_regex_lb_sh_3_s", true) ||
                                chType.contains("tekli_regex_lb_sh_3_u", true)

                        if (isLive) {
                            newLiveSearchResponse(
                                name = name,
                                url = item.toString(),
                                type = TvType.Live
                            ) { this.posterUrl = poster }
                        } else {
                            newMovieSearchResponse(
                                name = name,
                                url = item.toString(),
                                type = TvType.Movie
                            ) { this.posterUrl = poster }
                        }
                    } else null

                    if (searchResponse != null) {
                        results.add(searchResponse)
                        synchronized(urlToSearchResponse) {
                            urlToSearchResponse[searchResponse.url] = searchResponse
                        }
                    }
                } catch (e: Exception) {
                    // tek öğe hatalıysa atla
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return results
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type = if (item.has("diziType")) item.optString("diziType")
                   else item.optString("chType")

        if (type.contains("link", true) ||
            type.contains("destek", true) ||
            type.contains("yok", true) ||
            type.contains("web_basic", true)) return false

        val name = if (item.has("diziName")) item.optString("diziName")
                   else item.optString("chName")

        if (name.contains("inattv", true) ||
            name.contains("@", true) ||
            name.contains("Hata Bildir", true) ||
            name.contains("Telegram", true)) return false

        return true
    }

    // ================================================================
    // LOAD (detay)
    // ================================================================

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val item = JSONObject(url)
            if (!inatContentAllowed(item)) return null

            val chType = item.optString("chType")
            if (chType.contains("SsprDrm", true)) {
                return parseSSportResponse(item)
            }

            if (item.has("diziType")) {
                val type = item.optString("diziType")
                return when {
                    type.contains("dizi", true) -> parseTvSeriesResponse(item)
                    type.contains("film", true) -> parseMovieResponse(item)
                    else -> null
                }
            }

            if (item.has("chName")) {
                val chName = item.optString("chName")
                val chUrl = item.optString("chUrl")

                val isLive = chType.contains("live", true) || chType.contains("cable", true)
                if (isLive) return parseLiveStreamLoadResponse(item)

                val isSportsOrLive =
                    chUrl.contains("/4k/", true) ||
                    chType.contains("4k", true) ||
                    chType.contains("tekli_regex_lb_sh_3_s", true) ||
                    chType.contains("tekli_regex_lb_sh_3_u", true) ||
                    chName.contains("spor", true) ||
                    chName.contains("bein", true) ||
                    chName.contains("canlı", true) ||
                    chName.contains("tivibu", true) ||
                    chName.contains("smart spor", true) ||
                    chName.contains("tv", true) ||
                    chName.contains(" | ") ||
                    chName.contains("kanal", true)

                return if (isSportsOrLive) parseLiveSportsStreamLoadResponse(item)
                       else parseMovieResponse(item)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        return if (!item.has("diziType")) {
            val name = item.optString("chName")
            val poster = item.optString("chImg")
            newMovieLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.Movie,
                dataUrl = item.toString()
            ) { this.posterUrl = poster }
        } else {
            val name = item.optString("diziName")
            val diziUrl = item.optString("diziUrl")
            val poster = item.optString("diziImg")
            val plot = item.optString("diziDetay")

            makeInatRequest(diziUrl) ?: return null

            newMovieLoadResponse(
                name = name,
                url = item.toString(),
                type = TvType.Movie,
                dataUrl = item.toString()
            ) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    private suspend fun parseTvSeriesResponse(item: JSONObject): LoadResponse? {
        val name = item.optString("diziName")
        val diziUrl = item.optString("diziUrl")
        val posterUrl = item.optString("diziImg")
        val plot = item.optString("diziDetay")

        val jsonResponse = makeInatRequest(diziUrl) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        val firstItem = if (jsonArray.length() > 0) jsonArray.optJSONObject(0) else null
        val hasSeasons = firstItem?.let {
            (it.has("seasonUrl") || it.has("sezonUrl") || it.has("diziUrl")) &&
            !it.has("chUrl")
        } ?: false

        val seasonDataList = mutableListOf<SeasonData>()
        val episodeList = mutableListOf<Episode>()

        if (hasSeasons) {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.optString(
                    "sezonName",
                    seasonItem.optString(
                        "seasonName",
                        seasonItem.optString("diziName", "Sezon ${i + 1}")
                    )
                )
                val seasonUrl = seasonItem.optString(
                    "sezonUrl",
                    seasonItem.optString(
                        "seasonUrl",
                        seasonItem.optString("diziUrl")
                    )
                )
                val seasonImg = seasonItem.optString(
                    "seasonImg",
                    seasonItem.optString("diziImg", posterUrl)
                )

                seasonDataList.add(SeasonData(i + 1, seasonName))

                if (seasonUrl.isNotBlank()) {
                    val seasonResponse = makeInatRequest(seasonUrl) ?: continue
                    val seasonEpisodesArray = JSONArray(seasonResponse)
                    for (j in 0 until seasonEpisodesArray.length()) {
                        val epItem = seasonEpisodesArray.getJSONObject(j)
                        val epName = epItem.optString("chName", "Bölüm ${j + 1}")
                        val epImg = epItem.optString("chImg", seasonImg)
                        episodeList.add(newEpisode(epItem.toString()) {
                            this.name = epName
                            this.posterUrl = epImg
                            this.season = i + 1
                            this.episode = j + 1
                        })
                    }
                }
            }
        } else {
            for (i in 0 until jsonArray.length()) {
                val epItem = jsonArray.getJSONObject(i)
                val epName = epItem.optString("chName", "Bölüm ${i + 1}")
                val epImg = epItem.optString("chImg", posterUrl)
                episodeList.add(newEpisode(epItem.toString()) {
                    this.name = epName
                    this.posterUrl = epImg
                    this.season = 1
                    this.episode = i + 1
                })
            }
        }

        return newTvSeriesLoadResponse(
            name = name,
            url = item.toString(),
            type = TvType.TvSeries,
            episodes = episodeList
        ) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.seasonNames = seasonDataList
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LoadResponse? {
        val chContent = parseToChContent(item)
        if (chContent.chType.contains("inattvapk", true)) return null

        return newLiveStreamLoadResponse(
            name = chContent.chName,
            url = item.toString(),
            dataUrl = item.toString()
        )
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LoadResponse? {
        val chContent = parseToChContent(item)
        return newLiveStreamLoadResponse(
            name = chContent.chName,
            url = item.toString(),
            dataUrl = item.toString()
        )
    }

    private suspend fun parseSSportResponse(item: JSONObject): LoadResponse? {
        return try {
            val response = app.get(
                "https://sprspr.help/CDN/SSP/bir-p-no-cron.php",
                headers = mapOf(
                    "user-agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
                    "x-requested-with" to "XMLHttpRequest"
                )
            )
            if (!response.isSuccessful) return null

            val sSportData = jacksonObjectMapper()
                .readValue(response.text, SSportResponse::class.java)

            val firstCategory = sSportData.categories?.firstOrNull() ?: return null
            val contents = firstCategory.contents ?: return null

            val episodes = contents.mapIndexed { index, content ->
                val data = content.id?.toString() ?: index.toString()
                val title = content.title ?: "Etkinlik ${index + 1}"
                val description = content.description ?: content.tags ?: ""
                val poster = content.medias
                    ?.firstOrNull { it.type == 0x54 }
                    ?.url

                newEpisode(data) {
                    this.name = title
                    this.description = description
                    this.episode = index + 1
                    this.posterUrl = poster
                }
            }

            newTvSeriesLoadResponse(
                name = "S Sport Plus",
                url = item.toString(),
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = contents.firstOrNull()?.medias
                    ?.firstOrNull { it.type == 0x54 }?.url
            }
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // LOAD LINKS
    // ================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val trimmed = data.trim()
            when {
                trimmed.startsWith("[") -> {
                    val jsonArray = JSONArray(trimmed)
                    for (i in 0 until jsonArray.length()) {
                        val chContent = parseToChContent(jsonArray.getJSONObject(i))
                        loadChContentLinks(chContent, subtitleCallback, callback)
                    }
                    true
                }
                trimmed.startsWith("{") -> {
                    val chContent = parseToChContent(JSONObject(trimmed))
                    loadChContentLinks(chContent, subtitleCallback, callback)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadChContentLinks(
        chContent: ChContent,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mutableMapOf<String, String>()

        if (chContent.chHeaders.isNotBlank() && chContent.chHeaders != "null") {
            try {
                val jsonHeaders = JSONArray(chContent.chHeaders).getJSONObject(0)
                if (jsonHeaders.has("UserAgent")) headers["User-Agent"] = jsonHeaders.getString("UserAgent")
                if (jsonHeaders.has("User-Agent")) headers["User-Agent"] = jsonHeaders.getString("User-Agent")
                if (jsonHeaders.has("Referer")) headers["Referer"] = jsonHeaders.getString("Referer")
                if (jsonHeaders.has("XRequestedWith")) headers["X-Requested-With"] = jsonHeaders.getString("XRequestedWith")
                if (jsonHeaders.has("X-Requested-With")) headers["X-Requested-With"] = jsonHeaders.getString("X-Requested-With")

                val keys = jsonHeaders.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!headers.containsKey(key)) {
                        headers[key] = jsonHeaders.optString(key)
                    }
                }
            } catch (e: Exception) {
                // header parse hatası
            }
        }

        var regex1 = ""
        var regex2 = ""
        var regex2p: String? = null
        if (chContent.chReg.isNotBlank() && chContent.chReg != "null") {
            try {
                val jsonReg = JSONArray(chContent.chReg).getJSONObject(0)
                regex1 = jsonReg.optString("Regex1", "")
                regex2 = jsonReg.optString("Regex2", "")
                if (jsonReg.has("Regex2p")) regex2p = jsonReg.optString("Regex2p")
                if (jsonReg.has("playSH2")) headers["Cookie"] = jsonReg.optString("playSH2")
            } catch (e: Exception) {
                // reg parse hatası
            }
        }

        when {
            chContent.chType.contains("tekli_regex_lb_sh", true) -> {
                handleTekliRegex(chContent, headers, regex1, regex2, regex2p, subtitleCallback, callback)
            }
            chContent.chType.contains("tekli_regex_mode", true) ||
            chContent.chType.contains("tekli_regex_no_sh", true) -> {
                handleTekliRegexMode(chContent, headers, regex1, regex2p, subtitleCallback, callback)
            }
            else -> {
                handleFallback(chContent, headers, subtitleCallback, callback)
            }
        }
    }

    private suspend fun handleTekliRegex(
        chContent: ChContent,
        headers: Map<String, String>,
        regex1: String,
        regex2: String,
        regex2p: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val signedHeaders = InatBoxCrypto.getSignedHeaders(chContent.chUrl, "GET", "").toMutableMap()
        headers.forEach { (k, v) -> signedHeaders[k] = v }

        val response = try {
            app.get(chContent.chUrl, headers = signedHeaders)
        } catch (e: Exception) {
            return
        }
        if (!response.isSuccessful) return

        val encryptedText = response.text.trim()
        val decryptedJson = try {
            InatBoxCrypto.decryptChannelStream(encryptedText, regex1, regex2, regex2p)
        } catch (e: Exception) {
            return
        }
        val streamJson = JSONObject(decryptedJson)
        val streamUrl = streamJson.optString("chUrl", "")
        if (streamUrl.isBlank()) return

        val streamHeaders = signedHeaders.toMutableMap()
        if (streamJson.has("playSH2")) {
            streamHeaders["Cookie"] = streamJson.optString("playSH2")
        }

        val linkType = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8
                       else ExtractorLinkType.VIDEO

        callback(newExtractorLink(
            source = this.name,
            name = chContent.chName,
            url = streamUrl,
            type = linkType
        ) {
            this.referer = chContent.chUrl
            this.headers = streamHeaders
        })
    }

    private suspend fun handleTekliRegexMode(
        chContent: ChContent,
        headers: Map<String, String>,
        regex1: String,
        regex2p: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = try {
            app.get(chContent.chUrl, headers = headers)
        } catch (e: Exception) {
            return
        }
        if (!response.isSuccessful) return

        val responseText = response.text.trim()
        val streamUrl = if (regex1.isNotBlank()) {
            try {
                val match = Regex(regex1).find(responseText)
                (match?.groups?.get(1)?.value ?: match?.value ?: responseText)
                    .replace("\\/", "/")
            } catch (e: Exception) {
                responseText
            }
        } else responseText

        if (streamUrl.isBlank() || !streamUrl.startsWith("http")) return

        if (isDirectStream(streamUrl)) {
            val linkType = if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO
            callback(newExtractorLink(
                source = this.name,
                name = chContent.chName,
                url = streamUrl,
                type = linkType
            ) {
                this.referer = chContent.chUrl
                this.headers = headers
            })
        } else {
            loadExtractor(streamUrl, chContent.chUrl, subtitleCallback, callback)
        }
    }

    private suspend fun handleFallback(
        chContent: ChContent,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = chContent.chUrl
        if (isDirectStream(finalUrl)) {
            val linkType = when {
                finalUrl.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                finalUrl.contains(".mpd", true)  -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }
            callback(newExtractorLink(
                source = this.name,
                name = chContent.chName,
                url = finalUrl,
                type = linkType
            ) {
                this.referer = chContent.chUrl
                this.headers = headers
            })
        } else {
            loadExtractor(finalUrl, headers["Referer"] ?: "", subtitleCallback, callback)
        }
    }

    // ================================================================
    // HTTP (şifreli POST)
    // ================================================================

    private suspend fun makeInatRequest(url: String): String? {
        return try {
            val randomKey = InatBoxCrypto.getRandomAlphaNumeric(16)
            val body = "1=$randomKey&0=$randomKey"
            val headers = InatBoxCrypto.getSignedHeaders(url, "POST", body)

            val response = app.post(
                url = url,
                headers = headers,
                data = body
            )

            if (!response.isSuccessful) return null

            val responseText = response.text.trim()
            InatBoxCrypto.decryptDoubleAesCbc(responseText, randomKey)
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================
    // Yardımcılar
    // ================================================================

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.optString("chName"),
            chUrl = vkSourceFix(item.optString("chUrl")),
            chImg = item.optString("chImg"),
            chHeaders = item.optString("chHeaders"),
            chReg = item.optString("chReg"),
            chType = item.optString("chType")
        )
    }

    private fun vkSourceFix(url: String): String {
        return if (url.startsWith("act", ignoreCase = true)) {
            "https://vk.com/al_video.php?$url"
        } else url
    }

    private fun isDirectStream(url: String): Boolean {
        return url.contains(".m3u8", true) ||
               url.contains(".mpd", true) ||
               url.contains(".mp4", true) ||
               url.contains(".webm", true)
    }

    private fun normalizeForSearch(text: String): String {
        return text.lowercase()
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ş", "s")
            .replace("ö", "o")
            .replace("ç", "c")
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
    }
}
