package com.maroclive

import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

const val MAX_PLAYBACK_DEBUG = true

object Diag {
    private const val TAG = "MarocLive"
    private const val MAX_REDIRECTS = 8
    private const val TIMEOUT_SECONDS = 10L

    private val redactKeys = setOf(
        "authorization", "api-key", "apikey", "x-api-key", "x-access-token",
        "token", "password", "passwd", "cookie", "set-cookie", "proxy-authorization"
    )

    fun d(msg: String) {
        if (MAX_PLAYBACK_DEBUG) Log.d(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, if (t != null) "$msg\n${t.stackTraceToString()}" else msg)
    }

    fun section(title: String) = d("=== $title ===")

    fun kv(key: String, value: String) = d("$key: $value")

    fun redactHeader(name: String, value: String): String =
        if (redactKeys.any { name.equals(it, ignoreCase = true) }) "[REDACTED]" else value

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) -> redactHeader(k, v) }

    fun extensionOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val last = clean.substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        return if (dot >= 0) last.substring(dot + 1).lowercase() else "none"
    }

    fun classifyUrl(url: String): String {
        val ext = extensionOf(url)
        return when {
            url.contains(".m3u8") -> "HLS"
            ext == "mpd" -> "DASH"
            ext == "ts" -> "HLS segment"
            ext == "mp4" || ext == "mkv" || ext == "webm" || ext == "mov" -> "MEDIA"
            else -> "OTHER"
        }
    }

    fun hostOf(url: String): String = try {
        URI(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    fun urlBreakdown(url: String): List<Pair<String, String>> = try {
        val uri = URI(url)
        listOf(
            "protocol" to (uri.scheme ?: "none"),
            "host" to (uri.host ?: "none"),
            "port" to (if (uri.port >= 0) uri.port.toString() else "(default)"),
            "path" to (uri.path ?: ""),
            "query" to (uri.query ?: "none"),
            "extension" to extensionOf(url)
        )
    } catch (t: Throwable) {
        listOf("url-parse-error" to (t.message ?: t.javaClass.simpleName))
    }

    fun resolveAbsolute(baseUrl: String, target: String): String? = try {
        when {
            target.startsWith("http://") || target.startsWith("https://") -> target
            target.startsWith("//") -> "https:$target"
            else -> URI(baseUrl).resolve(target).toString()
        }
    } catch (t: Throwable) {
        null
    }

    fun classifyNetworkError(t: Throwable): String = when (t) {
        is UnknownHostException -> "DNS FAILURE: ${t.message}"
        is ConnectException -> "CONNECT FAILURE: ${t.message}"
        is SocketTimeoutException -> "TIMEOUT: ${t.message}"
        is SSLException -> "TLS/SSL FAILURE: ${t.message}"
        else -> "${t.javaClass.simpleName}: ${t.message}"
    }

    // ---- HTTP ----

    data class HttpResult(
        val method: String,
        val url: String,
        val finalUrl: String,
        val status: Int,
        val redirects: List<String>,
        val contentType: String?,
        val contentLength: Long?,
        val bodyText: String?,
        val failure: Throwable? = null
    ) {
        val networkOk: Boolean get() = failure == null && status in 200..399
    }

    suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        readBody: Boolean = true
    ): HttpResult = http(url, "GET", headers, followRedirects, readBody)

    suspend fun httpHead(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true
    ): HttpResult = http(url, "HEAD", headers, followRedirects, false)

    private fun buildClient(followRedirects: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .build()

    private fun newRequest(url: String, method: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        val merged = mutableMapOf("User-Agent" to DEFAULT_UA)
        headers.forEach { (k, v) -> merged[k] = v }
        merged.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, null)
        return builder.build()
    }

    private suspend fun http(
        url: String,
        method: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
        readBody: Boolean
    ): HttpResult = withContext(Dispatchers.IO) {
        val client = buildClient(followRedirects)
        val redirects = mutableListOf<String>()
        var current = url
        var finalUrl = url
        var status = -1
        var contentType: String? = null
        var contentLength: Long? = null
        var body: String? = null
        var hops = 0
        try {
            while (true) {
                client.newCall(newRequest(current, method, headers)).execute().use { resp: Response ->
                    status = resp.code
                    contentType = resp.header("Content-Type")
                    contentLength = resp.body?.contentLength()
                    val location = resp.header("Location")
                    if (resp.isRedirect && location != null && !followRedirects && hops < MAX_REDIRECTS) {
                        val resolved = if (location.startsWith("http")) location
                        else resolveAbsolute(current, location) ?: location
                        redirects.add("${resp.code} -> $resolved")
                        current = resolved
                        hops++
                        return@use
                    }
                    finalUrl = resp.request.url.toString()
                    if (readBody && method == "GET" && status in 200..399) {
                        body = resp.body?.string()
                    }
                    return@withContext HttpResult(
                        method, url, finalUrl, status, redirects,
                        contentType, contentLength, body, null
                    )
                }
            }
        } catch (t: Throwable) {
            HttpResult(method, url, finalUrl, status, redirects, contentType, contentLength, body, t)
        }
    }

    // ---- Network preflight ----

    suspend fun dnsLookup(host: String): Pair<List<String>, String?> = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).map { it.hostAddress }.toList() to null
        } catch (t: Throwable) {
            emptyList<String>() to classifyNetworkError(t)
        }
    }

    suspend fun tcpCheck(host: String, port: Int): String? = withContext(Dispatchers.IO) {
        try {
            Socket(host, port).use { }
            null
        } catch (t: Throwable) {
            classifyNetworkError(t)
        }
    }

    // ---- HLS ----

    data class HlsVariant(val resolution: String?, val bandwidth: Long?, val url: String)

    data class HlsInfo(
        val isMaster: Boolean,
        val live: Boolean,
        val targetDuration: Long?,
        val variants: List<HlsVariant>,
        val selectedVariant: HlsVariant?,
        val segmentCount: Int,
        val firstSegment: String?,
        val lastSegment: String?,
        val segmentsAbsolute: Boolean?,
        val keys: List<String>,
        val maps: List<String>,
        val medias: List<String>,
        val malformed: List<String>
    )

    fun hlsDiagnose(text: String?, baseUrl: String): HlsInfo {
        if (text == null) {
            return HlsInfo(false, false, null, emptyList(), null, 0, null, null, null,
                emptyList(), emptyList(), emptyList(), listOf("NO PLAYLIST BODY"))
        }
        val malformed = mutableListOf<String>()
        if (!text.contains("#EXTM3U")) malformed.add("missing #EXTM3U header")
        val hasEndlist = text.contains("#EXT-X-ENDLIST")
        val isMaster = Regex("#EXT-X-STREAM-INF").containsMatchIn(text)
        val targetDuration = Regex("#EXT-X-TARGETDURATION:\\s*(\\d+)")
            .find(text)?.groupValues?.get(1)?.toLongOrNull()
        val keys = Regex("#EXT-X-KEY:[^\\n]*").findAll(text).map { it.value.trim() }.toList()
        val maps = Regex("#EXT-X-MAP:[^\\n]*").findAll(text).map { it.value.trim() }.toList()
        val medias = Regex("#EXT-X-MEDIA:[^\\n]*").findAll(text).map { it.value.trim() }.toList()

        val variants = if (isMaster) {
            Regex("#EXT-X-STREAM-INF:([^\\n]*)\\n\\s*([^\\n]+)").findAll(text).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val uriLine = m.groupValues[2].trim()
                if (uriLine.isEmpty()) return@mapNotNull null
                val res = Regex("RESOLUTION=(\\d+x\\d+)").find(attrs)?.groupValues?.get(1)
                val bw = Regex("BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull()
                HlsVariant(res, bw, resolveAbsolute(baseUrl, uriLine) ?: uriLine)
            }.toList()
        } else emptyList()
        val selected = variants.maxByOrNull { it.bandwidth ?: 0 }

        val segments = Regex("#EXTINF:[^\\n]*\\n\\s*([^\\n]+)")
            .findAll(text).map { it.groupValues[1].trim() }.toList()
        val first = segments.firstOrNull()?.let { resolveAbsolute(baseUrl, it) ?: it }
        val last = segments.lastOrNull()?.let { resolveAbsolute(baseUrl, it) ?: it }
        val segmentsAbsolute = if (segments.isEmpty()) null
        else segments.all { it.startsWith("http://") || it.startsWith("https://") }

        return HlsInfo(
            isMaster, !hasEndlist, targetDuration, variants, selected,
            segments.size, first, last, segmentsAbsolute,
            keys, maps, medias, malformed
        )
    }

    fun keyUris(keys: List<String>, baseUrl: String): List<String> =
        keys.mapNotNull { line ->
            val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            resolveAbsolute(baseUrl, uri)
        }

    // ---- Diagnostics data ----

    data class DiagChannel(
        val name: String,
        val url: String,
        val referer: String?,
        val userAgent: String?
    )

    data class ChannelDiag(
        val httpStatus: Int = -1,
        val networkOk: Boolean = false,
        val hlsParsed: Boolean = false,
        val variants: Int = 0,
        val firstSegmentOk: Boolean? = null,
        val classification: String = "UNKNOWN"
    )

    fun buildHeaders(ch: DiagChannel): Map<String, String> {
        val h = mutableMapOf<String, String>()
        if (ch.referer != null) h["Referer"] = ch.referer
        if (ch.userAgent != null) h["User-Agent"] = ch.userAgent
        return h
    }

    fun classificationFor(ch: DiagChannel, res: HttpResult, hlsParsed: Boolean, firstSegOk: Boolean?): String {
        if (res.failure != null) {
            return "NETWORK FAILURE (${classifyNetworkError(res.failure)})"
        }
        return when {
            res.status == 403 || res.status == 401 ->
                "HEADER/REFERER FAILURE (HTTP ${res.status} - possible geo-restriction)"
            res.status in 400..499 -> "STREAM URL FAILURE (HTTP ${res.status})"
            res.status in 500..599 -> "NETWORK FAILURE (HTTP ${res.status})"
            res.status !in 200..399 -> "STREAM URL FAILURE (HTTP ${res.status})"
            classifyUrl(ch.url) == "HLS" && !hlsParsed -> "HLS PARSING FAILURE"
            firstSegOk == false -> "HLS SEGMENT FAILURE (first segment inaccessible)"
            else -> "PROVIDER OK (stream URL accessible)"
        }
    }

    suspend fun runChannelDiagnostics(index: Int, ch: DiagChannel): ChannelDiag {
        val headers = buildHeaders(ch)
        val host = hostOf(ch.url)
        val port = if (ch.url.startsWith("https")) 443 else 80

        section("PLAYBACK CHANNEL ${index + 1}: ${ch.name}")
        kv("URL", ch.url)
        kv("TYPE", classifyUrl(ch.url))
        urlBreakdown(ch.url).forEach { (k, v) -> kv("  $k", v) }
        kv("REFERER", ch.referer ?: "none")
        kv("HEADERS", redactHeaders(headers).toString())

        val (ips, dnsErr) = dnsLookup(host)
        kv("NETWORK TEST: DNS", if (dnsErr == null) "PASS (${ips.joinToString(",")})" else "FAIL ($dnsErr)")

        val tcpErr = tcpCheck(host, port)
        kv("NETWORK TEST: TCP", if (tcpErr == null) "PASS" else "FAIL ($tcpErr)")

        val res = httpGet(ch.url, headers)
        if (res.failure != null) {
            kv("NETWORK TEST: HTTP", "FAIL (${classifyNetworkError(res.failure)})")
            kv("PLAYER PREPARE", "APP-SIDE (not observable from plugin)")
            kv("PLAYER PLAY", "APP-SIDE (not observable from plugin)")
            val cls = classificationFor(ch, res, hlsParsed = false, firstSegOk = null)
            kv("CLASSIFICATION", cls)
            return ChannelDiag(res.status, false, false, 0, null, cls)
        }
        kv("NETWORK TEST: HTTP", "PASS (status=${res.status})")
        kv("HTTP status", res.status.toString())
        kv("HTTP content-type", res.contentType ?: "none")
        kv("HTTP content-length", res.contentLength?.toString() ?: "unknown")
        kv("HTTP final url", res.finalUrl)
        if (res.redirects.isNotEmpty()) res.redirects.forEach { kv("REDIRECT", it) }
        else kv("REDIRECTS", "none")
        if (res.status == 403 || res.status == 401) {
            kv("NOTE", "status ${res.status} - possible geo-restriction or header/referer rejection")
        }

        var hlsParsed = false
        var variants = 0
        var firstSegOk: Boolean? = null

        if (classifyUrl(ch.url) == "HLS") {
            val info = hlsDiagnose(res.bodyText, res.finalUrl)
            hlsParsed = res.bodyText?.contains("#EXTM3U") == true
            kv("M3U8 TEST", if (hlsParsed) "PASS" else "FAIL")
            info.malformed.forEach { kv("MALFORMED", it) }
            kv("playlist type", if (info.isMaster) "MASTER" else "MEDIA")
            kv("live/vod", if (info.live) "LIVE" else "VOD")
            kv("target duration", info.targetDuration?.toString() ?: "unknown")
            variants = info.variants.size
            kv("variants", variants.toString())
            info.variants.forEachIndexed { i, v ->
                kv("  variant[$i]", "res=${v.resolution ?: "unknown"} bw=${v.bandwidth ?: "unknown"} url=${v.url}")
            }
            if (info.isMaster && info.selectedVariant != null) {
                kv("selected variant", "res=${info.selectedVariant.resolution ?: "unknown"} bw=${info.selectedVariant.bandwidth ?: "unknown"} (highest bandwidth)")
            }
            kv("segments", info.segmentCount.toString())
            if (info.segmentCount > 0) {
                kv("first segment", info.firstSegment ?: "none")
                kv("last segment", info.lastSegment ?: "none")
                kv("segments absolute", when (info.segmentsAbsolute) {
                    true -> "all absolute"
                    false -> "some relative (resolved against base)"
                    null -> "n/a"
                })
            }
            if (info.keys.isNotEmpty()) info.keys.forEach { kv("EXT-X-KEY", it) }
            else kv("EXT-X-KEY", "none")
            if (info.maps.isNotEmpty()) info.maps.forEach { kv("EXT-X-MAP", it) }
            if (info.medias.isNotEmpty()) info.medias.forEach { kv("EXT-X-MEDIA", it) }

            var mediaUrl = res.finalUrl
            var mediaText = res.bodyText
            if (info.isMaster && info.selectedVariant != null) {
                val vres = httpGet(info.selectedVariant.url, headers)
                kv("M3U8 TEST: selected variant playlist", if (vres.status in 200..399) "PASS (status=${vres.status})" else "FAIL (status=${vres.status})")
                mediaUrl = vres.finalUrl
                mediaText = vres.bodyText
            }
            if (mediaText != null) {
                val mInfo = hlsDiagnose(mediaText, mediaUrl)
                if (mInfo.firstSegment != null) {
                    val seg = httpHead(mInfo.firstSegment, headers)
                    firstSegOk = seg.failure == null && seg.status in 200..399
                    kv("FIRST SEGMENT TEST", if (firstSegOk == true) "PASS"
                    else "FAIL (status=${seg.status} ${seg.failure?.message ?: ""})")
                } else {
                    kv("FIRST SEGMENT TEST", "N/A (no segments in playlist)")
                }
            }
            keyUris(info.keys, mediaUrl).forEach { uri ->
                val kres = httpHead(uri, headers)
                kv("KEY TEST", "uri=$uri -> ${if (kres.failure == null && kres.status in 200..399) "PASS" else "FAIL (status=${kres.status} ${kres.failure?.message ?: ""})"}")
            }
        } else {
            kv("M3U8 TEST", "N/A (not HLS)")
        }

        val cls = classificationFor(ch, res, hlsParsed, firstSegOk)
        kv("CLASSIFICATION", cls)
        kv("PLAYER PREPARE", "APP-SIDE (not observable from plugin)")
        kv("PLAYER PLAY", "APP-SIDE (not observable from plugin)")
        return ChannelDiag(res.status, res.networkOk, hlsParsed, variants, firstSegOk, cls)
    }

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"
}
