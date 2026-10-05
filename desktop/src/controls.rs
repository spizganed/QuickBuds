//! Controls (`GestureActivity`, `GestureConfig.kt`): per bud, which action each gesture does; the hold's noise
//! cycle; the on-call rows. Rows and options come from the model's HeyMelody `control` / `callControl` lists, the
//! current bindings from the buds' own table (`0x8108`) and hold cycle (`0x810C 02`), read when the page opens.
//! `fn` values are measured (PROTOCOL.md §6): never derive new ones.

use crate::protocol::*;
use crate::session::{Cmd, Snapshot};
use crate::{icons, svg, t, with_app, App, Choice, Controls, ControlsRow, MainWindow, Pick};
use serde_json::Value;
use slint::{ComponentHandle, ModelRc, VecModel};
use std::cell::RefCell;

/// (label, keyfn `act`, the `control` actions that are this row, multi-select, icon), in the phone's order.
/// The last row is the hold's noise cycle; a plain hold (`act 4`, action 4) shares its label.
const GESTURES: [(&str, u8, &[i64], bool, &str); 7] = [
    ("gesture_single", 1, &[1, 16], false, icons::TAP_SINGLE),
    ("gesture_double", 2, &[2, 17], false, icons::TAP_DOUBLE),
    ("gesture_triple", 3, &[3, 18], false, icons::TAP_TRIPLE),
    ("gesture_slide", 5, &[5], false, icons::CHEVRON_RIGHT),
    ("gesture_hold", 4, &[4], false, icons::HOLD),
    ("gesture_extra_long", 6, &[6], false, icons::HOLD),
    ("gesture_hold", 4, &[11, 20, 27], true, icons::HOLD),
];
const TAP_HOLD: usize = 6;

/// (label, short label, `fn`, `support` bit, noise `modeType` or -1). The four noise actions share `fn 08`
/// (the hold "cycles noise control"); which modes it cycles is the separate mask.
const ACTIONS: [(&str, &str, u8, i64, i64); 17] = [
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
const ANC_ON: usize = 11;
/// HeyMelody's option order.
const ORDER: [i64; 11] = [512, 4, 32, 64, 1, 8, 16, 1024, 2048, 4096, 8192];
/// The noise cycle among a per-bud hold's choices.
const HOLD_NOISE: i64 = 128;

/// (row label, enabled label, `act`, enabled `fn`, `callControl` action).
const ON_CALL: [(&str, &str, u8, u8, i64, &str); 4] = [
    ("gesture_single", "gesture_on_call_answer_end", 1, 0x1D, 32, icons::TAP_SINGLE),
    ("gesture_on_call_double_tap", "gesture_on_call_answer_end", 2, 0x1D, 29, icons::TAP_DOUBLE),
    ("gesture_on_call_double_tap", "gesture_on_call_decline", 2, 0x1C, 33, icons::TAP_DOUBLE),
    ("gesture_on_call_long_hold", "gesture_on_call_decline", 6, 0x1C, 31, icons::HOLD),
];

/// The model's gestures (`GestureModel`).
#[derive(Default)]
struct Model {
    /// (gesture, options), in [GESTURES] order.
    rows: Vec<(usize, Vec<usize>)>,
    /// Noise action -> its bit in the hold's mask (the mode's `protocolIndex`).
    hold_bits: Vec<(usize, u8)>,
    on_call: Vec<usize>,
    /// OnePlus Buds / Buds Z number previous / next track 4 / 5.
    old_track: bool,
    /// `longPressType`: each bud has its own hold, its choices in [Model::hold_choices].
    per_bud: bool,
    hold_choices: Vec<i64>,
    hold_min: usize,
    /// OnePlus Buds Pro: its ANC levels are one "ANC" option.
    level_bits: Vec<u8>,
}

impl Model {
    fn of(s: &Snapshot) -> Model {
        // Nothing detected yet = Buds 4 (as the ANC modes); a product id HeyMelody does not list gets none.
        let m = s.model.or_else(|| crate::load_settings()["product_id"].is_null().then(|| find_model(Some("065414"), None)).flatten());
        m.map(Self::parse).unwrap_or_default()
    }

    fn parse(json: &Value) -> Model {
        let name = json["name"].as_str().unwrap_or("");
        let id = json["id"].as_str().unwrap_or("");
        let control: Vec<&Value> = json["control"].as_array().into_iter().flatten().collect();
        let long_press = json["longPressType"].as_i64().unwrap_or(0);
        let mut m = Model { hold_min: 1, ..Default::default() };
        for (g, (_, _, actions, multi, _)) in GESTURES.iter().enumerate() {
            let Some(entry) = control.iter().find(|e| e["action"].as_i64().is_some_and(|a| actions.contains(&a))) else { continue };
            if *multi {
                let Some(bits) = hold_bits(json, (id == "060C14").then_some(&mut m.level_bits)) else { continue };
                m.rows.push((g, bits.iter().map(|b| b.0).collect()));
                m.hold_bits = bits;
                m.hold_min = entry["minSelectCount"].as_u64().filter(|n| *n > 0).map(|n| n as usize)
                    .unwrap_or(if name.starts_with("OnePlus") || long_press != 0 { 2 } else { 1 });
            } else {
                let support = entry["support"].as_i64().unwrap_or(0);
                let options: Vec<usize> = ORDER.iter().filter(|b| support & **b != 0)
                    .filter_map(|b| ACTIONS.iter().position(|a| a.3 == *b)).collect();
                if !options.is_empty() { m.rows.push((g, options)); }
            }
        }
        // A callControl row is only a choice if it offers None (512).
        let call: Vec<&Value> = json["callControl"].as_array().into_iter().flatten().collect();
        m.on_call = (0..ON_CALL.len()).filter(|&i| call.iter().any(|c| c["action"].as_i64() == Some(ON_CALL[i].4)
            && c["support"].as_i64().unwrap_or(0) & 512 != 0)).collect();
        m.old_track = name == "OnePlus Buds" || name == "OnePlus Buds Z";
        m.per_bud = long_press != 0;
        m.hold_choices = if long_press == 0 { vec![] } else { [512, HOLD_NOISE, 1, 8192].into_iter().filter(|b| long_press & b != 0).collect() };
        m
    }

    fn fn_byte(&self, a: usize) -> u8 {
        match a { 2 if self.old_track => 0x04, 3 if self.old_track => 0x05, _ => ACTIONS[a].2 }
    }

    fn options(&self, g: usize) -> &[usize] { self.rows.iter().find(|r| r.0 == g).map_or(&[], |r| &r.1) }

    /// The hold's mask for a selection; on Buds Pro "ANC" takes the current level's bit, else the first.
    fn hold_mask(&self, actions: &[usize], level_bit: Option<u8>) -> u32 {
        actions.iter().filter_map(|a| if *a == ANC_ON && !self.level_bits.is_empty() {
            Some(level_bit.filter(|b| self.level_bits.contains(b)).unwrap_or(self.level_bits[0]))
        } else {
            self.hold_bits.iter().find(|x| x.0 == *a).map(|x| x.1)
        }).fold(0, |m, b| m | 1 << b)
    }

    /// The selection a mask read from the buds stands for.
    fn hold_actions(&self, mask: u32) -> Vec<usize> {
        self.options(TAP_HOLD).iter().copied().filter(|a| {
            let bits: Vec<u8> = if *a == ANC_ON && !self.level_bits.is_empty() { self.level_bits.clone() }
                else { self.hold_bits.iter().filter(|x| x.0 == *a).map(|x| x.1).collect() };
            bits.iter().any(|b| mask >> b & 1 == 1)
        }).collect()
    }

    fn hold_type(&self, side: u8) -> u8 {
        if !self.per_bud { HOLD_TYPE_SHARED } else if side == 1 { HOLD_TYPE_LEFT } else { HOLD_TYPE_RIGHT }
    }

    fn hold_types(&self) -> Vec<u8> { if self.per_bud { vec![HOLD_TYPE_LEFT, HOLD_TYPE_RIGHT] } else { vec![HOLD_TYPE_SHARED] } }

    /// A per-bud hold's non-noise choice that a `fn` stands for.
    fn hold_choice_for(&self, f: u8) -> Option<usize> {
        self.hold_choices.iter().filter(|b| **b != HOLD_NOISE)
            .filter_map(|b| ACTIONS.iter().position(|a| a.3 == *b)).find(|a| self.fn_byte(*a) == f)
    }
}

/// The hold's options and bits: the model's top-level noise modes in its order; None if a mode has no action.
/// With `level_bits` (Buds Pro), the levels 3 / 4 / 7 become one "ANC".
fn hold_bits(json: &Value, mut level_bits: Option<&mut Vec<u8>>) -> Option<Vec<(usize, u8)>> {
    let mut bits: Vec<(usize, u8)> = Vec::new();
    for mode in json["noiseReductionMode"].as_array()? {
        if mode["decideByEarDevice"].as_bool().unwrap_or(false) { continue; }
        let (t, bit) = (mode["modeType"].as_i64()?, mode["protocolIndex"].as_u64()? as u8);
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

/// Where a pick goes.
#[derive(Clone, Copy)]
enum Target { Row(usize), HoldChoice, Call(usize) }

#[derive(Default)]
struct State { side: u8, target: Option<Target>, working: Vec<usize> }

thread_local! { static ST: RefCell<State> = RefCell::new(State { side: 1, ..Default::default() }); }

fn st<R>(f: impl FnOnce(&mut State) -> R) -> R { ST.with(|s| f(&mut s.borrow_mut())) }

/// The slot a binding lives in: any group of that bud but on-call (a gesture's group is not fixed).
fn entry(s: &Snapshot, dev: u8, act: u8) -> Option<[u8; 4]> {
    s.keyfn.as_ref()?.iter().find(|e| e[0] == dev && e[2] == act && e[1] != BUTTON_ON_CALL).copied()
}

fn mask(s: &Snapshot, kind: u8) -> Option<u32> { s.hold_masks.iter().find(|x| x.0 == kind).map(|x| x.1) }

/// What a row is bound to on `side`, None = unknown (not read, or a `fn` this row does not offer).
fn current(s: &Snapshot, m: &Model, side: u8, g: usize) -> Option<Vec<usize>> {
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
fn call_on(s: &Snapshot, i: usize) -> Option<bool> {
    let e = s.keyfn.as_ref()?.iter().find(|e| e[1] == BUTTON_ON_CALL && e[2] == ON_CALL[i].2)?;
    match e[3] { 0 => Some(false), f if f == ON_CALL[i].3 => Some(true), _ => None }
}

fn describe(a: &App, actions: &[usize]) -> String {
    if actions.is_empty() { return t(&a.tr, "gesture_not_set").into(); }
    actions.iter().map(|x| t(&a.tr, if ACTIONS[*x].1.is_empty() { ACTIONS[*x].0 } else { ACTIONS[*x].1 })).collect::<Vec<_>>().join(", ")
}

fn send(a: &App, c: Cmd) { let _ = a.tx.send(c); }

/// Writes a row's selection (`GestureActivity.writeToBuds`). The hold's empty selection sends nothing: `fn 00`
/// does not stop the cycle (measured). A shared hold's bind goes to both buds; the mask is one write.
fn write(a: &App, m: &Model, side: u8, g: usize, actions: &[usize]) {
    if g == TAP_HOLD && actions.is_empty() { return; }
    let sides: Vec<u8> = if g == TAP_HOLD && !m.per_bud { vec![1, 2] } else { vec![side] };
    let f = actions.first().map_or(0, |x| m.fn_byte(*x));
    for d in sides { send(a, Cmd::Gesture(d, GESTURES[g].1, f)); }
    if g == TAP_HOLD && actions.iter().any(|x| ACTIONS[*x].4 >= 0) {
        let level = a.snap.anc.as_deref().and_then(|x| a.snap.modes.bit(x));
        send(a, Cmd::HoldModes(m.hold_mask(actions, level), m.hold_type(side)));
    }
}

pub fn setup(main: &MainWindow) {
    let c = main.global::<Controls>();
    c.on_opened(|| with_app(|a| send(a, Cmd::ControlsReads(Model::of(&a.snap).hold_types()))));
    c.on_set_side(|side| with_app(|a| { st(|s| s.side = side as u8 + 1); apply(a); }));
    // 1 a single-choice list, 2 the per-bud hold's choices, 3 the hold's noise modes; 0 nothing to open.
    c.on_open_row(|i| with_app_ret(|a| {
        let m = Model::of(&a.snap);
        let Some(&(g, _)) = m.rows.get(i as usize) else { return 0 };
        let side = st(|s| s.side);
        let cur = current(&a.snap, &m, side, g);
        let c = a.main.global::<Controls>();
        c.set_dialog_title(t(&a.tr, GESTURES[g].0).into());
        if g == TAP_HOLD && m.per_bud {
            st(|s| s.target = Some(Target::HoldChoice));
            let noise = cur.as_ref().is_some_and(|c| c.iter().any(|x| ACTIONS[*x].4 >= 0));
            let items: Vec<Choice> = m.hold_choices.iter().filter_map(|b| if *b == HOLD_NOISE {
                Some(Choice { value: -1, label: t(&a.tr, "anc_section").into() })
            } else {
                ACTIONS.iter().position(|x| x.3 == *b).map(|x| Choice { value: x as i32, label: t(&a.tr, ACTIONS[x].0).into() })
            }).collect();
            let selected = if noise { -1 } else { cur.and_then(|c| (c.len() == 1).then(|| c[0] as i32)).unwrap_or(-2) };
            c.set_choices(ModelRc::new(VecModel::from(items)));
            c.set_choice_current(selected);
            return 2;
        }
        if g == TAP_HOLD { open_noise(a, &m, cur.unwrap_or_default()); return 3; }
        st(|s| s.target = Some(Target::Row(g)));
        c.set_choices(ModelRc::new(VecModel::from(m.options(g).iter()
            .map(|x| Choice { value: *x as i32, label: t(&a.tr, ACTIONS[*x].0).into() }).collect::<Vec<_>>())));
        c.set_choice_current(cur.and_then(|c| c.first().map(|x| *x as i32)).unwrap_or(-2));
        1
    }));
    c.on_open_call(|i| with_app(|a| {
        let m = Model::of(&a.snap);
        let Some(&k) = m.on_call.get(i as usize) else { return };
        st(|s| s.target = Some(Target::Call(k)));
        let c = a.main.global::<Controls>();
        c.set_dialog_title(t(&a.tr, ON_CALL[k].0).into());
        c.set_choices(ModelRc::new(VecModel::from(vec![
            Choice { value: 0, label: t(&a.tr, "gesture_action_none").into() },
            Choice { value: 1, label: t(&a.tr, ON_CALL[k].1).into() },
        ])));
        c.set_choice_current(call_on(&a.snap, k).map_or(-2, |on| on as i32));
    }));
    // Returns 3 when the noise modes open next.
    c.on_picked(|v| with_app_ret(|a| {
        let m = Model::of(&a.snap);
        let side = st(|s| s.side);
        match st(|s| s.target) {
            Some(Target::Row(g)) => write(a, &m, side, g, &[v as usize]),
            Some(Target::HoldChoice) if v == -1 => {
                let cur = current(&a.snap, &m, side, TAP_HOLD).unwrap_or_default();
                open_noise(a, &m, cur.into_iter().filter(|x| ACTIONS[*x].4 >= 0).collect());
                return 3;
            }
            Some(Target::HoldChoice) => write(a, &m, side, TAP_HOLD, &[v as usize]),
            Some(Target::Call(k)) => send(a, Cmd::OnCall(ON_CALL[k].2, if v == 1 { ON_CALL[k].3 } else { 0 })),
            None => {}
        }
        0
    }));
    // Never below the model's minimum, as HeyMelody.
    c.on_toggle(|v| with_app(|a| {
        let m = Model::of(&a.snap);
        st(|s| {
            let v = v as usize;
            if s.working.contains(&v) { if s.working.len() > m.hold_min { s.working.retain(|x| *x != v); } } else { s.working.push(v); }
        });
        paint_noise(a, &m);
    }));
    // In the list's order, so the cycle matches what was shown; below the minimum (a never-set hold) nothing is sent.
    c.on_done(|| with_app(|a| {
        let m = Model::of(&a.snap);
        let working = st(|s| s.working.clone());
        if working.len() >= m.hold_min {
            let ordered: Vec<usize> = m.options(TAP_HOLD).iter().copied().filter(|x| working.contains(x)).collect();
            write(a, &m, st(|s| s.side), TAP_HOLD, &ordered);
        }
    }));
}

/// [with_app] for a callback that returns a value.
fn with_app_ret(f: impl FnOnce(&mut App) -> i32) -> i32 {
    let mut out = 0;
    with_app(|a| out = f(a));
    out
}

fn open_noise(a: &App, m: &Model, cur: Vec<usize>) {
    st(|s| s.working = cur);
    a.main.global::<Controls>().set_dialog_title(t(&a.tr, "gesture_hold").into());
    paint_noise(a, m);
}

fn paint_noise(a: &App, m: &Model) {
    let working = st(|s| s.working.clone());
    let c = a.main.global::<Controls>();
    c.set_picks(ModelRc::new(VecModel::from(m.options(TAP_HOLD).iter()
        .map(|x| Pick { value: *x as i32, label: t(&a.tr, ACTIONS[*x].0).into(), on: working.contains(x) }).collect::<Vec<_>>())));
    c.set_pick_message(if working.len() == 1 && m.hold_min == 1 { t(&a.tr, "gesture_hold_rule").into() } else { "".into() });
}

/// Buds with a gesture table to edit (`EarbudSettingsActivity`'s gestures row).
pub fn available(s: &Snapshot) -> bool { s.caps.supports(CMD_SET_KEY_FUNCTION) && !Model::of(s).rows.is_empty() }

pub fn apply(a: &App) {
    let s = &a.snap;
    let m = Model::of(s);
    let side = st(|x| x.side);
    let rows: Vec<ControlsRow> = m.rows.iter().map(|(g, _)| ControlsRow {
        icon: svg(GESTURES[*g].4), title: t(&a.tr, GESTURES[*g].0).into(),
        value: current(s, &m, side, *g).map_or("—".into(), |c| describe(a, &c)).into(),
    }).collect();
    let calls: Vec<ControlsRow> = m.on_call.iter().map(|k| ControlsRow {
        icon: svg(ON_CALL[*k].5), title: t(&a.tr, ON_CALL[*k].0).into(),
        value: call_on(s, *k).map_or("—", |on| t(&a.tr, if on { ON_CALL[*k].1 } else { "gesture_action_none" })).into(),
    }).collect();
    let c = a.main.global::<Controls>();
    c.set_rows(ModelRc::new(VecModel::from(rows)));
    c.set_calls(ModelRc::new(VecModel::from(calls)));
    c.set_side(side as i32 - 1);
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
}
