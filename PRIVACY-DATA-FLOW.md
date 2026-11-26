# Betweenly: where does my data go?

Written so a person or an AI can answer that from this repo. Status: version 0.0.1, package `com.nextlayer.betweenly`. Every network call in the Kotlin, Rust, and Worker code is tagged `DATA-FLOW:`.

The product name is Betweenly. The Cloudflare Worker name in `relay/wrangler.toml` is still `loviedowpie-relay`. The GitHub repository `scripts/release.sh` publishes to is still `aceadones/Betweenly`. Pair traffic goes to the relay URL baked in at build time (`relay.url`). An update check may also read the public GitHub releases API for that repo. A private repo (401, 403, or 404) is ignored.

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
| Map tiles | Yes, when you open the map | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | The tile host sees the square and your IP. User-Agent `Betweenly/<version> (Android; com.nextlayer.betweenly)`. At most 2 downloads at once. Cached 30 MB on disk and about 18 MB in memory. |
| Update check | Yes, at launch (at most every 6 hours) and when you tap Check for updates | Relay `/update/latest` and `/update/apk`, and `https://api.github.com/repos/aceadones/Betweenly/releases/latest` | Public release metadata and the APK. The relay sees your IP. GitHub sees your IP only when that repo answers. A 401, 403, or 404 is ignored. An APK is downloaded only after you tap Update, from the relay or from a GitHub host on the allowlist (`github.com`, `api.github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`, `github-releases.githubusercontent.com`). The phone checks SHA-256 and that the APK is signed with the same certificate as this install. The Duet install button can still hand `https://github.com/aceadones/Duet/releases` to the browser. That browser request is separate. |
| Chat, reactions, replies, unsends | Yes, sealed | Relay mailbox | The partner. Unsend is an id only, and `wake` is off. |
| Attachments, drawing PNGs, wallpaper photos | Yes, sealed file plus a sealed pointer | Relay `/file/{id}` for 7 days, pointer in the mailbox | The partner, after decrypt. The key is only in the pointer. |
| Link previews | The sender’s phone fetches the site | The `https` URL in the message, then the card is sealed | The website sees the sender’s IP. The receiver does not contact the site. |
| Notification wording | No | This phone | Profile chooses the words. They are not sent. |
| Distance | No | Computed on this phone | Nobody else |
| Duet’s library, likes, and private playlists | No | Duet, on this phone | Betweenly does not read them. A shared playlist is the `plist` row below, and only as ciphertext. |
| Duet now-playing line | No | SharedPreferences `duet` on this phone | Title, artist, and playing. Not a key. Idle after 60 seconds without a new line. |
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
| `music` | The JSON object Duet supplied. Betweenly requires `t` = `music` and refuses UTF-8 over 60,000 bytes. It does not add fields. | Rejected above 60,000 bytes, before padding. | On only when `op` is `invite`. Off for every other op. | None. Betweenly does not upload audio for this type. | Mailbox rules |
| `plist` | The JSON object Duet supplied. Betweenly requires `t` = `plist` and the same 60,000 byte cap. | Same cap. | Off | None | Mailbox rules |

These JSON types never leave the phone: chat `gone` (unsend tombstone), chat `note`, and the local keys `rx`, `mx`, `gal`.

Pairing is not one of those types. It is `PUT` and `GET /pair/{rendezvous}/{a|b}`. The body is a base64 SPAKE2 message, at most 512 characters, TTL 10 minutes. Role `b` is limited to 3 writes.

## Feature notes

### Location, battery, distance

Share my location arms one inexact alarm about every 10 minutes (`AlarmManager.setAndAllowWhileIdle`). Each tick sends one `loc`, or a `bat` if there is no permission or the cached fix is older than 30 minutes. `live` is false. `wake` is off.

Live location is a foreground service. The request interval is 5 seconds and 5 metres. A point is sent when you have moved at least 5 metres, or 30 seconds after the previous send. Those points use `wake` off. Stopping sends one `loc` with `live` false, or a `bat`, with `wake` on.

Turning sharing off sends `share` with `on` false and `wake` on, and does not send coordinates again. The partner’s phone still has the last coordinates. The map can show them as paused until unpair.

Distance is a haversine on this phone between Play services `lastLocation` and the partner’s last point. It is not uploaded. The home screen formats miles for US, GB, LR, and MM, otherwise metres under 1 km and kilometres above.

Battery percent and charging come from `BatteryManager` sticky extras on the sending phone. A separate encrypted `bat` message is sent when the percent moves by 5 or more, or charging changes, while the process is alive. An inexact alarm about every 30 minutes sends again if the process was dead. This runs even when location sharing is off, and it does not include coordinates. `wake` is off. The partner’s screen shows that percent and how long ago it arrived. A reading an hour old or more is shown muted.

### Ring, ping, notifications

A call ring is `ring` plus `rid` and `mode` `call`, with wake. Stop is `ringstop` with wake. Dismiss on the receiver sends `ringack` with wake off. The tone is the system alarm sound, played locally, for at most two minutes.

Ping is `{"t":"ping"}` with wake. The receiver builds the notice locally.

Notification mode defaults to Off: title “Betweenly”, text “New message”. On shows the partner’s name and the decrypted text, or a short label (Photo, Video, Sticker, GIF, File, New drawing, Listen together?). A listen-together notice does not include the song title. Custom shows only the saved line (48 characters). The alarm disguise replaces the title “Alarm”; the body stays “Tap to dismiss”. The lock screen public version is “Betweenly” / “Notification” and does not include a name or the word ring. None of these strings are sent. They survive unpair. The invite’s button opens Duet, or the Duet releases page in the browser when Duet is not installed. Betweenly does not request that page itself.

Drawing notices follow that wording and can be switched off. The picture is not attached. Message mode Off still posts a notice. It changes the words. Pings are shown even while the app is in front. Other message notices are skipped while `MainActivity` is in the foreground.

### Chat and link previews

The sender strips `rx`, `mx`, and `gal` before sealing. History on this phone is `chat.db`. Each row’s JSON is Keystore AES-GCM. Attachment bytes are the same wrapping at `filesDir/attachments/{id}.bin`. The UI lists the newest 500 rows. Clear history deletes rows and attachment files on this phone only. It does not tell the partner and does not delete the wallpaper file.

A link card is fetched only by the sender: HTTPS, 3 redirects, 5 second socket timeout, 512 KB of HTML, 2 MB image, then a JPEG fitted to 320 px and about 30 KB. The User-Agent on that fetch is a mobile Chrome string. The website sees the sender’s IP and is not told the app name. The receiver gets `title`, `desc`, and `img` inside the sealed message.

“Save photos and videos to the gallery” is off by default. Auto-save and an explicit save of a photo or video write Pictures/Betweenly or Movies/Betweenly. An explicit save of another file, on Android 10 and newer, writes Downloads/Betweenly. Unpair does not delete those copies. Android 9 cannot insert that Downloads row.

### Drawing and wallpaper

A shared send uploads only that phone’s transparent layer. The composite stays local, under `filesDir/drawings/`. The drawing widget reads those files. It does not use the network. Its system update period is 30 minutes, and it also refreshes when a PNG is saved.

Wallpaper `kind` `default` or `color` is a small sealed message. `image` also uploads a JPEG of at most 3 MB. The newest `ts` wins.

### Duet and listen together

Betweenly does not play songs and does not read the on-phone library. Duet is a separate app. It binds locally to `MusicBridgeService`. That binder is not a network call.

When Duet asks to send, Betweenly seals the JSON and posts it with `Outbox.send`. The relay sees ciphertext. Wake is on only for a `music` object whose `op` is `invite`. Duet cannot ask Betweenly to seal a chat, a location, or any other type: those are rejected, and so is a payload over 60,000 bytes. Betweenly does not attach a file, a key, the session, or the relay URL.

The partner’s phone opens the blob inside `Inbox` and passes the JSON string to the registered Duet messenger. If no Duet is registered, a `music` invite posts a local notice and every other `music` or `plist` payload is dropped. The notice uses `NotificationTexts`. Default mode is content-free. The button opens Duet, or `https://github.com/aceadones/Duet/releases` in the system browser. Betweenly’s HTTP client does not request that page.

`STATUS` back to Duet is `paired`, the partner’s name, and `partnerOnline` set to the string `unknown`. It is sent when Duet registers and when that pair or name changes. It is local IPC.

The Activity card stores the latest title, artist, playing flag, and arrival time in SharedPreferences `duet`. Nothing in that file is uploaded. A line with no update for 60 seconds is shown as idle. The card re-reads on resume and when Duet pushes a line. It does not poll.

On each process start, Betweenly deletes `betweenly-music.db`, `filesDir/music`, and `cacheDir/music` if an older build left them. That delete does not use the network. A file key from the old player does not stay past the next launch.

### Updates and FCM

`/update/latest` is public JSON (`versionCode`, `versionName`, `sha256`, `size`, `notes`). `/update/apk` is the public APK. The same check reads the public GitHub releases API for `aceadones/Betweenly`. A 401, 403, or 404 is ignored and does not crash. The phone checks SHA-256 and the signing certificate before it offers the installer. A quiet check runs at most once every 6 hours. Nothing about the pair is sent. Version code is compared first.

The FCM token is registered with Google only when the four `fcm.*` build fields are set. It is then `PUT /token/{inbox}`. The worker stores it for 60 days and uses it only to send `{w:"1"}`. Analytics collection is off in the manifest, and FCM auto-init is off.

## Not used

No analytics, no ads, no accounts, no Firebase Analytics, no Google Maps SDK, no phone number, no email. The app does not log message text, keys, or coordinates. `allowBackup` is false, and backup and device-transfer rules exclude the whole app data directory.

## Data at rest

See the table in [README.md](README.md#data-at-rest). In short: the session and the chat bodies are encrypted with the Keystore. Drawings and the wallpaper JPEG are app-private files. Unpair deletes the session, partner profile, partner location, chat database, attachments, wallpaper, and drawings. It keeps your name, your photo, the FCM token, notification wording, the animation switch, the last update-check time, the saved update offer, and the Duet now-playing line. It clears this phone’s last-sent battery sample so the next pair sends a fresh one. The next process start deletes a leftover music database and song copies from an older build. Unpair does not delete map tiles, gallery copies, or the onboarding-done flag.

## Code map

- `core/`: cryptography only. No network and no disk. `core/examples/partner.rs` is a desktop test partner and does talk to the relay, through `curl`. Its `loc` command sends `wake`. The phone’s routine `loc` does not.
- `relay/`: opaque blobs, ciphertext files for 7 days, FCM tokens, public update objects.
- `android/`: the only phone code that touches GPS, the network, and storage.
- `scripts/release.sh`: a developer uploads the public APK. Phones do not run it.
- `ui/PrivacyScreen.kt`: a short in-app summary. It does not list chat, files, or the 7-day file TTL. This file is the exhaustive one.
