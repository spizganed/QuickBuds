//! Types, constants and model data both protocols share: command and feature ids, the model list, `Event` (what
//! either decoder produces), `Caps`, `AncModes`, equalizer and hearing helpers, and the packet log's text.
//! No decoding of typed messages here: that is `protocol.rs` (legacy) and `buds_core` (new). The packet bytes are built here.
// The ids stay listed in full, used or not.
#![allow(dead_code)]

use serde_json::Value;

pub const CMD_HANDSHAKE: u16 = 0x0100;

pub const CMD_QUERY_PRODUCT_ID: u16 = 0x0103;

pub const CMD_QUERY_BATTERY: u16 = 0x0106;

pub const CMD_QUERY_WEARING: u16 = 0x0109;

pub const CMD_QUERY_ANC: u16 = 0x010C;

pub const CMD_QUERY_STATUS: u16 = 0x010D;

/// Several queries in one frame (bitmap bit 56), answered by one or more `0x812F` (`OpoProtocol.CMD_BATCH`).
pub const CMD_BATCH: u16 = 0x012F;

pub const CMD_BATCH_REPLY: u16 = 0x812F;

pub const CMD_QUERY_BROADCAST: u16 = 0x0200;

/// Subscribe one event (`<id>`), for buds without `0x0205` in their bitmap `[VENDOR]`.
pub const CMD_REGISTER_ONE: u16 = 0x0201;

pub const CMD_REGISTER_NOTIFY: u16 = 0x0205;

pub const CMD_SET_FEATURE: u16 = 0x0403;

pub const CMD_SET_ANC: u16 = 0x0404;

/// `01` start / `00` stop, both buds (PROTOCOL.md section 18 Find my earbuds).
pub const CMD_FIND_BUDS: u16 = 0x0400;

/// `<level>` 1..10, ack `8427 00 <level>`; read `0x0130` -> `00 <level>` (PROTOCOL.md section 18).
pub const CMD_SET_ALERT_VOLUME: u16 = 0x0427;

pub const CMD_QUERY_ALERT_VOLUME: u16 = 0x0130;

/// `<level>` 1..5; read `0x0133` -> `00 <level> <default>` (PROTOCOL.md section 18, unverified).
pub const CMD_SET_TAP_LEVEL: u16 = 0x042D;

pub const CMD_QUERY_TAP_LEVEL: u16 = 0x0133;

pub const CMD_FIT_TEST: u16 = 0x0405;

pub const CMD_GOLDEN_DETECT: u16 = 0x040D;

/// Personalized ANC (PROTOCOL.md section 20, unverified): `<action>` 1 test / 2 use the stored result / 3 cancel, ack `8412 <status>`;
/// `0x011A` -> `00 <exist>`; the test's result is push `0B <r>` (0 applied, 1-5 a reason it failed).
pub const CMD_PERSONAL_NOISE: u16 = 0x0412;

pub const CMD_QUERY_PERSONAL_NOISE: u16 = 0x011A;

pub const FEATURE_PERSONAL_NOISE: u8 = 0x0C;

/// Game sound type `<type> 01`; read `0x012B` -> `00 <selected> <count> <types>` (PROTOCOL.md section 18).
pub const CMD_GAME_SOUND: u16 = 0x0423;

pub const CMD_QUERY_GAME_SOUND: u16 = 0x012B;

/// Game equalizer (PROTOCOL.md section 18): switch feature `21`, and `0x0420 01 <state>` tells the buds that a
/// game app is open. The state applies only while the switch is on.
pub const CMD_GAME_EQ_STATE: u16 = 0x0420;

pub const FEATURE_GAME_EQ: u8 = 0x21;

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

/// Time request (PROTOCOL.md section 11): answered `8500 <its seq> 00 <unix seconds u32 LE>`, as HeyMelody does.
pub const REQ_TIME: u16 = 0x0500;

pub const FEATURE_BASSWAVE: u8 = 0x1D;

pub const FEATURE_POWER_SAVING: u8 = 0x17;

pub const FEATURE_HEARING_OPTIMIZE: u8 = 0x38;

pub const EVT_PUSH: u16 = 0x0204;

/// Dual connection (PROTOCOL.md section 19): switch `0x11`; the device list `0x0112` -> `8112 00 <list>`, push `0204 06 <list>`.
/// Each toggle is followed by `0x0413 08 00 <00 after on | 01 after off>` (HeyMelody's, meaning unknown).
pub const FEATURE_DUAL: u8 = 0x11;

pub const CMD_QUERY_DEVICES: u16 = 0x0112;

pub const CMD_DUAL_FOLLOWUP: u16 = 0x0413;

/// Device manager (PROTOCOL.md section 19, unverified): `01|02 <MAC>` connect / disconnect, `04 00` preferred device automatic,
/// `04 01 <MAC>`; read `0x0132 02` -> `00 02 <00 | 01 <MAC>>`. MACs in written order (the list's reversed).
pub const CMD_MULTI_CONNECT: u16 = 0x0429;

pub const CMD_QUERY_PREFERRED: u16 = 0x0132;

/// Game mode's `0x0403` switch: `0x28` on buds with game sound (`0x0423`), `0x06` elsewhere (PROTOCOL.md section 16).
pub const FEATURE_GAME_MODE: u8 = 0x06;

pub const FEATURE_GAME_MODE_MAIN: u8 = 0x28;

/// `0x0205`, count first; never shorter than `03 01 02 03` (PROTOCOL.md section 8).
/// The events to subscribe to: ours, kept to those the buds offer in `0x8200` when they sent one, as
/// HeyMelody subscribes to the offered list only (PROTOCOL.md section 8). Some firmwares never ack a list with others.
pub fn notify_ids(caps: &Caps, offered: Option<&[u8]>) -> Vec<u8> {
    let mut ids = vec![1, 2, 3];
    if caps.supports(CMD_FIT_TEST) { ids.push(0x04); }
    if caps.supports(CMD_GOLDEN_DETECT) { ids.push(0x08); }
    if caps.supports(CMD_PERSONAL_NOISE) { ids.push(0x0B); }
    if let Some(o) = offered { ids.retain(|i| o.contains(i)); }
    ids
}

/// realme Link's wind noise switch; asked only for models with the `windMode` flag.
pub const FEATURE_WIND_NOISE: u8 = 0x1A;

/// High-quality audio (Hi-Res) and 3D audio: exclusive `0x0403` switches, PROTOCOL.md section 18 Spatial sound and Hi-Res.
pub const FEATURE_HIRES: u8 = 0x18;

pub const FEATURE_SPATIAL: u8 = 0x1B;

/// Spatial type `<type>` (0 off, 1 fixed, 2 head tracking) on buds with `0x012A` instead of feature `1B`:
/// read `0x012A` -> `00 <type>`, pushed as `0x0510 <type>`. `[VENDOR]`
pub const CMD_SET_SPATIAL_TYPE: u16 = 0x0422;

pub const CMD_QUERY_SPATIAL_TYPE: u16 = 0x012A;

pub const EVT_SPATIAL_TYPE: u16 = 0x0510;

/// Who renders 3D audio: `00` the buds, `01` the phone. realme Link sends `00` on connect. `[VENDOR]`
pub const CMD_SPATIAL_SOURCE: u16 = 0x041E;

/// Codec picker (`hiRes` models): current codec `0x0114` -> `00 <codec>`, offered `0x0123` ->
/// `00 <u16 LE mask>`, write `0x041A` ([codec_payload]); the buds restart after a write. `[VENDOR]`
pub const CMD_QUERY_CODEC: u16 = 0x0114;

pub const CMD_QUERY_CODEC_LIST: u16 = 0x0123;

pub const CMD_SET_CODEC: u16 = 0x041A;

/// The codecs in a `0x8123` mask (bit k = codec k+1); LHDC (7) is dropped when LHDC V5 (8) is offered too.
pub fn codecs_of(mask: u16) -> Vec<u8> {
    let all: Vec<u8> = (1..=8).filter(|c| mask & (1 << (c - 1)) != 0).collect();
    if all.contains(&8) { all.into_iter().filter(|&c| c != 7).collect() } else { all }
}

pub fn codec_name(codec: u8) -> String {
    ["SBC", "AAC", "LDAC", "aptX", "aptX HD", "aptX Adaptive", "LHDC", "LHDC"].get(codec as usize - 1)
        .map_or(format!("#{codec}"), |n| n.to_string())
}

/// The high-quality codecs of `models.json`, best first, with the names the UI shows
/// (`ModelCatalog.HIRES_CODECS`).
pub const HIRES_NAMES: [(&str, &str); 6] = [
    ("lhdc5", "LHDC 5.0"), ("lhdc", "LHDC"), ("ldac", "LDAC"),
    ("aptx-adaptive", "aptX Adaptive"), ("aptx-hd", "aptX HD"), ("aptx", "aptX"),
];

/// The model's own best high-quality codec (`ModelCatalog.bestHiResCodec`). None when the model has no
/// `codecs` key or lists none of them.
pub fn best_hires_codec(model: Option<&Value>) -> Option<&'static str> {
    let tokens = model?.get("codecs")?.as_array()?;
    HIRES_NAMES.iter().find(|(token, _)| tokens.iter().any(|v| v.as_str() == Some(token))).map(|(_, name)| *name)
}

/// `0x010D`: count, then the feature ids (`OpoProtocol.queryStatus`): the model's `statusQuery` (HeyMelody's
/// per-model list, PROTOCOL.md section 16), else the full list. The `windMode` flag adds [FEATURE_WIND_NOISE].
pub fn status_query(model: Option<&serde_json::Value>) -> Vec<u8> {
    let mut ids: Vec<u8> = match model.and_then(|m| m["statusQuery"].as_str()) {
        Some(s) => s.split(' ').filter_map(|h| u8::from_str_radix(h, 16).ok()).collect(),
        None => vec![
            0x05, 0x04, 0x0B, 0x11, 0x13, 0x18, 0x06, 0x1B, 0x1C, 0x27, 0x28, 0x1D,
            0x09, 0x17, 0x30, 0x31, 0x3A, 0x32, 0x35, 0x37, 0x38, 0x3B, 0x0C,
        ],
    };
    if model.is_some_and(|m| m["windMode"] == 1) { ids.push(FEATURE_WIND_NOISE); }
    let mut q = vec![ids.len() as u8];
    q.extend(ids);
    q
}

/// Commands behind each bit of the `0x8100` handshake bitmap (same table as `Capabilities.kt`).
const BIT_COMMANDS: &[&[u16]] = &[
    &[0x0105], &[0x0106], &[0x0107], &[0x0108, 0x0401, 0x0416], &[0x0109], &[0x0400], &[0x0402], &[0x0403],
    &[0x010C, 0x0404], &[0x0405], &[0x0406, 0x010F], &[0x0407], &[], &[0x0408], &[0x0409], &[], &[],
    &[0x0114], &[], &[0x040E, 0x040D, 0x0115, 0x0116], &[0x040F], &[0x0410, 0x0119], &[0x0205], &[0x0F00],
    &[], &[0x0118, 0x0411], &[0x011A, 0x0412], &[0x011C, 0x0413], &[], &[0x0112, 0x040B],
    &[0x011E, 0x011F, 0x0415], &[0x040D], &[], &[0x0121, 0x0417], &[0x0122, 0x0418], &[0x0419],
    &[0x011D, 0x0414], &[0x0123, 0x041A], &[0x0124, 0x041B], &[0x0125, 0x041C, 0x0127, 0x041D, 0x041F],
    &[0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129], &[0xEF01], &[0xEF02], &[0xEF03, 0x041E], &[0x0420],
    &[0x001C], &[], &[0x0422, 0x012A], &[0xEF04], &[0x0423, 0x012B], &[], &[0x0424], &[0xEF06], &[], &[],
    &[0x0425, 0x012E, 0x0426], &[0x012F], &[0x0427, 0x0130], &[0x0131, 0x0428], &[0x0429, 0x0132], &[0x0014],
    &[0x042D, 0x0133], &[0x042E], &[0xEF07], &[0xEF08, 0x0136, 0x0433], &[0xEF09], &[0x0431, 0x0134], &[0x0432, 0x0135, 0x0435], &[0x0117],
];

const ALWAYS: &[u16] = &[0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04];

/// What the buds said they accept. Before the handshake reply, everything is assumed (as the phone app does).
#[derive(Default, Clone)]
pub struct Caps(pub(crate) Option<Vec<u16>>);

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
    /// The buds answered the bitmap query.
    pub fn read(&self) -> bool { self.0.is_some() }
    pub fn game_mode_id(&self) -> u8 {
        if self.supports(CMD_GAME_SOUND) { FEATURE_GAME_MODE_MAIN } else { FEATURE_GAME_MODE }
    }
}

// --- Models and ANC modes (`ModelCatalog.kt`, `AncModes.kt`, PROTOCOL.md sections 6 and 14) ---

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
    static LIST: OnceLock<Vec<Value>> = OnceLock::new();
    LIST.get_or_init(|| {
        let v: Value = serde_json::from_str(MODELS_JSON).expect("models json");
        v["whiteList"].as_array().cloned().unwrap_or_default()
    })
}

/// `ModelCatalog.find`: the product id (unique in the list), else the device name (a renamed device has none).
pub fn find_model(id: Option<&str>, name: Option<&str>) -> Option<&'static Value> {
    let m = models();
    let by_name = |x: &&Value| name.is_some() && x["name"].as_str() == name;
    let by_id = |x: &&Value| id.is_some_and(|id| x["id"].as_str().is_some_and(|i| i.eq_ignore_ascii_case(id)));
    // HeyMelody's order (PROTOCOL.md section 6): name and id, then name, then id.
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
    Some((mode_name(v["mode"].as_i64()?)?, v["slot"].as_u64()? as u8))
}

impl AncModes {
    /// A model without `ancModes` (or none detected) gets no modes: a wrong bit sets another mode silently.
    pub fn of(model: Option<&Value>) -> AncModes {
        model.and_then(|m| m["ancModes"].as_array()).map(|a| Self::parse(a)).unwrap_or_default()
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
            for child in top["variants"].as_array().into_iter().flatten() {
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
    /// The level (not Smart) whose SET bit this is.
    pub fn level_for_bit(&self, bit: u8) -> Option<&'static str> { LEVELS[..3].iter().copied().find(|l| self.bit(l) == Some(bit)) }

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

// --- Equalizer (PROTOCOL.md section 17 Equalizer, `EqCodec`) ---

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

/// HeyMelody's bands where the model gives none (`customEqBands`).
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

/// A model flag from `models.json`; nothing detected counts as Buds 4, which has them all (as on the phone).
fn flag(model: Option<&Value>, key: &str) -> bool { model.map_or(true, |m| m[key].as_i64() == Some(1)) }

pub fn eq_has_custom(model: Option<&Value>, caps: &Caps) -> bool {
    flag(model, "customEq") && caps.supports(CMD_SAVE_CUSTOM_EQ)
}

/// Any equalizer at all (`EqActivity.hasEq`): custom presets, or built-in ones on a model with `eqKind` 1..4.
pub fn eq_has(model: Option<&Value>, caps: &Caps) -> bool {
    eq_has_custom(model, caps)
        || (1..=4).contains(&model.map_or(1, |m| m["eqKind"].as_i64().unwrap_or(0))) && caps.supports(CMD_SET_EQ)
}

/// The buds listed switch `id` in their last `0x810D` reply; nothing read yet = yes (`Capabilities.hasFeature`).
pub fn has_feature(features: &[(u8, u8)], id: u8) -> bool { features.is_empty() || features.iter().any(|f| f.0 == id) }

pub fn eq_has_bass(model: Option<&Value>, caps: &Caps) -> bool {
    flag(model, "bassEngine") && caps.supports(CMD_SET_BASSWAVE)
}

/// `bassBands` 3: realme dynamic sound, three sliders with a band byte (PROTOCOL.md section 17).
pub fn eq_bass_bands(model: Option<&Value>) -> bool { model.map_or(false, |m| m["bassBands"].as_i64() == Some(3)) }

/// `customEqMax`, else 3.
pub fn eq_max_custom(model: Option<&Value>) -> usize {
    model.and_then(|m| m["customEqLimit"].as_u64()).filter(|&n| n > 0).unwrap_or(3) as usize
}

pub fn eq_model_freqs(model: Option<&Value>) -> Vec<u16> {
    model.and_then(|m| m["customEqBands"].as_array())
        .map(|a| a.iter().filter_map(|f| f.as_u64().map(|f| f as u16)).collect())
        .unwrap_or_else(|| EQ_DEFAULT_FREQS.to_vec())
}

/// The model's built-in presets as (`0x0406` id, `strings.xml` key), in HeyMelody's order (`EqActivity.builtInPresets`).
pub fn eq_builtins(model: Option<&Value>, firmware: Option<&str>) -> Vec<(u8, &'static str)> {
    let buds4 = serde_json::json!([{"mode":11,"slot":0},{"mode":14,"slot":1},{"mode":12,"slot":2}]);
    let modes: Vec<Value> = match model {
        Some(m) => {
            // The lower non-zero of the two buds' versions; unknown = 0.
            let nums: Vec<i64> = firmware.unwrap_or("").split('.').map(|p| p.parse().unwrap_or(0)).collect();
            let version = if nums.len() == 3 { nums[..2].iter().copied().filter(|&n| n != 0).min().unwrap_or(0) } else { nums.first().copied().unwrap_or(0) };
            let mut v: Vec<Value> = ["eqPresetsCompat", "eqPresetsByFirmware", "eqPresets"].iter()
                .flat_map(|k| m[*k].as_array().cloned().unwrap_or_default())
                .filter(|e| e["minFirmware"].as_i64().unwrap_or(0) <= version)
                .collect();
            v.sort_by_key(|e| e["order"].as_i64().unwrap_or(0));
            v
        }
        None => buds4.as_array().unwrap().clone(),
    };
    let name = model.and_then(|m| m["name"].as_str()).unwrap_or("");
    let alt = name == "OPPO Enco R" || name == "OPPO Enco Air2" || model.is_some_and(|m| m["eqKind"].as_i64() == Some(2));
    modes.iter().filter_map(|m| {
        let key = match m["mode"].as_i64()? {
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
            // realme Link's four presets; HeyMelody has none, so the types are ours (PROTOCOL.md section 17).
            101 => "eq_pure_raw",
            102 => "eq_deep_bass",
            103 => "eq_pure_vocals",
            104 => "eq_pure_bass",
            _ => return None, // unnamed in HeyMelody too
        };
        Some((m["slot"].as_u64()? as u8, key))
    }).collect()
}

// --- Hearing profile (PROTOCOL.md section 20 Golden Sound) ---

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

pub type Curves = (u32, Vec<f32>, Vec<f32>);

#[derive(Clone, Debug)]
pub enum HearingEv {
    /// Push `08`: kind 2 test / 4 scan, status (1/3 audio playing, 5 a bud out, 7/10/255 failed).
    Status(u8, u8),
    /// `0x840D` / `0x840E`: the buds refused a start or a tone (non-zero status).
    Refused(u8),
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

// --- Gestures (PROTOCOL.md sections 14 and 15) ---

/// The gesture table: read `0x0108` -> `<status> <count>` then `<dev> <btn> <act> <fn>` entries; write `0x0401`
/// `<count> <entries>`, always the whole table (the entries we do not change come from the last read).
pub const CMD_QUERY_KEY_FUNCTION: u16 = 0x0108;

pub const CMD_SET_KEY_FUNCTION: u16 = 0x0401;

/// The on-call group; its writes are one entry with `dev 04` (both buds).
pub const BUTTON_ON_CALL: u8 = 0x06;

/// realme both-buds hold (issue #8): `04 01 04 <fn>`, read back as its own `dev 04` entry.
pub const BUTTON_PRIMARY: u8 = 0x01;

/// The hold's noise cycle: one shared, or per bud on models with `holdMask`.
pub const HOLD_TYPE_SHARED: u8 = 1;

pub const HOLD_TYPE_LEFT: u8 = 3;

pub const HOLD_TYPE_RIGHT: u8 = 4;

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

// --- Incoming packets ---

pub enum Event {
    Firmware(String),
    /// `0x8200`: the events the buds offer.
    Offered(Vec<u8>),
    EqCurrent(u8),
    EqCustom(Vec<Preset>),
    BassLevel(i8),
    Caps(Caps),
    ProductId(String),
    /// (index 1 left / 2 right / 3 case, level, charging)
    Battery(Vec<(u8, u8, bool)>),
    /// (component 1 left / 2 right / 3 case, status), PROTOCOL.md section 13
    Wear(Vec<(u8, u8)>),
    AncRaw(u32),
    /// The level Smart chose, as a SET bit mask: `0x810C` `00 04 01 <mask>`, push `03 04 01 <mask>`.
    SmartLevel(u32),
    /// Push `05 ...`, the whole payload: read it with [game_on].
    GameMode(Vec<u8>),
    /// `0x810D`: feature id -> value
    Features(Vec<(u8, u8)>),
    AlertVolume(u8),
    /// (selected, offered types)
    GameSound(u8, Vec<u8>),
    HeadMotion(u8),
    SpatialType(u8),
    Codec(u8),
    Codecs(Vec<u8>),
    /// Fit test result, left and right status (PROTOCOL.md section 20: 1 good, 0 average, 6 poor, else an error; 0xFF missing).
    FitResult(u8, u8),
    PncStored(bool),
    PncAck(u8),
    PncResult(u8),
    /// (level, the buds' default)
    TapLevel(u8, u8),
    /// The level alone, from a push; the default stays from the last read.
    TapPush(u8),
    Devices(Vec<PairedDevice>),
    Hearing(HearingEv),
    /// `0x8108`: the gesture table, `[dev, btn, act, fn]` each.
    KeyFunctions(Vec<[u8; 4]>),
    /// `0x810C 00 02 <type> <mask LE>`: the hold's noise cycle.
    HoldMask(u8, u32),
    /// None = automatic.
    Preferred(Option<[u8; 6]>),
}

/// These models put the game state at payload[3], not payload[1] (PROTOCOL.md section 10 push `05`) `[VENDOR]`, unverified.
const GAME_STATE_AT_3: [&str; 4] = ["062410", "062810", "063010", "063410"];

/// Push `05 <state>`, or `05 .. .. <state>` on [GAME_STATE_AT_3]. Non-zero = on.
pub fn game_on(pl: &[u8], product: Option<&str>) -> Option<bool> {
    let at = if product.is_some_and(|p| GAME_STATE_AT_3.iter().any(|x| x.eq_ignore_ascii_case(p))) { 3 } else { 1 };
    pl.get(at).map(|&b| b != 0)
}

/// The packet log's Human line for a decoded packet (the phone's `LogDecoder`, shortened).
pub fn describe(e: &Event) -> String {
    let side = |i: u8| ["?", "L", "R", "Case"][(i as usize).min(3)];
    match e {
        Event::Firmware(f) => format!("Firmware {f}"),
        Event::Offered(o) => format!("Notifications offered: {}", hex(o)),
        Event::EqCurrent(id) => format!("EQ preset {id}"),
        Event::EqCustom(v) => format!("Custom EQ: {}", v.iter().map(|p| format!("{} \"{}\"", p.id, p.name)).collect::<Vec<_>>().join(", ")),
        Event::BassLevel(l) => format!("Bass boost level {l}"),
        Event::Caps(_) => "Supported commands".into(),
        Event::ProductId(id) => format!("Product id {id}"),
        Event::Battery(v) => format!("Battery {}", v.iter().map(|(i, l, c)| format!("{}={l}%{}", side(*i), if *c { "+" } else { "" })).collect::<Vec<_>>().join(" ")),
        Event::Wear(v) => format!("Wear {}", v.iter().map(|(i, st)| format!("{}={}", side(*i), match st { 3 | 7 => "EAR", 4 => "CASE", 1 | 5 => "OUT", _ => "?" })).collect::<Vec<_>>().join(" ")),
        Event::AncRaw(raw) => format!("ANC report 0x{raw:X}"),
        Event::SmartLevel(m) => format!("Smart level mask 0x{m:X}"),
        Event::GameMode(pl) => format!("Low latency push {}", hex(pl)),
        Event::AlertVolume(l) => format!("Alert volume {l}"),
        Event::GameSound(t, all) => format!("Game sound type {t} (offered {all:?})"),
        Event::SpatialType(t) => format!("Spatial type {t}"),
        Event::Codec(c) => format!("Codec {}", codec_name(*c)),
        Event::Codecs(all) => format!("Codecs offered {:?}", all.iter().map(|&c| codec_name(c)).collect::<Vec<_>>()),
        Event::HeadMotion(t) => format!("Head gesture mapping {t}"),
        Event::FitResult(l, r) => format!("Fit test L={l} R={r}"),
        Event::PncStored(e) => format!("Personalized ANC stored result: {e}"),
        Event::PncAck(st) => format!("Personalized ANC ack {st}"),
        Event::PncResult(r) => format!("Personalized ANC result {r}"),
        Event::TapLevel(l, d) => format!("Tap sensitivity {l} (default {d})"),
        Event::TapPush(l) => format!("Tap sensitivity {l} (push)"),
        Event::Devices(v) => format!("Devices: {}", v.iter().map(|d| format!("{} {}", d.name, if d.connected { "on" } else { "off" })).collect::<Vec<_>>().join(", ")),
        Event::Hearing(h) => match h {
            HearingEv::Status(k, st) => format!("Hearing {} status {st}", if *k == 4 { "scan" } else { "test" }),
            HearingEv::Refused(st) => format!("Hearing test: FAILED (status {st})"),
            HearingEv::EarScan(u, d) => format!("Ear scan {u:08X}, {} bytes", d.len()),
            HearingEv::Filter(u, t, c) => format!("Hearing filter {u:08X} type {t}{}", c.as_ref().map_or(String::new(), |c| format!(", {} floats", c.1.len() * 2))),
            HearingEv::ScanCurves(u, c) => format!("Ear scan filter {u:08X} {} Hz, {} floats", c.0, c.1.len() * 2),
            HearingEv::Active(u, n, v) => format!("Hearing profile {u:08X} \"{n}\" {v:?}"),
            HearingEv::ActiveScan(u, d) => format!("Hearing profile ear scan {u:08X}, {} bytes", d.len()),
        },
        Event::KeyFunctions(t) => format!("Gestures {}", t.iter().map(|e| format!("{:02X}/{:02X}/{:02X}={:02X}", e[0], e[1], e[2], e[3])).collect::<Vec<_>>().join(" ")),
        Event::HoldMask(k, m) => format!("Hold cycle ({k}) 0x{m:04X}"),
        Event::Preferred(m) => format!("Preferred device {}", m.map_or("automatic".into(), |m| hex(&m))),
        Event::Features(f) => format!("Status {}", f.iter().map(|(id, v)| format!("{id:02X}={v}")).collect::<Vec<_>>().join(" ")),
    }
}

/// The packet log's line for a frame we send: its name, with the value for the common writes.
pub fn describe_tx(cmd: u16, p: &[u8]) -> Option<String> {
    let name = cmd_name(cmd)?;
    Some(match (cmd, p) {
        (CMD_SET_FEATURE, [id, v, ..]) => format!("{name} {id:02X} {}", if *v == 1 { "on" } else { "off" }),
        // `01 01 <bit>`: which mode bit; `02 <type> <mask>`: the hold's cycle.
        (CMD_SET_ANC, [1, 1, bits @ ..]) => format!("{name} bit {}", bits.iter().rev().fold(0u32, |v, &b| v << 8 | b as u32).trailing_zeros()),
        (CMD_SET_ANC, [2, t, mask @ ..]) => format!("Set hold cycle ({t}) 0x{:04X}", mask.iter().rev().fold(0u32, |v, &b| v << 8 | b as u32)),
        (CMD_SET_EQ | CMD_SET_ALERT_VOLUME | CMD_SET_TAP_LEVEL, [v, ..]) => format!("{name} {v}"),
        (CMD_BATCH, [n, ..]) => format!("{name}, {n} queries"),
        (CMD_FIND_BUDS | CMD_FIT_TEST, [v, ..]) => format!("{name} {}", if *v == 1 { "start" } else { "stop" }),
        _ => name.into(),
    })
}

/// The packet log's line for a packet [decode] does not turn into state: acks, the notification lists,
/// button presses, the time request.
pub fn describe_other(p: &[u8]) -> Option<String> {
    let cmd = cmd_of(p);
    let pl = payload_of(p);
    Some(match cmd {
        0x8400..=0x84FF => {
            let name = cmd_name(cmd & 0x7FFF).map_or(format!("0x{:04X}", cmd & 0x7FFF), String::from);
            match pl.first() { Some(0) => format!("{name}: ok"), Some(s) => format!("{name}: FAILED (status {s})"), None => format!("{name}: ack") }
        }
        0x8205 => "Notifications registered".into(),
        REQ_TIME => "Time request".into(),
        0x8500 => "Time reply".into(),
        0x0501 => "Bud state".into(),
        // Replies named by what the vendor app calls them; the payload is not decoded, so the hex follows.
        0x8101 => "Protocol version".into(), 0x8102 => "Vendor ID".into(), 0x8107 => "Frame budget".into(),
        0x810B => "Earbuds colour ID".into(), 0x8117 => "Earphone identity".into(), 0x8118 => "Ear restore data".into(),
        0x8119 => "Zen mode file info".into(), 0x811C => "Status 0x011C".into(), 0x811D => "Wear status".into(),
        0x8121 => "Ear tone data".into(), 0x8125 => "Account key".into(), 0x8127 => "Screen-off broadcast delay".into(),
        0x8129 => "Calibrated state".into(), 0x8135 => "GMN config".into(), 0x8136 => "LE/BR switch state".into(),
        // `F1 <side> <button> <action>`
        EVT_PUSH if pl.first() == Some(&0xF1) && pl.len() >= 4 => format!("Button {} {}",
            match pl[1] { 1 => "L", 2 => "R", _ => "?" },
            match pl[3] { 0 => "single tap".into(), 2 => "double tap".into(), 3 => "triple tap".into(), 4 => "hold".into(),
                7 => "slide up".into(), 8 => "slide down".into(), a => format!("action {a:02X}") }),
        _ => return None,
    })
}

/// The packet log's name for a command we send.
pub fn cmd_name(cmd: u16) -> Option<&'static str> {
    Some(match cmd {
        CMD_HANDSHAKE => "Handshake",
        CMD_QUERY_PRODUCT_ID => "Query product id",
        CMD_BATCH => "Batch query",
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
        CMD_SET_SPATIAL_TYPE => "Set spatial type",
        CMD_QUERY_SPATIAL_TYPE => "Query spatial type",
        CMD_SPATIAL_SOURCE => "3D audio on the buds",
        CMD_QUERY_CODEC => "Query codec",
        CMD_QUERY_CODEC_LIST => "Query codec list",
        CMD_SET_CODEC => "Set codec",
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
        CMD_QUERY_KEY_FUNCTION => "Query gestures",
        CMD_SET_KEY_FUNCTION => "Set gestures",
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
pub fn leb128(b: &[u8]) -> Option<(usize, usize)> {
    let mut v = 0usize;
    for (i, &x) in b.iter().enumerate().take(3) {
        v |= ((x & 0x7F) as usize) << (7 * i);
        if x & 0x80 == 0 { return Some((v, i + 1)); }
    }
    None
}

pub fn header_len(p: &[u8]) -> usize { 1 + leb128(&p[1..]).map_or(1, |(_, n)| n) + 7 }

pub fn cmd_of(p: &[u8]) -> u16 {
    let h = header_len(p) - 7;
    u16::from_le_bytes([p[h + 2], p[h + 3]])
}

pub fn payload_of(p: &[u8]) -> &[u8] { &p[header_len(p)..] }

/// A `0x812F` frame as the normal replies it carries (`00 <count>`, then `<cmd LE> <len LE> <reply
/// payload>`, `cmd` being the query's), each a frame with the batch's seq; stops at one that runs past the end.
pub fn unbatch(p: &[u8]) -> Vec<Vec<u8>> {
    let (pl, seq) = (payload_of(p), seq_of(p));
    let mut out = Vec::new();
    let [0, n, rest @ ..] = pl else { return out };
    let mut r = rest;
    for _ in 0..*n {
        let [c0, c1, l0, l1, tail @ ..] = r else { break };
        let len = u16::from_le_bytes([*l0, *l1]) as usize;
        if tail.len() < len { break; }
        out.push(build_packet(u16::from_le_bytes([*c0, *c1]) | 0x8000, seq, &tail[..len]));
        r = &tail[len..];
    }
    out
}

pub fn seq_of(p: &[u8]) -> u8 { p[header_len(p) - 3] }
