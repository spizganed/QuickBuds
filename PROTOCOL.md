# PROTOCOL.md — how the earbuds actually talk

A complete, evidence-first reference for the OPPO / OnePlus / realme earbud
RFCOMM protocol as **this project** understands it. Written so a future session
can re-learn the whole wire format without re-deriving it from captures.

**Rules for this document**

1. Every claim is marked with how we know it. `[VENDOR]` = from an official APK
   or vendor method name. `[OSS]` = from a public reverse-engineering project
   (see `CREDITS.md`). `[CAPTURE]` = observed on our own device, with the capture
   in `local/logs/`. `[GUESS]` = unverified. **Never promote a claim without
   evidence, and never delete a `[GUESS]` marker to make the doc look tidy.**
2. The test device is **OnePlus Buds 4**, firmware `B4.1-260810-1153`, Android.
   Other models differ — notably in feature IDs and button IDs.
3. If you learn something, add it here **in the same commit** as the code change.

---

## 1. Transport

Classic Bluetooth **RFCOMM / SPP**, not BLE.

| UUID | Role |
|------|------|
| `00001107-D102-11E1-9B23-00025B00A5A5` | Tried second; never connects on Buds 4, kept for other models |
| `0000079A-D102-11E1-9B23-00025B00A5A5` | Tried first; the one that actually works on Buds 4 `[CAPTURE]` |

`[CAPTURE]` On this device the first UUID reliably fails with
`read failed, socket might closed or timeout, read ret: -1`, and the second
succeeds. That is normal, not an error worth chasing.

The link is **one long-lived socket**. The buds push events over it at any time;
there is no request/response transaction pairing beyond the sequence number.

`[CAPTURE]` Closing the case lid with the buds docked **kills the socket**, which
is why the app treats that as a disconnect signal.

---

## 2. Frame format

```
AA  <TotalLen>  00 00  <Cmd LE>  <Seq>  <PayLen LE>  <Payload...>
^     ^         ^     ^          ^      ^             ^
1B    LEB128    2B    2B LE      1B     2B LE         PayLen bytes
```

| Field | Size | Meaning |
|-------|------|---------|
| Header | 1 | Always `0xAA` |
| TotalLen | LEB128 | Length of everything **after** the header and this field |
| Reserved | 2 | Always `00 00` |
| Cmd | 2 LE | Command id, low byte first |
| Seq | 1 | Sequence number `0x01`-`0xFE`. **`0xFF` = the buds are broadcasting** `[OSS]` |
| PayLen | 2 LE | Payload length |
| Payload | N | See per-command sections |

### TotalLen is LEB128, not a byte

`[OSS]` — from `Star-ZER0/Pods-Protocol-Reverse-Engineering`, and it is easy to
miss because every short frame looks like a plain byte:

- `< 128` → a single byte, value as-is.
- `>= 128` → **subtract 1**, then encode the result as standard LEB128
  (7 data bits per byte, low bits first, high bit set = "another byte follows").
- Decode: if the high bit is clear it is the value; otherwise accumulate 7-bit
  groups until a clear high bit, then **add 1**.

**Why this matters to us:** our frames are all small, so a naive single-byte
writer is correct for everything the app sends today. The gesture-config write
(`0x0401`, see §6) sends the whole key-function table back, which is the largest
frame we build — on Buds 4 that is ~80 bytes with the 18-20 entries the bud
reports, still under 127, so LEB128 has **not** been needed in practice. A bud
with a much larger table, or a write that adds entries, would cross 127 and need
real LEB128; `OpoProtocol.buildPacket()` does not implement it yet.

### Worked examples

```
AA 07 00 00 00 01 01 00 00         handshake     TotalLen=7,  Cmd=0x0100, Seq=01, PayLen=0
AA 0A 00 00 04 04 06 03 00 01 01 01  set ANC Off  TotalLen=10, Cmd=0x0404, Seq=06, PayLen=3
AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00  ANC push  TotalLen=12, Cmd=0x0204, Seq=FF (broadcast), PayLen=5
```

`[CAPTURE]` Note the pattern: **TotalLen = total bytes − 2**, because the two
sync/length bytes are excluded from their own count.

### Payload offset

**Payload always starts at byte index 9.** Size comes from bytes 7-8. This is
`BudsConnectionManager.payloadOf()`, and getting it wrong silently produces
garbage rather than an error.

---

## 3. Command map

### Query / response (0x01xx → 0x81xx)

| Cmd | Resp | Name | Payload | Status |
|-----|------|------|---------|--------|
| `0x0100` | `0x8100` | Handshake | — | `[CAPTURE]` |
| `0x0103` | `0x8103` | Product ID | — | `[CAPTURE]` |
| `0x0106` | `0x8106` | Battery | — | `[CAPTURE]` |
| `0x0108` | `0x8108` | **getKeyFunction** (gestures) | `<status> <count> <deviceType...>` | `[CAPTURE]` see §6 |
| `0x0109` | `0x8109` | Wearing / in-case | — | `[CAPTURE]` |
| `0x010C` | `0x810C` | ANC state | `01 01` | `[CAPTURE]` |
| `0x010D` | `0x810D` | Feature switch status (batch) | `<count> <featureIds...>` | `[CAPTURE]` |
| `0x010F` | `0x810F` | EQ | — | `[OSS]` |
| `0x0122` | — | EQ all presets | `01 05` | `[OSS]` |
| `0x0130` | `0x8130` | Alert-sound volume | — → `00 <level>` | `[CAPTURE]` see §9 |

Responses are **`cmd | 0x8000`**. That is a reliable rule `[OSS]`.

### Broadcast / notify (0x02xx)

| Cmd | Name | Notes |
|-----|------|-------|
| `0x0200` | Query broadcast codes | → `0x8200` lists what the firmware supports |
| `0x0201` | Register a single event | `[OSS]` |
| `0x0204` | **Spontaneous push** | `payload[0]` = subType. Seq is `0xFF` |
| `0x0205` | Subscribe to events | → `0x8205` ack |

### Set (0x04xx)

| Cmd | Name | Payload |
|-----|------|---------|
| `0x0401` | **setKeyFunction** (gestures) | `<count> [deviceType, button, buttonAction, function]...` `[OSS]`+`[CAPTURE]` see §6 |
| `0x0403` | Feature switch | `[featureId, status]`, status `01`/`00` |
| `0x0404` | Set ANC | `01 01 <bit>` — see §5 |
| `0x0406` | Set EQ | `[eqMode]` `[OSS]` |
| `0x0422` | Set spatial audio | `00` off / `01` fixed / `02` head-tracking |
| `0x0427` | Set alert-sound volume | `[level]` 1..10, ack `8427 00 <level>` `[CAPTURE]` see §9 |

Writes are acked by `0x8403` / `0x8404` with a 1-byte payload of `00` for
success `[CAPTURE]`.

---

## 4. Initialisation sequence

A working connection is exactly this, in order `[CAPTURE]`:

```
1. TX 0x0100 handshake                -> 0x8100
2. TX 0x0103 query product id         -> 0x8103
3. TX 0x0200 query broadcast codes    -> 0x8200   (learn what the buds support)
4. TX 0x0205 register notifications   -> 0x8205   (SUBSCRIBE - see below)
5. TX 0x010D query status             -> 0x810D
6. TX 0x010C query ANC                -> 0x810C
7. TX 0x0106 query battery            -> 0x8106
8. TX 0x0109 query wearing            -> 0x8109
9. TX 0x0108 query key function       -> 0x8108   (READ-ONLY, see §6)
10. TX 0x010C query noise switch modes -> 0x810C  (READ-ONLY, payload 02 01, see §5)
```

Each step is 200 ms apart, with a 300 ms delay before the first. Source:
`BudsConnectionManager.runInitSequence()`.

Then a periodic status poll (the app uses `POLL_INTERVAL_SECONDS = 60`). It sends the status
query only — wear is NOT polled, because the buds push it (§8).

Steps 9 and 10 are READ-ONLY additions, so neither can corrupt a binding.

`[CAPTURE]` Step 9 is settled: the buds answer it in ~40 ms and nothing objects, so it stays.
**If the timing of the earlier steps ever looks disturbed, these late additions are the first
suspect** — they are the only steps here that were appended after the sequence was stable.

`[CAPTURE]` **Step 10 is SETTLED as of 2026-09-20 — see §5's "The hold's SWITCH LIST" for the
reply and its decode.** This note used to say no capture had ever answered `02 01`; that stood for
the whole time the project ran on the phone. The first reply came from `packets.log`, not a
Wireshark capture, on the very first PC-side reconnect that happened to be logged from cold start.

### The broadcast codes reply (0x8200) is the map of what exists

`[CAPTURE]` On Buds 4:

```
AA 12 00 00 00 82 03 0B 00 00 09 01 02 03 04 08 0B F1 F2 F3
                                       ^^ ^^^^^^ ^^^^^^^^^^^
                                      count=9   the codes
```

| Code | Meaning |
|------|---------|
| `0x01` | Battery push |
| `0x02` | Wearing push |
| `0x03` | **ANC push** |
| `0x04` | `[GUESS]` something state-related; not subscribed by us |
| `0x08` | `[GUESS]` |
| `0x0B` | `[GUESS]` |
| `0xF1`–`0xF3` | `[OSS]` debug/JSON channels |

**Read this reply before assuming an event is unsupported.** It is the quickest
way to settle "is the buds' silence real, or are we just not subscribed?".

### Registering: the part that cost three captures

`0x0205` payload is **`[count][eventId...]`** — count FIRST `[CAPTURE]`.

```
02 01 02        count=2: battery + wearing
03 01 02 03     count=3: battery + wearing + ANC   <- current
```

**Do not write `01 01 02 02`.** Under the count-first shape that means "count=1,
register battery only", with `02 02` left over. The firmware ACKs it happily and
then never sends wear events, which is why wear looked poll-only for a long time.
That literal appears in older notes and in some public projects — it is a misread.

**We missed `0x03` (ANC) for the same class of reason.** Without subscribing to
it, ANC gestures were completely silent — no frame at all — and that looked
exactly like "this firmware does not report ANC". It does. See §5.

### The subscription ack (0x8205)

`[FOR VENDOR VERSION]` do not assume the ack echoes your list. On an older
capture the ack was:

```
AA 0D 00 00 05 82 04 06 00 01 02 01 00 02 00
                                   ^^ ^^ ^^ ^^
```
`[GUESS]` reads as `[01][02][01 00][02 00]` — i.e. ids with an enable byte each,
`00` = normal. **Check this ack lists all the ids you asked for**; if it lists
fewer, the firmware rejected part of the list.

---

## 5. ANC (noise cancelling)

### SET vs NOTIFY use DIFFERENT encodings

**This is the single most important trap in this document.** They do not agree,
they are not supposed to agree, and assuming they do produces writes the buds
silently ignore or misinterpret.

#### Set — `0x0404`, payload `01 01 <bitfield>` `[OSS]`

From `OppoPodsManager` `Protocol/OppoProtocol.Anc.cs`, where `PktAncByIndex()` is
the same algorithm as our `OpoProtocol.ancPayload()`:

| Mode | Payload | Bit |
|------|---------|-----|
| Off | `01 01 01` | 0 |
| On | `01 01 02` | 1 |
| **Transparency** | `01 01 04` | **2** |
| Deep | `01 01 10` | 4 |
| Medium | `01 01 20` | 5 |
| Light | `01 01 40` | 6 |
| Smart | `01 01 80` | 7 |
| Adaptive | `01 01 00 08` | mask `0x0800` = **bit 11**, 4-byte payload |

The bitfield grows: `index / 8 + 1` bytes after the `01 01` prefix.

**ADAPTIVE IS BIT 11, NOT BIT 8 — a real bug, found and fixed 2026-09-22.** The old
row here said "8", and `OpoProtocol.ancAdaptive()` passed `8` to `ancPayload()`,
which computes `index / 8 + 1 = 2` mask bytes and sets bit `8 % 8 = 0` of the
SECOND one — producing `01 01 00 01`, a different mode entirely. The mask is
little endian across the bytes after the `01 01` prefix, so `AncAdaptive`
(`01 01 00 08`) is the value **0x0800**, whose bit index is **11**.

**Say it precisely, because an earlier revision of this section said "Adaptive is the
only mode that cannot be expressed as an index", which is WRONG.** `ancPayload(11)`
does reproduce these bytes — the helper was never the problem, the number handed to it
was. 8 is just the plausible-looking wrong answer: the vendor's list reads like
"0-7, then the next one". The builder deliberately spells the four bytes literally
instead, to match `AncAdaptive = { 0x01, 0x01, 0x00, 0x08 }` verbatim rather than
re-deriving a number that was already derived wrong once. Every other row above is
reproduced correctly by the index algorithm. `LogDecoder.ancPayloadToString()` was
also reading `0x0100` for Adaptive and now reads `0x0800`, so an Adaptive command no
longer prints as `Unknown (0x0800)` in our own log.

The bug was INERT for as long as it existed: nothing called the builder. The
Adaptive circle added to the main screen makes it live, which is why it is fixed
in the same change rather than left as a latent wrong packet.

#### Notify — `0x0204` subType `0x03`, and the `0x810C` query reply `[CAPTURE]`+`[OSS]`

Payload `03 01 01 LO HI`; `LO`/`HI` are `(Val1, Val2)`, little endian.

| Raw | Val1, Val2 | Mode |
|-----|-----------|------|
| `0x0008` | `08 00` | **Off** |
| `0x0002` | `02 00` | ANC (generic) |
| `0x0080` | `80 00` | Smart |
| `0x0040` | `40 00` | Light |
| `0x0020` | `20 00` | **Medium** |
| `0x0010` | `10 00` | **Deep** |
| `0x0100` | `00 01` | **Transparency** |
| `0x0200` | `00 02` | Transparency, voice enhance on |
| `0x0800` | `00 08` | Adaptive |

Both our captures and `OppoPodsManager`'s `AncValues` dictionary agree exactly.

`[CAPTURE]` **The ANC-on stop echoes whichever LEVEL was last active.** Cycling
from Deep reported `0x0010`; cycling from Medium reported `0x0020`. So the value
is a **bitmask of the actual mode**, not a fixed three-value enum. Code that
treats it as an enum will be wrong half the time.

`[CAPTURE]` 2026-09-21 — a four-stop gesture cycle (`low -> adaptive ->
transparency -> off`) returned **`0x0800` for the Adaptive stop**, which had been
`[OSS]`-only until then. Two consequences worth keeping:

- `0x0020` was NOT the "ANC on" stop in general. The cycle above never produced
  it, because Light was the level in force. Both earlier captures that read it as
  *the* ANC-on value were simply starting from Medium. The bitmask reading is the
  correct one and this capture is the second, independent confirmation.
- **`0x0040` (Light) and `0x0800` (Adaptive) are byte-distinct and are now ALSO
  UI-distinct.** While the app had three ANC levels and no Adaptive control,
  `modeForRaw()` folded `0x0800` into `ANC-Light`, which meant a log line could not
  be read back: the four-stop cycle above printed `raw=0x0040 -> ANC-Light` followed
  by `raw=0x0800 -> ANC-Light`, two different stops rendered identically. That was
  patched in the LOG (`describe()` special-cased `0x0800`) while the mapping itself
  stayed folded, and the fold was right at the time. **SUPERSEDED 2026-09-22, when
  the main screen gained an Adaptive circle:** `modeForRaw()` now returns `"Adaptive"`
  and there is no special case in `describe()` any more, because the two are no
  longer the same UI state — Adaptive lights its own circle. The old instruction
  "do not unify these two back into one name" is therefore **retired**: they ARE one
  name now, deliberately, and `circleFor()` maps `"Adaptive"` to the ADAPT circle.
  If a future change removes the Adaptive control, the fold has to come back with it
  or a bud-side Adaptive switch will light nothing.
- `[USER]` The hold offers **only** the ANC cycle in HeyMelody, in exactly this order
  (`ANC on -> adaptive -> transparency -> ANC off`), so the four stops above are the
  cycle's canonical order and not an accident of what he happened to press. The
  "ANC on" stop is the last level set BY HAND, which is why it read `0x0040` here and
  `0x0010` / `0x0020` in the earlier captures — same rule, three observations.

### Querying

```
TX  AA 09 00 00 0C 01 05 02 00 01 01
RX  AA 0C 00 00 0C 81 05 05 00 00 01 01 08 00
                                           ^^^^^ last two bytes = the notify bitmask
```
`[CAPTURE]` Off returned `08 00` here, consistent with the notify table.

#### The hold's SWITCH LIST is a different question on the same command number

`0x010C` carries several questions, chosen by the REQUEST payload `[OSS]`:

| Request | Question |
|---------|----------|
| `01 01` | current mode (above) |
| `02 01` / `02 03` / `02 04` | **which modes the hold cycles through** (`getNoiseReductionSwitchMode`) |
| `04 01` | intelligent noise reduction mode |

#### `[CAPTURE]` 2026-09-20 — the switch-list reply, ANSWERED

The first-ever reply to the `02 01` variant, from `packets.log` (the app's own on-device log, not a
Wireshark/tshark capture — `PacketLogger` writes every TX/RX line to a file in app-specific external
storage, and that file happened to still hold the connection's init sequence):

```
TX  AA 09 00 00 0C 01 07 02 00 02 01
RX  AA 0C 00 00 0C 81 07 05 00 00 02 01 07 00
```

Payload breakdown, by the same shape the confirmed `01 01` (current-mode) reply already uses —
`[status][echo of the 2-byte request][answer]`:

| byte(s) | value | meaning |
|---|---|---|
| status | `00` | success, same as every other `0x81xx` reply |
| echo | `02 01` | the request payload, echoed back verbatim |
| answer | `07 00` | little-endian → mask `0x0007` |

The echo-of-request shape is not a guess — it is the same pattern the `01 01` reply already shows
(`00 01 01 08 00` = status, echo `01 01`, answer `08 00`), so this is `[CAPTURE]`, high confidence.

**What `0x0007` means is `[INFERRED]`, not proven.** Bits 0/1/2 are set. If this mask reuses the SET
table's bit numbering from earlier in this section (bit 0 = Off, bit 1 = On/generic, bit 2 =
Transparency — the `PktAncByIndex()` scheme, not the NOTIFY table's), it reads as **three tiers: Off,
On (resolving to whatever level was last hand-set), Transparency.** That lines up with the capture
this came from: the developer ran the hold through exactly those three stops (Medium → Transparency →
Off — "On" resolving to Medium, the level he'd last set by hand), which is consistent with, but does
not by itself prove, the bit-numbering theory. Earlier device sessions reported the cycle holding two
modes once and four another time (§ "Can the app choose which ANC modes..." below), so the mask is
expected to change with vendor-app configuration — that would be the next confirming test, the same
method §6 used to pin down the `function` enum: change the hold's membership in the vendor app,
re-query `02 01`, and check that the mask moves the way the bit theory predicts. **Not done yet.**

This unblocks the hold mode picker's read requirement (see "Order of work" below) but the WRITE side
(`setSupportNoiseReduction`, `0x0404`) is still `[OSS]`-only and untested — confirming the read does
not by itself make the picker safe to build.

The write side is `setSupportNoiseReduction` (`0x0404`, payload
`[action=2][noiseType][modeMask LE]`, `OppoProtocol.LongPressNoisePayload`), and
`[OSS]` only, untested.

#### Can the app choose which ANC modes the hold cycles? **Yes in principle — the
#### limit is our own protocol coverage, not the hardware and not HeyMelody's design.**

This is the short answer to a question worth recording, because the "it just uses the
last manually-chosen mode" behaviour is real but its CAUSE is not what it looks like:

- **It is not a hardware limit.** The buds hold a *changeable* list. Same stored `fn`
  byte (`0x08`) covered two modes in one session and four in another, so membership is
  stored somewhere on the device and is not fixed.
- **It is not a HeyMelody design choice either.** The vendor app does offer the mode
  list for the hold — so the capability is real and reachable.
- **It IS the protocol's shape.** The key-function table stores ONE byte meaning "this
  gesture cycles ANC". It carries no membership, so changing that byte cannot change the
  cycle (measured: clearing it to `0x00` did not stop the cycle). HeyMelody shows a mode
  list because it ALSO sends `setSupportNoiseReduction` alongside the binding. That write
  is the missing half in this app — not the hardware.
- **And what the app currently does** — leave the hold alone and set modes manually —
  is therefore a consequence of the two gaps above, not a behaviour anyone chose.

**Order of work, and why it is this order — ALL THREE STEPS ARE NOW DONE.** Confirm the read
(`0x010C` `02 01`) FIRST — done 2026-09-20, see "the switch-list reply, ANSWERED" above. Confirm the
mask's bit-numbering with a membership-change test — done 2026-09-22, see "THE MEMBERSHIP-CHANGE TEST,
ANSWERED" just below. Then the write side (`setSupportNoiseReduction`, `0x0404`) — also confirmed by
the same capture, ACKed and read back correctly. `OpoProtocol.setHoldAncModes()` and
`BudsConnectionManager.sendHoldAncModes()` implement it; the hold picker in `GestureActivity` now
sends it alongside the key-function bind (see `GestureAction.holdMaskBit`).

#### `[CAPTURE]` 2026-09-22 — THE MEMBERSHIP-CHANGE TEST, ANSWERED

An HCI capture of HeyMelody itself (Option C, PACKET-CAPTURE.md — `adb bugreport` +
`btsnoop_hci.log` + `tshark`), taken while he added Adaptive to a 3-stop hold cycle in HeyMelody's own
UI:

```
TX  AA 0A 00 00 04 04 <seq> 04 00 02 01 07 08     setSupportNoiseReduction: action=02 noiseType=01 mask=07 08
RX  AA 08 00 00 04 84 <seq> 01 00 00              ack, status=00
TX  AA 09 00 00 0C 01 <seq> 02 00 02 01           0x010C query, echo 02 01
RX  AA 0C 00 00 0C 81 <seq> 05 00 00 02 01 07 08  mask now 07 08 (was 07 00 before this write)
```

**THIS SETTLES THE BIT THEORY: the mask is [ancPayload]'s OWN bit numbering, not a separate scheme.**
Adding Adaptive moved the mask from `0x0007` to `0x0807` — bits 0/1/2 unchanged, bit 11 (`0x0800`)
newly set. Bit 11 is exactly Adaptive's bit in the plain SET_ANC table (§5 above, `ancAdaptive()`).
Off = bit 0, Transparency = bit 2 both match `ancPayload()` too; bit 1 remains the one bit with no
independent isolation — every capture so far shows it set, consistent with it being a generic "On"
that resolves to the last hand-set level, but no test has tried clearing it alone.

**THE WRITE WORKS.** Acked (`0x8404` status `00`), and the read-back matches predicted `0x0807`
exactly — two independent `0x010C` `02 01` queries (sent on each bud's own link) both returned it.
`OpoProtocol.setHoldAncModes(mask)` reproduces this payload shape exactly:
`byteArrayOf(0x02, 0x01, mask_lo, mask_hi)`.

Constants: `OpoProtocol.HOLD_MASK_BIT_OFF = 0`, `_ON = 1`, `_TRANSPARENCY = 2`, `_ADAPTIVE = 11`.

**`[CAPTURE]` 2026-09-23 — a SINGLE-BIT mask is valid, and it is HeyMelody's own minimum.** HeyMelody's
hold dialog requires at least one mode and allows exactly one ("1 option selected. Touching and
holding will not switch modes."). Set to Off-only there, our connect-time read returned
`0C 81 .. 00 02 01 01 00` → mask `0x0001`, with the hold's `fn` still `0x08` on both buds. Our own
write of `0x0004` (Transparency only, `AA 0B 00 00 04 04 <seq> 04 00 02 01 04 00`) read back `0x0004`,
and HeyMelody, reconnected afterwards, showed the hold as "Transparency". An all-zero mask is still
never observed — HeyMelody cannot produce one, so the app never sends one either.

**`[CAPTURE]` 2026-09-24 — the mask CANNOT pin an ANC level. Level bits are silently dropped.**
HeyMelody's hold dialog offers only Noise cancellation / Adaptive / Transparency / Off — no level.
To test whether the firmware accepts one anyway, with the level hand-set to Deep (`0x0010`), our app
wrote `0x0021` (bit 5 = Medium in the SET table + bit 0 = Off):

```
TX  AA 0B 00 00 04 04 0B 04 00 02 01 21 00        setSupportNoiseReduction mask=0x0021
RX  AA 08 00 00 04 84 0B 01 00 00                 ack, status=00
TX  AA 09 00 00 0C 01 0C 02 00 02 01              verify
RX  AA 0C 00 00 0C 81 0C 05 00 00 02 01 01 00     mask=0x0001 — bit 5 dropped
```

**The ACK says `00` and the write still did not take** — only the read-back shows it. So the hold's
"ANC" stop is always bit 1, the level last set by hand. A fixed-level hold has to be done app-side
(react to the hold's ANC push, send the level), not by the mask. Restored to `0x0003` afterwards.

**`[CAPTURE]` 2026-09-22, from wiring this into the real app and testing on-device (not HeyMelody this
time — our own build): the mask write ALSO raises a `0x0204` subType `0x03` frame**, the same family
`AncEventParser` decodes for an ANC mode change, but this one's payload is shaped like the `0x010C`
query's answer (`02 01 <mask LE>`), not the normal 2-byte ANC bitmask (`01 01 <value LE>`).
`AncEventParser.isAncEvent()` did not check bytes 1/2 before this fix, so it decoded the mask-write's
own echo anyway and produced a bogus mode name (`ANC EVT: raw=0x0807 -> ANC-Light` was observed).

**FIXED 2026-09-22 — THIS WAS A REAL BUG, NOT COSMETIC, AND IT COST HIM A REAL SYMPTOM.** First
filed here as "cosmetic, a log-reading trap." It is not: the bogus mode name went through the exact
same `onAncModeState()` path a genuine push uses, so it got written into the PERSISTED display state.
Surfaced as: "whenever i open the app after a new apk push then UI shows current mode is ANC-Low...
i didnt change it from OFF at all" — his earbuds were genuinely still Off (no tone, matches), the
display was just stuck on the bogus value from this session's own hold-mask testing, and nothing
corrected it because the current-mode QUERY reply (`0x010C` `01 01`, sent on every connect) was
**never wired to update the display at all** — a second, older gap this exposed. Both fixed together:
`isAncEvent()` now also requires `payload[1]==0x01 && payload[2]==0x01` (the mask-write echo's `02 01`
now fails this), and `BudsConnectionManager` now reads the current-mode query reply on every connect
and calls `onAncModeState()` from it too, so a reconnect self-corrects the display regardless of what
was persisted before.

**RETRACTED: "writing a mask that excludes the buds' current live mode changed the live mode."** That
claim, made in an earlier revision of this note, rested entirely on the SAME bogus push described
above — there is no longer any evidence the live mode changed at all, and his report (buds genuinely
still Off, no tone) is consistent with it never having changed. Do not resurrect this claim without a
fresh, properly-filtered capture.

#### `[USER]` The hold's ANC-cycle membership is ONE shared setting, not per-bud

2026-09-22 — changing the left bud's hold cycle in HeyMelody (3-stop `On -> Transparency -> Off` to
4-stop `On -> Transparency -> Adaptive -> Off`) changed the **right** bud's cycle to match, with no
separate edit made on that side. So the mode list `setSupportNoiseReduction` reads/writes is a single
device-level setting, not two independent per-bud lists — unlike the primary tap/hold BINDING
(`fn=0x08` in the key-function table), which is stored once per side (`dev=0x01` and `dev=0x02` each
have their own entry) and could in principle differ. Whatever side a write targets, expect both buds'
cycles to move together; do not build per-side controls for this list. This matches the capture above
exactly — the write's payload has no `deviceType`/side field at all, so "shared" is a property of the
command itself, not something either app chooses. The 3-stop vs 4-stop change was subsequently
confirmed through `0x010C` `02 01` too — see "THE MEMBERSHIP-CHANGE TEST, ANSWERED" above.

### The History of Getting This Wrong

Kept deliberately, so it is not repeated:

1. **"ANC raises no event."** Wrong. It raises `0x0204` subType `0x03`. The
   belief survived three captures because `noteUnattributed` in
   `BudsConnectionManager` **blanket-excludes cmd `0x0204`**, so an undecoded
   `0x0204` subType prints *nothing at all*. The F1 button frame also fires
   identically for every gesture, so it said WHEN but never WHAT.
2. **"Set and query must use the same bits."** Wrong. SET uses bit 0 for Off and
   bit 2 for Transparency; the buds *report* bits 3 and 8. The SET table was
   "corrected" to the notify values and sent the wrong bits. Reverted.

---

## 6. Button / gesture events — `0x0204` subType `0xF1`

`[OSS]` Payload body from `OppoPodsManager` `Models/UserInteractionEventInfo.cs`:

```
80 F1 | 01 | 01 | 04 | 08 | 03 | <int16 options...>
       ^    ^    ^    ^    ^
       |    |    |    |    +-- byte4: context / scenario
       |    |    |    +------- byte3: FUNCTION it resolves to (not a modifier, see §6.1)
       |    |    +------------ byte2: ACTION (a key-function `act`, not an F1 action id)
       |    +----------------- byte1: button / zone id (model dependent)
       +---------------------- byte0: SIDE
```

| Field | Values |
|-------|--------|
| side (byte0) | `0x01` left, `0x02` right `[CAPTURE]` |
| button (byte1) | model dependent; `0x01` on Buds 4 `[CAPTURE]` |
| action (byte2) | **a key-function `act`, 1..6** — NOT the `[OSS]` F1 action id. See §6.1, which established this by matching four `F1` frames against their `0x8108` entries: `0x01` single, `0x02` double, `0x03` triple, `0x04` long press `[CONFIRMED]` |
| function (byte3) | **the FUNCTION** the gesture resolves to — `[CONFIRMED]` in §6.1. The `[OSS]` calls this field *modifier / flag bits*, which is wrong |
| context (byte4) | `0x03` seen on Buds 4 |
| options | little-endian int16s to end of payload |

`[CAPTURE]` Worked example — a long press on the right bud:

```
AA 0D 00 00 04 02 FF 06 00 F1 02 01 04 08 03
```

Parsed in our code by `UserInteractionParser`; our index base differs (we skip the
`F1` subtype byte because `payload[0]` is already the subtype) but the resulting
fields are identical.

### What this frame can and cannot tell you

- It tells you **WHEN** a gesture happened, and which action and side.
- It is **identical for every occurrence of the same action + side**.

**THIS SECTION USED TO CONCLUDE THE OPPOSITE, AND IT WAS WRONG — see §6.1.** The frame
does tell you what the gesture does, via `byte2`/`byte3`. This section used to conclude that
the frame therefore does **NOT** tell you what the gesture *did*, and gave as proof "two
long presses on the right bud — one cycling ANC, one triggering voice assistant —
produce the same `F1` frame". `[USER]` That example **cannot be built on this model**:
HeyMelody offers the hold nothing but the ANC cycle (see §6.1), so a hold cannot be
bound to voice assistant at all. The observation is still true, but it no longer
supports the conclusion, because a binding is exactly one function per
`(device, button, action)` — so the frame *would* still look identical on every
occurrence even if it carried the function.

The safe reading, and it still stands now that §6.1 is confirmed: the `F1` log line is
**diagnostic only, never a control signal**. The *effect* must come from a separate event (§5) or a
query. Knowing what a gesture is bound to does not tell you what the buds just did — read that from
the ANC/game-mode/wear pushes instead.

### 6.1 `[CONFIRMED]` `F1` byte3 IS the bound function

**RESOLVED 2026-09-22 — the answer is YES.** `F1` `byte2`/`byte3` are the key-function
`act`/`function` pair, matched across five independent slots with five *different* values:

| `F1` (after `F1`) | byte2 | byte3 | key-function entry | its `fn` |
|---|---|---|---|---|
| `01 01 01 01 02` | 01 | 01 | `dev=01/btn=01 act 01` | 0x01 |
| `01 01 02 06 02` | 02 | 06 | `dev=01/btn=01 act 02` | 0x06 |
| `01 01 03 05 02` | 03 | 05 | `dev=01/btn=01 act 03` | 0x05 |
| `01 01 04 08 02` | 04 | 08 | `dev=01/btn=01 act 04` | 0x08 |

So the buds DO tell us, live, what a bound gesture does — and `byte2` is already a
key-function `act`, not an `F1` action id, which is why the two numberings looked so
confusing for so long.

**HOW THIS WAS FIRST GOT WRONG, AND THE LESSON — kept because it is the useful part.**
An earlier pass declared the theory *refuted* using one frame, `F1 01 01 00 00 03`, where
byte2/byte3 are both `0x00`, against the single-tap slot (`act 01 -> fn 01`). But
**`byte2 = 0x00` is not a key-function `act` at all** — the table's acts are 1..6 — so that
frame is not the single-tap binding firing; it is some other event. The comparison was
between two different kinds of thing. **Do not refute a theory with a sample that may not
be the same kind of event.**

**THE SLIDE "OUTLIER" IS NOW EXPLAINED (2026-09-22): `byte3` IS THE RESOLVED FUNCTION.**
This was listed here as an unexplained counter-example. It is not one — it is the rule
working. The same gesture, with two different slot values, gave two self-consistent pairs:

| slide slot `fn` | `byte3` up | `byte3` down | reading |
|---|---|---|---|
| `0x0A` switch track | `0x05` | `0x06` | resolves to prev / next |
| `0x07` volume | `0x0B` | `0x0C` | resolves to volume up / volume down |

So `0x0A` and `0x07` are **composites** that resolve per direction, and **`0x0B` = volume up,
`0x0C` = volume down** (the pairings are measured; the two NAMES are inferred, and marked so).
This is also the cleanest demonstration of why `byte3` cannot be read as just "the stored
byte" — it reports what the gesture EFFECTIVELY does.

**STILL UNEXPLAINED — so this stays diagnostic, never a control signal:**

- **`F1` action `0x00` vs `0x01`.** Both appear as a left single tap. `0x01` matches the
  bound slot, `0x00` does not — `0x00` is likely a raw touch report, which would also make
  the `[OSS]` action table's "`0x00` = single tap" **suspect**.
- **The hold breaks the pattern in a revealing way:** with its stored `fn` cleared to `0x00`
  the `F1` frame still reported `byte3 = 0x08`. So `byte3` tracks the **effective** function
  — what the gesture really does — not necessarily the byte stored in the table.

The evidence below is the original `[CONTENDED]` reasoning, kept as the record of how a
two-sample match was correctly treated as unproven *at the time*:

`[OSS]` labels byte3 *modifier / flag bits*. A pairing of our own two frames suggests
it may instead carry the **function id** — the same value the `0x8108` reply puts in
its fourth slot. That would mean the buds tell us *what* a gesture does, live, and the
gesture UI would not need a `0x8108` round trip to find out.

Evidence FOR, and it is one gesture only — the hold:

| frame | `F1` | `0x8108` entry |
|-------|------|----------------|
| left hold | `01 01 04 08 03` → side `01`, btn `01`, act `04`, byte3 `08` | `dev=0x01/btn=0x01 04:08` |
| right hold | same shape, side `02` | `dev=0x02/btn=0x01 04:08` |

Same side, same button, same action, and byte3 equals the reply's `fn` on both buds.
`[USER]` Both buds' hold is the ANC cycle, and `fn 0x08` is the only function assigned
to a hold in the reply. So the case is coherent — but it rests on ONE gesture.

Evidence AGAINST, which is why this is `[CONTENDED]` and not a finding:

- `[OSS]` byte3 is non-zero specifically on long presses, which is also what a
  modifier flag would do. The match could be a coincidence of the hold's flags being
  `0x08`.
- **No second data point.** The reply's other assigned slots — `fn 0x07` (act `0x05`)
  and `fn 0x11` (left double tap only) — have no `F1` capture to compare against.
  Every other `F1` sample we hold is a hold, i.e. the same gesture.
- The two readings are **the same event seen twice**, not two independent facts. That
  is exactly the shape of the SET-vs-NOTIFY mistake (§5): plausible, self-consistent,
  and wrong.

`[USER]` **Which functions a gesture may hold is constrained by the model, and that
removes the obvious test.** In HeyMelody the HOLD offers *nothing but* the ANC modes
(`ANC on`, `Adaptive`, `Transparency`, `ANC off`); game mode is only offered on
DOUBLE and TRIPLE tap. So "rebind the hold to game mode and watch byte3 change" is
**not a thing that can be done — do not propose it again.** It also means the
`fn 0x08` in the table above is the whole hold binding, not one of four: the hold is
a single *cycle* function, and the four-stop cycle he ran is its output, not four
separate bindings.

Our own gesture UI already encodes the same constraint independently —
`GestureConfig.actionsFor()` gives `TAP_HOLD` the four ANC actions and nothing else,
and gives `GAME_MODE` to double/triple tap only. The two agree, so that vocabulary is
not ours to widen.

**THE TEST THIS SECTION ASKED FOR WAS RUN, AND IT PASSED — see §6.1.** It wanted a non-hold
`F1` frame compared against its reply entry, and single/double/triple-tap frames all matched
once they were available. The lesson worth keeping is that the *first* attempt at this test
used a frame which was not a binding event at all, and drew the wrong conclusion from it;
§6.1 records that mistake in full.

### Gesture configuration — IMPLEMENTED AND WORKING (2026-09-22)

| Direction | Cmd | Payload |
|-----------|-----|---------|
| Read | `0x0108` → `0x8108` | `<status> <count> <4-byte entries>...` `[CAPTURE]` |
| Write | **`0x0401`** → ack `0x8401` | `<count> [deviceType, button, buttonAction, function]...` `[CAPTURE]` |

Each entry is 4 bytes `[deviceType, button, buttonAction, function]`
(`[OSS]` `Models/KeyFunctionItem.cs`, confirmed on the read side by our capture).

**`0x0402` IS NOT THE WRITE AND COST A SESSION.** It was sent repeatedly in a well-formed
frame (`TotalLen 80 = 7 + 73`, payload `12` + 18×4) and the buds ignored it **in total
silence** — no ack, read-back unchanged. `0x0401` acks immediately:
`RX AA 08 00 00 01 84 .. 01 00 00`, payload `00` = success. Sources:
the Melody-derived setting tables list `setKeyFunction` (`BtOperate.m2699L`) at `0x0401`
and run 0x0400, 0x0401, 0x0403, 0x0404 — 0x0402 is not among them — while OppoPodsManager
mentions 0x0402 **only inside a comment**. That comment was the trap.

**A WRONG COMMAND NUMBER FAILS SILENTLY.** "It worked" and "it did nothing" are
indistinguishable without reading the table back, which is why the write always re-reads
and diffs. A write that matches no slot is now refused loudly, for the same reason.

#### The `function` values — MEASURED, not guessed

Obtained by diffing two `0x8108` readings around changes he made, then checking every value
against what he reported binding (zero contradictions; two values confirmed twice):

| `fn` | meaning | | `fn` | meaning |
|---|---|---|---|---|
| `0x00` | none / unbound | | `0x08` | **ANC cycle** (the hold) |
| `0x01` | play/pause | | `0x0A` | switch track |
| `0x03` | voice assistant `[CAPTURE]` | | `0x0B` | volume up `[INFERRED]` |
| `0x05` | previous track | | `0x0C` | volume down `[INFERRED]` |
| `0x06` | next track | | `0x11` | game mode |
| `0x07` | volume | | | |

`0x03` is also confirmed from HeyMelody's own write, 2026-09-25: left double tap to Voice Assistant
sent `0401 01 01 01 02 03` and back to None `0401 01 01 01 02 00`, each re-read as `01 01 02 03` / `00`
(`local/logs/heymelody_voice_assistant_20260925.log.txt`).

`0x0B`/`0x0C` come from **`F1` byte3 during a slide**, where the same gesture resolved
per direction (see `byte3` in §6.1): a slot holding `0x07` (volume) reported `0x0B` going up
and `0x0C` going down. The PAIRING is measured; the two **names are inferred**, which is why
they are marked. `0x0A` and `0x07` are **composites** — they resolve per direction, so an
`F1` frame never reports them directly.

Unseen: `0x02`, `0x04`, `0x09`, `0x0D`–`0x10`, `0x12`+.

#### The table's SHAPE IS NOT FIXED — do not hardcode a count OR a button group

`[CAPTURE]` Both the entry COUNT and the button GROUPS a gesture lives in change, and they
differ **per bud, within a single capture**. The slide gesture, one session:

```
LEFT   19 entries  dev=0x01/btn=0x01[01 02 03 04 06 05:07]   slide bound inside btn 0x01
RIGHT  19 entries  dev=0x02/btn=0x02[05:00]                  slide SPLIT OUT, unbound
                   dev=0x02/btn=0x03[05:00]
both   20 entries  (an earlier session)                      slide split out, other side
both   18 entries  (after consolidation)                     slide back inside btn 0x01
```

**This broke slide twice, in opposite directions.** Writing to `btn 0x01` missed the split
shape; writing to `btn 0x02`/`0x03` missed the LEFT bud and produced
`NO SLOT MATCHED for dev=0x01`. **Any hardcoded button group is wrong about half the time,
so a write must take the slots FROM THE TABLE** — every entry for that `(side, action)`,
excluding only the `btn 0x06` group.

**AND THE DEVICE NORMALISES THE SPLIT SHAPE — `[CAPTURE]`, by arithmetic.** Giving the right
bud's two split halves the same function produced an ack (`0x8401`, status `00`), and the
re-read showed `btn=0x01 act=0x05 added: 0x07` with `btn=0x02 removed` and `btn=0x03 removed`
— 19 entries → 18 (=-2, +1 ✓). So the split is a **"two unbound halves" state** that the
firmware folds back into one `btn 0x01` slot once both halves agree. That is exactly why
slide appeared to work once and then never again: the first write collapsed the shape, and
every later write aimed at groups that no longer existed.

`[USER]` 2026-09-22 — **`btn 0x06` IS the on-call group.** First said from HeyMelody's own UI alone
(it shows an on-call section below the normal gestures with exactly two rows — **double tap**
(`None` / `Answer + end call`, one combined option, not two) and **long hold** (`None` / `Decline
call`)) — then settled the same day by the HCI capture below.

#### `[CAPTURE]` 2026-09-22 — the on-call write, from the same capture as the hold-mask test

He toggled both on-call rows off/on/off in HeyMelody while an HCI capture ran (Option C,
PACKET-CAPTURE.md). Both toggles landed cleanly, twice each:

```
TX  AA 0C 00 00 01 04 <seq> 05 00 01 04 06 02 1D   setKeyFunction: count=1 [dev=04 btn=06 act=02 fn=1D]
RX  AA 08 00 00 01 84 <seq> 01 00 00               ack
RX  AA 51 00 00 08 81 <seq> 4A 00 00 12 ...        0x8108 read-back: dev=0x01/btn=0x06 act=02 fn:00->1D
                                                    AND dev=0x02/btn=0x06 act=02 fn:00->1D, same write
```

Both rows, both directions, full detail:

| write payload | toggled | `act` | `fn` on/off | seen |
|---|---|---|---|---|
| `01 04 06 02 1D` / `01 04 06 02 00` | double tap | `0x02` | `0x1D` / `0x00` | twice |
| `01 04 06 06 1C` / `01 04 06 06 00` | long hold | `0x06` | `0x1C` / `0x00` | twice |

Every write is `count=1`, ONE entry, `deviceType=0x04` — not `0x01` or `0x02` — and every read-back
that followed showed the SAME `fn` land on both `dev=0x01` AND `dev=0x02`, never `0x04` itself on a
read. See `KeyFunctionParser.DEVICE_TYPE_BOTH`.

**THE BYTES ARE NOW `[CAPTURE]`; ONLY THE ENGLISH LABELS STAY `[INFERRED]`.** Which `act` is double-tap
and which is long-hold rests on the order he described the two rows in, not on an independent signal
— unlike the primary group's `function` enum (§6.1), nothing raises an `F1` frame during a call in any
capture taken so far, so there is no second data point to cross-check against. Implemented as
`OpoProtocol.setOnCallDoubleTap()` (act `0x02`) / `setOnCallLongHold()` (act `0x06`) on that
best-available reading; if a real call shows the wrong switch doing the wrong thing, swap the two
`act` values first, not the bytes. `act 0x03` is untouched in every capture — a third slot this group
has that HeyMelody's UI never exercised.

**Also `[USER]`+`[CAPTURE]`: the on-call rows bind BOTH buds together, one shared setting — not
per-bud** like the primary tap/hold group — now doubly confirmed, once from HeyMelody's UI and once
from the `deviceType=0x04` write itself. This matches what the ANC-cycle hold does (§5.1), though by a
different mechanism: the hold's mask command carries no `deviceType` field at all, while on-call's
`0x04` is a real alias value inside the normal `deviceType` field. Do not assume the two share
plumbing just because both are "shared, not per-bud".

#### THE HOLD'S FUNCTION BYTE DOES NOT CONTROL THE CYCLE

Clearing the hold's stored byte to `0x00` **does not stop the ANC cycle**: he long-pressed
right after and the noise mode still changed, with `F1` still reporting `byte3 = 0x08`.
Which modes the cycle steps through also stayed exactly the set configured in the VENDOR
app (two modes here, four earlier) — same `fn` byte both times.

So the key-function table only *describes* the hold; the cycle itself lives in the separate
**`setSupportNoiseReduction` (`0x0404`)**, payload `[action=2][noiseType][modeMask LE]`
(`OppoProtocol.LongPressNoisePayload`), read back with `0x010C` payloads
`02 01` / `02 03` / `02 04`. `[OSS]`, untested. **Not wired** — it is a different command
and folding it into the key-function save would make a failure impossible to attribute.
The read reply's 2-byte header is our own finding — see just below.

`[USER]` 2026-09-22 asked whether the hold could be given a Low/Medium/High choice, since
today it only uses whatever mode was last set by hand. **Answered: the device can, and the
vendor app does; what is missing is ours.** See "Can the app choose which ANC modes…" in
§5's Querying section for the reasoning and the required order of work.

**The `function` VALUES ARE NO LONGER UNKNOWN — they are in the table above, measured.**
This paragraph used to say they were the one blocking gap and describe two routes to find
them. Both routes were used and both are finished: route 1 (diff two readings) is what
produced the table, and route 2 (a HeyMelody capture) was never needed. Kept as a note only
so the history is not lost — **do not re-open it, and do not "re-derive" the enum.**

Note the source conflict, and its resolution: an `ai-generated/` doc said `0x0401`;
`OppoPodsManager` appeared to say **`0x0402`**. This document originally said "prefer
`0x0402`" — **that was wrong, and it cost a session.** The device settled it: `0x0402` is
ignored in total silence, `0x0401` is acked. See the IMPLEMENTED table above.

#### Route 1 — ANSWERED. The reply is readable, and the layout guess was WRONG.

`[CAPTURE]` The buds answer `0x0108`, and the reply decodes completely.

```
TX  AA 07 00 00 08 01 30 00 00        query key function (payload empty)
RX  AA 51 00 00 08 81 30 4A 00 | 00 12 ...    payLen = 0x4A = 74
```

`TotalLen` is `07` here, matching the handshake, because `TotalLen = 7 + payLen`
and this payload is empty — do not copy `08` from the command number. The seq byte
is whatever the counter was at; it is not fixed (the reply echoes it, `0x30` above).

**THE PAYLOAD HAS A LEADING STATUS BYTE THAT `KeyFunctionItem.cs` DOES NOT MODEL.**

```
<status> <count> <deviceType, button, buttonAction, function>...
  0x00    0x12      4 bytes each
```

`KeyFunctionItem.cs` describes ONE entry's bytes, not the payload wrapped around it.
Taking `payload[0]` as the count is therefore wrong: it is `0x00`, so the parser read
0 entries, shifted every entry one byte left, and printed `dev=0x12` — which is really
the count. The `!LAYOUT` guard caught it (`expected 1+0*4 bytes, got 74`) rather than
decoding garbage silently, which is why the guard exists. **Arithmetic settles the
header size:** 74 bytes with 18 entries is `2 + 18*4`, exactly; no other header size
fits. `KeyFunctionParser.HEADER_SIZE = 2`.

`[CAPTURE]` The 18 entries on Buds 4, grouped by the app (`act:fn`), entries in the
buds' own order:

```
dev=0x01/btn=0x01[01:00 02:11 03:00 04:08 06:00 05:07]
dev=0x01/btn=0x06[02:00 03:00 06:00]
dev=0x02/btn=0x01[01:00 02:00 03:00 04:08 06:00 05:07]
dev=0x02/btn=0x06[02:00 03:00 06:00]
```

`deviceType` matches the `0xF1` frame's side byte (`0x01` left, `0x02` right), and
`button` is `0x01` / `0x06` — `0x01` also being this project's button id from the
`0xF1` captures. So the two frames agree on their two shared fields.

Three places print the reply, so a capture can always be read even if the parse is
wrong: the raw `RX:` line, `BudsConnectionManager`'s `KEYFN:` line, and
`LogDecoder`'s description — **all of which end with `RAW=[...]`**.

#### THE `function` VALUES ARE NOW MEASURED — see the table above

This section used to say they were unread and that no UI may offer a function list. **Both are
obsolete.** The values were obtained by diffing two `0x8108` readings around changes made in the
vendor app, cross-checked against what he had actually bound (zero contradictions, two values
confirmed on two separate slots). They live in `GestureAction.functionByte`, and the Earbud controls
screen offers a real function list built from them.

The two hints recorded here at the time both held up: `0x00` is indeed the unbound/default value
(13 of 18 entries), and long press (`act 0x04`) is `0x08` on both buds. **Do not re-derive the
enum** — the work is done and a "refutation" was already attempted once with a bad sample.

`[USER]` **The hold is offered nothing but the ANC modes, and they CYCLE.** In
HeyMelody the hold's menu is `ANC on / adaptive / transparency / ANC off`, and a press
walks those states. `[USER]` Game mode is offered on double and triple tap ONLY.

Inference from that, ours and not his — kept separate on purpose: **`fn 0x08` is
therefore a CYCLE, not a mode**, and the four ANC menu rows are probably **one `fn`
value, not four**. The reason is structural: an entry is 4 bytes
(`deviceType, button, buttonAction, function`) with no room for a mode list, and
`dev=0x01/btn=0x01` carries exactly ONE `act 0x04` entry, so whatever the cycle
contains is not in this reply. That predicts the enum is closer to *menu rows* than
to *ANC modes* — so do not go looking for four ANC values in it.

This also decides the diff below: the first value we should expect to learn is a
menu-row value (Game Mode), not an ANC mode value.

The `buttonAction` NUMBERS are also unmapped, and they are **NOT** the `0xF1` action
numbers: `F1` calls single tap `0x00` and long press `0x04`, while this reply binds
`0x01` as well as `0x04`. `[GUESS]` `1..6` could be single, double, triple, long
press, slide up, slide down — but that is a guess and stays one. `[USER]` **It is
consistent with the menu he sees:** double and triple tap each accept game mode,
which is why `act 0x02` and `act 0x03` both appear under `btn 0x01` with values,
while the hold row is a single cycle entry.

#### HOW THE ENUM WAS MEASURED — the diff that finished it (DONE 2026-09-22)

This section used to be a plan. **It was carried out, and it is where the `function` table above
comes from.**

`0x8108` describes the CURRENT binding, so **DIFF TWO READINGS**: change ONE gesture in the vendor
app, reconnect our app, and compare the `KEYFN:` lines. Whichever `fn` byte moves for that `act` is
that function's value, and each further action can be learned the same way. This is why the reply is
printed GROUPED per `dev/btn` — the single entry that changed is meant to be visible at a glance.

**Do not re-run this to "verify" the table.** It has been done, the values are in `GestureAction`,
and a re-derivation was already attempted once with a bad sample and drew a wrong conclusion (§6.1).
The method below is kept for the next genuinely-unknown value, not for this enum.

**Pick the change from the UNBOUND side, not the bound one.** The reply above has
`fn=0x00` on 13 of its 18 entries and only three distinct non-zero values, so a
change made from `0x00` is unambiguous in a way that swapping two bound values is
not. `[USER]` Game mode is offered on double and triple tap, and **right** double tap
currently reads `fn=0x00` — so setting right double tap to Game Mode is the cleanest
first diff: one `0x00` should move, and the value it becomes is Game Mode.

Two traps that were live at the time, kept because they generalise to any future diff — not
just this one:

- **Do not diff the hold.** It offers only the ANC cycle (§6.1), so the `fn` byte
  cannot vary and a "no change" result would tell us nothing while looking like one.
- `[USER]` **`fn` may not be 1:1 with the menu label.** "ANC on" is ambiguous on the
  device itself: he expects it re-applies the last *manually* chosen level, which is
  exactly what §5's captures show (`0x0040`, then `0x0010` / `0x0020` on other days).
  So a diff that moves for "ANC on" names the *slot*, not a level — do not write down
  "0xNN = ANC on" as if it were a level constant.

**Note the write path IS built now.** `0x0401` is defined as a constant and the app writes the full
key-function table back (`BudsConnectionManager.writeGestureBinding`). `buildPacket()` still lacks
real LEB128 `TotalLen` (§2), but no frame the app builds reaches 127 bytes, so it has never been
needed in practice.

---

## 7. Battery — `0x8106` and `0x0204` subType `0x01`

`[OSS]` Payload is `[index][rawValue]` triples: `01LL 02RR 03MM`.

- index `01` left, `02` right, `03` case.
- level = `raw & 0x7F`
- charging = `(raw & 0x80) != 0`

`[CAPTURE]` Example: `... 03 01 64 02 64 03 50` → all three at `0x64` = 100, case
`0x50` = 80.

`[CAPTURE]` 2026-09-25 **case charging** (logcat, Buds 4, one bud in the case): plugging the case in
with the lid **open** makes the buds push a fresh `0x0204` subType `01` with the charge bit set on the
case, `03 A8` (40%, charging; it was `03 28` before), next to a wear push with no wear change. With
the lid **closed** nothing is pushed, and a `0x0106` query returns only the bud outside the case
(`01 01 5A`): the case and the bud inside it are silent until the lid opens. Unplugging was not
captured.

## 8. Wearing / in-case — `0x8109` and `0x0204` subType `0x02`

`[OSS]` Payload is `[count][component, status] × count`.

| component | Meaning |
|-----------|---------|
| 1 | Left bud |
| 2 | Right bud |
| 3 | Case |

| status | Meaning |
|--------|---------|
| `0` | disconnected |
| `1`, `5` | off-ear / idle |
| `3`, `7` | **in ear** |
| `4` | **in case** |

`[CAPTURE]` 2026-09-25 **case lid** (`local/logs/heymelody_case_lid_20260925.log.txt`): while the buds are
reachable the case entry is always `4`. Closing the lid is announced ~1 s before the socket drops:
the pushes fall to `0`, one bud first, then `01 00 02 00 03 00` (all zero). Seen on both closes.
Opening the lid brings the buds back with `04 04 04`; the case battery (`03 xx` in `0x0204` subType `01`)
is only sent then. So there is no lasting "closed" state to show, but the all-zero push tells a lid
close apart from a lost link: `BudsConnectionManager` logs `Case closed` and skips the reconnect
retries (ACL_CONNECTED reconnects when the lid opens). Charging: see §7.

`[CAPTURE]` Buds 4 query responses sometimes prepend a status byte, so
`WearingStatusParser` tries offset 0 and offset 1 and keeps the first layout that
yields plausible pairs.

---

## 9. Feature switches — `0x0403`

Payload `[featureId, status]`, status `0x01` on / `0x00` off.

`[OSS]` From `OppoPodsManager` `Protocol/OppoProtocol.Features.cs`, which maps
feature IDs to vendor method names:

| ID | Feature | | ID | Feature |
|----|---------|-|----|---------|
| `0x04` | Wear detection / auto play-pause | | `0x1B` | **Spatial sound** |
| `0x06` | **Game mode (low latency, legacy)** | | `0x27` | Game sound |
| `0x09` | Vocal enhance | | `0x28` | **Game mode (main, newer)** |
| `0x0B` | Hearing enhance | | `0x30` | Adaptive volume |
| `0x11` | **Dual device** | | `0x31` | Adaptive ear |
| `0x18` | **Hi-Res (LHDC 96/192 kHz, 400 kbps)** `[CAPTURE]` | | `0x3A` | Sleep detection |

`[OSS]` **Worth knowing:** newer devices put game mode on `0x28`, older on `0x06`.
This project uses `0x06`. If game mode misbehaves on a different model, that is
the first thing to try.

### Batch status query — `0x010D`

`[CAPTURE]` The app polls:

```
TX  AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
                                                     ^^ ^^ ^^ ^^...
RX  AA 17 00 00 0D 81 00 10 00 00 07 05 01 04 00 0B 01 11 01 18 01 06 00 1B 00
```

`[CAPTURE]` 2026-09-23 — **layout confirmed**: request is `[count][featureId...]`, reply is
`[status=00][count][featureId][value]...`. Only supported ids come back (ours asks 11, gets 7:
`05 04 0B 11 18 06 1B`). HeyMelody asks for `05 04 0B 11 18 06 1B 1D` and gets `1D` too. Every
toggle below moved exactly its own pair. Parsed by `BudsConnectionManager` into `featureStates`
and logged as `FEATURES:`. `0x05`, `0x0B`, `0x1D` values seen (`01`) but unassigned here.

### Spatial sound (`0x1B`) and Hi-Res codec (`0x18`) — `[CAPTURE]` 2026-09-23

HeyMelody btsnoop, his actions: spatial toggled while on AAC, Hi-Res on, spatial on from Hi-Res.

```
TX 0403 1B 01 / 1B 00                   spatial on/off — acked 8403 00, no reconnect (AAC only)
TX 0403 1B 01, TX 0403 18 00            spatial ON while Hi-Res on: spatial first, then codec off
TX 0403 1B 00, TX 0403 18 01            Hi-Res ON while spatial on: spatial off first, then codec on
```

- **Mutually exclusive.** Hi-Res (LHDC) and spatial never coexist; HeyMelody switches the other off,
  in the order above, behind an Accept/Decline warning.
- **Any `0x18` change drops the link** — the buds reconnect ~4 s later (fresh `0x0100` handshake).
- `0x0422` (three-mode spatial) is **not** what this firmware's HeyMelody sends.
- **`0x18` is a quality switch, not a codec switch** `[CAPTURE]` (phone `dumpsys bluetooth_manager`,
  2026-09-25, same session, music playing). The codec is LHDC V5 either way; the phone picks it.
  What changes is what the buds advertise for LHDC V5:

  | | `0x18` = 01 (Hi-Res on) | `0x18` = 00 (off) |
  |---|---|---|
  | Sample rates offered | 44.1 / 48 / 96 / 192 kHz | 44.1 / 48 kHz |
  | LHDC ABR bitrate cap | 400 kbps | 256 kbps |
  | Stream the phone chose | 48 kHz / 24-bit | 48 kHz / 24-bit |

  AAC and SBC are offered in both states. No LDAC on these buds. The phone's own per-device HD-audio
  switch is a separate, system-side setting.

### Equalizer — custom presets, READ side only — `[CAPTURE]` 2026-09-23 (from the codec capture)

HeyMelody's bulk settings read (`TX 0x2F00`, a list of 4-byte item requests) comes back as `0x812F`
/ `0x2F00` frames of `[id][?][len LE]` items. Item **`0x22`** (same number as `0x0122` "EQ all")
is the custom EQ list, 87 bytes, decoding with nothing left over:

```
22 01 57 00                                  item 0x22, len 0x57
00 03                                        ?, preset count = 3
  <flag> FA 06 <id> <nameLen> <name> 06 [freq u16 LE, gain s8] x6
```

| name | id | flag | 62 | 250 | 1k | 4k | 8k | 16k |
|---|---|---|---|---|---|---|---|---|
| flat | 04 | 00 | −5 | −5 | 0 | −3 | +2 | +6 |
| xdd | 05 | 00 | −1 | −1 | −2 | −3 | +2 | +6 |
| reddit | 06 | **01** | +2 | −1 | 0 | +2 | +3 | −3 |

**`[USER]` confirmed against HeyMelody screenshots 2026-09-23:** every name and gain matches, and
flag `01` is the SELECTED preset (reddit). Frequencies are HeyMelody's six bands; gains are signed dB,
range ±6. `FA 06` unknown (constant). BassWave was on, level 2, at the time — not located in the reply yet. Built-in presets (Balanced / Clear Vocals / Bass) are not
in this item. **No EQ write captured yet** — `0x0406` "set EQ" is `[OSS]` only.

### Equalizer — WRITE side — `[CAPTURE]` 2026-09-23 (HeyMelody, his actions in order)

```
TX 0406 00 / 01 / 02        select built-in: Balanced / Clear Vocals / Bass   ack 8406 00
RX 0504 <mode>              push: EQ mode changed (built-in or custom id)
TX 010F  ->  810F 00 <id>   current EQ: 00-02 built-in, 04-06 custom (reddit = 06)
TX 0122  ->  8122 00 <count> <presets...>   custom list, same layout as item 0x22 above
TX 0418 02 FA 06 <id> <nameLen> <name> 06 [freq,gain]x6   ack 8418 00 <id>
                            select / edit / rename a custom preset — the WHOLE preset every time
TX 0403 1D 00 / 1D 01       BassWave off / on (feature 0x1D, also in the 0x810D reply)
TX 041B FB 05 <level>       BassWave level; he set 5 = max           ack 841B 00
TX 0124  ->  8124 00 FB 05 <level>   BassWave level read (was 02 before)
```

- **`0x0418` is select + save in one.** Opening xdd for editing sent it (xdd became selected). Every
  step of a band drag sent the full preset again (1 kHz went FE -> FD -> FF -> 00), and a rename is
  the same frame with a new name (`07 "testing"`). HeyMelody re-reads `0x0122` after each.
- **`0x0418`'s first byte is the action** (`[CAPTURE]` 2026-09-23, second EQ capture): `01` CREATE
  (HeyMelody sent id `00`, name `Custom1`, all gains 0; the buds assign the id — ack `8418 00 06`),
  `02` SAVE/SELECT, `03` DELETE (whole preset sent; the selection falls back to `00` Balanced).
  **Ids are renumbered** after a delete/create (xdd went 05 -> 04), so always re-read `0x0122`.
  HeyMelody's UI caps custom presets at **3** (`[USER]`).
- **Built-in presets are not in the `0x0122` list**; they are ids 00-02 on `0x0406` / `0x010F`.
- `FA 06` and `FB 05` are constant in every frame; meaning unknown (`05` may be the level max).

### Find my earbuds — `0x0400` — `[CAPTURE]` 2026-09-23, wired

`TX 0400 01` / `TX 0400 00`, acked `8400 00`, alternating 3 times — matches his 3 start/stop cycles.
No side byte in the payload. HeyMelody sent `0x0114` (reply `8114 00 08`, meaning unknown) just before
and after the session. `[USER]`: it rings BOTH buds at once, and HeyMelody warns first when the
buds are in the ears (the tone is loud). `OpoProtocol.findTone()` / `FindBudsActivity` do the same.

### Auto play/pause (`0x04`) and alert-sound volume (`0x0427`) — `[CAPTURE]` 2026-09-25, wired

HeyMelody HCI capture (`local/logs/heymelody_autoplay_alertvol_20260925.log.txt`), his actions in order.

- **Auto play/pause** on>off>on>off sent `0403 04 01`, `04 00`, `04 01`, `04 00`, each acked
  `8403 00` and followed by a `0x010D` read whose `04` value flipped to match. The plain feature
  switch, nothing special. `WearActivity` writes it through `setFeatures`.
- **Alert-sound volume** (the buds' own prompt tones) — mid>lowest>max>lowest>max sent
  `0427 05`, `01`, `0A`, `01`, `0A`. One byte, range **1..10**. The ack is `8427 00 <level>`, and
  HeyMelody re-reads after every change with `0130` (empty) → `8130 00 <level>`. The value read
  before any change was `08`. Our slider sends only on release, so each change plays one prompt.

Our own **smart auto-pause** (pause only when both buds leave the ears) is not a firmware
feature. `BudsService` sends a media pause key when the last bud goes from wearing (3/7) to
anything else. It and the firmware switch are mutually exclusive in the UI.

### Dual connection (`0x11`) and the device list (`0x0112`) — `[CAPTURE]` 2026-09-25, wired

HeyMelody HCI capture made by the agent (`local/logs/heymelody_dual_connection_20260925.log.txt`):
screen opened, switch off, switch on.

```
TX 0112                                   read device list (empty payload)
RX 8112 00 02 77 da 74 70 f3 5c 12 02 00 0f "DESKTOP-8IN3GA6"
              1b 37 61 ed b0 3c 15 02 01 12 "Nothing Phone (3a)"
TX 0403 11 00 -> 8403 00, TX 010D (11 now 00), TX 0413 08 00 01 -> 8413 00      switch OFF
RX 0204 06 02 ...DESKTOP... 12 00 00 ...  ...Phone... 15 02 01 ...          desktop state -> 00
TX 0403 11 01 -> 8403 00, TX 010D (11 now 01), TX 0413 08 00 00 -> 8413 00      switch ON
RX 0204 06 02 ...DESKTOP... 12 02 00 ...                                    desktop back, ~1.5 s
```

- **Device list** — reply `[status 00][count]`, push `0x0204` subType `06` then `[count]`; each
  entry is `[MAC, 6 bytes reversed][?][state][?][nameLen][name UTF-8]`. State `02` = connected,
  `00` = not; a dropped device stays in the list with `00`, and HeyMelody shows only connected
  ones. The two `?` bytes (`12`/`15`, `00`/`01`) are undecoded — the `01` sits on this phone, but
  one sample is not proof, so the app finds "this device" by the phone's Bluetooth name.
- **`0x0413 08 00 xx`** follows every toggle, `01` after OFF and `00` after ON (HeyMelody also
  sends `08 00 00` when the screen opens). Meaning unknown; `setDualDevice` replays it verbatim.
- "Add device" in HeyMelody is only pairing instructions; tapping a device row does nothing.

### Time request (`0x0500`) — `[CAPTURE]` 2026-09-25, not answered by us

When the second device reconnected, the buds sent `0x0501` and `0x0500`, both empty. HeyMelody
answered `0x0500` with `8500 00 e0 72 b6 6a`: status, then **Unix seconds, u32 LE** (`0x6AB672E0` =
13:10:56 UTC, the frame's own time to the second), then sent `040F 01` (unknown). `0x0501` got no
reply. **QuickBuds does not answer it, by decision** (2026-09-25): no feature is known to depend on it,
and no OSS client answers it either — OppoPodsManager files `0x0500`–`0x05FF` as "RequestCommandManager"
status events and only logs them.

---

## 10. Debugging: what the log lines mean

| Line | Meaning |
|------|---------|
| `RX:` / `TX[...]:` | Raw hex in/out |
| `BTN EVT:` | `0x0204` subType `0xF1` decoded — a gesture happened |
| `ANC EVT:` | `0x0204` subType `0x03` decoded — the ANC mode, from the buds |
| `GAME EVT:` | `0x0204` subType `0x05` decoded — game mode, from the buds |
| `WEAR EVT:` / `WEAR QRY:` | Wearing push / reply |
| `KEYFN:` | `0x8108` reply decoded — the current gesture bindings. Ends with `RAW=[...]` |
| `UNATTR RX:` | A frame we do not decode, with its payload head |
| `ancFlush=` | Whether we had *just* written an ANC command — attribution aid |

### `UNATTR RX` has a blind spot — remember it

`noteUnattributed()` **blanket-excludes cmd `0x0204`**. So an undecoded `0x0204`
subType produces **no `UNATTR RX` line at all**. This is exactly what made ANC
look like silence for three separate captures.

**If a capture needs to see undecoded `0x0204` frames, change that exemption
first.** The Dev Tools decoder *does* show them
(`Unattributed active report: subType=0x.. [...]`), so the information exists —
it just is not in the raw log.

### Undecoded families worth chasing

`[CAPTURE]` `0x0501` / `0x0500` appear right after ANC writes:

```
AA 07 00 00 01 05 01 00 00
AA 07 00 00 00 05 02 00 00
```

Note these are cmd `0x0501`/`0x0500` — a **different family** from
`0x0204 subType 0x05` (game mode). No payload has been observed. **`0x0500` is a time
request** (§9, "Time request"); `0x0501` is still unknown.

---

## 11. Method: how to learn a new command safely

This sequence is what has actually worked, and skimping on it has cost whole
sessions:

1. **Read `CREDITS.md` / the source repos first.** Three of the last four
   protocol mistakes were settled for free by an existing document. Fastest path:
   `https://raw.githubusercontent.com/<owner>/<repo>/main/<path>`, file list via
   `https://api.github.com/repos/<owner>/<repo>/git/trees/main?recursive=1`.
2. **Check `0x8200`** — does the firmware even claim to support this?
3. **Subscribe** to the relevant broadcast code, or you will see silence and
   mistake it for absence.
4. **Capture with a known starting state**, and have him say in words what he did
   and in what order. The ANC mapping was only pinned down once the capture began
   from a mode we had set ourselves.
5. **Never guess an enum.** Log the raw value and let the device name it.
6. **Write the brief before capturing.** The capture brief worked because it
   predicted a small number of named outcomes and said exactly which lines to send.
   The procedure now lives in [PACKET-CAPTURE.md](./PACKET-CAPTURE.md).

### Things that look like bugs and are not

- The first RFCOMM UUID failing.
- Two `BTN EVT` frames plus two state events for one physical double-tap: the
  firmware **double-reports**. The app faithfully follows, which looks like a
  flap. Do not "fix" it in the parse path.
- ANC writing and nothing appearing to change: check the **SET** table (§5) before
  suspecting the buds.

---

## 12. Open questions

- **The hold's mode list — CLOSED 2026-09-22, wired UI verified on-device.** Read, bit theory, and
  write are all `[CAPTURE]`-confirmed (§5) — first from HeyMelody's own capture, then a second time
  by sending `mask=0x0006` and `mask=0x0807` from our own hold picker, both acked and read back
  exactly. Implemented: `OpoProtocol.setHoldAncModes()`, `BudsConnectionManager.sendHoldAncModes()`,
  `GestureAction.holdMaskBit`. Still open: bit 1's meaning in isolation (every capture so far shows it
  set; no test has cleared it alone). `AncEventParser` mislabeling the mask write's own `0x0204` echo
  as a mode change — a real bug, not cosmetic, it corrupted the persisted ANC display — is FIXED (§5).
- **On-call gestures — CLOSED 2026-09-22 for the bytes and the write, open for the labels.** `btn
  0x06`'s write shape is `[CAPTURE]`-confirmed (§6, "the on-call write") and re-verified by sending it
  from our own app: `act 0x02`/`0x06`, `fn 0x1D`/`0x1C`, `deviceType 0x04` for both buds, acked and
  read back exactly matching HeyMelody's own bytes. Implemented: `OpoProtocol.setOnCallDoubleTap()`/
  `setOnCallLongHold()`, the "When on call" section in `GestureActivity`. Still `[INFERRED]`: which
  `act` is double-tap vs long-hold — needs a real call to confirm the right switch does the right
  thing. `act 0x03` in the same group is still completely unknown.
- What `0x0501` / `0x0500` are.
- Broadcast codes `0x04`, `0x08`, `0x0B`.
- The `0x8205` ack layout (§4) — only one sample, and it does not obviously echo
  the request.
- Whether `0x0404` supports the `type=2` level-setting form `01 02 <level>` (the
  `[OSS]` doc mentions it for "set noise reduction info") — unverified here.
- Case **lid**: no state of its own, but a close is announced by an all-zero wear push just
  before the socket drops (§8). Case charging is reported only while the lid is open (§7).
