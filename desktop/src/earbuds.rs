//! The Earbud settings page (`EarbudSettingsActivity`): the feature switches the buds list, and the firmware.

use crate::protocol::*;
use crate::session::Cmd;
use crate::{icons, svg, t, with_app, App, Earbuds, FeatureItem, MainWindow};
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
    e.on_set_feature(|id, on| with_app(|a| { let _ = a.tx.send(Cmd::Feature(id as u8, on)); }));
    e.on_find(|on| with_app(|a| { let _ = a.tx.send(Cmd::Find(on)); }));
    e.on_opened(|| with_app(|a| { let _ = a.tx.send(Cmd::EarbudReads); }));
    e.on_alert_released(|l| with_app(|a| { let _ = a.tx.send(Cmd::AlertVolume(l as u8)); }));
    e.on_tap_released(|l| with_app(|a| { let _ = a.tx.send(Cmd::TapLevel(l as u8)); }));
}

pub fn apply(a: &App) {
    let s = &a.snap;
    let flagged = |flag: Option<&str>| s.manual && flag.is_some_and(|f| s.model.is_some_and(|m| m[f] == 1));
    let rows: Vec<FeatureItem> = ROWS.iter().filter_map(|&(id, flag, icon, title, sub, confirm, confirm_off)| {
        let v = match s.features.iter().find(|f| f.0 == id) { Some(f) => f.1, None if flagged(flag) => 0, None => return None };
        Some(FeatureItem {
            id: id as i32, icon: svg(icon), title: t(&a.tr, title).into(), sub: t(&a.tr, sub).into(), on: v == 1,
            confirm: confirm.map_or("", |k| t(&a.tr, k)).into(), confirm_off,
        })
    }).collect();
    let e = a.main.global::<Earbuds>();
    e.set_features(ModelRc::new(VecModel::from(rows)));
    e.set_has_firmware(s.caps.supports(CMD_QUERY_FIRMWARE));
    e.set_has_find(s.caps.supports(CMD_FIND_BUDS));
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
