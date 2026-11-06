//! The bridge between the link thread (`session`) and the web UI: the state goes out as JSON events,
//! the commands come in as one JSON command.

use crate::home_logic;
use crate::session::{self, Cmd, Snapshot, Status};
use crate::types::*;
use serde_json::{json, Value};
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use tauri::{AppHandle, Emitter, Manager, State};

/// The buds' own auto play/pause switch (feature id).
const FEATURE_AUTO_PLAY_PAUSE: u8 = 0x04;

pub struct Link {
    tx: Mutex<Sender<Cmd>>,
    /// The last state: for a window that opens later, and for the commands that need the model.
    last: Mutex<(Snapshot, Value)>,
}

impl Link {
    pub fn send_cmd(&self, c: Cmd) { let _ = self.tx.lock().unwrap().send(c); }
    /// The last state.
    pub fn snapshot(&self) -> Snapshot { self.last.lock().unwrap().0.clone() }
}

/// Starts the link thread. Each new state goes to every window as the `snapshot` event.
pub fn start(app: &AppHandle) {
    // `QB_DEBUG=1` prints the packet log and every state change to the terminal.
    if std::env::var_os("QB_DEBUG").is_some() {
        std::thread::spawn(|| {
            let mut shown = 0;
            loop {
                {
                    let log = session::LOG.lock().unwrap();
                    for l in &log[shown.min(log.len())..] {
                        eprintln!("{} {:<4} {} {}", l.at, l.dir, crate::types::hex(&l.bytes), l.human.as_deref().unwrap_or(""));
                    }
                    shown = log.len();
                }
                std::thread::sleep(std::time::Duration::from_millis(300));
            }
        });
    }
    let h = app.clone();
    // `QB_FAKE=1` shows a made-up connected Buds 4 and never connects: for tests on an invisible screen.
    if std::env::var_os("QB_FAKE").is_some() {
        let (tx, _rx) = std::sync::mpsc::channel();
        app.manage(Link { tx: Mutex::new(tx), last: Mutex::new((Snapshot::default(), Value::Null)) });
        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_secs(3));
            let s = fake();
            let v = snapshot_json(&s);
            if let Some(l) = h.try_state::<Link>() { *l.last.lock().unwrap() = (s, v.clone()); }
            let _ = h.emit("snapshot", v);
        });
        return;
    }
    let tx = session::spawn(move |s| {
        if std::env::var_os("QB_DEBUG").is_some() { eprintln!("state: {:?} name={:?} battery={:?}", s.status as u8, s.name, s.battery); }
        crate::hearing::drain();
        crate::modeltest::apply(&s);
        crate::support::note_link(s.status == Status::On);
        let v = snapshot_json(&s);
        if let Some(l) = h.try_state::<Link>() { *l.last.lock().unwrap() = (s, v.clone()); }
        let _ = h.emit("snapshot", v);
    });
    app.manage(Link { tx: Mutex::new(tx), last: Mutex::new((Snapshot::default(), Value::Null)) });
}

fn feat(s: &Snapshot, id: u8) -> bool { s.features.contains(&(id, 1)) }

fn mac(m: &[u8; 6]) -> String { m.iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":") }

fn snapshot_json(s: &Snapshot) -> Value {
    let levels = s.modes.levels();
    let builtins: Vec<Value> = if s.caps.supports(CMD_SET_EQ) {
        eq_builtins(s.model, s.firmware.as_deref()).into_iter().map(|(id, key)| json!({ "id": id, "name": crate::tr(key) })).collect()
    } else { vec![] };
    let custom: Vec<Value> = s.eq_custom.iter().map(|p| json!({ "id": p.id, "name": p.name, "gains": p.gains, "freqs": p.freqs })).collect();
    json!({
        "status": match s.status { Status::Off => "off", Status::Connecting => "connecting", Status::On => "on" },
        "name": s.name,
        "t": { "on": crate::tr("conn_on"), "off": crate::tr("conn_off"), "connect": crate::tr("conn_action_connect"), "disconnect": crate::tr("conn_action_disconnect"), "connecting": crate::tr("conn_connecting") },
        "model": s.model.and_then(|m| m["name"].as_str()),
        "firmware": s.firmware,
        // Left, right, case.
        "battery": s.battery.map(|b| b.map(|(level, charging)| json!({ "level": level, "charging": charging }))),
        "wear": s.wear,
        "anc": s.anc,
        "levels": levels,
        "lowLatency": s.low_latency,
        // The switches' state, and which of them the buds offer.
        "sw": {
            "hq": feat(s, FEATURE_HIRES),
            "sp": home_logic::spatial_on(s),
            "hp": feat(s, FEATURE_HEARING),
            "dual": feat(s, FEATURE_DUAL),
            "wear": feat(s, FEATURE_AUTO_PLAY_PAUSE),
            "bass": s.bass_on.unwrap_or(false),
        },
        "has": {
            "off": s.modes.supports(OFF),
            "anc": !levels.is_empty(),
            "adaptive": s.modes.supports(ADAPTIVE),
            "transparency": s.modes.supports(TRANSPARENCY),
            "hq": has_feature(&s.features, FEATURE_HIRES),
            "sp": has_feature(&s.features, FEATURE_SPATIAL),
            "hp": has_feature(&s.features, FEATURE_HEARING),
            "dual": has_feature(&s.features, FEATURE_DUAL),
            "wear": has_feature(&s.features, FEATURE_AUTO_PLAY_PAUSE),
            "eq": eq_has(s.model, &s.caps),
            "game": has_feature(&s.features, s.caps.game_mode_id()),
            "controls": s.caps.supports(CMD_SET_KEY_FUNCTION) && !crate::gesture_logic::Model::of(s).rows.is_empty(),
        },
        "eq": {
            "current": s.eq_current,
            "builtins": builtins,
            "custom": custom,
            "hasCustom": eq_has_custom(s.model, &s.caps),
            "freqs": eq_model_freqs(s.model),
            "max": eq_max_custom(s.model),
            "bass": {
                "has": eq_has_bass(s.model, &s.caps),
                "level": s.bass_level,
                "threeBands": eq_bass_bands(s.model),
                "bands": s.bass_bands,
            },
        },
        "gestures": crate::pages::gestures_json(s),
        "models": crate::pages::models_json(s),
        "dual": crate::pages::dual_json(s),
        "test": crate::modeltest::json_state(s),
        "hearing": { "on": feat(s, FEATURE_HEARING), "hasTest": s.caps.supports(CMD_GOLDEN_DETECT), "state": crate::hearing::json_state() },
        "earbudx": crate::pages::earbud_json(s),
        "earbud": {
            "hasAlert": s.caps.supports(CMD_SET_ALERT_VOLUME) && s.alert_volume.is_some(),
            "alert": s.alert_volume,
            "hasTap": s.model.is_some_and(|m| m["tapLevel"] == 1) && (s.manual || s.caps.supports(CMD_SET_TAP_LEVEL) && s.tap_level.is_some()),
            "tap": s.tap_level.map(|(v, d)| json!({ "v": v, "default": d })),
            "devices": s.devices.iter().map(|d| json!({ "mac": mac(&d.mac), "name": d.name, "connected": d.connected, "this": d.this_device })).collect::<Vec<_>>(),
        },
    })
}

/// The packet log from line `from` on, for the Dev tools page.
#[tauri::command]
pub fn get_log(from: usize) -> Value {
    let log = session::LOG.lock().unwrap();
    let lines: Vec<Value> = log.iter().skip(from).take(500)
        .map(|l| json!({ "at": l.at, "dir": l.dir, "hex": hex(&l.bytes), "human": l.human })).collect();
    json!({ "next": from + lines.len(), "lines": lines })
}

/// Every phone string in the chosen language.
#[tauri::command]
pub fn get_strings() -> Value { crate::pages::strings() }

#[tauri::command]
pub fn get_snapshot(link: State<Link>) -> Value { link.last.lock().unwrap().1.clone() }

/// One command from the UI, as `{ "t": "...", ... }`. Each one is the same write the other desktop apps send.
#[tauri::command]
pub fn send(link: State<Link>, cmd: Value) {
    let snap = link.snapshot();
    let cmds = commands(&snap, &cmd);
    let tx = link.tx.lock().unwrap();
    for c in cmds { let _ = tx.send(c); }
}

fn commands(s: &Snapshot, cmd: &Value) -> Vec<Cmd> {
    if let Some(c) = crate::pages::commands(s, cmd).or_else(|| crate::hearing::command(s, cmd)).or_else(|| crate::modeltest::command(s, cmd)) { return c; }
    let on = cmd["on"].as_bool().unwrap_or(false);
    let id = cmd["id"].as_u64().unwrap_or(0) as u8;
    let preset = || s.eq_custom.iter().find(|p| p.id == id).cloned();
    match cmd["t"].as_str() {
        Some("anc") => cmd["mode"].as_str().map(|m| Cmd::Anc(m.to_string())).into_iter().collect(),
        Some("ll") => vec![Cmd::LowLatency(on)],
        Some("connect") => vec![Cmd::Connect],
        Some("disconnect") => vec![Cmd::Disconnect],
        Some("sw") => match cmd["k"].as_str() {
            Some("hq") => home_logic::feature_cmds(s, FEATURE_HIRES, on),
            Some("sp") => home_logic::feature_cmds(s, FEATURE_SPATIAL, on),
            Some("hp") => vec![Cmd::Features(vec![(FEATURE_HEARING, on)])],
            Some("dual") => vec![Cmd::Dual(on)],
            Some("wear") => vec![Cmd::Features(vec![(FEATURE_AUTO_PLAY_PAUSE, on)])],
            Some("bass") => vec![Cmd::BassWave(on)],
            _ => vec![],
        },
        Some("eq_builtin") => vec![Cmd::EqBuiltIn(id)],
        // Selecting a custom preset saves it as the selected one.
        Some("eq_select") => preset().map(Cmd::EqSave).into_iter().collect(),
        Some("eq_gains") => preset().map(|mut p| {
            for (g, v) in p.gains.iter_mut().zip(cmd["gains"].as_array().into_iter().flatten()) { *g = v.as_i64().unwrap_or(0) as i8; }
            Cmd::EqSave(p)
        }).into_iter().collect(),
        Some("eq_rename") => preset().zip(cmd["name"].as_str()).map(|(mut p, n)| { p.name = n.chars().take(20).collect(); Cmd::EqSave(p) }).into_iter().collect(),
        Some("eq_create") => {
            let freqs = s.eq_custom.first().map(|p| p.freqs.clone()).unwrap_or_else(|| eq_model_freqs(s.model));
            cmd["name"].as_str().map(|n| Cmd::EqCreate(Preset::new(n, freqs))).into_iter().collect()
        }
        Some("eq_delete") => {
            // The buds refuse to delete the preset in use: switch to a recommended one first.
            let mut v: Vec<Cmd> = eq_builtins(s.model, s.firmware.as_deref()).first().map(|(b, _)| Cmd::EqBuiltIn(*b)).into_iter().collect();
            v.extend(preset().map(Cmd::EqDelete));
            v
        }
        Some("bass") => vec![Cmd::BassLevel(cmd["level"].as_i64().unwrap_or(0) as i8, cmd["band"].as_u64().unwrap_or(0) as u8)],
        Some("alert") => vec![Cmd::AlertVolume(cmd["v"].as_u64().unwrap_or(5) as u8)],
        Some("tap") => vec![Cmd::TapLevel(cmd["v"].as_u64().unwrap_or(3) as u8)],
        Some("find") => vec![Cmd::Find(on)],
        Some("earbud_open") => vec![Cmd::EarbudReads],
        _ => vec![],
    }
}

/// A made-up connected OnePlus Buds 4 (`QB_FAKE`).
fn fake() -> Snapshot {
    let model = find_model(Some("065414"), None);
    Snapshot {
        status: Status::On, name: "OnePlus Buds 4".into(), battery: [Some((82, false)), Some((79, false)), Some((60, true))], wear: [3, 3, 4],
        model, detected: model,
        caps: Caps(Some(vec![CMD_SET_EQ, CMD_SET_ALERT_VOLUME, CMD_SET_KEY_FUNCTION, CMD_FIND_BUDS, CMD_FIT_TEST, CMD_QUERY_FIRMWARE, CMD_SET_TAP_LEVEL,
            CMD_GOLDEN_DETECT, CMD_PERSONAL_NOISE, CMD_SET_HEAD_MOTION, CMD_GAME_SOUND, CMD_MULTI_CONNECT, CMD_QUERY_SPATIAL_TYPE])),
        firmware: Some("1.1.0.67".into()), alert_volume: Some(5), tap_level: Some((3, 3)), game_sound: Some((3, vec![3])), head_motion: Some(0),
        features: vec![(0x04, 1), (0x18, 1), (0x1B, 0), (0x0B, 1), (0x11, 1), (0x27, 1), (0x0C, 0), (0x1A, 1), (0x06, 0), (0x30, 0), (0x3B, 1)],
        keyfn: Some(vec![[1, 1, 1, 1], [2, 1, 1, 1], [1, 1, 2, 5], [2, 1, 2, 5], [1, 1, 3, 6], [2, 1, 3, 6], [1, 1, 4, 8], [2, 1, 4, 8]]),
        hold_masks: vec![(1, 0x0807)],
        spatial_type: Some(1),
        devices: vec![PairedDevice { mac: [1, 2, 3, 4, 5, 6], name: "Phone".into(), connected: true, this_device: false }],
        ..Default::default()
    }
}
