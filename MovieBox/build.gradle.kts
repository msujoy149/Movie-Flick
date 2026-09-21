plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 4

cloudstream {
    description = "Multi Language Movies and Series Provider"
    authors = listOf("Movie-Flick")
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    // The provider is shown as Hindi in CloudStream.
    language = "hi"
    iconUrl = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Logos/MovieBoxProvider/icon.png"
    isCrossPlatform = false

    setRepo(
        System.getenv("GITHUB_REPOSITORY")
            ?: "msujoy149/Movie-Flick"
    )
}

android {
    namespace = "com.movieflick.moviebox"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

// CloudStream, coroutines and the shared HTTP/JSON dependencies are supplied
// by the repository root build.gradle.kts.
