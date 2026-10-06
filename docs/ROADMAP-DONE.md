# QuickBuds — Done

Finished and confirmed work, one line per item. The live plan is in [ROADMAP.md](./ROADMAP.md).
Details are in PROTOCOL.md, CLAUDE.md and git history.

## Connection and push

- Any paired OPPO / OnePlus / realme buds connect (issue #1). Was: a hardcoded MAC. Is: the saved
  address, else the first bonded device with the `079A` / `1107` UUID or a `models.json` name.
- The buds push wear, battery, ANC and game mode (PROTOCOL.md §4). Nothing is polled.
  Was: a 300 s status keep-alive. Is: none; days of use showed no stale link.
- Reconnect after a lost link (for example after a codec switch) is fast and consistent.
- The status reply `0x810D` is decoded and persisted. Hi-Res, 3D audio and low latency open at the
  last known state.
- Gesture, hold and on-call config are read on every connect.
- Connect / Disconnect also drive phone audio, as HeyMelody's "device sync" does. Disconnect calls
  the hidden `BluetoothHeadset/A2dp.disconnect()` by reflection; nothing goes to the buds. A user
  connect calls `BluetoothA2dp.connect()`; automatic connects leave A2DP to Android (Was: audio got
  stuck on auto-connect). `Headset.connect()` is refused for ordinary apps; the system brings HFP up
  about 10 s later.
- Auto-connect when the phone's audio link comes up (A2DP / HFP).
- Model lookup in HeyMelody's order: name and id, then name, then id (issue #5). Was: id first, which
  hid the Enco Buds2 equalizer (they report the realme Buds Q2s id).
- Settings › "Hide old battery levels" (issue #5, default off).

## Controls

- Hearing profile (HeyMelody's "Golden Sound"): on/off, the hearing test in the app, profiles kept on
  the phone, the buds' own profile read back, the radar per ear. Confirmed on Buds 4.
- Earbud fit test (`0x0405`, event `04`). Confirmed on Buds 4.
- ANC: Off / Transparency / Adaptive / Low / Medium / High / Smart, on the main screen and the widget.
  Changes made on the buds show in the app.
- Earbud gestures: tap, double, triple, hold and slide per bud, with measured `fn` values (PROTOCOL.md
  §6). Hold keeps at least one mode, as HeyMelody does.
- Voice assistant gesture on double / triple tap (`0x03`, matches HeyMelody's write).
- On-call gestures: write verified and confirmed on a real call.
- Dual connection: switch, connected-device list, HeyMelody's write sequence and "Add device" help.
- Case lid: an all-zero wear push announces a close. The app skips its reconnect retries.
- Equalizer: built-in presets, Bass boost with level, up to 3 custom presets on a draggable curve
  with rename and delete.
- Hi-Res and 3D audio switches (mutually exclusive, with a reconnect warning), and low latency.
- Find my earbuds (both buds, in-ear warning).
- Wear detection sheet: the firmware's auto play/pause, or our smart auto-pause (pause only when both
  buds are out). The two are mutually exclusive.
- Firmware version (`0x0105`) in Earbud settings › About earbuds, formatted as HeyMelody shows it.
- Alert-sound volume slider in Earbud settings › Sounds.
- In-app updater from GitHub releases.

## Other models

Built from HeyMelody's model list (`[VENDOR]`) and the OSS clients (`[OSS]`). Every write stays
unverified until an owner of that model reads one back.

- Capability gating: the `0x8100` bitmap and the `0x810D` list decide rows and connect-time queries.
- Detection and a model picker (Automatic, or any model by brand). The model shows under the rings.
- Per model: noise control modes and levels, built-in EQ presets, EQ row / custom presets / BassWave,
  10-band presets and preset cap, firmware-gated presets, gesture rows and options (per-bud holds,
  on-call rows).
- Earbud settings › Features: every switch in HeyMelody's model list except the ones decided against
  (vocal enhancement, game sound, smart / adaptive volume, adaptive ear, pause when asleep, power
  saving, conversation awareness, adaptive sound, touch-and-hold volume, head gestures, Swift Pair).
- Power saving `0x17` works on Buds 4 (restart, audio brought back) with no visible effect. The row
  stays `[USER]`.
- 3D audio type (`0x0422`): Off / Fixed / Head tracking where the model has it. Game sound type as a sheet.
- Personalized noise cancellation, tap sensitivity, the dual connection device manager and the codec
  picker (`highAudio` models). Unverified: Buds 4 lacks them.
- Parity check: every item on HeyMelody's device page is built or decided against.
- A first-launch note and a README section ask owners of other models to report.

## Appearance

- UI after design/SPEC.md: OLED Black, Classic Dark and White, an accent per theme, up to 3 custom
  colour presets with live preview, a Settings screen, home rows that can be dragged and hidden.
- Match system: White in light mode, OLED Black or Classic Dark in dark mode, widgets included.
- Colour picker keeps the last five committed colours.
- Classic / Dot matrix style for the app and the widgets, one switch.
- One font family per style. The OEM font no longer leaks in.
- Home noise control: the ANC segment slides into the level picker and shows the level.
- Launcher and notification icons from the app's bud glyphs. The themed icon is one bud.
- 27 languages (26 machine-drafted) in the app's own Language screen.
- Wear and case icons traced from `local/svgs/`. Every screen is portrait-locked.
- Dot style: dotted boxes for buttons, chips, sheets and dialogs. Rule-drawn action icons.
- Find my earbuds, Wear detection and every confirm, notice and crash report are bottom sheets.

## Widgets

- 2x2 and 4x2, each with a battery page and a controls page (ANC with level picker, Transparency,
  Adaptive, Low latency). A double tap swaps pages. Haptic tick on taps.
- Both styles share one design (`scripts/widget-layouts.py`). The ANC button shows the level ("ANC L").

## Tooling and release

- Dev Tools: Simple / Detailed / Raw log, Clear, Export, Reconnect, Disconnect, Crash test. Unknown
  packets show in amber with their payload. The packet log caps itself at 2 x 512 KB.
- Crash handler in `QuickBudsApp.attachBaseContext`, before any app code.
- Dead code sweeps and a whole-codebase audit (`/ponytail-audit`).
- Signed release builds. The version is set in one place (`app/build.gradle.kts`).
- Was: a 60-minute wakelock. Is: none.
- README screenshots are scripted (`scripts/readme-screenshots.sh`, about 1.5 minutes).
- The in-app updater deletes its downloaded APK on the next start.
- Selection outlines slide on the language and model lists. Dot-matrix boxes of one size share a
  bitmap (model list: 0.12 s, was 1.08 s).

## Desktop

- Rust + Slint app in `desktop/`: frameless window and sidebar, the phone's pages arranged for a PC,
  Dot matrix style, tray (Linux: a menu; Windows: quick panel). Windows `.zip` and Linux `.tar.gz`.
  Linux (BlueZ) checked by him.
- Pages and overview rows hide what the buds lack, as on the phone.
- Self-update from App settings, check on start.
- Noise control slides to the levels, in the window and the tray panel. Dev tools page with the
  packet log, Export (`Downloads\QuickBuds\`), Reconnect, Disconnect.

## Docs

- Docs in `docs/`. Only README, LICENSE and CLAUDE.md stay in the root.
- LICENSE is the official GPL-3.0 text (GitHub detects `gpl-3.0`). The copyright notice is in the README.
- Contributor docs: CONTRIBUTING.md, PACKET-CAPTURE.md, TOOLCHAIN.md.
- Interop facts only: no vendor class, method or file names in the repo. README trademark notice.
- All docs in ASD-STE100 style (CLAUDE.md › Writing rules).
