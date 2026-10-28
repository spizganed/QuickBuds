//! The tray icon: the launcher icon, and the text the menu and the tooltip show. The GTK thread and the
//! Windows icon live below; the text rules stay pure so a test can check them.
//!
//! Closing the window hides it and the process keeps running here. The tray sends its events over a
//! channel and wakes the egui loop, so a click acts while the window is hidden.

use crate::session::{Cmd, Snapshot, Status};
use crate::t;
use crate::types::{self, AncModes};
use eframe::egui;
use std::sync::mpsc::Receiver;
#[cfg(not(windows))]
use std::sync::mpsc::Sender;

/// What a click in the tray asks of the app.
pub enum TrayEvent {
    /// Show and focus the window.
    Open,
    /// End the process.
    Quit,
    /// A right click on the Windows tray icon, at physical pixel (x, y): open or close the quick panel.
    Panel(f64, f64),
    /// A menu item that sends a `Cmd`, as the pages do.
    Cmd(Cmd),
}

/// The model name, else the app name while no buds named themselves.
fn name_of(s: &Snapshot, tr: &[String]) -> String {
    if s.name.is_empty() { t(tr, "app_name").to_string() } else { s.name.clone() }
}

/// The short battery line of the menu: "L:80 C:40 R:75". The model name while the buds are not connected.
pub fn battery_line(s: &Snapshot, tr: &[String]) -> String {
    if s.status != Status::On { return name_of(s, tr); }
    let line: Vec<String> = [("L", 0), ("C", 2), ("R", 1)].iter()
        .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{k}:{}", b.0))).collect();
    if line.is_empty() { name_of(s, tr) } else { line.join(" ") }
}

/// The Windows tooltip: the model name and "L 80 · R 75 · Case 40". The app name while the buds are not connected.
pub fn tooltip(s: &Snapshot, tr: &[String]) -> String {
    if s.status != Status::On { return t(tr, "app_name").to_string(); }
    let parts: Vec<String> = [("L", 0), ("R", 1), (t(tr, "status_case"), 2)].iter()
        .filter_map(|(k, i)| s.battery[*i].map(|b| format!("{k} {}", b.0))).collect();
    if parts.is_empty() { name_of(s, tr) } else { format!("{}\n{}", name_of(s, tr), parts.join(" · ")) }
}

/// The modes of a menu, in the order the app shows them: (mode name, string key, checked).
/// Only the modes the model offers; the current one is checked.
pub fn mode_items(modes: &AncModes, anc: Option<&str>) -> Vec<(&'static str, &'static str, bool)> {
    const ITEMS: [(&str, &str); 7] = [
        (types::OFF, "anc_seg_off"),
        ("ANC-Light", "anc_mode_low"),
        ("ANC-Medium", "anc_mode_medium"),
        ("ANC-Deep", "anc_mode_high"),
        ("ANC-Smart", "anc_mode_smart"),
        (types::ADAPTIVE, "anc_seg_adapt"),
        (types::TRANSPARENCY, "anc_seg_trans"),
    ];
    ITEMS.iter().filter(|(m, _)| modes.supports(m))
        .map(|(m, key)| (*m, *key, anc == Some(*m))).collect()
}

/// The "Low latency" item and its check: only for a model with a game mode, which offers this switch.
pub fn low_latency_item(s: &Snapshot) -> Option<(&'static str, bool)> {
    types::has_feature(&s.features, s.caps.game_mode_id())
        .then_some(("widget_low_latency", s.low_latency.unwrap_or(false)))
}

/// One menu row of a noise mode: the mode name, its label and its check.
#[derive(Clone, PartialEq)]
pub struct ModeRow {
    pub mode: &'static str,
    pub label: String,
    pub checked: bool,
}

/// What the Linux menu shows. The app builds it from a snapshot and sends it to the GTK thread.
#[derive(Clone, PartialEq)]
pub struct MenuUpdate {
    pub battery: String,
    pub modes: Vec<ModeRow>,
    pub low: Option<(String, bool)>,
    pub open: String,
    pub quit: String,
}

/// The menu for this snapshot: the last battery line, and the modes of the last buds seen. The controls stay
/// in place (dimmed) while disconnected, as on the pages.
pub fn menu_update(s: &Snapshot, modes: &AncModes, tr: &[String]) -> MenuUpdate {
    MenuUpdate {
        battery: battery_line(s, tr),
        modes: mode_items(modes, s.anc.as_deref()).into_iter()
            .map(|(mode, key, checked)| ModeRow { mode, label: t(tr, key).to_string(), checked }).collect(),
        low: low_latency_item(s).map(|(key, on)| (t(tr, key).to_string(), on)),
        open: t(tr, "tray_open").to_string(),
        quit: t(tr, "tray_quit").to_string(),
    }
}

/// True once a tray icon exists. Closing the window hides it only then: with no tray there is no way back, so a close quits.
/// (A Linux desktop without an indicator host, or a failed GTK start, never sets it.)
pub fn up() -> bool { UP.load(std::sync::atomic::Ordering::Relaxed) }
static UP: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(cfg!(windows));

/// The tray of this run: the icon on Windows, the sender of the menu on Linux.
pub struct Tray {
    #[cfg(windows)]
    icon: tray_icon::TrayIcon,
    #[cfg(not(windows))]
    menu: Sender<MenuUpdate>,
    events: Receiver<TrayEvent>,
    /// What the tray shows now: a frame that changes nothing sends nothing.
    #[cfg(windows)]
    tip: String,
    #[cfg(not(windows))]
    shown: Option<MenuUpdate>,
}

impl Tray {
    /// Starts the tray. The window's context wakes the app when an event arrives, even while it is hidden.
    pub fn new(ctx: &egui::Context, tr: &[String]) -> Tray {
        let (events, rx) = std::sync::mpsc::channel();
        #[cfg(windows)]
        {
            let (events, ctx) = (events, ctx.clone());
            tray_icon::TrayIconEvent::set_event_handler(Some(move |e| {
                let tray_icon::TrayIconEvent::Click { button, button_state: tray_icon::MouseButtonState::Up, position, .. } = e else { return };
                let event = match button {
                    tray_icon::MouseButton::Left => Some(TrayEvent::Open),
                    tray_icon::MouseButton::Right => on_tray_right_click(position.x, position.y),
                    _ => None,
                };
                if let Some(event) = event { let _ = events.send(event); }
                ctx.request_repaint();
            }));
            let icon = tray_icon::TrayIconBuilder::new().with_icon(tray_icon())
                .with_tooltip(t(tr, "app_name")).build().expect("tray");
            Tray { icon, events: rx, tip: t(tr, "app_name").to_string() }
        }
        #[cfg(not(windows))]
        {
            let (menu, updates) = std::sync::mpsc::channel();
            let (events, ctx) = (events, ctx.clone());
            let (open, quit) = (t(tr, "tray_open").to_string(), t(tr, "tray_quit").to_string());
            std::thread::spawn(move || linux_tray(updates, events, ctx, open, quit));
            Tray { menu, events: rx, shown: None }
        }
    }

    pub fn next_event(&self) -> Option<TrayEvent> { self.events.try_recv().ok() }

    /// Shows the new snapshot in the tray: the tooltip on Windows, the menu on Linux.
    pub fn update(&mut self, s: &Snapshot, modes: &AncModes, tr: &[String]) {
        #[cfg(windows)]
        {
            let _ = modes;
            let tip = tooltip(s, tr);
            if tip != self.tip {
                let _ = self.icon.set_tooltip(Some(tip.clone()));
                self.tip = tip;
            }
        }
        #[cfg(not(windows))]
        {
            let update = menu_update(s, modes, tr);
            if self.shown.as_ref() != Some(&update) {
                let _ = self.menu.send(update.clone());
                self.shown = Some(update);
            }
        }
    }
}

/// The launcher icon at 32x32, as the tray needs pixels.
fn tray_icon() -> tray_icon::Icon {
    let tree = resvg::usvg::Tree::from_str(crate::icons::LAUNCHER, &Default::default()).expect("tray svg");
    let size = 32;
    let mut pm = resvg::tiny_skia::Pixmap::new(size, size).unwrap();
    let s = size as f32 / tree.size().width().max(tree.size().height());
    resvg::render(&tree, resvg::tiny_skia::Transform::from_scale(s, s), &mut pm.as_mut());
    tray_icon::Icon::from_rgba(pm.take_demultiplied(), size, size).expect("tray icon")
}

/// A right click on the Windows tray icon opens the quick panel next to the click point. Linux keeps the menu:
/// AppIndicator shows a menu and no window of its own.
#[cfg(windows)]
fn on_tray_right_click(x: f64, y: f64) -> Option<TrayEvent> { Some(TrayEvent::Panel(x, y)) }

/// The Linux menu, on its own GTK thread: AppIndicator shows a menu and no window of its own.
/// The texts arrive over one channel; a click goes back over the other one.
#[cfg(not(windows))]
fn linux_tray(updates: Receiver<MenuUpdate>, events: Sender<TrayEvent>, ctx: egui::Context, open: String, quit: String) {
    use tray_icon::menu::MenuEvent;
    gtk::init().expect("gtk");
    let tray = tray_icon::TrayIconBuilder::new().with_icon(tray_icon()).build().expect("tray");
    UP.store(true, std::sync::atomic::Ordering::Relaxed);
    let mut shown = MenuItems::build(&tray, &MenuUpdate {
        battery: t(&crate::translations(""), "app_name").to_string(), modes: Vec::new(), low: None, open, quit,
    });
    // ponytail: polls 4 times a second; a click waits at most 250 ms and the battery changes slowly.
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(250), move || {
        if let Some(u) = updates.try_iter().last() {
            if shown.same_shape(&u) { shown.set_texts(&u); } else { shown = MenuItems::build(&tray, &u); }
        }
        for e in MenuEvent::receiver().try_iter() {
            let event = match shown.action(&e.id) {
                Some(Action::Open) => TrayEvent::Open,
                Some(Action::Quit) => TrayEvent::Quit,
                Some(Action::Mode(mode)) => TrayEvent::Cmd(Cmd::Anc(mode.to_string())),
                // The check item flips itself: send the state it shows now.
                Some(Action::LowLatency) => TrayEvent::Cmd(Cmd::LowLatency(shown.low_on())),
                None => continue,
            };
            let _ = events.send(event);
            ctx.request_repaint();
        }
        gtk::glib::ControlFlow::Continue
    });
    gtk::main();
}

/// What a menu row does when the user clicks it.
#[cfg(not(windows))]
#[derive(Clone, Copy)]
enum Action {
    Open,
    Quit,
    Mode(&'static str),
    LowLatency,
}

/// The menu items of the moment. A new snapshot changes their texts and checks; a new set of modes builds
/// the menu again, because a menu item cannot appear or go away after the menu is built.
#[cfg(not(windows))]
struct MenuItems {
    battery: tray_icon::menu::MenuItem,
    modes: Vec<(&'static str, tray_icon::menu::CheckMenuItem)>,
    low: Option<tray_icon::menu::CheckMenuItem>,
    open: tray_icon::menu::MenuItem,
    quit: tray_icon::menu::MenuItem,
    ids: Vec<(tray_icon::menu::MenuId, Action)>,
}

#[cfg(not(windows))]
impl MenuItems {
    fn build(tray: &tray_icon::TrayIcon, u: &MenuUpdate) -> MenuItems {
        use tray_icon::menu::{CheckMenuItem, IsMenuItem, Menu, MenuItem, PredefinedMenuItem};
        let battery = MenuItem::new(&u.battery, false, None);
        let modes: Vec<(&'static str, CheckMenuItem)> =
            u.modes.iter().map(|m| (m.mode, CheckMenuItem::new(&m.label, true, m.checked, None))).collect();
        let low = u.low.as_ref().map(|(label, on)| CheckMenuItem::new(label, true, *on, None));
        let open = MenuItem::new(&u.open, true, None);
        let quit = MenuItem::new(&u.quit, true, None);
        let (top, bottom) = (PredefinedMenuItem::separator(), PredefinedMenuItem::separator());
        let mut rows: Vec<&dyn IsMenuItem> = vec![&battery];
        if !modes.is_empty() || low.is_some() { rows.push(&top); }
        for (_, item) in &modes { rows.push(item); }
        if let Some(item) = &low { rows.push(item); }
        rows.push(&bottom);
        rows.push(&open);
        rows.push(&quit);
        tray.set_menu(Some(Box::new(Menu::with_items(&rows).expect("tray menu"))));
        let mut ids = vec![(open.id().clone(), Action::Open), (quit.id().clone(), Action::Quit)];
        ids.extend(modes.iter().map(|(mode, item)| (item.id().clone(), Action::Mode(mode))));
        if let Some(item) = &low { ids.push((item.id().clone(), Action::LowLatency)); }
        MenuItems { battery, modes, low, open, quit, ids }
    }

    /// The same rows in the same order: a text change only.
    fn same_shape(&self, u: &MenuUpdate) -> bool {
        self.modes.len() == u.modes.len()
            && self.modes.iter().zip(&u.modes).all(|((mode, _), row)| *mode == row.mode)
            && self.low.is_some() == u.low.is_some()
    }

    fn set_texts(&self, u: &MenuUpdate) {
        self.battery.set_text(&u.battery);
        self.open.set_text(&u.open);
        self.quit.set_text(&u.quit);
        for ((_, item), row) in self.modes.iter().zip(&u.modes) {
            item.set_text(&row.label);
            item.set_checked(row.checked);
        }
        if let (Some(item), Some((label, on))) = (&self.low, &u.low) {
            item.set_text(label);
            item.set_checked(*on);
        }
    }

    fn low_on(&self) -> bool { self.low.as_ref().is_some_and(|l| l.is_checked()) }

    fn action(&self, id: &tray_icon::menu::MenuId) -> Option<Action> {
        self.ids.iter().find(|(own, _)| own == id).map(|(_, action)| *action)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn buds(battery: [(u8, bool); 3], anc: &str) -> Snapshot {
        Snapshot {
            status: Status::On, name: "OnePlus Buds 4".into(), anc: Some(anc.into()),
            battery: battery.map(Some), ..Default::default()
        }
    }

    fn modes(json: serde_json::Value) -> AncModes { AncModes::of(Some(&json)) }

    #[test]
    fn battery_line_lists_left_case_right() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(battery_line(&s, &tr), "L:80 C:40 R:75");
    }

    #[test]
    fn battery_line_skips_a_missing_bud_and_names_the_model_with_no_reading() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        let mut half = s.clone();
        half.battery = [Some((80, false)), None, None];
        assert_eq!(battery_line(&half, &tr), "L:80");
        let mut none = s.clone();
        none.battery = [None; 3];
        assert_eq!(battery_line(&none, &tr), "OnePlus Buds 4");
    }

    #[test]
    fn battery_line_is_the_model_while_disconnected() {
        let tr = crate::translations("");
        let mut s = buds([(80, false), (75, false), (40, false)], "Off");
        s.status = Status::Off;
        assert_eq!(battery_line(&s, &tr), "OnePlus Buds 4");
        s.name.clear();
        assert_eq!(battery_line(&s, &tr), "QuickBuds");
    }

    #[test]
    fn tooltip_has_the_name_and_the_battery_line() {
        let tr = crate::translations("");
        let s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(tooltip(&s, &tr), "OnePlus Buds 4\nL 80 · R 75 · Case 40");
        let mut off = s.clone();
        off.status = Status::Off;
        assert_eq!(tooltip(&off, &tr), "QuickBuds");
    }

    #[test]
    fn mode_items_are_the_offered_modes_with_the_current_one_checked() {
        let m = modes(serde_json::json!({"ancModes": [
            {"mode": 1, "slot": 8}, {"mode": 3, "slot": 1}, {"mode": 8, "slot": 2}, {"mode": 4, "slot": 4}
        ]}));
        let items = mode_items(&m, Some("ANC-Medium"));
        let names: Vec<&str> = items.iter().map(|(n, _, _)| *n).collect();
        assert_eq!(names, ["Off", "ANC-Light", "ANC-Medium", "ANC-Deep"]);
        let checked: Vec<&str> = items.iter().filter(|(_, _, c)| *c).map(|(n, _, _)| *n).collect();
        assert_eq!(checked, ["ANC-Medium"]);
    }

    #[test]
    fn mode_items_cover_adaptive_and_transparency() {
        let m = modes(serde_json::json!({"ancModes": [{"mode": 1, "slot": 8}, {"mode": 2, "slot": 4}, {"mode": 10, "slot": 2}]}));
        let names: Vec<&str> = mode_items(&m, None).iter().map(|(n, _, _)| *n).collect();
        assert_eq!(names, ["Off", "Adaptive", "Transparency"]);
        assert!(mode_items(&m, None).iter().all(|(_, _, c)| !*c));
    }

    #[test]
    fn mode_items_are_empty_for_a_model_with_no_modes() {
        assert!(mode_items(&AncModes::default(), Some("Off")).is_empty());
    }

    #[test]
    fn low_latency_item_follows_the_game_mode_feature() {
        let mut s = buds([(80, false), (75, false), (40, false)], "Off");
        assert_eq!(low_latency_item(&s), Some(("widget_low_latency", false)));
        s.low_latency = Some(true);
        assert_eq!(low_latency_item(&s), Some(("widget_low_latency", true)));
        s.features = vec![(0x99, 1)];
        assert_eq!(low_latency_item(&s), None);
    }
}
