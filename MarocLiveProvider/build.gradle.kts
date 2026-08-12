// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 3

cloudstream {
    language = "ar"
    description = "Moroccan live TV & radio (SNRT, 2M, Medi1 TV/Radio, Chada, StoryChannel) for CloudStream 3."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf("Live")

    iconUrl = "https://flagcdn.com/w320/ma.png"
}
