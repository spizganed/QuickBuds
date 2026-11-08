//! The report path for Feedback and Test my earbuds: header, scrubbed packet log, preview, Worker post.

//! 



use crate::session::{Line, Snapshot, LOG};

use crate::types::*;

use std::io::Write;

use std::process::Stdio;



const WORKER: &str = "https://quickbuds-report.spizganed.workers.dev/report";
/// The Category options, in the Worker's own words; it drops any other value (report-worker/src/index.js).
pub const CATEGORIES: [&str; 6] = ["UI", "Lag", "Connection", "Feature not working", "Battery", "Other"];
/// Their labels, the phone's `problem_cat_*` strings.
pub const CATEGORY_KEYS: [&str; 6] = ["problem_cat_ui", "problem_cat_lag", "problem_cat_connection",
    "problem_cat_feature", "problem_cat_battery", "problem_cat_other"];

/// The first line of a sent or exported log: app, computer, system, buds model and firmware.
pub fn header(s: &Snapshot) -> String {
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

/// Device list and device manager packets (PROTOCOL.md section 19) hold addresses and names: only the header stays.
/// The `0x0204` push carries them in sub `06` (device list) and sub `F2` (connected-devices info).
fn device_bytes(l: &Line) -> bool {
    if l.bytes.len() < 10 || l.bytes[0] != 0xAA { return false; }
    let pl = payload_of(&l.bytes);
    matches!(cmd_of(&l.bytes), 0x8112 | 0x0429 | 0x8132)
        || (cmd_of(&l.bytes) == EVT_PUSH && matches!(pl.first(), Some(&0x06) | Some(&0xF2)))
}


/// The log as sent: header, every line, without addresses, device data or this computer's name.
pub fn log_text(s: &Snapshot) -> String {
    scrub(header(s) + "\n" + &lines(&LOG.lock().unwrap()))
}


fn lines(log: &[Line]) -> String {
    log.iter().map(|l| if device_bytes(l) {
        format!("{} {} {} [device data removed]\n", l.at, l.dir, hex(&l.bytes[..l.bytes.len() - payload_of(&l.bytes).len()]))
    } else {
        crate::devlog::raw(l) + "\n"
    }).collect()
}

fn scrub(text: String) -> String {
    let text = scrub_macs(&text);
    match host() { Some(h) => text.replace(&h, "[this computer]"), None => text }
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

/// One report; [Draft::log] is None when the person turned it off.
pub(crate) struct Draft {
    pub model: String,
    pub categories: Vec<&'static str>,
    pub description: String,
    pub log: Option<String>,
    pub crash: bool,
}

/// What the person reads before Send: one block per field, the log last.
pub fn preview(d: &Draft) -> String {
    let mut blocks = vec![format!("Model:\n{}", d.model)];
    if !d.categories.is_empty() { blocks.push(format!("Category:\n{}", d.categories.join(", "))); }
    blocks.push(format!("Description:\n{}", d.description));
    if let Some(log) = &d.log { blocks.push(format!("Log:\n{log}")); }
    blocks.join("\n\n")
}

/// Posts [body] to [url] through `curl`'s standard input. The answer code (0: no connection) and text.
fn post(url: &str, body: &str) -> (u16, String) {
    let child = crate::update::quiet("curl")
        .args(["-sS", "--max-time", "30", "-w", "\n%{http_code}", "-H", "Content-Type: application/json; charset=UTF-8", "--data-binary", "@-", url])
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn();
    let Ok(mut child) = child else { return (0, String::new()) };
    if child.stdin.take().map(|mut i| i.write_all(body.as_bytes())).is_none() { return (0, String::new()); }
    let out = child.wait_with_output().map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    let (text, code) = out.rsplit_once('\n').unwrap_or(("", out.as_str()));
    (code.trim().parse().unwrap_or(0), text.to_string())
}

/// A random version 4 UUID. `RandomState` is seeded by the system's random source: two of them give 128 random bits, with no extra crate.
fn new_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let r = |_: u8| std::collections::hash_map::RandomState::new().build_hasher().finish();
    let (a, b) = (r(0), r(1));
    format!("{:08x}-{:04x}-4{:03x}-{:04x}-{:012x}", (a >> 32) as u32, (a >> 16) as u16, (a & 0xfff) as u16,
        0x8000 | (b >> 48) as u16 & 0x3fff, b & 0xffff_ffff_ffff)
}

/// The install ID: a random UUID made on the first start and kept in `settings.json` ("app_id"). It ties to no device,
/// account or address. Sent with the daily ping and with a problem report. Delete the key to get a new one.
pub fn app_id() -> String {
    if let Some(id) = crate::load_settings()["app_id"].as_str() { return id.to_string(); }
    let id = new_id();
    crate::save_setting("app_id", id.clone().into());
    id
}


/// Posts [d] to the Worker.
/// Returns (sent, the Worker's answer text when it refused the report).
pub fn send(d: Draft) -> (bool, Option<String>) {
    let json = serde_json::json!({
        "app": format!("QuickBuds {} {}", env!("CARGO_PKG_VERSION"), if cfg!(windows) { "Windows" } else { "Linux" }),
        "model": d.model, "categories": d.categories, "description": d.description, "log": d.log, "crash": d.crash,
        "id": app_id(),
    });
    let (code, text) = post(WORKER, &json.to_string());
    let message = serde_json::from_str::<serde_json::Value>(&text).ok()
        .and_then(|v| v["message"].as_str().map(String::from));
    (code == 200, if code == 200 { None } else { message })
}

/// A model name from the list, any case.
pub fn known(model: &str) -> bool {
    models().iter().any(|m| m["name"].as_str().is_some_and(|n| n.eq_ignore_ascii_case(model)))
}

/// Posts [body] to the Worker route `report` or `ping`.
pub fn post_to(route: &str, body: &str) -> (u16, String) { post(&WORKER.replace("/report", &format!("/{route}")), body) }
