# QuickBuds — In Progress

The live plan and the current state. Finished work is removed from this file.

- The developer decides when to release.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody.** Our own improvements come next, other models after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## The plan, in order (2026-09-27)

Finish each step before the next one starts.

1. **PC version** (Windows and Linux, Rust + Slint in `desktop/`). The pages are done. Open:
   - **Next — Windows test:** he runs the cross-built zip (Winsock connect, tray left click / quick
     panel, EQ, ANC). The zip has never run on Windows.
   - **Question — Dot matrix font on PC:** Doto at 12-15 px on a 1x screen smears into thin grey
     strokes (a dot is under 2 px). The rest of the style works. He picks one:
     Doto only for text of 20 px and up (system font for the rest), all Dot matrix text at 20 px and
     up, or a second pixel font made for small sizes.
   - Personalized ANC, the dual-connection device manager and the preferred device have no owner to
     test them. Treat them as working until an issue says otherwise (2026-10-06).
   - **Parked — AUR** `quickbuds-bin` (`desktop/aur/`): publish when AUR registration opens again.

## Open

- **Buds 4 check of v4.5.0:** he checks the per-model `0x010D` query and the `0x012F` batch on the Buds 4.

## Later

- **In-app problem report** (Android and desktop, same form). The user needs no account and never
  leaves the app.
  - Settings › Report a problem: buds model (auto-filled, or a list with autocomplete), one or more
    categories (UI, lag, connection, feature not working, battery, other), a short description, and
    an optional log.
  - The app posts the answers to a Google Form (`formResponse`, sign-in off). Android uses
    `HttpURLConnection`; desktop uses `curl`, as the updater does. The log goes in a paragraph field.
  - A "See the report" button shows the exact text before Send. Nothing goes out without Send.
  - The sent and exported log starts with one header line: phone or PC maker and model, OS and
    version, QuickBuds version, buds model and firmware.
  - The sent log keeps the buds name (model lookup uses it). It drops Bluetooth addresses and the
    phone's or PC's own name.
  - First launch shows one notice: where the report screen is, and that nothing is sent without Send.
  - **First step:** every `catch` that swallows an error writes one log line (exception class and
    message). About 33 of the 55 Android `catch` blocks log nothing. Check the desktop too.

## Waiting on others

- **Issue #2, Nord Buds 3 Pro reconnect loop** (Android and desktop): the buds reset the link 3-5 s
  after connect. Since 4.3.0 the apps answer their requests (PROTOCOL.md §9) and subscribe only to
  offered events (§4). Waiting for an owner to test. Read their logs before changing anything.
- **Issue #5, OPPO Enco Buds2 reconnect loop:** the full `0x010D` list dropped the link. v4.5.0 sends
  the per-model query and the `0x012F` batch (PROTOCOL.md §4, §9). Waiting for @Abhishek-banal's retest
  (closed PR #7). Credit them in the release notes.
- **Issue #8, realme models:** Buds Air7 Pro noise control, gestures and wind noise come from realme
  Link data. They are wired and unverified. The reporter is asked to read back the ANC mode, one
  gesture and the hold cycle. 27 more realme models use the same data, also unverified (5 neckbands
  with `oneButton`, 5 with `bothHold`). The generator and its steps are in agent memory
  `realme-link-decompile`.

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
  tutorials and diagnostics. Our problem report is under Later.
- Guessed protocol payloads before a capture.
- Hardcoded gesture button groups: the write is table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Release versions on roadmap items.
