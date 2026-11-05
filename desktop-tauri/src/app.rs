//! The app's own commands: settings, App update, Feedback, the daily count and starting with the computer.

use crate::tr;
use crate::update::{self, Release};
use serde_json::{json, Value};
use std::sync::Mutex;
use tauri::{AppHandle, Emitter};

/// (locale tag, "" = system default), in the order of the language list in the UI.
pub const LANGUAGES: &[&str] = &[
    "", "en", "zh-CN", "zh-TW", "ja", "ko", "in", "ms", "cs", "de", "es", "fil", "fr", "it", "hu", "nl", "pl", "pt", "ro", "sv", "vi",
    "tr", "el", "ru", "uk", "hi", "bn", "th",
];

/// The settings the page may write, with the type each one holds.
const KEYS: &[&str] = &["usage_ping", "clear_battery", "dev_tools_button", "update_auto"];

/// A reply of the developer to a report of this install, from the daily count.
static REPLY: Mutex<Option<String>> = Mutex::new(None);
/// The release the last update check found.
static FOUND: Mutex<Option<Release>> = Mutex::new(None);

/// Counts this install once a day: platform, version and the install ID (README, Privacy).
pub fn ping() {
    let st = crate::load_settings();
    if st["usage_ping"].as_bool() == Some(false) { return; }
    let today = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() / 86_400).unwrap_or(0);
    if st["usage_ping_day"].as_u64() == Some(today) { return; }
    let body = json!({
        "platform": if cfg!(windows) { "Windows" } else { "Linux" },
        "version": env!("CARGO_PKG_VERSION"),
        "id": crate::report::app_id(),
    });
    let (code, text) = crate::report::post_to("ping", &body.to_string());
    if code != 200 { return; }
    crate::save_setting("usage_ping_day", today.into());
    let reply = serde_json::from_str::<Value>(&text).ok().and_then(|v| v["reply"]["text"].as_str().map(String::from));
    if let Some(r) = reply.filter(|r| !r.trim().is_empty()) { *REPLY.lock().unwrap() = Some(r); }
}

#[tauri::command]
pub fn get_settings() -> Value {
    let st = crate::load_settings();
    let flag = |k: &str, d: bool| st[k].as_bool().unwrap_or(d);
    let lang = st["language"].as_str().unwrap_or("");
    json!({
        "version": env!("CARGO_PKG_VERSION"),
        "language": LANGUAGES.iter().position(|l| *l == lang).unwrap_or(0),
        "usage_ping": flag("usage_ping", true),
        "clear_battery": flag("clear_battery", false),
        "dev_tools_button": flag("dev_tools_button", false),
        "update_auto": flag("update_auto", true),
        "autostart": autostart_on(),
        "reply": REPLY.lock().unwrap().clone(),
        "canInstall": update::can_install(),
    })
}

#[tauri::command]
pub fn set_setting(key: String, value: Value) {
    if KEYS.contains(&key.as_str()) && value.is_boolean() { crate::save_setting(&key, value); }
}

/// Saves the language and tells every window to load its strings again.
#[tauri::command]
pub fn set_language(app: AppHandle, index: usize) {
    let Some(tag) = LANGUAGES.get(index) else { return };
    crate::save_setting("language", (*tag).into());
    crate::reset_translations();
    let _ = app.emit("reload", ());
}

/// A new random install ID: the next count is a new install.
#[tauri::command]
pub fn reset_install_id() {
    crate::save_setting("app_id", Value::Null);
    crate::save_setting("usage_ping_day", Value::Null);
}

#[tauri::command]
pub fn open_url(url: String) {
    if url.starts_with("https://") { update::open_url(&url); }
}

// ---- starting with the computer ----

#[cfg(not(windows))]
fn autostart_file() -> Option<std::path::PathBuf> {
    let dir = std::env::var_os("XDG_CONFIG_HOME").map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".config")))?;
    Some(dir.join("autostart").join("quickbuds.desktop"))
}

fn autostart_on() -> bool {
    #[cfg(not(windows))]
    { autostart_file().is_some_and(|f| f.exists()) }
    #[cfg(windows)]
    { update::quiet("reg").args(["query", r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run", "/v", "QuickBuds"]).output().is_ok_and(|o| o.status.success()) }
}

/// Starts QuickBuds in the tray when the user signs in. Windows: the Run key (untested on a real PC).
#[tauri::command]
pub fn set_autostart(on: bool) {
    let Ok(exe) = std::env::current_exe() else { return };
    // An AppImage runs from its own file, not from the unpacked binary.
    let exe = std::env::var_os("APPIMAGE").map(std::path::PathBuf::from).unwrap_or(exe);
    #[cfg(not(windows))]
    if let Some(f) = autostart_file() {
        if on {
            if let Some(d) = f.parent() { let _ = std::fs::create_dir_all(d); }
            let _ = std::fs::write(&f, format!("[Desktop Entry]\nType=Application\nName=QuickBuds\nExec=\"{}\" --tray\nTerminal=false\n", exe.display()));
        } else {
            let _ = std::fs::remove_file(f);
        }
    }
    #[cfg(windows)]
    {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
        if on {
            let _ = update::quiet("reg").args(["add", key, "/v", "QuickBuds", "/t", "REG_SZ", "/d", &format!("\"{}\" --tray", exe.display()), "/f"]).output();
        } else {
            let _ = update::quiet("reg").args(["delete", key, "/v", "QuickBuds", "/f"]).output();
        }
    }
}

// ---- App update ----

/// Looks for a newer release. Answer: { latest, newer, error }.
#[tauri::command]
pub async fn update_check() -> Value {
    // A test of the update page: `QB_FAKE_UPDATE=1` offers a newer version and "downloads" it. Nothing is installed.
    if std::env::var_os("QB_FAKE_UPDATE").is_some() {
        *FOUND.lock().unwrap() = Some(Release { version: "99.0.0".into(), url: String::new(), sums: None, notes: "## Fake update\n- A test of the update page.\n- Nothing is installed.".into() });
        return json!({ "latest": "99.0.0", "newer": true, "notes": "## Fake update\n- A test of the update page.\n- Nothing is installed." });
    }
    update::clean_up();
    match update::latest() {
        Ok(r) => {
            let newer = update::newer(&r.version, env!("CARGO_PKG_VERSION"));
            let v = json!({ "latest": r.version, "newer": newer, "notes": r.notes });
            *FOUND.lock().unwrap() = newer.then_some(r);
            v
        }
        Err(e) => json!({ "error": e }),
    }
}

/// Downloads the release, checks its SHA256SUMS, replaces the program and quits so the new one runs alone.
/// Where the program cannot replace itself, it opens the release page.
#[tauri::command]
pub async fn update_install(app: AppHandle) -> Value {
    let Some(r) = FOUND.lock().unwrap().take() else { return json!({ "error": "no release" }) };
    if std::env::var_os("QB_FAKE_UPDATE").is_some() {
        for i in 0..=50u64 { let _ = app.emit("update_progress", json!({ "done": i, "total": 50 })); tokio_sleep(80).await; }
        *FOUND.lock().unwrap() = Some(r);
        return json!({ "error": "fake update: nothing was installed" });
    }
    if !update::can_install() { update::open_page(); *FOUND.lock().unwrap() = Some(r); return json!({ "page": true }); }
    let a2 = app.clone();
    let sent = move |done: u64, total: u64| { let _ = a2.emit("update_progress", json!({ "done": done, "total": total })); };
    match tauri::async_runtime::spawn_blocking(move || { let res = update::install(&r, &sent); (res, r) }).await {
        Ok((Ok(()), _)) => { app.exit(0); json!({}) }
        Ok((Err(e), r)) => { *FOUND.lock().unwrap() = Some(r); json!({ "error": e }) }
        Err(e) => json!({ "error": e.to_string() }),
    }
}

async fn tokio_sleep(ms: u64) { let _ = tauri::async_runtime::spawn_blocking(move || std::thread::sleep(std::time::Duration::from_millis(ms))).await; }

// ---- Feedback ----

/// The form of the Feedback page checked and made into a report: the draft, or the note to show.
fn draft(snap: &crate::session::Snapshot, form: &Value) -> Result<crate::report::Draft, String> {
    let model = form["model"].as_str().unwrap_or("").trim().to_string();
    let description = form["description"].as_str().unwrap_or("").trim().to_string();
    let categories = form["categories"].as_array().into_iter().flatten().filter_map(|i| i.as_u64())
        .filter_map(|i| crate::report::CATEGORIES.get(i as usize).copied()).collect();
    if !crate::report::known(&model) {
        return Err(format!("{}\n{}: {}#supported-earbuds", tr("problem_unsupported"), tr("problem_supported_list"),
            update::PAGE.trim_end_matches("/releases/latest")));
    }
    if description.is_empty() { return Err(tr("problem_description_hint")); }
    let with_log = form["log"].as_bool().unwrap_or(true);
    if with_log && !crate::session::LOG.lock().unwrap().iter().any(|l| l.dir == "RX") { return Err(tr("problem_log_empty")); }
    let log = with_log.then(|| crate::report::log_text(snap));
    Ok(crate::report::Draft { model, categories, description, log, crash: false })
}

/// What the report would send: { text } or { note }.
#[tauri::command]
pub fn report_preview(link: tauri::State<crate::link::Link>, form: Value) -> Value {
    match draft(&link.snapshot(), &form) { Ok(d) => json!({ "text": crate::report::preview(&d) }), Err(note) => json!({ "note": note }) }
}

/// Sends the report. Answer: { ok } or { note }.
#[tauri::command]
pub async fn report_send(link: tauri::State<'_, crate::link::Link>, form: Value) -> Result<Value, ()> {
    let d = match draft(&link.snapshot(), &form) { Ok(d) => d, Err(note) => return Ok(json!({ "note": note })) };
    let (ok, message) = crate::report::send(d);
    Ok(if ok { json!({ "ok": true }) } else { json!({ "note": message.unwrap_or_else(|| tr("problem_failed")) }) })
}

/// What the test would send: { text }, or {} before the test ran.
#[tauri::command]
pub fn test_preview(link: tauri::State<crate::link::Link>) -> Value {
    match crate::modeltest::preview(&link.snapshot()) { Some(t) => json!({ "text": t }), None => json!({}) }
}

/// A script error of the page, printed under `QB_DEBUG=1`.
#[tauri::command]
pub fn js_log(msg: String) { if std::env::var_os("QB_DEBUG").is_some() { eprintln!("js: {msg}"); } }
