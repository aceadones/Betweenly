# Loviedowpie: where does my data go?

Written so any person or AI agent reading this repo can answer that question.
Status: V2. Every network call in the code is tagged `DATA-FLOW:`.

## Who sees what
| Data | Leaves phone? | Where | Who can read it |
|---|---|---|---|
| Location, battery %, avatar, ring command | Yes, encrypted | Relay (Cloudflare Worker + KV) | Only the paired partner. Relay stores ciphertext only. |
| Pairing code | Never sent | Shown on screen, told to partner by you | You and partner. Relay sees only a one-way hash of it and PAKE messages. |
| Encryption keys | Never | Phone only (Android Keystore wrapped) | Nobody else |
| FCM token | Yes | Relay (to send an empty wake-up ping) | Relay, Google FCM |
| Wake-up ping | Yes | Google FCM, content-free | Google sees "a ping happened", not why or what |
| Map tiles | Yes | OpenStreetMap tile server | Tile host sees the area you view and your IP, not who your partner is |
| Update check | Yes | Relay `/update/latest` and `/update/apk` | Public release metadata and the APK. The relay sees your IP. The phone does not call GitHub. A developer running `scripts/release.sh` also uploads that same APK to a GitHub release. |
| Chat messages (text, reply ids, and reactions) | Yes, encrypted | Relay mailbox | Only the paired partner. Relay stores ciphertext only. A reply is the id of an earlier message. A reaction is an emoji plus that id. Neither adds a new server. |
| Drawings | Yes, encrypted | Relay `/file/{id}` for 7 days, plus a sealed `draw` message in the mailbox | The relay stores ciphertext only. The file key travels only inside the sealed message. `wake` is off, so a drawing does not send an FCM ping by itself. |
| Attachments (photos, videos, stickers, GIFs, files) | Yes, encrypted | Relay `/file/{id}`, kept 7 days | The relay stores ciphertext only. The file key travels only inside the end-to-end encrypted message, never on its own. |
| Link previews | Sender only | The website named in the message | Only the sender's IP touches that site. The receiver gets the title, description, and a small image inside the encrypted message and does not contact the website. |
| Chat history, attachment cache, chat wallpaper, unread count | No | This phone only | Message JSON is encrypted with the Android Keystore key in an app-private database. Attachment bytes are encrypted the same way in app-private files. The wallpaper is a picture chosen on this phone, stored in those same app-private files. A decrypted copy of a video or a file is written to app cache only while another app opens it. The unread count is a number in app prefs. |
| Camera capture | No, until you send it | App cache on this phone | A photo or video stays in app-private cache. Send then follows the attachment row above. Closing or retaking deletes the unsent file. |
| Notification text and notification switches | No | This phone only | The notice is the words "New message" or "New drawing". It does not include a name, message, or picture. The two switches are app prefs and stay after unpair. |

## Not used
No analytics, no ads, no accounts, no Firebase Analytics, no Google Maps, no phone number or email.

## Retention
Relay mailbox blobs expire automatically (target: 24 h for data, 10 min for pairing). Encrypted files at `/file/{id}` are kept 7 days. The public update APK stays until the next release replaces it.

## Code map
- `core/`: all cryptography. No network, no disk. `core/examples/partner.rs` is a desktop test partner and does talk to the relay (via `curl`).
- `relay/`: server. Stores opaque blobs by random mailbox id, ciphertext files for 7 days, and the public update APK.
- `android/`: the only phone code that touches GPS, network and storage.
- `scripts/release.sh`: a developer uploads the public APK. Phones do not run it.
