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
- Gesture, hold and on-call config are read from the buds on every connect.
- Connect/Disconnect drive phone audio too, like HeyMelody's "device sync" (2026-09-25, confirmed
  by him; faster than HeyMelody). Disconnect calls the hidden `BluetoothHeadset/A2dp.disconnect()` by
  reflection, nothing goes to the buds. A user connect (pill, Dev Tools) calls
  `BluetoothA2dp.connect()`; automatic connects leave A2DP to Android, which fixed audio getting stuck
  on auto-connect (2026-09-25). `Headset.connect()` is refused for ordinary apps, and the
  system brings HFP up itself ~10 s later. Found in a btsnoop + bugreport of HeyMelody, 2026-09-24.

## Controls

- ANC: Off / Transparency / Adaptive / Low / Medium / High on the main screen, widget and Quick
  Settings tile. Changes made on the buds show up in the app.
- Earbud controls: tap, double, triple, hold and slide per bud. The function values were measured,
  not guessed (PROTOCOL.md §5–6). Hold follows HeyMelody's rule of at least one mode.
- On-call gestures: the write is verified. The labels still need a real call (see ROADMAP.md).
- Equalizer: built-in presets, Bass boost with level, up to 3 custom presets on a draggable curve
  with rename and delete (PROTOCOL.md §9).
- EQ preset copy / import as text (`QB-EQ:<gains>:<name>`), via the clipboard.
- Hi-Res codec and 3D audio switches (mutually exclusive, with a reconnect warning), and low latency.
- Find my earbuds: the buds' own tone on both buds, with an in-ear warning.
- Wear detection screen: the firmware's auto play/pause, and our own smart auto-pause (pause only
  when both buds are out, never auto-play). The two are mutually exclusive.
- Alert-sound volume slider (HeyMelody style, muted icon at the lowest step) in Earbud controls → Sounds (PROTOCOL.md §9).
- In-app updater from GitHub releases.

## Appearance

- Main screen redesign: a status-ring battery card and a sliding noise-control pill, with a red accent.
- Wear and case icons traced verbatim from `local/svgs/` (confirmed as the newest design 2026-09-24);
  adaptive launcher icon.
- Portrait-locked on every screen.
- Switch knobs turn red when on; row icons, chevrons and header icons all red on grey chips; chevrons centred.
- Device name in the header shows only while connected, fading in on connect.

## Tooling and release

- Dev Tools screen: human-readable log, raw hex log, Mark / Clear / Export, Reconnect / Disconnect.
- Layout-report and screenshot-to-text tools (built; to be hidden, logic kept).
- Signed release builds with a version set in one place (`app/build.gradle.kts`).
