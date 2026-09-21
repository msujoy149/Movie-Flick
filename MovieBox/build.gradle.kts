plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

version = 1

cloudstream {
    description = "MovieBox provider with proven Global + IN backend failover"
    authors = listOf("Movie-Flick")
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    // Preserve the India/Tamil marker used by the original working provider.
    language = "ta"
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

// CloudStream + coroutines are supplied by the repository root build.gradle.kts.
