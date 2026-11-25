# Hear together

Two phones can play the same song while the music screen is open. Playback stays on that screen: there is no background player, no playback service, and nothing in this module starts when the process starts.

Audio from an online source is fetched by each phone on its own. Those bytes never go through the relay. A song that already lives on one phone is the exception, and only while a session is running: that file is encrypted and uploaded (20 MB or less). The relay stores ciphertext for 7 days. The file key rides only inside the sealed music message.

## When it starts and stops

`Application.onCreate` does not construct a player, scan the library, open a socket, or register a source. The first time the music screen is composed it builds a `MusicSession`. That session builds one `MediaPlayer` and loads the catalog.

Closing the screen (not a rotation) does all of this:

- sends `leave` if a session was open
- sets `AppState.fastPoll` back to false
- cancels the screen's coroutines
- releases the `MediaPlayer` and drops audio focus
- deletes `cacheDir/music`

Rotation keeps the same session, so the song does not stop and `leave` is not sent. Leaving the app pauses output. Coming back during an active session seeks forward by the time the screen was hidden, then continues. Solo playback resumes where it paused.

A music message that arrives while the screen is closed does not start the player. An invite is saved for 10 minutes and a local notification is posted. Opening Hear together reads that invite.

## Sources

`MusicSource` is the only way a catalog is searched and turned into something the player can open.

```kotlin
interface MusicSource {
    val id: String
    val displayName: String
    suspend fun search(query: String): List<Track>
    suspend fun resolveStreamUrl(track: Track): String?
}
```

`Track` is `id`, `title`, `artist`, `durationMs`, `sourceId`, and `extra`. `extra` is private to the source. The sealed message keeps only a file id and key from it, never a `content://` URI.

`MusicSources` always returns `LocalSource`. `LocalSource` lists `MediaStore.Audio` on this phone. Search filters those rows. Resolve returns a `content://media/...` URI. The screen asks for `READ_MEDIA_AUDIO` on Android 13+ and `READ_EXTERNAL_STORAGE` on Android 9–12. If that is denied, the library stays empty and nothing is scanned.

`OnlineSourceStub` is the plug-in point. It is not registered and it does not connect anywhere. The user will provide an open-source YouTube-based library here; implement MusicSource and register it in MusicSources.kt.

To add a source:

1. Implement `MusicSource`. Use an `id` other than `local`, 32 characters or fewer.
2. `resolveStreamUrl` must return an `https://` URL, a `content://media/` URI, or an absolute file path. `http://` is rejected. The platform player opens that address itself.
3. Call `MusicSources.register(source)` when the music screen opens. Do not call it from `Application.onCreate`. A second register with the same id replaces the first.
4. Both phones need the same source id. An online track is not uploaded. Each phone calls `resolveStreamUrl` for that id.

`register` only stores the instance. It does not search and it does not open a connection. The screen calls `search` when that chip is selected.

## Player

`MusicPlayer` wraps one `MediaPlayer`. It is created with the session and released when the screen closes. `prepareAsync` runs when a track actually starts, so nothing is decoded while the queue is sitting still. Audio attributes are media playback. The stream volume is the system volume. The player never calls `setVolume`.

Audio focus is requested at play time. A permanent loss pauses and tells the partner. A transient loss pauses and resumes when focus returns. Unplugging headphones pauses and tells the partner.

The queue supports next, previous, shuffle, and repeat (off, all, one). Shuffle and repeat are local. The track change is what gets synced. The position ticker runs only while the screen is visible.

## Listening together

Either person sends `invite` with `wake` true. The relay asks Google for an empty ping. The ping has no song and no name. The phone then shows the words "Listen together?" on the existing messages channel. Tapping it opens the Activity tab. The invite itself is applied when Hear together opens. That route is not a launch target of `MainActivity`.

The partner sends `accept` or `leave`. Leaving, or closing the screen, ends the session for the closer and sends `leave`. If the partner never answers, a banner appears after 45 seconds. The invite stays open, and the inbox keeps its faster poll, so a late accept still joins.

While a session is inviting, invited, or active, `AppState.fastPoll` is true and the inbox poller uses its 2 second interval. Solo browsing leaves the poller alone.

The sealed payload is:

```json
{"t":"music","op":"invite|accept|leave|queue|track|play|pause|seek|sync","ts":0,"pos":0,"track":{"id":"","title":"","artist":"","dur":0,"src":"","extra":""},"queue":[]}
```

`accept` may include `echo`, the invite's `ts`, so the inviter can measure the round trip. The queue is capped at 20 tracks. If the JSON would be over 60 KB, the queue is omitted before the single send. `Outbox` pads and seals it. The relay stores ciphertext only.

Either side may queue, change track, play, pause, or seek. Every message carries the sender's wall clock `ts` and position `pos`. The receiver aims at `pos + elapsed`, where elapsed is adjusted by a clock offset. That offset is estimated from the invite/accept round trip. Later one-way messages do not replace a round-trip offset. Whoever is playing sends `sync` about every 10 seconds. If the position is off by more than 400 ms, the receiver seeks. Rapid commands are debounced. An older `ts` does not overwrite a newer one.

A local file is not on the partner's phone. When that track is played in a session, this phone keeps playing it and, if it is 20 MB or smaller, uploads the bytes through `FileTransfer.upload`. The message then carries `fid` and `key` in `extra`. The partner shows "Downloading…" and writes the decrypted file to `cacheDir/music`, which is deleted when the screen closes. Larger files play only on the phone that has them. Online tracks skip this path: both phones resolve the same id.

## Before shipping an online source

Check the license and the site's terms before an online source is in a build you give to anyone.

Some open-source YouTube extractors are GPL-3. Linking a GPL-3 library into this app can require the whole app to be distributed under GPL-3, including offering source to people who receive the APK. That is a distribution choice, not something this module decides.

YouTube's terms restrict extracting streams and downloading audio. A library that does that can break those terms even when the code is open source. The owner has to decide whether a given source is allowed for the way this app is shipped.

This module does not include an extractor, a YouTube call, or a new dependency. `OnlineSourceStub` is only the place that code would go.
