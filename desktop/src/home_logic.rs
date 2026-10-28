//! What the Overview's Hi-Res and 3D audio switches write, from the buds' state and the model (PROTOCOL.md section 18). No UI here:
//! `earbuds` (Slint) and `desktop-egui` both use it.

use crate::icons;
use crate::session::{Cmd, Snapshot};
use crate::types::*;

/// (feature id, `models.json` flag, icon, title, subtitle, confirm text, confirm turning off too), in the
/// phone's order. A row shows when the buds list its id in `0x810D`, or the model picked by hand has the
/// flag (`Capabilities.offered`).
pub(crate) const ROWS: &[(u8, Option<&str>, &str, &str, &str, Option<&str>, bool)] = &[
    // Wear detection: the buds' own auto play/pause. The phone's smart auto-pause needs its media session.
    (0x04, None, icons::EARBUD, "wear_firmware_title", "wear_firmware_sub", None, false),
    (0x09, Some("voiceBoost"), icons::EQUALIZER, "row_vocal_title", "row_vocal_sub", None, false),
    (0x27, Some("gameSoundFx"), icons::LOW_LATENCY, "row_game_sound_title", "row_game_sound_sub", None, false),
    (0x1C, Some("autoVolume"), icons::VOLUME, "row_smart_volume_title", "row_smart_volume_sub", None, false),
    (0x30, None, icons::VOLUME, "row_adaptive_volume_title", "row_adaptive_volume_sub", None, false),
    (0x31, None, icons::EARBUD, "row_adaptive_ear_title", "row_adaptive_ear_sub", None, false),
    (0x3A, None, icons::EARBUD, "row_sleep_title", "row_sleep_sub", None, false),
    (0x16, None, icons::FIND_BUDS, "row_lost_title", "row_lost_sub", None, false),
    (0x32, None, icons::TRANSPARENCY, "row_speech_title", "row_speech_sub", None, false),
    // Adaptive sound costs battery: asks only to turn on. Power saving restarts the buds: asks both ways.
    (FEATURE_HEARING_OPTIMIZE, None, icons::HEARING, "row_hearing_optimize_title", "row_hearing_optimize_sub", Some("hearing_optimize_confirm"), false),
    (0x35, Some("holdVolume"), icons::VOLUME, "row_long_press_volume_title", "row_long_press_volume_sub", None, false),
    (0x3B, None, icons::GESTURE, "row_head_motion_title", "row_head_motion_sub", None, false),
    (0x37, Some("fastPair"), icons::DEVICES, "row_swift_pair_title", "row_swift_pair_sub", None, false),
    (FEATURE_POWER_SAVING, None, icons::POWER, "row_power_saving_title", "row_power_saving_sub", Some("power_saving_confirm"), true),
];

/// Our `spatialToggle` key (realme): Hi-Res and 3D audio are written alone, never together (PROTOCOL.md section 18).
pub(crate) fn exclusive(s: &Snapshot) -> bool { !s.model.is_some_and(|m| m["spatialToggle"] == 1) }

/// 3D audio as HeyMelody picks it (PROTOCOL.md section 18): buds whose bitmap has `0x012A` (or a hand-picked model with head
/// tracking) take a type through `0x0422`, the rest feature `1B`.
pub(crate) fn spatial_by_type(s: &Snapshot) -> bool {
    exclusive(s) && (s.caps.supports(CMD_QUERY_SPATIAL_TYPE) || s.manual && head_tracking(s))
}

/// The model's `spatialModes` has `2`: Off / Fixed / Head tracking instead of a plain switch.
pub(crate) fn head_tracking(s: &Snapshot) -> bool {
    s.model.is_some_and(|m| m["spatialModes"].as_array().is_some_and(|t| t.iter().any(|v| *v == 2)))
}

/// The codec picker replaces the Hi-Res write on `hiRes` models with the `0x0123` read (PROTOCOL.md section 18).
pub(crate) fn codec_picker(s: &Snapshot) -> bool { s.model.is_some_and(|m| m["hiRes"] == 1) && s.caps.supports(CMD_QUERY_CODEC_LIST) }

pub(crate) fn spatial_on(s: &Snapshot) -> bool {
    if spatial_by_type(s) { s.spatial_type.unwrap_or(0) != 0 } else { s.features.contains(&(FEATURE_SPATIAL, 1)) }
}

/// 3D audio to `t` (0 off): Hi-Res goes off after it where the two exclude each other, as HeyMelody sends them.
pub(crate) fn spatial_cmds(s: &Snapshot, t: u8) -> Vec<Cmd> {
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

/// The writes for one switch. Hi-Res is one feature-sync operation: the codec write on codec-picker models;
/// else 3D audio goes off first where the two exclude each other.
pub(crate) fn feature_cmds(s: &Snapshot, id: u8, on: bool) -> Vec<Cmd> {
    match id {
        FEATURE_SPATIAL => spatial_cmds(s, on as u8),
        // The game equalizer writes its switch and the game state together (`Cmd::GameEq`).
        FEATURE_GAME_EQ => vec![Cmd::GameEq(on)],
        FEATURE_HIRES if codec_picker(s) => s.codec.map(|c| Cmd::Codec(c, on)).into_iter().collect(),
        FEATURE_HIRES if on && exclusive(s) && spatial_on(s) => if spatial_by_type(s) {
            vec![Cmd::SpatialType(0), Cmd::Hires { on: true, drop_spatial: false }]
        } else {
            vec![Cmd::Hires { on: true, drop_spatial: true }]
        },
        FEATURE_HIRES => vec![Cmd::Hires { on, drop_spatial: false }],
        _ => vec![Cmd::Features(vec![(id, on)])],
    }
}
