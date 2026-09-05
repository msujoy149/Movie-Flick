plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movieflick.ctgftp"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
}

version = 1

cloudstream {
    description = "CTG FTP provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    // FTP providers use the Bangladesh locale/flag.
    language = "bn"
    iconUrl = "https://flagcdn.com/w320/bd.png"
    isCrossPlatform = true
}
