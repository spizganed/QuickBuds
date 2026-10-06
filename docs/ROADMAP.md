# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- Releases happen when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## The plan, in order (`[USER]` 2026-09-27)

Each step is done before the next one starts. Parity for other models, the codebase audit and the
contributor docs are finished (ROADMAP-DONE.md).

1. **PC version** (Windows and Linux), decided 2026-09-30: Rust + Slint in `desktop/`, same repo, every
   phone page that makes sense on a PC (done, ROADMAP-DONE.md › Desktop). Still open:
   - **Windows:** he tests the cross-built zip (Winsock connect, tray left click / quick panel, EQ, ANC).
   - **Dot matrix font on PC:** the style works on his PC (tested 2026-10-06), but Doto at 12-15 px
     on a 1x screen smears into thin grey strokes (a dot is under 2 px). The rest of the UI is fine.
     The hearing test flow works fully on his PC. **Question** (he decides): Doto only for text of
     20 px and up on desktop with the system font for the rest, every Dot matrix text at 20 px and up,
     or a second pixel font made for small sizes.
   - Personalized ANC, the dual-connection device manager and preferred device have no owner to test
     them: treated as working until an issue says otherwise ([USER] 2026-10-06).
   - AUR `quickbuds-bin` (`desktop/aur/`): published once AUR registration reopens.

## Open

Nothing.

## Waiting on others

- **Nord Buds 3 Pro reconnect loop** (issue #2, Android and Debian desktop, 2026-10-05): the buds reset
  the link 3-5 s after connect. 4.3.0 answers their requests (time, PROTOCOL.md §9) and subscribes only
  to offered events (§4). Waiting for an owner to test it.
- **OPPO Enco Buds2 reconnect loop** (issue #5, desktop, 2026-10-06): after `0x0200` the buds answer
  nothing (no `0x8205`, not even `0x0106`) and drop the link ~6 s later. They never send `0x0500`.
  Asked the reporter to try the init without `0x010D`; if that holds, gate only that query.

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
