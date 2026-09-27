# QuickBuds — Done

What is finished and confirmed. The live plan is in [ROADMAP.md](./ROADMAP.md).

## Connection and push

- Packet logging: every received `AA` frame and every sent command, timestamped.
- Wear, battery, ANC and Game Mode are pushed by the buds (PROTOCOL.md). The status poll is only a
  keep-alive, every 300 s.
- Poll storm fixed 2026-09-24: every reconnect used to stack another poller (~80 seen, several polls
  a second). There is now one poller per connection, and disconnect cancels it.
- Reconnect after a lost link (e.g. after a codec switch): fast and consistent.
- Status reply `0x810D` decoded: Hi-Res, 3D audio and low latency show the buds' own state on connect.
- The last `0x810D` reply is persisted, so the switches open at the last known state instead of
  jumping when the connect-time read lands (2026-09-25).
- Gesture, hold and on-call config are read from the buds on every connect.
- Connect/Disconnect drive phone audio too, like HeyMelody's "device sync" (2026-09-25, confirmed
  by him; faster than HeyMelody). Disconnect calls the hidden `BluetoothHeadset/A2dp.disconnect()` by
  reflection, nothing goes to the buds. A user connect (pill, Dev Tools) calls
  `BluetoothA2dp.connect()`; automatic connects leave A2DP to Android, which fixed audio getting stuck
  on auto-connect (2026-09-25). `Headset.connect()` is refused for ordinary apps, and the
  system brings HFP up itself ~10 s later. Found in a btsnoop + bugreport of HeyMelody, 2026-09-24.

## Controls

- ANC: Off / Transparency / Adaptive / Low / Medium / High on the main screen and widget.
  Changes made on the buds show up in the app.
- Earbud gestures: tap, double, triple, hold and slide per bud. The function values were measured,
  not guessed (PROTOCOL.md §5–6). Hold follows HeyMelody's rule of at least one mode.
- Dual connection: switch plus connected-device list (a home screen row), HeyMelody's exact write
  sequence (PROTOCOL.md §9, capture 2026-09-25), and HeyMelody's "Add device" pairing instructions.
- Voice assistant gesture on double / triple tap, same options as HeyMelody; `0x03` confirmed
  against HeyMelody's own write (2026-09-25).
- Case lid: a close is announced by an all-zero wear push before the link drops; the app then
  skips its reconnect retries (capture 2026-09-25). There is no lasting lid state to show.
- On-call gestures: write verified, and confirmed on a real call by him (2026-09-25).
- Equalizer: built-in presets, Bass boost with level, up to 3 custom presets on a draggable curve
  with rename and delete (PROTOCOL.md §9).
- EQ preset copy / import as text (`QB-EQ:<gains>:<name>`), via the clipboard.
- Hi-Res codec and 3D audio switches (mutually exclusive, with a reconnect warning), and low latency.
- Find my earbuds: the buds' own tone on both buds, with an in-ear warning.
- Wear detection screen: the firmware's auto play/pause, and our own smart auto-pause (pause only
  when both buds are out, never auto-play). The two are mutually exclusive.
- Alert-sound volume slider (HeyMelody style, muted icon at the lowest step) in Earbud settings → Sounds (PROTOCOL.md §9).
- In-app updater from GitHub releases.

## Appearance

- UI revision after design/SPEC.md (2026-09-26): six-token themes (OLED Black, Classic Dark, White),
  an accent per built-in theme, up to 3 custom colour presets with a live preview, a full Settings
  screen, and a home screen whose rows can be dragged and hidden. Details in CLAUDE.md.
- Wear and case icons traced verbatim from `local/svgs/` (confirmed as the newest design 2026-09-24);
  adaptive launcher icon.
- Portrait-locked on every screen.

## Tooling and release

- Dev Tools screen: human-readable log, raw hex log, Mark / Clear / Export, Reconnect / Disconnect.
- Dev Tools trimmed to the log, Export, link controls and Crash test (2026-09-27); the layout,
  screenshot and widget reports were deleted, adb covers them.
- Signed release builds with a version set in one place (`app/build.gradle.kts`).

## 2026-09-27

- CLAUDE.md: cloud session rules removed; test phone is on Android 16.
- Crash handler installed in `QuickBudsApp.attachBaseContext`, before any app code; Dev Tools' Crash
  test verified on device (report written, next-launch dialog shown).
- Widget mode list: a pick no longer waits up to 5 s (the list close no longer holds the broadcast
  with `goAsync`). Confirmed by him.
- One 2x2 widget with two pages (battery / controls), switched by a swap button or a double tap
  (Widget settings); the page is stored per widget. Page changes and the mode list cross-fade on every
  size, and widget taps give a haptic tick. Confirmed by him.
- In-app language screen (`LanguageActivity`) replaces the link to Android's per-app screen. Confirmed
  by him.
- Twelve more languages, machine-drafted: Russian, Ukrainian, Turkish, Japanese, Korean, Malay, Filipino,
  Bengali, Czech, Hungarian, Greek, Swedish (26 in all). Checked on device: home screen strings in ru, ja,
  fil, el, bn, and picking Čeština from the in-app list.

## Docs

- Docs moved to `docs/`; only README, LICENSE and CLAUDE.md stay in root (2026-09-25).
- LICENSE rewritten from the official gnu.org GPL-3.0 text; GitHub detects it as `gpl-3.0`. The
  copyright notice (author, app, GPL-3.0-or-later) is in the README (2026-09-25).
- Account mentions removed: neither HeyMelody nor QuickBuds needs one (2026-09-25).
