plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.gradle")
}

// Bump the plugin version so CloudStream installs the fixed resolver build.
version = 10

cloudstream {
    description = "Zoryva CloudStream provider"
    authors = listOf("Zoryva")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    language = "hi"
    iconUrl = "https://zoryva.me/icon-512.png"
    isCrossPlatform = true
    setRepo(
        System.getenv("GITHUB_REPOSITORY")
            ?: "msujoy149/Movie-Flick"
    )
}

android {
    namespace = "com.movieflick.zoryva"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.3")
}
