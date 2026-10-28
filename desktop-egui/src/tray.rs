//! The tray icon: the launcher icon, and the text the menu and the tooltip show. The GTK thread and the
//! Windows icon live below; the text rules stay pure so a test can check them.
//!
//! Closing the window hides it and the process keeps running here. The tray sends its events over a
//! channel and wakes the egui loop, so a click acts while the window is hidden.

use crate::session::{Snapshot, Status};
use crate::t;
use crate::types::{self, AncModes};
use eframe::egui;
use std::sync::mpsc::{Receiver, Sender};

/// What a click in the tray asks of the app.
pub enum TrayEvent {
    /// Show and focus the window.
    Open,
    /// End the process.
    Quit,
    /// A menu item that sends a `Cmd`, as the pages do.
    Cmd(crate::session::Cmd),
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

/// What the Linux menu shows. The app builds it from a snapshot and sends it to the GTK thread.
#[derive(Clone, PartialEq)]
pub struct MenuUpdate {
    pub battery: String,
    pub open: String,
    pub quit: String,
}

pub fn menu_update(s: &Snapshot, tr: &[String]) -> MenuUpdate {
    MenuUpdate {
        battery: battery_line(s, tr),
        open: t(tr, "tray_open").to_string(),
        quit: t(tr, "tray_quit").to_string(),
    }
}

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
                let tray_icon::TrayIconEvent::Click { button, button_state: tray_icon::MouseButtonState::Up, .. } = e else { return };
                let event = match button {
                    tray_icon::MouseButton::Left => Some(TrayEvent::Open),
                    tray_icon::MouseButton::Right => on_tray_right_click(),
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
    pub fn update(&mut self, s: &Snapshot, tr: &[String]) {
        #[cfg(windows)]
        {
            let tip = tooltip(s, tr);
            if tip != self.tip {
                let _ = self.icon.set_tooltip(Some(tip.clone()));
                self.tip = tip;
            }
        }
        #[cfg(not(windows))]
        {
            let update = menu_update(s, tr);
            if self.shown.as_ref() != Some(&update) {
                let _ = self.menu.send(update.clone());
                self.shown = Some(update);
            }
        }
        #[cfg(windows)]
        let _ = (s, tr);
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

/// Part 2 opens the quick panel here. Until then a right click does nothing.
#[cfg(windows)]
fn on_tray_right_click() -> Option<TrayEvent> { None }

/// The Linux menu, on its own GTK thread: AppIndicator shows a menu and no window of its own.
/// The battery line and the labels arrive over the channel; a click goes back over the other one.
#[cfg(not(windows))]
fn linux_tray(updates: Receiver<MenuUpdate>, events: Sender<TrayEvent>, ctx: egui::Context, open: String, quit: String) {
    use tray_icon::menu::{Menu, MenuEvent, MenuItem};
    gtk::init().expect("gtk");
    let battery = MenuItem::new("QuickBuds", false, None);
    let open = MenuItem::new(open, true, None);
    let quit = MenuItem::new(quit, true, None);
    let menu = Menu::with_items(&[&battery, &open, &quit]).expect("tray menu");
    let _tray = tray_icon::TrayIconBuilder::new().with_icon(tray_icon()).with_menu(Box::new(menu)).build().expect("tray");
    let (open_id, quit_id) = (open.id().clone(), quit.id().clone());
    // ponytail: polls 4 times a second; a menu click waits at most 250 ms and the battery changes slowly.
    gtk::glib::timeout_add_local(std::time::Duration::from_millis(250), move || {
        if let Some(u) = updates.try_iter().last() {
            battery.set_text(&u.battery);
            open.set_text(&u.open);
            quit.set_text(&u.quit);
        }
        for e in MenuEvent::receiver().try_iter() {
            let event = if e.id == open_id { TrayEvent::Open } else if e.id == quit_id { TrayEvent::Quit } else { continue };
            let _ = events.send(event);
            ctx.request_repaint();
        }
        gtk::glib::ControlFlow::Continue
    });
    gtk::main();
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
