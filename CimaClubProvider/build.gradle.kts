// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 11

cloudstream {
    language = "ar"
    description = "CimaClub (Arabic). Ported from Abodabodd/re-3arabi (https://github.com/Abodabodd/re-3arabi) and maintained in TotalArab."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("TvSeries", "Movie")

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQX1z-0NYyzyEiFs4q7X9c8SSBR6kJtVl7f7A&s"
}
