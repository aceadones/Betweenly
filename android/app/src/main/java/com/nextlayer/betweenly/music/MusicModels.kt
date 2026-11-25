package com.nextlayer.betweenly.music

/**
 * A playlist saved on this phone. [shared] playlists also sync with the partner.
 * Nothing here is sent until the user marks the playlist shared.
 */
data class PlaylistRow(
    val id: String,
    val name: String,
    val shared: Boolean,
    val count: Int,
)

/** One album from this phone's library. [id] is a MediaStore album id, or "none". */
data class AlbumRow(
    val id: String,
    val name: String,
    val artist: String,
    val count: Int,
)

/**
 * What the Activity card may show while Music is open or parked.
 * Titles stay on this phone. This object is not serialised onto the wire.
 */
data class MusicGlance(
    val title: String,
    val artist: String,
    val playing: Boolean,
)

/** One row in a cross-library search. */
sealed class MusicHit {
    data class Song(val track: Track) : MusicHit()
    data class AlbumHit(val album: AlbumRow) : MusicHit()
    data class PlaylistHit(val row: PlaylistRow) : MusicHit()
}

internal data class SavedPlayback(
    val queue: List<Track> = emptyList(),
    val track: Track? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
)
