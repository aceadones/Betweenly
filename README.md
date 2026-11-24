# Loviedowpie

A private two-person app for long-distance couples. Android only (sideloaded APK for now).

**V1:** pair with an 8-character code, share location (every ~10 min, or Live), battery %, ring your partner's phone, 2×2 home-screen widget, in-app update check.

## Privacy in one paragraph
Everything is end-to-end encrypted on the phones (Rust core: SPAKE2 pairing + XChaCha20-Poly1305). The relay (a tiny Cloudflare Worker) only stores opaque encrypted blobs and deletes them within 24 h. No accounts, no analytics, no ads. Read [PRIVACY-DATA-FLOW.md](PRIVACY-DATA-FLOW.md) for exactly where each piece of data goes, and [ARCHITECTURE.md](ARCHITECTURE.md) for the code map.

## Layout
- `core/`: Rust crypto + pairing (no network, no disk) + JNI bridge + tests.
- `relay/`: Cloudflare Worker (TypeScript) with Durable Object mailboxes.
- `android/`: Kotlin/Jetpack Compose app, widget, services.

## Build
Needs Rust (+ `cargo-ndk`, target `aarch64-linux-android`), JDK 17, Android SDK 35 + NDK 27.
`cd android && ./gradlew :app:assembleRelease` (Gradle builds the Rust library first).
Config goes in `android/local.properties` (never committed): `relay.url`, optional `fcm.*`, `signing.*`.
