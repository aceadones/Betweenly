# Betweenly

A private app for two people in a long-distance relationship. Android only, sideloaded. Package `com.nextlayer.betweenly`, version **0.0.1** (version code 1). One pair. No accounts.

> Pair once, then talk, draw, listen, and share where you are. The relay stores ciphertext. Only the two phones can read it.

## Highlights

- 🔐 Pair with an 8-character code. The code is never sent. Both phones show the same 6-digit safety number.
- 💬 Chat: text, reply, reactions, unsend, photos, video, files, stickers, GIFs, and link cards.
- 📍 Location about every 10 minutes, or live while you leave it on. Battery % is sent on its own, including when sharing is off. Distance is calculated on your phone.
- 🔔 Ring their phone for up to two minutes, or send a short ping. Notification words are chosen on each phone.
- 🎨 A shared drawing page, plus a home-screen drawing widget.
- 🎵 Listen together through Duet, a separate music app. Betweenly carries the encrypted messages. It does not play songs.
- 🔄 In-app updates from the relay, and from a public GitHub release when one exists, checked at most once every 6 hours unless you ask.

Accent is a crimson rose (`#D6245A`, deep `#A3123E`, soft `#FFE3EA`). Light screens use a warm blush background. Dark screens use deep wine (`#1A0A12` toward `#2A0F1C`). Success green and destructive red stay semantic colors.

## Screenshots

Screenshots are not in this repository yet. When they are added, put them here:

| | |
|---|---|
| Onboarding and pairing | `docs/screenshots/pair.png` |
| Home (location, battery, distance, ring, ping) | `docs/screenshots/home.png` |
| Chat | `docs/screenshots/chat.png` |
| Drawing | `docs/screenshots/draw.png` |
| Duet card | `docs/screenshots/music.png` |

Until those files exist, the tables below are the description an AI should trust.

## Install 0.0.1

A person installs the APK. Later updates can come from the relay, or from a public GitHub release of this repo when that release is the newer build (see [Updates](#updates)).

1. On a computer, open the GitHub release **v0.0.1** for `aceadones/Betweenly` (the repository name is still Loviedowpie). The asset is `Betweenly-v0.0.1.apk`. `scripts/release.sh` is what publishes that release.
2. Copy the APK to the phone. The build is **arm64-v8a only**.
3. Allow installation from that source (Android calls this “install unknown apps”).
4. Install and open Betweenly. Both people need this same build. The crypto domain string is `betweenly-v1` (`core/src/lib.rs`). A different build cannot pair with it.
5. Finish the onboarding pages, then one phone shows a code and the other types it.

The APK’s relay address is baked in at build time from `android/local.properties` (`relay.url`). Two phones pair only if they were built against the same relay.

## Feature reference

Every sealed message is JSON, padded, then encrypted. Exact fields, sizes, and retention are in [PRIVACY-DATA-FLOW.md](PRIVACY-DATA-FLOW.md). “Relay” means the Cloudflare Worker in `relay/`. “Phone only” means the bytes are not uploaded by this app.

### Pairing

| | |
|---|---|
| What it does | One phone shows an 8-character code (alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, about 40 bits). The other types it. Both run SPAKE2. Each shows a 6-digit safety number (`NNN NNN`) to read aloud. They match confirms the pair. They differ unpairs. |
| Data | The code, a one-way rendezvous id (HKDF-SHA256 of the code, info `rendezvous`, domain `betweenly-v1`), SPAKE2 messages, then the first profile (name and optional avatar). |
| Where it goes | The relay stores the SPAKE2 messages under `PUT/GET /pair/{rendezvous}/{a\|b}`. It never receives the code. Copy puts the code on the clipboard. Share opens the Android share sheet with the code as plain text, and the app you pick then has it. After the handshake, the profile is a sealed mailbox message. |
| Encryption | SPAKE2 (Ed25519, identity `betweenly-v1`) plus HKDF-SHA256. The session key is XChaCha20-Poly1305 afterwards. The relay sees the handshake, not the key. |
| Retention | Pairing records expire after 10 minutes. Role `b` (the joiner) may write at most 3 times; the relay then returns HTTP 429 and that code is burned. The session stays on the phones until unpair. |
| Permissions | Internet. |
| Battery and data | Polls the relay about every 2 seconds for up to 10 minutes, only while the pairing screen is waiting. Each handshake body is at most 512 characters. |
| Turn it off | Cancel on the pairing screen. Unpair later from Profile. |

The phone that shows the code is role `a` (initiator) and writes mailbox B / reads mailbox A. The joiner is role `b`, reversed. A wrong code usually times out, because the rendezvous ids differ. A substituted handshake is caught when the partner’s profile will not open (`CodesDidNotMatch`).

### Profile

| | |
|---|---|
| What it does | Your name (up to 80 characters, newlines become spaces) and a JPEG photo. The partner sees them on the home card, the map marker, and (if notification mode is On) in notices. |
| Data | `{"t":"profile","name","avatar"}`. `avatar` is base64 of a JPEG at most 48 KB. A photo that would push the JSON past 65,000 bytes is sent with an empty avatar. |
| Where it goes | Relay mailbox, sealed. `wake` is on, so the relay asks Google FCM for an empty ping. |
| Encryption | Session XChaCha20-Poly1305. The avatar bytes are inside that ciphertext, not a separate file. |
| Retention | Mailbox rules (read-and-delete, or 24 hours after the last write to that mailbox, and at most 20 blobs). Each phone keeps the partner’s name and photo until unpair. Your own name and photo stay after unpair. |
| Permissions | None beyond Internet. The photo comes from the gallery picker. |
| Battery and data | One small message when you save, plus one empty FCM wake. |
| Turn it off | Change the name or remove the photo on Profile. Unpair stops sharing it with that partner. The copy already on their phone remains until they unpair. |

### Location sharing

| | |
|---|---|
| What it does | While Share my location is on, an inexact alarm about every 10 minutes takes one balanced-power fix and sends it. The first fix is sent when you turn sharing on, so the partner does not wait 10 minutes. If location permission is missing, or the last cached fix is older than 30 minutes, that tick is battery only. Battery is also sent on its own when sharing is off. See [Battery](#battery). |
| Data | `loc`: `t`, `lat`, `lon`, `acc` (metres), `bat` (0–100 or -1), `chg`, `ts`, `live` (false). Or `bat`: `t`, `bat`, `chg`, `ts`. |
| Where it goes | Relay mailbox. These routine updates use `wake` off. They sit until the partner’s app pulls (app open, process start, or some other wake). |
| Encryption | Session XChaCha20-Poly1305. |
| Retention | Mailbox rules. The partner’s phone keeps the last point until unpair. Turning sharing off sends `share` with `on: false` and does not send the coordinates again. Their map can still show that last point, labelled as paused. |
| Permissions | Fine location, with coarse as the fallback. Background location so the 10-minute alarm can run with the screen off (Android 10+). Optional ignore-battery-optimizations so Doze delays the alarm less. |
| Battery and data | One alarm wakeup about every 10 minutes, one fused fix, one padded message (560 bytes of ciphertext for a short JSON). No polling loop. Reboot re-arms the alarm. Reboot does not start live mode. |
| Turn it off | Location tab, Share my location off. That also turns live off. The widget’s Live button is a separate control. |

A `share` message is `{"t":"share","on","ts"}` with `wake` on.

### Live location

| | |
|---|---|
| What it does | Replaces the 10-minute alarm with a foreground service. A balanced-power request asks about every 5 seconds and at least 5 metres. The phone sends when you have moved at least 5 metres, or when 30 seconds have passed. Stopping sends one last `loc` with `live: false` (or `bat` if no fix was taken), with `wake` on. |
| Data | Same `loc` fields, `live: true` while the service runs. |
| Where it goes | Relay mailbox. Moving updates use `wake` off. The stop message uses `wake` on. |
| Encryption | Session XChaCha20-Poly1305. |
| Retention | Same as location sharing. |
| Permissions | Fine or coarse location, foreground service, foreground-service location, notifications (the ongoing “live location is on” notice). |
| Battery and data | The radio and GPS stack stay in a low-power mode the whole time the service runs. A stationary phone sends about one small message every 30 seconds. This is the expensive mode. |
| Turn it off | Live location switch, the widget, or Stop on the ongoing notification. If the system refuses the foreground service, the app falls back to the 10-minute alarm. |

### Battery

| | |
|---|---|
| What it does | Shows the partner’s battery percent, whether it is charging, and how long ago that reading arrived. A reading an hour old or more is shown muted. This phone reads its own percent with `BatteryManager` sticky extras. |
| Data | Integer `bat`, boolean `chg`. |
| Where it goes | Inside an encrypted `bat` message on the relay, and also inside `loc` when a location fix is sent. |
| Encryption | Same as location. |
| Retention | Last value stays on the partner’s phone until a newer message or unpair. |
| Permissions | None for reading your own battery. |
| Battery and data | A send when the percent moves by 5 or more, or charging changes, while the process is alive. An inexact alarm about every 30 minutes sends again if the process was dead. Reboot re-arms that alarm when the phone is paired. `wake` is off. No coordinates. |
| Turn it off | Unpair stops it. Turning location sharing off does not stop the battery message. |

### Distance

| | |
|---|---|
| What it does | Great-circle distance between this phone’s last fused fix and the partner’s last coordinates. Miles for region US, GB, LR, and MM. Otherwise metres under 1 km, kilometres above. |
| Data | Coordinates already stored on this phone. |
| Where it goes | Phone only. The distance number is not sent. |
| Encryption | Not applicable. |
| Retention | Not stored as its own field. Recomputed when the screen opens. |
| Permissions | Location, so Play services can return this phone’s last fix. If location is denied, the distance line stays empty. |
| Battery and data | One `lastLocation` read while the home screen is open. No network call from this app for the number. Play services may use the network as part of Android’s own location stack. |
| Turn it off | Deny location, or leave the home screen. There is no separate switch. |

### Ring

| | |
|---|---|
| What it does | Rings the partner’s phone with the system alarm tone, at alarm volume, for at most two minutes. The sender’s button stays on Ringing until they stop, the partner dismisses, or two minutes pass. |
| Data | `{"t":"ring","rid":"<8 hex>","mode":"call"}`. Stop is `{"t":"ringstop","rid"}`. Dismiss on the receiver sends `{"t":"ringack","rid"}`. An older `{"t":"ring"}` with no id still starts the alarm and cannot be stopped by id. |
| Where it goes | Relay mailbox. `ring` and `ringstop` use `wake` on (empty FCM ping). `ringack` uses `wake` off; the sender sees it on the next inbox pull. |
| Encryption | Session XChaCha20-Poly1305. The sound is local. Nothing about the tone is uploaded. |
| Retention | Mailbox rules. The receiver’s service stops at two minutes even if `ringstop` never arrives. |
| Permissions | Notifications, full-screen intent (so it can show over the lock screen), foreground service (media playback type), wake lock for the short time the tone starts, vibrate. |
| Battery and data | Two small messages plus two empty FCM wakes for a full ring-and-stop. The receiver’s CPU and speaker run until dismiss, stop, or two minutes. |
| Turn it off | Tap the same control to stop, or the receiver taps Dismiss. There is no “never ring me” switch inside the app. Android notification settings for the alarm channel, and Do Not Disturb, still apply. The lock-screen version of the notice is always the generic “Betweenly” / “Notification”. |

The words on the receiver are chosen on that phone: default title “Alarm” and body “Tap to dismiss”, or an alarm disguise saved in Profile (48 characters). The notice does not include a name and does not contain the word ring.

### Ping

| | |
|---|---|
| What it does | One short notice on the partner’s phone. It does not loop and it does not start the alarm tone. A second ping from the same phone is ignored for 2 seconds. |
| Data | `{"t":"ping"}`. No text, no place, no name. |
| Where it goes | Relay mailbox, `wake` on. The words are built on the receiving phone. |
| Encryption | Session XChaCha20-Poly1305. |
| Retention | Mailbox rules. The notice is local and dismisses when tapped. |
| Permissions | Notifications on the receiver. |
| Battery and data | One small ciphertext plus one empty FCM wake. |
| Turn it off | Don’t tap Ping. The notice follows the message-notification wording (On / Off / Custom). Android can mute the channel. Tapping the notice opens the app and does not force a tab. |

### Notifications and discreet text

| | |
|---|---|
| What it does | Local notices for chat, drawings, pings, listen-together invites, and the ring alarm. Profile chooses the words. The lock screen always uses a public version: title “Betweenly”, text “Notification”. |
| Data | The wording, the custom line, and the alarm disguise. Partner name and message text are used only in On mode, and only from data already decrypted on this phone. |
| Where it goes | Phone only. None of this text is sent. |
| Encryption | Not applicable. Stored in app prefs. |
| Retention | Kept across unpair. Custom text and the alarm disguise are capped at 48 characters. |
| Permissions | Post notifications. |
| Battery and data | No network. |
| Turn it off | Profile → Notifications. |

Wording modes (default **Off**):

- **On.** Title is the partner’s name. Body is the message text (one line, 140 characters), or a short label such as Photo, Video, Sticker, GIF, or File. A drawing says “New drawing”. A ping says “New message”. A listen-together invite says “Listen together?”. The notice does not include the song title.
- **Off.** Title “Betweenly”, text “New message”, for every event including drawings and invites. The notice is still posted.
- **Custom.** The saved line is the whole title. An empty custom line falls back to Betweenly / New message.

There is a separate **New drawings** switch. Off means a drawing does not post a notice. The picture is never attached. Message notices are not suppressed by an in-app switch; Off mode changes the words. `Store.notifyMessages` is saved and kept across unpair, and the current UI does not read it. Android’s own notification settings can block the channels. Notices are not posted while the activity is in the foreground, except a ping and a listen-together invite. The invite’s button opens Duet, or the Duet releases page when Duet is not installed.

### Chat: text, reactions, reply, unsend

| | |
|---|---|
| What it does | Text up to 10,000 characters (shrunk further if the sealed JSON would exceed the codec). Long-press or swipe to reply (`re` is the other message’s id). Seven reaction shortcuts, default ❤️ 😂 😮 😢 🙏 👍 🔥, editable on this phone. Tap an emoji to set it; tap the same one to clear it. That tap sends the emoji again; the receiver clears the reaction when the same emoji arrives twice. Unsend turns your own message into a tombstone on both phones and deletes its attachment files. |
| Data | `chat`: `t`, `id` (16 hex), `ts`, `text`, optional `re`. `react`: `t`, `id`, `emoji` (up to 16). `unsend`: `t`, `id`. Local-only keys `rx`, `mx`, and `gal` are stripped before send. |
| Where it goes | Relay mailbox. Text, replies, and reactions use `wake` on. Unsend uses `wake` off. |
| Encryption | Session XChaCha20-Poly1305. On disk, the JSON is AES-256-GCM under the Android Keystore key `betweenly_wrap`, in `chat.db`. |
| Retention | Mailbox rules. This phone keeps history until you clear it (Chat menu) or unpair. The list UI shows the newest 500 rows; older rows can remain in the database until clear or unpair. Clear history deletes messages and attachment files on this phone only. It does not tell the partner and does not remove the wallpaper file. A failed unsend is remembered (up to 40 ids) and retried. |
| Permissions | Notifications if a background message should show a notice. |
| Battery and data | One small ciphertext per send, plus one empty FCM wake except for unsend. While the app is open and resumed, the inbox is pulled every 5 seconds (every 2 seconds while Duet has a listen-together session active). That pull stops when you leave the app. |
| Turn it off | Don’t send. Clear history for a local wipe. Unpair wipes the chat. Mute the channel in system settings. |

### Photos, videos, files, stickers, GIFs

| | |
|---|---|
| What it does | Gallery photos are re-encoded to JPEG, long edge 2048. Videos, GIFs, stickers (PNG or WebP, including from the keyboard), and other files are sent as themselves, up to 20 MB. A small JPEG thumb rides inside the sealed message. The partner downloads the file when the message arrives. |
| Data | `att`: `t`, `id`, `ts`, `kind` (`photo`, `video`, `sticker`, `gif`, or `file`), `mime`, `name`, `size`, `fid` (32 hex), `key` (base64 of 32 bytes), `w`, `h`, `thumb`, `text` (empty), optional `re`. |
| Where it goes | File ciphertext: `PUT /file/{fid}` on the relay. The id and key: sealed `att` in the mailbox, `wake` on. The key is not in the file request. |
| Encryption | File bytes: a fresh random key, XChaCha20-Poly1305, additional data `file`, nonce prepended. The pointer message uses the session key. On this phone the decrypted bytes are stored again under the Keystore AES-GCM key at `filesDir/attachments/{id}.bin`. |
| Retention | Relay file objects expire 7 days after upload, max 24 MB of ciphertext, and an id cannot be overwritten (HTTP 409). Download retries for about a minute because the file can appear slightly after the message. Local copies last until clear history or unpair. Opening a video or file in another app writes a short-lived copy under `cacheDir/chat-open`. |
| Permissions | The system photo picker or the Open document picker. The app does not hold a standing photo-library permission. |
| Battery and data | One encrypted upload up to 20 MB, one small pointer message, one empty wake, and one download on the other phone. Nothing is downloaded until a message points at it. |
| Turn it off | Don’t attach. “Save photos and videos to the gallery” in chat settings is off by default and applies only to photos and videos, not stickers, GIFs, or files. Photos you save land in Pictures/Betweenly, videos in Movies/Betweenly, and other files (Android 10 and newer) in Downloads/Betweenly. Those copies stay after unpair. |

### Link previews

| | |
|---|---|
| What it does | If the text contains an `https://` URL, the sender’s phone fetches the page and builds a card (title, description, small JPEG). The message is still sent if the fetch fails, with empty card fields. |
| Data | `link`: `t`, `id`, `ts`, `text`, `url`, `title` (180), `desc` (300), `img` (base64 JPEG, fitted to 320 px and about 30 KB, then shrunk again to fit the mailbox), optional `re`. |
| Where it goes | The sender’s phone contacts the website in the URL (HTTPS only, up to 3 redirects, 512 KB of HTML, 2 MB image cap, 5 second timeout, 12 second overall). The card is then inside the sealed mailbox message. The receiver does not contact that site. |
| Encryption | The card is sealed with the session key. The fetch to the website is ordinary HTTPS. That site sees the sender’s IP. The User-Agent on that fetch is a mobile Chrome string, so the site is not told the app name. |
| Retention | Mailbox rules, then the card lives in chat history on both phones. |
| Permissions | Internet. |
| Battery and data | One page fetch and maybe one image fetch, only when you send a link. No prefetch of links you only look at. |
| Turn it off | Don’t send `https` URLs. There is no separate preview switch. |

### Chat background

| | |
|---|---|
| What it does | Both phones share a chat background: default, one of six color indexes (0–5), or a photo. The newer timestamp wins. |
| Data | `wall`: `t`, `kind` (`default`, `color`, or `image`), `color`, `fid`, `key`, `ts`. A photo also uploads the JPEG (max 3 MB) as a file. |
| Where it goes | Sealed mailbox message, `wake` off. A photo’s ciphertext goes to `PUT /file/{fid}`. Each phone keeps `filesDir/chat_wallpaper.jpg` when the background is a photo. |
| Encryption | Session key for the message. File key for the JPEG, same scheme as attachments. |
| Retention | Mailbox rules and 7-day file TTL. The local file stays until a non-photo background replaces it, or unpair. |
| Permissions | Photo picker for a picture background. |
| Battery and data | A color or default is one small message and no wake, so the partner sees it on the next pull. A photo is one encrypted upload. |
| Turn it off | Chat settings → background → default. That sends `kind: default`. |

### Camera

| | |
|---|---|
| What it does | An in-app camera (CameraX) for a photo or a video. The file stays in `cacheDir/camera` until you send it. Send hands it to chat as a normal attachment. Closing or retaking deletes the unsent file. |
| Data | A local JPEG or video file, then the attachment path above. |
| Where it goes | Phone only until Send. Send follows photos and videos. |
| Encryption | None until send. Then the attachment scheme. |
| Retention | Unsent files are deleted when you leave or retake. Sent files follow attachment retention. |
| Permissions | Camera. Record audio for video sound. If audio is denied, the video is recorded without sound. Camera hardware is optional; the rest of the app runs without it. |
| Battery and data | The camera uses power only while that screen is open. No upload before Send. |
| Turn it off | Leave the camera. Deny the camera permission. The chat attach menu is the only way in. The dock has no camera tab. |

### Shared drawing and the drawing widget

| | |
|---|---|
| What it does | A studio with solid, pencil, marker, highlighter, spray, calligraphy, dotted, watercolor, neon, and eraser, plus color, size, and opacity. Shared mode draws on a transparent layer over the partner’s layer. Separate mode is private. New page archives the current shared page and opens the next one. Save stores a PNG on this phone. The home-screen widget shows the shared page (or a saved drawing if a shared page was never used). |
| Data | Layer send: `draw` with `t`, `ts`, `fid`, `key`, `size`, `layer` = `shared`, `page`. A full drawing from an older sender omits `layer` and is stored as the partner’s layer on the current page. New page: `t`, `op` = `newpage`, `page`, `ts`, no file. Pages are 1 through 1,000,000. |
| Where it goes | PNG ciphertext to `PUT /file/{fid}` (max 24 MB). The sealed `draw` message uses `wake` off. Local files live under `filesDir/drawings/` (`shared/mine.png`, `shared/partner.png`, `saved/<time>.png`, and related files). The widget reads those files. It does not talk to the network. It refreshes when a drawing is saved and on the 30-minute system widget period. |
| Encryption | Same file key and session key as attachments. |
| Retention | Relay file: 7 days. Local layers and saved drawings: until you delete them or unpair. Unpair deletes the whole drawings folder. Because `wake` is off, a background phone learns about a drawing when it next opens, when some other wake runs, or at the next cold start. |
| Permissions | None specific. Notifications only if drawing notices are on. |
| Battery and data | Strokes stay on the phone while you draw. A send is one PNG upload and one small message, with no FCM wake. The widget’s 30-minute refresh does not use the radio. |
| Turn it off | Don’t send. Drawing notices have their own switch. Remove the widget from the home screen. Unpair deletes the local drawings. |

### Listen together, through Duet

Playback, playlists, and the queue live in Duet (`com.nextlayer.duet`). Betweenly does not read the music library and does not play audio. The Activity tab has a Duet card. See [Duet (separate app)](#duet-separate-app).

| | |
|---|---|
| What it does | Duet hands Betweenly a `music` or `plist` JSON object. Betweenly seals it and posts it to the mailbox. The partner’s Betweenly opens it and gives that same JSON to their Duet. An invite with Duet not connected posts a local notice. |
| Data | The JSON Duet supplied. Betweenly keeps it only when `t` is `music` or `plist`, and only when the UTF-8 is at most 60,000 bytes. It does not add a key, a session, or a relay URL. `wake` is on only when `t` is `music` and `op` is `invite`. Every other op, including every `plist`, uses `wake` off. |
| Where it goes | Sealed mailbox message, same path as chat. The bridge between the two apps is a local binder on this phone. Duet never talks to the relay. Betweenly does not upload an audio file for these messages. |
| Encryption | Session XChaCha20-Poly1305. The relay stores ciphertext. |
| Retention | Mailbox rules. Betweenly does not keep a music database. A now-playing line from Duet (title, artist, playing, and the time it arrived) sits in SharedPreferences `duet` and is treated as idle after 60 seconds. Process start deletes a leftover `betweenly-music.db`, `filesDir/music`, and `cacheDir/music` from older builds. |
| Permissions | None for audio. Notifications for the invite. The manifest can see whether the Duet package is installed. |
| Battery and data | Solo listening never enters Betweenly. While Duet says a session is active and this app is in front, the inbox pull is every 2 seconds. It stops when you leave the app, when Duet disconnects, or when you unpair. An invite is one small ciphertext plus one empty FCM wake. Other music and playlist messages wait for the next pull. |
| Turn it off | Leave the session in Duet. Uninstall Duet. Unpair stops the sealed messages. |

The Activity card does not poll. It reads Duet when the Activity tab resumes, and when Duet pushes a new line. If Duet is missing, the card says “Music, together. Install the Duet app” and opens `https://github.com/aceadones/Duet/releases` in the browser. Betweenly’s own HTTP client does not request that page. If Duet is installed, the subtitle is “Playing: Title - Artist” while a line is fresh, or “Open Duet”. The chip is a read-only Playing or Paused hint. The tap opens Duet.

### Updates

| | |
|---|---|
| What it does | Compares `versionCode` first (a higher code is newer even when the version name matches), then the version name when a code is missing. A newer build can be downloaded, SHA-256 checked, checked for the same signing certificate as this install, and handed to the system installer. |
| Data | `versionCode`, `versionName`, `sha256`, `size`, `notes`. No pairing data. |
| Where it goes | `GET /update/latest` and, if you install from the relay, `GET /update/apk`. The same check reads `https://api.github.com/repos/aceadones/Betweenly/releases/latest`. A 401, 403, or 404 is ignored. If that public release is the one chosen, the APK is downloaded from its asset URL on a GitHub host. `scripts/release.sh` still uploads the APK to that GitHub release. |
| Encryption | HTTPS only. The JSON and the APK are not end-to-end encrypted. The phone checks SHA-256 and the signing certificate before install. |
| Retention | The published APK stays in relay KV until the next release replaces `upd:json` and `upd:apk`. The download is `cacheDir/update.apk` and is deleted if the hash or the signing certificate does not match. A quiet check runs at most once every 6 hours, from app launch. A failed check does not move that clock. Profile can force a check. Later hides the dialog until the next check. The banner stays. |
| Permissions | Internet. Request-install-packages, and the system “unknown apps” screen once. |
| Battery and data | A few kilobytes for the JSON on a check. The APK download happens only after you accept it. |
| Turn it off | Don’t tap install. There is no analytics in the check. |

### Widgets

**Betweenly (2×2, not resizable).** Track opens the map. Ring sends a call ring. Live toggles live location. Settings opens Profile. It reads `Store` on the phone. The 30-minute system period and `Store` changes refresh it. Unpaired, it asks you to pair.

**Partner’s drawing (resizable, about 2×2 up to 320×250 dp).** Shows the shared page, fitted, with rounded corners. Tap opens the Activity tab. Same 30-minute period, plus an immediate refresh when a drawing is saved. No network.

| | |
|---|---|
| Permissions | None of their own. Ring and Live use the same permissions as those features. |
| Battery and data | The widget refresh does not use the radio. Ring and Live do, the same as the in-app controls. |
| Turn it off | Remove the widget. |

### What the four tabs are

Activity (draw, and a card that opens Duet), Chat, Location (partner card, sharing, ring, ping, distance), Profile (name, photo, saved drawings, notifications, Reduce animations, update check, safety number, unpair). The map, camera, and drawing studio cover the dock. Onboarding and pairing are full screen. A widget or a notice can open the app with `open=track`, `open=chat`, `open=activity`, or `open=settings` (Profile). A listen-together notice opens Duet instead.

Reduce animations is on Profile, stored as `animations_enabled`, default on, kept across unpair. When it is off, `Motion.tween` and `Motion.spring` snap. The switch is not sent.

## Who can see what

| Data | You | Your partner | Relay operator | Google FCM | OpenStreetMap | A network observer |
|---|---|---|---|---|---|---|
| Pairing code | Yes, on screen | Yes, if you tell them | No. They see a hash-derived id and SPAKE2 bytes | No | No | Not on the relay connection. A share-sheet app you pick can send it in its own way |
| Session key and file keys | Yes, in Keystore-wrapped storage and in memory while sending | Yes, the same keys, derived on their phone | No | No | No | No |
| Safety number | Yes | Yes, the same number | No | No | No | No |
| Chat text, reactions, link cards | Yes | Yes | Ciphertext only | That a wake happened, not the text | No | TLS size and timing to the relay |
| Photos, video, files, drawing PNGs, wallpaper JPEGs | Yes | Yes, after decrypt | Ciphertext at `/file/{id}` for 7 days | Wake only when the pointer message asks for one. Drawings and wallpaper do not | No | TLS size and timing |
| Location and battery | Yes | Yes, after decrypt | Ciphertext only | No wake on routine points. A wake on share on/off and on the last live point | The map tiles for squares on screen, and your IP, when a phone opens the map | TLS size and timing |
| Distance | Yes, computed locally | No, unless they compute their own | No | No | Only if you open the map | No |
| FCM device token | Stored on this phone | No | Yes, stored under your inbox id for up to 60 days, so it can send an empty ping | Yes. It is their token | No | TLS to Google and to the relay |
| Wake ping body | The app sees `w` = `1` | Same, on their phone | The worker sends `{w:"1"}` | Yes, that field | No | The FCM payload is that one field |
| Notification words | Yes | No. Each phone uses its own wording | No | No | No | No |
| Link-preview fetch | Your phone requested that URL | The card you sealed, not the site | No | No | No | The website, and anyone on that HTTPS path, sees your IP |
| Update JSON and APK | Yes | No pairing data in it | Yes, the relay copy is public. A public GitHub release is also read | No | No | Yes, it is not end-to-end encrypted |
| Listen-together and shared-playlist JSON | Yes, sealed, and only the JSON Duet handed over | Yes, after decrypt, then only inside Duet | Ciphertext only | A wake for a music invite. Not for other music ops or playlists | No | TLS size and timing |
| Duet now-playing line | Yes, on the Activity card | No | No | No | No | No |
| Duet install page | The browser, if you tap Install and a browser is installed | No | No | No | No | The browser’s own request to GitHub. Betweenly does not make it |
| This phone’s IP | Your network | No | Yes, on every relay call | Yes, when a wake is delivered or a token is fetched | Yes, when a tile is fetched | Yes |

Play services location is used to read a fix. This app’s code does not POST coordinates to Google. Android’s location provider can still use the network on its own.

## What is not collected

- No account, email, or phone number.
- No analytics, ads, or Firebase Analytics. The manifest sets `firebase_analytics_collection_deactivated` and turns off advertising-id collection. FCM does not initialize until a project id is present in the build.
- No message text, keys, or coordinates in logs. The Kotlin app does not call `android.util.Log`. Relay failures are not logged with the path, because mailbox ids are secrets.
- No cloud backup and no device-to-device transfer of app data (`allowBackup` is false, and `data_extraction_rules.xml` excludes every domain).
- No Google Maps SDK. Map pictures are OpenStreetMap tiles.
- No standing access to the photo library.
- No music player in this app, and no fetch of a song or a catalog. Duet is a separate app. The Install button can open a browser on the Duet releases page.

## Threat model and limits

In scope: a curious relay operator, a person who finds the relay URL, and a passive network observer.

- Mailbox ids and file ids are 128-bit values. Knowing one lets you download ciphertext or post a blob. It does not reveal the key.
- The relay sees timing, ciphertext sizes, IP addresses, and FCM tokens. Short messages are padded to a multiple of 512 bytes before encryption, so a location update and a short chat look the same size on the wire (560 bytes of ciphertext). Large messages and files are not padded to look small. File sizes still show the size of the encrypted blob.
- There is one long-term session key per pair. There is no ratchet and no forward secrecy. Someone who later obtains the session key can decrypt messages they also captured.
- Replay of an old counter is rejected. A gap is accepted, so a lost send does not stall the pair. The mailbox holds at most 20 blobs; a 21st drops the oldest.
- Routine location, drawings, wallpaper, playlist JSON, and music JSON other than an invite do not wake the other phone. They wait until that phone pulls.
- The last shared place remains on the partner’s phone after you turn sharing off.
- A stolen unlocked phone can read what the app can read. The Keystore key does not require user authentication, so a background alarm can unwrap the session after the device has been unlocked once since boot.
- The safety number detects a substituted handshake only if the two people compare it. Skipping that check trusts the relay to have passed the real SPAKE2 messages.
- The joiner gets three online attempts per code, then the code burns. The code space is about 40 bits.
- Link previews tell the website the sender’s IP.
- Map tiles tell the tile server which squares were on screen, and the IP.
- Update metadata and the APK are public.
- Copies saved to the system gallery or Downloads stay after unpair. Betweenly does not keep a music library. The next process start deletes a leftover music database and song copies from an older build. The Duet now-playing line is not the pair and stays until Duet replaces it.
- Notification mode Off still posts a notice. It changes the words.
- Without Firebase keys in the build, nothing can wake a sleeping phone. Messages wait until the app is opened or the process starts.
- A compromised phone, a determined attacker with the unlocked device, and legal compulsion of a person who can see the screen are outside what the cryptography stops.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Relay, FCM, map tiles, link previews, update downloads. |
| `ACCESS_FINE_LOCATION` | Sharing and live fixes, and the local distance read. |
| `ACCESS_COARSE_LOCATION` | Fallback if fine location is denied. |
| `ACCESS_BACKGROUND_LOCATION` | The 10-minute sharing alarm with the screen off. The UI asks for this on its own. |
| `POST_NOTIFICATIONS` | Chat, drawing, ping, listen-together invite, live, and ring notices. |
| `FOREGROUND_SERVICE` | Required for the services below. |
| `FOREGROUND_SERVICE_LOCATION` | Live location. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The ring tone service. |
| `USE_FULL_SCREEN_INTENT` | The ring notice can appear over the lock screen. |
| `REQUEST_INSTALL_PACKAGES` | Hand an update APK to the system installer. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm the 10-minute sharing alarm after reboot. Live mode is not started from boot. |
| `VIBRATE` | Ring vibration. |
| `WAKE_LOCK` | A short wake so the alarm tone can start. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The UI can ask Android to let the sharing alarm run during Doze. It is not granted automatically. |
| `CAMERA` | The in-app camera. Optional hardware. |
| `RECORD_AUDIO` | Sound on a video. Optional. Denied means silent video. |

## Network endpoints

The relay base URL is `BuildConfig.RELAY_URL`, from `relay.url` in `android/local.properties`. If that key is missing, the build uses `https://loviedowpie-relay.example.workers.dev`. The Worker name in `relay/wrangler.toml` is `loviedowpie-relay`. Cleartext is refused. Redirects on relay calls are refused. The relay User-Agent is `Betweenly`. Timeouts on those calls are 10 seconds (file transfer uses 30 seconds to connect and 120 seconds to read).

| Call | Who |
|---|---|
| `PUT /pair/{32 hex}/{a\|b}` | Phone → relay. SPAKE2 message, text, max 512 characters. |
| `GET /pair/{32 hex}/{a\|b}` | Phone → relay. Returns the other role’s message. |
| `POST /box/{32 hex}` and `POST /box/{32 hex}?wake=1` | Phone → relay. One ciphertext blob, max 96 KB. |
| `GET /box/{32 hex}` | Phone → relay. Returns the blobs and deletes them. |
| `PUT /token/{32 hex}` | Phone → relay. FCM registration token, TLS only, 20–400 characters. |
| `PUT /file/{32 hex}` | Phone → relay. File ciphertext, max 24 MB, kept 7 days. |
| `GET /file/{32 hex}` | Phone → relay. That ciphertext. |
| `GET /update/latest` | Phone → relay. Public JSON. |
| `GET /update/apk` | Phone → relay. Public APK. |
| `https://api.github.com/repos/aceadones/Betweenly/releases/latest` | Phone → GitHub, on an update check. Public release JSON. A 401, 403, or 404 is ignored. An APK download from that release stays on `github.com`, `api.github.com`, `release-assets.githubusercontent.com`, `objects.githubusercontent.com`, or `github-releases.githubusercontent.com`. |
| `https://fcm.googleapis.com/v1/projects/{project}/messages:send` | Relay → Google, only when `wake=1` and a token exists. Body data is `{w:"1"}`. High priority, TTL 3600 seconds. |
| `https://oauth2.googleapis.com/token` | Relay → Google, to authorize that send. The service-account JSON is a Worker secret, not in the app. |
| Firebase Messaging SDK | Phone → Google, only if `fcm.projectId`, `fcm.appId`, `fcm.apiKey`, and `fcm.senderId` were set at build time. Used to obtain a token and receive the empty ping. |
| `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | Phone → OpenStreetMap, when a tile is not in the local cache. User-Agent `Betweenly/<version> (Android; com.nextlayer.betweenly)`. At most 2 downloads at once. Each tile is capped at 512 KB. Disk cache is 30 MB under `cacheDir/tiles`. Memory cache is about 18 MB. Zoom is 3 through 19. Redirects are followed for tiles. |
| `https://…` in a message you send | Sender’s phone only, for a link card. |

Play services location is a library call on the device. This app does not name another location URL.

## Data at rest

| What | Where | Encryption | Unpair |
|---|---|---|---|
| Session (80 bytes: 32-byte key, send counter, receive counter, inbox id, outbox id) | SharedPreferences `secure`, key `session` | AES-256-GCM, Android Keystore alias `betweenly_wrap`, stored as base64(12-byte IV \|\| ciphertext) | Deleted |
| Names, avatars, last partner place, sharing flags, FCM token, notification wording, animation switch, unread count, reaction shortcuts, update offer | SharedPreferences `app` | Avatars are base64 in that file. The session key is not in this file. The prefs file is app-private and excluded from backup | Partner fields, sharing flags, unread count, reaction shortcuts, and this phone’s last-sent battery sample are cleared. Your name, your avatar, the FCM token, notification wording, the animation switch, the last update-check time, and the saved update offer stay |
| Onboarding finished | SharedPreferences `ui` | App-private | Kept, so unpair does not repeat onboarding |
| Chat rows | `databases/chat.db` | Each JSON body is Keystore AES-GCM | Deleted |
| Attachment bytes | `filesDir/attachments/{id}.bin` | Keystore AES-GCM | Deleted |
| Wallpaper JPEG | `filesDir/chat_wallpaper.jpg` | The file is the decoded JPEG in app-private storage | Deleted |
| Drawings | `filesDir/drawings/` | App-private PNGs | Deleted |
| Camera captures | `cacheDir/camera` | App-private | Not the pair record. Unsent files are deleted when you leave the camera |
| Map tiles | `cacheDir/tiles` | Ordinary PNGs, no location identity beyond the tile numbers | Not deleted by unpair |
| Update APK | `cacheDir/update.apk` | The published APK | Not part of the pair |
| Duet now playing | SharedPreferences `duet` (`title`, `artist`, `playing`, `at`) | App-private. No key. A line older than 60 seconds is treated as idle | Not part of the pair. Unpair does not delete it |
| Old in-app player files | `databases/betweenly-music.db`, `filesDir/music`, `cacheDir/music` | Only if an older build left them. This build does not create them | Deleted the next time the process starts |
| Open-in-another-app cache | `cacheDir/chat-open`, `cacheDir/chat-anim` | Short-lived plaintext | Deleted with chat wipe |
| Gallery and Downloads copies | System media store, outside the app | Whatever the system uses | Left in place |

## Glossary

| Term | Meaning |
|---|---|
| Betweenly | This app. Package `com.nextlayer.betweenly`. |
| Relay | The Cloudflare Worker in `relay/src/index.ts`. Blind mailbox, file store, and update store. |
| Mailbox | A 32-hex id. You write your outbox and read your inbox. They are swapped for your partner. |
| Seal | Pad the JSON, then XChaCha20-Poly1305 with the session key and an authenticated counter. |
| Wake | `?wake=1` on a mailbox post. The worker sends an empty FCM data message `{w:"1"}`. |
| SPAKE2 | The password-authenticated handshake. The password is the pairing code. |
| Safety number | Six digits derived from the session key. Both phones should show the same number. |
| File key | A one-time 32-byte key for one attachment, drawing, or wallpaper. It travels only inside a sealed message. Betweenly does not put one in a Duet bundle. |
| Tombstone | A chat row with `t` = `gone` after unsend. |
| Listen together | A sealed `music` JSON that Duet asked Betweenly to carry. The player is Duet. |
| FCM | Firebase Cloud Messaging. Used only as an empty wake. |

## Ask an AI about this app

Paste the repository link and ask it to answer from the files, not from memory. Useful questions:

- Where does my location go, and who can read it?
- What does the relay store, and when does it delete it?
- What is inside a chat message, a photo, and a link preview?
- What leaves the phone when I press Ring, Ping, or Invite partner?
- What remains on the phone after Unpair?
- What can Google, OpenStreetMap, and a network observer see?

File map for checking a claim:

| Topic | Path |
|---|---|
| Crypto, code, SPAKE2, file encrypt | `core/src/lib.rs`, `core/src/jni_api.rs` |
| Desktop test partner | `core/examples/partner.rs` |
| Relay routes, TTL, FCM wake | `relay/src/index.ts`, `relay/wrangler.toml` |
| Phone HTTP client | `android/app/src/main/java/com/nextlayer/betweenly/Relay.kt` |
| Message JSON and inbox | `Msg.kt`, `ChatInbound.kt`, `DrawInbound.kt`, `DuetBridge.kt` |
| Pairing | `Pairing.kt`, `ui/PairScreen.kt`, `ui/PairSession.kt` |
| Keystore | `Secure.kt`, `Store.kt` (`unpair`) |
| Location, battery, distance | `LocationSharing.kt`, `LiveLocationService.kt`, `ui/HomeScreen.kt` |
| Ring and ping | `RingController.kt`, `RingService.kt` |
| FCM | `FcmService.kt` |
| Chat, reactions, unsend, files | `ui/ChatController.kt`, `ChatStore.kt`, `FileTransfer.kt`, `ui/ChatMedia.kt` |
| Link previews | `LinkPreview.kt` |
| Wallpaper | `ChatWallpaperSync.kt` |
| Notifications | `NotificationTexts.kt`, `ChatNotifications.kt`, `ui/NotificationSettings.kt` |
| Camera | `ui/CameraSession.kt`, `ui/CameraScreen.kt` |
| Drawing | `DrawStore.kt`, `ui/DrawingStudioScreen.kt`, `ui/DrawingWidget.kt` |
| Duet bridge | `MusicBridgeService.kt`, `DuetBridge.kt`, `DuetState.kt` |
| Map tiles | `ui/TileMap.kt` |
| Updates | `Updater.kt`, `scripts/release.sh` |
| Permissions | `android/app/src/main/AndroidManifest.xml` |
| Version and dependencies | `android/app/build.gradle.kts` |
| Privacy write-up | `PRIVACY-DATA-FLOW.md`, `SECURITY.md` |
| In-app privacy screen | `ui/PrivacyScreen.kt` and `privacy_*` strings in `res/values/strings.xml` |

The in-app privacy screen is a short summary. It says it matches this document. It does not list chat, files, drawings, or the 7-day file retention. Trust this file and `PRIVACY-DATA-FLOW.md` when they differ.

## Build from source

Needs Rust with `cargo-ndk` and the target `aarch64-linux-android`, JDK 17, Android SDK 35, and NDK `27.2.12479018`.

On the machine this repo is developed on, the defaults are:

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17`
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- `ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018`

```text
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
cd android && ./gradlew :app:assembleRelease
```

Gradle builds the Rust library first (`betweenly-core`, native library `betweenly_core`) for `arm64-v8a`. Release builds run R8 and resource shrinking. Config goes in `android/local.properties` (not committed): `relay.url`, optional `fcm.projectId`, `fcm.appId`, `fcm.apiKey`, `fcm.senderId`, and `signing.keystore`, `signing.password`, `signing.alias` (default alias `betweenly`). The Firebase Android app, if you use wake-ups, must be registered for `com.nextlayer.betweenly`.

Dependencies are the Compose BOM `2025.05.00`, CameraX `1.4.2`, `play-services-location` `21.3.0`, and `firebase-messaging` `24.1.1`, plus AndroidX. Do not add libraries.

Desktop partner (joins as role `b`, prints `RECEIVED:` lines, writes files under `/tmp/partner-in/`):

```text
cd core && cargo build --example partner
./target/debug/examples/partner https://your-relay.example <CODE>
```

Commands: `chat`, `ring` (legacy, no id), `share on|off`, `loc <lat> <lon> <bat>` (this tool sends `wake`, the phone’s routine `loc` does not), `draw`, `photo`, `sticker`, `gif`, `file`, `unsend`, `wall color <0-5>`, `react <id> <emoji>`, `quit`. It does not play audio. If a `music` or `plist` blob arrives, the phone forwards it to Duet or, for an invite with Duet disconnected, posts a notice. The tool does not send those types.

Release, from a maintainer machine:

```text
scripts/release.sh
scripts/release.sh path/to/notes.txt
```

That script reads `versionName` and `versionCode`, builds the release APK, copies `dist/Betweenly-v<versionName>.apk`, writes `dist/latest.json`, puts `upd:json` and `upd:apk` in the Worker KV, and runs `gh release create` on `aceadones/Betweenly` with tag `v<versionName>`. `dist/` is gitignored.

Manual checks: [TESTING.md](TESTING.md).

## Duet (separate app)

Duet is the music app. Betweenly is the pair. They are two installs that can be used apart, and they can be used together.

| | |
|---|---|
| App | Duet, package `com.nextlayer.duet`. Repository [aceadones/Duet](https://github.com/aceadones/Duet). |
| What Duet does | Plays songs, playlists, a queue, and likes on its own. It can bind to Betweenly when you want to listen with your partner or share a playlist. |
| What Betweenly does | Keeps the pair, the session key, and the relay. It seals the `music` and `plist` JSON Duet gives it, and it hands the partner’s decrypted JSON back to Duet. |
| What never crosses | Keys, session bytes, and relay URLs stay in Betweenly. Duet does not open a connection to the relay. The relay still sees only ciphertext. |
| How they trust each other | Both are signed with the same release keystore. Betweenly’s `MusicBridgeService` is exported and checks `checkSignatures` plus the package name `com.nextlayer.duet` on every bind and every message. A debug build accepts that package name without the signature match so an emulator can use a different debug key. |
| If Duet is missing | The Activity card and an invite notice offer the releases page, `https://github.com/aceadones/Duet/releases`. That open is a browser intent. |
| Licence | GNU Affero General Public License, version 3 or later, the same as Betweenly. `LICENSE` and `NOTICE` are at the root of each repository. |
| Donate | Buy me a coffee (Razorpay): <link coming soon> |

## Contributing and licence

`LICENSE` and `NOTICE` say Betweenly is free software under the GNU Affero General Public License, version 3 or later, copyright 2026 Next Layer Labs, with no warranty. Duet is under that same licence in its own repository. `core/Cargo.toml` says `license = "MIT"`. Those files disagree. Third-party pieces keep their own licences; see [THIRD-PARTY.md](THIRD-PARTY.md). Buy me a coffee (Razorpay): <link coming soon>.

Changes should stay small, keep the four constraints (battery, data, privacy, lightweight), and keep `PRIVACY-DATA-FLOW.md` in step with every network call. Network calls in code are marked `DATA-FLOW:`.
