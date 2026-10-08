version = 1

cloudstream {
    language = "tr"
    description = "InatBOX - Türk film, dizi ve canlı TV"
    authors = listOf("Safak")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    iconUrl = "https://raw.githubusercontent.com/cencbit/ssl/main/icon.png"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
}
