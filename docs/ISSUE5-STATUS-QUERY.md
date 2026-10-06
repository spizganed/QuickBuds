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
- **OnePlus Buds 4** by the same list: `05 04 0B 0D 11 18 1B 1D 1C`. It has no `gameMode` /
  `gameModeList`, `longPowerMode` or `personalNoise`, yet the buds answer `06`, `17` and `0C` and we show
  rows for them. Either OppoPodsManager's list (versionCode 16007000) is older than HeyMelody's server
  list, or HeyMelody reads those states elsewhere. Settle this before step 3 (watch HeyMelody's `0x010D`
  in a btsnoop capture on the Buds 4).
- Gadgetbridge does the same with a per-model list and sends no `0x010D` at all to Enco Buds2.

## Plan (replaces the "learn it per device" proposal in CLAUDE.md)

1. **Ask the reporter first** to try `02 05 0D`, then `01 05`, as `STATUS_QUERY` on a clean v4.4.0
   desktop build, and say whether the link stays up.
2. **The data:** our `models.json` is trimmed and lacks most of these keys (it has `vocalEnhance`,
   `bassEngineSupport`, `controlAutoVolumeSupport`, `personalNoise`, `spatialTypes`, `longPressVolume`,
   `swiftPair`; `multiConnect` / `highAudio` / `gameSound` are other keys, not the ones above). HeyMelody fetches its list
   from its server; the APK bundles none. Source for the full maps: OppoPodsManager's `DeviceModels.json`
   `[OSS]` (HeyMelody's 137 models with full `function` maps). Add the keys the table needs to
   `models.json` (or one precomputed `"status"` id list per model, built by a script from that file).
3. **Code, in one commit with PROTOCOL.md §9:** build the query per model in `OpoProtocol.queryStatus()`
   and `protocol.rs` (`STATUS_QUERY` becomes a function). The same model lookup decides it, so a model
   picked by hand changes the list too. A model with no `models.json` match: `01 05`.
4. **Check nothing we show disappears:** today's switches read their state from the `0x810D` reply.
   For every model with a row (Buds 4 first: it answers `05 04 0B 11 18 06 1B 1D 17 0C`), the new list
   must still include each id a visible row needs. If a row's id is not in the vendor map, the row
   should not show either (HeyMelody parity).
5. **Desktop:** `cargo test` needs a proot (removed). On mobile data, set up the Debian proot again from
   TOOLCHAIN.md, or leave the desktop half and its tests for the PC.
6. Delete this file; drop the issue #5 block from CLAUDE.md "Current state".
