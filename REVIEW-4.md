# REVIEW-4

Read against the code on branch `v2`. Gradle was not run. The lead builds.

## Verified

1. Dock is four tabs (Activity, Chat, Location, Profile). No camera tab. Camera is the chat plus menu (`Lu.Camera` → `Screen.Camera`).
2. `BetweenlyTheme` wraps every screen in `AppBackground`: sky, blobs, doodle tile at 0.07 light / 0.08 dark. Chat wallpaper is an extra layer on top.
3. No `Icons.*` in `android/`. Icons are `LuIcon`. Motion specs go through `Motion.tween` / `Motion.spring`, which return `snap()` when `animations_enabled` is false. Profile “Reduce animations” writes that flag (`checked = !animationsOn`).
4. Activity is two hub cards. Drawing Studio is the Draw card. Paper is clipped to `LdRadius.card`. Sub-dock is Brush, Color, Size, Opacity, Eraser, Save, Send. Color sheet has swatches and a nested wheel. Brush kinds and the eraser (`PorterDuff.Mode.CLEAR` on the ink layer) are implemented. Undo, redo, save, and send are wired.
5. Long-press opens a reaction pill (7 slots + edit) and a separate action card (reply, copy, delete for me, unsend on your own messages). Both tombstones use `chat_deleted_them` (“This message was deleted”). Jump-to-latest, image pager with a thumbnail strip, wallpaper from the overflow menu (`wall` message), and the plus menu are present.
6. Historical. The in-app player (`MusicScreen`, `MusicSession`, `music/*`) was removed after this review. Listen together is the Duet bridge (`MusicBridgeService`, `DuetBridge`, `DuetState`). Betweenly seals opaque `music` and `plist` JSON and does not play audio.
7. Four-tile widget still opens `open=track` / `open=settings` and sends ring / live. Drawing widget is 2×2–4×4, tap is `open=activity`, bitmap budget 1 MB. Tile radius is 16dp inside the 28dp card.
8. `MainActivity` still routes `open=track` (Track screen + Location), `open=chat`, `open=activity`, and `open=settings` (Profile). Pairing, location, ring, and update were not edited.
9. Manifest already has `CAMERA` and `RECORD_AUDIO`. FileProvider covers `cache-path` `.` and `chat-open/`. No `android.util.Log`, `println`, or `TODO` / `FIXME` under `android/`. `material-icons-core` is already absent. Unsend and wall already have rows in `PRIVACY-DATA-FLOW.md`. No new network call was added. Historical: this snapshot also listed `READ_MEDIA_AUDIO` and `READ_EXTERNAL_STORAGE`. Those audio read permissions left with the in-app player.

## Fixed

- `ChatStore.kt` — An unsend that arrives before the message is remembered and the later insert is stored as a tombstone (no notification, no attachment download). `setStatus` / `updateJson` refuse a tombstone. Chat loads the newest 500 messages, plus the newest 80 pending reactions.
- `ChatController.kt` — Unsend blocks the in-flight original so a later upload cannot put the text back or mark the tombstone failed.
- `ChatBubbles.kt` — Bubble padding is 16×12dp (media inset stays 4dp, so the inner radius is 16). Both phones show “This message was deleted”. Swipe-to-reply waits for touch slop, gives a vertical drag to the list, and the reply distance is in dp (it was capped at 140px, so it could not reach 72dp on a dense screen).
- `ChatViewer.kt` — If the open image disappears, the pager scrolls back to the last remaining page instead of keeping an index past the end.
- `DrawWheel.kt` — The saturation square calls `prepareToDraw()` after `setPixels`, so a hue change shows up.
- `DrawingWidget.kt` — The bitmap just given to `RemoteViews` is kept until the next successful update. A failed update keeps the previous bitmap. Updates take one lock.
- `Theme.kt` — Checker, brush-preview, and music wash colors live on `LdColors`.
- `DrawCanvas.kt`, `DrawPalette.kt`, `DrawingStudioScreen.kt`, `ActivityScreen.kt` — Those screens use the new tokens. `MusicScreen.kt` was in this list and has since been removed.

## Still risky

- Not compiled and not run.
- Chat older than the newest 500 stays in the database and is not shown. A reply to one of those shows the “message gone” line.
- If sealing the early tombstone fails, the mailbox blob is already consumed and that message never appears.
- An original send already inside `Outbox.send` still leaves. The unsend is sent after it.
- The image pager can still throw during measure if its current page is past the list before the scroll-back runs. Pull-to-dismiss, paging, and pinch can still fight.
- Reduce animations snaps new specs. An animation already running finishes.
- Export still allocates the 1024² bitmap on the main thread, then writes it on IO. Out-of-memory is caught.
- Two drawing widgets: if one update in the same pass fails, the previous bitmap for that widget can be recycled after the other one succeeds.
- Chat also polls the inbox every 5 seconds while it is open, in addition to the app pump. The session mutex makes that safe. It is extra traffic.
- `Color.White`, `Color.Black`, `Color.Transparent`, generated `Color.hsl` album washes, and paper ARGB values in `DrawModel` are not `LdColors` hex tokens.
- `chat_deleted_you` is unused. Both tombstones use `chat_deleted_them`.
- Historical: a listen-together invite used to open the Activity tab and wait for the in-app player. The notice now opens Duet, or the Duet releases page when Duet is not installed.
- `ChatStore.isGone` takes the store lock. Callers today are outside that lock. Calling it from inside `edit` would deadlock.
