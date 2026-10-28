//! The window: the phone frame, the header and the page the window shows.

use crate::glass::{self, white};
use crate::session::{Cmd, Snapshot, Status};
use crate::types::{self, FEATURE_DUAL, FEATURE_HEARING, FEATURE_HIRES, FEATURE_SPATIAL};
use crate::{motion, t, App, Page};

#[path = "home/mod.rs"]
pub mod home;

use eframe::egui::{self, Align2, FontId, Pos2, Rect, Ui, Vec2};

/// A fixed snapshot of Buds 4 for screenshots and UI tests (`QB_DEMO=1`): no link, no settings written.
/// It is the state of the Glass capture: Left 90% in ear, Case 80%, Right 90% out of ear.
pub fn demo_snapshot() -> Snapshot {
    let model = types::models().iter().find(|m| m["name"].as_str() == Some("OnePlus Buds 4"));
    Snapshot {
        status: Status::On,
        name: "OnePlus Buds 4".into(),
        battery: [Some((90, false)), Some((90, false)), Some((80, false))],
        wear: [3, 5, 0],
        anc: Some("Off".into()),
        modes: types::AncModes::of(model),
        low_latency: Some(false),
        model,
        codec: Some(3),
        devices: vec![
            types::PairedDevice { mac: [1, 2, 3, 4, 5, 6], name: "This computer".into(), connected: true, this_device: true },
            types::PairedDevice { mac: [1, 2, 3, 4, 5, 7], name: "Phone".into(), connected: true, this_device: false },
        ],
        keyfn: Some(vec![[1, 1, 1, 0x01], [2, 1, 1, 0x01], [1, 1, 2, 0x06], [2, 1, 2, 0x05], [1, 1, 3, 0x00], [2, 1, 3, 0x00], [1, 1, 5, 0x07], [2, 1, 5, 0x07], [1, 1, 4, 0x08], [2, 1, 4, 0x08]]),
        hold_masks: vec![(types::HOLD_TYPE_SHARED, 0x0807)],
        alert_volume: Some(6),
        firmware: Some("138.138.105".into()),
        bass_on: Some(true),
        bass_level: Some(2),
        eq_current: Some(21),
        eq_custom: vec![demo_preset(21, "GoodEQ", [2, 1, 0, -1, 2, 3]), demo_preset(22, "Custom1", [0; 6])],
        features: vec![(types::FEATURE_GAME_MODE, 0), (types::FEATURE_GAME_MODE_MAIN, 0), (FEATURE_HIRES, 1), (FEATURE_SPATIAL, 0), (FEATURE_DUAL, 1), (FEATURE_HEARING, 1)],
        ..Default::default()
    }
}

fn demo_preset(id: u8, name: &str, gains: [i8; 6]) -> types::Preset {
    let mut p = types::Preset::new(name, types::EQ_DEFAULT_FREQS.to_vec());
    p.id = id;
    p.gains = gains.to_vec();
    p
}

/// Without an "in ear" seen, "out" may only mean the model has no sensor: shown as in the ear (the app's rule).
pub fn wear_sensor_seen(s: &Snapshot, demo: bool) -> bool {
    if demo { return true; }
    let Some(id) = s.model.and_then(|m| m["id"].as_str()) else { return true };
    let mut seen = crate::load_settings()["wear_seen"].clone();
    if seen[id] == true { return true; }
    if !s.wear.iter().any(|w| matches!(w, 3 | 7)) { return false; }
    seen[id] = true.into();
    crate::save_setting("wear_seen", seen);
    true
}

pub fn show(app: &mut App, ui: &mut Ui) {
    let full = ui.max_rect();
    // PHASE 1: the phone's one column, 16 dp side margins and a 48 dp header.
    let content = Rect::from_min_max(Pos2::new(full.left() + 16.0, full.top() + 16.0), Pos2::new(full.right() - 16.0, full.bottom() - 16.0));
    header(app, ui, content);
    let top = if app.page == Page::Overview { content.top() + home::HEADER + home::GAP } else { content.top() + 44.2 };
    let body = Rect::from_min_max(Pos2::new(content.left(), top), content.max);
    confirm_dialog(app, ui);
    if app.page != app.shown_page {
        app.shown_page = app.page;
        app.page_since = std::time::Instant::now();
        app.page_seq += 1;
        app.scroll = 0.0;
        if !app.demo {
            match app.page { Page::Dual => crate::dual::opened(app), Page::Earbuds => app.send(Cmd::EarbudReads), Page::Gestures => crate::gestures::opened(app), Page::Hearing => crate::hearing::opened(app), _ => {} }
        }
    }
    if matches!(app.page, Page::Overview | Page::Eq | Page::Dual | Page::Earbuds | Page::Dev | Page::Gestures | Page::Hearing | Page::Settings | Page::Models | Page::Appearance | Page::Issues | Page::About | Page::Language | Page::Setup | Page::Layout | Page::PresetEdit | Page::Test | Page::Feedback | Page::Update) {
        // A page scrolls when it is taller than the window: a child ui clipped to the body.
        // Page motion (DESIGN §5): a child page rises and fades in; Home comes back with a small scale.
        let key = egui::Id::NULL.with(("page", app.page_seq));
        let m = if app.page == Page::Overview { motion::page_return(ui.ctx(), key) } else { motion::page_enter(ui.ctx(), key, full.height()) };
        let mut page = ui.new_child(egui::UiBuilder::new().max_rect(body));
        page.set_clip_rect(body.intersect(ui.clip_rect()));
        page.set_opacity(m.alpha);
        if page.rect_contains_pointer(body) {
            let dy = page.input(|i| i.smooth_scroll_delta.y);
            if dy != 0.0 { app.scroll = (app.scroll - dy).max(0.0); }
        }
        let shifted = Rect::from_min_max(Pos2::new(body.left(), body.top() - app.scroll + m.offset), body.max);
        let bottom = match app.page {
            Page::Eq => crate::eq::show(app, &mut page, shifted),
            Page::Dual => crate::dual::show(app, &mut page, shifted),
            Page::Earbuds => crate::earbuds::show(app, &mut page, shifted),
            Page::Dev => crate::dev::show(app, &mut page, shifted),
            Page::Gestures => crate::gestures::show(app, &mut page, shifted),
            Page::Hearing => crate::hearing::show(app, &mut page, shifted),
            Page::Models => crate::models::show(app, &mut page, shifted),
            Page::Settings => crate::settings::show(app, &mut page, shifted),
            Page::Appearance => crate::appearance::show(app, &mut page, shifted),
            Page::Issues => crate::issues::show(app, &mut page, shifted),
            Page::About => crate::about::show(app, &mut page, shifted),
            Page::Language => crate::language::show(app, &mut page, shifted),
            Page::Setup => crate::setup::show(app, &mut page, shifted),
            Page::Layout => crate::layout::show(app, &mut page, shifted),
            Page::PresetEdit => crate::preset_edit::show(app, &mut page, shifted),
            Page::Test => crate::test::show(app, &mut page, shifted),
            Page::Feedback => crate::feedback::show(app, &mut page, shifted),
            Page::Update => crate::update::show(app, &mut page, shifted),
            _ => home::show(app, &mut page, shifted),
        };
        // A page that shrinks (the EQ editor closes) eases the scroll back instead of snapping.
        let max = (bottom + app.scroll - body.bottom() + 12.0).max(0.0);
        if app.scroll > max {
            let dt = ui.input(|i| i.stable_dt).min(0.05);
            app.scroll += (max - app.scroll) * (1.0 - (-14.0 * dt).exp());
            if app.scroll - max < 0.5 { app.scroll = max; } else { ui.ctx().request_repaint(); }
        }
    } else {
        placeholder(ui, body);
    }
    // A refused write, or an ANC write with no bud in an ear, shows a short toast over the page.
    let now = std::time::Instant::now();
    if let Some(text) = app.toast.text(&app.tr, &app.snap, now) {
        crate::notice::draw(ui, &text, content);
        if let Some(d) = app.toast.repaint_after(now) { ui.ctx().request_repaint_after(d); }
    }
}

fn header(app: &mut App, ui: &mut Ui, c: Rect) {
    // The window has no title bar: the header moves it.
    let strip = Rect::from_min_max(Pos2::new(c.left(), c.top() - 16.0), Pos2::new(c.right(), c.top() + 44.0));
    let drag = ui.interact(strip, ui.scope_id().with("drag-header"), egui::Sense::click_and_drag());
    if drag.drag_started_by(egui::PointerButton::Primary) { ui.ctx().send_viewport_cmd(egui::ViewportCommand::StartDrag); }
    // The setup guide has its own bar: no back arrow and no title above it.
    if app.page == Page::Setup { return; }
    // Home layout keeps the phone's header: Cancel, the title, Apply.
    if app.page == Page::Layout {
        let accent = crate::palette::active(crate::palette::is_light_system(ui.ctx()), app.style == glass::style::Style::Material).accent;
        let cancel = Rect::from_min_size(c.min, Vec2::new(72.0, 44.0));
        if glass::button_at(ui, "layout-cancel", cancel, 22.0, false).clicked() { crate::layout::cancel(app); return; }
        glass::text(ui, cancel.center(), Align2::CENTER_CENTER, t(&app.tr, "layout_cancel"), 15.0, 1.0);
        let w = 108.0;
        let apply = Rect::from_min_size(Pos2::new(c.right() - w, c.top()), Vec2::new(w, 44.0));
        if glass::button_at(ui, "layout-apply", apply, 22.0, true).clicked() { crate::layout::apply(app); return; }
        ui.painter().rect_filled(apply.shrink(0.5), 22.0, accent);
        ui.painter().text(apply.center(), Align2::CENTER_CENTER, t(&app.tr, "layout_done"), FontId::proportional(15.0), glass::on_accent());
        let clip = Rect::from_min_max(Pos2::new(cancel.right() + 8.0, c.top()), Pos2::new(apply.left() - 8.0, c.top() + 44.0));
        let mut size = 19.0;
        while size > 15.0 && ui.painter().layout_no_wrap(t(&app.tr, "layout_title").to_string(), FontId::proportional(size), white(1.0)).size().x > clip.width() { size -= 1.0; }
        ui.painter().with_clip_rect(clip).text(clip.center(), Align2::CENTER_CENTER, t(&app.tr, "layout_title"), FontId::proportional(size), white(1.0));
        return;
    }
    // Home has the phone's header: model button, connection pill, an empty title, dev tools, settings.
    if app.page == Page::Overview {
        home::header(app, ui, c);
        return;
    }
    let left = c.left() + 56.0;
    let mut size = 28.0;
    let r = Rect::from_min_size(Pos2::new(c.left(), c.top()), Vec2::splat(44.0));
    if glass::button_at(ui, "back", r, 22.0, false).clicked() { app.page = if app.page == Page::Models { Page::Earbuds } else { Page::Overview }; }
    let m = r.center() + Vec2::new(1.5, 0.0);
    let st = egui::Stroke::new(2.4, white(1.0));
    ui.painter().line_segment([m + Vec2::new(-3.0, -8.0), m + Vec2::new(-11.0, 0.0)], st);
    ui.painter().line_segment([m + Vec2::new(-11.0, 0.0), m + Vec2::new(-3.0, 8.0)], st);
    ui.painter().line_segment([m + Vec2::new(-11.0, 0.0), m + Vec2::new(8.0, 0.0)], st);
    let title = t(&app.tr, match app.page { Page::Eq => "eq_title", Page::Gestures => "gesture_title", Page::Hearing => "row_golden_title", Page::Dual => "dual_title",
        Page::Earbuds | Page::Models => "earbuds_title", Page::Settings => "settings_title", Page::Dev => "action_dev_tools",
        Page::Appearance => "theme_title", Page::Issues => "settings_issues_title", Page::About => "settings_about_title", Page::Language => "settings_language_title",
        Page::Test => "test_title", Page::Feedback => "problem_title", Page::Update => "update_title",
        Page::PresetEdit => "preset_title", Page::Layout => "layout_title",
        Page::Overview | Page::Setup => "earbuds_title" });
    // The phone has no close button in a page header; the window closes with the desktop's own shortcut.
    let right = c.right() - 12.0;
    // The title stops at the right-hand controls instead of running under them; it shrinks to fit first.
    let clip = Rect::from_min_max(Pos2::new(left, c.top()), Pos2::new(right - 8.0, c.top() + 44.0));
    while size > 18.0 && ui.painter().layout_no_wrap(title.to_string(), egui::FontId::proportional(size), white(1.0)).size().x > clip.width() { size -= 1.0; }
    ui.painter().with_clip_rect(clip).text(Pos2::new(left, c.top() + 22.0), Align2::LEFT_CENTER, title, egui::FontId::proportional(size), white(1.0));
}

fn placeholder(ui: &mut Ui, body: Rect) {
    let r = Rect::from_min_size(body.min, Vec2::new(body.width(), 120.0));
    glass::flat_at(ui, r, 32.0, 1.0);
    glass::text(ui, r.center(), Align2::CENTER_CENTER, "This page is not ported to egui yet.", 16.0, 0.8);
}

/// The accept dialog before a write that makes the buds reconnect (`ConfirmDialog` on the phone).
pub fn confirm_dialog(app: &mut App, ui: &mut Ui) {
    let Some(c) = app.confirm.take() else { return };
    let mut decision = None;
    let tr = &app.tr;
    let open = glass::dialog(ui.ctx(), "confirm", t(tr, c.title), Vec2::new(440.0, 230.0), |ui| {
        ui.add(egui::Label::new(egui::RichText::new(t(tr, c.msg)).size(14.0).color(white(0.85))).wrap());
        decision = glass::dialog_buttons(ui, t(tr, "dialog_cancel"), t(tr, c.accept));
    });
    match decision {
        Some(true) => for cmd in c.cmds { app.send(cmd); },
        Some(false) => {}
        None if open => app.confirm = Some(c),
        None => {}
    }
}
