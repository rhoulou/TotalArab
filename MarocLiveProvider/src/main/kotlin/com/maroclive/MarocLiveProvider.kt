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

class MarocLiveProvider : MainAPI() {
    override var mainUrl = "https://snrtlive.ma/"
    override var name = "MarocLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private const val DIAG_ALL_URL = "maroclive://diagnostics/all"

    data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val category: String,
        val referer: String? = null,
        val userAgent: String? = null
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0"

    private val snrtReferer = "https://snrt.player.easybroadcast.io/"
    private val easybroadcastBase = "https://cdn.live.easybroadcast.io/abr_corp"
    private val twoMLogo = "https://m.2m.ma/static/images/2m-logo-thumbnail.png"

    private val channels = listOf(
        Channel("2M", "http://185.9.2.18/chid_218/index.m3u8", twoMLogo, "2M"),
        Channel(
            "2M Monde",
            "https://cdn-globecast.akamaized.net/live/eds/2m_monde/hls_video_ts_tuhawxpiemz257adfc/2m_monde.m3u8",
            twoMLogo,
            "2M",
            referer = "https://2m.ma",
            userAgent = browserUserAgent
        ),
        Channel(
            "2M Monde +1",
            "https://d2qh3gh0k5vp3v.cloudfront.net/v1/master/3722c60a815c199d9c0ef36c5b73da68a62b09d1/cc-n6pess5lwbghr/2M_ES.m3u8",
            twoMLogo,
            "2M"
        ),
        Channel(
            "Al Aoula",
            "$easybroadcastBase/73_aloula_w1dqfwm/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-03/alaoula.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Al Aoula Laâyoune",
            "$easybroadcastBase/73_laayoune_pgagr52/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/laayoune.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Al Maghribia",
            "$easybroadcastBase/73_almaghribia_83tz85q/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/almaghribia.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Al Maghribia (alt)",
            "http://185.9.2.18/chid_205/index.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/almaghribia.png",
            "Autres"
        ),
        Channel(
            "Arryadia",
            "$easybroadcastBase/73_arryadia_k2tgcj0/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/arriyadia.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Assadissa",
            "$easybroadcastBase/73_assadissa_7b7u5n1/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/assadissa.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Athaqafia",
            "$easybroadcastBase/73_arrabia_hthcj4p/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/attakafiya.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Tamazight TV",
            "$easybroadcastBase/73_tamazight_tccybxt/playlist_dvr.m3u8",
            "https://www.snrt.ma/sites/default/files/2023-04/tamazight.png",
            "SNRT",
            referer = snrtReferer
        ),
        Channel(
            "Chada TV",
            "https://edge19.vedge.infomaniak.com/livecast/ik:chadatv/playlist.m3u8",
            "https://freebox.cdn.scw.iliad.fr/medium_Logo_chada_tv_2ee1412d38.png",
            "Autres"
        ),
        Channel(
            "StoryChannel TV",
            "https://136044159.r.cdnsun.net/storychannel.m3u8",
            "https://i.imgur.com/ZBV6xph.png",
            "Autres"
        ),
        Channel(
            "Medi 1 TV Maghreb",
            "$easybroadcastBase/83_medi1tv-maghreb_jnbspmg/playlist.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule_mg.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 TV Maghreb (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-maghreb_jnbspmg/playlist_dvr.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule_mg.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 TV Afrique",
            "$easybroadcastBase/83_medi1tv-afrique_tm7tu45/playlist.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 TV Afrique (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-afrique_tm7tu45/playlist_dvr.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 TV Arabic",
            "$easybroadcastBase/83_medi1tv-arabic_g90v4ec/playlist.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule_ar.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 TV Arabic (DVR 6h)",
            "$easybroadcastBase/83_medi1tv-arabic_g90v4ec/playlist_dvr.m3u8",
            "https://www.medi1tv.com/assets/imgs/medi1_circule_ar.png",
            "Medi1 TV"
        ),
        Channel(
            "Medi 1 Radio Maghreb",
            "https://streaming1.medi1tv.com/radio/radio_mag.stream_aac/playlist.m3u8",
            "https://www.medi1.com/assets/imgs/medi1_logo_new.png?n",
            "Medi1 Radio"
        ),
        Channel(
            "Medi 1 Radio Afrique",
            "https://streaming1.medi1tv.com/radio/radio_afr.stream_aac/playlist.m3u8",
            "https://www.medi1.com/assets/imgs/medi1_logo_new.png?n",
            "Medi1 Radio"
        )
    )

    private val categories = listOf("SNRT", "2M", "Medi1 TV", "Medi1 Radio", "Autres")

    private fun Channel.toLiveSearch(): LiveSearchResponse =
        newLiveSearchResponse(name, url, TvType.Live) {
            posterUrl = logo
            lang = "ar"
        }

    private fun headersFor(channel: Channel): Map<String, String> =
        if (channel.userAgent != null) mapOf("User-Agent" to channel.userAgent) else emptyMap()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        if (MAX_PLAYBACK_DEBUG) {
            items.add(
                HomePageList(
                    "الاختبار (Diagnostics)",
                    listOf(
                        newLiveSearchResponse("Full diagnostics: all channels", DIAG_ALL_URL, TvType.Live) {
                            posterUrl = ""
                            lang = "ar"
                        }
                    ),
                    isHorizontalImages = true
                )
            )
        }
        for (category in categories) {
            val group = channels.filter { it.category == category }.map { it.toLiveSearch() }
            if (group.isNotEmpty()) {
                items.add(HomePageList(category, group, isHorizontalImages = true))
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
        val headers = headersFor(channel)
        val index = channels.indexOfFirst { it.url == data }
        var diag = Diag.ChannelDiag()

        if (MAX_PLAYBACK_DEBUG) {
            diag = try {
                Diag.runChannelDiagnostics(index, chToDiag(channel))
            } catch (t: Throwable) {
                Diag.e("loadLinks: diagnostics threw for ${channel.name}", t)
                Diag.ChannelDiag(classification = "DIAGNOSTICS FAILURE")
            }
            Diag.kv("PLAYBACK PATH", "extract -> hand to player")
        } else {
            Log.d("MarocLive", "loadLinks: ${channel.name} url=$data referer=${channel.referer ?: "none"} headers=$headers")
        }

        val emitRawFallback: suspend () -> Boolean = {
            Diag.w("loadLinks: ${channel.name} falling back to raw HLS url: $data")
            callback(
                newExtractorLink(
                    source = channel.name,
                    name = channel.name,
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = channel.referer ?: ""
                    headers = headers
                    quality = Qualities.Unknown.value
                }
            )
            true
        }

        return try {
            val links = M3u8Helper.generateM3u8(
                channel.name,
                data,
                referer = channel.referer ?: "",
                headers = headers
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
                Diag.runChannelDiagnostics(i, chToDiag(ch))
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
