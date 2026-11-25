package app.loviedowpie.music

/**
 * Plug-in point for a catalog that is not on this phone.
 *
 * The user will provide an open-source YouTube-based library here; implement MusicSource and register it in MusicSources.kt.
 *
 * This class is not registered. [search] and [resolveStreamUrl] do nothing and
 * do not open a connection. Do not add an extractor, a YouTube call, or a
 * dependency in this file. Read MUSIC.md before shipping any online source:
 * some extractors are GPL-3, and YouTube's terms restrict downloading.
 *
 * DATA-FLOW: none.
 */
class OnlineSourceStub : MusicSource {
    override val id: String = "online"
    override val displayName: String = "Online"

    override suspend fun search(query: String): List<Track> = emptyList()

    override suspend fun resolveStreamUrl(track: Track): String? = null
}
