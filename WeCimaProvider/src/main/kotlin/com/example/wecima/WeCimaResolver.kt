package com.example.wecima

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * WeCima embeds are scattered across many hosters. Some (abstream, savefiles,
 * vibuxer, miixdrop, ...) have no built-in CloudStream extractor, so they are
 * resolved here. Hosts with a built-in extractor (lulu*, dood*, mixdrop.*,
 * dhcplay, playmogo) are delegated to [loadExtractor].
 *
 * Every host is isolated so a dead link never kills the list: a host that does
 * not expose a playable source simply returns false and the next one is tried.
 */
object WeCimaResolver {

    const val userAgent =
        "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val fileRegex = Regex("""file\s*:\s*"([^"]+\.m3u8[^"]*)"""")
    private val m3u8Regex = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
    private val mp4Regex = Regex("""https?://[^"'\s]+?\.mp4[^"'\s]*""")
    private val wurlRegex = Regex("""wurl.*?=.*?"(.*?)";""", RegexOption.DOT_MATCHES_ALL)
    private val packerStartRegex = Regex("""eval\(function\(p,a,c,k,e,d\)""")
    private val packerArgsRegex = Regex(""",\s*(\d+),\s*(\d+),\s*'([^']*)'\.split\('\|'\)""")
    private val qualityRegex = Regex("""\b(\d{3,4})\s*p?""")

    suspend fun resolve(
        embedUrl: String,
        referer: String,
        knownQuality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = embedUrl.replace("/d/", "/e/").replace("/f/", "/e/")
        val host = runCatching { normalized.substringAfter("//").substringBefore("/").lowercase() }
            .getOrDefault("")
        return try {
            when {
                host.contains("abstream") -> resolveAbstream(normalized, knownQuality, callback)
                host.contains("savefiles") -> resolveSavefiles(normalized, knownQuality, callback)
                host.contains("vibuxer") -> resolveVibuxer(normalized, knownQuality, callback)
                host.contains("miixdrop") -> resolveMiixdrop(normalized, knownQuality, callback)
                host.contains("mixdrop") || host.contains("lulu") || host.contains("dood") ||
                    host.contains("dhcplay") || host.contains("playmogo") ->
                    resolveViaExtractor(normalized, referer, subtitleCallback, callback)
                else -> resolveGeneric(normalized, knownQuality, callback)
            }
        } catch (_: Exception) {
            false
        }
    }

    fun parseQuality(text: String?): Int? {
        if (text == null) return null
        val t = text.trim().lowercase()
        if (t.contains("4k") || t.contains("2160")) return 2160
        if (t.contains("1440") || t.contains("2k")) return 1440
        return qualityRegex.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun resolveAbstream(
        embedUrl: String,
        knownQuality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(embedUrl, headers = headers("https://abstream.to/")).text
        val master = fileRegex.find(text)?.groupValues?.get(1) ?: return false
        return emitHls("abstream", "abstream", master, knownQuality, "https://abstream.to/", callback)
    }

    private suspend fun resolveSavefiles(
        embedUrl: String,
        knownQuality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = embedUrl.substringAfter("//").substringBefore("/")
        val id = embedUrl.trimEnd('/').substringAfterLast('/')
        val resp = app.post(
            "https://$host/dl",
            data = mapOf("op" to "embed", "file_code" to id, "auto" to "0", "referer" to ""),
            headers = headers("https://$host/")
        )
        val master = fileRegex.find(resp.text)?.groupValues?.get(1) ?: return false
        return emitHls("savefiles", "savefiles", master, knownQuality, "https://$host/", callback)
    }

    private suspend fun resolveVibuxer(
        embedUrl: String,
        knownQuality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(embedUrl, headers = headers(embedUrl)).text
        val decoded = unpackPacker(text) ?: return false
        val master = m3u8Regex.find(decoded)?.value ?: return false
        return emitHls("vibuxer", "vibuxer", master, knownQuality, embedUrl, callback)
    }

    private suspend fun resolveMiixdrop(
        embedUrl: String,
        knownQuality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = embedUrl.substringAfter("//").substringBefore("/")
        val text = app.get(embedUrl, headers = headers("https://$host/")).text
        if (text.contains("File not found", ignoreCase = true)) return false
        val source = unpackPacker(text) ?: text
        val wurl = wurlRegex.find(source)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val direct = wurl
            ?: m3u8Regex.find(source)?.value
            ?: mp4Regex.find(source)?.value
            ?: m3u8Regex.find(text)?.value
            ?: mp4Regex.find(text)?.value
        if (direct == null) return false
        val url = if (direct.startsWith("//")) "https:$direct" else direct
        return emitVideo("miixdrop", "miixdrop", url, knownQuality, "https://$host/", callback)
    }

    private suspend fun resolveViaExtractor(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        loadExtractor(embedUrl, referer, subtitleCallback) { link ->
            if (link.url.isNotBlank()) {
                callback(link)
                emitted = true
            }
        }
        return emitted
    }

    private suspend fun resolveGeneric(
        embedUrl: String,
        knownQuality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = app.get(embedUrl, headers = headers(embedUrl)).text.replace("\\/", "/")
        val m3u8 = m3u8Regex.find(text)?.value
        if (m3u8 != null) return emitHls("wecima", "wecima", m3u8, knownQuality, embedUrl, callback)
        val mp4 = mp4Regex.find(text)?.value
        if (mp4 != null) return emitVideo("wecima", "wecima", mp4, knownQuality, embedUrl, callback)
        return false
    }

    private suspend fun emitHls(
        source: String,
        name: String,
        masterUrl: String,
        quality: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = try {
            M3u8Helper2.generateM3u8(
                source = source,
                name = name,
                streamUrl = masterUrl,
                quality = quality,
                headers = headers(referer),
                referer = referer
            )
        } catch (_: Exception) {
            return false
        }
        var emitted = 0
        for (link in links) {
            if (link.url.isBlank()) continue
            link.headers = headers(referer)
            link.referer = referer
            callback(link)
            emitted++
        }
        return emitted > 0
    }

    private suspend fun emitVideo(
        source: String,
        name: String,
        url: String,
        quality: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = try {
            app.get(url, headers = headers(referer) + ("Range" to "bytes=0-2047"))
        } catch (_: Exception) {
            return false
        }
        if (resp.code != 206 && resp.code !in 200..299) return false
        callback(
            newExtractorLink(source, name, url) {
                this.referer = referer
                this.quality = quality ?: 0
                this.headers = headers(referer)
                this.type = ExtractorLinkType.VIDEO
            }
        )
        return true
    }

    /**
     * Unpacks a base-36 JS packer the same way the packer's own e-function does:
     * iterate token indexes in DESCENDING order and replace every word-bounded
     * occurrence globally. (The built-in JsUnpacker walks tokens in appearance
     * order, which mangles query strings such as vibuxer's.) Returns the decoded
     * payload, or null when the page carries no such packer.
     */
    private fun unpackPacker(text: String): String? {
        val start = packerStartRegex.find(text)?.range?.first ?: return null
        val payloadStart = text.indexOf("}('", start)
        if (payloadStart < 0) return null
        var i = payloadStart + 3
        val payload = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                payload.append(text[i + 1])
                i += 2
                continue
            }
            if (c == '\'') break
            payload.append(c)
            i++
        }
        if (i >= text.length) return null
        val args = packerArgsRegex.find(text.substring(i)) ?: return null
        val radix = args.groupValues[1].toIntOrNull() ?: return null
        if (radix < 2 || radix > 36) return null
        val keys = args.groupValues[3].split("|")
        var decoded = payload.toString()
        for (idx in keys.size - 1 downTo 0) {
            val key = keys[idx]
            if (key.isEmpty()) continue
            val token = idx.toString(radix)
            decoded = Regex("\\b" + Regex.escape(token) + "\\b").replace(decoded, key)
        }
        return decoded
    }

    private fun headers(referer: String? = null): Map<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = userAgent
        referer?.takeIf { it.isNotBlank() }?.let { h["Referer"] = it }
        return h
    }
}
