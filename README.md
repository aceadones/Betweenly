# Loviedowpie

A private two-person app for long-distance couples. Android only (sideloaded APK for now).

**V1:** pair with an 8-character code, share location (every ~10 min, or Live), battery %, ring your partner's phone, 2×2 home-screen widget, in-app update check.

**V2:** four tabs (Activity, Chat, Location, Profile). Chat is text, emoji, photos, video, GIFs, stickers, files, link previews, six emoji reactions, and reply. Activity is a drawing canvas with a resizable home-screen widget. Profile holds the name, photo, saved drawings, notification switches, the safety number, and unpair. The camera stays on the phone until a picture or video is sent.

## Privacy in one paragraph

Everything is end-to-end encrypted on the phones (Rust core: SPAKE2 pairing + XChaCha20-Poly1305). The relay (a tiny Cloudflare Worker) only stores opaque encrypted blobs. Mailbox blobs are deleted within about 24 hours. Encrypted files are kept for 7 days. No accounts, no analytics, no ads. Read [PRIVACY-DATA-FLOW.md](PRIVACY-DATA-FLOW.md) for exactly where each piece of data goes, and [ARCHITECTURE.md](ARCHITECTURE.md) for the code map.

## Layout

- `core/`: Rust crypto + pairing (no network, no disk) + JNI bridge + tests + the desktop partner example.
- `relay/`: Cloudflare Worker (TypeScript) with Durable Object mailboxes and KV for files and updates.
- `android/`: Kotlin/Jetpack Compose app, widgets, services.
- `scripts/release.sh`: release build, relay upload, GitHub release.
- `TESTING.md`: manual checklist for two phones and for the emulator plus the desktop partner.

## Build

Needs Rust (with `cargo-ndk` and the target `aarch64-linux-android`), JDK 17, Android SDK 35, and NDK `27.2.12479018`.

On this Mac the defaults are:

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

Gradle builds the Rust library first. Config goes in `android/local.properties` (never committed): `relay.url`, optional `fcm.*`, and `signing.keystore`, `signing.password`, `signing.alias` for a signed release.

## Desktop partner

The phone shows the code. This program joins as the other person:

```text
cd core && cargo build --example partner
./target/debug/examples/partner https://your-relay.example <CODE>
```

After `PAIRED` it reads `chat`, `ring`, `share on|off`, `loc <lat> <lon> <bat>`, `draw <file.png>`, `photo <file>`, `sticker <file.png>`, `gif <file.gif>`, `file <path>`, and `quit`. It prints each decrypted message and saves received files under `/tmp/partner-in/`.

## Release

`scripts/release.sh` reads `versionName` and `versionCode` from `android/app/build.gradle.kts`, builds the release APK, copies it to `dist/Loviedowpie-v<versionName>.apk`, writes `dist/latest.json` (`versionCode`, `versionName`, `sha256`, `size`, `notes`), uploads `upd:json` and `upd:apk` to the relay KV, and runs `gh release create`. An optional first argument is a notes file. `dist/` is build output and is gitignored.

```text
scripts/release.sh
scripts/release.sh path/to/notes.txt
```

The upload uses `npx wrangler` from `relay/` and needs a logged-in Wrangler account. The GitHub step needs `gh`. Put a signing config in `android/local.properties` so the APK is signed before you ship it.
