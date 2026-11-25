package app.loviedowpie.music

/**
 * One place songs can come from. The screen asks [search] and, when a row
 * is played, [resolveStreamUrl]. Nothing here is constructed at process start.
 *
 * DATA-FLOW: the interface does not touch the network. Each implementation
 * documents its own.
 */
interface MusicSource {
    val id: String
    val displayName: String

    suspend fun search(query: String): List<Track>

    /** A URL, content URI, or file path [android.media.MediaPlayer] can open. Null if it cannot. */
    suspend fun resolveStreamUrl(track: Track): String?
}

/**
 * A song in a catalog or in the queue.
 *
 * [extra] is source-specific. Local tracks keep a content URI and, once shared,
 * the encrypted file id and key. Those bytes ride only inside the sealed
 * music message.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sourceId: String,
    val extra: String = "",
) {
    fun matches(other: Track?): Boolean =
        other != null && id == other.id && sourceId == other.sourceId
}
