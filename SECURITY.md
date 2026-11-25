# Betweenly security notes

Package `com.nextlayer.betweenly`, version 0.0.1. Cryptography is in `core/src/lib.rs`. The phone stores the session with `Secure.kt`. The relay is `relay/src/index.ts`.

## What it is trying to stop

A curious relay operator, a person who learns the relay URL, and a passive network observer. The relay should be unable to read messages, files, locations, or the pairing code.

## What the cryptography does

- The pairing code is not sent to the relay. Both sides run SPAKE2 (Ed25519) with the code as the password and the identity `betweenly-v1`. The relay stores those handshake bytes for 10 minutes. The joiner (role `b`) may write three times; the fourth write is HTTP 429 and that rendezvous is burned. Copy places the code on the clipboard. Share hands it, as plain text, to whatever app the person picks in the Android share sheet.
- The rendezvous id is HKDF-SHA256 of the code (info `rendezvous`). It is not reversible to the code.
- HKDF then derives a 32-byte encryption key (`enc-key`), two mailbox ids (`mailbox-a`, `mailbox-b`), and a 6-digit safety number (`safety`). The phone that showed the code is the initiator and swaps which mailbox it reads and writes.
- Messages use XChaCha20-Poly1305. A random 24-byte nonce is chosen per message. The send counter is additional data, and it is included in the blob. An old counter is rejected. A gap is accepted, so a lost post does not stall the pair.
- Files, drawing PNGs, wallpaper JPEGs, and shared songs use a fresh 32-byte key and the same cipher, with additional data `file`. The key is only inside a sealed message.
- JSON is padded to a multiple of 512 bytes before encryption, so short messages share one ciphertext size (560 bytes).
- The 80-byte session is encrypted with AES-256-GCM in the Android Keystore (alias `betweenly_wrap`) before it is written to preferences. The key does not require user authentication, so a background location alarm can unwrap it after the device has been unlocked once since boot.
- Chat rows and attachment files on disk use that same Keystore AES-GCM key.
- Relay HTTPS refuses cleartext and refuses redirects. Mailbox ids are not written to logs. The Kotlin app does not call `android.util.Log`.
- The app sets `allowBackup` to false and excludes all backup and device-transfer domains.
- Firebase Analytics and advertising-id collection are switched off. FCM is not initialized unless the build contains a project id, app id, API key, and sender id.

## Known limits

- There is one long-term key per pair. There is no ratchet and no forward secrecy. A person who later obtains the session key can decrypt captured ciphertext from that pair, past and future, until unpair.
- The relay sees IP addresses, timing, ciphertext sizes, and the FCM device token (stored up to 60 days). File ciphertext length tracks the file size. Padding hides small messages from each other. It does not hide a photo from a short text.
- Mailbox ids and file ids are capabilities. Anyone who learns one can download ciphertext or post a blob. They still cannot read it without the key.
- The mailbox keeps at most 20 blobs and deletes the rest 24 hours after the last write, or immediately when the owner reads. A burst can drop an unread message.
- The safety number catches a substituted handshake only if both people compare it. The app unpairs if they say the numbers differ. Skipping the comparison trusts the relay to have forwarded the real SPAKE2 messages.
- The code has about 40 bits. SPAKE2 is built so a recorded handshake does not let someone test code guesses offline, and learning the code later still does not reveal the session key from the transcript alone. A guess has to be a live join. The relay allows three joins, then burns the rendezvous. A live attacker who guesses the code before it expires can pair as the partner.
- Routine location, drawings, wallpaper, playlist edits, unsends, and music commands other than invite do not wake the other phone. They wait in the mailbox.
- Turning location sharing off does not delete the last point from the partner’s phone.
- Notification mode Off still posts a local notice. The words are generic. The lock screen uses a second, generic public version.
- Link previews reveal the sender’s IP to the website. Map tiles reveal the viewed squares and the IP to the OpenStreetMap tile server. Update metadata and the APK are public.
- Play services may use the network as part of Android’s location provider. This app does not POST coordinates to Google.
- Unpair deletes the session, chat, attachments, wallpaper, drawings, and partner location. It does not delete the music database, offline songs, gallery copies, or map tiles. A file key inside a music row can remain on the phone.
- A stolen unlocked phone exposes what the app can decrypt. That is outside this design.
- A compromised build, or a person who can change `relay.url` before you install, can point the phone at a different server. The safety number is the check that the partner you paired is the partner you think you paired.
- The desktop example `core/examples/partner.rs` prints decrypted JSON to the terminal. It is a test tool. Do not run it on a machine you do not trust with the conversation.

## Reporting

Report issues privately to the repository owner. Do not include message text, keys, mailbox ids, or coordinates in a report.
