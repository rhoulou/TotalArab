package com.totalarab.util

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Full diagnostic test for the TotalArab providers, run from inside the app.
 *
 * Trigger by searching the magic query "@@diag" (runs a fixed set of Arabic
 * queries) or "@@diag:<text>" (runs a single custom query). Every line is
 * prefixed with TOTALARAB so the whole report can be captured with:
 *
 *     adb logcat | grep TOTALARAB
 *
 * The diagnostic exercises the provider through its real search()/load()/
 * loadLinks() paths, then prints a structured report: search/load/loadLinks
 * pass counts, movie vs series classification, per-extractor OK/FAILED/SKIPPED
 * tables and a FAILURES list. Nothing is auto-considered a pass.
 */
object ProviderDiagnostics {

    private const val TAG = "TOTALARAB"

    val DEFAULT_QUERIES = listOf(
        "مشاهدة", "مسلسل", "فيلم", "عربي", "مترجم", "مدبلج", "لعبة",
        "إسطنبول", "طائر", "Avengers", "the", ""
    )

    /** Hosts handled by CloudStream's built-in loadExtractor (cannot be resolved here). */
    private val extractorHosts = listOf("lulustream", "dood", "dhcplay", "mixdrop", "playmogo")

    fun isDiagnosticQuery(query: String): Boolean = query.trim().startsWith("@@diag")

    private fun log(line: String) {
        println("$TAG|$line")
    }

    private fun hostOf(url: String): String {
        return runCatching { url.substringAfter("//").substringBefore("/").lowercase(Locale.ROOT) }
            .getOrDefault(url)
    }

    private fun isExtractorHost(host: String): Boolean =
        extractorHosts.any { host.contains(it) }

    private fun resolverName(host: String): String {
        return when {
            host.contains("abstream") -> "abstream"
            host.contains("savefiles") -> "savefiles"
            host.contains("vibuxer") -> "vibuxer"
            host.contains("miixdrop") -> "miixdrop"
            isExtractorHost(host) -> "loadExtractor($host)"
            else -> "generic"
        }
    }

    suspend fun run(provider: MainAPI, query: String) {
        val custom = query.trim()
            .removePrefix("@@diag")
            .removePrefix(":")
            .trim()
        val queries = if (custom.isNotEmpty()) listOf(custom) else DEFAULT_QUERIES

        val report = Report()
        try {
            for (q in queries) {
                searchQuery(provider, q, report)
                delay(400)
            }
            seriesDiscovery(provider, report)
        } catch (e: Exception) {
            report.failures += Failure(
                url = "<engine>",
                stage = "engine",
                extractor = "",
                http = "",
                reason = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            )
        }
        report.print(provider.name)
    }

    private suspend fun searchQuery(provider: MainAPI, q: String, report: Report) {
        log("  SEARCH: ${q.take(40)}")
        val start = System.currentTimeMillis()
        val results = try {
            provider.search(q) ?: emptyList()
        } catch (e: Exception) {
            report.searchesTotal++
            report.searchesFail++
            report.failures += Failure(
                url = "<search:${q.take(20)}>",
                stage = "search",
                extractor = "",
                http = "",
                reason = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            )
            log("      [FAIL] ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            return
        }
        report.searchesTotal++
        report.searchesOk++
        report.resultsDiscovered += results.size
        val elapsed = System.currentTimeMillis() - start
        log("      results=${results.size}  (${elapsed}ms)")
        if (results.isEmpty()) return

        val seen = HashSet<String>()
        var tested = 0
        for (res in results) {
            if (!seen.add(res.url)) continue
            if (tested >= 5) break
            tested++
            report.resultsTested++
            log("      [RESULT] ${res.name?.take(50)}  ${res.url.take(90)}")
            loadAndTest(provider, res.url, report)
            delay(350)
        }
    }

    private suspend fun loadAndTest(provider: MainAPI, url: String, report: Report) {
        val loadResponse = try {
            provider.load(url) ?: throw RuntimeException("load() returned null")
        } catch (e: Exception) {
            report.loadsFail++
            report.failures += Failure(
                url = url, stage = "load", extractor = "", http = "",
                reason = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            )
            log("  load: FAIL  ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            return
        }
        report.loadsOk++
        val poster = loadResponse.posterUrl
        val plot = loadResponse.plot
        log("  load: OK  type=${loadResponse.type}  name=${loadResponse.name.take(50)}")
        log("      poster=${if (poster.isNullOrBlank()) "NO" else "yes"}  " +
            "plot=${if (plot.isNullOrBlank()) "NO" else "yes"}")

        val episodes: List<Episode>? = when (loadResponse) {
            is TvSeriesLoadResponse -> loadResponse.episodes
            is AnimeLoadResponse -> loadResponse.episodes.values.flatten()
            else -> null
        }
        if (episodes == null) {
            report.movies++
            val dataUrl = (loadResponse as? MovieLoadResponse)?.dataUrl ?: loadResponse.url
            testLinks(provider, dataUrl, report)
            return
        }

        report.series++
        log("      episodes=${episodes.size}")
        val firstSeason = episodes.filter { it.season == null || it.season == 1 }.take(2)
        val otherSeasons = episodes.filter { it.season != null && it.season != 1 }.take(1)
        val picked = (firstSeason + otherSeasons).distinctBy { it.data }.take(3)
        for (ep in picked) {
            report.episodesTested++
            log("      ep ${ep.name ?: ""} (s${ep.season} e${ep.episode}) -> loadLinks()")
            testLinks(provider, ep.data, report)
            delay(350)
        }
    }

    /**
     * Tests every stream target on a page, classifying each host as
     * OK / FAILED / SKIPPED. WeCima's loadLinks already emits a "DBG|" link
     * carrying the per-host summary, so the report can tell a dead generic
     * host (FAILED) apart from an in-app extractor host (SKIPPED).
     */
    private suspend fun testLinks(provider: MainAPI, url: String, report: Report) {
        val emittedByHost = LinkedHashMap<String, Int>()
        var dbg = ""

        val ok = try {
            provider.loadLinks(url, false, {}, { link ->
                if (link.name.startsWith("DBG")) {
                    dbg = link.name
                } else {
                    val host = hostOf(link.url)
                    emittedByHost[host] = (emittedByHost[host] ?: 0) + 1
                }
            })
        } catch (e: Exception) {
            report.linksFail++
            report.failures += Failure(
                url = url, stage = "loadLinks", extractor = "", http = "",
                reason = "EXC ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            )
            log("      [loadLinks EXC] ${e.javaClass.simpleName}: ${e.message?.take(100)}")
            return
        }

        // WeCima reports via the DBG link; Akwam relies on emitted links only.
        if (dbg.isNotEmpty()) {
            val block = dbg.substringAfter("|targets=", "").substringBeforeLast("|", "")
            val targetCount = block.substringBefore("|").toIntOrNull() ?: 0
            val summary = block.substringAfter("|", "")
            if (dbg.contains("fetch=FAIL") || targetCount == 0) {
                report.linksFail++
                report.failures += Failure(
                    url = url, stage = "loadLinks", extractor = "", http = "",
                    reason = "no targets found on page"
                )
                log("      [FAIL] no targets found on page")
                return
            }
            val seenHosts = HashSet<String>()
            for (pair in summary.split(",")) {
                if (pair.isBlank()) continue
                val host = pair.substringBefore("=").trim()
                if (host.isBlank() || !seenHosts.add(host)) continue
                val emitted = emittedByHost[host] ?: 0
                val name = resolverName(host)
                when {
                    isExtractorHost(host) -> {
                        report.extractors
                            .getOrPut(name) { HostCounter() }
                            .skip += 1
                        log("      [SKIP] $name ($host) in-app built-in extractor")
                    }
                    emitted > 0 -> {
                        report.extractors
                            .getOrPut(name) { HostCounter() }
                            .ok += emitted
                        report.linksOk += emitted
                        log("      [OK] $name ($host) q=0 ${url.take(70)}")
                    }
                    else -> {
                        report.extractors
                            .getOrPut(name) { HostCounter() }
                            .fail += 1
                        report.linksFail++
                        report.failures += Failure(
                            url = url, stage = "loadLinks", extractor = name,
                            http = "", reason = url
                        )
                        log("      [FAIL] $name ($host) ${url.take(70)}")
                    }
                }
            }
            return
        }

        if (emittedByHost.isEmpty()) {
            if (!ok) {
                report.linksFail++
                report.failures += Failure(
                    url = url, stage = "loadLinks", extractor = "", http = "",
                    reason = "no targets found on page"
                )
                log("      [FAIL] no targets found on page")
            }
            return
        }
        for ((host, count) in emittedByHost) {
            report.extractors
                .getOrPut("direct-mp4") { HostCounter() }
                .ok += count
            report.linksOk += count
            log("      [OK] direct-mp4 ($host) ${url.take(70)}")
        }
    }

    /** Search can't reach /series/ pages on WeCima; pull them from category rows. */
    private suspend fun seriesDiscovery(provider: MainAPI, report: Report) {
        val paths = when (provider.name) {
            "WeCima" -> listOf(
                "category/arabic-series/", "category/turkish-series/",
                "category/foreign-series/", "category/indian-series/",
                "category/asian-series/", "category/anime-series/"
            )
            else -> listOf("series")
        }
        val base = provider.mainUrl.trimEnd('/')
        val discovered = LinkedHashSet<String>()
        for (path in paths) {
            if (discovered.size >= 8) break
            try {
                val doc = app.get("$base/$path").document
                val sel = if (provider.name == "WeCima")
                    "div.GridItem a[href*='/series/']"
                else
                    "a[href*='/series/']"
                doc.select(sel).forEach { a ->
                    val u = a.absUrl("href").ifBlank { a.attr("href") }
                    if (u.contains("/series/")) discovered.add(u)
                }
            } catch (e: Exception) {
                log("  [series-discovery] $path FAIL ${e.javaClass.simpleName}")
            }
            delay(350)
        }
        if (discovered.isEmpty()) {
            log("  [series-discovery] no /series/ URLs found")
            return
        }
        log("  [series-discovery] ${discovered.size} series URLs")
        var tested = 0
        for (url in discovered) {
            if (tested >= 3) break
            tested++
            log("  >>> series load() ${url.take(90)}")
            loadAndTest(provider, url, report)
            delay(350)
        }
    }

    private class HostCounter {
        var ok = 0
        var fail = 0
        var skip = 0
    }

    private data class Failure(
        val url: String,
        val stage: String,
        val extractor: String,
        val http: String,
        val reason: String
    )

    private class Report {
        var searchesTotal = 0
        var searchesOk = 0
        var searchesFail = 0
        var resultsDiscovered = 0
        var resultsTested = 0
        var loadsOk = 0
        var loadsFail = 0
        var linksOk = 0
        var linksFail = 0
        var movies = 0
        var series = 0
        var episodesTested = 0
        val extractors = LinkedHashMap<String, HostCounter>()
        val failures = mutableListOf<Failure>()

        fun print(providerName: String) {
            val border = "=".repeat(40)
            log("")
            log(border)
            log("${providerName.uppercase(Locale.ROOT)} PROVIDER TEST")
            log(border)
            log("Search queries:       ${searchesTotal}")
            log("Searches passed:      ${searchesOk}")
            log("Searches failed:      ${searchesFail}")
            log("Results discovered:   ${resultsDiscovered}")
            log("Results tested:       ${resultsTested}")
            log("load() passed:        ${loadsOk}")
            log("load() failed:        ${loadsFail}")
            log("loadLinks() tested:   ${linksOk + linksFail}")
            log("links success:        ${linksOk}")
            log("links failed:         ${linksFail}")
            log("Movies tested:        ${movies}")
            log("Series tested:        ${series}")
            log("Episodes tested:      ${episodesTested}")
            log("")
            log("Extractors:")
            if (extractors.isEmpty()) {
                log("  (none)")
            }
            for ((name, c) in extractors) {
                log(String.format("  %-28s OK=%d FAIL=%d SKIP=%d", name, c.ok, c.fail, c.skip))
            }
            log("")
            log("FAILURES:")
            if (failures.isEmpty()) {
                log("  (none)")
            }
            for ((i, f) in failures.withIndex()) {
                log("  [${i + 1}] ${f.url.take(90)}")
                log("      stage: ${f.stage}  extractor: ${f.extractor}  http: ${f.http}")
                log("      reason: ${f.reason.take(160)}")
            }
        }
    }
}
