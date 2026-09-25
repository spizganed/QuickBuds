# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- There are no version targets. A release happens when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## Parity: firmware features still missing

None known (2026-09-25). A new one needs a HeyMelody capture first.

## Our own features

- **Fixed-level hold.** Parked. Hold set to ANC only; when the hold's ANC push reports a level
  other than the chosen one (default Medium), the service sends the chosen level. The firmware cannot
  do this itself (PROTOCOL.md §5, tested 2026-09-24). Cost: a double tone whenever the last hand-set
  level differs. Only worth building if a silent level-set command turns up in a capture.
- **Reorder and hide the main-screen rows.** Open. Hold to drag a row into a new order, unlocked by a
  switch in Settings so nothing moves by accident. Rows can also be hidden (e.g. Hi-Res codec, if it
  stays on permanently).

## Settings screen (new, opened from the cog)

- **Themes.**
  - Presets: **OLED black + red** (the default on first install), **dark grey + red** (non-OLED),
    **light** (to be designed; he will judge it), and **follow system** (Material You).
  - Under the presets: an accent picker to swap the red for any colour.
  - A custom palette editor: pick each palette colour (black, the greys, white, the accent) with a
    hex/colour picker. Selected colours sit in slots next to the picker. Up to 3 saved custom
    palettes, each renameable. Reference: `local/refernce_hex_picker/unnamed.png` (htmlcolorcodes.com
    picker): a saturation/brightness square, a hue slider, and a swatch plus `#HEX` field. Our
    palette slots (1–5) sit next to it, in place of that site's shade strip.
  - The palette should also apply to the widget where possible.
- **Hide the Dev Tools button** from the main screen (switch).
- **Background service on/off**, for users who do not want it.
- **Widget on/off.**
- **App update** is in the cog since 2026-09-25. Still to add: an **auto-check on start** switch. The check
  runs in the background and shows one styled dialog when an update exists. It should never nag.
  **The update screen needs a redesign** (updater confirmed working 2.0.0 → 2.1.0, 2026-09-25): it is
  bare bones today. Add a download progress bar, the release notes, the installed and new version
  side by side, and the main screen's card and red-accent style.
- **About.** An in-app WebView of the GitHub README, with two buttons on top: GitHub, and a Ko-fi
  placeholder (not set up yet).

## UI polish

- The palette today: black, two greys, white and red, so five colours. That feeds the theme editor
  above.
- **New widget, rebuilt from scratch.** The old logic and UI are outdated. Start with a **2×2**
  (bud icons, the case if it fits, the battery rings from the main screen, ANC Off / Transparency /
  Low / Medium / High), styled like the main screen. Then 2–3 other sizes, free design, which he
  will judge. Per-widget settings screens are probably over-engineering for now.
- **New app icon.** The current one is acceptable, but a better one is welcome.

## Connection and battery

- **Keep-alive: drop it entirely?** Open. It is now 300 s. Dropping it needs a long session
  without it, to prove the link does not go stale.
- **Replace the connect retry timers with system Bluetooth state.** Listen for Android's own
  "device connected" (A2DP/headset) broadcasts and connect RFCOMM when the audio link comes up,
  instead of 5 s retries.

## Docs cleanup

- README: short and aimed at users, not developers.
- Fold CREDITS.md into a short section, at most 5 lines per referenced repo, with links.
- LICENSE stays the unedited GPL-3.0 text (that is standard). Put the author and app name in the
  README.
- Remove overlap. Protocol facts live only in PROTOCOL.md, and each feature is described once.
- A full human-read pass over every doc, with notes back to him.
- **Retake the README screenshots** once the UI and the widget are final. The current ones are
  placeholders. Rerun `scripts/readme-screenshots.sh`.

## Later

- Other earbud models, once parity and the items above are done.
- Golden Sound (hearing test → EQ): spike only, may be impossible over this protocol.
- Localisation: only English exists; hardcoded strings remain in `MainActivity` dialogs, Dev Tools
  and `BottomSheetDialog` callers.
- Slide up vs slide down: both are written with the same action; which is which is not established.

## Parked

- Lock-screen widget: feasibility only.
- Hide the screenshot-to-text and layout-report tools; keep the logic.
- A build quickstart for contributors (clone → first `assembleDebug`).
- Undecoded families (`0x0501`, broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family,
  `0x0510`): see PROTOCOL.md §12. Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Guessing protocol payloads before a capture.
- Hardcoded gesture button groups: the write must be table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Marking roadmap items with a release version.
