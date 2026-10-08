package com.safak.inatbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * InatBox şifreleme motoru.
 * Kaynak referans: wiojelt/turksinema vendor inatbox_wiojelt (decompile edilmiş .cs3)
 */
object InatBoxCrypto {

    const val HMK_KEY = "x7kkk0qmqz63kj68tla5i7u26192v7zqnnddhjgm"
    const val BOOTSTRAP_AES_KEY = "a4osa8x1yl4w3vrk"
    const val CERTIFICATE_URL = "https://raw.githubusercontent.com/cencbit/ssl/main/certificate.pem"
    const val DEFAULT_CATEGORY_URL = "https://dizilabmedia.click/CDN/001/002/dizilab/v2/ct.php"
    const val DEFAULT_CONFIG_URL = "https://dizilabmedia.click/CDN/001/002/dizilab/v2/cf.php"

    private val secureRandom = SecureRandom()
    private const val ALPHA_NUMERIC = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val HEX_CHARS = "0123456789abcdef"

    private val mapper: ObjectMapper = jacksonObjectMapper()

    // ----------------------------------------------------------------
    // Rastgele üreticiler
    // ----------------------------------------------------------------

    fun getRandomAlphaNumeric(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(ALPHA_NUMERIC[secureRandom.nextInt(ALPHA_NUMERIC.length)])
        }
        return sb.toString()
    }

    fun getRandomHex(byteLength: Int): String {
        val sb = StringBuilder(byteLength * 2)
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0xf])
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------
    // Hash & HMAC
    // ----------------------------------------------------------------

    fun sha256Hex(data: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        val sb = StringBuilder(hash.size * 2)
        for (b in hash) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0xf])
        }
        return sb.toString()
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun hmacSha256Hex(key: String, message: String): String {
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0xf])
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------
    // AES-CBC şifre çözme
    // ----------------------------------------------------------------

    fun decryptAesCbc(cipherTextBase64: String, ivBase64: String, keyText: String): String {
        val cipherBytes = Base64.getDecoder().decode(cipherTextBase64.trim())
        val ivBytes = Base64.getDecoder().decode(ivBase64.trim())
        // DİKKAT: ISO_8859_1 kullanılıyor (UTF_8 değil)
        val keyBytes = keyText.toByteArray(Charsets.ISO_8859_1)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes)
        )
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    /**
     * Format: "cipherB64:ivB64" veya "cipherB64:ivB64:cipherB64:ivB64"
     */
    fun decryptDoubleAesCbc(encryptedPayload: String, keyText: String): String {
        val cleaned = encryptedPayload.trim()
        val firstSplit = cleaned.split(":")
        val intermediate = decryptAesCbc(firstSplit[0], firstSplit[1], keyText)
        if (!intermediate.contains(":")) return intermediate

        val secondSplit = intermediate.split(":")
        val secondDecrypted = decryptAesCbc(secondSplit[0], secondSplit[1], keyText)

        // Son 64 karakter HMAC (hex) ise at
        if (secondDecrypted.length > 64) {
            val hmac = secondDecrypted.takeLast(64)
            if (hmac.all { it.isHexChar() }) {
                return secondDecrypted.dropLast(64)
            }
        }
        return secondDecrypted
    }

    /**
     * Kanal stream şifresi çözme.
     * decryptDoubleAesCbc benzeri ama regex1/regex2/regex2p farklı anahtarlar.
     */
    fun decryptChannelStream(
        encryptedPayload: String,
        regex1: String,
        regex2: String,
        regex2p: String? = null
    ): String {
        val cleaned = encryptedPayload.trim()
        val firstSplit = cleaned.split(":")
        val intermediate = decryptAesCbc(firstSplit[0], firstSplit[1], regex1)
        val secondSplit = intermediate.split(":")
        val secondDecrypted = try {
            decryptAesCbc(secondSplit[0], secondSplit[1], regex2)
        } catch (e: Exception) {
            if (regex2p.isNullOrEmpty()) throw e
            decryptAesCbc(secondSplit[0], secondSplit[1], regex2p)
        }
        if (secondDecrypted.length > 64) {
            val hmac = secondDecrypted.takeLast(64)
            if (hmac.all { it.isHexChar() }) {
                return secondDecrypted.dropLast(64)
            }
        }
        return secondDecrypted
    }

    private fun Char.isHexChar(): Boolean =
        (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')

    // ----------------------------------------------------------------
    // İmzalı header üretici
    // ----------------------------------------------------------------

    fun getSignedHeaders(url: String, method: String = "POST", body: String = ""): Map<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonce = getRandomHex(16)
        val urlPath = try {
            URI(url).rawPath ?: "/"
        } catch (e: Exception) {
            url
        }
        val bodyHash = sha256Hex(body)
        val toSign = buildString {
            append(method).append("\n")
            append(urlPath).append("\n")
            append(timestamp).append("\n")
            append(nonce).append("\n")
            append(bodyHash)
        }
        val signature = hmacSha256Hex(HMK_KEY, toSign)

        val headers = mutableMapOf(
            "User-Agent" to "speedrestapi",
            "X-Requested-With" to "com.bp.box",
            "Referer" to "https://speedrestapi.com/",
            "X-Ts" to timestamp,
            "X-Nc" to nonce,
            "X-Sg" to signature
        )
        if (method.equals("POST", ignoreCase = true)) {
            headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        }
        return headers
    }

    // ----------------------------------------------------------------
    // Bootstrap domain (certificate.pem → AES → Domain)
    // ----------------------------------------------------------------

    suspend fun fetchBootstrapDomain(): Domain? {
        return try {
            val response = app.get(CERTIFICATE_URL)
            if (!response.isSuccessful) return null

            val certificateText = response.text
            val cleanContent = certificateText
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .trim()

            val decryptedJson = decryptDoubleAesCbc(cleanContent, BOOTSTRAP_AES_KEY)
            mapper.readValue(decryptedJson, Domain::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
