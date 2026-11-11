//! One model's entry in `models.json`, as typed answers: the phone's `ModelProfile.kt`. It is the only reader of the
//! entry's top-level keys: code asks `profile.no_find()`, not `m["noFind"] == 1`. Nested tables (gestures, noise modes,
//! presets) go to their own parsers. No entry (an unknown model) answers false, 0, None or an empty list.

use serde_json::Value;

#[derive(Clone, Copy)]
pub struct Profile<'a>(pub Option<&'a Value>);

impl<'a> Profile<'a> {
    fn flag(&self, key: &str) -> bool { self.0.is_some_and(|m| m[key] == 1) }
    fn int(&self, key: &str) -> i64 { self.0.and_then(|m| m[key].as_i64()).unwrap_or(0) }
    fn array(&self, key: &str) -> Option<&'a Vec<Value>> { self.0.and_then(|m| m[key].as_array()) }
    fn has_str(&self, key: &str, v: &str) -> bool { self.array(key).is_some_and(|a| a.iter().any(|x| x.as_str() == Some(v))) }

    pub fn name(&self) -> &'a str { self.0.and_then(|m| m["name"].as_str()).unwrap_or("") }
    /// The first word of the name (OnePlus, OPPO, realme, DIZO). Vendor rules that differ by brand read it here.
    pub fn brand(&self) -> &'a str { self.name().split(' ').next().unwrap_or("") }
    /// A OnePlus or OPPO model: HeyMelody's list, not realme Link's.
    pub fn hey_melody(&self) -> bool { matches!(self.brand(), "OnePlus" | "OPPO") }

    // What to ask in 0x010D, and how a write is confirmed.
    pub fn status_query(&self) -> Option<&'a str> { self.0.and_then(|m| m["statusQuery"].as_str()).filter(|s| !s.trim().is_empty()) }
    pub fn game_id(&self) -> Option<u8> { Some(self.int("gameId")).filter(|&g| g > 0).map(|g| g as u8) }
    pub fn game_confirm_read(&self) -> bool { self.0.is_some_and(|m| m["gameConfirm"] == "read") }
    pub fn seqless(&self) -> bool { self.flag("seqless") }
    /// A flag that adds a switch to the ask list (`windMode`, `voiceBoost`, ...): see `row_rules::ask_ids`.
    pub fn asks(&self, key: &str) -> bool { self.flag(key) }

    // Rows.
    pub fn no_find(&self) -> bool { self.flag("noFind") }
    pub fn tap_level(&self) -> bool { self.flag("tapLevel") }
    pub fn personal_anc(&self) -> bool { self.flag("personalAnc") }
    pub fn find_phone(&self) -> bool { self.flag("findPhone") }
    pub fn wind_mode(&self) -> bool { self.flag("windMode") }
    /// A write-only row flag (`volumeEnhancer`, `autoAnswer`): the buds do not report it.
    pub fn write_only(&self, key: &str) -> bool { self.flag(key) }
    pub fn hi_res(&self) -> bool { self.flag("hiRes") }
    /// 3D audio and Hi-Res are one switch each, written alone (realme).
    pub fn spatial_toggle(&self) -> bool { self.flag("spatialToggle") }
    /// `spatialModes` holds the number 2: Off / Fixed / Head tracking.
    pub fn spatial_head_tracking(&self) -> bool { self.array("spatialModes").is_some_and(|a| a.iter().any(|v| v == 2)) }
    pub fn triple_device(&self) -> bool { self.flag("tripleDevice") }
    /// The model offers this dual-connection function (`setPriorityDevice`, `connectDisconnectDevice`, ...).
    pub fn dual_device(&self, f: &str) -> bool { self.has_str("dualDevice", f) }
    pub fn hearing_scan(&self) -> bool { self.flag("hearingScan") }
    pub fn codecs(&self) -> Option<&'a Vec<Value>> { self.array("codecs") }
    pub fn known_issue(&self, key: &str) -> bool { self.has_str("knownIssues", key) }
    /// An owner confirmed this feature in a model test.
    pub fn confirmed(&self, key: &str) -> bool { self.has_str("confirmed", key) }

    // Equalizer.
    /// 1-4: built-in presets; 2 also names types 1-4 differently. 0: none.
    pub fn eq_kind(&self) -> i64 { self.int("eqKind") }
    pub fn custom_eq(&self) -> bool { self.flag("customEq") }
    /// Custom presets the model takes; 3 when the entry says nothing.
    pub fn custom_eq_limit(&self) -> usize { Some(self.int("customEqLimit")).filter(|&n| n > 0).unwrap_or(3) as usize }
    pub fn custom_eq_bands(&self) -> Option<&'a Vec<Value>> { self.array("customEqBands") }
    pub fn bass_engine(&self) -> bool { self.flag("bassEngine") }
    /// 3: realme dynamic sound, three sliders with a band byte.
    pub fn bass_bands(&self) -> i64 { self.int("bassBands") }
    pub fn eq_alt_names(&self) -> bool { self.flag("eqAltNames") }
    pub fn eq_gentle4(&self) -> bool { self.flag("eqGentle4") }

    // Gestures and noise modes: tables for their parsers.
    pub fn gestures(&self) -> Option<&'a Vec<Value>> { self.array("gestures") }
    pub fn call_gestures(&self) -> Option<&'a Vec<Value>> { self.array("callGestures") }
    pub fn anc_modes(&self) -> Option<&'a Vec<Value>> { self.array("ancModes") }
    /// `longPressType`: each bud has its own hold; the bits are its choices. 0: one shared hold.
    pub fn hold_mask(&self) -> i64 { self.int("holdMask") }
    pub fn shared_hold(&self) -> bool { self.flag("sharedHold") }
    pub fn one_button(&self) -> bool { self.flag("oneButton") }
    pub fn both_hold(&self) -> bool { self.flag("bothHold") }
    /// OnePlus Buds, Buds Z: previous and next track are `fn` 4 and 5.
    pub fn old_track_bytes(&self) -> bool { self.flag("oldTrackBytes") }
    /// Buds Pro: the hold shows its ANC levels as one ANC.
    pub fn hold_anc_one(&self) -> bool { self.flag("holdAncOne") }
}
