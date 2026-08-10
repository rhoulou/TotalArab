// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 11

cloudstream {
    language = "ar"
    description = "YouTube (Arabic). Ported from Abodabodd/re-3arabi (https://github.com/Abodabodd/re-3arabi) and maintained in TotalArab."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("Movie", "Live")

    iconUrl = "https://raw.githubusercontent.com/Abodabodd/Oldarabrepo/refs/heads/main/img/IMG_%D9%A2%D9%A0%D9%A2%D9%A5%D9%A1%D9%A2%D9%A0%D9%A6_%D9%A1%D9%A7%D9%A2%D9%A6%D9%A1%D9%A6.jpg"
}

dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.2")
}
