# Loviedowpie: where does my data go?

Written so any person or AI agent reading this repo can answer that question.
Status: V1 in development. Every network call in the code is tagged `DATA-FLOW:`.

## Who sees what
| Data | Leaves phone? | Where | Who can read it |
|---|---|---|---|
| Location, battery %, avatar, ring command | Yes, encrypted | Relay (Cloudflare Worker + KV) | Only the paired partner. Relay stores ciphertext only. |
| Pairing code | Never sent | Shown on screen, told to partner by you | You and partner. Relay sees only a one-way hash of it and PAKE messages. |
| Encryption keys | Never | Phone only (Android Keystore wrapped) | Nobody else |
| FCM token | Yes | Relay (to send an empty wake-up ping) | Relay, Google FCM |
| Wake-up ping | Yes | Google FCM, content-free | Google sees "a ping happened", not why or what |
| Map tiles | Yes | OpenStreetMap tile server | Tile host sees the area you view and your IP, not who your partner is |
| Update check | Yes | GitHub Releases API | GitHub sees your IP and app version |

## Not used
No analytics, no ads, no accounts, no Firebase Analytics, no Google Maps, no phone number or email.

## Retention
Relay blobs expire automatically (target: 24 h for data, 10 min for pairing).

## Code map
- `core/`: all cryptography. No network, no disk.
- `relay/`: server. Stores opaque blobs by random mailbox id.
- `android/`: the only code that touches GPS, network and storage.
