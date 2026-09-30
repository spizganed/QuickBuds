# Capturing the earbuds' traffic

How to record what goes between the phone and the earbuds, for a bug report or to learn a new command.
**Capture first, then build:** a guessed payload or command number fails silently on the buds, and
guessing has cost this project whole sessions more than once.

Two ways, from easy to thorough:

| | Needs | Sees |
|---|---|---|
| **1. The app's own log** | Only the app | Everything QuickBuds sends and receives |
| **2. Bluetooth HCI snoop log** | Developer options, adb, Wireshark / tshark | Everything on the Bluetooth link, **HeyMelody's traffic included** |

Start with 1. Use 2 when the question is "what does HeyMelody send for this setting", or when a frame
never shows up in the app's log.

## 1. The app's own log

1. Open QuickBuds and connect. Wait a few seconds for the connect burst to finish.
2. Dev tools › **Clear**, so the export holds one clean session.
3. Do each action (a setting in the app, or a gesture on the buds) and **wait at least 3 seconds**
   before the next one. Write down what you did and the time.
4. Repeat the round once, so a one-off frame gets a chance to show again.
5. Dev tools › **Export**. (The Dev tools log has Human, Detailed and Raw hex tabs; packets the app cannot
   decode show in amber with their whole payload, and bytes it could not frame as `DISCARDED RX`.) The file lands in `Download/QuickBudsLogs/`.

With adb, skip the export. The app writes the same lines to a file (a 2 x 512 KB ring) and to logcat:

```bash
adb pull /sdcard/Android/data/com.spizganed.quickbuds/files/packets.log
adb logcat -d -s QuickBuds-Packets:D   # instant, but other apps can rotate it out in minutes
```

### Lines worth knowing

| Line | What it is |
|---|---|
| `TX[...]: AA ..` | A command the app sent. |
| `RX: ...` | A frame the buds sent, decoded where the app knows it. |
| `UNATTR RX:` | A frame the app does not decode, with its payload head. **Usually the point of a capture.** |
| `BTN EVT:` | A gesture on the buds (the `0xF1` family): side, button, action. |
| `KEYFN DIFF:` | What changed in the gesture table between two reads. |

**No log line does not mean no frame.** A filter once hid a real push for weeks ("verified three
times" that ANC raised no event). When the app's log is silent, check with the HCI log.

## 2. Bluetooth HCI snoop log

Android records every Bluetooth packet when this is on. It works for any app, so it also shows what
HeyMelody writes for a setting.

### Turn it on

1. Settings › About phone › tap Build number 7 times (Developer options).
2. Developer options › **Enable Bluetooth HCI snoop log** › **Enabled** (full).
3. Turn Bluetooth off and on, so the log restarts with the new setting.

Check it with `adb shell dumpsys bluetooth_manager | grep -i snoop`: `FULL` means on.

### Capture HeyMelody

The buds serve one control app at a time, so QuickBuds has to let go first.

1. Stop QuickBuds from Quick Settings › **Active apps** › Stop. (`am force-stop` is not enough: the
   app reconnects when the audio link comes back.)
2. Open HeyMelody and let it connect.
3. Change **one** setting at a time, with a few seconds between changes. Write down each change, its
   old and new value, and the time. Go back and forth once (on, off, on).
4. Pull the log soon: it is a ring of about 65 000 records, roughly 15 minutes of busy traffic.

### Pull the log

```bash
adb bugreport capture.zip        # about a minute, 30-60 MB
unzip -p capture.zip FS/data/misc/bluetooth/logs/btsnoop_hci.log > btsnoop_hci.log
# btsnoop_hci.log.last is the previous Bluetooth session; look there if Bluetooth restarted
```

**A bugreport holds personal data** (accounts, other apps, Wi-Fi networks). Never attach it to an
issue. Share only the filtered frames below, then delete the zip and the phone's own copy:
`adb shell rm /data/user_de/0/com.android.shell/files/bugreports/*`.

### Pull out the frames

Every QuickBuds / HeyMelody frame starts with `AA` (PROTOCOL.md §2):

```bash
tshark -r btsnoop_hci.log -Y "btrfcomm && data.data[0] == aa" \
  -T fields -e frame.number -e frame.time_relative -e hci_h4.direction -e data.data
```

- `hci_h4.direction`: `0x00` = sent by the phone, `0x01` = sent by the buds.
- Do not filter on `btrfcomm.channel`: it changes per connection.
- For full detail use `-T json`, but not together with `-c N` (returns nothing on some builds).
- Wireshark opens the same file; filter `btrfcomm` and look for payloads starting `aa`.

**If the filter returns nothing:** the log began after the RFCOMM link came up, so tshark never saw
which L2CAP channels carry RFCOMM. List the channels, skip the audio stream (by far the biggest), and
decode the rest by hand, one per direction:

```bash
tshark -r btsnoop_hci.log -Y btl2cap -T fields -e btl2cap.cid | sort | uniq -c
tshark -r btsnoop_hci.log -d btl2cap.cid==0x3040,btrfcomm -d btl2cap.cid==0x0071,btrfcomm -Y btrfcomm ...
```

Frames over 127 bytes have a two-byte length (LEB128, PROTOCOL.md §2) and can be split over several
RFCOMM frames: join them per direction before cutting at `AA`. Other RFCOMM channels carry HFP's AT
commands; ignore those.

tshark on the phone (Termux): `pkg install x11-repo && pkg install wireshark-qt`.

### What to send

The tshark output (or the lines for your actions), plus your notes: which setting, old and new value,
and the time. That is enough to add a command to PROTOCOL.md.

For the maintainer: keep the filtered, decoded transcript in `local/logs/` with a descriptive, dated
name (for example `heymelody_hold_oncall_20260922.log.txt`). Delete the bugreport and the raw
btsnoop file.

## Still open, and capturable

- `act 0x03` in the on-call group (`btn 0x06`): a third slot HeyMelody's UI has never used.
- Everything in PROTOCOL.md §12, on buds other than the Buds 4 in particular.
