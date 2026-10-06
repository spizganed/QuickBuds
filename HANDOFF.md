# Handoff: phone session -> PC agent (QuickBuds, 2026-10-06)

Follow CLAUDE.md. Never push without asking him; commits authored as spizganed (noreply), keep the trailer.

## Get the phone's work

Branch `realme-batch` (this file is temporary: delete it before anything merges to main).

```
git fetch && git checkout realme-batch
```

## What the branch contains

1. **27 more realme models** in `app/src/main/assets/models.json` (issue #8), generated from realme Link
   configs: ANC trees, gestures, `sharedHoldMask`; our new keys `oneButton` (5 neckbands, one button
   written as the left bud) and `bothHold` (5 models, hold both buds = `04 01 04 <fn>`, game mode `11` or
   none, read back as its own `dev 04` entry). Android (`GestureConfig.kt`, `GestureActivity.kt`,
   `OpoProtocol.setOnCall` button param, `BudsService`, `BudsConnectionManager`), new string
   `gesture_both_hold` in all 26 locales, desktop (`controls.rs`, `session.rs` `Cmd::OnCall(btn, act, fn)`,
   `protocol.rs` `BUTTON_PRIMARY`, `build.rs`, `app.slint` `Controls.one-button`), PROTOCOL.md §5/§6.
2. **Spatial fix** (from the issue #8 log): the Air7 Pro lists `0x012A`/`0x0422` but never answers
   `0x012A`; our 3D-sound write `0x0422 01` + `0x0403 18 00` dropped the link twice. realme Link writes
   3D sound as `0x0403 1B <01/00>` alone and Hi-Res `18` alone, never together. New key
   `"spatialSwitch":1` (23 realme models with 3D sound) -> `MainActivity.spatialSwitchOnly()` bypasses
   `spatialByType()` and the mutual-exclusion writes. PROTOCOL.md §9 entry. Android only (desktop has no
   spatial row).
3. CLAUDE.md "Current state" note about the batch.

All of it is wired, **unverified on real realme buds**. The phone runs a release build of it.

## Your tasks (in order)

1. `cd desktop && cargo test` — the desktop half has **never compiled**. 3 new tests in `controls.rs`
   (`wireless6_one_button`, `t110_both_hold`, plus existing). Fix compile errors / test failures.
2. `cargo build --release` and `cargo build --release --target x86_64-pc-windows-gnu`.
3. `./gradlew assembleRelease` (compile check; he decides on install).
4. Optional: UI check of the Controls page on the invisible screen (`scripts/desktop-vscreen.sh`),
   models can be picked without writing (see CLAUDE.md Desktop rules). Click nothing that writes.
5. Report results to him. Commit on `realme-batch` only if he says so. **Do not merge to main or push**
   without asking: CLAUDE.md says the batch stays uncommitted until the issue #8 reporter's log confirms
   the bits (the reporter is asked to read back ANC mode, one gesture, the hold cycle).

## Open, not started (realme Link parity, Air7 Pro)

realme Link 0x0403 feature ids found (decompile on the phone at `~/realmelink-decompile`):
wind noise `0x1A` (Air7 Pro has it), dynamic bass `0x1D`, vocal enhance `09`, `0C` "enhance voice"
(clashes with our `0C` = personalised ANC applied: do not assume), game mode `06`, wear `04`, dual `11`,
Hi-Res `18`, 3D `1B`; power saving reported as `05` there (ours `17` from HeyMelody: unresolved).
Adding `0x1A` means adding it to the `0x010D` status query, which affects every model (issue #5: that
query drops the Enco Buds2 link). Needs his decision before any work.
