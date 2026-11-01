# Capturing the earbuds' traffic

Record the traffic between the phone and the earbuds, for a bug report or to learn a new command.
**Capture first, then build.** A guessed payload or command number fails silently on the buds.

Two ways, from easy to thorough:

| | Needs | Sees |
|---|---|---|
| **1. The app's own log** | Only the app | Everything QuickBuds sends and receives |
| **2. Bluetooth HCI snoop log** | Developer options, adb, Wireshark / tshark | Everything on the Bluetooth link, **HeyMelody's traffic included** |

Start with 1. Use 2 to learn what HeyMelody sends for a setting. Use it also when a frame
never shows in the app's log.

## 1. The app's own log

1. Open QuickBuds and connect. Wait a few seconds for the connect burst to finish.
2. Dev tools › **Clear**, so the export holds one clean session.
3. Do each action: a setting in the app, or a gesture on the buds. **Wait at least 3 seconds**
   before the next action. Write down what you did and the time.
4. Repeat the round once, so a one-off frame gets a chance to show again.
5. Dev tools › **Export**. The file lands in `Download/QuickBuds/`.

The Dev tools log has Simple and Detailed views. Packets the app cannot decode show in amber with
their whole payload. Bytes it cannot frame show as `DISCARDED RX`.

On Windows and Linux, Dev tools works the same. Export saves to `Downloads/QuickBuds/`. The file has
raw `TX` and `RX` lines and connection notes, without the named lines below.

With adb, skip the export. The app writes the same lines to a file (a 2 x 512 KB ring) and to logcat:

```bash
adb pull /sdcard/Android/data/com.spizganed.quickbuds/files/packets.log
adb pull /sdcard/Android/data/com.spizganed.quickbuds/files/packets.old.log   # the older half
adb logcat -d -s QuickBuds-Packets:D   # instant, but other apps can rotate it out in minutes
```

### Lines worth knowing

| Line | What it is |
|---|---|
| `TX[...]: AA ..` | A command the app sent. |
| `RX: AA ..` | A frame the buds sent. The Detailed view decodes it. `RX[batch]` is one answer from a batch reply. |
| `UNATTR RX:` | A frame the app does not decode, with its payload head. **Usually the point of a capture.** |
| `BTN EVT:` | A gesture on the buds (the `0xF1` family): side, button, action. |
| `KEYFN DIFF:` | What changed in the gesture table between two reads. |
| `FEATURE REFUSED:` | The buds refused a switch write. Status 1: the buds lack the switch. Status 2: they have it and will not take it now, for example high-quality audio without an LDAC or LHDC link. The line shows the setting key, the reason and the codec. An old-path line also shows the status and the offered codecs. |

**A silent log does not prove that no frame exists.** Was: a filter hid the ANC push, and the team believed
"ANC raises no event" for weeks. Is: check the HCI log when the app's log is silent.

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
- Wireshark opens the same file. Filter `btrfcomm` and look for payloads starting `aa`.

**If the filter returns nothing:** the log started after the RFCOMM link came up. Then tshark never saw
which L2CAP channels carry RFCOMM. List the channels, skip the audio stream (by far the biggest), and
decode the rest by hand, one per direction:

```bash
tshark -r btsnoop_hci.log -Y btl2cap -T fields -e btl2cap.cid | sort | uniq -c
tshark -r btsnoop_hci.log -d btl2cap.cid==0x3040,btrfcomm -d btl2cap.cid==0x0071,btrfcomm -Y btrfcomm ...
```

Frames over 127 bytes have a two-byte length (LEB128, PROTOCOL.md §2). They can arrive in several
RFCOMM frames. Join them per direction before you cut at `AA`. Other RFCOMM channels carry HFP's AT
commands. Ignore those.

tshark on the phone (Termux): `pkg install x11-repo && pkg install wireshark-qt`.

### What to send

Send the tshark output, or the lines for your actions. Add your notes: the setting, the old and new value,
and the time. That is enough to add a command to PROTOCOL.md.

## Send a snoop log for a connect problem

Use this section when QuickBuds does not connect, for example in issue #18. You need only the phone.
Prefer to send the log in private. Step 4 gives the reason.

### 1. Turn on the snoop log with the phone

1. Open Settings › About phone (or **About device**). Tap **Build number** 7 times. This opens
   Developer options.
2. Open Developer options › **Enable Bluetooth HCI snoop log**. Set it to **Enabled** or **Full**.
3. Turn Bluetooth off and on again. The log starts again with the new setting.

The name can differ. OnePlus, OPPO, realme, and Xiaomi phones can use another name, or put the
option in another place. Search Developer options for "snoop" if you cannot find the option.

### 2. Reproduce the problem one time

1. Disconnect other Bluetooth devices: the car, the watch, and other headphones. The log then holds
   as little other traffic as possible.
2. Open QuickBuds and connect the earbuds one time.
3. Wait for the connection, or for the failure. Dev tools › **Simple** shows "Could not connect to
   the buds", and **Detailed** shows "All connection methods failed".
4. Write down the time of the test.

### 3. Get the log file without a PC

1. Open Developer options › **Take bug report** (or **Bug report**).
2. Select **Full**. Wait for the phone to finish.
3. Tap the bug report notification. Share or save the zip.
4. Open the zip with a file manager. The log is `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
5. The path can differ. Search the zip for `btsnoop_hci`.
6. Copy that one file out of the zip. Do not share the whole zip.
7. Delete the zip.

With a PC, use the adb steps in [Pull the log](#pull-the-log).

### 4. Protect your privacy

A bug report zip holds personal data: accounts, other apps, and Wi-Fi names. Never post the zip in
public.

Everyone who gets the raw `btsnoop_hci.log` file sees your Bluetooth addresses, device names, and
data from other connected Bluetooth devices. Send it in private, unless you accept that.

### 5. Where to send the file

Send the `btsnoop_hci.log` file in a direct message to the developer on the QuickBuds Discord
server: https://discord.gg/pvsnEf9TrZ.

If you accept that everyone sees this data, you can do one of these:

- Attach the filtered `connect.pcapng` file from step 6 to the GitHub issue.
- Share the raw `btsnoop_hci.log` file through a share link that you remove after the case. For
  example, use Google Drive with "anyone with the link", then remove the file.

The filtered `connect.pcapng` file still holds the Bluetooth addresses.

### 6. Filter for a connect problem

This step needs Wireshark or tshark. The frames that matter are the connection, the service search,
and the RFCOMM setup. The audio frames do not matter.

The field names below come from the Wireshark display-filter reference:
https://www.wireshark.org/docs/dfref/.

```bash
tshark -r btsnoop_hci.log \
  -Y 'bthci_evt.code == 0x03 || bthci_evt.code == 0x05 || bthci_cmd.opcode == 0x0405 || bthci_cmd.opcode == 0x0406 || btl2cap.cid == 0x0001 || btsdp || btrfcomm.dlci == 0 || (btrfcomm && btrfcomm.frame_type != 0xef)' \
  -w connect.pcapng
```

- `bthci_evt.code`: `0x03` is Connect Complete, `0x05` is Disconnect Complete.
- `bthci_cmd.opcode`: `0x0405` is Create Connection, `0x0406` is Disconnect.
- `btl2cap.cid == 0x0001`: the L2CAP signalling channel.
- `btsdp`: the service search.
- `btrfcomm.dlci == 0`: the RFCOMM multiplexer control channel.
- `btrfcomm.frame_type != 0xef`: the RFCOMM control frames, without the UIH data frames.

To do it in Wireshark, open the file, type the filter in the display filter bar, and press Enter.
Then use File › Export Specified Packets, select **Displayed**, and save.

If you send the raw `btsnoop_hci.log` file in private, the developer can do the filtering.

## Send a snoop log for a missing feature

Use this section when HeyMelody or realme Link has a setting that QuickBuds does not have.
Use it also when a QuickBuds switch does nothing on your earbuds. You need an Android phone.
A PC cannot make this log: HeyMelody and realme Link run only on a phone.
An iPhone cannot make it with these steps either.
A snoop log shows the exact command that the vendor app sends. Without it, the developer must guess.
A guessed command fails silently on the earbuds.

### Before you start

1. Turn on the snoop log. Follow [Turn on the snoop log with the phone](#1-turn-on-the-snoop-log-with-the-phone).
2. Install HeyMelody or realme Link, the app that has the setting.
3. Stop QuickBuds: Quick Settings › **Active apps** › Stop. The earbuds talk to one app at a time.

### Record the setting

1. Disconnect other Bluetooth devices: the car, the watch, and other headphones.
2. Open the vendor app and let it connect to the earbuds.
3. Change only the setting that QuickBuds lacks. Turn it on, wait 5 s, turn it off, wait 5 s, turn it on.
4. For a setting with more than 2 values, select each value. Wait 5 s between changes.
5. Write down each change and its time.
6. Record the screen of the vendor app during the test. Or take a screenshot of each step, with a number and a label.
7. Get the log within 15 minutes. The phone keeps only the newest Bluetooth traffic.

### Send it

1. Get the `btsnoop_hci.log` file. Follow [Get the log file without a PC](#3-get-the-log-file-without-a-pc).
2. Read [Protect your privacy](#4-protect-your-privacy).
3. Open a [Missing feature issue](https://github.com/spizganed/QuickBuds/issues/new?template=missing-feature.yml).
   Attach the log, the recording or screenshots, and your notes. To keep the log private, send it as in
   [Where to send the file](#5-where-to-send-the-file) and write "sent on Discord" in the issue.

One log for one setting is enough. You can record several settings in one log.
Then write down the time of each change.

## Still open, and capturable

- `act 0x03` in the on-call group (`btn 0x06`): a third slot HeyMelody's UI has never used.
- Everything in PROTOCOL.md §12, on buds other than the Buds 4 in particular.
