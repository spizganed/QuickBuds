//! QuickBuds for the desktop, egui port. The link thread, the protocol and the model data are the ones of `desktop`
//! (included from there); this crate holds the Glass UI.
#![cfg_attr(windows, windows_subsystem = "windows")]
#![allow(dead_code)]

#[path = "../../desktop/src/bt.rs"]
mod bt;
#[path = "../../desktop/src/devlog.rs"]
mod devlog;
#[path = "../../desktop/src/gesture_logic.rs"]
mod gesture_logic;
#[path = "../../desktop/src/hearing_logic.rs"]
mod hearing_logic;
#[path = "../../desktop/src/home_logic.rs"]
mod home_logic;
#[path = "../../desktop/src/protocol.rs"]
mod protocol;
#[path = "../../desktop/src/session.rs"]
mod session;
#[path = "../../desktop/src/types.rs"]
mod types;

mod about;
mod appearance;
mod dev;
mod dual;
mod feedback;
mod earbuds;
mod eq;
mod fonts;
mod gestures;
mod issues;
mod language;
mod layout;
mod glass;
mod hearing;
mod models;
mod motion;
mod notice;
mod overview;
mod settings;
mod palette;
mod panel;
mod phone;
mod preset_edit;
mod report;
mod setup;
mod sheets;
mod test;
mod token_sheet;
mod tokens;
mod tray;
mod update;
mod update_core;

pub use buds_core::{cmd, msg, wire};

pub mod icons { include!(concat!(env!("OUT_DIR"), "/icons.rs")); }
pub mod strings { include!(concat!(env!("OUT_DIR"), "/strings.rs")); }

use eframe::egui;
use session::{Cmd, Snapshot, Status};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use types::AncModes;

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

/// The desktop's reduced-motion setting. egui has no input for it; GNOME holds the choice on Linux.
fn os_reduced_motion() -> bool {
    #[cfg(target_os = "linux")]
    {
        let out = std::process::Command::new("gsettings")
            .args(["get", "org.gnome.desktop.interface", "enable-animations"]).output();
        return out.is_ok_and(|o| String::from_utf8_lossy(&o.stdout).trim() == "false");
    }
    #[allow(unreachable_code)]
    false
}

/// `settings.json` in the user's config folder, shared with the Slint app.
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

/// The saved language (`settings.json` "language"), else the system's.
fn chosen_locale() -> &'static str {
    let saved = load_settings()["language"].as_str().unwrap_or("").to_string();
    locale_for(&if saved.is_empty() { sys_locale::get_locale().unwrap_or_default() } else { saved })
}

/// The strings of one locale; a key it lacks falls back to English.
fn translations(locale: &str) -> Vec<String> {
    let table = |tag: &str| strings::LOCALES.iter().find(|l| l.0 == tag).map(|l| l.1);
    let base = table("").unwrap();
    let chosen = table(locale).unwrap_or(base);
    chosen.iter().zip(base).map(|(c, b)| c.or(*b).unwrap_or("").to_string()).collect()
}

pub fn t<'a>(tr: &'a [String], key: &str) -> &'a str {
    &tr[strings::KEYS.iter().position(|k| *k == key).expect(key)]
}

// ---- app ----

#[derive(Clone, Copy, PartialEq)]
pub enum Page {
    Overview, Eq, Gestures, Hearing, Dual, Earbuds, Models, Settings, Appearance, Issues, About, Language, Dev,
    Setup, Layout, PresetEdit, Test, Feedback, Update,
}

/// The page named by `QB_PAGE`, for screenshots.
fn page_from_key(key: &str) -> Option<Page> {
    Some(match key {
        "overview" => Page::Overview,
        "settings" => Page::Settings,
        "appearance" => Page::Appearance,
        "issues" => Page::Issues,
        "about" => Page::About,
        "language" => Page::Language,
        "setup" => Page::Setup,
        "layout" => Page::Layout,
        "preset" => Page::PresetEdit,
        "test" => Page::Test,
        "feedback" => Page::Feedback,
        "update" => Page::Update,
        _ => return None,
    })
}

/// What the connected buds offer; decides which Overview rows show.
#[derive(Clone, Copy, PartialEq)]
pub struct Has { eq: bool, controls: bool, hearing: bool, dual: bool }

impl Has {
    const ALL: Has = Has { eq: true, controls: true, hearing: true, dual: true };
    fn of(s: &Snapshot) -> Has {
        Has {
            eq: types::eq_has(s.model, &s.caps),
            controls: gestures::available(s),
            hearing: types::has_feature(&s.features, types::FEATURE_HEARING),
            dual: types::has_feature(&s.features, types::FEATURE_DUAL),
        }
    }
}

pub struct App {
    pub snap: Snapshot,
    pub tx: Sender<Cmd>,
    pub tr: Vec<String>,
    inbox: Arc<Mutex<Option<Snapshot>>>,
    pub page: Page,
    pub has: Has,
    /// Modes of the last buds seen: the controls stay in place (dimmed) while disconnected.
    pub modes: AncModes,
    pub last_level: Option<String>,
    /// The noise control shows the ANC levels instead of the modes.
    pub levels_open: bool,
    pub glow: bool,
    pub demo: bool,
    /// Writes that wait for the user's accept (the buds reconnect), and the string that says why.
    pub confirm: Option<Confirm>,
    /// The Overview's scroll offset in points.
    pub scroll: f32,
    pub shown_page: Page,
    /// Counts page changes: each one gets a fresh motion key.
    pub page_seq: u64,
    pub page_since: std::time::Instant,
    pub eq: eq::State,
    pub dual: dual::State,
    pub earbuds: earbuds::State,
    pub dev: dev::State,
    pub gestures: gestures::State,
    pub settings: settings::State,
    pub appearance: appearance::State,
    pub style: glass::style::Style,
    /// What the style was last made for: (system light, saved settings stamp).
    style_for: bool,
    pub hearing: hearing::State,
    pub setup: setup::State,
    pub layout: layout::State,
    pub preset: preset_edit::State,
    pub test: test::State,
    pub feedback: feedback::State,
    pub update: update::State,
    pub sheets: sheets::State,
    /// The tray icon and the events it sends.
    pub tray: tray::Tray,
    /// The quick panel of the tray (Windows).
    pub panel: panel::Panel,
    /// Quit was asked: the close that follows must not hide the window.
    quitting: bool,
    /// The short hints for a refused write and for an ANC write with no bud in an ear.
    pub toast: notice::State,
    /// The disconnect pill asked; Cancel keeps the link.
    pub disconnect_ask: bool,
    /// The codec picker of a `hiRes` model is open.
    pub codec_pick: bool,
    /// The 3D audio type picker of a head tracking model is open.
    pub spatial_pick: bool,
}

/// A write that waits for the user's accept: the buds reconnect, or it makes a sound in the ears.
pub struct Confirm { pub cmds: Vec<Cmd>, pub title: &'static str, pub msg: &'static str, pub accept: &'static str }

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        // egui 0.37 has no reduced-motion input: the settings key, else the desktop's own setting.
        motion::set_reduced_motion(load_settings()["reduced_motion"].as_bool().unwrap_or(false) || os_reduced_motion());
        glass::install(cc);
        egui_extras::install_image_loaders(&cc.egui_ctx);
        let inbox = Arc::new(Mutex::new(None));
        let demo = std::env::var_os("QB_DEMO").is_some();
        let tx = if demo {
            // A fixed snapshot and no link: for screenshots and UI tests, so no buds are touched.
            *inbox.lock().unwrap() = Some(overview::demo_snapshot());
            std::sync::mpsc::channel().0
        } else {
            let (inbox, ctx) = (inbox.clone(), cc.egui_ctx.clone());
            session::spawn(move |s| { *inbox.lock().unwrap() = Some(s); ctx.request_repaint(); })
        };
        let glow = load_settings()["glass_gradient"].as_bool().unwrap_or(true);
        let tr = translations(chosen_locale());
        let tray = tray::Tray::new(&cc.egui_ctx, &tr);
        let mut app = Self {
            snap: Snapshot::default(), tx, tr, inbox, page: Page::Overview, has: Has::ALL,
            modes: AncModes::default(), last_level: None, levels_open: false, glow, demo, confirm: None, scroll: 0.0, shown_page: Page::Overview, page_seq: 0, page_since: std::time::Instant::now(), eq: Default::default(), dual: Default::default(), earbuds: Default::default(), dev: Default::default(), gestures: Default::default(), settings: Default::default(), appearance: Default::default(), hearing: Default::default(), setup: Default::default(), layout: Default::default(), preset: Default::default(),
            test: Default::default(), feedback: Default::default(), update: Default::default(), sheets: Default::default(), tray, toast: Default::default(),
            panel: Default::default(),
            quitting: false, disconnect_ask: false, codec_pick: false, spatial_pick: false, style: glass::style::Style::Glass, style_for: false,
        };
        app.reload_style(&cc.egui_ctx);
        // First start: the guide runs before Home, as on the phone.
        if !demo && !load_settings()["setup_done"].as_bool().unwrap_or(false) {
            app.page = Page::Setup;
            app.shown_page = Page::Setup;
        }
        // A page at start, for screenshots: QB_PAGE=appearance, issues, about, language or settings.
        if let Some(p) = std::env::var("QB_PAGE").ok().and_then(|k| page_from_key(&k)) {
            app.page = p;
            app.shown_page = p;
        }
        if let Ok(n) = std::env::var("QB_SETUP") { if let Ok(i) = n.parse::<usize>() { app.setup.page = i.min(9); } }
        if let Ok(key) = std::env::var("QB_SHEET") {
            sheets::debug_open(&mut app, &key);
        }
        // The quick panel at start, for a screenshot on the invisible screen.
        if std::env::var_os("QB_PANEL").is_some() {
            app.panel.pos = (640.0, 260.0);
            app.panel.open = true;
        }
        app
    }

    /// Reads the saved style and theme, and sets them for the widgets and the font. Called at the start, after a change in
    /// Settings, and when the system's light / dark changes (Match system).
    pub fn reload_style(&mut self, ctx: &egui::Context) {
        use glass::style::{self, Style};
        self.style = Style::from_key(load_settings()["egui_style"].as_str().unwrap_or("glass"));
        let light = palette::is_light_system(ctx);
        self.style_for = light;
        let pal = if self.style == Style::Glass { palette::Pal::DEFAULT } else { palette::active(light, self.style == Style::Material) };
        style::set(self.style, pal);
        fonts::apply(ctx, self.style);
        self.glow = load_settings()["glass_gradient"].as_bool().unwrap_or(true);
    }

    fn apply(&mut self, s: Snapshot) {
        if s.status == Status::On {
            self.modes = s.modes.clone();
            if Has::of(&s) != self.has { self.has = Has::of(&s); }
        }
        if let Some(a) = s.anc.as_deref().filter(|a| types::LEVELS.contains(a)) { self.last_level = Some(a.into()); }
        self.toast.update(&s, std::time::Instant::now());
        self.snap = s;
    }

    pub fn send(&self, c: Cmd) { let _ = self.tx.send(c); }
}

impl eframe::App for App {
    /// The window is transparent outside its rounded pane.
    fn clear_color(&self, _visuals: &egui::Visuals) -> [f32; 4] { [0.0, 0.0, 0.0, 0.0] }

    /// Runs before each frame and also while the window is hidden: the link and the tray stay live there.
    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let new = self.inbox.lock().unwrap().take();
        if let Some(s) = new { self.apply(s); }
        self.tray.update(&self.snap, &self.modes, &self.tr);
        while let Some(e) = self.tray.next_event() {
            match e {
                tray::TrayEvent::Open => {
                    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
                    ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
                }
                tray::TrayEvent::Quit => {
                    // The close still comes through `close_requested`: it must not hide the window again.
                    self.quitting = true;
                    ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                tray::TrayEvent::Cmd(c) => self.send(c),
                tray::TrayEvent::Panel(x, y) => {
                    let ppp = ctx.pixels_per_point();
                    let screen = ctx.input(|i| i.viewport().monitor_size).map(|m| (m.x * ppp, m.y * ppp));
                    self.panel.click(panel::place((x as f32, y as f32), screen, ppp), std::time::Instant::now());
                    // The window is often hidden in the tray: a painted pass makes the panel's own window.
                    if self.panel.open { ctx.send_viewport_cmd(egui::ViewportCommand::RequestPaintWhileHidden); }
                }
            }
        }
        // The quick panel follows the snapshot while it is open. It has its own screen, so it draws the
        // flat glass; the app closes it when the panel asks.
        if self.panel.open {
            let view = panel::view(&self.snap, &self.modes, &self.tr);
            panel::open(ctx, view, self.panel.pos, self.panel.tx.clone(), self.panel.focused.clone());
        }
        while let Some(a) = self.panel.rx.try_recv().ok() {
            match a {
                panel::Action::Open => {
                    self.panel.open = false;
                    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
                    ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
                }
                panel::Action::Close { focus } => self.panel.closed(focus, std::time::Instant::now()),
                panel::Action::Cmd(c) => self.send(c),
            }
        }
        // Closing the window hides it; the app lives in the tray until Quit.
        if !self.quitting && tray::up() && ctx.input(|i| i.viewport().close_requested()) {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        }
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        if palette::is_light_system(ui.ctx()) != self.style_for && palette::auto() { let ctx = ui.ctx().clone(); self.reload_style(&ctx); }
        glass::set_glow(ui.ctx(), if self.glow { 1.0 } else { 0.0 });
        glass::backdrop(ui);
        // Glass draws its own window: the base colour and four blobs of the chosen background.
        if self.style == glass::style::Style::Glass { appearance::window(ui); }
        overview::show(self, ui);
        appearance::sheet(self, ui);
        sheets::show(self, ui);
    }
}

fn main() -> eframe::Result {
    // The token sheet: the built-in colours of every style and theme, for comparison with the phone.
    if std::env::args().skip(1).any(|a| a == "--tokens") {
        return token_sheet::run();
    }
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([412.0, 911.0])
            .with_resizable(false)
            .with_maximize_button(false)
            .with_decorations(false)
            .with_transparent(true)
            .with_title("QuickBuds")
            .with_app_id("quickbuds"),
        renderer: eframe::Renderer::Glow,
        ..Default::default()
    };
    eframe::run_native("QuickBuds", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
