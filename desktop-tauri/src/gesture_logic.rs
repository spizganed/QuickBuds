//! Which action each gesture does, per bud: the model's rows and options (HeyMelody's `control` / `callControl` lists),
//! the current bindings from the buds' table (`0x8108`) and hold cycle (`0x810C 02`), and the writes.
//! `fn` values are measured (PROTOCOL.md section 15): never derive new ones. No UI here.

use crate::icons;
use crate::session::{Cmd, Snapshot};
use crate::types::*;
use serde_json::Value;

/// (label, keyfn `act`, the `control` actions that are this row, multi-select, icon), in the phone's order.
/// The last row is the hold's noise cycle; a plain hold (`act 4`, action 4) shares its label.
pub(crate) const GESTURES: [(&str, u8, &[i64], bool, &str); 7] = [
    ("gesture_single", 1, &[1, 16], false, icons::TAP_SINGLE),
    ("gesture_double", 2, &[2, 17], false, icons::TAP_DOUBLE),
    ("gesture_triple", 3, &[3, 18], false, icons::TAP_TRIPLE),
    ("gesture_slide", 5, &[5], false, icons::CHEVRON_RIGHT),
    ("gesture_hold", 4, &[4], false, icons::HOLD),
    ("gesture_extra_long", 6, &[6], false, icons::HOLD),
    ("gesture_hold", 4, &[11, 20, 27], true, icons::HOLD),
];
pub(crate) const TAP_HOLD: usize = 6;

/// (label, short label, `fn`, `support` bit, noise `mode` or -1). The four noise actions share `fn 08`
/// (the hold "cycles noise control"); which modes it cycles is the separate mask.
pub(crate) const ACTIONS: [(&str, &str, u8, i64, i64); 17] = [
    ("gesture_action_none", "", 0x00, 512, -1),
    ("gesture_action_play_pause", "", 0x01, 4, -1),
    ("gesture_action_prev", "", 0x05, 32, -1),
    ("gesture_action_next", "", 0x06, 64, -1),
    ("gesture_action_assistant", "", 0x03, 1, -1),
    ("gesture_action_volume_up", "", 0x0B, 8, -1),
    ("gesture_action_volume_down", "", 0x0C, 16, -1),
    ("gesture_action_volume", "", 0x07, 1024, -1),
    ("gesture_action_switch_track", "", 0x0A, 2048, -1),
    ("gesture_action_switch_device", "", 0x0D, 4096, -1),
    ("gesture_action_game", "", 0x11, 8192, -1),
    ("gesture_action_anc_on", "", 0x08, 0, 5),
    ("widget_mode_anc_high", "anc_mode_high", 0x08, 0, 4),
    ("widget_mode_anc_low", "anc_mode_low", 0x08, 0, 3),
    ("gesture_action_anc_adaptive", "gesture_action_anc_adaptive_short", 0x08, 0, 10),
    ("gesture_action_anc_transparency", "gesture_action_anc_transparency_short", 0x08, 0, 2),
    ("gesture_action_anc_off", "gesture_action_anc_off_short", 0x08, 0, 1),
];
pub(crate) const ANC_ON: usize = 11;
/// HeyMelody's option order.
pub(crate) const ORDER: [i64; 11] = [512, 4, 32, 64, 1, 8, 16, 1024, 2048, 4096, 8192];
/// The noise cycle among a per-bud hold's choices.
pub(crate) const HOLD_NOISE: i64 = 128;

/// (row label, enabled label, `act`, enabled `fn`, `callControl` action).
pub(crate) const ON_CALL: [(&str, &str, u8, u8, i64, &str); 4] = [
    ("gesture_single", "gesture_on_call_answer_end", 1, 0x1D, 32, icons::TAP_SINGLE),
    ("gesture_on_call_double_tap", "gesture_on_call_answer_end", 2, 0x1D, 29, icons::TAP_DOUBLE),
    ("gesture_on_call_double_tap", "gesture_on_call_decline", 2, 0x1C, 33, icons::TAP_DOUBLE),
    ("gesture_on_call_long_hold", "gesture_on_call_decline", 6, 0x1C, 31, icons::HOLD),
];

/// The model's gestures (`GestureModel`).
#[derive(Default)]
pub(crate) struct Model {
    /// (gesture, options), in [GESTURES] order.
    pub(crate) rows: Vec<(usize, Vec<usize>)>,
    /// Noise action -> its bit in the hold's mask (the mode's `slot`).
    pub(crate) hold_bits: Vec<(usize, u8)>,
    pub(crate) on_call: Vec<usize>,
    /// OnePlus Buds / Buds Z number previous / next track 4 / 5.
    pub(crate) old_track: bool,
    /// `holdMask`: each bud has its own hold, its choices in [Model::hold_choices].
    pub(crate) per_bud: bool,
    /// Our `sharedHold` (realme Link data): a per-bud hold with one noise mask for both buds.
    pub(crate) shared_mask: bool,
    /// Our `oneButton` (realme Link data): a neckband, one button written as the left bud.
    pub(crate) one_button: bool,
    /// Our `bothHold` (realme Link data): a last row, holding both buds = game mode or none.
    pub(crate) both_hold: bool,
    pub(crate) hold_choices: Vec<i64>,
    pub(crate) hold_min: usize,
    /// OnePlus Buds Pro: its ANC levels are one "ANC" option.
    pub(crate) level_bits: Vec<u8>,
}

impl Model {
    pub(crate) fn of(s: &Snapshot) -> Model {
        // Nothing detected yet = Buds 4 (as the ANC modes); a product id HeyMelody does not list gets none.
        let m = s.model.or_else(|| crate::load_settings()["product_id"].is_null().then(|| find_model(Some("065414"), None)).flatten());
        m.map(Self::parse).unwrap_or_default()
    }

    pub(crate) fn parse(json: &Value) -> Model {
        let name = json["name"].as_str().unwrap_or("");
        let id = json["id"].as_str().unwrap_or("");
        let control: Vec<&Value> = json["gestures"].as_array().into_iter().flatten().collect();
        let long_press = json["holdMask"].as_i64().unwrap_or(0);
        let mut m = Model { hold_min: 1, ..Default::default() };
        for (g, (_, _, actions, multi, _)) in GESTURES.iter().enumerate() {
            let Some(entry) = control.iter().find(|e| e["gesture"].as_i64().is_some_and(|a| actions.contains(&a))) else { continue };
            if *multi {
                let Some(bits) = hold_bits(json, (id == "060C14").then_some(&mut m.level_bits)) else { continue };
                m.rows.push((g, bits.iter().map(|b| b.0).collect()));
                m.hold_bits = bits;
                m.hold_min = entry["minSelected"].as_u64().filter(|n| *n > 0).map(|n| n as usize)
                    .unwrap_or(if name.starts_with("OnePlus") || long_press != 0 { 2 } else { 1 });
            } else {
                let support = entry["options"].as_i64().unwrap_or(0);
                let options: Vec<usize> = ORDER.iter().filter(|b| support & **b != 0)
                    .filter_map(|b| ACTIONS.iter().position(|a| a.3 == *b)).collect();
                if !options.is_empty() { m.rows.push((g, options)); }
            }
        }
        // A callControl row is only a choice if it offers None (512).
        let call: Vec<&Value> = json["callGestures"].as_array().into_iter().flatten().collect();
        m.on_call = (0..ON_CALL.len()).filter(|&i| call.iter().any(|c| c["gesture"].as_i64() == Some(ON_CALL[i].4)
            && c["options"].as_i64().unwrap_or(0) & 512 != 0)).collect();
        m.old_track = name == "OnePlus Buds" || name == "OnePlus Buds Z";
        m.per_bud = long_press != 0;
        m.shared_mask = json["sharedHold"].as_i64() == Some(1);
        m.one_button = json["oneButton"].as_i64() == Some(1);
        m.both_hold = json["bothHold"].as_i64() == Some(1);
        m.hold_choices = if long_press == 0 { vec![] } else { [512, HOLD_NOISE, 1, 8192].into_iter().filter(|b| long_press & b != 0).collect() };
        m
    }

    pub(crate) fn fn_byte(&self, a: usize) -> u8 {
        match a { 2 if self.old_track => 0x04, 3 if self.old_track => 0x05, _ => ACTIONS[a].2 }
    }

    pub(crate) fn options(&self, g: usize) -> &[usize] { self.rows.iter().find(|r| r.0 == g).map_or(&[], |r| &r.1) }

    /// The hold's mask for a selection; on Buds Pro "ANC" takes the current level's bit, else the first.
    pub(crate) fn hold_mask(&self, actions: &[usize], level_bit: Option<u8>) -> u32 {
        actions.iter().filter_map(|a| if *a == ANC_ON && !self.level_bits.is_empty() {
            Some(level_bit.filter(|b| self.level_bits.contains(b)).unwrap_or(self.level_bits[0]))
        } else {
            self.hold_bits.iter().find(|x| x.0 == *a).map(|x| x.1)
        }).fold(0, |m, b| m | 1 << b)
    }

    /// The selection a mask read from the buds stands for.
    pub(crate) fn hold_actions(&self, mask: u32) -> Vec<usize> {
        self.options(TAP_HOLD).iter().copied().filter(|a| {
            let bits: Vec<u8> = if *a == ANC_ON && !self.level_bits.is_empty() { self.level_bits.clone() }
                else { self.hold_bits.iter().filter(|x| x.0 == *a).map(|x| x.1).collect() };
            bits.iter().any(|b| mask >> b & 1 == 1)
        }).collect()
    }

    pub(crate) fn hold_type(&self, side: u8) -> u8 {
        if !self.per_bud || self.shared_mask { HOLD_TYPE_SHARED } else if side == 1 { HOLD_TYPE_LEFT } else { HOLD_TYPE_RIGHT }
    }

    pub(crate) fn hold_types(&self) -> Vec<u8> { if self.per_bud && !self.shared_mask { vec![HOLD_TYPE_LEFT, HOLD_TYPE_RIGHT] } else { vec![HOLD_TYPE_SHARED] } }

    /// A per-bud hold's non-noise choice that a `fn` stands for.
    pub(crate) fn hold_choice_for(&self, f: u8) -> Option<usize> {
        self.hold_choices.iter().filter(|b| **b != HOLD_NOISE)
            .filter_map(|b| ACTIONS.iter().position(|a| a.3 == *b)).find(|a| self.fn_byte(*a) == f)
    }
}

/// The hold's options and bits: the model's top-level noise modes in its order; None if a mode has no action.
/// With `level_bits` (Buds Pro), the levels 3 / 4 / 7 become one "ANC".
pub(crate) fn hold_bits(json: &Value, mut level_bits: Option<&mut Vec<u8>>) -> Option<Vec<(usize, u8)>> {
    let mut bits: Vec<(usize, u8)> = Vec::new();
    for mode in json["ancModes"].as_array()? {
        if mode["decideByEarDevice"].as_bool().unwrap_or(false) { continue; }
        let (t, bit) = (mode["mode"].as_i64()?, mode["slot"].as_u64()? as u8);
        if let Some(l) = level_bits.as_deref_mut().filter(|_| [3, 4, 7].contains(&t)) {
            l.push(bit);
            if !bits.iter().any(|b| b.0 == ANC_ON) { bits.push((ANC_ON, bit)); }
            continue;
        }
        let a = ACTIONS.iter().position(|a| a.4 == t)?;
        match bits.iter_mut().find(|b| b.0 == a) { Some(b) => b.1 = bit, None => bits.push((a, bit)) }
    }
    (!bits.is_empty()).then_some(bits)
}

/// The slot a binding lives in: any group of that bud but on-call (a gesture's group is not fixed).
pub(crate) fn entry(s: &Snapshot, dev: u8, act: u8) -> Option<[u8; 4]> {
    s.keyfn.as_ref()?.iter().find(|e| e[0] == dev && e[2] == act && e[1] != BUTTON_ON_CALL).copied()
}

pub(crate) fn mask(s: &Snapshot, kind: u8) -> Option<u32> { s.hold_masks.iter().find(|x| x.0 == kind).map(|x| x.1) }

/// What a row is bound to on `side`, None = unknown (not read, or a `fn` this row does not offer).
pub(crate) fn current(s: &Snapshot, m: &Model, side: u8, g: usize) -> Option<Vec<usize>> {
    s.keyfn.as_ref()?;
    if g != TAP_HOLD {
        let f = entry(s, side, GESTURES[g].1)?[3];
        return m.options(g).iter().find(|a| m.fn_byte(**a) == f).map(|a| vec![*a]);
    }
    if m.per_bud {
        let f = entry(s, side, 4)?[3];
        return if f == 0x08 { Some(m.hold_actions(mask(s, m.hold_type(side))?)) } else { m.hold_choice_for(f).map(|a| vec![a]) };
    }
    // Not bound on either bud: nothing selected. A bound hold needs its mask.
    if ![1, 2].iter().any(|d| entry(s, *d, 4).is_some_and(|e| e[3] == 0x08)) { return Some(vec![]); }
    Some(m.hold_actions(mask(s, HOLD_TYPE_SHARED)?))
}

/// An on-call row's state: Some(on), None unknown.
/// The both-buds hold: its own `dev 04` entry, game mode (`11`) or none.
pub(crate) fn both_hold_on(s: &Snapshot) -> Option<bool> {
    let e = s.keyfn.as_ref()?.iter().find(|e| e[0] == 4 && e[1] == BUTTON_PRIMARY && e[2] == 4)?;
    match e[3] { 0 => Some(false), 0x11 => Some(true), _ => None }
}

pub(crate) fn call_on(s: &Snapshot, i: usize) -> Option<bool> {
    let e = s.keyfn.as_ref()?.iter().find(|e| e[1] == BUTTON_ON_CALL && e[2] == ON_CALL[i].2)?;
    match e[3] { 0 => Some(false), f if f == ON_CALL[i].3 => Some(true), _ => None }
}

/// The writes of a row's selection (`GestureActivity.writeToBuds`). The hold's empty selection sends nothing: `fn 00`
/// does not stop the cycle (measured). A shared hold's bind goes to both buds; the mask is one write.
pub(crate) fn write_cmds(s: &Snapshot, m: &Model, side: u8, g: usize, actions: &[usize]) -> Vec<Cmd> {
    if g == TAP_HOLD && actions.is_empty() { return vec![]; }
    let sides: Vec<u8> = if g == TAP_HOLD && !m.per_bud { vec![1, 2] } else { vec![side] };
    let f = actions.first().map_or(0, |x| m.fn_byte(*x));
    let mut out: Vec<Cmd> = sides.into_iter().map(|d| Cmd::Gesture(d, GESTURES[g].1, f)).collect();
    if g == TAP_HOLD && actions.iter().any(|x| ACTIONS[*x].4 >= 0) {
        let level = s.anc.as_deref().and_then(|x| s.modes.bit(x));
        out.push(Cmd::HoldModes(m.hold_mask(actions, level), m.hold_type(side)));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buds4_model() {
        let m = Model::parse(find_model(Some("065414"), None).unwrap());
        let gestures: Vec<usize> = m.rows.iter().map(|r| r.0).collect();
        assert!(gestures.contains(&TAP_HOLD) && gestures.contains(&0));
        // Buds 4 hold capture: ANC 1, Adaptive 11, Transparency 2, Off 0.
        let all = m.options(TAP_HOLD).to_vec();
        assert_eq!(m.hold_mask(&all, None), 1 << 1 | 1 << 11 | 1 << 2 | 1);
        assert_eq!(m.hold_actions(0x0807).len(), 4);
        let s = Snapshot { keyfn: Some(vec![[1, 1, 1, 0x01], [2, 1, 4, 0x08], [1, 6, 2, 0x1D]]), hold_masks: vec![(1, 0x0003)], ..Default::default() };
        assert_eq!(current(&s, &m, 1, 0), Some(vec![1]));
        assert_eq!(current(&s, &m, 1, TAP_HOLD).map(|v| v.len()), Some(2));
    }

    #[test]
    fn air7_pro_shared_hold_mask() {
        let m = Model::parse(find_model(Some("064C12"), None).unwrap());
        assert!(m.per_bud && m.hold_types() == vec![HOLD_TYPE_SHARED]);
        // Issue #8 log: both holds bound to the cycle, `010C 02 01` mask 0x0A = ANC (bit 3) + Transparency (bit 1).
        let s = Snapshot { keyfn: Some(vec![[1, 1, 4, 0x08], [2, 1, 4, 0x08]]), hold_masks: vec![(1, 0x000A)], ..Default::default() };
        assert_eq!(current(&s, &m, 1, TAP_HOLD).map(|v| v.len()), Some(2));
        assert_eq!(current(&s, &m, 2, TAP_HOLD).map(|v| v.len()), Some(2));
    }

    #[test]
    fn wireless6_one_button() {
        let m = Model::parse(find_model(Some("051C52"), None).unwrap());
        assert!(m.one_button && m.rows.len() == 4);
        // The button is the left bud's slot: single tap play/pause.
        let s = Snapshot { keyfn: Some(vec![[1, 1, 1, 0x01]]), ..Default::default() };
        assert_eq!(current(&s, &m, 1, 0).map(|v| v.len()), Some(1));
    }

    #[test]
    fn t110_both_hold() {
        let m = Model::parse(find_model(Some("062812"), None).unwrap());
        assert!(m.both_hold);
        // The bud's own hold (dev 1, act 4) is not the both-buds one (dev 4).
        let s = Snapshot { keyfn: Some(vec![[1, 1, 4, 0x01], [4, 1, 4, 0x11]]), ..Default::default() };
        assert_eq!(both_hold_on(&s), Some(true));
        assert_eq!(current(&s, &m, 1, 4).map(|v| v.len()), Some(1));
    }
}
