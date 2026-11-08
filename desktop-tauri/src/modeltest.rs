//! Test my earbuds: the phone's `ModelTest`, with the same feature keys and the same report text. Start clears the log
//! and reconnects; the automatic check notes which reads the buds answered; the person marks each feature; Send posts
//! one "Test" report, always with the log.

use crate::session::{Cmd, Snapshot, Status, LOG};
use crate::tr;
use crate::types::*;
use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap, HashSet};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

/// (key in `models.json` `confirmed`, label, name in the report, the buds have it): the phone's `ModelTest.FEATURES`.
type Feature = (&'static str, &'static str, &'static str, fn(&Snapshot) -> bool);

const FEATURES: &[Feature] = &[
    ("battery", "problem_cat_battery", "Battery", |_| true),
    ("anc", "anc_section", "Noise control", |s| {
        let m = &s.modes;
        m.supports(OFF) || !m.levels().is_empty() || m.supports(ADAPTIVE) || m.supports(TRANSPARENCY)
    }),
    ("wind", "row_wind_noise_title", "Wind noise reduction", |s| {
        listed(s, FEATURE_WIND_NOISE) || s.manual && s.model.is_some_and(|m| m["windMode"] == 1)
    }),
    ("game", "row_game_title", "Low latency", |s| has_feature(&s.features, s.caps.game_mode_id())),
    ("hires", "row_hires_title", "High-quality audio", |s| listed(s, FEATURE_HIRES) || crate::home_logic::codec_picker(s)),
    ("spatial", "row_spatial_title", "3D audio", |s| listed(s, FEATURE_SPATIAL) || crate::home_logic::spatial_by_type(s)),
    ("golden", "row_golden_title", "Hearing profile", |s| has_feature(&s.features, FEATURE_HEARING)),
    ("eq", "row_eq_title", "Equalizer", |s| eq_has(s.model, &s.caps)),
    ("dual", "dual_title", "Dual connection", |s| has_feature(&s.features, FEATURE_DUAL)),
    ("gestures", "gesture_title", "Gestures", |s| (s.caps.supports(CMD_SET_KEY_FUNCTION) && !crate::gesture_logic::Model::of(s).rows.is_empty())),
    ("wear", "wear_firmware_title", "Wear detection", |s| listed(s, 0x04)),
    ("find", "row_find_title", "Find my earbuds", |s| s.caps.supports(CMD_FIND_BUDS) && !s.model.is_some_and(|m| m["noFind"] == 1)),
];

/// The automatic check: (key, label, name in the report).
const CHECKS: [(&str, &str, &str); 3] = [
    ("battery", "test_check_battery", "battery level"),
    ("features", "test_check_features", "feature list"),
    ("states", "test_check_states", "feature states"),
];

/// A mark, as the page's chips number them.
const WORKS: i32 = 1;
const BROKEN: i32 = 2;
const SKIP: i32 = 3;

/// The buds need a moment between the drop and the new connect.
const RECONNECT_GAP: Duration = Duration::from_millis(1500);
/// No connection by then: the test says so and Start works again.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(25);
/// After the connect, the handshake's reads (battery, `0x8100`, `0x810D`) arrive within this time.
const HANDSHAKE: Duration = Duration::from_secs(6);
/// After a connect, the prompt waits for the reads that decide which features show.
const ASK_DELAY: Duration = Duration::from_secs(3);

fn listed(s: &Snapshot, id: u8) -> bool { s.features.iter().any(|f| f.0 == id) }

/// The start of the machine-readable line. `scripts/import-model-test.py` finds the JSON after it.
const JSON_PREFIX: &str = "MODEL TEST JSON ";

/// The row key of a feature key, where the two differ (the phone's `ModelTest.ROW_KEY`). A feature without a row
/// (battery) is absent.
const ROW_KEY: &[(&str, &str)] = &[
    ("wind", "wind"), ("game", "game"), ("hires", "hires"), ("spatial", "spatial"), ("golden", "golden"),
    ("eq", "eq"), ("dual", "dual"), ("anc", "anc"), ("gestures", "controls"), ("wear", "wear"), ("find", "find"),
];

/// The rows this app shows for the features the test lists, and the Earbud settings row it always shows. The gates
/// are the ones the pages use ([FEATURES]), so the app has one rule set.
fn rows(s: &Snapshot) -> BTreeSet<&'static str> {
    let mut out = BTreeSet::from(["earbuds"]);
    out.extend(shown(s).iter().filter_map(|f| ROW_KEY.iter().find(|(k, _)| *k == f.0).map(|(_, row)| *row)));
    out
}

/// What the buds' answers do not match (the phone's `ModelTest.Diff`):
///  - `missing`: a switch the app asked for in `0x010D`, and the reply did not list;
///  - `extra`: a switch in the reply that the app did not ask for;
///  - `hidden`: a feature the person marked `works`, but the app shows no row for it. Then a row rule hides
///    something that works.
struct Diff {
    missing: Vec<u8>,
    extra: Vec<u8>,
    hidden: Vec<&'static str>,
}

impl Diff {
    fn empty(&self) -> bool { self.missing.is_empty() && self.extra.is_empty() && self.hidden.is_empty() }

    /// One line for the report's description, in English for the developer.
    fn text(&self) -> String {
        let ids = |v: &[u8]| v.iter().map(|id| format!("0x{id:02X}")).collect::<Vec<_>>().join(" ");
        let mut parts = vec![];
        if !self.missing.is_empty() { parts.push(format!("asked, no answer: {}", ids(&self.missing))); }
        if !self.extra.is_empty() { parts.push(format!("answered, not asked: {}", ids(&self.extra))); }
        if !self.hidden.is_empty() {
            let names: Vec<&str> = FEATURES.iter().filter(|f| self.hidden.contains(&f.0)).map(|f| f.2).collect();
            parts.push(format!("marked works, no row: {}", names.join(", ")));
        }
        parts.join("; ")
    }
}

/// The diagnosis from plain values, so a unit test can call it ([diff_state] passes the live state). `reply` is
/// None when nothing was read yet.
/// The two game-mode ids are alternatives (the phone's `ModelTest.otherGameId`): when the app asked both and the
/// reply holds one, the other is no missing answer. Both ids must be in the ask list for this to apply.
fn other_game_id(answered: &HashSet<u8>, asked: &[u8], id: u8) -> bool {
    let alt = match id { 0x06 => 0x28, 0x28 => 0x06, _ => return false };
    answered.contains(&alt) && asked.contains(&alt)
}

fn diff(asked: &[u8], reply: Option<&[(u8, u8)]>, rows: &BTreeSet<&'static str>,
        marks: &HashMap<&'static str, i32>) -> Diff {
    let missing = reply.map(|r| {
        let answered: HashSet<u8> = r.iter().map(|f| f.0).collect();
        let mut v: Vec<u8> = asked.iter().copied()
            .filter(|id| !answered.contains(id) && !other_game_id(&answered, asked, *id))
            .collect();
        v.sort_unstable();
        v.dedup();
        v
    }).unwrap_or_default();
    let extra = reply.map(|r| {
        let mut v: Vec<u8> = r.iter().map(|f| f.0).filter(|id| !asked.contains(id)).collect();
        v.sort_unstable();
        v.dedup();
        v
    }).unwrap_or_default();
    let hidden = FEATURES.iter()
        .filter(|f| marks.get(f.0) == Some(&WORKS))
        .filter(|f| ROW_KEY.iter().find(|(k, _)| *k == f.0).is_some_and(|(_, row)| !rows.contains(row)))
        .map(|f| f.0)
        .collect();
    Diff { missing, extra, hidden }
}

/// The word a mark has in the JSON and the report, as the phone writes it.
fn mark_name(mark: i32) -> Option<&'static str> {
    Some(match mark { WORKS => "works", BROKEN => "broken", SKIP => "skip", _ => return None })
}

/// The one machine-readable line of this test, for the packet log: the model, the firmware, the two replies that
/// decide the rows, the rows the app showed and expected, the marks, and the commands the app did not decode. A
/// report then needs no heuristics: `scripts/import-model-test.py` reads this line and makes a row fixture from it.
/// Same keys and formats as the phone's `ModelTest.jsonLine`.
fn json_line_from(id: &str, name: &str, fw: &str, caps: Option<&[u16]>, status: &[(u8, u8)],
        shown: &[&'static str], expected: &BTreeSet<&'static str>, marks: &HashMap<&'static str, i32>,
        unknown_rx: &BTreeSet<u16>) -> String {
    let mut caps = caps.unwrap_or(&[]).to_vec();
    caps.sort_unstable();
    caps.dedup();
    let caps = caps.iter().map(|c| format!("{c:04X}")).collect::<Vec<_>>().join(" ");
    let status: serde_json::Map<String, serde_json::Value> =
        status.iter().map(|(id, v)| (format!("{id:02X}"), (*v).into())).collect();
    let marks: serde_json::Map<String, serde_json::Value> = shown.iter()
        .filter_map(|k| marks.get(k).and_then(|m| mark_name(*m)).map(|n| (k.to_string(), n.into())))
        .collect();
    let json = serde_json::json!({
        "id": id,
        "name": name,
        "fw": fw,
        "caps": caps,
        "status": status,
        "shown": shown,
        "expected": expected.iter().collect::<Vec<_>>(),
        "marks": marks,
        "unknownRx": unknown_rx.iter().map(|c| format!("0x{c:04X}")).collect::<Vec<_>>(),
    });
    format!("{JSON_PREFIX}{json}")
}

/// The command ids of the RX frames the app did not decode: the phone's `UNATTR RX:` lines. A `0x0204` push stays
/// out, as on the phone: its sub-type is not a command.
fn unknown_rx() -> BTreeSet<u16> {
    LOG.lock().unwrap().iter()
        .filter(|l| l.dir == "RX" && l.human.is_none())
        .map(|l| cmd_of(&l.bytes))
        .filter(|c| *c != EVT_PUSH)
        .collect()
}


fn shown(s: &Snapshot) -> Vec<&'static Feature> { FEATURES.iter().filter(|f| (f.3)(s)).collect() }

fn model_id(s: &Snapshot) -> Option<String> { s.model.and_then(|m| m["id"].as_str()).map(String::from) }

/// The test in progress for one model: kept while the person tries features on other pages.
struct State {
    model: String,
    checks: [bool; 3],
    marks: HashMap<&'static str, i32>,
    notes: HashMap<&'static str, String>,
}

#[derive(Clone, Copy, PartialEq)]
enum Phase { Idle, Dropping, Connecting, Reading }

struct Test {
    phase: Phase,
    /// Bumped by each Start: a timer from an earlier run does nothing.
    run: u32,
    state: Option<State>,
    busy: bool,
    status: String,
}

fn test<R>(f: impl FnOnce(&mut Test) -> R) -> R {
    static T: OnceLock<Mutex<Test>> = OnceLock::new();
    f(&mut T.get_or_init(|| Mutex::new(Test { phase: Phase::Idle, run: 0, state: None, busy: false, status: String::new() })).lock().unwrap())
}

static APP: OnceLock<tauri::AppHandle> = OnceLock::new();

pub fn init(app: &tauri::AppHandle) { let _ = APP.set(app.clone()); }

fn link<R>(f: impl FnOnce(&crate::link::Link) -> R) -> Option<R> {
    use tauri::Manager;
    APP.get().and_then(|a| a.try_state::<crate::link::Link>()).map(|l| f(&l))
}

fn snapshot() -> Snapshot { link(|l| l.snapshot()).unwrap_or_default() }

/// Sends the page its new state.
fn changed() {
    use tauri::Emitter;
    if let Some(a) = APP.get() { let _ = a.emit("test", json_state(&snapshot())); }
}

fn after(d: Duration, f: impl FnOnce() + Send + 'static) { std::thread::spawn(move || { std::thread::sleep(d); f(); }); }

/// Clears the log, drops the link and connects again; [apply] follows the status from there.
fn start(s: &Snapshot) {
    if test(|t| t.phase) != Phase::Idle { return; }
    let run = test(|t| { t.run += 1; t.run });
    LOG.lock().unwrap().clear();
    let name = s.model.and_then(|m| m["name"].as_str()).unwrap_or("unknown model");
    crate::session::log("NOTE", Vec::new(), Some(format!("MODEL TEST: start, {name}")));
    test(|t| { t.state = None; t.busy = true; t.status = tr("test_reconnecting"); });
    if s.status == Status::Off {
        test(|t| t.phase = Phase::Connecting);
        link(|l| l.send_cmd(Cmd::Connect));
    } else {
        test(|t| t.phase = Phase::Dropping);
        link(|l| l.send_cmd(Cmd::Disconnect));
    }
    after(CONNECT_TIMEOUT, move || {
        if test(|t| t.run == run && matches!(t.phase, Phase::Dropping | Phase::Connecting)) { fail(); }
    });
}

fn fail() {
    test(|t| { t.phase = Phase::Idle; t.busy = false; t.status = tr("test_failed"); });
    changed();
}

/// Each new state from the session: drives Start.
pub fn apply(s: &Snapshot) {
    let on = s.status == Status::On;
    match test(|t| t.phase) {
        Phase::Dropping if s.status == Status::Off => {
            test(|t| t.phase = Phase::Connecting);
            after(RECONNECT_GAP, || { link(|l| l.send_cmd(Cmd::Connect)); });
        }
        Phase::Connecting if on => {
            test(|t| t.phase = Phase::Reading);
            let run = test(|t| t.run);
            after(HANDSHAKE, move || { if test(|t| t.run == run && t.phase == Phase::Reading) { finish(); } });
        }
        _ => {}
    }
}

/// The check from the fresh connection's state, then the feature rows.
fn finish() {
    let s = snapshot();
    test(|t| t.phase = Phase::Idle);
    let Some(model) = model_id(&s) else { return fail() };
    let checks = [s.battery.iter().any(Option::is_some), s.caps.read(), !s.features.is_empty()];
    crate::session::log("NOTE", Vec::new(), Some(format!("MODEL TEST: check battery={} features={} states={}", checks[0], checks[1], checks[2])));
    test(|t| { t.state = Some(State { model, checks, marks: HashMap::new(), notes: HashMap::new() }); t.busy = false; t.status.clear(); });
    changed();
}

/// Every feature shown has a mark, and every "does not work" has a note.
fn complete(s: &Snapshot, st: &State) -> bool {
    shown(s).iter().all(|f| match st.marks.get(f.0) {
        None | Some(0) => false,
        Some(&BROKEN) => st.notes.get(f.0).is_some_and(|n| !n.is_empty()),
        _ => true,
    })
}

/// Send needs every feature marked and a log with frames from the buds.
fn ready(s: &Snapshot) -> bool {
    test(|t| t.state.as_ref().is_some_and(|st| complete(s, st))) && LOG.lock().unwrap().iter().any(|l| l.dir == "RX")
}

/// [diff] from the state of the test in progress: the ids this app asks ([status_query]), the last status reply,
/// the rows shown, and the marks.
fn diff_state(s: &Snapshot, st: &State) -> Diff {
    let mut asked = status_query(s.model);
    // The count comes first.
    if !asked.is_empty() { asked.remove(0); }
    let reply = (!s.features.is_empty()).then_some(s.features.as_slice());
    diff(&asked, reply, &rows(s), &st.marks)
}

fn json_line(s: &Snapshot, st: &State) -> String {
    let keys: Vec<&'static str> = shown(s).iter().map(|f| f.0).collect();
    let name = s.model.and_then(|m| m["name"].as_str()).unwrap_or("unknown model");
    let id = crate::load_settings()["product_id"].as_str().unwrap_or_default().to_string();
    json_line_from(&id, name, s.firmware.as_deref().unwrap_or(""), s.caps.0.as_deref(), &s.features, &keys, &rows(s), &st.marks, &unknown_rx())
}

/// The report's description, in English for the developer: the check, the diagnosis, then one line per feature.
fn description(s: &Snapshot, st: &State) -> String {
    let check: Vec<String> = CHECKS.iter().zip(st.checks)
        .map(|((_, _, name), ok)| format!("{name} {}", if ok { "answered" } else { "no answer" })).collect();
    let mut lines = vec![
        format!("Model test ({})", if cfg!(windows) { "Windows" } else { "Linux" }),
        format!("Automatic check: {}", check.join(", ")),
    ];
    let diagnosis = diff_state(s, st);
    if !diagnosis.empty() { lines.push(format!("Diagnosis: {}", diagnosis.text())); }
    for f in shown(s) {
        lines.push(match st.marks.get(f.0) {
            Some(&WORKS) => format!("{}: works", f.2),
            Some(&BROKEN) => format!("{}: does not work: {}", f.2, st.notes.get(f.0).map_or("", |n| n.as_str())),
            _ => format!("{}: skipped", f.2),
        });
    }
    lines.join("\n")
}

fn draft(s: &Snapshot) -> Option<crate::report::Draft> {
    let model = s.model.and_then(|m| m["name"].as_str())?.to_string();
    let description = test(|t| t.state.as_ref().map(|st| description(s, st)))?;
    Some(crate::report::Draft { model, categories: vec!["Test"], description, log: Some(crate::report::log_text(s)), crash: false })
}

/// The page: the automatic check, the feature rows with their marks, the diagnosis, and what blocks Send.
pub fn json_state(s: &Snapshot) -> Value {
    let id = model_id(s);
    // A state for another model is dropped.
    test(|t| if t.state.as_ref().is_some_and(|st| Some(&st.model) != id.as_ref()) { t.state = None; });
    let ready = ready(s);
    test(|t| {
        let body = t.state.as_ref().map(|st| {
            let d = diff_state(s, st);
            let list = |v: &[u8]| v.iter().map(|id| format!("0x{id:02X}")).collect::<Vec<_>>().join(" ");
            let mut diagnosis = vec![];
            if !d.missing.is_empty() { diagnosis.push(tr("test_diff_missing").replace("%1$s", &list(&d.missing))); }
            if !d.extra.is_empty() { diagnosis.push(tr("test_diff_extra").replace("%1$s", &list(&d.extra))); }
            if !d.hidden.is_empty() {
                let names: Vec<&str> = FEATURES.iter().filter(|f| d.hidden.contains(&f.0)).map(|f| f.2).collect();
                diagnosis.push(tr("test_diff_hidden").replace("%1$s", &names.join(", ")));
            }
            json!({
                "checks": CHECKS.iter().zip(st.checks).map(|((_, label, _), ok)| json!({ "title": tr(label), "sub": tr(if ok { "test_answered" } else { "test_no_answer" }) })).collect::<Vec<_>>(),
                "diagnosis": diagnosis,
                "rows": shown(s).iter().map(|f| {
                    let mark = st.marks.get(f.0).copied().unwrap_or(0);
                    json!({ "title": tr(f.1), "mark": mark, "note": st.notes.get(f.0).cloned().unwrap_or_default(),
                        "sub": tr(match mark { WORKS => "test_works", BROKEN => "test_broken", SKIP => "test_skipped", _ => "test_not_marked" }) })
                }).collect::<Vec<_>>(),
            })
        });
        json!({ "started": body.is_some(), "busy": t.busy, "status": t.status, "body": body,
            "note": if body.is_some() && !t.busy && !ready { tr("test_mark_all") } else { String::new() } })
    })
}

/// The page's commands. None = not a test command.
pub fn command(s: &Snapshot, cmd: &Value) -> Option<Vec<Cmd>> {
    let feature = || shown(s).get(cmd["i"].as_u64().unwrap_or(u64::MAX) as usize).copied();
    match cmd["t"].as_str()? {
        "test_open" => {}
        "test_start" => start(s),
        "test_mark" => {
            let f = feature()?;
            let mark = cmd["mark"].as_i64()? as i32;
            test(|t| if let Some(st) = t.state.as_mut() { st.marks.insert(f.0, mark); });
        }
        "test_note" => {
            let f = feature()?;
            let text = cmd["text"].as_str().unwrap_or("").trim().to_string();
            test(|t| if let Some(st) = t.state.as_mut() { st.notes.insert(f.0, text); });
        }
        "test_send" => send(s),
        _ => return None,
    }
    changed();
    Some(vec![])
}

/// What would be sent.
pub fn preview(s: &Snapshot) -> Option<String> { draft(s).map(|d| crate::report::preview(&d)) }

fn send(s: &Snapshot) {
    if !ready(s) { return; }
    // The machine-readable line goes in first, so the log this report sends carries it.
    test(|t| if let Some(st) = t.state.as_ref() { crate::session::log("NOTE", Vec::new(), Some(json_line(s, st))); });
    let Some(d) = draft(s) else { return };
    test(|t| { t.busy = true; t.status = tr("problem_sending"); });
    std::thread::spawn(move || {
        let (ok, refused) = crate::report::send(d);
        test(|t| {
            t.busy = false;
            if ok { t.state = None; t.status = tr("problem_sent"); }
            else { t.status = refused.unwrap_or_else(|| tr("problem_failed")); }
        });
        changed();
    });
    changed();
}
