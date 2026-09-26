# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- Releases happen when the developer feels the app is ready. The one fixed target is **3.0.0**, after
  the theme and widget work (step 6 below).
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## Parity: firmware features still missing

None known (2026-09-25). A new one needs a HeyMelody capture first.

## The plan, in order (`[USER]` 2026-09-25)

Each step is done before the next one starts.

**UI revision (design/SPEC.md, branch `claude/eager-faraday-p3z8km`, 2026-09-26), awaiting his device
test:** covers step 2 (Settings screen, with the Dev Tools button switch) and the SPEC's version of step 4
(OLED Black / Classic Dark / White, up to 3 custom presets with hue slider, hex field and quick swatches,
contrast warnings). Still open from step 4: auto-detect, changing the accent of a built-in preset, the
saturation/brightness square and recent colours. Step 3: the home tiles are reorderable and hideable
from prefs (`homeTileOrder` / `homeTileHidden`), but the Edit layout screen is not built.

1. **Small fixes and small features.** Bugs and small items first. Start with a read of every doc
   for outdated information; he will report anything he finds on his own read after that.
2. **Settings screen.** The cog opens its own screen, not the bottom sheet it opens today. A
   **Themes / Colour palette** entry lives there, plus the items under *Settings screen contents*.
3. **Edit layout.** An **Edit layout** button starts a mode where the main-screen rows can be
   dragged into a new order and hidden (e.g. Hi-Res codec, if it stays on permanently). Nothing
   moves outside that mode.
4. **Themes.**
   - **3 built-in presets:** **OLED Dark** (today's look, the default), **Dark** (dark grey, for
     non-OLED screens) and **White**. White may not keep the red accent; pick one that sits better
     on white. He judges it.
   - **Auto-detect:** follow the system light/dark setting by picking from the built-in presets.
   - **Accent:** on a built-in preset the user can change the accent colour (red by default).
   - **3 custom presets**, added, removed and renamed like the EQ presets. Each one is edited with:
     - a **hex picker**: saturation/brightness square, hue slider, swatch;
     - the **palette slots 1–5** (black, the greys, white, the accent) next to it;
     - a **`#HEX` field** to paste a value, copy it, or apply it;
     - **saved / recent colours** to pick from again.
   - Reference: `local/refernce_hex_picker/unnamed.png` (htmlcolorcodes.com picker). Take the
     layout idea only; the look follows our own UI (cards, red accent, drawn controls).
   - This means reworking a lot of UI code: every colour has to come from the active palette, not
     from fixed theme resources.
5. **New widget, rebuilt from scratch.** The old logic and UI are outdated. First a **2×2** (bud
   icons, the case if it fits, the battery rings from the main screen, ANC Off / Transparency /
   Low / Medium / High), styled like the main screen. Then **2 more sizes** with their own layouts,
   which he will judge. All of them use the in-app colour scheme. Per-widget settings screens are
   over-engineering for now.
6. **Release 3.0.0**, once the 2×2 and the two other widgets are locked in. It is a big enough step
   for a major version, not 2.2.0 (`[USER]` 2026-09-25).

## Settings screen contents

- **Hide the Dev Tools button** from the main screen (switch).
- **Background service on/off**, for users who do not want it.
- **Widget on/off.**
- **App update** is in the cog since 2026-09-25. Still to add: an **auto-check on start** switch. The
  check runs in the background and shows one styled dialog when an update exists. It should never
  nag. **The update screen needs a redesign** (updater confirmed working 2.0.0 → 2.1.0, 2026-09-25):
  add a download progress bar, the release notes, the installed and new version side by side, and
  the main screen's card and accent style.
- **About.** An in-app WebView of the GitHub README, with two buttons on top: GitHub, and a Ko-fi
  placeholder (not set up yet).

## Our own features

- **Fixed-level hold.** Parked. Hold set to ANC only; when the hold's ANC push reports a level
  other than the chosen one (default Medium), the service sends the chosen level. The firmware cannot
  do this itself (PROTOCOL.md §5, tested 2026-09-24). Cost: a double tone whenever the last hand-set
  level differs. Only worth building if a silent level-set command turns up in a capture.
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
- Undecoded families (broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family,
  `0x0510`): see PROTOCOL.md §12. Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Guessing protocol payloads before a capture.
- Hardcoded gesture button groups: the write must be table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Marking roadmap items with a release version.
