# QuickBuds — Done

What is finished and confirmed. The live plan is in [ROADMAP.md](./ROADMAP.md).

## Connection and push

- Packet logging: every received `AA` frame and every sent command, timestamped.
- Wear, battery, ANC and Game Mode are pushed by the buds (PROTOCOL.md). The 60 s poll is only a
  keep-alive.
- Reconnect after a lost link (e.g. after a codec switch): fast and consistent.
- Status reply `0x810D` decoded: Hi-Res, 3D audio and low latency show the buds' own state on connect.
- Gesture, hold and on-call config are read from the buds on every connect.

## Controls

- ANC: Off / Transparency / Adaptive / Low / Medium / High on the main screen, widget and Quick
  Settings tile. Changes made on the buds show up in the app.
- Earbud controls: tap, double, triple, hold and slide per bud. The function values were measured,
  not guessed (PROTOCOL.md §5–6). Hold follows HeyMelody's rule of at least one mode.
- On-call gestures: the write is verified. The labels still need a real call (see ROADMAP.md).
- Equalizer: built-in presets, Bass boost with level, up to 3 custom presets on a draggable curve
  with rename and delete (PROTOCOL.md §9).
- Hi-Res codec and 3D audio switches (mutually exclusive, with a reconnect warning), and low latency.
- Find my earbuds: the buds' own tone on both buds, with an in-ear warning.
- In-app updater from GitHub releases.

## Appearance

- Main screen redesign: a status-ring battery card and a sliding noise-control pill, with a red accent.
- Wear and case icons traced verbatim from `local/svgs/` (confirmed as the newest design 2026-09-24);
  adaptive launcher icon.
- Portrait-locked on every screen.

## Tooling and release

- Dev Tools screen: human-readable log, raw hex log, Mark / Clear / Export, Reconnect / Disconnect.
- Layout-report and screenshot-to-text tools (built; to be hidden, logic kept).
- Signed release builds with a version set in one place (`app/build.gradle.kts`).
