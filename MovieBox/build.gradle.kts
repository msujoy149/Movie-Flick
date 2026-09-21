version = 51

cloudstream {
    description = "Multi Language Movies and Series Provider"
    authors = listOf("NivinCNC", "Movie-Flick")
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    language = "hi"
    iconUrl = "https://h5-static.aoneroom.com/ssrStatic/club/public/_nuxt/logo.DMnCL3zY.svg"
    isCrossPlatform = false

    setRepo(
        System.getenv("GITHUB_REPOSITORY")
            ?: "msujoy149/Movie-Flick"
    )
}
