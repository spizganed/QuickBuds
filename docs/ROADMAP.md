# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- Releases happen when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## The plan, in order (`[USER]` 2026-09-27)

Each step is done before the next one starts. Parity for other models and the codebase audit are
finished (ROADMAP-DONE.md).

1. **Contributor docs and the toolchain write-up** ([USER] 2026-09-30):
   [CONTRIBUTING.md](./CONTRIBUTING.md) (build quickstart), [PACKET-CAPTURE.md](./PACKET-CAPTURE.md)
   (capture guide) and [TOOLCHAIN.md](./TOOLCHAIN.md) (Termux build and test device, SSH from a
   headless PC). **Next:** links from Reddit / XDA.
2. **PC version** (Windows and Linux), decided 2026-09-30: Rust + Slint in `desktop/`, same repo.
   Window like accessory software plus a tray (battery on hover, right-click quick panel with ANC,
   low latency and more). Every mobile feature that makes sense on a PC. Shipped as a portable `.zip`
   (Windows) and a `.tar.gz` (Linux), no installer for now: no drivers, no services, no helper processes
   `[USER]`. First beta ships in the 4.0.0 release.
   Done: the updater skips desktop releases; Windows: frameless window with our own title bar, sidebar +
   Overview (battery, noise control, low latency), Equalizer page (presets, band editor, Bass boost), tray
   quick panel, APK launcher icon. English only for now `[USER]`.
   **Next, in order** (`[USER]` 2026-09-30):
   1. **Noise control like the phone and the widget** `[USER]`: no separate level bar under Off / ANC /
      Adaptive / Transparency. Clicking ANC slides the segments over to Low / Medium / High / Smart, animated
      (the phone's `AncSegmentedView` level picker); same in the tray panel. **Built 2026-09-30, waiting
      for his check on the buds.**
   2. **Dev tools** `[USER]` asap: the phone's Dev Tools equivalent (packet log Human / Detailed / Raw,
      Clear, Export, Reconnect, Disconnect). **Built 2026-09-30** (sidebar entry; export to
      `Downloads\QuickBuds\`; newest line on top; no copy on long press yet), waiting for his check.
   3. **Reuse the phone UI as much as possible** `[USER]`: same components and look, only arranged to fit
      the desktop layout. **Started 2026-09-30:** Overview is the phone's home (status card L / Case / R with
      wear labels and the device name, noise pill with "ANC M", feature list with the phone's switch and an
      Equalizer row); Equalizer uses split preset rows, a "+ New" row and the value-over-knob bass slider.
   4. Dot matrix style, then the remaining pages (Controls, Hearing profile, Dual connection, Earbud
      settings, App settings).
   5. Linux (BlueZ): works on a Linux PC (2026-10-01), settings sync with the phone. AUR `quickbuds-bin` prepared (`desktop/aur/`), published once AUR registration reopens. Archives in CI.

## Open

Nothing.

## Waiting on others

- **First other-model reports** (issue #1, pratstick: OnePlus Nord Buds 2R, realme Buds Wireless 3,
  Galaxy S24; still no reply, 2026-09-30). Their logs are the first non-Buds 4 evidence: read them
  before changing anything. Every write on another model stays unverified until an owner reads one
  back.

## Decided against — do not re-suggest

- A resizable 2x2 widget and a 3x3 size: 3x2 / 2x3 shapes broke the layout; the 2x2 is fixed ([USER] 2026-09-30).
- More widget sizes (4x1, 2x1): scrapped until he has a good idea for one ([USER] 2026-09-30).

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
- Earphones Lab (HeyMelody's experimental page) and Connection info (the status chip already is it)
  ([USER] 2026-09-29).
- Conversation mode (`smartCall`, `0x011D`) and Spotify Tap (`spyTap`): no model in HeyMelody's list sets
  either flag, so HeyMelody never shows them (2026-09-29).
- Earbud fall detection (`deviceLostRemind`, one model: Enco Clip2) ([USER] 2026-09-29).
- Custom UI styles as a file (import / export): the built-in styles stay the only ones ([USER] 2026-09-29).
- **Any feature that needs a connection to a server** (OPPO's or anyone's): not built ([USER] 2026-09-29).
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
