package com.safak.netvgold

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.Locale

class NetVGoldProvider : MainAPI() {
    override var mainUrl = "https://github.com/Wiojelt/TurkSpor/"
    override var name = "NETV Gold Spor"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val CATALOGUE_URL =
            "https://raw.githubusercontent.com/Wiojelt/TurkSpor/main/catalogs/netvgold.json"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/139 Mobile Safari/537.36"

        private val FALLBACK_JSON = """
        [
          {"id":"ssporred1","title":"S Sport 1","url":"https://raw.githubusercontent.com/icebu12/turbo-guacamole/refs/heads/main/streams/stream_ss.m3u8","referer":"https://taraftarium1081.xyz/"},
          {"id":"trtspor","title":"TRT Spor","url":"https://tv-trtspor1.medya.trt.com.tr/master.m3u8","referer":"https://www.trtspor.com.tr/"}
        ]
        """.trimIndent()

        private const val CACHE_TTL_MS = 10L * 60L * 1000L
    }

    private var cached: List<NetVChannel> = emptyList()
    private var cachedAt: Long = 0L

    override val mainPage = mainPageOf(
        "all" to "Spor Kanalları"
    )

    private suspend fun channels(force: Boolean = false): List<NetVChannel> {
        if (!force && cached.isNotEmpty() &&
            System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
        ) {
            return cached
        }

        val text = try {
            app.get(
                CATALOGUE_URL,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            FALLBACK_JSON
        }

        val parsed: List<NetVChannel> = try {
            mapper.readValue(text, Array<NetVChannel>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }

        val cleaned = parsed
            .filter { ch ->
                ch.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
                ch.title.isNotBlank() &&
                ch.url.startsWith("https://") &&
                ch.referer.startsWith("https://")
            }
            .distinctBy { it.id }

        if (cleaned.isEmpty()) {
            throw ErrorLoadingException("NETV Gold spor kataloğu alınamadı.")
        }

        cached = cleaned
        cachedAt = System.currentTimeMillis()
        return cached
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val list = channels(force = page > 1)

        val sections = list
            .groupBy { it.title.split(" ").firstOrNull().orEmpty().ifBlank { "Diğer" } }
            .map { (groupName, groupList) ->
                HomePageList(
                    name = groupName,
                    list = groupList.map { it.toSearchResponse() },
                    isHorizontalImages = true
                )
            }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val term = query.lowercase(Locale("tr"))
        return channels()
            .filter { it.title.lowercase(Locale("tr")).contains(term) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = extractId(url)
            ?: throw ErrorLoadingException("Kanal kimliği eksik.")

        val channel = channels().firstOrNull { it.id == id }
            ?: throw ErrorLoadingException("Kanal güncel NETV Gold listesinde bulunamadı.")

        return newLiveStreamLoadResponse(
            name = channel.title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = channel.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = extractId(data) ?: return false
        val channel = channels().firstOrNull { it.id == id } ?: return false

        val sources = buildList {
            add(NetVSource(name = "Ana", url = channel.url, referer = channel.referer))
            addAll(channel.sources)
        }
            .filter { it.url.startsWith("https://") && it.referer.startsWith("https://") }
            .distinctBy { it.url }

        if (sources.isEmpty()) {
            throw ErrorLoadingException("NETV Gold kaynakları şu anda cevap vermiyor.")
        }

        sources.forEach { src ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${channel.title} · ${src.name}",
                    url = src.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = src.referer
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to src.referer
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    private fun extractId(url: String): String? =
        Regex("[?&]netvgold=([^&]+)").find(url)?.groupValues?.getOrNull(1)

    private fun NetVChannel.pageUrl(): String =
        "$mainUrl?netvgold=${URLEncoder.encode(this.id, "UTF-8")}"

    private fun NetVChannel.toSearchResponse(): LiveSearchResponse {
        return this@NetVGoldProvider.newLiveSearchResponse(
            name = this.title,
            url = this.pageUrl(),
            type = TvType.Live
        ) {
            this.posterUrl = this@toSearchResponse.logo
        }
    }
}

data class NetVChannel(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("title") val title: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("referer") val referer: String = "",
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("sources") val sources: List<NetVSource> = emptyList()
)

data class NetVSource(
    @JsonProperty("name") val name: String = "Kaynak",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("referer") val referer: String = ""
)
