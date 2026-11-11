//! Which rows the app shows, and which switch ids it asks in `0x010D`. A port of the phone's `RowRules.kt`:
//! both run `testdata/row_fixtures.json`. The buds' own answers decide:
//!  - the `0x810D` reply lists the feature ids the buds have. An id in the reply means "has the feature".
//!  - the `0x8100` bitmap lists the commands the firmware accepts.
//!  - `models.json` decides only what to ask. A flag never shows a row on its own, except the write-only rows.

use std::collections::{BTreeSet, HashMap};
use std::sync::OnceLock;

use serde_json::Value;

use crate::session::Snapshot;
use crate::types::*;

// Home screen rows.
pub const WIND: &str = "wind";
pub const GAME: &str = "game";
pub const HIRES: &str = "hires";
pub const SPATIAL: &str = "spatial";
pub const GOLDEN: &str = "golden";
pub const EQ: &str = "eq";
pub const DUAL: &str = "dual";
pub const ANC: &str = "anc";
pub const EARBUDS: &str = "earbuds";
// Earbud settings rows.
pub const CONTROLS: &str = "controls";
pub const WEAR: &str = "wear";
pub const FIND: &str = "find";
pub const FIT: &str = "fit";
pub const ALERT_VOLUME: &str = "alertVolume";
pub const FIRMWARE: &str = "firmware";
pub const TAP_LEVEL: &str = "tapLevel";
pub const PERSONAL_ANC: &str = "personalAnc";
pub const GAME_SOUND_TYPE: &str = "gameSoundType";
pub const GAME_EQ: &str = "gameEq";
pub const HEAD_GESTURE_TYPE: &str = "headGestureType";
// Equalizer parts.
pub const EQ_PRESETS: &str = "eqPresets";
pub const EQ_CUSTOM: &str = "eqCustom";
pub const BASS_WAVE: &str = "bassWave";
pub const BASS_BANDS: &str = "bassBands";
// Dual connection parts.
pub const DUAL_PRIORITY: &str = "dualPriority";
pub const DUAL_DEVICES: &str = "dualDevices";

/// The `0x0403` switches that are a row of their own: (feature id, row key). Same keys as `RowRules.kt`.
pub const SWITCH_ROWS: [(u8, &str); 17] = [
    (0x04, WEAR), (0x09, "voiceBoost"), (0x27, "gameSound"), (0x1C, "autoVolume"), (0x30, "adaptiveVolume"),
    (0x31, "adaptiveEar"), (0x3A, "sleepPause"), (0x16, "lostReminder"), (0x32, "speechPerception"),
    (0x38, "hearingOptimize"), (0x35, "holdVolume"), (0x3B, "headGesture"), (0x37, "fastPair"), (0x17, "powerSaving"),
    (0x26, "findPhone"), (0x36, "virtualBass"), (FEATURE_GAME_EQ, GAME_EQ),
];

/// Write-only rows: the buds do not report them, so the model flag alone shows the row. (flag, row key).
pub const WRITE_ONLY_ROWS: [(&str, &str); 2] = [("volumeEnhancer", "volumeEnhancer"), ("autoAnswer", "autoAnswer")];

const GAME_IDS: [u8; 2] = [FEATURE_GAME_MODE, FEATURE_GAME_MODE_MAIN];
const BUDS_SPATIAL: u16 = 0xEF03;

/// The ids a model without `statusQuery` is asked. [`asks_wind`] adds the wind id on top.
pub const DEFAULT_IDS: [u8; 23] = [
    0x05, 0x04, 0x0B, 0x11, 0x13, 0x18, 0x06, 0x1B, 0x1C, 0x27, 0x28, 0x1D,
    0x09, 0x17, 0x30, 0x31, 0x3A, 0x32, 0x35, 0x37, 0x38, 0x3B, 0x0C,
];

/// What the rules read. `reply` None: nothing read yet, so no conditional row. `bitmap` None: not read yet.
/// `product_anc`: the buds reported noise modes where `models.json` has none. `manual`: the person picked the model,
/// so its data passes where the bitmap would decide. `gestures`: None lets the model's `gestures` list decide.
#[derive(Default)]
pub struct Input<'a> {
    pub model: Option<&'a Value>,
    pub reply: Option<&'a HashMap<u8, u8>>,
    pub bitmap: Option<&'a [u16]>,
    pub product_anc: bool,
    pub phone_spatial_ok: bool,
    pub manual: bool,
    pub gestures: Option<bool>,
}

fn flag(model: Option<&Value>, key: &str) -> bool { model.is_some_and(|m| m[key] == 1) }
fn has_array(model: Option<&Value>, key: &str) -> bool { model.and_then(|m| m[key].as_array()).is_some_and(|a| !a.is_empty()) }
fn in_bitmap(bitmap: Option<&[u16]>, cmd: u16) -> bool { bitmap.is_some_and(|b| b.contains(&cmd)) }

pub fn rows(i: &Input) -> BTreeSet<&'static str> {
    let mut out = BTreeSet::from([EARBUDS]);
    let Some(reply) = i.reply else { return out };
    let (m, b) = (i.model, i.bitmap);
    let has = |id: u8| reply.contains_key(&id);
    let caps = |cmd: u16| in_bitmap(b, cmd);
    let accepts = |cmd: u16| caps(cmd) || i.manual;
    let mut add = |on: bool, key: &'static str| if on { out.insert(key); };

    add(has(FEATURE_WIND_NOISE), WIND);
    add(GAME_IDS.iter().any(|&g| has(g)), GAME);
    add(has(FEATURE_HIRES) || codec_picker(m, b), HIRES);
    add((has(FEATURE_SPATIAL) && (!phone_spatial(m, b) || i.phone_spatial_ok)) || spatial_by_type(m, b), SPATIAL);
    add(has(FEATURE_HEARING), GOLDEN);
    add(eq_custom(m, b) || eq_presets(m, b), EQ);
    add(has(FEATURE_DUAL), DUAL);
    add(caps(CMD_SET_ANC) && (i.product_anc || has_array(m, "ancModes")), ANC);

    add(caps(CMD_SET_KEY_FUNCTION) && i.gestures.unwrap_or_else(|| has_array(m, "gestures")), CONTROLS);
    add(caps(CMD_FIND_BUDS) && !flag(m, "noFind"), FIND);
    add(caps(CMD_FIT_TEST), FIT);
    add(caps(CMD_SET_ALERT_VOLUME), ALERT_VOLUME);
    add(caps(CMD_QUERY_FIRMWARE), FIRMWARE);
    add(flag(m, "tapLevel") && accepts(CMD_SET_TAP_LEVEL), TAP_LEVEL);
    // The flag too: HeyMelody gates on it, and Buds 4 list 0x0C without having the feature.
    add(flag(m, "personalAnc") && has(FEATURE_PERSONAL_NOISE) && accepts(CMD_PERSONAL_NOISE), PERSONAL_ANC);
    for (id, key) in SWITCH_ROWS {
        add(has(id) && (id != FEATURE_GAME_EQ || caps(CMD_GAME_EQ_STATE)), key);
    }
    add(has(0x27) && accepts(CMD_GAME_SOUND), GAME_SOUND_TYPE);
    add(has(0x3B) && caps(CMD_SET_HEAD_MOTION), HEAD_GESTURE_TYPE);
    for (f, key) in WRITE_ONLY_ROWS { add(flag(m, f), key); }

    add(eq_presets(m, b), EQ_PRESETS);
    add(eq_custom(m, b), EQ_CUSTOM);
    add(flag(m, "bassEngine") && caps(CMD_SET_BASSWAVE), BASS_WAVE);
    // realme dynamic sound: three sliders with a band byte.
    add(m.is_some_and(|m| m["bassBands"] == 3), BASS_BANDS);
    add(has(FEATURE_DUAL), DUAL_DEVICES);
    let priority = m.and_then(|m| m["dualDevice"].as_array()).is_some_and(|l| l.iter().any(|x| x == "setPriorityDevice"));
    add(has(FEATURE_DUAL) && priority && accepts(CMD_MULTI_CONNECT), DUAL_PRIORITY);
    out
}

/// Every command of the `0x8100` table: the bitmap of buds that have not answered yet.
fn every_command() -> &'static [u16] {
    static ALL: OnceLock<Vec<u16>> = OnceLock::new();
    ALL.get_or_init(|| crate::msg::Bitmap::parse(&[0; 1].iter().copied().chain([0xFF; 9]).collect::<Vec<u8>>())
        .and_then(|b| b.commands().map(<[u16]>::to_vec)).unwrap_or_default())
}

/// The bitmap the rules read: the buds' own, or [`every_command`] before they answer.
pub fn bitmap(s: &Snapshot) -> &[u16] { s.caps.0.as_deref().unwrap_or_else(|| every_command()) }

/// The rows of the session's state. The desktop keeps no reply between runs, so before the buds answer, an unread
/// reply counts as the ask list and an unread bitmap as every command: the pages show what the model can have.
pub fn of(s: &Snapshot) -> BTreeSet<&'static str> {
    let reply: HashMap<u8, u8> = if s.features.is_empty() { ask_ids(s.model).into_iter().map(|id| (id, 0)).collect() }
        else { s.features.iter().copied().collect() };
    rows(&Input {
        model: s.model, reply: Some(&reply), bitmap: Some(bitmap(s)), product_anc: !s.modes.is_empty(), phone_spatial_ok: false,
        manual: s.manual, gestures: Some(!crate::gesture_logic::Model::of(s).rows.is_empty()),
    })
}

/// True when the default list adds the wind id: only a model with the `windMode` flag.
pub fn asks_wind(model: Option<&Value>) -> bool { flag(model, "windMode") }

/// The ids asked in `0x010D`, in order: the model's `statusQuery` plus the ids its flags name and both game ids,
/// sorted. A model with no list gets [`DEFAULT_IDS`] (plus wind), or, with `findPhone`, the default list plus its extras.
pub fn ask_ids(model: Option<&Value>) -> Vec<u8> {
    let list = model.and_then(|m| m["statusQuery"].as_str()).filter(|s| !s.trim().is_empty());
    let base: Vec<u8> = match list {
        Some(s) => s.split(' ').filter_map(|h| u8::from_str_radix(h, 16).ok()).collect(),
        None if flag(model, "findPhone") => default_ids(model),
        None => return default_ids(model),
    };
    let mut ids: BTreeSet<u8> = base.into_iter().collect();
    ids.extend(GAME_IDS);
    for (f, id) in [("windMode", FEATURE_WIND_NOISE), ("voiceBoost", 0x09), ("gameSoundFx", 0x27), ("autoVolume", 0x1C),
        ("holdVolume", 0x35), ("fastPair", 0x37), ("personalAnc", FEATURE_PERSONAL_NOISE), ("findPhone", 0x26), ("virtualBass", 0x36)] {
        if flag(model, f) { ids.insert(id); }
    }
    ids.into_iter().collect()
}

fn default_ids(model: Option<&Value>) -> Vec<u8> {
    let mut ids = DEFAULT_IDS.to_vec();
    if asks_wind(model) { ids.push(FEATURE_WIND_NOISE); }
    ids
}

/// A OnePlus or OPPO model whose bitmap lacks `0xEF03`: the phone makes the 3D effect (PROTOCOL.md section 18).
pub fn phone_spatial(model: Option<&Value>, bitmap: Option<&[u16]>) -> bool {
    let name = model.and_then(|m| m["name"].as_str()).unwrap_or("");
    (name.starts_with("OnePlus") || name.starts_with("OPPO")) && !flag(model, "spatialToggle")
        && bitmap.is_some_and(|b| !b.is_empty() && !b.contains(&BUDS_SPATIAL))
}

/// `0x012A` on the buds, or `spatialModes` with `2`: the 3D row takes a type through `0x0422`.
pub fn spatial_by_type(model: Option<&Value>, bitmap: Option<&[u16]>) -> bool {
    !flag(model, "spatialToggle") && (in_bitmap(bitmap, CMD_QUERY_SPATIAL_TYPE) || spatial_head_tracking(model))
}

/// `spatialModes` holds the number 2.
pub fn spatial_head_tracking(model: Option<&Value>) -> bool {
    model.and_then(|m| m["spatialModes"].as_array()).is_some_and(|a| a.iter().any(|v| v == 2))
}

/// `hiRes` model with the codec list command: the row picks a codec instead of a switch.
pub fn codec_picker(model: Option<&Value>, bitmap: Option<&[u16]>) -> bool {
    flag(model, "hiRes") && in_bitmap(bitmap, CMD_QUERY_CODEC_LIST)
}

pub fn eq_presets(model: Option<&Value>, bitmap: Option<&[u16]>) -> bool {
    (1..=4).contains(&model.map_or(0, |m| m["eqKind"].as_i64().unwrap_or(0))) && in_bitmap(bitmap, CMD_SET_EQ)
}

pub fn eq_custom(model: Option<&Value>, bitmap: Option<&[u16]>) -> bool {
    flag(model, "customEq") && in_bitmap(bitmap, CMD_SAVE_CUSTOM_EQ)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex16(s: &str) -> Vec<u16> { s.split_whitespace().map(|h| u16::from_str_radix(h, 16).unwrap()).collect() }

    /// The phone's `RowRulesTest.realLogsShowTheRowsTheBudsReport`, on the same file.
    #[test]
    fn real_logs_show_the_rows_the_buds_report() {
        let file: Value = serde_json::from_str(include_str!("../../testdata/row_fixtures.json")).unwrap();
        let home = [WIND, GAME, HIRES, SPATIAL, GOLDEN, EQ, DUAL, ANC, EARBUDS];
        for f in file["fixtures"].as_array().unwrap() {
            let name = f["model"].as_str().unwrap();
            let m = models().iter().find(|x| x["name"] == name).unwrap_or_else(|| panic!("{name} not in models.json"));
            let bitmap = hex16(f["capabilities"].as_str().unwrap());
            let asked = ask_ids(Some(m));
            let reply: HashMap<u8, u8> = f["features"].as_str().unwrap().split_whitespace().filter_map(|e| {
                let (id, v) = e.split_once('=')?;
                Some((u8::from_str_radix(id, 16).ok()?, v.parse().ok()?))
            }).filter(|(id, _)| asked.contains(id)).collect();
            let got = rows(&Input { model: Some(m), reply: Some(&reply), bitmap: Some(&bitmap), ..Default::default() });
            let strs = |k: &str| f[k].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect::<BTreeSet<_>>();
            let (expect, uncertain) = (strs("expect"), strs("uncertain"));
            for key in home.iter().filter(|k| !uncertain.contains(**k)) {
                assert_eq!(got.contains(key), expect.contains(*key), "{name} report {}: row {key}", f["report"]);
            }
        }
    }

    #[test]
    fn nothing_read_shows_only_earbuds() {
        let m = find_model(Some("065414"), None);
        assert_eq!(rows(&Input { model: m, ..Default::default() }), BTreeSet::from([EARBUDS]));
    }

    #[test]
    fn manual_passes_the_bitmap_and_write_only_rows_show_by_flag() {
        let m = serde_json::json!({ "tapLevel": 1, "personalAnc": 1, "autoAnswer": 1 });
        let reply = HashMap::from([(FEATURE_PERSONAL_NOISE, 0)]);
        let base = Input { model: Some(&m), reply: Some(&reply), bitmap: Some(&[]), ..Default::default() };
        let auto = rows(&base);
        assert!(!auto.contains(TAP_LEVEL) && !auto.contains(PERSONAL_ANC) && auto.contains("autoAnswer"));
        let manual = rows(&Input { manual: true, ..base });
        assert!(manual.contains(TAP_LEVEL) && manual.contains(PERSONAL_ANC));
    }

    #[test]
    fn game_eq_needs_the_switch_and_the_command() {
        let reply = HashMap::from([(FEATURE_GAME_EQ, 1)]);
        let only_switch = rows(&Input { reply: Some(&reply), bitmap: Some(&[]), ..Default::default() });
        assert!(!only_switch.contains(GAME_EQ));
        let both = rows(&Input { reply: Some(&reply), bitmap: Some(&[CMD_GAME_EQ_STATE]), ..Default::default() });
        assert!(both.contains(GAME_EQ));
    }

    #[test]
    fn ask_ids_add_the_flag_ids() {
        let m = serde_json::json!({ "statusQuery": "05 04", "autoVolume": 1 });
        assert_eq!(ask_ids(Some(&m)), [0x04, 0x05, 0x06, 0x1C, 0x28]);
        assert_eq!(ask_ids(None), DEFAULT_IDS);
        let find = serde_json::json!({ "findPhone": 1 });
        assert!(ask_ids(Some(&find)).contains(&0x26));
    }
}
