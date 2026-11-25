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
| Chat messages (text, reply ids, and reactions) | Yes, encrypted | Relay mailbox | Only the paired partner. Relay stores ciphertext only. A reply is the id of an earlier message. A reaction is an emoji plus that id. Tapping the same emoji again sends that emoji once more and clears that person's reaction. Neither adds a new server. |
| Unsend | Yes, encrypted | Relay mailbox | A small sealed message with the message id only. No message text. `wake` is off, so it does not send an FCM ping by itself. The relay stores ciphertext only. |
| Shared chat background | Yes, encrypted | Relay mailbox, and relay `/file/{id}` for 7 days when the background is a photo | A sealed `wall` message carries the kind (default, a color index, or a photo), a timestamp, and for a photo the file id and key. The JPEG is encrypted the same way as an attachment. The key travels only inside the sealed message. `wake` is off. The relay stores ciphertext only. Each phone also keeps a copy in app-private storage. |
| Drawings | Yes, encrypted | Relay `/file/{id}` for 7 days, plus a sealed `draw` message in the mailbox | The relay stores ciphertext only. The file key travels only inside the sealed message. `wake` is off, so a drawing does not send an FCM ping by itself. |
| Attachments (photos, videos, stickers, GIFs, files) | Yes, encrypted | Relay `/file/{id}`, kept 7 days | The relay stores ciphertext only. The file key travels only inside the end-to-end encrypted message, never on its own. |
| Link previews | Sender only | The website named in the message | Only the sender's IP touches that site. The receiver gets the title, description, and a small image inside the encrypted message and does not contact the website. |
| Chat history, attachment cache, reaction shortcuts, unread count, background copy, reduce-animations | No | This phone only | Message JSON, including delete tombstones, is encrypted with the Android Keystore key in an app-private database. Attachment bytes are encrypted the same way in app-private files. The seven reaction shortcuts and the reduce-animations switch are app prefs. The switch is not sent. The current background kind, color index, timestamp, and photo file stay on this phone; choosing one also follows the shared chat background row. A decrypted copy of a video or a file is written to app cache only while another app opens it. The unread count is a number in app prefs. |
| Camera capture | No, until you send it | App cache on this phone | A photo or video stays in app-private cache. Send then follows the attachment row above. Closing or retaking deletes the unsent file. |
| Notification text and notification switches | No | This phone only | The notice is the words "New message" or "New drawing". It does not include a name, message, or picture. The two switches are app prefs and stay after unpair. |
| Music controls (invite, accept, leave, play, pause, seek, queue, sync) | Yes, encrypted | Relay mailbox | Only the paired partner. The relay stores ciphertext only. An invite is sent with wake, so the relay asks Google to send an empty ping. The ping does not include a song, a name, or the command. |
| A song from this phone played in a listening session | Yes, encrypted, and only if it is 20 MB or smaller | Relay `/file/{id}`, kept 7 days | The phone encrypts the file before upload. The relay stores ciphertext only. The file key travels only inside the sealed music message. The partner decrypts it into app cache while the music screen is open, and that copy is deleted when the screen closes. Songs larger than 20 MB are not uploaded. |
| Online audio, if a source is added later | Not through the relay | Whatever source each phone talks to | Each phone resolves the same track id on its own. Those bytes are not uploaded to the relay. |
| "Listen together?" notification | No | This phone only | The notice is the words "Listen together?". It does not include a song or a name. It uses the existing messages channel. |

## Not used
No analytics, no ads, no accounts, no Firebase Analytics, no Google Maps, no phone number or email.

## Retention
Relay mailbox blobs expire automatically (target: 24 h for data, 10 min for pairing). Encrypted files at `/file/{id}` are kept 7 days. The public update APK stays until the next release replaces it.

## Code map
- `core/`: all cryptography. No network, no disk. `core/examples/partner.rs` is a desktop test partner and does talk to the relay (via `curl`).
- `relay/`: server. Stores opaque blobs by random mailbox id, ciphertext files for 7 days, and the public update APK.
- `android/`: the only phone code that touches GPS, network and storage.
- `scripts/release.sh`: a developer uploads the public APK. Phones do not run it.
