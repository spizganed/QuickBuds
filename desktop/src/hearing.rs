//! Hearing profile (`GoldenSoundActivity`, `GoldenTestSheet`): the switch, the profiles kept on this PC (a click
//! applies one, the one on the buds has the outline), the radar of the active one, and the hearing test.
//! The buds hold only the active record; it is read when the page opens and added to the list.

use crate::protocol::*;
use crate::session::{Cmd, HEARING};
use crate::{load_settings, save_setting, t, with_app, App, EqRow, Hearing, MainWindow, RadarLabel};
use serde_json::{json, Value};
use slint::{ComponentHandle, ModelRc, Timer, TimerMode, VecModel};
use std::cell::RefCell;
use std::collections::HashMap;
use std::time::{Duration, Instant};

#[derive(Clone)]
struct Record { uid: u32, name: String, values: [i8; 12], scan: Vec<u8>, desc: u8 }

impl Record {
    fn to_json(&self) -> Value {
        json!({ "uid": self.uid, "name": self.name, "values": self.values.to_vec(), "scan": hex(&self.scan).replace(' ', ""), "desc": self.desc })
    }
    fn from_json(v: &Value) -> Option<Record> {
        let values: Vec<i8> = v["values"].as_array()?.iter().filter_map(|x| x.as_i64().map(|x| x as i8)).collect();
        let s = v["scan"].as_str().unwrap_or("");
        let scan = (0..s.len() / 2).filter_map(|i| u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).ok()).collect();
        Some(Record { uid: v["uid"].as_u64()? as u32, name: v["name"].as_str()?.into(), values: values.try_into().ok()?, scan, desc: v["desc"].as_u64().unwrap_or(0) as u8 })
    }
}

/// `settings.json` "hearing_profiles", newest first, at most 10 (as HeyMelody keeps per buds).
fn records() -> Vec<Record> {
    load_settings()["hearing_profiles"].as_array().into_iter().flatten().filter_map(Record::from_json).collect()
}

fn save_records(list: &[Record]) { save_setting("hearing_profiles", list.iter().map(Record::to_json).collect::<Vec<_>>().into()); }

/// Adds or replaces (same uid) a record.
fn put(r: Record) {
    let mut list = records();
    list.retain(|x| x.uid != r.uid);
    list.insert(0, r);
    list.truncate(10);
    save_records(&list);
}

#[derive(Clone, Copy, PartialEq, Default)]
enum Step { #[default] Idle, Scan, Test, Finish, Done }

/// What the test dialog's main button does.
#[derive(Clone, Copy, Default)]
enum Button { #[default] Start, Next, Again, None }

#[derive(Default)]
struct State {
    active: u32,
    hearing: HashMap<u32, (Vec<f32>, Vec<f32>)>,
    scan: HashMap<u32, Curves>,
    plot: (f32, f32),
    // The test
    step: Step,
    button: Button,
    uid: u32,
    index: usize,
    values: [i8; 12],
    scan_data: Vec<u8>,
    warned: bool,
    last_tone: Option<Instant>,
    toned: Option<usize>,
    timeout: Timer,
    tone: Timer,
}

thread_local! { static ST: RefCell<State> = RefCell::new(State::default()); }

fn st<R>(f: impl FnOnce(&mut State) -> R) -> R { ST.with(|s| f(&mut s.borrow_mut())) }

fn send(a: &App, frames: Vec<Frame>) { let _ = a.tx.send(Cmd::Hearing(frames)); }

/// A random number (no RNG crate): the std hasher's per-process random keys over the time.
fn random() -> u64 {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos()));
    h.finish()
}

/// HeyMelody's record name, the local time "2026/09/29 01:53".
fn now_name() -> String {
    let [y, mo, d, h, mi, ..] = crate::local_now();
    format!("{y:04}/{mo:02}/{d:02} {h:02}:{mi:02}")
}

pub fn setup(main: &MainWindow) {
    let h = main.global::<Hearing>();
    // The record on the buds, so one made in HeyMelody or on the phone shows up too.
    h.on_opened(|| with_app(|a| send(a, vec![(CMD_HEARING_ACTIVE, vec![]), (CMD_HEARING_ACTIVE_SCAN, vec![])])));
    h.on_set_on(|on| with_app(|a| { let _ = a.tx.send(Cmd::Features(vec![(FEATURE_HEARING, on)])); }));
    h.on_pick(|i| with_app(|a| if let Some(r) = records().get(i as usize).cloned() { apply_record(a, r); }));
    h.on_delete(|i| with_app(|a| {
        let mut list = records();
        if (i as usize) < list.len() { list.remove(i as usize); }
        save_records(&list);
        paint(a);
    }));
    h.on_set_ear(|e| with_app(|a| { a.main.global::<Hearing>().set_ear(e); redraw(a); }));
    h.on_radar_resized(|w, hh| with_app(|a| { st(|s| s.plot = (w, hh)); redraw(a); }));
    h.on_test_open(|| with_app(|a| { st(|s| s.step = Step::Idle); show(a, "golden_test_title", Some("golden_prepare"), Button::Start, false); }));
    h.on_test_action(|| with_app(|a| match st(|s| s.button) {
        Button::Start | Button::Again => start(a),
        Button::Next => next(a),
        Button::None => {}
    }));
    h.on_test_moved(|v| with_app(|a| {
        if st(|s| s.toned == Some(v as usize)) { return; }
        tone_soon(a);
    }));
    // Closed while the enhance type was being read: the result is still kept.
    h.on_test_closed(|| with_app(|a| if st(|s| s.step) == Step::Finish { save(a, 0) } else { abort(a) }));
}

/// The test dialog's texts; `message` None keeps the current one.
fn show(a: &App, title: &str, message: Option<&str>, button: Button, slider: bool) {
    let h = a.main.global::<Hearing>();
    h.set_test_title(t(&a.tr, title).into());
    if let Some(m) = message { h.set_test_message(if m.is_empty() { "".into() } else { t(&a.tr, m).into() }); }
    h.set_test_button(match button {
        Button::Start => t(&a.tr, "golden_start").into(),
        Button::Again => t(&a.tr, "golden_again").into(),
        Button::Next => t(&a.tr, if st(|s| s.index) < 11 { "golden_next" } else { "golden_finish" }).into(),
        Button::None => "…".into(),
    });
    h.set_test_done(false);
    h.set_test_slider(slider);
    st(|s| s.button = button);
}

fn start(a: &App) {
    let w = a.snap.wear;
    if ![w[0], w[1]].iter().all(|x| *x == 3 || *x == 7) {
        a.main.global::<Hearing>().set_test_title(t(&a.tr, "fit_insert").into());
        return;
    }
    let uid = (random() % 0x7FFF_FFFE) as u32 + 1;
    st(|s| { s.uid = uid; s.scan_data.clear(); s.warned = false; });
    // Ear scan only where HeyMelody's model list has it (`earScan`).
    let scan = a.snap.model.is_some_and(|m| m["earScan"] == 1) && a.snap.caps.supports(CMD_HEARING_SCAN_DATA);
    if scan {
        st(|s| s.step = Step::Scan);
        show(a, "golden_scanning", Some("golden_scan_hint"), Button::None, false);
        send(a, vec![ear_scan(true, uid)]);
        st(|s| s.timeout.start(TimerMode::SingleShot, Duration::from_secs(15), || with_app(|a| {
            if st(|s| s.step) == Step::Scan { stop(a, "golden_scan_failed"); }
        })));
    } else {
        send(a, vec![hearing_test(true)]);
        begin(a, 0);
    }
}

fn begin(a: &App, i: usize) {
    st(|s| { s.step = Step::Test; s.index = i; s.toned = None; });
    a.main.global::<Hearing>().set_test_stop(HEARING_START as i32);
    let step = t(&a.tr, "golden_step").replace("%1$d", &(i % 6 + 1).to_string());
    show(a, if i < 6 { "golden_left" } else { "golden_right" }, Some(""), Button::Next, true);
    a.main.global::<Hearing>().set_test_message(step.into());
    play_tone(a);
}

fn next(a: &App) {
    if st(|s| s.step) != Step::Test { return; }
    let stop = a.main.global::<Hearing>().get_test_stop() as usize;
    let i = st(|s| { s.values[s.index] = hearing_snap(HEARING_STOPS[stop.min(24)]); s.index });
    if i < 11 { return begin(a, i + 1); }
    let (uid, values) = st(|s| { s.step = Step::Finish; s.tone.stop(); (s.uid, s.values) });
    show(a, "golden_creating", Some(""), Button::None, false);
    send(a, vec![hearing_tone_stop(), hearing_test(false), hearing_filter(uid, &values)]);
    st(|s| s.timeout.start(TimerMode::SingleShot, Duration::from_secs(3), || with_app(|a| {
        if st(|s| s.step) == Step::Finish { save(a, 0); }
    })));
}

/// HeyMelody's description id: random within the enhance type's group (0 low 1..6, 1 middle 7..12, else 13..20).
fn desc_id(enhance: u8) -> u8 {
    let (lo, n) = match enhance { 0 => (1, 6), 1 => (7, 6), _ => (13, 8) };
    lo + (random() % n) as u8
}

fn save(a: &App, desc: u8) {
    let r = st(|s| { s.step = Step::Done; s.timeout.stop(); Record { uid: s.uid, name: now_name(), values: s.values, scan: s.scan_data.clone(), desc } });
    put(r.clone());
    apply_record(a, r);
    show(a, "golden_saved", Some("golden_saved_msg"), Button::None, false);
    let h = a.main.global::<Hearing>();
    h.set_test_button(t(&a.tr, "gesture_done").into());
    h.set_test_done(true);
}

/// At most one tone every 300 ms while dragging; the last position always plays.
fn tone_soon(a: &App) {
    if st(|s| s.step) != Step::Test { return; }
    let wait = st(|s| s.last_tone.map_or(0, |t| 300u64.saturating_sub(t.elapsed().as_millis() as u64)));
    if wait == 0 { return play_tone(a); }
    st(|s| s.tone.start(TimerMode::SingleShot, Duration::from_millis(wait), || with_app(|a| play_tone(a))));
}

fn play_tone(a: &App) {
    if st(|s| s.step) != Step::Test { return; }
    let stop = (a.main.global::<Hearing>().get_test_stop() as usize).min(24);
    let (i, warn) = st(|s| {
        s.last_tone = Some(Instant::now());
        s.toned = Some(stop);
        let warn = stop >= HEARING_LOUD && !s.warned;
        s.warned |= warn;
        (s.index, warn)
    });
    if warn { a.main.global::<Hearing>().set_test_message(t(&a.tr, "golden_loud").into()); }
    send(a, vec![hearing_tone_stop(), hearing_tone(i as u8 / 6 + 1, i as u8 % 6 + 1, HEARING_STOPS[stop])]);
}

fn stop(a: &App, reason: &str) {
    abort(a);
    show(a, "golden_stopped", Some(reason), Button::Again, false);
}

/// Stops whatever runs on the buds.
fn abort(a: &App) {
    let (step, uid) = st(|s| { s.tone.stop(); s.timeout.stop(); (s.step, s.uid) });
    match step {
        Step::Scan => send(a, vec![ear_scan(false, uid)]),
        Step::Test => send(a, vec![hearing_tone_stop(), hearing_test(false)]),
        _ => {}
    }
    if step != Step::Done { st(|s| s.step = Step::Idle); }
}

/// Applies a record as HeyMelody does (description id, record, ear scan, switch on) and shows its radar.
fn apply_record(a: &App, r: Record) {
    send(a, hearing_apply(r.uid, &r.name, &r.values, &r.scan, r.desc));
    st(|s| s.active = r.uid);
    paint(a);
    request_curves(a);
}

/// Asks the buds for the active record's filters (queries only), unless they are known.
fn request_curves(a: &App) {
    let uid = st(|s| s.active);
    let Some(r) = records().into_iter().find(|r| r.uid == uid) else { return };
    if st(|s| s.hearing.contains_key(&uid)) { return redraw(a); }
    let mut frames = vec![hearing_filter(r.uid, &r.values)];
    if !r.scan.is_empty() { frames.push(hearing_scan_filter(r.uid, &r.scan)); }
    send(a, frames);
}

fn event(a: &App, e: HearingEv) {
    match e {
        HearingEv::Status(kind, status) => {
            if !matches!(st(|s| s.step), Step::Scan | Step::Test) { return; }
            match status {
                1 | 3 => stop(a, "golden_stopped_music"),
                5 => stop(a, "golden_stopped_wear"),
                7 | 10 | 255 if kind == 4 => stop(a, "golden_scan_failed"),
                _ => {}
            }
        }
        // The scan is done: stop it and start the hearing test.
        HearingEv::EarScan(uid, data) => {
            if st(|s| s.step != Step::Scan || s.uid != uid) { return; }
            st(|s| { s.timeout.stop(); s.scan_data = data; });
            send(a, vec![ear_scan(false, uid), hearing_test(true)]);
            begin(a, 0);
        }
        HearingEv::Filter(uid, enhance, curves) => {
            if let Some((_, l, r)) = curves { st(|s| s.hearing.insert(uid, (l, r))); }
            if st(|s| s.step == Step::Finish && s.uid == uid) { save(a, desc_id(enhance)); }
        }
        HearingEv::ScanCurves(uid, c) => { st(|s| s.scan.insert(uid, c)); }
        HearingEv::Active(uid, name, values) => {
            st(|s| s.active = uid);
            if !records().iter().any(|r| r.uid == uid) { put(Record { uid, name, values, scan: vec![], desc: 0 }); }
            // After the ear-scan read (right behind this one) has filled the record in.
            Timer::single_shot(Duration::from_millis(600), || with_app(|a| request_curves(a)));
        }
        HearingEv::ActiveScan(uid, data) => {
            let mut list = records();
            if let Some(r) = list.iter_mut().find(|r| r.uid == uid && r.scan.is_empty()) { r.scan = data; save_records(&list); }
        }
    }
}

fn paint(a: &App) {
    let list = records();
    let active = st(|s| s.active);
    let h = a.main.global::<Hearing>();
    h.set_profiles(ModelRc::new(VecModel::from(list.iter().enumerate()
        .map(|(i, r)| EqRow { id: i as i32, name: r.name.as_str().into() }).collect::<Vec<_>>())));
    h.set_active(list.iter().position(|r| r.uid == active).map_or(-1, |i| i as i32));
    h.set_delete_names(ModelRc::new(VecModel::from(list.iter()
        .map(|r| t(&a.tr, "eq_delete_confirm").replace("%1$s", &r.name).into()).collect::<Vec<slint::SharedString>>())));
    redraw(a);
}

pub fn apply(a: &App) {
    let events: Vec<HearingEv> = std::mem::take(&mut *HEARING.lock().unwrap());
    for e in events { event(a, e); }
    let s = &a.snap;
    let h = a.main.global::<Hearing>();
    h.set_on(s.features.iter().any(|f| f.0 == FEATURE_HEARING && f.1 == 1));
    h.set_has_test(s.caps.supports(CMD_GOLDEN_DETECT));
    paint(a);
}

/// `settings.json` "hearing_radar": the last radar drawn, `[uid, left, right]`, shown until the buds' filters arrive.
fn radii(active: u32) -> Option<[[f32; 6]; 2]> {
    let (h, sc) = st(|s| (s.hearing.get(&active).cloned(), s.scan.get(&active).cloned()));
    if let Some((l, r)) = h {
        let both = [hearing_radar(&l, sc.as_ref().map(|c| (&c.1[..], c.0))), hearing_radar(&r, sc.as_ref().map(|c| (&c.2[..], c.0)))];
        let saved = load_settings()["hearing_radar"].clone();
        let fresh = json!([active, both[0].to_vec(), both[1].to_vec()]);
        if saved != fresh { save_setting("hearing_radar", fresh); }
        return Some(both);
    }
    let v = load_settings()["hearing_radar"].clone();
    if v[0].as_u64() != Some(active as u64) || active == 0 { return None; }
    let ear = |i: usize| -> Option<[f32; 6]> {
        v[i].as_array()?.iter().map(|x| x.as_f64().map(|x| x as f32)).collect::<Option<Vec<_>>>()?.try_into().ok()
    };
    Some([ear(1)?, ear(2)?])
}

/// HeyMelody's radar, as path commands in the plot's own pixels: the grid, the rim ("Boost") and the profile.
fn radar_paths(values: Option<[f32; 6]>, (w, h): (f32, f32)) -> (String, String, String, Vec<RadarLabel>) {
    let (cx, cy, rad) = (w / 2.0, h / 2.0, w.min(h) / 2.0 - 40.0);
    let point = |i: usize, r: f32| {
        let a = -2.0 * std::f32::consts::PI / 3.0 + i as f32 * std::f32::consts::PI / 3.0;
        (cx + a.cos() * rad * r / 10.0, cy + a.sin() * rad * r / 10.0)
    };
    let poly = |v: [f32; 6]| {
        let mut d = String::new();
        for (i, r) in v.iter().enumerate() { let (x, y) = point(i, *r); d += &format!("{} {x:.1} {y:.1} ", if i == 0 { "M" } else { "L" }); }
        d + "Z"
    };
    let mut grid: String = [2.5, 5.0, 7.5].iter().map(|r| poly([*r; 6]) + " ").collect();
    for i in 0..6 { let (x, y) = point(i, 10.0); grid += &format!("M {cx:.1} {cy:.1} L {x:.1} {y:.1} "); }
    let labels = HEARING_AXES.iter().enumerate().map(|(i, f)| {
        let (x, y) = point(i, 12.2);
        let text = if *f >= 1000 { format!("{} kHz", (*f as f32 / 1000.0).to_string()) } else { format!("{f} Hz") };
        RadarLabel { x, y, text: text.into() }
    }).collect();
    (grid, poly([10.0; 6]), values.map(poly).unwrap_or_default(), labels)
}

fn redraw(a: &App) {
    let h = a.main.global::<Hearing>();
    let (active, plot) = st(|s| (s.active, s.plot));
    let both = radii(active);
    h.set_has_radar(both.is_some());
    if plot.0 <= 60.0 || plot.1 <= 60.0 { return; }
    let (grid, rim, shape, labels) = radar_paths(both.map(|b| b[h.get_ear().clamp(0, 1) as usize]), plot);
    let (dots_on, scale) = crate::STYLE.get();
    let p = a.main.global::<crate::Palette>();
    let dots = |d: &str, c| if dots_on { crate::dots::curve(d, plot, c, scale) } else { Default::default() };
    h.set_grid_dots(dots(&grid, p.get_outline()));
    h.set_rim_dots(dots(&rim, p.get_text2()));
    h.set_shape_dots(dots(&shape, p.get_accent()));
    h.set_grid(grid.into());
    h.set_rim(rim.into());
    h.set_shape(shape.into());
    h.set_labels(ModelRc::new(VecModel::from(labels)));
}

#[cfg(test)]
mod tests {
    #[test]
    fn records_roundtrip() {
        let r = super::Record { uid: 7, name: "2026/10/04 12:00".into(), values: [-5; 12], scan: vec![0xA8, 1], desc: 3 };
        let back = super::Record::from_json(&r.to_json()).unwrap();
        assert_eq!((back.uid, back.name, back.values, back.scan, back.desc), (7, r.name, r.values, r.scan, 3));
        assert!((1..=6).contains(&super::desc_id(0)) && (13..=20).contains(&super::desc_id(2)));
    }
}
