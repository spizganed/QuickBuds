//! Hearing profile: the profiles kept on this PC, the radar of the active one and the hearing test.
//! The rules are in `hearing_logic.rs`; the screen is the web page.

use crate::cmd;
use crate::hearing_logic::*;
use crate::link::Link;
use crate::session::{Cmd, Snapshot, HEARING};
use crate::tr;
use crate::types::*;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};

#[derive(Clone, Copy, PartialEq, Default)]
enum Step { #[default] Idle, Scan, Test, Finish, Done }

/// What the test dialog's main button does.
#[derive(Clone, Copy, PartialEq, Default)]
enum Button { #[default] Start, Next, Again, None }

#[derive(Default)]
struct State {
    active: u32,
    hearing: HashMap<u32, (Vec<f32>, Vec<f32>)>,
    scan: HashMap<u32, Curves>,
    // The test
    step: Step,
    button: Button,
    uid: u32,
    index: usize,
    stop: usize,
    values: [i8; 12],
    scan_data: Vec<u8>,
    warned: bool,
    last_tone: Option<Instant>,
    toned: Option<usize>,
    /// Counts the timer starts and stops: a sleeping timer fires only if its number is still the current one.
    timeout_gen: u64,
    tone_gen: u64,
    // The dialog's texts.
    title: String,
    message: String,
    label: String,
    slider: bool,
    done: bool,
}

fn st<R>(f: impl FnOnce(&mut State) -> R) -> R {
    static S: OnceLock<Mutex<State>> = OnceLock::new();
    f(&mut S.get_or_init(Default::default).lock().unwrap())
}

static APP: OnceLock<AppHandle> = OnceLock::new();

pub fn init(app: &AppHandle) { let _ = APP.set(app.clone()); }

fn send(frames: Vec<Frame>) {
    if let Some(l) = APP.get().and_then(|a| a.try_state::<Link>()) { l.send_cmd(Cmd::Hearing(frames)); }
}

fn snapshot() -> Snapshot { APP.get().and_then(|a| a.try_state::<Link>()).map(|l| l.snapshot()).unwrap_or_default() }

/// Sends the page its new state.
fn changed() { if let Some(a) = APP.get() { let _ = a.emit("hearing", json_state()); } }

fn after(ms: u64, f: impl FnOnce() + Send + 'static) {
    std::thread::spawn(move || { std::thread::sleep(Duration::from_millis(ms)); f(); });
}

/// The dialog's texts; `message` None keeps the current one.
fn show(title: &str, message: Option<&str>, button: Button, slider: bool) {
    st(|s| {
        s.title = tr(title);
        if let Some(m) = message { s.message = if m.is_empty() { String::new() } else { tr(m) }; }
        s.label = match button {
            Button::Start => tr("golden_start"),
            Button::Again => tr("golden_again"),
            Button::Next => tr(if s.index < 11 { "golden_next" } else { "golden_finish" }),
            Button::None => "…".into(),
        };
        s.done = false;
        s.slider = slider;
        s.button = button;
    });
}

fn start() {
    let s = snapshot();
    let blind = !crate::wear_sensor_seen(&s);
    if ![s.wear[0], s.wear[1]].iter().all(|x| matches!(x, 3 | 7) || blind && matches!(x, 1 | 5)) {
        st(|t| t.title = tr("fit_insert"));
        return changed();
    }
    let uid = (random() % 0x7FFF_FFFE) as u32 + 1;
    st(|t| { t.uid = uid; t.scan_data.clear(); t.warned = false; });
    // Ear scan only where the model list has it (`hearingScan`).
    if s.model.is_some_and(|m| m["hearingScan"] == 1) && s.caps.supports(CMD_HEARING_SCAN_DATA) {
        st(|t| t.step = Step::Scan);
        show("golden_scanning", Some("golden_scan_hint"), Button::None, false);
        send(vec![cmd::ear_scan(true, uid)]);
        let gen = st(|t| { t.timeout_gen += 1; t.timeout_gen });
        after(15_000, move || { if st(|t| t.timeout_gen == gen && t.step == Step::Scan) { stop_test("golden_scan_failed"); } });
    } else {
        send(vec![cmd::hearing_test(true)]);
        begin(0);
    }
    changed();
}

fn begin(i: usize) {
    st(|t| { t.step = Step::Test; t.index = i; t.toned = None; t.stop = HEARING_START; });
    let step = tr("golden_step").replace("%1$d", &(i % 6 + 1).to_string());
    show(if i < 6 { "golden_left" } else { "golden_right" }, Some(""), Button::Next, true);
    st(|t| t.message = step);
    play_tone();
}

fn next() {
    if st(|t| t.step) != Step::Test { return; }
    let i = st(|t| { t.values[t.index] = hearing_snap(HEARING_STOPS[t.stop.min(24)]); t.index });
    if i < 11 { begin(i + 1); return changed(); }
    let (uid, values) = st(|t| { t.step = Step::Finish; t.tone_gen += 1; (t.uid, t.values) });
    show("golden_creating", Some(""), Button::None, false);
    send(vec![cmd::tone_stop(), cmd::hearing_test(false), cmd::golden_filter(uid, &values)]);
    let gen = st(|t| { t.timeout_gen += 1; t.timeout_gen });
    after(3_000, move || { if st(|t| t.timeout_gen == gen && t.step == Step::Finish) { save(0); } });
    changed();
}

fn save(desc: u8) {
    let r = st(|t| { t.step = Step::Done; t.timeout_gen += 1; Record { uid: t.uid, name: now_name(), values: t.values, scan: t.scan_data.clone(), desc } });
    put(r.clone());
    apply_record(r);
    show("golden_saved", Some("golden_saved_msg"), Button::None, false);
    st(|t| { t.label = tr("gesture_done"); t.done = true; });
    changed();
}

/// At most one tone every 300 ms while dragging; the last position always plays.
fn tone_soon() {
    if st(|t| t.step) != Step::Test { return; }
    let wait = st(|t| t.last_tone.map_or(0, |l| 300u64.saturating_sub(l.elapsed().as_millis() as u64)));
    if wait == 0 { return play_tone(); }
    let gen = st(|t| { t.tone_gen += 1; t.tone_gen });
    after(wait, move || { if st(|t| t.tone_gen == gen) { play_tone(); } });
}

fn play_tone() {
    if st(|t| t.step) != Step::Test { return; }
    let (i, stop, warn) = st(|t| {
        let stop = t.stop.min(24);
        t.last_tone = Some(Instant::now());
        t.toned = Some(stop);
        let warn = stop >= HEARING_LOUD && !t.warned;
        t.warned |= warn;
        (t.index, stop, warn)
    });
    if warn { st(|t| t.message = tr("golden_loud")); changed(); }
    send(vec![cmd::tone_stop(), cmd::tone(i as u8 / 6 + 1, i as u8 % 6 + 1, HEARING_STOPS[stop])]);
}

fn stop_test(reason: &str) {
    abort();
    show("golden_stopped", Some(reason), Button::Again, false);
    changed();
}

/// Stops whatever runs on the buds.
fn abort() {
    let (step, uid) = st(|t| { t.tone_gen += 1; t.timeout_gen += 1; (t.step, t.uid) });
    match step {
        Step::Scan => send(vec![cmd::ear_scan(false, uid)]),
        Step::Test => send(vec![cmd::tone_stop(), cmd::hearing_test(false)]),
        _ => {}
    }
    if step != Step::Done { st(|t| t.step = Step::Idle); }
}

/// Applies a record as HeyMelody does (description id, record, ear scan, switch on) and shows its radar.
fn apply_record(r: Record) {
    send(cmd::golden_apply(r.uid, &r.name, &r.values, &r.scan, r.desc));
    st(|t| t.active = r.uid);
    request_curves();
}

/// Asks the buds for the active record's filters (queries only), unless they are known.
fn request_curves() {
    let uid = st(|t| t.active);
    let Some(r) = records().into_iter().find(|r| r.uid == uid) else { return };
    if st(|t| t.hearing.contains_key(&uid)) { return changed(); }
    let mut frames = vec![cmd::golden_filter(r.uid, &r.values)];
    if !r.scan.is_empty() { frames.push(cmd::golden_scan_filter(r.uid, &r.scan)); }
    send(frames);
}

fn event(e: HearingEv) {
    match e {
        HearingEv::Status(kind, status) => {
            if !matches!(st(|t| t.step), Step::Scan | Step::Test) { return; }
            match status {
                1 | 3 => stop_test("golden_stopped_music"),
                5 => stop_test("golden_stopped_wear"),
                7 | 10 | 255 if kind == 4 => stop_test("golden_scan_failed"),
                _ => {}
            }
        }
        HearingEv::Refused(_) => { if matches!(st(|t| t.step), Step::Scan | Step::Test) { stop_test("golden_refused"); } }
        // The scan is done: stop it and start the hearing test.
        HearingEv::EarScan(uid, data) => {
            if st(|t| t.step != Step::Scan || t.uid != uid) { return; }
            st(|t| { t.timeout_gen += 1; t.scan_data = data; });
            send(vec![cmd::ear_scan(false, uid), cmd::hearing_test(true)]);
            begin(0);
        }
        HearingEv::Filter(uid, enhance, curves) => {
            if let Some((_, l, r)) = curves { st(|t| t.hearing.insert(uid, (l, r))); }
            if st(|t| t.step == Step::Finish && t.uid == uid) { save(desc_id(enhance)); }
        }
        HearingEv::ScanCurves(uid, c) => { st(|t| t.scan.insert(uid, c)); }
        HearingEv::Active(uid, name, values) => {
            st(|t| t.active = uid);
            if !records().iter().any(|r| r.uid == uid) { put(Record { uid, name, values, scan: vec![], desc: 0 }); }
            // After the ear-scan read (right behind this one) has filled the record in.
            after(600, request_curves);
        }
        HearingEv::ActiveScan(uid, data) => {
            let mut list = records();
            if let Some(r) = list.iter_mut().find(|r| r.uid == uid && r.scan.is_empty()) { r.scan = data; save_records(&list); }
        }
    }
}

/// Takes the replies and pushes the buds sent since the last state.
pub fn drain() {
    let events: Vec<HearingEv> = std::mem::take(&mut *HEARING.lock().unwrap());
    for e in events { event(e); }
}

/// `settings.json` "hearing_radar": the last radar drawn, `[uid, left, right]`, shown until the buds' filters arrive.
fn radii(active: u32) -> Option<[[f32; 6]; 2]> {
    let (h, sc) = st(|t| (t.hearing.get(&active).cloned(), t.scan.get(&active).cloned()));
    // A record with an ear scan draws only when its scan filters are here too: the radar then changes once, not twice.
    let needs_scan = records().iter().any(|r| r.uid == active && !r.scan.is_empty());
    if let Some((l, r)) = h.filter(|_| !needs_scan || sc.is_some()) {
        let both = [hearing_radar(&l, sc.as_ref().map(|c| (&c.1[..], c.0))), hearing_radar(&r, sc.as_ref().map(|c| (&c.2[..], c.0)))];
        let saved = crate::load_settings()["hearing_radar"].clone();
        let fresh = json!([active, both[0].to_vec(), both[1].to_vec()]);
        if saved != fresh { crate::save_setting("hearing_radar", fresh); }
        return Some(both);
    }
    let v = crate::load_settings()["hearing_radar"].clone();
    if v[0].as_u64() != Some(active as u64) || active == 0 { return None; }
    let ear = |i: usize| -> Option<[f32; 6]> {
        v[i].as_array()?.iter().map(|x| x.as_f64().map(|x| x as f32)).collect::<Option<Vec<_>>>()?.try_into().ok()
    };
    Some([ear(1)?, ear(2)?])
}

/// The page's state: the profiles, the radar of the active one and the test dialog.
pub fn json_state() -> Value {
    let list = records();
    let active = st(|t| t.active);
    let radar = radii(active);
    st(|t| json!({
        "profiles": list.iter().map(|r| json!({ "name": r.name, "confirm": tr("eq_delete_confirm").replace("%1$s", &r.name) })).collect::<Vec<_>>(),
        "active": list.iter().position(|r| r.uid == active),
        "radar": radar.map(|b| json!({ "left": b[0].to_vec(), "right": b[1].to_vec() })),
        "axes": HEARING_AXES,
        "test": { "title": t.title, "message": t.message, "label": t.label, "slider": t.slider, "done": t.done, "stop": t.stop,
            "enabled": t.button != Button::None, "stops": HEARING_STOPS, "loud": HEARING_LOUD },
    }))
}

/// The page's commands. None = not a hearing command.
pub fn command(s: &Snapshot, cmd: &Value) -> Option<Vec<Cmd>> {
    let i = cmd["i"].as_u64().unwrap_or(0) as usize;
    match cmd["t"].as_str()? {
        // The record on the buds, so one made in HeyMelody or on the phone shows up too.
        "hearing_open" => { changed(); return Some(vec![Cmd::Hearing(vec![(CMD_HEARING_ACTIVE, vec![]), (CMD_HEARING_ACTIVE_SCAN, vec![])])]); }
        "hearing_on" => return Some(vec![Cmd::Features(vec![(FEATURE_HEARING, cmd["on"].as_bool().unwrap_or(false))])]),
        "hearing_pick" => { if let Some(r) = records().get(i).cloned() { apply_record(r); } }
        "hearing_delete" => {
            let mut list = records();
            if i < list.len() { list.remove(i); }
            save_records(&list);
        }
        "htest_open" => { st(|t| t.step = Step::Idle); show("golden_test_title", Some("golden_prepare"), Button::Start, false); }
        "htest_action" => match st(|t| t.button) { Button::Start | Button::Again => start(), Button::Next => next(), Button::None => {} },
        "htest_moved" => {
            let v = cmd["v"].as_u64().unwrap_or(0) as usize;
            if st(|t| { t.stop = v.min(24); t.toned == Some(t.stop) }) { return Some(vec![]); }
            tone_soon();
            return Some(vec![]);
        }
        // Closed while the enhance type was being read: the result is still kept.
        "htest_closed" => { if st(|t| t.step) == Step::Finish { save(0); } else { abort(); } }
        _ => return None,
    }
    let _ = s;
    changed();
    Some(vec![])
}
