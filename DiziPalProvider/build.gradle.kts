// Use an integer for version numbers
version = 1

cloudstream {
    description = "DiziPal üzerinden dizi ve film izleme imkanı sunar."
    authors = listOf("SafakStream")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=dizipal1587.com&sz=128"
}
