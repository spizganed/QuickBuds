# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- There are no version targets. A release happens when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## Parity: firmware features still missing

- **Hold → fixed ANC mode.** Next. The hold gesture sets one chosen ANC mode (default Medium,
  user-selectable) instead of cycling through them. Needs a capture first; no guessed writes.
- **Dual device.** Next. Parity with HeyMelody: a dual-device switch plus its own screen.
- **Voice assistant gesture.** Open. HeyMelody offers it (probably on double or triple tap), and on
  his phone it opens Gemini / Google Assistant. Its function byte is missing from our list, so it
  needs a capture.
- **Auto play/pause on wear (firmware switch).** Open. The buds handle this themselves, so we only
  need the right TX. `autoPlayPauseOn/Off` exist in the manager but are unconfirmed.
- **Alert-sound volume slider.** Open. Sets the volume of the buds' own prompt sounds (ANC change,
  Game Mode, …). Firmware setting, needs a capture.
- **On-call gestures: confirm the labels.** Open. The write works. He will place a real call and
  report which gesture answers, ends and declines.
- **Case state (open / closed / charging).** Question. Worth doing only if the firmware pushes it
  unprompted. If it only answers a request, drop it. Report what is found either way.

## Our own features

- **Smart auto-pause.** Open. Pause only when **both** buds are out; one bud out keeps playing; never
  auto-play. Built on the wear pushes in our background service. Meant to be used with the firmware
  auto-pause switched off.
- **EQ preset import/export.** Open. Export a custom preset as plain text to the clipboard, and
  import it from pasted text.
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
    palettes, each renameable. He has reference screenshots of the picker he wants.
  - The palette should also apply to the widget where possible.
- **Hide the Dev Tools button** from the main screen (switch).
- **Background service on/off**, for users who do not want it.
- **Widget on/off.**
- **App update** moves here from the main screen, with an **auto-check on start** switch. The check
  runs in the background and shows one styled dialog when an update exists. It should never nag.
- **About.** An in-app WebView of the GitHub README, with two buttons on top: GitHub, and a Ko-fi
  placeholder (not set up yet).

## UI polish

- Switch knobs turn red (the accent) when Low latency, Hi-Res or 3D audio is on.
- Settings-row icons go back to **white**; the red ones do not fit.
- The arrow icons on the Equalizer, Find my earbuds, Earbud controls and App update rows look
  off-centre. Check them.
- A red outline on the battery card, the settings card and the custom sheets.
- The palette today: black, two greys, white and red, so five colours. That feeds the theme editor
  above.
- **New widget, rebuilt from scratch.** The old logic and UI are outdated. Start with a **2×2**
  (bud icons, the case if it fits, the battery rings from the main screen, ANC Off / Transparency /
  Low / Medium / High), styled like the main screen. Then 2–3 other sizes, free design, which he
  will judge. Per-widget settings screens are probably over-engineering for now.
- **New app icon.** The current one is acceptable, but a better one is welcome.

## Connection and battery

- **What sends a request every ~3 s?** Question. He saw it in the logs. The code only has a 60 s
  keep-alive. It may be the buds' own pushes or a screen that is open. Needs a log sample.
- **60 s keep-alive: drop it or stretch it to 300 s.** The buds push battery themselves, and he has
  never lost a connection.
- **Replace the connect retry timers with system Bluetooth state.** Listen for Android's own
  "device connected" (A2DP/headset) broadcasts and connect RFCOMM when the audio link comes up,
  instead of 5 s retries. Also try the UUID that works first (the primary UUID always burns ~5 s).
- **Make sure phone audio is connected when our app connects.** Question. This may need extra
  permissions or a hidden API. Check what is possible and report back first.

## Docs cleanup

- Remove every mention of DeepSeek, CodeAssist, other LLMs and tools, and the mobile→PC move, from
  all docs, the release notes and memory. Commit history stays as it is (his call, 2026-09-24).
- README: short and aimed at users, not developers.
- Fold CREDITS.md into a short section, at most 5 lines per referenced repo, with links.
- LICENSE stays the unedited GPL-3.0 text (that is standard). Put the author and app name in the
  README.
- Remove overlap. Protocol facts live only in PROTOCOL.md, and each feature is described once.
- A full human-read pass over every doc, with notes back to him.

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
- Undecoded families (`0x0500`/`0x0501`, broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family,
  `0x0510`): see PROTOCOL.md §12. Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Guessing protocol payloads before a capture.
- Hardcoded gesture button groups: the write must be table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Marking roadmap items with a release version.
