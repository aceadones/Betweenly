# Loviedowpie manual tests

Two setups. Run both before a release. Each item has steps, the result you should see, and a checkbox. Do not paste message text, coordinates, or keys into a bug report. Say which step failed.

Install the same APK on every phone, built against the same `relay.url`. FCM is optional. Without it, ring and background wake work only while the app process is already awake. Say so in the notes if you tested without FCM.

## Setup A — two phones

- Phone A: CMF Phone 1 (shows the pairing code, role a).
- Phone B: Phoenix Smart 10 (joins, role b).
- Swap roles on a second pass for pairing, ring, and live location.

## Setup B — emulator and desktop partner

- Emulator: one arm64 AVD, API 28 or newer, with a camera if the image has one.
- Desktop, from `core/`: `cargo run --example partner -- <relay-url> <CODE>`
- The emulator shows the code. The desktop tool joins as role b. `curl` must be on PATH.
- Incoming lines start with `RECEIVED:`. Files it downloads are under `/tmp/partner-in/`.
- Where a step says "the other side", use a desktop command. Where the desktop tool cannot do something (camera shutter, home-screen widget, system notifications), run that check on the emulator alone or mark it phone-only.

---

## Pairing

### Phones

1. Fresh install on both, or unpair first. Finish onboarding on both.
2. On A, create a code and leave that screen up. On B, type the code.

Expected: both land on the main tabs. The safety number is the same 6 digits on both Profile screens. A wrong code does not open the app and does not crash.

- [ ] Pass

### Emulator

1. Emulator shows a code.
2. Run the partner command with that code. Wait until it prints `PAIRED` and the safety number.
3. Open Profile on the emulator.

Expected: the safety number matches the number the tool printed. The partner name becomes Desktop. The tool does not exit.

- [ ] Pass

## Safety number

### Phones

1. Open Profile on both.

Expected: the same safety number. It is still there after leaving and reopening Profile.

- [ ] Pass

### Emulator

1. Compare Profile with the `PAIRED` line.

Expected: the digits match. Killing and reopening the app does not change them.

- [ ] Pass

## Profile

### Phones

1. On A, set a name and a photo. Save.
2. On B, pull to the foreground (or wait for the next inbox poll) and open Profile, then the chat header.

Expected: B shows A's name and photo. B can set a different name and A sees that. An empty name is not saved. Airplane mode shows a failure message and does not crash.

- [ ] Pass

### Emulator

1. The tool already sent the name Desktop.
2. On the emulator, set your own name and a photo.

Expected: chat and Profile show Desktop for the partner. Your name stays after you leave the screen. The tool prints a `RECEIVED` profile line when you save.

- [ ] Pass

## Location share on and off

### Phones

1. On A, turn location sharing on and allow location.
2. On B, open Location.
3. On A, turn sharing off.

Expected: B gets a place and a battery reading while sharing is on. After off, B keeps the last place and shows sharing as off. No crash if location permission is denied (a hint, sharing does not pretend to be on).

- [ ] Pass

### Emulator

1. Desktop: `share on`
2. Desktop: `loc <lat> <lon> <bat>` with a real-looking pair inside range and a battery from 0 to 100.
3. Desktop: `share off`

Expected: the emulator map and the location card show that point and battery after `loc`. `share off` marks the partner as not sharing and keeps the last point. `loc 99 0 10` and `loc 0 0 101` are refused by the tool with a usage line, and the emulator does not move.

- [ ] Pass

## Live

### Phones

1. On A, turn Live on (sharing turns on with it). Walk or wait for a few updates. Watch B.
2. Turn Live off.

Expected: B's place updates every few seconds and shows live. A shows a persistent live notification that does not include coordinates. Live off stops the updates and clears A's live notification. B keeps the last place.

- [ ] Pass

### Emulator

Live ticks are not sent by the desktop tool. On the emulator, turn Live on, then off.

Expected: the live notification appears and goes away. Denying location permission does not crash. Desktop `share on` / `share off` still updates the partner sharing flag.

- [ ] Pass

## Battery

### Phones

1. With sharing on, note the battery percent on the other phone.
2. Charge or unplug and wait for the next update.

Expected: the percent changes with the next location update. It stays in 0–100. The relay is not showing the number in any log you can read (ciphertext only).

- [ ] Pass

### Emulator

1. Desktop: `loc <lat> <lon> 41` then `loc` again with the same place and battery 42.

Expected: the emulator battery line changes from 41 to 42. The place does not jump.

- [ ] Pass

## Ring — screen on

### Phones

1. B is unlocked, app open or in another app, screen on.
2. A taps Ring.

Expected: B rings (sound or vibration plus the ring screen) within a few seconds. Stop on B ends it. A sees a sent or failed hint, not a crash, if the network is off.

- [ ] Pass

### Emulator

1. Emulator unlocked.
2. Desktop: `ring`

Expected: the emulator rings. `RECEIVED` is not required on the tool for this direction. Ring again after stopping. It rings again.

- [ ] Pass

## Ring — screen off

### Phones

1. Lock B and turn the screen off. Wait until it is asleep but not yet in deep idle.
2. A taps Ring.

Expected: B wakes and rings. If FCM is not configured, this may fail while the process is frozen. Record which case you hit.

- [ ] Pass

### Emulator

1. Emulator screen off (power button).
2. Desktop: `ring`

Expected: same as the phones. Record whether FCM was configured.

- [ ] Pass

## Ring — Doze, or after about an hour

### Phones

1. On B, leave the screen off for about an hour, or force idle: `adb shell dumpsys deviceidle force-idle` (then `adb shell dumpsys deviceidle unforce` when finished).
2. A taps Ring.

Expected: with FCM configured, B still rings. Without FCM, B rings only after the screen is on or the app is opened. The app does not crash either way.

- [ ] Pass

### Emulator

Same steps, desktop `ring` instead of the button.

- [ ] Pass

## Widget, 4 tiles

### Phones

1. Add the Loviedowpie widget (Track, Ring, Live, Settings). It does not resize.
2. Tap Track, Ring, Live, Settings, in that order. Try again while unpaired (or after unpair, on a spare home screen if the widget remains).

Expected: Track opens the map. Ring rings the partner. Live toggles and the tile shows on or off. Settings opens Profile. Unpaired, the widget asks you to pair and does not crash.

- [ ] Pass

### Emulator

Same widget steps on the emulator home screen. The desktop tool is not involved, except Ring should still reach it: the tool prints a `RECEIVED` line with `"t":"ring"`.

- [ ] Pass

## Drawing widget, resize 2×2 through 4×4

### Phones

1. Add the drawing widget. Resize from the smallest (about 2×2) through 3×3 to the largest (about 4×4).
2. With no drawing yet, then after the partner sends one, then after you save one on this phone and the partner has none.

Expected: empty state shows the short hint. After a drawing arrives, the picture fills the widget at each size without crashing. Tap opens the Activity tab. Rotating or relaunching keeps the latest picture.

- [ ] Pass

### Emulator

1. Same resize pass on the emulator.
2. Desktop: `draw <file.png>` with a small PNG.

Expected: within a minute the widget shows that PNG. The tool's send line is http 200. A missing path prints an error and the widget stays as it was.

- [ ] Pass

## Chat text and emoji

### Phones

1. A sends a short note, a note with emoji, and a long note (several lines).
2. B replies with emoji only.
3. Turn the network off and send one more line. Turn it back on and retry from the long-press menu.

Expected: both sides show the text and emoji. Times show. The failed line can be retried and then shows as sent. Nothing in logcat is the message text.

- [ ] Pass

### Emulator

1. Desktop: `chat hello` and `chat hi 😀`
2. From the emulator, send a line back.

Expected: both appear in the emulator chat. The tool prints `RECEIVED` with the emulator's text. `chat` with no text prints usage and sends nothing.

- [ ] Pass

## Unread badge

### Phones

1. B sits on Location, app open. A sends two messages.
2. B opens Chat.
3. B backgrounds the app. A sends one more. B opens the app on Chat from the notification, if it is shown.

Expected: while B is not on Chat, the Chat tab shows a badge with the count. Opening Chat clears it. The notification title is only "New message" (no text, no name, no count). With the app in the foreground on another tab, the badge updates and a heads-up notice is not required.

- [ ] Pass

### Emulator

1. Leave the emulator on Location.
2. Desktop: `chat one` then `chat two`.
3. Open Chat.

Expected: badge shows 2, then disappears on Chat. Messages are the two lines. No notification while the emulator activity is in the foreground.

- [ ] Pass

## Photo from gallery

### Phones

1. A attaches a gallery photo, with and without a caption if the UI has no caption field (sending the photo alone is enough).
2. B waits for it, opens it, and long-presses to save if a save action is offered by the viewer you use.

Expected: B sees the photo. Progress or a retry appears if the network drops. Retry downloads it. A file over the limit is refused with a message, not a crash.

- [ ] Pass

### Emulator

1. Desktop: `photo <file.jpg>`
2. On the emulator, attach a gallery image the other way.

Expected: the emulator shows the desktop photo. The tool prints `RECEIVED` and writes a file under `/tmp/partner-in/`.

- [ ] Pass

## Camera photo and video at each quality

Phone and emulator only. The desktop tool has no camera. On the emulator, skip a quality the AVD cannot open.

1. From Chat, open the camera. Deny permission once, then allow it.
2. For every photo quality in the quality sheet, take a photo, check the preview, send it.
3. Repeat for every video quality. Once with microphone allowed, once with "record without sound".
4. Retake one clip instead of sending. Close the camera without sending.

Expected: each listed quality either captures or shows a clear failure and returns to a usable camera. Sent items arrive as photo or video. Silent video has no audio track. Retake and close do not upload (the partner receives nothing). Flash, flip, and zoom do not crash if the device supports them. They are skipped quietly if it does not.

- [ ] Pass

## GIF and Emoji Kitchen sticker

### Phones

1. In chat, from Gboard, send a GIF.
2. From Gboard Emoji Kitchen (or any transparent PNG sticker), send a sticker.
3. On the other phone, look at both on a non-white area if you can (dark theme).

Expected: the GIF animates. The sticker keeps its transparent background (the bubble is not a white rectangle behind the art). A normal JPEG from the keyboard can still arrive as a photo.

- [ ] Pass

### Emulator

1. Desktop: `gif <file.gif>` and `sticker <file.png>` using a PNG that has transparent pixels.
2. If the emulator keyboard can insert a GIF, send one back.

Expected: GIF animates. The PNG sticker stays transparent. The tool saves both files under `/tmp/partner-in/` when the emulator sends them.

- [ ] Pass

## PDF or any other file

### Phones

1. Attach a small PDF, then a different file (for example a txt).
2. On the other phone, tap to open.

Expected: the name and size show. Open hands the file to another app. If nothing can open it, a snackbar says so and the app stays up. A file over 20 MB is refused before upload.

- [ ] Pass

### Emulator

1. Desktop: `file <path>` with a small PDF.
2. From the emulator, attach a small text file.

Expected: the PDF row appears and opens. The tool writes the text file under `/tmp/partner-in/`.

- [ ] Pass

## Link preview

### Phones

1. Send `https://example.com` as the whole message, then a sentence that contains an https link.
2. On the other phone, tap the card.

Expected: the sender's phone builds the card (title, description, image when the site has one). The receiver does not need to load the site to see the card. Tap opens a browser. A non-http link is not opened. Airplane mode on the sender still sends the text; the card may be empty and that is fine.

- [ ] Pass

### Emulator

1. Send `https://example.com` from the emulator.
2. Desktop has no link command. `chat https://example.com` sends plain text only, so the emulator will show it as text, not a card.

Expected: the emulator-sent link shows a card on the emulator and the tool prints the link JSON (`"t":"link"`) if the preview fitted. The tool's plain `chat` line shows as text.

- [ ] Pass

## Auto-save

### Phones

1. Chat menu, chat settings. Auto-save is off. Receive a photo. Confirm it is not in the gallery.
2. Turn auto-save on. Receive another photo and a short video. Receive a sticker.
3. Turn it off again.

Expected: only photos and videos received while the switch is on land in the gallery. Stickers, GIFs, and files never do. The switch survives leaving the screen.

- [ ] Pass

### Emulator

Same switch, using `photo` and `sticker` from the desktop tool. Check the emulator gallery app or Photos.

- [ ] Pass

## Wallpaper

### Phones

1. Chat settings. Pick a color, then the default, then a photo. Send a message on top of each.

Expected: the chat background changes immediately. Text stays readable. A failed photo shows an error and leaves the previous wallpaper. The choice is still there after restart.

- [ ] Pass

### Emulator

Same three choices on the emulator. The desktop tool is not involved.

- [ ] Pass

## Reactions

### Phones

1. Long-press your message and the partner's message. Confirm six choices: heart, laugh, thumbs up, surprised, sad, folded hands.
2. Set one on each. Change one.

Expected: the emoji sits under that bubble on both phones. A later reaction replaces the previous one. No extra notification text beyond "New message" if the app is backgrounded.

- [ ] Pass

### Emulator

The desktop tool does not send reactions. On the emulator, long-press a `chat` line from the tool and react.

Expected: the chip appears under that bubble. The tool prints `RECEIVED` with `"t":"react"` and the same id.

- [ ] Pass

## Reply

### Phones

1. Swipe a message sideways until the reply bar appears. Send a short reply.
2. Long-press another message, choose Reply, send.
3. Start a reply, open the camera, send the photo, and also cancel a reply with the X before sending once.
4. Delete the quoted message on one phone only, then look at the reply.

Expected: the outgoing bubble shows a one-line quote. The other phone shows the same quote. Camera send keeps the quote you armed. Cancel sends a normal message with no quote. If the original is gone locally, the quote reads "Message".

- [ ] Pass

### Emulator

1. Swipe or long-press a desktop `chat` line and reply from the emulator.
2. Desktop `chat` has no reply field, so a tool message is a plain reply target only.

Expected: the emulator shows the quote. The tool prints `"re"` on the received JSON. Swipe still lets the list scroll vertically.

- [ ] Pass

## Drawing send and save

### Phones

1. On Activity, draw a stroke, undo, clear, and draw again. Toggle white paper.
2. Save. Send to partner.
3. On the other phone, open Activity without tapping send.

Expected: the partner sees the drawing without a chat-style message. Save keeps a thumbnail on Profile. Send failure (airplane mode) shows an error and keeps the canvas. Undo and clear do what they say.

- [ ] Pass

### Emulator

1. Draw and save on the emulator. Send.
2. Desktop: `draw <file.png>`.

Expected: the tool prints `RECEIVED` for your send and writes a PNG under `/tmp/partner-in/`. The emulator Activity tab shows the desktop PNG. The drawing widget updates. `draw` uses no wake; you may need the app open or the 5-second poll.

- [ ] Pass

## Saved drawings

### Phones

1. Save two drawings. Open each from Profile. Delete one. Send the other again.

Expected: open shows the full picture. Delete removes only that one. Send again reaches the partner. Unpair (later) clears this list.

- [ ] Pass

### Emulator

Same steps on the emulator. Send again should make the tool save another PNG.

- [ ] Pass

## Notifications settings

### Phones

1. Profile, Notifications. Turn message notices off. Background the phone. Have the partner send a chat.
2. Turn them on. Repeat. Then turn drawing notices off and receive a drawing in the background.
3. Tap "System notification settings".

Expected: off means no notice for that type. On, background, the title is only "New message" or "New drawing". The system screen is this app's notification settings (or the app info page if the first screen is missing). The switches stay after you leave Profile.

- [ ] Pass

### Emulator

Same switches. Use desktop `chat` and `draw` while the emulator activity is stopped (Home), not while it is visible.

Expected: notices follow the switches. Foreground activity does not post one.

- [ ] Pass

## Update flow

Do this only when you mean to publish. Bump `versionCode` above the installed build first.

1. Run `scripts/release.sh` with a notes file. It prints each step and writes `dist/Loviedowpie-v<version>.apk` and `dist/latest.json`.
2. On a phone still on the older version, Profile, Check for updates. Install.

Expected: the banner shows the new version and the notes. The installer opens. The sha256 of the downloaded file matches `latest.json`. Canceling the installer leaves the old app usable. With no newer version, the check says you are up to date and does not crash offline.

The desktop tool is not part of this check. Repeat the check once on the emulator if you want a second device.

- [ ] Pass

## Unpair

### Phones

1. Note your name, photo, and notification switches.
2. Unpair from Profile and confirm.

Expected: the app returns to pairing (or onboarding if that was never finished). Chat, drawings, partner location, and the safety number for that pair are gone. Your name, photo, and notification switches are still there. The partner's next message does not appear. Widgets show the unpaired hint instead of crashing.

- [ ] Pass

### Emulator

1. Unpair on the emulator.
2. Desktop: `chat still there`

Expected: the emulator is back at the pair screen and does not show the chat. The tool may print a send error or a partner that can no longer decrypt. It should not be required to keep working after unpair. Start a new code and a new tool process to pair again.

- [ ] Pass
