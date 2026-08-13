package com.maroclive

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

private const val DIAG_ALL_URL = "maroclive://diagnostics/all"

class MarocLiveProvider : MainAPI() {
    override var mainUrl = "https://snrtlive.ma/"
    override var name = "MarocLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val source: String,
        val referer: String? = null,
        val userAgent: String? = null,
        val showOnHome: Boolean = true,
        val requiresToken: Boolean = false
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"

    private val snrtReferer = "https://snrt.player.easybroadcast.io/"
    private val easybroadcastBase = "https://cdn.live.easybroadcast.io/abr_corp"
    private val tokenServer = "https://token.easybroadcast.io/all"
    private val thumbs = "https://raw.githubusercontent.com/rhoulou/TotalArab/main/MarocLiveProvider/thumbnails"
    private val twoMLogo = "$thumbs/2m.png"

    private var cachedSignedBase: String? = null
    private var cachedSignedUrl: String? = null
    private var cachedSignedExpiry: Long = 0L

    private suspend fun signUrl(base: String): String {
        val now = System.currentTimeMillis() / 1000
        if (cachedSignedBase == base && cachedSignedUrl != null && cachedSignedExpiry - now > 120L) {
            return cachedSignedUrl ?: base
        }
        try {
            val target = "$tokenServer?url=${URLEncoder.encode(base, "UTF-8")}"
            val res = Diag.httpGet(target, mapOf("Referer" to snrtReferer))
            if (MAX_PLAYBACK_DEBUG) {
                Diag.kv("TOKEN REQUEST", "status=${res.status} failure=${res.failure?.message ?: "none"}")
                Diag.kv("TOKEN URL", res.finalUrl)
            }
            if (res.failure == null && res.status in 200..399 && res.bodyText != null) {
                val params = res.bodyText.trim().removePrefix("?").split('&').mapNotNull { part ->
                    val kv = part.split('=', limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }.toMap()
                val token = params["token"]
                val expires = params["expires"]?.toLongOrNull()
                val tokenPath = params["token_path"]
                if (token != null && expires != null && tokenPath != null) {
                    val query = "token=$token&expires=$expires&token_path=$tokenPath"
                    val signed = "$base?$query"
                    cachedSignedBase = base
                    cachedSignedUrl = signed
                    cachedSignedExpiry = expires
                    if (MAX_PLAYBACK_DEBUG) Diag.kv("SIGNED URL", signed)
                    return signed
                }
                Diag.w("signUrl: token response missing fields: ${res.bodyText}")
            }
        } catch (t: Throwable) {
            Diag.e("signUrl: token fetch failed for $base", t)
        }
        return base
    }

    private suspend fun signedVariantUrl(channel: Channel, signedMaster: String): String? {
        val fetchHeaders = headersFor(channel).toMutableMap()
        if (channel.referer != null) fetchHeaders["Referer"] = channel.referer
        var res = Diag.httpGet(signedMaster, fetchHeaders)
        if (res.failure != null || res.status !in 200..399 || res.bodyText == null) {
            Diag.w("signedVariantUrl: master fetch failed for ${channel.name} (status=${res.status}), retrying once")
            val retry = Diag.httpGet(signedMaster, fetchHeaders)
            if (retry.failure != null || retry.status !in 200..399 || retry.bodyText == null) {
                Diag.w("signedVariantUrl: master fetch failed again for ${channel.name} (status=${retry.status})")
                return null
            }
            res = retry
        }
        val info = Diag.hlsDiagnose(res.bodyText, res.finalUrl)
        if (!info.isMaster) return signedMaster
        val selected = info.selectedVariant ?: info.variants.firstOrNull() ?: return null
        val query = signedMaster.substringAfter('?', "")
        if (query.isEmpty()) return signedMaster
        val variantAbs = Diag.resolveAbsolute(signedMaster.substringBefore('?'), selected.url) ?: return null
        return "$variantAbs?$query"
    }

    private val channels = listOf(
        Channel(
            "2m.ma",
            "https://cdn-globecast.akamaized.net/live/eds/2m_monde/hls_video_ts_tuhawxpiemz257adfc/2m_monde.m3u8",
            twoMLogo,
            "2m.ma",
            referer = "https://2m.ma",
            userAgent = browserUserAgent
        ),
        Channel(
            "2M Monde +1",
            "https://d2qh3gh0k5vp3v.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-n6pess5lwbghr/2M_ES.m3u8",
            twoMLogo,
            "2m.ma"
        ),
        Channel(
            "Al Aoula",
            "$easybroadcastBase/73_aloula_w1dqfwm/playlist_dvr.m3u8",
            "$thumbs/al-aoula.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Al Aoula Laâyoune",
            "$easybroadcastBase/73_laayoune_pgagr52/playlist_dvr.m3u8",
            "$thumbs/laayoune.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Al Maghribia",
            "$easybroadcastBase/73_almaghribia_83tz85q/playlist_dvr.m3u8",
            "$thumbs/almaghribia.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Al Maghribia (alt)",
            "http://185.9.2.18/chid_205/index.m3u8",
            "$thumbs/almaghribia.png",
            "Autres",
            showOnHome = false
        ),
        Channel(
            "Arryadia",
            "$easybroadcastBase/73_arryadia_k2tgcj0/playlist_dvr.m3u8",
            "$thumbs/arryadia.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Assadissa",
            "$easybroadcastBase/73_assadissa_7b7u5n1/playlist_dvr.m3u8",
            "$thumbs/assadissa.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Athaqafia",
            "$easybroadcastBase/73_arrabia_hthcj4p/playlist_dvr.m3u8",
            "$thumbs/athaqafia.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Tamazight TV",
            "$easybroadcastBase/73_tamazight_tccybxt/playlist_dvr.m3u8",
            "$thumbs/tamazight.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Chada TV",
            "https://edge19.vedge.infomaniak.com/livecast/ik:chadatv/playlist.m3u8",
            "$thumbs/chada.png",
            "Autres"
        ),
        Channel(
            "StoryChannel TV",
            "https://136044159.r.cdnsun.net/storychannel.m3u8",
            "$thumbs/storychannel.png",
            "Autres"
        ),
        Channel(
            "Medi 1 TV Maghreb",
            "$easybroadcastBase/83_medi1tv-maghreb_jnbspmg/playlist.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com"
        ),
        Channel(
            "Medi 1 TV Maghreb (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-maghreb_jnbspmg/playlist_dvr.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com",
            showOnHome = false
        ),
        Channel(
            "Medi 1 TV Afrique",
            "$easybroadcastBase/83_medi1tv-afrique_tm7tu45/playlist.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com"
        ),
        Channel(
            "Medi 1 TV Afrique (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-afrique_tm7tu45/playlist_dvr.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com",
            showOnHome = false
        ),
        Channel(
            "Medi 1 TV Arabic",
            "$easybroadcastBase/83_medi1tv-arabic_g90v4ec/playlist.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com"
        ),
        Channel(
            "Medi 1 TV Arabic (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-arabic_g90v4ec/playlist_dvr.m3u8",
            "$thumbs/medi1.png",
            "medi1tv.com",
            showOnHome = false
        ),
        Channel(
            "Medi 1 Radio Maghreb",
            "https://streaming1.medi1tv.com/radio/radio_mag.stream_aac/playlist.m3u8",
            "$thumbs/medi1-radio.png",
            "medi1tv.com"
        ),
        Channel(
            "Medi 1 Radio Afrique",
            "https://streaming1.medi1tv.com/radio/radio_afr.stream_aac/playlist.m3u8",
            "$thumbs/medi1-radio.png",
            "medi1tv.com"
        ),
        Channel(
            "Alidaa Alwatania",
            "https://cdn.live.easybroadcast.io/live/radio_nationale/playlist.m3u8",
            "$thumbs/radio-watania.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Chaine Inter",
            "https://cdn.live.easybroadcast.io/live/radio_inter/playlist.m3u8",
            "$thumbs/radio-inter.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Idaât Mohammed Assadiss",
            "https://cdn.live.easybroadcast.io/live/radio_med_VI/playlist.m3u8",
            "$thumbs/radio-assadiss.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Alidaâ Alamazighia",
            "https://cdn.live.easybroadcast.io/live/radio_amazigh/playlist.m3u8",
            "$thumbs/radio-amazigh.png",
            "snrtlive.ma",
            referer = snrtReferer,
            requiresToken = true
        ),
        Channel(
            "Radio 2M",
            "https://cdn-globecast.akamaized.net/live/eds/radio_2m/radio_hls_ts_hy217612tge1f21j83/radio_2m.m3u8",
            twoMLogo,
            "2m.ma",
            referer = "https://2m.ma",
            userAgent = browserUserAgent
        )
    )

    private val homeRows = listOf("2m.ma", "snrtlive.ma", "medi1tv.com", "Autres")

    private fun Channel.toLiveSearch(): LiveSearchResponse =
        newLiveSearchResponse(name, url, TvType.Live) {
            posterUrl = logo
            lang = "ar"
        }

    private fun headersFor(channel: Channel): Map<String, String> =
        if (channel.userAgent != null) mapOf("User-Agent" to channel.userAgent) else emptyMap()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val items = mutableListOf<HomePageList>()
        if (MAX_PLAYBACK_DEBUG) {
            items.add(
                HomePageList(
                    "الاختبار (Diagnostics)",
                    listOf(
                        newLiveSearchResponse("Full diagnostics: all channels", DIAG_ALL_URL, TvType.Live) {
                            posterUrl = "$thumbs/snrt-live.png"
                            lang = "ar"
                        }
                    ),
                    isHorizontalImages = true
                )
            )
        }
        for (source in homeRows) {
            val group = channels.filter { it.source == source && it.showOnHome }.map { it.toLiveSearch() }
            if (group.isNotEmpty()) {
                items.add(HomePageList(source, group, isHorizontalImages = true))
            }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = channels.filter { it.name.contains(query, ignoreCase = true) }
        if (MAX_PLAYBACK_DEBUG) {
            Diag.section("PROVIDER/SEARCH DIAGNOSTICS")
            Diag.kv("provider", name)
            Diag.kv("search query", query)
            Diag.kv("search url", "N/A - static channel list (no network search)")
            Diag.kv("HTTP status", "N/A - static channel list")
            Diag.kv("response headers", "N/A - static channel list")
            Diag.kv("result count", results.size.toString())
            results.forEachIndexed { i, ch ->
                Diag.d("result[$i]: title=\"${ch.name}\" type=${Diag.classifyUrl(ch.url)} url=${ch.url}")
            }
        }
        return results.map { it.toLiveSearch() }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url == DIAG_ALL_URL) {
            if (MAX_PLAYBACK_DEBUG) Diag.d("load: diagnostics entry opened")
            return newLiveStreamLoadResponse(
                name = "MarocLive Diagnostics",
                url = url,
                dataUrl = url
            )
        }
        val channel = channels.firstOrNull { it.url == url }
        if (MAX_PLAYBACK_DEBUG) {
            Diag.section("LOAD DIAGNOSTICS")
            Diag.kv("load url", url)
            Diag.kv("channel", channel?.name ?: "UNKNOWN")
        }
        return newLiveStreamLoadResponse(
            name = channel?.name ?: "Live Stream",
            url = url,
            dataUrl = url
        ) {
            posterUrl = channel?.logo ?: ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (MAX_PLAYBACK_DEBUG && data == DIAG_ALL_URL) return runFullDiagnostics()

        val channel = channels.firstOrNull { it.url == data } ?: run {
            Diag.e("loadLinks: unknown url $data")
            return false
        }
        val channelHeaders = headersFor(channel)
        val index = channels.indexOfFirst { it.url == data }
        var diag = Diag.ChannelDiag()

        val effectiveUrl = if (channel.requiresToken) signUrl(data) else data

        if (MAX_PLAYBACK_DEBUG) {
            diag = try {
                val diagChannel = if (channel.requiresToken) chToDiag(channel).copy(url = effectiveUrl) else chToDiag(channel)
                Diag.runChannelDiagnostics(index, diagChannel)
            } catch (t: Throwable) {
                Diag.e("loadLinks: diagnostics threw for ${channel.name}", t)
                Diag.ChannelDiag(classification = "DIAGNOSTICS FAILURE")
            }
            Diag.kv("PLAYBACK PATH", "extract -> hand to player")
        } else {
            Log.d("MarocLive", "loadLinks: ${channel.name} url=$data referer=${channel.referer ?: "none"} headers=$channelHeaders")
        }

        val emitRawFallback: suspend () -> Boolean = {
            Diag.w("loadLinks: ${channel.name} falling back to raw HLS url: $effectiveUrl")
            callback(
                newExtractorLink(
                    source = channel.name,
                    name = channel.name,
                    url = effectiveUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = channel.referer ?: ""
                    headers = channelHeaders
                    quality = Qualities.Unknown.value
                }
            )
            true
        }

        return try {
            if (channel.requiresToken) {
                val signedMaster = signUrl(data)
                val playUrl = signedVariantUrl(channel, signedMaster) ?: signedMaster
                if (MAX_PLAYBACK_DEBUG) {
                    Diag.kv("PLAYBACK URL", playUrl)
                    Diag.kv(
                        "TOKEN TARGET",
                        if (playUrl == signedMaster) "SIGNED MASTER (fallback)"
                        else "SIGNED MEDIA VARIANT (highest bandwidth)"
                    )
                }
                callback(
                    newExtractorLink(
                        source = channel.name,
                        name = channel.name,
                        url = playUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = channel.referer ?: ""
                        headers = channelHeaders
                        quality = Qualities.Unknown.value
                    }
                )
                if (MAX_PLAYBACK_DEBUG) {
                    Diag.kv("STREAM EXTRACTION", "PASS (1 signed media link - token-based)")
                    logPlaybackSummary(channel, diag, 1, null)
                }
                return true
            }
            val links = M3u8Helper.generateM3u8(
                channel.name,
                effectiveUrl,
                referer = channel.referer ?: "",
                headers = channelHeaders
            )
            if (MAX_PLAYBACK_DEBUG) {
                Diag.kv(
                    "STREAM EXTRACTION",
                    if (links.isNotEmpty()) "PASS (${links.size} link(s))" else "FAIL (0 links - using raw fallback)"
                )
                links.forEachIndexed { i, l ->
                    Diag.d("link[$i]: url=${l.url} type=${Diag.classifyUrl(l.url)}")
                }
            } else {
                Log.d("MarocLive", "loadLinks: ${channel.name} parsed ${links.size} HLS link(s)")
            }
            links.forEach { callback(it) }
            if (links.isNotEmpty()) {
                if (MAX_PLAYBACK_DEBUG) logPlaybackSummary(channel, diag, links.size, null)
                true
            } else {
                if (MAX_PLAYBACK_DEBUG) logPlaybackSummary(channel, diag, 0, null)
                emitRawFallback()
            }
        } catch (e: Exception) {
            Diag.e("loadLinks: ${channel.name} failed to parse HLS: $data", e)
            if (MAX_PLAYBACK_DEBUG) logPlaybackSummary(channel, diag, 0, e)
            emitRawFallback()
        }
    }

    private fun chToDiag(channel: Channel) =
        Diag.DiagChannel(channel.name, channel.url, channel.referer, channel.userAgent)

    private suspend fun runFullDiagnostics(): Boolean {
        Diag.section("FULL DIAGNOSTICS - ALL CHANNELS")
        val results = channels.mapIndexed { i, ch ->
            try {
                val diagChannel = if (ch.requiresToken) chToDiag(ch).copy(url = signUrl(ch.url)) else chToDiag(ch)
                Diag.runChannelDiagnostics(i, diagChannel)
            } catch (t: Throwable) {
                Diag.e("runChannelDiagnostics: ${ch.name} threw", t)
                Diag.ChannelDiag(classification = "DIAGNOSTICS FAILURE")
            }
        }
        Diag.section("PLAYBACK DIAGNOSTIC SUMMARY (ALL CHANNELS)")
        Diag.kv("channels tested", results.size.toString())
        Diag.kv("network OK", results.count { it.networkOk }.toString())
        Diag.kv("HLS parsed", results.count { it.hlsParsed }.toString())
        Diag.kv(
            "classifications",
            results.mapIndexed { i, r -> "[${i + 1}] ${r.classification}" }.joinToString(" | ")
        )
        return false
    }

    private fun logPlaybackSummary(
        channel: Channel,
        diag: Diag.ChannelDiag,
        linksCount: Int,
        error: Throwable?
    ) {
        Diag.section("PLAYBACK DIAGNOSTIC SUMMARY")
        Diag.kv("Provider", "MarocLive")
        Diag.kv("Channel", channel.name)
        Diag.kv("Search", "PASS")
        Diag.kv("Results", "1")
        Diag.kv("Stream extraction", if (linksCount > 0) "PASS ($linksCount link(s))" else "FAIL")
        Diag.kv("URL accessibility", if (diag.networkOk) "PASS (HTTP ${diag.httpStatus})" else "FAIL (HTTP ${diag.httpStatus})")
        Diag.kv("HLS parsing", if (diag.hlsParsed) "PASS" else "FAIL")
        Diag.kv("Playlist variants", diag.variants.toString())
        Diag.kv("First segment", when (diag.firstSegmentOk) {
            true -> "PASS"
            false -> "FAIL"
            else -> "N/A"
        })
        Diag.kv("Player preparation", "APP-SIDE (not observable from plugin)")
        Diag.kv(
            "Playback start",
            if (error == null) "links handed to player (APP-SIDE confirmation required)"
            else "extraction error: ${error.message}"
        )
        Diag.kv("Root cause classification", diag.classification)
    }
}
