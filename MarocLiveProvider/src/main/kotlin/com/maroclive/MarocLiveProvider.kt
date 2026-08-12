package com.maroclive

import android.util.Log
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
        for (category in categories) {
            val group = channels.filter { it.category == category }.map { it.toLiveSearch() }
            if (group.isNotEmpty()) {
                items.add(HomePageList(category, group, isHorizontalImages = true))
            }
        }
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        channels.filter { it.name.contains(query, ignoreCase = true) }.map { it.toLiveSearch() }

    override suspend fun load(url: String): LoadResponse {
        val channel = channels.firstOrNull { it.url == url }
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
        val channel = channels.firstOrNull { it.url == data } ?: return false
        Log.d("MarocLive", "loadLinks: ${channel.name} url=$data referer=${channel.referer ?: "none"} headers=${headersFor(channel)}")
        val emitRawFallback: suspend () -> Boolean = {
            Log.w("MarocLive", "loadLinks: ${channel.name} falling back to raw HLS url: $data")
            callback(
                newExtractorLink(
                    source = channel.name,
                    name = channel.name,
                    url = data,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = channel.referer ?: ""
                    headers = headersFor(channel)
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
                headers = headersFor(channel)
            )
            Log.d("MarocLive", "loadLinks: ${channel.name} parsed ${links.size} HLS link(s)")
            links.forEach { link ->
                Log.d("MarocLive", "loadLinks: ${channel.name} -> ${link.url}")
                callback(link)
            }
            if (links.isNotEmpty()) true else emitRawFallback()
        } catch (e: Exception) {
            Log.e("MarocLive", "loadLinks: ${channel.name} failed to parse HLS: $data", e)
            emitRawFallback()
        }
    }
}
