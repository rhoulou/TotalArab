// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 12

cloudstream {
    language = "ar"
    description = "AnimeWitcher (Arabic). Ported from Abodabodd/re-3arabi (https://github.com/Abodabodd/re-3arabi) and maintained in TotalArab."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")

    iconUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/anime_witcher_round_icon.png"
}
