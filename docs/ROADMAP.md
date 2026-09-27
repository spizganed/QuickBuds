# QuickBuds — In Progress

The live plan: what is next and what is still open. Finished work moves to
[ROADMAP-DONE.md](./ROADMAP-DONE.md).

- Releases happen when the developer feels the app is ready.
- Protocol findings go in [PROTOCOL.md](./PROTOCOL.md), not here.
- **The north star is parity with HeyMelody**, then our own improvements. Other earbud models come
  after that.

Status words: **Next**, **Open**, **Question** (needs an answer before work starts), **Parked**.

## Parity: firmware features still missing

None known (2026-09-25). A new one needs a HeyMelody capture first.

## The plan, in order (`[USER]` 2026-09-27)

Each step is done before the next one starts. The previous plan (Settings screen, Home layout, themes,
widgets, 3.0.0) is finished; 3.1.0 is released.

1. **Right-to-left languages** (Arabic, Urdu, Persian), when wanted: RTL support in the manifest and a
   mirrored check of every screen.
2. **Small items** ([USER] 2026-09-27):
   - **Dead code sweep.** lint `UnusedResources` plus a scan for Kotlin symbols nothing references;
     delete what is unused.
   - **Dev Tools redesign** to match the app: `SettingRowFactory` screen and header, a two-segment
     Human / Raw control, the actions in one card (Export, Clear, Reconnect, Disconnect, Crash test
     with a confirm), the log in a normal card with TX / RX in the accent colour.
3. **Other HeyMelody models: detect, then show what the model supports.** realme models are dropped
   (HeyMelody does not support them). All from `Zhaoyi-ya/OppoPodsManager` `[OSS]`:
   - **Detection** (`DeviceInfoManager`, `ModelCatalog`): the `0x8103` reply is `00` + a 3-byte
     little-endian product id (`100100`–`100102` normalise to `060414`, three more such ranges).
     Look it up in the model list; if the id is missing or unknown, match the Bluetooth device name;
     if that fails, the user picks from the list (manual choice overrides). We already send `0x0103`
     and `0x0100` in the init sequence (PROTOCOL.md §4) but ignore both replies.
   - **Model list**: `Assets/Oplus/Data/DeviceModels.json`, HeyMelody's own per-model config.
     `whiteList` has 137 models: `id` (Buds 4 = `065414`), RFCOMM `uuid` (Buds 4 `0000079A-…`, ours),
     and a `function` map: feature flags, `noiseReductionMode` with a `protocolIndex` per ANC mode
     (Adaptive `11` = our bit 11), `equalizerMode`, `control` / `callControl` gesture bitmasks.
   - **What the firmware supports**: the `0x8100` reply is `00` + a bitmap; each bit maps to the
     commands it enables (`CapabilityReader.MelodyV16`, bits 0–66). A feature is shown only if the
     JSON lists it AND the bitmap has its commands (`CapabilityLoader.IntersectWhitelistFeatures`).
   - **Packets per feature**: `Control/Brands/Oppo/Features/*.cs` (bass engine, spatial audio, game
     sound, hearing enhancement, custom EQ…). OSS has been wrong before (`0x0402`, PROTOCOL.md §6), so
     each builder goes in with a PROTOCOL.md entry tagged `[OSS]`, and a feature Buds 4 lacks stays
     marked unverified until an owner of that model confirms a write by read-back.
   - **Firmware version**: query `0x0105`; `[OSS]` reply is `00`, one byte, then UTF-8 text of
     `deviceType,versionType,version` triples (`DeviceInfoManager.ApplyFirmware` joins the versions
     with dots). Show it in the Earbud settings hub. Confirm the format against a Buds 4 reply first.
   - UI: the header's device name becomes a button that opens the model list (switch or override).
   - The capture script and contributor docs are not needed for this.
4. **PC version: brainstorm session first**, once the Android app is finished. A standalone Windows
   app (Linux too, maybe) that shares the UI style, not the phone layout, plus a tray button for quick
   mode changes and no widget. Language, UI toolkit and code sharing with the app are all open. Same
   repo (`[USER]` 2026-09-27).

## Our own features

- **Themes, still open:** auto-detect (follow system light/dark with a built-in preset) and
  saved / recent colours in the picker.
- **New app icon.** The current one is acceptable, but a better one is welcome.

## Connection and battery

- **Keep-alive: drop it entirely?** Open. It is now 300 s. Dropping it needs a long session
  without it, to prove the link does not go stale.
- **Connect when the audio link comes up.** Done 2026-09-26, awaiting his test (CLAUDE.md,
  Connection robustness).

## Docs cleanup

- Done 2026-09-26: README is short and user-facing, CREDITS.md folded into its Credits section,
  outdated Appearance notes in ROADMAP-DONE replaced.
- **Retake the README screenshots** once the UI and the widget are final. The current ones are
  placeholders. Rerun `scripts/readme-screenshots.sh`.

## Parked

- A build quickstart and a capture guide for contributors, only if the device file is not enough.
- Undecoded families (broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family,
  `0x0510`): see PROTOCOL.md §12. Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Ear tip fit test and Golden Sound: the only HeyMelody features we skip; he sees no use in them
  ([USER] 2026-09-27).
- A lock-screen widget, a widget on/off switch, the Quick Settings tile (removed) and the
  fixed-level hold ([USER] 2026-09-26).
- Slide up vs slide down: nothing to do. The firmware maps up/down itself (volume up/down, next/prev)
  when the slide is set through our app, exactly as with HeyMelody ([USER] 2026-09-26).

- Guessing protocol payloads before a capture.
- Hardcoded gesture button groups: the write must be table-driven.
- A log on the main screen: Dev Tools owns logging.
- Removing the foreground-service notification: Android 15 requires it for a `connectedDevice` service.
- Committing logs or debug documents.
- Marking roadmap items with a release version.
