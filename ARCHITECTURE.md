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
