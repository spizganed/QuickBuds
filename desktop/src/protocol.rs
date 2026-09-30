//! Wire format (docs/PROTOCOL.md §2): `AA <TotalLen LEB128> 00 00 <cmd lo> <cmd hi> <seq> <len lo> <len hi> <payload>`.
//! TotalLen counts everything after itself: 7 + payload.

use serde_json::Value;

pub const CMD_HANDSHAKE: u16 = 0x0100;
pub const CMD_QUERY_PRODUCT_ID: u16 = 0x0103;
pub const CMD_QUERY_BATTERY: u16 = 0x0106;
pub const CMD_QUERY_WEARING: u16 = 0x0109;
pub const CMD_QUERY_ANC: u16 = 0x010C;
pub const CMD_QUERY_STATUS: u16 = 0x010D;
pub const CMD_QUERY_BROADCAST: u16 = 0x0200;
pub const CMD_REGISTER_NOTIFY: u16 = 0x0205;
pub const CMD_SET_FEATURE: u16 = 0x0403;
pub const CMD_SET_ANC: u16 = 0x0404;
pub const CMD_FIT_TEST: u16 = 0x0405;
pub const CMD_GOLDEN_DETECT: u16 = 0x040D;
pub const CMD_PERSONAL_NOISE: u16 = 0x0412;
pub const CMD_GAME_SOUND: u16 = 0x0423;
pub const EVT_PUSH: u16 = 0x0204;

/// Game mode's `0x0403` switch: `0x28` on buds with game sound (`0x0423`), `0x06` elsewhere (§9).
pub const FEATURE_GAME_MODE: u8 = 0x06;
pub const FEATURE_GAME_MODE_MAIN: u8 = 0x28;

/// `0x0404 01 01 <bit as LE bitmask>`; the bit is the model's SET `protocolIndex`, never a list position (§5).
pub fn anc_payload(bit: u8) -> Vec<u8> {
    let mut p = vec![0; 2 + bit as usize / 8 + 1];
    p[0] = 1;
    p[1] = 1;
    p[2 + bit as usize / 8] = 1 << (bit % 8);
    p
}

/// `0x0205`, count first; never shorter than `03 01 02 03` (§4).
pub fn register_payload(caps: &Caps) -> Vec<u8> {
    let mut ids = vec![1, 2, 3];
    if caps.supports(CMD_FIT_TEST) { ids.push(0x04); }
    if caps.supports(CMD_GOLDEN_DETECT) { ids.push(0x08); }
    if caps.supports(CMD_PERSONAL_NOISE) { ids.push(0x0B); }
    ids.insert(0, ids.len() as u8);
    ids
}

/// `0x010D`: count, then the feature ids the phone app reads.
pub const STATUS_QUERY: &[u8] = &[
    0x17, 0x05, 0x04, 0x0B, 0x11, 0x13, 0x18, 0x06, 0x1B, 0x1C, 0x27, 0x28, 0x1D,
    0x09, 0x17, 0x30, 0x31, 0x3A, 0x32, 0x35, 0x37, 0x38, 0x3B, 0x0C,
];

/// Commands behind each bit of the `0x8100` handshake bitmap (same table as `Capabilities.kt`).
const BIT_COMMANDS: &[&[u16]] = &[
    &[0x0105], &[0x0106], &[0x0107], &[0x0108, 0x0401, 0x0416], &[0x0109], &[0x0400], &[0x0402], &[0x0403],
    &[0x010C, 0x0404], &[0x0405], &[0x0406, 0x010F], &[0x0407], &[], &[0x0408], &[0x0409], &[], &[],
    &[0x0114], &[], &[0x040E, 0x040D, 0x0115, 0x0116], &[0x040F], &[0x0410, 0x0119], &[0x0205], &[0x0F00],
    &[], &[0x0118, 0x0411], &[0x011A, 0x0412], &[0x011C, 0x0413], &[], &[0x0112, 0x040B],
    &[0x011E, 0x011F, 0x0415], &[0x040D], &[], &[0x0121, 0x0417], &[0x0122, 0x0418], &[],
    &[0x011D, 0x0414], &[0x0123, 0x041A], &[0x0124, 0x041B], &[0x0125, 0x041C, 0x0127, 0x041D, 0x041F],
    &[0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129], &[0xEF01], &[0xEF02], &[0xEF03, 0x041E], &[0x0420],
    &[0x001C], &[], &[0x0422, 0x012A], &[0xEF04], &[0x0423, 0x012B], &[], &[0x0424], &[0xEF06], &[], &[],
    &[0x0425, 0x012E, 0x0426], &[0x012F], &[0x0427, 0x0130], &[0x0131, 0x0428], &[0x0429, 0x0132], &[0x0014],
    &[0x042D, 0x0133], &[0x042E], &[0xEF07], &[0xEF08], &[0xEF09], &[0x0431, 0x0134],
];
const ALWAYS: &[u16] = &[0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04];

/// What the buds said they accept. Before the handshake reply, everything is assumed (as the phone app does).
#[derive(Default, Clone)]
pub struct Caps(Option<Vec<u16>>);

impl Caps {
    pub fn parse(payload: &[u8]) -> Option<Caps> {
        if payload.len() < 2 || payload[0] != 0 { return None; }
        let mut out = ALWAYS.to_vec();
        for bit in 0..((payload.len() - 1) * 8).min(BIT_COMMANDS.len()) {
            if payload[1 + bit / 8] >> (bit % 8) & 1 == 1 { out.extend_from_slice(BIT_COMMANDS[bit]); }
        }
        Some(Caps(Some(out)))
    }
    pub fn supports(&self, cmd: u16) -> bool { self.0.as_ref().map_or(true, |c| c.contains(&cmd)) }
    pub fn game_mode_id(&self) -> u8 {
        if self.supports(CMD_GAME_SOUND) { FEATURE_GAME_MODE_MAIN } else { FEATURE_GAME_MODE }
    }
}

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

// --- Models and ANC modes (`ModelCatalog.kt`, `AncModes.kt`, PROTOCOL.md §5) ---

pub const OFF: &str = "Off";
pub const TRANSPARENCY: &str = "Transparency";
pub const ADAPTIVE: &str = "Adaptive";
/// Low to high, then Smart (the firmware picks the level).
pub const LEVELS: [&str; 4] = ["ANC-Light", "ANC-Medium", "ANC-Deep", "ANC-Smart"];
const NC: &str = "ANC";

/// The phone app's own file, embedded at build time: one list for both apps.
static MODELS_JSON: &str = include_str!("../../app/src/main/assets/models.json");

pub fn models() -> &'static [Value] {
    use std::sync::OnceLock;
    static M: OnceLock<Vec<Value>> = OnceLock::new();
    M.get_or_init(|| {
        let v: Value = serde_json::from_str(MODELS_JSON).expect("models.json");
        v["whiteList"].as_array().cloned().unwrap_or_default()
    })
}

/// `ModelCatalog.find`: name and id both match, else name, else id.
pub fn find_model(id: Option<&str>, name: Option<&str>) -> Option<&'static Value> {
    let m = models();
    let by_name = |x: &&Value| name.is_some() && x["name"].as_str() == name;
    let by_id = |x: &&Value| id.is_some_and(|id| x["id"].as_str().is_some_and(|i| i.eq_ignore_ascii_case(id)));
    m.iter().find(|x| by_name(x) && by_id(x)).or_else(|| m.iter().find(by_name)).or_else(|| m.iter().find(by_id))
}

pub fn is_known_name(name: &str) -> bool { models().iter().any(|m| m["name"].as_str() == Some(name)) }

#[derive(Default, Clone)]
pub struct AncModes {
    /// Mode name -> SET bit, in the model's order.
    set: Vec<(String, u8)>,
    /// Report bit -> mode name ([NC] where the report names no level).
    report: Vec<(u8, String)>,
}

fn mode_name(t: i64) -> Option<&'static str> {
    Some(match t {
        1 => OFF,
        2 => TRANSPARENCY,
        3 => "ANC-Light",
        8 => "ANC-Medium",
        4 => "ANC-Deep",
        5 => NC,
        10 => ADAPTIVE,
        7 => "ANC-Smart",
        6 => "Voice",
        _ => return None,
    })
}

fn mode_of(v: &Value) -> Option<(&'static str, u8)> {
    Some((mode_name(v["modeType"].as_i64()?)?, v["protocolIndex"].as_u64()? as u8))
}

impl AncModes {
    /// A model without `noiseReductionMode` (or none detected) gets no modes: a wrong bit sets another mode silently.
    pub fn of(model: Option<&Value>) -> AncModes {
        model.and_then(|m| m["noiseReductionMode"].as_array()).map(|a| Self::parse(a)).unwrap_or_default()
    }

    fn parse(modes: &[Value]) -> AncModes {
        let mut m = AncModes::default();
        fn put(set: &mut Vec<(String, u8)>, n: &str, b: u8) {
            if !set.iter().any(|(x, _)| x == n) { set.push((n.into(), b)); }
        }
        for top in modes {
            let Some((name, bit)) = mode_of(top) else { continue };
            let shown = if name == "Voice" { TRANSPARENCY } else { name };
            if !top["decideByEarDevice"].as_bool().unwrap_or(false) { put(&mut m.set, name, bit); }
            m.report.push((bit, shown.into()));
            for child in top["childrenMode"].as_array().into_iter().flatten() {
                let Some((cn, cb)) = mode_of(child) else { continue };
                // A child that repeats its parent (Off's bit 3) is a report value only.
                if cn != name { put(&mut m.set, cn, cb); }
                m.report.push((cb, if cn == "Voice" { shown } else { cn }.into()));
            }
        }
        // Plain noise cancelling with no levels is offered as the one level the UI has for it.
        if let Some(i) = m.set.iter().position(|(n, _)| n == NC) {
            let (_, b) = m.set.remove(i);
            if !LEVELS.iter().any(|l| m.supports(l)) { m.set.push(("ANC-Medium".into(), b)); }
        }
        m.set.retain(|(n, _)| n != "Voice");
        m
    }

    pub fn supports(&self, mode: &str) -> bool { self.set.iter().any(|(n, _)| n == mode) }
    pub fn bit(&self, mode: &str) -> Option<u8> { self.set.iter().find(|(n, _)| n == mode).map(|x| x.1) }
    pub fn levels(&self) -> Vec<&'static str> { LEVELS.into_iter().filter(|l| self.supports(l)).collect() }

    /// The mode a `0x810C` / `0x0204` value reports. A plain "noise cancelling" bit names no level:
    /// `current_level` if these buds have it, else their first.
    pub fn mode_for_raw(&self, raw: u32, current_level: Option<&str>) -> Option<String> {
        if raw == 0 { return None; }
        let bit = raw.trailing_zeros() as u8;
        let mode = &self.report.iter().find(|(b, _)| *b == bit)?.1;
        if mode != NC { return Some(mode.clone()); }
        let levels = self.levels();
        current_level.filter(|l| levels.contains(l)).or(levels.first().copied()).map(Into::into)
    }
}

// --- Incoming packets ---

pub enum Event {
    Caps(Caps),
    ProductId(String),
    /// (index 1 left / 2 right / 3 case, level, charging)
    Battery(Vec<(u8, u8, bool)>),
    /// (component 1 left / 2 right / 3 case, status), §8
    Wear(Vec<(u8, u8)>),
    AncRaw(u32),
    GameMode(bool),
    /// `0x810D`: feature id -> value
    Features(Vec<(u8, u8)>),
}

fn pairs(b: &[u8]) -> Option<Vec<(u8, u8)>> {
    let (&count, rest) = b.split_first()?;
    (rest.len() >= count as usize * 2).then(|| rest.chunks_exact(2).take(count as usize).map(|c| (c[0], c[1])).collect())
}

pub fn decode(p: &[u8]) -> Option<Event> {
    let pl = payload_of(p);
    let sub = pl.first().copied();
    Some(match cmd_of(p) {
        0x8100 => Event::Caps(Caps::parse(pl)?),
        0x8103 => Event::ProductId(product_id(pl)?),
        // `[status][count][pairs]`
        0x8106 if sub == Some(0) => Event::Battery(battery(&pl[1..])),
        0x8109 => {
            // `[count][pairs]`, or `[status][count][pairs]` (WearingStatusParser tries both).
            let valid = |v: &Vec<(u8, u8)>| !v.is_empty() && v.iter().all(|x| (1..=3).contains(&x.0));
            Event::Wear(pairs(pl).filter(valid).or_else(|| pairs(pl.get(1..)?).filter(valid))?)
        }
        // `[status][01 01][value LE, 1-4 bytes]`
        0x810C if pl.len() >= 4 && pl[1] == 1 && pl[2] == 1 => {
            Event::AncRaw(pl[3..pl.len().min(7)].iter().rev().fold(0, |v, &b| v << 8 | b as u32))
        }
        0x810D if sub == Some(0) => Event::Features(pairs(&pl[1..])?),
        EVT_PUSH => match sub? {
            0x01 => Event::Battery(battery(&pl[1..])),
            0x02 => Event::Wear(pairs(&pl[1..])?),
            0x03 if pl.len() >= 5 && pl[1] == 1 && pl[2] == 1 => Event::AncRaw(u16::from_le_bytes([pl[3], pl[4]]) as u32),
            0x05 if pl.len() >= 2 => Event::GameMode(pl[1] != 0),
            _ => return None,
        },
        _ => return None,
    })
}

pub fn build_packet(cmd: u16, seq: u8, payload: &[u8]) -> Vec<u8> {
    let mut p = vec![0xAA];
    let mut total = 7 + payload.len();
    loop {
        let b = (total & 0x7F) as u8;
        total >>= 7;
        p.push(if total != 0 { b | 0x80 } else { b });
        if total == 0 { break; }
    }
    let n = payload.len();
    p.extend_from_slice(&[0, 0, cmd as u8, (cmd >> 8) as u8, seq, n as u8, (n >> 8) as u8]);
    p.extend_from_slice(payload);
    p
}

/// (TotalLen, bytes it took), or None while incomplete.
fn leb128(b: &[u8]) -> Option<(usize, usize)> {
    let mut v = 0usize;
    for (i, &x) in b.iter().enumerate().take(3) {
        v |= ((x & 0x7F) as usize) << (7 * i);
        if x & 0x80 == 0 { return Some((v, i + 1)); }
    }
    None
}

fn header_len(p: &[u8]) -> usize { 1 + leb128(&p[1..]).map_or(1, |(_, n)| n) + 7 }

pub fn cmd_of(p: &[u8]) -> u16 {
    let h = header_len(p) - 7;
    u16::from_le_bytes([p[h + 2], p[h + 3]])
}

pub fn payload_of(p: &[u8]) -> &[u8] { &p[header_len(p)..] }

/// Splits the RFCOMM byte stream into packets. Bytes before an `AA` are dropped (and counted).
#[derive(Default)]
pub struct Framer { buf: Vec<u8>, pub discarded: usize }

impl Framer {
    pub fn push(&mut self, data: &[u8]) -> Vec<Vec<u8>> {
        self.buf.extend_from_slice(data);
        let mut out = Vec::new();
        loop {
            match self.buf.iter().position(|&b| b == 0xAA) {
                Some(i) => { self.discarded += i; self.buf.drain(..i); }
                None => { self.discarded += self.buf.len(); self.buf.clear(); break; }
            }
            let Some((total, n)) = leb128(&self.buf[1..]) else { break };
            // ponytail: 4 KiB sanity cap; a bogus length resyncs on the next AA.
            if total < 7 || total > 4096 { self.buf.remove(0); self.discarded += 1; continue; }
            let size = 1 + n + total;
            if self.buf.len() < size { break; }
            out.push(self.buf.drain(..size).collect());
        }
        out
    }
}

/// Battery (§7): `<count>` then `<index> <raw>` pairs, 1 left, 2 right, 3 case; level `raw & 7F`, charging `raw & 80`.
pub fn battery(payload: &[u8]) -> Vec<(u8, u8, bool)> {
    let Some((&count, rest)) = payload.split_first() else { return Vec::new() };
    rest.chunks_exact(2).take(count as usize).map(|c| (c[0], c[1] & 0x7F, c[1] & 0x80 != 0)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buds4_from_capture() {
        // Replies the Buds 4 sent to the spike (2026-09-30).
        let caps = Caps::parse(&[0x00, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07]).unwrap();
        assert_eq!(caps.game_mode_id(), FEATURE_GAME_MODE);
        assert_eq!(product_id(&[0x00, 0x14, 0x54, 0x06]).as_deref(), Some("065414"));
        let anc = AncModes::of(find_model(Some("065414"), Some("OnePlus Buds 4")));
        // Adaptive's SET bit is 11, `01 01 00 08` (CLAUDE.md); Off is reported as bit 3, Off's child.
        assert_eq!(anc.bit(OFF), Some(0));
        assert_eq!(anc_payload(anc.bit(ADAPTIVE).unwrap()), [1, 1, 0x00, 0x08]);
        assert_eq!(anc.mode_for_raw(1 << 3, None).as_deref(), Some(OFF));
        assert_eq!(anc.levels().len(), 4);
        let push = build_packet(EVT_PUSH, 7, &[2, 3, 1, 7, 2, 7, 3, 4]);
        assert!(matches!(decode(&push), Some(Event::Wear(w)) if w == [(1, 7), (2, 7), (3, 4)]));
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
        assert_eq!(f.discarded, 2);
        assert_eq!(battery(payload_of(&got[0])), [(1, 100, false), (2, 100, false), (3, 80, true)]);
    }
}
