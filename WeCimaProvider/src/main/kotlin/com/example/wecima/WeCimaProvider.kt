package com.example.wecima

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.totalarab.util.ArabicTitleParser
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class WeCimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.cx"
    override var name = "WeCima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * WeCima rotates its domain frequently. All mirrors run the same engine;
     * wecima.click always 301-redirects to the currently live domain. We try
     * the canonical domain first and fall back on failure, caching the
     * working base so the happy path makes no extra requests.
     */
    private val wecimaBases = listOf(
        "https://wecima.cx",
        "https://wecima.watch",
        "https://wecima.movie",
        "https://wecima.click"
    )
    private var currentBase = wecimaBases.first()
    private val base get() = currentBase

    private suspend fun fetchDocument(url: String): Document {
        var lastError: Exception? = null
        val candidates = (listOf(currentBase) + wecimaBases).distinct()
        for (candidate in candidates) {
            var attemptUrl = url
            for (known in wecimaBases) {
                attemptUrl = attemptUrl.replace(known, candidate)
            }
            try {
                val doc = app.get(attemptUrl).document
                currentBase = candidate
                return doc
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ErrorLoadingException("failed to reach WeCima")
    }

    private val categories = listOf(
        "$base/category/foreign-movies" to "أفلام أجنبية",
        "$base/category/arabic-movies" to "أفلام عربية",
        "$base/category/indian-movies" to "أفلام هندية",
        "$base/category/asian-movies" to "أفلام آسيوية",
        "$base/category/turkish-movies" to "أفلام تركية",
        "$base/category/anime-movies" to "أفلام أنمي",
        "$base/category/foreign-series" to "مسلسلات أجنبية",
        "$base/category/arabic-series" to "مسلسلات عربية",
        "$base/category/turkish-series" to "مسلسلات تركية",
        "$base/category/indian-series" to "مسلسلات هندية",
        "$base/category/asian-series" to "مسلسلات آسيوية",
        "$base/category/anime-series" to "مسلسلات أنمي",
        "$base/category/ramadan-series-2026" to "مسلسلات رمضان 2026",
        "$base/category/wwe-shows" to "عروض المصارعة"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst(".Thumb--GridItem a") ?: selectFirst("a") ?: return null
        val url = link.attr("abs:href").takeIf { it.isNotEmpty() } ?: return null
        val titleEl = selectFirst("h2.hasyear, strong.hasyear") ?: return null
        val title = titleEl.text().trim()
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()
            .takeIf { it.isNotEmpty() } ?: return null
        val year = titleEl.selectFirst("span.year")?.text()?.trim('(', ')', ' ')?.toIntOrNull()
        val bg = selectFirst("span.BG--GridItem")
        val poster = bg?.attr("data-src")?.ifBlank { null }
            ?: bg?.attr("data-lazy-style")
                ?.let { Regex("""--image:url\((.*?)\)""").find(it)?.groupValues?.get(1) }

        val isSeries =
            url.contains("%D9%85%D8%B3%D9%84%D8%B3%D9%84") || url.contains("%D8%AD%D9%84%D9%82%D8%A9")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNullOrBlank()) {
            val items = ArrayList<HomePageList>()
            for ((url, name) in categories) {
                try {
                    val doc = fetchDocument(url)
                    val list = doc.select("div.GridItem").mapNotNull { it.toSearchResponse() }
                    if (list.isNotEmpty()) items.add(HomePageList(name, list, isHorizontalImages = true))
                } catch (_: Exception) {
                }
            }
            if (items.isEmpty()) throw ErrorLoadingException()
            return newHomePageResponse(items)
        }

        val url = if (page > 1) "${request.data.trimEnd('/')}/page/$page/" else request.data
        val doc = fetchDocument(url)
        val items = doc.select("div.GridItem").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name ?: "القائمة", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "utf-8")
        val doc = fetchDocument("$base/?s=$q")
        return doc.select("div.GridItem").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDocument(url)

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("""\s+بجودة\s+.*$"""), "")?.trim()
            ?: throw ErrorLoadingException("Title not found on page: $url")
        val title = ArabicTitleParser.parse(rawTitle).title.ifBlank { rawTitle }
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")?.ifBlank { null }
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[href*='/genre/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }

        val isSeries = doc.selectFirst(".Seasons--Episodes") != null

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seriesId = doc.selectFirst(".List--Seasons--Episodes > a.selected[data-id]")
                ?.attr("data-id")

            fun parseEpisodes(doc: Document, seasonNum: Int?) {
                doc.select(".EpisodesList a[href], a.hoverable[href]").forEach { a ->
                    val epUrl = a.attr("abs:href").takeIf { it.isNotEmpty() } ?: return@forEach
                    val epName = a.selectFirst("episodetitle")?.text()?.trim()
                    val parsed = ArabicTitleParser.parse(epName ?: "")
                    episodes.add(newEpisode(epUrl) {
                        this.name = parsed.title.ifEmpty { null }
                        this.episode = parsed.episode
                        this.season = seasonNum
                    })
                }
            }

            val selectedSeason = doc.selectFirst(".List--Seasons--Episodes > a.selected[data-season]")
                ?.attr("data-season")
            parseEpisodes(doc, selectedSeason?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() })

            doc.select(".List--Seasons--Episodes > a.SeasonsEpisodes[data-season][data-id]")
                .forEach { tab ->
                    val dataSeason = tab.attr("data-season")
                    val dataId = tab.attr("data-id")
                    if (dataSeason == selectedSeason || dataId.isEmpty() || seriesId == null) {
                        return@forEach
                    }
                    try {
                        val seasonNum = Regex("""\d+""").find(dataSeason)?.value?.toIntOrNull()
                        val body = app.post(
                            "$base/ajax/Episode",
                            data = mapOf("season" to dataSeason, "post_id" to dataId)
                        ).document
                        parseEpisodes(body, seasonNum)
                    } catch (_: Exception) {
                    }
                }

            if (episodes.isEmpty()) throw ErrorLoadingException("No episodes found on page: $url")
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data

        val doc = try {
            fetchDocument(watchUrl)
        } catch (e: Exception) {
            return false
        }

        fun keyOf(url: String): String {
            val host = runCatching { url.substringAfter("//").substringBefore("/").lowercase() }
                .getOrDefault(url)
            val id = url.trimEnd('/').substringAfterLast('/')
            return "$host/$id"
        }

        val urls = LinkedHashMap<String, String>()
        val qualities = HashMap<String, Int?>()

        fun addTarget(url: String, quality: Int?) {
            if (url.isBlank()) return
            val key = keyOf(url)
            val existing = urls[key]
            if (existing == null) {
                urls[key] = url
                qualities[key] = quality
            } else if (qualities[key] == null && quality != null) {
                qualities[key] = quality
            }
        }

        val servers =
            doc.select(".WatchServersList li btn[data-url], .WatchServers > ul > li > btn[data-url]")
        for (btn in servers) {
            val encoded = btn.attr("data-url").replace("+", "")
            if (encoded.isBlank()) continue
            try {
                val embedUrl = Base64.decode("aHR0c$encoded", Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                addTarget(embedUrl, null)
            } catch (_: Exception) {
            }
        }

        doc.select("li.download-item.openLinkDown[data-href]").forEach { item ->
            val encoded = item.attr("data-href").replace("+", "")
            if (encoded.isBlank()) return@forEach
            val quality = WeCimaResolver.parseQuality(
                item.selectFirst("a.download-card span.resolution")?.text()
            )
            try {
                val url = Base64.decode("aHR0c$encoded", Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                addTarget(url, quality)
            } catch (_: Exception) {
            }
        }

        if (urls.isEmpty()) return false

        var emitted = 0
        for ((key, url) in urls) {
            try {
                if (WeCimaResolver.resolve(url, watchUrl, qualities[key], subtitleCallback, callback)) {
                    emitted++
                }
            } catch (_: Exception) {
            }
        }

        return emitted > 0
    }
}
