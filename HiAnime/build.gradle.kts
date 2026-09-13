plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.movieflick.hianime"
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
    description = "Hi Anime provider for Movie-Flick"
    authors = listOf("Movie-Flick")
    status = 1
    tvTypes = listOf(
        "Anime",
        "Movie",
        "TvSeries"
    )
    language = "en"
    iconUrl = "https://hianime.at/theme/images/logo.png"
    isCrossPlatform = true
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
