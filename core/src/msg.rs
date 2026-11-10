//! Typed messages of docs/PROTOCOL.md: decodes what the buds send. Written from the spec, not from
//! `protocol.rs`. Input is a [`Message`] from the link layer; output is [`Inbound`].

use std::collections::BTreeMap;

use crate::wire::Message;

pub const PUSH: u16 = 0x0204;
const REPLY: u16 = 0x8000;

/// These models put the game-mode state in byte 3, not byte 1 `[VENDOR]`, unverified.
pub const STATE_AT_3: [&str; 4] = ["062410", "062810", "063010", "063410"];

fn le16(p: &[u8], i: usize) -> Option<u16> { Some(u16::from_le_bytes(p.get(i..i + 2)?.try_into().ok()?)) }
fn be32(p: &[u8], i: usize) -> Option<u32> { Some(u32::from_be_bytes(p.get(i..i + 4)?.try_into().ok()?)) }
/// 1 to 4 bytes, little endian.
fn le_n(b: &[u8]) -> u32 { b.iter().take(4).rev().fold(0, |v, &x| v << 8 | x as u32) }
/// A MAC on the wire is reversed; this returns it in written order.
fn mac_at(p: &[u8], i: usize) -> Option<[u8; 6]> {
    let mut m: [u8; 6] = p.get(i..i + 6)?.try_into().ok()?;
    m.reverse();
    Some(m)
}

// --- Command bitmap (PROTOCOL.md section 5) ---

/// Commands behind each bit of the `0x8100` reply (PROTOCOL.md §5).
const BITS: [&[u16]; 69] = [
    &[0x0105], &[0x0106], &[0x0107], &[0x0108, 0x0401, 0x0416], &[0x0109], &[0x0400], &[0x0402], &[0x0403],
    &[0x010C, 0x0404], &[0x0405], &[0x0406, 0x010F], &[0x0407], &[], &[0x0408], &[0x0409], &[], &[],
    &[0x0114], &[], &[0x040E, 0x040D, 0x0115, 0x0116], &[0x040F], &[0x0410, 0x0119], &[0x0205], &[0x0F00],
    &[], &[0x0118, 0x0411], &[0x011A, 0x0412], &[0x011C, 0x0413], &[], &[0x0112, 0x040B],
    &[0x011E, 0x011F, 0x0415], &[0x040D], &[], &[0x0121, 0x0417], &[0x0122, 0x0418], &[0x0419],
    &[0x011D, 0x0414], &[0x0123, 0x041A], &[0x0124, 0x041B], &[0x0125, 0x041C, 0x0127, 0x041D, 0x041F],
    &[0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129], &[0xEF01], &[0xEF02], &[0xEF03, 0x041E], &[0x0420],
    &[0x001C], &[], &[0x0422, 0x012A], &[0xEF04], &[0x0423, 0x012B], &[], &[0x0424], &[0xEF06], &[], &[0x012C],
    &[0x0425, 0x012E, 0x0426], &[0x012F], &[0x0427, 0x0130], &[0x0131, 0x0428], &[0x0429, 0x0132], &[0x0014],
    &[0x042D, 0x0133], &[0x042E], &[0xEF07], &[0xEF08, 0x0136, 0x0433], &[0xEF09], &[0x0431, 0x0134],
    &[0x0432, 0x0135, 0x0435], &[0x0117],
];
const ALWAYS: [u16; 11] = [0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04];

/// The commands the buds accept. Before the reply, every command is allowed.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Bitmap(Option<Vec<u16>>);

impl Bitmap {
    /// `00` then bytes, least significant bit first. Bits past the table are ignored.
    pub fn parse(p: &[u8]) -> Option<Bitmap> {
        let [0, bytes @ ..] = p else { return None };
        if bytes.is_empty() { return None; }
        let mut cmds = ALWAYS.to_vec();
        for (bit, set) in BITS.iter().enumerate() {
            if bytes.get(bit / 8).is_some_and(|b| b >> (bit % 8) & 1 == 1) { cmds.extend_from_slice(set); }
        }
        Some(Bitmap(Some(cmds)))
    }
    pub fn allows(&self, cmd: u16) -> bool { self.0.as_ref().map_or(true, |c| c.contains(&cmd)) }
    /// The buds have answered.
    pub fn known(&self) -> bool { self.0.is_some() }
}

// --- Typed values ---

/// One battery cell: part `01` left, `02` right, `03` case.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Cell { pub part: u8, pub level: u8, pub charging: bool }

#[derive(Debug, Clone, PartialEq)]
pub enum Anc {
    /// The current mode as a bitmask (the report table of PROTOCOL.md section 14, not the SET table).
    Mode(u32),
    /// The modes the hold cycles: `kind` `01` shared, `03` left, `04` right; mask in SET bits.
    HoldCycle { kind: u8, mask: u32 },
    /// The level Smart chose, in SET bits.
    Smart(u32),
    /// `01 02 <level>`: a level report `[VENDOR]`, never seen on our buds.
    Level(u8),
}

/// One gesture binding: `dev` 1 left, 2 right, 4 both; `button`; `act`; `func`.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Binding { pub dev: u8, pub button: u8, pub act: u8, pub func: u8 }

/// Both ears' biquad filters of a Golden Sound result: sample rate, left, right.
#[derive(Debug, Clone, PartialEq)]
pub struct Curves { pub rate: u32, pub left: Vec<f32>, pub right: Vec<f32> }

/// A request from the buds, which they expect to be answered (PROTOCOL.md section 11).
#[derive(Debug, Clone, PartialEq)]
pub enum Request {
    /// `0x0500`: the phone's clock.
    Time,
    /// `0x050C`: is the foreground app on a list the buds hold.
    AppList,
    /// `0x051C`: the app's capabilities.
    Capabilities,
    /// `0x051D`: the phone's spatial mode.
    SpatialMode,
    /// `0x0501` and `0x0502`: camera state and shutter in HeyMelody; realme reads `0x0502 05`/`06` as find phone. Not answered.
    Camera(u16),
    /// A command outside the known groups.
    Unknown(u16),
}

/// One custom equaliser preset.
#[derive(Debug, Clone, PartialEq)]
pub struct EqPreset {
    pub id: u8,
    pub name: String,
    pub selected: bool,
    /// Gain range `(min, max)` as the buds send it, echoed back on a save. `FA 06` is -6..+6.
    pub range: (i8, i8),
    /// `(frequency Hz, gain dB)` per band.
    pub bands: Vec<(u16, i8)>,
}

/// One device on the dual-connection list.
#[derive(Debug, Clone, PartialEq)]
pub struct Device { pub mac: [u8; 6], pub name: String, pub connected: bool, pub flags: u8 }

impl Device {
    pub fn this_phone(&self) -> bool { self.flags & 1 != 0 }
}

/// One entry of the connected-devices push `F2`.
#[derive(Debug, Clone, PartialEq)]
pub struct Linked { pub mac: [u8; 6], pub times: u16, pub name: String }

#[derive(Debug, Clone, PartialEq)]
pub enum Public {
    AlertVolume(u8),
    /// `auto`: the buds pick the device. `mac`: the chosen handheld, written order.
    Handheld { auto: bool, mac: Option<[u8; 6]> },
    TapLevel(u8),
    /// A flag set the layout does not name.
    Other(u16),
}

/// Push `0D`. Each field is present when its flag is set and the bytes are there.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Triangle {
    pub capability: Option<u8>,
    pub version: Option<u8>,
    pub wear: Option<u8>,
    /// Bit 0 phone has no active command, bit 1 connected by the related device, bit 2 HFP busy, bit 3 A2DP busy,
    /// bit 4 the other device's auto switch is closed `[VENDOR]`.
    pub status: Option<u8>,
    pub related_name: Option<String>,
}

/// A decoded message from the buds.
#[derive(Debug, Clone, PartialEq)]
pub enum Inbound {
    // Replies (PROTOCOL.md section 3)
    Bitmap(Bitmap),
    /// 24-bit product id, as the buds send it.
    ProductId(u32),
    /// `(device, version type, version)`; device 1 left, 2 right, 3 case.
    Firmware(Vec<(u8, u8, String)>),
    Offered(Vec<u8>),
    /// The `0x8205` reply, after the subscription.
    Registered,
    /// A write reply (`0x84xx`): the command written, the status (`00` accepted) and the bytes after it.
    Ack { cmd: u16, status: u8, data: Vec<u8> },
    Gestures(Vec<Binding>),
    /// `0x810D`: `(feature id, value)`.
    Features(Vec<(u8, u8)>),
    EqCurrent(u8),
    EqCustom(Vec<EqPreset>),
    BassLevel(i8),
    /// `0x812C`: `(band 1-3, level)`.
    BassBands(Vec<(u8, i8)>),
    AlertVolume(u8),
    /// `default` is absent in a push.
    TapLevel { level: u8, default: Option<u8> },
    /// `0x812A` and push `0x0510`: 0 off, 1 fixed, 2 head tracking.
    Spatial(u8),
    Codec(u8),
    /// Bit k of the mask is codec k + 1.
    CodecMask(u16),
    GameSound { selected: u8, offered: Vec<u8> },
    PersonalAncStored(bool),
    /// `None`: the buds choose.
    Preferred(Option<[u8; 6]>),
    /// `0x8115`: the Golden Sound record on the buds. `values`: left ears 1-6, then right ears 1-6.
    GoldenRecord { id: u32, name: String, values: [i8; 12] },
    /// `0x811E`: its ear-scan data.
    GoldenScan { id: u32, data: Vec<u8> },
    /// `0x8116`: the hearing filters of a result and its enhance type.
    GoldenFilter { id: u32, enhance: u8, curves: Option<Curves> },
    /// `0x811F`: the ear-scan filters of a record.
    GoldenScanFilter { id: u32, curves: Curves },
    Request(Request),
    // Pushes (PROTOCOL.md section 10); the same facts also arrive as replies to a query
    Battery(Vec<Cell>),
    Wear(Vec<(u8, u8)>),
    Anc(Anc),
    FitTest { left: Option<u8>, right: Option<u8> },
    /// Both candidates; `game_mode_state` picks one by product id (PROTOCOL.md section 10, code `05`).
    GameMode { at1: Option<u8>, at3: Option<u8> },
    /// `0x0503`: feature id -> value; bytes after the last pair are ignored.
    FeaturePush(BTreeMap<u8, u8>),
    Devices(Vec<Device>),
    Hearing { kind: u8, status: u8 },
    ZenState(u8),
    PersonalAnc(u8),
    Triangle(Triangle),
    EarScan { id: u32, data: Vec<u8> },
    Public(Public),
    Gesture { side: u8, button: u8, act: u8, func: u8, context: u8, options: Vec<i16> },
    Linked(Vec<Linked>),
    Diagnostic(String),
    HeadGesture(u8),
    HeadTry(u8),
    /// `kind`: 0, 1 or 2 from flag bits 0-2; `flag`: bit 7.
    VoiceAssist { kind: Option<u8>, flag: bool },
    LinkSwitch { state: u8, owner: u8, reset: u8 },
    /// A push the spec names but no feature uses (`10`, `11`, `F3`, `F9`, ...): code and bytes after it.
    Unused(u8, Vec<u8>),
}

impl Inbound {
    /// The game-mode state for `product_id`: byte 3 for `STATE_AT_3`, byte 1 otherwise.
    /// `None` for another message, or when the chosen byte is absent.
    pub fn game_mode_state(&self, product_id: Option<&str>) -> Option<bool> {
        let Inbound::GameMode { at1, at3 } = self else { return None };
        let at = match product_id.map(str::to_uppercase) {
            Some(id) if STATE_AT_3.contains(&id.as_str()) => at3,
            _ => at1,
        };
        (*at).map(|v| v != 0)
    }
}

/// Decodes one message. `None`: not a message this layer knows, or a malformed one.
pub fn decode(m: &Message) -> Option<Inbound> {
    let p = &m.payload[..];
    if m.cmd == PUSH { return push(p); }
    // A write reply or a status-led reply starts with a status byte; a non-zero one carries no data.
    Some(match m.cmd {
        0x8100 => Inbound::Bitmap(Bitmap::parse(p)?),
        0x8103 => match p { [0, a, b, c] => Inbound::ProductId(u32::from_le_bytes([*a, *b, *c, 0])), _ => return None },
        0x8105 => Inbound::Firmware(firmware(p)?),
        0x8200 => match p { [0, n, codes @ ..] => Inbound::Offered(codes.get(..*n as usize)?.to_vec()), _ => return None },
        0x8205 => Inbound::Registered,
        0x8106 => Inbound::Battery(battery(ok(p)?)?),
        0x8109 => {
            // `<count> <pairs>`, or a status byte first; a list is valid when its parts are 1-3.
            let valid = |v: &Vec<(u8, u8)>| v.iter().all(|x| (1..=3).contains(&x.0));
            let list = pairs(p).filter(valid).or_else(|| pairs(p.get(1..)?).filter(valid))?;
            Inbound::Wear(list.into_iter().map(|(c, s)| (c, s & 7)).collect())
        }
        0x810C => Inbound::Anc(anc(ok(p)?)?),
        0x8108 => {
            let [0, n, e @ ..] = p else { return None };
            let b: Vec<Binding> = e.chunks_exact(4).take(*n as usize).map(|c| Binding { dev: c[0], button: c[1], act: c[2], func: c[3] }).collect();
            // A reply with no entries is no table: a write is built from the last good one.
            if b.is_empty() { return None; }
            Inbound::Gestures(b)
        }
        0x810D => Inbound::Features(pairs(ok(p)?)?),
        0x810F => Inbound::EqCurrent(*ok(p)?.first()?),
        0x8122 => Inbound::EqCustom(presets(ok(p)?)?),
        0x8124 => match ok(p)? { [_, _, level, ..] => Inbound::BassLevel(*level as i8), _ => return None },
        0x812C => Inbound::BassBands(bass_bands(ok(p)?)?),
        0x0503 => Inbound::FeaturePush(feature_push(p)?),
        0x0504 => Inbound::EqCurrent(*p.first()?),
        0x8130 => Inbound::AlertVolume(*ok(p)?.first()?),
        0x8133 => match ok(p)? { [level, default, ..] => Inbound::TapLevel { level: *level, default: Some(*default) }, _ => return None },
        0x812A => Inbound::Spatial(*ok(p)?.first()?),
        0x0510 => Inbound::Spatial(*p.first()?),
        0x8134 => Inbound::HeadGesture(*ok(p)?.first()?),
        0x8114 => Inbound::Codec(*ok(p)?.first()?),
        0x8123 => Inbound::CodecMask(le16(ok(p)?, 0)?),
        0x812B => match ok(p)? {
            [selected, n, rest @ ..] => Inbound::GameSound { selected: *selected, offered: rest[..rest.len().min(*n as usize)].to_vec() },
            _ => return None,
        },
        0x811A => Inbound::PersonalAncStored(*ok(p)?.first()? != 0),
        0x8112 => Inbound::Devices(devices(ok(p)?)?),
        0x8132 => match p { [0, 2, 0, ..] => Inbound::Preferred(None), [0, 2, _, ..] => Inbound::Preferred(Some(p.get(3..9)?.try_into().ok()?)), _ => return None },
        0x8115 => golden_record(ok(p)?)?,
        0x811E => { let (id, data) = ear_scan_body(ok(p)?, 0)?; Inbound::GoldenScan { id, data } }
        0x8116 => match ok(p)? {
            d if d.len() >= 9 => Inbound::GoldenFilter { id: be32(d, 0)?, enhance: d[8], curves: curves(d, false) },
            _ => return None,
        },
        0x811F => Inbound::GoldenScanFilter { id: be32(ok(p)?, 0)?, curves: curves(ok(p)?, true)? },
        c if c & 0x8000 == 0 && c != PUSH => Inbound::Request(request(c)?),
        c if c & 0xFF00 == 0x8400 => {
            let (&status, data) = p.split_first()?;
            Inbound::Ack { cmd: c & 0x7FFF, status, data: data.to_vec() }
        }
        _ => return None,
    })
}

/// What a request from the buds is. `None`: the command is in a known group and not a request.
fn request(cmd: u16) -> Option<Request> {
    Some(match cmd {
        0x0500 => Request::Time,
        0x050C => Request::AppList,
        0x051C => Request::Capabilities,
        0x051D => Request::SpatialMode,
        0x0501 | 0x0502 => Request::Camera(cmd),
        c if ![0x01, 0x02, 0x03, 0x04, 0x05, 0x0F].contains(&(c >> 8)) => Request::Unknown(c),
        _ => return None,
    })
}

/// `<kind> <len u16 LE> <data> <id u32 BE>`, from `from`.
fn ear_scan_body(p: &[u8], from: usize) -> Option<(u32, Vec<u8>)> {
    let len = le16(p, from + 1)? as usize;
    if len == 0 { return None; }
    Some((be32(p, from + 3 + len)?, p.get(from + 3..from + 3 + len)?.to_vec()))
}

/// `03 0C <12 x (side freq value)> <id u32 BE> <name>`.
fn golden_record(d: &[u8]) -> Option<Inbound> {
    if d.len() < 2 || d[1] != 12 { return None; }
    let end = 2 + 36;
    let id = be32(d, end)?;
    let mut values = [0i8; 12];
    for c in d[2..end].chunks_exact(3) {
        if !(1..=2).contains(&c[0]) || !(1..=6).contains(&c[1]) { return None; }
        values[(c[0] as usize - 1) * 6 + c[1] as usize - 1] = c[2] as i8;
    }
    Some(Inbound::GoldenRecord { id, name: String::from_utf8_lossy(&d[end + 4..]).into_owned(), values })
}

/// Filter floats, little endian, left ear then right. `0x8116`: `<id> 04 <count LE> <packet> <type> <floats>`.
/// `0x811F`: `<id> 04 <rate LE> <count LE> <packet> <floats>`. `d` starts at the id; a count is a multiple of 12.
fn curves(d: &[u8], scan: bool) -> Option<Curves> {
    if d.len() < 10 || d[4] != 4 { return None; }
    let (rate, count, start) = if scan { (le16(d, 5)? as u32, le16(d, 7)? as usize, 10) } else { (44100, le16(d, 5)? as usize, 9) };
    if count == 0 || count % 12 != 0 { return None; }
    let f: Vec<f32> = d.get(start..start + count * 4)?.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect();
    Some(Curves { rate, left: f[..count / 2].to_vec(), right: f[count / 2..].to_vec() })
}

/// The bytes after a `00` status byte. A reply with another status carries no data.
fn ok(p: &[u8]) -> Option<&[u8]> { match p { [0, rest @ ..] => Some(rest), _ => None } }

/// ANC data: `01 01 <mode LE>`, `01 02 <level>`, `02 <kind> <mask LE>`, `04 01 <mask LE>`. Used by `0x810C` and push `03`.
fn anc(d: &[u8]) -> Option<Anc> {
    Some(match d {
        [1, 1, v @ ..] if !v.is_empty() => Anc::Mode(le_n(v)),
        [1, 2, level, ..] => Anc::Level(*level),
        [2, kind, m @ ..] if !m.is_empty() => Anc::HoldCycle { kind: *kind, mask: le_n(m) },
        [4, 1, m @ ..] if !m.is_empty() => Anc::Smart(le_n(m)),
        _ => return None,
    })
}

/// `<count> <flag> <min> <max> <id> <nameLen> <name> <bands> [<freq u16 LE> <gain s8>]...` per preset.
fn presets(p: &[u8]) -> Option<Vec<EqPreset>> {
    let (&n, mut rest) = p.split_first()?;
    let mut out = Vec::new();
    for _ in 0..n {
        let h = rest.get(..5)?;
        let k = h[4] as usize;
        let name = String::from_utf8_lossy(rest.get(5..5 + k)?).into_owned();
        let bands = *rest.get(5 + k)? as usize;
        let data = rest.get(6 + k..6 + k + bands * 3)?;
        out.push(EqPreset {
            id: h[3], name, selected: h[0] == 1, range: (h[1] as i8, h[2] as i8),
            bands: data.chunks_exact(3).map(|c| (u16::from_le_bytes([c[0], c[1]]), c[2] as i8)).collect(),
        });
        rest = &rest[6 + k + bands * 3..];
    }
    Some(out)
}

/// `00 <count>` then UTF-8 `device,type,version` triples joined by commas.
fn firmware(p: &[u8]) -> Option<Vec<(u8, u8, String)>> {
    let [0, _, text @ ..] = p else { return None };
    let text = String::from_utf8_lossy(text);
    let parts: Vec<&str> = text.split(',').map(str::trim).collect();
    let v: Vec<_> = parts.chunks_exact(3).filter_map(|c| Some((c[0].parse().ok()?, c[1].parse().ok()?, c[2].to_string()))).collect();
    (!v.is_empty()).then_some(v)
}

/// `0x0503`: `<count> <id> <value>...`; bytes after the last pair are ignored.
fn feature_push(p: &[u8]) -> Option<BTreeMap<u8, u8>> {
    let (&count, rest) = p.split_first()?;
    let body = rest.get(..count as usize * 2)?;
    Some(body.chunks_exact(2).map(|c| (c[0], c[1])).collect())
}

/// `<count>` then `<a> <b>` pairs. `None` when the pairs run past the end.
fn pairs(p: &[u8]) -> Option<Vec<(u8, u8)>> {
    let (&n, rest) = p.split_first()?;
    // A count of 0 is no list.
    (n > 0 && rest.len() >= n as usize * 2).then(|| rest.chunks_exact(2).take(n as usize).map(|c| (c[0], c[1])).collect())
}

/// `<count>` then `<min> <max> <level> <band>` per band (`0x812C`). `None` when the records run past the end.
fn bass_bands(p: &[u8]) -> Option<Vec<(u8, i8)>> {
    let (&n, rest) = p.split_first()?;
    (n > 0 && rest.len() >= n as usize * 4).then(|| rest.chunks_exact(4).take(n as usize).map(|c| (c[3], c[2] as i8)).collect())
}

fn battery(p: &[u8]) -> Option<Vec<Cell>> {
    // Level 0 means "not known" (a closed case): left out.
    Some(pairs(p)?.into_iter()
        .filter(|c| c.1 & 0x7F != 0)
        .map(|(part, raw)| Cell { part, level: raw & 0x7F, charging: raw & 0x80 != 0 })
        .collect())
}

/// Status bit 3 is a flag; the other bits are the state.
fn wear(p: &[u8]) -> Option<Vec<(u8, u8)>> { Some(pairs(p)?.into_iter().map(|(c, s)| (c, s & 7)).collect()) }

/// `<count>` then `<MAC 6> <len> <state> <flags> <nameLen> <name>`.
pub fn devices(p: &[u8]) -> Option<Vec<Device>> {
    let (&n, mut rest) = p.split_first()?;
    let mut out = Vec::new();
    for _ in 0..n {
        let h = rest.get(..10)?;
        let k = h[9] as usize;
        out.push(Device { mac: mac_at(h, 0)?, connected: h[7] == 2, flags: h[8], name: String::from_utf8_lossy(rest.get(10..10 + k)?).into_owned() });
        rest = &rest[10 + k..];
    }
    Some(out)
}

/// `<count>` then `<MAC 6> <times u16 LE> <nameLen> <name>`.
fn linked(p: &[u8]) -> Option<Vec<Linked>> {
    let (&n, mut rest) = p.split_first()?;
    let mut out = Vec::new();
    for _ in 0..n {
        let k = *rest.get(8)? as usize;
        out.push(Linked { mac: mac_at(rest, 0)?, times: le16(rest, 6)?, name: String::from_utf8_lossy(rest.get(9..9 + k)?).into_owned() });
        rest = &rest[9 + k..];
    }
    Some(out)
}

/// Ear scan: `<kind> <len u16 LE> <data> <id u32 BE>` after the code.
fn ear_scan(p: &[u8]) -> Option<(u32, Vec<u8>)> {
    let len = le16(p, 1)? as usize;
    if len == 0 { return None; }
    Some((be32(p, 3 + len)?, p.get(3..3 + len)?.to_vec()))
}

fn public(p: &[u8]) -> Option<Public> {
    // Flags u16 LE, then data. Flag bit 0 wins over bit 1, bit 1 over bit 2, bit 2 over bit 4.
    if p.len() < 3 { return None; }
    let flags = le16(p, 0)?;
    Some(if flags & 1 != 0 { Public::AlertVolume(p[2]) }
        else if flags & 2 != 0 { Public::Other(flags) }
        else if flags & 4 != 0 {
            let auto = p[2] == 0;
            Public::Handheld { auto, mac: if auto { None } else { Some(mac_at(p, 3).filter(|_| p.len() > 8)?) } }
        } else if flags & 16 != 0 { Public::TapLevel(p[2]) }
        else { Public::Other(flags) })
}

fn triangle(p: &[u8]) -> Option<Triangle> {
    let flags = le16(p, 0)?;
    let mut t = Triangle::default();
    let mut i = 2;
    if i >= p.len() { return Some(t); }
    let byte = |i: &mut usize| { let b = p.get(*i).copied(); if b.is_some() { *i += 1; } b };
    if flags & 1 != 0 { t.capability = byte(&mut i); }
    if flags & 2 != 0 { t.version = byte(&mut i); }
    if flags & 4 != 0 { t.wear = byte(&mut i); }
    if flags & 8 != 0 { t.status = byte(&mut i); }
    if flags & 16 != 0 {
        if let Some(k) = byte(&mut i) { t.related_name = p.get(i..i + k as usize).map(|n| String::from_utf8_lossy(n).into_owned()); }
    }
    Some(t)
}

/// `payload[0]` is the event code.
fn push(p: &[u8]) -> Option<Inbound> {
    let (&code, d) = p.split_first()?;
    Some(match code {
        0x01 => Inbound::Battery(battery(d)?),
        0x02 => Inbound::Wear(wear(d)?),
        0x03 => Inbound::Anc(anc(d)?),
        0x04 => {
            let r: Vec<(u8, u8)> = d.chunks_exact(2).map(|c| (c[0], c[1])).collect();
            let of = |dev| r.iter().find(|x| x.0 == dev).map(|x| x.1);
            Inbound::FitTest { left: of(1), right: of(2) }
        }
        0x05 if !d.is_empty() => Inbound::GameMode { at1: d.first().copied(), at3: d.get(2).copied() },
        0x06 => Inbound::Devices(devices(d)?),
        0x08 if d.len() >= 2 => Inbound::Hearing { kind: d[0], status: d[1] },
        0x0A if !d.is_empty() => Inbound::ZenState(d[0]),
        0x0B if !d.is_empty() => Inbound::PersonalAnc(d[0]),
        0x0D => Inbound::Triangle(triangle(d)?),
        0x0E => { let (id, data) = ear_scan(d)?; Inbound::EarScan { id, data } }
        0x0F => Inbound::Public(public(d)?),
        0xF1 if d.len() >= 5 => Inbound::Gesture {
            side: d[0], button: d[1], act: d[2], func: d[3], context: d[4],
            options: d[5..].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect(),
        },
        0xF2 => Inbound::Linked(linked(d)?),
        0xF4 => Inbound::Diagnostic(String::from_utf8_lossy(d).into_owned()),
        0xF5 if !d.is_empty() => Inbound::HeadGesture(d[0]),
        0xF6 if !d.is_empty() => Inbound::HeadTry(d[0]),
        0xF7 if !d.is_empty() => Inbound::VoiceAssist {
            kind: [(4, 2), (2, 1), (1, 0)].into_iter().find(|(bit, _)| d[0] & bit != 0).map(|x| x.1),
            flag: d[0] & 0x80 != 0,
        },
        0xF8 if !d.is_empty() => Inbound::LinkSwitch { state: d[0] & 1, owner: d[0] >> 1 & 1, reset: d[0] >> 2 & 1 },
        // A named code with too few bytes is malformed and dropped.
        _ if !NAMED.contains(&code) => Inbound::Unused(code, d.to_vec()),
        _ => return None,
    })
}

const NAMED: &[u8] = &[1, 2, 3, 4, 5, 6, 8, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0xF1, 0xF2, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8];

// --- Requests from the buds (PROTOCOL.md section 11) ---

/// The answer to a message the buds sent on their own, or `None` (no answer is due). `unix`: the phone's clock.
pub fn answer(m: &Message, unix: u32) -> Option<Message> {
    if m.cmd & 0x8000 != 0 { return None; }
    let payload = match decode(m) {
        Some(Inbound::Request(r)) => match r {
            Request::Time => [&[0u8][..], &unix.to_le_bytes()].concat(),
            Request::AppList => vec![0, 1, 0],
            Request::Capabilities => vec![0, 0, 0],
            // Always "invalid": the phone's spatial mode is not known here.
            Request::SpatialMode => vec![1],
            Request::Camera(_) => return None,
            Request::Unknown(_) => vec![1],
        },
        // Answered whether or not the list parses: the buds wait for it.
        _ if m.cmd == PUSH && m.payload.first() == Some(&0xF2) => vec![0, 0xF2],
        _ if m.cmd == PUSH && m.payload.first() == Some(&0xF4) => json_ack(&m.payload[1..]),
        _ => return None,
    };
    Some(Message { cmd: m.cmd | REPLY, seq: m.seq, payload })
}

/// `F4` JSON: `00 F4 {"cmd":<its cmd>}`; `01 F4` when it does not parse.
fn json_ack(json: &[u8]) -> Vec<u8> {
    if json.is_empty() { return vec![0, 0xF4]; }
    match crate::json::cmd_of(json) {
        Some(c) => [&[0u8, 0xF4][..], c.map_or("{}".into(), |c| crate::json::echo(&c)).as_bytes()].concat(),
        None => vec![1, 0xF4],
    }
}

// --- Batch (PROTOCOL.md section 12) ---

/// `0x812F`: `00 <count>`, then `<cmd LE> <len LE> <reply payload>` per answer. Each answer is the reply
/// `cmd | 0x8000` with the batch's `seq`. Stops at an answer that runs past the end.
pub fn unbatch(m: &Message) -> Vec<Message> {
    let mut out = Vec::new();
    let [0, n, rest @ ..] = &m.payload[..] else { return out };
    let mut r = rest;
    for _ in 0..*n {
        let [c0, c1, l0, l1, tail @ ..] = r else { break };
        let len = u16::from_le_bytes([*l0, *l1]) as usize;
        let Some(body) = tail.get(..len) else { break };
        out.push(Message { cmd: u16::from_le_bytes([*c0, *c1]) | REPLY, seq: m.seq, payload: body.to_vec() });
        r = &tail[len..];
    }
    out
}
