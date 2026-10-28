//! The quick panel: the small window that a right click on the Windows tray icon opens. It sits next to
//! the click point, above and left of it, and closes when it loses focus or on Escape.
//!
//! The place of the panel and its content are pure and stay outside a `cfg` gate, so a test and a
//! screenshot on Linux check them. The tray wiring is the only Windows part.

use crate::glass::{self, white};
use crate::session::{Cmd, Snapshot, Status};
use crate::types::{self, AncModes};
use crate::{icons, palette, t};
use eframe::egui::{self, Align2, Color32, FontId, Id, Pos2, Rect, Sense, Ui, Vec2};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use std::sync::mpsc::{Receiver, Sender};
use std::sync::Arc;

/// The panel's size in logical points, as the Slint panel.
pub const SIZE: [f32; 2] = [300.0, 250.0];

/// The panel's top-left corner in physical pixels, for a click at `click` in physical pixels.
/// The panel goes above and left of the click point. It flips below or right of the point when that
/// side is off screen, and it never leaves the screen.
pub fn pos(click: (f32, f32), size: (f32, f32), screen: (f32, f32)) -> (f32, f32) {
    // Before the point, else after it; then keep the panel on the screen.
    let place = |p: f32, s: f32, max: f32| (if p - s >= 0.0 { p - s } else { p }).clamp(0.0, (max - s).max(0.0));
    (place(click.0, size.0, screen.0), place(click.1, size.1, screen.1))
}

/// The place of the panel for a right click at `click` in physical pixels, on a screen of `screen` in
/// physical pixels (`None` when the size is not known), at `ppp` physical pixels per point.
pub fn place(click: (f32, f32), screen: Option<(f32, f32)>, ppp: f32) -> (f32, f32) {
    let size = (SIZE[0] * ppp, SIZE[1] * ppp);
    // With no screen size, the panel goes above and left of the click point.
    pos(click, size, screen.unwrap_or((click.0 + size.0, click.1 + size.1)))
}

/// What the panel asks of the app.
pub enum Action {
    /// Show and focus the main window.
    Open,
    /// Close the panel. `focus` is true when it lost the focus: the click that took the focus must not
    /// open it again.
    Close { focus: bool },
    /// A control that sends a `Cmd`, as the pages do.
    Cmd(Cmd),
}

/// One segment of the noise control: a mode name, its icon and its check.
pub struct Mode {
    pub id: &'static str,
    pub label: String,
    pub icon: &'static str,
    pub checked: bool,
}

/// What the panel shows of a snapshot. The app builds it for each pass while the panel is open.
pub struct View {
    pub name: String,
    pub status: String,
    pub on: bool,
    /// Left, right and case, as the snapshot holds them.
    pub battery: [Option<u8>; 3],
    pub modes: Vec<Mode>,
    pub low: Option<bool>,
    pub open: String,
    pub low_label: String,
}

/// The icon of a noise mode.
fn mode_icon(mode: &str) -> &'static str {
    match mode {
        types::OFF => icons::MODE_OFF,
        "ANC-Light" => icons::MODE_ANC_LOW,
        "ANC-Deep" => icons::MODE_ANC_HIGH,
        "ANC-Smart" => icons::MODE_ANC_SMART,
        types::ADAPTIVE => icons::MODE_ADAPTIVE,
        types::TRANSPARENCY => icons::MODE_TRANSPARENCY,
        _ => icons::MODE_ANC_MEDIUM,
    }
}

/// The panel for this snapshot: the name, the battery of the three parts, the modes the model offers and
/// the low latency switch of a model with a game mode. The controls stay in place (dimmed) while disconnected.
pub fn view(s: &Snapshot, modes: &AncModes, tr: &[String]) -> View {
    let name = if s.name.is_empty() { t(tr, "app_name").to_string() } else { s.name.clone() };
    View {
        name,
        status: t(tr, match s.status {
            Status::On => "conn_on",
            Status::Connecting => "conn_connecting",
            Status::Off => "dual_not_connected",
        }).to_string(),
        on: s.status == Status::On,
        battery: s.battery.map(|b| b.map(|x| x.0)),
        modes: crate::tray::mode_items(modes, s.anc.as_deref()).into_iter()
            .map(|(id, key, checked)| Mode { id, label: t(tr, key).to_string(), icon: mode_icon(id), checked })
            .collect(),
        low: crate::tray::low_latency_item(s).map(|(_, on)| on),
        open: t(tr, "tray_open").to_string(),
        low_label: t(tr, "widget_low_latency").to_string(),
    }
}

/// The open state of the quick panel, its place in physical pixels and the actions it sends.
pub struct Panel {
    pub open: bool,
    /// The top-left corner in physical pixels.
    pub pos: (f32, f32),
    pub tx: Sender<Action>,
    pub rx: Receiver<Action>,
    /// True once the panel window had focus: a later focus loss closes it.
    pub focused: Arc<AtomicBool>,
    /// When the panel last closed because it lost the focus.
    closed_at: Option<Instant>,
}

/// A right click on the tray that takes the focus from the panel closes it. A click this soon after that
/// close is the same click, not a new one: it does not open the panel again.
const GUARD: Duration = Duration::from_millis(300);

impl Default for Panel {
    fn default() -> Self {
        let (tx, rx) = std::sync::mpsc::channel();
        Panel { open: false, pos: (0.0, 0.0), tx, rx, focused: Arc::new(AtomicBool::new(false)), closed_at: None }
    }
}

impl Panel {
    /// The panel's own viewport.
    pub fn viewport() -> egui::ViewportId { egui::ViewportId::from_hash_of("quick-panel") }

    /// A right click at `place` at `now`: open the panel there, or close it when it is already open.
    pub fn click(&mut self, place: (f32, f32), now: Instant) {
        if self.open {
            self.open = false;
            return;
        }
        if self.closed_at.is_some_and(|t| now.duration_since(t) < GUARD) {
            return;
        }
        self.pos = place;
        // A new window must get focus before a focus loss can close it.
        self.focused.store(false, Ordering::Relaxed);
        self.open = true;
    }

    /// The panel closed. A focus loss is remembered, so the click that caused it cannot open the panel again.
    pub fn closed(&mut self, focus: bool, now: Instant) {
        self.open = false;
        if focus {
            self.closed_at = Some(now);
        }
    }
}

/// Shows the panel at `pos` in physical pixels, or updates the open one with the state of this pass.
pub fn open(ctx: &egui::Context, view: View, pos: (f32, f32), tx: Sender<Action>, focused: Arc<AtomicBool>) {
    let ppp = ctx.pixels_per_point();
    let builder = egui::ViewportBuilder::default()
        .with_title("QuickBuds")
        .with_decorations(false)
        .with_transparent(true)
        .with_always_on_top()
        .with_taskbar(false)
        .with_resizable(false)
        .with_inner_size(Vec2::new(SIZE[0], SIZE[1]))
        .with_position(Pos2::new(pos.0 / ppp, pos.1 / ppp))
        .with_active(true);
    ctx.show_viewport_deferred(Panel::viewport(), builder, move |ui, _| show(&view, ui, &tx, &focused));
}

/// Sends an action and wakes the main window: its `logic` drains the channel, even while it is hidden.
fn send(ctx: &egui::Context, tx: &Sender<Action>, action: Action) {
    let _ = tx.send(action);
    ctx.request_repaint_of(egui::ViewportId::ROOT);
}

/// Closes the panel: the app stops showing its viewport, and the window goes away.
fn close(ctx: &egui::Context, tx: &Sender<Action>, focus: bool) {
    ctx.send_viewport_cmd(egui::ViewportCommand::Close);
    send(ctx, tx, Action::Close { focus });
}

/// The panel's content. It has its own screen, so the glass blur of the main window is not there: the
/// pane is the flat glass of the app, and the cards are `flat_at`.
pub fn show(v: &View, ui: &mut Ui, tx: &Sender<Action>, focused: &AtomicBool) {
    let ctx = ui.ctx().clone();
    // A click elsewhere closes the panel, as the system's tray flyouts. A window without focus yet stays open.
    if ctx.input(|i| i.viewport().focused) == Some(true) {
        focused.store(true, Ordering::Relaxed);
    } else if focused.load(Ordering::Relaxed) {
        close(&ctx, tx, true);
        return;
    }
    if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
        close(&ctx, tx, false);
        return;
    }
    crate::appearance::window(ui);
    let full = ui.max_rect();
    let inner = full.shrink(12.0);
    let a = if v.on { 1.0 } else { 0.45 };

    // The model name and the link state.
    let name_clip = Rect::from_min_max(Pos2::new(inner.left(), inner.top()), Pos2::new(inner.right() - 84.0, inner.top() + 16.0));
    ui.painter().with_clip_rect(name_clip).text(Pos2::new(inner.left(), inner.top() + 8.0), Align2::LEFT_CENTER, &v.name, FontId::proportional(13.5), white(a));
    glass::text(ui, Pos2::new(inner.right(), inner.top() + 8.0), Align2::RIGHT_CENTER, &v.status, 11.0, 0.72);

    // Left, case and right, as the Overview card: a 54 dp ring with its level under it.
    let cy = inner.top() + 24.0 + 27.0;
    for (n, (i, glyph)) in [(0usize, icons::BUD_LEFT), (2, icons::CASE), (1, icons::BUD_RIGHT)].into_iter().enumerate() {
        let cx = inner.left() + inner.width() * (1.0 / 6.0 + n as f32 / 3.0);
        let b = v.battery[i];
        let frac = b.map(|x| x as f32 / 100.0);
        let shown = glass::ring_at(ui, ("panel-ring", n), Pos2::new(cx, cy), 27.0, if v.on { frac } else { None }, Some(glyph), !v.on);
        let pct = match (b, v.on) {
            (Some(_), true) => format!("{}%", (shown * 100.0).round() as i32),
            _ => "—".into(),
        };
        glass::text(ui, Pos2::new(cx, cy + 37.0), Align2::CENTER_CENTER, &pct, 12.0, if v.on { 1.0 } else { 0.5 });
    }

    // The noise control: one plate, one segment for each mode the model offers.
    let plate = Rect::from_min_size(Pos2::new(inner.left(), inner.top() + 104.0), Vec2::new(inner.width(), 40.0));
    glass::flat_at(ui, plate, 20.0, 1.0);
    if !v.modes.is_empty() {
        let (pad, gap) = (4.0, 2.0);
        let n = v.modes.len() as f32;
        let seg_w = (plate.width() - 2.0 * pad - gap * (n - 1.0)) / n;
        let seg = |i: usize| Rect::from_min_size(
            Pos2::new(plate.left() + pad + (seg_w + gap) * i as f32, plate.top() + pad),
            Vec2::new(seg_w, plate.height() - 2.0 * pad));
        let at = v.modes.iter().position(|m| m.checked);
        let x = ctx.animate_value_with_time(Id::NULL.with("panel-anc-x"), at.map_or(0.0, |i| seg(i).left()), 0.24);
        let lit = ctx.animate_bool_with_time(Id::NULL.with("panel-anc-v"), at.is_some() && v.on, 0.18);
        if lit > 0.0 {
            let pill = Rect::from_min_size(Pos2::new(x, plate.top() + pad), Vec2::new(seg_w, plate.height() - 2.0 * pad));
            ui.painter().rect_filled(pill, 16.0, white(0.66 * lit));
        }
        for (i, m) in v.modes.iter().enumerate() {
            let r = ui.interact(seg(i), Id::NULL.with(("panel-anc", i)), Sense::click());
            if v.on && r.hovered() {
                ui.painter().rect_filled(seg(i).shrink(2.0), 14.0, white(0.07));
                ctx.set_cursor_icon(egui::CursorIcon::PointingHand);
            }
            let ink = palette::blend(white(0.72 * if v.on { 1.0 } else { 0.5 }), glass::on_accent(), if m.checked { lit } else { 0.0 });
            glass::icon_at(ui, m.icon, Rect::from_center_size(seg(i).center(), Vec2::splat(22.0)), ink);
            if v.on && r.on_hover_text(&m.label).clicked() { send(&ctx, tx, Action::Cmd(Cmd::Anc(m.id.to_string()))); }
        }
    }

    // The low latency switch of a model with a game mode, and the button that opens the window.
    if let Some(on) = v.low {
        let row = Rect::from_min_size(Pos2::new(inner.left(), inner.bottom() - 34.0 - 10.0 - 30.0), Vec2::new(inner.width(), 30.0));
        glass::icon_at(ui, icons::LOW_LATENCY, Rect::from_center_size(Pos2::new(row.left() + 10.0, row.center().y), Vec2::splat(20.0)), white(0.9 * a));
        glass::text(ui, Pos2::new(row.left() + 30.0, row.center().y), Align2::LEFT_CENTER, &v.low_label, 13.5, a);
        if glass::switch_at(ui, "panel-low", Pos2::new(row.right() - 52.0, row.center().y - 15.0), on, v.on) {
            send(&ctx, tx, Action::Cmd(Cmd::LowLatency(!on)));
        }
    }
    let button = Rect::from_min_size(Pos2::new(inner.left(), inner.bottom() - 34.0), Vec2::new(inner.width(), 34.0));
    let r = ui.interact(button, Id::NULL.with("panel-open"), Sense::click());
    let (h, p) = (ctx.animate_bool_with_time(r.id.with("h"), r.hovered(), 0.12), ctx.animate_bool_with_time(r.id.with("p"), r.is_pointer_button_down_on(), 0.06));
    ui.painter().rect_filled(button, 17.0, palette::blend(glass::style::accent(), Color32::WHITE, 0.12 * h - 0.10 * p));
    ui.painter().text(button.center(), Align2::CENTER_CENTER, &v.open, FontId::proportional(14.5), glass::on_accent());
    if r.hovered() { ctx.set_cursor_icon(egui::CursorIcon::PointingHand); }
    if r.clicked() { send(&ctx, tx, Action::Open); }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PANEL: (f32, f32) = (300.0, 250.0);
    const SCREEN: (f32, f32) = (1920.0, 1080.0);

    #[test]
    fn above_and_left_of_the_click() {
        assert_eq!(pos((1500.0, 800.0), PANEL, SCREEN), (1200.0, 550.0));
    }

    #[test]
    fn below_and_right_of_a_click_near_the_origin() {
        // A taskbar at the top, or the panel button of a taskbar on the left.
        assert_eq!(pos((100.0, 60.0), PANEL, SCREEN), (100.0, 60.0));
    }

    #[test]
    fn one_side_flips_on_its_own() {
        // A taskbar at the top: the panel goes below the click, and still left of it.
        assert_eq!(pos((1500.0, 60.0), PANEL, SCREEN), (1200.0, 60.0));
        // A taskbar on the left: the panel goes right of the click, and still above it.
        assert_eq!(pos((40.0, 800.0), PANEL, SCREEN), (40.0, 550.0));
    }

    #[test]
    fn the_panel_stays_on_a_small_screen() {
        assert_eq!(pos((100.0, 100.0), PANEL, (350.0, 300.0)), (50.0, 50.0));
    }

    #[test]
    fn a_screen_smaller_than_the_panel_gives_the_origin() {
        assert_eq!(pos((100.0, 100.0), PANEL, (200.0, 200.0)), (0.0, 0.0));
    }

    #[test]
    fn the_panel_of_a_click_scales_with_the_screen() {
        // A 4K screen at 200 percent: the panel is 600 x 500 physical pixels.
        assert_eq!(place((3000.0, 1600.0), Some((3840.0, 2160.0)), 2.0), (2400.0, 1100.0));
        // With no screen size the panel goes above and left of the click point.
        assert_eq!(place((1500.0, 800.0), None, 1.0), (1200.0, 550.0));
    }

    #[test]
    fn a_second_right_click_closes_the_panel() {
        let mut p = Panel::default();
        let t = Instant::now();
        p.click((1200.0, 550.0), t);
        assert!(p.open);
        assert_eq!(p.pos, (1200.0, 550.0));
        p.click((1200.0, 550.0), t);
        assert!(!p.open);
    }

    #[test]
    fn the_click_that_took_the_focus_does_not_open_the_panel_again() {
        let mut p = Panel::default();
        let t = Instant::now();
        p.open = true;
        p.closed(true, t);
        p.click((1200.0, 550.0), t + Duration::from_millis(100));
        assert!(!p.open);
        // A later click is a new click: it opens the panel.
        p.click((1200.0, 550.0), t + Duration::from_millis(400));
        assert!(p.open);
    }

    #[test]
    fn escape_lets_the_next_click_open_the_panel() {
        let mut p = Panel::default();
        let t = Instant::now();
        p.open = true;
        p.closed(false, t);
        p.click((1200.0, 550.0), t + Duration::from_millis(10));
        assert!(p.open);
    }

    fn buds() -> Snapshot {
        Snapshot {
            status: Status::On, name: "OnePlus Buds 4".into(), anc: Some("ANC-Light".into()),
            battery: [Some((80, false)), Some((75, false)), Some((40, false))],
            modes: AncModes::of(Some(&serde_json::json!({"ancModes": [
                {"mode": 1, "slot": 8}, {"mode": 3, "slot": 1}, {"mode": 2, "slot": 4}, {"mode": 10, "slot": 2}
            ]}))),
            ..Default::default()
        }
    }

    #[test]
    fn the_view_lists_the_model_the_battery_and_the_modes() {
        let tr = crate::translations("");
        let s = buds();
        let v = view(&s, &s.modes, &tr);
        assert_eq!(v.name, "OnePlus Buds 4");
        assert_eq!(v.status, "Connected");
        assert!(v.on);
        assert_eq!(v.battery, [Some(80), Some(75), Some(40)]);
        let ids: Vec<&str> = v.modes.iter().map(|m| m.id).collect();
        assert_eq!(ids, ["Off", "ANC-Light", "Adaptive", "Transparency"]);
        let checked: Vec<&str> = v.modes.iter().filter(|m| m.checked).map(|m| m.id).collect();
        assert_eq!(checked, ["ANC-Light"]);
        assert_eq!(v.modes[0].label, t(&tr, "anc_seg_off"));
        assert_eq!(v.low, Some(false));
        assert_eq!(v.open, "Open QuickBuds");
    }

    #[test]
    fn the_view_is_dimmed_and_empty_while_disconnected() {
        let tr = crate::translations("");
        let mut s = buds();
        s.status = Status::Off;
        s.battery = [None; 3];
        let v = view(&s, &s.modes, &tr);
        assert!(!v.on);
        assert_eq!(v.status, "Not connected");
        assert_eq!(v.battery, [None; 3]);
        // The controls stay in place, as on the pages.
        assert_eq!(v.modes.len(), 4);
    }

    #[test]
    fn the_view_has_no_low_latency_row_for_a_model_without_game_mode() {
        let tr = crate::translations("");
        let mut s = buds();
        s.features = vec![(0x99, 1)];
        assert_eq!(view(&s, &s.modes, &tr).low, None);
    }
}
