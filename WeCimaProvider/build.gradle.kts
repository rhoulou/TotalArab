// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 9

cloudstream {
    language = "ar"
    description = "Movies & series from WeCima (Arabic). Scrapes wecima.cx directly from the phone - latest Arabic/Foreign/Indian/Asian/Turkish/Anime movies and series, seasons + episodes, embed servers (lulustream, doodstream, mixdrop, ...). Falls back to wecima.watch/wecima.movie/wecima.click on domain changes."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // verified working against wecima.cx
    tvTypes = listOf("TvSeries", "Movie")

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTMF29SVl7LOlFaI8GHbFYxuvm-Z_Q5s7IPWNMhx_SbkQ&s"
}
