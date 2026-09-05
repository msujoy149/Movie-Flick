@CloudstreamPlugin
class CTGFTPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CTGFTP())
    }
}
