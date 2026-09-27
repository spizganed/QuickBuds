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

1. **Other HeyMelody models: detect, then show what the model supports.** realme (and DIZO) models
   are in HeyMelody's list and in ours ([USER] 2026-09-27). All from `Zhaoyi-ya/OppoPodsManager` `[OSS]`:
   - **Done 2026-09-27: capability gating.** The buds' own `0x8100` bitmap and `0x810D` list decide
     which home rows, Earbud settings rows and connect-time queries appear (`Capabilities.kt`,
     PROTOCOL.md §4). The product id is read and logged.
   - **Done 2026-09-27: per-model noise control.** HeyMelody's `noiseReductionMode` per product id
     (`assets/models.json`, `AncModes.kt`, PROTOCOL.md §5) sets the bits both ways and decides which
     segments, level pills and widget modes show. Buds 4 unchanged on device; other models unverified
     until an owner reads a write back.
   - **Done 2026-09-27: detection and the model list.** `ModelCatalog` folds the colour ranges and
     matches id and Bluetooth name as HeyMelody does (PROTOCOL.md §4). The device name under the rings
     shows the model; a header button before the connect pill opens `ModelActivity`: Automatic plus
     every model by brand (OnePlus, OPPO, realme, DIZO); a pick
     overrides detection until other buds connect. Checked on device (Buds 4 detected; a manual Buds
     Pro swapped the noise segments). Next: firmware version, then per-feature packets.
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
   - **Firmware version**: queried and logged on connect; format confirmed on Buds 4 (PROTOCOL.md §3).
     Next: show it in the Earbud settings hub, once the triples are matched to HeyMelody's own screen.
   - The capture script and contributor docs are not needed for this.
   - **Sources checked 2026-09-27** ([USER]: reuse what the OSS clients already do). Both carry the same
     137-model list (56 OPPO, 49 realme, 32 OnePlus). `Leaf-lsgtky/OppoPods` (Kotlin, Android) now has
     product-id detection with a model registry, per-model ANC options (`noiseReductionMode`, legacy
     ANC order), game mode `0x06` or `0x28`, spatial three-mode `0x0422` vs on/off `0x1B`, EQ presets and
     device custom EQ, auto play/pause, dual connection. `OppoPodsManager` adds bass engine, hearing
     enhancement `0x0B`, long battery `0x17`, voice enhancement `0x09`, spine health `0x22`, game sound
     `0x27`, find device and the capability bitmap. Neither writes gestures.
   - **The model list is HeyMelody's own** (pulled from the HeyMelody APK, [USER] 2026-09-27), so which
     features a model has is `[VENDOR]`; only the packet builders are `[OSS]`. OppoPods' `docs/` has two
     JADX write-ups of HeyMelody (`HeyMelody_Official_App_Protocol_Findings.md`,
     `HeyMelody_Bluetooth_Protocol_Notes.md`). Gestures on other models: HeyMelody downloads a per-model
     `control_<id>/config.json` with each action's function codes (their Enco X3 table); our writer
     already reads the slots from the bud, so those codes are the missing piece. Until then gestures stay
     Buds 4 only.
   - **More sources (2026-09-27):** HeyMelody decompiled (see CLAUDE.md) gives `[VENDOR]` payloads for
     every command. `GazzasaurusRex/oneplus-buds-omarchy` has read-back-verified profiles for Buds Pro
     and Buds Pro 2 (ANC levels, EQ ids, `0x0105` firmware). `digisatapathy2025/oneplus-buds-mac` has a
     OnePlus product-id catalogue. `maniacx/BudsLink` PR #94 and `thelok1s/orchestra` verify realme
     models (dropped here).
2. **PC version: brainstorm session first**, once the Android app is finished. A standalone Windows
   app (Linux too, maybe) that shares the UI style, not the phone layout, plus a tray button for quick
   mode changes and no widget. Language, UI toolkit and code sharing with the app are all open. Same
   repo (`[USER]` 2026-09-27).

## Our own features

- **Next: a new White preset** ([USER] 2026-09-27): the current White colours look off and need a
  redesign. Do it together with **auto-detect** (follow the system light/dark setting, switching
  between a dark built-in and the new White).

## Connection and battery

- **Keep-alive: drop it entirely?** Open. It is now 300 s. A test build without it is on his phone
  since 2026-09-27 evening (not committed); his report decides.

## Docs cleanup

- Done 2026-09-26: README is short and user-facing, CREDITS.md folded into its Credits section,
  outdated Appearance notes in ROADMAP-DONE replaced.
- Done 2026-09-27: README screenshots retaken (model list and the redesigned widgets included).
  Rerun `scripts/readme-screenshots.sh` after any visible UI change.

## Parked

- A build quickstart and a capture guide for contributors, only if the device file is not enough.
- Undecoded families (broadcast codes `0x04`/`0x08`/`0x0B`, the `F1` family,
  `0x0510`): see PROTOCOL.md §12. Do not guess from a couple of samples.

## Decided against — do not re-suggest

- Right-to-left languages (Arabic, Urdu, Persian, Hebrew): they need a mirrored layout and a check of
  every custom-drawn view ([USER] 2026-09-27).
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
