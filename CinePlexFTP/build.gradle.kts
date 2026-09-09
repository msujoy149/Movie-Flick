plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.movieflick.cineplexftp"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

version = 1

cloudstream {
    description = "Cine Plex FTP provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    language = "bn"
    iconUrl = "https://cineplexbd.net/favicon.png?v=2"
    isCrossPlatform = true
}

dependencies {
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
