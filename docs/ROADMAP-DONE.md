# QuickBuds — Done

What is finished and confirmed. The live plan is in [ROADMAP.md](./ROADMAP.md).

## Connection and push

- Any paired OPPO / OnePlus / realme buds connect, not only his Buds 4 (2026-09-28, GitHub issue #1):
  `BudsDevice` replaced a hardcoded MAC left from the single-device days; saved address, else the first
  bonded device with the `079A` / `1107` UUID or a `models.json` name.
- Packet logging: every received `AA` frame and every sent command, timestamped.
- Wear, battery, ANC and Game Mode are pushed by the buds (PROTOCOL.md). Nothing is polled: the
  300 s status keep-alive was dropped 2026-09-27 after days of use without it showed no stale link.
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

- Auto-connect when the phone's audio link comes up (A2DP / HFP), confirmed by him 2026-09-27.

## Controls

- ANC: Off / Transparency / Adaptive / Low / Medium / High on the main screen and widget.
  Changes made on the buds show up in the app.
- Earbud gestures: tap, double, triple, hold and slide per bud. The function values were measured,
  not guessed (PROTOCOL.md §5–6). Hold follows HeyMelody's rule of at least one mode.
- Gestures per model (2026-09-29): rows and options from HeyMelody's `control` / `callControl` lists,
  plus volume up / down and switch devices (PROTOCOL.md §6).
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
- Smart as a fourth ANC level (app and widget, confirmed on Buds 4, 2026-09-27).
- Firmware version (`0x0105`) in Earbud settings › About earbuds, formatted as HeyMelody shows it
  (PROTOCOL.md §3).
- Alert-sound volume slider (HeyMelody style, muted icon at the lowest step) in Earbud settings → Sounds (PROTOCOL.md §9).
- In-app updater from GitHub releases.

## Appearance

- UI revision after design/SPEC.md (2026-09-26): six-token themes (OLED Black, Classic Dark, White),
  an accent per built-in theme, up to 3 custom colour presets with a live preview, a full Settings
  screen, and a home screen whose rows can be dragged and hidden. Details in CLAUDE.md.
- White preset redesigned and Match system (White in light mode, OLED Black or Classic Dark in dark
  mode, live, widgets included), 2026-09-27. Checked on device both ways.
- Colour picker: the last five committed colours under the quick swatches.
- Classic / Nothing style for the app and the widgets, one switch in Theme & colors (2026-09-28):
  Nothing's dot font, no cards, dot-matrix rings, icons, switches, sliders and EQ curve.
- One font family per style (`sans-serif` in Classic; the OEM font no longer leaks in).
- Home noise control: the ANC segment slides into the level picker (Smart included) and shows the level.
- Launcher and notification icons from the app's own bud glyphs; the themed icon is one bud.
- 27 languages (26 machine-drafted), picked in the app's own Language screen. Checked on device.
- Wear and case icons traced verbatim from `local/svgs/`. Portrait-locked on every screen.

## Widgets

- 2x2, 3x3 (the 2x2 scaled) and 4x2, fixed size, each with a battery page and a controls page (ANC with a
  level picker, Transparency, Adaptive, Low latency), swapped by a button or a double tap (200 ms).
- Slides only, like a carousel; the widget stays on the controls page after a change. Haptic tick on taps.
- Both styles share one design (`scripts/widget-layouts.py`); the cycle mode and mode button are gone.
  Details in CLAUDE.md, Widgets.

## Tooling and release

- Dev Tools: Human-readable / Raw hex log, Clear, Export, Reconnect, Disconnect, Crash test, styled like
  Settings (2026-09-27). The layout, screenshot and widget reports were deleted; adb covers them.
- Crash handler installed in `QuickBudsApp.attachBaseContext`, before any app code; verified on device.
- Dead code sweep (2026-09-27): lint `UnusedResources` and unreferenced Kotlin.
- Signed release builds with a version set in one place (`app/build.gradle.kts`).
- The 60-minute wakelock is gone (2026-09-28); `dumpsys power` shows it no longer taken.
- README screenshots scripted (`scripts/readme-screenshots.sh`, Classic and Nothing sets).

## Docs

- Docs moved to `docs/`; only README, LICENSE and CLAUDE.md stay in root (2026-09-25).
- LICENSE rewritten from the official gnu.org GPL-3.0 text; GitHub detects it as `gpl-3.0`. The
  copyright notice (author, app, GPL-3.0-or-later) is in the README (2026-09-25).
- Account mentions removed: neither HeyMelody nor QuickBuds needs one (2026-09-25).
