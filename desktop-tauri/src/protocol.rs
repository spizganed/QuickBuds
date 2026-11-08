//! The wire format (docs/PROTOCOL.md section 2): `AA <TotalLen LEB128> 00 00 <cmd lo> <cmd hi> <seq> <len lo> <len hi> <payload>`.
//! TotalLen counts everything after itself: 7 + payload.
//! Framing, decoders and answers of the old link. `newproto.rs` bridges the new protocol in `buds_core`; the tests use it. The old builders also stay as the
//! reference of the core tests; the senders use `buds_core::cmd`. Shared ids and types are in `types.rs`.
#![allow(dead_code)]

use crate::types::*;
use serde_json::Value;

/// `0x0404 01 01 <bit as LE bitmask>`; the bit is the model's SET `slot`, never a list position (PROTOCOL.md section 14).
pub fn anc_payload(bit: u8) -> Vec<u8> {
    let mut p = vec![0; 2 + bit as usize / 8 + 1];
    p[0] = 1;
    p[1] = 1;
    p[2 + bit as usize / 8] = 1 << (bit % 8);
    p
}

/// `0x0205` payload: count, then the ids.
pub fn register_payload(ids: &[u8]) -> Vec<u8> { [&[ids.len() as u8][..], ids].concat() }

/// `0x8200` = `00 <count> <codes>`.
pub fn offered_events(pl: &[u8]) -> Option<Vec<u8>> {
    match pl { [0, n, codes @ ..] => codes.get(..*n as usize).map(<[u8]>::to_vec), _ => None }
}

/// `<codec> <hiRes> 00`: Hi-Res only with LDAC (3) or LHDC V5 (8), as HeyMelody sends it.
pub fn codec_payload(codec: u8, hires: bool) -> [u8; 3] { [codec, (hires && (codec == 3 || codec == 8)) as u8, 0] }

/// `0x8103` `00 <id LE, 3 bytes>` -> "065414", old OnePlus ids folded as `ModelCatalog.normalise` does.
pub fn product_id(payload: &[u8]) -> Option<String> {
    if payload.len() != 4 || payload[0] != 0 { return None; }
    let id = u32::from_le_bytes([payload[1], payload[2], payload[3], 0]);
    let id = match id {
        0x100100..=0x100102 => 0x060414,
        0x100200..=0x100202 => 0x060814,
        0x108100..=0x108102 => 0x068414,
        0x108200..=0x108202 => 0x068814,
        x => x,
    };
    Some(format!("{id:06X}"))
}

/// `0x8122` payload: `<status> <count>` then `<flag> <tag 2B> <id> <nameLen> <name> <bands> [freq, gain]...`.
pub fn parse_presets(p: &[u8]) -> Option<Vec<Preset>> {
    if p.len() < 2 || p[0] != 0 { return None; }
    let mut o = 2;
    let mut out = Vec::new();
    for _ in 0..p[1] {
        let head = p.get(o..o + 5)?;
        let name_len = head[4] as usize;
        let name = String::from_utf8_lossy(p.get(o + 5..o + 5 + name_len)?).into_owned();
        o += 5 + name_len;
        let bands = *p.get(o)? as usize;
        o += 1;
        let data = p.get(o..o + bands * 3)?;
        o += bands * 3;
        out.push(Preset {
            id: head[3],
            name,
            freqs: data.chunks_exact(3).map(|c| u16::from_le_bytes([c[0], c[1]])).collect(),
            gains: data.chunks_exact(3).map(|c| c[2] as i8).collect(),
            selected: head[0] == 1,
            tag: [head[1], head[2]],
        });
    }
    Some(out)
}

/// `0x8105` text `1,2,138,2,2,138,3,1,01,3,2,105` -> "138.138.105" (the `versionType` 2 entries).
pub fn firmware_version(text: &str) -> Option<String> {
    let parts: Vec<&str> = text.split(',').collect();
    let v: Vec<&str> = parts.chunks(3).filter(|c| c.len() == 3 && c[1].trim() == "2").map(|c| c[2].trim()).collect();
    (!v.is_empty()).then(|| v.join("."))
}

/// 12 x `<side> <freq> <value>`: left 1..6 then right 1..6.
fn hearing_info(values: &[i8; 12]) -> Vec<u8> {
    (0..12).flat_map(|i| [i as u8 / 6 + 1, i as u8 % 6 + 1, values[i] as u8]).collect()
}

/// Ear scan start / stop `04 01|00 <uid>`; the result is push `0E`.
pub fn ear_scan(on: bool, uid: u32) -> Frame { (CMD_GOLDEN_DETECT, [&[4, on as u8][..], &uid.to_be_bytes()].concat()) }

pub fn hearing_test(on: bool) -> Frame { (CMD_GOLDEN_DETECT, vec![2, on as u8]) }

/// A tone on one bud, side 1 L / 2 R, freq 1..6; `tone_stop` goes before every new level.
pub fn hearing_tone(side: u8, freq: u8, value: i8) -> Frame { (CMD_HEARING_RECORD, vec![3, 1, side, freq, value as u8]) }

pub fn hearing_tone_stop() -> Frame { (CMD_HEARING_RECORD, vec![4]) }

/// The filter of a result; the `0x8116` reply carries the enhance type.
pub fn hearing_filter(uid: u32, values: &[i8; 12]) -> Frame {
    (CMD_HEARING_FILTER, [vec![0x0C], hearing_info(values), uid.to_be_bytes().to_vec()].concat())
}

pub fn hearing_scan_filter(uid: u32, data: &[u8]) -> Frame {
    (CMD_HEARING_SCAN_FILTER, [&(data.len() as u16).to_le_bytes()[..], data, &uid.to_be_bytes()].concat())
}

/// Applies a record: description id, record, ear scan (if any), then the switch on (HeyMelody's order).
pub fn hearing_apply(uid: u32, name: &str, values: &[i8; 12], scan: &[u8], desc: u8) -> Vec<Frame> {
    let mut out = Vec::new();
    if desc > 0 { out.push((CMD_HEARING_RESTORE, vec![1, 1, 1, 0, desc])); }
    out.push((CMD_HEARING_RECORD, [vec![3, 0x0C], hearing_info(values), uid.to_be_bytes().to_vec(), name.as_bytes().to_vec()].concat()));
    if !scan.is_empty() {
        out.push((CMD_HEARING_SCAN_DATA, [&[3][..], &(scan.len() as u16).to_le_bytes(), scan, &uid.to_be_bytes()].concat()));
    }
    out.push((CMD_SET_FEATURE, vec![FEATURE_HEARING, 1]));
    out
}

fn be32(p: &[u8], i: usize) -> Option<u32> { Some(u32::from_be_bytes(p.get(i..i + 4)?.try_into().ok()?)) }

fn le16(p: &[u8], i: usize) -> Option<usize> { Some(u16::from_le_bytes(p.get(i..i + 2)?.try_into().ok()?) as usize) }

/// `0x8115` `00 03 0C <12 x side freq value> <uid> <name>`.
fn parse_hearing_active(p: &[u8]) -> Option<HearingEv> {
    if p.len() < 3 || p[0] != 0 || p[2] != 12 { return None; }
    let end = 3 + 36;
    let uid = be32(p, end)?;
    let mut values = [0i8; 12];
    for c in p[3..end].chunks_exact(3) {
        if !(1..=2).contains(&c[0]) || !(1..=6).contains(&c[1]) { return None; }
        values[(c[0] as usize - 1) * 6 + c[1] as usize - 1] = c[2] as i8;
    }
    Some(HearingEv::Active(uid, String::from_utf8_lossy(&p[end + 4..]).into_owned(), values))
}

/// `<action> <length LE> <data> <uid>` from `from` (push `0E` after its id, `0x811E` after its status).
fn parse_hearing_scan(p: &[u8], from: usize) -> Option<(u32, Vec<u8>)> {
    let len = le16(p, from + 1)?;
    let start = from + 3;
    if len == 0 { return None; }
    Some((be32(p, start + len)?, p[start..start + len].to_vec()))
}

/// Filter replies, both ears (type `04`), floats LE, left half then right:
/// `0x8116` `00 <uid> 04 <count LE> <packet> <enhance type> <floats>`,
/// `0x811F` `00 <uid> 04 <rate LE> <count LE> <packet> <floats>`. Returns (rate, left, right).
fn parse_curves(p: &[u8], scan: bool) -> Option<Curves> {
    if p.len() < 11 || p[5] != 4 { return None; }
    let fs = if scan { le16(p, 6)? as u32 } else { 44100 };
    let count = if scan { le16(p, 8)? } else { le16(p, 6)? };
    let start = if scan { 11 } else { 10 };
    if count == 0 || count % 12 != 0 { return None; }
    let f: Vec<f32> = p.get(start..start + count * 4)?.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect();
    Some((fs, f[..count / 2].to_vec(), f[count / 2..].to_vec()))
}

/// `0x0401 <count> <entries>`.
pub fn key_function_payload(table: &[[u8; 4]]) -> Vec<u8> {
    std::iter::once(table.len() as u8).chain(table.iter().flatten().copied()).collect()
}

/// `0x0404 02 <type> <mask LE, 1-4 bytes>`: which modes the hold cycles (the mask uses SET_ANC's bits).
pub fn hold_modes_payload(mask: u32, kind: u8) -> Vec<u8> {
    let len = (1..=4).rev().find(|&n| n == 1 || mask >> ((n - 1) * 8) & 0xFF != 0).unwrap();
    [&[2, kind][..], &mask.to_le_bytes()[..len]].concat()
}

/// `<count>`, then `<MAC reversed, 6> <len> <state> <flags> <nameLen> <name>` per device; state `02` connected.
pub fn parse_devices(b: &[u8]) -> Option<Vec<PairedDevice>> {
    let (&count, mut rest) = b.split_first()?;
    let mut out = Vec::new();
    for _ in 0..count {
        let h = rest.get(..10)?;
        let n = h[9] as usize;
        let name = String::from_utf8_lossy(rest.get(10..10 + n)?).into_owned();
        let mut mac: [u8; 6] = h[..6].try_into().ok()?;
        mac.reverse();
        out.push(PairedDevice { mac, name, connected: h[7] == 2, this_device: h[8] & 1 != 0 });
        rest = &rest[10 + n..];
    }
    Some(out)
}

/// `<a> <b>` pairs with no count byte.
fn pairs_n(b: &[u8]) -> Vec<(u8, u8)> { b.chunks_exact(2).map(|c| (c[0], c[1])).collect() }

fn pairs(b: &[u8]) -> Option<Vec<(u8, u8)>> {
    let (&count, rest) = b.split_first()?;
    (rest.len() >= count as usize * 2).then(|| rest.chunks_exact(2).take(count as usize).map(|c| (c[0], c[1])).collect())
}

/// 1-4 bytes little-endian (`NoiseReductionInfo`).
fn le(b: &[u8]) -> u32 { b[..b.len().min(4)].iter().rev().fold(0, |v, &x| v << 8 | x as u32) }

/// PROTOCOL.md section 13: status bits 0 out of the case, 1 in ear, 2 lid open; bit 3 is a flag we drop.
/// PROTOCOL.md section 13: a wearing list has parts and every part is 1 (left), 2 (right) or 3 (case).
fn valid_wear(v: &Vec<(u8, u8)>) -> bool { !v.is_empty() && v.iter().all(|x| (1..=3).contains(&x.0)) }

fn wear_bits(v: Vec<(u8, u8)>) -> Vec<(u8, u8)> { v.into_iter().map(|(i, st)| (i, st & 7)).collect() }

pub fn decode(p: &[u8]) -> Option<Event> {
    let pl = payload_of(p);
    let sub = pl.first().copied();
    Some(match cmd_of(p) {
        0x8100 => Event::Caps(Caps::parse(pl)?),
        0x8103 => Event::ProductId(product_id(pl)?),
        0x8200 => Event::Offered(offered_events(pl)?),
        // `[status][count][pairs]`
        0x8106 if sub == Some(0) => Event::Battery(battery(&pl[1..])?),
        0x8109 => {
            // `[count][pairs]`, or `[status][count][pairs]` (WearingStatusParser tries both).
            Event::Wear(wear_bits(pairs(pl).filter(valid_wear).or_else(|| pairs(pl.get(1..)?).filter(valid_wear))?))
        }
        // `[status][01 01][value LE, 1-4 bytes]`
        0x810C if pl.len() >= 4 && pl[1] == 1 && pl[2] == 1 => {
            Event::AncRaw(pl[3..pl.len().min(7)].iter().rev().fold(0, |v, &b| v << 8 | b as u32))
        }
        0x810C if pl.len() >= 4 && pl[1] == 4 && pl[2] == 1 => Event::SmartLevel(le(&pl[3..])),
        0x810C if pl.len() >= 4 && pl[1] == 2 => {
            Event::HoldMask(pl[2], pl[3..pl.len().min(7)].iter().rev().fold(0, |v, &b| v << 8 | b as u32))
        }
        // A reply with no entries never replaces a table (a write is built from it).
        0x8108 if sub == Some(0) && pl.len() >= 6 => Event::KeyFunctions(pl[2..].chunks_exact(4).map(|c| [c[0], c[1], c[2], c[3]]).collect()),
        0x810D if sub == Some(0) => Event::Features(pairs(&pl[1..])?),
        0x8130 | 0x8427 if sub == Some(0) && pl.len() >= 2 => Event::AlertVolume(pl[1]),
        0x8133 if sub == Some(0) && pl.len() >= 3 => Event::TapLevel(pl[1], pl[2]),
        0x811A if sub == Some(0) && pl.len() >= 2 => Event::PncStored(pl[1] != 0),
        0x8412 if !pl.is_empty() => Event::PncAck(pl[0]),
        0x840D | 0x840E if pl.first().is_some_and(|&s| s != 0) => Event::Hearing(HearingEv::Refused(pl[0])),
        0x8134 if sub == Some(0) && pl.len() >= 2 => Event::HeadMotion(pl[1]),
        0x812A if sub == Some(0) && pl.len() >= 2 => Event::SpatialType(pl[1]),
        EVT_SPATIAL_TYPE if !pl.is_empty() => Event::SpatialType(pl[0]),
        0x8114 if sub == Some(0) && pl.len() >= 2 => Event::Codec(pl[1]),
        0x8123 if sub == Some(0) && pl.len() >= 3 => Event::Codecs(codecs_of(u16::from_le_bytes([pl[1], pl[2]]))),
        0x812B if sub == Some(0) && pl.len() >= 3 => Event::GameSound(pl[1], pl[3..pl.len().min(3 + pl[2] as usize)].to_vec()),
        // `00 <count>` + UTF-8 `deviceType,versionType,version` triples
        0x8105 if pl.len() > 2 => Event::Firmware(firmware_version(&String::from_utf8_lossy(&pl[2..]))?),
        0x810F if sub == Some(0) && pl.len() >= 2 => Event::EqCurrent(pl[1]),
        EVT_EQ_CHANGED if !pl.is_empty() => Event::EqCurrent(pl[0]),
        0x8122 => Event::EqCustom(parse_presets(pl)?),
        0x8115 => Event::Hearing(parse_hearing_active(pl)?),
        0x811E if sub == Some(0) => { let (u, d) = parse_hearing_scan(pl, 1)?; Event::Hearing(HearingEv::ActiveScan(u, d)) }
        0x8116 if sub == Some(0) && pl.len() >= 10 => Event::Hearing(HearingEv::Filter(be32(pl, 1)?, pl[9], parse_curves(pl, false))),
        0x811F if sub == Some(0) => Event::Hearing(HearingEv::ScanCurves(be32(pl, 1)?, parse_curves(pl, true)?)),
        0x8112 if sub == Some(0) => Event::Devices(parse_devices(&pl[1..])?),
        0x8132 if pl.len() >= 3 && pl[0] == 0 && pl[1] == 2 => Event::Preferred(if pl[2] == 0 { None } else { Some(pl.get(3..9)?.try_into().ok()?) }),
        // `00 FB 05 <level>`
        0x8124 if sub == Some(0) && pl.len() >= 4 => Event::BassLevel(pl[3] as i8),
        EVT_PUSH => match sub? {
            0x01 => Event::Battery(battery(&pl[1..])?),
            0x02 => Event::Wear(wear_bits(pairs(&pl[1..]).filter(valid_wear)?)),
            // `03 01 01 <mode LE, 1-4 bytes>` (PROTOCOL.md §14)
            0x03 if pl.len() >= 4 && pl[1] == 1 && pl[2] == 1 => Event::AncRaw(le(&pl[3..])),
            0x03 if pl.len() >= 4 && pl[1] == 4 && pl[2] == 1 => Event::SmartLevel(le(&pl[3..])),
            0x05 if pl.len() >= 2 => Event::GameMode(pl.to_vec()),
            // `04 <dev> <s> <dev> <s>`, dev 01 left / 02 right
            0x04 => {
                let r = pairs_n(&pl[1..]);
                let of = |d| r.iter().find(|x| x.0 == d).map_or(0xFF, |x| x.1);
                Event::FitResult(of(1), of(2))
            }
            0xF5 if pl.len() >= 2 => Event::HeadMotion(pl[1]),
            // `0F <flags u16 LE> <data>` (PROTOCOL.md §10): the first flag wins. Bit 0 alert-sound volume; bits 1 and 2 are not used here; bit 4 tap level.
            0x0F if pl.len() >= 4 => {
                let flags = pl[1] as u16 | (pl[2] as u16) << 8;
                if flags & 1 != 0 { Event::AlertVolume(pl[3]) }
                else if flags & 6 == 0 && flags & 0x10 != 0 { Event::TapPush(pl[3]) }
                else { return None }
            }
            0x0B if pl.len() >= 2 => Event::PncResult(pl[1]),
            0x06 => Event::Devices(parse_devices(&pl[1..])?),
            0x08 if pl.len() >= 3 => Event::Hearing(HearingEv::Status(pl[1], pl[2])),
            0x0E => { let (u, d) = parse_hearing_scan(pl, 1)?; Event::Hearing(HearingEv::EarScan(u, d)) }
            _ => return None,
        },
        _ => return None,
    })
}

/// `0x012F` payload (PROTOCOL.md "Batch query", section 12): `<count>`, then `<cmd LE> <len LE> <payload>` per query.
pub fn batch_payload(queries: &[(u16, Vec<u8>)]) -> Vec<u8> {
    let mut b = vec![queries.len() as u8];
    for (cmd, pl) in queries {
        b.extend_from_slice(&cmd.to_le_bytes());
        b.extend_from_slice(&(pl.len() as u16).to_le_bytes());
        b.extend_from_slice(pl);
    }
    b
}


/// HeyMelody's answer `(cmd, payload)` to a frame the buds send on their own, or `None`
/// (PROTOCOL.md section 11 "Requests from the buds and replies"). `[VENDOR]`
pub fn answer_for(p: &[u8], unix: u32) -> Option<(u16, Vec<u8>)> {
    let cmd = cmd_of(p);
    let pl = payload_of(p);
    if cmd & 0x8000 != 0 { return None; }
    let answer = match cmd {
        REQ_TIME => [&[0u8][..], &unix.to_le_bytes()].concat(),
        0x050C => vec![0, 1, 0],
        0x051C => vec![0, 0, 0],
        // ponytail: always "invalid"; HeyMelody answers the phone's spatial mode, which we do not have.
        0x051D => vec![1],
        EVT_PUSH => match pl.first() {
            Some(0xF2) => vec![0, 0xF2],
            Some(0xF4) => json_ack(&pl[1..]),
            _ => return None,
        },
        _ if ![0x0100, 0x0200, 0x0300, 0x0400, 0x0500, 0x0F00].contains(&(cmd & 0x7F00)) => vec![1],
        _ => return None,
    };
    Some((cmd | 0x8000, answer))
}

/// `F4` JSON push: `00 F4 {"cmd":<its cmd>}`, `01 F4` if it does not parse.
fn json_ack(json: &[u8]) -> Vec<u8> {
    if json.is_empty() { return vec![0, 0xF4]; }
    match serde_json::from_slice::<Value>(json) {
        Ok(v) => {
            let echo = match v["cmd"].as_str() { Some(c) => serde_json::json!({ "cmd": c }), None => serde_json::json!({}) };
            [&[0u8, 0xF4][..], echo.to_string().as_bytes()].concat()
        }
        Err(_) => vec![1, 0xF4],
    }
}

/// Splits the RFCOMM byte stream into packets. Bytes before an `AA` are dropped into `discarded` (for the log).
/// Slices of one message (PROTOCOL.md section 2: slice byte 1 first, 2 middle, 3 last) come out as one whole packet.
#[derive(Default)]
pub struct Framer { buf: Vec<u8>, slices: Option<Vec<u8>>, pub discarded: Vec<u8> }

impl Framer {
    pub fn push(&mut self, data: &[u8]) -> Vec<Vec<u8>> {
        self.buf.extend_from_slice(data);
        let mut out = Vec::new();
        loop {
            match self.buf.iter().position(|&b| b == 0xAA) {
                Some(i) => { self.discarded.extend(self.buf.drain(..i)); }
                None => { self.discarded.append(&mut self.buf); break; }
            }
            let Some((total, n)) = leb128(&self.buf[1..]) else {
                // Three length bytes with no terminator are noise: drop the AA and resync.
                if self.buf.len() >= 4 { self.discarded.push(self.buf.remove(0)); continue; }
                break;
            };
            if self.buf.len() < 2 + n { break; }
            let slice = self.buf[1 + n];
            let sliced = (1..=3).contains(&slice);
            // ponytail: 4 KiB sanity cap; a bogus length resyncs on the next AA. A slice has 3 header bytes, a message 7.
            if total < if sliced { 4 } else { 7 } || total > 4096 { self.discarded.push(self.buf.remove(0)); continue; }
            let size = 1 + n + total;
            if self.buf.len() < size { break; }
            let frame: Vec<u8> = self.buf.drain(..size).collect();
            if sliced { out.extend(self.join(slice, &frame[1 + n + 3..])); } else { out.push(frame); }
        }
        out
    }

    /// Collects slice data; after slice 3 returns the whole message as one packet. A middle or last slice with no first slice is dropped.
    /// A `PayLen` past the bytes present keeps the bytes present.
    fn join(&mut self, slice: u8, data: &[u8]) -> Option<Vec<u8>> {
        if slice == 1 { self.slices = Some(data.to_vec()); } else { self.slices.as_mut()?.extend_from_slice(data); }
        if slice != 3 { return None; }
        let msg = self.slices.take()?;
        if msg.len() < 5 { return None; }
        let n = (u16::from_le_bytes([msg[3], msg[4]]) as usize).min(msg.len() - 5);
        let mut whole = vec![0xAA, ((2 + 5 + n) & 0x7F) as u8, 0, 0];
        whole.extend_from_slice(&msg[..3]);
        whole.extend_from_slice(&(n as u16).to_le_bytes());
        whole.extend_from_slice(&msg[5..5 + n]);
        Some(whole)
    }
}

/// Battery (PROTOCOL.md section 13): `<count>` then `<index> <raw>` pairs, 1 left, 2 right, 3 case; level `raw & 7F`, charging `raw & 80`.
/// A count of 0, or a list that runs past the end, is no list (None).
pub fn battery(payload: &[u8]) -> Option<Vec<(u8, u8, bool)>> {
    let (&count, rest) = payload.split_first()?;
    if count == 0 || rest.len() < count as usize * 2 { return None; }
    // Level 0 is "not known" (the Air7 Pro's closed case, issue #9): left out, as HeyMelody hides it.
    Some(rest.chunks_exact(2).take(count as usize).filter(|c| c[1] & 0x7F != 0).map(|c| (c[0], c[1] & 0x7F, c[1] & 0x80 != 0)).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn battery_level_zero_is_left_out() {
        assert_eq!(battery(&[3, 1, 0x46, 2, 0xC6, 3, 0]).unwrap(), [(1, 0x46, false), (2, 0x46, true)]);
        assert_eq!(battery(&[0]), None);
        assert_eq!(battery(&[0, 1, 0x46]), None);
        assert_eq!(battery(&[3, 1, 0x46, 2]), None);
    }

    #[test]
    fn status_query_wind_noise() {
        let plain = status_query(None);
        assert_eq!((plain[0] as usize, plain.len()), (23, 24));
        let air7 = serde_json::json!({"windMode": 1});
        let q = status_query(Some(&air7));
        assert_eq!((q[0], q[q.len() - 1], &q[1..24]), (24, FEATURE_WIND_NOISE, &plain[1..]));
    }

    #[test]
    fn batch_round_trip() {
        assert_eq!(batch_payload(&[(0x0106, vec![]), (0x010C, vec![2, 1])]), [2, 6, 1, 0, 0, 0x0C, 1, 2, 0, 2, 1]);
        // Two answers (battery, ANC), then one cut short.
        let reply = build_packet(CMD_BATCH_REPLY, 7, &[0, 3, 6, 1, 4, 0, 0, 1, 1, 0x64, 0x0C, 1, 2, 0, 0, 2, 0x0D, 1, 9, 0, 0]);
        let got = unbatch(&reply);
        assert_eq!(got, [build_packet(0x8106, 7, &[0, 1, 1, 0x64]), build_packet(0x810C, 7, &[0, 2])]);
    }

    #[test]
    fn status_query_per_model() {
        // Buds 4: HeyMelody's captured list. Enco Buds2: the short list that keeps their link up (issue #5).
        let buds4 = find_model(Some("065414"), None);
        assert_eq!(status_query(buds4), [9, 0x05, 0x04, 0x0B, 0x11, 0x18, 0x06, 0x1B, 0x1D, 0x1C]);
        assert_eq!(status_query(find_model(Some("064810"), None)), [2, 0x05, 0x06]);
    }

    #[test]
    fn eq_presets() {
        let p = Preset { id: 4, name: "Custom1".into(), freqs: EQ_DEFAULT_FREQS.to_vec(), gains: vec![-6, 0, 2, 0, 0, 6], selected: true, tag: [0xFA, 0x06] };
        // The list entry is the save frame with the selected flag in place of the action.
        let mut list = vec![0, 1];
        let mut entry = p.encode(1);
        entry[0] = 1;
        list.extend(entry);
        assert_eq!(parse_presets(&list), Some(vec![p.clone()]));
        assert_eq!(&p.encode(EQ_SAVE)[..5], [2, 0xFA, 0x06, 4, 7]);
        assert_eq!(firmware_version("1,2,138,2,2,138,3,1,01,3,2,105").as_deref(), Some("138.138.105"));
        // Gates: the Enco Buds have no equalizer; a switch list without 0x11 hides Dual connection.
        assert!(eq_has(find_model(Some("065414"), None), &Caps(None)));
        assert!(!eq_has(find_model(Some("062410"), None), &Caps(None)));
        assert!(has_feature(&[], FEATURE_DUAL) && !has_feature(&[(FEATURE_HEARING, 1)], FEATURE_DUAL));
        let b = eq_builtins(find_model(Some("065414"), Some("OnePlus Buds 4")), Some("138.138.105"));
        assert_eq!(b, [(0, "eq_balanced"), (1, "eq_clear_vocals"), (2, "eq_bass")]);
    }

    #[test]
    fn buds4_from_capture() {
        // Replies the Buds 4 sent to the spike.
        let caps = Caps::parse(&[0x00, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07]).unwrap();
        assert_eq!(caps.game_mode_id(), FEATURE_GAME_MODE);
        assert_eq!(product_id(&[0x00, 0x14, 0x54, 0x06]).as_deref(), Some("065414"));
        let anc = AncModes::of(find_model(Some("065414"), Some("OnePlus Buds 4")));
        // A renamed device keeps its model by id; a factory name beats a reused id (issue #5: the Enco
        // Buds2 report the Q2s id 060C12).
        assert_eq!(find_model(Some("065414"), Some("My buds")).unwrap()["name"], "OnePlus Buds 4");
        assert_eq!(find_model(Some("060C12"), Some("OPPO Enco Buds2")).unwrap()["id"], "064810");
        assert_eq!(find_model(None, Some("OnePlus Buds 4")).unwrap()["id"], "065414");
        // Adaptive's SET bit is 11, `01 01 00 08`; Off is reported as bit 3, Off's child.
        assert_eq!(anc.bit(OFF), Some(0));
        assert_eq!(anc_payload(anc.bit(ADAPTIVE).unwrap()), [1, 1, 0x00, 0x08]);
        assert_eq!(anc.mode_for_raw(1 << 3, None).as_deref(), Some(OFF));
        assert_eq!(anc.levels().len(), 4);
        // Nord Buds 3 Pro offers battery and wear only: nothing else is asked for (issue #2).
        let offered = offered_events(&[0, 2, 2, 1]).unwrap();
        assert_eq!(notify_ids(&Caps(None), Some(&offered)), [1, 2]);
        assert_eq!(notify_ids(&Caps(None), None), [1, 2, 3, 4, 8, 0x0B]);
        assert_eq!(register_payload(&[1, 2]), [2, 1, 2]);
        let req = build_packet(REQ_TIME, 0x14, &[0, 0, 0, 0]);
        assert_eq!((cmd_of(&req), seq_of(&req)), (REQ_TIME, 0x14));
        assert_eq!(answer_for(&req, 0x6700_1234), Some((0x8500, vec![0, 0x34, 0x12, 0x00, 0x67])));
        let json = build_packet(EVT_PUSH, 0xFF, &[&[0xF4u8][..], br#"{"cmd":"a2dp_caton","ver":"1"}"#].concat());
        assert_eq!(answer_for(&json, 0), Some((0x8204, [&[0u8, 0xF4][..], br#"{"cmd":"a2dp_caton"}"#].concat())));
        assert_eq!(answer_for(&build_packet(EVT_PUSH, 3, &[2, 3, 1, 3]), 0), None);
        assert_eq!(answer_for(&build_packet(0x0600, 9, &[]), 0), Some((0x8600, vec![1])));
        assert_eq!(answer_for(&build_packet(0x8105, 9, &[0]), 0), None);
        let push = build_packet(EVT_PUSH, 7, &[2, 3, 1, 7, 2, 7, 3, 4]);
        assert!(matches!(decode(&push), Some(Event::Wear(w)) if w == [(1, 7), (2, 7), (3, 4)]));
    }

    #[test]
    fn earbud_settings_replies() {
        assert!(matches!(decode(&build_packet(0x8130, 1, &[0, 6])), Some(Event::AlertVolume(6))));
        assert!(matches!(decode(&build_packet(0x8427, 1, &[0, 3])), Some(Event::AlertVolume(3))));
        assert!(matches!(decode(&build_packet(0x8133, 1, &[0, 2, 3])), Some(Event::TapLevel(2, 3))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 0xFF, &[0x0F, 0x10, 0, 4])), Some(Event::TapPush(4))));
        // ANC push `03 01 01 <mode LE, 1-4 bytes>`; a hold-cycle echo `03 02 01 <mask>` is no mode
        for (pl, raw) in [(&[3u8, 1, 1, 0x20, 0][..], 0x20), (&[3, 1, 1, 2][..], 2), (&[3, 1, 1, 0, 8][..], 0x800), (&[3, 1, 1, 1, 2, 3][..], 0x030201)] {
            assert!(matches!(decode(&build_packet(EVT_PUSH, 0xFF, pl)), Some(Event::AncRaw(r)) if r == raw), "{pl:?}");
        }
        assert!(!matches!(decode(&build_packet(EVT_PUSH, 0xFF, &[3, 2, 1, 7, 8])), Some(Event::AncRaw(_))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 0xFF, &[0x0F, 0x01, 0, 7])), Some(Event::AlertVolume(7))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 0xFF, &[0x0F, 0x11, 0, 7])), Some(Event::AlertVolume(7))));
        assert!(decode(&build_packet(EVT_PUSH, 0xFF, &[0x0F, 0x04, 0, 7])).is_none());
        // A failed read (status not 0) is no value.
        assert!(decode(&build_packet(0x8130, 1, &[1, 6])).is_none());
        assert!(matches!(decode(&build_packet(0x812B, 1, &[0, 3, 2, 0, 3])), Some(Event::GameSound(3, t)) if t == [0, 3]));
        // Buds 4 capture: both good.
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[4, 1, 1, 2, 1])), Some(Event::FitResult(1, 1))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[4, 2, 6])), Some(Event::FitResult(0xFF, 6))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[0xF5, 1])), Some(Event::HeadMotion(1))));
        assert!(matches!(decode(&build_packet(0x8134, 1, &[0, 1])), Some(Event::HeadMotion(1))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[3, 4, 1, 0x20, 0])), Some(Event::SmartLevel(0x20))));
        assert!(matches!(decode(&build_packet(0x810C, 1, &[0, 4, 1, 0x40])), Some(Event::SmartLevel(0x40))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[2, 2, 1, 0x0F, 2, 4])), Some(Event::Wear(w)) if w == [(1, 7), (2, 4)]));
        assert_eq!(game_on(&[5, 1], None), Some(true));
        assert_eq!(game_on(&[5, 1, 0, 0], Some("062410")), Some(false));
        assert_eq!(game_on(&[5, 0, 0, 1], Some("062410")), Some(true));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[0x0B, 2])), Some(Event::PncResult(2))));
        assert!(matches!(decode(&build_packet(0x811A, 1, &[0, 1])), Some(Event::PncStored(true))));
        assert!(matches!(decode(&build_packet(0x8412, 1, &[15])), Some(Event::PncAck(15))));
        assert!(matches!(decode(&build_packet(0x840E, 1, &[3])), Some(Event::Hearing(HearingEv::Refused(3)))));
        assert!(!matches!(decode(&build_packet(0x840D, 1, &[0])), Some(Event::Hearing(_))));
    }

    #[test]
    fn hearing_frames() {
        let v: [i8; 12] = [-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17];
        // The apply record parses back as the buds' active record.
        let apply = hearing_apply(0x01020304, "2026/09/29 01:53", &v, &[9; 168], 11);
        assert_eq!(apply[0], (CMD_HEARING_RESTORE, vec![1, 1, 1, 0, 11]));
        assert_eq!(&apply[2].1[..3], [3, 0xA8, 0]);
        assert_eq!(apply[3], (CMD_SET_FEATURE, vec![FEATURE_HEARING, 1]));
        let mut active = vec![0];
        active.extend(&apply[1].1[..]);
        let Some(Event::Hearing(HearingEv::Active(u, n, got))) = decode(&build_packet(0x8115, 1, &active)) else { panic!() };
        assert_eq!((u, n.as_str(), got), (0x01020304, "2026/09/29 01:53", v));
        let mut scan = vec![0x0E, 3, 2, 0, 7, 8];
        scan.extend(5u32.to_be_bytes());
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &scan)), Some(Event::Hearing(HearingEv::EarScan(5, d))) if d == [7, 8]));
        assert_eq!(hearing_snap(-30), -35);
        // A flat filter (b = a) changes nothing: the rim.
        assert_eq!(hearing_radar(&[1.0, 0.5, 0.2, 1.0, 0.5, 0.2], None), [10.0; 6]);
    }

    #[test]
    fn gestures() {
        // Buds 4 hold capture: `02 01 07 08` added Adaptive (bit 11) to a 3-stop cycle.
        assert_eq!(hold_modes_payload(0x0807, HOLD_TYPE_SHARED), [2, 1, 7, 8]);
        assert_eq!(hold_modes_payload(0x07, HOLD_TYPE_LEFT), [2, 3, 7]);
        assert!(matches!(decode(&build_packet(0x810C, 1, &[0, 2, 1, 7, 8])), Some(Event::HoldMask(1, 0x0807))));
        let t = [[1, 1, 1, 1], [2, 6, 2, 0x1D]];
        let mut reply = vec![0, 2];
        reply.extend(t.iter().flatten());
        assert!(matches!(decode(&build_packet(0x8108, 1, &reply)), Some(Event::KeyFunctions(v)) if v == t));
        assert_eq!(key_function_payload(&t), [2, 1, 1, 1, 1, 2, 6, 2, 0x1D]);
        assert!(decode(&build_packet(0x8108, 1, &[0, 0])).is_none());
    }

    #[test]
    fn dual_devices() {
        // One connected device flagged as the reader, one dropped.
        let mut list = vec![0, 2];
        list.extend([0x66, 0x55, 0x44, 0x33, 0x22, 0x11, 5, 2, 1, 2, b'P', b'C']);
        list.extend([1, 2, 3, 4, 5, 6, 6, 0, 0, 3, b'T', b'a', b'b']);
        let Some(Event::Devices(d)) = decode(&build_packet(0x8112, 1, &list)) else { panic!() };
        assert_eq!(d[0], PairedDevice { mac: [0x11, 0x22, 0x33, 0x44, 0x55, 0x66], name: "PC".into(), connected: true, this_device: true });
        assert_eq!((d[1].name.as_str(), d[1].connected, d[1].mac), ("Tab", false, [6, 5, 4, 3, 2, 1]));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[6, 0])), Some(Event::Devices(v)) if v.is_empty()));
        assert!(matches!(decode(&build_packet(0x8132, 1, &[0, 2, 0])), Some(Event::Preferred(None))));
        assert!(matches!(decode(&build_packet(0x8132, 1, &[0, 2, 1, 1, 2, 3, 4, 5, 6])), Some(Event::Preferred(Some([1, 2, 3, 4, 5, 6])))));
    }

    #[test]
    fn packet_roundtrip() {
        let p = build_packet(CMD_REGISTER_NOTIFY, 5, &[3, 1, 2, 3]);
        assert_eq!(p, [0xAA, 11, 0, 0, 0x05, 0x02, 5, 4, 0, 3, 1, 2, 3]);
        assert_eq!(cmd_of(&p), 0x0205);
        assert_eq!(payload_of(&p), [3, 1, 2, 3]);
        // Two-byte LEB128 length.
        let big = build_packet(0x0401, 1, &[0; 200]);
        assert_eq!(&big[..3], [0xAA, 0xCF, 0x01]);
        assert_eq!(payload_of(&big).len(), 200);
    }

    #[test]
    fn framer_splits_and_resyncs() {
        let a = build_packet(0x8106, 0xF0, &[3, 1, 0x64, 2, 0x64, 3, 0xD0]);
        let mut stream = vec![0x01, 0x02];
        stream.extend(&a);
        stream.extend(&a);
        let mut f = Framer::default();
        let (x, y) = stream.split_at(7);
        assert!(f.push(x).is_empty());
        let got = f.push(y);
        assert_eq!(got, vec![a.clone(), a]);
        assert_eq!(f.discarded.len(), 2);
        assert_eq!(battery(payload_of(&got[0])).unwrap(), [(1, 100, false), (2, 100, false), (3, 80, true)]);
    }

    #[test]
    fn framer_survives_a_bad_length() {
        // `AA 80 80 80` has no length terminator; the framer must drop the AA and keep the next frame.
        let w = build_packet(0x8106, 0xF0, &[1, 2]);
        let mut stream = vec![0xAA, 0x80, 0x80, 0x80];
        stream.extend(&w);
        assert_eq!(Framer::default().push(&stream), vec![w]);
    }

    #[test]
    fn framer_joins_slices() {
        // PROTOCOL.md §2: a 1500-byte message in slices of 3 + data bytes; the whole packet has a one-byte length.
        let payload: Vec<u8> = (0..300).map(|i| i as u8).collect();
        let mut msg = vec![0x15, 0x81, 4, 0x2C, 0x01];
        msg.extend(&payload);
        let slice = |kind: u8, fsn: u8, d: &[u8]| { let mut f = vec![0xAA, 3 + d.len() as u8, kind, 0, fsn]; f.extend(d); f };
        let (a, rest) = msg.split_at(100);
        let (b, c) = rest.split_at(100);
        let mut f = Framer::default();
        let mut stream = slice(1, 0, a);
        stream.extend(slice(2, 1, b));
        stream.extend(slice(3, 2, c));
        let got = f.push(&stream);
        assert_eq!(got.len(), 1);
        assert_eq!((cmd_of(&got[0]), payload_of(&got[0])), (0x8115, &payload[..]));
        // a last slice with no first slice before it is dropped
        assert!(Framer::default().push(&slice(3, 2, c)).is_empty());
        // a whole message still passes unchanged
        let w = build_packet(0x8106, 0xF0, &[1, 2]);
        assert_eq!(Framer::default().push(&w), vec![w]);
    }

    #[test]
    fn codecs() {
        // Buds 4 `0x8123` = `00 46 01`: AAC, LDAC, LHDC (bit 8 is past the table); PROTOCOL.md section 21.7 trusts it only on `hiRes`.
        assert_eq!(codecs_of(0x0146), [2, 3, 7]);
        assert_eq!(codecs_of(0b1100_0000), [8]);
        assert_eq!(codecs_of(0b0100_0001), [1, 7]);
        assert_eq!(codec_payload(8, true), [8, 1, 0]);
        assert_eq!(codec_payload(2, true), [2, 0, 0]);
        assert_eq!(decode(&build_packet(0x8114, 1, &[0, 8])).map(|e| describe(&e)), Some("Codec LHDC".into()));
        assert!(matches!(decode(&build_packet(EVT_SPATIAL_TYPE, 1, &[2])), Some(Event::SpatialType(2))));
    }
}
