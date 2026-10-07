//! The Earbud settings page (`EarbudSettingsActivity`): the feature switches the buds list, and the firmware.

use crate::protocol::*;
use crate::session::{Cmd, Snapshot};
use crate::{icons, svg, t, with_app, App, Choice, Earbuds, FeatureItem, MainWindow};
use slint::{ComponentHandle, ModelRc, VecModel};

/// (feature id, `models.json` flag, icon, title, subtitle, confirm text, confirm turning off too), in the
/// phone's order. A row shows when the buds list its id in `0x810D`, or the model picked by hand has the
/// flag (`Capabilities.offered`).
// ponytail: Personalized ANC (0x0C) needs the phone's test flow, game sound and head gestures their type
// pickers; they come with those.
const ROWS: &[(u8, Option<&str>, &str, &str, &str, Option<&str>, bool)] = &[
    // Wear detection: the buds' own auto play/pause. The phone's smart auto-pause needs its media session.
    (0x04, None, icons::EARBUD, "wear_firmware_title", "wear_firmware_sub", None, false),
    (0x09, Some("vocalEnhance"), icons::EQUALIZER, "row_vocal_title", "row_vocal_sub", None, false),
    (0x27, Some("gameSound"), icons::LOW_LATENCY, "row_game_sound_title", "row_game_sound_sub", None, false),
    (0x1C, Some("controlAutoVolumeSupport"), icons::VOLUME, "row_smart_volume_title", "row_smart_volume_sub", None, false),
    (0x30, None, icons::VOLUME, "row_adaptive_volume_title", "row_adaptive_volume_sub", None, false),
    (0x31, None, icons::EARBUD, "row_adaptive_ear_title", "row_adaptive_ear_sub", None, false),
    (0x3A, None, icons::EARBUD, "row_sleep_title", "row_sleep_sub", None, false),
    (0x32, None, icons::TRANSPARENCY, "row_speech_title", "row_speech_sub", None, false),
    // Adaptive sound costs battery: asks only to turn on. Power saving restarts the buds: asks both ways.
    (FEATURE_HEARING_OPTIMIZE, None, icons::HEARING, "row_hearing_optimize_title", "row_hearing_optimize_sub", Some("hearing_optimize_confirm"), false),
    (0x35, Some("longPressVolume"), icons::VOLUME, "row_long_press_volume_title", "row_long_press_volume_sub", None, false),
    (0x3B, None, icons::GESTURE, "row_head_motion_title", "row_head_motion_sub", None, false),
    (0x37, Some("swiftPair"), icons::DEVICES, "row_swift_pair_title", "row_swift_pair_sub", None, false),
    (FEATURE_POWER_SAVING, None, icons::POWER, "row_power_saving_title", "row_power_saving_sub", Some("power_saving_confirm"), true),
];

pub fn setup(main: &MainWindow) {
    let e = main.global::<Earbuds>();
    e.on_set_feature(|id, on| with_app(|a| for c in feature_cmds(&a.snap, id as u8, on) { let _ = a.tx.send(c); }));
    e.on_find(|on| with_app(|a| { let _ = a.tx.send(Cmd::Find(on)); }));
    e.on_opened(|| with_app(|a| { let _ = a.tx.send(Cmd::EarbudReads); }));
    e.on_alert_released(|l| with_app(|a| { let _ = a.tx.send(Cmd::AlertVolume(l as u8)); }));
    e.on_tap_released(|l| with_app(|a| { let _ = a.tx.send(Cmd::TapLevel(l as u8)); }));
    // 1 game sound type, 2 head gesture mapping, 3 codec, 4 spatial type.
    e.on_choose(|kind, v| with_app(|a| {
        let (s, v) = (&a.snap, v as u8);
        let cmds = match kind {
            1 => vec![Cmd::GameSoundType(v)],
            3 => vec![Cmd::Codec(v, s.features.contains(&(FEATURE_HIRES, 1)))],
            4 => spatial_cmds(s, v),
            _ => vec![Cmd::HeadMotion(v)],
        };
        for c in cmds { let _ = a.tx.send(c); }
    }));
    e.on_fit(|on| with_app(|a| { let _ = a.tx.send(Cmd::FitTest(on)); }));
    e.on_pnc_query(|| with_app(|a| { let _ = a.tx.send(Cmd::PncQuery); }));
    e.on_pnc(|action| with_app(|a| { let _ = a.tx.send(Cmd::Pnc(action as u8)); }));
    e.on_status_read(|| with_app(|a| { let _ = a.tx.send(Cmd::StatusRead); }));
}

/// Our `spatialSwitch` key (realme): Hi-Res and 3D audio are written alone, never together (§9).
fn exclusive(s: &Snapshot) -> bool { !s.model.is_some_and(|m| m["spatialSwitch"] == 1) }

/// 3D audio as HeyMelody picks it (§9): buds whose bitmap has `0x012A` (or a hand-picked model with head
/// tracking) take a type through `0x0422`, the rest feature `1B`.
fn spatial_by_type(s: &Snapshot) -> bool {
    exclusive(s) && (s.caps.supports(CMD_QUERY_SPATIAL_TYPE) || s.manual && head_tracking(s))
}

/// The model's `spatialTypes` has `2`: Off / Fixed / Head tracking instead of a plain switch.
fn head_tracking(s: &Snapshot) -> bool {
    s.model.is_some_and(|m| m["spatialTypes"].as_array().is_some_and(|t| t.iter().any(|v| *v == 2)))
}

/// The codec picker replaces the Hi-Res write on `highAudio` models with the `0x0123` read (§9).
fn codec_picker(s: &Snapshot) -> bool { s.model.is_some_and(|m| m["highAudio"] == 1) && s.caps.supports(CMD_QUERY_CODEC_LIST) }

fn spatial_on(s: &Snapshot) -> bool {
    if spatial_by_type(s) { s.spatial_type.unwrap_or(0) != 0 } else { s.features.contains(&(FEATURE_SPATIAL, 1)) }
}

/// 3D audio to `t` (0 off): Hi-Res goes off after it where the two exclude each other, as HeyMelody sends them.
fn spatial_cmds(s: &Snapshot, t: u8) -> Vec<Cmd> {
    let drop_hires = t != 0 && exclusive(s) && s.features.contains(&(FEATURE_HIRES, 1));
    let mut cmds = vec![];
    if spatial_by_type(s) {
        cmds.push(Cmd::SpatialType(t));
        if drop_hires { cmds.push(Cmd::Features(vec![(FEATURE_HIRES, false)])); }
    } else if drop_hires {
        cmds.push(Cmd::Features(vec![(FEATURE_SPATIAL, true), (FEATURE_HIRES, false)]));
    } else {
        cmds.push(Cmd::Features(vec![(FEATURE_SPATIAL, t != 0)]));
    }
    cmds
}

/// The writes for one switch. Hi-Res: the codec write on codec-picker models; else 3D audio goes off first
/// where the two exclude each other.
fn feature_cmds(s: &Snapshot, id: u8, on: bool) -> Vec<Cmd> {
    match id {
        FEATURE_SPATIAL => spatial_cmds(s, on as u8),
        FEATURE_HIRES if codec_picker(s) => s.codec.map(|c| Cmd::Codec(c, on)).into_iter().collect(),
        FEATURE_HIRES if on && exclusive(s) && spatial_on(s) => if spatial_by_type(s) {
            vec![Cmd::SpatialType(0), Cmd::Features(vec![(FEATURE_HIRES, true)])]
        } else {
            vec![Cmd::Features(vec![(FEATURE_SPATIAL, false), (FEATURE_HIRES, true)])]
        },
        _ => vec![Cmd::Features(vec![(id, on)])],
    }
}

fn spatial_label(a: &App, ty: u8) -> String {
    t(&a.tr, match ty { 1 => "spatial_fixed", 2 => "spatial_head_tracking", _ => "anc_seg_off" }).into()
}

/// The Overview's switches, in the phone's order: wind noise reduction, low latency, Hi-Res, 3D audio.
/// Low latency is id -1: it writes through `Buds.set-low-latency`. A codec write makes the buds
/// reconnect, so Hi-Res and the codec picker ask first, and 3D audio asks when it turns Hi-Res off.
pub fn home(a: &App) {
    let s = &a.snap;
    let listed = |id: u8| s.features.iter().find(|f| f.0 == id).map(|f| f.1);
    let flag = |k: &str| s.model.is_some_and(|m| m[k] == 1);
    let item = |id: i32, icon: &str, title: &str, sub: &str, on: bool, confirm: &str, confirm_off: bool| FeatureItem {
        id, icon: svg(icon), title: t(&a.tr, title).into(), sub: t(&a.tr, sub).into(), on,
        confirm: if confirm.is_empty() { "".into() } else { t(&a.tr, confirm).into() }, confirm_off, ..Default::default()
    };
    let mut rows = vec![];
    if let Some(v) = listed(FEATURE_WIND_NOISE).or((s.manual && flag("windNoise")).then_some(0)) {
        rows.push(item(FEATURE_WIND_NOISE as i32, icons::ANC, "row_wind_noise_title", "row_wind_noise_sub", v == 1, "", false));
    }
    if a.has.game {
        rows.push(item(-1, icons::LOW_LATENCY, "row_game_title", "row_game_sub", s.low_latency.unwrap_or(false), "", false));
    }
    let hires_on = listed(FEATURE_HIRES) == Some(1);
    let picker = codec_picker(s);
    if listed(FEATURE_HIRES).is_some() || picker {
        let msg = if !picker && spatial_on(s) && exclusive(s) { "codec_msg_hires_drops_spatial" } else { "codec_msg_reconnect" };
        let mut r = item(FEATURE_HIRES as i32, icons::HIRES, "row_hires_title",
            if hires_on { "row_hires_sub" } else { "row_hires_sub_off" }, hires_on, msg, true);
        if picker {
            // HeyMelody's "High-quality audio" screen: pick the codec; Hi-Res only with LDAC or LHDC V5.
            let name = s.codec.map(codec_name);
            r.locked = !matches!(s.codec, Some(3 | 8));
            r.choice_kind = 3;
            r.choice_title = t(&a.tr, "row_hires_title").into();
            r.choice = name.unwrap_or("—".into()).into();
        }
        rows.push(r);
    }
    if listed(FEATURE_SPATIAL).is_some() || spatial_by_type(s) {
        let msg = if hires_on && exclusive(s) { "codec_msg_spatial_drops_hires" } else { "" };
        let mut r = item(FEATURE_SPATIAL as i32, icons::SPATIAL, "row_spatial_title", "row_spatial_sub", spatial_on(s), msg, false);
        if head_tracking(s) {
            r.choice_kind = 4;
            r.choice_title = t(&a.tr, "row_spatial_title").into();
            r.choice = spatial_label(a, s.spatial_type.unwrap_or(0)).into();
        }
        rows.push(r);
    }
    let e = a.main.global::<Earbuds>();
    crate::update_rows(e.get_home(), rows, |m| e.set_home(m));
    // HeyMelody's order: LHDC V5, LHDC, LDAC, aptX Adaptive, aptX HD, aptX, AAC, SBC.
    let codecs: Vec<Choice> = [8, 7, 3, 6, 5, 4, 2, 1].into_iter().filter(|c| s.codecs.contains(c))
        .map(|c| Choice { value: c as i32, label: codec_name(c).into() }).collect();
    e.set_codec_choices(ModelRc::new(VecModel::from(codecs)));
    e.set_codec_current(s.codec.map_or(-1, |c| c as i32));
    e.set_spatial_choices(ModelRc::new(VecModel::from((0..3).map(|v| Choice { value: v, label: spatial_label(a, v as u8).into() }).collect::<Vec<_>>())));
    e.set_spatial_current(s.spatial_type.map_or(0, |t| t as i32));
}

/// HeyMelody's names for the game sound types; a type without one is not offered.
fn game_label(a: &App, t: u8) -> Option<String> {
    Some(crate::t(&a.tr, match t { 0 => "anc_seg_off", 1 => "game_sound_type_peace", 3 => "game_sound_type_shooter", _ => return None }).into())
}

fn head_label(a: &App, t: u8) -> Option<String> {
    Some(crate::t(&a.tr, match t { 0 => "head_motion_nod", 1 => "head_motion_shake", _ => return None }).into())
}

pub fn apply(a: &App) {
    let s = &a.snap;
    let flagged = |flag: Option<&str>| s.manual && flag.is_some_and(|f| s.model.is_some_and(|m| m[f] == 1));
    // Personalized ANC first, as on the phone: the model's flag too (HeyMelody gates on it alone, and the
    // Buds 4 list `0C` without having the feature). Turning it on runs the test flow, not a write.
    let pnc_listed = s.features.iter().find(|f| f.0 == FEATURE_PERSONAL_NOISE).map(|f| f.1);
    // A model without the key has no feature (`optInt` gives 0); no model at all leaves it to the buds.
    let pnc = (s.model.map_or(true, |m| m["personalNoise"] == 1)
        && (pnc_listed.is_some() || flagged(Some("personalNoise")))
        && (s.caps.supports(CMD_PERSONAL_NOISE) || s.manual))
        .then(|| FeatureItem {
            id: FEATURE_PERSONAL_NOISE as i32, icon: svg(icons::MODE_ANC_MEDIUM), title: t(&a.tr, "pnc_title").into(),
            sub: t(&a.tr, "pnc_sub").into(), on: pnc_listed == Some(1), flow: true, ..Default::default()
        });
    let rows: Vec<FeatureItem> = pnc.into_iter().chain(ROWS.iter().filter_map(|&(id, flag, icon, title, sub, confirm, confirm_off)| {
        let v = match s.features.iter().find(|f| f.0 == id) { Some(f) => f.1, None if flagged(flag) => 0, None => return None };
        // The picker under its switch: which game sound effect, or which head gesture answers.
        let (kind, choice_title, choice) = match id {
            0x27 if s.manual || s.caps.supports(CMD_GAME_SOUND) => (1, "game_sound_type_title",
                s.game_sound.as_ref().and_then(|g| game_label(a, g.0))),
            0x3B if s.caps.supports(CMD_SET_HEAD_MOTION) => (2, "head_motion_type_title", s.head_motion.and_then(|t| head_label(a, t))),
            _ => (0, "", None),
        };
        Some(FeatureItem {
            id: id as i32, icon: svg(icon), title: t(&a.tr, title).into(), sub: t(&a.tr, sub).into(), on: v == 1,
            confirm: confirm.map_or("", |k| t(&a.tr, k)).into(), confirm_off,
            choice_kind: kind, choice_title: if kind > 0 { t(&a.tr, choice_title).into() } else { "".into() },
            choice: choice.unwrap_or("—".into()).into(), flow: false, locked: false,
        })
    })).collect();
    let e = a.main.global::<Earbuds>();
    crate::update_rows(e.get_features(), rows, |m| e.set_features(m));
    e.set_has_firmware(s.caps.supports(CMD_QUERY_FIRMWARE));
    e.set_has_find(s.caps.supports(CMD_FIND_BUDS));
    e.set_has_fit(s.caps.supports(CMD_FIT_TEST));
    e.set_fit_left(s.fit.0 as i32);
    e.set_fit_right(s.fit.1 as i32);
    e.set_fit_seq(s.fit.2 as i32);
    e.set_pnc_kind(s.pnc.0 as i32);
    e.set_pnc_value(s.pnc.1 as i32);
    e.set_pnc_seq(s.pnc.2 as i32);
    // The types the buds offer (`0x812B`), Off first; before a read, HeyMelody's usual Off + shooting.
    let offered = s.game_sound.as_ref().map(|g| g.1.clone()).filter(|t| !t.is_empty()).unwrap_or(vec![3]);
    let mut game: Vec<u8> = vec![0];
    for t in offered { if !game.contains(&t) { game.push(t); } }
    let choices = |list: &[u8], label: &dyn Fn(u8) -> Option<String>| -> ModelRc<Choice> {
        ModelRc::new(VecModel::from(list.iter().filter_map(|&v| Some(Choice { value: v as i32, label: label(v)?.into() })).collect::<Vec<_>>()))
    };
    e.set_game_choices(choices(&game, &|t| game_label(a, t)));
    e.set_game_current(s.game_sound.as_ref().map_or(-1, |g| g.0 as i32));
    e.set_head_choices(choices(&[0, 1], &|t| head_label(a, t)));
    e.set_head_current(s.head_motion.map_or(-1, |t| t as i32));
    e.set_has_alert(s.caps.supports(CMD_SET_ALERT_VOLUME) && s.alert_volume.is_some());
    // Tap sensitivity where HeyMelody's model list has `tapLevelSetting`.
    // Without the buds' word (a manual pick), the slider starts at the middle level.
    e.set_has_tap(s.model.is_some_and(|m| m["tapLevelSetting"] == 1) && (s.manual || s.caps.supports(CMD_SET_TAP_LEVEL) && s.tap_level.is_some()));
    // A held slider keeps its value (`Eq.dragging` is the window's one drag flag).
    if !a.main.global::<crate::Eq>().get_dragging() {
        if let Some(l) = s.alert_volume { e.set_alert(l as i32); }
        if let Some((l, d)) = s.tap_level { e.set_tap(l as i32); e.set_tap_default(d as i32); }
    }
    e.set_firmware(s.firmware.as_deref().unwrap_or("—").into());
}
