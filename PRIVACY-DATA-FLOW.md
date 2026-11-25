# Betweenly: where does my data go?

Written so a person or an AI can answer that from this repo. Status: version 0.0.1, package `com.nextlayer.betweenly`. Every network call in the Kotlin, Rust, and Worker code is tagged `DATA-FLOW:`.

The product name is Betweenly. The Cloudflare Worker name in `relay/wrangler.toml` is still `loviedowpie-relay`. The GitHub repository `scripts/release.sh` publishes to is still `aceadones/Loviedowpie`. Phones talk to the relay URL baked in at build time (`relay.url`), not to GitHub.

## How a message is wrapped

1. JSON, UTF-8.
2. A 2-byte big-endian length, then zero padding out to a multiple of 512 bytes. JSON larger than 65,535 bytes is refused. Profile avatars are dropped earlier, at 65,000 bytes of JSON.
3. XChaCha20-Poly1305 under the pair’s session key. On the wire: 24-byte nonce, 8-byte counter, then ciphertext including a 16-byte tag. The counter is the additional data.
4. A short message is 512 bytes of padded plaintext and **560 bytes** of ciphertext. The relay accepts a blob up to 96 KB.

The session is 80 bytes (32-byte key, send counter, receive counter, 16-byte inbox, 16-byte outbox), stored with AES-256-GCM in the Android Keystore (alias `betweenly_wrap`). Domain separation for HKDF and SPAKE2 is the string `betweenly-v1`.

File bytes use a fresh 32-byte key, XChaCha20-Poly1305, additional data `file`, and a 24-byte nonce prepended to the ciphertext. The relay stores that blob at `/file/{id}` for 7 days, at most 24 MB. The key is base64 inside the sealed message. It is not sent on the file request.

## Who sees what

| Data | Leaves the phone? | Where | Who can read it |
|---|---|---|---|
| Location, battery, name, avatar | Yes, sealed | Relay mailbox | The paired partner. The relay stores ciphertext. |
| Pairing code | Not to the relay | The screen. Copy uses the clipboard. Share opens the Android share sheet with the code as plain text, and the app you pick receives it. | You and your partner. The relay sees a 16-byte HKDF rendezvous id and SPAKE2 messages, not the code. |
| Session key and file keys | Never in the clear | Phones, Keystore-wrapped | The two phones |
| FCM token | Yes | Relay KV `tok:{inbox}`, TLS only, 60-day TTL | The relay, and Google |
| Wake ping | Yes | Google FCM, body `{w:"1"}` | Google sees that a ping happened. The body has no message, name, or place. |
| Map tiles | Yes, when you open the map | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | The tile host sees the square and your IP. User-Agent `Betweenly/1.0`. Cached 30 MB on the phone. |
| Update check | Yes | Relay `/update/latest` and `/update/apk` | Public release metadata and the APK. The relay sees your IP. The phone does not call GitHub. |
| Chat, reactions, replies, unsends | Yes, sealed | Relay mailbox | The partner. Unsend is an id only, and `wake` is off. |
| Attachments, drawing PNGs, wallpaper photos, shared songs | Yes, sealed file plus a sealed pointer | Relay `/file/{id}` for 7 days, pointer in the mailbox | The partner, after decrypt. The key is only in the pointer. |
| Link previews | The sender’s phone fetches the site | The `https` URL in the message, then the card is sealed | The website sees the sender’s IP. The receiver does not contact the site. |
| Notification wording | No | This phone | Profile chooses the words. They are not sent. |
| Distance | No | Computed on this phone | Nobody else |
| Music likes, private playlists, on-phone songs | No | This phone | A shared playlist and a song you play together are the exceptions in the rows below. |
| Camera capture | No, until Send | `cacheDir/camera` | Send follows the attachment row. |

## Message types

`Wake` means the sender posts `?wake=1`, and the relay asks FCM for an empty ping if it has a token. Ciphertext for every row below is the session seal, except the file blob column.

Mailbox retention, all sealed types: `GET /box/{id}` deletes the queue. The queue holds at most 20 blobs (a 21st drops the oldest). A Durable Object alarm deletes whatever is left 24 hours after the last push to that mailbox. Pushing again slides that 24 hours.

| `t` | Fields | Plaintext size | Wake | File blob | Relay retention of the blob |
|---|---|---|---|---|---|
| `loc` | `t`, `lat`, `lon`, `acc`, `bat`, `chg`, `ts`, `live` | Short. 560-byte ciphertext. | Off for routine and live points. On for the last point when live stops. | None | Mailbox rules |
| `bat` | `t`, `bat`, `chg`, `ts` | Short. 560-byte ciphertext. | Off on a sharing tick with no fix. On when live stops and no fix was taken. | None | Mailbox rules |
| `share` | `t`, `on`, `ts` | Short. | On | None | Mailbox rules |
| `ring` | `t`, and for a call `rid` (8 hex) and `mode` = `call`. A body with only `t` still starts the alarm. | Short. | On | None | Mailbox rules |
| `ringstop` | `t`, `rid` | Short. | On | None | Mailbox rules |
| `ringack` | `t`, `rid` | Short. | Off | None | Mailbox rules |
| `ping` | `t` | Short. | On | None | Mailbox rules |
| `profile` | `t`, `name` (80), `avatar` (base64 JPEG, at most 48 KB, omitted if the JSON would pass 65,000 bytes) | Up to the mailbox cap. A photo is inside this blob, not `/file`. | On | None | Mailbox rules |
| `chat` | `t`, `id` (16 hex), `ts`, `text` (composer takes 10,000 characters, then shrinks to fit), optional `re` | Usually one 512-byte block. Long text grows by 512-byte steps. | On | None | Mailbox rules |
| `att` | `t`, `id`, `ts`, `kind` (`photo`, `video`, `sticker`, `gif`, `file`), `mime`, `name`, `size`, `fid` (32 hex), `key` (base64), `w`, `h`, `thumb` (base64 JPEG), `text` (empty), optional `re` | The pointer is small plus the thumb. The bytes are the file. | On | Ciphertext, max 24 MB stored. Chat refuses plaintext over 20 MB. Photos are JPEG, long edge 2048, before encrypt. | File: 7 days. Pointer: mailbox rules |
| `link` | `t`, `id`, `ts`, `text`, `url`, `title` (180), `desc` (300), `img` (base64 JPEG, fitted toward 320 px and 30 KB), optional `re` | The card is inside the sealed blob. Empty strings if the fetch failed. | On | None | Mailbox rules |
| `react` | `t`, `id`, `emoji` (16) | Short. The same emoji again clears that person’s reaction on the receiver. | On | None | Mailbox rules |
| `unsend` | `t`, `id` | Short. No message text. | Off | None. Local attachment files are deleted on each phone that applies it. | Mailbox rules |
| `wall` | `t`, `kind` (`default`, `color`, `image`), `color` (0–5), `fid`, `key`, `ts` | Short, unless you count the photo file. | Off | JPEG ciphertext when `kind` is `image`, plaintext cap 3 MB. | File: 7 days. Pointer: mailbox rules |
| `draw` | Layer: `t`, `ts`, `fid`, `key`, `size`, `layer` = `shared`, `page`. Full drawing: same without `layer`. New page: `t`, `op` = `newpage`, `page`, `ts`. | The PNG is the file. New page is short. | Off | PNG ciphertext, 24 MB cap. New page has no file. | File: 7 days. Pointer: mailbox rules |
| `music` | `t`, `op` (`invite`, `accept`, `leave`, `queue`, `track`, `play`, `pause`, `seek`, `sync`), `ts`, `pos`, optional `echo`, optional `track`, optional `queue` (20). Track: `id` (128), `title` (120), `artist` (80), `dur`, `src` (32), `extra` (file id and key only, or empty). | Kept under about 60 KB before padding, or the send drops extras. | On for `invite` only. Off for the rest. | Optional audio ciphertext, plaintext cap 20 MB, only while a session is sharing a local file. | File: 7 days. Command: mailbox rules |
| `plist` | `t`, `op` (`create`, `rename`, `add`, `remove`, `delete`, `snapshot`), `id` (32), `name` (80), `tracks` (80, each with its own `ts`), `ts` | A large snapshot can strip `extra` to fit. | Off | Same 20 MB audio upload when a shared playlist adds a local file that has not been uploaded. | File: 7 days. Edit: mailbox rules |

These JSON types never leave the phone: chat `gone` (unsend tombstone), chat `note`, and the local keys `rx`, `mx`, `gal`.

Pairing is not one of those types. It is `PUT` and `GET /pair/{rendezvous}/{a|b}`. The body is a base64 SPAKE2 message, at most 512 characters, TTL 10 minutes. Role `b` is limited to 3 writes.

## Feature notes

### Location, battery, distance

Share my location arms one inexact alarm about every 10 minutes (`AlarmManager.setAndAllowWhileIdle`). Each tick sends one `loc`, or a `bat` if there is no permission or the cached fix is older than 30 minutes. `live` is false. `wake` is off.

Live location is a foreground service. The request interval is 5 seconds and 5 metres. A point is sent when you have moved at least 5 metres, or 30 seconds after the previous send. Those points use `wake` off. Stopping sends one `loc` with `live` false, or a `bat`, with `wake` on.

Turning sharing off sends `share` with `on` false and `wake` on, and does not send coordinates again. The partner’s phone still has the last coordinates. The map can show them as paused until unpair.

Distance is a haversine on this phone between Play services `lastLocation` and the partner’s last point. It is not uploaded. The home screen formats miles for US, GB, LR, and MM, otherwise metres under 1 km and kilometres above.

Battery percent and charging come from `BatteryManager` sticky extras on the sending phone. There is no separate battery upload.

### Ring, ping, notifications

A call ring is `ring` plus `rid` and `mode` `call`, with wake. Stop is `ringstop` with wake. Dismiss on the receiver sends `ringack` with wake off. The tone is the system alarm sound, played locally, for at most two minutes.

Ping is `{"t":"ping"}` with wake. The receiver builds the notice locally.

Notification mode defaults to Off: title “Betweenly”, text “New message”. On shows the partner’s name and the decrypted text, or a short label (Photo, Video, Sticker, GIF, File, New drawing, Listen together?). Custom shows only the saved line (48 characters). The alarm disguise replaces the title “Alarm”; the body stays “Tap to dismiss”. The lock screen public version is “Betweenly” / “Notification” and does not include a name or the word ring. None of these strings are sent. They survive unpair.

Drawing notices follow that wording and can be switched off. The picture is not attached. Message mode Off still posts a notice. It changes the words. Pings are shown even while the app is in front. Other message notices are skipped while `MainActivity` is in the foreground.

### Chat and link previews

The sender strips `rx`, `mx`, and `gal` before sealing. History on this phone is `chat.db`. Each row’s JSON is Keystore AES-GCM. Attachment bytes are the same wrapping at `filesDir/attachments/{id}.bin`. The UI lists the newest 500 rows. Clear history deletes rows and attachment files on this phone only. It does not tell the partner and does not delete the wallpaper file.

A link card is fetched only by the sender: HTTPS, 3 redirects, 5 second socket timeout, 512 KB of HTML, 2 MB image, then a JPEG fitted to 320 px and about 30 KB. The User-Agent on that fetch is a mobile Chrome string. The website sees the sender’s IP and is not told the app name. The receiver gets `title`, `desc`, and `img` inside the sealed message.

“Save photos and videos to the gallery” is off by default. Auto-save and an explicit save of a photo or video write Pictures/Betweenly or Movies/Betweenly. An explicit save of another file, on Android 10 and newer, writes Downloads/Betweenly. Unpair does not delete those copies. Android 9 cannot insert that Downloads row.

### Drawing and wallpaper

A shared send uploads only that phone’s transparent layer. The composite stays local, under `filesDir/drawings/`. The drawing widget reads those files. It does not use the network. Its system update period is 30 minutes, and it also refreshes when a PNG is saved.

Wallpaper `kind` `default` or `color` is a small sealed message. `image` also uploads a JPEG of at most 3 MB. The newest `ts` wins.

### Music

Solo playback does not use the network. Listen together sends `music` only while that mode is on. An invite wakes. Later commands do not. A local song of 20 MB or less can be uploaded for the partner; the decrypted copy in `cacheDir/music` is deleted when the session releases. Larger songs are not uploaded.

Shared playlist edits are `plist` with `wake` off. Private playlists are not sent. Offline copies are written only when you tap Download for offline, into `filesDir/music/offline`, default cap 500 MB. An `https` download in that store is only reached if a future `MusicSource` returns an `https` URL. Redirects are not followed. The built-in library does not call it. `OnlineSourceStub` is not registered.

The playback notice, if Keep playing is on, says “Music” and “Playing” or “Paused”. No song title. It stops after 10 minutes of pause. Default is off.

`betweenly-music.db` is created the first time Music opens or a sealed playlist message arrives. Unpair does not delete it or the offline folder. A file key for a shared song can remain in a row’s `extra` column.

### Updates and FCM

`/update/latest` is public JSON (`versionCode`, `versionName`, `sha256`, `size`, `notes`). `/update/apk` is the public APK. The phone checks SHA-256 before it offers the installer. A quiet check runs at most once every 24 hours. Nothing about the pair is sent.

The FCM token is registered with Google only when the four `fcm.*` build fields are set. It is then `PUT /token/{inbox}`. The worker stores it for 60 days and uses it only to send `{w:"1"}`. Analytics collection is off in the manifest, and FCM auto-init is off.

## Not used

No analytics, no ads, no accounts, no Firebase Analytics, no Google Maps SDK, no phone number, no email. The app does not log message text, keys, or coordinates. `allowBackup` is false, and backup and device-transfer rules exclude the whole app data directory.

## Data at rest

See the table in [README.md](README.md#data-at-rest). In short: the session and the chat bodies are encrypted with the Keystore. Drawings, the wallpaper JPEG, and offline songs are app-private files. Unpair deletes the session, partner profile, partner location, chat database, attachments, wallpaper, and drawings. It keeps your name, your photo, the FCM token, notification wording, the animation switch, and the last update-check time. It does not delete the music database, offline songs, map tiles, gallery copies, or the onboarding-done flag.

## Code map

- `core/`: cryptography only. No network and no disk. `core/examples/partner.rs` is a desktop test partner and does talk to the relay, through `curl`. Its `loc` command sends `wake`. The phone’s routine `loc` does not.
- `relay/`: opaque blobs, ciphertext files for 7 days, FCM tokens, public update objects.
- `android/`: the only phone code that touches GPS, the network, and storage.
- `scripts/release.sh`: a developer uploads the public APK. Phones do not run it.
- `ui/PrivacyScreen.kt`: a short in-app summary. It does not list chat, files, or the 7-day file TTL. This file is the exhaustive one.
