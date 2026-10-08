package com.safakstream.dizipal

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DizipalPlayer : ExtractorApi() {
    override var name = "DizipalPlayer"
    override var mainUrl = "dplayer82.site"
    override val requiresReferer = true

    private val TAG = "DiziPal"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text

        // 1. Altyazı linklerini çek
        val subUrls = mutableSetOf<String>()
        val subRegex = Regex("\"file\":\"((?:\\\\\\\\\\\"|[^\"])+)\",\"label\":\"((?:\\\\\\\\\\\"|[^\"])+)\"")
        subRegex.findAll(response).forEach { match ->
            val subUrlExt = match.groupValues[1]
            val subLangExt = match.groupValues[2]

            val subUrl = subUrlExt
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\", "")

            val subLang = subLangExt
                .replace("\\u0131", "ı")
                .replace("\\u0130", "İ")
                .replace("\\u00fc", "ü")
                .replace("\\u00e7", "ç")
                .replace("\\u011f", "ğ")
                .replace("\\u015f", "ş")

            if (!subUrls.contains(subUrl)) {
                subUrls.add(subUrl)
                subtitleCallback(newSubtitleFile(subLang, fixUrl(subUrl)))
            }
        }

        // 2. playlistId'yi bul
        val openPlayerRegex = Regex("""window\.openPlayer\s*\(\s*['"]([^'"]+)['"]""")
        val playlistId = openPlayerRegex.find(response)?.groupValues?.get(1)

        Log.d(TAG, "--> playlistId: $playlistId")

        if (playlistId.isNullOrEmpty()) return

        // 3. Domain'i bul
        val domainRegex = Regex("https?://[^/]+")
        val domain = domainRegex.find(url)?.value ?: "https://dplayer82.site"

        // 4. API isteği
        val apiUrl = "$domain/source2.php?v=$playlistId"
        Log.d(TAG, "--> apiUrl: $apiUrl")

        val apiResponse = try {
            app.get(apiUrl, referer = url).text
        } catch (e: Exception) {
            Log.e(TAG, "--> DPlayer Extractor Hata: ${e.message}")
            return
        }

        // 5. fileUrl'leri çek
        val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
        fileRegex.findAll(apiResponse).forEach { match ->
            var fileUrl = match.groupValues[1].replace("\\/", "/")

            fileUrl = when {
                fileUrl.startsWith("//") -> "https:$fileUrl"
                !fileUrl.startsWith("http") -> "https://$fileUrl"
                else -> fileUrl
            }

            if (fileUrl.contains("m.php")) {
                fileUrl = fileUrl.replace("m.php", "master.m3u8")
            }

            Log.d(TAG, "--> fileUrl: $fileUrl")

            callback(
                newExtractorLink(
                    source = this.name,
                    name = "DPlayer (Auto)",
                    url = fileUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$domain/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
