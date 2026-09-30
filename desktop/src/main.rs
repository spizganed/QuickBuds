//! QuickBuds for the desktop: a window, a tray icon with a quick panel, one link thread.
#![windows_subsystem = "windows"]

mod bt;
mod devtools;
mod eq;
mod protocol;
mod session;

mod icons { include!(concat!(env!("OUT_DIR"), "/icons.rs")); }
mod strings { include!(concat!(env!("OUT_DIR"), "/strings.rs")); }

slint::include_modules!();

use protocol::{AncModes, LEVELS};
use session::{Cmd, Snapshot, Status};
use slint::winit_030::{winit, EventResult, WinitWindowAccessor};
use slint::{ComponentHandle, Image, ModelRc, PhysicalPosition, VecModel};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::mpsc::Sender;
use tray_icon::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};

struct App {
    main: MainWindow,
    panel: QuickPanel,
    tray: TrayIcon,
    tx: Sender<Cmd>,
    /// Modes of the last buds seen: the controls stay in place (dimmed) while disconnected.
    modes: AncModes,
    last_level: Option<String>,
    tr: Vec<String>,
    /// The last state from the session.
    snap: Snapshot,
    /// The band editor's gains, updated in place while dragging.
    gains: Rc<VecModel<i32>>,
    /// The preset (id, name) the name field was last filled from.
    edit_key: Option<(u8, String)>,
    /// The band editor's plot size in pixels.
    plot: (f32, f32),
    /// Packet log lines already in the Dev tools list.
    log_shown: usize,
}

thread_local! { static APP: RefCell<Option<App>> = const { RefCell::new(None) }; }

fn with_app(f: impl FnOnce(&mut App)) { APP.with(|a| a.borrow_mut().as_mut().map(f)); }

fn svg(s: &str) -> Image { Image::load_from_svg_data(s.as_bytes()).expect("icon") }

/// English strings from `values/strings.xml`.
// ponytail: English only for now [USER]; the other locales are already in `strings::LOCALES`.
fn translations() -> Vec<String> {
    let base = strings::LOCALES.iter().find(|l| l.0.is_empty()).unwrap().1;
    base.iter().map(|s| s.unwrap_or("").to_string()).collect()
}

fn t<'a>(tr: &'a [String], key: &str) -> &'a str {
    &tr[strings::KEYS.iter().position(|k| *k == key).expect(key)]
}

fn setup_ui(b: &Buds, tr: &Tr, s: &[String]) {
    b.set_icon_left(svg(icons::BUD_LEFT));
    b.set_icon_right(svg(icons::BUD_RIGHT));
    b.set_icon_case(svg(icons::CASE));
    b.set_icon_off(svg(icons::MODE_OFF));
    b.set_icon_anc(svg(icons::MODE_ANC_MEDIUM));
    b.set_icon_adaptive(svg(icons::MODE_ADAPTIVE));
    b.set_icon_transparency(svg(icons::MODE_TRANSPARENCY));
    b.set_icon_low_latency(svg(icons::LOW_LATENCY));
    b.set_icon_app(svg(icons::LAUNCHER));
    b.set_icon_bass(svg(icons::EQUALIZER));
    b.set_icon_chevron(svg(icons::CHEVRON_RIGHT));
    b.set_icon_plus(svg(icons::PLUS));

    tr.set_connected(t(s, "conn_on").into());
    tr.set_connect(t(s, "conn_action_connect").into());
    tr.set_disconnect(t(s, "conn_action_disconnect").into());
    tr.set_connecting(t(s, "conn_connecting").into());
    tr.set_not_connected(t(s, "dual_not_connected").into());
    tr.set_left(t(s, "status_left").into());
    tr.set_right(t(s, "status_right").into());
    tr.set_case(t(s, "status_case").into());
    tr.set_noise_control(t(s, "anc_section").into());
    tr.set_off(t(s, "anc_seg_off").into());
    tr.set_anc(t(s, "anc_seg_anc").into());
    tr.set_adaptive(t(s, "anc_seg_adapt").into());
    tr.set_transparency(t(s, "anc_seg_trans").into());
    tr.set_low_latency(t(s, "widget_low_latency").into());
    tr.set_in_ear(t(s, "status_in_ear").into());
    tr.set_in_case(t(s, "status_in_case").into());
    tr.set_out_of_ear(t(s, "status_out").into());
    tr.set_game_title(t(s, "row_game_title").into());
    tr.set_game_sub(t(s, "row_game_sub").into());
    tr.set_eq_row_title(t(s, "row_eq_title").into());
    tr.set_eq_row_sub(t(s, "row_eq_sub").into());
    tr.set_eq_title(t(s, "eq_title").into());
    tr.set_eq_not_connected(t(s, "eq_not_connected").into());
    tr.set_eq_recommended(t(s, "eq_recommended").into());
    tr.set_eq_basswave(t(s, "eq_basswave").into());
    tr.set_eq_basswave_sub(t(s, "eq_basswave_sub").into());
    tr.set_eq_custom(t(s, "eq_custom").into());
    tr.set_eq_delete(t(s, "eq_delete").into());
    tr.set_eq_add(t(s, "eq_add").into());
    tr.set_eq_save(t(s, "eq_save").into());

    b.on_set_anc(|mode| with_app(|a| {
        let mode = if mode == "ANC" {
            // The ANC segment picks the last level used, else Medium, else the first the buds have.
            let levels = a.modes.levels();
            let Some(l) = a.last_level.clone().filter(|l| levels.contains(&l.as_str()))
                .or_else(|| levels.iter().find(|l| **l == "ANC-Medium").or(levels.first()).map(|l| l.to_string()))
            else { return };
            l
        } else {
            mode.to_string()
        };
        let _ = a.tx.send(Cmd::Anc(mode));
    }));
    b.on_set_low_latency(|on| with_app(|a| { let _ = a.tx.send(Cmd::LowLatency(on)); }));
    b.on_connect(|| with_app(|a| { let _ = a.tx.send(Cmd::Connect); }));
    b.on_disconnect(|| with_app(|a| { let _ = a.tx.send(Cmd::Disconnect); }));
    b.on_open_main(|| with_app(|a| { a.panel.hide().ok(); show_main(&a.main); }));
    b.on_quit(|| { let _ = slint::quit_event_loop(); });
}

/// (mode id, icon, index into the level names)
fn level_info(id: &str) -> (&'static str, usize) {
    match id {
        "ANC-Light" => (icons::MODE_ANC_LOW, 0),
        "ANC-Deep" => (icons::MODE_ANC_HIGH, 2),
        "ANC-Smart" => (icons::MODE_ANC_SMART, 3),
        _ => (icons::MODE_ANC_MEDIUM, 1),
    }
}

impl App {
    fn apply(&mut self, s: Snapshot) {
        self.snap = s.clone();
        eq::apply(self);
        if s.status == Status::On { self.modes = s.modes.clone(); }
        if let Some(a) = s.anc.as_deref().filter(|a| LEVELS.contains(a)) { self.last_level = Some(a.into()); }
        let level_names = ["anc_mode_low", "anc_mode_medium", "anc_mode_high", "anc_mode_smart"];
        let levels: Vec<Level> = self.modes.levels().into_iter().map(|id| {
            let (icon, i) = level_info(id);
            Level { id: id.into(), name: t(&self.tr, level_names[i]).into(), icon: svg(icon) }
        }).collect();
        let anc = s.anc.clone().unwrap_or_default();
        let is_level = LEVELS.contains(&anc.as_str());
        let (anc_icon, li) = level_info(if is_level { &anc } else { "" });
        // "ANC M": the level's first letter, as on the phone's segment and the widget's button.
        let mut anc_label = t(&self.tr, "anc_seg_anc").to_string();
        if is_level {
            if let Some(c) = t(&self.tr, level_names[li]).chars().next() { anc_label = format!("{anc_label} {}", c.to_uppercase()); }
        }

        for b in [self.main.global::<Buds>(), self.panel.global::<Buds>()] {
            b.set_status(match s.status { Status::Off => 0, Status::Connecting => 1, Status::On => 2 });
            b.set_name(s.name.as_str().into());
            let bat = |i: usize| s.battery[i].map_or(-1, |x| x.0 as i32);
            let chg = |i: usize| s.battery[i].is_some_and(|x| x.1);
            b.set_left(bat(0));
            b.set_right(bat(1));
            b.set_case(bat(2));
            b.set_left_charging(chg(0));
            b.set_right_charging(chg(1));
            b.set_case_charging(chg(2));
            // §8: 3/7 in ear, 4 in case, 1/5 out
            let wear = |i: usize| match s.wear[i] { 3 | 7 => 2, 4 => 3, 1 | 5 => 1, _ => 0 };
            b.set_left_wear(wear(0));
            b.set_right_wear(wear(1));
            b.set_anc(anc.as_str().into());
            b.set_anc_is_level(is_level);
            b.set_icon_anc(svg(anc_icon));
            b.set_anc_label(anc_label.as_str().into());
            b.set_has_off(self.modes.supports(protocol::OFF));
            b.set_has_adaptive(self.modes.supports(protocol::ADAPTIVE));
            b.set_has_transparency(self.modes.supports(protocol::TRANSPARENCY));
            b.set_levels(ModelRc::new(VecModel::from(levels.clone())));
            b.set_low_latency(s.low_latency.unwrap_or(false));
        }

        let mut tip = String::from("QuickBuds");
        if s.status == Status::On {
            let parts: Vec<String> = [("status_left", 0), ("status_right", 1), ("status_case", 2)].iter()
                .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{} {}%", t(&self.tr, k), b.0)))
                .collect();
            if !parts.is_empty() { tip = format!("{}\n{}", s.name, parts.join(" · ")); }
        }
        let _ = self.tray.set_tooltip(Some(tip));
    }
}

/// The frameless main window's own title bar and edges, through winit.
fn window_chrome(main: &MainWindow) {
    use winit::window::ResizeDirection as R;
    let w = main.as_weak();
    main.on_drag(move || { w.upgrade().map(|m| m.window().with_winit_window(|w| w.drag_window())); });
    let w = main.as_weak();
    main.on_minimize(move || { w.upgrade().map(|m| m.window().set_minimized(true)); });
    let w = main.as_weak();
    main.on_toggle_maximize(move || {
        if let Some(m) = w.upgrade() {
            let max = !m.window().is_maximized();
            m.window().set_maximized(max);
            m.set_max_state(max);
        }
    });
    let w = main.as_weak();
    main.on_close_window(move || { w.upgrade().map(|m| m.hide()); });
    let w = main.as_weak();
    main.on_resize(move |dir| {
        let dir = [R::North, R::South, R::West, R::East, R::NorthWest, R::NorthEast, R::SouthWest, R::SouthEast][dir as usize];
        w.upgrade().map(|m| m.window().with_winit_window(|w| w.drag_resize_window(dir)));
    });
}

fn show_main(w: &MainWindow) {
    w.show().ok();
    w.window().with_winit_window(|w| w.focus_window());
}

fn on_tray(e: TrayIconEvent) {
    let TrayIconEvent::Click { button, button_state: MouseButtonState::Up, position, .. } = e else { return };
    with_app(|a| match button {
        MouseButton::Left => { a.panel.hide().ok(); show_main(&a.main); }
        MouseButton::Right => {
            // Above the cursor and left of it (taskbar at the bottom right); below it if that is off screen.
            let scale = a.panel.window().scale_factor();
            let (w, h) = ((300.0 * scale) as i32, (250.0 * scale) as i32);
            let (x, y) = (position.x as i32, position.y as i32);
            let px = if x - w >= 0 { x - w } else { x };
            let py = if y - h >= 0 { y - h } else { y };
            a.panel.window().set_position(PhysicalPosition::new(px, py));
            a.panel.show().ok();
            a.panel.window().with_winit_window(|w| w.focus_window());
        }
        _ => {}
    });
}

fn tray_icon() -> tray_icon::Icon {
    let tree = resvg::usvg::Tree::from_str(icons::LAUNCHER, &Default::default()).expect("tray svg");
    let size = 32;
    let mut pm = resvg::tiny_skia::Pixmap::new(size, size).unwrap();
    let s = size as f32 / tree.size().width().max(tree.size().height());
    resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pm.as_mut());
    tray_icon::Icon::from_rgba(pm.take_demultiplied(), size, size).expect("tray icon")
}

fn main() {
    session::app_start();
    // Software rendering: ~25 MB instead of ~130 MB with the GPU renderer, and fast enough for this UI.
    if std::env::var_os("SLINT_BACKEND").is_none() {
        slint::BackendSelector::new().backend_name("winit".into()).renderer_name("software".into())
            .select().expect("backend");
    }
    let main = MainWindow::new().expect("window");
    let panel = QuickPanel::new().expect("panel");
    let tr = translations();
    setup_ui(&main.global::<Buds>(), &main.global::<Tr>(), &tr);
    setup_ui(&panel.global::<Buds>(), &panel.global::<Tr>(), &tr);
    // Sections of the phone app; each turns on when its page exists.
    let nav: Vec<NavEntry> = [
        (icons::LAYOUT, "Overview", true),
        (icons::EQUALIZER, "Equalizer", true),
        (icons::GESTURE, "Controls", false),
        (icons::HEARING, "Hearing profile", false),
        (icons::DEVICES, "Dual connection", false),
        (icons::EARBUD, "Earbud settings", false),
        (icons::SETTINGS_COG, "App settings", false),
        (icons::DEV_TOOLS, "Dev tools", true),
    ].into_iter().map(|(icon, name, ready)| NavEntry { icon: svg(icon), name: name.into(), ready }).collect();
    main.set_nav(ModelRc::new(VecModel::from(nav)));
    window_chrome(&main);
    eq::setup(&main);
    devtools::setup(&main);
    let gains = Rc::new(VecModel::default());
    main.global::<Eq>().set_gains(ModelRc::from(gains.clone()));

    // The quick panel closes when it loses focus, like the system's own tray flyouts.
    panel.window().on_winit_window_event(|w, e| {
        if let winit::event::WindowEvent::Focused(false) = e { w.hide().ok(); }
        EventResult::Propagate
    });

    let tray = TrayIconBuilder::new()
        .with_icon(tray_icon())
        .with_tooltip("QuickBuds")
        .build()
        .expect("tray");
    TrayIconEvent::set_event_handler(Some(|e| { let _ = slint::invoke_from_event_loop(move || on_tray(e)); }));

    let tx = session::spawn(|s| { let _ = slint::invoke_from_event_loop(move || with_app(|a| a.apply(s))); });
    APP.with(|a| *a.borrow_mut() = Some(App {
        main: main.clone_strong(), panel, tray, tx, modes: AncModes::default(), last_level: None, tr,
        snap: Snapshot::default(), gains, edit_key: None, plot: (0.0, 0.0), log_shown: 0,
    }));
    with_app(|a| a.apply(Snapshot::default()));

    main.show().expect("show");
    // Closing the window hides it; the app lives in the tray until Quit.
    slint::run_event_loop_until_quit().expect("event loop");
}
