//! QuickBuds for the desktop, Tauri UI. The frontend is in `ui/`.
#![cfg_attr(windows, windows_subsystem = "windows")]
#![allow(dead_code)]

mod bt;
mod devlog;
mod gesture_logic;
mod hearing_logic;
mod home_logic;
mod protocol;
mod session;
mod types;

#[cfg(target_os = "linux")]
mod blur;
mod app;
mod hearing;
mod modeltest;
mod link;
mod pages;
mod report;
mod support;
mod update;
#[cfg(target_os = "linux")]
mod tray;

pub use buds_core::{cmd, msg, wire};

pub mod icons { include!(concat!(env!("OUT_DIR"), "/icons.rs")); }
pub mod strings { include!(concat!(env!("OUT_DIR"), "/strings.rs")); }

use tauri::{AppHandle, Manager, PhysicalPosition, RunEvent, WebviewWindow, WebviewWindowBuilder, WindowEvent};

// ---- helpers `session` expects at the crate root (the same as in `desktop`) ----

#[cfg(not(windows))]
fn local_now() -> [u32; 7] {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default();
    // SAFETY: localtime_r fills the zeroed tm from a valid time_t.
    let tm = unsafe {
        let t = now.as_secs() as libc::time_t;
        let mut tm: libc::tm = std::mem::zeroed();
        libc::localtime_r(&t, &mut tm);
        tm
    };
    [tm.tm_year as u32 + 1900, tm.tm_mon as u32 + 1, tm.tm_mday as u32, tm.tm_hour as u32, tm.tm_min as u32, tm.tm_sec as u32, now.subsec_millis()]
}

#[cfg(windows)]
fn local_now() -> [u32; 7] {
    // SAFETY: GetLocalTime fills the zeroed SYSTEMTIME.
    let st = unsafe {
        let mut st = std::mem::zeroed();
        windows_sys::Win32::System::SystemInformation::GetLocalTime(&mut st);
        st
    };
    [st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds].map(u32::from)
}

/// Without an "in ear" seen, "out" may only mean the model has no sensor: shown as in the ear (the app's rule).
fn wear_sensor_seen(s: &session::Snapshot) -> bool {
    let Some(id) = s.model.and_then(|m| m["id"].as_str()) else { return true };
    let mut seen = load_settings()["wear_seen"].clone();
    if seen[id] == true { return true; }
    if !s.wear.iter().any(|w| matches!(w, 3 | 7)) { return false; }
    seen[id] = true.into();
    save_setting("wear_seen", seen);
    true
}

/// `settings.json` in the user's config folder, shared with the other desktop apps.
fn settings_path() -> Option<std::path::PathBuf> {
    #[cfg(windows)]
    let dir = std::env::var_os("APPDATA").map(|d| std::path::PathBuf::from(d).join("QuickBuds"));
    #[cfg(not(windows))]
    let dir = std::env::var_os("XDG_CONFIG_HOME").map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".config")))
        .map(|d| d.join("quickbuds"));
    dir.map(|d| d.join("settings.json"))
}

fn load_settings() -> serde_json::Value {
    settings_path().and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_else(|| serde_json::json!({}))
}

fn save_setting(key: &str, value: serde_json::Value) {
    let Some(path) = settings_path() else { return };
    let mut all = load_settings();
    all[key] = value;
    if let Some(dir) = path.parent() { let _ = std::fs::create_dir_all(dir); }
    let _ = std::fs::write(path, serde_json::to_string_pretty(&all).unwrap_or_default());
}

// ---- strings ----

fn locale_for(tag: &str) -> &'static str {
    let tag = tag.replace('_', "-");
    let parts: Vec<&str> = tag.split(['-', '.']).collect();
    let lang = match parts[0].to_lowercase().as_str() {
        "zh" if parts.iter().any(|p| ["Hant", "TW", "HK", "MO"].contains(p)) => "zh-TW".to_string(),
        "zh" => "zh-CN".into(),
        "id" => "in".into(),
        "tl" => "fil".into(),
        l => l.into(),
    };
    strings::LOCALES.iter().find(|l| l.0 == lang).map_or("", |l| l.0)
}

/// The strings of the saved language (`settings.json` "language"), else the system's. A key a locale lacks falls back to English.
fn translations() -> std::sync::Arc<Vec<String>> {
    if let Some(t) = TRANSLATIONS.lock().unwrap().as_ref() { return t.clone(); }
    let saved = load_settings()["language"].as_str().unwrap_or("").to_string();
    let locale = locale_for(&if saved.is_empty() { sys_locale::get_locale().unwrap_or_default() } else { saved });
    let table = |tag: &str| strings::LOCALES.iter().find(|l| l.0 == tag).map(|l| l.1);
    let base = table("").unwrap();
    let chosen = table(locale).unwrap_or(base);
    let all: std::sync::Arc<Vec<String>> = std::sync::Arc::new(chosen.iter().zip(base).map(|(c, b)| c.or(*b).unwrap_or("").to_string()).collect());
    *TRANSLATIONS.lock().unwrap() = Some(all.clone());
    all
}

static TRANSLATIONS: std::sync::Mutex<Option<std::sync::Arc<Vec<String>>>> = std::sync::Mutex::new(None);

/// The language changed: the next `tr` reads the strings again.
pub fn reset_translations() { *TRANSLATIONS.lock().unwrap() = None; }

/// One string of the chosen language; an unknown key shows as itself.
pub fn tr(key: &str) -> String {
    strings::KEYS.iter().position(|k| *k == key).map_or_else(|| key.to_string(), |i| translations()[i].clone())
}

// ---- windows ----

/// Corner radius per window in logical px. The CSS uses the same values.
fn radius(label: &str) -> i32 { if label == "panel" { 24 } else { 22 } }

/// The window of this label. A window that is closed has no web engine running, so it is built again on demand.
fn window(app: &AppHandle, label: &str) -> Option<WebviewWindow> {
    if let Some(w) = app.get_webview_window(label) { return Some(w); }
    let cfg = app.config().app.windows.iter().find(|w| w.label == label)?.clone();
    let w = WebviewWindowBuilder::from_config(app, &cfg).ok()?.build().ok()?;
    style(&w, label);
    Some(w)
}

/// The blur behind the window, and on Linux the panel's place in the corner.
fn style(w: &WebviewWindow, label: &str) {
    #[cfg(target_os = "linux")]
    {
        // WebKit's default draws on the GPU only when it thinks the page needs it. The UI has many animations.
        let _ = w.with_webview(|wv| {
            use webkit2gtk::{HardwareAccelerationPolicy, SettingsExt, WebViewExt};
            if let Some(st) = wv.inner().settings() { st.set_hardware_acceleration_policy(HardwareAccelerationPolicy::Always); }
        });
        let gw = w.gtk_window().expect("gtk window");
        // The panel sits in the bottom right corner, pinned by the compositor (KDE, wlroots). Others place it themselves.
        if label == "panel" && gtk_layer_shell::is_supported() {
            use gtk::prelude::WidgetExt;
            use gtk_layer_shell::{Edge, KeyboardMode, Layer, LayerShell};
            // The layer role needs a window that is not realized, and the compositor needs a size to map it.
            gw.unrealize();
            gw.set_size_request(350, 360);
            gw.init_layer_shell();
            gw.set_layer(Layer::Overlay);
            gw.set_namespace("quickbuds-panel");
            gw.set_keyboard_mode(KeyboardMode::OnDemand);
            // The compositor already keeps the panel clear of the taskbar: a small bottom margin leaves a small gap, not a wide one.
            gw.set_anchor(Edge::Bottom, true); gw.set_layer_shell_margin(Edge::Bottom, 0);
            gw.set_anchor(Edge::Right, true); gw.set_layer_shell_margin(Edge::Right, 12);
        }
        blur::enable(&gw, radius(label));
    }
    #[cfg(windows)]
    {
        use tauri::{utils::config::WindowEffectsConfig, window::Effect};
        let _ = label;
        let _ = w.set_effects(WindowEffectsConfig { effects: vec![Effect::Acrylic], state: None, radius: None, color: None, interactive: false });
    }
    #[cfg(not(any(target_os = "linux", windows)))]
    let _ = (w, label);
}

/// When the panel last closed because it lost focus. A click on the tray icon takes the focus first,
/// so the click that closes the panel must not open it again.
static PANEL_CLOSED: std::sync::Mutex<Option<std::time::Instant>> = std::sync::Mutex::new(None);
/// What a window needs to open without a blank frame, and to give its web engine back when it stays hidden.
struct Slot {
    /// A new window stays hidden until its page has drawn (`main_ready`, `fit_panel`).
    pending: std::sync::atomic::AtomicBool,
    /// Counts the openings and closings: a hidden window is destroyed 45 s later unless it was shown again.
    gen: std::sync::atomic::AtomicU64,
}
impl Slot {
    const fn new() -> Slot { Slot { pending: std::sync::atomic::AtomicBool::new(false), gen: std::sync::atomic::AtomicU64::new(0) } }
}
static MAIN: Slot = Slot::new();
static PANEL: Slot = Slot::new();

/// Opens the quick panel. A panel that is still alive shows at once; a new one shows when its page is ready.
fn show_panel(app: &AppHandle) {
    use std::sync::atomic::Ordering::SeqCst;
    if PANEL_CLOSED.lock().unwrap().is_some_and(|t| t.elapsed() < std::time::Duration::from_millis(300)) { return; }
    PANEL.gen.fetch_add(1, SeqCst);
    let fresh = app.get_webview_window("panel").is_none();
    let Some(p) = window(app, "panel") else { return };
    if fresh { PANEL.pending.store(true, SeqCst); } else { place_panel(&p); }
}

/// Puts the panel in the bottom right corner of the work area, above the taskbar, and shows it.
fn place_panel(p: &WebviewWindow) {
    if let (Ok(Some(m)), Ok(size)) = (p.current_monitor(), p.outer_size()) {
        let wa = m.work_area();
        let margin = (12.0 * m.scale_factor()) as i32;
        let x = wa.position.x + wa.size.width as i32 - size.width as i32 - margin;
        let y = wa.position.y + wa.size.height as i32 - size.height as i32 - margin;
        let _ = p.set_position(PhysicalPosition::new(x, y));
    }
    let _ = p.show();
    let _ = p.set_focus();
}

/// Hides a window. It stays alive for 45 s, so the next open is fast; then its web engine goes.
fn hide_later(app: &AppHandle, label: &'static str, slot: &'static Slot) {
    use std::sync::atomic::Ordering::SeqCst;
    let Some(w) = app.get_webview_window(label) else { return };
    let _ = w.hide();
    let gen = slot.gen.fetch_add(1, SeqCst) + 1;
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_secs(45));
        if slot.gen.load(SeqCst) == gen { if let Some(w) = app.get_webview_window(label) { let _ = w.close(); } }
    });
}

fn dismiss_panel(app: &AppHandle) {
    *PANEL_CLOSED.lock().unwrap() = Some(std::time::Instant::now());
    hide_later(app, "panel", &PANEL);
}

/// Opens the main window. A window that is still alive shows at once; a new one shows when its page is ready.
fn show_main(app: &AppHandle) {
    use std::sync::atomic::Ordering::SeqCst;
    MAIN.gen.fetch_add(1, SeqCst);
    let fresh = app.get_webview_window("main").is_none();
    let Some(w) = window(app, "main") else { return };
    if fresh { MAIN.pending.store(true, SeqCst); } else { let _ = w.show(); let _ = w.set_focus(); }
}

/// The main page has drawn: a new window shows now.
#[tauri::command]
fn main_ready(app: AppHandle) {
    if MAIN.pending.swap(false, std::sync::atomic::Ordering::SeqCst) {
        if let Some(w) = app.get_webview_window("main") { let _ = w.show(); let _ = w.set_focus(); }
    }
}

/// The panel's "Open QuickBuds" button.
#[tauri::command]
fn open_main(app: AppHandle) {
    dismiss_panel(&app);
    show_main(&app);
}

#[tauri::command]
fn quit(app: AppHandle) { app.exit(0); }

/// Counts the window size changes: a newer change stops an older animation.
static SIZE_GEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// The main window has a fixed size the user cannot change. The pixel style asks for a larger one: its dot text is wide.
/// The window grows or shrinks over 250 ms (ease out), not in one jump.
#[tauri::command]
fn main_size(app: AppHandle, width: f64, height: f64) {
    use std::sync::atomic::Ordering;
    let Some(w) = app.get_webview_window("main") else { return };
    let gen = SIZE_GEN.fetch_add(1, Ordering::SeqCst) + 1;
    std::thread::spawn(move || {
        let scale = w.scale_factor().unwrap_or(1.0);
        let (fw, fh) = w.inner_size().map(|s| (s.width as f64 / scale, s.height as f64 / scale)).unwrap_or((width, height));
        // A window that is not resizable ignores set_size on some systems: allow it while it animates.
        let _ = w.set_resizable(true);
        const STEPS: u32 = 16;
        for i in 1..=STEPS {
            if SIZE_GEN.load(Ordering::SeqCst) != gen { return; }
            let t = f64::from(i) / f64::from(STEPS);
            let e = 1.0 - (1.0 - t).powi(3);
            let _ = w.set_size(tauri::LogicalSize::new(fw + (width - fw) * e, fh + (height - fh) * e));
            std::thread::sleep(std::time::Duration::from_millis(16));
        }
        let _ = w.set_resizable(false);
    });
}

/// Sizes the panel to its content. The panel has no frame, so the user cannot resize it by hand.
#[tauri::command]
fn fit_panel(app: AppHandle, height: f64) {
    let Some(p) = app.get_webview_window("panel") else { return };
    let _ = p.set_size(tauri::LogicalSize::new(350.0, height));
    // A layer-shell surface takes its size from the size request.
    #[cfg(target_os = "linux")]
    {
        use gtk::prelude::WidgetExt;
        let win = p.clone();
        let _ = p.run_on_main_thread(move || { if let Ok(g) = win.gtk_window() { g.set_size_request(350, height as i32); } });
    }
    if PANEL.pending.swap(false, std::sync::atomic::Ordering::SeqCst) || p.is_visible().unwrap_or(false) { place_panel(&p); }
}

/// Windows: the page needs the WebView2 runtime (Windows 11 has it; an older Windows 10 may not).
/// Without it the app would not open and show nothing. This says what is missing and opens the download page.
#[cfg(windows)]
fn check_webview2() {
    let key = |hive: &str, path: &str| update::quiet("reg").args(["query", &format!("{hive}\\{path}"), "/v", "pv"]).output().is_ok_and(|o| o.status.success());
    let id = r"SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    let wow = r"SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
    if key("HKLM", wow) || key("HKLM", id) || key("HKCU", id) { return; }
    use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, IDOK, MB_ICONERROR, MB_OKCANCEL};
    let wide = |s: &str| s.encode_utf16().chain(std::iter::once(0)).collect::<Vec<u16>>();
    let text = wide("QuickBuds needs the Microsoft WebView2 runtime. Choose OK to open the download page, then start QuickBuds again.");
    let title = wide("QuickBuds");
    let r = unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_OKCANCEL | MB_ICONERROR) };
    if r == IDOK { update::open_url("https://developer.microsoft.com/microsoft-edge/webview2/#download"); }
    std::process::exit(1);
}

fn main() {
    #[cfg(windows)]
    check_webview2();
    // Wayland finds the window icon through the application ID: the program name. `quickbuds.desktop` (install script, AppImage) names the icon.
    #[cfg(target_os = "linux")]
    gtk::glib::set_prgname(Some("quickbuds"));
    let app = tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![fit_panel, main_size, main_ready, open_main, quit, link::get_snapshot, link::get_log, link::send, link::get_strings, app::get_settings, app::set_setting, app::set_language, app::reset_install_id, app::open_url, app::set_autostart, app::update_check, app::update_install, app::report_preview, app::report_send, app::test_preview, app::js_log, support::support_due, support::support_off])
        .setup(|app| {
            hearing::init(app.handle());
            modeltest::init(app.handle());
            link::start(app.handle());
            std::thread::spawn(app::ping);

            // Windows: the icon opens the panel on a left or a right click. Linux has `tray.rs`.
            #[cfg(windows)]
            tauri::tray::TrayIconBuilder::new()
                .icon(tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png")).expect("tray icon"))
                .tooltip("QuickBuds")
                .on_tray_icon_event(|tray, e| {
                    if let tauri::tray::TrayIconEvent::Click { button_state: tauri::tray::MouseButtonState::Up, .. } = e {
                        show_panel(tray.app_handle());
                    }
                })
                .build(app)?;
            #[cfg(target_os = "linux")]
            tray::start(app.handle());

            // `--panel` opens the quick panel at start, for tests on a desktop without tray clicks.
            if std::env::args().any(|a| a == "--panel") { show_panel(app.handle()); } else if !std::env::args().any(|a| a == "--tray") { show_main(app.handle()); }
            Ok(())
        })
        // The panel closes when the user clicks elsewhere: a closed window runs no web engine.
        .on_window_event(|w, e| match (w.label(), e) {
            ("panel", WindowEvent::Focused(false)) => dismiss_panel(w.app_handle()),
            // The close button hides the window; the tray keeps the app running.
            ("main", WindowEvent::CloseRequested { api, .. }) => { api.prevent_close(); hide_later(w.app_handle(), "main", &MAIN); }
            _ => {}
        })
        .build(tauri::generate_context!())
        .expect("build QuickBuds");
    app.run(|_, e| {
        // Closing the last window leaves the app in the tray; the Quit item exits with a code.
        if let RunEvent::ExitRequested { code: None, api, .. } = e { api.prevent_exit(); }
    });
}
