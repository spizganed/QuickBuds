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
/// `01` start / `00` stop, both buds (§9 Find my earbuds).
pub const CMD_FIND_BUDS: u16 = 0x0400;
/// `<level>` 1..10, ack `8427 00 <level>`; read `0x0130` -> `00 <level>` (§9).
pub const CMD_SET_ALERT_VOLUME: u16 = 0x0427;
pub const CMD_QUERY_ALERT_VOLUME: u16 = 0x0130;
/// `<level>` 1..5; read `0x0133` -> `00 <level> <default>` (§9, unverified).
pub const CMD_SET_TAP_LEVEL: u16 = 0x042D;
pub const CMD_QUERY_TAP_LEVEL: u16 = 0x0133;
pub const CMD_FIT_TEST: u16 = 0x0405;
pub const CMD_GOLDEN_DETECT: u16 = 0x040D;
/// Personalized ANC (§9, unverified): `<action>` 1 test / 2 use the stored result / 3 cancel, ack `8412 <status>`;
/// `0x011A` -> `00 <exist>`; the test's result is push `0B <r>` (0 applied, 1-5 a reason it failed).
pub const CMD_PERSONAL_NOISE: u16 = 0x0412;
pub const CMD_QUERY_PERSONAL_NOISE: u16 = 0x011A;
pub const FEATURE_PERSONAL_NOISE: u8 = 0x0C;
/// Game sound type `<type> 01`; read `0x012B` -> `00 <selected> <count> <types>` (§9).
pub const CMD_GAME_SOUND: u16 = 0x0423;
pub const CMD_QUERY_GAME_SOUND: u16 = 0x012B;
/// Head gesture mapping `<type>` (0 nod answers, 1 shake answers); read `0x0134`, the type comes as push `F5`.
pub const CMD_SET_HEAD_MOTION: u16 = 0x0431;
pub const CMD_QUERY_HEAD_MOTION: u16 = 0x0134;
pub const CMD_QUERY_FIRMWARE: u16 = 0x0105;
pub const CMD_QUERY_EQ: u16 = 0x010F;
pub const CMD_QUERY_EQ_ALL: u16 = 0x0122;
pub const CMD_QUERY_BASSWAVE: u16 = 0x0124;
pub const CMD_SET_EQ: u16 = 0x0406;
pub const CMD_SAVE_CUSTOM_EQ: u16 = 0x0418;
pub const CMD_SET_BASSWAVE: u16 = 0x041B;
pub const EVT_EQ_CHANGED: u16 = 0x0504;
pub const FEATURE_BASSWAVE: u8 = 0x1D;
pub const FEATURE_POWER_SAVING: u8 = 0x17;
pub const FEATURE_HEARING_OPTIMIZE: u8 = 0x38;
pub const EVT_PUSH: u16 = 0x0204;
/// Dual connection (§9): switch `0x11`; the device list `0x0112` -> `8112 00 <list>`, push `0204 06 <list>`.
/// Each toggle is followed by `0x0413 08 00 <00 after on | 01 after off>` (HeyMelody's, meaning unknown).
pub const FEATURE_DUAL: u8 = 0x11;
pub const CMD_QUERY_DEVICES: u16 = 0x0112;
pub const CMD_DUAL_FOLLOWUP: u16 = 0x0413;
/// Device manager (§9, unverified): `01|02 <MAC>` connect / disconnect, `04 00` preferred device automatic,
/// `04 01 <MAC>`; read `0x0132 02` -> `00 02 <00 | 01 <MAC>>`. MACs in written order (the list's reversed).
pub const CMD_MULTI_CONNECT: u16 = 0x0429;
pub const CMD_QUERY_PREFERRED: u16 = 0x0132;

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

/// `ModelCatalog.find`: the product id (unique in the list), else the device name (a renamed device has none).
pub fn find_model(id: Option<&str>, name: Option<&str>) -> Option<&'static Value> {
    let m = models();
    let by_name = |x: &&Value| name.is_some() && x["name"].as_str() == name;
    let by_id = |x: &&Value| id.is_some_and(|id| x["id"].as_str().is_some_and(|i| i.eq_ignore_ascii_case(id)));
    m.iter().find(by_id).or_else(|| m.iter().find(by_name))
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

// --- Equalizer (PROTOCOL.md §9 Equalizer, `EqCodec.kt`) ---

#[derive(Clone, PartialEq, Debug)]
pub struct Preset {
    pub id: u8,
    pub name: String,
    pub freqs: Vec<u16>,
    pub gains: Vec<i8>,
    pub selected: bool,
    /// Gain range as (min, max), `FA 06` = -6..+6; echoed back verbatim.
    pub tag: [u8; 2],
}

pub const EQ_CREATE: u8 = 0x01;
/// Select AND save in one frame (a band edit or a rename is the same).
pub const EQ_SAVE: u8 = 0x02;
pub const EQ_DELETE: u8 = 0x03;
pub const EQ_GAIN: i32 = 6;
/// HeyMelody's bands where the model gives none (`customEqFrequency`).
pub const EQ_DEFAULT_FREQS: [u16; 6] = [62, 250, 1000, 4000, 8000, 16000];

impl Preset {
    pub fn new(name: &str, freqs: Vec<u16>) -> Preset {
        let gains = vec![0; freqs.len()];
        Preset { id: 0, name: name.into(), freqs, gains, selected: false, tag: [(-EQ_GAIN) as i8 as u8, EQ_GAIN as u8] }
    }

    /// `0x0418` payload: `<action> <tag> <id> <nameLen> <name> <bandCount> [freq u16 LE, gain s8]...`
    pub fn encode(&self, action: u8) -> Vec<u8> {
        let mut out = vec![action, self.tag[0], self.tag[1], self.id, self.name.len() as u8];
        out.extend_from_slice(self.name.as_bytes());
        out.push(self.freqs.len() as u8);
        for (f, g) in self.freqs.iter().zip(&self.gains) {
            out.extend_from_slice(&f.to_le_bytes());
            out.push(*g as u8);
        }
        out
    }
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

/// A model flag from `models.json`; nothing detected counts as Buds 4, which has them all (as on the phone).
fn flag(model: Option<&Value>, key: &str) -> bool { model.map_or(true, |m| m[key].as_i64() == Some(1)) }

pub fn eq_has_custom(model: Option<&Value>, caps: &Caps) -> bool {
    flag(model, "customEqualizer") && caps.supports(CMD_SAVE_CUSTOM_EQ)
}

pub fn eq_has_bass(model: Option<&Value>, caps: &Caps) -> bool {
    flag(model, "bassEngineSupport") && caps.supports(CMD_SET_BASSWAVE)
}

/// `customEqMax`, else 3.
pub fn eq_max_custom(model: Option<&Value>) -> usize {
    model.and_then(|m| m["customEqMax"].as_u64()).filter(|&n| n > 0).unwrap_or(3) as usize
}

pub fn eq_model_freqs(model: Option<&Value>) -> Vec<u16> {
    model.and_then(|m| m["customEqFrequency"].as_array())
        .map(|a| a.iter().filter_map(|f| f.as_u64().map(|f| f as u16)).collect())
        .unwrap_or_else(|| EQ_DEFAULT_FREQS.to_vec())
}

/// `0x8105` text `1,2,138,2,2,138,3,1,01,3,2,105` -> "138.138.105" (the `versionType` 2 entries).
pub fn firmware_version(text: &str) -> Option<String> {
    let parts: Vec<&str> = text.split(',').collect();
    let v: Vec<&str> = parts.chunks(3).filter(|c| c.len() == 3 && c[1].trim() == "2").map(|c| c[2].trim()).collect();
    (!v.is_empty()).then(|| v.join("."))
}

/// The model's built-in presets as (`0x0406` id, `strings.xml` key), in HeyMelody's order (`EqActivity.builtInPresets`).
pub fn eq_builtins(model: Option<&Value>, firmware: Option<&str>) -> Vec<(u8, &'static str)> {
    let buds4 = serde_json::json!([{"modeType":11,"protocolIndex":0},{"modeType":14,"protocolIndex":1},{"modeType":12,"protocolIndex":2}]);
    let modes: Vec<Value> = match model {
        Some(m) => {
            // The lower non-zero of the two buds' versions; unknown = 0.
            let nums: Vec<i64> = firmware.unwrap_or("").split('.').map(|p| p.parse().unwrap_or(0)).collect();
            let version = if nums.len() == 3 { nums[..2].iter().copied().filter(|&n| n != 0).min().unwrap_or(0) } else { nums.first().copied().unwrap_or(0) };
            let mut v: Vec<Value> = ["equalizerModeCompat", "equalizerModeByVersion", "equalizerMode"].iter()
                .flat_map(|k| m[*k].as_array().cloned().unwrap_or_default())
                .filter(|e| e["minFirmVersion"].as_i64().unwrap_or(0) <= version)
                .collect();
            v.sort_by_key(|e| e["order"].as_i64().unwrap_or(0));
            v
        }
        None => buds4.as_array().unwrap().clone(),
    };
    let name = model.and_then(|m| m["name"].as_str()).unwrap_or("");
    let alt = name == "OPPO Enco R" || name == "OPPO Enco Air2" || model.is_some_and(|m| m["equalizer"].as_i64() == Some(2));
    modes.iter().filter_map(|m| {
        let key = match m["modeType"].as_i64()? {
            1 => if alt { "eq_nature_balance" } else { "eq_classic" },
            2 => if alt { "eq_bass_boost" } else { "eq_dynamic_bass" },
            3 | 14 | 32 => "eq_clear_vocals",
            4 => if name == "OPPO Enco R" { "eq_gentle" } else { "eq_clear" },
            5 | 35 => "eq_default",
            6 | 36 => "eq_dyn_simple",
            7 | 37 => "eq_dyn_warm",
            8 | 38 => "eq_dyn_punchy",
            9 | 39 => "eq_dyn_real",
            10 => "eq_hisaishi",
            11 | 17 => "eq_balanced",
            12 => "eq_bass",
            13 => "eq_bold",
            15 => "eq_gentle",
            16 => "eq_enco_x_classic",
            18 => "eq_reno_dawn",
            19 => "eq_hans_zimmer",
            20 => "eq_natural_inspiration",
            21 => "eq_reno_sunrise",
            22 => "eq_nature_balance",
            23 => "eq_punchy",
            24 => "eq_spacious",
            25 => "eq_reno_galaxy",
            26 => "eq_ultimate",
            27 => "eq_hd_clarity",
            28 => "eq_pure_vocals",
            29 => "eq_thundering_bass",
            30 => "eq_dyn_featured",
            31 => "eq_bass_boost",
            33 => "eq_galactic",
            34 => "eq_vibrant",
            40 => "eq_dyn_vocal",
            41 => "eq_clear_crisp",
            _ => return None, // unnamed in HeyMelody too
        };
        Some((m["protocolIndex"].as_u64()? as u8, key))
    }).collect()
}

// --- Hearing profile (PROTOCOL.md §9 Golden Sound) ---

/// On / off; it applies the record stored on the buds.
pub const FEATURE_HEARING: u8 = 0x0B;
pub const CMD_HEARING_RECORD: u16 = 0x040E;
pub const CMD_HEARING_RESTORE: u16 = 0x0411;
pub const CMD_HEARING_SCAN_DATA: u16 = 0x0415;
pub const CMD_HEARING_FILTER: u16 = 0x0116;
pub const CMD_HEARING_SCAN_FILTER: u16 = 0x011F;
pub const CMD_HEARING_ACTIVE: u16 = 0x0115;
pub const CMD_HEARING_ACTIVE_SCAN: u16 = 0x011E;

/// The hearing test slider's 25 stops.
pub const HEARING_STOPS: [i8; 25] = [-120, -88, -55, -52, -49, -45, -41, -38, -35, -30, -25, -22, -19, -15, -11, -8, -5, -1, 3, 5, 7, 10, 13, 15, 17];
/// The values a result can hold; a stop is saved as the nearest one.
const HEARING_RESULTS: [i8; 12] = [-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17];
/// Where each tone starts (`-30`), and the stop from which HeyMelody warns about loudness (value 10).
pub const HEARING_START: usize = 9;
pub const HEARING_LOUD: usize = 21;

pub fn hearing_snap(v: i8) -> i8 { *HEARING_RESULTS.iter().min_by_key(|r| (**r as i32 - v as i32).abs()).unwrap() }

/// A frame to send: (command, payload).
pub type Frame = (u16, Vec<u8>);

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

pub type Curves = (u32, Vec<f32>, Vec<f32>);

#[derive(Clone, Debug)]
pub enum HearingEv {
    /// Push `08`: kind 2 test / 4 scan, status (1/3 audio playing, 5 a bud out, 7/10/255 failed).
    Status(u8, u8),
    /// Push `0E`: the ear scan finished.
    EarScan(u32, Vec<u8>),
    /// `0x8116`: a result's enhance type, and its hearing filters.
    Filter(u32, u8, Option<Curves>),
    /// `0x811F`: a record's ear-scan filters.
    ScanCurves(u32, Curves),
    /// `0x8115`: the record on the buds.
    Active(u32, String, [i8; 12]),
    /// `0x811E`: its ear-scan data.
    ActiveScan(u32, Vec<u8>),
}

/// HeyMelody's radar axes (Hz), in its order (80 at the top, clockwise), and each one's scale in dB.
pub const HEARING_AXES: [u32; 6] = [80, 10000, 4800, 2400, 1200, 250];
const AXIS_SCALE: [f64; 6] = [7.5, 15.0, 15.0, 12.5, 12.5, 7.5];

/// The summed response of biquads (a0 a1 a2 b0 b1 b2 each) at `freq`, in dB.
fn response_db(c: &[f32], fs: u32, freq: u32) -> f64 {
    let w = 2.0 * std::f64::consts::PI * freq as f64 / fs as f64;
    let mag = |x: &[f32]| {
        let (x0, x1, x2) = (x[0] as f64, x[1] as f64, x[2] as f64);
        (x0 + x1 * w.cos() + x2 * (2.0 * w).cos()).hypot(-x1 * w.sin() - x2 * (2.0 * w).sin())
    };
    c.chunks_exact(6).map(|k| (mag(&k[..3]), mag(&k[3..]))).filter(|(d, n)| *d > 0.0 && *n > 0.0)
        .map(|(d, n)| 20.0 * (n / d).log10()).sum()
}

/// One ear's radar radii (0..10, 10 = no change, at least 2), from its hearing and ear-scan filters.
pub fn hearing_radar(hearing: &[f32], scan: Option<(&[f32], u32)>) -> [f32; 6] {
    std::array::from_fn(|i| {
        let db = response_db(hearing, 44100, HEARING_AXES[i]) + scan.map_or(0.0, |(c, fs)| response_db(c, fs, HEARING_AXES[i]));
        (-db.abs() * 10.0 / AXIS_SCALE[i] + 10.0).max(2.0) as f32
    })
}

/// One device on the buds' list.
#[derive(Clone, PartialEq, Debug)]
pub struct PairedDevice {
    /// In written order.
    pub mac: [u8; 6],
    pub name: String,
    pub connected: bool,
    /// Flags bit 0: the device reading the list.
    pub this_device: bool,
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

// --- Incoming packets ---

pub enum Event {
    Firmware(String),
    EqCurrent(u8),
    EqCustom(Vec<Preset>),
    BassLevel(i8),
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
    AlertVolume(u8),
    /// (selected, offered types)
    GameSound(u8, Vec<u8>),
    HeadMotion(u8),
    /// Fit test result, left and right status (§9: 1 good, 0 average, 6 poor, else an error; 0xFF missing).
    FitResult(u8, u8),
    PncStored(bool),
    PncAck(u8),
    PncResult(u8),
    /// (level, the buds' default)
    TapLevel(u8, u8),
    Devices(Vec<PairedDevice>),
    Hearing(HearingEv),
    /// None = automatic.
    Preferred(Option<[u8; 6]>),
}

/// `<a> <b>` pairs with no count byte.
fn pairs_n(b: &[u8]) -> Vec<(u8, u8)> { b.chunks_exact(2).map(|c| (c[0], c[1])).collect() }

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
        0x8130 | 0x8427 if sub == Some(0) && pl.len() >= 2 => Event::AlertVolume(pl[1]),
        0x8133 if sub == Some(0) && pl.len() >= 3 => Event::TapLevel(pl[1], pl[2]),
        0x811A if sub == Some(0) && pl.len() >= 2 => Event::PncStored(pl[1] != 0),
        0x8412 if !pl.is_empty() => Event::PncAck(pl[0]),
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
            0x01 => Event::Battery(battery(&pl[1..])),
            0x02 => Event::Wear(pairs(&pl[1..])?),
            0x03 if pl.len() >= 5 && pl[1] == 1 && pl[2] == 1 => Event::AncRaw(u16::from_le_bytes([pl[3], pl[4]]) as u32),
            0x05 if pl.len() >= 2 => Event::GameMode(pl[1] != 0),
            // `04 <dev> <s> <dev> <s>`, dev 01 left / 02 right
            0x04 => {
                let r = pairs_n(&pl[1..]);
                let of = |d| r.iter().find(|x| x.0 == d).map_or(0xFF, |x| x.1);
                Event::FitResult(of(1), of(2))
            }
            0xF5 if pl.len() >= 2 => Event::HeadMotion(pl[1]),
            0x0B if pl.len() >= 2 => Event::PncResult(pl[1]),
            0x06 => Event::Devices(parse_devices(&pl[1..])?),
            0x08 if pl.len() >= 3 => Event::Hearing(HearingEv::Status(pl[1], pl[2])),
            0x0E => { let (u, d) = parse_hearing_scan(pl, 1)?; Event::Hearing(HearingEv::EarScan(u, d)) }
            _ => return None,
        },
        _ => return None,
    })
}

/// The packet log's Human line for a decoded packet (the phone's `LogDecoder`, shortened).
pub fn describe(e: &Event) -> String {
    let side = |i: u8| ["?", "L", "R", "Case"][(i as usize).min(3)];
    match e {
        Event::Firmware(f) => format!("Firmware {f}"),
        Event::EqCurrent(id) => format!("EQ preset {id}"),
        Event::EqCustom(v) => format!("Custom EQ: {}", v.iter().map(|p| format!("{} \"{}\"", p.id, p.name)).collect::<Vec<_>>().join(", ")),
        Event::BassLevel(l) => format!("Bass boost level {l}"),
        Event::Caps(_) => "Supported commands".into(),
        Event::ProductId(id) => format!("Product id {id}"),
        Event::Battery(v) => format!("Battery {}", v.iter().map(|(i, l, c)| format!("{}={l}%{}", side(*i), if *c { "+" } else { "" })).collect::<Vec<_>>().join(" ")),
        Event::Wear(v) => format!("Wear {}", v.iter().map(|(i, st)| format!("{}={}", side(*i), match st { 3 | 7 => "EAR", 4 => "CASE", 1 | 5 => "OUT", _ => "?" })).collect::<Vec<_>>().join(" ")),
        Event::AncRaw(raw) => format!("ANC report 0x{raw:X}"),
        Event::GameMode(on) => format!("Low latency {}", if *on { "on" } else { "off" }),
        Event::AlertVolume(l) => format!("Alert volume {l}"),
        Event::GameSound(t, all) => format!("Game sound type {t} (offered {all:?})"),
        Event::HeadMotion(t) => format!("Head gesture mapping {t}"),
        Event::FitResult(l, r) => format!("Fit test L={l} R={r}"),
        Event::PncStored(e) => format!("Personalized ANC stored result: {e}"),
        Event::PncAck(st) => format!("Personalized ANC ack {st}"),
        Event::PncResult(r) => format!("Personalized ANC result {r}"),
        Event::TapLevel(l, d) => format!("Tap sensitivity {l} (default {d})"),
        Event::Devices(v) => format!("Devices: {}", v.iter().map(|d| format!("{} {}", d.name, if d.connected { "on" } else { "off" })).collect::<Vec<_>>().join(", ")),
        Event::Hearing(h) => match h {
            HearingEv::Status(k, st) => format!("Hearing {} status {st}", if *k == 4 { "scan" } else { "test" }),
            HearingEv::EarScan(u, d) => format!("Ear scan {u:08X}, {} bytes", d.len()),
            HearingEv::Filter(u, t, c) => format!("Hearing filter {u:08X} type {t}{}", c.as_ref().map_or(String::new(), |c| format!(", {} floats", c.1.len() * 2))),
            HearingEv::ScanCurves(u, c) => format!("Ear scan filter {u:08X} {} Hz, {} floats", c.0, c.1.len() * 2),
            HearingEv::Active(u, n, v) => format!("Hearing profile {u:08X} \"{n}\" {v:?}"),
            HearingEv::ActiveScan(u, d) => format!("Hearing profile ear scan {u:08X}, {} bytes", d.len()),
        },
        Event::Preferred(m) => format!("Preferred device {}", m.map_or("automatic".into(), |m| hex(&m))),
        Event::Features(f) => format!("Status {}", f.iter().map(|(id, v)| format!("{id:02X}={v}")).collect::<Vec<_>>().join(" ")),
    }
}

/// The packet log's name for a command we send.
pub fn cmd_name(cmd: u16) -> Option<&'static str> {
    Some(match cmd {
        CMD_HANDSHAKE => "Handshake",
        CMD_QUERY_PRODUCT_ID => "Query product id",
        CMD_QUERY_BATTERY => "Query battery",
        CMD_QUERY_WEARING => "Query wearing",
        CMD_QUERY_ANC => "Query ANC",
        CMD_QUERY_STATUS => "Query status",
        CMD_QUERY_BROADCAST => "Query supported commands",
        CMD_REGISTER_NOTIFY => "Register notifications",
        CMD_SET_FEATURE => "Set feature",
        CMD_SET_ANC => "Set ANC",
        CMD_FIND_BUDS => "Find earbuds",
        CMD_SET_ALERT_VOLUME => "Set alert volume",
        CMD_GAME_SOUND => "Set game sound type",
        CMD_QUERY_GAME_SOUND => "Query game sound type",
        CMD_SET_HEAD_MOTION => "Set head gesture mapping",
        CMD_QUERY_HEAD_MOTION => "Query head gesture mapping",
        CMD_FIT_TEST => "Fit test",
        CMD_PERSONAL_NOISE => "Personalized ANC",
        CMD_QUERY_PERSONAL_NOISE => "Query personalized ANC",
        CMD_QUERY_ALERT_VOLUME => "Query alert volume",
        CMD_SET_TAP_LEVEL => "Set tap sensitivity",
        CMD_QUERY_TAP_LEVEL => "Query tap sensitivity",
        CMD_QUERY_FIRMWARE => "Query firmware",
        CMD_QUERY_EQ => "Query EQ",
        CMD_QUERY_EQ_ALL => "Query custom EQ",
        CMD_QUERY_BASSWAVE => "Query bass boost",
        CMD_SET_EQ => "Set EQ",
        CMD_SAVE_CUSTOM_EQ => "Save custom EQ",
        CMD_SET_BASSWAVE => "Set bass boost",
        CMD_QUERY_DEVICES => "Query devices",
        CMD_GOLDEN_DETECT => "Hearing test",
        CMD_HEARING_RECORD => "Hearing profile",
        CMD_HEARING_RESTORE => "Hearing profile id",
        CMD_HEARING_SCAN_DATA => "Ear scan data",
        CMD_HEARING_FILTER => "Query hearing filter",
        CMD_HEARING_SCAN_FILTER => "Query ear scan filter",
        CMD_HEARING_ACTIVE => "Query hearing profile",
        CMD_HEARING_ACTIVE_SCAN => "Query hearing profile ear scan",
        CMD_DUAL_FOLLOWUP => "Dual follow-up",
        CMD_MULTI_CONNECT => "Device manager",
        CMD_QUERY_PREFERRED => "Query preferred device",
        _ => return None,
    })
}

pub fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02X}")).collect::<Vec<_>>().join(" ") }

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

/// Splits the RFCOMM byte stream into packets. Bytes before an `AA` are dropped into `discarded` (for the log).
#[derive(Default)]
pub struct Framer { buf: Vec<u8>, pub discarded: Vec<u8> }

impl Framer {
    pub fn push(&mut self, data: &[u8]) -> Vec<Vec<u8>> {
        self.buf.extend_from_slice(data);
        let mut out = Vec::new();
        loop {
            match self.buf.iter().position(|&b| b == 0xAA) {
                Some(i) => { self.discarded.extend(self.buf.drain(..i)); }
                None => { self.discarded.append(&mut self.buf); break; }
            }
            let Some((total, n)) = leb128(&self.buf[1..]) else { break };
            // ponytail: 4 KiB sanity cap; a bogus length resyncs on the next AA.
            if total < 7 || total > 4096 { self.discarded.push(self.buf.remove(0)); continue; }
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
        let b = eq_builtins(find_model(Some("065414"), Some("OnePlus Buds 4")), Some("138.138.105"));
        assert_eq!(b, [(0, "eq_balanced"), (1, "eq_clear_vocals"), (2, "eq_bass")]);
    }

    #[test]
    fn buds4_from_capture() {
        // Replies the Buds 4 sent to the spike (2026-09-30).
        let caps = Caps::parse(&[0x00, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07]).unwrap();
        assert_eq!(caps.game_mode_id(), FEATURE_GAME_MODE);
        assert_eq!(product_id(&[0x00, 0x14, 0x54, 0x06]).as_deref(), Some("065414"));
        let anc = AncModes::of(find_model(Some("065414"), Some("OnePlus Buds 4")));
        // The id wins over a device name the user changed, even to another model's.
        assert_eq!(find_model(Some("065414"), Some("OnePlus Nord Buds 2R")).unwrap()["name"], "OnePlus Buds 4");
        assert_eq!(find_model(None, Some("OnePlus Buds 4")).unwrap()["id"], "065414");
        // Adaptive's SET bit is 11, `01 01 00 08` (CLAUDE.md); Off is reported as bit 3, Off's child.
        assert_eq!(anc.bit(OFF), Some(0));
        assert_eq!(anc_payload(anc.bit(ADAPTIVE).unwrap()), [1, 1, 0x00, 0x08]);
        assert_eq!(anc.mode_for_raw(1 << 3, None).as_deref(), Some(OFF));
        assert_eq!(anc.levels().len(), 4);
        let push = build_packet(EVT_PUSH, 7, &[2, 3, 1, 7, 2, 7, 3, 4]);
        assert!(matches!(decode(&push), Some(Event::Wear(w)) if w == [(1, 7), (2, 7), (3, 4)]));
    }

    #[test]
    fn earbud_settings_replies() {
        assert!(matches!(decode(&build_packet(0x8130, 1, &[0, 6])), Some(Event::AlertVolume(6))));
        assert!(matches!(decode(&build_packet(0x8427, 1, &[0, 3])), Some(Event::AlertVolume(3))));
        assert!(matches!(decode(&build_packet(0x8133, 1, &[0, 2, 3])), Some(Event::TapLevel(2, 3))));
        // A failed read (status not 0) is no value.
        assert!(decode(&build_packet(0x8130, 1, &[1, 6])).is_none());
        assert!(matches!(decode(&build_packet(0x812B, 1, &[0, 3, 2, 0, 3])), Some(Event::GameSound(3, t)) if t == [0, 3]));
        // Buds 4 capture: both good.
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[4, 1, 1, 2, 1])), Some(Event::FitResult(1, 1))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[4, 2, 6])), Some(Event::FitResult(0xFF, 6))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[0xF5, 1])), Some(Event::HeadMotion(1))));
        assert!(matches!(decode(&build_packet(EVT_PUSH, 1, &[0x0B, 2])), Some(Event::PncResult(2))));
        assert!(matches!(decode(&build_packet(0x811A, 1, &[0, 1])), Some(Event::PncStored(true))));
        assert!(matches!(decode(&build_packet(0x8412, 1, &[15])), Some(Event::PncAck(15))));
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
        assert_eq!(battery(payload_of(&got[0])), [(1, 100, false), (2, 100, false), (3, 80, true)]);
    }
}
