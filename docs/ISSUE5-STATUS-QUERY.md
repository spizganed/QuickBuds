# Issue #5: per-model `0x010D` query (temporary)

Temporary working note. Delete it once the change lands; the facts move to PROTOCOL.md §9 then.

## Findings (2026-10-06)

`[VENDOR]` HeyMelody 116.9.0 (decompiled again from the APKPure APK; signer `CN=OP`):

- HeyMelody sends `0x010D` to every model. It is always allowed (no bitmap bit), and it is the only
  place the command is sent.
- The payload is `<count> <ids>`, **built per model**: always `05`, then one id per feature the model's
  `function` map has. It never sends a fixed list.
- A flag counts only when its value is exactly `1` (0, missing or anything else = off), unless noted.

| Id | Added when (`function` key) |
|---|---|
| `05` | always, first |
| `04` | `wearDetection` |
| `0B` | `hearingEnhancement` or `hearingEnhancementNew` |
| `0C` | `personalNoise`, or `personalNoiseCompat.personalNoise` |
| `0D` | `clickTakePic` or `clickTakePicNew` |
| `0F` | `zenMode` > 0 |
| `11` | `multiDevicesConnect` 1 or 2 |
| `09` | `vocalEnhance` |
| `13` | `headSetSoundRecord` |
| `18` | `highToneQuality` 1 or 2 |
| `17` | `longPowerMode` |
| `15` | `smartCall` |
| `16` | `deviceLostRemind` |
| `14` | `voiceWake` 1 or 3 |
| `19` | `voiceCommand` 1 or 2 |
| `06` | `gameMode`, or `gameModeList` present |
| `1B` | `spatialTypes` present |
| `1D` | `bassEngineSupport` |
| `1C` | `controlAutoVolumeSupport` |
| `1E` | `collectLogs` |
| `21` | `gameEqPkgList` non-empty |
| `1F` | only on OPPO / OnePlus phones (skip) |
| `22 23 24` | `spineHealth` |
| `27 28` | `gameSoundList` non-empty, or the bitmap has `0x0423` |
| `30` | `adaptiveVolume` |
| `31` | `adaptiveEar` |
| `32` | `speechPerception` |
| `34` | `meetingAssistant` |
| `35` | `longPressVolume` |
| `37` | `swiftPair` |
| `38` | `hearingOptimize` |
| `39` | `incomingCallControl` |
| `3B` | `headMotion` |
| `3A` | `sleepDetection` |

Ids go out in the table's order (HeyMelody's order).

- **OPPO Enco Buds2** (`064810`, matched by name; they report `060C12`) `[OSS]` vendor list: `function`
  has `clickTakePic: 1` and none of the other keys, so HeyMelody sends **`0x010D 02 05 0D`**. By id
  (realme Buds Q2s, `function` = `fastDiscovery` only) it would be `01 05`. We send our fixed 23-id list
  to every model (`OpoProtocol.queryStatus()`, `protocol::STATUS_QUERY`), which the reporter confirmed
  drops the link. That `02 05 0D` keeps it up is not tested yet (asked on #5).
- **OnePlus Buds 4, captured** (PROTOCOL.md "Batch query"): HeyMelody asks `05 04 0B 11 18 06 1B 1D 1C`,
  inside a `0x012F` batch. OppoPodsManager's list predicted `05 04 0B 0D 11 18 1B 1D 1C`: the order and
  the rule hold, the data does not (server list has `gameMode`, no `clickTakePic`). So **the Enco Buds2's
  `05 0D` is only a best guess** from old data; the reporter's test decides it.
- HeyMelody never asks the Buds 4 for `17` (power saving) or `0C` (personalised ANC), and we show both
  rows from our wider query. With a per-model query those rows would go, unless the model data keeps the
  flags. Decide that with him before step 3.
- Gadgetbridge does the same with a per-model list and sends no `0x010D` at all to Enco Buds2.

## Plan (replaces the "learn it per device" proposal in CLAUDE.md)

Decided `[USER]` 2026-10-06: follow HeyMelody's per-model list. HeyMelody shows neither Personalised ANC
nor Power saving for the Buds 4, so those rows going away is parity. Use the `0x012F` batch where the
bitmap has it.

1. **Reporter's test** (asked on #5): `02 05 0D`, then `01 05`, as `STATUS_QUERY` on a clean v4.4.0
   desktop build. PR #7 on hold until then (review posted).
2. **Current model data, on the PC:** run HeyMelody in a rooted Android emulator and read the model list
   it downloads (current `function` maps for every model). The steps live in the agent's memory, not
   here (vendor details stay out of the repo). Diff it against `models.json` and OppoPodsManager's
   `DeviceModels.json` `[OSS]` (out of date, see the Buds 4 capture above). Take only per-model facts:
   the keys the table needs, added to `models.json`. If the emulator route fails: captures per model;
   models without data keep today's 23-id query.
3. **Code, in one commit with PROTOCOL.md §9:** build the query per model in `OpoProtocol.queryStatus()`
   and `protocol.rs` (`STATUS_QUERY` becomes a function). The same model lookup decides it, so a model
   picked by hand changes the list too. Rows whose id is no longer asked disappear (HeyMelody parity).
4. **`0x012F` batch at connect** (PROTOCOL.md "Batch query"), both apps, where the bitmap has bit 56:
   one frame instead of the separate queries; replies split per answer into the normal handlers.
5. **Builds:** Android builds on the phone. Desktop `cargo test` needs the Debian proot (removed) or
   the PC; release both apps together.
6. Delete this file; drop the issue #5 block from CLAUDE.md "Current state".
