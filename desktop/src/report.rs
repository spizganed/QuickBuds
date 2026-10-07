//! App settings › Report a problem: the phone's `ProblemReport`, posted to the same Google Form with `curl`
//! (as the updater does). Nothing goes out without Send; See the report shows the exact text first.

use crate::protocol::*;
use crate::session::{Line, LOG};
use crate::{with_app, App, MainWindow, Report};
use slint::{ComponentHandle, Model, VecModel};
use std::io::Write;
use std::process::Stdio;
use std::sync::Mutex;

const FORM: &str = "https://docs.google.com/forms/d/e/1FAIpQLScPNdmogyip2EDWZayNUSW3ElYz674GAC7t7BZuFNHgvxGP_Q/formResponse";
const ENTRY_MODEL: &str = "entry.849813820";
const ENTRY_CATEGORY: &str = "entry.494675210";
const ENTRY_DESCRIPTION: &str = "entry.1786789589";
const ENTRY_LOG: &str = "entry.863055756";
/// Google answers 413 somewhere between 31 and 41 KB of form body.
const MAX_BODY: usize = 30_000;
/// The form's Category options, in its own words; it rejects any other value.
const CATEGORIES: [&str; 6] = ["UI", "Lag", "Connection", "Feature not working", "Battery", "Other"];
/// Their labels, the phone's `problem_cat_*` strings.
pub const CATEGORY_KEYS: [&str; 6] = ["problem_cat_ui", "problem_cat_lag", "problem_cat_connection",
    "problem_cat_feature", "problem_cat_battery", "problem_cat_other"];

/// The first line of a sent or exported log: app, computer, system, buds model and firmware.
pub fn header(a: &App) -> String {
    let s = &a.snap;
    let model = s.model.and_then(|m| m["name"].as_str()).unwrap_or(if s.name.is_empty() { "unknown buds" } else { s.name.as_str() });
    let id = s.model.and_then(|m| m["id"].as_str()).unwrap_or("?");
    format!("QuickBuds {} | {} | {} | {model} ({id}) firmware {}", env!("CARGO_PKG_VERSION"), computer(), system(),
        s.firmware.as_deref().unwrap_or("?"))
}

#[cfg(not(windows))]
fn computer() -> String {
    let read = |f: &str| std::fs::read_to_string(format!("/sys/class/dmi/id/{f}")).map(|s| s.trim().to_string()).unwrap_or_default();
    format!("{} {}", read("sys_vendor"), read("product_name")).trim().to_string()
}

#[cfg(not(windows))]
fn system() -> String {
    let os = std::fs::read_to_string("/etc/os-release").unwrap_or_default();
    let name = os.lines().find_map(|l| l.strip_prefix("PRETTY_NAME=")).unwrap_or("Linux").trim_matches('"').to_string();
    let kernel = std::fs::read_to_string("/proc/sys/kernel/osrelease").unwrap_or_default();
    format!("{name} (kernel {})", kernel.trim())
}

/// Maker and model from the BIOS keys (`reg` ships with Windows).
#[cfg(windows)]
fn computer() -> String {
    let out = crate::update::quiet("reg").args(["query", r"HKLM\HARDWARE\DESCRIPTION\System\BIOS"]).output();
    let text = out.map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    let value = |k: &str| text.lines().find(|l| l.trim_start().starts_with(k))
        .and_then(|l| l.split("REG_SZ").nth(1)).map(|v| v.trim().to_string()).unwrap_or_default();
    format!("{} {}", value("SystemManufacturer"), value("SystemProductName")).trim().to_string()
}

#[cfg(windows)]
fn system() -> String {
    let out = crate::update::quiet("cmd").args(["/c", "ver"]).output();
    out.map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_else(|_| "Windows".into())
}

/// This computer's own name, which the Bluetooth adapter and the buds' device list carry.
fn host() -> Option<String> {
    #[cfg(windows)]
    let name = std::env::var("COMPUTERNAME").ok();
    #[cfg(not(windows))]
    let name = std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string());
    name.filter(|n| n.len() >= 3)
}

/// Device list and device manager packets (PROTOCOL.md §9) hold addresses and names: only the header stays.
fn device_bytes(l: &Line) -> bool {
    if l.bytes.len() < 10 || l.bytes[0] != 0xAA { return false; }
    let pl = payload_of(&l.bytes);
    matches!(cmd_of(&l.bytes), 0x8112 | 0x0429 | 0x8132) || (cmd_of(&l.bytes) == EVT_PUSH && pl.first() == Some(&0x06))
}

/// The log as sent: header, then every line, then the last crash report; without Bluetooth addresses, device data
/// or this computer's name.
fn log_text(a: &App) -> String {
    scrub(header(a) + "\n" + &lines(&LOG.lock().unwrap()) + CRASH.lock().unwrap().as_str())
}

fn lines(log: &[Line]) -> String {
    log.iter().map(|l| if device_bytes(l) {
        format!("{} {} {} [device data removed]\n", l.at, l.dir, hex(&l.bytes[..l.bytes.len() - payload_of(&l.bytes).len()]))
    } else {
        crate::devtools::raw(l) + "\n"
    }).collect()
}

fn scrub(text: String) -> String {
    let text = scrub_macs(&text);
    match host() { Some(h) => text.replace(&h, "[this computer]"), None => text }
}

/// Downloads/QuickBuds: exported logs and crash reports, as on the phone.
pub fn folder() -> std::path::PathBuf {
    #[cfg(windows)]
    let home = std::env::var_os("USERPROFILE");
    #[cfg(not(windows))]
    let home = std::env::var_os("HOME");
    std::path::PathBuf::from(home.unwrap_or_default()).join("Downloads").join("QuickBuds")
}

/// The crash report the Report page sends after a crash; "" otherwise.
static CRASH: Mutex<String> = Mutex::new(String::new());

/// First thing in `main`: a panic writes `crash-<seconds>.txt` to [folder], with the newest log lines. The release
/// build aborts on a panic and Windows shows no console, so this file is the only record.
pub fn catch_panics() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        // A panic while the log is locked skips the log rather than deadlock.
        let log = LOG.try_lock().map(|l| lines(&l[l.len().saturating_sub(300)..])).unwrap_or_default();
        let text = format!("QuickBuds crash report\nversion: {}\ncomputer: {}\nsystem: {}\nthread: {}\n\n{info}\n\n{log}",
            env!("CARGO_PKG_VERSION"), computer(), system(), std::thread::current().name().unwrap_or("?"));
        let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
        let _ = std::fs::create_dir_all(folder()).and_then(|_| std::fs::write(folder().join(format!("crash-{secs}.txt")), scrub(text)));
        previous(info);
    }));
}

/// A crash report newer than the last one shown opens the Report page, filled in with it. Once per report.
pub fn show_crash(a: &mut App) {
    let newest = std::fs::read_dir(folder()).into_iter().flatten().flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.starts_with("crash-") && n.ends_with(".txt"))
        .max_by_key(|n| n[6..n.len() - 4].parse::<u64>().unwrap_or(0));
    let Some(name) = newest else { return };
    if crate::load_settings()["crash_shown"].as_str() == Some(name.as_str()) { return; }
    crate::save_setting("crash_shown", name.clone().into());
    let Ok(text) = std::fs::read_to_string(folder().join(&name)) else { return };
    *CRASH.lock().unwrap() = format!("\n{text}");
    open(a);
    let r = a.main.global::<Report>();
    r.get_picked().set_row_data(5, true);
    r.set_description("App crash".into());
    r.set_note(crate::t(&a.tr, "crash_title").into());
    a.main.set_page(9);
}

/// `AA:BB:CC:DD:EE:FF` (or with `-`) -> `XX:XX:XX:XX:XX:XX`.
fn scrub_macs(s: &str) -> String {
    let b = s.as_bytes();
    let is_mac = |i: usize| i + 17 <= b.len()
        && (0..6).all(|k| b[i + 3 * k].is_ascii_hexdigit() && b[i + 3 * k + 1].is_ascii_hexdigit())
        && (0..5).all(|k| b[i + 3 * k + 2] == b':' || b[i + 3 * k + 2] == b'-')
        && (i == 0 || !b[i - 1].is_ascii_alphanumeric())
        && (i + 17 == b.len() || !b[i + 17].is_ascii_alphanumeric());
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < b.len() {
        if is_mac(i) { out += "XX:XX:XX:XX:XX:XX"; i += 17; continue; }
        let ch = s[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// `application/x-www-form-urlencoded`, as Java's `URLEncoder` writes it.
fn enc(s: &str) -> String {
    s.bytes().map(|c| match c {
        b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => (c as char).to_string(),
        b' ' => "+".into(),
        _ => format!("%{c:02X}"),
    }).collect()
}

fn dec(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => out.push(b' '),
            b'%' if i + 2 < b.len() => {
                out.push(u8::from_str_radix(&s[i + 1..i + 3], 16).unwrap_or(b'?'));
                i += 2;
            }
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// The form body. A log over [MAX_BODY] loses its oldest lines (the header stays).
fn body(model: &str, categories: &[&str], description: &str, log: Option<&str>) -> String {
    let mut fields = vec![(ENTRY_MODEL, model), (ENTRY_DESCRIPTION, description)];
    fields.extend(categories.iter().map(|c| (ENTRY_CATEGORY, *c)));
    let head = fields.iter().map(|(k, v)| format!("{k}={}", enc(v))).collect::<Vec<_>>().join("&");
    let Some(log) = log else { return head };
    let lines: Vec<&str> = log.lines().collect();
    let mut from = 1;
    loop {
        let kept = std::iter::once(lines[0]).chain(lines[from.min(lines.len())..].iter().copied()).collect::<Vec<_>>().join("\n");
        let body = format!("{head}&{ENTRY_LOG}={}", enc(&kept));
        if body.len() <= MAX_BODY || from >= lines.len() { return body; }
        // A tenth of what is left per pass, not one line.
        from += ((lines.len() - from) / 10).max(1);
    }
}

/// What the person reads before Send: one block per question, the log last.
fn preview(body: &str) -> String {
    body.split('&').map(|f| {
        let (k, v) = f.split_once('=').unwrap_or((f, ""));
        let name = match k { ENTRY_MODEL => "Model", ENTRY_CATEGORY => "Category", ENTRY_DESCRIPTION => "Description", _ => "Log" };
        format!("{name}:\n{}", dec(v))
    }).collect::<Vec<_>>().join("\n\n")
}

/// Posts [body] through `curl`'s standard input. True when Google took it.
fn send(body: String) -> bool {
    let child = crate::update::quiet("curl")
        .args(["-fsS", "--max-time", "30", "-o", if cfg!(windows) { "NUL" } else { "/dev/null" },
            "-H", "Content-Type: application/x-www-form-urlencoded; charset=UTF-8", "--data-binary", "@-", FORM])
        .stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    let Ok(mut child) = child else { return false };
    if child.stdin.take().map(|mut i| i.write_all(body.as_bytes())).is_none() { return false; }
    child.wait().is_ok_and(|s| s.success())
}

/// The current form as a body; None (with a note) while it has neither a category nor a description.
fn current(a: &App) -> Option<String> {
    let r = a.main.global::<Report>();
    let picked: Vec<&str> = (0..6).filter(|&i| r.get_picked().row_data(i).unwrap_or(false)).map(|i| CATEGORIES[i]).collect();
    let description = r.get_description().trim().to_string();
    if picked.is_empty() && description.is_empty() {
        r.set_note("Pick a category or describe the problem.".into());
        return None;
    }
    let log = r.get_with_log().then(|| log_text(a));
    Some(body(r.get_model().trim(), &picked, &description, log.as_deref()))
}

fn open(a: &mut App) {
    let r = a.main.global::<Report>();
    if r.get_model().is_empty() {
        r.set_model(a.snap.model.and_then(|m| m["name"].as_str()).unwrap_or(a.snap.name.as_str()).into());
    }
    r.set_note("".into());
    r.set_preview("".into());
}

pub fn setup(main: &MainWindow) {
    let r = main.global::<Report>();
    r.set_picked(VecModel::from_slice(&[false; 6]));
    r.on_open(|| with_app(open));
    r.on_toggle(|i| with_app(|a| {
        let p = a.main.global::<Report>().get_picked();
        let i = i as usize;
        p.set_row_data(i, !p.row_data(i).unwrap_or(false));
    }));
    r.on_show_preview(|| with_app(|a| {
        if let Some(b) = current(a) {
            let r = a.main.global::<Report>();
            r.set_note("".into());
            r.set_preview(preview(&b).into());
        }
    }));
    r.on_send(|| with_app(|a| {
        let Some(b) = current(a) else { return };
        let r = a.main.global::<Report>();
        r.set_busy(true);
        r.set_note("".into());
        let weak = a.main.as_weak();
        std::thread::spawn(move || {
            let ok = send(b);
            let _ = weak.upgrade_in_event_loop(move |m| {
                let r = m.global::<Report>();
                r.set_busy(false);
                if ok {
                    r.set_note("Report sent. Thank you!".into());
                    r.set_description("".into());
                    r.set_preview("".into());
                    r.set_picked(VecModel::from_slice(&[false; 6]));
                    CRASH.lock().unwrap().clear();
                } else {
                    r.set_note("Could not send the report. Check the internet connection and try again.".into());
                }
            });
        });
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macs_and_encoding() {
        assert_eq!(scrub_macs("to AA:bb:01:02:03:04 ok"), "to XX:XX:XX:XX:XX:XX ok");
        assert_eq!(scrub_macs("12:34:56.789 RX AA 07"), "12:34:56.789 RX AA 07");
        assert_eq!(dec(&enc("a b&c=ü\n")), "a b&c=ü\n");
    }

    #[test]
    fn long_log_keeps_header_and_newest() {
        let log = std::iter::once("HEADER".to_string()).chain((0..5000).map(|i| format!("line {i:05} xxxxxxxxxx"))).collect::<Vec<_>>().join("\n");
        let b = body("m", &["UI"], "d", Some(&log));
        assert!(b.len() <= MAX_BODY);
        let p = preview(&b);
        assert!(p.contains("Log:\nHEADER\n") && p.ends_with("line 04999 xxxxxxxxxx"));
        assert!(p.contains("Category:\nUI"));
    }
}
