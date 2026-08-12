// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 12

cloudstream {
    language = "ar"
    description = "Asia2TV 2 (Arabic). Ported from Abodabodd/re-3arabi (https://github.com/Abodabodd/re-3arabi) and maintained in TotalArab."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("AsianDrama", "TvSeries", "Movie")

}
