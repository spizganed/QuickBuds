# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- Releases happen when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## The plan, in order (`[USER]` 2026-09-27)

Each step is done before the next one starts.

1. **Other HeyMelody models: parity per model.** Detection, capability gating, per-model noise control,
   EQ presets and gestures, and every switch Buds 4 lacks are built (ROADMAP-DONE.md, Other models).
   **The UI adapts to the model** ([USER] 2026-09-29): a feature the model lacks is not shown; one it has
   that the app lacks gets its UI built. So every feature in HeyMelody's model list ends up built or
   decided against.
   - **Open: first other-model reports** (issue #1, pratstick: OnePlus Nord Buds 2R, realme Buds Wireless
     3, Galaxy S24; no reply yet, 2026-09-29). The app asks every user once since 3.7.0, and the README
     asks too. Their logs are the first non-Buds 4 evidence: read them before changing anything. Every
     write on another model stays unverified until an owner reads one back.
   - **Next: the rest of the parity check** (2026-09-29, every item on HeyMelody's device page against the app).
2. **PC version: brainstorm session first**, once the Android app is finished. A standalone Windows
   app (Linux too, maybe) that shares the UI style, not the phone layout, plus a tray button for quick
   mode changes and no widget. Language, UI toolkit and code sharing with the app are all open. Same
   repo (`[USER]` 2026-09-27).

## Before the PC brainstorm

In this order, once the Android app is finished ([USER] 2026-09-29):

1. A whole-codebase pass for improvements (`/ponytail-audit`).
2. A write-up of the toolchain and the phone setup (Termux build/test device, SSH from a headless PC),
   then links from Reddit / XDA.

## Parked

- Custom UI styles as a file (import / export, widget and app): the very last thing, maybe after the PC
  app, if at all ([USER] 2026-09-28). Testing it needs a third style.
- A build quickstart and a capture guide for contributors, only if the device file is not enough.
- Undecoded families (broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family): see PROTOCOL.md §12.
  Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Right-to-left languages (Arabic, Urdu, Persian, Hebrew): they need a mirrored layout and a check of
  every custom-drawn view ([USER] 2026-09-27).
- A lock-screen widget, a widget on/off switch, the Quick Settings tile (removed) and the
  fixed-level hold ([USER] 2026-09-26).
- Slide up vs slide down: nothing to do. The firmware maps up/down itself (volume up/down, next/prev)
  when the slide is set through our app, exactly as with HeyMelody ([USER] 2026-09-26).
- Widgets bigger than the launcher's padding allows: the host clips to it; ours already fill the same box
  as Nothing's own widgets (measured 2026-09-27).
- The hearing profile's before / after preview (`0x040E 01` / `02`) ([USER] 2026-09-29).
- Renaming a hearing profile: the date and time label each one well enough ([USER] 2026-09-29).
- Voice wakeup (`0x14`, needs OPPO's Breeno), voice commands (`0x19`, Chinese-only) and incoming-call
  voice control (`0x39`) ([USER] 2026-09-29).
- Neck health (`0x22`-`0x24`, needs OPPO's Health app) and meeting assistant (`0x34`, voiceprint enrolment
  for one meeting app) ([USER] 2026-09-29).
- The time request `0x0500` / `0x0501`: no feature needs it (PROTOCOL.md §9).
- Firmware updates: too risky, a failed flash can brick the buds. The firmware row says to update from
  HeyMelody ([USER] 2026-09-29).
- From HeyMelody's device page ([USER] 2026-09-29): Zen mode (sound packs from OPPO's servers flashed to the
  buds) and Sound space / white noise (the same idea); the tap camera shutter (HeyMelody shows it only with
  OPPO's camera app); realme's "More functions" (it only opens the realme Link app); AI translation, summary
  and clear call (locked to ColorOS); skins, guides, tutorials, feedback, log collection and diagnostics
  (Dev Tools and the crash log cover ours).
- Guessing protocol payloads before a capture.
- Hardcoded gesture button groups: the write must be table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Marking roadmap items with a release version.
