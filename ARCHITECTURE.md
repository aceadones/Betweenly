# Architecture

```
Phone A  --(ciphertext)-->  Relay (Cloudflare Worker + Durable Object mailboxes)  --(ciphertext)-->  Phone B
                                   |-- empty FCM "wake" ping (ring) --> Google FCM --> Phone B
```

- **Pairing:** A shows an 8-char code. Both phones run SPAKE2 with the code as password; the relay only sees the SPAKE2 messages and a one-way hash of the code (the rendezvous id). Both derive the same session key, two mailbox ids and a 6-digit safety number to compare aloud. The relay allows 3 join attempts per code, then burns it.
- **Messages:** JSON, padded to 512-byte multiples, sealed with XChaCha20-Poly1305 with an authenticated counter (replay and tamper rejected). The session state (key + counters) is stored in Android Keystore-wrapped storage.
- **Location:** inexact alarm about every 10 min fetches ONE balanced-power fix and sends it. "Live" is a foreground service (visible notification) updating every ~5 s. Turning sharing off sends a message; the partner keeps the last known place.
- **Ring:** encrypted "ring" message + `wake=1`; the relay sends a content-free high-priority FCM data message so a sleeping phone wakes and fetches the message. Without a Firebase project configured, ring works only when the app is awake.
- **Updates:** the app asks the relay `/update/latest` (public, developer-published), downloads `/update/apk`, verifies SHA-256, and hands it to the system installer (Android enforces the signing key).
- **Map:** simple built-in OpenStreetMap tile viewer (no map SDK).

## V2

After pairing, one activity shows four tabs: **Activity**, **Chat**, **Location**, **Profile**. Location is the V1 home screen (partner card, sharing switches, ring). The map is still a full-screen page opened from Location. The camera is a full-screen page opened from Chat. Onboarding and pairing stay full screen, with no tab bar. A widget or a notification can open the app with `open=track`, `open=chat`, or `open=activity`.

- **Chat:** text, photos, video, stickers, GIFs, files, and link previews. The JSON rides in the same padded, sealed envelope as V1. The relay still stores only ciphertext. A message may carry `"re"` (the id it quotes). A separate `react` message sets one emoji on a message id. The sender strips local-only keys (`rx`, `gal`) before sealing.
- **Unread:** `ChatStore.unreadCount` is how many partner messages arrived since the Chat tab was last open. It is stored in app prefs. The Chat tab shows a badge. Opening Chat clears it. The notification text stays "New message" and does not include the count.
- **Notifications:** off while `MainActivity` is in the foreground. Message notices and drawing notices are separate switches on Profile (kept across unpair). A drawing notice is posted from `DrawInbound` only after the PNG is saved, and only in the background. Tapping a message notice opens Chat. Tapping a drawing notice opens Activity. "System notification settings" opens the Android app notification screen.
- **Replies:** long-press or a horizontal swipe arms a quote. The composer shows a one-line preview. The camera covers Chat, so the armed id lives on `ChatController` until the send.
- **Attachments:** the sender encrypts the bytes with a one-time key (`Core.fileEncrypt`), PUTs the ciphertext to `/file/{id}` (random 32-hex id, 24 MB max, kept 7 days), and puts the id and the key inside the sealed message. The partner GETs the ciphertext and decrypts it. The relay cannot read the file. `FileTransfer.upload` / `download` are the shared helpers. Download retries for about a minute because the relay can be briefly behind the upload.
- **Camera:** capture stays in app cache until the user sends it. The chat screen then treats it as a normal attachment. Nothing from the camera is uploaded before Send.
- **Drawing:** the Activity tab is a canvas (brush sizes, swatches, an HSV color wheel, eraser, undo). "Send to partner" exports a PNG, uploads it the same way as an attachment, and sends a `draw` message with `wake` false (no FCM ping). The partner's latest drawing is saved under app-private `filesDir/drawings/partner_latest.png`. Drawings the user saves live in `filesDir/drawings/saved/<timestamp>.png`.
- **Drawing widget:** a resizable home-screen widget (2×2 up to 4×4, RemoteViews) shows the partner's latest drawing, or this phone's latest saved drawing, or a short hint. Tapping it opens the Activity tab. It refreshes when a drawing arrives and on the 30-minute system period.
- **Profile:** name and photo (the existing encrypted profile message), saved drawings, notification switches, update check, safety number, and unpair. Unpair also wipes chat and drawings. The notification switches, this phone's name, avatar, and FCM token stay.
- **Desktop partner:** `core/examples/partner.rs` (`cargo build --example partner`, then `./target/debug/examples/partner <relay-url> <CODE>`) joins as role b and speaks the same sealed JSON, including file upload via `file_encrypt`.
- **Release:** `scripts/release.sh` builds the release APK, writes `dist/latest.json`, puts `upd:json` and `upd:apk` in the relay KV, and creates the GitHub release. The app reads only the relay.
