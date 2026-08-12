// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 12

cloudstream {
    language = "ar"
    description = "Movies & series from akwam (Arabic). Scrapes akwam.it directly from the phone - latest movies/series/shows plus Arabic/Turkish/Asian/Foreign/Indian sections, seasons + episodes, direct mp4 links. Falls back to ak.sv which always redirects to the live akwam domain."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // verified working against akwam.it
    tvTypes = listOf("TvSeries", "Movie", "Anime", "AsianDrama")

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQq1OMQIgGXbMMPF8_szyAFJAjGV50VjCpZrR-Bgmlwvg&s"
}
