# PROTOCOL.md — the earbud wire format

Matches QuickBuds 5.4.2 (version code 49).

This is the wire format of the OPPO / OnePlus / realme earbud RFCOMM link. One protocol layer
implements it in Kotlin: `protocol/`, which the Android app runs. Each section names its code file.
The desktop apps decode with `desktop/src/protocol.rs` and build commands with `core/src/cmd.rs`; their feature
sync is `core/src/feature_sync.rs`. Phase E moves the rest of the decoding into `core/`, the Rust port of `protocol/`.

Each fact comes from a capture, from our own buds, or from the vendor apps' behaviour (studied for
interoperability only). This file describes the code. It does not describe the vendor apps.

**Evidence tags:**
- `[VENDOR]`: HeyMelody's behaviour, or realme Link's where a line says so. The project studied the vendor apps for interoperability only.
- `[CAPTURE]`: a frame in a capture on our own buds.
- `[TESTED]`: a manual test on our own buds.
- `[GUESS]`: unverified.

Never promote a claim without evidence. Never remove a `[GUESS]` tag to tidy up.

**Test device:** OnePlus Buds 4, firmware `B4.1-260810-1153` (HeyMelody shows `138.138.105`). Other models differ in feature ids, button ids and ANC bits. "Wired, unverified" means the app sends it but no owner of that model has read a write back.

**Status:** complete for every feature the apps build. Section 22 lists every unused command and its layout. Some ids there remain unread.

**How to add a fact:**

1. Check HeyMelody and realme Link first.
2. Check the `0x8100` bitmap and the `0x8200` codes: does the firmware claim it?
3. Subscribe to the event, or silence looks like absence.
4. Capture from a known state. The developer says what he did, in order ([PACKET-CAPTURE.md](./PACKET-CAPTURE.md)).
5. **Never guess a payload or an enum.** Log the raw value; let the device name it. Confirming a read is cheap; a guessed write fails silently.
6. **Read every write back.** An ack of `00` is not proof.
7. **Do not refute a theory with a sample that may be a different kind of event.**

Update this file in the same commit as the protocol change.

---

## 1. Transport

Classic Bluetooth **RFCOMM / SPP**, not BLE. One long-lived socket. The buds push events at any time.

| UUID | Use |
|---|---|
| `0000079A-D102-11E1-9B23-00025B00A5A5` | Tried first. The one that works on Buds 4 `[CAPTURE]`, as HeyMelody does `[VENDOR]` |
| `00001107-D102-11E1-9B23-00025B00A5A5` | Tried second. Never connects on Buds 4 (~5 s timeout); kept for other models |

The connect order is both UUIDs secure first, then both insecure: `079A` secure, `1107` secure, `079A`
insecure, `1107` insecure. Then RFCOMM channel 15 by reflection. A 12 s watchdog closes a connect that
blocks. A connect makes 5 socket tries in total, then stops. A lost link gets 8 reconnect tries `[CAPTURE]`. The desktop app tries
the two UUIDs in the same order and takes the channel from the buds' SDP record.

A case lid close ends the socket (section 13) `[CAPTURE]`.

**Code:** `protocol/Protocol.kt` holds the UUID constants. The socket code is in `bluetooth/`.
The core crate has no transport code.

---

## 2. Frame format

```
AA  <TotalLen>  <Slice> 00  [<Fsn>]  <Cmd LE>  <Seq>  <PayLen LE>  <Payload...>
```

| Field | Size | Meaning |
|---|---|---|
| `AA` | 1 | Frame start |
| TotalLen | 1-3 | LEB128: 7 bits per byte, low bits first, bit 7 set = more bytes. Counts every byte after itself |
| Slice | 1 | `00` whole message, `01` first slice, `02` middle slice, `03` last slice `[VENDOR]` |
| `00` | 1 | Always zero |
| Fsn | 1 | Slice counter, from `00`. Present in every slice, absent in a whole message `[VENDOR]` |
| Cmd | 2 | Command id, little endian |
| Seq | 1 | Picked by the sender; the reply echoes it. `FF` = a push from the buds `[CAPTURE]` |
| PayLen | 2 | Payload length, little endian |
| Payload | PayLen | The message body |

Examples `[CAPTURE]`:

```
AA 07 00 00 00 01 01 00 00                  handshake 0x0100, PayLen 0
AA 0A 00 00 04 04 06 03 00 01 01 01         set ANC Off
AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00   ANC push (Seq FF)
```

- Whole message: `TotalLen = 7 + PayLen`. Slice: `TotalLen = 3 + slice length` `[CAPTURE]`.
- **TotalLen is standard LEB128** `[CAPTURE]`: one byte below 128, else 7 bits per byte, low first, high bit = more. A length that subtracts 1 first does not fit. A 124-byte payload goes out as `AA 83 01` (131). A 250-byte reply arrives as `AA 81 02` (257). Golden Sound (section 20) uses frames over 127 bytes.
- The framer normalises every frame to a one-byte length. After that the header is a fixed 9 bytes: **cmd at 4-5, seq at 6, payload length at 7-8, payload from index 9**. A wrong offset gives garbage, not an error.
- No checksum.

### Slicing `[VENDOR]`

A message that does not fit the frame budget goes out in slices.

```
AA  <TotalLen>  <Slice> 00  <Fsn>  <part of Cmd/Seq/PayLen/Payload>
```

- `Slice`: `00` whole message, `01` first slice, `02` middle slice, `03` last slice.
- `Fsn` (slice counter, from `00`) is present in every slice, never in a whole message. `TotalLen` is then `3 + data`, not `2 + data`.
- The receiver joins the slice data in order. It parses the message when `Slice` is `03`. A middle or last slice with no first slice before it is dropped. A declared `PayLen` larger than the bytes present cuts the payload to the bytes present `[VENDOR]`.
- A slice frame has `TotalLen` 4 or more. A whole message has `TotalLen` 7 or more.
- The receiver reads a byte stream, not separate reads. It discards bytes before `AA`, reads `TotalLen`, and waits for that many bytes. The Kotlin framer treats a `TotalLen` below 4 (slice) or 7 (whole message) as noise: it drops the `AA` and searches on. It drops a frame above 2048 bytes. The core framer drops a `Len` below 2 or above 4096.
- A read that starts with `AA` begins a new frame. Any other read continues the frame in progress.
- The sender splits a message when it exceeds the frame budget. The budget is 660 bytes by default. The reply to `0x0107` (`00 <u16 LE>`) can lower it `[VENDOR]`. Some bulk transfers use a larger budget. QuickBuds sends none.
- One write at a time. Wait for the write to finish (5 s limit) and pause a model-specific interval between slices.

**Code:** `protocol/Wire.kt`, `core/src/wire.rs`.

---

## 3. Sequence and replies

The sender picks `Seq`. The reply repeats it `[CAPTURE]`. QuickBuds uses these ranges `[CAPTURE]`:

| Seq | Use |
|---|---|
| `00` | The status poll `0x010D`, and the connect and plain status reads |
| `01`-`BF` | The generic counter of the other commands |
| `C0`-`DF` | The feature-sync engine; wire seq = `C0 + (n mod 0x20)` |
| `F0` | The battery query `0x0106` |
| `F2` | The wearing query `0x0109` |
| `FF` | A push from the buds |

- The buds echo a status read with a non-zero seq: the reply `0x810D` carries the same seq `[TESTED 2026-10-24]`.
- Reply command = request command with bit 15 set (`0x0106` → `0x8106`) `[CAPTURE]`. A write reply carries a status byte, `00` = accepted. Accepted does not mean applied: read the state back.
- A refused `0x0403` write carries a non-zero status. `01` means the buds lack the id. `02` or any other value means they refuse it now (section 16). The buds refuse a `0x0404` write with `0E` when no bud is in an ear (section 14).
- The app matches a reply on `(Seq, Cmd & 0x7FFF)` `[VENDOR]`.

**Code:** `protocol/Protocol.kt` (the sequence counter and the envelope), `protocol/Replies.kt`; `core/src/wire.rs`.

---

## 4. Command classes

| Range | Class |
|---|---|
| `0x01xx` | Query, reply `0x81xx` |
| `0x02xx` | Event registration and pushes (`0x0204` push) |
| `0x03xx` | Firmware upgrade (not used) |
| `0x04xx` | Write, reply `0x84xx` |
| `0x05xx` | Requests from the buds |
| `0x0Fxx` | Private channel (not used) |

`0x01F0` and `0x01F2` are not commands. Each is a command byte welded to its Seq. Battery is `0x0106`;
wearing is `0x0109`.

**Code:** `protocol/Protocol.kt`; `core/src/msg.rs`.

---

## 5. Command bitmap

The reply to `0x0100` is `00` and then bytes read least significant bit first. Bit n, when set, enables the commands
in the table. The vendor app refuses a command with no bit and never sends it. Always enabled: `0x0100`-`0x0104`,
`0x0106`, `0x010B`, `0x010D`, `0x0F00`, `0x0F03`, `0x0F04`.

The table has 69 bits. Bits past the buds' bitmap length are off `[VENDOR]`. Buds 4 `[CAPTURE]`:
`00 FF 77 5A EA 67 0E 20 07`. That is every command the app uses, not `0x0422` / `0x012A`.

| Bit | Commands |
|---|---|
| 0 | `0x0105` |
| 1 | `0x0106` |
| 2 | `0x0107` |
| 3 | `0x0108` `0x0401` `0x0416` |
| 4 | `0x0109` |
| 5 | `0x0400` |
| 6 | `0x0402` |
| 7 | `0x0403` |
| 8 | `0x010C` `0x0404` |
| 9 | `0x0405` |
| 10 | `0x0406` `0x010F` |
| 11 | `0x0407` |
| 12 | - |
| 13 | `0x0408` |
| 14 | `0x0409` |
| 15 | - |
| 16 | - |
| 17 | `0x0114` |
| 18 | - |
| 19 | `0x040E` `0x040D` `0x0115` `0x0116` |
| 20 | `0x040F` |
| 21 | `0x0410` `0x0119` |
| 22 | `0x0205` |
| 23 | `0x0F00` |
| 24 | - |
| 25 | `0x0118` `0x0411` |
| 26 | `0x011A` `0x0412` |
| 27 | `0x011C` `0x0413` |
| 28 | - |
| 29 | `0x0112` `0x040B` |
| 30 | `0x011E` `0x011F` `0x0415` |
| 31 | `0x040D` |
| 32 | - |
| 33 | `0x0121` `0x0417` |
| 34 | `0x0122` `0x0418` |
| 35 | `0x0419` |
| 36 | `0x011D` `0x0414` |
| 37 | `0x0123` `0x041A` |
| 38 | `0x0124` `0x041B` |
| 39 | `0x0125` `0x041C` `0x0127` `0x041D` `0x041F` |
| 40 | `0x0421` `0x0023` `0x0024` `0x0022` `0x0126` `0x0129` |
| 41 | `0xEF01` |
| 42 | `0xEF02` |
| 43 | `0xEF03` `0x041E` |
| 44 | `0x0420` |
| 45 | `0x001C` |
| 46 | - |
| 47 | `0x0422` `0x012A` |
| 48 | `0xEF04` |
| 49 | `0x0423` `0x012B` |
| 50 | - |
| 51 | `0x0424` |
| 52 | `0xEF06` |
| 53 | - |
| 54 | - |
| 55 | `0x0425` `0x012E` `0x0426` |
| 56 | `0x012F` |
| 57 | `0x0427` `0x0130` |
| 58 | `0x0131` `0x0428` |
| 59 | `0x0429` `0x0132` |
| 60 | `0x0014` |
| 61 | `0x042D` `0x0133` |
| 62 | `0x042E` |
| 63 | `0xEF07` |
| 64 | `0xEF08` `0x0136` `0x0433` |
| 65 | `0xEF09` |
| 66 | `0x0431` `0x0134` |
| 67 | `0x0432` `0x0135` `0x0435` |
| 68 | `0x0117` |

Bits 35, 64, 67 and 68 enable `0x0419`, `0x0136`, `0x0433`, `0x0432`, `0x0135`, `0x0435` and `0x0117` `[VENDOR]`.
QuickBuds never sends these, because their payloads are unknown. HeyMelody sends nothing else, and
QuickBuds sends nothing else either `[VENDOR]`.

**Code:** `protocol/Capabilities.kt`; `core/src/msg.rs`.

---

## 6. Connection and init sequence

Init sequence `[CAPTURE]` (Buds 4, both apps): wait 300 ms, then one message every 200 ms.

```
0x0100 handshake → 0x0103 product id → 0x0200 broadcast codes → 0x0205 subscribe →
0x010D features → 0x010C 01 01 ANC → 0x010C 04 01 Smart level (Smart models) →
0x0130 alert volume → 0x0106 battery → 0x0109 wear → 0x0108 gesture table →
0x010C 02 01 hold cycle (02 03 / 02 04 on per-bud holds) → 0x0105 firmware →
0x012A spatial type → 0x0123 codec list → 0x0114 codec → 0x012B game sound →
0x0134 head motion → 0x041E 00 3D audio on the buds (spatialToggle models)
```

With bitmap bit 56, every read after the subscription goes out in one `0x012F` frame. A later status
read goes alone. The read order starts with `0x010D`, `0x010C 01 01` and `0x010C 04 01` on Smart
models. Then `0x0130`, `0x0106`, `0x0109`, `0x0108` and the hold-cycle reads. Then `0x0105`,
`0x012A`, `0x0123`, `0x0114`, `0x012B` and `0x0134`. A model with `spatialToggle` gets `0x041E 00`
after the reads.

The app does not poll after that. Battery, wearing, ANC and game mode arrive as pushes `[TESTED]`.
Was: a keep-alive poll. Is: none. No stale link seen `[TESTED]`.

### What the buds are and accept

- **Bitmap** `0x8100` (section 5) `[VENDOR]`: `00`, then bytes read LSB first. Bit n enables a fixed list of commands. The app checks every command against the bitmap. It does not send a command with no bit.
- **Product id** `0x8103` `[CAPTURE]`: `00 <id, 3 bytes LE>`. Buds 4: `00 14 54 06` is `065414`. Four colour ranges fold into one id before the model lookup `[VENDOR]`: `100100`-`100102` → `060414`, `100200`-`100202` → `060814`, `108100`-`108102` → `068414`, `108200`-`108202` → `068814`.
- **Model lookup** `[VENDOR]`: an entry whose `name` equals the Bluetooth name exactly. Also an entry whose `id` equals the product id. An entry that matches both wins, then the first name match, then the first id match. A model picked by hand overrides it until other buds connect. Name before id matters: OPPO Enco Buds2 report `060C12`, the realme Buds Q2s id (issue #5) `[CAPTURE]`. A renamed device matches no name and falls back to its id.
- **Firmware** `0x0105` → `0x8105` `[CAPTURE]`: `00 <count>` then UTF-8 `deviceType,versionType,version` triples, joined by commas. Buds 4: `00 04` + `1,2,138,2,2,138,3,1,01,3,2,105`. Device types: `1` left, `2` right, `3` case `[VENDOR]`. The app shows the `versionType` 2 versions in reply order, joined by dots: `138.138.105`.
- **Offered codes** `0x8200` = `00 <count> <codes>`. Buds 4 `[CAPTURE]`: `00 09 01 02 03 04 08 0B F1 F2 F3`. Read it before deciding that an event does not exist: silence usually means "not subscribed". Nord Buds 3 Pro (`064414`) offers only `00 02 02 01` (wear, battery) `[CAPTURE]`. OPPO Enco Buds2 (`060C12`) also offers `02 01` `[CAPTURE]`.
- **Subscribe** `0x0205 <count> <codes>`, count first `[CAPTURE]`. The app asks for `01 02 03`. It adds `04` when the bitmap has `0x0405`, `08` when it has `0x040D`, and `0B` when it has `0x0412`. It keeps only the codes the buds offered, as the vendor app registers exactly the offered list `[VENDOR]`.
  - Never send `01 01 02 02`: that is "count 1, battery only". The buds accept it and never push wearing.
  - Never drop `03` when offered. Without it Buds 4 sends no ANC change made on the buds.
  - Buds without `0x0205` in the bitmap take `0x0201 <code>` once per code `[VENDOR]`.
  - The `0x8205` ack: `01 02 01 00 02 00` and, for five ids, `01 05 01 00 02 00 03 00 04 00 08 00` (Buds 4): `01 <count>`, then `<id> 00` per id `[GUESS]`. Fewer ids than asked means a part was rejected. HeyMelody reads none of these bytes; the ack only ends its connect setup `[VENDOR]`.
  - Nord Buds 3 Pro (`064414`) sent no `0x8205` when asked for `01 02 03 04 08 0B` `[CAPTURE]`. It still pushed an ANC change once without `03`.

Per-model product id and bitmap rules are in sections 21.1 and 21.2.

**Code:** `protocol/Commands.kt`, `protocol/Capabilities.kt`, `protocol/ModelCatalog.kt`; `core/src/cmd.rs`.

---

## 7. Queries — `0x01xx`

Details in the sections cited. `—` = empty payload.

| Cmd | Name | Request → reply | Section |
|---|---|---|---|
| `0x0100` | Handshake | — → command bitmap | 5 |
| `0x0101` | Remote MTU | `00 02` (u16 512) → `00 <mtu u16>` | 22 |
| `0x0102` | Remote vendor id | `FF FF` (65535), or `9A 07` (1946) from another app → `00 <vendor id u16>` | 22 |
| `0x0103` | Product id | — → `00 <3 bytes LE>` | 6 |
| `0x0104` | Remote protocol version | — (the buds send it) → `00 <UTF-8 version>` | 22 |
| `0x0105` | Firmware version | — → `00 <count> <triples>` | 6 |
| `0x0106` | Battery | — → `00 <count> <part> <raw>...` | 13 |
| `0x0107` | Frame budget | — → `00 <u16 LE>` | 2 |
| `0x0108` | Gesture table | — → `00 <count> <entries>` | 15 |
| `0x0109` | Wearing | — → `00 <count> <part> <state>...` | 13 |
| `0x010B` | Colour id | — → `00 <colour id>` | 22 |
| `0x010C` | ANC state / hold cycle | `01 01`, `02 01` / `02 03` / `02 04`, `04 01` | 14 |
| `0x010D` | Feature switches | `<count> <ids>` → `00 <count> <id> <value>...` | 16 |
| `0x010F` | Current EQ | — → `00 <id>` | 17 |
| `0x0112` | Dual connection device list | — → `00 <count> <entries>` | 19 |
| `0x0114` | Current codec | — → `00 <codec>` | 18 |
| `0x0115` / `0x011E` | Golden Sound active record | — | 20 |
| `0x0116` / `0x011F` | Golden Sound filters | see section 20 | 20 |
| `0x0117` | Earphone identity | `<count> <types>`, the vendor asks `03 02 03 04` → `00 <count>` then `<type> <len u16> <value>` | 22 |
| `0x0118` | Ear-restore data | `<count> <ids>` → `00 <count>` then `<type> <len u16> <data>` | 22 |
| `0x011A` | Personalised ANC result stored? | — → `00 <exist>` | 20 |
| `0x011C` | Related-device info | — → `00 <flags u16>` then the push `0D` fields in order | 22 |
| `0x0121` | Tone list | — → `00 <count>` then `<id u32> <valid> <crc u32> <selected> <nameLen> <name>` | 22 |
| `0x0122` | Custom EQ list | — → `00 <count> <presets>` | 17 |
| `0x0123` | Codecs offered | — → `00 <u16 LE mask>` | 18 |
| `0x0124` | BassWave level | — → `00 FB 05 <level>` | 17 |
| `0x0125` | Hearing-enhancement data | — → `00 <kind> <count>`, then `<count>` × 3 bytes | 22 |
| `0x0126` | Hearing-enhancement frequency | `<start u32> <end u32>` → `00` then the push `09` stream | 22 |
| `0x0127` | Screen-off broadcast delay | — → `00 <delay>` | 22 |
| `0x0129` | Zen-mode file information | — → `00 <file id u32> <file well u32> <name>` | 22 |
| `0x012A` | Spatial type | — → `00 <type>` | 18 |
| `0x012B` | Game sound type | — → `00 <selected> <count> <types>` | 18 |
| `0x012E` | AI summary type | — → `00 <AI summary type>` | 22 |
| `0x012F` | Batch query | see section 12 | 12 |
| `0x0130` | Alert-sound volume | — → `00 <level>` | 18 |
| `0x0131` | AI translation app status | — → `00 <status>` | 22 |
| `0x0132` | Preferred device | `02` → `00 02 <00 | 01 <MAC>>` | 19 |
| `0x0133` | Tap sensitivity | — → `00 <level> <default>` | 18 |
| `0x0134` | Head gesture mapping | — → `00 <type>` | 18 |
| `0x0135` | Voice-assistant config | — → `00 <bits>`: bit 0 type 0, bit 1 type 1, bit 2 type 2 (highest set bit wins), bit 7 the in-ear music mute flag | 22 |
| `0x0136` | LE / BR link switch state | — → `00 <bits>`: bit 0 state, bit 1 owner, bit 2 reset | 22 |

**Code:** `protocol/Commands.kt` and the topic decoders (`protocol/Battery.kt`, `Anc.kt`, `Eq.kt`,
`Gestures.kt`, `GoldenSound.kt`); `core/src/cmd.rs`, `core/src/msg.rs`.

---

## 8. Subscriptions — `0x02xx`

| Cmd | Name | Payload |
|---|---|---|
| `0x0200` | Query broadcast codes | — → `0x8200` = `00 <count> <codes>` |
| `0x0201` | Subscribe one event | `<code>` |
| `0x0204` | Push | Seq `FF`, `payload[0]` = event code (section 10) |
| `0x0205` | Subscribe | `<count> <codes...>`, count first |

- `0x0200` lists the codes the buds offer. Read it before deciding that an event does not exist `[CAPTURE]`.
- `0x0205` subscribes. Use `0x0201 <code>` once per code on buds without `0x0205` in the bitmap `[VENDOR]`.
- The app asks for `01 02 03`. It adds `04` when the bitmap has `0x0405`, `08` when it has `0x040D`, and `0B` when it has `0x0412`. It keeps only the codes the buds offered `[VENDOR]`.
- The `0x8205` ack is `01 <count>` then `<code> 00` per code `[GUESS]`. The vendor app reads none of it. The reply ends the setup.

**Code:** `protocol/Commands.kt`, `protocol/Protocol.kt`; `core/src/cmd.rs`.

---

## 9. Writes — `0x04xx`

Details in the sections cited. `—` = empty payload.

| Cmd | Name | Payload | Section |
|---|---|---|---|
| `0x0400` | Find my earbuds | `01` / `00` | 18 |
| `0x0401` | Gesture write | `<count> [dev, btn, act, fn]...` | 15 |
| `0x0402` | Free-music request (the buds send it) | the phone answers `00` | 22 |
| `0x0403` | Feature switch | `<id> <01/00>` | 16 |
| `0x0404` | ANC mode / hold cycle | `01 01 <mask>` / `02 <kind> <mask>` | 14 |
| `0x0405` | Fit test | `01` / `00` | 20 |
| `0x0406` | Select built-in EQ | `<id>` | 17 |
| `0x0407` | Unused | — | 22 |
| `0x0408` | Related-device list | `<hostType> <host MAC 6> <count>`, then `<type> <MAC 6> <state>` per device; at most 6 | 22 |
| `0x0409` | Unused | — | 22 |
| `0x040B` | Unused | — | 22 |
| `0x040D` | Golden Sound ear scan / hearing test | `04 01 <id>` / `04 00 <id>` / `02 01` / `02 00` | 20 |
| `0x040E` | Golden Sound tone / record | see section 20 | 20 |
| `0x040F` | Camera state | `<state>` | 22 |
| `0x0410` | Tone file check | — | 22 |
| `0x0411` | Golden Sound restore | `01 01 01 00 <desc>` | 20 |
| `0x0412` | Personalised ANC test | `01` / `02` / `03` | 20 |
| `0x0413` | Phone state for device switching | `<flags> 00 <values>` | 19 |
| `0x0414` | Free-dialog recovery time | `<time>` | 22 |
| `0x0415` | Golden Sound ear-scan data | `03 A8 00 <scan> <id>` / `02` / `01 00 00 00000000` | 20 |
| `0x0416` | Unused | — | 22 |
| `0x0417` | Tone file check | — | 22 |
| `0x0418` | Custom EQ create / save / delete | see section 17 | 17 |
| `0x0419` | Host capabilities | `<bits>` | 22 |
| `0x041A` | Set codec (`hiRes` models) | `<codec> <hiRes> 00` | 18 |
| `0x041B` | BassWave level | `FB 05 <level>` | 17 |
| `0x041C` | Account key | `01 01 <len> <key>` binds, `02` clears | 22 |
| `0x041D` | Screen-off broadcast delay write | `<delay>` | 22 |
| `0x041E` | 3D audio source | `00` the buds, `01` the phone | 18 |
| `0x041F` | Device build model | `<len> <UTF-8 model>` | 22 |
| `0x0420` | Game equalizer | `01 <state>` | 18 |
| `0x0421` | Spine-health range | `<state> <step>` | 22 |
| `0x0422` | Spatial type | `<type>` | 18 |
| `0x0423` | Game sound type | `<type> 01` | 18 |
| `0x0424` | LE Audio action | `<type> <value>` | 22 |
| `0x0425` | AI summary type | — | 22 |
| `0x0426` | AI prompt sound | — | 22 |
| `0x0427` | Alert-sound volume | `<level>` 1..10 | 18 |
| `0x0428` | AI translation | — | 22 |
| `0x0429` | Device manager / preferred device | see section 19 | 19 |
| `0x042B` | One-shot voice operation | `<type> <data>` | 22 |
| `0x042C` | Start or stop the sound record | `<on> 00` | 22 |
| `0x042D` | Tap sensitivity | `<level>` 1..5 | 18 |
| `0x042E` | Multi-conversation switch | `<v>` | 22 |
| `0x0430` | Speech-assist dialog status | `<status>` | 22 |
| `0x0431` | Head gesture mapping | `<type>` | 18 |
| `0x0432` | Voice-assistant control type | `<t>`: `01`, `02` or `04` | 22 |
| `0x0433` | LE / BR link switch | `<on>` | 22 |
| `0x0434` | Earbud control sound channel | `<off>`: `01` is off | 22 |
| `0x0435` | Start voice-assistant config | `00` | 22 |

- A write reply is `0x84xx`. Status `00` means accepted. Read the state back.
- `0x0401` is the gesture write `[CAPTURE]`. Its reply is `0x8401 00`. `0x0402` gets no reply at all. A wrong command number fails silently (section 15).
- Writes that QuickBuds does not send have no builder in `protocol/Commands.kt`. Their layouts are in section 22.

**Code:** `protocol/Commands.kt`, `protocol/HearingCommands.kt`; `core/src/cmd.rs`.

---

## 10. Pushes — `0x0204`

`payload[0]` is the event code. `Seq` is `FF`. "After the code" below means the bytes that follow
`payload[0]`. The app drops a known code with too few bytes. It keeps an unknown code as raw bytes.

| Code | Event | Layout after the code |
|---|---|---|
| `01` | Battery | `<count>` then `<part> <raw>` pairs (section 13) `[CAPTURE]` |
| `02` | Wearing | `<count>` then `<part> <state>` pairs (section 13) `[CAPTURE]` |
| `03` | ANC | `01 01 <mode LE, 1-4 bytes>` current mode; `02 <kind> <mask LE>` hold-cycle mask; `04 01 <mask LE>` the level Smart chose `[CAPTURE]` |
| `04` | Fit test | `<dev> <result>` per bud; `dev` `01` left, `02` right. A bud missing from the list has no result `[CAPTURE]` |
| `05` | Game mode | `<state>`; on four product ids (`062410` `062810` `063010` `063410`) `<x> <x> <state>`. The push confirms a game-mode write about 1 s after the ack; a read sooner returns the old value `[CAPTURE]` |
| `06` | Dual connection devices | `<count>` then devices (section 19) `[CAPTURE]` |
| `07` | No handler | — |
| `08` | Hearing test status | `<kind> <status>`; kind `02` hearing test, `04` ear scan |
| `09` | Hearing-enhancement frequency stream | `<uid u32 BE> <ear 01 L / 02 R> <total u16 LE> <index> <type> <floats, f32 LE>` (section 22) |
| `0A` | Zen-mode switch state | `<state>` `[VENDOR]`. QuickBuds has no zen-mode feature (needs the vendor's tone files) |
| `0B` | Personalised ANC result | `<result>` |
| `0C` | No handler | — |
| `0D` | Related-device info | `<flags u16 LE>` then the fields whose flag is set, in this order: bit 0 capability `<u8>`, bit 1 version `<u8>`, bit 2 wear `<u8>`, bit 3 status `<u8>`, bit 4 name `<len> <bytes>` `[VENDOR]`. Status bits: 0 phone has no active command, 1 connected by the related device, 2 HFP busy, 3 A2DP busy, 4 the other device's auto switch is closed. A field whose bytes are missing is absent |
| `0E` | Ear scan result | `<kind> <len u16 LE> <data> <id u32 BE>`; `len` is not 0 (section 20) `[CAPTURE]` |
| `0F` | Public event | `<flags u16 LE> <data>`. The first match wins: bit 0 alert-sound volume `<v>`; bit 1 nothing; bit 2 handheld device `<mode> [<MAC 6, reversed>]`, mode `00` = automatic, no MAC; bit 4 tap level `<v>` `[VENDOR]`. Needs at least 1 data byte; a MAC needs 6 more |
| `10` | One-shot state | Voice-wakeup audio stream setup `[VENDOR]`: flags, sample-rate type, format, channels, codec, frame size u16 LE, session state, VAD state. Not built (voice wakeup is decided against) |
| `11` | Tone-change list | List of tone records `[VENDOR]`. Not built (tone files come from the vendor's servers) |
| `F1` | Gesture fired | `<side> <button> <act> <fn> <context> <int16 LE options...>`; needs 5 bytes (section 15) `[CAPTURE]` |
| `F2` | Connected-devices info (answered) | `<count>` then `<MAC 6, reversed> <times u16 LE> <nameLen> <name>` |
| `F3` | Content unknown | HeyMelody ignores it `[VENDOR]`. It is in the `0x8200` list. It falls through the vendor event switch like an unknown code |
| `F4` | Diagnostic JSON (answered) | UTF-8 JSON |
| `F5` | Head gesture type | `<type>` |
| `F6` | Head-motion try result | `<result>`; one byte `[VENDOR]` |
| `F7` | Voice-assist control type | `<bits>`: bit 0 type 0, bit 1 type 1, bit 2 type 2 (highest set bit wins), bit 7 a support flag `[VENDOR]` |
| `F8` | LE / BR link switch state | `<bits>`: bit 0 state, bit 1 owner, bit 2 reset flag `[VENDOR]` |
| `F9` | AI translation audio focus | `<v>`; online feature, not built |

Codes `07` and `0C` have no handler. Code `09` is the hearing-enhancement frequency stream. The vendor
app joins the packets until both ears are complete.

### Feature push `0x0503`

`0x0503 <count> <id> <value>...` reports a changed switch after a `0x0403` write or a buds gesture.
95 frames in ten reports prove the layout, including a 17-pair frame and one extra byte after the last
pair on OPPO Enco Buds3 Pro `[CAPTURE]`. The app feeds the pairs to the sync engine as a push, so a
model that reports the new game-mode state only here confirms the write (reports #63 and #73).

`0x0504 <id>` reports that the EQ changed. `0x0510 <type>` reports that the spatial type changed.

Per-model push rules are in section 21.9 and the sections it names.

**Code:** `protocol/Battery.kt`, `protocol/Anc.kt`, `protocol/GameMode.kt`, `protocol/Gestures.kt`,
`protocol/FeaturePush.kt`; `core/src/msg.rs`.

---

## 11. Requests from the buds and replies

Some firmwares wait for these answers and reset the link when none comes. Nord Buds 3 Pro (`064414`,
issue #2) `[CAPTURE]` sends `0x0500` with a real `Seq` and `00 00 00 00`. It sends the request again,
then stops answering queries. It resets the link 3-5 s after connect, every time, if no answer comes.
Buds 4 sends `0x0500` empty and works either way.

Every answer repeats the request's `Seq` and sets bit 15 of its command.

| From the buds | Answer | Meaning |
|---|---|---|
| `0x0500` | `00 <Unix seconds u32 LE>` | Time request, sent on connect with `0x0501` `[CAPTURE]` |
| `0x050C` | `00 01 00` | Is the foreground app on a list the buds hold: no |
| `0x051C` | `00 00 00` | The app's capabilities; the middle byte is fast discovery: off |
| `0x051D` | `01` | The phone's spatial mode. The vendor app answers with the mode, and `01` for bad data; QuickBuds always sends `01` |
| push `F2` | `00 F2` | Connected-devices info, parsed or not |
| push `F4` | `00 F4 {"cmd":<its cmd>}` | Diagnostic JSON; `01 F4` when it is not a JSON object; `00 F4` when empty. Buds 4 sends these with `Seq` `FF` |
| a command outside groups `01`-`05` and `0F` | `01` | Unknown request |

- The vendor app does not answer any other push. This includes battery, wearing, ANC and `06`. `0x0501`, `0x0502` and `0x0504` get no answer.
- The vendor app adds `<zone offset seconds s32 LE>` to the time answer for models its list flags `utcTimeZone`. The list flags none, so the 4-byte form goes out for every model. A newer list may flag some.
- Buds 4 `[CAPTURE]`: `0x0500` answered within 5 ms, link unchanged.
- `0x0501` and `0x0502` are the camera state and shutter requests of a feature QuickBuds does not have (decided against).

**Code:** `protocol/Replies.kt`; `core/src/msg.rs`.

---

## 12. Batch query — `0x012F`

Bitmap bit 56. One message carries many queries `[CAPTURE]`: HeyMelody sends one with 24 queries after
`0x0100`, `0x0200` and `0x0205`.

- Request: `<count>` then `<cmd LE> <len LE> <payload>` per query.
- Reply: several `0x812F` messages with the request's `Seq`, each `00 <count>` then `<cmd LE> <len LE> <reply payload>` per answer. `cmd` is the query's. Handle each answer as the reply `cmd | 0x8000` with the batch's `Seq` `[VENDOR]`. An answer that runs past the end ends the list. The app drops an answer whose status byte is not `00`.
- Buds 4 query list `[CAPTURE]`: `0102 FFFF`, `0106`, `010B`, `0103`, `0101 0002`, `0114`, `0105`, `0107`, `0108 020301`, `010C 0201`, `010C 0101`, `010C 0301`, `010C 0401`, `010F`, `010D 09 05 04 0B 11 18 06 1B 1D 1C`, `0121`, `0123`, `0118 0101`, `011C`, `0115`, `011E`, `0122`, `0105`, `0109`. The reply came in three frames with 1, 21 and 2 answers.
- Buds without bit 56 (Enco Buds2) take the queries one by one. Enco Buds2 has a 4-byte bitmap `[CAPTURE]`.
- With bit 56, the connect-time reads after the event registration go out as one `0x012F`. A later status read goes alone.
- HeyMelody's `0x010D` id list for the Buds 4 is `05 04 0B 11 18 06 1B 1D 1C` `[VENDOR]`. The buds answered `00 08 05 00 04 00 0B 01 11 01 18 01 06 00 1B 00 1D 01` (no `1C`). It does not ask for `17`, `0C`, `09` or `30`-`3B`.
- QuickBuds sends its own list. A model without bit 56 takes the queries one by one.

**Code:** `protocol/Replies.kt`; `core/src/msg.rs`.

---

## 13. Battery and wearing

**Battery** — query `0x0106`, reply `0x8106`, push `01`. Reply: `00 <count> <part> <raw>...`. Push:
`<count> <part> <raw>...` `[CAPTURE]`. A count of 0, or a list that runs past the end, is no list.
The parsers ignore the message and clear nothing.

- `part`: `01` left, `02` right, `03` case. Level = `raw & 7F`, charging = `raw & 80`. Example: `03 01 64 02 64 03 50`.
- Level `0` means "not known": the app leaves that part out. The realme Buds Air7 Pro sends the case at `0` with the lid closed `[CAPTURE]` (issue #9). HeyMelody hides a part at `0` `[VENDOR]`.
- A part left out keeps its last level unless the "Hide old battery levels" setting is on.
- The case, and a bud inside it, report only while the lid is open. With the lid closed, the query returns only the bud outside (`01 01 5A`) and the buds push nothing `[CAPTURE]`. The app does not show case charging, by decision.
- Pushes come every 1-6 minutes on the buds' own clock, not tied to `0x0500` `[CAPTURE]`.

**Wearing** — query `0x0109`, reply `0x8109`, push `02`. Payload: `<count> <part> <state>...`. A reply can put a status byte
before the count. The receiver accepts both, at offset 0 and at offset 1 `[CAPTURE]`. Query replies and pushes both need at least one part.
Every part must be `1`-`3`.

- The state is a set of bits `[VENDOR]`. Bit 0 means out of the case, bit 1 means in ear, bit 2 means lid open. Bit 3 means "safe remind". The app clears bit 3.

| State | Meaning |
|---|---|
| `0` | In the case, lid closed |
| `1`, `5` | Out of the ear (lid closed, lid open) |
| `3`, `7` | In the ear (lid closed, lid open) |
| `4` | In the case, lid open |

- **No in-ear sensor** `[CAPTURE]` (report #46, and the logs of the T200, T200x and Nord Buds 3r): these models answer `1` (out) for a bud in every state. They answer `0` or `4` when it sits in the case. The vendor's `wearDetection` flag does not tell. The Bullets Wireless Z3 and the Buds Ace lack it and report `3`. The app therefore shows `1` and `5` as in the ear, with no wear label, until the buds report `3` or `7` once. It remembers that per model.
- Lid close `[CAPTURE]`: about 1 s before the socket drops, the pushes fall to `0`, ending with `01 00 02 00 03 00`. No lasting "closed" state exists. The app logs `Case closed` and skips the reconnect retries. Opening the lid brings `04 04 04` and the case battery.

Per-model battery and wearing rules are in section 21.8.

**Code:** `protocol/Battery.kt`; `core/src/msg.rs`.

---

## 14. ANC (noise control)

Query `0x010C <what> <arg>`, reply `0x810C 00 <what> <arg-or-kind> <value>`, push `03`, write `0x0404`.

| Read | Reply or push | Meaning |
|---|---|---|
| `010C 01 01` | `01 01 <mode LE, 1-4 bytes>` | Current mode, a bitmask (report table below) `[CAPTURE]` |
| `010C 02 <kind>` | `02 <kind> <mask LE>` | Modes the hold cycles. `kind` `01` shared, `03` left, `04` right `[CAPTURE]` |
| `010C 04 01` | `04 01 <mask LE>` | The level Smart chose, in SET bits `[CAPTURE]` |
| — | `01 02 <level>` | Level report; the vendor app reads it, our buds never send it `[VENDOR]` |

The query `0x010C 01 01` returns `00 01 01 <mode LE>` (status, echo, value) `[CAPTURE]`. Read it on every
connect, so a reconnect corrects the display. The vendor app also reads `01 05 <mask>` as a mode mask.
QuickBuds has not seen it and does not decode it `[GUESS]`.

### SET — `0x0404 01 01 <mask>`

One bit set `[CAPTURE]`, `bit / 8 + 1` mask bytes, low byte first `[CAPTURE]`. The bit is the mode's `slot` in the
model list, never the mode's position in a list.

| Buds 4 mode | Bit | Payload |
|---|---|---|
| Off | 0 | `01 01 01` |
| ANC (last level) | 1 | `01 01 02` |
| Transparency | 2 | `01 01 04` |
| Deep | 4 | `01 01 10` |
| Medium | 5 | `01 01 20` |
| Light | 6 | `01 01 40` |
| Smart | 7 | `01 01 80` |
| Adaptive | 11 | `01 01 00 08` |

Adaptive is bit 11 (mask `0x0800`), not bit 8. Bit 8 gives `01 01 00 01`, a different mode. Take the bit
from the model's `slot`, never from a mode's position in a list.

### Report table

The report table is not the SET table `[CAPTURE]`. A report names a child of the mode that was set `[CAPTURE]`:

| Raw (LE) | Mode |
|---|---|
| `0x0008` | Off |
| `0x0002` | ANC, generic |
| `0x0010` `0x0020` `0x0040` `0x0080` | Deep, Medium, Light, Smart |
| `0x0100` | Transparency |
| `0x0200` | Transparency with voice enhance |
| `0x0800` | Adaptive |

- The ANC stop reports whichever level is active. The value is a bitmask, not an enum.
- The mode tree is per model (`ancModes` in the model list): `mode`, `slot`, `variants` `[VENDOR]`. Mode ids: `1` Off, `2` Transparency, `3` Light, `8` Medium, `4` Deep, `5` noise reduction, `7` Smart, `10` Adaptive, `6` transparency with voice.
- SET sends the mode's `slot` as the bit. A level sends its child's index `[VENDOR]`.
- A report is looked up in the same tree, parents first, then children `[VENDOR]`.
- A model with no tree gets no noise control. Before the app knows the model, it uses the Buds 4 tree `[VENDOR]`.
- **realme models** `[VENDOR]`: the write table and the report table are the same. Off `01`, Transparency `02`, Light `04`, Deep and plain ANC `08`, Medium `10`, Smart `20`. The Air7 Pro's owner confirms them `[TESTED]`. The others are unverified. The Air7 Pro log's `0x810C 01 01` value `02` = Transparency.
- With no bud in an ear, the buds refuse a SET. The reply `0x8404` has status `0E` `[CAPTURE]`. One bud in an ear is enough. The app then reads `0x010C 01 01` again, so the display shows the real mode, and says to put a bud in.
- A push is a mode only when its two bytes after the code are `01 01`. A hold-cycle write also raises `03 02 <kind> <mask>`: that is the new cycle mask, not a mode. In the Buds 4 capture `<kind>` is `01` `[CAPTURE]`.
- `AncEventParser` accepts a push only when bytes 1-2 are `01 01`. Was: the app decoded the hold-cycle push as a mode, and the display stuck on a false mode. Is: the app treats it as the hold-cycle mask.
- Smart `[CAPTURE]`: the buds ack the SET and push `0x0080`. Right after, the buds push `03 04 01 20 00` and `03 04 01 40 00`. The lowest set bit of that mask is a SET bit: `0x20` Medium, `0x40` Light on Buds 4. The app reads it on connect for models with Smart. Buds 4 answers the read with ANC Off too, and pushes right after a Smart SET. The ANC label shows it ("ANC S·M").
- The vendor app reads a level report `01 02 <level>`. Our buds never send it. The app does not decode a mode mask `01 05 <mask>` `[GUESS]`. It does not send a level write `0x0404 01 02 <level>` `[GUESS]`.

### The hold's ANC cycle

The gesture table's `fn 08` only says "the hold cycles ANC". Which modes it cycles is a separate
setting. Clearing `fn` to `00` does not stop the cycle `[CAPTURE]`.

| | Frame |
|---|---|
| Read | `0x010C 02 01` → `00 02 01 <mask LE>` `[CAPTURE]` (`02 03` / `02 04` on per-bud holds, section 15) |
| Write | `0x0404 02 <kind> <mask LE>`, kind `01` shared, `03` left / `04` right on per-bud holds |

- The mask uses SET bits `[CAPTURE]`. Adding Adaptive moved `0x0007` to `0x0807`.
- The mask takes 1-4 bytes, the fewest that hold it `[VENDOR]`: `02 01 03` was acked and read back as `0x0003` `[CAPTURE]`.
- `kind` `01` is one setting for both buds `[TESTED]` `[CAPTURE]`. Models with a per-bud hold use `03` left and `04` right. realme models keep `01`.
- The buds drop level bits silently. They acked `0x0021` with `00` and read it back as `0x0001` `[CAPTURE]`. The ANC stop in the cycle is always bit 1.
- A single-bit mask is valid. The app never sends `0`. Clearing bit 1 alone has not been tested.
- `0x0404 01 02 <level>` is a possible level write `[GUESS]`. The app does not send it.

### Two tables, three rules

- An ANC change raises push `03`. Subscribe to `03`; without it, the change is silent.
- SET and report use different bits. Do not copy a report value into a SET payload.
- Adaptive is bit 11. Bit 8 is a different mode.

Per-model tree shapes, realme levels and counts are in section 21.3.

**Code:** `protocol/Anc.kt`, `protocol/AncModes.kt`, `protocol/Commands.kt`; `core/src/msg.rs`,
`core/src/cmd.rs`.

---

## 15. Gestures

### Gesture table

Read `0x0108`, reply `0x8108 00 <count> <entries>`, write `0x0401 <count> <entries>` `[CAPTURE]`. An
entry is 4 bytes: `<dev> <button> <act> <fn>` `[CAPTURE]`. The read reply has a 2-byte header `<status 00> <count>`.
74 bytes = 2 + 18 × 4.

- `dev`: `01` left, `02` right, `04` both buds (on-call rows, and a write that the buds fan out).
- `button`: `01` main gestures, `06` on-call group.
- `act`: `01` single, `02` double, `03` triple, `04` hold, `05` slide, `06` long hold / "super long press".
- `fn`: see the table below. `02` is listening music; the app never shows it.

A write carries the whole table `[CAPTURE]`: the entries that do not change come from the last good read. A reply
with no entries replaces nothing. The app re-reads the table and diffs it after every write. A write
that matches no slot is refused loudly. The diff baseline is persisted (`QuickBudsKeyFnDiff`).

`0x0402` is ignored without any reply `[CAPTURE]`. A wrong command number fails silently, so the app reads the
table again after every write and compares.

Buds 4 table (`act:fn`) `[CAPTURE]`: left/`01` `01:00 02:11 03:00 04:08 06:00 05:07`; left/`06` `02:00 03:00 06:00`; right/`01` `01:00 02:00 03:00 04:08 06:00 05:07`;
right/`06` `02:00 03:00 06:00`.

Call control `[CAPTURE]`: `0401 01 04 06 02 1D` (double tap answers and ends), `0401 01 04 06 06 1C`
(long hold declines). The clear writes are `0401 01 04 06 02 00` and `0401 01 04 06 06 00`. One write
lands on both buds. The labels are confirmed on a real call `[TESTED]`.

### `fn` values

`00 01 03 05 06 07 08 0A 0B 0C 11 1C 1D` are measured on Buds 4 `[CAPTURE]`. Do not re-derive them.

| `fn` | Function | `fn` | Function |
|---|---|---|---|
| `00` | none | `0D` | switch devices |
| `01` | play/pause | `11` | game mode |
| `02` | listening music (never shown) | `12` | zen mode |
| `03` | voice assistant | `16` | collect music |
| `05` `06` | previous, next track | `19` | AI summary |
| `07` | volume (slide; reports as `0B`/`0C`) | `1A` `1B` | AI translation |
| `08` | ANC cycle (hold) | `1C` `1D` | decline, answer and end call |
| `09` | favourite music | `20` | spy tap |
| `0A` | switch track (slide; reports as `05`/`06`) | | |
| `0B` `0C` | volume up, down | | |

On "OnePlus Buds" (`060414`) and "OnePlus Buds Z" (`060814`), previous and next are `04` and `05`
`[VENDOR]`. Which functions a row offers is per model (`gestures` and `callGestures` in the model list).

### `F1` push

`<side> <button> <act> <fn> <context> <int16 LE options...>` `[CAPTURE]`. Example, right hold:
`AA 0D 00 00 04 02 FF 06 00 F1 02 01 04 08 03`.

- `act` and `fn` are the table's pair. `fn` is the effective function (a slide reports `0B`/`0C` or `05`/`06`). A hold with `fn` cleared still reports `08`. A "modifier bits" reading does not fit.
- A frame with `act 00` is some other event `[GUESS]`. The vendor app stores the frame and reads no field of it.
- The push is diagnostic. Never use it as a control signal: read the effect from the ANC, game and wearing pushes. One double tap can report twice. The app follows faithfully. Do not "fix" it.

The table changes shape per bud and between writes `[CAPTURE]`. Slide once lived in button `01` on the
left and in two unbound buttons on the right. Writing both halves with the same `fn` folded them back
into one `btn 01` slot (19 → 18 entries). The app writes every slot that the table has for a (side,
act) pair, excluding only `btn 06`. It never hardcodes a button group or a count.

Per-model gesture menus, options and hold rules are in section 21.4.

**Code:** `protocol/Gestures.kt`, `protocol/Commands.kt`; `core/src/msg.rs`, `core/src/cmd.rs`.

---

## 16. Feature switches

Write `0x0403 <id> <01|00>`. Read `0x010D <count> <ids>` → `00 <count> <id> <value>...`. Only ids the
firmware has come back `[CAPTURE]`. A missing id is a switch these buds do not have.

The reply `0x8403` carries the same seq byte `[CAPTURE]`. `payload[0]` is the status `[CAPTURE]`:

| Status | Meaning |
|---|---|
| `00` | Accepted |
| `01` | The buds lack that id (`04 0B 18 27 35` on the realme Buds T310) |
| `02` | The buds have the id and refuse it now |
| other | Refused now |

Status `02` is a condition, not a fault `[CAPTURE]`. The OnePlus Buds 4 refuses `18 01` 32 times while the phone
plays AAC. It accepts the same write while the phone plays LHDC V5.

- The id list is the model's `statusQuery` field in the model list `[VENDOR]`. A model without one gets 23 ids: `05 04 0B 11 13 18 06 1B 1C 27 28 1D 09 17 30 31 3A 32 35 37 38 3B 0C`. The `windMode` flag adds `1A`. Buds 4: `05 04 0B 11 18 06 1B 1D 1C` (matches the capture; the buds answered all but `1C`). Enco Buds2: `05 06` `[TESTED]`.
- The plain status read uses `Seq` `00` `[CAPTURE]`. The engine's own reads and writes use `C0`-`DF`, and the reply carries that seq (section 3).
- A longer list drops some links: one fixed 23-id list dropped the Enco Buds2 link (issue #5). `02 05 0D` kept it up `[CAPTURE]`.
- The id `05` is asked first for every model. The vendor app ignores the value and never writes it.
- Feature `1F` is for OPPO and OnePlus phones and is never asked.
- A game-mode write is confirmed by push `05`, not by a read. The buds apply the change about 1 s after the ack `[CAPTURE 2026-10-24]`.
- An id in the status reply does not prove the switch. The OPPO Enco Buds3 Pro lists `1B` and reports `1B=1` after a write. Its bitmap has no `0x012A`, and two owners hear no 3D audio (reports #60 #61). QuickBuds hides the row through its `statusQuery` (`05 11 06`) `[CAPTURE]`.

| Id | Switch | Notes |
|---|---|---|
| `04` | Wear detection (auto play/pause) | `[CAPTURE]` |
| `05` | ? | First id in every query; the vendor app ignores the value and never writes it `[VENDOR]` |
| `06` | Game mode, low latency | the apps call it Low latency mode; all models except where the bitmap has `0x0423`; there it is `28` |
| `09` | Vocal enhancement | `[VENDOR]` |
| `0B` | Golden Sound; the apps call it Hearing profile | section 20 `[CAPTURE]` |
| `0C` | Personalised ANC applied | the apps call it Personalised noise cancelling; the buds set it when a result applies |
| `0F` | Zen mode | not built; asked on 6 OnePlus and OPPO models, and no code reads the reply `[VENDOR]` |
| `11` | Dual connection | section 19 `[CAPTURE]` |
| `13` | Sound record | in both default id lists; asked on 5 models `[VENDOR]` |
| `16` | Lost reminder | switch `0x0403 16`; asked on 2 models (OPPO Enco Clip2, OnePlus Buds Clip) `[VENDOR]` |
| `17` | Power saving | Either write restarts the buds; the link returns about 12 s later `[CAPTURE]` |
| `18` | Hi-Res | the apps call it High-quality audio; a quality switch, not a codec switch; a model with a codec list refuses it unless the phone plays LDAC or LHDC `[CAPTURE]` |
| `1A` | Wind noise reduction | Only models with the `windMode` flag; realme Link writes `0x0403 1A`; unverified on our buds `[VENDOR]` |
| `1B` | Spatial sound | the apps call it 3D audio; models without `0x012A` `[CAPTURE]` |
| `1C` | Smart volume | `[VENDOR]` |
| `1D` | BassWave | the apps call it Bass boost `[CAPTURE]` |
| `1E` | Log collection | asked on 16 models; no constant in the code `[VENDOR]` |
| `21` | Game equalizer | section 18 `[VENDOR]` |
| `26` | Find phone | realme Link writes `0x0403 26`; the earbuds ring the phone `[VENDOR]` |
| `27` | Game sound; the apps call it game sound effects | the vendor app writes `0x0403 27` when the game-sound switch moves `[VENDOR]` |
| `28` | Game mode main switch | both default id lists ask it next to `06`; 10 models `[VENDOR]` |
| `30` `31` `32` `35` `37` `3A` `3B` | Adaptive volume, adaptive ear, conversation awareness, hold volume, Swift Pair, pause when asleep, head gestures | `[VENDOR]`, unverified on buds |
| `38` | Hearing optimisation | the vendor UI writes `0x0403 38`; the `adaptive sound` label is unverified `[VENDOR]` |

- **Power saving `17`** `[CAPTURE]`: either write restarts the buds. The link returns about 12 s later and `0x810D` shows the new value. ANC, gestures, wear, codec (LHDC V5 48 kHz / 24-bit) and link parameters do not change. The buds do not bring phone audio back; the app asks for it. HeyMelody does not show this row (China-market feature `[VENDOR]`).
- **Head gesture mapping** `[VENDOR]`: `0x0431 00` = nod answers / shake declines, `01` = the reverse. Read `0x0134` → `00 <type>`, pushed as `F5 <type>`. Bitmap bit 66. Was: "HeyMelody parses only the push". Is: it parses both.
- **Game sound type** `[VENDOR]`: `0x0423 <type> 01`, Off (`0`) included. Read `0x012B` → `00 <selected> <count> <types>`. Types: `0` Off, `1` a Chinese game, `3` shooting games. Offered: the model's `gameSoundList` types the buds also list.

The id list is per model `[VENDOR]`. `models.json` `statusQuery` holds the list (85 HeyMelody models,
from HeyMelody 116.9.0's built-in data). Buds 4: `05 04 0B 11 18 06 1B 1D 1C` (matches the capture).
Enco Buds2: `05 06` `[TESTED]`. Checked against the HeyMelody 117.6.6 id rule, all 85 listed models
match it. The lists leave out the ids of features the project decided against (`22`-`24`, `3C` neck
health, `3D` Gemini assistant). A model whose vendor data gives `wearDetection` 1 or 2 has `04` (wear detection) in its
`statusQuery` id list. The Enco X4 and Buds Pro 4 have 2.

- A model without `statusQuery` (hand-added) gets the 23 ids, plus `1A` where it has `windMode`.
- **realme models** `[VENDOR]` (realme Link 5.8.811): the per-model screen lists name the rows a model shows. The `statusQuery` for 30 realme models asks only for the ids of those rows: `04` smart play/pause, `06` game mode, `0B` hearing profile, `11` dual connection, `18` Hi-Res, `1B` 3D sound. Only the Air8 Pro, Air7 Pro, Air 5 Pro and Air6 Pro list a hearing profile. The T500 Pro does not (report #21), and its buds do not answer the tones. The Air7 Pro keeps its list: its owner confirmed it `[TESTED]`.
- A row whose id the model is not asked for does not show (HeyMelody parity: no Power saving or Personalised ANC on Buds 4).
- Parsed into `featureStates`, logged as `FEATURES:`. The buds' list wins over the model list while connected.

How HeyMelody builds the list `[VENDOR]`: `05`, then one id per `function` key, in this order. The value
is exactly `1` unless noted:

`04` wearDetection, `0B` hearingEnhancement(New), `0C` personalNoise (or
personalNoiseCompat.personalNoise), `0D` clickTakePic(New), `0F` zenMode > 0, `11` multiDevicesConnect
1/2, `09` vocalEnhance, `13` headSetSoundRecord, `18` highToneQuality 1/2, `17` longPowerMode, `15`
smartCall, `16` deviceLostRemind, `14` voiceWake 1/3, `19` voiceCommand 1/2, `06` gameMode or
gameModeList, `1B` spatialTypes, `1D` bassEngineSupport, `1C` controlAutoVolumeSupport, `1E`
collectLogs, `21` gameEqPkgList, (`1F` OPPO / OnePlus phones only, skipped), `22 23 24` spineHealth,
`27 28` gameSoundList or bitmap `0x0423`, `30` adaptiveVolume, `31` adaptiveEar, `32`
speechPerception, `34` meetingAssistant, `35` longPressVolume, `37` swiftPair, `38` hearingOptimize,
`39` incomingCallControl, `3B` headMotion, `3A` sleepDetection.

Ids the vendor app also builds: `0D` camera tap, `14` voice wakeup, `15` smart call, `19` voice command,
`22`-`24` neck health, `34` meeting assistant, `39` incoming-call control. Decided against: voice
wakeup `14`, voice command `19`, call control `39`, neck health, meeting assistant `34` `[TESTED]`.

realme Link writes the same ids: dynamic sound `1D`, enhance voice `09`, wind noise `1A`, game mode
`06`, wear `04`, dual `11`, Hi-Res `18`, 3D sound `1B` `[VENDOR]`. A second "enhance voice" setter
writes `0C` (ours: personalised ANC applied. Do not assume they match). It reports power saving as
`05`. The id is unresolved. The realme items STRENGTH_VOICE, WIND_NOISE and DYNAMIC_SOUND_EFFECT map
to `09`, `1A` and `1D`. No device test exists.

Ids `09`-`3B` without a `[CAPTURE]` are `[VENDOR]`, wired, unverified on buds. A row shows where
`0x810D` lists the id, or a hand-picked model has the flag.

Per-model switch lists and flags are in section 21.5.

**Code:** `protocol/Feature.kt`, `protocol/FeatureSync.kt`, `protocol/FeaturePush.kt`,
`protocol/RowRules.kt`; `core/src/feature_sync.rs`.

---

## 17. Equalizer and BassWave

```
0x0406 <id>                                   select a built-in preset     reply 8406 00
push 0x0504 <id>                              the EQ changed
0x010F → 810F 00 <id>                         current EQ (Buds 4: 00-02 built-in, 04-06 custom)
0x0122 → 8122 00 <count> <presets>            custom list
0x0418 <action> <min> <max> <id> <nameLen> <name> <bands> [<freq u16 LE> <gain s8>]...     reply 8418 00 <id>
0x0403 1D <01|00>                             BassWave on, off
0x041B FB 05 <level> [<band>]                 BassWave level, -5..+5; read 0x0124 → 00 FB 05 <level> [<band>]
```

- realme dynamic sound `[VENDOR]`: a switch (`1D`) and three sliders. Each slider write adds a band byte: `01` low, `02` medium, `03` high frequency. A model without the band byte ignores the write and still answers `00`. `models.json` marks these models with `bassBands` 3. T310 does not answer `0x0124`, so the app shows the last values it wrote.

- A preset in the list: `<selected> <min> <max> <id> <nameLen> <name> <bands> <bands...>` `[VENDOR]`. `FA 06` is gain -6..+6. `FB 05` is -5..+5. The tag bytes are `FA 06` in every frame seen `[VENDOR]`.
- `0x0418` actions:
  - `01` create. Send id `00`. The buds assign it and return it in the reply.
  - `02` save and select. Send the whole preset, every time, also on every drag step and every rename.
  - `03` delete. The selection falls back to `00`.
- Ids are renumbered after create and delete. Read `0x0122` again each time `[CAPTURE]`.
- Built-in presets differ per model `[VENDOR]`: `eqPresets` lists `{mode, slot}`; `slot` is the `0x0406` id. Buds 4: 0 Balanced, 1 Clear Vocals, 2 Bass. Nord Buds 2r: 0, 1 Bold, 2. Types 1-4 are named differently where `eqKind` is `2` (or Enco R / Air2). 41 of 143 models have none.
- Firmware-gated lists drop entries whose `minFirmware` is above the buds' firmware `[VENDOR]` (the lower non-zero of left and right, `138` on Buds 4, unreadable = 0). Use `eqPresetsCompat`, else `eqPresetsByFirmware`, else `eqPresets`. Stable sort by `order` (0 if absent).
- realme models `[VENDOR]`: four built-ins. `0x0406` ids: 0 Pure Raw, 1 Deep Bass, 2 Pure Voice, 3 Pure Bass. 28 realme models have them, not in HeyMelody's list. `models.json` gives them our own types 101-104, `eqKind` 1. T110 and T01 have three other presets with unknown ids: no EQ until a capture.
- A model shows custom presets with `customEq` and `0x0418` in the bitmap `[VENDOR]`, and BassWave with `bassEngine` and `0x041B`. An EQ row shows where `eqKind` is 1-4 or custom presets exist. New presets use the model's `customEqBands` (10 bands `31 62 125 250 500 1k 2k 4k 8k 16k` on 10 models), else 6 bands `62 250 1k 4k 8k 16k`. `customEqLimit` caps presets (default 3). Reads go out only where the bitmap lists them.

Per-model preset lists are in section 21.6.

**Code:** `protocol/Eq.kt`, `protocol/Commands.kt`; `core/src/cmd.rs`, `core/src/msg.rs`.

---

## 18. Sound and device settings

| Setting | Read | Write | Notes |
|---|---|---|---|
| Alert-sound volume | `0x0130` → `00 <level>` | `0x0427 <level>`, 1..10 | The reply to the write is `8427 00 <level>`. Send on slider release only: each write plays a prompt `[CAPTURE]` |
| Tap sensitivity | `0x0133` → `00 <level> <default>` | `0x042D <level>`, 1..5 | Flag `tapLevel`. Lower = taps trigger more easily. A change on the buds is push `0F` bit 4 (`0F 10 00 <level>`, no default byte). Warn below the default; offer a reset. Wired, unverified `[VENDOR]` |
| Head gesture | `0x0134` → `00 <type>`, push `F5` | `0x0431 <type>` | `00` nod answers and shake declines, `01` the reverse. Bitmap bit 66 `[VENDOR]` |
| Spatial type | `0x012A` → `00 <type>`, push `0x0510 <type>` | `0x0422 <type>` | `0` off, `1` fixed, `2` head tracking (Buds Pro 2, Buds Pro 3, Enco X3). Bitmap bit 47. Wired, unverified `[VENDOR]` |
| 3D audio source | — | `0x041E <source>` | `00` the buds render 3D audio, `01` the phone does. Bitmap bit 43, with `0xEF03`. HeyMelody sends only `01`; realme Link sends `00` on connect `[VENDOR]` |
| Current codec | `0x0114` → `00 <codec>` | — | Buds 4 `00 08` = LHDC V5 `[CAPTURE]` |
| Codecs offered | `0x0123` → `00 <u16 LE mask>` | — | Bit k = codec k + 1. Buds 4 answers `00 46 01`, which decodes to AAC, LDAC and LHDC; the maker lists LHDC 5.0, AAC and SBC only. Trust the mask only on `hiRes` models `[CAPTURE]` |
| Codec | — | `0x041A <codec> <hiRes> 00` | `hiRes` is set only with codec 3 or 8. The buds restart `[VENDOR]` |
| Game sound type | `0x012B` → `00 <selected> <count> <types>` | `0x0423 <type> 01` | Type `0` off, `1` a Chinese game, `3` shooting games `[VENDOR]` |
| Game equalizer | — | `0x0420 01 <state>` | `01` when a game app comes to the foreground, `00` when it leaves. Applies only while feature `21` is on. Bitmap bit 44 `[VENDOR]` |
| Find my earbuds | — | `0x0400 <01\|00>` | Rings both buds; no side byte; the reply is `8400 00` `[CAPTURE]`. Warn when the buds are in the ears |

- Codecs: `1` SBC, `2` AAC, `3` LDAC, `4` aptX, `5` aptX HD, `6` aptX Adaptive, `7` LHDC, `8` LHDC V5. The app lists 8, 7, 3, 6, 5, 4, 2, 1 and drops 7 when 8 is offered `[VENDOR]`.
- Spatial sound and Hi-Res exclude each other `[VENDOR]`. Spatial on is `1B 01` then `18 00`; Hi-Res on is `1B 00` then `18 01`. HeyMelody warns, then writes this.
- `18` is a quality switch, not a codec switch `[CAPTURE]`. The phone's Bluetooth settings choose the codec. The switch raises the bitrate and the sampling rate only. With LHDC V5: on is 44.1-192 kHz, 400 kbps cap; off is 44.1 / 48 kHz, 256 kbps. The phone chose 48 kHz / 24-bit both times. Buds 4 has no LDAC.
- On a model with a codec list (`0x0123`), the buds accept `18 01` (`00`) when the phone uses LDAC (3) or LHDC (7, 8). They refuse it (`02`) while the phone plays AAC or SBC `[TESTED 2026-10-24]`. Read `0x0114` first. Report #33's phone played AAC, so the newer rule refuses those writes. The link stays up.
- Older models accept `18` on AAC `[CAPTURE: realme Buds Air 5 Pro #4, T500 Pro #21, Air7 Pro #27, OnePlus Nord Buds 3 Pro #50]`. The Nord Buds 3 Pro offers only SBC and AAC. The switch turns on, but the sound does not change (report #40).
- A refused `18` write changes nothing. An accepted write restarts the link. The buds return about 4 s later `[CAPTURE]`.
- `18` and `0x8114` are per link `[CAPTURE]`: with dual connection each host reads its own value. Buds 4: phone `18=1`, LHDC V5; PC `18=0`, AAC, at the same time.
- realme models `[VENDOR]`: 3D sound is feature `1B` alone and Hi-Res is `18` alone, whatever the bitmap says. realme Link never writes one with the other (on some models it only warns).
- Smart auto-pause (pause only when both buds leave) is an app feature, not firmware. The app sends a media pause key. It and switch `04` are mutually exclusive in the UI.
- **Two ways for spatial sound** `[VENDOR]`: if the bitmap has `0x012A` (bit 47), the app writes `0x0422 <type>`. The types are `0` off, `1` fixed and `2` head tracking. It reads `0x012A` → `00 <type>`. The buds push `0x0510 <type>`. Else the app uses feature `1B`. Head tracking only where `spatialModes` has `2` (Buds Pro 2, Buds Pro 3, Enco X3). `0x0422` is wired, unverified.
- The Air7 Pro lists `0x012A` / `0x0422` but leaves `0x012A` unanswered `[VENDOR]`. Our key `spatialToggle` covers the realme models with 3D sound. It works on the Air7 Pro (its owner, issue #9). It is unverified on the others.
- **3D audio source** `[VENDOR]` (bitmap bit 43, with `0xEF03`): `00` the buds render 3D audio, `01` the phone does. HeyMelody sends only `01`, when the phone's own spatializer takes over. realme Link sends `00` on every connect (no realme model prefers the phone). The apps do the same for `spatialToggle` models. Issue #9's "`1B` changes nothing" was a false alarm: the buds play no sound on the switch.
- **Codec picker** `[VENDOR]`, wired: models with `hiRes` (Enco X2, Enco Air3 Pro, Enco Free3) and `0x0123` in the bitmap. It replaces the Hi-Res switch. Write `0x041A <codec> <hiRes> 00`; `hiRes` only with codec 3 or 8 (else `0`). The buds restart; the app warns and asks for phone audio on the reconnect.

Per-model codecs, spatial and Hi-Res rules are in section 21.7.

**Code:** `protocol/Commands.kt`; `core/src/cmd.rs`.

---

## 19. Dual connection

```
0x0112 → 8112 00 <count> <entries>            push 0x0204 06 <count> <entries>
entry: <MAC, 6 bytes reversed> <len> <state> <flags> <nameLen> <name UTF-8>
```

The entry layout is `[CAPTURE]`.

- `len` counts state, flags, nameLen and name.
- State `02` is connected. State `00` is not connected. A dropped device stays in the list `[CAPTURE]`.
- Flags: bit 0 this phone, bit 1 main audio device, bit 2 playing, bits 3-5 device type `[VENDOR]`.
- Toggle: `0x0403 11 <00|01>`, then `0x0413` with auto switch: `08 00 01` after off, `08 00 00` after on. The app sends it also when the screen opens `[CAPTURE]`. The app repeats the frame verbatim.
- `0x0413 <flags> 00 <values>`: the phone's state. One byte for each set flag, in bit order. Bit 0 protocol version, bit 1 screen on, bit 2 held by hand, bit 3 auto switch on, bit 4 music playing `[VENDOR]`. A `0` flag byte with no values (`00 00`) is valid. So `08 00 01` = auto switch on.
- Device manager `[VENDOR]`, wired, unverified: models whose `dualDevice` lists it (11 models; bitmap bit 59 `0x0429` `0x0132`. Buds 4 lacks it). `0x0429 01 <MAC>` connect, `02 <MAC>` disconnect, `03 <MAC>` unpair (unused). The MAC is in written order, the reverse of the list. Preferred device: `0x0429 04 00` automatic, `04 01 <MAC>`. Read `0x0132 02` → `00 02 <00 | 01 <MAC>>`.
- Related-device push `0D` (section 10) and the handheld device in push `0F` bit 2 belong to the same automatic switching. QuickBuds does not use them.

Per-model dual rules are in section 21.11.

**Code:** `protocol/Commands.kt`; `core/src/cmd.rs`, `core/src/msg.rs`.

---

## 20. Golden Sound, fit test, personalised ANC

### Golden Sound (hearing profile)

`[VENDOR]` `[CAPTURE]`. On and off: `0x0403 0B <01|00>`. It applies the record stored on the buds. The
buds hold only the active record. The phone keeps the list. A record: a 4-byte id (big endian, chosen
by the phone), 12 hearing values, 168 bytes of ear-scan data. A hearing value is
`<side 01 L / 02 R> <freq 01..06> <value, signed>`. The 12 values are left ears 1-6 then right ears
1-6. Every frame below matches a HeyMelody capture `[CAPTURE]`.

| Step | Frames |
|---|---|
| Read the active record | `0x0115` → `00 03 0C <12 values> <id> <name ASCII>`; `0x011E` → `00 03 A8 00 <168 scan> <id>` |
| Ear scan | `0x040D 04 01 <id>` start, `04 00 <id>` stop; the result arrives about 8 s later as push `0E 03 A8 00 <168> <id>` |
| Hearing test | `0x040D 02 01` start, `02 00` stop |
| Tone | `0x040E 03 01 <side> <freq> <value>`; `0x040E 04` stops it and goes before each new level |
| Apply | `0x0411 01 01 01 00 <desc>` (when the record has a description id; `0B` in the capture), `0x040E 03 0C <12 values> <id> <name>` (the name is the date, "2026/09/29 01:53"), `0x0415 03 A8 00 <scan> <id>` (when there is a scan), then `0x0403 0B 01` |
| Clear | `0x040E 02` and `0x0415 02`; after the test `0x040E 01 00 00000000` and `0x0415 01 00 00 00000000` |
| Status push `08` | `<2 test / 4 scan> <s>`: 1 or 3 audio playing, 2 or 4 resumed, 5 a bud out, 6 back in, 7 timed out |
| Filters | `0x0116 0C <12 values> <id>` → `00 <id> 04 <count LE> <packet> <enhance type> <floats>`; `0x011F A8 00 <scan> <id>` → `00 <id> 04 <rate LE> <count LE> <packet> <floats>` |

- Floats are little endian, left ear first, then right. A count is a multiple of 12. They are biquads `a0 a1 a2 b0 b1 b2`: 60 floats = 5 per ear, 72 = 6. The `0x0116` filter rate is 44100.
- A refused write (`0x840D` or `0x840E` with a non-zero status) ends the step.
- The OPPO Enco Air5 Pro refuses an ear-scan start (`0x040D 04 01 <id>`) with status `01` on Windows (6 times, report #44). The cause is unknown. The wear state is a guess `[CAPTURE]`.
- `0x040F 01` in the capture is an unrelated camera feature.
- Slider: 25 stops `-120 -88 -55 -52 -49 -45 -41 -38 -35 -30 -25 -22 -19 -15 -11 -8 -5 -1 3 5 7 10 13 15 17`. Each move plays a tone, at most every 300 ms, starting near `-30`. The saved value is the stop snapped to the nearest of `-55 -49 -41 -35 -25 -19 -11 -5 3 7 13 17`. A loudness warning shows once at 10 or more.
- Radar graph: per ear, add the hearing and ear-scan biquads in dB (`20 log10 |B/A|`, 44100 Hz default). Read at 80, 10000, 4800, 2400, 1200 and 250 Hz (80 at the top, clockwise). Radius = `max(2, -|dB| × 10 / scale + 10)`, scales 7.5, 15, 15, 12.5, 12.5, 7.5. 10 means no change.
- The ear scan exists only on models whose list has `hearingScan`.

### Fit test

`[VENDOR]` `[CAPTURE]`. `0x0405 01` start, `00` stop. The reply is `00`. The result is push
`04 <dev> <s> <dev> <s>`: `1` good, `0` average, `6` poor, else an error. Buds 4: about 8.6 s →
`04 01 01 02 01`. Both buds must be in the ears. The app stops after 15 s and when the sheet closes.
It needs event `04` subscribed.

### Personalised ANC

`[VENDOR]`, wired, unverified. Model flag `personalAnc` (11 models), bitmap bit 26 (`0x0412`,
`0x011A`). Buds 4 lacks it.

| Step | Frame |
|---|---|
| Is a result stored? | `0x011A` → `811A 00 <exist>`; non-zero = yes |
| Test, use the stored result, cancel | `0x0412 01`, `02`, `03`; reply status `15` = another device is busy |
| Result | push `0B <r>`: `0` applied, `1` too quiet, `2` poor fit, `3` wind, `4` movement, `5` audio or call |
| Off | `0x0403 0C 00`. The buds set `0C` on themselves when a result applies |

Both buds must be in the ears. The app waits 5 s for `0x811A` and 15 s for a result, and cancels when
the dialog closes.

Per-model Golden Sound, fit-test and personalised ANC rules are in section 21.10.

**Code:** `protocol/GoldenSound.kt`, `protocol/HearingCommands.kt`; `core/src/cmd.rs`.

---

## 21. Per-model rules

Every rule in this section names the model id, the byte and the value. A topic section points here.

### 21.1 Product id and model lookup

- Four colour ranges fold into one id before the lookup `[VENDOR]`: `100100`-`100102` → `060414`, `100200`-`100202` → `060814`, `108100`-`108102` → `068414`, `108200`-`108202` → `068814`.
- The model list matches the Bluetooth name exactly first, then the product id. An entry that matches both wins, then the first name match, then the first id match `[VENDOR]`.
- A model picked by hand overrides the lookup until other buds connect `[VENDOR]`.
- OPPO Enco Buds2: the model entry is `064810`, but the buds report product id `060C12`, the realme Buds Q2s id (issue #5) `[CAPTURE]`. A renamed device matches no name and falls back to its id.
- OnePlus Buds 4: model id `065414`, product id `00 14 54 06` `[CAPTURE]`.
- OnePlus Nord Buds 3 Pro `064414` (issue #2) `[CAPTURE]`.
- OnePlus Buds Pro `060C14` `[VENDOR]`.
- OnePlus Buds `060414`. OnePlus Buds Z `060814` `[VENDOR]`.

### 21.2 Bitmap and command support

- OnePlus Buds 4 `065414`: bitmap `00 FF 77 5A EA 67 0E 20 07` `[CAPTURE]`. It enables every command the app uses, not `0x0422` / `0x012A`.
- OPPO Enco Buds2 (model entry `064810`, reported product id `060C12`): 4-byte bitmap `00 9B 2C 50 80` `[CAPTURE]`. It offers `02 01` and sends no `0x8205`. A long `0x010D` list dropped its link (issue #5). `02 05 0D` kept the link up `[CAPTURE]`.
- OnePlus Nord Buds 3 Pro `064414`: offered codes `00 02 02 01` (wear, battery) `[CAPTURE]`. It sent no `0x8205` when asked for `01 02 03 04 08 0B`. It pushed an ANC change once without `03`.
- OnePlus Buds 4 `065414`: `0x8205` ack `01 02 01 00 02 00`; for five ids `01 05 01 00 02 00 03 00 04 00 08 00` `[GUESS]`.
- OPPO Enco Buds3 Pro `06A850`: the bitmap has no `0x012A`, but the status reply lists `1B` and reports `1B=1` after a write (reports #60 #61) `[CAPTURE]`. Its `statusQuery` is `05 11 06`.
- OnePlus Buds 4 `065414`: `statusQuery` `05 04 0B 11 18 06 1B 1D 1C` `[CAPTURE]`. The buds answered `00 08 05 00 04 00 0B 01 11 01 18 01 06 00 1B 00 1D 01` (no `1C`) `[CAPTURE]`.
- OPPO Enco Buds2 (model entry `064810`, reported product id `060C12`): `statusQuery` `05 06` `[TESTED]`.
- realme Buds T310 `063C12`: the status reply uses status `01` for ids `04 0B 18 27 35`, so the buds lack them `[CAPTURE]`.
- A model without `statusQuery` (hand-added): 23 ids `05 04 0B 11 13 18 06 1B 1C 27 28 1D 09 17 30 31 3A 32 35 37 38 3B 0C`, plus `1A` where it has `windMode` `[VENDOR]`.
- The Enco X4 `06EC10` and Buds Pro 4 `067814`: `wearDetection` 2. Their `statusQuery` id list starts `05 04`, so the app asks for `04`.
- The `statusQuery` lists come from 85 HeyMelody models (HeyMelody 116.9.0). All 85 match the HeyMelody 117.6.6 id rule. The lists leave out `22`-`24`, `3C` and `3D` `[VENDOR]`.
- 30 realme models have a `statusQuery` with only the ids of the rows their realme Link screen shows: `04`, `06`, `0B`, `11`, `18`, `1B` `[VENDOR]`.
- realme models with `dualDevice` (11 models): bitmap bit 59 `0x0429` / `0x0132`. Buds 4 lacks it `[VENDOR]`.
- Models with `personalAnc` (11 models): bitmap bit 26 `0x0412` / `0x011A`. Buds 4 lacks it `[VENDOR]`.
- OnePlus Buds 4 `065414`: HeyMelody parity hides Power saving and Personalised ANC, because its list does not ask for `17` or `0C` `[VENDOR]`.

### 21.3 ANC

- Buds 4 style: with or without Adaptive `11`, with or without Medium `[VENDOR]`.
- Buds 4 `065414` tree: `Off 0`, `ANC 1`, `Transparency 2`, `Deep 4`, `Medium 5`, `Light 6`, `Smart 7`, `Adaptive 11` `[CAPTURE]`. Off has a report child at 3. Transparency has one at 8.
- Tree shape `NC 0, Off 1, Transparency 2` (9 models) `[VENDOR]`: Buds 4's Off bytes turn ANC on there `[VENDOR]`.
- Tree shape `Off 0, Transparency 1, Light 2, Deep 3` (Enco X `061410`, Buds Z2 `061014`), plus `Smart 4` on Buds Pro `060C14` `[VENDOR]`.
- A model with no `ancModes` (79 of 143 in `models.json`) gets no noise control. Before detection, the app uses the Buds 4 tree `[VENDOR]`.
- The app does not offer voice-transparency modes that need a per-bud support read (4 OPPO models) `[VENDOR]`.
- realme models `[VENDOR]`: the write and report tables are the same. Off `01`, Transparency `02`, Light `04`, Deep and plain ANC `08`, Medium `10`, Smart `20`. As a tree: Off 0, Transparency 1, ANC 3 with children Light 2, Deep 3, Medium 4, Smart 5. Only the levels a model has. T200 `065452`, T300 `062412`, T500 `067052`, T200x `065852` and Wireless 3 `050812` have plain ANC. N1 `064012` and T310 `063C12` have no Smart. 21 realme models have this tree in `models.json`.
- realme Buds Air7 Pro `064C12`: the owner confirms the table works (issue #8) `[TESTED]`. The others are unverified. Its `0x810C 01 01` value `02` = Transparency.
- OnePlus Buds Pro `060C14`: shows `3` / `4` / `7` as one "ANC" option `[VENDOR]`. Its bit is the current level's, or Smart's (bit 4) outside ANC.
- Buds Z2 `061014` and Enco X `061410`: list strong (4) and weak (3) as their own cycle options `[VENDOR]`.
- OnePlus Buds 4 `065414`: Smart SET bit 7 is acked and pushed as `0x0080` `[CAPTURE]`. Right after, the buds push `03 04 01 20 00` and `03 04 01 40 00` `[CAPTURE]`.
- OnePlus Buds 4 `065414`: no bud in an ear refuses a SET with status `0E` `[CAPTURE]`. One bud is enough.
- realme Buds Air7 Pro `064C12`: its hold cycle is `0A` = ANC + Transparency. The cycle is one mask for both buds: write `0x0404 02 01 <mask>`, read `0x010C 02 01`. Our key `sharedHold` keeps type 1 on a per-bud hold `[VENDOR]`.
- realme hold models: `holdMask` 8833 (Air7 Pro). The mask is one setting for both buds.
- OnePlus Buds 4 `065414`: hold mask `0x0007` before Adaptive, `0x0807` after `[CAPTURE]`.
- OnePlus Buds 4: `0x0021` was acked `00` and read back `0x0001`; level bits are dropped silently `[CAPTURE]`.
- Enco X `061410`, Buds Z2 `061014`, Buds Pro `060C14`: Smart is bit 4 where the model offers it.

### 21.4 Gestures

- Buds 4 `065414` table (`act:fn`) `[CAPTURE]`: left/`01` `01:00 02:11 03:00 04:08 06:00 05:07`; left/`06` `02:00 03:00 06:00`; right/`01` `01:00 02:00 03:00 04:08 06:00 05:07`; right/`06` `02:00 03:00 06:00` `[CAPTURE]`.
- Buds 4 `065414` call control `[CAPTURE]`: double tap `0401 01 04 06 02 1D`, long hold `0401 01 04 06 06 1C`; clear with `02 00` / `06 00`. One write lands on both buds `[CAPTURE]`.
- OnePlus Buds `060414` and OnePlus Buds Z `060814`: previous and next are `04` and `05` `[VENDOR]`.
- Per-model menus: `gestures` and `callGestures` in `models.json`, one entry per row, `{gesture, options, minSelected}`. `options` is a mask: bit 1 voice assistant `03`, bit 4 play/pause `01`, bit 8/16 volume up/down `0B`/`0C`, bit 32/64 previous/next `05`/`06`, bit 128 noise cycle `08`, bit 256 favourite music `09`, bit 512 none `00`, bit 1024 volume `07`, bit 2048 switch track `0A`, bit 4096 switch devices `0D`, bit 8192 game mode `11`, bit 32768/65536/131072 collect music/zen/AI summary `[VENDOR]`.
- Buds 4 `065414`: `1:516 2:8807 3:3154531 5:3584`, hold `27`. It matches HeyMelody's menus `[TESTED]`.
- `gesture` → `act`: 1-6 are themselves. 16/17/18 = acts 1/2/3 (stem-press models). 11, 20, 27 = the hold's ANC cycle on act 4 (options = the model's top-level noise modes). 7, 8, 12-15 are text-only rows.
- Call control (all `btn 06`, `dev 04`): 28/32 = act 1, 29/33/36 = act 2, 30/34 = act 3, 31/35 = act 6. `options` 524288 = answer/end `1D`, 262144 = decline `1C`, 131072 = AI summary. A row without none (512) has one fixed option and writes nothing.
- OPPO Enco Air5 Pro `06C410`: the `F1` gesture report carries button id `06` `[VENDOR]`.
- realme models `[VENDOR]` (realme Link, issue #8), wired: per bud, double tap, triple tap and hold, the same `fn` bytes, `dev` 1 / 2, `btn 01`. The Air7 Pro's owner confirms them `[TESTED]`. The others are unverified.
- realme hold with the noise cycle: `holdMask` 8833 `[VENDOR]`. One mask for both buds: write `0x0404 02 01 <mask>`, read `0x010C 02 01`.
- Holding both buds (Air 3S `061012`, T01 `064412`, T110 `062812`, T200 Lite `065052`, T200x `065852`): one entry `04 01 04 <fn>`, game mode `11` or none. The buds read it back as its own `dev 04` entry. Our key `bothHold` shows it as a gesture row. The app writes it like an on-call row. Not offered: volume on the noise-cycle hold (T500 `067052`, Air8 `066812`, T200x `065852`), AI options.
- realme neckbands (Wireless 3 Neo `050C12`, 5 ANC `051412`, 6 `051C52`, 6 ANC `052052`, 6 Neo `051812`): one button written as the left bud, `01 01 <act> <fn>`. Act 1-4 (single, double, triple, hold). Our key `oneButton` hides Left / Right. The app does not offer the noise button of the two ANC ones (`01 04 01 08`, single press = the noise cycle).
- Per-bud hold (`holdMask`, 28 models) `[VENDOR]`: options 512 none, 128 noise cycle, 1 voice assistant, 8192 game mode. Each bud is written alone (`dev` 1/2, `act 04`). The cycle mask per bud uses type 3 / 4, read with `0x010C 02 03` / `02 04`.
- Minimum modes in the cycle: `minSelected`, else 2 on OnePlus models and per-bud holds, else 1 (Buds 4: 1) `[VENDOR]`.
- Top-level ANC levels: Buds Z2 `061014` and Enco X `061410` list strong (4) and weak (3) as their own cycle options. OnePlus Buds Pro `060C14` shows 3 / 4 / 7 as one "ANC" option.

### 21.5 Feature switches

- OnePlus Buds 4 `065414`: `statusQuery` `05 04 0B 11 18 06 1B 1D 1C`. The buds answered all but `1C` `[CAPTURE]`.
- OPPO Enco Buds2 `064810`: `statusQuery` `05 06` `[TESTED]`.
- realme Buds T310 `063C12`: lacks ids `04 0B 18 27 35` (status `01`) `[CAPTURE]`. It has `windMode`.
- realme Link rows: dynamic sound `1D`, enhance voice `09`, wind noise `1A`, game mode `06`, wear `04`, dual `11`, Hi-Res `18`, 3D sound `1B`; a second enhance voice setter writes `0C`; power saving is reported as `05` `[VENDOR]`.
- Models with `windMode`: `0x010D` asks `1A` only for them. realme Buds Air7 Pro `064C12` and T310 `063C12` have the wind-noise row in realme Link. Report #46 says the vendor app shows it `[VENDOR]`. No capture proves that every `windMode` model answers `[GUESS]`.
- realme models with a hearing profile `[VENDOR]`: Air8 Pro `066C12`, Air7 Pro `064C12`, Air 5 Pro `061C12`, Air6 Pro `063012`. The T500 Pro `066452` does not list one (report #21), and its buds do not answer the tones.
- OnePlus Buds 4 `065414`: refuses `18 01` 32 times while the phone plays AAC; accepts it on LHDC V5 `[CAPTURE]`.
- OPPO Enco Buds3 Pro: `1B` is in the status reply and reports `1B=1`, but `0x012A` is absent; two owners hear no 3D audio (reports #60 #61). The row is hidden through `statusQuery` `05 11 06` `[CAPTURE]`.
- Enco X4 `06EC10` and Buds Pro 4 `067814`: `wearDetection` 2 `[VENDOR]`.
- 85 HeyMelody models match the 117.6.6 id rule `[VENDOR]`. The lists leave out `22`-`24`, `3C`, `3D` `[VENDOR]`.
- 30 realme models: the `statusQuery` asks only for `04`, `06`, `0B`, `11`, `18`, `1B` `[VENDOR]`.
- `06` is game mode on every model except where the bitmap has `0x0423`; there it is `28`. Both default lists ask `06` and `28` next to each other `[VENDOR]`.
- `1E` log collection: asked on 16 models. `0F` zen mode: asked on 6 OnePlus and OPPO models. `13` sound record: asked on 5 models. `16` lost reminder: asked on 2 models (OPPO Enco Clip2 `06E010`, OnePlus Buds Clip `06E410`) `[VENDOR]`.
- `28` game mode main switch: 10 models `[VENDOR]`.

### 21.6 Equalizer

- Buds 4 `065414`: built-ins 0 Balanced, 1 Clear Vocals, 2 Bass `[VENDOR]`. Current EQ `00`-`02` built-in, `04`-`06` custom `[CAPTURE]`.
- Nord Buds 2r `062C14`: built-ins 0, 1 Bold, 2 `[VENDOR]`.
- Types 1-4 are named differently where `eqKind` is `2` (or Enco R `063010` / Air2 `063410`) `[VENDOR]`.
- 41 of 143 models have no built-in presets `[VENDOR]`.
- realme: 28 models have four built-ins, not in HeyMelody's list. `0x0406` ids: 0 Pure Raw, 1 Deep Bass, 2 Pure Voice, 3 Pure Bass. `models.json` uses types 101-104, `eqKind` 1 `[VENDOR]`.
- realme T110 `062812` and T01 `064412`: three other presets with unknown ids. No EQ until a capture `[VENDOR]`.
- Firmware gate: drop an entry whose `minFirmware` is above the buds' firmware (the lower non-zero of left and right; `138` on Buds 4; unreadable = 0). Use `eqPresetsCompat`, else `eqPresetsByFirmware`, else `eqPresets`. Stable sort by `order` (0 if absent) `[VENDOR]`.
- 10 models use the 10-band custom EQ `[VENDOR]` `31 62 125 250 500 1k 2k 4k 8k 16k`. The others use 6 bands `62 250 1k 4k 8k 16k`. `customEqLimit` defaults to 3 `[VENDOR]`.

### 21.7 Codecs, spatial sound and Hi-Res

- Models with `hiRes` and `0x0123` `[VENDOR]`: Enco X2 `063C10`, Enco Air3 Pro `065C10`, Enco Free3 `066010`. They use the codec picker `0x041A` `[VENDOR]`.
- Head tracking (`spatialModes` 2) `[VENDOR]`: Buds Pro 2 `062014`, Buds Pro 3 `064014`, Enco X3 `067410` `[VENDOR]`.
- OnePlus Buds 4 `065414`: `0x0114` = `00 08` (LHDC V5) `[CAPTURE]`; `0x8123` = `00 46 01` (AAC, LDAC, LHDC). The maker lists LHDC 5.0, AAC and SBC only. The mask is trusted only on `hiRes` models `[CAPTURE]`.
- OnePlus Buds 4 `065414`: phone `18=1`, LHDC V5; PC `18=0`, AAC, at the same time `[CAPTURE]`.
- realme models with 3D sound: `spatialToggle` 1 `[VENDOR]`. The Air7 Pro `064C12` lists `0x012A` / `0x0422` but leaves `0x012A` unanswered. 3D sound works on it (issue #9) `[VENDOR]`. The others are unverified.
- realme Link never writes `1B` with `18`; on some models it only warns `[VENDOR]`.
- Older models accept `18` on AAC: realme Buds Air 5 Pro `061C12` (report #4), T500 Pro `066452` (report #21), Air7 Pro `064C12` (report #27), OnePlus Nord Buds 3 Pro `064414` (report #50) `[CAPTURE]`. The Nord Buds 3 Pro offers only SBC and AAC. The switch turns on, but the sound does not change (report #40).

### 21.8 Battery, wearing and the case

- realme Buds Air7 Pro `064C12`: sends the case at level 0 with the lid closed (issue #9) `[CAPTURE]`.
- No in-ear sensor (report #46, T200 `065452`, T200x `065852`, Nord Buds 3r `065854`): answer `1` (out) in every state; `0` or `4` in the case `[CAPTURE]`. The app shows `1` and `5` as in the ear with no wear label until the buds report `3` or `7` once. It remembers this per model.
- The vendor's `wearDetection` flag does not tell whether a model has the sensor. The Bullets Wireless Z3 `051014` and the Buds Ace `062814` lack it and report `3` `[CAPTURE]`.
- OnePlus Buds 4 `065414`: case and a bud inside report only with the lid open `[CAPTURE]`; closed lid returns only the outside bud `01 01 5A` `[CAPTURE]`.

### 21.9 Game mode

- OnePlus Buds 4 `065414`: game-mode push `05 <state>` `[CAPTURE]`.
- `062410`, `062810`, `063010`, `063410`: game-mode push `05 <x> <x> <state>` `[VENDOR]`. The state is in byte 3, not byte 1.

### 21.10 Golden Sound, fit test, personalised ANC

- OPPO Enco Air5 Pro `06C410`: refuses an ear-scan start (`0x040D 04 01 <id>`) with status `01` on Windows (6 times, report #44) `[CAPTURE]`. The cause is unknown. The wear state is a guess.
- OnePlus Buds 4 `065414`: fit test takes about 8.6 s and returns `04 01 01 02 01` `[CAPTURE]`.
- `hearingScan` models `[VENDOR]`: the ear scan is offered only where the list has it.
- `personalAnc` models (11) `[VENDOR]`: `0x0412` / `0x011A`, bitmap bit 26. Buds 4 `065414` lacks it `[VENDOR]`.
- realme models with a hearing profile: Air8 Pro `066C12`, Air7 Pro `064C12`, Air 5 Pro `061C12`, Air6 Pro `063012`. The T500 Pro `066452` does not list one (report #21), and its buds do not answer the tones.

### 21.11 Dual connection

- Models with `dualDevice` (11): bitmap bit 59 `0x0429` / `0x0132`. Buds 4 `065414` lacks it `[VENDOR]`.
- OnePlus Buds 4 `065414`: after off, `0x0413 08 00 01`; after on, `08 00 00` `[CAPTURE]`. `08 00 01` = auto switch on.

### 21.12 Counts

- `ancModes`: 79 of 143 models in `models.json` have none `[VENDOR]`.
- `holdMask`: 28 models use a per-bud hold `[VENDOR]`.
- `eqPresets`: 41 of 143 models have none `[VENDOR]`.
- `statusQuery`: 85 HeyMelody models, 30 realme models `[VENDOR]`.
- `dualDevice`: 11 models. `personalAnc`: 11 models `[VENDOR]`.
- `customEqBands` 10-band: 10 models. `customEqLimit` default: 3 `[VENDOR]`.
- `hearingScan`: the ear scan exists only where the list has it.

**Code:** `protocol/ModelCatalog.kt`, `protocol/AncModes.kt`, `protocol/RowRules.kt`,
`protocol/Gestures.kt`; `core/src/cmd.rs`.

---

## 22. Not built

QuickBuds talks to the network only to check GitHub for updates and to send reports to the Worker. These commands belong to features that need the vendor's servers
or account, or that QuickBuds decided against. The app has no UI for them.

| Command | Feature |
|---|---|
| `0x0425`, `0x0426` | AI summary type, AI prompt sound |
| `0x0428`, push `F9` | AI translation |
| `0x041C` | Account key (`01 01 <len> <key>` binds, `02` clears) |
| `0x0410`, `0x0417`, push `11`, push `0A` | Tone and zen-mode files |
| `0x03xx` | Firmware upgrade |
| `0x040F`, `0x0501`, `0x0502` | Camera state and shutter |
| `0x0421` | Spine-health range |
| `0x042E` | Multi-conversation switch `[GUESS]` |
| `0x0424` | LE Audio action |
| push `10`, features `14` `19` `39` | Voice wakeup, voice commands, incoming-call voice control |

Some commands share a bitmap bit with the features above; others are always enabled. No QuickBuds feature
reads them. The table below gives their wire layouts. All rows are `[VENDOR]`; a request that the Buds 4
batch carries is also `[CAPTURE]`. u16 and u32 are little endian unless marked. QuickBuds sends none of them.
The queries and writes in this table are unread. Their meanings are `[GUESS]` where a row says so.
The writes are `0x0408`, `0x0414`, `0x0419`, `0x041D`, `0x041F`, `0x042B`, `0x042C`, `0x0430`,
`0x0432`-`0x0435`. The queries are `0x0117`, `0x0125`-`0x0127`, `0x0129`, `0x012E`, `0x0135`, `0x0136`.

| Command | Payload | Reply | Meaning |
|---|---|---|---|
| `0x0014` | — | — | Bitmap bit 60. No sender or reader in the vendor app. The feature is voice wakeup. |
| `0x001C` | — | — | Bitmap bit 45. Capability bit in both apps; no sender or parser. Unknown. |
| `0x0022`-`0x0024` | — | — | Bitmap bit 40, the spine-health group. Capability bit in both apps; no sender or parser. Unknown. |
| `0x0101` | `00 02` (u16 512) | `00 <mtu u16>` | Remote MTU. In the Buds 4 batch. |
| `0x0102` | `FF FF` (65535), or `9A 07` (1946) from another app | `00 <vendor id u16>` | Remote vendor id. In the Buds 4 batch. |
| `0x0104` | — (the buds send it) | `00 <UTF-8 version>` | Remote protocol version. Always enabled. The vendor app reads the string and discards it. |
| `0x010B` | — | `00 <colour id>` | Buds colour id. In the Buds 4 batch. |
| `0x0117` | `<count> <types>`; the vendor asks `03 02 03 04` | `00 <count>` then `<type> <len u16> <value>` | Earphone identity. Type `01` brand, `02` left SN, `03` right SN, `04` box SN, `05` model, `06` product date. |
| `0x0118` | `<count> <ids>` | `00 <count>` then `<type> <len u16> <data>` | Ear-restore data. In the Buds 4 batch as `01 01`. |
| `0x011C` | — | `00 <flags u16>` then the push `0D` fields in order | Related-device info. The query side of push `0D`. In the Buds 4 batch. |
| `0x0121` | — | `00 <count>` then `<id u32> <valid> <crc u32> <selected> <nameLen> <name>` | Tone list. In the Buds 4 batch. |
| `0x0125` | — | `00 <kind> <count>`, then `<count>` × 3 bytes | Hearing-enhancement data. Each 3-byte entry is one hearing value. |
| `0x0126` | `<start u32> <end u32>` | `00` then the push `09` stream | Hearing-enhancement frequency data for a time range. |
| `0x0127` | — | `00 <delay>` | Screen-off broadcast delay. |
| `0x0129` | — | `00 <file id u32> <file well u32> <name>` | Zen-mode file information. |
| `0x012E` | — | `00 <AI summary type>` | AI summary type. |
| `0x0131` | — | `00 <status>` | AI translation app status. |
| `0x0135` | — | `00 <bits>`: bit 0 type 0, bit 1 type 1, bit 2 type 2 (highest set bit wins), bit 7 the in-ear music mute flag | Voice-assistant config. |
| `0x0136` | — | `00 <bits>`: bit 0 state, bit 1 owner, bit 2 reset | LE/BR link switch state. |
| `0x0402` | — (the buds send it) | `00` from the phone | Free-music request. The phone answers `0x8402 00`; the request has no payload field. |
| `0x0407` | — | — | No sender and no reply handler in the vendor app `[GUESS]`. |
| `0x0408` | `<hostType> <host MAC 6> <count>`, then `<type> <MAC 6> <state>` per device; at most 6 devices | — (unhandled) | Related-device list. |
| `0x0409` | — | — | No sender and no reply handler `[GUESS]`. |
| `0x040B` | — | — | No sender and no reply handler `[GUESS]`. |
| `0x0414` | `<time>` | `00` | Free-dialog recovery time. |
| `0x0416` | — | — | No sender and no reply handler `[GUESS]`. |
| `0x0419` | `<bits>` | `00` | Host capabilities. |
| `0x041D` | `<delay>` | `00` | Screen-off broadcast delay write. |
| `0x041F` | `<len> <UTF-8 model>` | `00` (not parsed) | Device build model. |
| `0x042B` | `<type> <data>` | `00 <type>`; when type is `03`, `<flags> <rate type> <format> <channels-1> <encode> <frame size u16> <session> <VAD>` | One-shot voice operation. |
| `0x042C` | `<on> 00` | `00` | Start or stop the sound record. |
| `0x0430` | `<status>` | — | Speech-assist dialog status. The vendor app drops the reply. |
| `0x0432` | `<type>`: `01`, `02` or `04` | `00` | Voice-assistant control type. The caller maps 0, 1, 2 to those bits. |
| `0x0433` | `<on>` | `00` | LE/BR link switch. |
| `0x0434` | `<value>`: `01` is off | `00` | Earbud control sound channel. The vendor app inverts the caller's flag. |
| `0x0435` | `00` | `00` | Start voice-assistant config. |
| `0xEF01`-`0xEF09` | — | — | Capability-only bits (41-43, 48, 52, 63-65). `EF08` gates the game-mode main switch and `EF09` gates voice commands. No sender or reader. |

- `0x01F0` and `0x01F2` are not commands. Each is a command byte welded to its Seq. Battery is `0x0106`, wearing `0x0109`.
- `0x0407`, `0x0409`, `0x040B` and `0x0416` have no sender and no reply handler in the vendor app.
- The vendor app does not send `0x042B`, `0x042C`, `0x0430` or `0x0434` behind a bitmap bit. They are not gated by the handshake.
- The `0x03xx` group is the firmware upgrade. QuickBuds does not use it.

**Code:** `protocol/Protocol.kt` (command ids), `protocol/Capabilities.kt`; `core/src/msg.rs`.

---

## 23. Log lines

| Line | Meaning |
|---|---|
| `RX:` / `TX[...]:` | Raw hex |
| `BTN EVT:` | `F1` push (section 15) |
| `ANC EVT:` / `GAME EVT:` | ANC / game mode push |
| `WEAR EVT:` / `WEAR QRY:` | Wear push / reply |
| `KEYFN:` | Gesture table, grouped per `dev/btn` |
| `FEATURES:` | `0x810D` reply |
| `UNATTR RX:` | A frame we do not decode |
| `ancFlush=` | Whether an ANC write just went out |

- The gesture table prints in three places, all ending `RAW=[...]`: the `RX:` line, `KEYFN:`, and `LogDecoder`.
- **`UNATTR RX` never shows `0x0204`** (`noteUnattributed()` excludes it). An undecoded push prints nothing there. Dev tools' decoder does show it (`Unknown active report`, in amber). Change the exclusion first if a capture needs these.

**Code:** `protocol/LogDecoder.kt`, `protocol/SimpleLog.kt`.

---

## 24. Open questions

- Hold cycle mask: bit 1 alone has never been cleared (section 14).
- Push `F1` `act 00`; `F3` (HeyMelody ignores both).
- The recurring `F1` family `AA 0D 00 00 04 02 FF 06 00 F1 01 01 XX YY 02`, and `02 01 08 0C 02` / `02 01 07 0B 02` (non-multiples of ten; maybe a fine battery field). **Do not guess these from a few samples.**
- `0x0404` with a level form `01 02 <level>` `[GUESS]`. Unverified.
- Switch `05`: HeyMelody never uses its value.
- Other models' ANC bits, gesture writes and `[VENDOR]` features: unverified until an owner reads a write back.

**Code:** `protocol/Commands.kt` (hold mask, level form), `protocol/Anc.kt`, `protocol/Gestures.kt`; `core/src/msg.rs`.
