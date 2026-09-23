# QuickBuds — Roadmap & Plan

**What this file is.** A checklist of plans: what is planned, in what order, and whether it is
done, partly done, or still undecided. It is written for the developer to remember his own plan,
and it is the first thing the agent should read to know what to work on next.

**How to read it.**

- Status words: **Done**, **Partly**, **Next**, **Undecided**, **Parked**.
- There is no release targeting. Releases happen when the developer feels the app is ready;
  nothing here is marked "must ship in version X".
- **Protocol findings do not live here.** They belong in [PROTOCOL.md](./PROTOCOL.md), which is the
  single reference for the wire format. Where an item below depends on protocol work, it points there.
- This file replaced the old numbered priority tables. **Do not reintroduce item numbers** or
  version targets — that structure is what made the previous roadmap unreadable.

## State of play: mobile → PC

- **v1.1.0 is the last release built entirely on the mobile workflow** — Nothing Phone (3a),
  Android 15, CodeAssist, Termux, GitHub mobile. That is commit `2875262`.
- The project has now **moved to Claude Code on a PC**. CodeAssist is no longer the build
  environment, and none of its prerequisites or dependencies apply any more.
- The build is plain desktop Gradle from here: **AGP 8.13.0, Kotlin 2.4.0, Gradle 8.13**,
  compileSdk 36 / minSdk 26 / targetSdk 35, Java 8, one dependency (`androidx.core:core:1.13.1`).
- Source, docs and build files are on **GitHub**. `local/` was tracked temporarily to carry the
  mobile working state across; since 2026-09-22 it is git-ignored and stays on the PC only.

## Working principles

- **Feature philosophy — the north star is parity.** Match HeyMelody as much as possible first,
  then expand with this project's own ideas and improvements (the widget, the wear-state display).
  Support for other earbud models is considered only after that, through the HeyMelody app and its
  protocol — other OnePlus / OPPO / realme models first, since they share it.
- **Who does what.** Human: device testing, git operations, publishing, design decisions.
  Agent: reverse-engineering, parsers, protocol work, code.
- **App identity.** Package `com.spizganed.quickbuds` (final). App name **QuickBuds**.
- **AI attribution** (full history in README + CREDITS): the project began on DeepSeek chat (~15% —
  first codebase, first reverse-engineering steps, basic UI/logger/widget; that widget was later
  rewritten almost entirely), then moved to the CodeAssist agent via OpenRouter running DeepSeek
  v4.1-fast (~80% of the current code). Kimi, Gemini (images) and Grok did small odd jobs. From
  this release and the last commit onward, development is handed to **Claude Code on PC**.

## Next up, in order

1. **Equalizer — WIRED 2026-09-23, awaiting his test.** Built-in presets, BassWave on/off and level
   (−5..+5), custom presets with a 6-band ±6 dB editor and rename — all from a HeyMelody capture
   (PROTOCOL.md §9), shown from the buds' own state and re-read after every write. Custom presets:
   create (max 3) / delete / rename, edited on a HeyMelody-style draggable curve in a sheet.
   BassWave is labelled "Bass boost" in our UI (trademark).
2. **Dual device** — expected quick. Two devices connected, with a switch.
3. **On-call gestures — write DONE, verified on-device 2026-09-22, NOT YET TESTED ON A REAL CALL.**
   An HCI capture of HeyMelody caught the exact write for both rows (PROTOCOL.md §6, "the on-call
   write"): **double tap** (`None` / `Answer + end call`) is `act 0x02`, **long hold** (`None` /
   `Decline call`) is `act 0x06`, both bound to **both buds together as one shared setting** via
   `deviceType 0x04`. The "When on call" section now exists in `GestureActivity`, below the normal
   gesture list, with no Left/Right reach since the setting is shared. Re-sent from our own app (not
   just replayed from the HeyMelody capture) — double tap toggled true/false, acked, read back exactly
   matching HeyMelody's own bytes. **The write is confirmed; the act-to-row LABELS are still
   `[INFERRED]` — NEXT SESSION: place a real call and confirm double tap answers/ends and long hold
   declines, the right way round, before trusting the labels.**
4. **Auto play/pause on wear** — two parts: (a) a switch in the UI that tells the firmware to react
   by itself (`autoPlayPauseOn` / `autoPlayPauseOff` already exist in the manager); (b) our own
   implementation on top — **pause only when both buds are out of the ear; a single bud out keeps
   playing; never auto-play, only pause.**
5. **Golden Sound** — spike only. A one-time hearing test that probably produces an EQ profile. It
    may be hard or impossible through this protocol; find out before promising it.

## The final UI

- **Full app UI** — bottom navigation (Device / Earbud controls / About) like the original app's
  structure, in the finished widget's style, carrying only the wanted functions. Last by design; the
  remaining screens are slotted in as it grows.
- **Theming is not properly finished**, and the Light theme is the known offender — it is the source
  of invisible-on-light bugs, e.g. white L/C/R letters. Decision: either remove it, or leave it
  exactly as it is until the final UI lands. Do not polish it in the meantime.
- **The widget is on hold** until the final UI and full HeyMelody parity. Its bud styling is already
  implemented and is the source of truth the app copies: `AncWidgetProvider.budStyle()` draws a bud
  **white** in ear (`#FFFFFF`), **grey** out of ear (`#8A8A8A`), and **hidden** in the case. Those
  two colours are the widget's own fixed palette, **not** theme attributes — deliberately, because
  the widget is always dark. `MainActivity.budStyle()` is a port of it so the two cannot disagree.

## Undecided

- **Case lid state** — the buds or case may report lid open/closed, but it is not established. Keep
  as is, remove, or change: **not decided.** The case code and `ic_case.xml` stay until then.
- **When the next release happens** — by feel, not by schedule. There is no version target.

## Parked

- **Lock screen widget** — feasibility only, low importance.
- **Screenshot-to-text tool** — remove from the UI, or hide it. Keep the logic; it may be useful
  again someday.
- **Layout report tool** — same treatment: remove or hide it from the UI, keep the logic.
- **Other earbud models** — after parity.
- **A build quickstart guide.** `[USER]` 2026-09-22: needed for a contributor who has nothing set up
  yet — starting from `git clone`, not from an already-checked-out working copy the way CLAUDE.md's
  "Build and run" section does. Copy-pasteable commands, in order: clone, JDK/SDK prerequisites,
  generate the wrapper, `local.properties`, first `assembleDebug`. CLAUDE.md's "First run on the PC"
  section already has most of the raw material (including the JDK-version gotcha and the
  Android-Studio-already-did-this-for-you shortcut) — this is about surfacing it as a short, linear,
  copy-paste path for someone who is not this project's regular dev, not re-deriving it. Likely lands
  in README.md, since CLAUDE.md is agent working notes, not a contributor-facing doc.
- **Redo the bud/case icons from scratch.** `[USER]` 2026-09-22: new set drawn
  (`local/svgs/left|case|right.svg`, PC-only) and wired into the app's wear icons. Pending the
  developer's on-device look before it counts as final. The launcher icon and widget preview still
  carry the old bud art.

## Known loose ends

- Localisation: all user-facing text is in `strings.xml`, but only `values/` (English) exists, and
  hardcoded strings remain in `MainActivity` dialogs (crash report, Bluetooth permission), the
  Dev Tools labels and legend, and `BottomSheetDialog` callers.
- Slide up vs slide down: both directions are written with the same action, because which is which
  is not established. Harmless, worth settling.
- The app is **not signed** at all yet — the installer warns the user on first install. Revisit later.
- The in-app updater should check the latest GitHub release, download it, and start the update when
  it is ready.

## Decided against — do not re-suggest

- **Guessing protocol payloads before a capture.** This has cost real time more than once; see
  PROTOCOL.md's "History of Getting This Wrong".
- **Hardcoded gesture button groups.** The buds' slot layout differs per bud and changes itself, so
  the write must be table-driven — every slot the bud actually has.
- **A log on the main screen.** Status events there are silent by design; the Dev Tools screen owns
  logging.
- **Removing the foreground-service notification.** Android 15 requires it for a `connectedDevice`
  service, and the only real fix would cost the instant push updates.
- **Committing logs, screenshots or debug documents to GitHub.**
- **Marking roadmap items "must ship in version X".**

## Done

Foundation & debugging:

- Packet logging foundation — every received `AA` frame and sent command, timestamped.
- Bud icon states on the widget: **white in ear, grey out of ear, hidden in the case** — matching
  `AncWidgetProvider.budStyle()`, which the app's own renderer copies.
- Battery rows show last-known values, updated on every hardware packet.

Push and control:

- Wear, battery and Game Mode are all pushed by the buds; the 60s poll is only a keep-alive now.
- 2026-09-23, all confirmed on device by him:
  - Hold gesture: min-one-mode rule like HeyMelody; mask read/write (PROTOCOL.md §5).
  - Gesture/hold/on-call config synced from the buds on every connect.
  - Hi-Res codec + 3D audio switches, mutually exclusive, reconnect warning (PROTOCOL.md §9).
  - Status reply `0x810D` decoded; Hi-Res, 3D and low latency show the buds' state on connect.
  - Find my earbuds: the buds' own tone on both buds, in-ear warning.
  - Header pill is the Connect/Disconnect button (green connected, red not).
  - Reconnect after a lost link (e.g. after a codec switch) — fast and consistent.
- ANC gesture sync — bud-side ANC changes light the app's circles and the widget.
- Adaptive is a fourth ANC surface (main-screen circle, widget segment, Quick Settings tile), and
  its SET payload is fixed.
- Dev Tools screen — human-readable log, raw-hex log, Mark / Clear / Export, Reconnect / Disconnect.
- Settings card and secondary screens (EQ, Find my earbuds, App update, chime player).
- Gesture configuration ("Earbud controls") — writes to the buds, verified on the device; the
  function values were measured, not guessed. Hold and on-call landed 2026-09-22 (see PROTOCOL.md
  §5/§6); config now syncs from the buds on every connect instead of trusting local storage alone.

Appearance and tooling:

- Adaptive launcher icon; the bud artwork is traced from source SVGs and used verbatim; the widget
  preview is decoupled from the launcher icon.
- Battery icon alignment fixed by fill fraction, not by eye.
- PROTOCOL.md and CREDITS.md written.
- Layout-report and screenshot-to-text tools built (both now parked — see *Parked*).
- `local/` folder created so working material stays out of the repo root.
- Portrait-locked, no rotation, on every activity (2026-09-22) — no screen has a landscape layout.

## Build and transfer notes

- **The PC move is done** (confirmed working 2026-09-20) — Gradle 8.13 + AGP 8.13.0 + Kotlin 2.4.0,
  no CodeAssist prerequisites. Full setup is in [CLAUDE.md](./CLAUDE.md), including a JDK-version
  gotcha (Gradle 8.13 rejects JDK 24+) worth reading before the next fresh machine.
- **adb over USB** pushes the `.apk` to the device and is the debugging path. The app's own packet
  log can also be pulled directly with `adb pull` or `adb logcat` — no manual export needed, see
  PACKET-CAPTURE.md's automated-pull note under Option A.
- `local/` now holds only `logs/` and `svgs/`, both kept for their lasting value as evidence/
  provenance — nothing left in it is temporary. Git-ignored since 2026-09-22; PC-only.

**Docs:** [README.md](./README.md) is the accurate feature summary. [PROTOCOL.md](./PROTOCOL.md) is
the wire format end to end — read it before touching anything protocol-related.
[CREDITS.md](./CREDITS.md) is whose reverse-engineering this stands on.
[CLAUDE.md](./CLAUDE.md) is how the project is built and worked on.
