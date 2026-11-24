# Morning handoff (written 2026-11-25)

## What to install
- **V1.0.0** (stable, tested): GitHub release `v1.0.0` on the private repo `aceadones/Loviedowpie` (`Loviedowpie-v1.0.0.apk`, 1.9 MB). Install on both phones, allow "install unknown apps".
- **V2** is NOT published (as requested). It lives on the local branch `v2`; a debug/release APK can be built with `cd android && ./gradlew :app:assembleRelease`.

## Done and verified on the emulator (against the live relay, with a desktop partner)
Pairing + matching safety numbers, name/profile, battery + accuracy, share on/off, ring message delivery, Live service starts (foreground notification), chat text / replies / reactions / unread badge, photo + sticker rendering, drawing send/save/profile grid, dark mode, rotation, no crashes.

## Not verified yet (needs real phones; see TESTING.md and Linear XO-96, XO-97)
Real GPS in Live, home-screen widgets, ring waking a sleeping phone through FCM, in-app camera on real hardware (4K/60 options per phone), Gboard GIF / Emoji Kitchen, video send, battery use.

## Accounts and infrastructure
- Cloudflare worker: https://loviedowpie-relay.loviedowpie-relay.workers.dev (account aceadones@gmail.com)
- Firebase project `loviedowpie` (Messaging only; Analytics off), same account.
- Signing key: `~/.loviedowpie-signing/` (BACK THIS UP; losing it means users cannot update in place).
- Release: `scripts/release.sh` (build, sign, upload to relay update feed, GitHub release).
- Browsers: all of this was done in XObr. (A reconnect briefly switched the session to 32br; only Firebase console pages were opened there and nothing was created. One empty tab may remain open in 32br.)

## Known limits
- Private repo cannot serve APK to the app, so updates come from the relay feed.
- No forward secrecy yet (long-term session key); planned.
- Attachments are limited to 20 MB (relay KV limit).
