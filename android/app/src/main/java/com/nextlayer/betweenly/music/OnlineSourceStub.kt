package com.nextlayer.betweenly.music

/**
 * Plug-in point for a catalog that is not on this phone.
 *
 * This class is not registered. [search] and [resolveStreamUrl] do nothing
 * and do not open a connection. Do not add an extractor, a scraper, or a
 * new dependency in this file. MUSIC.md, "Online streaming options", is the
 * decision record for a future source.
 *
 * DATA-FLOW: none.
 */
class OnlineSourceStub : MusicSource {
    override val id: String = "online"
    override val displayName: String = "Online"

    override suspend fun search(query: String): List<Track> = emptyList()

    override suspend fun resolveStreamUrl(track: Track): String? = null
}
