//! Dev tools: the phone's packet log (Human / Detailed / Raw), Clear, Export, Reconnect, Disconnect.

use crate::protocol::{cmd_of, hex, payload_of};
use crate::session::{Cmd, Line, LOG};
use crate::{with_app, App, DevLog, LogRow, MainWindow};
use slint::{ComponentHandle, Model, VecModel};

/// The line as the given mode shows it (0 Human, 1 Detailed, 2 Raw).
fn format(l: &Line, mode: i32) -> LogRow {
    let t = &l.at;
    let frame = l.dir != "DISCARDED RX";
    let text = match (mode, frame, &l.human) {
        (2, _, _) | (_, false, _) => format!("{t} {} {}", l.dir, hex(&l.bytes)),
        (0, true, Some(h)) => format!("{t}  {}  {h}", if l.dir == "TX" { "→" } else { "←" }),
        (_, true, h) => format!("{t} {} {}\n      {:04X} {}", l.dir, h.as_deref().unwrap_or("?"),
            cmd_of(&l.bytes), hex(payload_of(&l.bytes))),
    };
    // Human: whatever nothing names is amber, with its payload.
    LogRow { text: text.into(), amber: l.human.is_none() }
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
        let mode = a.main.global::<DevLog>().get_mode();
        let text: String = LOG.lock().unwrap().iter().map(|l| format(l, mode).text.to_string() + "\n").collect();
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
    for l in &log[a.log_shown..] { rows.insert(0, format(l, d.get_mode())); }
    a.log_shown = log.len();
}
