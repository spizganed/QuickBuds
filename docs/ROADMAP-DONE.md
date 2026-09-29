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

- Hearing profile (HeyMelody's "Golden Sound", renamed, [USER] 2026-09-29): on/off (feature `0x0B`, read
  back), the hearing test in the app (ear scan, 12 tones, save, apply), profiles kept on the phone, the
  buds' own profile read back, and HeyMelody's radar per ear. Full run confirmed by him on Buds 4.
- Earbud fit test (2026-09-29): HeyMelody's sheet from Earbud settings, `0x0405` and event `0x04`,
  confirmed on Buds 4.
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
- Smart as a fourth ANC level (app and widget, confirmed on Buds 4, 2026-09-27).
- Firmware version (`0x0105`) in Earbud settings › About earbuds, formatted as HeyMelody shows it
  (PROTOCOL.md §3).
- Alert-sound volume slider (HeyMelody style, muted icon at the lowest step) in Earbud settings → Sounds (PROTOCOL.md §9).
- In-app updater from GitHub releases.

## Other models

Built from HeyMelody's own model list (`[VENDOR]`) and the OSS clients' packet builders (`[OSS]`). Shown on
device by picking a model by hand; every write stays unverified until an owner of that model reads one
back.

- Capability gating (2026-09-27): the buds' `0x8100` bitmap and `0x810D` list decide which rows and
  connect-time queries appear (PROTOCOL.md §4).
- Per-model noise control (2026-09-27): HeyMelody's `noiseReductionMode` sets the bits both ways and the
  segments, levels and widget buttons (PROTOCOL.md §5).
- Detection and the model list (2026-09-27): product id + Bluetooth name as HeyMelody matches them, the
  model under the rings, and a model picker (Automatic or any of 127 models by brand).
- Built-in EQ presets per model (2026-09-29): HeyMelody's `equalizerMode` names and numbers.
- Gestures per model (2026-09-29): rows and options from HeyMelody's `control` / `callControl` lists, plus
  volume up / down and switch devices; per-bud holds, the hold with top-level ANC levels, on-call single
  tap / double-tap decline (PROTOCOL.md §6).
- The switches Buds 4 lacks (2026-09-29), in Earbud settings › Features: vocal enhancement, game sound
  effects, smart volume, adaptive volume, adaptive ear, pause when asleep, power saving (asks first). Game
  mode writes `0x28` on game-sound buds (PROTOCOL.md §9).
- Device check of the model-list features (2026-09-29, HEAD build on Buds 4): on Automatic, Earbud settings ›
  Features shows none of the new rows (only power saving, which Buds 4 lists itself); Nord Buds 4 picked by hand
  shows Swift Pair and the game sound rows, Open Buds shows touch and hold volume and smart volume. No feature
  write reached the buds.
- Power saving `0x17` on Buds 4 (2026-09-29): listed by the buds, the write works (a restart, audio brought
  back by the app), and nothing visible changes (PROTOCOL.md §9). The row stays ([USER]): it is harmless and
  was researched as far as the phone side can see; its effect is internal to the buds.
- 3D audio's type form `0x0422` (2026-09-29): Off / Fixed / Head tracking on Buds Pro 2, Buds Pro 3 and
  Enco X3, a type switch on other `0x012A` buds; game sound's type `0x0423` as a sheet (PROTOCOL.md §9).
- The rest of HeyMelody's model-list features (2026-09-29), same section: conversation awareness,
  adaptive sound (asks before turning on), touch and hold volume, head gestures with the nod / shake
  choice `0x0431`, Windows Swift Pair. The voice features were decided against (PROTOCOL.md §9).
- Equalizer per model (2026-09-29): the EQ row, custom presets and BassWave appear only where HeyMelody
  shows them; new presets get the model's bands (10 on 8 models) and its preset cap (PROTOCOL.md §9).
  Checked on device with models picked by hand; Buds 4 unchanged. The firmware row explains that updates
  belong in HeyMelody.
- A once-only first-launch note and a README section asking owners of other models to report (3.7.0).

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
- README screenshots scripted (`scripts/readme-screenshots.sh`, Classic and Nothing sets); the README
  shows only the key ones (2026-09-29).
- The in-app updater deletes its downloaded APK on the next start (2026-09-29). The packet log caps
  itself at 2 x 512 KB.

## Docs

- Docs moved to `docs/`; only README, LICENSE and CLAUDE.md stay in root (2026-09-25).
- LICENSE rewritten from the official gnu.org GPL-3.0 text; GitHub detects it as `gpl-3.0`. The
  copyright notice (author, app, GPL-3.0-or-later) is in the README (2026-09-25).
- Account mentions removed: neither HeyMelody nor QuickBuds needs one (2026-09-25).
- Interop facts only (2026-09-29): no vendor class, method or file names in the repo; the `[VENDOR]`
  bytes stay. README trademark notice.
