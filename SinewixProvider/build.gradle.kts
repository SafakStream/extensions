// Use an integer for version numbers
version = 1

cloudstream {
    description = "Sinewix içerik sağlayıcısı - film, dizi, anime ve daha fazlası"
    authors = listOf("SafakStream")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "AsianDrama", "Cartoon")
    iconUrl = "https://sinewix.com/favicon.png"

    isCrossPlatform = true
}
