//! Dev tools: the packet log (Simple / Detailed), Clear, Export, Reconnect, Disconnect. Like the phone's: Simple
//! says in words what happened and leaves protocol steps out; Detailed shows every line with its bytes. One colour
//! per kind (`LogRow.kind`). Export always saves the raw lines.

use crate::protocol::*;
use crate::session::{Cmd, Line, LOG};
use crate::{with_app, App, DevLog, LogRow, MainWindow};
use slint::{ComponentHandle, Model, VecModel};

/// `LogRow.kind`, one colour each in `app.slint`.
const SENT: i32 = 0;
const RECEIVED: i32 = 1;
const CONNECTION: i32 = 2;
const PROBLEM: i32 = 3;

/// `0x0403` feature ids (PROTOCOL.md §9) by the name the apps show (the phone's `SimpleLog.FEATURES`).
fn feature(id: u8) -> Option<&'static str> {
    Some(match id {
        0x04 => "Auto play/pause", 0x06 => "Low latency mode", 0x09 => "Vocal enhancement", 0x0B => "Hearing profile",
        0x0C => "Personalised noise cancelling", 0x11 => "Dual connection", 0x17 => "Power saving",
        0x18 => "High-quality audio", 0x1A => "Wind noise reduction", 0x1B => "3D audio", 0x1C => "Smart volume",
        0x1D => "Bass boost", 0x27 => "Game sound effects", 0x30 => "Adaptive volume", 0x31 => "Adaptive left / right ear",
        0x32 => "Conversation awareness", 0x35 => "Touch-and-hold volume", 0x37 => "Windows Swift Pair",
        0x38 => "Adaptive sound", 0x3A => "Pause when asleep", 0x3B => "Head gestures",
        _ => return None,
    })
}

fn on_off(v: u8) -> &'static str { if v == 1 { "on" } else { "off" } }

/// A write in words; None for reads and protocol steps.
fn simple_tx(cmd: u16, p: &[u8]) -> Option<String> {
    Some(match (cmd, p) {
        (CMD_SET_FEATURE, [id, v, ..]) => format!("{} turned {}", feature(*id).unwrap_or("A setting"), on_off(*v)),
        (CMD_SET_ANC, [2, ..]) => "Noise modes for the hold gesture changed".into(),
        (CMD_SET_ANC, _) => "Noise control changed".into(),
        (CMD_FIND_BUDS, [v, ..]) => if *v == 1 { "Ringing the earbuds" } else { "Stopped ringing the earbuds" }.into(),
        (CMD_FIT_TEST, [v, ..]) => if *v == 1 { "Fit test started" } else { "Fit test stopped" }.into(),
        (CMD_SET_ALERT_VOLUME, [v, ..]) => format!("Alert sound volume set to {v}"),
        (CMD_SET_TAP_LEVEL, [v, ..]) => format!("Tap sensitivity set to {v}"),
        (CMD_SET_EQ, _) => "Equalizer preset changed".into(),
        (CMD_SAVE_CUSTOM_EQ, _) => "Custom equalizer preset saved".into(),
        (CMD_SET_BASSWAVE, [v, ..]) => format!("Bass boost level set to {v}"),
        (CMD_SET_KEY_FUNCTION, _) => "Gesture changed".into(),
        (CMD_GAME_SOUND, _) => "Game sound mode changed".into(),
        (CMD_SET_HEAD_MOTION, _) => "Head gestures changed".into(),
        (CMD_MULTI_CONNECT, _) => "Asked the buds to connect or disconnect a device".into(),
        _ => return None,
    })
}

/// A reply or push in words; None for what only matters to the protocol.
fn simple_rx(e: &Event) -> Option<String> {
    let side = |i: u8| ["Earbud", "Left earbud", "Right earbud", "Case"][(i as usize).min(3)];
    Some(match e {
        Event::ProductId(id) => format!("Buds: {}", find_model(Some(id), None).and_then(|m| m["name"].as_str()).unwrap_or("unknown model")),
        Event::Firmware(f) => format!("Firmware version {f}"),
        Event::Battery(v) if !v.is_empty() => format!("Battery: {}", v.iter()
            .map(|(i, l, c)| format!("{} {l}%{}", side(*i).to_lowercase(), if *c { " (charging)" } else { "" }))
            .collect::<Vec<_>>().join(", ")),
        Event::Wear(v) => {
            let parts: Vec<_> = v.iter().filter(|(i, _)| *i == 1 || *i == 2).filter_map(|(i, st)| match st {
                3 | 7 => Some(format!("{} in ear", side(*i))),
                4 => Some(format!("{} in the case", side(*i))),
                1 | 5 => Some(format!("{} out of the ear", side(*i))),
                _ => None,
            }).collect();
            if parts.is_empty() { return None; }
            parts.join(", ")
        }
        Event::AncRaw(_) => "Noise control mode reported".into(),
        Event::GameMode(on) => format!("Low latency mode {}", on_off(*on as u8)),
        Event::AlertVolume(l) => format!("Alert sound volume {l}"),
        Event::TapLevel(l, _) => format!("Tap sensitivity {l}"),
        Event::BassLevel(l) => format!("Bass boost level {l}"),
        Event::EqCurrent(_) => "Equalizer read".into(),
        Event::KeyFunctions(_) => "Gestures read".into(),
        Event::FitResult(l, r) => {
            let w = |v: u8| match v { 1 => "good fit", 0 => "average fit", 6 => "poor fit", _ => "no result" };
            format!("Fit test: left {}, right {}", w(*l), w(*r))
        }
        Event::PncResult(_) => "Personalised noise cancelling test finished".into(),
        Event::Hearing(HearingEv::Status(..)) => "Hearing test update".into(),
        Event::Devices(d) => {
            let on: Vec<_> = d.iter().filter(|d| d.connected).map(|d| d.name.as_str()).collect();
            if on.is_empty() { "No devices connected to the buds".into() } else { format!("Devices connected to the buds: {}", on.join(", ")) }
        }
        Event::Features(f) => {
            let on: Vec<_> = f.iter().filter(|(_, v)| *v == 1).filter_map(|(id, _)| feature(*id)).collect();
            if on.is_empty() { "Settings read: none switched on".into() } else { format!("Settings on: {}", on.join(", ")) }
        }
        _ => return None,
    })
}

/// The raw line, as Export saves it.
pub fn raw(l: &Line) -> String {
    match l.dir {
        "NOTE" => format!("{} {}", l.at, l.human.as_deref().unwrap_or("")),
        _ => format!("{} {} {}", l.at, l.dir, hex(&l.bytes)),
    }
}

/// The line as the given mode shows it (0 Simple, 1 Detailed); None = left out of Simple.
fn format(l: &Line, mode: i32) -> Option<LogRow> {
    let t = &l.at[..8];
    let row = |text: String, kind: i32| Some(LogRow { text: text.into(), kind });
    // Notes (connection) and bytes nothing names (problem) read the same in both modes, apart from the bytes.
    if l.dir == "NOTE" { return row(format!("{t}  {}", l.human.as_deref().unwrap_or("")), CONNECTION); }
    if l.dir != "TX" && l.dir != "RX" || l.human.is_none() {
        let what = if l.dir == "TX" { "QuickBuds sent something it has no name for" } else { "The buds sent something QuickBuds does not understand" };
        return if mode == 0 { row(format!("{t}  {what} (see Detailed)"), PROBLEM) } else { row(raw(l), PROBLEM) };
    }
    if mode == 0 {
        let (cmd, pl) = (cmd_of(&l.bytes), payload_of(&l.bytes));
        return if l.dir == "TX" {
            simple_tx(cmd, pl).and_then(|s| row(format!("{t}  {s}"), SENT))
        } else {
            decode(&l.bytes).as_ref().and_then(simple_rx).and_then(|s| row(format!("{t}  {s}"), RECEIVED))
        };
    }
    row(format!("{} {} {}\n      {:04X} {}", l.at, if l.dir == "TX" { "→" } else { "←" }, l.human.as_deref().unwrap_or(""),
        cmd_of(&l.bytes), hex(payload_of(&l.bytes))), if l.dir == "TX" { SENT } else { RECEIVED })
}

pub fn setup(main: &MainWindow) {
    let d = main.global::<DevLog>();
    d.set_rows(VecModel::from_slice(&[]));
    d.set_font(if cfg!(windows) { "Consolas" } else { "DejaVu Sans Mono" }.into());
    d.on_refresh(|| with_app(refresh));
    d.on_set_mode(|m| with_app(|a| { a.main.global::<DevLog>().set_mode(m); a.log_shown = 0; refresh(a); }));
    d.on_clear(|| with_app(|a| { LOG.lock().unwrap().clear(); a.log_shown = 0; refresh(a); }));
    d.on_reconnect(|| with_app(|a| { let _ = a.tx.send(Cmd::Disconnect); let _ = a.tx.send(Cmd::Connect); }));
    d.on_export(|| with_app(|a| {
        let text: String = crate::report::header(a) + "\n" + &LOG.lock().unwrap().iter().map(|l| raw(l) + "\n").collect::<String>();
        #[cfg(windows)]
        let home = std::env::var_os("USERPROFILE");
        #[cfg(not(windows))]
        let home = std::env::var_os("HOME");
        let dir = std::path::PathBuf::from(home.unwrap_or_default()).join("Downloads").join("QuickBuds");
        let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
        let path = dir.join(format!("quickbuds-log-{secs}.txt"));
        let note = match std::fs::create_dir_all(&dir).and_then(|_| std::fs::write(&path, text)) {
            Ok(_) => format!("Saved to {}", path.display()),
            Err(e) => format!("Export failed: {e}"),
        };
        a.main.global::<DevLog>().set_note(note.into());
    }));
}

/// Adds the lines logged since the last call, newest on top; everything again after a mode change or Clear.
fn refresh(a: &mut App) {
    let d = a.main.global::<DevLog>();
    let log = LOG.lock().unwrap();
    if a.log_shown == log.len() && a.log_shown != 0 { return; }
    let rows = d.get_rows();
    let rows = rows.as_any().downcast_ref::<VecModel<LogRow>>().unwrap();
    if a.log_shown == 0 { rows.set_vec(Vec::new()); }
    for l in &log[a.log_shown..] { if let Some(r) = format(l, d.get_mode()) { rows.insert(0, r); } }
    a.log_shown = log.len();
}
