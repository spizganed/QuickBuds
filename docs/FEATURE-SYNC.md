# Feature sync: one rule for every setting that writes to and reads from the buds

## Principles
1. The buds are the only source of truth. Every control shows the buds' last report.
2. A write is a request. The buds' own report confirms it. Nothing moves on a tap, and nothing goes back, because nothing moved first.
3. A control moves in two cases only. The buds report a different value (a read reply or a push). Or the buds cannot report the value and they ack our write.
4. Every failure shows a toast. The toast names the setting and says what went wrong.
5. No timer settles or hides a state. The only clock is a failure detector.
6. The rule works for every model. Nothing here depends on a model. A model may override only HOW the app confirms a setting, as data.

## One user action: an operation
`operation = { key, writes: [(id, value)...], confirm: read | push | ack, restarts: bool }`.
- `key` is the setting that the user touched. The control shows "Applying…" and the confirmation checks this key. It need not be the first write.
- The engine sends the writes one after the other. The next write goes out when the buds ack the previous write with status 0. Hi-Res on is `[(3D audio, 0), (Hi-Res, 1)]`.
- `confirm` sets how the app confirms the write:
  - `read`: after the last ack, the engine sends ONE status read with its own sequence byte. The reply to this read decides. This is the default for every on/off feature that the buds list in their status reply.
  - `push`: after the last ack, the engine waits for the buds' push for that setting. Game mode and ANC mode have pushes (`PROTOCOL.md` section 10). Spatial type pushes `0x0510` (PROTOCOL.md section 18). Use it where the buds apply late, however late. The buds can also report the new value BEFORE the ack. That fast push already confirms the write. The engine then ends the operation at the ack, because a second push does not come.
  - `ack`: the ack decides. Use it only for a setting that the buds cannot report. The control moves when the ack arrives.
  - A model may override the game-mode confirmation with `"gameConfirm":"read"` in `models.json`. Use it when the buds apply the write and never push (realme Buds T310, reports #16 and #73). The status reply then confirms the write.
  - A model with `"seqless":1` answers with its own sequence byte, not ours (realme Buds T100, report #100). The app then pairs a reply with the newest outstanding request.
- `restarts`: the write restarts the link (Hi-Res, power saving, codec). The wait ends with the first report after the reconnect.

## The engine
The engine is a plain class. It has no Bluetooth, no clock and no threads.

State: `value[id]` (what the controls show) and a queue of operations. Only the first operation is active.

Inputs: `tap(operation)`, `ack(seq, status)`, `report(values, seq?)`, `tick(now)`, `disconnect`, `reconnect`.

Outputs, in order: `busy(key, on|off)`, `send(id, value, seq)`, `read(seq)`, `changed(id, value)`, `toast(key, kind)`.

Rules:
1. `tap`: the engine ignores the tap while that key is busy. Else it sends `busy(key, on)` at once and the operation joins the queue. The first operation in the queue starts with the `send` of its first write. The control does NOT move.
2. `ack(seq, status)` must match the active write. The engine ignores any other seq.
   - Status 1: the toast is `refused_missing`. The buds do not have this setting.
   - Any other non-zero status: the toast is `refused_now`. The buds have the setting and refuse it now.
   - Both cases end the operation. If an earlier write of the same operation went through, the engine adds one `read` to re-sync the controls.
   - Status 0: the engine sends the next write. After the last write, it acts on `confirm`.
3. `report(values, seq)` handles a report in these steps:
   - 3a. It always updates `value[id]`. It emits `changed` for each value that really changed.
   - 3b. A report confirms the operation in two cases. It is the reply to our own read (`seq` equals the read that we sent). Or it is the first REAL push for the key, for `push`.
   - 3c. A read reply that is not ours is never a push. It only updates values. A device showed this on 2026-10-24: a connect-time read ended a game-mode wait with a false toast.
   - 3d. A confirming report ends the operation. The engine sends `busy(key, off)`.
   - 3e. The engine sends one `not_changed` toast if the setting's value is not the one that we wrote.
4. `tick(now)`: a wait step that reaches 3000 ms ends the operation with the `no_answer` toast. The step is the ack of a write, then the reply or the push. After the ack of a `restarts` write, the limit is 15000 ms.
5. When an operation ends, the next operation in the queue starts.
6. `disconnect`: the active operation ends with the `no_answer` toast. One exception is a `restarts` write that was acked: it waits for the reconnect. Queued operations that sent nothing end silently with `busy off`.
7. The engine keeps values across a disconnect. The UI shows its neutral, dimmed state while disconnected.
8. A repaint with an equal value emits nothing. Nothing animates.
9. A `restarts` operation waits across a disconnect only after the buds ack its last write. Before that, a disconnect ends it like any other operation.
10. After a refusal that follows an acked write, the engine sends one re-sync read. This applies to every confirm kind.
11. A report that arrives while disconnected only updates values. It never confirms a `restarts` operation before the `reconnect` event.

Assumption to check on a real device: the buds answer in order. A late reply to an older read arrives before the reply to our read.

## What the UI does
- The UI draws only `value[id]`. It animates only on `changed`.
- While a key is busy, the control is disabled. The row's subtitle says "Applying…". The thumb has no spinner and does not move.
- The app shows one toast for each failed operation. The texts are `feature_refused_missing`, `feature_refused_now`, `feature_not_changed` and `feature_no_answer`. Each text has the setting's name. Hi-Res `refused_now` adds the codec hint (`hires_needs_codec`).

## What is data and what is code
- Code: the engine and the set of keys it handles. Each call site passes the writes and the confirmation kind.
- Data in `models.json` contains only the cases where a report or a capture shows that the default is wrong.
- The default for an unknown model is `read` for every on/off feature that the buds list in the status reply. It is `push` for ANC mode, game mode and spatial type. It is `ack` for a setting with no way to read it.
- Every failure writes a log line (`FEATURE REFUSED`, `FEATURE NOT CHANGED`, `FEATURE NO ANSWER`). The line has the setting key, the reason and the codec. The reports then show which model needs a data override.

## Where it lives
- Android engine: `app/src/main/kotlin/com/spizganed/quickbuds/protocol/FeatureSync.kt`. `BudsConnectionManager` owns it. The service shares one engine with the app, the widget and the notification.
- Android enabled feature ids: `app/src/main/kotlin/com/spizganed/quickbuds/protocol/FeatureSyncConfig.kt`.
- Desktop engine: `core/src/feature_sync.rs`. Same names, same states.
- Tests: both engines run `core/tests/feature_sync_scenarios.json`. A change of the rule changes that file first.

## Removed when it works
- `notePending`
- `withPending`
- `PENDING_MS`
- every `Thread.sleep(VERIFY_DELAY_MS)` after a write
- the game-mode special wait
- `setSwitchQuiet`
- the `syncingFeatures` flags outside the engine's listener
- `audioAfterRestart` as a flag (it becomes `restarts`)

## Scenarios
The file `core/tests/feature_sync_scenarios.json` is the contract. It holds 28 scenarios, and both engines must pass all of them.
1. Read confirmation: the value moves once, at our own read reply.
2. Read confirmation: the buds accept the write but report the old value. Nothing moves and the app shows one toast.
3. Push confirmation (game mode): the value moves at the push, however late.
4. Push confirmation: a push of the wanted value before the ack confirms at the ack. A second push does not come.
5. Push confirmation: a push with the old value is a refusal.
6. Push confirmation: no push in 3 s after the ack gives one toast.
7. Push confirmation: a status-read reply that is not a push does not confirm. It only updates values.
8. Ack confirmation (the buds can report nothing): the value moves at the ack.
9. Refused, status 1 (the buds lack the switch): the value never moves.
10. Refused, status 2 (a condition fails).
11. Refused, any other non-zero status counts as refused now.
12. Link restart: busy stays across the drop, and the first report after the reconnect confirms.
13. Link restart: no report within 15 s of the ack gives one toast.
14. Link restart: the buds come back with the old value.
15. A report that is not our read reply updates values but does not confirm.
16. The engine ignores a stale ack that is not the current seq.
17. The engine ignores a second tap on a busy setting.
18. Two settings: the second waits in the queue and starts when the first ends.
19. One action with two writes (Hi-Res on drops 3D audio): one confirmation at the end.
20. Two writes: the buds refuse the second, so one read re-syncs what the first changed.
21. Slider: the buds clamp the value, the slider follows the buds, and one toast says so.
22. No ack in 3 s: one toast, and the next queued setting starts.
23. Ack but no read reply in 3 s: one toast.
24. The engine ignores a late ack after the timeout.
25. Disconnect: the sent write gets a toast, and the queued one is dropped silently.
26. A push while idle moves the value at once. The same value does nothing.
27. A push before the ack only updates the value. The ack then asks for the read.
28. After a disconnect the idle value is kept.
