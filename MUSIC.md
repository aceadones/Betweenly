# Music

Music is a player first. Listen together is a mode of it. Solo playback does not touch the network.

Nothing in this module starts from `Application.onCreate`. The first time `MusicScreen` is composed it builds a `MusicSession` (or reuses one that was parked). That session opens the database, builds one `MediaPlayer`, and loads the source catalog. Closing the screen releases those, unless Keep playing in background is on.

## Screens

The Activity card is a quick look only. It reads `MusicBridge.glance` (title, artist, playing). It does not construct a session and it has no controls.

`MusicScreen` is the module. Home lists Library (songs, albums, liked), Playlists, Queue, and Offline, plus search across those. A mini player sits at the bottom while a song is loaded. Now Playing opens from that bar with a scale and translation from the bottom center. Only transform and opacity move. Reduce animations snaps that motion.

Now Playing is dark in both themes. The accent `#D6245A` is the progress arc and the glow behind the circular artwork. Dragging around the ring seeks. Remaining time sits at the bottom of the ring. Shuffle, previous, play/pause, next, and repeat sit under it. Next songs is the queue; a sideways swipe removes a row. Like is stored in the likes table.

Lists are `LazyColumn`. Artwork is a generated rose gradient, or embedded album art decoded on demand through `MediaMetadataRetriever` into a small cache and downsampled. Nothing downloads artwork.

## When it starts and stops

Rotation keeps the same session. `close(configChange = true)` returns immediately, so the song does not stop and `leave` is not sent.

Leaving Music (not a rotation):

- If Keep playing is off, `releaseFully` runs: leave a listen-together session if one is open, set `AppState.fastPoll` false, cancel the session coroutines, release the `MediaPlayer`, drop audio focus, clear the art cache, and delete `cacheDir/music`.
- If Keep playing is on and a track is loaded, the session stays open and `MusicPlayService` posts a low-importance ongoing notification. The words are "Music" and "Playing" or "Paused". No song title. Actions are play/pause and next. There is no media session. The service does not own the player. If the notification cannot start, playback pauses. After 10 minutes of pause the session releases fully. The switch defaults to off.

Hiding the app while Music is still composed pauses output, unless Keep playing is on and a song is playing. Coming back during an active listen-together session seeks forward by the time the screen was hidden, unless output was held for the notification (that case does not add the gap).

A sleep timer (15, 30, 45, or 60 minutes, from the Now Playing menu) stops playback, leaves listen together if it is on, releases the player, stops the notification, and wipes `cacheDir/music`. If the screen is still open, the session stays so the next play creates a new player. If the screen is gone, the session releases fully. The timer is a coroutine delay, not a wakeup alarm.

A music message that arrives while the screen is closed does not start the player. An invite is saved for 10 minutes and a local "Listen together?" notification is posted. Opening Music reads that invite.

## Saved on this phone

`music/MusicDb.kt` creates `betweenly-music.db` the first time Music opens, or the first time a sealed playlist message arrives. It is not created at process start. Tables hold playlists, playlist tracks, likes, the queue, offline rows, and a few settings (keep-playing, offline cap). The file never leaves the phone except for the shared-playlist row below.

Unpair does not delete `betweenly-music.db` or `filesDir/music/offline`. A shared song's file id and key can remain in a row's `extra` column. Clear downloads is the control that removes offline copies.

Offline copies live in `filesDir/music/offline`. A song that is already on this phone is offline without a copy. Download for offline is a user action for a shared song or, later, an online song. The cap defaults to 500 MB (choices 100, 250, 500, 1000, 2000) and is shown in Music settings. Clear downloads deletes those copies only. `https` downloads are only reached if a future source returns an `https` URL. Redirects are not followed. `OnlineSourceStub` never reaches that path.

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

`Track` is `id`, `title`, `artist`, `durationMs`, `sourceId`, and `extra`. `extra` is private to the source. A sealed message keeps only a file id and key from it, never a `content://` URI.

`MusicSources` always returns `LocalSource`. The Sources sheet shows Local as on. If nothing else is registered it also shows "Online source" as not installed. `LocalSource` lists `MediaStore.Audio` on this phone, including albums. Search and album lists are capped. Resolve returns a `content://media/...` URI. The screen asks for `READ_MEDIA_AUDIO` on Android 13+ and `READ_EXTERNAL_STORAGE` on Android 9–12. If that is denied, the on-phone lists stay empty and nothing is scanned.

`OnlineSourceStub` is the plug-in point. It is not registered. `search` returns nothing and `resolveStreamUrl` returns null. It does not connect anywhere, and it is not a YouTube client. Do not add an extractor, a scraper, or a new dependency there.

To add a source inside the app:

1. Implement `MusicSource`. Use an `id` other than `local`, 32 characters or fewer.
2. `resolveStreamUrl` must return an `https://` URL, a `content://media/` URI, or an absolute file path under app storage. `http://` is rejected. The platform player opens that address itself.
3. Call `MusicSources.register(source)` when the music screen opens. Do not call it from `Application.onCreate`. A second register with the same id replaces the first.
4. Both phones need the same source id for listen together. An online track is not uploaded. Each phone calls `resolveStreamUrl` for that id.

`register` only stores the instance. It does not search and it does not open a connection.

## Playlists and the queue

Playlists, likes, and the queue are in the database above. Create, rename, delete, add, remove, and reorder (up and down) stay on this phone until a playlist is marked shared.

A shared playlist syncs with the partner. Both can add and remove songs. The sealed payload is:

```json
{"t":"plist","op":"create|rename|add|remove|delete|snapshot","id":"","name":"","tracks":[],"ts":0}
```

`wake` is off. The relay stores ciphertext only. Last-writer-wins per track id by `ts`. A snapshot drops a missing track only when that track's `ts` is older than or equal to the snapshot. Reorder has no separate op; a shared reorder sends a snapshot. Turning sharing off stops sending and ignores later ops for that id. It does not delete the partner's copy. Deleting a shared playlist does send `delete`.

Adding a song from this phone to a shared playlist uploads it when it is 20 MB or smaller, through the same encrypted file path as listen together. The message then carries `fid` and `key`. Larger files still sync their title and id, and the sender sees a banner. Private playlists never upload. Turning sharing on uploads at most 15 local files, then sends one snapshot.

The solo queue holds up to 100 songs. While listen together is on, the queue on the wire is capped at 20.

## Listen together

Solo playback never sets `fastPoll` and never calls `Outbox`.

Either person sends `invite` with `wake` true. The relay asks Google for an empty ping. The ping has no song and no name. The phone shows "Listen together?" on the existing messages channel. The invite is applied when Music opens.

The partner sends `accept` or `leave`. Leaving, or closing the screen while a session is open (and keep-playing is off), sends `leave`. If the partner never answers, a banner appears after 45 seconds. The invite stays open, and the inbox keeps its faster poll, so a late accept still joins.

While a session is inviting, invited, or active, `AppState.fastPoll` is true and the inbox poller uses its 2 second interval. That poller runs only while the activity is resumed. There is no background poller. A listen-together session that keeps playing after you leave the app will drift until you come back.

The sealed payload is:

```json
{"t":"music","op":"invite|accept|leave|queue|track|play|pause|seek|sync","ts":0,"pos":0,"track":{"id":"","title":"","artist":"","dur":0,"src":"","extra":""},"queue":[]}
```

`accept` may include `echo`, the invite's `ts`, so the inviter can measure the round trip. If the JSON would be over 60 KB, extras are stripped or the queue is omitted before the single send. `Outbox` pads and seals it. The relay stores ciphertext only.

Either side may queue, change track, play, pause, or seek. Every message carries the sender's wall clock `ts` and position `pos`. The receiver aims at `pos + elapsed`, where elapsed is adjusted by a clock offset. That offset is estimated from the invite/accept round trip. Later one-way messages do not replace a round-trip offset. Whoever is playing sends `sync` about every 10 seconds. If the position is off by more than 400 ms, the receiver seeks. An older `ts` does not overwrite a newer one.

A local file is not on the partner's phone. When that track is played in a session, this phone keeps playing it and, if it is 20 MB or smaller, uploads the bytes through `FileTransfer.upload`. The partner writes the decrypted file to `cacheDir/music`, which is deleted when the session releases. Larger files play only on the phone that has them. Online tracks skip this path: both phones resolve the same id.

## Player

`MusicPlayer` wraps one `MediaPlayer`. It is created with the session and released when the session releases. `prepareAsync` runs when a track actually starts. Audio attributes are media playback. The stream volume is the system volume. The player never calls `setVolume`.

Audio focus is requested at play time. A permanent loss pauses. A transient loss pauses and resumes when focus returns. Unplugging headphones pauses.

Shuffle and repeat (off, all, one) are local. The track change is what gets synced while listen together is on. The position ticker runs only while the screen is visible and a song is playing. It does not wake the radio, and the Activity card does not recompose on each tick.

## Online streaming options

No online source ships in this build. These are the options if one is added later. Each one is a product and licensing decision, not something this module decides.

### (a) Pluggable extension inside Betweenly

Implement `MusicSource` and `MusicSources.register` as above. The player, queue, offline cap, and listen-together protocol stay as they are. An online track is not uploaded; each phone resolves the same id.

Trade-offs: a GPL-3 extractor linked into this app can require the whole app to be distributed under GPL-3, including source for people who receive the APK. YouTube's terms restrict extracting streams and downloading audio, even when the extractor is open source. Ads, age gates, and region locks then sit inside playback. Offline copies of those streams are often not allowed. Each phone fetches the audio itself, so battery and data cost sit on both phones, and a long session is a long radio use. This module does not include that code.

### (b) A separate app

Another app can play the audio and talk to Betweenly through the existing encrypted channel, or through an Intent that only carries a track id both people already understand. Betweenly would still send the small sealed music commands. The audio bytes never enter this process.

Trade-offs: no GPL link into this APK, and no new dependency here. The other app has its own terms, ads, and DRM. Handoff can fail when that app is missing. Listen together then depends on both apps staying in step. Offline rights belong to the other app. Battery and data belong to the other app. This repo does not implement that app.

### (c) Official players

YouTube's IFrame or Android player can play inside a view the user can see, with YouTube's own UI and ads. Spotify's App Remote (and similar official SDKs) control the user's own Spotify app. The stream stays in that official player.

Trade-offs: this follows the service's terms more closely than extracting a URL. Ads, DRM, and login stay with that service. Offline playback follows that service's rules, not the 500 MB cap. The SDK is a new dependency and a new account, which this app does not have today. Battery and data are whatever that player uses. A hidden stream URL is not available, so `resolveStreamUrl` cannot point `MediaPlayer` at it. These routes are not wired up.

Check the license and the site's terms before any of these is in a build you give to anyone.
