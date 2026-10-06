# PROTOCOL.md — the earbud wire format

The OPPO / OnePlus / realme earbud RFCOMM protocol as QuickBuds uses it. Current knowledge only;
the history of how it was found is in git.

**Evidence tags.** Every claim carries one:
`[VENDOR]` HeyMelody's behaviour, or realme Link's where a line says so (studied for interoperability only) ·
`[OSS]` a public project from the README's Credits ·
`[CAPTURE]` seen on our own device (captures in `local/logs/`, on the developer's machine) ·
`[USER]` the developer's report · `[GUESS]` unverified.
Never promote a claim without evidence. Never remove a `[GUESS]` tag to tidy up.

**Test device:** OnePlus Buds 4, firmware `B4.1-260810-1153` (HeyMelody shows `138.138.105`). Other models differ in feature ids, button
ids and ANC bits. "Wired, unverified" means the app sends it but no owner of that model has read a
write back.

**Update this file in the same commit as the code change.** Keep section numbers stable: code and
CLAUDE.md cite them.

---

## 1. Transport

Classic Bluetooth **RFCOMM / SPP**, not BLE. One long-lived socket; the buds push events at any time.

| UUID | Use |
|---|---|
| `0000079A-D102-11E1-9B23-00025B00A5A5` | Tried first. The one that works on Buds 4 `[CAPTURE]`, as HeyMelody does `[VENDOR]` |
| `00001107-D102-11E1-9B23-00025B00A5A5` | Tried second. Never connects on Buds 4 (~5 s timeout); kept for other models |

A case lid close kills the socket (§8).

---

## 2. Frame format

```
AA  <TotalLen>  00 00  <Cmd LE>  <Seq>  <PayLen LE>  <Payload...>
1B  LEB128      2B     2B        1B     2B           PayLen bytes
```

| Field | Meaning |
|---|---|
| `AA` | Header |
| TotalLen | Length of everything after this field: `7 + PayLen` |
| `00 00` | Reserved |
| Cmd | Command id, little-endian |
| Seq | `0x01`-`0xFE`; the reply echoes it. **`0xFF` = a push from the buds** `[OSS]` |
| PayLen | Payload length, little-endian |

- **TotalLen is standard LEB128** `[CAPTURE]`: one byte below 128, else 7 bits per byte, low first,
  high bit = more. The `[OSS]` "subtract 1 first" is wrong: a 124-byte payload goes out as `AA 83 01`
  (131), a 250-byte reply arrives as `AA 81 02` (257). Golden Sound (§9) uses frames over 127 bytes.
- `OpoProtocol.buildPacket()` writes it. `OppoPacketFramer` reads it and normalises every frame to a
  one-byte length, so after the framer the header is a fixed 9 bytes: **cmd at 4-5, payload from
  index 9** (`BudsConnectionManager.payloadOf()`). A wrong offset gives garbage, not an error.
- Replies are **`cmd | 0x8000`** `[OSS]`. Write acks (`0x84xx`) carry `00` for success `[CAPTURE]`.
  **An ack of `00` does not prove the write took** (§5 hold mask); read back.

```
AA 07 00 00 00 01 01 00 00                  handshake 0x0100, PayLen 0
AA 0A 00 00 04 04 06 03 00 01 01 01         set ANC Off
AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00   ANC push (Seq FF)
```

---

## 3. Command map

Details in the sections cited. `—` = empty payload.

### Queries (`0x01xx` → `0x81xx`)

| Cmd | Name | Request → reply | § |
|---|---|---|---|
| `0x0100` | Handshake | — → command bitmap | 4 |
| `0x0103` | Product id | — → `00 <3 bytes LE>` | 4 |
| `0x0105` | Firmware version | — → `00 <count> <triples>` | 4 |
| `0x0106` | Battery | — | 7 |
| `0x0108` | Gesture table | — → `<status> <count> <entries>` | 6 |
| `0x0109` | Wearing | — | 8 |
| `0x010C` | ANC state / hold cycle | `01 01`, `02 01`/`02 03`/`02 04`, `04 01` | 5 |
| `0x010D` | Feature switches | `<count> <ids>` | 9 |
| `0x010F` | Current EQ | — → `00 <id>` | 9 |
| `0x0112` | Dual connection device list | — | 9 |
| `0x0114` | Current codec | — → `00 <codec>` | 9 |
| `0x0115` / `0x011E` | Golden Sound active record | — | 9 |
| `0x0116` / `0x011F` | Golden Sound filters | see §9 | 9 |
| `0x011A` | Personalised ANC result stored? | — → `00 <exist>` | 9 |
| `0x0122` | Custom EQ list | — | 9 |
| `0x0123` | Codecs offered | — → `00 <u16 LE mask>` | 9 |
| `0x0124` | BassWave level | — → `00 FB 05 <level>` | 9 |
| `0x012A` | Spatial type | — → `00 <type>` | 9 |
| `0x012B` | Game sound type | — → `00 <selected> <count> <types>` | 9 |
| `0x0130` | Alert-sound volume | — → `00 <level>` | 9 |
| `0x0132` | Preferred device | `02` | 9 |
| `0x0133` | Tap sensitivity | — → `00 <level> <default>` | 9 |
| `0x0134` | Head gesture mapping | — (reply logged raw) | 9 |

### Subscriptions and pushes (`0x02xx`)

| Cmd | Name |
|---|---|
| `0x0200` | Query broadcast codes → `0x8200` (§4) |
| `0x0205` | Subscribe → `0x8205` ack (§4) |
| `0x0204` | **Push**, Seq `0xFF`, `payload[0]` = event code (table in §4) |

### Writes (`0x04xx` → `0x84xx`)

| Cmd | Name | Payload | § |
|---|---|---|---|
| `0x0400` | Find my earbuds | `01` / `00` | 9 |
| `0x0401` | **Gesture write** | `<count> [dev, btn, act, fn]...` | 6 |
| `0x0403` | Feature switch | `<id> <01/00>` | 9 |
| `0x0404` | ANC mode / hold cycle | `01 01 <mask>` / `02 <type> <mask>` | 5 |
| `0x0405` | Fit test | `01` / `00` | 9 |
| `0x0406` | Select built-in EQ | `<id>` | 9 |
| `0x040D` / `0x040E` / `0x0411` / `0x0415` | Golden Sound | see §9 | 9 |
| `0x0412` | Personalised ANC test | `01` / `02` / `03` | 9 |
| `0x0413` | After a dual connection toggle | `08 00 <xx>` | 9 |
| `0x0418` | Custom EQ create / save / delete | see §9 | 9 |
| `0x041A` | Set codec (`highAudio` models) | `<codec> <hiRes> 00` | 9 |
| `0x041B` | BassWave level | `FB 05 <level>` | 9 |
| `0x0422` | Spatial type (bitmap `0x012A` models) | `<type>` | 9 |
| `0x0423` | Game sound type | `<type> 01` | 9 |
| `0x0427` | Alert-sound volume | `<level>` 1..10 | 9 |
| `0x0429` | Device manager / preferred device | see §9 | 9 |
| `0x042D` | Tap sensitivity | `<level>` 1..5 | 9 |
| `0x0431` | Head gesture mapping | `<type>` | 9 |

### Other pushes

| Cmd | Name |
|---|---|
| `0x0500` / `0x0501` | Time request (§9, answered) / bud state |
| `0x0504` | EQ mode changed: `<id>` (§9) |
| `0x0510` | Spatial type changed: `<type>` (§9) |

**Not commands:** `0x01F0` / `0x01F2` are a command byte welded to its Seq. Battery is `0x0106`,
wearing `0x0109`.

---

## 4. Connection

### Init sequence `[CAPTURE]`

`BudsConnectionManager.runInitSequence()`: 300 ms before the first frame, then 200 ms apart.

```
0x0100 handshake → 0x0103 product id → 0x0200 broadcast codes → 0x0205 subscribe →
0x010D features → 0x010C 01 01 ANC → 0x0106 battery → 0x0109 wear →
0x0108 gesture table → 0x010C 02 01 hold cycle
```

Nothing is polled after that: battery, wear, ANC and game mode are pushed. The periodic "keep-alive"
poll of the OSS sources was dropped with no stale link seen `[USER]`. If the early steps ever look
disturbed, suspect the last two (they were added last).

### What the buds are and accept

- **`0x8100` (handshake reply) is a command bitmap** `[VENDOR]`: `00`, then bytes read LSB first; bit n
  enables a fixed list of commands (bit 3 = `0x0108` `0x0401` `0x0416`; bit 8 = `0x010C` `0x0404`;
  bit 57 = `0x0427` `0x0130`; 67 bits). Always allowed without a bit: `0x0100`-`0x0104`, `0x0106`,
  `0x010B`, `0x010D`, `0x0F00`, `0x0F03`, `0x0F04`. HeyMelody sends nothing else, and neither do we
  (`protocol/Capabilities.kt`). Buds 4 `[CAPTURE]`: `00 FF 77 5A EA 67 0E 20 07` — every command the
  app uses, not `0x0422` / `0x012A`.
- **`0x8103` product id:** `00` + 3 bytes LE. Buds 4: `00 14 54 06` = `065414` `[CAPTURE]`. Four colour
  ranges fold into one id `[VENDOR]`+`[OSS]`: `100100`-`100102` → `060414`, `100200`-`100202` →
  `060814`, `108100`-`108102` → `068414`, `108200`-`108202` → `068814` (`ModelCatalog.normalise`).
- **Model lookup** `[VENDOR]` (`ModelCatalog.find`): entries in `assets/models.json` whose `name`
  equals the Bluetooth name exactly, and those whose `id` equals the product id. An entry matching
  both wins, then the first name match, then the first id match. A model picked by hand overrides it
  until other buds connect. Name before id matters: OPPO Enco Buds2 report `060C12`, the realme Buds Q2s
  id (issue #5) `[CAPTURE]`; a renamed device matches no name and falls back to its id.
- **`0x8105` firmware** `[CAPTURE]`: `00 <count>` then UTF-8 `deviceType,versionType,version` triples.
  Buds 4: `00 04` + `1,2,138,2,2,138,3,1,01,3,2,105`. HeyMelody shows `138.138.105`: the `versionType` 2
  versions in reply order, joined by dots (`OpoProtocol.firmwareVersion()`). Device types 1 / 2 / 3 are
  probably left / right / case `[GUESS]`.
- **`0x810D` lists only the switches the firmware has** (§9). A missing id = no such switch
  `[CAPTURE]`+`[OSS]`.

### Batch query — `0x012F` `[CAPTURE]`

HeyMelody on the Buds 4 (2026-10-06): after `0x0100`, `0x0200` and `0x0205` it sends **one `0x012F`
frame carrying 24 queries**, not the queries one by one. Bitmap bit 56; buds without it (Enco Buds2:
4-byte bitmap) get the queries separately. We do not send it.

- Request payload: `<count>`, then per query `<cmd LE> <len LE> <payload>`. Buds 4 (`0D 01 0A 00 …` =
  `0x010D` with 10 bytes): `0102 FFFF`, `0106`, `010B`, `0103`, `0101 0002`, `0114`, `0105`, `0107`,
  `0108 020301`, `010C 0201`, `010C 0101`, `010C 0301`, `010C 0401`, `010F`,
  `010D 09 05 04 0B 11 18 06 1B 1D 1C`, `0121`, `0123`, `0118 0101`, `011C`, `0115`, `011E`, `0122`,
  `0105`, `0109`.
- Replies: several `0x812F` frames with the request's seq, each `00 <count>` then per answer
  `<cmd LE> <len LE> <normal reply payload>` (here 1 + 21 + 2 = 24 answers).
- **HeyMelody's `0x010D` id list for the Buds 4 is `05 04 0B 11 18 06 1B 1D 1C`**; the buds answered
  `00 08 05 00 04 00 0B 01 11 01 18 01 06 00 1B 00 1D 01` (no `1C`). It does not ask for `17`, `0C`,
  `09`, `30`-`3B`. OppoPodsManager's model list predicts `0D` and no `06` for this model, so its
  `function` maps are not HeyMelody's current server list.

### Broadcast codes (`0x8200`) and pushes

`0x8200` = `00 <count> <codes>`. Buds 4 `[CAPTURE]`: `00 09 01 02 03 04 08 0B F1 F2 F3`.
**Read it before deciding an event is unsupported**: silence usually means "not subscribed".

| Code | `0x0204` push | Subscribed |
|---|---|---|
| `01` | Battery (§7) | always |
| `02` | Wearing (§8) | always |
| `03` | ANC mode (§5) | always |
| `04` | Fit test result (§9) | when the bitmap has `0x0405` |
| `05` | Game mode (`GAME EVT`) `[CAPTURE]` | — |
| `06` | Dual connection device list (§9) `[CAPTURE]` | — |
| `08` | Golden Sound test status (§9) | when the bitmap has `0x040D` |
| `0B` | Personalised ANC result (§9) | when the bitmap has `0x0412` |
| `0E` | Golden Sound ear scan result (§9) | pushed, not in the list |
| `F1` | Gesture fired (§6) | `[OSS]` debug channel |
| `F2` | Connected-devices info `[VENDOR]`, acked (§9) | no |
| `F3` | `[OSS]` debug channel | no |
| `F4` | Diagnostic JSON `[VENDOR]`, acked (§9) | pushed, not in the list |
| `F5` | Head gesture type `[VENDOR]` | — |

### Subscribing — `0x0205`

Payload **`<count> <ids...>`, count first** `[CAPTURE]`. The app asks for `01 02 03` (battery, wear,
ANC), plus `04` / `08` / `0B` as above, **kept to the ids the buds offer in `0x8200`**
(`OpoProtocol.notifyIds`, `protocol::notify_ids`). HeyMelody registers exactly the offered list
`[VENDOR]`; on buds without `0x0205` in their bitmap it sends `0x0201 <id>` once per id instead.

- **Never `01 01 02 02`**: that is "count 1, battery only". The buds ACK it and never push wear.
- **Never drop `03` when the buds offer it**: on Buds 4, without it, ANC changes made on the buds send
  nothing at all.
- Nord Buds 3 Pro (issue #2) `[CAPTURE]` offers only `00 02 02 01` (wear, battery). Asked for
  `01 02 03 04 08 0B`, it never sent `0x8205`. It still pushed an ANC change once without `03`.
- OPPO Enco Buds2 (issue #5, product id `060C12`, bitmap `00 9B 2C 50 80`) `[CAPTURE]` also offers
  `02 01`. Asked for exactly `02 01 02`, it sent no `0x8205` and answered nothing after it (not
  `0x010D`, `0x0106`, `0x0109`, `0x0105` or `0x010F`); the link dropped ~6 s later. The
  reporter's test: without `0x010D` the link stays up and wear, battery and firmware answer `[CAPTURE]`.
- The `0x8205` ack: `01 02 01 00 02 00` and, for five ids, `01 05 01 00 02 00 03 00 04 00 08 00`
  (Buds 4): `01 <count>`, then `<id> 00` per id `[GUESS]`. Fewer ids than asked = part rejected.

---

## 5. ANC (noise control)

### Per-model bits `[VENDOR]`

Each model's `noiseReductionMode` in `assets/models.json` (`protocol/AncModes.kt`) is a tree of
`{modeType, protocolIndex, childrenMode}`. modeType: `1` Off, `2` Transparency, `3` Light (weak),
`8` Medium, `4` Deep (strong), `5` noise reduction (a parent of levels, or alone), `7` Smart,
`10` Adaptive, `6` transparency with voice (**not** Adaptive, as `[OSS]` OppoPods maps it).

- **SET** sends the mode's `protocolIndex` as the bit; a level sends its child's index.
- **A report** is looked up in the same tree, parents first, then children.
- Tree shapes: Buds 4 style (with or without Adaptive `11`, with or without Medium); legacy `NC 0,
  Off 1, Transparency 2` (9 models: **Buds 4's Off bytes turn ANC on there**); `Off 0, Transparency 1,
  Light 2, Deep 3` (Enco X, Buds Z2), plus `Smart 4` on Buds Pro. Voice-transparency modes that need a
  per-bud support read (4 OPPO models) are not offered.
- A model with no `noiseReductionMode` (76 of 137) gets no noise control. Before detection, Buds 4's
  tree is used.
- **realme models** `[VENDOR]` (realme Link 5.5.514): HeyMelody lists some realme ids with no modes;
  realme Link has their data. Its write and report tables are **the same** (unlike Buds 4): Off `01`,
  Transparency `02`, Light `04`, Deep (and plain ANC) `08`, Medium `10`, Smart `20`. As a tree: Off 0,
  Transparency 1, ANC 3 with children Light 2, Deep 3, Medium 4, Smart 5 (only the levels a model
  has; T200 / T300 / T500 / T200x / Wireless 3 have plain ANC, N1 / T310 no Smart). In `models.json`
  for 23 realme models, wired, unverified (issue #8; Air7 Pro first). The Air7 Pro log's
  `0x810C 01 01` value `02` = Transparency.

### SET — `0x0404 01 01 <mask>` `[OSS]`+`[CAPTURE]`

`OpoProtocol.anc(bit)`: `bit / 8 + 1` mask bytes after `01 01`, little-endian. Buds 4:

| Mode | Bit | Payload |
|---|---|---|
| Off | 0 | `01 01 01` |
| ANC (last level) | 1 | `01 01 02` |
| Transparency | 2 | `01 01 04` |
| Deep | 4 | `01 01 10` |
| Medium | 5 | `01 01 20` |
| Light | 6 | `01 01 40` |
| Smart | 7 | `01 01 80` |
| Adaptive | **11** | `01 01 00 08` |

**Adaptive is bit 11 (mask `0x0800`), not 8.** Bit 8 gives `01 01 00 01`, a different mode. Take the
bit from the model's `protocolIndex`, never from a mode's position in a list.

### NOTIFY — push `03 01 01 <LO> <HI>` and the `0x810C 01 01` reply `[CAPTURE]`+`[OSS]`

**A different table from SET. They are not supposed to agree.** It is the same model tree reported
one level down (Buds 4's Off has a child at 3, Transparency one at 8).

| Raw (LE) | Mode |
|---|---|
| `0x0008` | Off |
| `0x0002` | ANC, generic |
| `0x0010` / `0x0020` / `0x0040` / `0x0080` | Deep / Medium / Light / Smart |
| `0x0100` | Transparency |
| `0x0200` | Transparency, voice enhance |
| `0x0800` | Adaptive |

- The ANC stop reports **whichever level is active**; it is a bitmask, not an enum.
- Query: `0x010C 01 01` → `00 01 01 <LO> <HI>` (status, echo, value). Read on every connect, so a
  reconnect corrects the display.
- `AncEventParser` accepts a push only when bytes 1-2 are `01 01`. A hold-cycle write also raises a
  subType `03` push, shaped `03 02 01 <mask>`; decoding it as a mode once stuck the display on a false
  mode.
- **Smart** `[CAPTURE]`: SET bit 7 is acked and pushed as `0x0080`. Right after, the buds pushed
  `03 04 01 20 00` and `03 04 01 40 00`: probably the level Smart chose `[GUESS]`. Not parsed.

### The hold's ANC cycle — `setSupportNoiseReduction`

The gesture table's `fn 0x08` only says "the hold cycles ANC". **Which modes it cycles is a separate
setting**; clearing `fn` to `0x00` does not stop the cycle `[CAPTURE]`.

| | Frame |
|---|---|
| Read | `0x010C 02 01` → `00 02 01 <mask LE>` `[CAPTURE]` (`02 03` / `02 04` on per-bud holds, §6) |
| Write | `0x0404 02 <type> <mask LE>`, type `01` shared, `03` left / `04` right on per-bud holds |

- The mask uses **the SET bits** `[CAPTURE]`: adding Adaptive in HeyMelody moved it `0x0007` →
  `0x0807`. Buds 4 bits: Off 0, ANC 1, Transparency 2, Adaptive 11 (same numbering as `OpoProtocol.anc`).
  Other models use their top-level modes' `protocolIndex` `[VENDOR]`.
- The mask is sent in as few bytes as needed, 1-4 `[VENDOR]`: `02 01 03` acked and read back as
  `0x0003` `[CAPTURE]`.
- A single-bit mask is valid (HeyMelody's minimum is one mode) `[CAPTURE]`. The app never sends 0.
- **Level bits are dropped silently**: `0x0021` was ACKed `00` and read back `0x0001` `[CAPTURE]`. The
  hold's ANC stop is always bit 1, the level last set by hand.
- One shared setting for both buds (type `01`) `[USER]`+`[CAPTURE]`.
- Bit 1 alone has never been cleared in a test (§12).

### Mistakes not to repeat

1. "ANC raises no event": it does (push `03`); we were not subscribed, and `UNATTR RX` hides `0x0204`
   (§10).
2. "SET and NOTIFY share bits": they do not. "Fixing" SET to the notify values sent wrong bits.
3. Adaptive as bit 8 (see SET).

---

## 6. Gestures

### Gesture table — read `0x0108`, write `0x0401` `[CAPTURE]`

Entry: 4 bytes `[dev, btn, act, fn]` `[OSS]`. The read reply has a **2-byte header**
`<status 00> <count>` (`KeyFunctionParser.HEADER_SIZE = 2`): 74 bytes = 2 + 18 × 4.

- **`dev`**: `01` left, `02` right; `04` in a write = both buds (on-call rows).
- **`btn`**: `01` main gestures, `06` on-call group (`KeyFunctionParser.BUTTON_ON_CALL`).
- **`act`**: `01` single, `02` double, `03` triple, `04` hold, `05` slide, `06` long hold / "super long
  press" (confirmed by matching `F1` frames).

Buds 4 table (`act:fn`):
```
dev=01/btn=01[01:00 02:11 03:00 04:08 06:00 05:07]
dev=01/btn=06[02:00 03:00 06:00]
dev=02/btn=01[01:00 02:00 03:00 04:08 06:00 05:07]
dev=02/btn=06[02:00 03:00 06:00]
```

**Write is `0x0401`** → ack `0x8401 00`. **`0x0402` is ignored in total silence.** A wrong command
number fails silently, so every write re-reads the table and diffs it; a write that matches no slot
is refused loudly. The diff baseline is persisted (`QuickBudsKeyFnDiff`); an empty reply never
overwrites a good one.

**The table's shape changes per bud and between writes** `[CAPTURE]`. Slide once lived in
`btn 01` on the left and was split into unbound `btn 02` + `btn 03` on the right; writing both halves
the same `fn` folded them back into one `btn 01` slot (19 → 18 entries). So
`writeGestureBinding(side, ...)` writes **every slot the table has for that (side, act)**, excluding
only `btn 06`. Never hardcode a button group or a count.

### `fn` values — measured `[CAPTURE]`, names `[VENDOR]`

| `fn` | Function | | `fn` | Function |
|---|---|---|---|---|
| `00` | none | | `0B` / `0C` | volume up / down |
| `01` | play/pause | | `0D` | switch devices |
| `02` | listening music (never shown) | | `11` | game mode |
| `03` | voice assistant | | `12` | zen mode |
| `05` / `06` | previous / next track | | `16` | collect music |
| `07` | volume (slide; resolves to `0B`/`0C`) | | `19` | AI summary |
| `08` | ANC cycle (hold) | | `1A` / `1B` | AI translation |
| `09` | favourite music | | `1C` / `1D` | decline / answer + end call |
| `0A` | switch track (slide; resolves to `05`/`06`) | | `20` | spy tap |

`00 01 03 05 06 07 08 0A 0B 0C 11 1C 1D` are measured on Buds 4. **Do not re-derive them.** On
"OnePlus Buds" and "OnePlus Buds Z" only (by Bluetooth name), previous / next are `04` / `05` `[VENDOR]`.

### Per-model gesture menus `[VENDOR]` (`GestureModel`)

Each model's `control` and `callControl` in `assets/models.json`: one entry per row,
`{action, support, minSelectCount}`. `support` is a mask of options:

| bit | option | | bit | option |
|---|---|---|---|---|
| 1 | voice assistant `03` | | 512 | none `00` |
| 4 | play/pause `01` | | 1024 | volume `07` |
| 8 / 16 | volume up / down `0B` / `0C` | | 2048 | switch track `0A` |
| 32 / 64 | previous / next `05` / `06` | | 4096 | switch devices `0D` |
| 128 | noise cycle `08` | | 8192 | game mode `11` |
| 256 | favourite music `09` | | 32768 / 65536 / 131072 | collect music / zen / AI summary |

Buds 4: `1:516 2:8807 3:3154531 5:3584`, hold `27` — matches HeyMelody's menus `[USER]`.

- `action` → `act`: 1-6 are themselves; 16/17/18 = acts 1/2/3 (stem-press models); 11, 20, 27 = the
  hold's ANC cycle on act 4 (options = the model's top-level noise modes); 7, 8, 12-15 are text-only
  rows.
- **callControl** (all `btn 06`, `dev 04`): 28/32 = act 1, 29/33/36 = act 2, 30/34 = act 3, 31/35 =
  act 6. Support 524288 = answer/end `1D`, 262144 = decline `1C`, 131072 = AI summary. A row without
  none (512) has one fixed option and writes nothing. Buds 4 `[CAPTURE]`: double tap
  `0401 01 04 06 02 1D` / `…02 00`, long hold `0401 01 04 06 06 1C` / `…06 00`; one write lands on both
  buds, labels confirmed on a real call `[USER]`.
- **realme models** `[VENDOR]` (realme Link, issue #8), wired, unverified: per-bud double tap, triple
  tap and hold, same `fn` bytes as above, `dev` 1 / 2, `btn 01`. A hold with the noise cycle is
  `longPressType` 8833; its cycle is **one mask for both buds** (`0x0404 02 01 <mask>`, read
  `0x010C 02 01`; the Air7 Pro log's `0A` = ANC + Transparency), so our own key `"sharedHoldMask":1`
  keeps type 1 on a per-bud hold. **Holding both buds** (Air 3S, T01, T110, T200 Lite, T200x): one
  entry `04 01 04 <fn>`, game mode `11` or none, read back as its own `dev 04` entry (realme Link reads
  it so; Buds 4 fans `dev 04` on-call writes out to both sides instead). Our own key `"bothHold":1`,
  shown as a gesture row; written like an on-call row. Not offered: volume on the noise-cycle hold
  (T500, Air8, T200x), AI options.
- **realme neckbands** `[VENDOR]` (Wireless 3 Neo, 5 ANC, 6, 6 ANC, 6 Neo), wired, unverified: one button
  written as the left bud, `01 01 <act> <fn>`, act 1-4 (single, double, triple, hold). Our own key
  `"oneButton":1` hides Left / Right. The noise button of the two ANC ones (`01 04 01 08`, single
  press = the noise cycle) is not offered.
- **Per-bud hold** (`longPressType`, 25 models): options 512 none, 128 noise cycle, 1 voice assistant,
  8192 game mode. Each bud written alone (`dev` 1/2, `act 04`); the cycle mask per bud with type 3 / 4,
  read with `0x010C 02 03` / `02 04`.
- **Minimum modes in the cycle:** `minSelectCount`, else 2 on OnePlus models and per-bud holds, else 1
  (Buds 4: 1).
- **Top-level ANC levels:** Buds Z2 and Enco X list strong (4) and weak (3) as their own cycle options.
  OnePlus Buds Pro (`060C14`) shows 3 / 4 / 7 as one "ANC" option whose bit is the current level's, or
  Smart's (bit 4) outside ANC.

### `F1` push — a gesture fired `[OSS]`+`[CAPTURE]`

`0x0204` payload `F1 <side> <btn> <act> <fn> <context> <int16 options...>`.
Example, right hold: `AA 0D 00 00 04 02 FF 06 00 F1 02 01 04 08 03`.

- `act` and `fn` are the gesture table's pair; `fn` is the **effective** function (a slide reports
  `0B`/`0C` or `05`/`06`; a hold with `fn` cleared still reports `08`). The `[OSS]` "modifier bits"
  reading is wrong.
- `act 00` frames (seen on a single tap) are some other event, not a binding `[GUESS]`.
- **Diagnostic only, never a control signal.** Read the effect from the ANC / game / wear pushes. One
  double tap can report twice; the app follows faithfully, do not "fix" it.

---

## 7. Battery — `0x8106` and push `01`

`[OSS]`+`[CAPTURE]` Payload `<count>` then `<index> <raw>` pairs: `01` left, `02` right, `03` case.
Level = `raw & 0x7F`, charging = `raw & 0x80`. Example: `03 01 64 02 64 03 50`.

- The case and a bud inside it report only with the lid **open**. With it closed, `0x0106` returns
  only the bud outside (`01 01 5A`) and nothing is pushed `[CAPTURE]`. So case charging is not shown,
  by decision. A part left out keeps its last level unless the "Hide old battery levels" setting is on.
- Pushes come every 1-6 min on the buds' own clock, not tied to `0x0500` `[CAPTURE]`.

## 8. Wearing — `0x8109` and push `02`

`[OSS]` Payload `<count>` then `<component> <status>` pairs; component `1` left, `2` right, `3` case.

| Status | Meaning |
|---|---|
| `0` | disconnected |
| `1`, `5` | out of ear |
| `3`, `7` | **in ear** |
| `4` | **in case** |

- Query replies sometimes prepend a status byte; `WearingStatusParser` tries offsets 0 and 1
  `[CAPTURE]`.
- **Lid close** `[CAPTURE]`: ~1 s before the socket drops, pushes fall to 0, ending all-zero
  `01 00 02 00 03 00`. No lasting "closed" state exists. The app logs `Case closed` and skips the
  reconnect retries. Opening brings `04 04 04` and the case battery.

---

## 9. Features

### Feature switches — `0x0403 <id> <01/00>`, read with `0x010D`

`0x010D <count> <ids>` → `00 <count> [<id> <value>]...`; only ids the firmware has come back
`[CAPTURE]`. **The ids are per model** `[VENDOR]`, as HeyMelody builds them: `05`, then one id per
feature its model data has. `models.json` `statusQuery` holds the list (81 HeyMelody models, generated
from HeyMelody 116.9.0's built-in data); Buds 4 = `05 04 0B 11 18 06 1B 1D 1C` (= the capture), Enco Buds2
= `05 06`. A longer list drops some links (issue #5: the old fixed 23-id list dropped the Enco Buds2; `02 05 0D`
kept it up `[CAPTURE]`). A model without `statusQuery` (realme, hand-added) still gets the 23 ids, plus `1A`
where it has `windNoise`. Rows for ids a model is not asked for do not show (HeyMelody parity: no Power
saving or Personalised ANC on Buds 4). Parsed into
`featureStates`, logged as `FEATURES:`. The buds' list wins over the model list while connected.

| Id | Switch | Notes |
|---|---|---|
| `04` | Auto play/pause (wear detection) | `[CAPTURE]` |
| `05` | ? | Buds 4 lists it, unassigned |
| `06` | Game mode / low latency | `[VENDOR]` all models, **except** `28` where the bitmap has `0x0423` (`gameModeId()`) |
| `09` | Vocal enhancement | flag `vocalEnhance` |
| `0B` | Golden Sound (hearing profile) | see below `[CAPTURE]` |
| `0C` | Personalised ANC applied | see below |
| `11` | Dual connection | see below `[CAPTURE]` |
| `17` | Power saving | restarts the buds, see below `[CAPTURE]` |
| `18` | Hi-Res | quality switch, drops the link, see below `[CAPTURE]` |
| `1A` | Wind noise reduction | realme Link `[VENDOR]`; asked in `0x010D` only for models with flag `windNoise` (Air7 Pro), unverified |
| `1B` | Spatial sound | on models without `0x012A` `[CAPTURE]` |
| `1C` | Smart volume | flag `controlAutoVolumeSupport` |
| `1D` | BassWave | `[CAPTURE]` |
| `27` | Game sound effects | flag `gameSoundList` |
| `30` | Adaptive volume | |
| `31` | Adaptive left / right ear | |
| `32` | Conversation awareness | HeyMelody turns it off against voice commands (`19`) |
| `35` | Touch-and-hold volume | flag `longPressVolume` |
| `37` | Windows Swift Pair | flag `swiftPair` |
| `38` | Adaptive sound (ear canal and fit) | HeyMelody asks first (battery) |
| `3A` | Pause when asleep | |
| `3B` | Head gestures | mapping below |

Ids `09`-`3B` without a `[CAPTURE]` are `[VENDOR]`, wired, unverified on buds. A row shows where
`0x810D` lists the id, or a hand-picked model has the flag.

**Decided against** `[USER]`: voice wakeup `14`, voice commands `19`, incoming-call voice control
`39`, neck health `22`-`24` (needs OPPO's Health app), meeting assistant `34`.

- **Power saving `17`** `[CAPTURE]`: either write restarts the buds; the link returns ~12 s later
  and `0x810D` shows the new value. No visible difference in ANC, gestures, wear, codec (LHDC V5
  48 kHz / 24-bit) or link parameters; the effect is internal. The buds do not bring phone audio back
  by themselves; the app asks for it. HeyMelody and the system melody app never show this row; it is a
  China-market feature `[VENDOR]`.
- **Head gesture mapping** `[VENDOR]`: `0x0431 00` = nod answers / shake declines, `01` = the reverse.
  Read `0x0134`; HeyMelody parses the type only from push `F5 <type>`. Bitmap bit 63.
- **Game sound type** `[VENDOR]`: `0x0423 <type> 01`, Off (`0`) included. Read `0x012B` →
  `00 <selected> <count> <types>`. Types: `0` Off, `1` a Chinese game, `3` shooting games. Offered:
  the model's `gameSoundList` types the buds also list.

### Personalised ANC — `0x0412` `[VENDOR]`, wired, unverified

Model flag `personalNoise` (11 models), bitmap `0x0412` (bit 26 with `0x011A`). Buds 4 lacks it.

| Step | Frame |
|---|---|
| Stored result? | `0x011A` → `811A 00 <exist>`, non-zero = a result exists |
| Test / use stored / cancel | `0x0412 01` / `02` / `03`; ack status `15` = another device busy |
| Result | push `0B <r>`: `0` applied, `1` too quiet, `2` poor fit, `3` wind, `4` movement, `5` audio or call |
| Off | `0x0403 0C 00`; the buds set `0C` on themselves when a result applies |

Both buds in an ear; 5 s timeout for `0x811A`, 15 s for a result; cancel when the dialog closes.

### Tap sensitivity — `0x042D` `[VENDOR]`, wired, unverified

Flag `tapLevelSetting`. `0x042D <level>` 1..5 (lower = taps trigger more easily). Read `0x0133` →
`00 <level> <default>`. Warn below the default; offer a reset.

### Golden Sound (Hearing profile) `[VENDOR]`+`[CAPTURE]`

On / off: `0x0403 0B 01/00` `[CAPTURE]`; it applies the record stored on the buds. The buds hold only
the active record; the phone keeps the list. A record: 4-byte id (big-endian, chosen by the phone),
12 hearing values, 168 bytes of ear-scan data. A hearing value is 3 bytes
`<side 01 L / 02 R> <freq 01..06> <value, signed>`. Every frame below matches a HeyMelody capture.

| Step | Frames |
|---|---|
| Read active record | `0x0115` → `00 03 0C <12 values> <id> <name ASCII>`; `0x011E` → `00 03 A8 00 <168 scan> <id>` |
| Ear scan | `0x040D 04 01 <id>` start, `04 00 <id>` stop; result pushed ~8 s later: `0E 03 A8 00 <168> <id>` |
| Hearing test | `0x040D 02 01` start, `02 00` stop |
| Tone | `0x040E 03 01 <value>`; `0x040E 04` stops it, sent before each new level |
| Apply | `0x040E 03 0C <12 values> <id> <name>` (name = date, "2026/09/29 01:53"), `0x0411 01 01 01 00 0B`, `0x0415 03 A8 00 <scan> <id>`, then `0x0403 0B 01` |
| Clear | `0x040E 02` + `0x0415 02`; after the test `0x040E 01 00 00000000` + `0x0415 01 00 00 00000000` |
| Status push | `08 <2 test / 4 scan> <s>`: 1/3 audio playing, 2/4 resumed, 5 a bud out, 6 back in, 7 timed out |
| Filters | `0x0116 0C <12 values> <id>` → `00 <id> 04 <count LE> <packet> <type> <floats>`; `0x011F A8 00 <scan> <id>` → `00 <id> 04 <rate LE 44100> <count LE> <packet> <floats>`. Floats LE, left ear then right, biquads `a0 a1 a2 b0 b1 b2` (60 = 5 per ear, 72 = 6) |

`0x040F 01` seen in the capture is an unrelated camera feature.

- **Slider:** 25 stops `-120 -88 -55 -52 -49 -45 -41 -38 -35 -30 -25 -22 -19 -15 -11 -8 -5 -1 3 5 7 10
  13 15 17`; each move plays a tone (at most every 300 ms), start near `-30`. Saved value = the stop
  snapped to the nearest of `-55 -49 -41 -35 -25 -19 -11 -5 3 7 13 17`. Loudness warning once at ≥ 10.
- **Radar graph:** per ear, sum the hearing and ear-scan biquads in dB (`20 log10 |B/A|`, 44100 Hz
  default), read at 80, 10000, 4800, 2400, 1200, 250 Hz (80 at the top, clockwise). Radius =
  `max(2, -|dB| × 10 / scale + 10)`, scales 7.5, 15, 15, 12.5, 12.5, 7.5; 10 = no change.
- Ear scan only where `models.json` has `"earScan":1`.

### Fit test — `0x0405` `[VENDOR]`+`[CAPTURE]`

`0x0405 01` start / `00` stop, ack `00`. Result push `04 <dev> <s> <dev> <s>` (dev `01` L / `02` R):
`1` good, `0` average, `6` poor, else error. Buds 4: ~8.6 s → `04 01 01 02 01`. Both buds in an ear,
15 s timeout, stop when the sheet closes. Needs event `04` subscribed (§4).

### Spatial sound and Hi-Res `[CAPTURE]`+`[VENDOR]`

- **Two ways** `[VENDOR]`: if the bitmap has `0x012A` (bit 47), `0x0422 <type>` (`0` off, `1` fixed,
  `2` head tracking), read `0x012A` → `00 <type>`, pushed as `0x0510 <type>`. Else feature `1B`.
  Head tracking only where `spatialTypes` has `2` (Buds Pro 2, Buds Pro 3, Enco X3). `0x0422`
  wired, unverified.
- **Spatial and Hi-Res are mutually exclusive.** HeyMelody warns, then: spatial on = `1B 01` then
  `18 00`; Hi-Res on = `1B 00` then `18 01`.
- **Any `18` change drops the link**; the buds reconnect ~4 s later.
- **realme models** `[VENDOR]` (realme Link, issue #8): 3D sound is feature `1B` alone and Hi-Res `18`
  alone, whatever the bitmap says; realme Link never writes one with the other (on some models it only
  warns). The Air7 Pro lists `0x012A` / `0x0422` but leaves `0x012A` unanswered. Our own key
  `"spatialSwitch":1` (realme models with 3D sound). Wired, unverified.
- **realme Link's `0x0403` ids** `[VENDOR]`, not used yet: dynamic bass `1D`, vocal enhance `09`, `0C`
  "enhance voice" (ours: personalised ANC applied; do not assume they match), game mode `06`, wear `04`,
  dual `11`. It reports power saving as `05` (ours `17` from HeyMelody: unresolved).
- **`18` is a quality switch, not a codec switch** `[CAPTURE]`: LHDC V5 either way. On: 44.1-192 kHz,
  400 kbps cap. Off: 44.1 / 48 kHz, 256 kbps. The phone chose 48 kHz / 24-bit both times. No LDAC on
  Buds 4.

### Codec picker (`highAudio` models) `[VENDOR]`, wired

Models with `highAudio` (Enco X2, Enco Air3 Pro, Enco Free3) and `0x0123` in the bitmap; replaces
the Hi-Res switch.

- Codecs: `1` SBC, `2` AAC, `3` LDAC, `4` aptX, `5` aptX HD, `6` aptX Adaptive, `7` LHDC, `8` LHDC V5.
  Shown in the order 8, 7, 3, 6, 5, 4, 2, 1; drop 7 when 8 is also offered.
- `0x0123` → `00 <u16 LE mask>`, bit k = codec k+1. `0x0114` → `00 <codec>`.
- Write `0x041A <codec> <hiRes> 00`, `hiRes` only with codec 3 or 8 (else `0`). The buds restart; the
  app warns and asks for phone audio on the reconnect.
- Buds 4 `[CAPTURE]`: `0x8114` = `00 08` (LHDC V5, correct). `0x8123` = `00 46 01`, which the table
  misreads (LDAC, no SBC) — so the mask is trusted only on `highAudio` models.

### Equalizer `[CAPTURE]`+`[VENDOR]`

```
0x0406 <id>                          select built-in                 ack 8406 00
push 0x0504 <id>                     EQ changed
0x010F → 810F 00 <id>                current EQ (Buds 4: 00-02 built-in, 04-06 custom)
0x0122 → 8122 00 <count> <presets>   custom list
0x0418 <action> FA 06 <id> <nameLen> <name> <bands> [freq u16 LE, gain s8]...   ack 8418 00 <id>
0x0403 1D 01/00                      BassWave on / off
0x041B FB 05 <level>                 BassWave level; read 0x0124 → 00 FB 05 <level>
```

- Custom preset: `<selected flag> FA 06 <id> <nameLen> <name> <band count> <bands>`. `FA 06` = gain
  range -6..+6; `FB 05` = -5..+5.
- `0x0418` actions: `01` create (send id `00`; the buds assign it, returned in the ack), `02` save and
  select (the whole preset, every time, also on every drag step and rename), `03` delete (selection
  falls back to `00`). **Ids are renumbered after create / delete: always re-read `0x0122`.**
- **Built-ins differ per model** `[VENDOR]`: `equalizerMode` lists `{modeType, protocolIndex}`;
  `protocolIndex` is the `0x0406` id. Buds 4: 0 Balanced, 1 Clear Vocals, 2 Bass; Nord Buds 2r: 0, 1
  Bold, 2. Types 1-4 are named differently where `equalizer` is `2` (or Enco R / Air2). 67 of 137
  models have none. Firmware-gated: use `equalizerModeCompat`, else `equalizerModeByVersion`, else
  `equalizerMode`; drop entries whose `minFirmVersion` is above the buds' firmware (the lower non-zero
  of left and right, `138` on Buds 4; unreadable = 0); stable sort by `order` (0 if absent).
- **What a model shows** `[VENDOR]`: EQ row where `equalizer` is 1-4 or custom presets exist; custom
  presets with `customEqualizer` and `0x0418` in the bitmap; BassWave with `bassEngineSupport` and
  `0x041B`. New presets use `customEqFrequency` bands (10 bands `31 62 125 250 500 1k 2k 4k 8k 16k`, 8
  models), else 6 bands `62 250 1k 4k 8k 16k`. `customEqMax` caps presets (default 3). EQ reads go out
  only where the bitmap lists them.

### Find my earbuds — `0x0400` `[CAPTURE]`

`0x0400 01` / `00`, ack `8400 00`. Rings both buds; no side byte. Warn when the buds are in the ears.

### Alert-sound volume — `0x0427` `[CAPTURE]`

`0x0427 <level>` 1..10, ack `8427 00 <level>`; read `0x0130` → `00 <level>`. The app sends on slider
release only (each write plays a prompt).

Smart auto-pause (pause only when both buds leave) is the app's own, not firmware: `BudsService` sends
a media pause key. It and switch `04` are mutually exclusive in the UI.

### Dual connection — `11`, device list `0x0112` `[CAPTURE]`+`[VENDOR]`

```
0x0112 → 8112 00 <count> <entries>        push 0x0204 06 <count> <entries>
entry: <MAC, 6 bytes reversed> <len> <state> <flags> <nameLen> <name UTF-8>
```

- `len` counts state, flags, nameLen and name. State `02` connected, `00` not (a dropped device stays
  listed). Flags: bit 0 this phone, bit 1 main audio device, bit 2 playing, bits 3-5 device type.
- Toggle: `0x0403 11 00/01`, then `0x0413 08 00 01` after off / `08 00 00` after on (also sent when the
  screen opens). Meaning unknown; replayed verbatim.
- **Device manager** `[VENDOR]`, wired, unverified: models whose `multiConnect` lists it (11;
  bitmap bit 59 `0x0429` / `0x0132`, Buds 4 lacks it). `0x0429 01 <MAC>` connect, `02 <MAC>`
  disconnect (MAC in written order, the reverse of the list); `03 <MAC>` unpair (unused). Preferred
  device: `0x0429 04 00` automatic, `04 01 <MAC>`; read `0x0132 02` → `00 02 <00 | 01 <MAC>>`.

### Requests from the buds — answered as HeyMelody does `[VENDOR]`

Some firmwares wait for these answers and reset the link when none comes. Nord Buds 3 Pro
(`064414`, issue #2) `[CAPTURE]`: sends `0x0500` with a real Seq and `00 00 00 00`, resends it, stops
answering queries and resets the link 3-5 s after connect, every time, when it goes unanswered. Buds 4
sends `0x0500` empty and works either way. Both apps answer from one function (`OpoProtocol.answerFor`,
`protocol::answer_for`); every answer echoes the request's Seq and sets `0x8000` on its cmd.

| From the buds | Answer | Meaning |
|---|---|---|
| `0x0500` | `00 <Unix seconds u32 LE>` | Time request, sent on connect with `0x0501` |
| `0x050C` | `00 01 00` | Last byte: is the foreground app on a list the buds hold (no) |
| `0x051C` | `00 00 00` | The app's capabilities; middle byte = fast discovery (off) |
| `0x051D` | `01` | The phone's spatial mode. HeyMelody answers it later with the mode, `01` only for bad data; we always send `01` |
| `0x0204` push `F2` | `00 F2` | Connected-devices info (HeyMelody's status 00 = parsed) |
| `0x0204` push `F4` | `00 F4 {"cmd":<its cmd>}` | Diagnostic JSON; `01 F4` if it does not parse. Buds 4 sends these with Seq `FF` |
| a cmd outside groups `01`-`05`, `0F` | `01` | Unknown request |

- No other push gets an answer: battery, wear, ANC, `06` and the rest are not acked by HeyMelody either.
  `0x0501` and `0x0504` get none.
- HeyMelody adds `<zone offset seconds s32 LE>` to the time answer for models whose model list flags
  `utcTimeZone`. The full list in the `[OSS]` repos (OppoPods, OppoPodsManager; 137 models, the source
  of `models.json`) flags none, so the 4-byte form goes out for every model. A newer list may flag some.
- Seen on Buds 4 after the change `[CAPTURE]` 2026-10-05: `0x0500` answered within 5 ms, link unchanged.

---

## 10. Debugging — log lines

| Line | Meaning |
|---|---|
| `RX:` / `TX[...]:` | Raw hex |
| `BTN EVT:` | `F1` push (§6) |
| `ANC EVT:` / `GAME EVT:` | ANC / game mode push |
| `WEAR EVT:` / `WEAR QRY:` | Wear push / reply |
| `KEYFN:` | Gesture table, grouped per `dev/btn` |
| `FEATURES:` | `0x810D` reply |
| `UNATTR RX:` | A frame we do not decode |
| `ancFlush=` | Whether an ANC write just went out |

- The gesture table prints in three places, all ending `RAW=[...]`: the `RX:` line, `KEYFN:`, and
  `LogDecoder`.
- **`UNATTR RX` never shows `0x0204`** (`noteUnattributed()` excludes it). An undecoded push prints
  nothing there. Dev Tools' decoder does show it (`Unknown active report`, in amber). Change the exclusion
  first if a capture needs these.

---

## 11. Method — learning a new command

1. Check the `[OSS]` sources and HeyMelody first.
2. Check the `0x8100` bitmap and the `0x8200` codes: does the firmware claim it?
3. Subscribe to the event, or silence looks like absence.
4. Capture from a known state; the developer says what he did, in order
   ([PACKET-CAPTURE.md](./PACKET-CAPTURE.md)).
5. **Never guess a payload or an enum.** Log the raw value; let the device name it. Confirming a read
   is cheap; a guessed write fails silently.
6. **Read every write back.** An ack of `00` is not proof.
7. **Do not refute a theory with a sample that may be a different kind of event.**

---

## 12. Open questions

- Hold cycle mask: bit 1 alone has never been cleared (§5).
- Pushes `F1` `act 00`, `F3`; the `F2` layout; the Smart level pushes `03 04 01 …` (§5).
- The recurring `F1` family `AA 0D 00 00 04 02 FF 06 00 F1 01 01 XX YY 02`, and `02 01 08 0C 02` /
  `02 01 07 0B 02` (non-multiples of ten; maybe a fine battery field). **Do not guess these from a
  few samples.**
- The `0x8205` ack layout (§4): the meaning of its bytes.
- `0x0404` with a level form `01 02 <level>` (`[OSS]` mentions it). Unverified.
- `0x8134` head gesture reply layout; `0x0413 08 00 xx`; switch `05`; `0x0501`.
- Other models' ANC bits, gesture writes and `[VENDOR]` features: unverified until an owner reads a
  write back.
