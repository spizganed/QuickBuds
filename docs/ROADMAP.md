# QuickBuds — In Progress

The live plan and the current state. Finished work is removed from this file.

- The developer decides when to release.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## The plan, in order (2026-09-27)

Finish each step before the next one starts.

1. **PC version** (Windows and Linux, Rust + Slint in `desktop/`). The pages are done. Open:
   - **Question — Dot matrix font on PC:** Doto at 12-15 px on a 1x screen smears into thin grey
     strokes (a dot is under 2 px). The rest of the style works. He picks one:
     Doto only for text of 20 px and up (system font for the rest), all Dot matrix text at 20 px and
     up, or a second pixel font made for small sizes.
   - Personalized ANC, the dual-connection device manager and the preferred device have no owner to
     test them. Treat them as working until an issue says otherwise (2026-10-06).
   - **Parked — AUR** `quickbuds-bin` (`desktop/aur/`): publish when AUR registration opens again.

## Unverified

- **realme models:** 27 realme models besides the Air7 Pro use realme Link data (noise control,
  gestures, wind noise). Only the Air7 Pro's owner has confirmed it (5 neckbands with `oneButton`,
  5 with `bothHold`).

## Decided against — do not suggest again

- A resizable 2x2 widget, a 3x3 size, and 3x2 / 2x3 shapes: they broke the layout (2026-09-30).
- More widget sizes (4x1, 2x1): not until he has a good idea for one (2026-09-30).
- Right-to-left languages (Arabic, Urdu, Persian, Hebrew): they need a mirrored layout and a check of
  every custom-drawn view (2026-09-27).
- A lock-screen widget, a widget on/off switch, the Quick Settings tile (removed) and the fixed-level
  hold (2026-09-26).
- Slide up vs slide down: the firmware maps up/down itself, as with HeyMelody (2026-09-26).
- Widgets bigger than the launcher's padding: the host clips them. Ours fill the same box as Nothing's
  own widgets.
- The hearing profile's before / after preview (`0x040E 01` / `02`) (2026-09-29).
- Renaming a hearing profile: the date and time label each one well enough (2026-09-29).
- Voice wakeup (`0x14`, needs OPPO's Breeno), voice commands (`0x19`, Chinese only) and incoming-call
  voice control (`0x39`) (2026-09-29).
- Neck health (`0x22`-`0x24`, needs OPPO's Health app) and meeting assistant (`0x34`, voiceprint for
  one meeting app) (2026-09-29).
- Earphones Lab (HeyMelody's experimental page) and Connection info (the status chip shows it)
  (2026-09-29).
- Conversation mode (`smartCall`, `0x011D`) and Spotify Tap (`spyTap`): no model in HeyMelody's list
  sets either flag, so HeyMelody never shows them.
- Earbud fall detection (`deviceLostRemind`, only Enco Clip2) (2026-09-29).
- Custom UI styles as a file (import / export): only the built-in styles (2026-09-29).
- **Any feature that needs a server** (OPPO's or anyone's). Exception: the problem report's Google Form (2026-09-29).
- Firmware updates: a failed flash can brick the buds. The firmware row says to update from HeyMelody
  (2026-09-29).
- From HeyMelody's device page (2026-09-29): Zen mode and Sound space / white noise (sound packs
  from OPPO's servers); the tap camera shutter (needs OPPO's camera app); realme's "More functions" (it
  only opens realme Link); AI translation, summary and clear call (ColorOS only); skins, guides,
  tutorials and diagnostics.
- Guessed protocol payloads before a capture.
- Hardcoded gesture button groups: the write is table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Release versions on roadmap items.
