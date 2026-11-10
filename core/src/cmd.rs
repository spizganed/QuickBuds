//! Typed messages of docs/PROTOCOL.md: builds what the app sends. Each function returns a [`Frame`]
//! (command, payload); the link layer adds the sequence number and the frame. [`battery`] and
//! [`wearing`] return a [`Message`] with the fixed sequence. Written from the spec.

use crate::msg::{Binding, Bitmap, EqPreset};
use crate::wire::Message;

/// A message to send: (command, payload).
pub type Frame = (u16, Vec<u8>);

// --- Connection (PROTOCOL.md section 6) ---

pub fn handshake() -> Frame { (0x0100, vec![]) }
pub fn product_id() -> Frame { (0x0103, vec![]) }
pub fn broadcast_codes() -> Frame { (0x0200, vec![]) }

/// `0x0205 <count> <codes...>`. Count first: `01 01 02 02` would mean "count 1, battery only".
pub fn register(codes: &[u8]) -> Frame { (0x0205, [&[codes.len() as u8][..], codes].concat()) }
/// `0x0201 <code>`, for buds without `0x0205` in the bitmap.
pub fn register_one(code: u8) -> Frame { (0x0201, vec![code]) }

/// The events to subscribe to: battery, wearing, ANC, plus fit test, hearing test and personalised ANC where the
/// bitmap has their commands. Kept to the codes the buds offer when they answered `0x0200`.
pub fn notify_ids(bitmap: &Bitmap, offered: Option<&[u8]>) -> Vec<u8> {
    let mut ids = vec![0x01, 0x02, 0x03];
    if bitmap.allows(0x0405) { ids.push(0x04); }
    if bitmap.allows(0x040D) { ids.push(0x08); }
    if bitmap.allows(0x0412) { ids.push(0x0B); }
    if let Some(o) = offered { ids.retain(|c| o.contains(c)); }
    ids
}

/// The id the model list uses: four colour ranges fold into one id.
pub fn fold_product(id: u32) -> u32 {
    match id {
        0x100100..=0x100102 => 0x060414,
        0x100200..=0x100202 => 0x060814,
        0x108100..=0x108102 => 0x068414,
        0x108200..=0x108202 => 0x068814,
        x => x,
    }
}

/// Six hex digits, as the model list writes ids: `065414`.
pub fn product_text(id: u32) -> String { format!("{:06X}", fold_product(id)) }

/// The device-type list the version text names: the `versionType` 2 versions in reply order, joined by dots.
pub fn firmware_text(parts: &[(u8, u8, String)]) -> Option<String> {
    let v: Vec<&str> = parts.iter().filter(|p| p.1 == 2).map(|p| p.2.as_str()).collect();
    (!v.is_empty()).then(|| v.join("."))
}

/// `0x012F`: `<count>` then `<cmd LE> <len LE> <payload>` per query.
pub fn batch(queries: &[Frame]) -> Frame {
    let mut p = vec![queries.len() as u8];
    for (cmd, pl) in queries {
        p.extend_from_slice(&cmd.to_le_bytes());
        p.extend_from_slice(&(pl.len() as u16).to_le_bytes());
        p.extend_from_slice(pl);
    }
    (0x012F, p)
}

// --- Reads ---

/// `0x0106` with the fixed sequence `0xF0`, as `queryBattery` (`Commands.kt` 250).
pub fn battery() -> Message { Message { cmd: 0x0106, seq: 0xF0, payload: vec![] } }
/// `0x0109` with the fixed sequence `0xF2`, as `queryWearingStatus` (`Commands.kt` 254).
pub fn wearing() -> Message { Message { cmd: 0x0109, seq: 0xF2, payload: vec![] } }
pub fn firmware() -> Frame { (0x0105, vec![]) }
/// `0x010C <what> <arg>`: `01 01` current mode, `02 01` shared hold cycle (`02 03` left, `02 04` right), `04 01` the level Smart chose.
pub fn anc_read(what: u8, arg: u8) -> Frame { (0x010C, vec![what, arg]) }
pub fn gestures() -> Frame { (0x0108, vec![]) }
/// `0x010D <count> <ids>`.
pub fn features(ids: &[u8]) -> Frame { (0x010D, [&[ids.len() as u8][..], ids].concat()) }
pub fn eq_current() -> Frame { (0x010F, vec![]) }
pub fn eq_custom() -> Frame { (0x0122, vec![]) }
pub fn bass_level() -> Frame { (0x0124, vec![]) }
/// `0x012C`: realme dynamic sound, all band levels in one reply.
pub fn bass_bands() -> Frame { (0x012C, vec![]) }

// --- Writes ---

/// `0x0404 01 01 <mask LE>`: one bit set, `bit / 8 + 1` mask bytes. `bit` is the model's SET slot.
pub fn anc_set(bit: u8) -> Frame {
    let mut p = vec![0; 2 + bit as usize / 8 + 1];
    p[0] = 1;
    p[1] = 1;
    p[2 + bit as usize / 8] = 1 << (bit % 8);
    (0x0404, p)
}

/// `0x0404 02 <kind> <mask LE>`: the modes the hold cycles. The mask takes the fewest bytes, 1 to 4.
pub fn hold_cycle(kind: u8, mask: u32) -> Frame {
    let n = (1..=4).rev().find(|&n| n == 1 || mask >> ((n - 1) * 8) & 0xFF != 0).unwrap();
    (0x0404, [&[2, kind][..], &mask.to_le_bytes()[..n]].concat())
}

/// `0x0401 <count> <entries>`: the whole table, every time.
pub fn gesture_table(table: &[Binding]) -> Frame {
    (0x0401, std::iter::once(table.len() as u8).chain(table.iter().flat_map(|b| [b.dev, b.button, b.act, b.func])).collect())
}

/// `0x0403 <id> <01|00>`.
pub fn feature(id: u8, on: bool) -> Frame { (0x0403, vec![id, on as u8]) }

/// `0x0406 <id>`: select a built-in preset.
pub fn eq_select(id: u8) -> Frame { (0x0406, vec![id]) }

/// What `0x0418` does with a preset.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum EqAction {
    /// The buds assign the id (send `00`) and return it in the reply.
    Create = 1,
    /// The whole preset, every time: also on every drag step and every rename. It selects the preset.
    Save = 2,
    /// The selection falls back to `00`.
    Delete = 3,
}

/// `0x0418 <action> <min> <max> <id> <nameLen> <name> <bands> [<freq u16 LE> <gain s8>]...`
pub fn eq_preset(action: EqAction, p: &EqPreset) -> Frame {
    let mut out = vec![action as u8, p.range.0 as u8, p.range.1 as u8, p.id, p.name.len() as u8];
    out.extend_from_slice(p.name.as_bytes());
    out.push(p.bands.len() as u8);
    for (f, g) in &p.bands {
        out.extend_from_slice(&f.to_le_bytes());
        out.push(*g as u8);
    }
    (0x0418, out)
}

/// `0x041B FB 05 <level> [<band>]`: BassWave level, -5..+5. The BassWave switch is feature `1D`.
/// `band` 0 sends no band byte. realme dynamic sound (`bassBands` 3) sends 1 low, 2 medium, 3 high.
pub fn bass_set(level: i8, band: u8) -> Frame {
    let mut p = vec![0xFB, 0x05, level as u8];
    if band > 0 { p.push(band); }
    (0x041B, p)
}

// --- Sound and device settings (PROTOCOL.md section 18) ---

pub fn alert_volume_read() -> Frame { (0x0130, vec![]) }
/// `0x0427 <level>`, 1..10. Each write plays a prompt: send on slider release only.
pub fn alert_volume(level: u8) -> Frame { (0x0427, vec![level]) }
pub fn tap_level_read() -> Frame { (0x0133, vec![]) }
/// `0x042D <level>`, 1..5. A lower level triggers more easily.
pub fn tap_level(level: u8) -> Frame { (0x042D, vec![level]) }
pub fn head_gesture_read() -> Frame { (0x0134, vec![]) }
/// `0x0431 <type>`: `00` nod answers and shake declines, `01` the reverse.
pub fn head_gesture(kind: u8) -> Frame { (0x0431, vec![kind]) }
pub fn spatial_read() -> Frame { (0x012A, vec![]) }
/// `0x0422 <type>`: 0 off, 1 fixed, 2 head tracking.
pub fn spatial(kind: u8) -> Frame { (0x0422, vec![kind]) }
/// `0x041E <source>`: `00` the buds render 3D audio, `01` the phone does.
pub fn spatial_source(source: u8) -> Frame { (0x041E, vec![source]) }
pub fn codec_read() -> Frame { (0x0114, vec![]) }
pub fn codec_list_read() -> Frame { (0x0123, vec![]) }
/// `0x041A <codec> <hiRes> 00`. The Hi-Res byte is set only with LDAC (3) or LHDC V5 (8).
pub fn codec(codec: u8, hires: bool) -> Frame { (0x041A, vec![codec, (hires && (codec == 3 || codec == 8)) as u8, 0]) }
pub fn game_sound_read() -> Frame { (0x012B, vec![]) }
/// `0x0423 <type> 01`; type 0 is off.
pub fn game_sound(kind: u8) -> Frame { (0x0423, vec![kind, 1]) }
/// `0x0420 01 <state>`: a game app is in the foreground. Applies only while feature `21` is on.
pub fn game_eq(in_game: bool) -> Frame { (0x0420, vec![1, in_game as u8]) }
/// The codecs in a mask, as the app offers them: LHDC (7) is dropped when LHDC V5 (8) is offered too.
pub fn codecs_of(mask: u16) -> Vec<u8> {
    let all: Vec<u8> = (1..=8).filter(|c| mask & (1 << (c - 1)) != 0).collect();
    if all.contains(&8) { all.into_iter().filter(|&c| c != 7).collect() } else { all }
}

/// `0x0400 <01|00>`: ring both buds.
pub fn find(on: bool) -> Frame { (0x0400, vec![on as u8]) }
/// `0x0405 <01|00>`: fit test. The result is push `04`.
pub fn fit_test(on: bool) -> Frame { (0x0405, vec![on as u8]) }
/// `0x0412 <action>`: 1 test, 2 use the stored result, 3 cancel.
pub fn personal_anc(action: u8) -> Frame { (0x0412, vec![action]) }
pub fn personal_anc_stored() -> Frame { (0x011A, vec![]) }

// --- Dual connection (PROTOCOL.md section 19) ---

pub fn devices_read() -> Frame { (0x0112, vec![]) }
/// `0x0429 01|02 <MAC>`: connect or disconnect a device. The MAC is in written order.
pub fn device_connect(mac: [u8; 6], on: bool) -> Frame { (0x0429, [&[if on { 1 } else { 2 }][..], &mac].concat()) }
/// `0x0429 04 00` automatic, `04 01 <MAC>` a chosen device.
pub fn preferred_set(mac: Option<[u8; 6]>) -> Frame {
    (0x0429, match mac { None => vec![4, 0], Some(m) => [&[4, 1][..], &m].concat() })
}
pub fn preferred_read() -> Frame { (0x0132, vec![2]) }

/// The phone's state for switching between devices: `<flags> 00`, then one byte per set flag in bit order.
#[derive(Clone, Copy, Default, PartialEq, Debug)]
pub struct PhoneState {
    pub version: Option<u8>,
    pub screen_on: Option<bool>,
    pub held: Option<bool>,
    pub auto_switch: Option<bool>,
    pub music: Option<bool>,
}

/// `0x0413 <flags> 00 <values>`: flag bit 0 protocol version, 1 screen on, 2 held by hand, 3 auto switch on, 4 music playing.
pub fn phone_state(s: PhoneState) -> Frame {
    let fields = [s.version, s.screen_on.map(u8::from), s.held.map(u8::from), s.auto_switch.map(u8::from), s.music.map(u8::from)];
    let flags = fields.iter().enumerate().fold(0u8, |f, (i, v)| f | (v.is_some() as u8) << i);
    (0x0413, [&[flags, 0][..], &fields.iter().flatten().copied().collect::<Vec<_>>()].concat())
}

// --- Golden Sound (PROTOCOL.md section 20) ---

/// 12 hearing values, left ears 1-6 then right ears 1-6, as `<side> <freq> <value>`.
fn hearing_info(values: &[i8; 12]) -> Vec<u8> { (0..12).flat_map(|i| [i as u8 / 6 + 1, i as u8 % 6 + 1, values[i] as u8]).collect() }

pub fn golden_record_read() -> Frame { (0x0115, vec![]) }
pub fn golden_scan_read() -> Frame { (0x011E, vec![]) }
/// `0x040D 04 <01|00> <id u32 BE>`: ear scan. The result is push `0E`.
pub fn ear_scan(on: bool, id: u32) -> Frame { (0x040D, [&[4, on as u8][..], &id.to_be_bytes()].concat()) }
/// `0x040D 02 <01|00>`: hearing test.
pub fn hearing_test(on: bool) -> Frame { (0x040D, vec![2, on as u8]) }
/// `0x040E 03 01 <side> <freq> <value>`: a tone on one bud. Send [tone_stop] before each new level.
pub fn tone(side: u8, freq: u8, value: i8) -> Frame { (0x040E, vec![3, 1, side, freq, value as u8]) }
pub fn tone_stop() -> Frame { (0x040E, vec![4]) }
/// `0x0116 0C <12 values> <id>`: the hearing filters of a result.
pub fn golden_filter(id: u32, values: &[i8; 12]) -> Frame { (0x0116, [vec![0x0C], hearing_info(values), id.to_be_bytes().to_vec()].concat()) }
/// `0x011F <len u16 LE> <scan> <id>`: the ear-scan filters.
pub fn golden_scan_filter(id: u32, scan: &[u8]) -> Frame {
    (0x011F, [&(scan.len() as u16).to_le_bytes()[..], scan, &id.to_be_bytes()].concat())
}
/// Applies a record: the description id, the record, the ear scan when there is one, then the switch on.
/// `desc` is the description id, 0 for none.
pub fn golden_apply(id: u32, name: &str, values: &[i8; 12], scan: &[u8], desc: u8) -> Vec<Frame> {
    let mut out = Vec::new();
    if desc > 0 { out.push((0x0411, vec![1, 1, 1, 0, desc])); }
    out.push((0x040E, [vec![3, 0x0C], hearing_info(values), id.to_be_bytes().to_vec(), name.as_bytes().to_vec()].concat()));
    if !scan.is_empty() {
        out.push((0x0415, [&[3][..], &(scan.len() as u16).to_le_bytes(), scan, &id.to_be_bytes()].concat()));
    }
    out.push(feature(0x0B, true));
    out
}

#[cfg(test)]
mod tests {
    use super::{battery, wearing};
    use crate::wire::{encode, Message, BUDGET};

    fn packet(m: &Message) -> Vec<u8> { encode(m, BUDGET).remove(0) }

    /// The builders fix the sequence: `Commands.kt` 250 (battery) and 254 (wearing).
    #[test]
    fn fixed_sequences_match_legacy_packets() {
        assert_eq!(battery(), Message { cmd: 0x0106, seq: 0xF0, payload: vec![] });
        // queryBattery, Commands.kt 250: AA 07 00 00 06 01 F0 00 00.
        assert_eq!(packet(&battery()), [0xAA, 0x07, 0x00, 0x00, 0x06, 0x01, 0xF0, 0x00, 0x00]);
        assert_eq!(wearing(), Message { cmd: 0x0109, seq: 0xF2, payload: vec![] });
        // queryWearingStatus, Commands.kt 254: AA 07 00 00 09 01 F2 00 00.
        assert_eq!(packet(&wearing()), [0xAA, 0x07, 0x00, 0x00, 0x09, 0x01, 0xF2, 0x00, 0x00]);
    }
}
