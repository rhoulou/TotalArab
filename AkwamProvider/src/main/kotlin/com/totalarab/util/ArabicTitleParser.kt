package com.totalarab.util

/**
 * Parses Arabic (and Latin) TV series episode/series titles into a clean
 * series title, season, episode and language, ignoring quality/release tags.
 *
 * Arabic ordinals in both masculine (الموسم الخامس) and feminine
 * (الحلقة الرابعة) forms are supported, including compounds such as
 * الحلقة الرابعة والثلاثون (34) and الحلقة الحادية عشرة (11).
 */
data class ParsedTitle(
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val language: String = "unknown",
    val isFinalEpisode: Boolean = false
)

object ArabicTitleParser {

    private val unitNumbers = mapOf(
        "الاول" to 1, "الاولي" to 1,
        "الثاني" to 2, "الثانيه" to 2,
        "الثالث" to 3, "الثالثه" to 3,
        "الرابع" to 4, "الرابعه" to 4,
        "الخامس" to 5, "الخامسه" to 5,
        "السادس" to 6, "السادسه" to 6,
        "السابع" to 7, "السابعه" to 7,
        "الثامن" to 8, "الثامنه" to 8,
        "التاسع" to 9, "التاسعه" to 9,
        "العاشر" to 10, "العاشره" to 10,
        "الحادي" to 1, "الحاديه" to 1
    )

    private val tensNumbers = mapOf(
        "عشر" to 10, "عشره" to 10,
        "العشرون" to 20, "العشرين" to 20,
        "الثلاثون" to 30, "الثلاثين" to 30,
        "الاربعون" to 40, "الاربعين" to 40,
        "الخمسون" to 50, "الخمسين" to 50,
        "الستون" to 60, "الستين" to 60,
        "السبعون" to 70, "السبعين" to 70,
        "الثمانون" to 80, "الثمانين" to 80,
        "التسعون" to 90, "التسعين" to 90
    )

    private val seasonKeywords = setOf("الموسم", "season", "سيزون")
    private val episodeKeywords = setOf("الحلقه", "episode", "ep")
    private val dubbedWords = setOf("مدبلج", "مدبلجه", "دبلجه", "دبلج")
    private val subbedWords = setOf("مترجم", "مترجمه", "ترجمه")
    private val finalWords = setOf("الاخيره", "اخيره", "الاخير", "نهائيه", "final")
    private val seriesWord = "مسلسل"

    private val qualityTags = setOf(
        "web-dl", "webdl", "bluray", "hdr", "hevc", "x264", "x265",
        "1080p", "720p", "480p", "2160p", "4k"
    )

    /** Normalize letters and strip diacritics/tatweel/zero-width marks. */
    private fun normalize(s: String): String {
        var out = s
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
        out = out.replace(
            Regex("[\\u064B-\\u0652\\u0640\\u0670\\u061C\\u200B-\\u200F\\u2066-\\u2069]"),
            ""
        )
        return out.trim().replace(Regex("\\s+"), " ").lowercase()
    }

    /** Map a single (possibly و-prefixed) token to an Arabic ordinal value. */
    private fun ordinalValue(token: String): Int? {
        val n = normalize(token)
        if (n.isEmpty()) return null
        unitNumbers[n]?.let { return it }
        tensNumbers[n]?.let { return it }
        if (n.startsWith("و")) {
            val rest = n.substring(1)
            unitNumbers[rest]?.let { return it }
            tensNumbers[rest]?.let { return it }
        }
        return null
    }

    private data class Phrase(val value: Int?, val numeric: Boolean, val consumed: Int, val isFinal: Boolean)

    /**
     * Parse the ordinal (or numeric, or final-descriptor) phrase that follows a
     * season/episode keyword. Consumes at most 3 tokens.
     */
    private fun parseOrdinalPhrase(tokens: List<String>, start: Int): Phrase {
        var sum = 0
        var consumed = 0
        var hasWord = false
        var idx = start
        while (idx < tokens.size && consumed < 3) {
            val n = normalize(tokens[idx])
            val num = n.toIntOrNull()
            if (num != null) {
                return if (!hasWord) Phrase(num, true, consumed + 1, false) else break
            }
            val ord = ordinalValue(n)
            if (ord != null) {
                sum += ord
                hasWord = true
                consumed++
                idx++
                continue
            }
            break
        }
        if (hasWord) return Phrase(sum, false, consumed, false)
        if (idx < tokens.size && normalize(tokens[idx]) in finalWords) {
            return Phrase(null, false, consumed + 1, true)
        }
        return Phrase(null, false, consumed, false)
    }

    fun parse(input: String): ParsedTitle {
        if (input.isBlank()) return ParsedTitle("")
        val tokens = input.trim().split(Regex("\\s+"))
        val kept = mutableListOf<String>()
        var seasonNumeric: Int? = null
        var seasonWord: Int? = null
        var episodeNumeric: Int? = null
        var episodeWord: Int? = null
        var language = "unknown"
        var isFinal = false
        var i = 0
        while (i < tokens.size) {
            val n = normalize(tokens[i])
            when {
                n == seriesWord -> {
                    i++
                }
                n in seasonKeywords -> {
                    val ph = parseOrdinalPhrase(tokens, i + 1)
                    if (ph.numeric) seasonNumeric = ph.value else if (ph.value != null) seasonWord = ph.value
                    i += 1 + ph.consumed
                }
                n in episodeKeywords -> {
                    val ph = parseOrdinalPhrase(tokens, i + 1)
                    if (ph.isFinal) isFinal = true
                    if (ph.numeric) episodeNumeric = ph.value else if (ph.value != null) episodeWord = ph.value
                    i += 1 + ph.consumed
                }
                n in dubbedWords -> {
                    language = "dubbed"
                    i++
                }
                n in subbedWords -> {
                    language = "subbed"
                    i++
                }
                n in qualityTags -> {
                    i++
                }
                else -> {
                    kept.add(tokens[i])
                    i++
                }
            }
        }
        return ParsedTitle(
            title = kept.joinToString(" ").trim(),
            season = seasonNumeric ?: seasonWord,
            episode = episodeNumeric ?: episodeWord,
            language = language,
            isFinalEpisode = isFinal
        )
    }
}
