// Use an integer for version numbers
version = 1

cloudstream {
    description = "BeyazElma - Türk canlı spor kanalları (beIN Sports, S Sport, TRT Spor, Tabii Spor, Exxen, Tivibu ve daha fazlası)"
    authors = listOf("SafakStream")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     */
    status = 1

    tvTypes = listOf("Live")
    iconUrl = "https://beyazelma78.com/favicon.png"

    isCrossPlatform = true
}
