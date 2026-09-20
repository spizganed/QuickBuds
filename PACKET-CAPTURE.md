# Capturing a packet log

How to get a raw RFCOMM capture off the device. Kept as a **backup procedure** — it was written to
answer whether bud-side ANC gestures produce any attributable frame (they do, see below), and it is
still the way to capture anything new.

## When you need this

Any time a protocol question cannot be settled from the code or from
[PROTOCOL.md](./PROTOCOL.md). **Capture first, then build.** Guessing a payload has cost this project
real sessions more than once, and a wrong write to the buds fails completely silently.

## Option A — the app's own log (no PC needed)

1. Force-stop the app, reopen it, connect, and let the init burst finish.
2. Dev Tools → **Clear** (so the export is one clean session with no dead gaps).
3. Be in the app **before** each gesture, so the mark and the gesture land in the right order.
   There is no need to open Dev Tools between gestures.
4. For each thing you want to capture:
   - perform the gesture / action on the buds,
   - press **MARK** within about 2 seconds after it,
   - wait at least 3 seconds before the next one.
5. Repeat the whole cycle **twice**, so a one-off frame gets a chance to appear again.
6. Dev Tools → **Export**. Files land in `Download/QuickBudsLogs/` via MediaStore.

Hand over only the lines between the first and last `MARK`, plus the two lines either side of each
one. The 60-second `TX[poll status]` keep-alives in between can be left out.

### Automated pull — no MARK/Export needed at all (confirmed working, 2026-09-20)

The agent can pull the same log itself, straight off the device, with no manual export step —
`PacketLogger` (`bluetooth/Packet_Logger.kt`) already writes every line to two places:

- **A file**, app-specific external storage, survives restarts, 512 KB dedicated ring buffer (not
  shared with the rest of the phone's logging):
  ```
  adb pull /sdcard/Android/data/com.spizganed.quickbuds/files/packets.log
  ```
  This is the **preferred** method — it captured this session's entire connection, from the first
  handshake attempt onward, uncontended by anything else on the phone.
- **Logcat**, tag `QuickBuds-Packets`, instant but shares the phone's single global logcat ring
  buffer with every other app, so busy phones can rotate it out within a few minutes:
  ```
  adb logcat -d -s QuickBuds-Packets:D
  ```

Either way, just do the gesture on the buds and say so — no MARK, no Dev Tools trip, no export.
This is the fast path for "does the thing that already works still work" / "what did that gesture
just send". Reach for Option B/C only when the question needs the HCI layer itself (something the
app doesn't decode at all, or doesn't attribute to any command).

## Option B — btsnoop / Wireshark (when the app's log is not enough)

Captures the HCI layer, so it sees frames the app never decoded. Wireshark opens a btsnoop log
directly (filter to Bluetooth RFCOMM); on a phone, an app that reads btsnoop works without a desktop.

**Warning:** nothing in this repo reads a btsnoop file. It is raw HCI — L2CAP and RFCOMM framing must
be walked by hand, or with Wireshark, before any payload starting `AA` is visible.

## Option C — automated: `adb bugreport` + `tshark` (no manual Wireshark step)

Confirmed working end-to-end 2026-09-20. Use this over Option A when the question needs the HCI
layer itself — something the app's own log doesn't decode or doesn't attribute to any command.

btsnoop logging is controlled by Developer Options as before, but on Android 15 the old
`persist.bluetooth.btsnoopenable` property no longer exists — check
`adb shell dumpsys bluetooth_manager | grep -i snoop` instead; `sSnoopLogSettingAtEnable = FULL`
means it's on.

1. Do the gesture / action on the buds as normal.
2. `adb bugreport capture.zip` — pulls a full bugreport (~10s, ~30 MB). Contains
   `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (current boot) and `...btsnoop_hci.log.last`
   (previous boot — check this one if the app was reconnected recently).
3. `unzip -p capture.zip FS/data/misc/bluetooth/logs/btsnoop_hci.log > btsnoop_hci.log`
4. Extract every QuickBuds frame as clean hex, with timestamp and direction:
   ```
   tshark -r btsnoop_hci.log -Y "btrfcomm && data.data[0] == aa" \
     -T fields -e frame.number -e frame.time_relative -e hci_h4.direction -e data.data
   ```
   `hci_h4.direction` is `0x00` = sent (app→buds), `0x01` = received (buds→app). Don't filter on
   `btrfcomm.channel` — it's assigned per-connection (seen as 15 on this device, but it's not
   fixed) — the `data.data[0] == aa` check is what actually identifies our protocol frames, same as
   `payloadOf()` does in the app.
5. For full packet detail instead of the flat hex, drop `-T fields ...` and use `-T json` (do
   **not** add `-c N` together with `-Y` — that combination returns an empty result on the tshark
   build tested here).

This trades Option A's speed for HCI-layer visibility, at the cost of `adb bugreport`'s latency and
pulling a whole-system capture rather than just this app's session.

## Lines worth knowing

| Line | What it is |
|---|---|
| `MARK #n` | A manual timestamp. Press it *after* the gesture. |
| `BTN EVT:` | The `0xF1` user-interaction family, decoded — side, button, action, modifier, context. |
| `UNATTR RX:` | Any frame the app does not already decode, with payload head. |
| `TX[...]: AA ..` | A command the app sent. |
| `KEYFN DIFF:` | What changed in the key-function table between two reads. |

`UNATTR RX:` and `BTN EVT:` are usually the whole point of a capture.

## What this already answered

**Bud-side ANC gestures DO raise a frame.** The `0x0204` subType `0x03` push follows an ANC hold.
This was believed impossible for a long time — it was declared "verified 3 times" that ANC raises no
event — because `noteUnattributed` blanket-excluded cmd `0x0204`, so an undecoded subType printed
nothing at all and genuine silence looked identical to unparsed data. The lesson is in
[PROTOCOL.md](./PROTOCOL.md) §5 ("History of Getting This Wrong"): **absence of a log line is not
absence of a frame.**

Also settled by capture: `F1` `byte3` carries the resolved function, and a hold's `F1` frame still
reports `byte3 = 0x08` even when the stored function byte has been cleared to `0x00`.

## Still open, and capturable

- **Confirming the `0x810C` `02 01` mask's meaning.** The reply shape is now known (`[CAPTURE]`
  2026-09-20, see PROTOCOL.md §5) — `0x0007`. What the bits mean is still `[INFERRED]`; needs a
  membership-change test (change the hold's cycle in the vendor app, re-query, see which bit moves).
- `0x0500` / `0x0501` — empty payloads, so they cannot be gesture bindings. Seen right after ANC
  writes; possibly this firmware's alternate ANC notification.
- Broadcast codes `0x04` / `0x08` / `0x0B`.
- The `0x810D` batch-status reply layout.
