# Third-party notices

## Lucide

The stroke icons in `android/app/src/main/java/com/nextlayer/betweenly/ui/icons/Lu.kt` are drawn from the [Lucide](https://lucide.dev) icon set (paths and names). Lucide is a community fork of Feather Icons.

- Lucide: ISC License. Copyright (c) Lucide Contributors, 2022–present.
- Feather portions: MIT License. Copyright (c) Cole Bemis, 2013–2022.

ISC summary: you may use, copy, modify, and distribute the icon paths for any purpose, with or without a fee, provided this copyright notice and the permission notice stay with the copies. The paths are provided as-is, without warranty.

The app does not ship the Lucide font or the Lucide package. Each icon is an `ImageVector` built in code. Unused icons are created on first use.

The duotone fill behind some icons is original work in this app. It is a closed shape drawn from the same Lucide path, filled with the icon tint at low opacity, and painted behind the 2px stroke. It is not the Font Awesome Duotone font, and it is not a Lucide filled-icon set. Font Awesome artwork is not included.

## Libraries linked into the app

Versions are declared in `android/app/build.gradle.kts` and `core/Cargo.toml`. Their licence texts ship with those packages. This app does not restate them.

- AndroidX, Jetpack Compose BOM 2025.05.00, and Material 3.
- CameraX 1.4.2 (camera core, camera2, lifecycle, video, view).
- Play services location 21.3.0, used to read a fused fix.
- Firebase Messaging 24.1.1. Firebase Analytics is not a dependency. The manifest turns analytics collection off.
- Rust crates used by `betweenly-core`: `spake2`, `hkdf`, `sha2`, `chacha20poly1305`, `getrandom`, `zeroize`, and `jni` when the `jni-api` feature is on. The desktop example also uses `base64`.

## Services contacted at runtime

These are not bundled. See `PRIVACY-DATA-FLOW.md` for what is sent.

- The Betweenly relay (a Cloudflare Worker). Address from `relay.url` at build time.
- Google Firebase Cloud Messaging, only when the build contains FCM ids.
- OpenStreetMap tile server, `https://tile.openstreetmap.org`, when the map is on screen and the tile is not cached. Tile use is subject to the OpenStreetMap Foundation tile usage policy.
- Whatever `https` site is named in a link the sender chooses to preview.
