//! The Earbud settings page (`EarbudSettingsActivity`): the feature switches the buds list, and the firmware.

use crate::protocol::*;
use crate::session::Cmd;
use crate::{icons, svg, t, with_app, App, Earbuds, FeatureItem, MainWindow};
use slint::{ComponentHandle, ModelRc, VecModel};

/// (feature id, icon, title, subtitle, confirm text, confirm turning off too), in the phone's order.
/// A row shows only when the buds list its id in `0x810D` (`Capabilities.offered`).
// ponytail: Personalized ANC (0x0C) needs the phone's test flow, game sound and head gestures their type
// pickers; they come with those.
const ROWS: &[(u8, &str, &str, &str, Option<&str>, bool)] = &[
    // Wear detection: the buds' own auto play/pause. The phone's smart auto-pause needs its media session.
    (0x04, icons::EARBUD, "wear_firmware_title", "wear_firmware_sub", None, false),
    (0x09, icons::EQUALIZER, "row_vocal_title", "row_vocal_sub", None, false),
    (0x27, icons::LOW_LATENCY, "row_game_sound_title", "row_game_sound_sub", None, false),
    (0x1C, icons::VOLUME, "row_smart_volume_title", "row_smart_volume_sub", None, false),
    (0x30, icons::VOLUME, "row_adaptive_volume_title", "row_adaptive_volume_sub", None, false),
    (0x31, icons::EARBUD, "row_adaptive_ear_title", "row_adaptive_ear_sub", None, false),
    (0x3A, icons::EARBUD, "row_sleep_title", "row_sleep_sub", None, false),
    (0x32, icons::TRANSPARENCY, "row_speech_title", "row_speech_sub", None, false),
    // Adaptive sound costs battery: asks only to turn on. Power saving restarts the buds: asks both ways.
    (FEATURE_HEARING_OPTIMIZE, icons::HEARING, "row_hearing_optimize_title", "row_hearing_optimize_sub", Some("hearing_optimize_confirm"), false),
    (0x35, icons::VOLUME, "row_long_press_volume_title", "row_long_press_volume_sub", None, false),
    (0x3B, icons::GESTURE, "row_head_motion_title", "row_head_motion_sub", None, false),
    (0x37, icons::DEVICES, "row_swift_pair_title", "row_swift_pair_sub", None, false),
    (FEATURE_POWER_SAVING, icons::POWER, "row_power_saving_title", "row_power_saving_sub", Some("power_saving_confirm"), true),
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
    let rows: Vec<FeatureItem> = ROWS.iter().filter_map(|&(id, icon, title, sub, confirm, confirm_off)| {
        let v = s.features.iter().find(|f| f.0 == id)?.1;
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
    e.set_has_tap(s.model.is_some_and(|m| m["tapLevelSetting"] == 1) && s.caps.supports(CMD_SET_TAP_LEVEL) && s.tap_level.is_some());
    // A held slider keeps its value (`Eq.dragging` is the window's one drag flag).
    if !a.main.global::<crate::Eq>().get_dragging() {
        if let Some(l) = s.alert_volume { e.set_alert(l as i32); }
        if let Some((l, d)) = s.tap_level { e.set_tap(l as i32); e.set_tap_default(d as i32); }
    }
    e.set_firmware(s.firmware.as_deref().unwrap_or("—").into());
}
